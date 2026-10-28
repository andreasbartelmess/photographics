package de.photographics.core.adjust

import de.photographics.core.image.Colors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Bildet eine RGB-Farbe (0xRRGGBB) auf eine neue ab; Alpha wird vom Aufrufer behandelt. */
fun interface RgbMap {
    fun map(rgb: Int): Int
}

/** Beschreibung eines Reglers für die generische Oberfläche. */
data class AdjParam(
    val label: String,
    val min: Float,
    val max: Float,
    val value: Float,
    val decimals: Int = 0,
    val kind: Kind = Kind.SLIDER,
) {
    enum class Kind { SLIDER, SWITCH, HUE }
}

/**
 * Nicht-destruktive Korrektur (Einstellungsebene) oder direkt angewendete Korrektur.
 * Unveränderliche Werte; Änderungen erzeugen über [withValues] eine neue Instanz.
 */
sealed class Adjustment {
    abstract val type: AdjustmentType
    abstract fun params(): List<AdjParam>
    abstract fun withValues(v: FloatArray): Adjustment
    abstract fun compile(): RgbMap

    val label get() = type.label
}

enum class AdjustmentType(val label: String, val psdKey: String) {
    BRIGHTNESS_CONTRAST("Helligkeit/Kontrast", "brit"),
    LEVELS("Tonwertkorrektur", "levl"),
    CURVES("Gradationskurven", "curv"),
    EXPOSURE("Belichtung", "expA"),
    VIBRANCE("Dynamik", "vibA"),
    HUE_SATURATION("Farbton/Sättigung", "hue2"),
    COLOR_BALANCE("Farbbalance", "blnc"),
    BLACK_WHITE("Schwarzweiß", "blwh"),
    PHOTO_FILTER("Fotofilter", "phfl"),
    INVERT("Umkehren", "nvrt"),
    POSTERIZE("Tontrennung", "post"),
    THRESHOLD("Schwellenwert", "thrs"),
    COLOR_LOOKUP("Farbtabelle (LUT)", "clrL");

    fun createDefault(): Adjustment = when (this) {
        BRIGHTNESS_CONTRAST -> BrightnessContrastAdj()
        LEVELS -> LevelsAdj()
        CURVES -> CurvesAdj()
        EXPOSURE -> ExposureAdj()
        VIBRANCE -> VibranceAdj()
        HUE_SATURATION -> HueSaturationAdj()
        COLOR_BALANCE -> ColorBalanceAdj()
        BLACK_WHITE -> BlackWhiteAdj()
        PHOTO_FILTER -> PhotoFilterAdj()
        INVERT -> InvertAdj
        POSTERIZE -> PosterizeAdj()
        THRESHOLD -> ThresholdAdj()
        COLOR_LOOKUP -> ColorLookupAdj()
    }

    companion object {
        private val byKey = entries.associateBy { it.psdKey }
        fun fromPsdKey(k: String) = byKey[k] ?: if (k == "CgEd") BRIGHTNESS_CONTRAST else null
    }
}

// ---------------------------------------------------------------- Hilfen

/** Tabelle für Operationen, die jeden Kanal gleich behandeln. */
private fun lutMap(f: (Float) -> Float): RgbMap {
    val lut = IntArray(256) { (f(it / 255f) * 255f).roundToInt().coerceIn(0, 255) }
    return RgbMap { c -> (lut[(c shr 16) and 0xFF] shl 16) or (lut[(c shr 8) and 0xFF] shl 8) or lut[c and 0xFF] }
}

private fun pack(r: Float, g: Float, b: Float): Int =
    (Colors.clamp8((r * 255f).roundToInt()) shl 16) or (Colors.clamp8((g * 255f).roundToInt()) shl 8) or Colors.clamp8((b * 255f).roundToInt())

private fun lum(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

// ---------------------------------------------------------------- Korrekturen

data class BrightnessContrastAdj(val brightness: Int = 0, val contrast: Int = 0) : Adjustment() {
    override val type get() = AdjustmentType.BRIGHTNESS_CONTRAST
    override fun params() = listOf(AdjParam("Helligkeit", -150f, 150f, brightness.toFloat()), AdjParam("Kontrast", -50f, 100f, contrast.toFloat()))
    override fun withValues(v: FloatArray) = copy(brightness = v[0].roundToInt(), contrast = v[1].roundToInt())
    override fun compile(): RgbMap {
        val b = brightness / 150f
        val c = contrast / 100f
        return lutMap { v ->
            // Helligkeit über Gamma (Schwarz/Weiß bleiben), Kontrast als S-förmige Spreizung um die Mitte
            var x = if (b >= 0) v.pow(1f / (1f + b * 1.5f)) else v.pow(1f - b * 1.5f)
            val k = if (c >= 0) 1f / (1f - c * 0.99f) else 1f + c
            x = (x - 0.5f) * k + 0.5f
            x.coerceIn(0f, 1f)
        }
    }
}

data class LevelsAdj(
    val inBlack: Int = 0, val inWhite: Int = 255, val gamma: Float = 1f,
    val outBlack: Int = 0, val outWhite: Int = 255,
) : Adjustment() {
    override val type get() = AdjustmentType.LEVELS
    override fun params() = listOf(
        AdjParam("Tiefen", 0f, 253f, inBlack.toFloat()),
        AdjParam("Mitteltöne (Gamma)", 0.1f, 9.99f, gamma, decimals = 2),
        AdjParam("Lichter", 2f, 255f, inWhite.toFloat()),
        AdjParam("Ausgabe Schwarz", 0f, 255f, outBlack.toFloat()),
        AdjParam("Ausgabe Weiß", 0f, 255f, outWhite.toFloat()),
    )
    override fun withValues(v: FloatArray) = copy(inBlack = v[0].roundToInt(), gamma = v[1], inWhite = v[2].roundToInt(), outBlack = v[3].roundToInt(), outWhite = v[4].roundToInt())
    override fun compile(): RgbMap {
        val lo = inBlack / 255f; val hi = max(inWhite, inBlack + 1) / 255f
        return lutMap { v ->
            val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f).pow(1f / gamma.coerceIn(0.1f, 9.99f))
            (outBlack + (outWhite - outBlack) * t) / 255f
        }
    }
}

/**
 * Gradationskurve über Stützpunkte (Eingabe → Ausgabe, 0..255), interpoliert mit einer
 * monotonen kubischen Spline (Fritsch–Carlson), wie Photoshop sie ohne Überschwinger zeichnet.
 * [channel]-Kurven für R/G/B sind optional zusätzlich zur Gesamtkurve.
 */
data class CurvesAdj(
    val master: List<Pair<Int, Int>> = listOf(0 to 0, 255 to 255),
    val red: List<Pair<Int, Int>>? = null,
    val green: List<Pair<Int, Int>>? = null,
    val blue: List<Pair<Int, Int>>? = null,
) : Adjustment() {
    override val type get() = AdjustmentType.CURVES
    /** Regler-Darstellung: Tiefen-/Mitteltöne-/Lichter-Punkte der Gesamtkurve (Kurveneditor nutzt [master] direkt). */
    override fun params() = listOf(
        AdjParam("Tiefen (Ausgabe bei 64)", 0f, 255f, evaluate(master, 64).toFloat()),
        AdjParam("Mitteltöne (Ausgabe bei 128)", 0f, 255f, evaluate(master, 128).toFloat()),
        AdjParam("Lichter (Ausgabe bei 192)", 0f, 255f, evaluate(master, 192).toFloat()),
    )
    override fun withValues(v: FloatArray) = copy(
        master = listOf(0 to 0, 64 to v[0].roundToInt(), 128 to v[1].roundToInt(), 192 to v[2].roundToInt(), 255 to 255),
    )

    override fun compile(): RgbMap {
        val m = table(master)
        val r = red?.let { table(it) }; val g = green?.let { table(it) }; val b = blue?.let { table(it) }
        return RgbMap { c ->
            var rr = (c shr 16) and 0xFF; var gg = (c shr 8) and 0xFF; var bb = c and 0xFF
            if (r != null) rr = r[rr]; if (g != null) gg = g[gg]; if (b != null) bb = b[bb]
            (m[rr] shl 16) or (m[gg] shl 8) or m[bb]
        }
    }

    companion object {
        fun table(points: List<Pair<Int, Int>>): IntArray = IntArray(256) { evaluate(points, it) }

        fun evaluate(points: List<Pair<Int, Int>>, x: Int): Int {
            val p = points.sortedBy { it.first }.distinctBy { it.first }
            if (p.isEmpty()) return x
            if (p.size == 1) return p[0].second
            if (x <= p.first().first) return p.first().second
            if (x >= p.last().first) return p.last().second
            val n = p.size
            val xs = DoubleArray(n) { p[it].first.toDouble() }; val ys = DoubleArray(n) { p[it].second.toDouble() }
            val dk = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
            val m = DoubleArray(n)
            m[0] = dk[0]; m[n - 1] = dk[n - 2]
            for (i in 1 until n - 1) m[i] = if (dk[i - 1] * dk[i] <= 0) 0.0 else (dk[i - 1] + dk[i]) / 2
            for (i in 0 until n - 1) {
                if (dk[i] == 0.0) { m[i] = 0.0; m[i + 1] = 0.0; continue }
                val a = m[i] / dk[i]; val b = m[i + 1] / dk[i]
                val s = a * a + b * b
                if (s > 9) { val t = 3 / kotlin.math.sqrt(s); m[i] = t * a * dk[i]; m[i + 1] = t * b * dk[i] }
            }
            var k = 0
            while (k < n - 2 && x > xs[k + 1]) k++
            val h = xs[k + 1] - xs[k]; val t = (x - xs[k]) / h
            val t2 = t * t; val t3 = t2 * t
            val y = (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
            return y.roundToInt().coerceIn(0, 255)
        }
    }
}

data class ExposureAdj(val exposure: Float = 0f, val offset: Float = 0f, val gamma: Float = 1f) : Adjustment() {
    override val type get() = AdjustmentType.EXPOSURE
    override fun params() = listOf(
        AdjParam("Belichtung", -5f, 5f, exposure, decimals = 2),
        AdjParam("Versatz", -0.5f, 0.5f, offset, decimals = 3),
        AdjParam("Gammakorrektur", 0.1f, 3f, gamma, decimals = 2),
    )
    override fun withValues(v: FloatArray) = copy(exposure = v[0], offset = v[1], gamma = v[2])
    override fun compile() = lutMap { v ->
        // Photoshop rechnet die Belichtung im linearen Licht
        val lin = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        val e = (lin * 2f.pow(exposure) + offset).coerceIn(0f, 1f).pow(1f / gamma.coerceIn(0.01f, 10f))
        if (e <= 0.0031308f) e * 12.92f else 1.055f * e.pow(1f / 2.4f) - 0.055f
    }
}

data class VibranceAdj(val vibrance: Int = 0, val saturation: Int = 0) : Adjustment() {
    override val type get() = AdjustmentType.VIBRANCE
    override fun params() = listOf(AdjParam("Dynamik", -100f, 100f, vibrance.toFloat()), AdjParam("Sättigung", -100f, 100f, saturation.toFloat()))
    override fun withValues(v: FloatArray) = copy(vibrance = v[0].roundToInt(), saturation = v[1].roundToInt())
    override fun compile(): RgbMap {
        val vib = vibrance / 100f; val sat = saturation / 100f
        return RgbMap { c ->
            var r = ((c shr 16) and 0xFF) / 255f; var g = ((c shr 8) and 0xFF) / 255f; var b = (c and 0xFF) / 255f
            val l = lum(r, g, b)
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val curSat = mx - mn
            // Dynamik wirkt stärker auf wenig gesättigte Farben
            val k = 1f + sat + vib * (1f - curSat) * (if (vib > 0) 1.5f else 1f)
            r = l + (r - l) * k; g = l + (g - l) * k; b = l + (b - l) * k
            pack(r, g, b)
        }
    }
}

data class HueSaturationAdj(val hue: Int = 0, val saturation: Int = 0, val lightness: Int = 0, val colorize: Boolean = false) : Adjustment() {
    override val type get() = AdjustmentType.HUE_SATURATION
    override fun params() = listOf(
        AdjParam("Farbton", -180f, 180f, hue.toFloat(), kind = AdjParam.Kind.HUE),
        AdjParam("Sättigung", -100f, 100f, saturation.toFloat()),
        AdjParam("Helligkeit", -100f, 100f, lightness.toFloat()),
        AdjParam("Färben", 0f, 1f, if (colorize) 1f else 0f, kind = AdjParam.Kind.SWITCH),
    )
    override fun withValues(v: FloatArray) = copy(hue = v[0].roundToInt(), saturation = v[1].roundToInt(), lightness = v[2].roundToInt(), colorize = v[3] >= 0.5f)
    override fun compile(): RgbMap {
        val hsl = FloatArray(3); val rgb = FloatArray(3)
        val s = saturation / 100f; val l = lightness / 100f
        return RgbMap { c ->
            Colors.rgbToHsl(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, hsl)
            if (colorize) {
                hsl[0] = ((hue + 360) % 360).toFloat()
                hsl[1] = ((s + 1f) * 0.25f).coerceIn(0f, 1f)
            } else {
                hsl[0] += hue
                hsl[1] = (if (s >= 0) hsl[1] + (1f - hsl[1]) * s * hsl[1].coerceAtLeast(0.15f) else hsl[1] * (1f + s)).coerceIn(0f, 1f)
            }
            hsl[2] = if (l >= 0) hsl[2] + (1f - hsl[2]) * l else hsl[2] * (1f + l)
            Colors.hslToRgb(hsl[0], hsl[1], hsl[2], rgb)
            pack(rgb[0], rgb[1], rgb[2])
        }
    }
}

/** Farbbalance: je Tonwertbereich Cyan↔Rot, Magenta↔Grün, Gelb↔Blau (-100..100). */
data class ColorBalanceAdj(
    val shadows: List<Int> = listOf(0, 0, 0),
    val midtones: List<Int> = listOf(0, 0, 0),
    val highlights: List<Int> = listOf(0, 0, 0),
    val preserveLuminosity: Boolean = true,
) : Adjustment() {
    override val type get() = AdjustmentType.COLOR_BALANCE
    override fun params() = listOf(
        AdjParam("Mitteltöne Cyan ↔ Rot", -100f, 100f, midtones[0].toFloat()),
        AdjParam("Mitteltöne Magenta ↔ Grün", -100f, 100f, midtones[1].toFloat()),
        AdjParam("Mitteltöne Gelb ↔ Blau", -100f, 100f, midtones[2].toFloat()),
        AdjParam("Tiefen Cyan ↔ Rot", -100f, 100f, shadows[0].toFloat()),
        AdjParam("Tiefen Gelb ↔ Blau", -100f, 100f, shadows[2].toFloat()),
        AdjParam("Lichter Cyan ↔ Rot", -100f, 100f, highlights[0].toFloat()),
        AdjParam("Lichter Gelb ↔ Blau", -100f, 100f, highlights[2].toFloat()),
        AdjParam("Luminanz erhalten", 0f, 1f, if (preserveLuminosity) 1f else 0f, kind = AdjParam.Kind.SWITCH),
    )
    override fun withValues(v: FloatArray) = copy(
        midtones = listOf(v[0].roundToInt(), v[1].roundToInt(), v[2].roundToInt()),
        shadows = listOf(v[3].roundToInt(), shadows[1], v[4].roundToInt()),
        highlights = listOf(v[5].roundToInt(), highlights[1], v[6].roundToInt()),
        preserveLuminosity = v[7] >= 0.5f,
    )
    override fun compile(): RgbMap = RgbMap { c ->
        var r = ((c shr 16) and 0xFF) / 255f; var g = ((c shr 8) and 0xFF) / 255f; var b = (c and 0xFF) / 255f
        val l0 = lum(r, g, b)
        // Gewichte je Bereich (weiche Übergänge wie bei Photoshop)
        val ws = (1f - l0 * 2f).coerceIn(0f, 1f).let { it * it }
        val wh = (l0 * 2f - 1f).coerceIn(0f, 1f).let { it * it }
        val wm = (1f - ws - wh).coerceAtLeast(0f)
        fun shift(i: Int) = (shadows[i] * ws + midtones[i] * wm + highlights[i] * wh) / 100f * 0.35f
        r += shift(0); g += shift(1); b += shift(2)
        if (preserveLuminosity) { val d = l0 - lum(r, g, b); r += d; g += d; b += d }
        pack(r, g, b)
    }
}

/** Schwarzweiß mit Gewichtung je Farbbereich (Prozent, wie in Photoshop) und optionaler Tönung. */
data class BlackWhiteAdj(
    val red: Int = 40, val yellow: Int = 60, val green: Int = 40, val cyan: Int = 60, val blue: Int = 20, val magenta: Int = 80,
    val tint: Boolean = false, val tintHue: Int = 35, val tintSaturation: Int = 25,
) : Adjustment() {
    override val type get() = AdjustmentType.BLACK_WHITE
    override fun params() = listOf(
        AdjParam("Rottöne", -200f, 300f, red.toFloat()), AdjParam("Gelbtöne", -200f, 300f, yellow.toFloat()),
        AdjParam("Grüntöne", -200f, 300f, green.toFloat()), AdjParam("Cyantöne", -200f, 300f, cyan.toFloat()),
        AdjParam("Blautöne", -200f, 300f, blue.toFloat()), AdjParam("Magentatöne", -200f, 300f, magenta.toFloat()),
        AdjParam("Farbton", 0f, 1f, if (tint) 1f else 0f, kind = AdjParam.Kind.SWITCH),
        AdjParam("Tönung Farbton", 0f, 360f, tintHue.toFloat(), kind = AdjParam.Kind.HUE),
        AdjParam("Tönung Sättigung", 0f, 100f, tintSaturation.toFloat()),
    )
    override fun withValues(v: FloatArray) = BlackWhiteAdj(
        v[0].roundToInt(), v[1].roundToInt(), v[2].roundToInt(), v[3].roundToInt(), v[4].roundToInt(), v[5].roundToInt(),
        v[6] >= 0.5f, v[7].roundToInt(), v[8].roundToInt(),
    )
    override fun compile(): RgbMap {
        val rgb = FloatArray(3)
        val weights = floatArrayOf(red / 100f, yellow / 100f, green / 100f, cyan / 100f, blue / 100f, magenta / 100f)
        return RgbMap { c ->
            val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            // Photoshop-Verfahren: Grauwert = min + Anteil der beiden Primär-/Sekundärfarben nach Gewicht
            val mn = min(r, min(g, b))
            val rr = r - mn; val gg = g - mn; val bb = b - mn
            val gray: Float
            if (rr == 0f) { val m = min(gg, bb); gray = mn + weights[3] * m + (if (gg > bb) weights[2] * (gg - m) else weights[4] * (bb - m)) }
            else if (gg == 0f) { val m = min(rr, bb); gray = mn + weights[5] * m + (if (rr > bb) weights[0] * (rr - m) else weights[4] * (bb - m)) }
            else { val m = min(rr, gg); gray = mn + weights[1] * m + (if (rr > gg) weights[0] * (rr - m) else weights[2] * (gg - m)) }
            val v = gray.coerceIn(0f, 1f)
            if (!tint) pack(v, v, v) else {
                Colors.hslToRgb(tintHue.toFloat(), tintSaturation / 100f, 0.5f, rgb)
                // Tönung wie „Ineinanderkopieren“ auf dem Grauwert
                fun ov(t: Float) = if (v < 0.5f) 2f * v * t else 1f - 2f * (1f - v) * (1f - t)
                pack(ov(rgb[0]), ov(rgb[1]), ov(rgb[2]))
            }
        }
    }
}

/** Fotofilter: Farbfilter vor dem Objektiv (z. B. Warmfilter 85, Kaltfilter 80). */
data class PhotoFilterAdj(val hue: Int = 30, val density: Int = 25, val preserveLuminosity: Boolean = true) : Adjustment() {
    override val type get() = AdjustmentType.PHOTO_FILTER
    override fun params() = listOf(
        AdjParam("Filterfarbe", 0f, 360f, hue.toFloat(), kind = AdjParam.Kind.HUE),
        AdjParam("Dichte", 0f, 100f, density.toFloat()),
        AdjParam("Luminanz erhalten", 0f, 1f, if (preserveLuminosity) 1f else 0f, kind = AdjParam.Kind.SWITCH),
    )
    override fun withValues(v: FloatArray) = copy(hue = v[0].roundToInt(), density = v[1].roundToInt(), preserveLuminosity = v[2] >= 0.5f)
    val color: Int get() { val o = FloatArray(3); Colors.hslToRgb(hue.toFloat(), 0.85f, 0.55f, o); return pack(o[0], o[1], o[2]) }
    override fun compile(): RgbMap {
        val fc = color; val fr = ((fc shr 16) and 0xFF) / 255f; val fg = ((fc shr 8) and 0xFF) / 255f; val fb = (fc and 0xFF) / 255f
        val d = density / 100f
        return RgbMap { c ->
            val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            // Multiplizieren mit der Filterfarbe, nach Dichte gemischt
            var nr = r + (r * fr - r) * d * 1.6f; var ng = g + (g * fg - g) * d * 1.6f; var nb = b + (b * fb - b) * d * 1.6f
            if (preserveLuminosity) { val dl = lum(r, g, b) - lum(nr, ng, nb); nr += dl; ng += dl; nb += dl }
            pack(nr, ng, nb)
        }
    }
}

object InvertAdj : Adjustment() {
    override val type get() = AdjustmentType.INVERT
    override fun params() = emptyList<AdjParam>()
    override fun withValues(v: FloatArray) = this
    override fun compile() = RgbMap { c -> c.inv() and 0xFFFFFF }
}

data class PosterizeAdj(val levels: Int = 4) : Adjustment() {
    override val type get() = AdjustmentType.POSTERIZE
    override fun params() = listOf(AdjParam("Stufen", 2f, 255f, levels.toFloat()))
    override fun withValues(v: FloatArray) = copy(levels = v[0].roundToInt().coerceIn(2, 255))
    override fun compile(): RgbMap {
        val n = levels - 1
        return lutMap { v -> (v * n).roundToInt().toFloat() / n }
    }
}

data class ThresholdAdj(val level: Int = 128) : Adjustment() {
    override val type get() = AdjustmentType.THRESHOLD
    override fun params() = listOf(AdjParam("Schwellenwert", 1f, 255f, level.toFloat()))
    override fun withValues(v: FloatArray) = copy(level = v[0].roundToInt().coerceIn(1, 255))
    override fun compile() = RgbMap { c ->
        val l = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF))
        if (l >= level) 0xFFFFFF else 0
    }
}

/**
 * Farbtabelle (Photoshop „Color Lookup“): eine 3D-LUT aus einer .cube/.3dl-Datei oder ein
 * eingebauter Look. [fileData]/[fileFormat] halten die Originaldatei für das PSD fest.
 * Die Stärke regelt man wie in Photoshop über die Deckkraft der Einstellungsebene.
 */
class ColorLookupAdj(
    val lutName: String = de.photographics.core.color.Looks.DEFAULT,
    val lut: de.photographics.core.color.Lut3D? = de.photographics.core.color.Looks.byName(lutName)?.lut,
    val fileData: ByteArray? = null,
    val fileFormat: String = "CUBE",
) : Adjustment() {
    override val type get() = AdjustmentType.COLOR_LOOKUP
    override fun params() = emptyList<AdjParam>()
    override fun withValues(v: FloatArray) = this
    override fun compile(): RgbMap {
        val l = lut ?: return RgbMap { it }
        // Arbeitspuffer je Thread: der Compositor rechnet parallel
        val scratch = ThreadLocal.withInitial { FloatArray(3) }
        return RgbMap { c -> l.map(c and 0xFFFFFF, scratch.get()) }
    }

    companion object {
        /** Lädt eine Farbtabellen-Datei (.cube oder .3dl). */
        fun fromFile(bytes: ByteArray, fileName: String): ColorLookupAdj {
            val lut = de.photographics.core.color.Lut3D.parse(bytes, fileName)
            // Wie Photoshop: angezeigt wird der Dateiname
            val name = fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { lut.title }
            val format = if (fileName.endsWith(".3dl", ignoreCase = true)) "3DL" else "CUBE"
            return ColorLookupAdj(name, lut, bytes, format)
        }
    }
}
