package de.photographics.core.filters

import de.photographics.core.tr
import de.photographics.core.image.Blur
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.image.Resample
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Filter, die die Lage im Dokument brauchen (Mittelpunkt, Rand). Bekommen das ganze Dokument;
 * ([offX], [offY]) ist die Lage des Ausschnitts, ([docW], [docH]) die Dokumentgröße.
 */
interface GeometryFilter : ImageFilter {
    fun process(px: IntArray, w: Int, h: Int, offX: Int, offY: Int, docW: Int, docH: Int): IntArray
    override fun process(px: IntArray, w: Int, h: Int) = process(px, w, h, 0, 0, w, h)
}

// ---------------------------------------------------------------- Hilfen

private inline fun mapPixels(px: IntArray, f: (Int) -> Int) = IntArray(px.size) { f(px[it]) }

private fun lumOf(c: Int) = 0.299f * Colors.r(c) + 0.587f * Colors.g(c) + 0.114f * Colors.b(c)

/** Deterministisches Rauschen −1..1. */
private fun hashNoise(i: Int, salt: Int): Float {
    var h = i * 374761393 + salt * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return ((h and 0xFFFF) / 32767.5f) - 1f
}

/** Graustufenebene eines Kanals als Float-Feld (für Weichzeichner auf Luminanz). */
private fun lumaPlane(px: IntArray) = FloatArray(px.size) { lumOf(px[it]) }

// ---------------------------------------------------------------- Weichzeichnungsfilter

/** Bewegungsunschärfe: Mittelung entlang einer Linie mit Winkel und Distanz (Pixel). */
class MotionBlur(private val angle: Float, private val distance: Int) : ImageFilter {
    override val label = tr("Bewegungsunschärfe")
    override val margin get() = distance / 2 + 2
    /*
     * Wie Photoshop (Punktantwort in Photoshop 2026 gemessen): Distanz + 1 gleich gewichtete Abtastungen
     * im Abstand von 1 px entlang der Richtung, zentriert (−Distanz/2 … +Distanz/2); waagerecht/senkrecht
     * exakt Photoshops Kastenfilter, schräg bilinear interpoliert.
     */
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val rad = Math.toRadians(angle.toDouble())
        val dx = cos(rad).toFloat(); val dy = -sin(rad).toFloat()
        val n = max(1, distance) + 1
        val half = (n - 1) / 2f
        return IntArray(px.size) { i ->
            val x = i % w; val y = i / w
            var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f
            for (k in 0 until n) {
                val t = k - half
                val fx = (x + dx * t).coerceIn(0f, (w - 1).toFloat()); val fy = (y + dy * t).coerceIn(0f, (h - 1).toFloat())
                val x0 = fx.toInt(); val y0 = fy.toInt(); val ax = fx - x0; val ay = fy - y0
                val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
                for (q in 0 until 4) {
                    val wgt = (if (q and 1 == 0) 1f - ax else ax) * (if (q < 2) 1f - ay else ay)
                    if (wgt <= 0f) continue
                    val c = px[(if (q < 2) y0 else y1) * w + (if (q and 1 == 0) x0 else x1)]
                    val a = Colors.a(c) / 255f * wgt
                    sa += a; sr += Colors.r(c) * a; sg += Colors.g(c) * a; sb += Colors.b(c) * a
                }
            }
            if (sa <= 0f) 0 else Colors.argb((sa / n * 255).roundToInt(), (sr / sa).roundToInt(), (sg / sa).roundToInt(), (sb / sa).roundToInt())
        }
    }
}

/** Radialer Weichzeichner: Kreisförmig (Drehen) oder Strahlenförmig (Zoom) um die Bildmitte. */
class RadialBlur(private val amount: Float, private val zoom: Boolean, private val cxRel: Float = 0.5f, private val cyRel: Float = 0.5f) : RandomAccessFilter {
    override val label = tr("Radialer Weichzeichner")
    /*
     * Wie Photoshop (Qualität „Sehr gut“, in Photoshop 2026 nachgemessen): kreisförmig = gleich gewichtete
     * Abtastung über einen Bogen von insgesamt „Stärke“ Grad, strahlenförmig = über den Faktor 1 ± Stärke/400
     * des Abstands zur Mitte; bilinear, Mittelpunkt in Pixelmitte. Etwa eine Abtastung je Pixel Spurlänge,
     * jeweils in der Mitte ihres Teilstücks.
     */
    override fun processBand(src: PixelSource, band: IntRect, docW: Int, docH: Int): IntArray {
        val cx = docW * cxRel - 0.5; val cy = docH * cyRel - 0.5
        val span = if (zoom) amount / 400.0 else Math.toRadians(amount.toDouble()) / 2
        val w = band.width
        return IntArray(w * band.height) { i ->
            val x = band.left + i % w; val y = band.top + i / w
            val vx = x - cx; val vy = y - cy
            val r = kotlin.math.sqrt(vx * vx + vy * vy)
            val n = (2 * span * r).toInt().coerceIn(1, 400) + 1
            var aa = 0.0; var ar = 0.0; var ag = 0.0; var ab = 0.0
            for (k in 0 until n) {
                // Abtastung in der Mitte gleich langer Teilstücke (passt besser zu Photoshop als mit Endpunkten)
                val t = ((k + 0.5) / n * 2 - 1) * span
                val sx: Double; val sy: Double
                if (zoom) { sx = cx + vx * (1 + t); sy = cy + vy * (1 + t) }
                else { val ca = kotlin.math.cos(t); val sa = kotlin.math.sin(t); sx = cx + vx * ca - vy * sa; sy = cy + vx * sa + vy * ca }
                val c = src.bilinear(sx, sy, docW, docH)
                val al = Colors.a(c) / 255.0
                aa += al; ar += Colors.r(c) * al; ag += Colors.g(c) * al; ab += Colors.b(c) * al
            }
            if (aa <= 0.0) 0 else Colors.argb((aa / n * 255).roundToInt(), (ar / aa).roundToInt(), (ag / aa).roundToInt(), (ab / aa).roundToInt())
        }
    }
}

/**
 * Matter machen wie Photoshop (pixelgenau nachgemessen): je Farbkanal gewichteter Mittelwert im
 * quadratischen Fenster (Radius), Gewicht = max(0, 1 − |Nachbar − Pixel| / (2,5 · Schwellenwert)).
 * Gleitendes Histogramm je Zeile – Aufwand wächst nur linear mit dem Radius.
 */
class SurfaceBlur(private val radius: Int, private val threshold: Int) : ImageFilter {
    override val label = tr("Matter machen")
    override val margin get() = radius + 1
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val r = max(1, radius)
        val t = 2.5 * max(1, threshold)
        val span = kotlin.math.floor(t).toInt().coerceAtMost(255)
        // Gewichte je Abstand 0..255
        val wt = DoubleArray(256) { d -> max(0.0, 1 - d / t) }
        val out = IntArray(px.size) { px[it] and (0xFF shl 24) }
        val hist = IntArray(256)
        for (shift in intArrayOf(16, 8, 0)) {
            val ch = IntArray(px.size) { (px[it] shr shift) and 0xFF }
            for (y in 0 until h) {
                java.util.Arrays.fill(hist, 0)
                fun col(x: Int, d: Int) {
                    val xx = x.coerceIn(0, w - 1)
                    for (dy in -r..r) hist[ch[(y + dy).coerceIn(0, h - 1) * w + xx]] += d
                }
                for (dx in -r..r) col(dx, 1)
                for (x in 0 until w) {
                    if (x > 0) { col(x - r - 1, -1); col(x + r, 1) }
                    val p = ch[y * w + x]
                    var num = 0.0; var den = 0.0
                    for (v in max(0, p - span)..min(255, p + span)) {
                        val n = hist[v]; if (n == 0) continue
                        val g = wt[kotlin.math.abs(v - p)] * n
                        num += g * v; den += g
                    }
                    val o = if (den > 0) (num / den).roundToInt() else p
                    out[y * w + x] = out[y * w + x] or (o shl shift)
                }
            }
        }
        return out
    }
}

/** Tilt-Shift (Miniatur-Effekt): scharfer Streifen, nach oben und unten zunehmend unscharf. */
class TiltShift(private val blur: Float, private val focusRel: Float = 0.5f, private val bandRel: Float = 0.15f) : GeometryFilter {
    override val label = tr("Tilt-Shift")
    override val margin get() = (blur * 3).toInt() + 1
    override fun process(px: IntArray, w: Int, h: Int, offX: Int, offY: Int, docW: Int, docH: Int): IntArray {
        val blurred = Blur.gaussArgb(px, w, h, blur)
        val focus = docH * focusRel - offY; val band = docH * bandRel
        return IntArray(px.size) { i ->
            val y = i / w
            val d = (abs(y - focus) - band / 2) / (band * 1.5f)
            Colors.lerp(px[i], blurred[i], d.coerceIn(0f, 1f))
        }
    }
}

// ---------------------------------------------------------------- Rauschfilter

/** Median (Rauschen entfernen, Werte je Kanal) mit Radius. */
class Median(private val radius: Int) : ImageFilter {
    override val label = tr("Median")
    override val margin get() = radius + 1
    override fun process(px: IntArray, w: Int, h: Int) = median(px, w, h, radius)

    companion object {
        /** Histogramm-basierter Median (Huang) je Kanal, O(n·r). */
        fun median(px: IntArray, w: Int, h: Int, r: Int): IntArray {
            val out = IntArray(px.size) { px[it] and 0xFF000000.toInt() }
            val area = (2 * r + 1) * (2 * r + 1)
            val half = area / 2
            for (shift in intArrayOf(16, 8, 0)) {
                for (y in 0 until h) {
                    val hist = IntArray(256)
                    for (dy in -r..r) for (dx in -r..r) hist[(px[(y + dy).coerceIn(0, h - 1) * w + dx.coerceIn(0, w - 1)] shr shift) and 0xFF]++
                    for (x in 0 until w) {
                        if (x > 0) for (dy in -r..r) {
                            val yy = (y + dy).coerceIn(0, h - 1) * w
                            hist[(px[yy + (x - r - 1).coerceIn(0, w - 1)] shr shift) and 0xFF]--
                            hist[(px[yy + (x + r).coerceIn(0, w - 1)] shr shift) and 0xFF]++
                        }
                        var acc = 0; var v = 0
                        while (v < 255) { acc += hist[v]; if (acc > half) break; v++ }
                        out[y * w + x] = out[y * w + x] or (v shl shift)
                    }
                }
            }
            return out
        }
    }
}

/** Staub & Kratzer: Median, aber nur dort, wo sich das Pixel deutlich unterscheidet. */
class DustAndScratches(private val radius: Int, private val threshold: Int) : ImageFilter {
    override val label = tr("Staub & Kratzer")
    override val margin get() = radius + 1
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val med = Median.median(px, w, h, radius)
        return IntArray(px.size) { i ->
            val o = px[i]; val m = med[i]
            val d = max(abs(Colors.r(o) - Colors.r(m)), max(abs(Colors.g(o) - Colors.g(m)), abs(Colors.b(o) - Colors.b(m))))
            if (d > threshold) m else o
        }
    }
}

/** Rauschen hinzufügen: Stärke in %, gaußverteilt oder gleichmäßig, optional einfarbig. */
class AddNoise(private val amount: Float, private val gaussian: Boolean, private val monochrome: Boolean) : GeometryFilter {
    override val label = tr("Rauschen hinzufügen")
    /** Rauschen hängt an der Dokumentposition – gleiches Muster, egal in welchen Streifen gerechnet wird. */
    override fun process(px: IntArray, w: Int, h: Int, offX: Int, offY: Int, docW: Int, docH: Int): IntArray {
        val k = amount / 100f * 255f
        // Wie Photoshop (nachgemessen): gaußsch mit Streuung = Stärke·2,55 (Summe aus vier gleichverteilten
        // Werten, Ausläufer bis ≈3,4σ), gleichmäßig im Bereich ±Stärke·2,55
        fun n(i: Int, salt: Int): Float = if (gaussian)
            (hashNoise(i, salt) + hashNoise(i, salt + 7) + hashNoise(i, salt + 13) + hashNoise(i, salt + 19)) * 0.8660254f
        else hashNoise(i, salt)
        val stride = max(docW, w)
        return IntArray(px.size) { j ->
            val c = px[j]
            val i = (offY + j / w) * stride + offX + j % w
            val nr = n(i, 1) * k
            val ng = if (monochrome) nr else n(i, 2) * k
            val nb = if (monochrome) nr else n(i, 3) * k
            Colors.argb(Colors.a(c), (Colors.r(c) + nr).roundToInt(), (Colors.g(c) + ng).roundToInt(), (Colors.b(c) + nb).roundToInt())
        }
    }
}

// ---------------------------------------------------------------- Sonstige / Stilisierungsfilter

/** Hochpass: nur feine Details bleiben (Basis für Scharfzeichnen per „Ineinanderkopieren“). */
class HighPass(private val radius: Float) : ImageFilter {
    override val label = tr("Hochpass")
    override val margin get() = (radius * 3).toInt() + 1
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val bl = de.photographics.core.image.PsGauss.blurArgb(px, w, h, radius)
        return IntArray(px.size) { i ->
            val o = px[i]; val b = bl[i]
            Colors.argb(Colors.a(o), 128 + Colors.r(o) - Colors.r(b), 128 + Colors.g(o) - Colors.g(b), 128 + Colors.b(o) - Colors.b(b))
        }
    }
}

/** Relief: Prägung aus der Helligkeit in Lichtrichtung. */
class Emboss(private val angle: Float, private val height: Int, private val amount: Float) : ImageFilter {
    override val label = tr("Relief")
    override val margin get() = height + 2
    /** Wie Photoshop (nachgemessen): je Farbkanal 128 + Stärke/100 · (Wert ½ Höhe in Lichtrichtung − ½ Höhe dagegen), bilinear. */
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val rad = Math.toRadians(angle.toDouble())
        val ox = cos(rad) * height / 2; val oy = -sin(rad) * height / 2
        val k = amount / 100.0
        val src = PixelSource { x, y -> px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)] }
        return IntArray(px.size) { i ->
            val x = i % w; val y = i / w
            val p = src.bilinear(x + ox, y + oy, w, h); val m = src.bilinear(x - ox, y - oy, w, h)
            fun ch(sh: Int) = (128 + k * (((p shr sh) and 0xFF) - ((m shr sh) and 0xFF))).roundToInt().coerceIn(0, 255)
            Colors.argb(Colors.a(px[i]), ch(16), ch(8), ch(0))
        }
    }
}

/** Konturen finden (Sobel), als dunkle Linien auf Weiß wie in Photoshop. */
class FindEdges : ImageFilter {
    override val label = tr("Konturen finden")
    override val margin get() = 2
    override fun process(px: IntArray, w: Int, h: Int): IntArray = IntArray(px.size) { i ->
        val x = i % w; val y = i / w
        fun p(xx: Int, yy: Int, s: Int) = (px[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)] shr s) and 0xFF
        fun ch(s: Int): Int {
            val gx = -p(x - 1, y - 1, s) - 2 * p(x - 1, y, s) - p(x - 1, y + 1, s) + p(x + 1, y - 1, s) + 2 * p(x + 1, y, s) + p(x + 1, y + 1, s)
            val gy = -p(x - 1, y - 1, s) - 2 * p(x, y - 1, s) - p(x + 1, y - 1, s) + p(x - 1, y + 1, s) + 2 * p(x, y + 1, s) + p(x + 1, y + 1, s)
            // Wie Photoshop (nachgemessen): Sobel-Betrag ohne Skalierung
            return 255 - min(255, hypot(gx.toFloat(), gy.toFloat()).roundToInt())
        }
        Colors.argb(Colors.a(px[i]), ch(16), ch(8), ch(0))
    }
}

/** Mosaik: Zellen gleicher Farbe (Durchschnitt), ausgerichtet am Dokumentursprung. */
class Mosaic(private val cell: Int) : GeometryFilter {
    override val label = tr("Mosaik")
    override val margin get() = cell + 1
    override fun process(px: IntArray, w: Int, h: Int, offX: Int, offY: Int, docW: Int, docH: Int): IntArray {
        val c = max(2, cell)
        val out = IntArray(px.size)
        var cy0 = -Math.floorMod(offY, c)
        while (cy0 < h) {
            var cx0 = -Math.floorMod(offX, c)
            while (cx0 < w) {
                val acc = FloatArray(4); var n = 0
                for (y in max(0, cy0) until min(h, cy0 + c)) for (x in max(0, cx0) until min(w, cx0 + c)) {
                    val p = px[y * w + x]; val a = Colors.a(p) / 255f
                    acc[3] += a; acc[0] += Colors.r(p) * a; acc[1] += Colors.g(p) * a; acc[2] += Colors.b(p) * a; n++
                }
                val col = if (acc[3] <= 0f || n == 0) 0 else Colors.argb((acc[3] / n * 255).roundToInt(), (acc[0] / acc[3]).roundToInt(), (acc[1] / acc[3]).roundToInt(), (acc[2] / acc[3]).roundToInt())
                for (y in max(0, cy0) until min(h, cy0 + c)) for (x in max(0, cx0) until min(w, cx0 + c)) out[y * w + x] = col
                cx0 += c
            }
            cy0 += c
        }
        return out
    }
}

/** Ölfarbe: Kuwahara-Filter – flächige, malerische Pinselstruktur bei erhaltenen Kanten. */
class OilPaint(private val radius: Int) : ImageFilter {
    override val label = tr("Ölfarbe")
    override val margin get() = radius + 1
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val r = max(1, radius)
        return IntArray(px.size) { i ->
            val x = i % w; val y = i / w
            var best = Float.MAX_VALUE; var res = px[i]
            for ((qx, qy) in QUADRANTS) {
                var sr = 0f; var sg = 0f; var sb = 0f; var sl = 0f; var sl2 = 0f; var n = 0
                for (dy in 0..r) for (dx in 0..r) {
                    val c = px[(y + dy * qy).coerceIn(0, h - 1) * w + (x + dx * qx).coerceIn(0, w - 1)]
                    val l = lumOf(c)
                    sr += Colors.r(c); sg += Colors.g(c); sb += Colors.b(c); sl += l; sl2 += l * l; n++
                }
                val variance = sl2 / n - (sl / n) * (sl / n)
                if (variance < best) { best = variance; res = Colors.argb(Colors.a(px[i]), (sr / n).roundToInt(), (sg / n).roundToInt(), (sb / n).roundToInt()) }
            }
            res
        }
    }

    private companion object { val QUADRANTS = arrayOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1) }
}

/** Strudel: Drehung, die zur Mitte hin zunimmt. */
/**
 * Strudel wie Photoshop (in Photoshop 2026 nachgemessen): Wirkbereich = Ellipse über das ganze Bild
 * (Halbachsen = halbe Breite/Höhe), Drehung in diesen normierten Koordinaten um −Winkel·(1−ρ)²;
 * positive Winkel drehen im Uhrzeigersinn.
 */
class Twirl(private val angle: Float) : RandomAccessFilter {
    override val label = tr("Strudel")
    override fun processBand(src: PixelSource, band: IntRect, docW: Int, docH: Int): IntArray {
        // Mittelpunkt auf Pixelindex Breite/2, Höhe/2; Halbachsen ein halbes Pixel größer (auf 0,1 px gemessen)
        val cx = docW / 2.0; val cy = docH / 2.0
        val hw = cx + 0.5; val hh = cy + 0.5
        val rad = Math.toRadians(angle.toDouble())
        val w = band.width
        return IntArray(w * band.height) { i ->
            val px = band.left + i % w; val py = band.top + i / w
            val u = (px - cx) / hw; val v = (py - cy) / hh
            val rho = kotlin.math.sqrt(u * u + v * v)
            if (rho >= 1.0) src.get(px, py) else {
                val a = -rad * (1 - rho) * (1 - rho)
                val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
                val su = u * ca - v * sa; val sv = u * sa + v * ca
                src.bilinear(cx + su * hw, cy + sv * hh, docW, docH)
            }
        }
    }
}

// ---------------------------------------------------------------- Fotografische Filter

/** Vignette: Rand abdunkeln (negativ) oder aufhellen (positiv); Mittelpunkt 0..100 %. */
class Vignette(private val amount: Float, private val midpoint: Float) : GeometryFilter {
    override val label = tr("Vignette")
    override fun process(px: IntArray, w: Int, h: Int, offX: Int, offY: Int, docW: Int, docH: Int): IntArray {
        val cx = docW / 2f; val cy = docH / 2f
        val norm = hypot(cx, cy)
        val start = midpoint / 100f * 0.8f
        val k = amount / 100f
        return IntArray(px.size) { i ->
            val x = i % w + offX; val y = i / w + offY
            val d = hypot(x - cx, y - cy) / norm
            val t = ((d - start) / (1f - start)).coerceIn(0f, 1f)
            val f = t * t * (3 - 2 * t) * k
            val c = px[i]
            fun ch(v: Int) = if (f < 0) v * (1 + f) else v + (255 - v) * f
            Colors.argb(Colors.a(c), ch(Colors.r(c)).roundToInt(), ch(Colors.g(c)).roundToInt(), ch(Colors.b(c)).roundToInt())
        }
    }
}

/** Objektivkorrektur: tonnen- (negativ) bzw. kissenförmige (positiv) Verzeichnung entfernen. */
class LensDistortion(private val amount: Float) : RandomAccessFilter {
    override val label = tr("Objektivkorrektur")
    override fun processBand(src: PixelSource, band: IntRect, docW: Int, docH: Int): IntArray {
        val cx = docW / 2.0; val cy = docH / 2.0
        val norm = hypot(docW / 2.0, docH / 2.0)
        val k = amount / 100.0 * 0.5
        val w = band.width
        return IntArray(w * band.height) { i ->
            val x = band.left + i % w + 0.5; val y = band.top + i / w + 0.5
            val nx = (x - cx) / norm; val ny = (y - cy) / norm
            val f = 1 + k * (nx * nx + ny * ny)
            src.bilinear(cx + nx * f * norm - 0.5, cy + ny * f * norm - 0.5, docW, docH)
        }
    }
}

/** Klarheit: lokaler Kontrast in den Mitteltönen (Unscharf maskieren mit großem Radius). */
class Clarity(private val amount: Float) : ImageFilter {
    override val label = tr("Klarheit")
    override val margin get() = 60
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val l = lumaPlane(px)
        val bl = l.copyOf(); Blur.gaussPlane(bl, w, h, 18f)
        val k = amount / 100f
        return IntArray(px.size) { i ->
            val c = px[i]
            val lum = l[i] / 255f
            val mid = 1f - abs(lum - 0.5f) * 2f // Mitteltöne gewichten
            val d = (l[i] - bl[i]) * k * mid * 1.2f
            Colors.argb(Colors.a(c), (Colors.r(c) + d).roundToInt(), (Colors.g(c) + d).roundToInt(), (Colors.b(c) + d).roundToInt())
        }
    }
}

/** Dunst entfernen (Dark Channel Prior, vereinfacht). */
class Dehaze(private val amount: Float) : PreparedFilter {
    override val label = tr("Dunst entfernen")
    override val margin get() = 20
    /** Farbe des atmosphärischen Lichts; vorab über Stichproben aus dem ganzen Bild bestimmt. */
    private var air: FloatArray? = null

    override fun prepare(src: PixelSource, docW: Int, docH: Int) {
        // ~250 000 Stichproben genügen für die hellsten 0,1 % des dunklen Kanals
        val step = maxOf(1, kotlin.math.sqrt(docW.toDouble() * docH / 250_000).toInt())
        val cols = (docW + step - 1) / step; val rows = (docH + step - 1) / step
        val dark = FloatArray(cols * rows); val col = IntArray(cols * rows)
        for (j in 0 until rows) for (i in 0 until cols) {
            val c = src.get(minOf(docW - 1, i * step), minOf(docH - 1, j * step))
            col[j * cols + i] = c
            dark[j * cols + i] = min(Colors.r(c), min(Colors.g(c), Colors.b(c))) / 255f
        }
        air = airLight(dark, col)
    }

    private fun airLight(dark: FloatArray, px: IntArray): FloatArray {
        val n = dark.size
        val level = dark.copyOf().also { it.sort() }[(n * 0.999).toInt().coerceIn(0, n - 1)]
        var ar = 0f; var ag = 0f; var ab = 0f; var cnt = 0
        for (i in 0 until n) if (dark[i] >= level) { ar += Colors.r(px[i]); ag += Colors.g(px[i]); ab += Colors.b(px[i]); cnt++ }
        return floatArrayOf(ar / max(1, cnt), ag / max(1, cnt), ab / max(1, cnt))
    }

    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val n = px.size
        val dark = FloatArray(n) { val c = px[it]; min(Colors.r(c), min(Colors.g(c), Colors.b(c))) / 255f }
        // Minimum-Filter grob über eine Weichzeichnung des dunklen Kanals annähern
        val dk = dark.copyOf(); Blur.gaussPlane(dk, w, h, 6f)
        val air = air ?: airLight(dk, px)
        val k = amount / 100f
        return IntArray(n) { i ->
            val c = px[i]
            val t = max(0.15f, 1f - 0.9f * k * dk[i])
            fun ch(v: Int, a: Float) = ((v - a) / t + a)
            Colors.argb(Colors.a(c), ch(Colors.r(c), air[0]).roundToInt(), ch(Colors.g(c), air[1]).roundToInt(), ch(Colors.b(c), air[2]).roundToInt())
        }
    }
}

/**
 * Haut glätten: Frequenztrennung – grobe Hauttöne werden kantenerhaltend geglättet,
 * feine Struktur (Poren) bleibt mit [detail] % erhalten.
 */
class SkinSmooth(private val strength: Float, private val detail: Float) : ImageFilter {
    override val label = tr("Haut glätten")
    override val margin get() = 40
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val sigma = 2f + strength / 100f * 10f
        val low = Blur.gaussArgb(px, w, h, sigma)
        val smooth = SurfaceBlur((sigma * 1.5f).toInt(), 18 + (strength / 4).toInt()).process(px, w, h)
        val fine = Blur.gaussArgb(px, w, h, 1.2f)
        val d = detail / 100f
        val k = strength / 100f
        return IntArray(px.size) { i ->
            val o = px[i]; val s = smooth[i]; val lo = low[i]; val fi = fine[i]
            fun ch(sh: Int): Int {
                val ov = (o shr sh) and 0xFF; val sv = (s shr sh) and 0xFF; val fv = (fi shr sh) and 0xFF; val lv = (lo shr sh) and 0xFF
                val texture = (ov - fv) * d // feinste Poren behalten
                val v = sv + texture + (fv - lv) * 0.15f * d
                return (ov + (v - ov) * k).roundToInt()
            }
            Colors.argb(Colors.a(o), ch(16), ch(8), ch(0))
        }
    }
}

/** Schärfe abhängig vom Rauschen: Unscharf maskieren nur oberhalb eines Kantenschwellwerts (schonend). */
class SmartSharpen(private val amount: Float, private val radius: Float, private val noiseReduction: Float) : ImageFilter {
    override val label = tr("Selektiver Scharfzeichner")
    override val margin get() = (radius * 3).toInt() + 2
    override fun process(px: IntArray, w: Int, h: Int): IntArray {
        val bl = Blur.gaussArgb(px, w, h, radius)
        val k = amount / 100f
        val nr = noiseReduction / 100f * 12f
        return IntArray(px.size) { i ->
            val o = px[i]; val b = bl[i]
            fun ch(s: Int): Int {
                val ov = (o shr s) and 0xFF; val d = ov - ((b shr s) and 0xFF)
                val g = if (abs(d) < nr) d * (abs(d) / max(nr, 1f)) else d.toFloat()
                return (ov + g * k).roundToInt()
            }
            Colors.argb(Colors.a(o), ch(16), ch(8), ch(0))
        }
    }
}

