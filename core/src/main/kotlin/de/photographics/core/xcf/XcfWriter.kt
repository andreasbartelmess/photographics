package de.photographics.core.xcf

import de.photographics.core.tr
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer
import de.photographics.core.doc.RasterLayer
import de.photographics.core.image.IntRect
import de.photographics.core.render.BlendMode
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.Deflater
import kotlin.math.roundToInt

/**
 * Schreibt GIMP-Dateien (XCF v011, 8 Bit, zlib – lesbar ab GIMP 2.10): Ebenen mit Versatz,
 * Gruppen, Masken, Deckkraft, Sichtbarkeit, Mischmodi, Hilfslinien, Auflösung und Farbprofil.
 * Mischen und Zusammensetzen werden auf „wahrnehmungsgleich“ gestellt – so mischt GIMP wie
 * Photographics und Photoshop. Text- und Smartobjekte werden als Pixel geschrieben;
 * Einstellungsebenen und Schnittmasken gibt es in GIMP nicht (siehe Rückgabe: Hinweise).
 *
 * Die Datei entsteht in [tmpDir] (Zeiger werden nachgetragen) und wird dann in [out] kopiert.
 */
object XcfWriter {
    private const val TILE = XcfReader.TILE
    /** GimpLayerColorSpace: wahrnehmungsgleiches RGB. */
    private const val SPACE_PERCEPTUAL = 2

    fun write(doc: Document, out: OutputStream, tmpDir: File): List<String> {
        tmpDir.mkdirs()
        val warnings = linkedSetOf<String>()
        val file = File.createTempFile("xcf-", ".xcf", tmpDir)
        try {
            RandomAccessFile(file, "rw").use { raf -> Out(raf).writeDocument(doc, warnings) }
            file.inputStream().use { it.copyTo(out, 1 shl 16) }
            out.flush()
        } finally {
            file.delete()
        }
        return warnings.toList()
    }

    /** Ebenen in XCF-Reihenfolge (oben zuerst, Gruppen vor ihren Kindern) mit Elementpfad. */
    private class Item(val layer: Layer, val path: IntArray)

    private fun items(group: GroupLayer, prefix: IntArray, out: MutableList<Item>, warnings: MutableSet<String>) {
        val kept = group.children.filter { l ->
            if (l is AdjustmentLayer) { warnings += tr("Einstellungsebenen gibt es in GIMP nicht – sie wurden weggelassen."); false } else true
        }
        for ((i, l) in kept.asReversed().withIndex()) {
            val path = prefix + i
            if (l.clipped) warnings += tr("Schnittmasken gibt es in GIMP nicht – die Ebenen wirken dort auf alles darunter.")
            out += Item(l, path)
            if (l is GroupLayer) items(l, path, out, warnings)
        }
    }

    private class Out(val raf: RandomAccessFile) {
        private val buf = java.io.ByteArrayOutputStream()
        fun flush() { if (buf.size() > 0) { raf.write(buf.toByteArray()); buf.reset() } }
        fun u8(v: Int) { buf.write(v); if (buf.size() > 1 shl 16) flush() }
        fun u32(v: Int) { u8(v ushr 24); u8(v ushr 16); u8(v ushr 8); u8(v) }
        fun u64(v: Long) { u32((v ushr 32).toInt()); u32(v.toInt()) }
        fun f32(v: Float) = u32(v.toRawBits())
        fun bytes(b: ByteArray) { flush(); raf.write(b) }
        fun string(s: String) { val b = s.toByteArray(Charsets.UTF_8); u32(b.size + 1); bytes(b); u8(0) }
        fun prop(type: Int, body: Out.() -> Unit) {
            // Eigenschaft: Typ, Länge, Inhalt (Länge nachgetragen)
            u32(type); flush(); val lenAt = raf.filePointer; u32(0); flush()
            val start = raf.filePointer
            body(); flush()
            val end = raf.filePointer
            raf.seek(lenAt); raf.writeInt((end - start).toInt()); raf.seek(end)
        }
        fun patch64(at: Long, v: Long) { flush(); val here = raf.filePointer; raf.seek(at); raf.writeLong(v); raf.seek(here) }

        fun writeDocument(doc: Document, warnings: MutableSet<String>) {
            bytes("gimp xcf v011".toByteArray(Charsets.US_ASCII)); u8(0)
            u32(doc.width); u32(doc.height); u32(0); u32(150)
            prop(17) { u8(2) }   // zlib
            prop(19) { f32(doc.dpi); f32(doc.dpi) }
            if (doc.guides.isNotEmpty()) prop(18) { for (g in doc.guides) { u32(g.position.roundToInt()); u8(if (g.vertical) 2 else 1) } }
            prop(22) { u32(1) }   // Einheit: Zoll
            prop(21) {   // Parasiten: Farbprofil
                string("icc-profile"); u32(3); u32(doc.colorProfile.bytes.size); bytes(doc.colorProfile.bytes)   // dauerhaft + rückgängig machbar (wie GIMP)
            }
            u32(0); u32(0)
            val list = ArrayList<Item>()
            items(doc.root, IntArray(0), list, warnings)
            flush()
            val table = raf.filePointer
            repeat(list.size + 1) { u64(0) }   // Ebenenzeiger (nachgetragen), Ende
            u64(0)                              // keine Kanäle
            for ((i, item) in list.withIndex()) {
                flush(); patch64(table + i * 8L, raf.filePointer)
                writeLayer(doc, item, item.layer === doc.activeLayer, warnings)
            }
            flush()
        }

        private fun writeLayer(doc: Document, item: Item, active: Boolean, warnings: MutableSet<String>) {
            val l = item.layer
            val raster = (l as? RasterLayer)?.raster
            val bounds = when {
                raster != null -> raster.contentBounds().takeIf { !it.isEmpty } ?: IntRect(0, 0, 1, 1)
                else -> IntRect.ofSize(doc.width, doc.height)
            }
            u32(bounds.width); u32(bounds.height); u32(1)   // RGBA
            string(l.name)
            if (active) prop(2) {}
            prop(6) { u32((l.opacity * 255).roundToInt().coerceIn(0, 255)) }
            prop(33) { f32(l.opacity) }
            prop(8) { u32(if (l.visible) 1 else 0) }
            prop(10) { u32(if (l.lockTransparency) 1 else 0) }
            if (l.mask != null) { prop(11) { u32(if (l.mask!!.enabled) 1 else 0) }; prop(12) { u32(0) }; prop(13) { u32(0) } }
            prop(15) { u32(bounds.left); u32(bounds.top) }
            prop(7) { u32(gimpMode(l, warnings)) }
            prop(37) { u32(SPACE_PERCEPTUAL) }   // Mischfarbraum
            prop(36) { u32(SPACE_PERCEPTUAL) }   // Zusammensetzungsfarbraum
            if (l is GroupLayer) { prop(29) {}; prop(31) { u32(if (l.expanded) 1 else 0) } }
            if (item.path.size > 1) prop(30) { for (p in item.path) u32(p) }
            u32(0); u32(0)
            flush()
            val ptrs = raf.filePointer
            u64(0); u64(0)
            flush(); patch64(ptrs, raf.filePointer)
            writeHierarchy(bounds, 4) { r, px -> if (raster != null) raster.readRect(r, px) else px.fill(0) }
            l.mask?.let { m ->
                flush(); patch64(ptrs + 8, raf.filePointer)
                u32(bounds.width); u32(bounds.height); string("${l.name} – Maske")
                prop(6) { u32(255) }; prop(8) { u32(0) }; prop(16) { u8(0); u8(0); u8(0) }
                u32(0); u32(0)
                flush(); val hp = raf.filePointer; u64(0); flush(); patch64(hp, raf.filePointer)
                writeHierarchy(bounds, 1) { r, px -> m.grid.readRect(r, px) }
            }
        }

        /** Hierarchie mit einer Stufe; Kacheln 64×64, zlib, [bpp] = 4 (RGBA) oder 1 (Grau). */
        private fun writeHierarchy(bounds: IntRect, bpp: Int, read: (IntRect, IntArray) -> Unit) {
            u32(bounds.width); u32(bounds.height); u32(bpp)
            flush(); val levelPtr = raf.filePointer; u64(0); u64(0)
            flush(); patch64(levelPtr, raf.filePointer)
            u32(bounds.width); u32(bounds.height)
            val tilesX = (bounds.width + TILE - 1) / TILE; val tilesY = (bounds.height + TILE - 1) / TILE
            flush(); val table = raf.filePointer
            repeat(tilesX * tilesY + 1) { u64(0) }
            val px = IntArray(TILE * TILE); val raw = ByteArray(TILE * TILE * 4); val packed = ByteArray(TILE * TILE * 4 + 1024)
            val def = Deflater(6)
            try {
                for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
                    val r = IntRect(bounds.left + tx * TILE, bounds.top + ty * TILE, minOf(bounds.right, bounds.left + (tx + 1) * TILE), minOf(bounds.bottom, bounds.top + (ty + 1) * TILE))
                    val n = r.width * r.height
                    read(r, px)
                    var o = 0
                    for (i in 0 until n) {
                        val c = px[i]
                        if (bpp == 4) { raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte(); raw[o++] = (c ushr 24).toByte() }
                        else raw[o++] = c.coerceIn(0, 255).toByte()
                    }
                    def.reset(); def.setInput(raw, 0, o); def.finish()
                    flush(); patch64(table + (ty * tilesX + tx) * 8L, raf.filePointer)
                    while (!def.finished()) { val k = def.deflate(packed); if (k > 0) raf.write(packed, 0, k) }
                }
            } finally { def.end() }
        }

        private fun gimpMode(l: Layer, warnings: MutableSet<String>): Int = when (l.blendMode) {
            BlendMode.PASS_THROUGH -> if (l is GroupLayer) 61 else 28
            BlendMode.NORMAL -> 28; BlendMode.DISSOLVE -> 1; BlendMode.MULTIPLY -> 30; BlendMode.SCREEN -> 31
            BlendMode.OVERLAY -> 23; BlendMode.DIFFERENCE -> 32; BlendMode.LINEAR_DODGE -> 33; BlendMode.SUBTRACT -> 34
            BlendMode.DARKEN -> 35; BlendMode.LIGHTEN -> 36; BlendMode.HUE -> 37; BlendMode.SATURATION -> 38
            BlendMode.COLOR -> 39; BlendMode.LUMINOSITY -> 40; BlendMode.DIVIDE -> 41; BlendMode.COLOR_DODGE -> 42
            BlendMode.COLOR_BURN -> 43; BlendMode.HARD_LIGHT -> 44; BlendMode.SOFT_LIGHT -> 45; BlendMode.VIVID_LIGHT -> 48
            BlendMode.PIN_LIGHT -> 49; BlendMode.LINEAR_LIGHT -> 50; BlendMode.HARD_MIX -> 51; BlendMode.EXCLUSION -> 52
            BlendMode.LINEAR_BURN -> 53; BlendMode.DARKER_COLOR -> 54; BlendMode.LIGHTER_COLOR -> 55
        }
    }
}
