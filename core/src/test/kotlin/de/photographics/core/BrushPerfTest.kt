package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.Compositor
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.BrushTool
import de.photographics.core.tools.PaintTarget
import org.junit.Test

/** Misst Pinselstriche mit verschiedenen Größen (Zeit für Abdrücke und für das Neuzeichnen). */
class BrushPerfTest {
    @Test
    fun brushStrokeTiming() = run("Heap")

    /** Gleiche Messung mit Kacheln im nativen Speicher (wie in der App). */
    @Test
    fun brushStrokeTimingSlab() {
        val prev = de.photographics.core.image.TileMemory.current
        de.photographics.core.image.TileMemory.current =
            de.photographics.core.image.SlabTileMemory(1L shl 30, { java.nio.ByteBuffer.allocateDirect(it) })
        try { run("Nativ") } finally { de.photographics.core.image.TileMemory.current = prev }
    }

    private fun run(kind: String) {
        for (size in listOf(20f, 100f, 300f, 600f)) {
            val doc = Document.blank(3000, 2000)
            doc.root.add(PixelLayer("Ebene", TileGrid(3000, 2000)).also { doc.activeLayer = it })
            val ed = Editor(doc)
            val st = ed.beginStroke(BrushTool(0xFFFF0000.toInt()), BrushSettings(size = size, hardness = 0.5f), PaintTarget.PIXELS)!!
            var dabTime = 0L; var renderTime = 0L; var renderedPx = 0L
            val cache = de.photographics.core.render.StrokeRenderCache.create(doc, doc.activeLayer as PixelLayer)!!
            var x = 200f
            st.addPoint(x, 1000f)
            repeat(60) {
                x += 30f
                val t0 = System.nanoTime()
                val dirty = st.addPoint(x, 1000f + (it % 10) * 5f)
                val t1 = System.nanoTime()
                if (!dirty.isEmpty) {
                    val fast = cache.renderArgb(dirty)
                    renderedPx += dirty.width.toLong() * dirty.height
                    // Schnellweg muss exakt dem vollen Compositing entsprechen
                    if (it == 30 && size <= 100f) {
                        val full = Compositor.renderArgb(doc, dirty)
                        val diff = full.indices.maxOf { k -> intArrayOf(0, 8, 16, 24).maxOf { s -> kotlin.math.abs(((full[k] ushr s) and 0xFF) - ((fast[k] ushr s) and 0xFF)) } }
                        org.junit.Assert.assertTrue("Abweichung $diff", diff <= 1)
                    }
                }
                val t2 = System.nanoTime()
                dabTime += t1 - t0; renderTime += t2 - t1
            }
            ed.endStroke(st)
            print("$kind ")
            println("Pinsel %4.0f px: Abdrücke %6.1f ms, Rendern %6.1f ms (%.1f MP) pro 60 Bewegungen".format(size, dabTime / 1e6, renderTime / 1e6, renderedPx / 1e6))
        }
    }

    @Suppress("unused") private val r = IntRect.EMPTY
}
