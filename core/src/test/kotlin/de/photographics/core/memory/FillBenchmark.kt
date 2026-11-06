package de.photographics.core.memory
import de.photographics.core.doc.*
import de.photographics.core.editor.Editor
import de.photographics.core.image.*
import de.photographics.core.selection.MagicWand
import org.junit.Test
/** Füllwerkzeug/Zauberstab auf 24 MP: `PG_BENCH=1 ./gradlew :core:memoryTest --tests "*FillBenchmark*"`. */
class FillBenchmark {
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    @Test fun run() {
        org.junit.Assume.assumeTrue(System.getenv("PG_BENCH") != null)
        TileMemory.current = SlabTileMemory(limitBytes = 2900L shl 20, newSlab = { java.nio.ByteBuffer.allocateDirect(it) })
        val w = 6000; val h = 4000
        // Obere Hälfte „Himmel“ (leichter Verlauf), unten Rauschen, mittig ein Kreis
        val g = TileGrid(w, h)
        var y = 0
        while (y < h) {
            val r = IntRect(0, y, w, minOf(h, y + 256))
            g.writeRect(r, IntArray(r.width * r.height) { i ->
                val x = i % w; val yy = y + i / w
                val dx = x - 3000; val dy = yy - 1000
                when {
                    dx * dx + dy * dy < 600 * 600 -> 0xFFF0C020.toInt()
                    yy < h / 2 -> (0xFF shl 24) or ((80 + yy / 200) shl 16) or (140 shl 8) or 220
                    else -> (0xFF shl 24) or (((x * 7919 xor yy * 104729) and 0xFF) * 0x010101)
                }
            })
            y = r.bottom
        }
        val doc = Document(w, h)
        val l = PixelLayer("Bild", g); doc.root.add(l); doc.activeLayer = l
        val ed = Editor(doc)
        repeat(2) { k ->
            println("--- Durchgang ${k + 1}")
            var t = System.nanoTime(); MagicWand.select(g, 10, 10, 32, contiguous = true); println("Zauberstab Himmel zusammenhängend: ${ms(t)} ms")
            t = System.nanoTime(); MagicWand.select(g, 10, 10, 32, contiguous = false); println("Zauberstab Himmel global: ${ms(t)} ms")
            t = System.nanoTime(); MagicWand.select(g, 10, 3000, 40, contiguous = true); println("Zauberstab Rauschen: ${ms(t)} ms")
            t = System.nanoTime(); ed.bucketFill(10, 10, 0xFF00FF00.toInt(), 32, true, false); println("Füllwerkzeug Himmel: ${ms(t)} ms")
            ed.undo()
            t = System.nanoTime(); ed.bucketFill(10, 10, 0xFF00FF00.toInt(), 32, true, true); println("Füllwerkzeug Himmel alle Ebenen: ${ms(t)} ms")
            ed.undo()
            t = System.nanoTime(); ed.magicErase(3000, 1000, 10, true, false); println("Magischer Radiergummi Kreis: ${ms(t)} ms")
            ed.undo()
            ed.selectAll()
            t = System.nanoTime(); ed.fillArea(0xFF336699.toInt()); println("Fläche füllen alles: ${ms(t)} ms")
            ed.undo(); ed.deselect()
        }
    }
}
