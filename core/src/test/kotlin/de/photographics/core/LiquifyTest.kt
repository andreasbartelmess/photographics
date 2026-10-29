package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.TileGrid
import de.photographics.core.selection.Selection
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.LiquifyMode
import de.photographics.core.tools.LiquifyTool
import de.photographics.core.tools.PaintTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquifyTest {
    private val w = 200; private val h = 120
    private val red = 0xFFFF0000.toInt(); private val white = -1

    /** Weiß mit senkrechtem rotem Balken x = 90..110. */
    private fun bar(): Pair<Editor, PixelLayer> {
        val doc = Document(w, h)
        val l = PixelLayer("Balken", TileGrid.fromPixels(w, h, IntArray(w * h) { i -> if (i % w in 90 until 110) red else white }))
        doc.root.add(l); doc.activeLayer = l
        return Editor(doc) to l
    }

    /** Weiß mit rotem Punkt (Radius 10) in der Mitte. */
    private fun dot(): Pair<Editor, PixelLayer> {
        val doc = Document(w, h)
        val l = PixelLayer("Punkt", TileGrid.fromPixels(w, h, IntArray(w * h) { i -> val x = i % w - 100; val y = i / w - 60; if (x * x + y * y <= 100) red else white }))
        doc.root.add(l); doc.activeLayer = l
        return Editor(doc) to l
    }

    private fun stroke(ed: Editor, mode: LiquifyMode, pts: List<Pair<Float, Float>>, size: Float = 60f) {
        val st = ed.beginStroke(LiquifyTool(mode, 1f), BrushSettings(size = size, hardness = 0.3f, spacing = 0.1f, smoothing = 0f), PaintTarget.PIXELS)!!
        for ((x, y) in pts) st.addPoint(x, y)
        ed.endStroke(st)
    }

    private fun redCount(l: PixelLayer): Int {
        val px = IntArray(w * h); l.pixels.readRect(de.photographics.core.image.IntRect(0, 0, w, h), px)
        return px.count { ((it shr 16) and 0xFF) > 200 && ((it shr 8) and 0xFF) < 80 }
    }

    private fun isRed(c: Int) = ((c shr 16) and 0xFF) > 200 && ((c shr 8) and 0xFF) < 100

    @Test
    fun pushMovesContentAlongTheStroke() {
        val (ed, l) = bar()
        stroke(ed, LiquifyMode.PUSH, (0..20).map { 95f + it * 1.5f to 60f })
        // Wie „Vorwärts verkrümmen“: der Balken wandert an der Strichhöhe mit dem Pinsel nach rechts
        assertTrue("Balken an der alten Stelle weg", !isRed(l.pixels[100, 60]))
        assertTrue("Balken weiter rechts", (115..135).any { isRed(l.pixels[it, 60]) })
        assertEquals("oben unverändert", white, l.pixels[125, 5])
        assertEquals("Balken oben unverändert", red, l.pixels[100, 5])
    }

    @Test
    fun bloatGrowsAndPuckerShrinks() {
        val (ed, l) = dot()
        val before = redCount(l)
        stroke(ed, LiquifyMode.BLOAT, List(12) { 100f to 60f }, size = 50f)
        val grown = redCount(l)
        assertTrue("aufgeblasen: $before → $grown", grown > before * 1.15)
        val (ed2, l2) = dot()
        stroke(ed2, LiquifyMode.PUCKER, List(12) { 100f to 60f }, size = 50f)
        val shrunk = redCount(l2)
        assertTrue("zusammengezogen: $before → $shrunk", shrunk < before * 0.85)
    }

    @Test
    fun undoRestoresExactly() {
        val (ed, l) = bar()
        val px = IntArray(w * h); l.pixels.readRect(de.photographics.core.image.IntRect(0, 0, w, h), px)
        stroke(ed, LiquifyMode.TWIRL_CW, List(10) { 100f to 60f }, size = 80f)
        val after = IntArray(w * h); l.pixels.readRect(de.photographics.core.image.IntRect(0, 0, w, h), after)
        assertTrue("Strudel hat etwas verändert", !px.contentEquals(after))
        ed.undo()
        val back = IntArray(w * h); l.pixels.readRect(de.photographics.core.image.IntRect(0, 0, w, h), back)
        assertArrayEquals(px, back)
    }

    @Test
    fun selectionLimitsTheEffect() {
        val (ed, l) = bar()
        ed.doc.selection = Selection.rect(w, h, 0f, 0f, 100f, h.toFloat())   // nur die linke Hälfte
        stroke(ed, LiquifyMode.PUSH, (0..20).map { 105f - it * 1.5f to 60f })
        assertEquals("rechts der Auswahl unverändert", red, l.pixels[105, 60])
        assertTrue("links der Auswahlgrenze nach links verschoben", (55..85).any { isRed(l.pixels[it, 60]) })
    }
}
