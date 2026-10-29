package de.photographics.core.render

import de.photographics.core.tr
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Alle Photoshop-Mischmodi mit ihrem 4-Zeichen-Schlüssel aus dem PSD-Format.
 * Die Formeln folgen der W3C-Compositing-Spezifikation bzw. dem dokumentierten Photoshop-Verhalten.
 */
enum class BlendMode(val psdKey: String, val label: String, val separable: Boolean = true) {
    PASS_THROUGH("pass", tr("Hindurchwirken")),
    NORMAL("norm", tr("Normal")),
    DISSOLVE("diss", tr("Sprenkeln")),
    DARKEN("dark", tr("Abdunkeln")),
    MULTIPLY("mul ", tr("Multiplizieren")),
    COLOR_BURN("idiv", tr("Farbig nachbelichten")),
    LINEAR_BURN("lbrn", tr("Linear nachbelichten")),
    DARKER_COLOR("dkCl", tr("Dunklere Farbe"), separable = false),
    LIGHTEN("lite", tr("Aufhellen")),
    SCREEN("scrn", tr("Negativ multiplizieren")),
    COLOR_DODGE("div ", tr("Farbig abwedeln")),
    LINEAR_DODGE("lddg", tr("Linear abwedeln (Addieren)")),
    LIGHTER_COLOR("lgCl", tr("Hellere Farbe"), separable = false),
    OVERLAY("over", tr("Ineinanderkopieren")),
    SOFT_LIGHT("sLit", tr("Weiches Licht")),
    HARD_LIGHT("hLit", tr("Hartes Licht")),
    VIVID_LIGHT("vLit", tr("Strahlendes Licht")),
    LINEAR_LIGHT("lLit", tr("Lineares Licht")),
    PIN_LIGHT("pLit", tr("Lichtpunkt")),
    HARD_MIX("hMix", tr("Hart mischen")),
    DIFFERENCE("diff", tr("Differenz")),
    EXCLUSION("smud", tr("Ausschluss")),
    SUBTRACT("fsub", tr("Subtrahieren")),
    DIVIDE("fdiv", tr("Dividieren")),
    HUE("hue ", tr("Farbton"), separable = false),
    SATURATION("sat ", tr("Sättigung"), separable = false),
    COLOR("colr", tr("Farbe"), separable = false),
    LUMINOSITY("lum ", tr("Luminanz"), separable = false);

    /** Separierbare Mischfunktion B(cb, cs) für einen Kanal (Werte 0..1). */
    fun blendChannel(cb: Float, cs: Float): Float = when (this) {
        PASS_THROUGH, NORMAL, DISSOLVE -> cs
        DARKEN -> min(cb, cs)
        MULTIPLY -> cb * cs
        COLOR_BURN -> colorBurn(cb, cs)
        LINEAR_BURN -> max(0f, cb + cs - 1f)
        LIGHTEN -> max(cb, cs)
        SCREEN -> cb + cs - cb * cs
        COLOR_DODGE -> colorDodge(cb, cs)
        LINEAR_DODGE -> min(1f, cb + cs)
        OVERLAY -> hardLight(cs, cb)
        SOFT_LIGHT -> if (cs <= 0.5f) 2f * cb * cs + cb * cb * (1f - 2f * cs)
                      else 2f * cb * (1f - cs) + sqrt(cb) * (2f * cs - 1f)
        HARD_LIGHT -> hardLight(cb, cs)
        VIVID_LIGHT -> if (cs <= 0.5f) colorBurn(cb, 2f * cs) else colorDodge(cb, 2f * (cs - 0.5f))
        LINEAR_LIGHT -> (cb + 2f * cs - 1f).coerceIn(0f, 1f)
        PIN_LIGHT -> if (cs <= 0.5f) min(cb, 2f * cs) else max(cb, 2f * cs - 1f)
        HARD_MIX -> if (cb + cs >= 1f) 1f else 0f
        DIFFERENCE -> abs(cb - cs)
        EXCLUSION -> cb + cs - 2f * cb * cs
        SUBTRACT -> max(0f, cb - cs)
        DIVIDE -> if (cs <= 0f) (if (cb > 0f) 1f else 0f) else min(1f, cb / cs)
        else -> cs
    }

    /** Nicht separierbare Modi: schreibt das Ergebnis nach [out] (r, g, b). */
    fun blendNonSeparable(br: Float, bg: Float, bb: Float, sr: Float, sg: Float, sb: Float, out: FloatArray) {
        when (this) {
            HUE -> { setSat(sr, sg, sb, sat(br, bg, bb), out); setLum(out[0], out[1], out[2], lum(br, bg, bb), out) }
            SATURATION -> { setSat(br, bg, bb, sat(sr, sg, sb), out); setLum(out[0], out[1], out[2], lum(br, bg, bb), out) }
            COLOR -> setLum(sr, sg, sb, lum(br, bg, bb), out)
            LUMINOSITY -> setLum(br, bg, bb, lum(sr, sg, sb), out)
            DARKER_COLOR -> if (lum(sr, sg, sb) < lum(br, bg, bb)) set(out, sr, sg, sb) else set(out, br, bg, bb)
            LIGHTER_COLOR -> if (lum(sr, sg, sb) > lum(br, bg, bb)) set(out, sr, sg, sb) else set(out, br, bg, bb)
            else -> set(out, blendChannel(br, sr), blendChannel(bg, sg), blendChannel(bb, sb))
        }
    }

    companion object {
        private val byKey = entries.associateBy { it.psdKey }

        fun fromPsdKey(key: String): BlendMode = byKey[key] ?: NORMAL

        /** Modi, die für normale Ebenen im UI angeboten werden (ohne "Hindurchwirken"). */
        val layerModes: List<BlendMode> get() = entries.filter { it != PASS_THROUGH }

        private fun set(o: FloatArray, r: Float, g: Float, b: Float) { o[0] = r; o[1] = g; o[2] = b }

        private fun colorBurn(cb: Float, cs: Float): Float = when {
            cb >= 1f -> 1f
            cs <= 0f -> 0f
            else -> 1f - min(1f, (1f - cb) / cs)
        }

        private fun colorDodge(cb: Float, cs: Float): Float = when {
            cb <= 0f -> 0f
            cs >= 1f -> 1f
            else -> min(1f, cb / (1f - cs))
        }

        private fun hardLight(cb: Float, cs: Float): Float =
            if (cs <= 0.5f) cb * 2f * cs else { val s = 2f * cs - 1f; cb + s - cb * s }

        private fun lum(r: Float, g: Float, b: Float) = 0.3f * r + 0.59f * g + 0.11f * b
        private fun sat(r: Float, g: Float, b: Float) = max(r, max(g, b)) - min(r, min(g, b))

        private fun setLum(r: Float, g: Float, b: Float, l: Float, out: FloatArray) {
            val d = l - lum(r, g, b)
            clipColor(r + d, g + d, b + d, out)
        }

        private fun clipColor(r: Float, g: Float, b: Float, out: FloatArray) {
            val l = lum(r, g, b)
            val n = min(r, min(g, b)); val x = max(r, max(g, b))
            var rr = r; var gg = g; var bb = b
            if (n < 0f) {
                val k = l - n
                rr = l + (rr - l) * l / k; gg = l + (gg - l) * l / k; bb = l + (bb - l) * l / k
            }
            if (x > 1f) {
                val k = x - l
                rr = l + (rr - l) * (1f - l) / k; gg = l + (gg - l) * (1f - l) / k; bb = l + (bb - l) * (1f - l) / k
            }
            set(out, rr.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), bb.coerceIn(0f, 1f))
        }

        private fun setSat(r: Float, g: Float, b: Float, s: Float, out: FloatArray) {
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            if (mx - mn <= 1e-6f) { set(out, 0f, 0f, 0f); return }
            fun f(c: Float) = (c - mn) * s / (mx - mn)
            set(out, f(r), f(g), f(b))
        }
    }
}
