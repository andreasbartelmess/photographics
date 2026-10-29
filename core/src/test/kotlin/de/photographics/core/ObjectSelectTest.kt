package de.photographics.core

import de.photographics.core.image.IntRect
import de.photographics.core.selection.ObjectSelect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ObjectSelectTest {
    /** Verrauschter grüner Hintergrund mit einer roten Scheibe (Mitte 200/150, Radius 70). */
    private val w = 400; private val h = 300
    private val px = Random(3).let { rnd ->
        IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val inDisc = (x - 200) * (x - 200) + (y - 150) * (y - 150) < 70 * 70
            val n = rnd.nextInt(-20, 21)
            if (inDisc) (0xFF shl 24) or ((200 + n).coerceIn(0, 255) shl 16) or ((40 + n / 2) shl 8) or 40
            else (0xFF shl 24) or ((50 + n / 2) shl 16) or ((150 + n).coerceIn(0, 255) shl 8) or 60
        }
    }

    private fun sample(r: IntRect, step: Int) = IntArray(r.width * r.height) { i ->
        val x = minOf(w - 1, (r.left + i % r.width) * step + step / 2); val y = minOf(h - 1, (r.top + i / r.width) * step + step / 2)
        px[y * w + x]
    }

    @Test
    fun separatesDiscFromBackground() {
        val sel = ObjectSelect.select(w, h, IntRect(110, 60, 290, 240), ::sample)
        assertEquals("Mitte gehört zum Objekt", 255, sel.at(200, 150))
        assertEquals("Rand innerhalb der Scheibe", 255, sel.at(200, 90))
        assertEquals("Ecke des Rechtecks ist Hintergrund", 0, sel.at(115, 65))
        assertEquals("außerhalb des Rechtecks", 0, sel.at(20, 20))
        // Fläche ungefähr die der Scheibe
        var area = 0L
        for (y in 0 until h) for (x in 0 until w) area += sel.at(x, y)
        val disc = Math.PI * 70 * 70
        assertTrue("Fläche ${area / 255} statt ~${disc.toInt()}", kotlin.math.abs(area / 255.0 - disc) < disc * 0.08)
        assertTrue(sel.outlines.isNotEmpty())
    }

    @Test
    fun tinyRectGivesEmptySelection() {
        val sel = ObjectSelect.select(w, h, IntRect(10, 10, 12, 12), ::sample)
        assertTrue(sel.bounds.isEmpty)
    }
}
