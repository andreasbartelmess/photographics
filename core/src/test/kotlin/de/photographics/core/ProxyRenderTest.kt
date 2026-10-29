package de.photographics.core

import de.photographics.core.adjust.HueSaturationAdj
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRenderTest {
    @Test
    fun sampledMatchesFullRenderAtSamplePoints() {
        val w = 1200; val h = 900
        val doc = Document(w, h)
        doc.root.add(PixelLayer("A", TileGrid.fromPixels(w, h, IntArray(w * h) { i -> (0xFF shl 24) or ((i % w) * 255 / w shl 16) or ((i / w) * 255 / h) })))
        val b = PixelLayer("B", TileGrid.fromPixels(w, h, IntArray(w * h) { i -> if ((i % w / 50 + i / w / 50) % 2 == 0) 0xC0FF8000.toInt() else 0 }))
        b.blendMode = BlendMode.MULTIPLY; b.opacity = 0.7f
        b.mask = LayerMask(TileGrid.fromPixels(w, h, IntArray(w * h) { i -> (i % w) * 255 / w }), enabled = true)
        b.previewDx = 13; b.previewDy = -7
        doc.root.add(b)
        doc.root.add(AdjustmentLayer("HS", HueSaturationAdj(30, 20, 0)))
        val step = 3
        val pw = (w + step - 1) / step; val ph = (h + step - 1) / step
        val proxy = Compositor.renderArgbSampled(doc, IntRect(0, 0, pw, ph), step)
        val full = Compositor.renderArgb(doc, doc.bounds)
        // Die Vorschau liest aus Mipmaps (Mittel über 2×2): an glatten Stellen gleich dem Abtastpunkt,
        // nur an Kanten des Schachbrettmusters weichen einzelne Punkte stärker ab
        var samples = 0; var off = 0
        for (y in 0 until ph step 7) for (x in 0 until pw step 11) {
            val dx = x * step + step / 2; val dy = y * step + step / 2
            if (dx >= w || dy >= h) continue
            val a = full[dy * w + dx]; val c = proxy[y * pw + x]
            samples++
            if (intArrayOf(24, 16, 8, 0).any { s -> kotlin.math.abs(((a shr s) and 0xFF) - ((c shr s) and 0xFF)) > 2 }) off++
        }
        assertTrue("$off von $samples Punkten weichen ab", off * 20 <= samples)
        val t0 = System.nanoTime(); repeat(5) { Compositor.renderArgb(doc, doc.bounds) }
        val t1 = System.nanoTime(); repeat(5) { Compositor.renderArgbSampled(doc, IntRect(0, 0, pw, ph), step) }
        val t2 = System.nanoTime()
        println("Volle Auflösung %.1f ms, Vorschau (1/%d) %.1f ms".format((t1 - t0) / 5e6, step, (t2 - t1) / 5e6))
    }

    /** Live-Vorschau mit zwischengespeicherten unteren Ebenen = vollständig gerechnetes Bild (bis auf Rundung). */
    @Test
    fun rangeOverCachedBelowMatchesFullRender() {
        val w = 300; val h = 200
        fun grid(f: (Int, Int) -> Int) = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> f(i % w, i / w) })
        val doc = Document(w, h)
        doc.root.add(PixelLayer("Hintergrund", grid { x, y -> (0xFF shl 24) or (x * 255 / w shl 16) or (y * 255 / h shl 8) or 0x40 }))
        val soft = PixelLayer("Weiches Licht", grid { x, y -> if ((x / 20 + y / 20) % 2 == 0) 0xA0FF8000.toInt() else 0x60204080 })
        soft.blendMode = BlendMode.SOFT_LIGHT; soft.opacity = 0.6f
        doc.root.add(soft)
        val group = GroupLayer("Gruppe")
        val inGroup = PixelLayer("In der Gruppe", grid { x, _ -> if (x in 100..200) 0xFF30C030.toInt() else 0 })
        group.add(inGroup)
        doc.root.add(group)
        val base = PixelLayer("Basis", grid { x, y -> if ((x - 150) * (x - 150) + (y - 100) * (y - 100) < 60 * 60) 0xFFFFFFFF.toInt() else 0 })
        doc.root.add(base)
        val clipped = PixelLayer("Beschnitten", grid { x, _ -> 0xFF0000FF.toInt() or (x shl 16) }).also { it.clipped = true; it.blendMode = BlendMode.MULTIPLY }
        doc.root.add(clipped)
        doc.root.add(AdjustmentLayer("HS", HueSaturationAdj(-40, 10, 5)))
        for (step in intArrayOf(1, 2)) {
            val rect = IntRect(0, 0, (w + step - 1) / step, (h + step - 1) / step)
            Compositor.prepare(doc, step)
            for (changed in listOf<de.photographics.core.doc.Layer>(soft, inGroup, clipped)) {
                val from = Compositor.liveSplit(doc, changed)
                val below = Compositor.renderRangeSampled(doc, rect, step, 0, from, null)
                // Teilbereich wie bei der Live-Vorschau (betroffener Ausschnitt), Zwischenspeicher für die ganze Fläche
                val part = IntRect(rect.left + 7, rect.top + 5, rect.right - 11, rect.bottom - 3)
                val got = Compositor.renderRangeSampled(doc, part, step, from, doc.root.children.size, below, rect.width, rect.left, rect.top)
                val want = Compositor.renderArgbSampled(doc, part, step)
                var maxDiff = 0
                for (k in want.indices) for (sh in intArrayOf(24, 16, 8, 0))
                    maxDiff = maxOf(maxDiff, kotlin.math.abs(((want[k] shr sh) and 0xFF) - ((got[k] shr sh) and 0xFF)))
                assertTrue("Schritt $step, ${changed.name} (ab $from): Abweichung $maxDiff", maxDiff <= 2)
            }
        }
        assertEquals(2, Compositor.liveSplit(doc, inGroup))
        assertEquals(3, Compositor.liveSplit(doc, clipped))
    }

    /** GPU-Vorschau: darunter + Ebene (verschoben, Deckkraft, Maske) + darüber = Compositor (Quelle-über ist assoziativ). */
    @Test
    fun gpuSandwichMatchesCompositor() {
        val w = 240; val h = 160
        fun grid(f: (Int, Int) -> Int) = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> f(i % w, i / w) })
        val doc = Document(w, h)
        doc.root.add(PixelLayer("Hintergrund", grid { x, y -> (0xFF shl 24) or (x shl 16) or (y shl 8) or 0x30 }))
        doc.root.add(PixelLayer("Weich", grid { x, _ -> if (x % 40 < 20) 0x8020A0F0.toInt() else 0 }).also { it.blendMode = BlendMode.SOFT_LIGHT })
        val moved = PixelLayer("Motiv", grid { x, y -> if (x in 60..140 && y in 40..110) 0xC0E04020.toInt() else 0 }).also { it.opacity = 0.7f }
        moved.mask = LayerMask(TileGrid.fromPixels(w, h, IntArray(w * h) { i -> (i % w) * 255 / w }), enabled = true)
        doc.root.add(moved)
        doc.root.add(PixelLayer("Oben", grid { _, y -> if (y < 30) 0x9000FF00.toInt() else 0 }).also { it.opacity = 0.5f })
        moved.previewDx = 17; moved.previewDy = -9
        val from = 2; val rect = IntRect(0, 0, w, h)
        val below = Compositor.renderRangeSampled(doc, rect, 1, 0, from, null)
        val above = Compositor.renderRangeSampled(doc, rect, 1, from + 1, doc.root.children.size, null)
        val layerPx = Compositor.renderLayerSampled(moved, rect, 1)   // unverschoben, Maske eingerechnet
        fun over(dst: FloatArray, c: Int, alpha: Float) {
            val a = (c ushr 24) / 255f * alpha
            if (a <= 0f) return
            for (k in 0..2) dst[k] = ((c shr (16 - 8 * k)) and 0xFF) / 255f * a + dst[k] * (1 - a)
            dst[3] = a + dst[3] * (1 - a)
        }
        val want = Compositor.renderArgbSampled(doc, rect, 1)
        var maxDiff = 0
        for (y in 0 until h) for (x in 0 until w) {
            val d = FloatArray(4)
            over(d, below[y * w + x], 1f)
            val lx = x - moved.previewDx; val ly = y - moved.previewDy
            if (lx in 0 until w && ly in 0 until h) over(d, layerPx[ly * w + lx], moved.opacity)
            over(d, above[y * w + x], 1f)
            val got = if (d[3] <= 0f) 0 else ((d[3] * 255 + 0.5f).toInt() shl 24) or
                ((d[0] / d[3] * 255 + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((d[1] / d[3] * 255 + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (d[2] / d[3] * 255 + 0.5f).toInt().coerceIn(0, 255)
            val ref = want[y * w + x]
            for (sh in intArrayOf(24, 16, 8, 0)) maxDiff = maxOf(maxDiff, kotlin.math.abs(((got shr sh) and 0xFF) - ((ref shr sh) and 0xFF)))
        }
        assertTrue("Abweichung $maxDiff", maxDiff <= 3)
    }
}
