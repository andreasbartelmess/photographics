package de.photographics.core.gpu

import de.photographics.core.adjust.AdjParam
import de.photographics.core.adjust.AdjustmentType
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor

/**
 * Selbstprüfung GPU gegen CPU auf dem jeweiligen Gerät: jeder Mischmodus, jede Einstellung (auch
 * mit Maske und als Schnittmaske), Masken, Schnittmasken, isolierte und hindurchwirkende Gruppen,
 * verschobene Ebenen. Liefert die Abweichungen (leer = alles gleich bis auf Rundung).
 */
object GpuSelfCheck {
    private const val W = 520; private const val H = 390

    private fun grid(f: (Int, Int) -> Int) = TileGrid.fromPixels(W, H, IntArray(W * H) { i -> f(i % W, i / W) })

    private fun backdrop() = grid { x, y ->
        val a = if (y < 40) 0 else if (x % 211 < 30) 90 else 255
        (a shl 24) or ((x * 255 / W) shl 16) or ((y * 255 / H) shl 8) or ((x xor y) and 0xFF)
    }

    private fun layer() = grid { x, y ->
        val d = kotlin.math.hypot((x - 260).toDouble(), (y - 190).toDouble())
        val a = (255 * (1.2 - d / 200)).toInt().coerceIn(0, 255)
        (a shl 24) or (((y * 3) and 0xFF) shl 16) or (((x + y) and 0xFF) shl 8) or ((x * 5) and 0xFF)
    }

    /** Alle Prüfdokumente mit Namen – nacheinander erzeugt, damit nie alle zugleich im Speicher liegen. */
    fun documents(): Sequence<Pair<String, Document>> = sequence {
        for (m in BlendMode.entries) {
            val doc = Document(W, H)
            doc.root.add(PixelLayer("Hintergrund", backdrop()))
            doc.root.add(PixelLayer(m.name, layer()).also { it.blendMode = if (m == BlendMode.PASS_THROUGH) BlendMode.NORMAL else m; it.opacity = 0.8f })
            yield(m.name to doc)
        }
        for (t in AdjustmentType.entries) {
            val def = t.createDefault(); val ps = def.params()
            val adj = def.withValues(FloatArray(ps.size) { i -> val p = ps[i]; if (p.kind == AdjParam.Kind.SWITCH) 1f else p.min + (p.max - p.min) * 0.7f })
            for (variant in 0..2) {
                val doc = Document(W, H)
                doc.root.add(PixelLayer("Hintergrund", backdrop()))
                doc.root.add(PixelLayer("Basis", layer()))
                val a = AdjustmentLayer(t.name, adj)
                when (variant) {
                    1 -> { a.opacity = 0.6f; a.blendMode = BlendMode.LUMINOSITY; a.mask = LayerMask(grid { x, _ -> x * 255 / W }) }
                    2 -> a.clipped = true
                }
                doc.root.add(a)
                yield("$t/$variant" to doc)
            }
        }
        yield("Gemischt" to mixed())
    }

    private fun mixed(): Document {
        val doc = Document(W, H)
        doc.root.add(PixelLayer("Hintergrund", backdrop()))
        val masked = PixelLayer("Maskiert", layer())
        masked.mask = LayerMask(TileGrid(W, H, 255).also { m ->
            for (y in 80 until 240) for (x in 90 until 330) { val dx = (x - 210) / 120.0; val dy = (y - 160) / 80.0; if (dx * dx + dy * dy < 1) m[x, y] = ((1 - dx * dx - dy * dy) * 255).toInt().coerceIn(0, 255) xor 0xFF }
        })
        masked.previewDx = 37; masked.previewDy = -21; masked.blendMode = BlendMode.MULTIPLY
        doc.root.add(masked)
        val base = PixelLayer("Basis", grid { x, y -> if (x in 230..490 && y in 40..360) 0xC0E04080.toInt() else 0 })
        base.blendMode = BlendMode.SCREEN; base.opacity = 0.7f
        doc.root.add(base)
        doc.root.add(PixelLayer("Geschnitten 1", layer()).also { it.clipped = true; it.blendMode = BlendMode.OVERLAY })
        doc.root.add(PixelLayer("Geschnitten 2", grid { x, _ -> if (x % 40 < 20) 0xFF2040F0.toInt() else 0 }).also { it.clipped = true; it.opacity = 0.5f })
        val g1 = GroupLayer("Gruppe")
        g1.blendMode = BlendMode.DIFFERENCE; g1.opacity = 0.85f
        g1.mask = LayerMask(grid { x, _ -> x * 255 / W })
        g1.add(PixelLayer("In Gruppe", grid { x, y -> if ((x / 64 + y / 64) % 2 == 0) 0xFF40C060.toInt() else 0x80FFFF00.toInt() }))
        g1.add(PixelLayer("Sprenkeln", layer()).also { it.blendMode = BlendMode.DISSOLVE; it.opacity = 0.6f })
        doc.root.add(g1)
        val g2 = GroupLayer("Hindurch")
        g2.blendMode = BlendMode.PASS_THROUGH; g2.opacity = 0.6f
        g2.mask = LayerMask(grid { _, y -> 255 - y * 255 / H })
        g2.add(PixelLayer("Farbe", grid { x, y -> 0xFF000000.toInt() or ((x * 7 and 0xFF) shl 16) or ((y * 3 and 0xFF) shl 8) or 0x80 }).also { it.blendMode = BlendMode.COLOR })
        g2.add(PixelLayer("Weich", layer()).also { it.blendMode = BlendMode.SOFT_LIGHT })
        doc.root.add(g2)
        val outside = PixelLayer("Außen", TileGrid(W, H, area = IntRect(-300, -200, W + 300, H + 200)).also { g ->
            for (y in -200 until 0) for (x in -300 until 200) g[x, y] = 0xFFFF8800.toInt()
        })
        outside.previewDx = 150; outside.previewDy = 120
        doc.root.add(outside)
        return doc
    }

    /**
     * Vergleicht [g] mit der CPU auf allen Prüfdokumenten bei den Schritten [steps]. Kippkanten
     * (Hart mischen, Sprenkeln …) dürfen an einzelnen Pixeln abweichen.
     */
    fun run(g: GpuCompositor, steps: IntArray = intArrayOf(1, 2, 8), tol: Int = 2): List<String> {
        val errors = ArrayList<String>()
        for ((name, doc) in documents()) for (step in steps) {
            if (!g.supports(doc, step)) continue
            val lw = (W + step - 1) / step; val lh = (H + step - 1) / step
            for (r in listOf(IntRect(0, 0, lw, lh), IntRect(lw / 5, lh / 7, lw - lw / 9, lh - 1))) {
                if (r.isEmpty) continue
                Compositor.prepare(doc, step, r)
                val cpu = Compositor.renderArgbSampled(doc, r, step)
                val out = g.renderArgb(doc, r, step)
                var worst = 0; var over = 0; var at = ""
                for (i in cpu.indices) {
                    val a = cpu[i]; val c = out[i]
                    if ((a ushr 24) == 0 && (c ushr 24) == 0) continue
                    val alpha = maxOf(a ushr 24, c ushr 24)
                    var d = kotlin.math.abs((a ushr 24) - (c ushr 24))
                    if (alpha >= 16) for (s in intArrayOf(16, 8, 0)) d = maxOf(d, kotlin.math.abs(((a shr s) and 0xFF) - ((c shr s) and 0xFF)))
                    if (d > tol) over++
                    if (d > worst) { worst = d; at = "bei ${r.left + i % r.width},${r.top + i / r.width}: GPU ${Integer.toHexString(c)} CPU ${Integer.toHexString(a)}" }
                }
                if (worst > tol && over > maxOf(2, cpu.size / 5000)) errors += "$name Schritt $step $r: Abweichung $worst an $over Pixeln, $at"
            }
        }
        return errors
    }
}
