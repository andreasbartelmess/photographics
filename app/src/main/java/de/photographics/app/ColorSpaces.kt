package de.photographics.app

import android.graphics.ColorSpace
import de.photographics.core.color.IccProfile
import de.photographics.core.color.IccProfiles

/** Brücke zwischen Android-Farbräumen (Bitmaps) und ICC-Profilen des Kerns. */
object ColorSpaces {
    private val named = listOf(
        ColorSpace.Named.SRGB to IccProfiles.Builtin.SRGB,
        ColorSpace.Named.DISPLAY_P3 to IccProfiles.Builtin.DISPLAY_P3,
        ColorSpace.Named.ADOBE_RGB to IccProfiles.Builtin.ADOBE_RGB,
        ColorSpace.Named.PRO_PHOTO_RGB to IccProfiles.Builtin.PROPHOTO,
    )

    /** ICC-Profil zu einem Bitmap-Farbraum; null = nicht darstellbar (dann sRGB-Umrechnung). */
    fun toIcc(cs: ColorSpace?): IccProfile? {
        if (cs == null || cs.isSrgb) return IccProfiles.sRGB
        for ((n, b) in named) if (cs == ColorSpace.get(n)) return b.profile
        val rgb = cs as? ColorSpace.Rgb ?: return null
        if (rgb.model != ColorSpace.Model.RGB) return null
        // Beliebiger RGB-Farbraum (z. B. Kameraprofil): Matrix/TRC-Profil aus Primärfarben und Kurve
        val p = rgb.primaries.map { it.toDouble() }.toDoubleArray()
        val w = rgb.whitePoint.map { it.toDouble() }.toDoubleArray()
        val bytes = IccProfiles.rgbProfile(cs.name.ifBlank { "Eingebettetes RGB-Profil" }, p, w, IccProfiles.Tone.Function { rgb.eotf.applyAsDouble(it) })
        return IccProfile(bytes)
    }

    /** Android-Farbraum zu einem RGB-Matrixprofil, um Bitmaps beim Export korrekt zu kennzeichnen. */
    fun toAndroid(p: IccProfile): ColorSpace? {
        for ((n, b) in named) if (b.profile.bytes.contentEquals(p.bytes)) return ColorSpace.get(n)
        val (m, trc) = p.matrixShaper() ?: return null
        // Spaltenweise RGB → XYZ (D50) – Androids Profilverbindungsraum ist ebenfalls D50
        val toXyz = floatArrayOf(m[0].toFloat(), m[3].toFloat(), m[6].toFloat(), m[1].toFloat(), m[4].toFloat(), m[7].toFloat(), m[2].toFloat(), m[5].toFloat(), m[8].toFloat())
        return runCatching {
            ColorSpace.Rgb(p.description, toXyz, { v -> trc[0].inverse(v.coerceIn(0.0, 1.0)) }, { v -> trc[0].eval(v.coerceIn(0.0, 1.0)) })
        }.getOrNull()
    }
}
