package de.photographics.core.xcf

import de.photographics.core.tr
import de.photographics.core.color.IccProfile
import de.photographics.core.color.IccProfiles
import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Guide
import de.photographics.core.doc.Layer
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.BinaryReader
import de.photographics.core.render.BlendMode
import java.nio.ByteBuffer
import java.util.zip.Inflater
import kotlin.math.pow

class XcfFormatException(msg: String) : Exception(msg)

/**
 * Liest GIMP-Dateien (XCF, alle Versionen bis GIMP 3): Ebenen mit Versatz (auch über die
 * Arbeitsfläche hinaus), Gruppen, Masken, Deckkraft, Sichtbarkeit, Mischmodi, Hilfslinien,
 * Auflösung und eingebettetes Farbprofil. Genauigkeit 8/16/32 Bit Ganzzahl und 16/32/64 Bit
 * Gleitkomma, linear oder wahrnehmungsgleich, wird in 8 Bit umgerechnet. Kachelweise aus dem
 * (ggf. speicherabgebildeten) Puffer; unkomprimiert, RLE und zlib.
 */
object XcfReader {
    private const val PROP_END = 0; private const val PROP_COLORMAP = 1; private const val PROP_ACTIVE_LAYER = 2
    private const val PROP_OPACITY = 6; private const val PROP_MODE = 7; private const val PROP_VISIBLE = 8
    private const val PROP_LOCK_ALPHA = 10; private const val PROP_APPLY_MASK = 11; private const val PROP_OFFSETS = 15
    private const val PROP_COMPRESSION = 17; private const val PROP_GUIDES = 18; private const val PROP_RESOLUTION = 19
    private const val PROP_PARASITES = 21; private const val PROP_TEXT_LAYER_FLAGS = 26; private const val PROP_GROUP_ITEM = 29
    private const val PROP_ITEM_PATH = 30; private const val PROP_FLOAT_OPACITY = 33
    internal const val TILE = 64

    fun isXcf(b: ByteBuffer): Boolean {
        if (b.limit() < 14) return false
        val magic = "gimp xcf "
        for (i in magic.indices) if (b.get(i).toInt() != magic[i].code) return false
        return true
    }

    /** Genauigkeit: Bytes je Komponente, Zahlenart, linear? */
    private class Precision(val bytes: Int, val kind: Kind, val linear: Boolean) {
        enum class Kind { INT, HALF, FLOAT, DOUBLE }
    }

    private fun precisionOf(version: Int, p: Int): Precision = if (version < 7) when (p) {
        0 -> Precision(1, Precision.Kind.INT, false); 1 -> Precision(2, Precision.Kind.INT, false)
        2 -> Precision(4, Precision.Kind.INT, true); 3 -> Precision(2, Precision.Kind.HALF, true)
        4 -> Precision(4, Precision.Kind.FLOAT, true)
        else -> throw XcfFormatException(tr("Unbekannte Genauigkeit {0}", p))
    } else {
        val linear = p % 100 == 0
        when (p / 100) {
            1 -> Precision(1, Precision.Kind.INT, linear); 2 -> Precision(2, Precision.Kind.INT, linear)
            3 -> Precision(4, Precision.Kind.INT, linear); 5 -> Precision(2, Precision.Kind.HALF, linear)
            6 -> Precision(4, Precision.Kind.FLOAT, linear); 7 -> Precision(8, Precision.Kind.DOUBLE, linear)
            else -> throw XcfFormatException(tr("Unbekannte Genauigkeit {0}", p))
        }
    }

    private class LayerInfo(
        val name: String, val width: Int, val height: Int, val type: Int,
        var opacity: Float = 1f, var mode: Int = 0, var visible: Boolean = true, var lockAlpha: Boolean = false,
        var applyMask: Boolean = true, var ox: Int = 0, var oy: Int = 0, var group: Boolean = false,
        var path: IntArray? = null, var text: Boolean = false, var active: Boolean = false,
        var hptr: Long = 0, var mptr: Long = 0,
    )

    fun read(buffer: ByteBuffer, workingRgb: IccProfile = IccProfiles.sRGB): Document {
        if (!isXcf(buffer)) throw XcfFormatException(tr("Keine GIMP-Datei"))
        val r = BinaryReader(buffer)
        r.skip(9)
        val v = r.ascii(4)
        val version = if (v == "file") 0 else v.removePrefix("v").toIntOrNull() ?: throw XcfFormatException(tr("Unbekannte Version {0}", v))
        r.u8()
        val w = r.i32(); val h = r.i32(); val base = r.i32()
        if (w <= 0 || h <= 0 || w.toLong() * h > 1_000_000_000L) throw XcfFormatException(tr("Ungültige Bildgröße {0}×{1}", w, h))
        val prec = precisionOf(version, if (version >= 4) r.i32() else 150)
        val big = version >= 11
        fun ptr() = if (big) r.i64() else r.u32()

        val doc = Document(w, h)
        if (version > 23) doc.importWarnings += tr("Neuere XCF-Version ({0}) als bekannt – es wird versucht, sie zu lesen.", version)
        var compression = 0
        var colormap = ByteArray(0)
        var iccData: ByteArray? = null
        // Bildeigenschaften
        while (true) {
            val type = r.i32(); val size = r.u32().toInt()
            if (type == PROP_END) break
            val start = r.pos
            when (type) {
                PROP_COLORMAP -> { val n = r.i32(); colormap = r.bytes(3 * n); r.seek(start + if (version == 0) 4 + 3 * n else size) ; continue }
                PROP_COMPRESSION -> compression = r.u8()
                PROP_GUIDES -> {
                    val list = ArrayList<Guide>()
                    repeat(size / 5) { val pos = r.i32(); val o = r.i8(); if (o == 1 || o == 2) list += Guide(o == 2, pos.toDouble()) }
                    doc.guides = list
                }
                PROP_RESOLUTION -> { val x = r.f32(); r.f32(); if (x > 0) doc.dpi = x }
                PROP_PARASITES -> {
                    val end = start + size
                    while (r.pos < end) {
                        val name = string(r); r.u32(); val len = r.u32().toInt()
                        if (name == "icc-profile" && len > 0) iccData = r.bytes(len) else r.skip(len.toLong())
                    }
                }
            }
            r.seek(start + size)
        }
        val layerPtrs = ArrayList<Long>()
        while (true) { val p = ptr(); if (p == 0L) break; layerPtrs += p }

        // Farbe: RGB übernimmt das eingebettete Profil; Graustufen werden mit Graustufenprofil umgerechnet
        val icc = iccData?.let { runCatching { IccProfile(it) }.getOrNull() }
        doc.colorProfile = if (base == 0 || base == 2) icc?.takeIf { it.isRgb } ?: IccProfiles.sRGB else workingRgb
        val grayConv = if (base == 1 && icc?.isGray == true) runCatching { de.photographics.core.color.DeviceToRgb.icc(icc, workingRgb) }.getOrNull() else null
        if (prec.bytes != 1 || prec.linear) doc.importWarnings += tr("XCF mit {0} Bit{1} wurde in 8 Bit umgerechnet.", prec.bytes * 8, if (prec.linear) tr(" (linear)") else "")

        // Ebenen in Dateireihenfolge (oben zuerst, Gruppen vor ihren Kindern)
        val infos = layerPtrs.map { readLayerInfo(r, it, big) }
        val layers = ArrayList<Layer>()
        val warnings = linkedSetOf<String>()
        for (info in infos) {
            val layer: Layer = if (info.group) GroupLayer(info.name).also { it.expanded = true } else {
                val grid = TileGrid(w, h, area = IntRect(info.ox, info.oy, info.ox + info.width, info.oy + info.height))
                readPixels(r, info.hptr, big, compression, info, prec, colormap, grayConv, grid)
                grid.compact()
                PixelLayer(info.name, grid)
            }
            layer.opacity = info.opacity.coerceIn(0f, 1f)
            layer.visible = info.visible
            layer.lockTransparency = info.lockAlpha
            layer.blendMode = blendMode(info.mode, info.group, warnings)
            if (info.text) warnings += tr("Textebenen wurden als Pixel übernommen.")
            if (info.mptr != 0L) runCatching { layer.mask = readMask(r, info, big, compression, prec, w, h) }
            if (info.active) doc.activeLayer = layer
            layers += layer
        }
        // Baumstruktur aus den Elementpfaden; Index 0 = oberste Ebene ihrer Gruppe
        val paths = infos.mapIndexed { i, info -> info.path ?: intArrayOf(i) }
        val children = HashMap<String, MutableList<Pair<Int, Int>>>()   // Elternpfad → (Position, Ebenenindex)
        for ((i, path) in paths.withIndex()) children.getOrPut(path.dropLast(1).joinToString(",")) { ArrayList() } += path.last() to i
        fun attach(key: String, group: GroupLayer) {
            for ((_, i) in children[key].orEmpty().sortedByDescending { it.first }) {
                val l = layers[i]
                group.add(l)
                if (l is GroupLayer) attach(paths[i].joinToString(","), l)
            }
        }
        attach("", doc.root)
        if (doc.activeLayer == null) doc.activeLayer = doc.allLayers().lastOrNull { it is PixelLayer }
        if (layers.any { it.blendMode != BlendMode.NORMAL && it.blendMode != BlendMode.PASS_THROUGH } || layers.any { it.opacity < 1f })
            warnings += tr("GIMP mischt Ebenen linear, Photographics wie Photoshop – Mischmodi und Deckkraft können leicht anders wirken.")
        doc.importWarnings += warnings
        return doc
    }

    private fun string(r: BinaryReader): String {
        val n = r.u32().toInt()
        if (n <= 0) return ""
        return String(r.bytes(n), Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun readLayerInfo(r: BinaryReader, at: Long, big: Boolean): LayerInfo {
        r.seek(at.toInt())
        val lw = r.i32(); val lh = r.i32(); val type = r.i32()
        val info = LayerInfo(string(r), lw, lh, type)
        while (true) {
            val t = r.i32(); val size = r.u32().toInt()
            if (t == PROP_END) break
            val start = r.pos
            when (t) {
                PROP_ACTIVE_LAYER -> info.active = true
                PROP_OPACITY -> info.opacity = r.i32() / 255f
                PROP_FLOAT_OPACITY -> info.opacity = r.f32()
                PROP_MODE -> info.mode = r.i32()
                PROP_VISIBLE -> info.visible = r.i32() != 0
                PROP_LOCK_ALPHA -> info.lockAlpha = r.i32() != 0
                PROP_APPLY_MASK -> info.applyMask = r.i32() != 0
                PROP_OFFSETS -> { info.ox = r.i32(); info.oy = r.i32() }
                PROP_GROUP_ITEM -> info.group = true
                PROP_ITEM_PATH -> info.path = IntArray(size / 4) { r.i32() }
                PROP_TEXT_LAYER_FLAGS -> info.text = true
            }
            r.seek(start + size)
        }
        info.hptr = if (big) r.i64() else r.u32()
        info.mptr = if (big) r.i64() else r.u32()
        return info
    }

    /** Pixel einer Ebene (Hierarchie → erste Stufe → Kacheln) in [grid] (Dokumentkoordinaten). */
    private fun readPixels(
        r: BinaryReader, hptr: Long, big: Boolean, compression: Int, info: LayerInfo, prec: Precision,
        colormap: ByteArray, grayConv: de.photographics.core.color.DeviceToRgb?, grid: TileGrid,
    ) {
        if (hptr == 0L) return
        val comps = when (info.type) { 0 -> 3; 1 -> 4; 2 -> 1; 3 -> 2; 4 -> 1; 5 -> 2; else -> throw XcfFormatException("Ebenentyp ${info.type}") }
        val hasAlpha = info.type == 1 || info.type == 3 || info.type == 5
        val tables = Tables(prec)
        val dev = IntArray(4)
        readTiles(r, hptr, big, compression) { tw, th, x0, y0, bytes, bpp ->
            if (bpp != comps * prec.bytes) throw XcfFormatException(tr("Unerwartete Pixelgröße {0}", bpp))
            val px = IntArray(tw * th)
            for (i in 0 until tw * th) {
                val o = i * bpp
                fun c(k: Int) = component(bytes, o + k * prec.bytes, prec)
                val a = if (hasAlpha) tables.alpha(c(comps - 1)) else 255
                px[i] = when (info.type) {
                    0, 1 -> (a shl 24) or (tables.color(c(0)) shl 16) or (tables.color(c(1)) shl 8) or tables.color(c(2))
                    2, 3 -> {
                        val g = tables.color(c(0))
                        if (grayConv != null) { dev[0] = g; (a shl 24) or (grayConv.rgb(dev) and 0xFFFFFF) } else (a shl 24) or (g * 0x010101)
                    }
                    else -> {
                        val idx = bytes[o].toInt() and 0xFF
                        val rgb = if (idx * 3 + 2 < colormap.size) ((colormap[idx * 3].toInt() and 0xFF) shl 16) or
                            ((colormap[idx * 3 + 1].toInt() and 0xFF) shl 8) or (colormap[idx * 3 + 2].toInt() and 0xFF) else 0
                        (a shl 24) or rgb
                    }
                }
            }
            grid.writeRect(IntRect(info.ox + x0, info.oy + y0, info.ox + x0 + tw, info.oy + y0 + th), px)
        }
    }

    private fun readMask(r: BinaryReader, info: LayerInfo, big: Boolean, compression: Int, prec: Precision, w: Int, h: Int): LayerMask {
        r.seek(info.mptr.toInt())
        r.i32(); r.i32(); string(r)
        while (true) { val t = r.i32(); val size = r.u32().toInt(); if (t == PROP_END) break; r.skip(size.toLong()) }
        val hptr = if (big) r.i64() else r.u32()
        val grid = TileGrid(w, h, 255, area = IntRect(info.ox, info.oy, info.ox + info.width, info.oy + info.height))
        val tables = Tables(prec)
        readTiles(r, hptr, big, compression) { tw, th, x0, y0, bytes, bpp ->
            val px = IntArray(tw * th) { i -> tables.alpha(component(bytes, i * bpp, prec)) }
            grid.writeRect(IntRect(info.ox + x0, info.oy + y0, info.ox + x0 + tw, info.oy + y0 + th), px)
        }
        grid.compact()
        return LayerMask(grid, enabled = info.applyMask)
    }

    /** Liest die Kacheln der ersten Hierarchiestufe; [onTile]: Breite, Höhe, Lage in der Ebene, Bytes (verschachtelt), Bytes je Pixel. */
    private fun readTiles(r: BinaryReader, hptr: Long, big: Boolean, compression: Int, onTile: (Int, Int, Int, Int, ByteArray, Int) -> Unit) {
        r.seek(hptr.toInt())
        val hw = r.i32(); val hh = r.i32(); val bpp = r.i32()
        val lptr = if (big) r.i64() else r.u32()
        r.seek(lptr.toInt())
        val lw = r.i32(); val lh = r.i32()
        if (lw != hw || lh != hh || bpp !in 1..64) throw XcfFormatException(tr("Beschädigte Ebenendaten"))
        val tilesX = (lw + TILE - 1) / TILE; val tilesY = (lh + TILE - 1) / TILE
        val ptrs = LongArray(tilesX * tilesY) { if (big) r.i64() else r.u32() }
        val buf = ByteArray(TILE * TILE * bpp)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val i = ty * tilesX + tx
            val tw = minOf(TILE, lw - tx * TILE); val th = minOf(TILE, lh - ty * TILE)
            val n = tw * th
            val start = ptrs[i].toInt()
            val end = if (i + 1 < ptrs.size && ptrs[i + 1] > ptrs[i]) ptrs[i + 1].toInt() else r.limit
            when (compression) {
                0 -> r.copy(start, buf, 0, minOf(n * bpp, r.limit - start))
                1 -> rle(r, start, end, buf, n, bpp)
                2 -> inflate(r, start, end, buf, n * bpp)
                else -> throw XcfFormatException(tr("Nicht unterstützte Kompression {0}", compression))
            }
            onTile(tw, th, tx * TILE, ty * TILE, buf, bpp)
        }
    }

    /** XCF-RLE: je Byte-Position eines Pixels ein eigener Strom (Ebenen nacheinander). */
    private fun rle(r: BinaryReader, start: Int, end: Int, out: ByteArray, n: Int, bpp: Int) {
        var p = start
        fun next(): Int { if (p >= end || p >= r.limit) throw XcfFormatException(tr("RLE-Daten beschädigt")); return r.buf.get(p++).toInt() and 0xFF }
        for (plane in 0 until bpp) {
            var i = 0
            while (i < n) {
                val op = next()
                when {
                    op <= 126 -> { val v = next().toByte(); repeat(minOf(op + 1, n - i)) { out[(i++) * bpp + plane] = v } }
                    op == 127 -> { val cnt = (next() shl 8) or next(); val v = next().toByte(); repeat(minOf(cnt, n - i)) { out[(i++) * bpp + plane] = v } }
                    op == 128 -> { val cnt = (next() shl 8) or next(); repeat(cnt) { val v = next().toByte(); if (i < n) out[(i++) * bpp + plane] = v } }
                    else -> repeat(256 - op) { val v = next().toByte(); if (i < n) out[(i++) * bpp + plane] = v }
                }
            }
        }
    }

    private fun inflate(r: BinaryReader, start: Int, end: Int, out: ByteArray, n: Int) {
        val inf = Inflater()
        try {
            val chunk = ByteArray(minOf(1 shl 16, maxOf(1, end - start)))
            var p = start; var o = 0
            while (o < n && !inf.finished()) {
                if (inf.needsInput()) {
                    if (p >= end) break
                    val k = minOf(chunk.size, end - p); r.copy(p, chunk, 0, k); p += k
                    inf.setInput(chunk, 0, k)
                }
                val got = inf.inflate(out, o, n - o)
                if (got == 0 && inf.needsInput() && p >= end) break
                o += got
            }
        } finally { inf.end() }
    }

    /** Komponente als Gleitkommawert 0..1 bzw. Rohwert (Ganzzahl), Big-Endian. */
    private fun component(b: ByteArray, o: Int, p: Precision): Double = when (p.kind) {
        Precision.Kind.INT -> when (p.bytes) {
            1 -> (b[o].toInt() and 0xFF) / 255.0
            2 -> (((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)) / 65535.0
            else -> ((((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)).toDouble() / 4294967295.0)
        }
        Precision.Kind.HALF -> halfToFloat(((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)).toDouble()
        Precision.Kind.FLOAT -> Float.fromBits(((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)).toDouble()
        Precision.Kind.DOUBLE -> Double.fromBits((0 until 8).fold(0L) { acc, k -> (acc shl 8) or (b[o + k].toLong() and 0xFF) })
    }

    private fun halfToFloat(h: Int): Float {
        val s = (h shr 15) and 1; val e = (h shr 10) and 0x1F; val m = h and 0x3FF
        val v = when (e) {
            0 -> m / 1024f * 2f.pow(-14)
            31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1 + m / 1024f) * 2f.pow(e - 15)
        }
        return if (s == 1) -v else v
    }

    /** Umrechnung 0..1 → 8 Bit: Farbe ggf. von linear nach sRGB-Kurve, Alpha und Masken linear. */
    private class Tables(val p: Precision) {
        fun color(v: Double): Int {
            val x = v.coerceIn(0.0, 1.0)
            val g = if (p.linear) (if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - 0.055) else x
            return (g * 255 + 0.5).toInt().coerceIn(0, 255)
        }
        fun alpha(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
    }

    /** GIMP-Mischmodus → nächster Photoshop-Mischmodus. */
    private fun blendMode(m: Int, group: Boolean, warnings: MutableSet<String>): BlendMode = when (m) {
        0, 28 -> BlendMode.NORMAL
        1 -> BlendMode.DISSOLVE
        3, 30 -> BlendMode.MULTIPLY
        4, 31 -> BlendMode.SCREEN
        5, 23 -> BlendMode.OVERLAY
        6, 32 -> BlendMode.DIFFERENCE
        7, 33 -> BlendMode.LINEAR_DODGE
        8, 34 -> BlendMode.SUBTRACT
        9, 35 -> BlendMode.DARKEN
        10, 36 -> BlendMode.LIGHTEN
        11, 24, 37 -> BlendMode.HUE
        12, 25, 38 -> BlendMode.SATURATION
        13, 26, 39 -> BlendMode.COLOR
        14, 27, 40, 56 -> BlendMode.LUMINOSITY
        15, 41 -> BlendMode.DIVIDE
        16, 42 -> BlendMode.COLOR_DODGE
        17, 43 -> BlendMode.COLOR_BURN
        18, 44 -> BlendMode.HARD_LIGHT
        19, 45 -> BlendMode.SOFT_LIGHT
        48 -> BlendMode.VIVID_LIGHT
        49 -> BlendMode.PIN_LIGHT
        50 -> BlendMode.LINEAR_LIGHT
        51 -> BlendMode.HARD_MIX
        52 -> BlendMode.EXCLUSION
        53 -> BlendMode.LINEAR_BURN
        54 -> BlendMode.DARKER_COLOR
        55 -> BlendMode.LIGHTER_COLOR
        61 -> if (group) BlendMode.PASS_THROUGH else BlendMode.NORMAL
        else -> { warnings += tr("GIMP-Mischmodus {0} (z. B. Körnung extrahieren/zusammenführen) gibt es hier nicht – als „Normal“ übernommen.", m); BlendMode.NORMAL }
    }
}
