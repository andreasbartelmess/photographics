package de.photographics.core.vector

import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.tr
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ankerpunkt eines Bézierpfads mit eingehendem ([inX], [inY]) und ausgehendem ([outX], [outY])
 * Griff in Dokumentkoordinaten. Ohne Griffe (Griff = Anker) ist es ein Eckpunkt.
 */
data class Anchor(
    val x: Double, val y: Double,
    val inX: Double = x, val inY: Double = y,
    val outX: Double = x, val outY: Double = y,
    /** Griffe bleiben gegenüberliegend (Übergangspunkt, wie Photoshops „verbundene“ Knoten). */
    val smooth: Boolean = false,
) {
    val isCorner get() = inX == x && inY == y && outX == x && outY == y
    fun map(m: Affine) = Anchor(m.mapX(x, y), m.mapY(x, y), m.mapX(inX, inY), m.mapY(inX, inY), m.mapX(outX, outY), m.mapY(outX, outY), smooth)
    fun moved(dx: Double, dy: Double) = Anchor(x + dx, y + dy, inX + dx, inY + dy, outX + dx, outY + dy, smooth)
}

data class SubPath(val anchors: List<Anchor>, val closed: Boolean)

/** Pfad aus Bézier-Teilpfaden (wie Photoshops Pfade und Form-Vektormasken). */
data class VectorPath(val subpaths: List<SubPath>) {
    val isEmpty get() = subpaths.all { it.anchors.isEmpty() }

    fun map(m: Affine) = VectorPath(subpaths.map { s -> SubPath(s.anchors.map { it.map(m) }, s.closed) })

    /** Begrenzung (über Anker und Griffe, also etwas großzügig): minX, minY, maxX, maxY. */
    fun bounds(): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (s in subpaths) for (a in s.anchors) for ((x, y) in listOf(a.x to a.y, a.inX to a.inY, a.outX to a.outY)) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        return doubleArrayOf(x0, y0, x1, y1)
    }

    /** Teilpfade als Polygone (x, y, …) mit Kurven in Stücke höchstens [tolerance] Pixel vom Bogen. */
    fun flatten(tolerance: Double = 0.2): List<Pair<FloatArray, Boolean>> = subpaths.filter { it.anchors.isNotEmpty() }.map { s ->
        val out = ArrayList<Float>()
        val a = s.anchors
        out += a[0].x.toFloat(); out += a[0].y.toFloat()
        val n = if (s.closed) a.size else a.size - 1
        for (i in 0 until n) {
            val p = a[i]; val q = a[(i + 1) % a.size]
            if (p.outX == p.x && p.outY == p.y && q.inX == q.x && q.inY == q.y) {
                out += q.x.toFloat(); out += q.y.toFloat()
            } else {
                // Unterteilung nach der Größe des Kontrollpolygons
                val len = hypot(p.outX - p.x, p.outY - p.y) + hypot(q.inX - p.outX, q.inY - p.outY) + hypot(q.x - q.inX, q.y - q.inY)
                val steps = ceil(sqrt(len / max(0.05, tolerance)) * 0.9).toInt().coerceIn(2, 512)
                for (k in 1..steps) {
                    val t = k.toDouble() / steps; val u = 1 - t
                    val b0 = u * u * u; val b1 = 3 * u * u * t; val b2 = 3 * u * t * t; val b3 = t * t * t
                    out += (b0 * p.x + b1 * p.outX + b2 * q.inX + b3 * q.x).toFloat()
                    out += (b0 * p.y + b1 * p.outY + b2 * q.inY + b3 * q.y).toFloat()
                }
            }
        }
        // Geschlossen: der letzte Punkt ist wieder der erste
        if (s.closed && out.size >= 4) { out.removeAt(out.size - 1); out.removeAt(out.size - 1) }
        out.toFloatArray() to s.closed
    }

    companion object {
        val EMPTY = VectorPath(emptyList())

        /** Rechteck; [radius] > 0 rundet die Ecken ab (wie das Abgerundete-Rechteck-Werkzeug). */
        fun rect(x0: Double, y0: Double, x1: Double, y1: Double, radius: Double = 0.0): VectorPath {
            val l = min(x0, x1); val t = min(y0, y1); val r = max(x0, x1); val b = max(y0, y1)
            val rr = radius.coerceIn(0.0, min(r - l, b - t) / 2)
            if (rr <= 0.0) return VectorPath(listOf(SubPath(listOf(Anchor(l, t), Anchor(r, t), Anchor(r, b), Anchor(l, b)), true)))
            val k = rr * KAPPA
            return VectorPath(listOf(SubPath(listOf(
                Anchor(l + rr, t, inX = l + rr - k, inY = t), Anchor(r - rr, t, outX = r - rr + k, outY = t),
                Anchor(r, t + rr, inX = r, inY = t + rr - k), Anchor(r, b - rr, outX = r, outY = b - rr + k),
                Anchor(r - rr, b, inX = r - rr + k, inY = b), Anchor(l + rr, b, outX = l + rr - k, outY = b),
                Anchor(l, b - rr, inX = l, inY = b - rr + k), Anchor(l, t + rr, outX = l, outY = t + rr - k),
            ), true)))
        }

        fun ellipse(x0: Double, y0: Double, x1: Double, y1: Double): VectorPath {
            val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; val rx = abs(x1 - x0) / 2; val ry = abs(y1 - y0) / 2
            val kx = rx * KAPPA; val ky = ry * KAPPA
            return VectorPath(listOf(SubPath(listOf(
                Anchor(cx, cy - ry, cx - kx, cy - ry, cx + kx, cy - ry, true),
                Anchor(cx + rx, cy, cx + rx, cy - ky, cx + rx, cy + ky, true),
                Anchor(cx, cy + ry, cx + kx, cy + ry, cx - kx, cy + ry, true),
                Anchor(cx - rx, cy, cx - rx, cy + ky, cx - rx, cy - ky, true),
            ), true)))
        }

        /** Regelmäßiges Vieleck bzw. Stern ([inner] < 1: Radius der inneren Ecken) im Rechteck. */
        fun polygon(x0: Double, y0: Double, x1: Double, y1: Double, sides: Int, inner: Double = 1.0): VectorPath {
            val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; val rx = abs(x1 - x0) / 2; val ry = abs(y1 - y0) / 2
            val n = sides.coerceIn(3, 100)
            val star = inner < 0.999
            val pts = ArrayList<Anchor>()
            for (i in 0 until n * (if (star) 2 else 1)) {
                val f = if (star && i % 2 == 1) inner else 1.0
                val a = -PI / 2 + i * PI * 2 / (n * (if (star) 2 else 1))
                pts += Anchor(cx + cos(a) * rx * f, cy + sin(a) * ry * f)
            }
            return VectorPath(listOf(SubPath(pts, true)))
        }

        /** Linie mit Breite [width] als geschlossene Fläche (wie Photoshops Linienzeichner). */
        fun line(x0: Double, y0: Double, x1: Double, y1: Double, width: Double): VectorPath {
            val len = hypot(x1 - x0, y1 - y0)
            if (len < 1e-6) return EMPTY
            val nx = -(y1 - y0) / len * width / 2; val ny = (x1 - x0) / len * width / 2
            return VectorPath(listOf(SubPath(listOf(Anchor(x0 + nx, y0 + ny), Anchor(x1 + nx, y1 + ny), Anchor(x1 - nx, y1 - ny), Anchor(x0 - nx, y0 - ny)), true)))
        }

        /** Umrisse einer Auswahl als Pfad (Arbeitspfad aus Auswahl), vereinfacht mit Toleranz [tolerance]. */
        fun fromOutlines(outlines: List<FloatArray>, tolerance: Double = 1.0): VectorPath = VectorPath(outlines.mapNotNull { poly ->
            val pts = simplify(poly, tolerance)
            if (pts.size < 6) null else SubPath((0 until pts.size / 2).map { Anchor(pts[it * 2].toDouble(), pts[it * 2 + 1].toDouble()) }, true)
        })

        /** Douglas-Peucker für geschlossene Polygone. */
        private fun simplify(p: FloatArray, tol: Double): FloatArray {
            val n = p.size / 2
            if (n < 4) return p
            val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
            fun rec(a: Int, b: Int) {
                var best = -1.0; var idx = -1
                val ax = p[a * 2]; val ay = p[a * 2 + 1]; val bx = p[b * 2]; val by = p[b * 2 + 1]
                val len = max(1e-9, hypot((bx - ax).toDouble(), (by - ay).toDouble()))
                for (i in a + 1 until b) {
                    val d = abs((bx - ax) * (ay - p[i * 2 + 1]) - (ax - p[i * 2]) * (by - ay)) / len
                    if (d > best) { best = d; idx = i }
                }
                if (best > tol && idx > 0) { keep[idx] = true; rec(a, idx); rec(idx, b) }
            }
            rec(0, n / 2); rec(n / 2, n - 1); keep[n / 2] = true
            val out = ArrayList<Float>()
            for (i in 0 until n) if (keep[i]) { out += p[i * 2]; out += p[i * 2 + 1] }
            return out.toFloatArray()
        }

        /** Bézier-Näherung eines Viertelkreises. */
        const val KAPPA = 0.5522847498
    }
}

/** Kontur einer Form: Breite in Pixeln, Farbe (ARGB), Lage zur Kante. */
data class ShapeStroke(val width: Float, val color: Int, val align: Align = Align.CENTER) {
    enum class Align(val label: String) { INSIDE(tr("Innen")), CENTER(tr("Mitte")), OUTSIDE(tr("Außen")) }
}

/** Kantengeglättetes Rastern von Pfaden in Deckungsmasken (0..255). */
object PathRaster {
    /**
     * Füllt Polygone (Nonzero-Regel, wie Photoshop; mit [evenOdd] gerade-ungerade) mit 4-fachem
     * vertikalem Supersampling und exakter waagerechter Abdeckung. Nur Zeilen in [clip].
     */
    fun fill(w: Int, h: Int, polys: List<FloatArray>, evenOdd: Boolean = false, clip: IntRect = IntRect.ofSize(w, h)): TileGrid {
        val grid = TileGrid(w, h, 0)
        // Kanten (x0, y0, x1, y1, Richtung) nach oberem Ende sortiert
        val edges = ArrayList<FloatArray>()
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in polys) {
            val n = p.size / 2
            if (n < 3) continue
            for (i in 0 until n) {
                val ax = p[i * 2]; val ay = p[i * 2 + 1]; val j = (i + 1) % n
                val bx = p[j * 2]; val by = p[j * 2 + 1]
                if (ay == by) continue
                edges += if (ay < by) floatArrayOf(ax, ay, bx, by, 1f) else floatArrayOf(bx, by, ax, ay, -1f)
                minX = min(minX, min(ax, bx)); maxX = max(maxX, max(ax, bx)); minY = min(minY, min(ay, by)); maxY = max(maxY, max(ay, by))
            }
        }
        if (edges.isEmpty()) return grid
        edges.sortBy { it[1] }
        val area = IntRect(floor(minX).toInt(), floor(minY).toInt(), floor(maxX).toInt() + 1, floor(maxY).toInt() + 1).intersect(clip).intersect(IntRect.ofSize(w, h))
        if (area.isEmpty) return grid
        val bw = area.width
        val cov = FloatArray(bw); val row = IntArray(bw)
        val xs = FloatArray(edges.size); val dirs = IntArray(edges.size)
        val active = ArrayList<FloatArray>()
        var next = 0
        val sub = 4
        for (y in area.top until area.bottom) {
            cov.fill(0f)
            for (s in 0 until sub) {
                val sy = y + (s + 0.5f) / sub
                while (next < edges.size && edges[next][1] <= sy) active += edges[next++]
                active.removeAll { it[3] <= sy }
                var cnt = 0
                for (e in active) if (e[1] <= sy && e[3] > sy) {
                    xs[cnt] = e[0] + (sy - e[1]) / (e[3] - e[1]) * (e[2] - e[0]); dirs[cnt] = e[4].toInt(); cnt++
                }
                // Nach x sortieren (Einfügesortierung: meist wenige Schnittpunkte)
                for (i in 1 until cnt) {
                    val x = xs[i]; val d = dirs[i]; var j = i - 1
                    while (j >= 0 && xs[j] > x) { xs[j + 1] = xs[j]; dirs[j + 1] = dirs[j]; j-- }
                    xs[j + 1] = x; dirs[j + 1] = d
                }
                var wind = 0
                for (i in 0 until cnt - 1) {
                    wind += dirs[i]
                    val inside = if (evenOdd) (i % 2 == 0) else wind != 0
                    if (inside) span(cov, xs[i] - area.left, xs[i + 1] - area.left, 1f / sub)
                }
            }
            var any = false
            for (i in 0 until bw) { row[i] = (cov[i] * 255f + 0.5f).toInt().coerceIn(0, 255); if (row[i] != 0) any = true }
            if (any) grid.writeRect(IntRect(area.left, y, area.right, y + 1), row)
        }
        grid.compact()
        return grid
    }

    private fun span(cov: FloatArray, a: Float, b: Float, weight: Float) {
        val l = max(a, 0f); val r = min(b, cov.size.toFloat())
        if (r <= l) return
        val il = floor(l).toInt(); val ir = floor(r).toInt()
        if (il == ir) { cov[il] += (r - l) * weight; return }
        cov[il] += (il + 1 - l) * weight
        for (i in il + 1 until ir) cov[i] += weight
        if (ir < cov.size) cov[ir] += (r - ir) * weight
    }

    /**
     * Kontur mit Breite [width]: Abstand zu den Strecken der Polylinien ≤ width/2 (runde Ecken und
     * Enden), kantengeglättet. Offene Teilpfade ohne Schlusskante.
     */
    fun stroke(w: Int, h: Int, polys: List<Pair<FloatArray, Boolean>>, width: Float, clip: IntRect = IntRect.ofSize(w, h)): TileGrid {
        val grid = TileGrid(w, h, 0)
        val r = max(0.25f, width / 2)
        val bounds = IntRect.ofSize(w, h).intersect(clip)
        // Je Kachelzeile alle Strecken sammeln, die hineinragen, und die Abdeckung pro Pixel bestimmen
        val segs = ArrayList<FloatArray>()
        for ((p, closed) in polys) {
            val n = p.size / 2
            if (n < 2) continue
            val m = if (closed) n else n - 1
            for (i in 0 until m) { val j = (i + 1) % n; segs += floatArrayOf(p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1]) }
        }
        if (segs.isEmpty()) return grid
        val bandH = 64
        var y0 = bounds.top
        while (y0 < bounds.bottom) {
            val y1 = min(bounds.bottom, y0 + bandH)
            val local = segs.filter { s -> min(s[1], s[3]) - r - 1 < y1 && max(s[1], s[3]) + r + 1 > y0 }
            if (local.isNotEmpty()) {
                var lx = Float.MAX_VALUE; var hx = -Float.MAX_VALUE
                for (s in local) { lx = min(lx, min(s[0], s[2])); hx = max(hx, max(s[0], s[2])) }
                val area = IntRect(floor(lx - r - 1).toInt(), y0, ceil(hx + r + 1).toInt(), y1).intersect(bounds)
                if (!area.isEmpty) {
                    val cov = FloatArray(area.width * area.height)
                    for (s in local) {
                        val sx0 = floor(min(s[0], s[2]) - r - 1).toInt().coerceAtLeast(area.left); val sx1 = ceil(max(s[0], s[2]) + r + 1).toInt().coerceAtMost(area.right)
                        val sy0 = floor(min(s[1], s[3]) - r - 1).toInt().coerceAtLeast(area.top); val sy1 = ceil(max(s[1], s[3]) + r + 1).toInt().coerceAtMost(area.bottom)
                        val dx = s[2] - s[0]; val dy = s[3] - s[1]; val len2 = dx * dx + dy * dy
                        for (yy in sy0 until sy1) for (xx in sx0 until sx1) {
                            val px = xx + 0.5f; val py = yy + 0.5f
                            val t = if (len2 > 0f) (((px - s[0]) * dx + (py - s[1]) * dy) / len2).coerceIn(0f, 1f) else 0f
                            val ex = s[0] + t * dx - px; val ey = s[1] + t * dy - py
                            val c = (r - sqrt(ex * ex + ey * ey) + 0.5f).coerceIn(0f, 1f)
                            val k = (yy - area.top) * area.width + xx - area.left
                            if (c > cov[k]) cov[k] = c
                        }
                    }
                    val px = IntArray(cov.size) { (cov[it] * 255f + 0.5f).toInt() }
                    if (px.any { it != 0 }) grid.writeRect(area, px)
                }
            }
            y0 = y1
        }
        grid.compact()
        return grid
    }
}

/** Gesicherter Pfad des Dokuments (Pfade-Bedienfeld). */
data class NamedPath(val name: String, val path: VectorPath)

/** Rechenhilfe: Deckung [cov] (0..255) mit Farbe [color] als ARGB. */
internal fun tint(color: Int, cov: Int): Int = if (cov == 0) 0 else ((((color ushr 24) * cov + 127) / 255) shl 24) or (color and 0xFFFFFF)

/** Auf ganze Pixel gerundete Begrenzung. */
fun DoubleArray.toIntRect(pad: Int = 0) = IntRect(floor(this[0]).toInt() - pad, floor(this[1]).toInt() - pad, ceil(this[2]).toInt() + pad, ceil(this[3]).toInt() + pad)

