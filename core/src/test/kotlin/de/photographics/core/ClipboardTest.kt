package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.selection.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kopieren, Einfügen und schwebende Auswahl (Verschieben/Transformieren ausgewählter Pixel) wie in Photoshop. */
class ClipboardTest {
    private val red = 0xFFFF0000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** Weißer Hintergrund mit rotem Quadrat 10..20 × 10..20, das Quadrat ausgewählt. */
    private fun setup(): Pair<Editor, PixelLayer> {
        val d = Document.blank(60, 40, white)
        val e = Editor(d)
        val bg = d.root.children[0] as PixelLayer
        for (y in 10 until 20) for (x in 10 until 20) bg.pixels[x, y] = red
        e.setSelection(Selection.rect(60, 40, 10f, 10f, 20f, 20f))
        return e to bg
    }

    @Test
    fun copyPasteInPlaceAndUndo() {
        val (e, bg) = setup()
        val clip = e.copy()!!
        assertEquals(IntRect(10, 10, 20, 20), clip.rect)
        assertEquals(red, clip.pixels()[0])
        val at = e.paste(clip, inPlace = true)
        assertEquals(IntRect(10, 10, 20, 20), at)
        val pasted = e.doc.activeLayer as PixelLayer
        assertTrue(pasted !== bg)
        assertEquals(red, pasted.pixels[15, 15])
        assertEquals(0, pasted.pixels[5, 5])
        assertNull("Einfügen hebt die Auswahl auf", e.doc.selection)
        // Ein Schritt im Verlauf: Ebene und Auswahl zurück
        e.undo()
        assertEquals(1, e.doc.root.children.size)
        assertNotNull(e.doc.selection)
    }

    @Test
    fun pasteCentersInSelection() {
        val (e, _) = setup()
        val clip = e.copy()!!
        e.setSelection(Selection.rect(60, 40, 30f, 0f, 60f, 40f))
        assertEquals(IntRect(40, 15, 50, 25), e.paste(clip, inPlace = false))
    }

    @Test
    fun cutClearsSource() {
        val (e, bg) = setup()
        val d = Document.blank(60, 40, null)  // transparent: Ausschneiden hinterlässt Transparenz
        val e2 = Editor(d)
        val l = d.root.children[0] as PixelLayer
        l.pixels[12, 12] = red
        e2.setSelection(Selection.rect(60, 40, 10f, 10f, 20f, 20f))
        assertNotNull(e2.cut())
        assertEquals(0, l.pixels[12, 12] ushr 24)
        assertEquals("anderes Dokument unberührt", red, bg.pixels[12, 12])
        // Nur transparente Pixel ausgewählt: nichts zu kopieren
        e2.setSelection(Selection.rect(60, 40, 40f, 30f, 50f, 35f))
        assertNull(e2.copy())
    }

    @Test
    fun floatMoveCommitAndUndo() {
        val (e, bg) = setup()
        val f = e.floatSelection()!!
        // Während der Bewegung: Quelle ausgeschnitten, Pixel auf eigener Ebene
        assertEquals(2, e.doc.root.children.size)
        f.layer.previewDx = 25; f.layer.previewDy = 5
        e.commitFloating(f, "Verschieben")
        assertEquals(1, e.doc.root.children.size)
        assertEquals(red, bg.pixels[37, 17])
        assertEquals("Ausgeschnittene Stelle wird transparent", 0, bg.pixels[12, 12] ushr 24)
        val sel = e.doc.selection!!
        assertEquals(255, sel.at(37, 17)); assertEquals(0, sel.at(12, 12))
        // Ein Schritt im Verlauf
        e.undo()
        assertEquals(red, bg.pixels[12, 12]); assertEquals(white, bg.pixels[37, 17])
        assertEquals(255, e.doc.selection!!.at(12, 12))
        e.redo()
        assertEquals(red, bg.pixels[37, 17])
    }

    @Test
    fun floatDuplicateAndTransform() {
        val (e, bg) = setup()
        val f = e.floatSelection(duplicate = true)!!
        // Auf doppelte Größe um die linke obere Ecke
        f.layer.transform = Affine.scale(2.0).then(Affine.translate(10.0, 10.0))
        e.commitFloating(f)
        assertEquals("Alt: Original bleibt", red, bg.pixels[12, 12])
        assertEquals(red, bg.pixels[27, 27])
        assertEquals(white, bg.pixels[32, 32])
        val sel = e.doc.selection!!
        assertTrue(sel.at(27, 27) > 200)
        assertEquals(0, sel.at(33, 33))
    }

    @Test
    fun cancelFloatingRestores() {
        val (e, bg) = setup()
        val f = e.floatSelection()!!
        f.layer.previewDx = 20
        e.cancelFloating(f)
        assertEquals(1, e.doc.root.children.size)
        assertEquals(red, bg.pixels[12, 12])
        assertEquals(255, e.doc.selection!!.at(12, 12))
        assertTrue("kein Verlaufsschritt", !e.history.canUndo || e.history.undoLabel != "Verschieben")
    }

    @Test
    fun moveSelectionOnly() {
        val (e, bg) = setup()
        e.moveSelection(5, -3)
        val s = e.doc.selection!!
        assertEquals(IntRect(15, 7, 25, 17), s.bounds)
        assertEquals(red, bg.pixels[12, 12])
        e.undo()
        assertEquals(IntRect(10, 10, 20, 20), e.doc.selection!!.bounds)
    }

    @Test
    fun layerViaCut() {
        val (e, bg) = setup()
        e.layerViaCut()
        val l = e.doc.activeLayer as PixelLayer
        assertTrue(l !== bg)
        assertEquals(red, l.pixels[12, 12])
        assertEquals(0, bg.pixels[12, 12] ushr 24)
        e.undo()
        assertEquals(red, bg.pixels[12, 12])
        assertEquals(1, e.doc.root.children.size)
    }

    @Test
    fun transformWholeLayerWithoutSelection() {
        val (e, bg) = setup()
        e.deselect()
        val f = e.floatSelection()!!
        f.layer.previewDx = 5
        e.commitFloating(f)
        assertTrue("bleibt eine Pixelebene", e.doc.activeLayer === bg)
        assertEquals(red, bg.pixels[22, 12])
        assertEquals(white, bg.pixels[12, 12])
        assertNull(e.doc.selection)
        e.undo()
        assertEquals(red, bg.pixels[12, 12])
    }
}
