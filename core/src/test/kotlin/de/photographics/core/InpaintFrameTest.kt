package de.photographics.core

import de.photographics.core.retouch.InpaintFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vorbereitung für Inpainting-Modelle: keine Verzerrung, Rückweg exakt, Lücke vollständig erfasst. */
class InpaintFrameTest {
    private fun image(w: Int, h: Int) = IntArray(w * h) { i -> (0xFF shl 24) or ((i % w * 7 and 0xFF) shl 16) or ((i / w * 5 and 0xFF) shl 8) or 0x40 }

    @Test fun dynamicSmallCropIsOnlyPaddedAndComesBackExactly() {
        val w = 301; val h = 157
        val px = image(w, h)
        val hole = BooleanArray(w * h) { i -> (i % w) in 100..180 && (i / w) in 40..90 }
        val f = InpaintFrame.create(px, w, h, hole, fixed = null, maxSide = 1024)
        assertEquals(304, f.tensorW); assertEquals(160, f.tensorH)
        assertEquals(w, f.contentW); assertEquals(h, f.contentH)
        // Inhalt unverändert oben links, Rand gespiegelt
        for (y in 0 until h) for (x in 0 until w) assertEquals(px[y * w + x], f.image[y * f.tensorW + x])
        assertEquals(px[(h - 2) * w + 5], f.image[h * f.tensorW + 5])
        assertEquals(px[3 * w + (w - 2)], f.image[3 * f.tensorW + w])
        // Rückweg: Identität
        assertArrayEquals(px, f.back(f.image))
        // Lücke (erweitert) enthalten, Auffüllung ist keine Lücke
        for (i in hole.indices) if (hole[i]) assertTrue(f.mask[(i / w) * f.tensorW + i % w])
        for (x in w until f.tensorW) assertTrue(!f.mask[50 * f.tensorW + x])
    }

    @Test fun dynamicLargeCropKeepsAspect() {
        val w = 2000; val h = 900
        val f = InpaintFrame.create(image(w, h), w, h, BooleanArray(w * h) { it % w in 900..1100 && it / w in 300..600 }, maxSide = 1024)
        assertEquals(1024, f.contentW); assertEquals(461, f.contentH)
        assertEquals(464, f.tensorH)
        assertEquals(w * h, f.back(f.image).size)
    }

    @Test fun fixedSizeIsPaddedToSquareNotStretched() {
        val w = 400; val h = 100
        val px = image(w, h)
        val hole = BooleanArray(w * h) { i -> (i % w) in 190..210 && (i / w) in 40..60 }
        val f = InpaintFrame.create(px, w, h, hole, fixed = 512)
        assertEquals(512, f.tensorW); assertEquals(512, f.tensorH)
        assertEquals(512, f.contentW); assertEquals(128, f.contentH)
        // Ein runder Fleck bleibt rund: Lücke in Arbeitsgröße etwa gleich breit wie hoch (21 px · 1,28 + Erweiterung)
        var minX = 512; var maxX = 0; var minY = 512; var maxY = 0
        for (i in f.mask.indices) if (f.mask[i]) { val x = i % 512; val y = i / 512; minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        assertTrue("Breite ${maxX - minX} Höhe ${maxY - minY}", kotlin.math.abs((maxX - minX) - (maxY - minY)) <= 2)
        val back = f.back(f.image)
        // Rückweg ohne Verschiebung: mittlere Abweichung klein (nur Skalierung)
        var d = 0L
        for (i in px.indices) d += kotlin.math.abs(((back[i] shr 16) and 0xFF) - ((px[i] shr 16) and 0xFF))
        assertTrue("mittlere Abweichung ${d.toDouble() / px.size}", d.toDouble() / px.size < 6.0)
    }

    @Test fun mirrorPaddingLargerThanImage() {
        val px = intArrayOf(1, 2, 3)
        assertArrayEquals(intArrayOf(1, 2, 3, 2, 1, 2, 3, 2), InpaintFrame.padReflect(px, 3, 1, 8, 1))
    }
}
