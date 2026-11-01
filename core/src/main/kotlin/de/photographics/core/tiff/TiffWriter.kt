package de.photographics.core.tiff

import de.photographics.core.tr
import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.IntRect
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import kotlin.math.roundToInt

/**
 * Schreibt TIFF (Big-Endian, wie Photoshop mit Bytefolge „Macintosh“): zusammengefügtes Bild in
 * 8 Bit RGB(A), unkomprimiert, LZW oder ZIP mit Prädiktor, eingebettetes Farbprofil, Auflösung und
 * – wenn gewünscht – die Ebenen im Photoshop-Ebenenblock (Tag 37724). Photoshop öffnet die Datei
 * dann mit allen Ebenen; andere Programme sehen das zusammengefügte Bild.
 *
 * Streifen und Ebenendaten werden zuerst in temporäre Dateien kodiert; danach steht der ganze
 * Aufbau fest und die Datei wird in einem Zug geschrieben (geht auch in nicht positionierbare Ziele).
 */
object TiffWriter {
    enum class Compression(val code: Int, val label: String) { NONE(1, "Ohne"), LZW(5, "LZW"), ZIP(8, "ZIP") }

    private const val MAGIC = "Adobe Photoshop Document Data Block"

    fun write(doc: Document, output: OutputStream, tmpDir: File, compression: Compression = Compression.LZW, layers: Boolean = true) {
        tmpDir.mkdirs()
        val hasAlpha = !compositeIsOpaque(doc)
        val spp = if (hasAlpha) 4 else 3
        val w = doc.width; val h = doc.height
        // 16-Bit-Dokument: 16 Bit je Kanal (Big Endian) aus dem Gleitkomma-Ergebnis
        val deep = doc.bitDepth == 16
        val bytesPerSample = if (deep) 2 else 1
        val rowBytes = w * spp * bytesPerSample
        val rowsPerStrip = (256 * 1024 / rowBytes).coerceIn(1, h)
        val strips = (h + rowsPerStrip - 1) / rowsPerStrip
        val stripCounts = LongArray(strips)
        val stripFile = File.createTempFile("tiff", ".strips", tmpDir)
        var layerInfo: PsdWriter.LayerInfo? = null
        try {
            // 1. Gesamtbild streifenweise kodieren
            BufferedOutputStream(FileOutputStream(stripFile), 1 shl 16).use { out ->
                val raw = ByteArray(rowsPerStrip * rowBytes)
                for (s in 0 until strips) {
                    val y0 = s * rowsPerStrip; val y1 = minOf(h, y0 + rowsPerStrip)
                    val n = (y1 - y0) * rowBytes
                    var o = 0
                    if (deep) {
                        val s16 = PsdWriter.renderDeep(doc, IntRect(0, y0, w, y1))
                        for (k in 0 until w * (y1 - y0)) for (c in 0 until spp) { val v = s16[k * 4 + c].toInt(); raw[o++] = (v ushr 8).toByte(); raw[o++] = v.toByte() }
                    } else {
                        val px = Compositor.renderArgb(doc, IntRect(0, y0, w, y1))
                        for (c in px) {
                            raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte()
                            if (hasAlpha) raw[o++] = (c ushr 24).toByte()
                        }
                    }
                    if (compression != Compression.NONE) {
                        // Horizontaler Prädiktor (rückwärts, damit die Originalwerte als Basis dienen), je Abtastwert
                        for (r in 0 until y1 - y0) {
                            val b = r * rowBytes
                            if (deep) for (i in w * spp - 1 downTo spp) {
                                val cur = ((raw[b + i * 2].toInt() and 0xFF) shl 8) or (raw[b + i * 2 + 1].toInt() and 0xFF)
                                val prev = ((raw[b + (i - spp) * 2].toInt() and 0xFF) shl 8) or (raw[b + (i - spp) * 2 + 1].toInt() and 0xFF)
                                val d = cur - prev
                                raw[b + i * 2] = (d ushr 8).toByte(); raw[b + i * 2 + 1] = d.toByte()
                            }
                            else for (i in rowBytes - 1 downTo spp) raw[b + i] = (raw[b + i] - raw[b + i - spp]).toByte()
                        }
                    }
                    val enc = when (compression) {
                        Compression.NONE -> raw.copyOf(n)
                        Compression.LZW -> Lzw.encode(raw, n)
                        Compression.ZIP -> deflate(raw, n)
                    }
                    out.write(enc); stripCounts[s] = enc.size.toLong()
                }
            }
            // 2. Ebenen (Photoshop-Format) vorbereiten
            if (layers) layerInfo = PsdWriter.layerInfo(doc, tmpDir, hasAlpha, deep)
            val layerBlockSize = layerInfo?.let { (MAGIC.length + 1) + 12 + pad4(it.size) } ?: 0L

            // 3. Aufbau berechnen: Kopf, IFD, ausgelagerte Werte, Streifen, Ebenenblock
            val icc = doc.colorProfile.bytes
            val software = "Photographics\u0000".toByteArray(Charsets.US_ASCII)
            val dpi = doc.dpi.takeIf { it > 0 } ?: 72f
            val tags = ArrayList<Tag>()
            tags += Tag.long(254, 0)
            tags += Tag.long(256, w.toLong()); tags += Tag.long(257, h.toLong())
            tags += Tag.shorts(258, IntArray(spp) { if (deep) 16 else 8 })
            tags += Tag.short(259, compression.code)
            tags += Tag.short(262, 2)
            val stripOffsetsTag = Tag.longs(273, LongArray(strips)); tags += stripOffsetsTag
            tags += Tag.short(277, spp)
            tags += Tag.long(278, rowsPerStrip.toLong())
            tags += Tag.longs(279, stripCounts)
            tags += Tag.rational(282, dpi); tags += Tag.rational(283, dpi)
            tags += Tag.short(284, 1)
            tags += Tag.short(296, 2)
            tags += Tag(305, 2, software.size.toLong(), software)
            if (compression != Compression.NONE) tags += Tag.short(317, 2)
            if (hasAlpha) tags += Tag.short(338, 2) // nicht vormultiplizierte Transparenz
            tags += Tag(TiffReader.T_ICC, 7, icc.size.toLong(), icc)
            val layerTag = if (layerInfo != null) Tag(TiffReader.T_PHOTOSHOP_LAYERS, 7, layerBlockSize, null).also { tags += it } else null
            tags.sortBy { it.id }

            val ifdSize = 2L + tags.size * 12 + 4
            var p = 8 + ifdSize
            for (t in tags) { val d = t.data; if (d != null && d.size > 4) { t.offset = p; p += even(d.size.toLong()) } }
            val stripStart = p
            var sp = stripStart
            for (s in 0 until strips) { stripOffsetsTag.values!![s] = sp; sp += stripCounts[s] }
            stripOffsetsTag.refresh()
            // Werte der Streifen-Offsets haben sich geändert, die Länge nicht
            layerTag?.offset = even(sp)
            val total = (layerTag?.offset ?: sp) + layerBlockSize
            if (total > 0xFFFFFFFFL) throw TiffFormatException(tr("Dokument zu groß für TIFF (über 4 GB)"))

            // 4. Schreiben
            val head = Be()
            head.ascii("MM"); head.u16(42); head.u32(8)
            head.u16(tags.size)
            for (t in tags) {
                head.u16(t.id); head.u16(t.type); head.u32(t.count)
                val d = t.data
                when {
                    d == null || d.size > 4 -> head.u32(t.offset)
                    else -> { head.bytes(d); repeat(4 - d.size) { head.u8(0) } }
                }
            }
            head.u32(0)
            for (t in tags) { val d = t.data; if (d != null && d.size > 4) { head.bytes(d); if (d.size % 2 == 1) head.u8(0) } }
            output.write(head.toByteArray())
            stripFile.inputStream().use { it.copyTo(output, 1 shl 16) }
            layerInfo?.let { li ->
                if (sp % 2 == 1L) output.write(0)
                val b = Be()
                b.ascii(MAGIC); b.u8(0)
                b.ascii("8BIM"); b.ascii("Layr"); b.u32(pad4(li.size))
                output.write(b.toByteArray())
                li.writeTo(output)
                repeat((pad4(li.size) - li.size).toInt()) { output.write(0) }
            }
            output.flush()
        } finally {
            layerInfo?.close()
            stripFile.delete()
        }
    }

    /**
     * Ist das Gesamtbild sicher deckend? Das ist es, wenn eine sichtbare Ebene der obersten Stufe
     * normal, voll deckend, ohne Maske die ganze Arbeitsfläche deckt (Ebenen darüber können die
     * Deckung nicht verringern) – so bekommen gewöhnliche Fotos keinen unnötigen Alphakanal.
     */
    private fun compositeIsOpaque(doc: Document): Boolean {
        for (l in doc.root.children) {
            if (l !is PixelLayer || !l.visible || l.clipped || l.mask != null || l.opacity < 1f || l.fillOpacity < 1f ||
                l.blendMode != BlendMode.NORMAL) continue
            val g = l.pixels
            if (g.extent.intersect(doc.bounds) != doc.bounds) continue
            var opaque = true
            val band = maxOf(1, (1 shl 18) / doc.width)
            val row = IntArray(doc.width * band)
            var y = 0
            while (y < doc.height && opaque) {
                val r = IntRect(0, y, doc.width, minOf(doc.height, y + band))
                val buf = if (r.height == band) row else IntArray(r.width * r.height)
                g.readRect(r, buf)
                for (c in buf) if ((c ushr 24) != 0xFF) { opaque = false; break }
                y = r.bottom
            }
            if (opaque) return true
        }
        return false
    }

    private fun even(v: Long) = v + (v and 1)
    private fun pad4(v: Long) = (v + 3) / 4 * 4

    private fun deflate(raw: ByteArray, n: Int): ByteArray {
        val d = Deflater(6)
        try {
            d.setInput(raw, 0, n); d.finish()
            val out = ByteArrayOutputStream(n / 2 + 64)
            val buf = ByteArray(1 shl 16)
            while (!d.finished()) { val k = d.deflate(buf); out.write(buf, 0, k) }
            return out.toByteArray()
        } finally { d.end() }
    }

    private class Tag(val id: Int, val type: Int, val count: Long, var data: ByteArray?) {
        var offset = 0L
        var values: LongArray? = null
        fun refresh() { data = Be().also { b -> values!!.forEach { b.u32(it) } }.toByteArray() }

        companion object {
            fun short(id: Int, v: Int) = Tag(id, 3, 1, byteArrayOf((v shr 8).toByte(), v.toByte()))
            fun shorts(id: Int, v: IntArray) = Tag(id, 3, v.size.toLong(), Be().also { b -> v.forEach { b.u16(it) } }.toByteArray())
            fun long(id: Int, v: Long) = Tag(id, 4, 1, Be().also { it.u32(v) }.toByteArray())
            fun longs(id: Int, v: LongArray) = Tag(id, 4, v.size.toLong(), null).also { it.values = v; it.refresh() }
            fun rational(id: Int, v: Float) = Tag(id, 5, 1, Be().also { it.u32((v * 100).roundToInt().toLong()); it.u32(100) }.toByteArray())
        }
    }

    private class Be {
        private val o = ByteArrayOutputStream()
        fun u8(v: Int) = o.write(v)
        fun u16(v: Int) { o.write(v shr 8); o.write(v) }
        fun u32(v: Long) { u16((v shr 16).toInt() and 0xFFFF); u16(v.toInt() and 0xFFFF) }
        fun ascii(s: String) = o.write(s.toByteArray(Charsets.ISO_8859_1))
        fun bytes(b: ByteArray) = o.write(b)
        fun toByteArray(): ByteArray = o.toByteArray()
    }

    /** TIFF-LZW-Kodierer (MSB zuerst, „early change“ wie libtiff). */
    internal object Lzw {
        fun encode(src: ByteArray, n: Int): ByteArray {
            val out = ByteArrayOutputStream(n / 2 + 64)
            var acc = 0L; var bits = 0
            var width = 9
            fun put(code: Int) {
                acc = (acc shl width) or code.toLong(); bits += width
                while (bits >= 8) { out.write((acc shr (bits - 8)).toInt() and 0xFF); bits -= 8 }
            }
            // Hashtabelle (Präfixcode, Byte) → Code
            val size = 1 shl 13
            val keys = IntArray(size); val vals = IntArray(size)
            fun clear() { keys.fill(-1) }
            clear()
            var next = 258
            put(256)
            if (n == 0) { put(257); if (bits > 0) out.write((acc shl (8 - bits)).toInt() and 0xFF); return out.toByteArray() }
            var cur = src[0].toInt() and 0xFF
            for (i in 1 until n) {
                val c = src[i].toInt() and 0xFF
                val key = (cur shl 8) or c
                var hsh = (key * -0x61c88647 ushr 19) and (size - 1)
                var found = -1
                while (keys[hsh] != -1) { if (keys[hsh] == key) { found = vals[hsh]; break }; hsh = (hsh + 1) and (size - 1) }
                if (found >= 0) { cur = found; continue }
                put(cur)
                keys[hsh] = key; vals[hsh] = next; next++
                if (next >= (1 shl width) && width < 12) width++
                if (next >= 4094) { put(256); clear(); next = 258; width = 9 }
                cur = c
            }
            put(cur)
            next++
            if (next >= (1 shl width) && width < 12) width++
            put(257)
            if (bits > 0) out.write((acc shl (8 - bits)).toInt() and 0xFF)
            return out.toByteArray()
        }
    }
}
