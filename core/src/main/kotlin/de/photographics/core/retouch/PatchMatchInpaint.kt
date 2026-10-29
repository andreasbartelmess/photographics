package de.photographics.core.retouch

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Füllt markierte Bereiche eines Bildausschnitts (z. B. KI-Modell oder PatchMatch). */
fun interface Inpainter {
    /** [px]: ARGB, [hole]: true = zu füllen. Liefert das vollständige Bild zurück. */
    fun inpaint(px: IntArray, w: Int, h: Int, hole: BooleanArray): IntArray

    /** Größter Ausschnitt (Pixel), den das Verfahren in voller Auflösung rechnet; größere werden vorher verkleinert. */
    val maxPixels: Int get() = 360_000
}

/**
 * Inhaltsbasiertes Füllen nach Wexler/Barnes mit den Verbesserungen von Newson et al. (2014):
 * - Bildpyramide, bis die Lücke auf der gröbsten Stufe nur noch wenige Pixel tief ist;
 *   dort Zwiebelschalen-Start (von außen nach innen, je Pixel bester Treffer der bekannten Nachbarschaft)
 *   statt glattem Vorfüllen – sonst gewinnen flache Bildstücke und die Füllung wird ein Farbbrei.
 * - Texturmerkmale (gemittelte Helligkeitsgradienten) im Patch-Abstand: strukturierte Lücken
 *   suchen strukturierte Quellen; die Merkmale werden in der Lücke mit abgestimmt.
 * - Abstimmung gewichtet nach Trefferqualität (75-%-Quantil), auf der feinsten Stufe zuletzt
 *   „bester Treffer“ statt Mittelwert – das hält die Füllung scharf.
 */
class PatchMatchInpainter(
    private val patchRadius: Int = 3,
    private val seed: Int = 1,
) : Inpainter {
    override val maxPixels: Int get() = 800_000

    /** Eine Pyramidenstufe; [f] enthält je Pixel [C] Merkmale (R, G, B, A, Textur x, Textur y). */
    private class Level(val w: Int, val h: Int, val f: FloatArray, val hole: BooleanArray)

    override fun inpaint(px: IntArray, w: Int, h: Int, hole: BooleanArray): IntArray {
        if (hole.none { it }) return px.copyOf()
        val rnd = Random(seed)
        val levels = ArrayList<Level>()
        levels += Level(w, h, FloatArray(w * h * C).also { f ->
            for (i in 0 until w * h) {
                val c = px[i]
                f[i * C] = ((c shr 16) and 0xFF).toFloat(); f[i * C + 1] = ((c shr 8) and 0xFF).toFloat()
                f[i * C + 2] = (c and 0xFF).toFloat(); f[i * C + 3] = (c ushr 24).toFloat()
            }
        }, hole.copyOf())
        // Vergröbern, bis die Lücke höchstens etwa eine Patchbreite tief ist
        while (levels.size < 10) {
            val l = levels.last()
            if (holeDepth(l) <= 2 * patchRadius) break
            if (min(l.w, l.h) / 2 < 3 * (2 * patchRadius + 1)) break
            val next = downsample(l)
            if (countValid(next) < 16) break
            levels += next
        }
        for (l in levels) texture(l)

        var nnfX = IntArray(0); var nnfY = IntArray(0); var prevW = 0; var prevH = 0
        for (li in levels.indices.reversed()) {
            val lv = levels[li]
            val validSet = validSources(lv)
            val valid = validSet.indices.filter { validSet[it] }.toIntArray()
            if (valid.isEmpty()) { smoothFill(lv); nnfX = IntArray(0); continue }
            val tgt = targets(lv)
            val nx = IntArray(lv.w * lv.h) { -1 }; val ny = IntArray(lv.w * lv.h) { -1 }
            if (li == levels.size - 1 || nnfX.isEmpty()) {
                onionPeel(lv, validSet, valid, nx, ny, rnd)
                for (i in tgt) if (nx[i] < 0) { val v = valid[rnd.nextInt(valid.size)]; nx[i] = v % lv.w; ny[i] = v / lv.w }
            } else {
                // Trefferfeld der gröberen Stufe hochrechnen und die Lücke damit gleichgewichtet vorbelegen
                for (i in tgt) {
                    val x = i % lv.w; val y = i / lv.w
                    val ci = min(y / 2, prevH - 1) * prevW + min(x / 2, prevW - 1)
                    var cx = -1; var cy = -1
                    if (nnfX[ci] >= 0) { cx = nnfX[ci] * 2 + (x and 1); cy = nnfY[ci] * 2 + (y and 1) }
                    if (!isValid(lv, cx, cy, validSet)) { val v = valid[rnd.nextInt(valid.size)]; cx = v % lv.w; cy = v / lv.w }
                    nx[i] = cx; ny[i] = cy
                }
                vote(lv, tgt, nx, ny, null, best = false)
            }
            val finest = li == 0
            val emIters = when { finest -> 2; li == levels.size - 1 -> 5; else -> 3 }
            val dist = FloatArray(lv.w * lv.h)
            repeat(emIters) { e ->
                patchMatch(lv, validSet, tgt, nx, ny, dist, rnd, iterations = 2)
                vote(lv, tgt, nx, ny, dist, best = finest && e == emIters - 1)
            }
            nnfX = nx; nnfY = ny; prevW = lv.w; prevH = lv.h
        }
        matchBoundary(levels[0])
        val f = levels[0].f
        return IntArray(w * h) { i ->
            if (!hole[i]) px[i] else {
                fun ch(k: Int) = (f[i * C + k] + 0.5f).toInt().coerceIn(0, 255)
                (ch(3) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
            }
        }
    }

    /**
     * Gleicht Farbe/Helligkeit der Füllung an die Umgebung an, ohne ihre Textur anzutasten: am Rand
     * der Lücke wird der Unterschied der lokalen Mittelwerte (bekannt gegen gefüllt, Radius [EDGE_R])
     * gemessen und glatt ins Innere interpoliert (wie eine Membran beim Poisson-Einsetzen).
     */
    private fun matchBoundary(l: Level) {
        val w = l.w; val h = l.h; val n = w * h; val r = EDGE_R
        val depth = distanceToKnown(l)
        val off = FloatArray(n * 4); val wt = FloatArray(n)
        var any = false
        val sk = FloatArray(4); val sf = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            // Nur im Randstreifen messen (direkt über das Fenster); das Innere wird interpoliert
            if (!l.hole[i] || depth[i] > r) continue
            sk.fill(0f); sf.fill(0f)
            var nk = 0; var nf = 0
            for (yy in max(0, y - r)..min(h - 1, y + r)) for (xx in max(0, x - r)..min(w - 1, x + r)) {
                val j = yy * w + xx
                val acc = if (l.hole[j]) { nf++; sf } else { nk++; sk }
                for (k in 0..3) acc[k] += l.f[j * C + k]
            }
            if (nk < r || nf < 1) continue
            for (k in 0..3) off[i * 4 + k] = sk[k] / nk - sf[k] / nf
            wt[i] = 1f; any = true
        }
        if (!any) return
        de.photographics.core.tools.PullPush.fill(off, wt, w, h)
        for (i in 0 until n) {
            if (!l.hole[i]) continue
            for (k in 0..3) l.f[i * C + k] = (l.f[i * C + k] + off[i * 4 + k]).coerceIn(0f, 255f)
        }
    }

    /** Größte Entfernung (Schachbrett-Metrik) eines Lückenpixels zum nächsten bekannten Pixel. */
    private fun holeDepth(l: Level): Int = distanceToKnown(l).max()

    /** Je Pixel die Entfernung (Schachbrett-Metrik) zum nächsten bekannten Pixel; bekannte Pixel 0. */
    private fun distanceToKnown(l: Level): IntArray {
        val w = l.w; val h = l.h
        val d = IntArray(w * h) { if (l.hole[it]) Int.MAX_VALUE / 2 else 0 }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0) continue
            var m = d[i]
            if (x > 0) m = min(m, d[i - 1] + 1)
            if (y > 0) { m = min(m, d[i - w] + 1); if (x > 0) m = min(m, d[i - w - 1] + 1); if (x < w - 1) m = min(m, d[i - w + 1] + 1) }
            d[i] = m
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0) continue
            var m = d[i]
            if (x < w - 1) m = min(m, d[i + 1] + 1)
            if (y < h - 1) { m = min(m, d[i + w] + 1); if (x < w - 1) m = min(m, d[i + w + 1] + 1); if (x > 0) m = min(m, d[i + w - 1] + 1) }
            d[i] = m
        }
        return d
    }

    private fun downsample(l: Level): Level {
        val nw = (l.w + 1) / 2; val nh = (l.h + 1) / 2
        val f = FloatArray(nw * nh * C)
        val hole = BooleanArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            var n = 0; var anyHole = false
            val o = (y * nw + x) * C
            for (dy in 0..1) for (dx in 0..1) {
                val sx = 2 * x + dx; val sy = 2 * y + dy
                if (sx >= l.w || sy >= l.h) continue
                val i = sy * l.w + sx
                if (l.hole[i]) { anyHole = true; continue }
                for (k in 0..3) f[o + k] += l.f[i * C + k]
                n++
            }
            hole[y * nw + x] = anyHole || n == 0
            if (n > 0) for (k in 0..3) f[o + k] /= n
        }
        return Level(nw, nh, f, hole)
    }

    /** Texturmerkmale bekannter Pixel: Betrag der Helligkeitsgradienten, über 5×5 gemittelt. */
    private fun texture(l: Level) {
        val w = l.w; val h = l.h; val n = w * h
        val lum = FloatArray(n) { i -> 0.299f * l.f[i * C] + 0.587f * l.f[i * C + 1] + 0.114f * l.f[i * C + 2] }
        val gx = FloatArray(n); val gy = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (l.hole[i]) continue
            fun at(xx: Int, yy: Int): Float {
                val j = yy * w + xx
                return if (xx < 0 || yy < 0 || xx >= w || yy >= h || l.hole[j]) lum[i] else lum[j]
            }
            gx[i] = abs(at(x + 1, y) - at(x - 1, y)) * 0.5f
            gy[i] = abs(at(x, y + 1) - at(x, y - 1)) * 0.5f
        }
        // Kastenmittel nur über bekannte Pixel (getrennt horizontal/vertikal)
        val r = 2
        fun box(src: FloatArray, k: Int) {
            val tmp = FloatArray(n); val cnt = FloatArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var c = 0f
                for (xx in max(0, x - r)..min(w - 1, x + r)) { val j = y * w + xx; if (!l.hole[j]) { s += src[j]; c++ } }
                tmp[y * w + x] = s; cnt[y * w + x] = c
            }
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (l.hole[i]) { l.f[i * C + k] = 0f; continue }
                var s = 0f; var c = 0f
                for (yy in max(0, y - r)..min(h - 1, y + r)) { s += tmp[yy * w + x]; c += cnt[yy * w + x] }
                l.f[i * C + k] = if (c > 0) s / c else 0f
            }
        }
        box(gx, 4); box(gy, 5)
    }

    /** Gültige Quell-Patch-Mitten: Patch komplett im Bild und ohne Lückenpixel. */
    private fun validSources(l: Level): BooleanArray {
        val r = patchRadius
        val w = l.w; val h = l.h
        val sat = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) for (x in 0 until w) {
            sat[(y + 1) * (w + 1) + x + 1] = (if (l.hole[y * w + x]) 1 else 0) +
                sat[y * (w + 1) + x + 1] + sat[(y + 1) * (w + 1) + x] - sat[y * (w + 1) + x]
        }
        val out = BooleanArray(w * h)
        for (y in r until h - r) for (x in r until w - r) {
            val x0 = x - r; val y0 = y - r; val x1 = x + r + 1; val y1 = y + r + 1
            if (sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0] == 0) out[y * w + x] = true
        }
        return out
    }

    private fun countValid(l: Level) = validSources(l).count { it }

    private fun isValid(l: Level, x: Int, y: Int, validSet: BooleanArray): Boolean =
        x >= 0 && y >= 0 && x < l.w && y < l.h && validSet[y * l.w + x]

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

    /** Patch-Abstand (gewichtete Quadratsumme aller Merkmale) mit Abbruch über [limit]. */
    private fun distance(l: Level, tx: Int, ty: Int, sx: Int, sy: Int, limit: Float): Float {
        val r = patchRadius; val f = l.f; val w = l.w
        var d = 0f
        for (dy in -r..r) {
            val ty2 = ty + dy
            if (ty2 < 0 || ty2 >= l.h) continue
            val trow = ty2 * w; val srow = (sy + dy) * w
            for (dx in -r..r) {
                val tx2 = tx + dx
                if (tx2 < 0 || tx2 >= w) continue
                val a = (trow + tx2) * C; val b = (srow + sx + dx) * C
                val e0 = f[a] - f[b]; val e1 = f[a + 1] - f[b + 1]; val e2 = f[a + 2] - f[b + 2]; val e3 = f[a + 3] - f[b + 3]
                val e4 = f[a + 4] - f[b + 4]; val e5 = f[a + 5] - f[b + 5]
                d += e0 * e0 + e1 * e1 + e2 * e2 + e3 * e3 + TEX * (e4 * e4 + e5 * e5)
            }
            if (d > limit) return d
        }
        return d
    }

    /** Wie [distance], aber nur über bekannte Zielpixel, gemittelt (für den Zwiebelschalen-Start). */
    private fun maskedDistance(l: Level, known: BooleanArray, tx: Int, ty: Int, sx: Int, sy: Int): Float {
        val r = patchRadius; val f = l.f; val w = l.w
        var d = 0f; var n = 0
        for (dy in -r..r) {
            val ty2 = ty + dy
            if (ty2 < 0 || ty2 >= l.h) continue
            for (dx in -r..r) {
                val tx2 = tx + dx
                if (tx2 < 0 || tx2 >= w || !known[ty2 * w + tx2]) continue
                val a = (ty2 * w + tx2) * C; val b = ((sy + dy) * w + sx + dx) * C
                val e0 = f[a] - f[b]; val e1 = f[a + 1] - f[b + 1]; val e2 = f[a + 2] - f[b + 2]; val e3 = f[a + 3] - f[b + 3]
                val e4 = f[a + 4] - f[b + 4]; val e5 = f[a + 5] - f[b + 5]
                d += e0 * e0 + e1 * e1 + e2 * e2 + e3 * e3 + TEX * (e4 * e4 + e5 * e5)
                n++
            }
        }
        return if (n == 0) Float.MAX_VALUE else d / n
    }

    /**
     * Gröbste Stufe: Lücke von außen nach innen ringweise füllen. Jeder Pixel des Rings sucht den
     * Quell-Patch, der zu seiner schon bekannten Nachbarschaft am besten passt, und übernimmt dessen Mitte.
     */
    private fun onionPeel(l: Level, validSet: BooleanArray, valid: IntArray, nx: IntArray, ny: IntArray, rnd: Random) {
        val w = l.w; val h = l.h
        val known = BooleanArray(w * h) { !l.hole[it] }
        val maxR = max(w, h)
        while (true) {
            val ring = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (known[i]) continue
                var edge = false
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h && known[yy * w + xx]) edge = true
                }
                if (edge) ring += i
            }
            if (ring.isEmpty()) break
            for (i in ring) {
                val x = i % w; val y = i / w
                var bx = -1; var by = -1; var bd = Float.MAX_VALUE
                fun tryC(cx: Int, cy: Int) {
                    if (!isValid(l, cx, cy, validSet) || (cx == bx && cy == by)) return
                    val d = maskedDistance(l, known, x, y, cx, cy)
                    if (d < bd) { bd = d; bx = cx; by = cy }
                }
                // Kohärenz: Nachbarn mit Treffer schlagen die passende Verschiebung vor
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val j = yy * w + xx
                    if (nx[j] >= 0) tryC(nx[j] - dx, ny[j] - dy)
                }
                repeat(min(valid.size, 48)) { val v = valid[rnd.nextInt(valid.size)]; tryC(v % w, v / w) }
                var rad = maxR / 2
                while (rad >= 1 && bx >= 0) { tryC(bx + rnd.nextInt(-rad, rad + 1), by + rnd.nextInt(-rad, rad + 1)); rad /= 2 }
                if (bx < 0) { val v = valid[rnd.nextInt(valid.size)]; bx = v % w; by = v / w }
                nx[i] = bx; ny[i] = by
            }
            for (i in ring) {
                val s = (ny[i] * w + nx[i]) * C
                System.arraycopy(l.f, s, l.f, i * C, C)
                known[i] = true
            }
        }
    }

    private fun patchMatch(l: Level, validSet: BooleanArray, tgt: IntArray, nx: IntArray, ny: IntArray, dist: FloatArray, rnd: Random, iterations: Int) {
        for (i in tgt) dist[i] = distance(l, i % l.w, i / l.w, nx[i], ny[i], Float.MAX_VALUE)
        val maxR = max(l.w, l.h)
        for (iter in 0 until iterations) {
            val forward = iter % 2 == 0
            val step = if (forward) -1 else 1
            var k = if (forward) 0 else tgt.size - 1
            while (k in tgt.indices) {
                val i = tgt[k]; val x = i % l.w; val y = i / l.w
                k += if (forward) 1 else -1
                var bx = nx[i]; var by = ny[i]; var bd = dist[i]
                // Propagation vom linken/oberen (bzw. rechten/unteren) Nachbarn
                for (o in 0..1) {
                    val ox = if (o == 0) step else 0; val oy = if (o == 0) 0 else step
                    val xx = x + ox; val yy = y + oy
                    if (xx < 0 || yy < 0 || xx >= l.w || yy >= l.h) continue
                    val j = yy * l.w + xx
                    if (nx[j] < 0) continue
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
    }

    /**
     * Jeder Lückenpixel wird aus den überlappenden Treffer-Patches neu bestimmt: gewichtetes Mittel
     * (Gewicht aus [dist], ohne [dist] gleichgewichtet) oder bei [best] der Beitrag mit dem größten Gewicht.
     */
    private fun vote(l: Level, tgt: IntArray, nx: IntArray, ny: IntArray, dist: FloatArray?, best: Boolean) {
        val r = patchRadius; val w = l.w; val h = l.h
        val n = w * h
        var sigma2 = 1f
        if (dist != null && tgt.isNotEmpty()) {
            val sample = FloatArray(min(tgt.size, 4096)) { dist[tgt[(it.toLong() * tgt.size / min(tgt.size, 4096)).toInt()]] }
            sample.sort()
            sigma2 = max(1f, sample[(sample.size * 3) / 4])
        }
        val acc = if (best) FloatArray(0) else FloatArray(n * C)
        val wsum = FloatArray(n)
        val bestSrc = if (best) IntArray(n) { -1 } else IntArray(0)
        for (i in tgt) {
            val x = i % w; val y = i / w
            val wgt = if (dist == null) 1f else exp(-dist[i] / (2 * sigma2)) + 1e-6f
            val sx = nx[i]; val sy = ny[i]
            for (dy in -r..r) {
                val ty = y + dy; if (ty < 0 || ty >= h) continue
                for (dx in -r..r) {
                    val tx = x + dx; if (tx < 0 || tx >= w) continue
                    val ti = ty * w + tx
                    if (!l.hole[ti]) continue
                    val si = (sy + dy) * w + sx + dx
                    if (best) {
                        if (wgt > wsum[ti]) { wsum[ti] = wgt; bestSrc[ti] = si }
                    } else {
                        val a = ti * C; val b = si * C
                        for (k in 0 until C) acc[a + k] += l.f[b + k] * wgt
                        wsum[ti] += wgt
                    }
                }
            }
        }
        for (i in 0 until n) {
            if (!l.hole[i] || wsum[i] <= 0f) continue
            if (best) System.arraycopy(l.f, bestSrc[i] * C, l.f, i * C, C)
            else { val ws = wsum[i]; for (k in 0 until C) l.f[i * C + k] = acc[i * C + k] / ws }
        }
    }

    /** Notlösung ohne gültige Quellen: Lücke durch wiederholtes Mitteln der Nachbarn glatt füllen. */
    private fun smoothFill(l: Level) {
        val known = BooleanArray(l.w * l.h) { !l.hole[it] }
        var changed = true
        while (changed) {
            changed = false
            val next = known.copyOf()
            for (y in 0 until l.h) for (x in 0 until l.w) {
                val i = y * l.w + x
                if (known[i]) continue
                val acc = FloatArray(C); var n = 0
                for ((dx, dy) in NEIGHBORS) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= l.w || yy >= l.h) continue
                    val j = yy * l.w + xx
                    if (!known[j]) continue
                    for (k in 0 until C) acc[k] += l.f[j * C + k]
                    n++
                }
                if (n > 0) { for (k in 0 until C) l.f[i * C + k] = acc[k] / n; next[i] = true; changed = true }
            }
            System.arraycopy(next, 0, known, 0, known.size)
        }
    }

    private companion object {
        const val C = 6
        /** Gewicht der Texturmerkmale gegenüber den Farbkanälen. */
        const val TEX = 2f
        /** Fenster (Radius) für den Farbabgleich am Lückenrand. */
        const val EDGE_R = 4
        val NEIGHBORS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
