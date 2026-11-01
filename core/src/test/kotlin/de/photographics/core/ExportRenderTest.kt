package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.ExportRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRenderTest {
    private fun doc(): Document {
        val d = Document(400, 300)
        val g = TileGrid(400, 300)
        // Linke Hälfte rot deckend, rechte Hälfte transparent
        g.writeRect(IntRect(0, 0, 200, 300), IntArray(200 * 300) { 0xFFFF0000.toInt() })
        d.root.add(PixelLayer("A", g))
        return d
    }

    private fun collect(d: Document, w: Int, h: Int, white: Boolean): IntArray {
        val out = IntArray(w * h)
        var rows = 0
        ExportRender.bands(d, w, h, toSrgb = true, onWhite = white) { r, px ->
            for (y in r.top until r.bottom) System.arraycopy(px, (y - r.top) * w, out, y * w, w)
            rows += r.height
        }
        assertEquals(h, rows)
        return out
    }

    @Test
    fun fullSizeAndScaled() {
        val full = collect(doc(), 400, 300, white = false)
        assertEquals(0xFFFF0000.toInt(), full[10 * 400 + 10]); assertEquals(0, full[10 * 400 + 390] ushr 24)
        // Halbe Größe, auf Weiß: links rot, rechts weiß, keine Transparenz
        val half = collect(doc(), 200, 150, white = true)
        assertEquals(0xFFFF0000.toInt(), half[75 * 200 + 20])
        assertEquals(0xFFFFFFFF.toInt(), half[75 * 200 + 180])
        assertTrue(half.all { (it ushr 24) == 0xFF })
    }
}
