package de.photographics.core.color

import de.photographics.core.tr
import kotlin.math.pow

/**
 * Eingebaute Looks als 3D-Farbtabellen (33³), berechnet statt mitgeliefert – keine fremden Dateien,
 * keine Lizenzfragen. Eigene Looks lassen sich als .cube/.3dl laden.
 */
object Looks {
    /** [category]: Gruppe in der Auswahl („Looks“ oder „Film“). */
    class Look(val name: String, val description: String, val category: String = "Looks", private val f: (Float, Float, Float, FloatArray) -> Unit) {
        val lut: Lut3D by lazy { Lut3D.of(33, name, f) }
    }

    private fun lum(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun s(x: Float, k: Float) = x + (x * x * (3 - 2 * x) - x) * k          // S-Kurve
    private fun lift(x: Float, black: Float, white: Float = 1f) = black + x * (white - black)
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun sat(r: Float, g: Float, b: Float, k: Float, o: FloatArray) {
        val l = lum(r, g, b); o[0] = l + (r - l) * k; o[1] = l + (g - l) * k; o[2] = l + (b - l) * k
    }
    private fun clamp(o: FloatArray) { for (i in 0..2) o[i] = o[i].coerceIn(0f, 1f) }

    private val basic: List<Look> = listOf(
        Look("Film warm", tr("Weiche Lichter, warme Mitteltöne, leicht angehobene Schatten")) { r, g, b, o ->
            sat(r, g, b, 0.92f, o)
            o[0] = lift(s(o[0], 0.25f), 0.03f, 0.98f) * 1.04f; o[1] = lift(s(o[1], 0.25f), 0.03f, 0.97f); o[2] = lift(s(o[2], 0.2f), 0.05f, 0.92f) * 0.94f
            clamp(o)
        },
        Look("Kühl", tr("Kühle Farbstimmung, klare Lichter")) { r, g, b, o ->
            o[0] = s(r, 0.15f) * 0.94f; o[1] = s(g, 0.15f) * 0.99f; o[2] = lift(s(b, 0.1f), 0.04f, 1f) * 1.05f; clamp(o)
        },
        Look("Teal & Orange", tr("Kinolook: Hauttöne orange, Schatten blaugrün")) { r, g, b, o ->
            val l = lum(r, g, b)
            val shadow = (1 - l).pow(1.5f); val high = l.pow(1.5f)
            o[0] = r + 0.10f * high - 0.06f * shadow; o[1] = g + 0.03f * high + 0.02f * shadow; o[2] = b - 0.10f * high + 0.07f * shadow
            for (i in 0..2) o[i] = s(o[i].coerceIn(0f, 1f), 0.25f)
            sat(o[0], o[1], o[2], 1.1f, o); clamp(o)
        },
        Look("Bleach Bypass", tr("Entsättigt, hoher Kontrast, silbrig")) { r, g, b, o ->
            val l = lum(r, g, b)
            for ((i, v) in floatArrayOf(r, g, b).withIndex()) {
                // Überlagerung mit der Luminanz wie beim Silberbleichverfahren
                val ov = if (l < 0.5f) 2 * v * l else 1 - 2 * (1 - v) * (1 - l)
                o[i] = mix(v, ov, 0.8f)
            }
            sat(o[0], o[1], o[2], 0.45f, o); clamp(o)
        },
        Look("Matt", tr("Ausgebleichte Schwärzen, sanfter Kontrast")) { r, g, b, o ->
            o[0] = lift(s(r, -0.1f), 0.10f, 0.95f); o[1] = lift(s(g, -0.1f), 0.10f, 0.95f); o[2] = lift(s(b, -0.1f), 0.12f, 0.94f)
            sat(o[0], o[1], o[2], 0.85f, o); clamp(o)
        },
        Look("Kontrastreich", tr("Kräftige S-Kurve, satte Farben")) { r, g, b, o ->
            o[0] = s(r, 0.6f); o[1] = s(g, 0.6f); o[2] = s(b, 0.6f); sat(o[0], o[1], o[2], 1.15f, o); clamp(o)
        },
        Look("Sepia", tr("Warmer Monochrom-Ton")) { r, g, b, o ->
            val l = s(lum(r, g, b), 0.2f)
            o[0] = l * 1.07f + 0.02f; o[1] = l * 0.95f + 0.01f; o[2] = l * 0.78f; clamp(o)
        },
        Look("Schwarzweiß Rotfilter", tr("Dunkler Himmel, helle Haut (wie Rotfilter vor dem Objektiv)")) { r, g, b, o ->
            val l = s((0.65f * r + 0.3f * g + 0.05f * b), 0.3f); o[0] = l; o[1] = l; o[2] = l
        },
        Look("Cross-Process", tr("Farbverschobene Diafilm-Entwicklung")) { r, g, b, o ->
            o[0] = s(r, 0.5f); o[1] = s(g, 0.3f) * 1.03f; o[2] = lift(b, 0.15f, 0.85f); clamp(o)
        },
        Look("Nacht", tr("„Day for Night“: dunkel, blau, entsättigt")) { r, g, b, o ->
            sat(r, g, b, 0.35f, o)
            o[0] = o[0].pow(1.6f) * 0.6f; o[1] = o[1].pow(1.5f) * 0.7f; o[2] = o[2].pow(1.2f) * 0.9f + 0.03f; clamp(o)
        },
    )

    /** Alle eingebauten Looks: allgemeine Looks und Filmemulationen. */
    val all: List<Look> = basic + Films.all.map { (meta, model) -> Look(meta.first, meta.second, "Film") { r, g, b, o -> model.apply(r, g, b, o) } }

    val DEFAULT = all.first().name

    fun byName(name: String): Look? = all.firstOrNull { it.name == name }
}
