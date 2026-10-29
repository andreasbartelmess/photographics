package de.photographics.core.selection

import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Objektauswahl aus einem Rechteck (GrabCut, Rother et al. 2004): außerhalb des Rechtecks ist
 * Hintergrund, innen wird zwischen Objekt und Hintergrund getrennt – mit je einem Farbmodell
 * (Gaußmischung) und einem Graph-Schnitt, der Kanten mit großem Farbsprung bevorzugt.
 * Gerechnet wird auf einem verkleinerten Ausschnitt; die Maske wird weich auf volle Größe gebracht.
 */
object ObjectSelect {
    private const val K = 5
    private const val GAMMA = 50.0
    private const val WORK_PIXELS = 90_000

    /**
     * [rect]: Rechteck um das Objekt (Dokumentkoordinaten). [sample] liefert ARGB-Pixel eines
     * Ausgabebereichs bei Abtastschritt step (wie Compositor.renderArgbSampled).
     */
    fun select(docW: Int, docH: Int, rect: IntRect, sample: (IntRect, Int) -> IntArray, iterations: Int = 4): Selection {
        val inner = rect.intersect(IntRect.ofSize(docW, docH))
        val mask = TileGrid(docW, docH, 0)
        if (inner.width < 4 || inner.height < 4) return Selection(mask, emptyList())
        // Arbeitsbereich: Rechteck plus Rand (dort lernt das Hintergrundmodell)
        val m = maxOf(8, maxOf(inner.width, inner.height) / 10)
        val area = inner.inset(-m).intersect(IntRect.ofSize(docW, docH))
        val step = maxOf(1, kotlin.math.ceil(sqrt(area.width.toDouble() * area.height / WORK_PIXELS)).toInt())
        val out = IntRect(area.left / step, area.top / step, (area.right + step - 1) / step, (area.bottom + step - 1) / step)
        val w = out.width; val h = out.height; val n = w * h
        val px = sample(out, step)
        val z = Array(3) { c -> DoubleArray(n) { i -> ((px[i] shr (16 - 8 * c)) and 0xFF).toDouble() } }
        // Trimap: true = sicher Hintergrund (außerhalb des Rechtecks oder am Bildrand des Rechtecks)
        val fixedBg = BooleanArray(n) { i ->
            val x = (out.left + i % w) * step + step / 2; val y = (out.top + i / w) * step + step / 2
            !(x >= inner.left && x < inner.right && y >= inner.top && y < inner.bottom)
        }
        if (fixedBg.all { it }) return Selection(mask, emptyList())
        var fg = BooleanArray(n) { !fixedBg[it] }

        // Glattheit: 8 Nachbarn, Gewicht nach Farbabstand
        val nb = intArrayOf(1, 0, 0, 1, 1, 1, -1, 1)   // (dx, dy) rechts, unten, rechts unten, links unten
        var sum = 0.0; var cnt = 0
        for (y in 0 until h) for (x in 0 until w) for (k in 0 until 4) {
            val x2 = x + nb[k * 2]; val y2 = y + nb[k * 2 + 1]
            if (x2 !in 0 until w || y2 >= h) continue
            sum += dist2(z, y * w + x, y2 * w + x2); cnt++
        }
        val beta = if (sum > 0) 1.0 / (2.0 * sum / cnt) else 0.0
        // Kantengewichte hängen nur vom Bild ab – einmal statt in jeder Iteration rechnen
        val edgeW = DoubleArray(n * 4)
        for (y in 0 until h) for (x in 0 until w) for (k in 0 until 4) {
            val x2 = x + nb[k * 2]; val y2 = y + nb[k * 2 + 1]
            if (x2 !in 0 until w || y2 >= h) continue
            val a = y * w + x
            edgeW[a * 4 + k] = GAMMA / (if (k < 2) 1.0 else 1.41421356) * exp(-beta * dist2(z, a, y2 * w + x2))
        }

        repeat(iterations) {
            val fgModel = Gmm.fit(z, n, fg, true) ?: return Selection(mask, emptyList())
            val bgModel = Gmm.fit(z, n, fg, false) ?: return Selection(mask, emptyList())
            val g = Graph(n + 2, n * 6)
            val s = n; val t = n + 1
            for (i in 0 until n) {
                if (fixedBg[i]) { g.add(i, t, 1e9, 0.0); continue }
                val dFg = -ln(maxOf(1e-300, fgModel.prob(z, i)))
                val dBg = -ln(maxOf(1e-300, bgModel.prob(z, i)))
                g.add(s, i, dBg, 0.0)   // durchtrennt → Pixel wird Hintergrund
                g.add(i, t, dFg, 0.0)   // durchtrennt → Pixel wird Objekt
            }
            for (y in 0 until h) for (x in 0 until w) for (k in 0 until 4) {
                val x2 = x + nb[k * 2]; val y2 = y + nb[k * 2 + 1]
                if (x2 !in 0 until w || y2 >= h) continue
                val a = y * w + x; val b = y2 * w + x2
                val wgt = edgeW[a * 4 + k]
                g.add(a, b, wgt, wgt)
            }
            g.maxFlow(s, t)
            val reach = g.sourceSide(s)
            fg = BooleanArray(n) { !fixedBg[it] && reach[it] }
        }

        // Auf volle Auflösung: bilinear aus der groben Maske, Kante leicht weich
        val band = TileGrid.TILE
        var y0 = area.top
        while (y0 < area.bottom) {
            val r = IntRect(area.left, y0, area.right, minOf(area.bottom, y0 + band))
            val buf = IntArray(r.width * r.height)
            var any = false
            for (yy in r.top until r.bottom) {
                val fy = ((yy + 0.5) / step - 0.5 - out.top).coerceIn(0.0, (h - 1).toDouble())
                val j0 = fy.toInt(); val j1 = minOf(h - 1, j0 + 1); val ty = fy - j0
                for (xx in r.left until r.right) {
                    val fx = ((xx + 0.5) / step - 0.5 - out.left).coerceIn(0.0, (w - 1).toDouble())
                    val i0 = fx.toInt(); val i1 = minOf(w - 1, i0 + 1); val tx = fx - i0
                    fun v(i: Int, j: Int) = if (fg[j * w + i]) 1.0 else 0.0
                    val top = v(i0, j0) * (1 - tx) + v(i1, j0) * tx
                    val bot = v(i0, j1) * (1 - tx) + v(i1, j1) * tx
                    val a = top * (1 - ty) + bot * ty
                    // Übergang auf etwa ein Pixel zusammenziehen (bei Schritt 1 unverändert hart)
                    val c = ((a - 0.5) * maxOf(1.0, step.toDouble()) + 0.5).coerceIn(0.0, 1.0)
                    val o = (c * 255 + 0.5).toInt()
                    if (o != 0) any = true
                    buf[(yy - r.top) * r.width + xx - r.left] = o
                }
            }
            if (any) mask.writeRect(r, buf)
            y0 = r.bottom
        }
        mask.compact()
        return Selection(mask, Selection.outlinesOf(mask))
    }

    private fun dist2(z: Array<DoubleArray>, a: Int, b: Int): Double {
        val d0 = z[0][a] - z[0][b]; val d1 = z[1][a] - z[1][b]; val d2 = z[2][a] - z[2][b]
        return d0 * d0 + d1 * d1 + d2 * d2
    }

    /** Gaußmischung mit [K] Komponenten (volle 3×3-Kovarianz), Start über k-Means. */
    private class Gmm(val weight: DoubleArray, val mean: Array<DoubleArray>, val inv: Array<DoubleArray>, val norm: DoubleArray) {
        fun prob(z: Array<DoubleArray>, i: Int): Double {
            var p = 0.0
            for (k in 0 until K) {
                if (weight[k] <= 0) continue
                val d0 = z[0][i] - mean[k][0]; val d1 = z[1][i] - mean[k][1]; val d2 = z[2][i] - mean[k][2]
                val c = inv[k]
                val q = d0 * (c[0] * d0 + c[1] * d1 + c[2] * d2) + d1 * (c[3] * d0 + c[4] * d1 + c[5] * d2) + d2 * (c[6] * d0 + c[7] * d1 + c[8] * d2)
                p += weight[k] * norm[k] * exp(-0.5 * q)
            }
            return p
        }

        companion object {
            /** Modell über alle Pixel mit `mask[i] == want`. */
            fun fit(z: Array<DoubleArray>, n: Int, mask: BooleanArray, want: Boolean): Gmm? {
                // Primitive Felder statt List<Int>: auf Android kostet das Boxen hier Sekunden
                var cnt = 0
                for (i in 0 until n) if (mask[i] == want) cnt++
                if (cnt < K) return null
                val idx = IntArray(cnt)
                run { var j = 0; for (i in 0 until n) if (mask[i] == want) idx[j++] = i }
                // k-Means: Startzentren gleichmäßig über die nach Helligkeit sortierten Pixel
                val keys = LongArray(cnt) { j -> val i = idx[j]; ((z[0][i] + z[1][i] + z[2][i]).toLong() shl 32) or i.toLong() }
                keys.sort()
                val centers = Array(K) { k -> val i = (keys[(k * 2 + 1) * cnt / (2 * K)] and 0xFFFFFFFFL).toInt(); doubleArrayOf(z[0][i], z[1][i], z[2][i]) }
                val label = IntArray(cnt)
                repeat(6) {
                    for (j in 0 until cnt) {
                        val i = idx[j]
                        var best = 0; var bd = Double.MAX_VALUE
                        for (k in 0 until K) {
                            val d0 = z[0][i] - centers[k][0]; val d1 = z[1][i] - centers[k][1]; val d2 = z[2][i] - centers[k][2]
                            val d = d0 * d0 + d1 * d1 + d2 * d2
                            if (d < bd) { bd = d; best = k }
                        }
                        label[j] = best
                    }
                    val acc = Array(K) { DoubleArray(4) }
                    for (j in 0 until cnt) { val i = idx[j]; val a = acc[label[j]]; a[0] += z[0][i]; a[1] += z[1][i]; a[2] += z[2][i]; a[3]++ }
                    for (k in 0 until K) if (acc[k][3] > 0) for (c in 0..2) centers[k][c] = acc[k][c] / acc[k][3]
                }
                val weight = DoubleArray(K); val inv = Array(K) { DoubleArray(9) }; val norm = DoubleArray(K)
                for (k in 0 until K) {
                    var cntK = 0; val cov = DoubleArray(9)
                    for (j in 0 until cnt) {
                        if (label[j] != k) continue
                        val i = idx[j]
                        cntK++
                        val d0 = z[0][i] - centers[k][0]; val d1 = z[1][i] - centers[k][1]; val d2 = z[2][i] - centers[k][2]
                        cov[0] += d0 * d0; cov[1] += d0 * d1; cov[2] += d0 * d2
                        cov[4] += d1 * d1; cov[5] += d1 * d2; cov[8] += d2 * d2
                    }
                    if (cntK == 0) continue
                    cov[3] = cov[1]; cov[6] = cov[2]; cov[7] = cov[5]
                    weight[k] = cntK.toDouble() / cnt
                    for (q in 0..8) cov[q] /= cntK
                    for (q in intArrayOf(0, 4, 8)) cov[q] += 4.0   // Regularisierung (Farbe 0..255)
                    val det = cov[0] * (cov[4] * cov[8] - cov[5] * cov[7]) - cov[1] * (cov[3] * cov[8] - cov[5] * cov[6]) + cov[2] * (cov[3] * cov[7] - cov[4] * cov[6])
                    if (det <= 1e-12) { weight[k] = 0.0; continue }
                    val iv = inv[k]
                    iv[0] = (cov[4] * cov[8] - cov[5] * cov[7]) / det; iv[1] = (cov[2] * cov[7] - cov[1] * cov[8]) / det; iv[2] = (cov[1] * cov[5] - cov[2] * cov[4]) / det
                    iv[3] = (cov[5] * cov[6] - cov[3] * cov[8]) / det; iv[4] = (cov[0] * cov[8] - cov[2] * cov[6]) / det; iv[5] = (cov[2] * cov[3] - cov[0] * cov[5]) / det
                    iv[6] = (cov[3] * cov[7] - cov[4] * cov[6]) / det; iv[7] = (cov[1] * cov[6] - cov[0] * cov[7]) / det; iv[8] = (cov[0] * cov[4] - cov[1] * cov[3]) / det
                    norm[k] = 1.0 / sqrt(Math.pow(2 * Math.PI, 3.0) * det)
                }
                return Gmm(weight, centers, inv, norm)
            }
        }
    }

    /** Fluss-Graph (Dinic, ohne Rekursion). */
    private class Graph(val nodes: Int, edgeHint: Int) {
        private var to = IntArray(edgeHint * 2); private var cap = DoubleArray(edgeHint * 2); private var next = IntArray(edgeHint * 2)
        private val head = IntArray(nodes) { -1 }
        private var count = 0

        fun add(a: Int, b: Int, c: Double, rev: Double) {
            if (count + 2 > to.size) { to = to.copyOf(to.size * 2); cap = cap.copyOf(cap.size * 2); next = next.copyOf(next.size * 2) }
            to[count] = b; cap[count] = c; next[count] = head[a]; head[a] = count++
            to[count] = a; cap[count] = rev; next[count] = head[b]; head[b] = count++
        }

        private val level = IntArray(nodes)
        private val iter = IntArray(nodes)

        private fun bfs(s: Int, t: Int): Boolean {
            java.util.Arrays.fill(level, -1)
            val q = IntArray(nodes); var qh = 0; var qt = 0
            level[s] = 0; q[qt++] = s
            while (qh < qt) {
                val v = q[qh++]
                var e = head[v]
                while (e != -1) {
                    if (cap[e] > 1e-12 && level[to[e]] < 0) { level[to[e]] = level[v] + 1; q[qt++] = to[e] }
                    e = next[e]
                }
            }
            return level[t] >= 0
        }

        fun maxFlow(s: Int, t: Int): Double {
            var flow = 0.0
            val pathE = IntArray(nodes)
            while (bfs(s, t)) {
                System.arraycopy(head, 0, iter, 0, nodes)
                // Augmentierende Wege im Stufengraphen, Tiefensuche mit eigenem Stapel
                while (true) {
                    var depth = 0; var v = s
                    while (v != t) {
                        var e = iter[v]
                        while (e != -1 && !(cap[e] > 1e-12 && level[to[e]] == level[v] + 1)) e = next[e]
                        iter[v] = e
                        if (e == -1) {
                            if (depth == 0) { v = -1; break }
                            level[v] = -1                    // Sackgasse
                            depth--; v = to[pathE[depth] xor 1]
                            iter[v] = next[iter[v]]
                            continue
                        }
                        pathE[depth++] = e; v = to[e]
                    }
                    if (v == -1) break
                    var f = Double.MAX_VALUE
                    for (k in 0 until depth) f = minOf(f, cap[pathE[k]])
                    for (k in 0 until depth) { cap[pathE[k]] -= f; cap[pathE[k] xor 1] += f }
                    flow += f
                }
            }
            return flow
        }

        /** Knoten, die nach dem Schnitt noch von [s] erreichbar sind. */
        fun sourceSide(s: Int): BooleanArray {
            val seen = BooleanArray(nodes)
            val q = IntArray(nodes); var qh = 0; var qt = 0
            seen[s] = true; q[qt++] = s
            while (qh < qt) {
                val v = q[qh++]
                var e = head[v]
                while (e != -1) { if (cap[e] > 1e-12 && !seen[to[e]]) { seen[to[e]] = true; q[qt++] = to[e] }; e = next[e] }
            }
            return seen
        }
    }
}
