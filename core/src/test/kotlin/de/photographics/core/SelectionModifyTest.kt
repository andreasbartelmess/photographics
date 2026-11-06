package de.photographics.core

import de.photographics.core.image.TileGrid
import de.photographics.core.selection.ColorRange
import de.photographics.core.selection.Selection
import de.photographics.core.selection.SelectionModify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionModifyTest {
    private val w = 600; private val h = 400

    private fun rect() = Selection.rect(w, h, 200f, 100f, 400f, 300f)

    @Test
    fun distanceTransformIsExactEuclidean() {
        val n = 37; val m = 23
        val src = setOf(5 * n + 7, 18 * n + 30, 0, 22 * n + 1)
        val d = SelectionModify.distance(n, m) { it in src }
        for (i in 0 until n * m) {
            val x = i % n; val y = i / n
            val best = src.minOf { s -> val dx = (s % n - x).toFloat(); val dy = (s / n - y).toFloat(); dx * dx + dy * dy }
            assertEquals("Pixel $x,$y", best, d[i], 1e-3f)
        }
        val near = IntArray(n * m)
        SelectionModify.distance(n, m, near) { it in src }
        for (i in 0 until n * m) {
            val q = near[i]
            assertTrue(q in src)
            val dx = (q % n - i % n).toFloat(); val dy = (q / n - i / n).toFloat()
            assertEquals(d[i], dx * dx + dy * dy, 1e-3f)
        }
    }

    @Test
    fun expandGrowsWithRoundCorners() {
        val s = SelectionModify.expand(rect(), 20)
        assertEquals(255, s.at(185, 200)); assertEquals(0, s.at(178, 200))
        assertEquals(255, s.at(300, 81)); assertEquals(0, s.at(300, 77))
        // Ecke rund: diagonal 17 px von der Ecke (200,100) noch drin, 24 px nicht mehr
        assertEquals(255, s.at(188, 88))
        assertEquals(0, s.at(183, 83))
    }

    @Test
    fun contractShrinksAndRespectsCanvasBounds() {
        val s = SelectionModify.contract(rect(), 10)
        assertEquals(0, s.at(205, 200)); assertEquals(255, s.at(212, 200))
        assertEquals(0, s.at(395, 200)); assertEquals(255, s.at(388, 200))
        val all = Selection.all(w, h)
        assertEquals(255, SelectionModify.contract(all, 10).at(2, 2))
        val edge = SelectionModify.contract(all, 10, atCanvasBounds = true)
        assertEquals(0, edge.at(2, 2)); assertEquals(255, edge.at(300, 200))
    }

    @Test
    fun smoothRemovesSmallSpecks() {
        val speck = Selection.rect(w, h, 50f, 50f, 53f, 53f)
        val sel = rect().combine(speck, de.photographics.core.selection.SelectionOp.ADD)
        val s = SelectionModify.smooth(sel, 8)
        assertEquals(0, s.at(51, 51))
        assertEquals(255, s.at(300, 200))
    }

    @Test
    fun borderIsBandAroundEdge() {
        val s = SelectionModify.border(rect(), 20)
        assertTrue(s.at(200, 200) > 200)
        assertEquals(0, s.at(300, 200)); assertEquals(0, s.at(150, 200))
    }

    @Test
    fun colorRangeSampledAndPresets() {
        val red = 0xFFE01010.toInt(); val blue = 0xFF1020E0.toInt(); val grey = 0xFF808080.toInt()
        val img = TileGrid.fromPixels(30, 10, IntArray(300) { i -> when ((i % 30) / 10) { 0 -> red; 1 -> blue; else -> grey } })
        val s = ColorRange.select(img, ColorRange.Spec(samples = listOf(ColorRange.Sample(0xFFD82020.toInt()))))
        assertTrue(s.at(5, 5) > 150); assertEquals(255, ColorRange.select(img, ColorRange.Spec(samples = listOf(ColorRange.Sample(red)))).at(5, 5)); assertEquals(0, s.at(15, 5)); assertEquals(0, s.at(25, 5))
        val minus = ColorRange.select(img, ColorRange.Spec(samples = listOf(ColorRange.Sample(red), ColorRange.Sample(red, subtract = true))))
        assertEquals(0, minus.at(5, 5))
        val blues = ColorRange.select(img, ColorRange.Spec(ColorRange.Preset.BLUES))
        assertEquals(255, blues.at(15, 5)); assertEquals(0, blues.at(5, 5)); assertEquals(0, blues.at(25, 5))
        val mid = ColorRange.select(img, ColorRange.Spec(ColorRange.Preset.MIDTONES, invert = true))
        assertEquals(0, mid.at(25, 5))
    }

    @Test
    fun largeRadiusAcrossTilesMatchesGeometry() {
        // Kleiner Fleck um (600, 450); nach Erweitern um 300 ein Kreis mit Radius ≈ 300 über viele Kacheln
        val big = Selection.rect(1200, 900, 599f, 449f, 601f, 451f)
        val e = SelectionModify.expand(big, 300)
        for (deg in 0 until 360 step 7) {
            val a = Math.toRadians(deg.toDouble())
            fun at(r: Double) = e.at((600 + r * kotlin.math.cos(a)).toInt().coerceIn(0, 1199), (450 + r * kotlin.math.sin(a)).toInt().coerceIn(0, 899))
            assertEquals("innen $deg°", 255, at(295.0)); assertEquals("außen $deg°", 0, at(306.0))
        }
        val c = SelectionModify.contract(e, 200)
        assertEquals(255, c.at(600, 450)); assertEquals(255, c.at(690, 450)); assertEquals(0, c.at(710, 450))
        // Abrunden mit großem Radius (verkleinertes Raster): Rechteck bleibt, Ecken runden sich
        val r = SelectionModify.smooth(Selection.rect(1200, 900, 200f, 200f, 1000f, 700f), 120)
        assertEquals(255, r.at(600, 450)); assertEquals(0, r.at(203, 203)); assertEquals(0, r.at(100, 450))
        assertTrue(r.at(205, 450) > 200)
    }

    @Test
    fun edgeCases() {
        // Umgekehrte Auswahl (ungespeicherte Kacheln = 255): Loch wird beim Erweitern kleiner, beim Verkleinern größer
        val inv = rect().inverted()
        val e = SelectionModify.expand(inv, 10)
        assertEquals(0, e.at(300, 200)); assertEquals(255, e.at(205, 200)); assertEquals(255, e.at(5, 5))
        val c = SelectionModify.contract(inv, 10)
        assertEquals(0, c.at(195, 200)); assertEquals(255, c.at(185, 200)); assertEquals(255, c.at(0, 0))
        assertEquals(0, SelectionModify.contract(inv, 10, atCanvasBounds = true).at(0, 0))
        // Auswahl am Rand: Erweitern bleibt in der Arbeitsfläche, kein Absturz
        val edge = Selection.rect(w, h, 0f, 0f, 50f, 50f)
        assertEquals(255, SelectionModify.expand(edge, 30).at(70, 0))
        // Weiche Auswahl: weicher Rand außerhalb bleibt beim Erweitern erhalten, innen wird voll
        val soft = rect().feathered(6f)
        val es = SelectionModify.expand(soft, 5)
        assertEquals(255, es.at(300, 200))
        // Der ganze Verlauf wandert um 5 px nach außen (Stufen ±1/16)
        for (x in 180..215) assertEquals("x=$x", soft.at(x + 5, 200).toFloat(), es.at(x, 200).toFloat(), 18f)
        val cs = SelectionModify.contract(soft, 5)
        for (x in 185..215) assertEquals("x=$x", soft.at(x - 5, 200).toFloat(), cs.at(x, 200).toFloat(), 18f)
        // Leere Auswahl und Radius 0 verändern nichts
        val r = rect()
        assertTrue(SelectionModify.expand(r, 0) === r)
        assertTrue(SelectionModify.border(r, 1).at(200, 200) > 0)
        // Verkleinern über die ganze Auswahl hinaus: leer
        assertTrue(SelectionModify.contract(r, 150).isEmpty)
    }
}
