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

    /** Bitweise Füllung = einfache 4er-Nachbarschaftsfüllung, auf krummen Breiten über Wortgrenzen. */
    @Test
    fun bitFillMatchesNaiveFloodFill() {
        val rnd = java.util.Random(7)
        for ((w, h) in listOf(1 to 1, 63 to 5, 64 to 64, 65 to 70, 130 to 97, 301 to 260)) {
            val px = IntArray(w * h) { if (rnd.nextInt(100) < 38) red else blue }
            val g = TileGrid.fromPixels(w, h, px)
            val sx = rnd.nextInt(w); val sy = rnd.nextInt(h)
            val want = BooleanArray(w * h)
            val st = ArrayDeque<Int>(); st.add(sy * w + sx); want[sy * w + sx] = true
            while (st.isNotEmpty()) {
                val i = st.removeLast(); val x = i % w; val y = i / w
                for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (!want[j] && px[j] == px[i]) { want[j] = true; st.add(j) }
                }
            }
            val sel = MagicWand.select(g, sx, sy, 0, contiguous = true)
            val all = MagicWand.select(g, sx, sy, 0, contiguous = false)
            for (i in 0 until w * h) {
                assertEquals("$w×$h bei ${i % w},${i / w}", if (want[i]) 255 else 0, sel.at(i % w, i / w))
                assertEquals(if (px[i] == px[sy * w + sx]) 255 else 0, all.at(i % w, i / w))
            }
        }
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

    @Test fun bucketFillLikePhotoshop() {
        // Photoshop 2026 nachgemessen: gleiche Farbe mit 100/50/10 % Deckkraft gilt als gleich (auch bei Toleranz 0),
        // Transparentes nur zu Transparentem; gefüllte Pixel werden voll deckend, Glätten schwächt die Fläche nicht ab
        val w = 300; val h = 60
        val red = 0xC82828
        val px = IntArray(w * h) { i -> val x = i % w
            when { x < 60 -> 0xFF000000.toInt() or red; x < 120 -> (128 shl 24) or red; x < 180 -> (26 shl 24) or red; x < 240 -> 0xFF28C828.toInt(); else -> 0 } }
        for (aa in listOf(false, true)) {
            val doc = de.photographics.core.doc.Document(w, h)
            val l = de.photographics.core.doc.PixelLayer("e", de.photographics.core.image.TileGrid.fromPixels(w, h, px.copyOf()))
            doc.root.add(l); doc.activeLayer = l
            de.photographics.core.editor.Editor(doc).bucketFill(30, 30, 0xFF1414C8.toInt(), 0, contiguous = true, allLayers = false, antiAlias = aa)
            for (x in listOf(5, 30, 59, 60, 90, 119, 120, 150, 178)) assertEquals("x=$x aa=$aa", 0xFF1414C8.toInt(), l.pixels[x, 30])
            assertEquals(0xFF28C828.toInt(), l.pixels[210, 30])
            assertEquals(0, l.pixels[270, 30] ushr 24)
        }
        // Start im Transparenten: Pixel mit 10 % Deckkraft gehören nicht dazu
        val doc = de.photographics.core.doc.Document(w, h)
        val l = de.photographics.core.doc.PixelLayer("e", de.photographics.core.image.TileGrid.fromPixels(w, h, px.copyOf()))
        doc.root.add(l); doc.activeLayer = l
        de.photographics.core.editor.Editor(doc).bucketFill(270, 30, 0xFF1414C8.toInt(), 32, contiguous = true, allLayers = false, antiAlias = false)
        assertEquals(0xFF1414C8.toInt(), l.pixels[270, 30])
        assertEquals((26 shl 24) or red, l.pixels[150, 30])
    }
}

