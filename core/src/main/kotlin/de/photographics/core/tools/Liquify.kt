package de.photographics.core.tools

import de.photographics.core.image.Colors
import de.photographics.core.tr
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Modi des Verflüssigen-Werkzeugs (wie in Photoshops Verflüssigen-Filter). */
enum class LiquifyMode(val label: String) {
    PUSH(tr("Vorwärts")), PUCKER(tr("Zusammenziehen")), BLOAT(tr("Aufblasen")), TWIRL_CW(tr("Strudel rechts")), TWIRL_CCW(tr("Strudel links"))
}

/**
 * Verflüssigen als Pinselwerkzeug. Die Abdrücke verändern ein Netz aus Abtast-Versätzen (Quelle =
 * Pixel + Versatz); jedes Pixel im Abdruck wird danach aus dem Zustand **vor dem Strich** neu
 * abgetastet. So wird das Bild auch nach vielen Abdrücken nur einmal interpoliert und bleibt scharf.
 */
class LiquifyTool(private val mode: LiquifyMode, private val strength: Float) : PaintTool() {
    override val label = tr("Verflüssigen")
    override val customDab = true

    /** Abstand der Netzknoten (px); feiner bei kleinen Pinseln. */
    private var sp = 4
    /** Netz blockweise (B×B Knoten, je x/y-Versatz): Speicher nur dort, wo gemalt wurde. */
    private val blocks = HashMap<Long, FloatArray>()
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    override fun begin(ctx: StrokeContext) {
        sp = (ctx.settings.size / 24f).toInt().coerceIn(2, 12)
    }

    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int = orig

    private fun key(bi: Int, bj: Int) = (bi.toLong() shl 32) or (bj.toLong() and 0xFFFFFFFFL)

    /** Versatz am Knoten (i, j) als (x, y). */
    private fun node(i: Int, j: Int, out: FloatArray) {
        val b = blocks[key(Math.floorDiv(i, B), Math.floorDiv(j, B))]
        if (b == null) { out[0] = 0f; out[1] = 0f; return }
        val o = (Math.floorMod(j, B) * B + Math.floorMod(i, B)) * 2
        out[0] = b[o]; out[1] = b[o + 1]
    }

    private fun setNode(i: Int, j: Int, x: Float, y: Float) {
        val b = blocks.getOrPut(key(Math.floorDiv(i, B), Math.floorDiv(j, B))) { FloatArray(B * B * 2) }
        val o = (Math.floorMod(j, B) * B + Math.floorMod(i, B)) * 2
        b[o] = x; b[o + 1] = y
    }

    private val t00 = FloatArray(2); private val t10 = FloatArray(2); private val t01 = FloatArray(2); private val t11 = FloatArray(2)

    /** Versatz an einer beliebigen Stelle (bilinear zwischen den Knoten). */
    private fun offsetAt(x: Float, y: Float, out: FloatArray) {
        val gx = x / sp; val gy = y / sp
        val i = floor(gx).toInt(); val j = floor(gy).toInt()
        val fx = gx - i; val fy = gy - j
        node(i, j, t00); node(i + 1, j, t10); node(i, j + 1, t01); node(i + 1, j + 1, t11)
        for (c in 0..1) {
            val top = t00[c] + (t10[c] - t00[c]) * fx
            val bot = t01[c] + (t11[c] - t01[c]) * fx
            out[c] = top + (bot - top) * fy
        }
    }

    override fun dab(ctx: StrokeContext, cx: Float, cy: Float, radius: Float, shape: (Float) -> Float) {
        if (radius < 1f) return
        val dx = if (lastX.isNaN()) 0f else (cx - lastX).coerceIn(-radius, radius)
        val dy = if (lastY.isNaN()) 0f else (cy - lastY).coerceIn(-radius, radius)
        lastX = cx; lastY = cy
        if (mode == LiquifyMode.PUSH && dx == 0f && dy == 0f) return
        val k0 = strength.coerceIn(0f, 1f) * ctx.settings.opacity

        // 1) Netz im Abdruck fortschreiben: S_neu(p) = S_alt(p + v) + v (Versatz wandert mit)
        val i0 = floor((cx - radius) / sp).toInt(); val i1 = floor((cx + radius) / sp).toInt() + 1
        val j0 = floor((cy - radius) / sp).toInt(); val j1 = floor((cy + radius) / sp).toInt() + 1
        val nw = i1 - i0 + 1; val nh = j1 - j0 + 1
        val upd = FloatArray(nw * nh * 2); val has = BooleanArray(nw * nh)
        val tmp = FloatArray(2)
        for (j in j0..j1) for (i in i0..i1) {
            val px = (i * sp).toFloat(); val py = (j * sp).toFloat()
            val rx = px - cx; val ry = py - cy
            val d = hypot(rx, ry) / radius
            if (d >= 1f) continue
            val k = shape(d) * k0
            if (k <= 0f) continue
            var vx: Float; var vy: Float
            when (mode) {
                LiquifyMode.PUSH -> { vx = -dx * k; vy = -dy * k }
                LiquifyMode.PUCKER -> { vx = rx * k * 0.1f; vy = ry * k * 0.1f }
                LiquifyMode.BLOAT -> { vx = -rx * k * 0.1f; vy = -ry * k * 0.1f }
                LiquifyMode.TWIRL_CW, LiquifyMode.TWIRL_CCW -> {
                    val a = (if (mode == LiquifyMode.TWIRL_CW) -0.08f else 0.08f) * k
                    val c = cos(a); val s = sin(a)
                    vx = rx * c - ry * s - rx; vy = rx * s + ry * c - ry
                }
            }
            offsetAt(px + vx, py + vy, tmp)
            val o = ((j - j0) * nw + (i - i0))
            upd[o * 2] = tmp[0] + vx; upd[o * 2 + 1] = tmp[1] + vy; has[o] = true
        }
        for (j in j0..j1) for (i in i0..i1) {
            val o = (j - j0) * nw + (i - i0)
            if (has[o]) setNode(i, j, upd[o * 2], upd[o * 2 + 1])
        }

        // 2) Pixel im Abdruck aus dem Zustand vor dem Strich neu abtasten
        val g = ctx.grid
        // Genau das Rechteck, das der Strich für das Rückgängigmachen gesichert hat
        val x0 = maxOf(0, floor(cx - radius).toInt()); val x1 = minOf(g.width, kotlin.math.ceil(cx + radius).toInt() + 1)
        val y0 = maxOf(0, floor(cy - radius).toInt()); val y1 = minOf(g.height, kotlin.math.ceil(cy + radius).toInt() + 1)
        val off = FloatArray(2)
        for (y in y0 until y1) for (x in x0 until x1) {
            offsetAt(x.toFloat(), y.toFloat(), off)
            var c = if (off[0] == 0f && off[1] == 0f) ctx.original(x, y) else sample(ctx, x + off[0], y + off[1])
            val sel = ctx.selectionFactor(x, y)
            if (sel < 1f) c = Colors.lerp(ctx.original(x, y), c, sel)
            g[x, y] = c
        }
    }

    /** Bilinear aus dem Original, Farben nach Deckung gewichtet (keine dunklen Säume an Kanten). */
    private fun sample(ctx: StrokeContext, sx: Float, sy: Float): Int {
        val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
        val fx = sx - x0; val fy = sy - y0
        val c00 = ctx.original(x0, y0); val c10 = ctx.original(x0 + 1, y0)
        val c01 = ctx.original(x0, y0 + 1); val c11 = ctx.original(x0 + 1, y0 + 1)
        val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy); val w01 = (1 - fx) * fy; val w11 = fx * fy
        fun a(c: Int) = (c ushr 24).toFloat()
        val a = a(c00) * w00 + a(c10) * w10 + a(c01) * w01 + a(c11) * w11
        if (a < 0.5f) return 0
        fun ch(s: Int) = (((c00 shr s) and 0xFF) * a(c00) * w00 + ((c10 shr s) and 0xFF) * a(c10) * w10 +
            ((c01 shr s) and 0xFF) * a(c01) * w01 + ((c11 shr s) and 0xFF) * a(c11) * w11) / a
        return ((a + 0.5f).toInt().coerceAtMost(255) shl 24) or ((ch(16) + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((ch(8) + 0.5f).toInt().coerceIn(0, 255) shl 8) or (ch(0) + 0.5f).toInt().coerceIn(0, 255)
    }

    private companion object { const val B = 32 }
}
