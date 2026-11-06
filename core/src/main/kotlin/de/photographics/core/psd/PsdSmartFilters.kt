package de.photographics.core.psd

import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.SmartFilter
import de.photographics.core.doc.SmartFilters
import de.photographics.core.filters.Gallery
import de.photographics.core.filters.GalleryEffect
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import java.io.ByteArrayOutputStream

/**
 * Smartfilter im eigenen Zusatzblock „pgSF“ (neben dem Smartobjekt „pgSO“): Kennung und Werte je
 * Filter, Fülloptionen, Filtergalerie-Ketten und die Filtermaske. Photoshop kennt den Block nicht
 * und zeigt die gespeicherten (gefilterten) Ebenenpixel; photographics regelt die Filter weiter nach.
 */
object PsdSmartFilters {
    const val KEY = "pgSF"
    private const val VERSION = 1

    fun write(sf: SmartFilters): ByteArray {
        val w = BinaryWriter()
        w.u16(VERSION)
        w.u8(if (sf.visible) 1 else 0)
        w.i32(sf.filters.size)
        for (f in sf.filters) {
            str(w, f.filterId)
            w.u8(if (f.enabled) 1 else 0)
            w.ascii(f.blendMode.psdKey)
            w.f64(f.opacity.toDouble())
            w.i32(f.values.size); for (v in f.values) w.f64(v.toDouble())
            val g = f.gallery
            w.i32(g?.size ?: -1)
            if (g != null) {
                w.i32(f.galleryFg); w.i32(f.galleryBg)
                for (e in g) {
                    str(w, e.spec.id); w.u8(if (e.visible) 1 else 0)
                    w.i32(e.values.size); for (v in e.values) w.f64(v.toDouble())
                }
            }
        }
        val m = sf.mask
        w.u8(if (m == null) 0 else 1)
        if (m != null) {
            val g = m.grid
            w.u8(if (m.enabled) 1 else 0); w.u8(g.defaultValue)
            w.i32(g.width); w.i32(g.height)
            // Zeilenweise PackBits wie die Ebenenkanäle
            val row = IntArray(g.width); val plane = ByteArray(g.width)
            val enc = ByteArrayOutputStream(); val rowOut = ByteArrayOutputStream()
            for (y in 0 until g.height) {
                g.readRect(IntRect(0, y, g.width, y + 1), row)
                for (x in 0 until g.width) plane[x] = row[x].toByte()
                rowOut.reset(); PackBits.encode(plane, 0, g.width, rowOut)
                val n = rowOut.size()
                enc.write(n ushr 24); enc.write(n ushr 16); enc.write(n ushr 8); enc.write(n)
                rowOut.writeTo(enc)
            }
            w.bytes(enc.toByteArray())
        }
        return w.toByteArray()
    }

    /** null: Block beschädigt oder aus einer neueren, unbekannten Fassung. */
    fun read(data: ByteArray): SmartFilters? = runCatching {
        val r = BinaryReader(java.nio.ByteBuffer.wrap(data))
        if (r.u16() != VERSION) return@runCatching null
        val visible = r.u8() != 0
        val n = r.i32()
        val filters = List(n) {
            val id = str(r)
            val enabled = r.u8() != 0
            val mode = BlendMode.fromPsdKey(r.ascii(4))
            val opacity = r.f64().toFloat()
            val values = List(r.i32()) { r.f64().toFloat() }
            val gn = r.i32()
            if (gn < 0) SmartFilter(id, values, enabled = enabled, blendMode = mode, opacity = opacity)
            else {
                val fg = r.i32(); val bg = r.i32()
                val effects = (0 until gn).mapNotNull {
                    val sid = str(r); val vis = r.u8() != 0
                    val vals = FloatArray(r.i32()) { r.f64().toFloat() }
                    Gallery.byId(sid)?.let { spec -> GalleryEffect(spec, vals, vis) }
                }
                SmartFilter(id, values, effects, fg, bg, enabled, mode, opacity)
            }
        }
        val mask = if (r.u8() == 0) null else {
            val enabled = r.u8() != 0; val def = r.u8()
            val w = r.i32(); val h = r.i32()
            val g = TileGrid(w, h, def)
            val row = ByteArray(w); val line = IntArray(w)
            for (y in 0 until h) {
                val len = r.i32()
                PackBits.decode(r, r.pos, len, row, 0, w)
                r.skip(len.toLong())
                for (x in 0 until w) line[x] = row[x].toInt() and 0xFF
                g.writeRect(IntRect(0, y, w, y + 1), line)
            }
            g.compact()
            LayerMask(g, enabled)
        }
        SmartFilters(filters, visible, mask)
    }.getOrNull()

    private fun str(w: BinaryWriter, s: String) { val b = s.toByteArray(Charsets.UTF_8); w.u16(b.size); w.bytes(b) }
    private fun str(r: BinaryReader): String = String(r.bytes(r.u16()), Charsets.UTF_8)
}
