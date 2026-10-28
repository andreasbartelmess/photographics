package de.photographics.core.image

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Schnelle Weichzeichner auf Planar-Float-Daten. Drei Box-Durchgänge nähern einen Gaußschen
 * Weichzeichner sehr gut an (Fehler < 3 %), die Laufzeit ist unabhängig vom Radius.
 */
object Blur {
    /** Box-Radien für drei Durchgänge, die zusammen etwa ein Gauß mit [sigma] ergeben. */
    fun boxesForGauss(sigma: Float, n: Int = 3): IntArray {
        if (sigma <= 0f) return IntArray(n)
        val wIdeal = sqrt(12f * sigma * sigma / n + 1f)
        var wl = wIdeal.toInt(); if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12f * sigma * sigma - n * wl * wl - 4f * n * wl - 3f * n) / (-4f * wl - 4f)
        val m = mIdeal.roundToInt()
        return IntArray(n) { i -> ((if (i < m) wl else wu) - 1) / 2 }
    }

    /** Gauß-Approximation für einen Kanal (in-place). */
    fun gaussPlane(data: FloatArray, w: Int, h: Int, sigma: Float) {
        if (sigma <= 0f || w == 0 || h == 0) return
        val tmp = FloatArray(data.size)
        for (r in boxesForGauss(sigma)) {
            if (r <= 0) continue
            boxH(data, tmp, w, h, r)
            boxV(tmp, data, w, h, r)
        }
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = acc * inv
                val add = (x + r + 1).coerceAtMost(w - 1)
                val sub = (x - r).coerceAtLeast(0)
                acc += src[row + add] - src[row + sub]
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = acc * inv
                val add = (y + r + 1).coerceAtMost(h - 1)
                val sub = (y - r).coerceAtLeast(0)
                acc += src[add * w + x] - src[sub * w + x]
            }
        }
    }

    /**
     * Gauß-Weichzeichner für nicht vormultiplizierte ARGB-Pixel. Intern wird vormultipliziert,
     * damit transparente Bereiche keine dunklen Säume erzeugen.
     */
    fun gaussArgb(px: IntArray, w: Int, h: Int, sigma: Float): IntArray {
        // Kanäle nacheinander (Alpha + ein Farbkanal gleichzeitig) – spart die Hälfte an Speicher
        val n = w * h
        val a = FloatArray(n) { (px[it] ushr 24) / 255f }
        val alphaSrc = a.copyOf()
        gaussPlane(a, w, h, sigma)
        val out = IntArray(n)
        for (i in 0 until n) out[i] = (a[i] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24
        val c = FloatArray(n)
        for (shift in intArrayOf(16, 8, 0)) {
            for (i in 0 until n) c[i] = ((px[i] shr shift) and 0xFF) * alphaSrc[i]
            gaussPlane(c, w, h, sigma)
            for (i in 0 until n) {
                val af = a[i]
                if (af > 1e-5f) out[i] = out[i] or ((c[i] / af + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
        }
        for (i in 0 until n) if ((out[i] ushr 24) == 0) out[i] = 0
        return out
    }
}
