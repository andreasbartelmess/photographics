package de.photographics.core.adjust

import de.photographics.core.tr
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
    /** Regler mit gleicher Gruppe zeigt die Oberfläche zusammen, wählbar wie in Photoshop (z. B. Ausgabekanal). */
    val group: String? = null,
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

    /**
     * Harte Stufen (Schwellenwert, Tontrennung): auf gemittelten Pixeln einer verkleinerten Ansicht
     * sähe das Ergebnis anders aus – solche Ansichten rechnen dann in voller Auflösung.
     */
    open val needsFullResolution: Boolean get() = false

    val label get() = type.label
}

enum class AdjustmentType(val label: String, val psdKey: String) {
    BRIGHTNESS_CONTRAST(tr("Helligkeit/Kontrast"), "brit"),
    LEVELS(tr("Tonwertkorrektur"), "levl"),
    CURVES(tr("Gradationskurven"), "curv"),
    EXPOSURE(tr("Belichtung"), "expA"),
    VIBRANCE(tr("Dynamik"), "vibA"),
    HUE_SATURATION(tr("Farbton/Sättigung"), "hue2"),
    COLOR_BALANCE(tr("Farbbalance"), "blnc"),
    BLACK_WHITE(tr("Schwarzweiß"), "blwh"),
    PHOTO_FILTER(tr("Fotofilter"), "phfl"),
    CHANNEL_MIXER(tr("Kanalmixer"), "mixr"),
    COLOR_LOOKUP(tr("Farbtabelle (LUT)"), "clrL"),
    INVERT(tr("Umkehren"), "nvrt"),
    POSTERIZE(tr("Tontrennung"), "post"),
    THRESHOLD(tr("Schwellenwert"), "thrs"),
    SELECTIVE_COLOR(tr("Selektive Farbkorrektur"), "selc");

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
        CHANNEL_MIXER -> ChannelMixerAdj()
        SELECTIVE_COLOR -> SelectiveColorAdj()
    }

    companion object {
        private val byKey = entries.associateBy { it.psdKey }
        fun fromPsdKey(k: String) = byKey[k] ?: if (k == "CgEd") BRIGHTNESS_CONTRAST else null
    }
}

// ---------------------------------------------------------------- Hilfen

/** Tabelle für Operationen, die jeden Kanal gleich behandeln. */
/**
 * Kanalweise Abbildung über drei Tabellen (je 256 Einträge): Rot, Grün und Blau unabhängig. Die GPU
 * erkennt diese Form und rechnet sie über eine kleine Tabellentextur statt einer 3D-Tabelle.
 */
class ChannelLuts(val r: IntArray, val g: IntArray, val b: IntArray) : RgbMap {
    override fun map(rgb: Int): Int = (r[(rgb shr 16) and 0xFF] shl 16) or (g[(rgb shr 8) and 0xFF] shl 8) or b[rgb and 0xFF]
}

private fun lutMap(f: (Float) -> Float): RgbMap {
    val lut = IntArray(256) { (f(it / 255f) * 255f).roundToInt().coerceIn(0, 255) }
    return ChannelLuts(lut, lut, lut)
}

private fun pack(r: Float, g: Float, b: Float): Int =
    (Colors.clamp8((r * 255f).roundToInt()) shl 16) or (Colors.clamp8((g * 255f).roundToInt()) shl 8) or Colors.clamp8((b * 255f).roundToInt())

private fun lum(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

// ---------------------------------------------------------------- Korrekturen

data class BrightnessContrastAdj(val brightness: Int = 0, val contrast: Int = 0) : Adjustment() {
    override val type get() = AdjustmentType.BRIGHTNESS_CONTRAST
    override fun params() = listOf(AdjParam(tr("Helligkeit"), -150f, 150f, brightness.toFloat()), AdjParam(tr("Kontrast"), -50f, 100f, contrast.toFloat()))
    override fun withValues(v: FloatArray) = copy(brightness = v[0].roundToInt(), contrast = v[1].roundToInt())
    override fun compile(): RgbMap {
        // Wie Photoshop (nicht „Früheren Wert verwenden“, in Photoshop 2026 nachgemessen): erst Helligkeit,
        // dann Kontrast, beide als natürlicher Spline durch (0,0), (64,·), (128,·), (192,·), (255,255).
        // Helligkeit < 0 ist die Umkehrfunktion der positiven; Stützhöhen aus Messreihen interpoliert
        // (~/vm-tools/parity/adj, Abweichung meist ≤ 2).
        val bh = brightnessHeights(kotlin.math.abs(brightness).toDouble())
        val bx = doubleArrayOf(0.0, 64.0, 128.0, 192.0, 255.0)
        val by = doubleArrayOf(0.0, bh[0], bh[1], bh[2], 255.0)
        val q = interp(contrast.toDouble(), C_KEYS, C_Q)
        val cy = doubleArrayOf(0.0, q, 128.0, 255.0 - q, 255.0)
        fun bright(v: Double): Double {
            if (brightness >= 0) return CurvesAdj.naturalSpline(bx, by, v)
            // Umkehrfunktion per Bisektion (die Kurve steigt monoton)
            var lo = 0.0; var hi = 255.0
            repeat(40) { val mid = (lo + hi) / 2; if (CurvesAdj.naturalSpline(bx, by, mid) < v) lo = mid else hi = mid }
            return (lo + hi) / 2
        }
        val lut = IntArray(256) { x ->
            val b = bright(x.toDouble()).coerceIn(0.0, 255.0)
            CurvesAdj.naturalSpline(bx, cy, b).roundToInt().coerceIn(0, 255)
        }
        return ChannelLuts(lut, lut, lut)
    }

    private companion object {
        val B_KEYS = doubleArrayOf(0.0, 10.0, 25.0, 50.0, 75.0, 100.0, 150.0)
        val B_H = arrayOf(
            doubleArrayOf(64.0, 68.33, 75.05, 87.9, 102.99, 120.16, 160.8),
            doubleArrayOf(128.0, 136.1, 149.21, 170.83, 191.22, 209.98, 239.96),
            doubleArrayOf(192.0, 204.82, 216.41, 228.77, 237.67, 244.62, 252.05),
        )
        val C_KEYS = doubleArrayOf(-50.0, -25.0, 0.0, 10.0, 25.0, 50.0, 75.0, 100.0)
        val C_Q = doubleArrayOf(76.4, 70.15, 64.0, 60.7, 56.75, 50.55, 44.55, 38.5)

        fun brightnessHeights(b: Double) = DoubleArray(3) { interp(b, B_KEYS, B_H[it]) }
        fun interp(v: Double, xs: DoubleArray, ys: DoubleArray): Double {
            if (v <= xs.first()) return ys.first()
            if (v >= xs.last()) return ys.last()
            var i = 0
            while (v > xs[i + 1]) i++
            return ys[i] + (ys[i + 1] - ys[i]) * (v - xs[i]) / (xs[i + 1] - xs[i])
        }
    }
}

data class LevelsAdj(
    val inBlack: Int = 0, val inWhite: Int = 255, val gamma: Float = 1f,
    val outBlack: Int = 0, val outWhite: Int = 255,
) : Adjustment() {
    override val type get() = AdjustmentType.LEVELS
    override fun params() = listOf(
        AdjParam(tr("Tiefen"), 0f, 253f, inBlack.toFloat()),
        AdjParam(tr("Mitteltöne (Gamma)"), 0.1f, 9.99f, gamma, decimals = 2),
        AdjParam(tr("Lichter"), 2f, 255f, inWhite.toFloat()),
        AdjParam(tr("Ausgabe Schwarz"), 0f, 255f, outBlack.toFloat()),
        AdjParam(tr("Ausgabe Weiß"), 0f, 255f, outWhite.toFloat()),
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
 * Gradationskurve über Stützpunkte (Eingabe → Ausgabe, 0..255), interpoliert wie Photoshop mit einem
 * natürlichen kubischen Spline (pixelgenau nachgemessen).
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
        AdjParam(tr("Tiefen (Ausgabe bei 64)"), 0f, 255f, evaluate(master, 64).toFloat()),
        AdjParam(tr("Mitteltöne (Ausgabe bei 128)"), 0f, 255f, evaluate(master, 128).toFloat()),
        AdjParam(tr("Lichter (Ausgabe bei 192)"), 0f, 255f, evaluate(master, 192).toFloat()),
    )
    override fun withValues(v: FloatArray) = copy(
        master = listOf(0 to 0, 64 to v[0].roundToInt(), 128 to v[1].roundToInt(), 192 to v[2].roundToInt(), 255 to 255),
    )

    override fun compile(): RgbMap {
        val m = table(master)
        val r = red?.let { table(it) }; val g = green?.let { table(it) }; val b = blue?.let { table(it) }
        // Erst die Kanalkurve, dann die Gesamtkurve
        fun chain(c: IntArray?) = IntArray(256) { m[c?.get(it) ?: it] }
        return ChannelLuts(chain(r), chain(g), chain(b))
    }

    companion object {
        fun table(points: List<Pair<Int, Int>>): IntArray = IntArray(256) { evaluate(points, it) }

        fun evaluate(points: List<Pair<Int, Int>>, x: Int): Int = evaluateExact(points, x.toDouble()).roundToInt().coerceIn(0, 255)

        /**
         * Wie Photoshop (in Photoshop 2026 nachgemessen, pixelgenau): natürlicher kubischer Spline durch die
         * Stützpunkte (Krümmung 0 an den Enden), außerhalb waagerecht; Ergebnis gerundet und begrenzt.
         */
        fun evaluateExact(points: List<Pair<Int, Int>>, x: Double): Double {
            val p = points.sortedBy { it.first }.distinctBy { it.first }
            if (p.isEmpty()) return x
            if (p.size == 1) return p[0].second.toDouble()
            if (x <= p.first().first) return p.first().second.toDouble()
            if (x >= p.last().first) return p.last().second.toDouble()
            return naturalSpline(DoubleArray(p.size) { p[it].first.toDouble() }, DoubleArray(p.size) { p[it].second.toDouble() }, x)
        }

        /** Natürlicher kubischer Spline durch ([xs], [ys]) (aufsteigend), außerhalb waagerecht. */
        fun naturalSpline(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
            val n = xs.size
            if (x <= xs[0]) return ys[0]
            if (x >= xs[n - 1]) return ys[n - 1]
            // Zweite Ableitungen (natürliche Randbedingung) über das Tridiagonalsystem
            val m = DoubleArray(n)
            if (n > 2) {
                val a = DoubleArray(n); val b = DoubleArray(n); val c = DoubleArray(n); val d = DoubleArray(n)
                for (i in 1 until n - 1) {
                    a[i] = xs[i] - xs[i - 1]; c[i] = xs[i + 1] - xs[i]; b[i] = 2 * (a[i] + c[i])
                    d[i] = 6 * ((ys[i + 1] - ys[i]) / c[i] - (ys[i] - ys[i - 1]) / a[i])
                }
                for (i in 2 until n - 1) { val w = a[i] / b[i - 1]; b[i] -= w * c[i - 1]; d[i] -= w * d[i - 1] }
                for (i in n - 2 downTo 1) m[i] = (d[i] - (if (i < n - 2) c[i] * m[i + 1] else 0.0)) / b[i]
            }
            var k = 0
            while (k < n - 2 && x > xs[k + 1]) k++
            val h = xs[k + 1] - xs[k]
            val t1 = xs[k + 1] - x; val t0 = x - xs[k]
            return (m[k] * t1 * t1 * t1 + m[k + 1] * t0 * t0 * t0) / (6 * h) +
                (ys[k] / h - m[k] * h / 6) * t1 + (ys[k + 1] / h - m[k + 1] * h / 6) * t0
        }
    }
}

data class ExposureAdj(val exposure: Float = 0f, val offset: Float = 0f, val gamma: Float = 1f) : Adjustment() {
    override val type get() = AdjustmentType.EXPOSURE
    override fun params() = listOf(
        AdjParam(tr("Belichtung"), -5f, 5f, exposure, decimals = 2),
        AdjParam(tr("Versatz"), -0.5f, 0.5f, offset, decimals = 3),
        AdjParam(tr("Gammakorrektur"), 0.1f, 3f, gamma, decimals = 2),
    )
    override fun withValues(v: FloatArray) = copy(exposure = v[0], offset = v[1], gamma = v[2])
    override fun compile() = lutMap { v ->
        // Wie Photoshop (nachgemessen): linear über eine reine Gammakurve 2,2 (nicht die sRGB-Kurve)
        val lin = v.pow(2.2f)
        val e = (lin * 2f.pow(exposure) + offset).coerceIn(0f, 1f).pow(1f / gamma.coerceIn(0.01f, 10f))
        e.pow(1f / 2.2f)
    }
}

data class VibranceAdj(val vibrance: Int = 0, val saturation: Int = 0) : Adjustment() {
    override val type get() = AdjustmentType.VIBRANCE
    override fun params() = listOf(AdjParam(tr("Dynamik"), -100f, 100f, vibrance.toFloat()), AdjParam(tr("Sättigung"), -100f, 100f, saturation.toFloat()))
    override fun withValues(v: FloatArray) = copy(vibrance = v[0].roundToInt(), saturation = v[1].roundToInt())
    /** Wie Photoshop (nachgemessen), Verfahren in [PsVibrance]. */
    override fun compile(): RgbMap {
        if (vibrance == 0 && saturation == 0) return RgbMap { it and 0xFFFFFF }
        val v = PsVibrance(vibrance, saturation)
        return RgbMap { c -> v.map(c) }
    }
}

data class HueSaturationAdj(val hue: Int = 0, val saturation: Int = 0, val lightness: Int = 0, val colorize: Boolean = false) : Adjustment() {
    override val type get() = AdjustmentType.HUE_SATURATION
    override fun params() = listOf(
        AdjParam(tr("Farbton"), -180f, 180f, hue.toFloat(), kind = AdjParam.Kind.HUE),
        AdjParam(tr("Sättigung"), -100f, 100f, saturation.toFloat()),
        AdjParam(tr("Helligkeit"), -100f, 100f, lightness.toFloat()),
        AdjParam(tr("Färben"), 0f, 1f, if (colorize) 1f else 0f, kind = AdjParam.Kind.SWITCH),
    )
    override fun withValues(v: FloatArray) = copy(hue = v[0].roundToInt(), saturation = v[1].roundToInt(), lightness = v[2].roundToInt(), colorize = v[3] >= 0.5f)
    override fun compile(): RgbMap {
        // Hilfsarrays je Thread: Anzeige und GPU-Tabellen rufen die Abbildung parallel auf
        val scratch = ThreadLocal.withInitial { FloatArray(3) to FloatArray(3) }
        val s = saturation / 100f; val l = lightness / 100f
        // Wie Photoshop (in Photoshop 2026 nachgemessen, Abweichung ≤ 1–2): erst Helligkeit kanalweise
        // (zu Weiß bzw. Schwarz mischen), dann Farbton (HSL) und Sättigung (Photoshop-Verfahren über die
        // HSL-Helligkeit); Färben = HSL(Farbton, Sättigung/100, HSL-Helligkeit).
        return RgbMap { c ->
            val (hsl, rgb) = scratch.get()
            var r = ((c shr 16) and 0xFF) / 255f; var g = ((c shr 8) and 0xFF) / 255f; var b = (c and 0xFF) / 255f
            if (l >= 0) { r += (1f - r) * l; g += (1f - g) * l; b += (1f - b) * l } else { r *= 1f + l; g *= 1f + l; b *= 1f + l }
            if (colorize) {
                val light = (max(r, max(g, b)) + min(r, min(g, b))) / 2f
                Colors.hslToRgb(((hue + 360) % 360).toFloat(), s.coerceIn(0f, 1f), light, rgb)
                return@RgbMap pack(rgb[0], rgb[1], rgb[2])
            }
            if (hue != 0) {
                Colors.rgbToHsl(r, g, b, hsl)
                Colors.hslToRgb(hsl[0] + hue, hsl[1], hsl[2], rgb)
                r = rgb[0]; g = rgb[1]; b = rgb[2]
            }
            if (s != 0f) {
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val delta = mx - mn; val value = mx + mn
                if (delta > 0f) {
                    val light = value / 2f
                    val sat = if (light < 0.5f) delta / value else delta / (2f - value)
                    val k = if (s >= 0f) 1f / (if (s + sat >= 1f) sat else 1f - s) - 1f else s
                    r += (r - light) * k; g += (g - light) * k; b += (b - light) * k
                }
            }
            pack(r, g, b)
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
    // Wie Photoshop: Farbtonbalance Tiefen / Mitteltöne / Lichter mit je drei Reglern
    override fun params(): List<AdjParam> =
        listOf(tr("Tiefen") to shadows, tr("Mitteltöne") to midtones, tr("Lichter") to highlights).flatMap { (g, vals) ->
            listOf(tr("Cyan ↔ Rot"), tr("Magenta ↔ Grün"), tr("Gelb ↔ Blau")).mapIndexed { i, l -> AdjParam(l, -100f, 100f, vals[i].toFloat(), group = g) }
        } + AdjParam(tr("Luminanz erhalten"), 0f, 1f, if (preserveLuminosity) 1f else 0f, kind = AdjParam.Kind.SWITCH)
    override fun withValues(v: FloatArray) = copy(
        shadows = listOf(v[0].roundToInt(), v[1].roundToInt(), v[2].roundToInt()),
        midtones = listOf(v[3].roundToInt(), v[4].roundToInt(), v[5].roundToInt()),
        highlights = listOf(v[6].roundToInt(), v[7].roundToInt(), v[8].roundToInt()),
        preserveLuminosity = v[9] >= 0.5f,
    )
    /**
     * Wie Photoshop (in Photoshop 2026 nachgemessen, pixelgenau bis ±1): je Kanal eine Tonwertkorrektur.
     * Ohne „Luminanz erhalten“: Gamma 2^−(Tiefen/200 + Mitteltöne/100 + Lichter/200), Tiefen < 0 setzen den
     * Eingabe-Schwarzpunkt auf −Wert, Lichter > 0 den Eingabe-Weißpunkt auf 255 − Wert. Mit „Luminanz
     * erhalten“: Mitteltöne wirken halb und gegenläufig auf die anderen Kanäle, Tiefen nur als Schwarzpunkt
     * max(Tiefen) − Wert, Lichter nur als Weißpunkt 255 − (Wert − min(Lichter)).
     */
    override fun compile(): RgbMap {
        val luts = Array(3) { ch ->
            val ib: Double; val iw: Double; val gamma: Double
            if (!preserveLuminosity) {
                ib = max(0, -shadows[ch]).toDouble(); iw = 255.0 - max(0, highlights[ch])
                gamma = Math.pow(2.0, -(shadows[ch] / 200.0 + midtones[ch] / 100.0 + highlights[ch] / 200.0))
            } else {
                ib = (shadows.max() - shadows[ch]).toDouble().coerceAtLeast(0.0)
                iw = 255.0 - (highlights[ch] - highlights.min()).coerceAtLeast(0)
                val m = (midtones[ch] - (midtones.sum() - midtones[ch])) / 2.0
                gamma = Math.pow(2.0, -m / 100.0)
            }
            IntArray(256) { x -> (255.0 * Math.pow(((x - ib) / (iw - ib).coerceAtLeast(1.0)).coerceIn(0.0, 1.0), gamma)).roundToInt() }
        }
        return ChannelLuts(luts[0], luts[1], luts[2])
    }
}

/** Schwarzweiß mit Gewichtung je Farbbereich (Prozent, wie in Photoshop) und optionaler Tönung. */
data class BlackWhiteAdj(
    val red: Int = 40, val yellow: Int = 60, val green: Int = 40, val cyan: Int = 60, val blue: Int = 20, val magenta: Int = 80,
    val tint: Boolean = false, val tintHue: Int = 35, val tintSaturation: Int = 25,
) : Adjustment() {
    override val type get() = AdjustmentType.BLACK_WHITE
    override fun params() = listOf(
        AdjParam(tr("Rottöne"), -200f, 300f, red.toFloat()), AdjParam(tr("Gelbtöne"), -200f, 300f, yellow.toFloat()),
        AdjParam(tr("Grüntöne"), -200f, 300f, green.toFloat()), AdjParam(tr("Cyantöne"), -200f, 300f, cyan.toFloat()),
        AdjParam(tr("Blautöne"), -200f, 300f, blue.toFloat()), AdjParam(tr("Magentatöne"), -200f, 300f, magenta.toFloat()),
        AdjParam(tr("Farbton"), 0f, 1f, if (tint) 1f else 0f, kind = AdjParam.Kind.SWITCH),
        AdjParam(tr("Tönung Farbton"), 0f, 360f, tintHue.toFloat(), kind = AdjParam.Kind.HUE),
        AdjParam(tr("Tönung Sättigung"), 0f, 100f, tintSaturation.toFloat()),
    )
    override fun withValues(v: FloatArray) = BlackWhiteAdj(
        v[0].roundToInt(), v[1].roundToInt(), v[2].roundToInt(), v[3].roundToInt(), v[4].roundToInt(), v[5].roundToInt(),
        v[6] >= 0.5f, v[7].roundToInt(), v[8].roundToInt(),
    )
    override fun compile(): RgbMap {
        // Tönungsfarbe einmal vorab (die Abbildung läuft parallel auf mehreren Threads)
        val rgb = FloatArray(3).also { Colors.hslToRgb(tintHue.toFloat(), tintSaturation / 100f, 0.5f, it) }
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
        AdjParam(tr("Filterfarbe"), 0f, 360f, hue.toFloat(), kind = AdjParam.Kind.HUE),
        AdjParam(tr("Dichte"), 0f, 100f, density.toFloat()),
        AdjParam(tr("Luminanz erhalten"), 0f, 1f, if (preserveLuminosity) 1f else 0f, kind = AdjParam.Kind.SWITCH),
    )
    override fun withValues(v: FloatArray) = copy(hue = v[0].roundToInt(), density = v[1].roundToInt(), preserveLuminosity = v[2] >= 0.5f)
    val color: Int get() { val o = FloatArray(3); Colors.hslToRgb(hue.toFloat(), 0.85f, 0.55f, o); return pack(o[0], o[1], o[2]) }
    override fun compile(): RgbMap {
        // Wie Photoshop (in Photoshop 2026 nachgemessen, mittlere Abweichung ≈ 1): Filterfarbe in XYZ
        // multiplizieren (lineares sRGB, nach Dichte gemischt) = feste 3×3-Matrix im linearen Licht;
        // „Luminanz erhalten“ wie der Mischmodus Luminanz (Luma 0,3/0,59/0,11 setzen, farbtonerhaltend kappen).
        val a = matrix()
        return RgbMap { c ->
            val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            val lr = srgbLin(r); val lg = srgbLin(g); val lb = srgbLin(b)
            var nr = srgbEnc(a[0] * lr + a[1] * lg + a[2] * lb)
            var ng = srgbEnc(a[3] * lr + a[4] * lg + a[5] * lb)
            var nb = srgbEnc(a[6] * lr + a[7] * lg + a[8] * lb)
            if (preserveLuminosity) {
                val o = setLum(nr, ng, nb, 0.3f * r + 0.59f * g + 0.11f * b)
                nr = o[0]; ng = o[1]; nb = o[2]
            }
            pack(nr, ng, nb)
        }
    }

    /** Wirkung im linearen sRGB als Zeilenmatrix (3×3): M⁻¹ · diag(1 − d + d·XYZ(Filter)/Weiß) · M. */
    fun matrix(): FloatArray {
        val fc = color
        val f = doubleArrayOf(srgbLin(((fc shr 16) and 0xFF) / 255f).toDouble(), srgbLin(((fc shr 8) and 0xFF) / 255f).toDouble(), srgbLin((fc and 0xFF) / 255f).toDouble())
        val d = density / 100.0
        val k = DoubleArray(3) { i -> val xf = M[i][0] * f[0] + M[i][1] * f[1] + M[i][2] * f[2]; val wn = M[i][0] + M[i][1] + M[i][2]; 1 - d + d * xf / wn }
        return FloatArray(9) { idx -> val i = idx / 3; val j = idx % 3; (0 until 3).sumOf { t -> MI[i][t] * k[t] * M[t][j] }.toFloat() }
    }

    companion object {
        private val M = arrayOf(doubleArrayOf(0.4124, 0.3576, 0.1805), doubleArrayOf(0.2126, 0.7152, 0.0722), doubleArrayOf(0.0193, 0.1192, 0.9505))
        private val MI = arrayOf(
            doubleArrayOf(3.2406255, -1.5372080, -0.4986286), doubleArrayOf(-0.9689307, 1.8757561, 0.0415175), doubleArrayOf(0.0557101, -0.2040211, 1.0569959))
        fun srgbLin(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        fun srgbEnc(v: Float): Float { val x = max(0f, v); return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f }

        /** Mischmodus-Luminanz (PDF/W3C SetLum + ClipColor) mit Luma 0,3/0,59/0,11. */
        fun setLum(r0: Float, g0: Float, b0: Float, l: Float): FloatArray {
            val dl = l - (0.3f * r0 + 0.59f * g0 + 0.11f * b0)
            var r = r0 + dl; var g = g0 + dl; var b = b0 + dl
            val lum = 0.3f * r + 0.59f * g + 0.11f * b
            val n = min(r, min(g, b)); val x = max(r, max(g, b))
            if (n < 0f) { val k = lum / (lum - n); r = lum + (r - lum) * k; g = lum + (g - lum) * k; b = lum + (b - lum) * k }
            if (x > 1f) { val k = (1f - lum) / (x - lum); r = lum + (r - lum) * k; g = lum + (g - lum) * k; b = lum + (b - lum) * k }
            return floatArrayOf(r, g, b)
        }
    }
}

object InvertAdj : Adjustment() {
    override val type get() = AdjustmentType.INVERT
    override fun params() = emptyList<AdjParam>()
    override fun withValues(v: FloatArray) = this
    override fun compile(): RgbMap { val t = IntArray(256) { 255 - it }; return ChannelLuts(t, t, t) }
}

data class PosterizeAdj(val levels: Int = 4) : Adjustment() {
    override val type get() = AdjustmentType.POSTERIZE
    override val needsFullResolution get() = true
    override fun params() = listOf(AdjParam(tr("Stufen"), 2f, 255f, levels.toFloat()))
    override fun withValues(v: FloatArray) = copy(levels = v[0].roundToInt().coerceIn(2, 255))
    override fun compile(): RgbMap {
        // Wie Photoshop (pixelgenau nachgemessen): Stufe = ⌊Wert·n/256⌋, Ausgabe = ⌊Stufe·255/(n−1)⌋
        val lut = IntArray(256) { v -> (v * levels / 256) * 255 / (levels - 1) }
        return ChannelLuts(lut, lut, lut)
    }
}

data class ThresholdAdj(val level: Int = 128) : Adjustment() {
    override val type get() = AdjustmentType.THRESHOLD
    override val needsFullResolution get() = true
    override fun params() = listOf(AdjParam(tr("Schwellenwert"), 1f, 255f, level.toFloat()))
    override fun withValues(v: FloatArray) = copy(level = v[0].roundToInt().coerceIn(1, 255))
    override fun compile() = RgbMap { c ->
        // Wie Photoshop (pixelgenau nachgemessen): Luminanz mit 15-Bit-Gewichten ≈ 0,30/0,59/0,11, gerundet
        val l = (9829 * ((c shr 16) and 0xFF) + 19335 * ((c shr 8) and 0xFF) + 3604 * (c and 0xFF) + 16384) shr 15
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

/**
 * Kanalmixer wie Photoshop (in Photoshop 2026 nachgemessen, Abweichung ≤ 1): jeder Ausgabekanal ist
 * Rot·r + Grün·g + Blau·b (Prozent) + Konstante·2,55, abgerundet; „Monochrom“ nimmt die erste Zeile für alle.
 * [rows]: je Ausgabekanal R, G, B (−200…200 %) und Konstante (−200…200 %).
 */
data class ChannelMixerAdj(
    val rows: List<List<Int>> = listOf(listOf(100, 0, 0, 0), listOf(0, 100, 0, 0), listOf(0, 0, 100, 0)),
    val monochrome: Boolean = false,
) : Adjustment() {
    override val type get() = AdjustmentType.CHANNEL_MIXER
    override fun params(): List<AdjParam> {
        val out = listOf(tr("Rot"), tr("Grün"), tr("Blau")); val src = listOf(tr("Rot"), tr("Grün"), tr("Blau"), tr("Konstante"))
        return (0 until 3).flatMap { o -> (0 until 4).map { i -> AdjParam("${src[i]} %", -200f, 200f, rows[o][i].toFloat(), group = out[o]) } } +
            AdjParam(tr("Monochrom"), 0f, 1f, if (monochrome) 1f else 0f, kind = AdjParam.Kind.SWITCH)
    }
    override fun withValues(v: FloatArray) = ChannelMixerAdj(List(3) { o -> List(4) { i -> v[o * 4 + i].roundToInt() } }, v[12] >= 0.5f)
    override fun compile(): RgbMap {
        val m = if (monochrome) List(3) { rows[0] } else rows
        val w = Array(3) { o -> DoubleArray(4) { i -> m[o][i] / 100.0 } }
        return RgbMap { c ->
            val r = ((c shr 16) and 0xFF).toDouble(); val g = ((c shr 8) and 0xFF).toDouble(); val b = (c and 0xFF).toDouble()
            fun ch(o: Int) = kotlin.math.floor(w[o][0] * r + w[o][1] * g + w[o][2] * b + w[o][3] * 255.0 + 1e-9).toInt().coerceIn(0, 255)
            (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
    }
}

/**
 * Selektive Farbkorrektur wie Photoshop (in Photoshop 2026 nachgemessen, Abweichung ≤ 1). Für neun Bereiche
 * (Rot, Gelb, Grün, Cyan, Blau, Magenta, Weiß, Neutral, Schwarz) je Cyan/Magenta/Gelb/Schwarz in %.
 * Druckfarbe x = 1 − Kanal; wirksam A = (1 + Farbe)·Schwarz + Farbe; relativ x' = x + x·A, absolut
 * x' = x + A, begrenzt auf 0…1 und mit dem Bereichsgewicht gemischt: Primärfarben Max − Mitte,
 * Sekundärfarben Mitte − Min, Weiß 2·(Min − ½), Schwarz 2·(½ − Max), Neutral 1 − |Max − ½| − |Min − ½|.
 */
data class SelectiveColorAdj(
    val values: List<List<Int>> = List(9) { listOf(0, 0, 0, 0) },
    val absolute: Boolean = false,
) : Adjustment() {
    override val type get() = AdjustmentType.SELECTIVE_COLOR
    override fun params(): List<AdjParam> {
        val inks = listOf(tr("Cyan"), tr("Magenta"), tr("Gelb"), tr("Schwarz"))
        return RANGES.indices.flatMap { r -> (0 until 4).map { i -> AdjParam("${inks[i]} %", -100f, 100f, values[r][i].toFloat(), group = RANGES[r]) } } +
            AdjParam(tr("Absolut"), 0f, 1f, if (absolute) 1f else 0f, kind = AdjParam.Kind.SWITCH)
    }
    override fun withValues(v: FloatArray) = SelectiveColorAdj(List(9) { r -> List(4) { i -> v[r * 4 + i].roundToInt() } }, v[36] >= 0.5f)
    override fun compile(): RgbMap {
        val a = Array(9) { r -> DoubleArray(4) { i -> values[r][i] / 100.0 } }
        val used = (0 until 9).filter { r -> a[r].any { it != 0.0 } }
        return RgbMap { c ->
            val x = doubleArrayOf(((c shr 16) and 0xFF) / 255.0, ((c shr 8) and 0xFF) / 255.0, (c and 0xFF) / 255.0)
            var imax = 0; var imin = 0
            for (i in 1 until 3) { if (x[i] > x[imax]) imax = i; if (x[i] < x[imin]) imin = i }
            if (imax == imin) imin = (imax + 1) % 3
            val mx = x[imax]; val mn = x[imin]; val mid = x[0] + x[1] + x[2] - mx - mn
            val ink = DoubleArray(3) { 1 - x[it] }
            val out = ink.copyOf()
            for (r in used) {
                val wgt = when (r) {
                    0 -> if (imax == 0) mx - mid else 0.0
                    2 -> if (imax == 1) mx - mid else 0.0
                    4 -> if (imax == 2) mx - mid else 0.0
                    1 -> if (imin == 2) mid - mn else 0.0
                    3 -> if (imin == 0) mid - mn else 0.0
                    5 -> if (imin == 1) mid - mn else 0.0
                    6 -> max(0.0, (mn - 0.5) * 2)
                    8 -> max(0.0, (0.5 - mx) * 2)
                    else -> max(0.0, 1 - (kotlin.math.abs(mx - 0.5) + kotlin.math.abs(mn - 0.5)))
                }
                if (wgt <= 0.0) continue
                val k = a[r][3]
                for (ch in 0 until 3) {
                    val aa = (1 + a[r][ch]) * k + a[r][ch]
                    val target = (ink[ch] + if (absolute) aa else ink[ch] * aa).coerceIn(0.0, 1.0)
                    out[ch] += wgt * (target - ink[ch])
                }
            }
            fun ch(i: Int) = ((1 - out[i]) * 255).roundToInt().coerceIn(0, 255)
            (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
    }

    companion object {
        val RANGES = listOf(tr("Rottöne"), tr("Gelbtöne"), tr("Grüntöne"), tr("Cyantöne"), tr("Blautöne"), tr("Magentatöne"), tr("Weiß"), tr("Neutrale Farben"), tr("Schwarz"))
    }
}
