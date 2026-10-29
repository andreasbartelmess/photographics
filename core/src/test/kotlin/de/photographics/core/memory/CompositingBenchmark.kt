package de.photographics.core.memory
import de.photographics.core.adjust.CurvesAdj
import de.photographics.core.doc.*
import de.photographics.core.editor.Editor
import de.photographics.core.image.*
import de.photographics.core.render.Compositor
import de.photographics.core.render.BlendMode
import org.junit.Test
/** Leistungsmessung großer Dokumente (48 MP): `PG_BENCH=1 ./gradlew :core:memoryTest --tests "*CompositingBenchmark*"`. */
class CompositingBenchmark {
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    @Test fun run() {
        org.junit.Assume.assumeTrue(System.getenv("PG_BENCH") != null)
        TileMemory.current = SlabTileMemory(limitBytes = 2900L shl 20, newSlab = { java.nio.ByteBuffer.allocateDirect(it) })
        val w = 8000; val h = 6000
        val doc = Document(w, h)
        fun photo(seed: Int) = TileGrid(w, h).also { g -> val band = 256; var y = 0; while (y < h) { val r = IntRect(0, y, w, minOf(h, y + band)); g.writeRect(r, IntArray(r.width * r.height) { i -> val x = i % w; val yy = y + i / w; (0xFF shl 24) or (((x * 3 + seed) xor yy) and 0xFF shl 16) or ((x + yy * 2 + seed) and 0xFF shl 8) or ((x * yy + seed) and 0xFF) }); y = r.bottom } }
        doc.root.add(PixelLayer("Hintergrund", photo(1)))
        doc.root.add(PixelLayer("Foto 2", photo(7)).also { it.blendMode = BlendMode.SOFT_LIGHT; it.opacity = 0.7f })
        doc.root.add(AdjustmentLayer("Kurven", CurvesAdj()).also { it.mask = LayerMask(TileGrid(w, h, 255)) })
        val ed = Editor(doc)
        val top = PixelLayer("Oben", TileGrid(w, h).also { g -> g.writeRect(IntRect(1000, 1000, 5000, 4000), IntArray(4000 * 3000) { 0xFFCC4422.toInt() }) })
        doc.root.add(top); doc.activeLayer = top
        fun full(label: String) {
            val t = System.nanoTime(); Compositor.prepare(doc)
            val bh = 64; val n = (h + bh - 1) / bh
            Parallel.bands(n, w.toLong() * bh * 40, compute = { i -> Compositor.renderArgb(doc, IntRect(0, i * bh, w, minOf(h, (i + 1) * bh))) }, consume = { _, _ -> })
            println("   $label: ${ms(t)} ms")
        }
        val all = doc.root.children.toList()
        all.forEach { it.visible = false }
        all[0].visible = true; full("nur Hintergrund")
        all[1].visible = true; full("+ Weiches Licht 70 %")
        all[2].visible = true; full("+ Kurven mit Maske")
        all[3].visible = true; full("+ obere Ebene")
                // a) volle Anzeige s=2 (wie renderNow: volle Auflösung komponieren, alle Kerne)
        var t0 = System.nanoTime()
        Compositor.prepare(doc)
        val bandH = 64; val count = (h + bandH - 1) / bandH
        Parallel.bands(count, w.toLong() * bandH * 40, compute = { i -> Compositor.renderArgb(doc, IntRect(0, i * bandH, w, minOf(h, (i + 1) * bandH))) }, consume = { _, _ -> })
        println("a) Gesamtbild voll komponiert (48 MP, parallel): ${ms(t0)} ms")
        fun display(label: String) {
            val t = System.nanoTime(); Compositor.prepare(doc, 2)
            val ow = w / 2; val oh = h / 2; val bh = 64; val n = (oh + bh - 1) / bh
            Parallel.bands(n, ow.toLong() * bh * 40, compute = { i -> Compositor.renderArgbSampled(doc, IntRect(0, i * bh, ow, minOf(oh, (i + 1) * bh)), 2) }, consume = { _, _ -> })
            println("e) Anzeige 1/2 aus Mipmaps ($label): ${ms(t)} ms")
        }
        display("erster Aufbau"); display("zweites Mal")
        // f) Detailausschnitt 2560×1600 bei 100 % gegen gleich viele Ausgabepixel aus Mipmap-Stufe 1
        fun region(label: String, step: Int) {
            val r = IntRect(1000 / step, 1000 / step, 1000 / step + 2560, 1000 / step + 1600)
            Compositor.prepare(doc, step)
            repeat(2) { k ->
                val t = System.nanoTime(); val bh = 26
                Parallel.bands((r.height + bh - 1) / bh, r.width.toLong() * bh * 40, compute = { i -> Compositor.renderArgbSampled(doc, IntRect(r.left, r.top + i * bh, r.right, minOf(r.bottom, r.top + (i + 1) * bh)), step) }, consume = { _, _ -> })
                println("f) $label (Durchgang ${k + 1}): ${ms(t)} ms")
            }
            val t = System.nanoTime(); val buf = IntArray(r.width * 26)
            for (l in doc.root.children) if (l is PixelLayer) { var y = r.top; while (y < r.bottom) { val rr = IntRect(r.left * step, y * step, r.right * step, minOf(r.bottom, y + 26) * step); if (step == 1) l.pixels.readRect(rr, buf) else l.pixels.mip(1).readRect(IntRect(r.left, y, r.right, minOf(r.bottom, y + 26)), buf); y += 26 } }
            println("f) $label nur Kacheln lesen (1 Kern): ${ms(t)} ms")
        }
        region("4 MP bei 100 %", 1); region("4 MP aus Stufe 1", 2)
        // g) Ebeneneffekte auf einer Textebene-ähnlichen Ebene (viele kleine Formen): Schatten 40 px + Kontur 8 px
        run {
            val fxLayer = PixelLayer("Formen", TileGrid(w, h).also { g ->
                for (k in 0 until 400) { val x = (k * 977) % (w - 200); val y = (k * 613) % (h - 120); g.writeRect(IntRect(x, y, x + 120, y + 60), IntArray(120 * 60) { 0xFFE0E0E0.toInt() }) }
            })
            fxLayer.preservedBlocks = listOf(de.photographics.core.psd.PsdEffects.toBlock(LayerEffects(
                dropShadows = listOf(LayerEffects.Shadow(0, 0.6f, BlendMode.MULTIPLY, 120.0, true, 20.0, 0.0, 40.0)),
                strokes = listOf(LayerEffects.Stroke(0xFF0000, 1f, BlendMode.NORMAL, 8.0, LayerEffects.StrokePosition.OUTSIDE)),
            )))
            doc.root.add(fxLayer)
            for (k in 1..3) {
                if (k == 3) fxLayer.pixels.writeRect(IntRect(3000, 2000, 3100, 2050), IntArray(5000) { -1 })   // kleine Änderung
                val t = System.nanoTime(); Compositor.prepare(doc, 1, doc.bounds)
                val bh = 64; val n = (h + bh - 1) / bh
                Parallel.bands(n, w.toLong() * bh * 40, compute = { i -> Compositor.renderArgb(doc, IntRect(0, i * bh, w, minOf(h, (i + 1) * bh))) }, consume = { _, _ -> })
                println("g) Gesamtbild mit Ebeneneffekten (48 MP, Durchgang $k): ${ms(t)} ms")
            }
            doc.root.remove(fxLayer)
        }
        (doc.root.children[1] as PixelLayer).pixels.writeRect(IntRect(100, 100, 200, 200), IntArray(10000) { -1 })
        display("nach kleiner Änderung")
                // b) Vorschau 1,5 MP
        val step = Math.ceil(Math.sqrt(w.toDouble() * h / 1_500_000)).toInt()
        t0 = System.nanoTime(); Compositor.renderArgbSampled(doc, IntRect(0, 0, (w + step - 1) / step, (h + step - 1) / step), step)
        println("b) Vorschau 1/$step: ${ms(t0)} ms")
        // c) Transformieren: Smartobjekt + Vorschau je Frame
        val smart = ed.convertToSmartObject(top)!!
        val base = smart.transform
        for (k in 1..3) {
            t0 = System.nanoTime()
            ed.previewTransform(smart, base.then(Affine.translate(10.0 * k, 5.0 * k)))
            Compositor.renderArgbSampled(doc, IntRect(0, 0, (w + step - 1) / step, (h + step - 1) / step), step)
            println("c) Transform-Vorschau Frame $k: ${ms(t0)} ms")
        }
        // d) Endgültig (bikubisch)
        t0 = System.nanoTime(); ed.commitTransform(smart, base, base.then(Affine.translate(30.0, 15.0))); smart.raster
        println("d) Transform übernehmen (bikubisch): ${ms(t0)} ms")
    }
}
