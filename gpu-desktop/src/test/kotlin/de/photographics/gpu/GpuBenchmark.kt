package de.photographics.gpu
import de.photographics.core.doc.*
import de.photographics.core.gpu.GpuCompositor
import de.photographics.core.image.*
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import org.junit.Assume.assumeTrue
import org.junit.Test
/** `PG_BENCH=1 ./gradlew :gpu-desktop:test --tests "*GpuBenchmark*"` */
class GpuBenchmark {
    private fun ms(t: Long) = (System.nanoTime() - t) / 1_000_000
    @Test fun run() {
        assumeTrue(System.getenv("PG_BENCH") != null)
        TileMemory.current = SlabTileMemory(limitBytes = 2900L shl 20, newSlab = { java.nio.ByteBuffer.allocateDirect(it) })
        val egl = HeadlessEgl.open() ?: return
        val g = GpuCompositor(LwjglGles())
        val w = 6000; val h = 4000
        fun photo(seed: Int) = TileGrid(w, h).also { gr -> var y = 0; while (y < h) { val r = IntRect(0, y, w, minOf(h, y + 256)); gr.writeRect(r, IntArray(r.width * r.height) { i -> val x = i % w; val yy = y + i / w; (0xFF shl 24) or (((x * 3 + seed) xor yy) and 0xFF shl 16) or ((x + yy * 2 + seed) and 0xFF shl 8) or ((x * yy + seed) and 0xFF) }); y = r.bottom } }
        val doc = Document(w, h)
        doc.root.add(PixelLayer("Hintergrund", photo(1)))
        doc.root.add(PixelLayer("Weiches Licht", photo(7)).also { it.blendMode = BlendMode.SOFT_LIGHT; it.opacity = 0.7f })
        doc.root.add(PixelLayer("Farbe", photo(3)).also { it.blendMode = BlendMode.COLOR; it.opacity = 0.4f; it.mask = LayerMask(TileGrid(w, h, 255)) })
        val grp = GroupLayer("Gruppe").also { it.blendMode = BlendMode.MULTIPLY }
        grp.add(PixelLayer("In Gruppe", photo(9)))
        doc.root.add(grp)
        val view = IntRect(1000, 1000, 3560, 2600)
        for ((label, rect, step) in listOf(Triple("100 % (2560×1600)", view, 1), Triple("Ganzes Bild 1/4 (1500×1000)", IntRect(0, 0, 1500, 1000), 4))) {
            repeat(3) { k ->
                var t = System.nanoTime()
                Compositor.prepare(doc, step, rect)
                val bh = 64
                Parallel.bands((rect.height + bh - 1) / bh, rect.width.toLong() * bh * 40, compute = { i -> Compositor.renderArgbSampled(doc, IntRect(rect.left, rect.top + i * bh, rect.right, minOf(rect.bottom, rect.top + (i + 1) * bh)), step) }, consume = { _, _ -> })
                val cpu = ms(t)
                t = System.nanoTime()
                val b = g.render(doc, rect, step); LwjglGles().finish()
                val gpu = ms(t)
                g.release(b)
                println("$label Durchgang ${k + 1}: CPU (alle Kerne) $cpu ms, GPU $gpu ms")
            }
        }
        // Nur ein Regler ändert sich (Deckkraft): kein Hochladen, nur Zusammensetzen
        repeat(3) { k ->
            doc.root.children[1].opacity = 0.3f + k * 0.2f
            val t = System.nanoTime(); val b = g.render(doc, view, 1); LwjglGles().finish(); println("Deckkraft geändert, GPU: ${ms(t)} ms"); g.release(b)
        }
        // Einstellungsebenen: Kurven (Kanaltabellen) und Farbton/Sättigung (3D-Tabelle), Regler bewegt
        val curves = AdjustmentLayer("Kurven", de.photographics.core.adjust.CurvesAdj())
        val hue = AdjustmentLayer("Farbton", de.photographics.core.adjust.HueSaturationAdj())
        doc.root.add(curves); doc.root.add(hue)
        repeat(4) { k ->
            curves.adjustment = de.photographics.core.adjust.CurvesAdj().withValues(floatArrayOf(60f + k * 5, 130f, 200f))
            var t = System.nanoTime(); var b = g.render(doc, view, 1); LwjglGles().finish(); println("Kurven-Regler, GPU: ${ms(t)} ms"); g.release(b)
            hue.adjustment = de.photographics.core.adjust.HueSaturationAdj(hue = 10 + k * 7, saturation = 20)
            t = System.nanoTime(); b = g.render(doc, view, 1); LwjglGles().finish(); println("Farbton-Regler (Shader), GPU: ${ms(t)} ms"); g.release(b)
        }
        Compositor.prepare(doc, 1, view)
        var t = System.nanoTime()
        Parallel.bands((view.height + 63) / 64, view.width.toLong() * 64 * 40, compute = { i -> Compositor.renderArgbSampled(doc, IntRect(view.left, view.top + i * 64, view.right, minOf(view.bottom, view.top + (i + 1) * 64)), 1) }, consume = { _, _ -> })
        println("Dasselbe auf der CPU (alle Kerne): ${ms(t)} ms")
        g.dispose(); egl.close()
    }
}
