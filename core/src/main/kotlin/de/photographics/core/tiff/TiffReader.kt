package de.photographics.core.tiff

import de.photographics.core.tr
import de.photographics.core.color.IccProfile
import de.photographics.core.color.IccProfiles
import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.ColorConvert
import de.photographics.core.psd.PsdColorMode
import de.photographics.core.psd.PsdReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

class TiffFormatException(msg: String) : Exception(msg)

/**
 * Liest TIFF-Dateien (auch BigTIFF): Streifen und Kacheln, unkomprimiert, LZW, ZIP/Deflate und
 * PackBits, mit Prädiktor; 1–16 Bit Ganzzahl und 32 Bit Gleitkomma; Graustufen, RGB, indizierte
 * Farben, CMYK und Lab, getrennte oder verschachtelte Kanäle, Transparenz (auch vormultipliziert),
 * eingebettetes ICC-Profil, Auflösung und Ausrichtung.
 *
 * Photoshop-Ebenen (Tag 37724) werden über den PSD-Ebenenleser übernommen; das zusammengefügte
 * Bild der Datei dient sonst als Hintergrundebene. Gelesen wird streifenweise aus dem (ggf.
 * speicherabgebildeten) Puffer – auch ein einzelner riesiger LZW-Streifen wird fortlaufend dekodiert.
 */
object TiffReader {
    private const val T_WIDTH = 256; private const val T_HEIGHT = 257; private const val T_BPS = 258
    private const val T_COMPRESSION = 259; private const val T_PHOTOMETRIC = 262; private const val T_FILL_ORDER = 266
    private const val T_STRIP_OFFSETS = 273; private const val T_ORIENTATION = 274; private const val T_SPP = 277
    private const val T_ROWS_PER_STRIP = 278; private const val T_STRIP_COUNTS = 279; private const val T_XRES = 282
    private const val T_PLANAR = 284; private const val T_RES_UNIT = 296; private const val T_PREDICTOR = 317
    private const val T_COLORMAP = 320; private const val T_TILE_W = 322; private const val T_TILE_H = 323
    private const val T_TILE_OFFSETS = 324; private const val T_TILE_COUNTS = 325; private const val T_INKSET = 332
    private const val T_EXTRA = 338; private const val T_SAMPLE_FORMAT = 339
    internal const val T_ICC = 34675; internal const val T_PHOTOSHOP_LAYERS = 37724

    fun isTiff(b: ByteBuffer): Boolean {
        if (b.limit() < 8) return false
        val a = b.get(0).toInt(); val c = b.get(1).toInt()
        val order = if (a == 'I'.code && c == 'I'.code) ByteOrder.LITTLE_ENDIAN else if (a == 'M'.code && c == 'M'.code) ByteOrder.BIG_ENDIAN else return false
        val v = b.duplicate().order(order).getShort(2).toInt()
        return v == 42 || v == 43
    }

    /** Ganze Datei als Dokument; mit [layers] = false nur das zusammengefügte Bild (Referenz für Tests). */
    fun read(buffer: ByteBuffer, workingRgb: IccProfile = IccProfiles.sRGB, layers: Boolean = true): Document {
        val f = TiffFile(buffer)
        val ifd = f.ifd(f.firstIfd)
        val w = ifd.int(T_WIDTH) ?: throw TiffFormatException(tr("Bildbreite fehlt"))
        val h = ifd.int(T_HEIGHT) ?: throw TiffFormatException(tr("Bildhöhe fehlt"))
        if (w <= 0 || h <= 0 || w.toLong() * h > 1_000_000_000L) throw TiffFormatException(tr("Ungültige Bildgröße {0}×{1}", w, h))
        val img = ImageInfo(f, ifd, w, h)

        val doc = Document(w, h)
        ifd.rational(T_XRES)?.let { r ->
            val unit = ifd.int(T_RES_UNIT) ?: 2
            if (r > 0) doc.dpi = (if (unit == 3) r * 2.54 else r).toFloat()
        }
        val header = PsdReader.Header(1, img.spp, w, h, if (img.bps in setOf(16, 32)) img.bps else 8, img.mode)
        val icc = ifd.bytes(T_ICC)
        if (img.mode == PsdColorMode.INDEXED) doc.colorProfile = icc?.let { runCatching { IccProfile(it) }.getOrNull() }?.takeIf { it.isRgb } ?: IccProfiles.sRGB
        else PsdReader.setUpColor(doc, header, icc, workingRgb)
        if (img.bps != 8 && img.mode != PsdColorMode.INDEXED && img.bps > 1) doc.importWarnings += tr("{0}-Bit-Bild wurde in 8 Bit pro Kanal umgewandelt.", img.bps)

        // Photoshop-Ebenen
        var hasLayers = false
        if (layers) ifd.entries[T_PHOTOSHOP_LAYERS]?.let { e ->
            runCatching {
                val slice = f.slice(e.dataPos, e.byteLength.toInt())
                hasLayers = PsdReader.readEmbeddedLayers(doc, header, slice, f.le, img.colorData)
            }.onFailure {
                doc.root.children.toList().forEach { l -> doc.root.remove(l) }
                doc.importWarnings += tr("Photoshop-Ebenen konnten nicht gelesen werden ({0}) – das zusammengefügte Bild wird verwendet.", it.message)
            }
        }
        if (!hasLayers) {
            val bg = PixelLayer("Hintergrund", readComposite(img, header))
            doc.root.add(bg)
            doc.activeLayer = bg
        }
        if (f.pageCount() > 1) doc.importWarnings += tr("Mehrseitiges TIFF: nur die erste Seite wurde geöffnet.")
        applyOrientation(doc, ifd.int(T_ORIENTATION) ?: 1)
        return doc
    }

    // ---------------------------------------------------------------- Bilddaten

    /** Eigenschaften des Bildes und Umrechnung der Abtastwerte in die Kanalebenen des PSD-Farbmodus. */
    private class ImageInfo(val f: TiffFile, val ifd: Ifd, val w: Int, val h: Int) {
        val spp = ifd.int(T_SPP) ?: 1
        val bpsAll = ifd.ints(T_BPS) ?: longArrayOf(1)
        val bps = bpsAll[0].toInt()
        val compression = ifd.int(T_COMPRESSION) ?: 1
        val photometric = ifd.int(T_PHOTOMETRIC) ?: if (spp >= 3) 2 else 1
        val planar = (ifd.int(T_PLANAR) ?: 1) == 2
        val predictor = ifd.int(T_PREDICTOR) ?: 1
        val sampleFormat = ifd.int(T_SAMPLE_FORMAT) ?: 1
        val fillOrderLsb = (ifd.int(T_FILL_ORDER) ?: 1) == 2
        val extra = ifd.ints(T_EXTRA) ?: LongArray(0)
        val base: Int
        val mode: Int
        var colorData = ByteArray(0)
        /** Index des Transparenzkanals unter den Abtastwerten, sonst −1; vormultipliziert? */
        val alphaIndex: Int
        val premultiplied: Boolean

        init {
            if (bpsAll.any { it.toInt() != bps }) throw TiffFormatException(tr("Unterschiedliche Bittiefen je Kanal werden nicht unterstützt"))
            if (bps !in setOf(1, 2, 4, 8, 16, 32)) throw TiffFormatException(tr("Nicht unterstützte Bittiefe {0}", bps))
            if (sampleFormat == 3 && bps != 32) throw TiffFormatException(tr("Gleitkomma mit {0} Bit wird nicht unterstützt", bps))
            when (compression) {
                1, 5, 8, 32946, 32773 -> {}
                6, 7 -> throw TiffFormatException(tr("JPEG-komprimierte TIFFs werden noch nicht unterstützt – bitte als LZW oder ZIP speichern"))
                2, 3, 4 -> throw TiffFormatException(tr("Fax-komprimierte (CCITT) TIFFs werden nicht unterstützt"))
                else -> throw TiffFormatException(tr("Nicht unterstützte TIFF-Kompression {0}", compression))
            }
            when (photometric) {
                0, 1 -> { base = 1; mode = PsdColorMode.GRAYSCALE }
                2 -> { base = 3; mode = PsdColorMode.RGB }
                3 -> {
                    if (bps > 8) throw TiffFormatException(tr("Palette mit {0} Bit wird nicht unterstützt", bps))
                    base = 1; mode = PsdColorMode.INDEXED
                    val map = ifd.ints(T_COLORMAP) ?: throw TiffFormatException(tr("Farbpalette fehlt"))
                    val n = map.size / 3
                    colorData = ByteArray(768).also { cd ->
                        for (i in 0 until minOf(n, 256)) {
                            cd[i] = (map[i] shr 8).toByte(); cd[256 + i] = (map[n + i] shr 8).toByte(); cd[512 + i] = (map[2 * n + i] shr 8).toByte()
                        }
                    }
                }
                5 -> {
                    if ((ifd.int(T_INKSET) ?: 1) != 1 || spp - extra.size < 4) throw TiffFormatException(tr("Nur CMYK-Separationen werden unterstützt"))
                    base = 4; mode = PsdColorMode.CMYK
                }
                8, 9 -> { base = 3; mode = PsdColorMode.LAB }
                6 -> throw TiffFormatException(tr("YCbCr-TIFFs werden noch nicht unterstützt"))
                else -> throw TiffFormatException(tr("Nicht unterstützter Farbraum (Photometric {0})", photometric))
            }
            if (spp < base) throw TiffFormatException(tr("Zu wenige Kanäle ({0}) für den Farbraum", spp))
            val ai = extra.indexOfFirst { it == 1L || it == 2L }
            alphaIndex = if (ai >= 0 && base + ai < spp) base + ai else -1
            premultiplied = ai >= 0 && extra[ai] == 1L
        }

        /** Rohwert → 0..255 für Kanal [c] (bereits im Wertebereich des PSD-Modus). */
        fun to8(c: Int, v: Long, isFloat: Boolean): Int {
            if (isFloat) {
                val x = Float.fromBits(v.toInt())
                val isColor = c < base && mode != PsdColorMode.LAB
                return if (isColor) ColorConvert.linearToSrgb(x) else (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            }
            if (mode == PsdColorMode.INDEXED && c == 0) return v.toInt() and 0xFF
            if (photometric == 8 && (c == 1 || c == 2)) {
                // CIELab: a/b vorzeichenbehaftet
                return when (bps) { 8 -> v.toInt().toByte() + 128; 16 -> v.toInt().toShort() / 256 + 128; else -> 128 }.coerceIn(0, 255)
            }
            val u = when (bps) {
                8 -> v.toInt()
                16 -> ((v * 255 + 32767) / 65535).toInt()
                32 -> (v ushr 24).toInt()
                else -> (v * 255 / ((1 shl bps) - 1)).toInt()
            }
            return when {
                photometric == 0 && c == 0 -> 255 - u
                mode == PsdColorMode.CMYK && c < 4 -> 255 - u   // PSD speichert CMYK invertiert
                else -> u
            }
        }
    }

    private fun readComposite(img: ImageInfo, header: PsdReader.Header): TileGrid {
        val w = img.w; val h = img.h
        val grid = TileGrid(w, h)
        val source = SegmentRows(img)
        val band = maxOf(1, minOf(h, (1 shl 20) / w))
        val channels = (0 until img.base).toList() + listOfNotNull(img.alphaIndex.takeIf { it >= 0 })
        val isFloat = img.sampleFormat == 3
        var y0 = 0
        while (y0 < h) {
            val y1 = minOf(h, y0 + band)
            val n = (y1 - y0) * w
            val planes = HashMap<Int, ByteArray>()
            for (c in channels) planes[if (c == img.alphaIndex) -1 else c] = ByteArray(n)
            for (y in y0 until y1) {
                for (c in channels) {
                    val row = source.row(y, if (img.planar) c else 0)
                    val dst = planes[if (c == img.alphaIndex) -1 else c]!!
                    val o = (y - y0) * w
                    val sampleIdx = if (img.planar) 0 else c
                    val step = if (img.planar) 1 else img.spp
                    for (x in 0 until w) dst[o + x] = img.to8(c, source.sample(row, x * step + sampleIdx), isFloat).toByte()
                }
            }
            val alpha = planes[-1]
            if (alpha != null && img.premultiplied && (img.mode == PsdColorMode.RGB || img.mode == PsdColorMode.GRAYSCALE)) {
                for (c in 0 until img.base) {
                    val p = planes[c]!!
                    for (i in 0 until n) {
                        val a = alpha[i].toInt() and 0xFF
                        if (a in 1..254) p[i] = minOf(255, ((p[i].toInt() and 0xFF) * 255 + a / 2) / a).toByte()
                    }
                }
            }
            grid.writeRect(IntRect(0, y0, w, y1), PsdReader.planesToArgb(planes, header, 0, n, img.colorData, if (alpha != null) -1 else null))
            y0 = y1
        }
        grid.compact()
        return grid
    }

    /**
     * Liefert dekomprimierte, entprädizierte Zeilen in aufsteigender Reihenfolge – aus Streifen
     * (fortlaufend dekodiert) oder Kacheln (eine Kachelreihe zwischengespeichert).
     */
    private class SegmentRows(private val img: ImageInfo) {
        private val f = img.f
        private val samplesPerPlaneRow = if (img.planar) img.w else img.w * img.spp
        private val tiled = img.ifd.entries.containsKey(T_TILE_OFFSETS)
        private val tileW = img.ifd.int(T_TILE_W) ?: img.w
        private val tileH = img.ifd.int(T_TILE_H) ?: img.h
        private val offsets = img.ifd.ints(if (tiled) T_TILE_OFFSETS else T_STRIP_OFFSETS) ?: throw TiffFormatException(tr("Bilddaten fehlen"))
        private val counts = img.ifd.ints(if (tiled) T_TILE_COUNTS else T_STRIP_COUNTS)
        private val rowsPerStrip = (img.ifd.long(T_ROWS_PER_STRIP) ?: img.h.toLong()).coerceIn(1, img.h.toLong()).toInt()
        private val planes = if (img.planar) img.spp else 1
        private val rowBytes = (samplesPerPlaneRow.toLong() * img.bps + 7) / 8
        /** Bei Gleitkomma-Prädiktor liegen die Werte danach immer als Big-Endian vor. */
        private var floatPredicted = img.predictor == 3

        // Streifen: aktueller Dekoder je Ebene
        private val stripSource = arrayOfNulls<ByteSource>(planes)
        private val stripIndex = IntArray(planes) { -1 }
        private val rowBuf = Array(planes) { ByteArray(rowBytes.toInt()) }
        private val rowOf = IntArray(planes) { -1 }
        // Kacheln: zwischengespeicherte Kachelreihe je Ebene
        private val tileRow = IntArray(planes) { -1 }
        private val tileBuf = arrayOfNulls<ByteArray>(planes)

        private val stripsPerPlane = (img.h + rowsPerStrip - 1) / rowsPerStrip
        private val tilesAcross = (img.w + tileW - 1) / tileW
        private val tilesDown = (img.h + tileH - 1) / tileH

        fun row(y: Int, plane: Int): ByteArray {
            if (rowOf[plane] == y) return rowBuf[plane]
            if (tiled) readTiledRow(y, plane) else readStripRow(y, plane)
            rowOf[plane] = y
            return rowBuf[plane]
        }

        private fun segment(i: Int): ByteSource {
            if (i >= offsets.size) return ZeroSource
            val off = offsets[i]
            val len = counts?.getOrNull(i) ?: (f.size - off)
            if (off <= 0 || off >= f.size) return ZeroSource
            return f.source(off.toInt(), minOf(len, f.size - off).toInt(), img.compression)
        }

        private fun readStripRow(y: Int, plane: Int) {
            val s = y / rowsPerStrip
            if (stripIndex[plane] != s) {
                stripSource[plane] = segment(plane * stripsPerPlane + s); stripIndex[plane] = s
                // Zeilen bis y im neuen Streifen überspringen (nur bei Sprüngen, normalerweise y = Streifenanfang)
                repeat(y - s * rowsPerStrip) { readFully(stripSource[plane]!!, rowBuf[plane]) }
            }
            val dst = rowBuf[plane]
            readFully(stripSource[plane]!!, dst)
            unpredict(dst, if (img.planar) 1 else img.spp, samplesPerPlaneRow)
        }

        private fun readTiledRow(y: Int, plane: Int) {
            val tr = y / tileH
            val tileRowBytes = ((tileW.toLong() * (if (img.planar) 1 else img.spp) * img.bps + 7) / 8).toInt()
            if (tileRow[plane] != tr) {
                val buf = tileBuf[plane] ?: ByteArray(tileRowBytes * tilesAcross * tileH).also { tileBuf[plane] = it }
                val tileRow = ByteArray(tileRowBytes)
                for (tx in 0 until tilesAcross) {
                    val src = segment(plane * tilesAcross * tilesDown + tr * tilesAcross + tx)
                    for (ty in 0 until tileH) {
                        readFully(src, tileRow)
                        unpredict(tileRow, if (img.planar) 1 else img.spp, tileW * (if (img.planar) 1 else img.spp))
                        System.arraycopy(tileRow, 0, buf, (ty * tilesAcross + tx) * tileRowBytes, tileRowBytes)
                    }
                }
                this.tileRow[plane] = tr
            }
            // Zeile aus den Kacheln zusammensetzen (Kachelbreite ist ein Vielfaches von 16 → byteweise ausgerichtet)
            val buf = tileBuf[plane]!!
            val dst = rowBuf[plane]
            val ty = y - tr * tileH
            var o = 0
            for (tx in 0 until tilesAcross) {
                val n = minOf(tileRowBytes, dst.size - o)
                if (n <= 0) break
                System.arraycopy(buf, (ty * tilesAcross + tx) * tileRowBytes, dst, o, n)
                o += n
            }
        }

        private fun readFully(src: ByteSource, dst: ByteArray) {
            var o = 0
            while (o < dst.size) {
                val n = src.read(dst, o, dst.size - o)
                if (n <= 0) { dst.fill(0, o, dst.size); break }
                o += n
            }
            if (img.fillOrderLsb && img.bps < 8) for (i in dst.indices) dst[i] = REVERSE[dst[i].toInt() and 0xFF]
        }

        /** Horizontaler Prädiktor (2) und Gleitkomma-Prädiktor (3) zeilenweise aufheben. */
        private fun unpredict(row: ByteArray, stride: Int, samples: Int) {
            when (img.predictor) {
                2 -> when (img.bps) {
                    8 -> for (i in stride until samples) row[i] = (row[i] + row[i - stride]).toByte()
                    16 -> for (i in stride until samples) put16(row, i, get16(row, i) + get16(row, i - stride))
                    32 -> for (i in stride until samples) put32(row, i, get32(row, i) + get32(row, i - stride))
                }
                3 -> {
                    val bytes = img.bps / 8
                    val n = samples * bytes
                    for (i in stride until n) row[i] = (row[i] + row[i - stride]).toByte()
                    val tmp = row.copyOf(n)
                    for (c in 0 until samples) for (b in 0 until bytes) row[c * bytes + b] = tmp[b * samples + c]
                }
            }
        }

        private fun get16(r: ByteArray, i: Int) = if (f.le) (r[2 * i].toInt() and 0xFF) or ((r[2 * i + 1].toInt() and 0xFF) shl 8)
            else ((r[2 * i].toInt() and 0xFF) shl 8) or (r[2 * i + 1].toInt() and 0xFF)
        private fun put16(r: ByteArray, i: Int, v: Int) {
            if (f.le) { r[2 * i] = v.toByte(); r[2 * i + 1] = (v shr 8).toByte() } else { r[2 * i] = (v shr 8).toByte(); r[2 * i + 1] = v.toByte() }
        }
        private fun get32(r: ByteArray, i: Int): Int {
            val b = 4 * i
            return if (f.le) (r[b].toInt() and 0xFF) or ((r[b + 1].toInt() and 0xFF) shl 8) or ((r[b + 2].toInt() and 0xFF) shl 16) or (r[b + 3].toInt() shl 24)
            else (r[b].toInt() shl 24) or ((r[b + 1].toInt() and 0xFF) shl 16) or ((r[b + 2].toInt() and 0xFF) shl 8) or (r[b + 3].toInt() and 0xFF)
        }
        private fun put32(r: ByteArray, i: Int, v: Int) {
            val b = 4 * i
            if (f.le) { r[b] = v.toByte(); r[b + 1] = (v shr 8).toByte(); r[b + 2] = (v shr 16).toByte(); r[b + 3] = (v shr 24).toByte() }
            else { r[b] = (v shr 24).toByte(); r[b + 1] = (v shr 16).toByte(); r[b + 2] = (v shr 8).toByte(); r[b + 3] = v.toByte() }
        }

        /** Abtastwert Nr. [i] einer Zeile als vorzeichenlose Zahl (Gleitkomma: Bitmuster). */
        fun sample(row: ByteArray, i: Int): Long = when (img.bps) {
            8 -> (row[i].toInt() and 0xFF).toLong()
            16 -> get16(row, i).toLong()
            32 -> if (floatPredicted) (((row[4 * i].toInt() shl 24) or ((row[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((row[4 * i + 2].toInt() and 0xFF) shl 8) or (row[4 * i + 3].toInt() and 0xFF)).toLong() and 0xFFFFFFFFL)
                else get32(row, i).toLong() and 0xFFFFFFFFL
            else -> {
                val bit = i.toLong() * img.bps
                val byte = row[(bit / 8).toInt()].toInt() and 0xFF
                ((byte shr (8 - img.bps - (bit % 8).toInt())) and ((1 shl img.bps) - 1)).toLong()
            }
        }
    }

    private val REVERSE = ByteArray(256) { i -> Integer.reverse(i).ushr(24).toByte() }

    private fun applyOrientation(doc: Document, o: Int) {
        if (o == 1 || o !in 2..8) return
        val ed = Editor(doc)
        when (o) {
            2 -> ed.flip(true)
            3 -> { ed.rotate(true); ed.rotate(true) }
            4 -> ed.flip(false)
            5 -> { ed.rotate(true); ed.flip(true) }
            6 -> ed.rotate(true)
            7 -> { ed.rotate(false); ed.flip(true) }
            8 -> ed.rotate(false)
        }
        ed.history.clear()
    }

    // ---------------------------------------------------------------- Dateistruktur

    internal class Entry(val tag: Int, val type: Int, val count: Long, val dataPos: Long) {
        val byteLength get() = count * typeSize(type)
    }

    internal class Ifd(val f: TiffFile, val entries: Map<Int, Entry>, val next: Long) {
        fun ints(tag: Int): LongArray? = entries[tag]?.let { f.values(it) }
        fun long(tag: Int): Long? = ints(tag)?.firstOrNull()
        fun int(tag: Int): Int? = long(tag)?.toInt()
        fun rational(tag: Int): Double? = entries[tag]?.takeIf { it.type == 5 }?.let {
            val n = f.u32(it.dataPos); val d = f.u32(it.dataPos + 4); if (d == 0L) null else n.toDouble() / d
        }
        fun bytes(tag: Int): ByteArray? = entries[tag]?.let { e ->
            val n = e.byteLength.toInt()
            if (e.dataPos < 0 || e.dataPos + n > f.size) null else ByteArray(n).also { f.buf.duplicate().apply { position(e.dataPos.toInt()) }.get(it) }
        }
    }

    internal class TiffFile(buffer: ByteBuffer) {
        val le = buffer.get(0).toInt() == 'I'.code
        val buf: ByteBuffer = buffer.duplicate().order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val size = buf.limit().toLong()
        val big = u16(2) == 43
        val firstIfd = if (big) u64(8) else u32(4)

        fun u16(p: Long) = buf.getShort(p.toInt()).toInt() and 0xFFFF
        fun u32(p: Long) = buf.getInt(p.toInt()).toLong() and 0xFFFFFFFFL
        fun u64(p: Long) = buf.getLong(p.toInt())

        fun ifd(pos: Long): Ifd {
            if (pos < 8 || pos + 2 > size) throw TiffFormatException(tr("Ungültiger IFD-Offset {0}", pos))
            val n = if (big) u64(pos) else u16(pos).toLong()
            val entrySize = if (big) 20 else 12
            val first = pos + if (big) 8 else 2
            if (n < 0 || first + n * entrySize > size) throw TiffFormatException(tr("Beschädigtes IFD"))
            val map = HashMap<Int, Entry>()
            for (i in 0 until n) {
                val p = first + i * entrySize
                val tag = u16(p); val type = u16(p + 2)
                val count = if (big) u64(p + 4) else u32(p + 4)
                val valuePos = p + if (big) 12 else 8
                val inline = if (big) 8 else 4
                val len = count * typeSize(type)
                val dataPos = if (len <= inline) valuePos else if (big) u64(valuePos) else u32(valuePos)
                map[tag] = Entry(tag, type, count, dataPos)
            }
            val nextPos = first + n * entrySize
            val next = if (nextPos + (if (big) 8 else 4) <= size) (if (big) u64(nextPos) else u32(nextPos)) else 0L
            return Ifd(this, map, next)
        }

        fun pageCount(): Int {
            var n = 0; var p = firstIfd
            val seen = HashSet<Long>()
            while (p in 8 until size && seen.add(p) && n < 1000) { n++; p = runCatching { ifd(p).next }.getOrDefault(0L) }
            return n
        }

        fun values(e: Entry): LongArray {
            val sz = typeSize(e.type)
            if (e.count > 50_000_000 || e.dataPos < 0 || e.dataPos + e.count * sz > size) throw TiffFormatException(tr("Beschädigter Eintrag {0}", e.tag))
            return LongArray(e.count.toInt()) { i ->
                val p = e.dataPos + i * sz
                when (e.type) {
                    1, 7 -> (buf.get(p.toInt()).toLong() and 0xFF)
                    6 -> buf.get(p.toInt()).toLong()
                    3 -> u16(p).toLong()
                    8 -> buf.getShort(p.toInt()).toLong()
                    4, 13 -> u32(p)
                    9 -> buf.getInt(p.toInt()).toLong()
                    16, 17, 18 -> u64(p)
                    else -> u32(p)
                }
            }
        }

        fun slice(pos: Long, len: Int): ByteBuffer {
            if (pos < 0 || pos + len > size) throw TiffFormatException(tr("Ebenendaten außerhalb der Datei"))
            return buf.duplicate().order(ByteOrder.BIG_ENDIAN).apply { position(pos.toInt()); limit(pos.toInt() + len) }.slice()
        }

        fun source(off: Int, len: Int, compression: Int): ByteSource = when (compression) {
            1 -> RawSource(buf, off, len)
            5 -> LzwSource(buf, off, len)
            8, 32946 -> InflateSource(buf, off, len)
            32773 -> PackBitsSource(buf, off, len)
            else -> throw TiffFormatException("Kompression $compression")
        }
    }

    internal fun typeSize(type: Int) = when (type) {
        1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11, 13 -> 4; 5, 10, 12, 16, 17, 18 -> 8; else -> 1
    }

    // ---------------------------------------------------------------- Dekoder (fortlaufend)

    internal interface ByteSource { fun read(dst: ByteArray, off: Int, len: Int): Int }

    private object ZeroSource : ByteSource {
        override fun read(dst: ByteArray, off: Int, len: Int): Int { dst.fill(0, off, off + len); return len }
    }

    private class RawSource(private val b: ByteBuffer, private var pos: Int, len: Int) : ByteSource {
        private val end = pos + len
        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            val n = minOf(len, end - pos)
            if (n <= 0) return -1
            b.duplicate().apply { position(pos) }.get(dst, off, n); pos += n
            return n
        }
    }

    private class PackBitsSource(private val b: ByteBuffer, private var pos: Int, len: Int) : ByteSource {
        private val end = pos + len
        private var literal = 0; private var run = 0; private var runValue: Byte = 0
        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            var n = 0
            while (n < len) {
                when {
                    run > 0 -> { dst[off + n++] = runValue; run-- }
                    literal > 0 -> { if (pos >= end) break; dst[off + n++] = b.get(pos++); literal-- }
                    pos >= end -> break
                    else -> {
                        val hdr = b.get(pos++).toInt()
                        if (hdr >= 0) literal = hdr + 1
                        else if (hdr != -128 && pos < end) { run = 1 - hdr; runValue = b.get(pos++) }
                    }
                }
            }
            return if (n == 0) -1 else n
        }
    }

    private class InflateSource(private val b: ByteBuffer, private var pos: Int, len: Int) : ByteSource {
        private val end = pos + len
        private val inf = Inflater()
        private val chunk = ByteArray(1 shl 16)
        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            var n = 0
            try {
                while (n < len && !inf.finished()) {
                    if (inf.needsInput()) {
                        if (pos >= end) break
                        val k = minOf(chunk.size, end - pos)
                        b.duplicate().apply { position(pos) }.get(chunk, 0, k); pos += k
                        inf.setInput(chunk, 0, k)
                    }
                    val r = inf.inflate(dst, off + n, len - n)
                    if (r == 0 && (inf.needsDictionary() || (inf.needsInput() && pos >= end))) break
                    n += r
                }
            } catch (e: java.util.zip.DataFormatException) {
                throw TiffFormatException(tr("ZIP-Daten beschädigt"))
            }
            if (inf.finished() || n == 0) inf.end()
            return if (n == 0) -1 else n
        }
    }

    /** TIFF-LZW (MSB zuerst, „early change“) und der alte LSB-Dialekt älterer Programme. */
    private class LzwSource(private val b: ByteBuffer, private val start: Int, len: Int) : ByteSource {
        private val endBit = len.toLong() * 8
        private var bit = 0L
        private val oldStyle = len >= 2 && b.get(start).toInt() == 0 && (b.get(start + 1).toInt() and 1) == 1
        private val prefix = IntArray(4096); private val suffix = ByteArray(4096)
        private val length = IntArray(4096); private val first = ByteArray(4096)
        private var next = 258; private var width = 9; private var prev = -1; private var done = false
        private val pend = ByteArray(4096); private var pendPos = 0; private var pendEnd = 0

        init { for (i in 0 until 256) { suffix[i] = i.toByte(); first[i] = i.toByte(); length[i] = 1 } }

        private fun byteAt(i: Long): Int = if (i < endBit / 8) b.get(start + i.toInt()).toInt() and 0xFF else 0

        private fun code(): Int {
            if (bit + width > endBit) return 257
            val i = bit / 8; val s = (bit % 8).toInt()
            val v = if (oldStyle) (byteAt(i) or (byteAt(i + 1) shl 8) or (byteAt(i + 2) shl 16)) ushr s
                else ((byteAt(i) shl 16) or (byteAt(i + 1) shl 8) or byteAt(i + 2)) ushr (24 - s - width)
            bit += width
            return v and ((1 shl width) - 1)
        }

        private fun emit(c: Int) {
            val n = length[c]
            var k = c
            for (i in n - 1 downTo 0) { pend[i] = suffix[k]; k = prefix[k] }
            pendPos = 0; pendEnd = n
        }

        private fun add(p: Int, ch: Byte) {
            if (next >= 4096) return
            prefix[next] = p; suffix[next] = ch; length[next] = length[p] + 1; first[next] = first[p]; next++
            val limit = if (oldStyle) 1 shl width else (1 shl width) - 1
            if (next >= limit && width < 12) width++
        }

        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            var n = 0
            while (n < len) {
                if (pendPos < pendEnd) {
                    val k = minOf(len - n, pendEnd - pendPos)
                    System.arraycopy(pend, pendPos, dst, off + n, k); pendPos += k; n += k
                    continue
                }
                if (done) break
                val c = code()
                when {
                    c == 257 -> done = true
                    c == 256 -> { next = 258; width = 9; prev = -1 }
                    prev == -1 -> { if (c > 255) { done = true } else { emit(c); prev = c } }
                    c < next -> { emit(c); add(prev, first[c]); prev = c }
                    c == next -> { add(prev, first[prev]); emit(c); prev = c }
                    else -> done = true // beschädigt
                }
            }
            return if (n == 0) -1 else n
        }
    }
}
