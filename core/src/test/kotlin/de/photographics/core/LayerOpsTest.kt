package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ebenenbedienfeld: Verschieben per Drag & Drop, Mehrfachauswahl, Auswahl aus Transparenz – jeweils mit Undo. */
class LayerOpsTest {
    private fun setup(): Pair<Editor, List<PixelLayer>> {
        val d = Document.blank(40, 30)
        val e = Editor(d)
        val bg = d.root.children[0] as PixelLayer
        val a = e.addLayer("A"); val b = e.addLayer("B"); val c = e.addLayer("C")
        return e to listOf(bg, a, b, c)
    }

    private fun names(g: GroupLayer) = g.children.map { it.name }

    @Test
    fun moveSeveralAndUndo() {
        val (e, l) = setup()
        val (bg, a, _, c) = l
        val root = e.doc.root
        assertEquals(listOf(bg.name, "A", "B", "C"), names(root))
        // A und C ganz nach unten (unter den Hintergrund)
        assertTrue(e.moveLayersTo(listOf(c, a), root, 0))
        assertEquals(listOf("A", "C", bg.name, "B"), names(root))
        e.undo()
        assertEquals(listOf(bg.name, "A", "B", "C"), names(root))
        e.redo()
        assertEquals(listOf("A", "C", bg.name, "B"), names(root))
    }

    @Test
    fun moveIntoGroupButNotIntoItself() {
        val (e, l) = setup()
        val (_, a, b, _) = l
        val g = e.groupLayers(listOf(b))!!
        assertTrue(e.moveLayersTo(listOf(a), g, g.children.size))
        assertEquals(listOf("B", "A"), names(g))
        assertFalse("Gruppe nicht in sich selbst", e.moveLayersTo(listOf(g), g, 0))
        e.undo()
        assertEquals(listOf("B"), names(g))
        assertTrue(e.doc.root.children.contains(a))
    }

    @Test
    fun groupDeleteDuplicateMerge() {
        val (e, l) = setup()
        val (bg, a, b, c) = l
        val root = e.doc.root
        val g = e.groupLayers(listOf(a, c))!!
        assertEquals(listOf(bg.name, "B", g.name), names(root))
        assertEquals(listOf("A", "C"), names(g))
        e.undo()
        assertEquals(listOf(bg.name, "A", "B", "C"), names(root))

        e.duplicateLayers(listOf(a, b))
        assertEquals(6, root.children.size)
        e.undo()
        e.deleteLayers(listOf(a, c))
        assertEquals(listOf(bg.name, "B"), names(root))
        e.undo()
        assertEquals(listOf(bg.name, "A", "B", "C"), names(root))

        // Pixel: A rot links, C blau rechts → Zusammengefügt enthält beides
        a.pixels.writeRect(IntRect(0, 0, 10, 10), IntArray(100) { 0xFFFF0000.toInt() })
        c.pixels.writeRect(IntRect(20, 0, 30, 10), IntArray(100) { 0xFF0000FF.toInt() })
        assertTrue(e.mergeLayers(listOf(a, c)))
        val m = e.doc.activeLayer as PixelLayer
        assertEquals(0xFFFF0000.toInt(), m.pixels[5, 5]); assertEquals(0xFF0000FF.toInt(), m.pixels[25, 5])
        assertEquals(listOf(bg.name, "B", "C"), names(root))
        e.undo()
        assertEquals(listOf(bg.name, "A", "B", "C"), names(root))
    }

    @Test
    fun selectionFromTransparency() {
        val (e, l) = setup()
        val a = l[1]
        a.pixels.writeRect(IntRect(5, 5, 15, 12), IntArray(70) { 0x80FFFFFF.toInt() })
        e.selectionFromLayer(a)
        val s = assertNotNull(e.doc.selection).let { e.doc.selection!! }
        assertEquals(0x80, s.at(8, 8)); assertEquals(0, s.at(20, 20))
        assertTrue(s.outlines.isNotEmpty())
        e.undo()
        assertEquals(null, e.doc.selection)
    }
}
