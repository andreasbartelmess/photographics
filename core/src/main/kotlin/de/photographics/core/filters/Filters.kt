package de.photographics.core.filters

import de.photographics.core.image.Blur
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Ein Bildfilter verarbeitet einen ARGB-Ausschnitt. [margin] = benötigter Rand (z. B. Blur-Radius).
 * Filter werden streifenweise ausgeführt – kein Filter darf das ganze Bild auf einmal brauchen.
 */
interface ImageFilter {
    val label: String
    val margin: Int get() = 0
    fun process(px: IntArray, w: Int, h: Int): IntArray
}

/** Lesezugriff auf beliebige Originalpixel (Dokumentkoordinaten), ohne das Bild im Speicher zu halten. */
fun interface PixelSource {
    fun get(x: Int, y: Int): Int
}

/**
 * Filter mit Fernzugriff (Verzerrungen, Strahlen): berechnet einen Streifen und liest dafür
 * beliebige Originalpixel über [PixelSource].
 */
interface RandomAccessFilter : ImageFilter {
    fun processBand(src: PixelSource, band: IntRect, docW: Int, docH: Int): IntArray
    override fun process(px: IntArray, w: Int, h: Int): IntArray =
        processBand({ x, y -> px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)] }, IntRect(0, 0, w, h), w, h)
}

/** Filter mit einer Bildstatistik (z. B. Dunst-Farbe), die vorab über Stichproben bestimmt wird. */
interface PreparedFilter : ImageFilter {
    fun prepare(src: PixelSource, docW: Int, docH: Int)
}

/** Bilineare Abtastung über eine [PixelSource] (Ränder werden wiederholt). */
fun PixelSource.bilinear(x: Double, y: Double, w: Int, h: Int): Int {
    val x0 = kotlin.math.floor(x).toInt(); val y0 = kotlin.math.floor(y).toInt()
    val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
    fun at(xx: Int, yy: Int) = get(xx.coerceIn(0, w - 1), yy.coerceIn(0, h - 1))
    val c00 = at(x0, y0); val c10 = at(x0 + 1, y0); val c01 = at(x0, y0 + 1); val c11 = at(x0 + 1, y0 + 1)
    var a = 0f; var r = 0f; var g = 0f; var b = 0f
    fun add(c: Int, wt: Float) { val al = (c ushr 24) * wt; a += al; r += ((c shr 16) and 0xFF) * al; g += ((c shr 8) and 0xFF) * al; b += (c and 0xFF) * al }
    add(c00, (1 - fx) * (1 - fy)); add(c10, fx * (1 - fy)); add(c01, (1 - fx) * fy); add(c11, fx * fy)
    if (a <= 0.5f) return 0
    return Colors.argb(a.roundToInt(), (r / a).roundToInt(), (g / a).roundToInt(), (b / a).roundToInt())
}

/** Punktoperation auf RGB, Alpha bleibt erhalten. Wird über eine 256er-Tabelle beschleunigt, wenn möglich. */
abstract class PixelAdjustment : ImageFilter {
    abstract fun map(c: Int): Int
    override fun process(px: IntArray, w: Int, h: Int) = IntArray(px.size) { i ->
        val c = px[i]
        if ((c ushr 24) == 0) c else (c and 0xFF000000.toInt()) or (map(c) and 0xFFFFFF)
    }
}

/** Operation, die jeden Kanal unabhängig über eine Tabelle abbildet. */
abstract class LutAdjustment : PixelAdjustment() {
    private val lut by lazy { IntArray(256) { channel(it / 255f).let { v -> (v * 255f).roundToInt().coerceIn(0, 255) } } }
    abstract fun channel(v: Float): Float
    override fun map(c: Int) = (lut[Colors.r(c)] shl 16) or (lut[Colors.g(c)] shl 8) or lut[Colors.b(c)]
}

/** Helligkeit/Kontrast, beide -100..100 (moderne Photoshop-Kurve, nicht linear abschneidend). */
class BrightnessContrast(private val brightness: Float, private val contrast: Float) : LutAdjustment() {
    override val label = "Helligkeit/Kontrast"
    override fun channel(v: Float): Float {
        val b = brightness / 100f
        // Helligkeit über Gamma, damit Schwarz und Weiß erhalten bleiben
        var x = if (b >= 0) v.pow(1f / (1f + b * 1.5f)) else v.pow(1f - b * 1.5f)
        val c = contrast / 100f
        val k = if (c >= 0) 1f / (1f - c * 0.99f) else 1f + c
        x = (x - 0.5f) * k + 0.5f
        return x
    }
}

/** Tonwertkorrektur: Eingangs-Schwarz/-Weiß (0..255), Gamma, Ausgangs-Schwarz/-Weiß. */
class Levels(
    private val inBlack: Int = 0, private val inWhite: Int = 255, private val gamma: Float = 1f,
    private val outBlack: Int = 0, private val outWhite: Int = 255,
) : LutAdjustment() {
    override val label = "Tonwertkorrektur"
    override fun channel(v: Float): Float {
        val lo = inBlack / 255f; val hi = maxOf(inWhite, inBlack + 1) / 255f
        val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f).pow(1f / gamma.coerceIn(0.1f, 9.99f))
        return (outBlack + (outWhite - outBlack) * t) / 255f
    }
}

/** Belichtung in Blendenstufen (-5..5), angewendet im linearen Licht. */
class Exposure(private val ev: Float, private val gammaCorrection: Float = 1f) : LutAdjustment() {
    override val label = "Belichtung"
    override fun channel(v: Float): Float {
        val lin = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        val e = (lin * 2f.pow(ev)).coerceIn(0f, 1f)
        val s = if (e <= 0.0031308f) e * 12.92f else 1.055f * e.pow(1f / 2.4f) - 0.055f
        return s.pow(1f / gammaCorrection)
    }
}

class Invert : LutAdjustment() {
    override val label = "Umkehren"
    override fun channel(v: Float) = 1f - v
}

/** Farbton (-180..180), Sättigung (-100..100), Helligkeit (-100..100). */
class HueSaturation(private val hue: Float, private val saturation: Float, private val lightness: Float) : PixelAdjustment() {
    override val label = "Farbton/Sättigung"
    private val hsl = FloatArray(3)
    private val rgb = FloatArray(3)
    override fun map(c: Int): Int {
        Colors.rgbToHsl(Colors.r(c) / 255f, Colors.g(c) / 255f, Colors.b(c) / 255f, hsl)
        val s = saturation / 100f
        hsl[0] += hue
        hsl[1] = if (s >= 0) hsl[1] + (1f - hsl[1]) * s * hsl[1].coerceAtLeast(0.15f) else hsl[1] * (1f + s)
        hsl[1] = hsl[1].coerceIn(0f, 1f)
        val l = lightness / 100f
        hsl[2] = if (l >= 0) hsl[2] + (1f - hsl[2]) * l else hsl[2] * (1f + l)
        Colors.hslToRgb(hsl[0], hsl[1], hsl[2], rgb)
        return Colors.argbF(1f, rgb[0], rgb[1], rgb[2])
    }
}

/** Farbtemperatur (-100 kühl .. 100 warm) und Tönung (-100 grün .. 100 magenta). */
class WhiteBalance(private val temperature: Float, private val tint: Float) : PixelAdjustment() {
    override val label = "Weißabgleich"
    private val rk = 1f + temperature / 100f * 0.25f
    private val bk = 1f - temperature / 100f * 0.25f
    private val gk = 1f - tint / 100f * 0.2f
    override fun map(c: Int) = Colors.argb(255, (Colors.r(c) * rk).roundToInt(), (Colors.g(c) * gk).roundToInt(), (Colors.b(c) * bk).roundToInt())
}

/** Schwarzweiß mit Luminanzgewichtung (Rec. 709). */
class Desaturate : PixelAdjustment() {
    override val label = "Schwarzweiß"
    override fun map(c: Int): Int {
        val y = (0.2126f * Colors.r(c) + 0.7152f * Colors.g(c) + 0.0722f * Colors.b(c)).roundToInt()
        return Colors.argb(255, y, y, y)
    }
}

/** Dynamik: erhöht die Sättigung bevorzugt bei wenig gesättigten Farben (-100..100). */
class Vibrance(private val amount: Float) : PixelAdjustment() {
    override val label = "Dynamik"
    override fun map(c: Int): Int {
        val r = Colors.r(c) / 255f; val g = Colors.g(c) / 255f; val b = Colors.b(c) / 255f
        val mx = maxOf(r, g, b); val avg = (r + g + b) / 3f
        val k = (amount / 100f) * (1f - (mx - avg) * 2f).coerceIn(0f, 1f)
        fun f(v: Float) = mx + (v - mx) * (1f - k)
        return Colors.argbF(1f, f(r), f(g), f(b))
    }
}

class GaussianBlurFilter(private val radius: Float) : ImageFilter {
    override val label = "Gaußscher Weichzeichner"
    override val margin get() = (radius * 3).toInt() + 1
    override fun process(px: IntArray, w: Int, h: Int) = Blur.gaussArgb(px, w, h, radius)
}

/** Unscharf maskieren: Stärke in % (0..500), Radius, Schwellenwert 0..255. */
class UnsharpMask(private val amount: Float, private val radius: Float, private val threshold: Int) : ImageFilter {
    override val label = "Unscharf maskieren"
    override val margin get() = (radius * 3).toInt() + 1
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val bl = Blur.gaussArgb(px, w, h, radius)
        val k = amount / 100f
        return IntArray(px.size) { i ->
            val o = px[i]; val b = bl[i]
            fun ch(s: Int): Int {
                val ov = (o shr s) and 0xFF; val bv = (b shr s) and 0xFF
                val d = ov - bv
                return if (abs(d) < threshold) ov else (ov + d * k).roundToInt()
            }
            Colors.argb(Colors.a(o), ch(16), ch(8), ch(0))
        }
    }
}

/** Rauschen reduzieren: kantenerhaltender Weichzeichner (vereinfachter bilateraler Filter, 5x5). */
class ReduceNoise(private val strength: Float) : ImageFilter {
    override val label = "Rauschen reduzieren"
    override val margin get() = 2
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val sigmaR = 8f + strength * 0.6f
        val inv2 = 1f / (2f * sigmaR * sigmaR)
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            var sr = 0f; var sg = 0f; var sb = 0f; var ws = 0f
            for (dy in -2..2) {
                val yy = (y + dy).coerceIn(0, h - 1)
                for (dx in -2..2) {
                    val n = px[yy * w + (x + dx).coerceIn(0, w - 1)]
                    val dr = Colors.r(n) - Colors.r(c); val dg = Colors.g(n) - Colors.g(c); val db = Colors.b(n) - Colors.b(c)
                    val spatial = 1f / (1f + (dx * dx + dy * dy) * 0.25f)
                    val wgt = spatial * kotlin.math.exp(-(dr * dr + dg * dg + db * db) * inv2 / 3f)
                    sr += Colors.r(n) * wgt; sg += Colors.g(n) * wgt; sb += Colors.b(n) * wgt; ws += wgt
                }
            }
            out[y * w + x] = Colors.argb(Colors.a(c), (sr / ws).roundToInt(), (sg / ws).roundToInt(), (sb / ws).roundToInt())
        }
        return out
    }
}
