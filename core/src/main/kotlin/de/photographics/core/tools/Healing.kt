package de.photographics.core.tools

import de.photographics.core.tr
import de.photographics.core.retouch.Inpainter
import de.photographics.core.retouch.PatchMatchInpainter
import de.photographics.core.retouch.Poisson
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reparatur-Pinsel: während des Strichs wie der Kopierstempel; am Strichende wird die Quelle
 * per Poisson-Blending eingesetzt – Struktur von der Quelle, Farbe und Helligkeit nahtlos
 * aus der Umgebung des Ziels.
 */
class HealTool(private val dx: Int, private val dy: Int, private val sampleAll: Boolean) : PaintTool() {
    override val label = tr("Reparatur-Pinsel")
    private lateinit var source: SourceSampler

    override fun begin(ctx: StrokeContext) { source = SourceSampler(ctx, sampleAll) }

    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int {
        val s = source.sample(x + dx, y + dy)
        return Colors.srcOver(orig, Colors.withAlpha(s, (Colors.a(s) * amount).roundToInt()))
    }

    override fun finish(ctx: StrokeContext): IntRect {
        val rect = ctx.touched
        if (rect.isEmpty) return IntRect.EMPTY
        val r = rect.inset(-3).intersect(ctx.doc.bounds)
        val n = r.width * r.height
        val s = IntArray(n); source.read(r.offset(dx, dy), s)
        val t = IntArray(n)
        if (sampleAll) source.read(r, t) else ctx.tx.readOriginal(r, t)
        val inside = BooleanArray(n)
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            if (ctx.coverage[x, y] > 0) inside[(y - r.top) * r.width + (x - r.left)] = true
        }
        val healed = Poisson.blend(t, s, inside, r.width, r.height)
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val cov = ctx.coverage[x, y]
            if (cov == 0) continue
            val i = (y - r.top) * r.width + (x - r.left)
            val amount = cov / 65535f * ctx.settings.opacity * ctx.selectionFactor(x, y)
            val c = healed[i]
            ctx.grid[x, y] = Colors.srcOver(ctx.original(x, y), Colors.withAlpha(c, (Colors.a(c) * amount).roundToInt()))
        }
        return rect
    }
}

/**
 * Bereichsreparatur: übermalte Stellen werden aus der Umgebung neu aufgebaut – standardmäßig
 * per PatchMatch (inhaltsbasiert), optional über ein lokales KI-Modell ([inpainter]).
 */
class SpotHealTool(
    private val sampleAll: Boolean,
    private val inpainter: Inpainter = PatchMatchInpainter(),
    override val label: String = tr("Bereichsreparatur"),
) : PaintTool() {
    private lateinit var source: SourceSampler

    override fun begin(ctx: StrokeContext) { source = SourceSampler(ctx, sampleAll) }

    /** Während des Strichs bleibt das Bild unverändert; die Oberfläche zeigt die Pinselspur an. */
    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int = orig

    override fun finish(ctx: StrokeContext): IntRect {
        val rect = ctx.touched
        if (rect.isEmpty) return IntRect.EMPTY
        val res = Retouch.fillHole(ctx.doc.bounds, rect, { x, y -> ctx.coverage[x, y] > 0 },
            { r, out -> if (sampleAll) source.read(r, out) else ctx.tx.readOriginal(r, out) }, inpainter)
            ?: return IntRect.EMPTY
        val (r, filled, soft) = res
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val i = (y - r.top) * r.width + (x - r.left)
            val k = soft[i] * ctx.settings.opacity * ctx.selectionFactor(x, y)
            if (k <= 0f) continue
            val c = filled[i]
            ctx.grid[x, y] = Colors.srcOver(ctx.original(x, y), Colors.withAlpha(c, (Colors.a(c) * k).roundToInt()))
        }
        return rect
    }
}

/**
 * Wischfinger: nimmt Farbe unter dem Pinsel auf und zieht sie in Strichrichtung mit
 * (arbeitet auf dem aktuellen Zustand, nicht auf dem Original).
 */
class SmudgeTool(private val strength: Float = 0.6f) : PaintTool() {
    override val label = tr("Wischfinger")
    override val customDab = true
    private var carry: IntArray? = null
    private var carrySize = 0

    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int = orig

    override fun dab(ctx: StrokeContext, cx: Float, cy: Float, radius: Float, shape: (Float) -> Float) {
        val size = (radius * 2).toInt() + 2
        val half = size / 2
        val ox = cx.toInt() - half; val oy = cy.toInt() - half
        val cur = carry
        if (cur == null || carrySize != size) {
            // Erster Abdruck: aktuelle Farben aufnehmen
            carry = IntArray(size * size) { i -> ctx.grid[ox + i % size, oy + i / size] }
            carrySize = size
            return
        }
        for (j in 0 until size) for (i in 0 until size) {
            val x = ox + i; val y = oy + j
            if (x < 0 || y < 0 || x >= ctx.grid.width || y >= ctx.grid.height) continue
            val d = kotlin.math.hypot(i - half + 0.5f, j - half + 0.5f) / radius
            val k = shape(d) * strength * ctx.settings.opacity * ctx.selectionFactor(x, y)
            if (k <= 0f) continue
            val under = ctx.grid[x, y]
            val c = cur[j * size + i]
            val mixed = Colors.lerp(under, c, k)
            ctx.grid[x, y] = mixed
            // Mitgenommene Farbe nimmt etwas vom Untergrund an
            cur[j * size + i] = Colors.lerp(c, under, 0.12f)
        }
    }
}

/** Gemeinsame Hilfen für Füll- und Reparaturverfahren. */
object Retouch {
    /** Größter Ausschnitt (Lücke plus Umgebung) in Pixeln. */
    private const val MAX_REGION = 3_000_000L

    /**
     * Füllt die Lücke [isHole] innerhalb von [holeRect] aus der Umgebung.
     * [read] liefert die Originalpixel eines Rechtecks. Ergebnis: (Ausschnitt, gefüllte Pixel,
     * weiche Deckung 0..1 je Pixel) oder null.
     */
    fun fillHole(
        bounds: IntRect, holeRect: IntRect, isHole: (Int, Int) -> Boolean,
        read: (IntRect, IntArray) -> Unit, inpainter: Inpainter,
    ): Triple<IntRect, IntArray, FloatArray>? {
        // Umgebung: drei Viertel der Lückengröße (mind. 64, höchstens 480 px) – genug Quellmaterial, aber kompakt
        var margin = (max(holeRect.width, holeRect.height) * 3 / 4).coerceIn(64, 480)
        // Sehr große Lücken: Umgebung begrenzen, sonst sprengen die Arbeitsfelder den Speicher
        while (margin > 64 && holeRect.inset(-margin).intersect(bounds).let { it.width.toLong() * it.height } > MAX_REGION) margin = margin * 3 / 4
        val r = holeRect.inset(-margin).intersect(bounds)
        val w = r.width; val h = r.height; val n = w * h
        if (n == 0) return null
        val base = IntArray(n); read(r, base)
        val hole = BooleanArray(n)
        for (y in holeRect.top until holeRect.bottom) for (x in holeRect.left until holeRect.right) {
            if (isHole(x, y)) hole[(y - r.top) * w + (x - r.left)] = true
        }
        val grown = dilate(hole, w, h, 2)
        val maxArea = inpainter.maxPixels
        val filled = if (n <= maxArea) inpainter.inpaint(base, w, h, grown) else {
            // Großer Bereich: verkleinert füllen und die Lücke hochskaliert einsetzen
            val f = kotlin.math.sqrt(maxArea.toDouble() / n)
            val sw = max(8, (w * f).toInt()); val sh = max(8, (h * f).toInt())
            val small = de.photographics.core.image.Resample.resizeArgb(base, w, h, sw, sh, de.photographics.core.image.ResampleMethod.BILINEAR)
            val smallHole = BooleanArray(sw * sh) { i -> grown[min(h - 1, (i / sw * h) / sh) * w + min(w - 1, (i % sw * w) / sw)] }
            val sf = inpainter.inpaint(small, sw, sh, smallHole)
            val up = de.photographics.core.image.Resample.resizeArgb(sf, sw, sh, w, h, de.photographics.core.image.ResampleMethod.BICUBIC)
            for (i in 0 until n) if (grown[i]) base[i] = up[i]
            base
        }
        val soft = FloatArray(n) { if (grown[it]) 1f else 0f }
        de.photographics.core.image.Blur.gaussPlane(soft, w, h, 1.2f)
        for (i in 0 until n) if (hole[i]) soft[i] = 1f
        return Triple(r, filled, soft)
    }

    fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (!m[y * w + x]) continue
            for (yy in max(0, y - r)..min(h - 1, y + r)) for (xx in max(0, x - r)..min(w - 1, x + r)) out[yy * w + xx] = true
        }
        return out
    }
}

/** Pull-Push-Interpolation: füllt Pixel mit Gewicht < 1 aus einer Bildpyramide der bekannten Pixel. */
object PullPush {
    /** [c]: 4 Kanäle je Pixel (0..1), [wt]: Gewicht 0..1 (1 = bekannt). Arbeitet in-place. */
    fun fill(c: FloatArray, wt: FloatArray, w: Int, h: Int) {
        if (w <= 1 && h <= 1) return
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        val cc = FloatArray(cw * ch * 4)
        val cwt = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            var ws = 0f; var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
            for (yy in 2 * y until min(h, 2 * y + 2)) for (xx in 2 * x until min(w, 2 * x + 2)) {
                val i = yy * w + xx
                val k = wt[i]
                ws += k; s0 += c[i * 4] * k; s1 += c[i * 4 + 1] * k; s2 += c[i * 4 + 2] * k; s3 += c[i * 4 + 3] * k
            }
            val o = y * cw + x
            cwt[o] = min(1f, ws)
            if (ws > 0f) { cc[o * 4] = s0 / ws; cc[o * 4 + 1] = s1 / ws; cc[o * 4 + 2] = s2 / ws; cc[o * 4 + 3] = s3 / ws }
        }
        fill(cc, cwt, cw, ch)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val k = wt[i]
            if (k >= 1f) continue
            // bilineare Abtastung der gröberen Stufe
            val fx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (cw - 1).toFloat())
            val fy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (ch - 1).toFloat())
            val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
            val x1 = min(cw - 1, x0 + 1); val y1 = min(ch - 1, y0 + 1)
            val ax = fx - x0; val ay = fy - y0
            for (k4 in 0..3) {
                val v = (cc[(y0 * cw + x0) * 4 + k4] * (1 - ax) + cc[(y0 * cw + x1) * 4 + k4] * ax) * (1 - ay) +
                    (cc[(y1 * cw + x0) * 4 + k4] * (1 - ax) + cc[(y1 * cw + x1) * 4 + k4] * ax) * ay
                c[i * 4 + k4] = c[i * 4 + k4] * k + v * (1f - k)
            }
            wt[i] = 1f
        }
    }
}
