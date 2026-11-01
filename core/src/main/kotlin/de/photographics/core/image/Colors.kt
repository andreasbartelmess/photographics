package de.photographics.core.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Hilfsfunktionen für nicht vormultiplizierte ARGB-Ints. */
object Colors {
    /** Kanalverschiebungen A, R, G, B (einmal angelegt statt je Pixel). */
    @JvmField val SHIFTS_ARGB = intArrayOf(24, 16, 8, 0)
    @JvmField val SHIFTS_RGB = intArrayOf(16, 8, 0)
    /** Reihenfolge R, G, B, A (16-Bit-Puffer RGBA). */
    @JvmField val SHIFTS_RGBA = intArrayOf(16, 8, 0, 24)

    /**
     * Unteres Byte der 16-Bit-Deckkraft aus oberem [hi] und gespeichertem unterem Byte [lo].
     * 8-Bit-Werkzeuge ändern nur das obere Byte; Deckkraft 0 bzw. 255 oben gilt daher als exakt –
     * radierte Flächen bleiben ganz durchsichtig, neu bemalte ganz deckend.
     */
    fun lowAlpha(hi: Int, lo: Int): Int = if (hi == 0) 0 else if (hi == 255) 255 else lo

    fun a(c: Int) = c ushr 24
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (clamp8(a) shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)

    fun argbF(a: Float, r: Float, g: Float, b: Float): Int =
        argb((a * 255f).roundToInt(), (r * 255f).roundToInt(), (g * 255f).roundToInt(), (b * 255f).roundToInt())

    fun clamp8(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
    fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun withAlpha(c: Int, a: Int) = (clamp8(a) shl 24) or (c and 0xFFFFFF)

    /** Luma nach Rec. 601 (wie Photoshop bei "Luminanz"-Modi), 0..1. */
    fun luma(r: Float, g: Float, b: Float) = 0.3f * r + 0.59f * g + 0.11f * b

    /** RGB (0..1) → [Farbton 0..1, Sättigung, Helligkeit]. */
    fun rgbToHsb(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val h = when {
            d <= 0f -> 0f
            max == r -> ((g - b) / d / 6f + 1f) % 1f
            max == g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return floatArrayOf(h, if (max <= 0f) 0f else d / max, max)
    }

    /** Farbton/Sättigung/Helligkeit (0..1) → deckendes ARGB. */
    fun hsbToRgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 1f + 1f) % 1f) * 6f
        val i = hh.toInt(); val f = hh - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i % 6) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
        return argbF(1f, r, g, b)
    }

    /** Lineare Mischung zweier Farben (RGB, Alpha deckend). */
    fun lerpRgb(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** "Quelle über Ziel" für nicht vormultiplizierte Farben (Ganzzahl-Arithmetik). */
    fun srcOver(dst: Int, src: Int): Int {
        val sa = src ushr 24
        if (sa == 255) return src
        if (sa == 0) return dst
        val da = dst ushr 24
        val isa = 255 - sa
        if (da == 255) {
            val r = (((src shr 16) and 0xFF) * sa + ((dst shr 16) and 0xFF) * isa + 127) / 255
            val g = (((src shr 8) and 0xFF) * sa + ((dst shr 8) and 0xFF) * isa + 127) / 255
            val b = ((src and 0xFF) * sa + (dst and 0xFF) * isa + 127) / 255
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        // Allgemein: oa = sa + da·(1−sa); Farbe gewichtet nach den Deckkräften
        val dw = da * isa // Gewicht des Ziels ×255
        val sw = sa * 255
        val oa255 = sw + dw
        if (oa255 == 0) return 0
        val half = oa255 / 2
        val r = (((src shr 16) and 0xFF) * sw + ((dst shr 16) and 0xFF) * dw + half) / oa255
        val g = (((src shr 8) and 0xFF) * sw + ((dst shr 8) and 0xFF) * dw + half) / oa255
        val b = ((src and 0xFF) * sw + (dst and 0xFF) * dw + half) / oa255
        return ((oa255 + 127) / 255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Lineare Interpolation zweier ARGB-Farben (inkl. Alpha), t in 0..1. */
    fun lerp(c0: Int, c1: Int, t: Float): Int {
        if (t <= 0f) return c0
        if (t >= 1f) return c1
        val u = 1f - t
        return argb(
            (a(c0) * u + a(c1) * t).roundToInt(),
            (r(c0) * u + r(c1) * t).roundToInt(),
            (g(c0) * u + g(c1) * t).roundToInt(),
            (b(c0) * u + b(c1) * t).roundToInt(),
        )
    }

    /** RGB (0..1) -> HSL (h in 0..360, s/l in 0..1). */
    fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        var h = 0f; var s = 0f
        val d = mx - mn
        if (d > 1e-6f) {
            s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
        }
        out[0] = h; out[1] = s; out[2] = l
    }

    fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        if (s <= 0f) { out[0] = l; out[1] = l; out[2] = l; return }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hk = ((h % 360f) + 360f) % 360f / 360f
        out[0] = hue2rgb(p, q, hk + 1f / 3f)
        out[1] = hue2rgb(p, q, hk)
        out[2] = hue2rgb(p, q, hk - 1f / 3f)
    }

    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
}
