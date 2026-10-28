package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.Compositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MipMapTest {
    private val w = 700; private val h = 520

    private fun pattern(seed: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val a = if ((x / 37 + y / 23) % 5 == 0) 0 else 255
        (a shl 24) or (((x * 3 + seed) xor y) and 0xFF shl 16) or ((x + y * 2) and 0xFF shl 8) or ((x * y + seed) and 0xFF)
    }

    /** Erwartete Verkleinerung (deckkraftgewichteter Mittelwert über f×f). */
    private fun reference(px: IntArray, f: Int, x: Int, y: Int): Int {
        var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
        for (yy in y * f until minOf(h, y * f + f)) for (xx in x * f until minOf(w, x * f + f)) {
            val c = px[yy * w + xx]; val ca = c ushr 24
            a += ca; r += ((c shr 16) and 0xFF) * ca; g += ((c shr 8) and 0xFF) * ca; b += (c and 0xFF) * ca; n++
        }
        return if (a == 0) 0 else ((a / n) shl 24) or ((r / a) shl 16) or ((g / a) shl 8) or (b / a)
    }

    private fun at(g: TileGrid, x: Int, y: Int) = IntArray(1).also { g.readRect(IntRect(x, y, x + 1, y + 1), it) }[0]

    @Test
    fun levelsMatchBoxFilterAndFollowChanges() {
        val px = pattern(3)
        val g = TileGrid.fromPixels(w, h, px)
        for (level in 1..3) {
            val m = g.mip(level); val f = 1 shl level
            for (y in 0 until m.height step 7) for (x in 0 until m.width step 5) assertEquals("L$level ($x,$y)", reference(px, f, x, y), at(m, x, y))
        }
        // Schreiben über writeRect
        g.writeRect(IntRect(300, 260, 304, 264), IntArray(16) { 0xFF00FF00.toInt() })
        assertEquals(0xFF00FF00.toInt(), at(g.mip(2), 75, 65))
        // Schreiben über angeheftetes Array (Pinsel)
        val t = g.tileOrCreate(0, 0); val a = t.pin(); for (i in 0 until 4) for (j in 0 until 4) a[(j shl 8) or i] = 0xFF0000FF.toInt(); t.unpin()
        assertEquals(0xFF0000FF.toInt(), at(g.mip(2), 0, 0))
        // Kachel austauschen (Rückgängig) und Kachel entfernen
        g.setTile(1, 1, null)
        assertEquals(0, at(g.mip(1), 128 + 5, 128 + 5))
        // fill
        g.tileOrCreate(2, 0).fill(0xFFFFFFFF.toInt())
        assertEquals(0xFFFFFFFF.toInt(), at(g.mip(3), 64 + 3, 3))
    }

    @Test
    fun scalarMipForMasks() {
        val m = TileGrid(w, h, 255)
        m.writeRect(IntRect(0, 0, 2, 2), intArrayOf(0, 0, 255, 255))
        assertEquals(128, at(m.mip(1, scalar = true), 0, 0))
        assertEquals(255, at(m.mip(1, scalar = true), 100, 100))
    }

    /** Verkleinerte Anzeige aus Mipmaps ≈ volle Auflösung, danach verkleinert. */
    @Test
    fun sampledCompositeMatchesDownscaledFull() {
        val doc = Document(w, h)
        doc.root.add(PixelLayer("A", TileGrid.fromPixels(w, h, IntArray(w * h) { 0xFF336699.toInt() })))
        val top = PixelLayer("B", TileGrid.fromPixels(w, h, pattern(9))).also { it.opacity = 0.6f }
        top.mask = LayerMask(TileGrid(w, h, 255).also { it.writeRect(IntRect(0, 0, 350, 520), IntArray(350 * 520) { 60 }) })
        doc.root.add(top)
        val full = Compositor.renderArgb(doc, doc.bounds)
        val ow = (w + 1) / 2; val oh = (h + 1) / 2
        val small = Compositor.renderArgbSampled(doc, IntRect(0, 0, ow, oh), 2)
        var worst = 0; var sum = 0L
        for (y in 0 until oh - 1) for (x in 0 until ow - 1) {
            val ref = reference(full, 2, x, y); val c = small[y * ow + x]
            for (s in intArrayOf(16, 8, 0)) { val d = abs(((ref shr s) and 0xFF) - ((c shr s) and 0xFF)); worst = maxOf(worst, d); sum += d }
        }
        val mean = sum.toDouble() / ((ow - 1) * (oh - 1) * 3)
        assertTrue("max $worst Ø $mean", worst <= 3 && mean < 0.5)
    }

    /** Smartobjekt-Vorschau (direkt aus der Quelle) ≈ endgültiges bikubisches Ergebnis. */
    @Test
    fun smartPreviewMatchesFinal() {
        val doc = Document(w, h)
        val layer = PixelLayer("S", TileGrid.fromPixels(w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or ((x / 4) and 0xFF shl 16) or ((y / 4) and 0xFF shl 8) or 0x40 }))
        doc.root.add(layer); doc.activeLayer = layer
        val ed = Editor(doc)
        val smart = ed.convertToSmartObject(layer)!!
        val t = smart.transform.then(Affine.rotate(12.0)).then(Affine.translate(60.0, -20.0))
        ed.previewTransform(smart, t)
        assertTrue(smart.samplesDirectly)
        val preview = Compositor.renderArgb(doc, doc.bounds)
        assertTrue("Vorschau darf kein volles Raster erzeugen", smart.samplesDirectly)
        ed.commitTransform(smart, smart.transform, t)
        val final = Compositor.renderArgb(doc, doc.bounds)
        var sum = 0L; var n = 0
        for (i in final.indices) if ((final[i] ushr 24) == 255 && (preview[i] ushr 24) == 255) {
            for (s in intArrayOf(16, 8, 0)) sum += abs(((final[i] shr s) and 0xFF) - ((preview[i] shr s) and 0xFF)); n++
        }
        assertTrue("Ø ${sum.toDouble() / (n * 3)}", n > w * h / 3 && sum.toDouble() / (n * 3) < 1.5)
    }
}
