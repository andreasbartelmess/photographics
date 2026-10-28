package de.photographics.core

import de.photographics.core.adjust.AdjustmentType
import de.photographics.core.adjust.BlackWhiteAdj
import de.photographics.core.adjust.BrightnessContrastAdj
import de.photographics.core.adjust.ColorBalanceAdj
import de.photographics.core.adjust.CurvesAdj
import de.photographics.core.adjust.ExposureAdj
import de.photographics.core.adjust.HueSaturationAdj
import de.photographics.core.adjust.InvertAdj
import de.photographics.core.adjust.LevelsAdj
import de.photographics.core.adjust.PosterizeAdj
import de.photographics.core.adjust.ThresholdAdj
import de.photographics.core.adjust.VibranceAdj
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.SmartLayer
import de.photographics.core.doc.TextLayer
import de.photographics.core.doc.TextRasterizer
import de.photographics.core.doc.TextStyle
import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.image.Resample
import de.photographics.core.image.ResampleMethod
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.Compositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedPsdTest {

    /** Einfacher Rasterer für Tests: ein Rechteck in der Textfarbe. */
    private object BoxRasterizer : TextRasterizer {
        override fun measure(style: TextStyle) = style.sizePx * style.text.length * 0.5f to style.sizePx
        override fun render(style: TextStyle, transform: Affine, docWidth: Int, docHeight: Int): TileGrid {
            val g = TileGrid(docWidth, docHeight)
            val (w, h) = measure(style)
            val r = Affine.clampRect(transform.bounds(w.toDouble(), h.toDouble()), docWidth, docHeight)
            for (y in r.top until r.bottom) for (x in r.left until r.right) g[x, y] = style.color
            return g
        }
    }

    @Test
    fun adjustmentLayersRoundTrip() {
        val samples = listOf(
            BrightnessContrastAdj(40, -20), LevelsAdj(10, 240, 1.3f, 5, 250), CurvesAdj(listOf(0 to 0, 90 to 120, 255 to 255)),
            ExposureAdj(0.5f, 0.01f, 1.1f), VibranceAdj(30, -10), HueSaturationAdj(25, 30, -10),
            ColorBalanceAdj(listOf(10, 0, -5), listOf(20, -10, 5), listOf(0, 0, 15)), BlackWhiteAdj(red = 50, tint = true),
            InvertAdj, PosterizeAdj(6), ThresholdAdj(100),
        )
        for (adj in samples) {
            val doc = Document.blank(64, 48, background = 0xFF4080C0.toInt())
            doc.root.add(AdjustmentLayer(adj.label, adj))
            val back = PsdReader.read(PsdWriter.write(doc))
            val layer = back.root.children[1]
            assertTrue("${adj.label} als Einstellungsebene gelesen", layer is AdjustmentLayer)
            layer as AdjustmentLayer
            assertEquals(adj.type, layer.adjustment.type)
            if (adj.type != AdjustmentType.BLACK_WHITE) assertEquals(adj.label, adj, layer.adjustment)
            // Darstellung identisch
            val a = Compositor.renderArgb(doc, doc.bounds)[100]
            val b = Compositor.renderArgb(back, back.bounds)[100]
            assertTrue("${adj.label}: ${Integer.toHexString(a)} vs ${Integer.toHexString(b)}", channelDiff(a, b) <= 3)
        }
    }

    @Test
    fun invertAdjustmentChangesComposite() {
        val doc = Document.blank(4, 4, background = 0xFF102030.toInt())
        doc.root.add(AdjustmentLayer("Umkehren", InvertAdj))
        assertEquals(0xFFEFDFCF.toInt(), Compositor.sample(doc, 1, 1))
    }

    @Test
    fun textLayerRoundTrip() {
        TextRasterizer.current = BoxRasterizer
        val doc = Document.blank(200, 100)
        val style = TextStyle("Hallo Welt\nZeile 2", "serif", 20f, 0xFFFF0000.toInt(), bold = true)
        doc.root.add(TextLayer("Hallo", style, Affine.translate(10.0, 20.0), 200, 100))
        val back = PsdReader.read(PsdWriter.write(doc))
        val t = back.root.children[1] as TextLayer
        assertEquals(style, t.style)
        assertEquals(10.0, t.transform.tx, 1e-9)
        assertEquals(0xFFFF0000.toInt(), Compositor.sample(back, 15, 25))
    }

    @Test
    fun smartObjectKeepsFullResolutionSource() {
        val doc = Document.blank(100, 100)
        val src = TileGrid(80, 80).also { g -> for (y in 0 until 80) for (x in 0 until 80) g[x, y] = if ((x / 10 + y / 10) % 2 == 0) 0xFF000000.toInt() else -1 }
        // Stark verkleinert platziert – das Original muss trotzdem vollständig erhalten bleiben
        doc.root.add(SmartLayer("Smart", src, Affine.scale(0.25).then(Affine.translate(5.0, 5.0)), 100, 100))
        val back = PsdReader.read(PsdWriter.write(doc))
        val s = back.root.children[1] as SmartLayer
        assertEquals(80, s.source.width)
        assertEquals(src[15, 5], s.source[15, 5])
        // Zurück auf volle Größe: scharfes Schachbrett ohne Qualitätsverlust
        s.transform = Affine.IDENTITY
        assertEquals(0xFF000000.toInt(), s.raster[5, 5])
        assertEquals(-1, s.raster[15, 5])
    }

    @Test
    fun lanczosPreservesFlatColorsAndSize() {
        val px = IntArray(50 * 40) { 0xFF336699.toInt() }
        for (m in ResampleMethod.entries) {
            val out = Resample.resizeArgb(px, 50, 40, 123, 77, m)
            assertEquals(123 * 77, out.size)
            assertTrue(m.name, channelDiff(out[500], 0xFF336699.toInt()) <= 1)
        }
    }

    @Test
    fun curvesSplineIsMonotoneAndHitsPoints() {
        val pts = listOf(0 to 0, 64 to 40, 192 to 220, 255 to 255)
        val t = CurvesAdj.table(pts)
        assertEquals(40, t[64]); assertEquals(220, t[192])
        for (i in 1..255) assertTrue(t[i] >= t[i - 1])
    }

    private fun channelDiff(a: Int, b: Int) = intArrayOf(0, 8, 16, 24).maxOf { s -> kotlin.math.abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)) }

    @Suppress("unused") private val unused = IntRect.EMPTY
}
