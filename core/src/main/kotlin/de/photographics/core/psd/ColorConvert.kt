package de.photographics.core.psd

import kotlin.math.pow
import kotlin.math.roundToInt

/** Farbraumumrechnungen für den Import von Nicht-RGB-Dokumenten (ohne ICC-Farbmanagement). */
internal object ColorConvert {
    private val linearToSrgb8 = IntArray(4096) { i ->
        val v = i / 4095.0
        val s = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
        (s * 255).roundToInt().coerceIn(0, 255)
    }

    fun linearToSrgb(v: Float): Int = linearToSrgb8[(v.coerceIn(0f, 1f) * 4095f).roundToInt()]

    /** Photoshop speichert CMYK invertiert (255 = keine Farbe). Naive Umrechnung ohne Profil. */
    fun cmykToRgb(c: Int, m: Int, y: Int, k: Int): Int {
        val r = c * k / 255; val g = m * k / 255; val b = y * k / 255
        return (r shl 16) or (g shl 8) or b
    }

    /** Lab (8-Bit-Kodierung wie im PSD) -> sRGB, Weißpunkt D50 mit Bradford-Anpassung nach D65. */
    fun labToRgb(l8: Int, a8: Int, b8: Int): Int {
        val l = l8 * 100.0 / 255.0
        val a = a8 - 128.0
        val b = b8 - 128.0
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        fun finv(t: Double) = if (t > 6.0 / 29) t * t * t else 3 * (6.0 / 29) * (6.0 / 29) * (t - 4.0 / 29)
        val x = 0.9642 * finv(fx); val yy = finv(fy); val z = 0.8249 * finv(fz)
        // Bradford D50 -> D65, dann XYZ -> lineares sRGB
        val x65 = 0.9555766 * x - 0.0230393 * yy + 0.0631636 * z
        val y65 = -0.0282895 * x + 1.0099416 * yy + 0.0210077 * z
        val z65 = 0.0122982 * x - 0.0204830 * yy + 1.3299098 * z
        val rl = 3.2404542 * x65 - 1.5371385 * y65 - 0.4985314 * z65
        val gl = -0.9692660 * x65 + 1.8760108 * y65 + 0.0415560 * z65
        val bl = 0.0556434 * x65 - 0.2040259 * y65 + 1.0572252 * z65
        return (linearToSrgb(rl.toFloat()) shl 16) or (linearToSrgb(gl.toFloat()) shl 8) or linearToSrgb(bl.toFloat())
    }
}
