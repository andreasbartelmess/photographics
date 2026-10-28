package de.photographics.core.retouch

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Füllt markierte Bereiche eines Bildausschnitts (z. B. KI-Modell oder PatchMatch). */
fun interface Inpainter {
    /** [px]: ARGB, [hole]: true = zu füllen. Liefert das vollständige Bild zurück. */
    fun inpaint(px: IntArray, w: Int, h: Int, hole: BooleanArray): IntArray
}

/**
 * Inhaltsbasiertes Füllen nach Wexler/Barnes: Mehrstufige Bildpyramide, auf jeder Stufe
 * wechseln PatchMatch-Suche (nächste passende Bildstücke außerhalb der Lücke) und
 * Abstimmung (jeder Lückenpixel = gewichteter Mittelwert der überlappenden Treffer).
 */
class PatchMatchInpainter(
    private val patchRadius: Int = 3,
    private val seed: Int = 1,
) : Inpainter {

    /** Eine Pyramidenstufe; [img] enthält gepackte ARGB-Werte (ein Speicherzugriff pro Pixel). */
    private class Level(val w: Int, val h: Int, val img: IntArray, val hole: BooleanArray)

    override fun inpaint(px: IntArray, w: Int, h: Int, hole: BooleanArray): IntArray {
        if (hole.none { it }) return px.copyOf()
        val rnd = Random(seed)
        val levels = ArrayList<Level>()
        levels += Level(w, h, px.copyOf(), hole)
        while (true) {
            val l = levels.last()
            if (min(l.w, l.h) < 4 * (2 * patchRadius + 1) || levels.size >= 8) break
            if (!l.hole.any { it }) break
            levels += downsample(l)
        }
        // Gröbste Stufe glatt vorfüllen
        val coarsest = levels.last()
        smoothFill(coarsest)

        var nnfX: IntArray? = null; var nnfY: IntArray? = null
        var prevW = 0
        for (li in levels.indices.reversed()) {
            val lv = levels[li]
            if (li < levels.size - 1) {
                // Lösung der gröberen Stufe hochskalieren
                val coarse = levels[li + 1]
                for (y in 0 until lv.h) for (x in 0 until lv.w) {
                    val i = y * lv.w + x
                    if (!lv.hole[i]) continue
                    val ci = min(y / 2, coarse.h - 1) * coarse.w + min(x / 2, coarse.w - 1)
                    lv.img[i] = coarse.img[ci]
                }
            }
            val valid = validSources(lv)
            if (valid.isEmpty()) continue
            val (nx, ny) = initNnf(lv, valid, nnfX, nnfY, prevW, rnd)
            // Grobe Stufen legen die Struktur fest; feine Stufen verfeinern nur noch
            val emIters = if (li == 0) 1 else if (li == levels.size - 1) 5 else 3
            val pmIters = if (li == 0) 2 else 3
            repeat(emIters) {
                patchMatch(lv, valid, nx, ny, rnd, iterations = pmIters)
                vote(lv, nx, ny)
            }
            nnfX = nx; nnfY = ny; prevW = lv.w
        }
        val res = levels[0].img
        return IntArray(w * h) { i -> if (!hole[i]) px[i] else res[i] }
    }

    private fun downsample(l: Level): Level {
        val nw = (l.w + 1) / 2; val nh = (l.h + 1) / 2
        val img = IntArray(nw * nh)
        val hole = BooleanArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            var n = 0; var a = 0; var r = 0; var g = 0; var b = 0; var anyHole = false
            for (dy in 0..1) for (dx in 0..1) {
                val sx = 2 * x + dx; val sy = 2 * y + dy
                if (sx >= l.w || sy >= l.h) continue
                val i = sy * l.w + sx
                if (l.hole[i]) { anyHole = true; continue }
                val c = l.img[i]
                a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                n++
            }
            val o = y * nw + x
            hole[o] = anyHole || n == 0
            if (n > 0) img[o] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return Level(nw, nh, img, hole)
    }

    /** Lücke durch wiederholtes Mitteln der Nachbarn glatt füllen (Startwert der gröbsten Stufe). */
    private fun smoothFill(l: Level) {
        val known = BooleanArray(l.w * l.h) { !l.hole[it] }
        var changed = true
        while (changed) {
            changed = false
            val next = known.copyOf()
            for (y in 0 until l.h) for (x in 0 until l.w) {
                val i = y * l.w + x
                if (known[i]) continue
                var n = 0; var a = 0; var r = 0; var g = 0; var b = 0
                for ((dx, dy) in NEIGHBORS) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= l.w || yy >= l.h) continue
                    val j = yy * l.w + xx
                    if (!known[j]) continue
                    val c = l.img[j]
                    a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                if (n > 0) { l.img[i] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n); next[i] = true; changed = true }
            }
            System.arraycopy(next, 0, known, 0, known.size)
        }
    }

    /** Mittelpunkte gültiger Quell-Patches: komplett im Bild und ohne Lückenpixel. */
    private fun validSources(l: Level): IntArray {
        val r = patchRadius
        val w = l.w; val h = l.h
        // Summierte Flächentabelle der Lücke
        val sat = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) for (x in 0 until w) {
            sat[(y + 1) * (w + 1) + x + 1] = (if (l.hole[y * w + x]) 1 else 0) +
                sat[y * (w + 1) + x + 1] + sat[(y + 1) * (w + 1) + x] - sat[y * (w + 1) + x]
        }
        val out = ArrayList<Int>()
        for (y in r until h - r) for (x in r until w - r) {
            val x0 = x - r; val y0 = y - r; val x1 = x + r + 1; val y1 = y + r + 1
            val s = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
            if (s == 0) out += y * w + x
        }
        return out.toIntArray()
    }

    private fun isValid(l: Level, x: Int, y: Int, validSet: BooleanArray): Boolean =
        x >= 0 && y >= 0 && x < l.w && y < l.h && validSet[y * l.w + x]

    private fun initNnf(l: Level, valid: IntArray, px: IntArray?, py: IntArray?, prevW: Int, rnd: Random): Pair<IntArray, IntArray> {
        val nx = IntArray(l.w * l.h); val ny = IntArray(l.w * l.h)
        val validSet = BooleanArray(l.w * l.h).also { for (v in valid) it[v] = true }
        for (y in 0 until l.h) for (x in 0 until l.w) {
            val i = y * l.w + x
            var sx = -1; var sy = -1
            if (px != null && py != null && prevW > 0) {
                val pi = min(y / 2, (px.size / prevW) - 1) * prevW + min(x / 2, prevW - 1)
                val cx = px[pi] * 2 + (x and 1); val cy = py[pi] * 2 + (y and 1)
                if (isValid(l, cx, cy, validSet)) { sx = cx; sy = cy }
            }
            if (sx < 0) { val v = valid[rnd.nextInt(valid.size)]; sx = v % l.w; sy = v / l.w }
            nx[i] = sx; ny[i] = sy
        }
        return nx to ny
    }

    /** Patch-Abstand (Summe quadrierter Differenzen) mit Abbruch über [limit]. */
    private fun distance(l: Level, tx: Int, ty: Int, sx: Int, sy: Int, limit: Long): Long {
        val r = patchRadius
        var d = 0L
        for (dy in -r..r) {
            val ty2 = ty + dy; val sy2 = sy + dy
            if (ty2 < 0 || ty2 >= l.h) continue
            for (dx in -r..r) {
                val tx2 = tx + dx
                if (tx2 < 0 || tx2 >= l.w) continue
                val a = l.img[ty2 * l.w + tx2]; val b = l.img[sy2 * l.w + sx + dx]
                val e0 = (a ushr 24) - (b ushr 24); val e1 = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
                val e2 = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF); val e3 = (a and 0xFF) - (b and 0xFF)
                d += e0 * e0 + e1 * e1 + e2 * e2 + e3 * e3
            }
            if (d > limit) return d
        }
        return d
    }

    /** Pixel, deren Patch die Lücke berührt (nur diese brauchen Treffer). */
    private fun targets(l: Level): IntArray {
        val r = patchRadius
        val mark = BooleanArray(l.w * l.h)
        for (y in 0 until l.h) for (x in 0 until l.w) {
            if (!l.hole[y * l.w + x]) continue
            for (yy in max(0, y - r)..min(l.h - 1, y + r)) for (xx in max(0, x - r)..min(l.w - 1, x + r)) mark[yy * l.w + xx] = true
        }
        return mark.indices.filter { mark[it] }.toIntArray()
    }

    private fun patchMatch(l: Level, valid: IntArray, nx: IntArray, ny: IntArray, rnd: Random, iterations: Int) {
        val validSet = BooleanArray(l.w * l.h).also { for (v in valid) it[v] = true }
        val tgt = targets(l)
        val dist = LongArray(l.w * l.h)
        for (i in tgt) dist[i] = distance(l, i % l.w, i / l.w, nx[i], ny[i], Long.MAX_VALUE)
        val maxR = max(l.w, l.h)
        for (iter in 0 until iterations) {
            val forward = iter % 2 == 0
            val step = if (forward) -1 else 1
            val order = if (forward) tgt.indices else tgt.indices.reversed()
            for (k in order) {
                val i = tgt[k]; val x = i % l.w; val y = i / l.w
                var bx = nx[i]; var by = ny[i]; var bd = dist[i]
                // Propagation vom linken/oberen (bzw. rechten/unteren) Nachbarn
                for ((ox, oy) in arrayOf(step to 0, 0 to step)) {
                    val xx = x + ox; val yy = y + oy
                    if (xx < 0 || yy < 0 || xx >= l.w || yy >= l.h) continue
                    val j = yy * l.w + xx
                    val cx = nx[j] - ox; val cy = ny[j] - oy
                    if (!isValid(l, cx, cy, validSet) || (cx == bx && cy == by)) continue
                    val d = distance(l, x, y, cx, cy, bd)
                    if (d < bd) { bd = d; bx = cx; by = cy }
                }
                // Zufallssuche mit schrumpfendem Radius
                var rad = maxR
                while (rad >= 1) {
                    val cx = bx + rnd.nextInt(-rad, rad + 1); val cy = by + rnd.nextInt(-rad, rad + 1)
                    if (isValid(l, cx, cy, validSet)) {
                        val d = distance(l, x, y, cx, cy, bd)
                        if (d < bd) { bd = d; bx = cx; by = cy }
                    }
                    rad /= 2
                }
                nx[i] = bx; ny[i] = by; dist[i] = bd
            }
        }
        lastDist = dist
        lastTargets = tgt
    }

    private var lastDist: LongArray = LongArray(0)
    private var lastTargets: IntArray = IntArray(0)

    /** Jeder Lückenpixel wird zum gewichteten Mittel der überlappenden Treffer-Patches. */
    private fun vote(l: Level, nx: IntArray, ny: IntArray) {
        val r = patchRadius
        val n = l.w * l.h
        val acc = Array(4) { DoubleArray(n) }
        val wsum = DoubleArray(n)
        // Gewicht aus der Trefferqualität (robuster als gleiches Gewicht)
        val dists = lastDist
        val tgt = lastTargets
        var meanD = 0.0
        for (i in tgt) meanD += dists[i].toDouble()
        val sigma2 = max(1.0, if (tgt.isNotEmpty()) meanD / tgt.size else 1.0)
        for (i in tgt) {
            val x = i % l.w; val y = i / l.w
            val wgt = exp(-dists[i] / (2 * sigma2)) + 1e-6
            val sx = nx[i]; val sy = ny[i]
            for (dy in -r..r) {
                val ty = y + dy; if (ty < 0 || ty >= l.h) continue
                for (dx in -r..r) {
                    val tx = x + dx; if (tx < 0 || tx >= l.w) continue
                    val ti = ty * l.w + tx
                    if (!l.hole[ti]) continue
                    val c = l.img[(sy + dy) * l.w + sx + dx]
                    acc[0][ti] += (c ushr 24) * wgt; acc[1][ti] += ((c shr 16) and 0xFF) * wgt
                    acc[2][ti] += ((c shr 8) and 0xFF) * wgt; acc[3][ti] += (c and 0xFF) * wgt
                    wsum[ti] += wgt
                }
            }
        }
        for (i in 0 until n) {
            if (!l.hole[i] || wsum[i] <= 0) continue
            val ws = wsum[i]
            l.img[i] = ((acc[0][i] / ws).toInt().coerceIn(0, 255) shl 24) or ((acc[1][i] / ws).toInt().coerceIn(0, 255) shl 16) or
                ((acc[2][i] / ws).toInt().coerceIn(0, 255) shl 8) or (acc[3][i] / ws).toInt().coerceIn(0, 255)
        }
    }

    private companion object {
        val NEIGHBORS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
