package de.photographics.core.selection

import de.photographics.core.tr
import de.photographics.core.image.Blur
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class SelectionShape(val label: String) {
    RECT(tr("Rechteck")), ELLIPSE(tr("Ellipse")), LASSO(tr("Lasso")), POLYGON(tr("Polygon-Lasso")), WAND(tr("Zauberstab")), QUICK(tr("Schnellauswahl")), OBJECT(tr("Objekt"));

    /** Form aus einem Zug (Rechteck/Ellipse/Lasso) – für Werkzeuge, die nur solche Formen brauchen. */
    val isShape get() = this == RECT || this == ELLIPSE || this == LASSO
}
enum class SelectionOp { REPLACE, ADD, SUBTRACT }

/**
 * Pixelauswahl als Graustufenmaske (0 = nicht ausgewählt, 255 = ausgewählt) plus
 * Umrisspolygone für die "laufenden Ameisen"-Darstellung.
 */
class Selection(
    val mask: TileGrid,
    /** Polygone als (x0, y0, x1, y1, ...) in Dokumentkoordinaten. */
    val outlines: List<FloatArray>,
) {
    val width get() = mask.width
    val height get() = mask.height

    /** Auswahlwert 0..255 an (x, y). */
    fun at(x: Int, y: Int): Int = mask[x, y]

    /** Begrenzungsrahmen der Auswahl (bei invertierten Auswahlen meist das ganze Dokument). */
    val bounds: IntRect by lazy {
        if (mask.defaultValue != 0) IntRect.ofSize(width, height) else mask.contentBounds(alphaOnly = false)
    }

    val isEmpty: Boolean get() = bounds.isEmpty

    fun inverted(): Selection {
        val inv = TileGrid(width, height, 255 - mask.defaultValue)
        for (ty in 0 until mask.tilesY) for (tx in 0 until mask.tilesX) {
            val tT = mask.tile(tx, ty) ?: continue
            val oT = inv.tileOrCreate(tx, ty)
            val t = tT.pin(forWrite = false); val o = oT.pin()
            for (i in t.indices) o[i] = 255 - t[i]
            tT.unpin(); oT.unpin()
        }
        return Selection(inv, outlines + listOf(floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())))
    }

    /** Kombiniert diese Auswahl mit einer weiteren. */
    fun combine(other: Selection, op: SelectionOp): Selection {
        if (op == SelectionOp.REPLACE) return other
        val defaultV = when (op) {
            SelectionOp.ADD -> max(mask.defaultValue, other.mask.defaultValue)
            else -> max(0, mask.defaultValue - other.mask.defaultValue)
        }
        val out = TileGrid(width, height, defaultV)
        for (ty in 0 until mask.tilesY) for (tx in 0 until mask.tilesX) {
            val aT = mask.tile(tx, ty); val bT = other.mask.tile(tx, ty)
            if (aT == null && bT == null) continue
            val oT = out.tileOrCreate(tx, ty)
            val a = aT?.pin(forWrite = false); val b = bT?.pin(forWrite = false); val o = oT.pin()
            for (i in o.indices) {
                val va = a?.get(i) ?: mask.defaultValue
                val vb = b?.get(i) ?: other.mask.defaultValue
                o[i] = if (op == SelectionOp.ADD) max(va, vb) else (va * (255 - vb) + 127) / 255
            }
            aT?.unpin(); bT?.unpin(); oT.unpin()
        }
        out.compact()
        return Selection(out, outlines + other.outlines)
    }

    /** Weiche Auswahlkante mit Radius in Pixeln. */
    fun feathered(radius: Float): Selection {
        if (radius <= 0.5f) return this
        val pad = (radius * 3).toInt() + 1
        val r = bounds.inset(-pad).intersect(IntRect.ofSize(width, height))
        if (r.isEmpty) return this
        // Streifenweise mit Überlappung – auch eine Auswahl über das ganze Bild braucht nur wenig Speicher
        val out = mask.copy()
        val bandH = maxOf(64, 1_000_000 / maxOf(1, r.width))
        var y0 = r.top
        while (y0 < r.bottom) {
            val y1 = minOf(r.bottom, y0 + bandH)
            val read = IntRect(r.left, maxOf(r.top, y0 - pad), r.right, minOf(r.bottom, y1 + pad))
            val buf = IntArray(read.width * read.height)
            mask.readRect(read, buf)
            val f = FloatArray(buf.size) { buf[it].toFloat() }
            Blur.gaussPlane(f, read.width, read.height, radius)
            val off = (y0 - read.top) * read.width
            val res = IntArray((y1 - y0) * read.width) { f[off + it].roundToInt().coerceIn(0, 255) }
            out.writeRect(IntRect(r.left, y0, r.right, y1), res)
            y0 = y1
        }
        out.compact()
        return Selection(out, outlines)
    }

    companion object {
        /**
         * Umrisse (für die laufenden Ameisen) einer beliebigen Maske: folgt den Kanten zwischen
         * ausgewählten (≥ 128) und nicht ausgewählten Pixeln. Große Auswahlen werden auf einem
         * verkleinerten Raster verfolgt (Umrisse dienen nur der Anzeige); höchstens [maxContours] Umrisse.
         */
        fun outlinesOf(mask: TileGrid, maxCells: Int = 2_000_000, maxContours: Int = 400): List<FloatArray> {
            val b = if (mask.defaultValue != 0) IntRect.ofSize(mask.width, mask.height) else mask.contentBounds(alphaOnly = false)
            if (b.isEmpty) return emptyList()
            var st = 1
            while ((b.width / st).toLong() * (b.height / st) > maxCells) st++
            val gw = (b.width + st - 1) / st; val gh = (b.height + st - 1) / st
            val sel = BooleanArray(gw * gh)
            val row = IntArray(b.width)
            for (gy in 0 until gh) {
                val y = minOf(b.bottom - 1, b.top + gy * st + st / 2)
                mask.readRect(IntRect(b.left, y, b.right, y + 1), row)
                for (gx in 0 until gw) sel[gy * gw + gx] = row[minOf(b.width - 1, gx * st + st / 2)] >= 128
            }
            fun at(x: Int, y: Int) = x in 0 until gw && y in 0 until gh && sel[y * gw + x]
            // Gerichtete Kanten (ausgewählt rechts der Laufrichtung, y nach unten): Bits je Eckpunkt
            val vw = gw + 1
            val bits = ByteArray(vw * (gh + 1))
            val R = 1; val D = 2; val L = 4; val U = 8
            for (y in 0 until gh) for (x in 0 until gw) {
                if (!sel[y * gw + x]) continue
                if (!at(x, y - 1)) bits[y * vw + x] = (bits[y * vw + x].toInt() or R).toByte()
                if (!at(x + 1, y)) bits[y * vw + x + 1] = (bits[y * vw + x + 1].toInt() or D).toByte()
                if (!at(x, y + 1)) bits[(y + 1) * vw + x + 1] = (bits[(y + 1) * vw + x + 1].toInt() or L).toByte()
                if (!at(x - 1, y)) bits[(y + 1) * vw + x] = (bits[(y + 1) * vw + x].toInt() or U).toByte()
            }
            fun rightOf(d: Int) = when (d) { R -> D; D -> L; L -> U; else -> R }
            fun leftOf(d: Int) = when (d) { R -> U; U -> L; L -> D; else -> R }
            val loops = ArrayList<FloatArray>()
            for (start in bits.indices) {
                while (bits[start].toInt() != 0) {
                    var v = start
                    var b0 = bits[v].toInt()
                    var dir = listOf(R, D, L, U).first { b0 and it != 0 }
                    val pts = ArrayList<Float>()
                    var prev = -1
                    var guard = 0
                    while (true) {
                        val bv = bits[v].toInt()
                        if (prev != -1) {
                            // An Diagonal-Kreuzungen rechts abbiegen: getrennte Bereiche bleiben getrennt
                            dir = when { bv and rightOf(prev) != 0 -> rightOf(prev); bv and prev != 0 -> prev; bv and leftOf(prev) != 0 -> leftOf(prev); else -> 0 }
                            if (dir == 0) break
                        }
                        if (dir != prev) { pts += (b.left + (v % vw) * st).toFloat(); pts += (b.top + (v / vw) * st).toFloat() }
                        bits[v] = (bv and dir.inv()).toByte()
                        v += when (dir) { R -> 1; D -> vw; L -> -1; else -> -vw }
                        prev = dir
                        if (++guard > 50_000_000) break
                    }
                    if (pts.size >= 6) loops += pts.toFloatArray()
                }
            }
            return if (loops.size <= maxContours) loops else loops.sortedByDescending { it.size }.take(maxContours)
        }

        fun all(w: Int, h: Int) = Selection(
            TileGrid(w, h, 255),
            listOf(floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())),
        )

        fun rect(w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float): Selection {
            val l = min(x0, x1); val r = max(x0, x1); val t = min(y0, y1); val b = max(y0, y1)
            return polygon(w, h, floatArrayOf(l, t, r, t, r, b, l, b))
        }

        fun ellipse(w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float): Selection {
            val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f
            val rx = kotlin.math.abs(x1 - x0) / 2f; val ry = kotlin.math.abs(y1 - y0) / 2f
            val n = (max(rx, ry) * 0.5f).toInt().coerceIn(24, 360)
            val pts = FloatArray(n * 2)
            for (i in 0 until n) {
                val a = 2.0 * PI * i / n
                pts[i * 2] = cx + (rx * cos(a)).toFloat()
                pts[i * 2 + 1] = cy + (ry * sin(a)).toFloat()
            }
            return polygon(w, h, pts)
        }

        /** Rastert ein Polygon (gerade-ungerade-Regel) mit 4-fachem vertikalem Supersampling. */
        fun polygon(w: Int, h: Int, pts: FloatArray): Selection {
            val grid = TileGrid(w, h, 0)
            val n = pts.size / 2
            if (n < 3) return Selection(grid, emptyList())
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            for (i in 0 until n) {
                minX = min(minX, pts[i * 2]); maxX = max(maxX, pts[i * 2])
                minY = min(minY, pts[i * 2 + 1]); maxY = max(maxY, pts[i * 2 + 1])
            }
            val x0 = floor(minX).toInt().coerceIn(0, w); val x1 = (floor(maxX).toInt() + 1).coerceIn(0, w)
            val y0 = floor(minY).toInt().coerceIn(0, h); val y1 = (floor(maxY).toInt() + 1).coerceIn(0, h)
            val bw = x1 - x0
            if (bw <= 0 || y1 <= y0) return Selection(grid, listOf(pts))
            val cov = FloatArray(bw)
            val xs = FloatArray(n)
            val row = IntArray(bw)
            val sub = 4
            for (y in y0 until y1) {
                cov.fill(0f)
                for (s in 0 until sub) {
                    val sy = y + (s + 0.5f) / sub
                    var cnt = 0
                    for (i in 0 until n) {
                        val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
                        val j = (i + 1) % n
                        val bx = pts[j * 2]; val by = pts[j * 2 + 1]
                        if ((ay <= sy && by > sy) || (by <= sy && ay > sy)) {
                            xs[cnt++] = ax + (sy - ay) / (by - ay) * (bx - ax)
                        }
                    }
                    xs.sort(0, cnt)
                    var k = 0
                    while (k + 1 < cnt) {
                        addSpan(cov, xs[k] - x0, xs[k + 1] - x0, 1f / sub)
                        k += 2
                    }
                }
                var any = false
                for (i in 0 until bw) { row[i] = (cov[i] * 255f).roundToInt().coerceIn(0, 255); if (row[i] != 0) any = true }
                if (any) grid.writeRect(IntRect(x0, y, x1, y + 1), row)
            }
            grid.compact()
            return Selection(grid, listOf(pts))
        }

        private fun addSpan(cov: FloatArray, a: Float, b: Float, weight: Float) {
            val l = max(a, 0f); val r = min(b, cov.size.toFloat())
            if (r <= l) return
            val il = floor(l).toInt(); val ir = floor(r).toInt()
            if (il == ir) { cov[il] += (r - l) * weight; return }
            cov[il] += (il + 1 - l) * weight
            for (i in il + 1 until ir) cov[i] += weight
            if (ir < cov.size) cov[ir] += (r - ir) * weight
        }
    }
}
