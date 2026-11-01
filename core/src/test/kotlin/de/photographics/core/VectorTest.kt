package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.doc.ShapeLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.selection.Selection
import de.photographics.core.vector.PathRaster
import de.photographics.core.vector.ShapeStroke
import de.photographics.core.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** Formen, Pfade und Alphakanäle: Rastern, Bearbeiten mit Undo, PSD-Rundlauf mit Photoshop-Datensätzen. */
class VectorTest {
    private fun area(w: Int, h: Int, p: VectorPath): Double {
        val g = PathRaster.fill(w, h, p.flatten().filter { it.second }.map { it.first })
        val px = IntArray(w * h); g.readRect(IntRect.ofSize(w, h), px)
        return px.sumOf { it.toDouble() } / 255.0
    }

    @Test
    fun fillCoverage() {
        assertEquals(30.0 * 20.0, area(64, 64, VectorPath.rect(10.0, 10.0, 40.0, 30.0)), 0.5)
        assertEquals(PI * 20 * 12, area(64, 64, VectorPath.ellipse(10.0, 10.0, 50.0, 34.0)), PI * 20 * 12 * 0.01)
        // Abgerundet: weniger als das Rechteck, mehr als ohne Ecken
        val rr = area(64, 64, VectorPath.rect(10.0, 10.0, 50.0, 40.0, radius = 8.0))
        assertEquals(40.0 * 30 - (4 - PI) * 64, rr, 4.0)
        // Zwei Teilpfade, der innere entgegengesetzt: Nonzero füllt beide; even-odd lässt das Loch
        val ring = VectorPath(VectorPath.rect(0.0, 0.0, 40.0, 40.0).subpaths + VectorPath.rect(10.0, 10.0, 30.0, 30.0).subpaths)
        val polys = ring.flatten().map { it.first }
        val eo = PathRaster.fill(64, 64, polys, evenOdd = true)
        assertEquals(0, eo[20, 20]); assertEquals(255, eo[5, 5])
    }

    @Test
    fun strokeWidth() {
        val g = PathRaster.stroke(100, 40, VectorPath(listOf(VectorPath.line(10.0, 20.0, 90.0, 20.0, 0.0).subpaths.firstOrNull() ?: return)).flatten(), 6f)
        assertTrue(g[50, 20] > 250); assertTrue(g[50, 22] > 250); assertEquals(0, g[50, 25])
    }

    @Test
    fun shapeLayerEditAndUndo() {
        val e = Editor(Document.blank(80, 60))
        val l = e.addShapeLayer(VectorPath.rect(10.0, 10.0, 50.0, 40.0), 0xFFFF0000.toInt(), ShapeStroke(4f, 0xFF0000FF.toInt()))
        assertEquals(0xFFFF0000.toInt(), l.raster[30, 25])
        assertEquals("Kontur mittig auf der Kante", 0xFF0000FF.toInt(), l.raster[10, 25])
        e.setShapeStyle(l, 0xFF00FF00.toInt(), null)
        assertEquals(0xFF00FF00.toInt(), l.raster[30, 25])
        e.undo()
        assertEquals(0xFFFF0000.toInt(), l.raster[30, 25])
        // Verschieben über die Lage, nicht über Pixel
        e.commitMove(l, 5, 0)
        assertEquals(0xFFFF0000.toInt(), l.raster[52, 25])
        e.undo(); e.undo()
        assertEquals(1, e.doc.root.children.size)
    }

    /** Der Transformationsrahmen liegt auf der Form, auch wenn der Pfad nicht bei (0, 0) beginnt. */
    @Test
    fun shapeTransformBoxFollowsPathBounds() {
        val e = Editor(Document.blank(80, 60))
        val l = e.addShapeLayer(VectorPath.rect(10.0, 10.0, 50.0, 40.0), 0xFFFF0000.toInt(), null)
        // Pfad mit Ankerpunkten links/oben vom Ursprung (wie nach dem Ziehen mit dem Direktauswahl-Werkzeug)
        e.setShapePath(l, VectorPath.rect(-6.0, -4.0, 50.0, 40.0).map(de.photographics.core.image.Affine.translate(12.0, 8.0)))
        val b = l.docPath().bounds()
        val (w, h) = l.boxSize()
        val box = l.boxTransform
        assertEquals(b[0], box.mapX(0.0, 0.0), 1e-9); assertEquals(b[1], box.mapY(0.0, 0.0), 1e-9)
        assertEquals(b[2], box.mapX(w, h), 1e-9); assertEquals(b[3], box.mapY(w, h), 1e-9)
        // Rahmen verschieben: die Form folgt, Rückgängig stellt sie wieder her
        e.commitTransform(l, box, box.then(de.photographics.core.image.Affine.translate(5.0, 3.0)))
        assertEquals(b[0] + 5, l.docPath().bounds()[0], 1e-9); assertEquals(b[1] + 3, l.docPath().bounds()[1], 1e-9)
        e.undo()
        assertEquals(b[0], l.docPath().bounds()[0], 1e-9)
    }

    @Test
    fun pathsSelectionsAndChannels() {
        val e = Editor(Document.blank(80, 60))
        val p = VectorPath.ellipse(10.0, 10.0, 50.0, 40.0)
        e.setWorkPath(p)
        e.saveWorkPath("Kreis")
        assertEquals("Kreis", e.doc.paths.single().name)
        e.selectionFromPath(e.doc.paths[0].path)
        assertEquals(255, e.doc.selection!!.at(30, 25)); assertEquals(0, e.doc.selection!!.at(5, 5))
        e.saveSelectionAsChannel()
        e.deselect()
        e.selectionFromChannel(0)
        assertEquals(255, e.doc.selection!!.at(30, 25))
        // Pfad füllen auf der Pixelebene
        e.deselect()
        val bg = e.doc.root.children[0] as PixelLayer
        e.fillPath(VectorPath.rect(0.0, 0.0, 10.0, 10.0), 0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), bg.pixels[5, 5])
        // Auswahl → Arbeitspfad
        e.setSelection(Selection.rect(80, 60, 20f, 20f, 40f, 30f))
        e.pathFromSelection()
        val b = e.doc.workPath!!.bounds()
        assertEquals(20.0, b[0], 0.6); assertEquals(40.0, b[2], 0.6)
        // Freistellen nimmt Pfade und Kanäle mit
        e.crop(IntRect(10, 10, 70, 50))
        assertEquals(20.0, e.doc.paths[0].path.bounds()[2] - 20.0, 1.0)
        assertEquals(60, e.doc.channels[0].grid.width)
        e.undo()
        assertEquals(80, e.doc.channels[0].grid.width)
    }

    @Test
    fun psdRoundTrip() {
        val d = Document.blank(120, 90)
        val e = Editor(d)
        e.addShapeLayer(VectorPath.ellipse(20.0, 10.0, 80.0, 70.0), 0xFF3366CC.toInt(), ShapeStroke(3f, 0xFFFFCC00.toInt(), ShapeStroke.Align.OUTSIDE))
        e.setWorkPath(VectorPath.rect(5.0, 5.0, 25.0, 15.0))
        e.saveWorkPath("Kasten")
        e.setWorkPath(VectorPath.polygon(40.0, 40.0, 100.0, 80.0, 5, 0.5))
        e.setSelection(Selection.rect(120, 90, 30f, 30f, 60f, 60f))
        e.saveSelectionAsChannel("Maske A")
        val bytes = PsdWriter.write(d)
        val back = PsdReader.read(bytes)
        val s = back.root.children.filterIsInstance<ShapeLayer>().single()
        assertEquals(0xFF3366CC.toInt(), s.fill)
        assertEquals(3f, s.stroke!!.width, 0.01f)
        assertEquals(ShapeStroke.Align.OUTSIDE, s.stroke!!.align)
        val b = s.docPath().bounds()
        assertTrue(abs(b[0] - 20.0) < 0.5 && abs(b[3] - 70.0) < 0.5)
        assertEquals("Kasten", back.paths.single().name)
        assertEquals(15.0, back.paths[0].path.bounds()[3], 0.01)
        assertEquals(10, back.workPath!!.subpaths[0].anchors.size)
        val ch = back.channels.single()
        assertEquals("Maske A", ch.name)
        assertEquals(255, ch.grid[45, 45]); assertEquals(0, ch.grid[10, 10])
        assertNotNull(back.root.children.firstOrNull { it is PixelLayer })
    }
}
