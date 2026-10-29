package de.photographics.core.editor

import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.tr
import kotlin.math.abs
import kotlin.math.hypot

enum class GradientType(val label: String) { LINEAR(tr("Linear")), RADIAL(tr("Radial")), REFLECTED(tr("Gespiegelt")) }

/**
 * Verlauf von ([x0], [y0]) nach ([x1], [y1]): Farbe [c0] → [c1] (nicht vormultipliziert gemischt,
 * Deckung vormultipliziert – kein dunkler Saum zu transparent). Leichtes Dithering gegen Abrisse.
 */
class Gradient(
    val type: GradientType, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val c0: Int, val c1: Int,
) {
    private val dx = x1 - x0; private val dy = y1 - y0
    private val len2 = maxOf(1e-6f, dx * dx + dy * dy)
    private val len = kotlin.math.sqrt(len2)

    /** Lage 0..1 eines Pixelmittelpunkts. */
    fun t(x: Int, y: Int): Float {
        val px = x + 0.5f - x0; val py = y + 0.5f - y0
        return when (type) {
            GradientType.LINEAR -> ((px * dx + py * dy) / len2).coerceIn(0f, 1f)
            GradientType.RADIAL -> (hypot(px, py) / len).coerceIn(0f, 1f)
            GradientType.REFLECTED -> abs((px * dx + py * dy) / len2).coerceIn(0f, 1f)
        }
    }

    /** Farbe bei [t] mit Dithering am Pixel ([x], [y]). */
    fun color(t: Float, x: Int, y: Int): Int {
        val n = noise(x, y) - 0.5f
        val a0 = (c0 ushr 24) / 255f; val a1 = (c1 ushr 24) / 255f
        val a = a0 + (a1 - a0) * t
        if (a <= 0f) return 0
        // Farbe gewichtet nach Deckung mischen (wie vormultipliziert), dann zurückrechnen
        fun ch(s: Int): Int {
            val v0 = ((c0 shr s) and 0xFF) * a0; val v1 = ((c1 shr s) and 0xFF) * a1
            return ((v0 + (v1 - v0) * t) / a + n + 0.5f).toInt().coerceIn(0, 255)
        }
        return ((a * 255f + n + 0.5f).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Deterministisches Rauschen 0..1 je Pixel (Dithering). */
    private fun noise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    /** Grauwert für Masken: Helligkeit des Verlaufs. */
    fun gray(t: Float, x: Int, y: Int): Int {
        val c = color(t, x, y)
        return (Colors.luma(Colors.r(c).toFloat(), Colors.g(c).toFloat(), Colors.b(c).toFloat()) + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Schreibt den Verlauf in [grid] (Pixel oder Maske [scalar]) über [area], gemischt mit Deckkraft
     * [opacity] und Auswahl [sel] (Rohwerte 0..255 oder null). [lockAlpha]: Deckung des Ziels bleibt.
     */
    fun apply(grid: TileGrid, area: IntRect, opacity: Float, sel: TileGrid?, scalar: Boolean, lockAlpha: Boolean) {
        val band = TileGrid.TILE
        var y = Math.floorDiv(area.top, band) * band
        while (y < area.bottom) {
            val r = IntRect(area.left, maxOf(area.top, y), area.right, minOf(area.bottom, y + band))
            y += band
            if (r.isEmpty) continue
            val n = r.width * r.height
            val px = IntArray(n); grid.readRect(r, px)
            val sv = sel?.let { IntArray(n).also { s -> it.readRect(r, s) } }
            for (k in 0 until n) {
                val s = if (sv != null) sv[k] / 255f else 1f
                val amount = opacity * s
                if (amount <= 0f) continue
                val x = r.left + k % r.width; val yy = r.top + k / r.width
                val t = t(x, yy)
                px[k] = if (scalar) {
                    val g = gray(t, x, yy)
                    (px[k] + (g - px[k]) * amount + 0.5f).toInt().coerceIn(0, 255)
                } else {
                    val src = color(t, x, yy)
                    val over = Colors.srcOver(px[k], Colors.withAlpha(src, ((src ushr 24) * amount + 0.5f).toInt()))
                    if (lockAlpha) Colors.withAlpha(over, px[k] ushr 24) else over
                }
            }
            grid.writeRect(r, px)
        }
    }
}
