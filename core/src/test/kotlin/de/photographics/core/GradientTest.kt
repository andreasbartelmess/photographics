package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.editor.Gradient
import de.photographics.core.editor.GradientType
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.selection.Selection
import de.photographics.core.tools.PaintTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientTest {
    private val w = 300; private val h = 100

    private fun doc(fill: Int = 0xFF808080.toInt()): Pair<Editor, PixelLayer> {
        val doc = Document(w, h)
        val l = PixelLayer("Ebene", TileGrid.fromPixels(w, h, IntArray(w * h) { fill }))
        doc.root.add(l); doc.activeLayer = l
        return Editor(doc) to l
    }

    private fun gray(c: Int) = (c shr 16) and 0xFF

    @Test
    fun linearBlackToWhite() {
        val (ed, l) = doc()
        ed.fillGradient(PaintTarget.PIXELS, Gradient(GradientType.LINEAR, 0f, 0f, w.toFloat(), 0f, 0xFF000000.toInt(), -1), 1f)
        assertTrue(gray(l.pixels[0, 50]) <= 2)
        assertTrue(gray(l.pixels[w - 1, 50]) >= 253)
        // Mitte: über eine Spalte gemittelt (Dithering) etwa 128
        val mid = (0 until h).map { gray(l.pixels[150, it]) }.average()
        assertEquals(128.0, mid, 1.5)
        // monoton steigend im Spaltenmittel
        val cols = (0 until w step 10).map { x -> (0 until h).map { gray(l.pixels[x, it]) }.average() }
        assertTrue(cols.zipWithNext().all { (a, b) -> b >= a - 0.6 })
    }

    @Test
    fun radialAndReflected() {
        val (ed, l) = doc()
        ed.fillGradient(PaintTarget.PIXELS, Gradient(GradientType.RADIAL, 150f, 50f, 200f, 50f, -1, 0xFF000000.toInt()), 1f)
        assertTrue(gray(l.pixels[150, 50]) >= 250)
        assertTrue(gray(l.pixels[250, 50]) <= 3)
        val (ed2, l2) = doc()
        ed2.fillGradient(PaintTarget.PIXELS, Gradient(GradientType.REFLECTED, 150f, 0f, 250f, 0f, -1, 0xFF000000.toInt()), 1f)
        assertTrue("gespiegelt: links wie rechts", kotlin.math.abs(gray(l2.pixels[100, 10]) - gray(l2.pixels[199, 10])) <= 3)
    }

    @Test
    fun selectionAndUndo() {
        val (ed, l) = doc()
        val before = IntArray(w * h); l.pixels.readRect(IntRect(0, 0, w, h), before)
        ed.doc.selection = Selection.rect(w, h, 0f, 0f, 100f, h.toFloat())
        ed.fillGradient(PaintTarget.PIXELS, Gradient(GradientType.LINEAR, 0f, 0f, w.toFloat(), 0f, 0xFFFF0000.toInt(), 0xFF0000FF.toInt()), 1f)
        assertEquals("außerhalb der Auswahl unverändert", 0xFF808080.toInt(), l.pixels[200, 50])
        assertTrue("innen rötlich", ((l.pixels[20, 50] shr 16) and 0xFF) > 200)
        ed.undo()
        val back = IntArray(w * h); l.pixels.readRect(IntRect(0, 0, w, h), back)
        assertArrayEquals(before, back)
    }

    @Test
    fun maskGradientAndTransparentEnd() {
        val (ed, l) = doc()
        l.mask = LayerMask(TileGrid(w, h, 255), enabled = true)
        ed.fillGradient(PaintTarget.MASK, Gradient(GradientType.LINEAR, 0f, 0f, w.toFloat(), 0f, -1, 0xFF000000.toInt()), 1f)
        assertTrue(l.mask!!.grid[2, 50] >= 250)
        assertTrue(l.mask!!.grid[w - 2, 50] <= 5)
        // Vordergrund → transparent auf leerer Ebene: Farbe bleibt rot, nur die Deckung fällt
        val (ed2, l2) = doc(0)
        ed2.fillGradient(PaintTarget.PIXELS, Gradient(GradientType.LINEAR, 0f, 0f, w.toFloat(), 0f, 0xFFFF0000.toInt(), 0x00FF0000), 1f)
        val c = l2.pixels[200, 50]
        assertTrue("Deckung fällt: ${c ushr 24}", (c ushr 24) in 70..110)
        assertEquals("kein dunkler Saum", 0xFF0000, c and 0xFFFFFF)
    }
}
