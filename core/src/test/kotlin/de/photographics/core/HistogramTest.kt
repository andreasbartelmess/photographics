package de.photographics.core

import de.photographics.core.adjust.CurvesAdj
import de.photographics.core.adjust.Histogram
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.Compositor
import org.junit.Assert.assertEquals
import org.junit.Test

class HistogramTest {
    @Test
    fun countsImageBelowAdjustment() {
        val doc = Document.blank(100, 50, background = 0xFF204080.toInt())
        val curves = AdjustmentLayer("Kurven", CurvesAdj(master = listOf(0 to 255, 255 to 0)))
        doc.root.add(curves)
        val h = Histogram.below(doc, curves)
        // Eingang der Kurve, nicht ihr (umgekehrtes) Ergebnis
        assertEquals(5000, h[Histogram.RED * 256 + 0x20])
        assertEquals(5000, h[Histogram.GREEN * 256 + 0x40])
        assertEquals(5000, h[Histogram.BLUE * 256 + 0x80])
        assertEquals(5000, h.sliceArray(0 until 256).sum())
        val d = Histogram.display(h, Histogram.RED)
        assertEquals(1f, d[0x20]); assertEquals(0f, d[0x21])
    }

    @Test
    fun channelCurvesApplyAndRoundTrip() {
        val doc = Document.blank(8, 8, background = 0xFF808080.toInt())
        val adj = CurvesAdj(red = listOf(0 to 0, 128 to 200, 255 to 255), blue = listOf(0 to 0, 128 to 60, 255 to 255))
        doc.root.add(AdjustmentLayer("Kurven", adj))
        assertEquals(0xFFC8803C.toInt(), Compositor.sample(doc, 2, 2))
        val back = PsdReader.read(PsdWriter.write(doc))
        assertEquals(adj, (back.root.children[1] as AdjustmentLayer).adjustment)
    }
}
