package de.photographics.core.image

import de.photographics.core.tr
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Interpolationsverfahren für Größenänderungen (wie im Photoshop-Dialog „Bildgröße“). */
enum class ResampleMethod(val label: String) {
    NEAREST(tr("Pixelwiederholung (harte Kanten)")),
    BILINEAR(tr("Bilinear")),
    BICUBIC(tr("Bikubisch (glatter)")),
    LANCZOS(tr("Lanczos (schärfer)")),
}

/**
 * Hochwertige Neuberechnung von Rastern. Gerechnet wird vormultipliziert, damit an
 * transparenten Kanten keine dunklen Säume entstehen.
 */
object Resample {

    // ------------------------------------------------------------ Größenänderung (separabel)

    /** Skaliert ein ARGB-Pixelfeld auf [nw]×[nh]. */
    fun resizeArgb(src: IntArray, w: Int, h: Int, nw: Int, nh: Int, method: ResampleMethod): IntArray {
        if (method == ResampleMethod.NEAREST) {
            return IntArray(nw * nh) { i ->
                val x = ((i % nw + 0.5) * w / nw).toInt().coerceIn(0, w - 1)
                val y = ((i / nw + 0.5) * h / nh).toInt().coerceIn(0, h - 1)
                src[y * w + x]
            }
        }
        // Kanalweise rechnen, damit auch große Bilder in den Speicher passen
        val kx = weights(w, nw, method)
        val ky = weights(h, nh, method)
        val n = w * h
        val plane = FloatArray(n)
        val tmp = FloatArray(nw * h)
        for (i in 0 until n) plane[i] = (src[i] ushr 24) / 255f
        val outA = FloatArray(nw * nh)
        applyH(plane, tmp, w, h, nw, kx); applyV(tmp, outA, nw, h, nh, ky)
        val result = IntArray(nw * nh)
        for (i in result.indices) {
            val a = outA[i].coerceIn(0f, 1f)
            result[i] = (a * 255f).roundToInt() shl 24
        }
        val outC = FloatArray(nw * nh)
        for (shift in intArrayOf(16, 8, 0)) {
            for (i in 0 until n) { val c = src[i]; plane[i] = ((c shr shift) and 0xFF) * ((c ushr 24) / 255f) }
            applyH(plane, tmp, w, h, nw, kx); applyV(tmp, outC, nw, h, nh, ky)
            for (i in result.indices) {
                val a = outA[i]
                val v = if (a <= 1f / 512f) 0 else (outC[i] / a).roundToInt().coerceIn(0, 255)
                result[i] = result[i] or (v shl shift)
            }
        }
        for (i in result.indices) if ((result[i] ushr 24) == 0) result[i] = 0
        return result
    }

    /**
     * Skaliert ein Raster streifenweise direkt von Kacheln zu Kacheln. Es liegt nie das ganze Bild
     * als Gleitkomma-Puffer im Speicher – auch sehr große Ebenen passen in den Heap eines Tablets.
     * [gray] = Maske (Graustufen 0..255) statt ARGB.
     */
    fun resizeGrid(src: TileGrid, nw: Int, nh: Int, method: ResampleMethod, gray: Boolean = false): TileGrid {
        val w = src.width; val h = src.height
        val sx = nw.toDouble() / w; val sy = nh.toDouble() / h
        // Bereich außerhalb der Arbeitsfläche wird exakt mit demselben Maßstab mitskaliert
        val extended = src.extendsBeyondCanvas
        val srcArea = if (extended) src.contentBounds(alphaOnly = false).union(IntRect.ofSize(w, h)) else IntRect.ofSize(w, h)
        val dstArea = if (!extended) IntRect.ofSize(nw, nh) else IntRect(
            floor(srcArea.left * sx).toInt(), floor(srcArea.top * sy).toInt(), ceil(srcArea.right * sx).toInt(), ceil(srcArea.bottom * sy).toInt(),
        )
        val out = TileGrid(nw, nh, src.defaultValue, area = if (extended) dstArea else null)
        val m = if (gray && method == ResampleMethod.NEAREST) ResampleMethod.BILINEAR else method
        val aw = srcArea.width; val ah = srcArea.height
        val ow = dstArea.width; val oh = dstArea.height
        if (m == ResampleMethod.NEAREST) {
            val row = IntArray(ow); val srcRow = IntArray(aw)
            var lastY = Int.MIN_VALUE
            for (y in 0 until oh) {
                val syAbs = floor((dstArea.top + y + 0.5) / sy).toInt().coerceIn(srcArea.top, srcArea.bottom - 1)
                if (syAbs != lastY) { src.readRect(IntRect(srcArea.left, syAbs, srcArea.right, syAbs + 1), srcRow); lastY = syAbs }
                for (x in 0 until ow) {
                    val sxAbs = floor((dstArea.left + x + 0.5) / sx).toInt().coerceIn(srcArea.left, srcArea.right - 1)
                    row[x] = srcRow[sxAbs - srcArea.left]
                }
                out.writeRect(IntRect(dstArea.left, dstArea.top + y, dstArea.right, dstArea.top + y + 1), row)
            }
            out.compact(); return out
        }
        val kx = weights(srcArea.left, dstArea.left, ow, sx, m)
        val ky = weights(srcArea.top, dstArea.top, oh, sy, m)
        val channels = if (gray) 1 else 4
        // Streifen so wählen, dass Ein- und Ausgabe je Streifen klein bleiben; parallel auf allen Kernen
        val band = maxOf(8, minOf(262_144 / maxOf(1, ow), (1_000_000 / maxOf(1, aw)) * oh / maxOf(1, ah)))
        val count = (oh + band - 1) / band
        val inRowsPerBand = band.toLong() * ah / maxOf(1, oh) + 8
        val bytesPerBand = inRowsPerBand * aw * 8 + inRowsPerBand * ow * 4 * channels + band.toLong() * ow * 4
        Parallel.bands(count, bytesPerBand, compute = { bi ->
            val y0 = bi * band
            val y1 = minOf(oh, y0 + band)
            // Benötigte Eingabezeilen für diesen Streifen (relativ zu srcArea)
            var r0 = Int.MAX_VALUE; var r1 = Int.MIN_VALUE
            for (y in y0 until y1) { r0 = minOf(r0, ky.start[y]); r1 = maxOf(r1, ky.start[y] + ky.weights[y].size - 1) }
            r0 = r0.coerceIn(0, ah - 1); r1 = r1.coerceIn(0, ah - 1)
            val rows = r1 - r0 + 1
            val inPx = IntArray(aw * rows)
            src.readRect(IntRect(srcArea.left, srcArea.top + r0, srcArea.right, srcArea.top + r1 + 1), inPx)
            // Horizontaler Durchgang (vormultipliziert) für die benötigten Zeilen
            val hp = Array(channels) { FloatArray(ow * rows) }
            val plane = FloatArray(aw * rows)
            for (c in 0 until channels) {
                for (i in plane.indices) {
                    val p = inPx[i]
                    plane[i] = if (gray) p.toFloat() else {
                        val a = (p ushr 24) / 255f
                        when (c) { 0 -> a; 1 -> ((p shr 16) and 0xFF) * a; 2 -> ((p shr 8) and 0xFF) * a; else -> (p and 0xFF) * a }
                    }
                }
                applyH(plane, hp[c], aw, rows, ow, kx)
            }
            // Vertikaler Durchgang direkt in Ganzzahl-Pixel
            val outRows = IntArray(ow * (y1 - y0))
            for (y in y0 until y1) {
                val ws = ky.weights[y]; val st = ky.start[y]
                for (x in 0 until ow) {
                    if (gray) {
                        var acc = 0f
                        for (j in ws.indices) acc += hp[0][((st + j).coerceIn(0, ah - 1) - r0) * ow + x] * ws[j]
                        outRows[(y - y0) * ow + x] = acc.roundToInt().coerceIn(0, 255)
                    } else {
                        var a = 0f; var r = 0f; var g = 0f; var b = 0f
                        for (j in ws.indices) {
                            val o = ((st + j).coerceIn(0, ah - 1) - r0) * ow + x
                            val k = ws[j]
                            a += hp[0][o] * k; r += hp[1][o] * k; g += hp[2][o] * k; b += hp[3][o] * k
                        }
                        val ac = a.coerceIn(0f, 1f)
                        outRows[(y - y0) * ow + x] = if (ac <= 1f / 512f) 0 else Colors.argb(
                            (ac * 255f).roundToInt(), (r / a).roundToInt(), (g / a).roundToInt(), (b / a).roundToInt(),
                        )
                    }
                }
            }
            outRows
        }, consume = { bi, outRows ->
            val y0 = bi * band
            out.writeRect(IntRect(dstArea.left, dstArea.top + y0, dstArea.right, minOf(dstArea.bottom, dstArea.top + y0 + band)), outRows)
        })
        out.compact()
        return out
    }

    /** Skaliert einen Graustufenkanal (Masken). */
    fun resizeGray(src: IntArray, w: Int, h: Int, nw: Int, nh: Int, method: ResampleMethod): IntArray {
        val p = FloatArray(src.size) { src[it].toFloat() }
        val tmp = FloatArray(nw * h); val out = FloatArray(nw * nh)
        val m = if (method == ResampleMethod.NEAREST) ResampleMethod.BILINEAR else method
        applyH(p, tmp, w, h, nw, weights(w, nw, m))
        applyV(tmp, out, nw, h, nh, weights(h, nh, m))
        return IntArray(out.size) { out[it].roundToInt().coerceIn(0, 255) }
    }

    private class Kernel(val start: IntArray, val weights: Array<FloatArray>)

    private fun filterRadius(m: ResampleMethod) = when (m) {
        ResampleMethod.BILINEAR -> 1.0
        ResampleMethod.BICUBIC -> 2.0
        ResampleMethod.LANCZOS -> 3.0
        ResampleMethod.NEAREST -> 0.5
    }

    private fun kernel(m: ResampleMethod, x: Double): Double {
        val ax = abs(x)
        return when (m) {
            ResampleMethod.BILINEAR -> max(0.0, 1.0 - ax)
            ResampleMethod.BICUBIC -> { // Catmull-Rom (a = -0.5)
                val a = -0.5
                when {
                    ax < 1 -> (a + 2) * ax * ax * ax - (a + 3) * ax * ax + 1
                    ax < 2 -> a * ax * ax * ax - 5 * a * ax * ax + 8 * a * ax - 4 * a
                    else -> 0.0
                }
            }
            ResampleMethod.LANCZOS -> if (ax < 1e-8) 1.0 else if (ax >= 3) 0.0 else {
                val px = PI * x; 3 * sin(px) * sin(px / 3) / (px * px)
            }
            ResampleMethod.NEAREST -> if (ax <= 0.5) 1.0 else 0.0
        }
    }

    /** Gewichte pro Zielpixel; beim Verkleinern wird der Kern gestreckt (Antialiasing). */
    private fun weights(srcLen: Int, dstLen: Int, m: ResampleMethod): Kernel =
        weights(0, 0, dstLen, dstLen.toDouble() / srcLen, m)

    /**
     * Gewichte für einen Ausschnitt: Zielpixel dstStart+i liegt bei der absoluten Quellkoordinate
     * (x + 0.5) / scale − 0.5; die Startindizes sind relativ zu [srcStart].
     */
    private fun weights(srcStart: Int, dstStart: Int, dstLen: Int, scale: Double, m: ResampleMethod): Kernel {
        val stretch = if (scale < 1) 1 / scale else 1.0
        val radius = filterRadius(m) * stretch
        val starts = IntArray(dstLen)
        val ws = Array(dstLen) { i ->
            val center = (dstStart + i + 0.5) / scale - 0.5 - srcStart
            val s = ceil(center - radius).toInt(); val e = floor(center + radius).toInt()
            starts[i] = s
            val arr = FloatArray(e - s + 1)
            var sum = 0.0
            for (j in s..e) { val v = kernel(m, (j - center) / stretch); arr[j - s] = v.toFloat(); sum += v }
            if (sum != 0.0) for (k in arr.indices) arr[k] = (arr[k] / sum).toFloat()
            arr
        }
        return Kernel(starts, ws)
    }

    private fun applyH(src: FloatArray, dst: FloatArray, w: Int, h: Int, nw: Int, k: Kernel) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until nw) {
                val ws = k.weights[x]; val s = k.start[x]
                var acc = 0f
                for (j in ws.indices) acc += src[row + (s + j).coerceIn(0, w - 1)] * ws[j]
                dst[y * nw + x] = acc
            }
        }
    }

    private fun applyV(src: FloatArray, dst: FloatArray, w: Int, h: Int, nh: Int, k: Kernel) {
        for (y in 0 until nh) {
            val ws = k.weights[y]; val s = k.start[y]
            for (x in 0 until w) {
                var acc = 0f
                for (j in ws.indices) acc += src[(s + j).coerceIn(0, h - 1) * w + x] * ws[j]
                dst[y * w + x] = acc
            }
        }
    }

    // ------------------------------------------------------------ Affine Abbildung (Smartobjekte)

    /**
     * Rendert [src] (Größe sw×sh) mit Transformation [t] in ein Raster der Größe dw×dh.
     * Beim Verkleinern wird aus einer Mipmap-Stufe gelesen, sonst bikubisch interpoliert.
     */
    fun renderAffine(src: TileGrid, t: Affine, dw: Int, dh: Int, fast: Boolean = false): TileGrid {
        val out = TileGrid(dw, dh)
        val region = Affine.clampRect(t.bounds(src.width.toDouble(), src.height.toDouble()), dw, dh)
        if (region.isEmpty) return out
        // Mipmap-Stufe: Faktor 2^level, sodass der verbleibende Maßstab >= 0.5 ist
        var level = 0; var s = t.scale
        while (s < 0.5 && level < 8) { s *= 2; level++ }
        var mip = src; var mipScale = 1.0
        repeat(level) { mip = halve(mip); mipScale *= 0.5 }
        val inv = t.inverse()
        val sw = mip.width; val sh = mip.height
        // Streifenweise auf allen Kernen: pro Ausgabestreifen nur den benötigten Quellausschnitt lesen
        val bandH = maxOf(1, minOf(region.height, 262_144 / maxOf(1, region.width)))
        val count = (region.height + bandH - 1) / bandH
        Parallel.bands(count, region.width.toLong() * bandH * 4 * 6, compute = { bi ->
            val y0 = region.top + bi * bandH; val y1 = minOf(region.bottom, y0 + bandH)
            val px = IntArray(region.width * (y1 - y0))
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (cy in intArrayOf(y0, y1)) for (cx in intArrayOf(region.left, region.right)) {
                val qx = inv.mapX(cx.toDouble(), cy.toDouble()) * mipScale; val qy = inv.mapY(cx.toDouble(), cy.toDouble()) * mipScale
                minX = minOf(minX, qx); maxX = maxOf(maxX, qx); minY = minOf(minY, qy); maxY = maxOf(maxY, qy)
            }
            val src = IntRect(floor(minX).toInt() - 3, floor(minY).toInt() - 3, ceil(maxX).toInt() + 3, ceil(maxY).toInt() + 3)
                .intersect(IntRect.ofSize(sw, sh))
            if (!src.isEmpty) {
                val buf = IntArray(src.width * src.height)
                mip.readRect(src, buf)
                var o = 0
                for (y in y0 until y1) for (x in region.left until region.right) {
                    val qx = inv.mapX(x + 0.5, y + 0.5) * mipScale - 0.5 - src.left
                    val qy = inv.mapY(x + 0.5, y + 0.5) * mipScale - 0.5 - src.top
                    px[o++] = if (fast) sampleBilinear(buf, src.width, src.height, qx, qy) else sampleBicubic(buf, src.width, src.height, qx, qy)
                }
            }
            IntRect(region.left, y0, region.right, y1) to px
        }, consume = { _, (r, px) -> out.writeRect(r, px) })
        out.compact()
        return out
    }

    /**
     * Rendert [src] mit der projektiven Abbildung [h] (Quelle → Ziel) in ein Raster [dw]×[dh] –
     * für Verzerren, Perspektive und perspektivisches Freistellen. Beim Verkleinern aus einer
     * Mipmap-Stufe (nach dem mittleren Maßstab), sonst bikubisch; parallel in Streifen.
     */
    fun renderProjective(src: TileGrid, h: Homography, dw: Int, dh: Int, fast: Boolean = false): TileGrid {
        val out = TileGrid(dw, dh)
        val c = h.corners(src.width.toDouble(), src.height.toDouble())
        val region = IntRect(
            floor(minOf(c[0], c[2], c[4], c[6])).toInt() - 1, floor(minOf(c[1], c[3], c[5], c[7])).toInt() - 1,
            ceil(maxOf(c[0], c[2], c[4], c[6])).toInt() + 1, ceil(maxOf(c[1], c[3], c[5], c[7])).toInt() + 1,
        ).intersect(IntRect.ofSize(dw, dh))
        if (region.isEmpty) return out
        var level = 0; var s = h.scaleFor(src.width.toDouble(), src.height.toDouble())
        while (s < 0.5 && level < 8) { s *= 2; level++ }
        var mip = src; var mipScale = 1.0
        repeat(level) { mip = halve(mip); mipScale *= 0.5 }
        val inv = h.inverse()
        val sw = mip.width; val sh = mip.height
        val bandH = maxOf(1, minOf(region.height, 131_072 / maxOf(1, region.width)))
        val count = (region.height + bandH - 1) / bandH
        Parallel.bands(count, region.width.toLong() * bandH * 4 * 6, compute = { bi ->
            val y0 = region.top + bi * bandH; val y1 = minOf(region.bottom, y0 + bandH)
            val px = IntArray(region.width * (y1 - y0))
            // Quellausschnitt: Bild der Streifenränder (Geraden bleiben Geraden); bei Punkten „hinter“ der Abbildung alles
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            var valid = true
            for (cy in intArrayOf(y0, y1)) for (cx in intArrayOf(region.left, region.right)) {
                if (inv.w(cx.toDouble(), cy.toDouble()) <= 0) valid = false
                val qx = inv.mapX(cx.toDouble(), cy.toDouble()) * mipScale; val qy = inv.mapY(cx.toDouble(), cy.toDouble()) * mipScale
                minX = minOf(minX, qx); maxX = maxOf(maxX, qx); minY = minOf(minY, qy); maxY = maxOf(maxY, qy)
            }
            val srcRect = (if (valid) IntRect(floor(minX).toInt() - 3, floor(minY).toInt() - 3, ceil(maxX).toInt() + 3, ceil(maxY).toInt() + 3)
                else IntRect.ofSize(sw, sh)).intersect(IntRect.ofSize(sw, sh))
            if (!srcRect.isEmpty && srcRect.width.toLong() * srcRect.height <= 64L shl 20) {
                val buf = IntArray(srcRect.width * srcRect.height)
                mip.readRect(srcRect, buf)
                var o = 0
                for (y in y0 until y1) for (x in region.left until region.right) {
                    val wx = x + 0.5; val wy = y + 0.5
                    if (inv.w(wx, wy) <= 0) { o++; continue }
                    val qx = inv.mapX(wx, wy) * mipScale - 0.5 - srcRect.left
                    val qy = inv.mapY(wx, wy) * mipScale - 0.5 - srcRect.top
                    px[o++] = if (fast) sampleBilinear(buf, srcRect.width, srcRect.height, qx, qy) else sampleBicubic(buf, srcRect.width, srcRect.height, qx, qy)
                }
            }
            IntRect(region.left, y0, region.right, y1) to px
        }, consume = { _, (r, px) -> out.writeRect(r, px) })
        out.compact()
        return out
    }

    private fun halve(g: TileGrid): TileGrid {
        val w = g.width; val h = g.height
        val nw = max(1, (w + 1) / 2); val nh = max(1, (h + 1) / 2)
        return resizeGrid(g, nw, nh, ResampleMethod.BILINEAR)
    }

    private fun texel(buf: IntArray, w: Int, h: Int, x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= w || y >= h) 0 else buf[y * w + x]

    fun sampleBilinear(buf: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
        val acc = FloatArray(4)
        accum(acc, texel(buf, w, h, x0, y0), (1 - fx) * (1 - fy))
        accum(acc, texel(buf, w, h, x0 + 1, y0), fx * (1 - fy))
        accum(acc, texel(buf, w, h, x0, y0 + 1), (1 - fx) * fy)
        accum(acc, texel(buf, w, h, x0 + 1, y0 + 1), fx * fy)
        return finish(acc)
    }

    fun sampleBicubic(buf: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val wx = FloatArray(4) { kernel(ResampleMethod.BICUBIC, fx - (it - 1)).toFloat() }
        val wy = FloatArray(4) { kernel(ResampleMethod.BICUBIC, fy - (it - 1)).toFloat() }
        val acc = FloatArray(4)
        for (j in 0..3) for (i in 0..3) {
            val k = wx[i] * wy[j]
            if (k != 0f) accum(acc, texel(buf, w, h, x0 + i - 1, y0 + j - 1), k)
        }
        return finish(acc)
    }

    private fun accum(acc: FloatArray, c: Int, k: Float) {
        val a = (c ushr 24) / 255f * k
        acc[3] += a
        acc[0] += ((c shr 16) and 0xFF) * a; acc[1] += ((c shr 8) and 0xFF) * a; acc[2] += (c and 0xFF) * a
    }

    private fun finish(acc: FloatArray): Int {
        val a = acc[3].coerceIn(0f, 1f)
        if (a <= 1f / 512f) return 0
        val inv = 1f / acc[3]
        return Colors.argb((a * 255f).roundToInt(), (acc[0] * inv).roundToInt(), (acc[1] * inv).roundToInt(), (acc[2] * inv).roundToInt())
    }
}
