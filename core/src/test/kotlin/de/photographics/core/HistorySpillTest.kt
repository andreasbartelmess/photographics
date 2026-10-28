package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.history.ScratchDisk
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Ausgelagerte Ebenen-Operationen: nach Rückgängig/Wiederholen exakt gleiche Pixel. */
class HistorySpillTest {
    private fun layer(name: String, seed: Int) = PixelLayer(name, TileGrid.fromPixels(600, 400, IntArray(600 * 400) { (0xFF shl 24) or (it * seed) and 0xFFFFFF or (0xFF shl 24) }))
    private fun pixels(g: TileGrid) = IntArray(g.width * g.height).also { g.readRect(IntRect.ofSize(g.width, g.height), it) }

    @Test
    fun spilledLayerOperationsRestoreExactly() {
        val doc = Document(600, 400)
        val a = layer("A", 7); val b = layer("B", 13); val c = layer("C", 31)
        b.mask = LayerMask(TileGrid.fromPixels(600, 400, IntArray(600 * 400) { it % 256 }), enabled = true)
        doc.root.add(a); doc.root.add(b); doc.root.add(c)
        doc.activeLayer = c
        val ed = Editor(doc)
        ed.history.scratch = ScratchDisk(Files.createTempDirectory("pg-spill").toFile())
        val pa = pixels(a.pixels); val pb = pixels(b.pixels); val mb = pixels(b.mask!!.grid); val pc = pixels(c.pixels)

        ed.deleteLayer()                     // C löschen
        doc.activeLayer = b; ed.mergeDown()  // B auf A
        ed.flatten()
        doc.root.children.first().let { ed.setActive(it); ed.convertToSmartObject(it as PixelLayer) }
        ed.history.spillAll()
        assertEquals(1, doc.root.children.size)

        while (ed.history.canUndo) ed.undo()
        assertEquals(3, doc.root.children.size)
        assertArrayEquals(pa, pixels(a.pixels)); assertArrayEquals(pb, pixels(b.pixels))
        assertArrayEquals(mb, pixels(b.mask!!.grid)); assertArrayEquals(pc, pixels(c.pixels))

        ed.history.spillAll()
        while (ed.history.canRedo) ed.redo()
        assertEquals(1, doc.root.children.size)
        ed.history.spillAll()
        while (ed.history.canUndo) ed.undo()
        assertArrayEquals(pa, pixels(a.pixels)); assertArrayEquals(pb, pixels(b.pixels)); assertArrayEquals(pc, pixels(c.pixels))
    }
}
