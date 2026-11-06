package de.photographics.core.selection

import de.photographics.core.image.IntRect
import de.photographics.core.image.Parallel
import de.photographics.core.image.TileGrid
import de.photographics.core.tr
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Auswahl → Farbbereich (wie Photoshop): wählt Pixel nach aufgenommenen Farben (mit Toleranz,
 * im Lab-Raum gemessen) oder nach einer Vorgabe (Rottöne … Tiefen, Hauttöne). Das Ergebnis ist
 * eine weiche Maske; Pixel ohne Deckung werden nicht ausgewählt.
 */
object ColorRange {
    enum class Preset(val label: String) {
        SAMPLED(tr("Aufgenommene Farben")),
        REDS(tr("Rottöne")), YELLOWS(tr("Gelbtöne")), GREENS(tr("Grüntöne")),
        CYANS(tr("Cyantöne")), BLUES(tr("Blautöne")), MAGENTAS(tr("Magentatöne")),
        HIGHLIGHTS(tr("Lichter")), MIDTONES(tr("Mitteltöne")), SHADOWS(tr("Tiefen")),
        SKIN(tr("Hauttöne")),
    }

    /** Eine aufgenommene Farbe (ARGB); [subtract] = mit der Minus-Pipette aufgenommen. */
    data class Sample(val argb: Int, val subtract: Boolean = false)

    data class Spec(
        val preset: Preset = Preset.SAMPLED,
        val samples: List<Sample> = emptyList(),
        /** Toleranz 0..200 (Photoshop) */
        val fuzziness: Int = 40,
        val invert: Boolean = false,
    )

    /** Auswahlwert 0..255 für eine Farbe. */
    fun value(spec: Spec, argb: Int): Int = value(Prepared(spec), argb)

    /** [Spec] mit vorberechneten Lab-Werten der aufgenommenen Farben. */
    class Prepared(val spec: Spec) {
        internal val labs = spec.samples.map { lab(it.argb) }
    }

    fun value(p: Prepared, argb: Int): Int {
        val spec = p.spec
        val a = argb ushr 24
        if (a == 0) return if (spec.invert) 255 else 0
        val v = when (spec.preset) {
            Preset.SAMPLED -> sampled(p, argb)
            else -> preset(spec.preset, spec.fuzziness, argb)
        }
        val s = v * a / 255f
        return ((if (spec.invert) 1f - s else s) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Auswahl auf [src] (ARGB, z. B. aktive Ebene oder sichtbares Bild). */
    fun select(src: TileGrid, spec: Spec, outlines: Boolean = true): Selection {
        val w = src.width; val h = src.height
        val prepared = Prepared(spec)
        val out = TileGrid(w, h, if (spec.invert) 255 else 0)
        val rows = 64
        val bands = (h + rows - 1) / rows
        Parallel.bands(bands, w * rows * 8L, compute = { b ->
            val r = IntRect(0, b * rows, w, min(h, (b + 1) * rows))
            val buf = IntArray(r.width * r.height)
            src.readRect(r, buf)
            val cache = HashMap<Int, Int>()
            for (i in buf.indices) {
                val c = buf[i]
                buf[i] = cache.getOrPut(c) { value(prepared, c) }
                if (cache.size > 65536) cache.clear()
            }
            buf
        }, consume = { b, buf -> out.writeRect(IntRect(0, b * rows, w, min(h, (b + 1) * rows)), buf) })
        out.compact()
        return Selection(out, if (outlines) Selection.outlinesOf(out) else emptyList())
    }

    /** Graustufen-Vorschau (ARGB, weiß = ausgewählt) für die Pixel [small] (z. B. ein verkleinertes Bild). */
    fun preview(small: IntArray, spec: Spec): IntArray {
        val p = Prepared(spec)
        return IntArray(small.size) { val v = value(p, small[it]); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    }

    // ------------------------------------------------------------------------------

    private fun sampled(p: Prepared, argb: Int): Float {
        val spec = p.spec
        if (spec.samples.isEmpty()) return 0f
        val lab = lab(argb)
        // Toleranz in ΔE: voll ausgewählt bis zur halben Toleranz, dann linear bis 0
        val radius = max(0.5f, spec.fuzziness * 0.5f)
        var plus = 0f; var minus = 0f
        for ((i, s) in spec.samples.withIndex()) {
            val o = p.labs[i]
            val d = sqrt((lab[0] - o[0]).pow(2) + (lab[1] - o[1]).pow(2) + (lab[2] - o[2]).pow(2))
            val v = ((radius - d) / (radius * 0.5f)).coerceIn(0f, 1f)
            if (s.subtract) minus = max(minus, v) else plus = max(plus, v)
        }
        return plus * (1f - minus)
    }

    private fun preset(p: Preset, fuzz: Int, argb: Int): Float {
        val r = (argb shr 16 and 255) / 255f; val g = (argb shr 8 and 255) / 255f; val b = (argb and 255) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        // Breite der Übergänge wächst mit der Toleranz (Photoshop-Standard 40 ≈ Faktor 1)
        val k = (0.5f + fuzz / 80f)
        fun hueBand(center: Float): Float {
            val sat = mx - mn
            if (sat <= 0.0001f) return 0f
            val hue = hue(r, g, b, mx, mn)
            var dh = abs(hue - center); if (dh > 180f) dh = 360f - dh
            val hv = (1f - (dh - 15f * k) / (30f * k)).coerceIn(0f, 1f)
            return hv * (sat / 0.25f).coerceIn(0f, 1f)
        }
        return when (p) {
            Preset.REDS -> hueBand(0f)
            Preset.YELLOWS -> hueBand(60f)
            Preset.GREENS -> hueBand(120f)
            Preset.CYANS -> hueBand(180f)
            Preset.BLUES -> hueBand(240f)
            Preset.MAGENTAS -> hueBand(300f)
            Preset.SHADOWS -> (1f - (luma - 0.25f * k) / (0.15f * k)).coerceIn(0f, 1f)
            Preset.HIGHLIGHTS -> ((luma - (1f - 0.25f * k)) / (0.15f * k) + 1f).coerceIn(0f, 1f)
            Preset.MIDTONES -> (1f - (abs(luma - 0.5f) - 0.15f * k) / (0.15f * k)).coerceIn(0f, 1f)
            Preset.SKIN -> {
                val l = lab(argb)
                // Hauttöne: a* und b* positiv, Bunttonwinkel etwa 30–70°, nicht zu dunkel
                val c = sqrt(l[1] * l[1] + l[2] * l[2])
                if (c < 5f || l[0] < 15f) 0f else {
                    val ang = Math.toDegrees(kotlin.math.atan2(l[2], l[1]).toDouble()).toFloat()
                    val hv = (1f - (abs(ang - 52f) - 14f * k) / (14f * k)).coerceIn(0f, 1f)
                    val cv = (1f - (abs(c - 25f) - 15f * k) / (10f * k)).coerceIn(0f, 1f)
                    hv * cv * ((l[0] - 15f) / 15f).coerceIn(0f, 1f)
                }
            }
            Preset.SAMPLED -> 0f
        }
    }

    private fun hue(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
        val d = mx - mn
        val h = when (mx) {
            r -> ((g - b) / d).let { if (it < 0) it + 6f else it }
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return h * 60f
    }

    private val lin = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }

    /** sRGB → CIE Lab (D65), ausreichend genau für Farbabstände. */
    internal fun lab(argb: Int): FloatArray {
        val r = lin[argb shr 16 and 255]; val g = lin[argb shr 8 and 255]; val b = lin[argb and 255]
        val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
        fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
        val fx = f(x); val fy = f(y); val fz = f(z)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }
}
