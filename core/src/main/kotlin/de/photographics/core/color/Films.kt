package de.photographics.core.color

import de.photographics.core.tr
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Parametrisches Filmmodell für die Film-Looks: Farbübersprechen der Schichten, Gradation je
 * Schicht, Kennlinie mit Fuß und Schulter, Sättigung (gesamt und je Farbton), Tonung von Schatten
 * und Lichtern sowie Schwarzweiß mit spektraler Gewichtung. Die Werte sind eigene Annäherungen an
 * typische Filmcharaktere – keine Messdaten und keine fremden LUT-Dateien.
 */
internal class FilmModel(
    /** Steilheit der Kennlinie (S-Kurve), 0 = linear. */
    private val contrast: Float,
    /** Schwarzwert (Fuß) und Weißwert (Schulter). */
    private val toe: Float = 0f,
    private val shoulder: Float = 1f,
    /** Gamma je Schicht (R, G, B): > 1 macht den Kanal in den Mitteltönen dunkler. */
    private val gamma: FloatArray = floatArrayOf(1f, 1f, 1f),
    private val saturation: Float = 1f,
    /** Sättigung je Farbton: Rot, Gelb, Grün, Cyan, Blau, Magenta. */
    private val hueSaturation: FloatArray = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f),
    /** Farbverschiebung in Schatten bzw. Lichtern (R, G, B). */
    private val shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    private val highlightTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Farbübersprechen 3×3 (Zeilen = Ausgabe R, G, B) oder null. */
    private val crosstalk: FloatArray? = null,
    /** Schwarzweiß: spektrale Gewichte (R, G, B) oder null. */
    private val mono: FloatArray? = null,
    /** Tonung des Schwarzweißbilds (R, G, B, Multiplikatoren). */
    private val monoTone: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    fun apply(r0: Float, g0: Float, b0: Float, o: FloatArray) {
        var r = r0; var g = g0; var b = b0
        crosstalk?.let { m ->
            val nr = m[0] * r + m[1] * g + m[2] * b; val ng = m[3] * r + m[4] * g + m[5] * b; val nb = m[6] * r + m[7] * g + m[8] * b
            r = nr; g = ng; b = nb
        }
        if (mono != null) {
            val l = (mono[0] * r + mono[1] * g + mono[2] * b).coerceIn(0f, 1f)
            r = l; g = l; b = l
        }
        r = r.coerceIn(0f, 1f).pow(gamma[0]); g = g.coerceIn(0f, 1f).pow(gamma[1]); b = b.coerceIn(0f, 1f).pow(gamma[2])
        r = curve(r); g = curve(g); b = curve(b)
        if (mono == null) {
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val k = saturation * hueFactor(r, g, b)
            r = l + (r - l) * k; g = l + (g - l) * k; b = l + (b - l) * k
        } else {
            r *= monoTone[0]; g *= monoTone[1]; b *= monoTone[2]
        }
        val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
        val sh = (1 - l) * (1 - l); val hi = l * l
        o[0] = (r + shadowTint[0] * sh + highlightTint[0] * hi).coerceIn(0f, 1f)
        o[1] = (g + shadowTint[1] * sh + highlightTint[1] * hi).coerceIn(0f, 1f)
        o[2] = (b + shadowTint[2] * sh + highlightTint[2] * hi).coerceIn(0f, 1f)
    }

    /** Kennlinie: S-Kurve um die Mitte, dann zwischen Fuß und Schulter abgebildet. */
    private fun curve(x: Float): Float {
        val s = x + (x * x * (3 - 2 * x) - x) * contrast
        return toe + s.coerceIn(0f, 1f) * (shoulder - toe)
    }

    /** Sättigungsfaktor nach Farbton (linear zwischen den sechs Stützstellen), gewichtet nach Buntheit. */
    private fun hueFactor(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d < 1e-4f) return 1f
        val h = when (mx) {
            r -> ((g - b) / d / 6f + 1f) % 1f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        } * 6f
        val i = h.toInt() % 6; val f = h - h.toInt()
        val k = hueSaturation[i] + (hueSaturation[(i + 1) % 6] - hueSaturation[i]) * f
        return 1f + (k - 1f) * (d / max(mx, 1e-4f)).coerceIn(0f, 1f)
    }
}

/** Film-Looks: Farbnegativ, Dia, Schwarzweiß, Kino und Sofortbild. */
internal object Films {
    private fun f3(a: Float, b: Float, c: Float) = floatArrayOf(a, b, c)
    private fun hue(r: Float, y: Float, g: Float, c: Float, b: Float, m: Float) = floatArrayOf(r, y, g, c, b, m)

    val all: List<Pair<Pair<String, String>, FilmModel>> = listOf(
        ("Porträt 400 (Farbnegativ)" to tr("Weiche Gradation, warme Hauttöne, cremige Lichter – klassischer Porträtfilm")) to FilmModel(
            contrast = 0.12f, toe = 0.035f, shoulder = 0.965f, gamma = f3(0.97f, 1f, 1.04f), saturation = 0.9f,
            hueSaturation = hue(0.95f, 0.95f, 0.82f, 0.85f, 0.88f, 0.9f), shadowTint = f3(0f, 0.01f, 0.025f), highlightTint = f3(0.03f, 0.015f, -0.02f),
        ),
        ("Farbnegativ 100 brillant" to tr("Feinkörnig, kräftige Farben und satte Rottöne – Landschaft und Reise")) to FilmModel(
            contrast = 0.32f, toe = 0.015f, shoulder = 0.99f, gamma = f3(0.98f, 1f, 1.01f), saturation = 1.18f,
            hueSaturation = hue(1.15f, 1.05f, 1.05f, 1.05f, 1.1f, 1f),
        ),
        ("Farbnegativ 400 kühl" to tr("Pastellig, kühle Grün- und Cyantöne, offene Schatten – Hochzeit und Mode")) to FilmModel(
            contrast = 0.1f, toe = 0.05f, shoulder = 0.955f, gamma = f3(1.03f, 0.99f, 0.97f), saturation = 0.86f,
            hueSaturation = hue(0.9f, 0.85f, 0.95f, 1.05f, 0.9f, 0.85f), shadowTint = f3(-0.01f, 0.02f, 0.03f), highlightTint = f3(0.01f, 0.02f, 0f),
            crosstalk = floatArrayOf(1f, 0f, 0f, 0f, 0.94f, 0.06f, 0f, 0.04f, 0.96f),
        ),
        ("Farbnegativ 200 warm" to tr("Goldgelber Grundton, kräftig und sonnig – Alltags- und Urlaubsfilm")) to FilmModel(
            contrast = 0.28f, toe = 0.03f, shoulder = 0.97f, gamma = f3(0.95f, 0.98f, 1.07f), saturation = 1.08f,
            hueSaturation = hue(1.08f, 1.12f, 0.95f, 0.9f, 0.95f, 1f), highlightTint = f3(0.03f, 0.025f, -0.03f),
        ),
        ("Kunstlicht 800 (Kinofilm)" to tr("Für Glühlampenlicht abgestimmt: bei Tag kühl, blaugrüne Schatten, warme Lichter – Nacht und Stadt")) to FilmModel(
            contrast = 0.25f, toe = 0.04f, shoulder = 0.96f, gamma = f3(1.14f, 1.01f, 0.86f), saturation = 1.02f,
            shadowTint = f3(-0.02f, 0.01f, 0.04f), highlightTint = f3(0.05f, 0f, -0.02f),
        ),
        ("Diafilm 50 brillant" to tr("Sehr satte Farben, tiefe Schwärzen, hoher Kontrast – Landschaft")) to FilmModel(
            contrast = 0.55f, toe = 0f, shoulder = 1f, gamma = f3(1f, 1f, 1.02f), saturation = 1.35f,
            hueSaturation = hue(1.15f, 1.05f, 1.2f, 1.1f, 1.2f, 1.1f), shadowTint = f3(0.01f, 0f, 0.012f),
        ),
        ("Diafilm 100 neutral" to tr("Sauber und ausgewogen, moderater Kontrast – vielseitiger Diafilm")) to FilmModel(
            contrast = 0.35f, toe = 0.005f, shoulder = 0.995f, saturation = 1.1f, hueSaturation = hue(1.02f, 1f, 1.05f, 1.05f, 1.05f, 1f),
        ),
        ("Diafilm 64 klassisch" to tr("Warmer Grundton, leuchtende Rottöne, tiefblauer Himmel – Reportage der 60er")) to FilmModel(
            contrast = 0.45f, toe = 0.01f, shoulder = 0.985f, gamma = f3(0.96f, 1f, 1.03f), saturation = 1.15f,
            hueSaturation = hue(1.22f, 1.05f, 0.95f, 1f, 1.12f, 1f), highlightTint = f3(0.02f, 0.01f, -0.015f),
        ),
        ("Schwarzweiß 400 klassisch" to tr("Kräftiger Kontrast, leicht rotempfindlich – Reportage und Straße")) to FilmModel(
            contrast = 0.5f, toe = 0.02f, shoulder = 0.985f, mono = f3(0.3f, 0.6f, 0.1f),
        ),
        ("Schwarzweiß 400 weich" to tr("Weiche Gradation, viel Zeichnung in Schatten und Lichtern – Porträt")) to FilmModel(
            contrast = 0.18f, toe = 0.05f, shoulder = 0.96f, mono = f3(0.28f, 0.6f, 0.12f),
        ),
        ("Schwarzweiß Infrarot" to tr("Helles Laub, dunkler Himmel, leuchtende Haut – traumartig")) to FilmModel(
            contrast = 0.4f, toe = 0.02f, shoulder = 0.99f, mono = f3(0.55f, 0.95f, -0.5f),
        ),
        ("Schwarzweiß Selen getont" to tr("Klassisch mit kühl-violettem Ton in den Schatten")) to FilmModel(
            contrast = 0.35f, toe = 0.03f, shoulder = 0.975f, mono = f3(0.3f, 0.59f, 0.11f), monoTone = f3(1f, 0.99f, 1.005f),
            shadowTint = f3(0.02f, -0.012f, 0.028f),
        ),
        ("Kinokopie" to tr("Kopierfilm-Charakter: weich auslaufende Lichter, blaugrüne Schatten, warme Haut")) to FilmModel(
            contrast = 0.4f, toe = 0.02f, shoulder = 0.93f, gamma = f3(1f, 0.98f, 1.02f), saturation = 0.95f,
            shadowTint = f3(-0.025f, 0.012f, 0.025f), highlightTint = f3(0.03f, 0.012f, -0.02f),
        ),
        ("Sofortbild" to tr("Verblasst, cyanfarbene Schatten, warme Lichter, wenig Kontrast")) to FilmModel(
            contrast = 0.08f, toe = 0.09f, shoulder = 0.9f, gamma = f3(0.98f, 1f, 1.02f), saturation = 0.8f,
            shadowTint = f3(0f, 0.03f, 0.05f), highlightTint = f3(0.04f, 0.02f, -0.02f),
        ),
        ("Abgelaufener Film" to tr("Farbstich und ausgeblichene Schwärzen wie bei überlagertem Material")) to FilmModel(
            contrast = 0.15f, toe = 0.08f, shoulder = 0.9f, gamma = f3(1.05f, 0.95f, 1.05f), saturation = 0.75f,
            shadowTint = f3(0.03f, -0.01f, 0.04f), highlightTint = f3(0.02f, 0.03f, -0.03f),
        ),
    )
}
