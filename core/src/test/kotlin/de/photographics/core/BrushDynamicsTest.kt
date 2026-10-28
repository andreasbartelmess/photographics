package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.tools.AngleControl
import de.photographics.core.tools.BrushMode
import de.photographics.core.tools.BrushPresets
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.BrushTool
import de.photographics.core.tools.DynControl
import de.photographics.core.tools.PaintTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushDynamicsTest {
    private val W = 200; private val H = 120

    private fun paint(settings: BrushSettings, base: Int = 0, color: Int = 0xFF000000.toInt(), bg: Int = color,
                      points: List<Triple<Float, Float, Float>> = line(), airbrushTicks: Int = 0): IntArray {
        val doc = Document(W, H)
        val layer = PixelLayer("E", TileGrid.fromPixels(W, H, IntArray(W * H) { base }))
        doc.root.add(layer); doc.activeLayer = layer
        val ed = Editor(doc)
        val st = ed.beginStroke(BrushTool(color, bg), settings, PaintTarget.PIXELS)!!
        for ((x, y, p) in points) st.addPoint(x, y, p)
        repeat(airbrushTicks) { st.airbrushTick() }
        ed.endStroke(st)
        return IntArray(W * H).also { layer.pixels.readRect(IntRect.ofSize(W, H), it) }
    }

    private fun line(p: Float = 1f) = (0..60).map { Triple(30f + it * 2.3f, 60f, p) }
    private fun alpha(px: IntArray, x: Int, y: Int) = px[y * W + x] ushr 24

    @Test
    fun opacityByPressureCapsBuildUp() {
        // Viele überlappende Abdrücke mit vollem Fluss: ohne Steuerung voll deckend, mit 30 % Druck höchstens ~30 %
        val full = paint(BrushSettings(size = 30f, hardness = 1f, sizeControl = DynControl.OFF), points = line(0.3f))
        val capped = paint(BrushSettings(size = 30f, hardness = 1f, sizeControl = DynControl.OFF, opacityControl = DynControl.PRESSURE), points = line(0.3f))
        assertEquals(255, alpha(full, 100, 60))
        assertTrue("${alpha(capped, 100, 60)}", alpha(capped, 100, 60) in 70..80)
    }

    @Test
    fun ellipticTipFollowsAngle() {
        val dot = listOf(Triple(100f, 60f, 1f))
        val flat = paint(BrushSettings(size = 60f, hardness = 1f, roundness = 0.2f, angle = 0f, sizeControl = DynControl.OFF), points = dot)
        val tall = paint(BrushSettings(size = 60f, hardness = 1f, roundness = 0.2f, angle = 90f, sizeControl = DynControl.OFF), points = dot)
        assertTrue(alpha(flat, 125, 60) > 200 && alpha(flat, 100, 85) == 0)
        assertTrue(alpha(tall, 100, 85) > 200 && alpha(tall, 125, 60) == 0)
    }

    @Test
    fun scatterSpreadsDabs() {
        val s = BrushSettings(size = 8f, hardness = 1f, spacing = 1f, scatter = 4f, scatterBothAxes = true, sizeControl = DynControl.OFF)
        val px = paint(s)
        var far = 0
        for (y in 0 until H) for (x in 0 until W) if (alpha(px, x, y) > 0 && kotlin.math.abs(y - 60) > 8) far++
        assertTrue("$far", far > 20)
    }

    @Test
    fun colorDynamicsMixesForegroundAndBackground() {
        val s = BrushSettings(size = 10f, hardness = 1f, spacing = 1.2f, fgBgJitter = 1f, sizeControl = DynControl.OFF)
        val px = paint(s, color = 0xFFFF0000.toInt(), bg = 0xFF0000FF.toInt())
        val reds = mutableSetOf<Int>()
        for (c in px) if ((c ushr 24) == 255) reds += (c shr 16) and 0xFF
        assertTrue("Farbstufen: ${reds.size}", reds.size > 5)
    }

    @Test
    fun brushModes() {
        val gray = 0xFF808080.toInt()
        val dot = listOf(Triple(100f, 60f, 1f))
        val s = BrushSettings(size = 20f, hardness = 1f, sizeControl = DynControl.OFF)
        val mul = paint(s.copy(mode = BrushMode.MULTIPLY), base = gray, color = 0xFF808080.toInt(), points = dot)
        assertTrue(((mul[60 * W + 100] shr 16) and 0xFF) in 60..68)
        val behind = paint(s.copy(mode = BrushMode.BEHIND), base = gray, color = 0xFFFF0000.toInt(), points = dot)
        assertEquals(gray, behind[60 * W + 100])
        val clear = paint(s.copy(mode = BrushMode.CLEAR), base = gray, points = dot)
        assertEquals(0, alpha(clear, 100, 60))
    }

    @Test
    fun airbrushBuildsUpWhileResting() {
        val s = BrushSettings(size = 20f, hardness = 1f, flow = 0.1f, airbrush = true, sizeControl = DynControl.OFF)
        val dot = listOf(Triple(100f, 60f, 1f))
        val once = alpha(paint(s, points = dot), 100, 60)
        val held = alpha(paint(s, points = dot, airbrushTicks = 15), 100, 60)
        assertTrue("$once → $held", held > once + 100)
    }

    @Test
    fun presetsEncodeAndDecode() {
        for (p in BrushPresets.builtin) assertEquals(p.name, p.settings, BrushSettings.decode(p.settings.encode()))
        val odd = BrushSettings(angleControl = AngleControl.DIRECTION, mode = BrushMode.LUMINOSITY, count = 7)
        assertEquals(odd, BrushSettings.decode(odd.encode()))
        assertEquals(BrushSettings(), BrushSettings.decode("unsinn;size=abc"))
    }
}
