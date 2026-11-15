package de.photographics.core.adjust

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Dynamik wie in Photoshop 2026 (nachgemessen an 1,4 Mio. Farben je Reglerstellung, mittlere
 * Abweichung 0,1–0,5, siehe vm-tools/parity/vib). Alles im linearen sRGB:
 *
 * 1. Dynamik: Das Maximum bleibt, die übrigen Kanäle rücken mit dem Faktor k₀ = 1 + g zum Maximum
 *    hin bzw. davon weg (HSV-Sättigung S = 1 − Min/Max). g hängt von Max und Min ab und wird je
 *    Reglerstellung als Tabelle 33×33 über (Max, Min) in Gamma-Werten vorbereitet:
 *    negativ g = −(A(S)·t − B(S)·t²) mit S·min(1, Max/62) (dunkle Farben gelten als weniger
 *    gesättigt), positiv aus gemessenen Tabellen, für Hauttöne (Farbton 300°…45°) gedämpft.
 * 2. Helligkeit: das Pixel wird mit b = 1 ∓ c·t·(1 − Max)·r·(1 − r)·min(1, Max/62) skaliert
 *    (r = Min/Max; c = 16/9 negativ, 4/9 positiv, bei Hauttönen t^1,6 statt t).
 * 3. Sättigung: zum Grau 0,288·R + 0,712·G hin bzw. davon weg (Blau zählt nicht), begrenzt auf 0…1.
 */
internal class PsVibrance(vibrance: Int, private val saturation: Int) {
    private val t = vibrance / 100.0
    /** Verstärkung je Knoten (Max-Index · 33 + Min-Index): normale Farben, Hauttöne. */
    private val gain = FloatArray(N * N)
    private val gainSkin = FloatArray(N * N)
    private val tSkin = if (t > 0) t.pow(1.6) else 0.0

    init {
        if (t < 0) {
            val a = -t
            for (i in 0 until N) for (j in 0 until N) {
                val mg = min(i, j) * STEP; val xg = i * STEP
                val mx = lin(min(xg, 255.0) / 255.0); val mn = lin(min(mg, 255.0) / 255.0)
                if (mx <= 0.0) continue
                val s = (1 - mn / mx) * min(1.0, min(xg, 255.0) / DARK)
                val w = horner(NEG_A, s) * a - horner(NEG_AB, s) * a * a
                gain[i * N + j] = (-min(w, 1.0)).toFloat()
            }
            gain.copyInto(gainSkin)
        } else if (t > 0) {
            val tab = tables
            for (k in 0 until N * N) {
                gain[k] = (tab[0][k] * t + tab[1][k] * t * t).toFloat()
                gainSkin[k] = (tab[2][k] * t + tab[3][k] * t * t).toFloat()
            }
        }
    }

    fun map(c: Int): Int {
        var r = LIN[(c shr 16) and 0xFF]; var g = LIN[(c shr 8) and 0xFF]; var b = LIN[c and 0xFF]
        if (t != 0.0) {
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            if (mx > 0f && mx > mn) {
                val mxg = max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF))
                val mng = min((c shr 16) and 0xFF, min((c shr 8) and 0xFF, c and 0xFF))
                val ratio = mn / mx
                val dark = min(1f, mxg / DARK.toFloat())
                val skin = if (t > 0) skinWeight(r, g, b, mx, mn) else 0f
                var k = 1f + lerp(gain, mxg, mng) * (1f - skin) + (if (skin > 0f) lerp(gainSkin, mxg, mng) * skin else 0f)
                k = k.coerceIn(0f, 1f / (1f - ratio))
                val tb = if (t > 0) (t * (1 - skin) + tSkin * skin).toFloat() * POS_C else t.toFloat() * NEG_C
                val scale = 1f + tb * (1f - mx) * ratio * (1f - ratio) * dark
                r = (mx - (mx - r) * k) * scale; g = (mx - (mx - g) * k) * scale; b = (mx - (mx - b) * k) * scale
            }
        }
        if (saturation != 0) {
            val gray = 0.288f * r + 0.712f * g
            val f = 1f + saturation / 100f
            r = gray + (r - gray) * f; g = gray + (g - gray) * f; b = gray + (b - gray) * f
        }
        return (enc(r) shl 16) or (enc(g) shl 8) or enc(b)
    }

    /** Für den GPU-Shader: Verstärkung normal/Hautton je Knoten als RGBA (G = Hautton), Zeile = Min, Spalte = Max. */
    fun gpuTable(): FloatArray {
        val o = FloatArray(N * N * 4)
        for (i in 0 until N) for (j in 0 until N) { val p = (j * N + i) * 4; o[p] = gain[i * N + j]; o[p + 1] = gainSkin[i * N + j] }
        return o
    }

    /** Uniforms des Shaders: Dynamik t, Sättigung, t^1,6 für Hauttöne. */
    fun gpuParams() = floatArrayOf(t.toFloat(), saturation / 100f, tSkin.toFloat())

    /** Bilinear in der Tabelle über (Max, Min) in Gamma-Werten 0…255. */
    private fun lerp(tab: FloatArray, mxg: Int, mng: Int): Float {
        val x = mxg / 8f; val y = mng / 8f
        val x0 = min(x.toInt(), N - 2); val y0 = min(y.toInt(), N - 2)
        val fx = x - x0; val fy = y - y0
        val a = tab[x0 * N + y0]; val b = tab[(x0 + 1) * N + y0]; val c = tab[x0 * N + y0 + 1]; val d = tab[(x0 + 1) * N + y0 + 1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    companion object {
        private const val N = 33
        private const val STEP = 8.0
        private const val DARK = 62.0
        private const val NEG_C = 16f / 9f
        private const val POS_C = 4f / 9f

        /** A(S) und A·β(S) der negativen Dynamik (Polynome 6. Grades, höchster Koeffizient zuerst). */
        private val NEG_A = doubleArrayOf(49.283874, -175.161458, 245.938023, -172.727531, 62.646291, -11.719395, 1.995933)
        private val NEG_AB = doubleArrayOf(49.269100, -175.275686, 246.409848, -173.361419, 63.412817, -11.456058, 1.007092)

        private val LIN = FloatArray(256) { lin(it / 255.0).toFloat() }
        /** Lineares Licht (16 Bit) → 8-Bit-sRGB, gerundet. */
        private val ENC = ByteArray(65536) { i ->
            val l = i / 65535.0
            val e = if (l <= 0.0031308) l * 12.92 else 1.055 * l.pow(1 / 2.4) - 0.055
            (e * 255 + 0.5).toInt().coerceIn(0, 255).toByte()
        }

        /** Gemessene Tabellen der positiven Dynamik (linear, quadratisch; normal, Hauttöne). */
        private val tables: List<DoubleArray> by lazy {
            val text = PsVibrance::class.java.getResourceAsStream("/adjust/vibrance.txt")!!.bufferedReader().readLines()
            val rows = text.filter { it.isNotBlank() && !it.startsWith("#") }.map { l -> l.trim().split(' ').map { it.toDouble() } }
            require(rows.size == 4 * N && rows.all { it.size == N }) { "vibrance.txt: ${rows.size} Zeilen" }
            List(4) { b -> DoubleArray(N * N) { k -> rows[b * N + k / N][k % N] } }
        }

        private fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        private fun enc(l: Float): Int = ENC[(l.coerceIn(0f, 1f) * 65535f + 0.5f).toInt()].toInt() and 0xFF
        private fun horner(p: DoubleArray, x: Double): Double { var y = 0.0; for (c in p) y = y * x + c; return y }

        /** Hautton-Gewicht nach dem HSV-Farbton im linearen Licht: 0–30° voll, bis 45° auslaufend, ab 300° ansteigend. */
        private fun skinWeight(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
            val d = mx - mn
            val h = when (mx) {
                r -> { val x = (g - b) / d * 60f; if (x < 0f) x + 360f else x }
                g -> (b - r) / d * 60f + 120f
                else -> (r - g) / d * 60f + 240f
            }
            return when {
                h <= 30f -> 1f
                h < 45f -> (45f - h) / 15f
                h < 300f -> 0f
                else -> (h - 300f) / 60f
            }
        }
    }
}
