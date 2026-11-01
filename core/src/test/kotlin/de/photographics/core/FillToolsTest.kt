package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.editor.FillContent
import de.photographics.core.editor.PixelBlend
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import de.photographics.core.selection.QuickSelect
import de.photographics.core.selection.Selection
import de.photographics.core.tools.BackgroundEraserTool
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.BrushTool
import de.photographics.core.tools.PaintTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fläche füllen, Füllwerkzeug, Magischer und Hintergrund-Radiergummi, Buntstift, Schnellauswahl. */
class FillToolsTest {
    /** Links blau (x < 50), rechts weiß, in der Mitte rechts ein rotes Quadrat 70..90. */
    private fun editor(): Pair<Editor, PixelLayer> {
        val w = 100; val h = 60
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x in 70 until 90 && y in 20 until 40 -> 0xFFFF0000.toInt()
                x < 50 -> 0xFF2040C0.toInt()
                else -> -1
            }
        }
        val doc = Document(w, h)
        val l = PixelLayer("Bild", TileGrid.fromPixels(w, h, px))
        doc.root.add(l)
        doc.activeLayer = l
        return Editor(doc) to l
    }

    @Test
    fun blendOverMatchesModes() {
        val gray = 0xFF808080.toInt()
        assertEquals(0xFF404040.toInt() and 0xFFFFFF, PixelBlend.over(gray, gray, BlendMode.MULTIPLY, 1f) and 0xFFFFFF)
        assertEquals(gray, PixelBlend.over(gray, -1, BlendMode.NORMAL, 0f))
        // Transparenz erhalten: leere Pixel bleiben leer
        assertEquals(0, PixelBlend.over(0, -1, BlendMode.NORMAL, 1f, preserveAlpha = true))
        assertEquals(255, PixelBlend.over(0, -1, BlendMode.NORMAL, 1f) ushr 24)
    }

    @Test
    fun fillAreaInSelectionWithOpacity() {
        val (e, l) = editor()
        e.setSelection(Selection.rect(100, 60, 0f, 0f, 10f, 10f))
        e.fillArea(FillContent.BLACK.color(0, 0, 0)!!, BlendMode.NORMAL, 0.5f)
        val c = l.pixels[5, 5]
        assertEquals(0x10, (c shr 16) and 0xFF)        // 0x20 halb zu Schwarz
        assertEquals(0x2040C0 or 0xFF000000.toInt(), l.pixels[20, 20])  // außerhalb unverändert
        e.undo()
        assertEquals(0xFF2040C0.toInt(), l.pixels[5, 5])
    }

    @Test
    fun bucketFillsOnlyContiguousSimilar() {
        val (e, l) = editor()
        e.bucketFill(60, 5, 0xFF00FF00.toInt(), 10, contiguous = true, allLayers = false, antiAlias = false)
        assertEquals(0xFF00FF00.toInt(), l.pixels[95, 55])   // weißer Bereich gefüllt
        assertEquals(0xFFFF0000.toInt(), l.pixels[80, 30])   // rotes Quadrat nicht
        assertEquals(0xFF2040C0.toInt(), l.pixels[10, 10])   // blaue Hälfte nicht
    }

    @Test
    fun magicEraseMakesRegionTransparent() {
        val (e, l) = editor()
        e.magicErase(80, 30, 10, contiguous = true, allLayers = false, antiAlias = false)
        assertEquals(0, l.pixels[80, 30] ushr 24)
        assertEquals(255, l.pixels[60, 30] ushr 24)
    }

    @Test
    fun backgroundEraserKeepsDifferentColors() {
        val (e, l) = editor()
        // Pinsel auf Weiß, reicht bis ins rote Quadrat: nur Weiß wird radiert
        val st = e.beginStroke(BackgroundEraserTool(20), BrushSettings(size = 40f, hardness = 1f), PaintTarget.PIXELS)!!
        st.addPoint(65f, 30f)
        e.endStroke(st)
        assertEquals(0, l.pixels[65, 30] ushr 24)
        assertEquals(255, l.pixels[72, 30] ushr 24)
    }

    @Test
    fun pencilHasHardEdge() {
        val (e, l) = editor()
        val st = e.beginStroke(BrushTool(0xFF000000.toInt(), pencil = true), BrushSettings(size = 9f, hardness = 0f), PaintTarget.PIXELS)!!
        st.addPoint(60.5f, 50.5f)
        e.endStroke(st)
        // Nur ganz schwarz oder unverändert weiß, keine Zwischentöne
        for (y in 44..57) for (x in 54..67) {
            val v = l.pixels[x, y] and 0xFF
            assertTrue("($x,$y) = $v", v == 0 || v == 255)
        }
        assertEquals(0, l.pixels[60, 50] and 0xFF)
    }

    @Test
    fun quickSelectFollowsColorRegion() {
        val (_, l) = editor()
        val sel = QuickSelect.select(l.pixels, floatArrayOf(10f, 30f, 30f, 30f), 6f)
        assertEquals(255, sel.at(20, 30))
        assertEquals(255, sel.at(40, 20))     // im Blau über den Pinsel hinaus gewachsen
        assertEquals(0, sel.at(60, 30))       // nicht ins Weiß
    }
}
