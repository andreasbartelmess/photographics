package de.photographics.core.render

import de.photographics.core.doc.LayerEffects
import de.photographics.core.image.Blur
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Alphakarten der Ebeneneffekte. Eingabe ist die Deckung des Ebeneninhalts [a] (0..1, Maske schon
 * eingerechnet) auf einer Fläche [w]×[h], die um die Reichweite der Effekte größer ist als der
 * gerenderte Ausschnitt. Alle Längen in Ausgabepixeln (Dokumentpixel geteilt durch den Abtastschritt).
 */
object Effects {
    /** Größe → Gauß-Sigma: Photoshops „Größe“ ist etwa die Breite des weichen Übergangs. */
    private const val SIZE_TO_SIGMA = 1f / 2.5f

    fun dropShadow(a: FloatArray, w: Int, h: Int, s: LayerEffects.Shadow, angle: Double, scale: Double): FloatArray {
        val (dx, dy) = offset(angle, s.distance / scale)
        val e = soften(shift(a, w, h, dx, dy), w, h, s.spread, s.size / scale)
        if (s.knockOut) for (i in e.indices) e[i] *= 1f - a[i]
        return e
    }

    fun innerShadow(a: FloatArray, w: Int, h: Int, s: LayerEffects.Shadow, angle: Double, scale: Double): FloatArray {
        val (dx, dy) = offset(angle, s.distance / scale)
        val inv = shift(FloatArray(a.size) { 1f - a[it] }, w, h, dx, dy, outside = 1f)
        val e = soften(inv, w, h, s.spread, s.size / scale)
        for (i in e.indices) e[i] *= a[i]
        return e
    }

    fun outerGlow(a: FloatArray, w: Int, h: Int, g: LayerEffects.Glow, scale: Double): FloatArray =
        soften(a.copyOf(), w, h, g.spread, g.size / scale)

    fun innerGlow(a: FloatArray, w: Int, h: Int, g: LayerEffects.Glow, scale: Double): FloatArray {
        val e = soften(FloatArray(a.size) { 1f - a[it] }, w, h, g.spread, g.size / scale)
        for (i in e.indices) e[i] = (if (g.fromEdge) e[i] else 1f - e[i]) * a[i]
        return e
    }

    fun stroke(a: FloatArray, w: Int, h: Int, s: LayerEffects.Stroke, scale: Double, inside: FloatArray? = null): FloatArray {
        val size = (s.size / scale).toFloat()
        return when (s.position) {
            LayerEffects.StrokePosition.OUTSIDE -> { val d = dilate(a, w, h, size, inside); FloatArray(a.size) { (d[it] - a[it]).coerceIn(0f, 1f) } }
            LayerEffects.StrokePosition.INSIDE -> { val e = erode(a, w, h, size); FloatArray(a.size) { (a[it] - e[it]).coerceIn(0f, 1f) } }
            LayerEffects.StrokePosition.CENTER -> {
                val d = dilate(a, w, h, size / 2, inside); val e = erode(a, w, h, size / 2)
                FloatArray(a.size) { (d[it] - e[it]).coerceIn(0f, 1f) }
            }
        }
    }

    /** Licht aus [angle] Grad (0° rechts, 90° oben) → Schatten in die Gegenrichtung (Bild-y nach unten). */
    private fun offset(angle: Double, dist: Double): Pair<Int, Int> {
        val r = Math.toRadians(angle)
        return (-cos(r) * dist).roundToInt() to (sin(r) * dist).roundToInt()
    }

    /**
     * Ausdehnung um spread·size, danach Weichzeichnen um den Rest (wie „Überfüllen/Unterfüllen“ und
     * „Größe“). Große weiche Effekte werden verkleinert gerechnet (bis 1/4) und glatt hochgerechnet –
     * das Ergebnis ist ohnehin weich, kostet so aber nur einen Bruchteil.
     */
    private fun soften(a: FloatArray, w: Int, h: Int, spread: Double, size: Double): FloatArray {
        val grow = (spread * size).toFloat()
        val sigma = ((1 - spread) * size).toFloat() * SIZE_TO_SIGMA
        val q = when { sigma >= 16f -> 4; sigma >= 6f -> 2; else -> 1 }
        if (q == 1) {
            val e = if (grow > 0.25f) dilate(a, w, h, grow) else a
            if (sigma > 0.2f) Blur.gaussPlane(e, w, h, sigma)
            return e
        }
        val sw = (w + q - 1) / q; val sh = (h + q - 1) / q
        var small = FloatArray(sw * sh)
        for (y in 0 until h) { val row = (y / q) * sw; for (x in 0 until w) small[row + x / q] += a[y * w + x] }
        val area = 1f / (q * q)
        for (i in small.indices) small[i] *= area
        if (grow / q > 0.25f) small = dilate(small, sw, sh, grow / q)
        Blur.gaussPlane(small, sw, sh, sigma / q)
        // bilinear zurück auf volle Größe (Pixelmitten)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / q - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = minOf(sh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / q - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = minOf(sw - 1, x0 + 1); val tx = fx - x0
                val top = small[y0 * sw + x0] * (1 - tx) + small[y0 * sw + x1] * tx
                val bot = small[y1 * sw + x0] * (1 - tx) + small[y1 * sw + x1] * tx
                out[y * w + x] = top * (1 - ty) + bot * ty
            }
        }
        return out
    }

    private fun shift(a: FloatArray, w: Int, h: Int, dx: Int, dy: Int, outside: Float = 0f): FloatArray {
        if (dx == 0 && dy == 0) return a.copyOf()
        val o = FloatArray(a.size)
        for (y in 0 until h) {
            val sy = y - dy
            for (x in 0 until w) {
                val sx = x - dx
                o[y * w + x] = if (sx in 0 until w && sy in 0 until h) a[sy * w + sx] else outside
            }
        }
        return o
    }

    /** Erweitert die Deckung um [r] Pixel (runde Form, weiche Kante über die Teildeckung). */
    /** [dist]: bereits berechnete [distanceToInside] von [a] (spart die Rechnung bei mehreren Effekten). */
    fun dilate(a: FloatArray, w: Int, h: Int, r: Float, dist: FloatArray? = null): FloatArray {
        if (r <= 0f) return a.copyOf()
        val d = dist ?: distanceToInside(a, w, h)
        return FloatArray(a.size) { i -> maxOf(a[i], (r + 0.5f - d[i]).coerceIn(0f, 1f)) }
    }

    fun erode(a: FloatArray, w: Int, h: Int, r: Float): FloatArray {
        val d = dilate(FloatArray(a.size) { 1f - a[it] }, w, h, r)
        return FloatArray(a.size) { 1f - d[it] }
    }

    /**
     * Euklidischer Abstand jedes Pixels zum nächsten „gedeckten“ Pixel (Deckung ≥ 0,5), exakt nach
     * Felzenszwalb/Huttenlocher (zwei 1D-Durchgänge über das Quadrat des Abstands).
     */
    fun distanceToInside(a: FloatArray, w: Int, h: Int): FloatArray {
        val inf = 1e20f
        val f = FloatArray(a.size) { if (a[it] >= 0.5f) 0f else inf }
        val n = maxOf(w, h)
        val col = FloatArray(n); val out = FloatArray(n)
        val v = IntArray(n); val z = FloatArray(n + 1)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = f[y * w + x]
            edt1d(col, h, out, v, z)
            for (y in 0 until h) f[y * w + x] = out[y]
        }
        for (y in 0 until h) {
            System.arraycopy(f, y * w, col, 0, w)
            edt1d(col, w, out, v, z)
            for (x in 0 until w) f[y * w + x] = sqrt(out[x])
        }
        return f
    }

    private fun edt1d(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        fun cut(q: Int, p: Int) = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * (q - p))
        var k = 0
        v[0] = 0; z[0] = -Float.MAX_VALUE; z[1] = Float.MAX_VALUE
        for (q in 1 until n) {
            var s = cut(q, v[k])
            while (s <= z[k]) { k--; s = cut(q, v[k]) }
            k++; v[k] = q; z[k] = s; z[k + 1] = Float.MAX_VALUE
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            d[q] = (q - p).toFloat() * (q - p) + f[p]
        }
    }

    /**
     * Abgeflachte Kante und Relief: Höhenfeld aus dem Abstand zur Kante, beleuchtet aus [angle]
     * (Grad) in Höhe [b].altitude. Ergebnis: Deckung der Lichter und der Tiefen.
     */
    fun bevel(a: FloatArray, w: Int, h: Int, b: LayerEffects.Bevel, angle: Double, scale: Double): Pair<FloatArray, FloatArray> {
        val size = (b.size / scale).toFloat().coerceAtLeast(0.5f)
        val n = a.size
        // Abstand nach innen (zur Kante) und nach außen
        val dIn = distanceToInside(FloatArray(n) { 1f - a[it] }, w, h)
        val dOut = distanceToInside(a, w, h)
        fun ramp(d: Float) = (d / size).coerceIn(0f, 1f).let { t -> t * t * (3 - 2 * t) }
        val hgt = FloatArray(n) { i ->
            val inner = if (a[i] >= 0.5f) ramp(dIn[i]) else 0f
            val outer = if (a[i] < 0.5f) 1f - ramp(dOut[i]) else 1f
            when (b.style) {
                LayerEffects.BevelStyle.INNER -> inner
                LayerEffects.BevelStyle.OUTER -> outer
                LayerEffects.BevelStyle.EMBOSS -> (inner + outer) / 2
                LayerEffects.BevelStyle.PILLOW -> if (a[i] >= 0.5f) inner else 1f - outer
            }
        }
        val soft = (b.soften / scale).toFloat() + size * 0.15f
        if (soft > 0.3f) Blur.gaussPlane(hgt, w, h, soft)
        val r = Math.toRadians(angle); val alt = Math.toRadians(b.altitude.coerceIn(0.0, 90.0))
        val lx = (cos(alt) * cos(r)).toFloat(); val ly = (-cos(alt) * sin(r)).toFloat(); val lz = sin(alt).toFloat()
        val k = (b.depth * size * (if (b.up) 1 else -1)).toFloat()
        val hi = FloatArray(n); val lo = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val gx = (hgt[y * w + minOf(w - 1, x + 1)] - hgt[y * w + maxOf(0, x - 1)]) * 0.5f * k
            val gy = (hgt[minOf(h - 1, y + 1) * w + x] - hgt[maxOf(0, y - 1) * w + x]) * 0.5f * k
            val len = sqrt(gx * gx + gy * gy + 1f)
            val shade = (-gx * lx - gy * ly + lz) / len
            // Nur wo die Form wirkt: innen bzw. im Bereich der äußeren Kante
            val where = when (b.style) {
                LayerEffects.BevelStyle.INNER -> a[i]
                LayerEffects.BevelStyle.OUTER -> 1f - a[i]
                else -> 1f
            }
            if (shade > lz) hi[i] = ((shade - lz) / (1f - lz).coerceAtLeast(1e-3f)).coerceIn(0f, 1f) * where
            else lo[i] = ((lz - shade) / lz.coerceAtLeast(1e-3f)).coerceIn(0f, 1f) * where
        }
        return hi to lo
    }

    /** Glanz: |weich(verschoben +d) − weich(verschoben −d)| im Inneren (umgekehrt: 1 − …). */
    fun satin(a: FloatArray, w: Int, h: Int, s: LayerEffects.Satin, scale: Double): FloatArray {
        val (dx, dy) = offset(s.angle, s.distance / scale)
        val p = soften(shift(a, w, h, dx, dy), w, h, 0.0, s.size / scale)
        val q = soften(shift(a, w, h, -dx, -dy), w, h, 0.0, s.size / scale)
        return FloatArray(a.size) { i -> val v = kotlin.math.abs(p[i] - q[i]).coerceIn(0f, 1f); (if (s.invert) 1f - v else v) * a[i] }
    }
}
