package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.LayerPick
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.TileGrid
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LayerPickTest {
    private val w = 100; private val h = 100

    /** Ebene mit einem deckenden Quadrat [x0, x1) × [y0, y1). */
    private fun square(name: String, x0: Int, y0: Int, x1: Int, y1: Int) =
        PixelLayer(name, TileGrid.fromPixels(w, h, IntArray(w * h) { i -> if (i % w in x0 until x1 && i / w in y0 until y1) 0xFF336699.toInt() else 0 }))

    @Test
    fun picksTopmostVisibleContent() {
        val doc = Document.blank(w, h)
        val bg = doc.root.children[0]
        val a = square("A", 10, 10, 60, 60); val b = square("B", 40, 40, 90, 90)
        doc.root.add(a); doc.root.add(b)
        assertSame(b, LayerPick.at(doc, 50, 50))     // Überlappung: obere Ebene
        assertSame(a, LayerPick.at(doc, 20, 20))
        assertSame(bg, LayerPick.at(doc, 5, 95))     // nur Hintergrund
        b.visible = false
        assertSame(a, LayerPick.at(doc, 50, 50))
        b.visible = true; b.opacity = 0.05f
        assertSame("fast unsichtbar zählt nicht", a, LayerPick.at(doc, 50, 50))
        assertNull(LayerPick.at(doc, 500, 5))
    }

    @Test
    fun respectsMasksGroupsAndClipping() {
        val doc = Document(w, h)
        val base = square("Basis", 0, 0, 50, 100)
        val clip = square("Beschnitten", 0, 0, 100, 100).also { it.clipped = true }
        val g = GroupLayer("Gruppe")
        val inGroup = square("In Gruppe", 60, 0, 100, 50)
        g.add(inGroup)
        doc.root.add(base); doc.root.add(clip); doc.root.add(g)
        assertSame(inGroup, LayerPick.at(doc, 80, 20))
        assertSame("Schnittmaske nur über der Basis", clip, LayerPick.at(doc, 20, 80))
        assertNull("rechts unten zeigt nichts (Basis fehlt)", LayerPick.at(doc, 80, 80))
        g.mask = LayerMask(TileGrid(w, h, 0))
        assertNull("Gruppenmaske verdeckt", LayerPick.at(doc, 80, 20))
    }
}
