package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.filters.GaussianBlurFilter
import de.photographics.core.history.ScratchDisk
import de.photographics.core.image.IntRect
import de.photographics.core.image.ResampleMethod
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.Compositor
import de.photographics.core.render.StrokeRenderCache
import de.photographics.core.selection.Selection
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.BrushTool
import de.photographics.core.tools.PaintTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Ebenen, die über die Arbeitsfläche hinausragen: nichts darf verloren gehen (wie in Photoshop). */
class OutsideCanvasTest {
    private val w = 700; private val h = 500

    /** Deckende, eindeutige Pixel auf der ganzen Arbeitsfläche. */
    private fun photo(seed: Int) = PixelLayer("P$seed", TileGrid.fromPixels(w, h, IntArray(w * h) { i ->
        (0xFF shl 24) or ((i % w * 3 + seed) and 0xFF shl 16) or ((i / w * 5) and 0xFF shl 8) or ((i * 7 + seed) and 0xFF)
    }))

    private fun read(g: TileGrid, r: IntRect) = IntArray(r.width * r.height).also { g.readRect(r, it) }
    private val canvas get() = IntRect.ofSize(w, h)

    private fun setup(): Triple<Document, Editor, PixelLayer> {
        val doc = Document(w, h)
        val bg = photo(1); val top = photo(77)
        doc.root.add(bg); doc.root.add(top); doc.activeLayer = top
        val ed = Editor(doc)
        ed.history.scratch = ScratchDisk(Files.createTempDirectory("pg-outside").toFile())
        return Triple(doc, ed, top)
    }

    @Test
    fun moveOutAndBackKeepsAllPixels() {
        val (doc, ed, layer) = setup()
        val original = read(layer.pixels, canvas)
        ed.commitMove(layer, 500, -300)         // größtenteils außerhalb
        assertTrue(layer.pixels.extendsBeyondCanvas)
        ed.commitMove(layer, -900, 450)         // auf die andere Seite
        ed.commitMove(layer, 400, -150)         // zurück an den Ursprung
        assertArrayEquals(original, read(layer.pixels, canvas))
        // Rückgängig/Wiederholen, auch nach dem Auslagern
        ed.history.spillAll()
        repeat(3) { ed.undo() }
        assertArrayEquals(original, read(layer.pixels, canvas))
        repeat(3) { ed.redo() }
        assertArrayEquals(original, read(layer.pixels, canvas))
        assertEquals(2, doc.root.children.size)
    }

    @Test
    fun psdKeepsPixelsOutsideCanvas() {
        val (doc, ed, layer) = setup()
        layer.mask = LayerMask(TileGrid.fromPixels(w, h, IntArray(w * h) { (it * 13) and 0xFF }), enabled = true)
        ed.commitMove(layer, -250, 120)
        val ext = layer.pixels.contentBounds()
        assertTrue(ext.left < 0 && ext.bottom > h)
        val back = PsdReader.read(PsdWriter.write(doc))
        val l2 = back.pixelLayers().last()
        assertArrayEquals(read(layer.pixels, ext), read(l2.pixels, ext))
        val mExt = layer.mask!!.grid.contentBounds(alphaOnly = false)
        assertArrayEquals(read(layer.mask!!.grid, mExt), read(l2.mask!!.grid, mExt))
    }

    @Test
    fun rotateAndFlipKeepOutside() {
        val (_, ed, layer) = setup()
        ed.commitMove(layer, 300, 200)
        val before = read(layer.pixels, layer.pixels.contentBounds())
        ed.rotate(true); ed.rotate(false)
        assertArrayEquals(before, read(layer.pixels, layer.pixels.contentBounds()))
        ed.flip(true); ed.flip(true); ed.flip(false); ed.flip(false)
        assertArrayEquals(before, read(layer.pixels, layer.pixels.contentBounds()))
        ed.rotate(true); repeat(3) { ed.rotate(true) }
        assertArrayEquals(before, read(layer.pixels, layer.pixels.contentBounds()))
    }

    @Test
    fun mergeDownAndCanvasSizeKeepOutside_cropDiscards() {
        val (doc, ed, layer) = setup()
        ed.commitMove(layer, 0, 300)
        val outside = IntRect(0, h, w, h + 300)
        val expected = read(layer.pixels, outside)
        ed.mergeDown()
        val merged = doc.pixelLayers().single()
        assertArrayEquals(expected, read(merged.pixels, outside))
        // Arbeitsfläche vergrößern: der herausragende Teil wird sichtbar
        ed.resizeCanvas(w, h + 300, 0f, 0f)
        assertArrayEquals(expected, read(doc.pixelLayers().single().pixels, outside))
        // Freistellen verwirft alles außerhalb
        ed.crop(IntRect(0, 0, w, h))
        assertFalse(doc.pixelLayers().single().pixels.extendsBeyondCanvas)
    }

    @Test
    fun imageSizeScalesOutsideToo() {
        val (_, ed, layer) = setup()
        ed.commitMove(layer, 350, 0)          // rechte Hälfte ragt hinaus
        ed.resizeImage(w * 2, h * 2, ResampleMethod.NEAREST)
        val g = layer.pixels
        // Pixelwiederholung: Quellpixel (x, y) landet bei (2x..2x+1, 2y..2y+1)
        val srcPx = photo(77).pixels
        for ((sx, sy) in listOf(10 to 10, 500 to 300, 690 to 490)) {
            val v = srcPx[sx, sy]
            assertEquals(v, g[(sx + 350) * 2, sy * 2]); assertEquals(v, g[(sx + 350) * 2 + 1, sy * 2 + 1])
        }
        assertTrue(g.contentBounds().right >= (w + 350) * 2 - 2)
    }

    @Test
    fun paintingFiltersAndFastRenderOnExtendedLayer() {
        val (doc, ed, layer) = setup()
        ed.commitMove(layer, -300, -200)
        doc.selection = Selection.rect(w, h, 50f, 50f, 400f, 300f)
        val st = ed.beginStroke(BrushTool(0xFF00FF00.toInt()), BrushSettings(size = 120f, hardness = 0.8f), PaintTarget.PIXELS)!!
        for (i in 0..20) st.addPoint(60f + i * 15f, 100f + i * 5f)
        ed.endStroke(st)
        assertEquals(0xFF00FF00.toInt(), layer.pixels[200, 150])   // in Auswahl gemalt
        // Schnelles Neuzeichnen entspricht dem vollen Compositing
        val cache = StrokeRenderCache.create(doc, layer)!!
        val r = IntRect(0, 0, w, h)
        assertArrayEquals(Compositor.renderArgb(doc, r), cache.renderArgb(r))
        // Filter wirkt auch außerhalb der Arbeitsfläche
        doc.selection = null
        val outsideBefore = read(layer.pixels, IntRect(-300, -200, 0, 0))
        val (tx, _) = ed.previewFilter(GaussianBlurFilter(3f))!!
        ed.commitFilter(tx, "Weichzeichnen")
        assertFalse(outsideBefore.contentEquals(read(layer.pixels, IntRect(-300, -200, 0, 0))))
        ed.undo()
        assertArrayEquals(outsideBefore, read(layer.pixels, IntRect(-300, -200, 0, 0)))
    }
}
