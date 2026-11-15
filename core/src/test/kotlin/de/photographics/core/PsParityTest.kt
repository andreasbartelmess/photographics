package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.filters.FilterCatalog
import de.photographics.core.filters.PreparedFilter
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Abgleich mit Photoshop (Test-VM, ~/vm-tools/parity): PG_PARITY_DIR=Verzeichnis mit jobs.tsv.
 *   PG_PARITY_MODE=input    schreibt input.psd (Testbild)
 *   PG_PARITY_MODE=compare  vergleicht ps/<job>.psd (in Photoshop gefiltert) mit unserem Filter
 * Ausgabe je Auftrag: mittlere/maximale Abweichung (0–255) und Anteil Pixel mit Abweichung > 8.
 * Rauschfilter (zufällig) werden über Mittelwert und Streuung der Differenz zum Original verglichen.
 */
class PsParityTest {
    private val w = 400
    private val h = 300

    private fun inputPixels(): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        var r = x * 255 / (w - 1); var g = y * 255 / (h - 1); var b = 128
        // Farbflächen mit harten Kanten
        if (x in 60..160 && y in 50..140) { r = 230; g = 40; b = 30 }
        val dx = x - 280; val dy = y - 150
        if (dx * dx + dy * dy < 70 * 70) { r = 30; g = 80; b = 220 }
        // feine Strukturen: Linien und Schachbrett
        if (y in 200..260 && x in 40..200 && (x / 3) % 2 == 0) { r = 250; g = 250; b = 250 }
        if (y in 200..260 && x in 220..360 && ((x / 6) + (y / 6)) % 2 == 0) { r = 10; g = 10; b = 10 }
        // weiche Textur (deterministisch)
        val t = ((x * 7919 + y * 104729) xor (x * y)) and 15
        r = (r + t - 8).coerceIn(0, 255); g = (g + t - 8).coerceIn(0, 255); b = (b + t - 8).coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test fun parity() {
        val dir = File(System.getenv("PG_PARITY_DIR") ?: return)
        val src = inputPixels()
        System.getenv("PG_PARITY_COORD")?.let { spec ->
            // Koordinatenbild (Rot = x, Grün = y) durch unseren Filter: Geometrie direkt vergleichbar
            val (id, vals, out) = spec.split(';')
            val c = IntArray(w * h) { i -> val x = i % w; val y = i / w
                (0xFF shl 24) or (Math.round(x * 255f / (w - 1)) shl 16) or (Math.round(y * 255f / (h - 1)) shl 8) or 128 }
            val sp = FilterCatalog.byId(id)!!; val v = vals.split(',').map { it.toFloat() }
            val o = sp.build(FloatArray(sp.params.size) { v.getOrElse(it) { sp.params[it].default } }).process(c, w, h)
            val d2 = Document(w, h); d2.root.add(PixelLayer("c", TileGrid.fromPixels(w, h, o)))
            File(dir, out).writeBytes(PsdWriter.write(d2)); return
        }
        if (System.getenv("PG_PARITY_MODE") == "input") {
            val doc = Document(w, h); doc.root.add(PixelLayer("Ebene", TileGrid.fromPixels(w, h, src)))
            File(dir, "input.psd").writeBytes(PsdWriter.write(doc)); println("PARITY input.psd geschrieben"); return
        }
        for (line in File(dir, "jobs.tsv").readLines().filter { it.isNotBlank() }) {
            val (job, id, vals) = line.split('\t')
            val ps = File(dir, "ps/$job.psd")
            if (!ps.exists()) { println("PARITY %-22s fehlt".format(job)); continue }
            val doc = PsdReader.read(ps.readBytes())
            val psPx = IntArray(w * h).also { doc.root.children.filterIsInstance<PixelLayer>().last().pixels.readRect(de.photographics.core.image.IntRect(0, 0, w, h), it) }
            val spec = FilterCatalog.byId(id)!!
            val v = vals.split(',').filter { it.isNotBlank() }.map { it.toFloat() }
            val f = spec.build(FloatArray(spec.params.size) { v.getOrElse(it) { spec.params[it].default } })
            if (f is PreparedFilter) f.prepare({ x, y -> src[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)] }, w, h)
            val ours = f.process(src.copyOf(), w, h)
            if (id == "add-noise") {
                fun stats(p: IntArray): Pair<Double, Double> {
                    var s = 0.0; var s2 = 0.0; var n = 0
                    for (i in p.indices) for (sh in intArrayOf(16, 8, 0)) { val d = ((p[i] shr sh) and 255) - ((src[i] shr sh) and 255); s += d; s2 += d * d; n++ }
                    val m = s / n; return m to sqrt(s2 / n - m * m)
                }
                val (pm, ps2) = stats(psPx); val (om, os) = stats(ours)
                println("PARITY %-22s Rauschen: Photoshop Mittel %.2f Streuung %.2f | photographics Mittel %.2f Streuung %.2f".format(job, pm, ps2, om, os))
                continue
            }
            var sum = 0.0; var max = 0; var over = 0
            for (i in src.indices) {
                var m = 0
                for (sh in intArrayOf(16, 8, 0)) { val d = abs(((psPx[i] shr sh) and 255) - ((ours[i] shr sh) and 255)); sum += d; if (d > m) m = d }
                if (m > max) max = m; if (m > 8) over++
            }
            println("PARITY %-22s mittel %6.2f  max %3d  >8: %5.1f %%".format(job, sum / (src.size * 3), max, over * 100.0 / src.size))
            if (System.getenv("PG_PARITY_DUMP") != null) {
                val d2 = Document(w, h); d2.root.add(PixelLayer("photographics", TileGrid.fromPixels(w, h, ours)))
                File(dir, "ours").mkdirs(); File(dir, "ours/$job.psd").writeBytes(PsdWriter.write(d2))
            }
        }
    }

    /**
     * PG_GAL_DIR (~/vm-tools/parity/gal): je Zeile von ours.tsv (Auftrag, Effekt, Werte) unseren
     * Filtergalerie-Effekt aufs Testbild rechnen → ours/<Auftrag>.png (Vergleich mit ps/<Auftrag>.psd in cmpours.py).
     */
    @Test fun galleryParity() {
        val dir = File(System.getenv("PG_GAL_DIR") ?: return)
        // PG_GAL_INPUT=Bild (PNG): anderes Eingabebild, Ergebnisse dann in PG_GAL_OUT (Standard ours)
        val inImg = System.getenv("PG_GAL_INPUT")?.let { javax.imageio.ImageIO.read(File(it)) }
        val w = inImg?.width ?: w; val h = inImg?.height ?: h
        val src = inImg?.getRGB(0, 0, w, h, null, 0, w)?.map { it or (0xFF shl 24) }?.toIntArray() ?: inputPixels()
        val outDir = System.getenv("PG_GAL_OUT") ?: "ours"
        val only = System.getenv("PG_GAL_ONLY")?.split(',')
        File(dir, outDir).mkdirs()
        for (line in File(dir, "ours.tsv").readLines().filter { it.isNotBlank() }) {
            val (job, id, vals) = line.split('\t')
            if (only != null && only.none { job.startsWith(it) }) continue
            val spec = de.photographics.core.filters.Gallery.byId(id)!!
            val v = vals.split(',').map { it.toFloat() }.toFloatArray()
            val out = de.photographics.core.filters.GalleryChain(listOf(de.photographics.core.filters.GalleryEffect(spec, v))).process(src.copyOf(), w, h)
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, w, h, out, 0, w)
            javax.imageio.ImageIO.write(img, "png", File(dir, "$outDir/$job.png"))
        }
    }

    /** PG_ADJ_DIR (~/vm-tools/parity/adj): Aufträge aus jobs.tsv mit unseren Einstellungen auf input.png → ours/<job>.png. */
    @Test fun adjustmentParity() {
        val dir = File(System.getenv("PG_ADJ_DIR") ?: return)
        val img = javax.imageio.ImageIO.read(File(dir, "input.png"))
        val w = img.width; val h = img.height
        val src = img.getRGB(0, 0, w, h, null, 0, w)
        File(dir, "ours").mkdirs()
        for (line in File(dir, "jobs.tsv").readLines().filter { it.isNotBlank() }) {
            val parts = line.split('\t')
            val job = parts[0]; val type = de.photographics.core.adjust.AdjustmentType.valueOf(parts[1]); val vals = parts.getOrElse(2) { "" }
            val adj = if (type == de.photographics.core.adjust.AdjustmentType.CURVES)
                de.photographics.core.adjust.CurvesAdj(master = vals.split(';').map { p -> p.split(':').let { it[0].toInt() to it[1].toInt() } })
            else type.createDefault().let { a -> if (vals.isBlank()) a else a.withValues(vals.split(',').map { it.toFloat() }.toFloatArray()) }
            val map = adj.compile()
            val out = IntArray(src.size) { i -> (0xFF shl 24) or (map.map(src[i] and 0xFFFFFF) and 0xFFFFFF) }
            val o = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            o.setRGB(0, 0, w, h, out, 0, w)
            javax.imageio.ImageIO.write(o, "png", File(dir, "ours/$job.png"))
        }
    }

    /** PG_ADJ_PSD_OUT: PSDs (Testbild + Einstellungsebene) und unser Ergebnis als PNG – zum Öffnen in Photoshop. */
    @Test fun adjustmentLayerFixtures() {
        val out = File(System.getenv("PG_ADJ_PSD_OUT") ?: return).also { it.mkdirs() }
        val src = inputPixels()
        val samples = mapOf(
            "mixer" to de.photographics.core.adjust.ChannelMixerAdj(listOf(listOf(80, 20, 0, 10), listOf(0, 100, 0, -20), listOf(-30, 0, 130, 5))),
            "mixer-mono" to de.photographics.core.adjust.ChannelMixerAdj(listOf(listOf(40, 40, 20, 0), listOf(0, 100, 0, 0), listOf(0, 0, 100, 0)), monochrome = true),
            "selective" to de.photographics.core.adjust.SelectiveColorAdj(List(9) { r -> listOf(if (r == 0) -50 else 0, if (r == 4) 30 else 0, if (r == 7) 20 else 0, if (r == 8) 15 else 0) }),
            "selective-abs" to de.photographics.core.adjust.SelectiveColorAdj(List(9) { r -> listOf(if (r == 2) 40 else 0, 0, if (r == 6) -30 else 0, if (r == 7) -10 else 0) }, absolute = true),
            "colorize" to de.photographics.core.adjust.HueSaturationAdj(200, 40, 10, colorize = true),
            "balance" to de.photographics.core.adjust.ColorBalanceAdj(listOf(10, -20, 5), listOf(30, 0, -15), listOf(0, 10, 20), true),
            "brightness" to de.photographics.core.adjust.BrightnessContrastAdj(40, 30),
            "photofilter" to de.photographics.core.adjust.PhotoFilterAdj(200, 40, true),
        )
        for ((name, adj) in samples) {
            val doc = Document(w, h)
            doc.root.add(PixelLayer("Ebene", TileGrid.fromPixels(w, h, src)))
            doc.root.add(de.photographics.core.doc.AdjustmentLayer(adj.label, adj))
            File(out, "adjlayer-$name.psd").writeBytes(PsdWriter.write(doc))
            val px = de.photographics.core.render.Compositor.renderArgb(doc, doc.bounds)
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, w, h, px, 0, w)
            javax.imageio.ImageIO.write(img, "png", File(out, "adjlayer-$name.png"))
        }
    }

    /** PG_FX_OUT: Ebene mit Schlagschatten bei verringerter Deckkraft/Fläche (PSD + unser Ergebnis) zum Vergleich mit Photoshop. */
    @Test fun effectOpacityFixtures() {
        val out = File(System.getenv("PG_FX_OUT") ?: return).also { it.mkdirs() }
        fun shadow(blend: de.photographics.core.render.BlendMode) = de.photographics.core.doc.LayerEffects(
            dropShadows = listOf(de.photographics.core.doc.LayerEffects.Shadow(0x000000, 1f, blend, 135.0, false, 12.0, 0.0, 0.0)))
        val cases = mapOf(
            "deckkraft50" to Triple(0.5f, 1f, de.photographics.core.render.BlendMode.NORMAL),
            "flaeche50" to Triple(1f, 0.5f, de.photographics.core.render.BlendMode.NORMAL),
            "deckkraft50-mult" to Triple(0.5f, 1f, de.photographics.core.render.BlendMode.MULTIPLY),
            "beide" to Triple(0.6f, 0.4f, de.photographics.core.render.BlendMode.NORMAL),
        )
        for ((name, c) in cases) {
            val doc = Document(200, 160)
            doc.root.add(PixelLayer("Hintergrund", TileGrid.fromPixels(200, 160, IntArray(200 * 160) { i -> val x = i % 200; (0xFF shl 24) or (((x * 255) / 199) shl 8) or 0xC0C0 })))
            val l = PixelLayer("Quadrat", TileGrid(200, 160).also { g -> g.writeRect(de.photographics.core.image.IntRect(50, 40, 130, 110), IntArray(80 * 70) { 0xFFD02020.toInt() }) })
            doc.root.add(l)
            l.opacity = c.first; l.fillOpacity = c.second
            de.photographics.core.editor.Editor(doc).setEffects(l, shadow(c.third))
            File(out, "fx-$name.psd").writeBytes(PsdWriter.write(doc))
            val px = de.photographics.core.render.Compositor.renderArgb(doc, doc.bounds)
            val img = java.awt.image.BufferedImage(200, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, 200, 160, px, 0, 200)
            javax.imageio.ImageIO.write(img, "png", File(out, "fx-$name.png"))
        }
    }

    /** PG_FILL_DIR: input.png (aus Photoshop) mit dem Füllwerkzeug füllen wie in f.jsx, Ergebnis als ours-<job>.png. */
    @Test fun fillParity() {
        val dir = File(System.getenv("PG_FILL_DIR") ?: return)
        val img = javax.imageio.ImageIO.read(File(dir, "input.png"))
        val w = img.width; val h = img.height
        val src = IntArray(w * h).also { img.getRGB(0, 0, w, h, it, 0, w) }
        val jobs = listOf(Triple("innen-32-aa", intArrayOf(120, 100, 32), true), Triple("innen-32-noaa", intArrayOf(120, 100, 32), false),
            Triple("innen-0-aa", intArrayOf(120, 100, 0), true), Triple("aussen-32-aa", intArrayOf(10, 10, 32), true),
            Triple("blau-32-aa", intArrayOf(250, 100, 32), true), Triple("blau-100-aa", intArrayOf(250, 100, 100), true))
        for ((job, p, aa) in jobs) {
            val doc = Document(w, h); val layer = PixelLayer("Ebene", TileGrid.fromPixels(w, h, src.copyOf()))
            doc.root.add(layer); doc.activeLayer = layer
            de.photographics.core.editor.Editor(doc).bucketFill(p[0], p[1], 0xFFDC281E.toInt(), p[2], contiguous = true, allLayers = false, antiAlias = aa)
            val out = IntArray(w * h).also { layer.pixels.readRect(de.photographics.core.image.IntRect(0, 0, w, h), it) }
            val bi = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB); bi.setRGB(0, 0, w, h, out, 0, w)
            javax.imageio.ImageIO.write(bi, "png", File(dir, "ours-$job.png"))
        }
        println("FILL fertig")
    }
}

