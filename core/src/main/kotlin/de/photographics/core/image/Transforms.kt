package de.photographics.core.image

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Geometrische Operationen auf ganzen Rastern. */
object Transforms {
    /** Zu bearbeitender Bereich: Arbeitsfläche plus Inhalt außerhalb (falls vorhanden). */
    private fun area(g: TileGrid): IntRect =
        if (g.extendsBeyondCanvas) g.contentBounds(alphaOnly = false).union(IntRect.ofSize(g.width, g.height)) else IntRect.ofSize(g.width, g.height)

    fun flipHorizontal(g: TileGrid): TileGrid {
        val w = g.width; val h = g.height
        val a = area(g)
        val na = IntRect(w - a.right, a.top, w - a.left, a.bottom)
        val src = IntArray(a.width); val dst = IntArray(a.width)
        val out = TileGrid(w, h, g.defaultValue, area = na)
        for (y in a.top until a.bottom) {
            g.readRect(IntRect(a.left, y, a.right, y + 1), src)
            for (i in dst.indices) dst[i] = src[a.width - 1 - i]
            out.writeRect(IntRect(na.left, y, na.right, y + 1), dst)
        }
        out.compact()
        return out
    }

    fun flipVertical(g: TileGrid): TileGrid {
        val w = g.width; val h = g.height
        val a = area(g)
        val na = IntRect(a.left, h - a.bottom, a.right, h - a.top)
        val row = IntArray(a.width)
        val out = TileGrid(w, h, g.defaultValue, area = na)
        for (y in a.top until a.bottom) {
            g.readRect(IntRect(a.left, y, a.right, y + 1), row)
            out.writeRect(IntRect(a.left, h - 1 - y, a.right, h - y), row)
        }
        out.compact()
        return out
    }

    /** Dreht um 90° im ([clockwise]) oder gegen den Uhrzeigersinn (um die Arbeitsfläche). */
    fun rotate90(g: TileGrid, clockwise: Boolean): TileGrid {
        val w = g.width; val h = g.height
        val a = area(g)
        // (x, y) → (h−1−y, x) im Uhrzeigersinn, (y, w−1−x) dagegen
        val na = if (clockwise) IntRect(h - a.bottom, a.left, h - a.top, a.right) else IntRect(a.top, w - a.right, a.bottom, w - a.left)
        val out = TileGrid(h, w, g.defaultValue, area = na)
        val row = IntArray(a.width)
        for (y in a.top until a.bottom) {
            g.readRect(IntRect(a.left, y, a.right, y + 1), row)
            for (i in row.indices) {
                val v = row[i]
                if (v == g.defaultValue) continue
                val x = a.left + i
                if (clockwise) out[h - 1 - y, x] = v else out[y, w - 1 - x] = v
            }
        }
        out.compact()
        return out
    }

    /**
     * Skaliert ein Raster. Beim Verkleinern wird über die Quellpixel gemittelt (Flächenmittel),
     * beim Vergrößern bilinear interpoliert. [argb] = Farbpixel (vormultipliziert gemittelt).
     */
    fun resize(g: TileGrid, newW: Int, newH: Int, argb: Boolean): TileGrid {
        val w = g.width; val h = g.height
        val src = IntArray(w * h)
        g.readRect(IntRect.ofSize(w, h), src)
        val out = IntArray(newW * newH)
        val sx = w.toFloat() / newW; val sy = h.toFloat() / newH
        val acc = FloatArray(4)
        for (y in 0 until newH) for (x in 0 until newW) {
            acc.fill(0f)
            var wsum = 0f
            if (sx > 1f || sy > 1f) {
                val x0 = x * sx; val x1 = (x + 1) * sx; val y0 = y * sy; val y1 = (y + 1) * sy
                for (yy in floor(y0).toInt() until min(h, kotlin.math.ceil(y1).toInt())) {
                    val wy = min(y1, yy + 1f) - max(y0, yy.toFloat())
                    for (xx in floor(x0).toInt() until min(w, kotlin.math.ceil(x1).toInt())) {
                        val wx = min(x1, xx + 1f) - max(x0, xx.toFloat())
                        add(acc, src[yy * w + xx], wx * wy, argb); wsum += wx * wy
                    }
                }
            } else {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val ix = floor(fx).toInt(); val iy = floor(fy).toInt()
                val ax = fx - ix; val ay = fy - iy
                val ix1 = min(w - 1, ix + 1); val iy1 = min(h - 1, iy + 1)
                add(acc, src[iy * w + ix], (1 - ax) * (1 - ay), argb)
                add(acc, src[iy * w + ix1], ax * (1 - ay), argb)
                add(acc, src[iy1 * w + ix], (1 - ax) * ay, argb)
                add(acc, src[iy1 * w + ix1], ax * ay, argb)
                wsum = 1f
            }
            out[y * newW + x] = finish(acc, wsum, argb)
        }
        return TileGrid.fromPixels(newW, newH, out, g.defaultValue)
    }

    private fun add(acc: FloatArray, c: Int, k: Float, argb: Boolean) {
        if (!argb) { acc[0] += c * k; return }
        val a = (c ushr 24) * k
        acc[3] += a
        acc[0] += ((c shr 16) and 0xFF) * a
        acc[1] += ((c shr 8) and 0xFF) * a
        acc[2] += (c and 0xFF) * a
    }

    private fun finish(acc: FloatArray, wsum: Float, argb: Boolean): Int {
        if (wsum <= 0f) return 0
        if (!argb) return (acc[0] / wsum).toInt().coerceIn(0, 255)
        val a = acc[3]
        if (a <= 0f) return 0
        return Colors.argb((a / wsum + 0.5f).toInt(), (acc[0] / a + 0.5f).toInt(), (acc[1] / a + 0.5f).toInt(), (acc[2] / a + 0.5f).toInt())
    }
}
