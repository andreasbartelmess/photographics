package de.photographics.core

import de.photographics.core.image.TileGrid
import de.photographics.core.selection.MagicWand
import de.photographics.core.selection.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicWandTest {
    private val red = 0xFFFF0000.toInt(); private val blue = 0xFF0000FF.toInt()

    /** Links rot, rechts blau; im Blau eine rote Insel 140..160 × 40..60. */
    private fun img(): TileGrid {
        val w = 200; val h = 100
        return TileGrid.fromPixels(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x < 100 || (x in 140 until 160 && y in 40 until 60)) red else blue
        })
    }

    @Test
    fun contiguousStopsAtBorders() {
        val sel = MagicWand.select(img(), 10, 10, 10, contiguous = true)
        assertEquals(255, sel.at(99, 50)); assertEquals(0, sel.at(100, 50)); assertEquals(0, sel.at(150, 50))
        assertEquals(1, sel.outlines.size)
    }

    @Test
    fun nonContiguousFindsIslands() {
        val sel = MagicWand.select(img(), 10, 10, 10, contiguous = false)
        assertEquals(255, sel.at(150, 50)); assertEquals(0, sel.at(120, 50))
        assertEquals(2, sel.outlines.size)
    }

    @Test
    fun toleranceOnGradient() {
        val w = 256; val h = 10
        val g = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> val v = i % w; (0xFF shl 24) or (v shl 16) or (v shl 8) or v })
        val sel = MagicWand.select(g, 100, 5, 20, contiguous = true)
        assertEquals(255, sel.at(80, 5)); assertEquals(255, sel.at(120, 5))
        assertEquals(0, sel.at(79, 5)); assertEquals(0, sel.at(121, 5))
    }

    @Test
    fun outlinesOfRectangleAndRing() {
        val w = 50; val h = 40
        val rect = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> if (i % w in 10 until 30 && i / w in 5 until 25) 255 else 0 })
        val o = Selection.outlinesOf(rect)
        assertEquals(1, o.size)
        assertEquals("vier Ecken", 8, o[0].size)
        val xs = o[0].filterIndexed { k, _ -> k % 2 == 0 }; val ys = o[0].filterIndexed { k, _ -> k % 2 == 1 }
        assertEquals(10f, xs.min()); assertEquals(30f, xs.max()); assertEquals(5f, ys.min()); assertEquals(25f, ys.max())
        val ring = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (x in 5 until 45 && y in 5 until 35 && !(x in 15 until 35 && y in 15 until 25)) 255 else 0 })
        assertEquals("außen und innen", 2, Selection.outlinesOf(ring).size)
        // Diagonal berührende Pixel bleiben zwei getrennte Umrisse
        val diag = TileGrid.fromPixels(4, 4, IntArray(16) { i -> if (i == 5 || i == 10) 255 else 0 })
        assertEquals(2, Selection.outlinesOf(diag).size)
        assertTrue(Selection.outlinesOf(TileGrid(10, 10, 0)).isEmpty())
    }
}
