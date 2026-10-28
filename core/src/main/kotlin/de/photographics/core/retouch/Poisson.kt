package de.photographics.core.retouch

import de.photographics.core.tools.PullPush
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Poisson-Bildbearbeitung (Pérez et al.): übernimmt die Struktur (Gradienten) der Quelle,
 * passt Farbe und Helligkeit aber nahtlos an den Rand des Zielbereichs an. Grundlage für
 * Reparatur-Pinsel und Ausbessern-Werkzeug.
 */
object Poisson {
    /**
     * [target] und [source]: ARGB-Ausschnitte gleicher Größe; [inside]: zu lösende Pixel.
     * Pixel außerhalb von [inside] bleiben unverändert (Randbedingung).
     */
    fun blend(target: IntArray, source: IntArray, inside: BooleanArray, w: Int, h: Int, iterations: Int = 120): IntArray {
        val n = w * h
        val out = target.copyOf()
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val t = FloatArray(n) { ((target[it] ushr shift) and 0xFF).toFloat() }
            val s = FloatArray(n) { ((source[it] ushr shift) and 0xFF).toFloat() }
            // 1. Startwert: Quelle + glatt interpolierte Randdifferenz (fast schon die Lösung)
            val diff = FloatArray(n * 4)
            val wt = FloatArray(n)
            for (i in 0 until n) {
                if (inside[i]) continue
                val d = t[i] - s[i]
                diff[i * 4] = d; diff[i * 4 + 1] = d; diff[i * 4 + 2] = d; diff[i * 4 + 3] = d
                wt[i] = 1f
            }
            PullPush.fill(diff, wt, w, h)
            val f = FloatArray(n) { if (inside[it]) s[it] + diff[it * 4] else t[it] }
            // 2. Verfeinern mit SOR (Gauss-Seidel mit Überrelaxation)
            val omega = 1.85f
            for (iter in 0 until iterations) {
                var maxDelta = 0f
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (!inside[i]) continue
                    var sum = 0f; var guide = 0f; var cnt = 0
                    if (x > 0) { sum += f[i - 1]; guide += s[i] - s[i - 1]; cnt++ }
                    if (x < w - 1) { sum += f[i + 1]; guide += s[i] - s[i + 1]; cnt++ }
                    if (y > 0) { sum += f[i - w]; guide += s[i] - s[i - w]; cnt++ }
                    if (y < h - 1) { sum += f[i + w]; guide += s[i] - s[i + w]; cnt++ }
                    if (cnt == 0) continue
                    val nv = (sum + guide) / cnt
                    val d = (nv - f[i]) * omega
                    f[i] += d
                    maxDelta = max(maxDelta, abs(d))
                }
                if (maxDelta < 0.2f) break
            }
            for (i in 0 until n) {
                if (!inside[i]) continue
                val v = f[i].roundToInt().coerceIn(0, 255)
                out[i] = (out[i] and (0xFF shl shift).inv()) or (v shl shift)
            }
        }
        return out
    }
}
