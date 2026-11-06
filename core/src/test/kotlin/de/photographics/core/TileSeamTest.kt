package de.photographics.core

import de.photographics.core.adjust.CurvesAdj
import de.photographics.core.doc.*
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kachelweise gerendert (wie die Anzeige: 256er-Kacheln mit 4 px Rand) muss pixelgleich mit dem
 * Rendern in einem Stück sein – auf jeder Zoomstufe, auch über transparenten Flächen mit Effekten.
 */
class TileSeamTest {
    private val TILE = 256; private val PAD = 4

    private fun doc(): Document {
        val w = 1500; val h = 1100
        val doc = Document(w, h)
        fun grid(f: (Int, Int) -> Int) = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> f(i % w, i / w) })
        // Weiche, halbtransparente Flecken über Kachelgrenzen (Farbe auch unter Alpha 0 „bunt“)
        val blobs = grid { x, y ->
            val d = kotlin.math.hypot((x % 300 - 150).toDouble(), (y % 300 - 150).toDouble())
            val a = (255 * (1 - d / 150)).toInt().coerceIn(0, 255)
            (a shl 24) or (((x * 13) and 0xFF) shl 16) or (((y * 7) and 0xFF) shl 8) or 0x40
        }
        doc.root.add(PixelLayer("Flecken", blobs))
        val sq = PixelLayer("Quadrat", grid { x, y -> if (x in 240..780 && y in 200..530) 0xFF3080E0.toInt() else 0x00FF00FF })
        doc.root.add(sq)
        val mul = PixelLayer("Multiplizieren", grid { x, y -> if ((x / 97 + y / 61) % 3 == 0) 0x80C04020.toInt() else 0 })
        mul.blendMode = BlendMode.MULTIPLY; mul.opacity = 0.6f
        doc.root.add(mul)
        doc.root.add(AdjustmentLayer("Kurven", CurvesAdj()).also { it.mask = LayerMask(grid { x, _ -> x * 255 / w }) })
        val ed = Editor(doc)
        ed.setEffects(sq, LayerEffects(
            dropShadows = listOf(LayerEffects.Shadow(0, 0.7f, BlendMode.MULTIPLY, 120.0, false, 24.0, 0.1, 30.0)),
            outerGlow = LayerEffects.Glow(0xFFEE00, 0.8f, BlendMode.SCREEN, 0.0, 40.0),
            strokes = listOf(LayerEffects.Stroke(0xFF0000, 1f, BlendMode.NORMAL, 9.0, LayerEffects.StrokePosition.OUTSIDE)),
        ))
        return doc
    }

    /** Testdokument als PSD für Sichttests der Anzeige: PG_SEAM_PSD=datei.psd. */
    @Test
    fun writeFixture() {
        val out = System.getenv("PG_SEAM_PSD")?.takeIf { it.isNotBlank() } ?: return
        java.io.File(out).writeBytes(de.photographics.core.psd.PsdWriter.write(doc()))
    }

    @Test
    fun tilesMatchWholeRenderAtEveryStep() {
        val doc = doc()
        val report = StringBuilder()
        for (step in intArrayOf(1, 2, 3, 4, 8, 16, 32, 64)) {
            val lw = (doc.width + step - 1) / step; val lh = (doc.height + step - 1) / step
            Compositor.prepare(doc, step, IntRect.ofSize(lw, lh))
            val whole = Compositor.renderArgbSampled(doc, IntRect.ofSize(lw, lh), step)
            var worst = 0; var at = ""
            for (ty in 0 until (lh + TILE - 1) / TILE) for (tx in 0 until (lw + TILE - 1) / TILE) {
                val r = IntRect(maxOf(0, tx * TILE - PAD), maxOf(0, ty * TILE - PAD), minOf((tx + 1) * TILE + PAD, lw), minOf((ty + 1) * TILE + PAD, lh))
                val px = Compositor.renderArgbSampled(doc, r, step)
                for (y in r.top until r.bottom) for (x in r.left until r.right) {
                    val a = px[(y - r.top) * r.width + x - r.left]; val b = whole[y * lw + x]
                    // Vollständig transparente Pixel: Farbe egal
                    if ((a ushr 24) == 0 && (b ushr 24) == 0) continue
                    var d = 0
                    for (s in intArrayOf(24, 16, 8, 0)) d = maxOf(d, kotlin.math.abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)))
                    if (d > worst) { worst = d; at = "Kachel $tx/$ty bei $x,$y: ${Integer.toHexString(a)} statt ${Integer.toHexString(b)}" }
                }
            }
            report.append("Schritt $step: max. Abweichung $worst $at\n")
        }
        println(report)
        assertTrue(report.toString(), report.lines().filter { it.isNotBlank() }.all { it.substringAfter("Abweichung ").substringBefore(" ").toInt() <= 1 })
    }
}
