package de.photographics.gpu

import de.photographics.core.doc.*
import de.photographics.core.gpu.GpuCompositor
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/** GPU-Zusammensetzen muss (bis auf Rundung) dasselbe Bild liefern wie die CPU. */
class GpuVsCpuTest {
    companion object {
        private var egl: HeadlessEgl? = null
        private var gpu: GpuCompositor? = null

        @BeforeClass @JvmStatic fun setUp() {
            egl = HeadlessEgl.open()
            if (egl != null) gpu = GpuCompositor(LwjglGles()).also { println("GPU: ${it.renderer}, Float-Puffer ${it.floatBuffers}, an Ort und Stelle ${it.inPlace}") }
        }

        @AfterClass @JvmStatic fun tearDown() { gpu?.dispose(); egl?.close() }
    }

    private val w = 700; private val h = 530

    private fun grid(f: (Int, Int) -> Int) = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> f(i % w, i / w) })

    /** Bunter Hintergrund mit weichen, halbtransparenten Bereichen. */
    private fun backdrop() = grid { x, y ->
        val a = if (y < 60) 0 else if (x % 211 < 30) 90 else 255
        (a shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x xor y) and 0xFF)
    }

    private fun layer() = grid { x, y ->
        val d = kotlin.math.hypot((x - 350).toDouble(), (y - 260).toDouble())
        val a = (255 * (1.2 - d / 260)).toInt().coerceIn(0, 255)
        (a shl 24) or (((y * 3) and 0xFF) shl 16) or (((x + y) and 0xFF) shl 8) or ((x * 5) and 0xFF)
    }

    private fun compare(doc: Document, label: String, steps: IntArray = intArrayOf(1, 2, 3, 4, 8, 64), tol: Int = 2): String {
        val g = gpu!!
        assumeTrue(g.supports(doc))
        val sb = StringBuilder()
        for (step in steps) {
            val lw = (w + step - 1) / step; val lh = (h + step - 1) / step
            // Ausschnitt mit krummem Ursprung (wie eine verschobene Ansicht) und das ganze Bild
            for (r in listOf(IntRect(0, 0, lw, lh), IntRect(lw / 5, lh / 7, lw - lw / 9, lh - 1))) {
                if (r.isEmpty) continue
                Compositor.prepare(doc, step, r)
                val cpu = Compositor.renderArgbSampled(doc, r, step)
                val b = g.render(doc, r, step)
                val out = g.readArgb(b); g.release(b)
                var worst = 0; var at = ""; var over = 0
                for (i in cpu.indices) {
                    val a = cpu[i]; val c = out[i]
                    if ((a ushr 24) == 0 && (c ushr 24) == 0) continue
                    // Farbe fast transparenter Pixel ist schlecht bestimmt: Toleranz nach Deckkraft
                    val alpha = maxOf(a ushr 24, c ushr 24)
                    var d = kotlin.math.abs((a ushr 24) - (c ushr 24))
                    if (alpha >= 16) for (s in intArrayOf(16, 8, 0)) d = maxOf(d, kotlin.math.abs(((a shr s) and 0xFF) - ((c shr s) and 0xFF)))
                    if (d > tol) over++
                    if (d > worst) { worst = d; at = "bei ${r.left + i % r.width},${r.top + i / r.width}: GPU ${Integer.toHexString(c)} CPU ${Integer.toHexString(a)}" }
                }
                // Kippkanten (Hart mischen, Dunklere Farbe, Sprenkeln …): einzelne Pixel dürfen kippen
                if (worst > tol && over > maxOf(2, cpu.size / 5000)) sb.append("$label Schritt $step $r: Abweichung $worst an $over Pixeln, $at\n")
            }
        }
        return sb.toString()
    }

    @Test fun everyBlendModeMatches() {
        assumeTrue(gpu != null)
        val errors = StringBuilder()
        for (m in BlendMode.entries) {
            val doc = Document(w, h)
            doc.root.add(PixelLayer("Hintergrund", backdrop()))
            doc.root.add(PixelLayer(m.name, layer()).also { it.blendMode = if (m == BlendMode.PASS_THROUGH) BlendMode.NORMAL else m; it.opacity = 0.8f })
            errors.append(compare(doc, m.name, steps = intArrayOf(1, 2, 8)))
        }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun masksClippingGroupsOffsets() {
        assumeTrue(gpu != null)
        val doc = Document(w, h)
        doc.root.add(PixelLayer("Hintergrund", backdrop()))
        // Maske mit Grundwert 255 und ausgesparter Ellipse; Ebene in der Vorschau verschoben
        val masked = PixelLayer("Maskiert", layer())
        masked.mask = LayerMask(TileGrid(w, h, 255).also { m ->
            for (y in 100 until 300) for (x in 120 until 420) { val dx = (x - 270) / 150.0; val dy = (y - 200) / 100.0; if (dx * dx + dy * dy < 1) m[x, y] = ((1 - dx * dx - dy * dy) * 255).toInt().coerceIn(0, 255) xor 0xFF }
        })
        masked.previewDx = 37; masked.previewDy = -21
        masked.blendMode = BlendMode.MULTIPLY
        doc.root.add(masked)
        // Schnittmaske: Basis mit Bildschirm 70 %, darauf zwei geschnittene Ebenen
        val base = PixelLayer("Basis", grid { x, y -> if (x in 300..650 && y in 50..480) 0xC0E04080.toInt() else 0 })
        base.blendMode = BlendMode.SCREEN; base.opacity = 0.7f
        doc.root.add(base)
        doc.root.add(PixelLayer("Geschnitten 1", layer()).also { it.clipped = true; it.blendMode = BlendMode.OVERLAY })
        doc.root.add(PixelLayer("Geschnitten 2", grid { x, _ -> if (x % 40 < 20) 0xFF2040F0.toInt() else 0 }).also { it.clipped = true; it.opacity = 0.5f })
        // Isolierte Gruppe mit Modus, Deckkraft und Maske
        val g1 = GroupLayer("Gruppe")
        g1.blendMode = BlendMode.DIFFERENCE; g1.opacity = 0.85f
        g1.mask = LayerMask(grid { x, _ -> x * 255 / w })
        g1.add(PixelLayer("In Gruppe", grid { x, y -> if ((x / 64 + y / 64) % 2 == 0) 0xFF40C060.toInt() else 0x80FFFF00.toInt() }))
        g1.add(PixelLayer("Sprenkeln", layer()).also { it.blendMode = BlendMode.DISSOLVE; it.opacity = 0.6f })
        doc.root.add(g1)
        // Hindurchwirkende Gruppe mit Deckkraft und Maske, darin Farbe und Weiches Licht
        val g2 = GroupLayer("Hindurch")
        g2.blendMode = BlendMode.PASS_THROUGH; g2.opacity = 0.6f
        g2.mask = LayerMask(grid { _, y -> 255 - y * 255 / h })
        g2.add(PixelLayer("Farbe", grid { x, y -> 0xFF000000.toInt() or ((x * 7 and 0xFF) shl 16) or ((y * 3 and 0xFF) shl 8) or 0x80 }).also { it.blendMode = BlendMode.COLOR })
        g2.add(PixelLayer("Weich", layer()).also { it.blendMode = BlendMode.SOFT_LIGHT })
        doc.root.add(g2)
        // Pixel außerhalb der Arbeitsfläche (verschoben ins Bild)
        val outside = PixelLayer("Außen", TileGrid(w, h, area = IntRect(-300, -200, w + 300, h + 200)).also { g ->
            for (y in -200 until 0) for (x in -300 until 200) g[x, y] = 0xFFFF8800.toInt()
        })
        outside.previewDx = 150; outside.previewDy = 120
        doc.root.add(outside)
        doc.root.add(PixelLayer("Unsichtbar", layer()).also { it.visible = false })
        val errors = compare(doc, "Gemischt")
        assertTrue(errors, errors.isEmpty())
    }

    @Test fun repeatedRenderReusesTexturesAndSeesEdits() {
        assumeTrue(gpu != null)
        val doc = Document(w, h)
        val l = PixelLayer("Bild", backdrop())
        doc.root.add(l)
        assertTrue(compare(doc, "vorher", intArrayOf(1)).isEmpty())
        // Eine Kachel ändern: nur diese wird neu hochgeladen, das Ergebnis muss stimmen
        for (y in 10 until 40) for (x in 10 until 40) l.pixels[x, y] = 0xFF00FF00.toInt()
        val errors = compare(doc, "nachher", intArrayOf(1, 2))
        assertTrue(errors, errors.isEmpty())
    }

    @Test fun everyAdjustmentMatches() {
        assumeTrue(gpu != null)
        val errors = StringBuilder()
        for (t in de.photographics.core.adjust.AdjustmentType.entries) {
            val def = t.createDefault()
            val ps = def.params()
            // Werte deutlich neben der Grundeinstellung (Schalter an)
            val adj = def.withValues(FloatArray(ps.size) { i -> val p = ps[i]; if (p.kind == de.photographics.core.adjust.AdjParam.Kind.SWITCH) 1f else p.min + (p.max - p.min) * 0.7f })
            for (variant in 0..2) {
                val doc = Document(w, h)
                doc.root.add(PixelLayer("Hintergrund", backdrop()))
                val base = PixelLayer("Basis", layer())
                doc.root.add(base)
                val a = AdjustmentLayer(t.name, adj)
                when (variant) {
                    1 -> { a.opacity = 0.6f; a.blendMode = BlendMode.LUMINOSITY; a.mask = LayerMask(grid { x, _ -> x * 255 / w }) }
                    2 -> a.clipped = true
                }
                doc.root.add(a)
                errors.append(compare(doc, "$t/$variant", steps = if (adj.needsFullResolution) intArrayOf(1) else intArrayOf(1, 2, 8)))
            }
        }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    /** Testdokument für Sichttests der Anzeige: PG_GPU_PSD=datei.psd. */
    @Test fun writeFixture() {
        val out = System.getenv("PG_GPU_PSD")?.takeIf { it.isNotBlank() } ?: return
        val doc = Document(w * 4, h * 4)
        fun big(f: (Int, Int) -> Int) = TileGrid.fromPixels(w * 4, h * 4, IntArray(w * h * 16) { i -> f(i % (w * 4), i / (w * 4)) })
        doc.root.add(PixelLayer("Hintergrund", big { x, y -> (0xFF shl 24) or ((x * 255 / (w * 4)) shl 16) or ((y * 255 / (h * 4)) shl 8) or (((x / 7) xor (y / 5)) and 0xFF) }))
        doc.root.add(PixelLayer("Weich", big { x, y -> val d = kotlin.math.hypot((x - 1400).toDouble(), (y - 1000).toDouble()); (((255 * (1.1 - d / 900)).toInt().coerceIn(0, 255)) shl 24) or 0xE04020 }).also { it.blendMode = BlendMode.OVERLAY })
        val g = GroupLayer("Gruppe").also { it.blendMode = BlendMode.MULTIPLY; it.opacity = 0.8f; it.mask = LayerMask(big { x, _ -> x * 255 / (w * 4) }) }
        g.add(PixelLayer("Streifen", big { x, _ -> if (x % 120 < 60) 0xFF60A0F0.toInt() else 0 }))
        doc.root.add(g)
        doc.root.add(AdjustmentLayer("Farbton", de.photographics.core.adjust.HueSaturationAdj(hue = 40, saturation = 30)))
        doc.root.add(AdjustmentLayer("Kurven", de.photographics.core.adjust.CurvesAdj().withValues(floatArrayOf(50f, 140f, 210f))))
        java.io.File(out).writeBytes(de.photographics.core.psd.PsdWriter.write(doc))
    }

    /** Die Selbstprüfung (läuft auch im Geräte-Benchmark) findet auf dieser GPU nichts. */
    @Test fun selfCheckPasses() {
        assumeTrue(gpu != null)
        val errors = de.photographics.core.gpu.GpuSelfCheck.run(gpu!!)
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }
}
