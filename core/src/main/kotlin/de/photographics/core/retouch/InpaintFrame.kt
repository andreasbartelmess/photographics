package de.photographics.core.retouch

import de.photographics.core.image.Resample
import de.photographics.core.image.ResampleMethod
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bereitet einen Ausschnitt für ein Inpainting-Modell vor und rechnet das Ergebnis zurück – ohne
 * das Bild zu verzerren:
 *
 * - Modelle mit fester Eingangsgröße ([fixed], z. B. LaMa 512²): der Ausschnitt wird spiegelnd zum
 *   Quadrat ergänzt und dann skaliert.
 * - Modelle mit freier Eingangsgröße: der Ausschnitt wird nur verkleinert, wenn seine längste Seite
 *   [maxSide] überschreitet, und spiegelnd auf ein Vielfaches von [multiple] ergänzt.
 *
 * [image] und [mask] (true = Lücke) haben die Größe [tensorW] × [tensorH]; der Bildinhalt liegt oben
 * links in [contentW] × [contentH].
 */
class InpaintFrame private constructor(
    val srcW: Int, val srcH: Int,
    val tensorW: Int, val tensorH: Int,
    val contentW: Int, val contentH: Int,
    val image: IntArray, val mask: BooleanArray,
) {
    /** Modellausgabe ([tensorW] × [tensorH], ARGB) auf die Ausgangsgröße zurückbringen. */
    fun back(out: IntArray): IntArray {
        require(out.size == tensorW * tensorH)
        val content = if (contentW == tensorW && contentH == tensorH) out
            else IntArray(contentW * contentH).also { c -> for (y in 0 until contentH) System.arraycopy(out, y * tensorW, c, y * contentW, contentW) }
        return if (contentW == srcW && contentH == srcH) content
            else Resample.resizeArgb(content, contentW, contentH, srcW, srcH, ResampleMethod.BICUBIC)
    }

    companion object {
        /** Lücke in Arbeitsgröße um so viele Pixel erweitern, damit keine Kanten des Objekts übrig bleiben. */
        const val GROW = 2

        fun create(px: IntArray, w: Int, h: Int, hole: BooleanArray, fixed: Int? = null, maxSide: Int = 1024, multiple: Int = 8): InpaintFrame {
            require(px.size == w * h && hole.size == w * h)
            if (fixed != null) {
                // Spiegelnd zum Quadrat ergänzen, dann auf die feste Größe bringen
                val side = max(w, h)
                val sq = padReflect(px, w, h, side, side)
                val sqHole = BooleanArray(side * side).also { m -> for (y in 0 until h) System.arraycopy(hole, y * w, m, y * side, w) }
                val img = if (side == fixed) sq else Resample.resizeArgb(sq, side, side, fixed, fixed, ResampleMethod.BILINEAR)
                val mask = grow(scaleMask(sqHole, side, side, fixed, fixed), fixed, fixed, GROW)
                val cw = (w.toDouble() * fixed / side).roundToInt().coerceIn(1, fixed)
                val ch = (h.toDouble() * fixed / side).roundToInt().coerceIn(1, fixed)
                return InpaintFrame(w, h, fixed, fixed, cw, ch, img, mask)
            }
            val f = minOf(1.0, maxSide.toDouble() / max(w, h))
            val cw = max(1, (w * f).roundToInt()); val ch = max(1, (h * f).roundToInt())
            val small = if (cw == w && ch == h) px else Resample.resizeArgb(px, w, h, cw, ch, ResampleMethod.BILINEAR)
            val smallHole = if (cw == w && ch == h) hole else scaleMask(hole, w, h, cw, ch)
            val tw = roundUp(max(cw, multiple), multiple); val th = roundUp(max(ch, multiple), multiple)
            val img = padReflect(small, cw, ch, tw, th)
            val m = BooleanArray(tw * th).also { m -> for (y in 0 until ch) System.arraycopy(smallHole, y * cw, m, y * tw, cw) }
            return InpaintFrame(w, h, tw, th, cw, ch, img, grow(m, tw, th, GROW))
        }

        private fun roundUp(v: Int, m: Int) = (v + m - 1) / m * m

        /** Spiegelnd (ohne Randwiederholung) auf [nw] × [nh] ergänzen; der Inhalt bleibt oben links. */
        fun padReflect(px: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
            if (nw == w && nh == h) return px
            fun mirror(i: Int, n: Int): Int {
                if (n == 1) return 0
                val p = 2 * n - 2
                val k = ((i % p) + p) % p
                return if (k < n) k else p - k
            }
            val out = IntArray(nw * nh)
            for (y in 0 until nh) {
                val sy = mirror(y, h) * w
                for (x in 0 until nw) out[y * nw + x] = px[sy + mirror(x, w)]
            }
            return out
        }

        /** Maske skalieren: ein Zielpixel ist Lücke, wenn irgendein Quellpixel darunter Lücke ist. */
        fun scaleMask(m: BooleanArray, w: Int, h: Int, nw: Int, nh: Int): BooleanArray {
            val out = BooleanArray(nw * nh)
            for (y in 0 until h) {
                val ty0 = (y.toLong() * nh / h).toInt(); val ty1 = (((y + 1).toLong() * nh + h - 1) / h).toInt().coerceAtMost(nh)
                for (x in 0 until w) {
                    if (!m[y * w + x]) continue
                    val tx0 = (x.toLong() * nw / w).toInt(); val tx1 = (((x + 1).toLong() * nw + w - 1) / w).toInt().coerceAtMost(nw)
                    for (ty in ty0 until max(ty1, ty0 + 1)) for (tx in tx0 until max(tx1, tx0 + 1)) out[ty * nw + tx] = true
                }
            }
            return out
        }

        fun grow(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
            if (r <= 0) return m
            val out = BooleanArray(m.size)
            for (y in 0 until h) for (x in 0 until w) {
                if (!m[y * w + x]) continue
                for (yy in max(0, y - r)..minOf(h - 1, y + r)) for (xx in max(0, x - r)..minOf(w - 1, x + r)) out[yy * w + xx] = true
            }
            return out
        }
    }
}
