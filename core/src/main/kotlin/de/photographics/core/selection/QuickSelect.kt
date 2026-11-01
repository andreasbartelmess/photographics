package de.photographics.core.selection

import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Schnellauswahl: entlang eines Pinselstrichs wächst die Auswahl in Bereiche ähnlicher Farbe. Je
 * Abdruck werden Mittelwert und Streuung der Farben unter dem Pinsel bestimmt; von dort wächst die
 * Auswahl bis zu [REACH] Pinselradien weit, solange die Farbe innerhalb der Toleranz bleibt – so
 * folgt sie Kanten, ohne in ähnliche, weit entfernte Flächen auszulaufen.
 */
object QuickSelect {
    /** Reichweite um jeden Abdruck in Pinselradien. */
    private const val REACH = 3f

    /** [points] als (x0, y0, x1, y1, …) in Dokumentkoordinaten, [radius] Pinselradius. */
    fun select(src: TileGrid, points: FloatArray, radius: Float): Selection {
        val w = src.width; val h = src.height
        val mask = TileGrid(w, h, 0)
        val r = radius.coerceAtLeast(1f)
        // Abdrücke im Abstand eines halben Radius entlang des Strichs
        val dabs = ArrayList<Pair<Float, Float>>()
        for (i in 0 until points.size / 2) {
            val x = points[i * 2]; val y = points[i * 2 + 1]
            if (dabs.isEmpty()) { dabs += x to y; continue }
            val (px, py) = dabs.last()
            val d = hypot(x - px, y - py)
            val n = (d / (r * 0.5f)).toInt()
            for (k in 1..n) dabs += (px + (x - px) * k / (n + 1)) to (py + (y - py) * k / (n + 1))
            dabs += x to y
        }
        for ((cx, cy) in dabs) grow(src, mask, cx, cy, r)
        mask.compact()
        return Selection(mask, Selection.outlinesOf(mask))
    }

    private fun grow(src: TileGrid, mask: TileGrid, cx: Float, cy: Float, r: Float) {
        val reach = r * REACH
        val win = IntRect((cx - reach).toInt(), (cy - reach).toInt(), (cx + reach).toInt() + 1, (cy + reach).toInt() + 1)
            .intersect(IntRect.ofSize(src.width, src.height))
        if (win.isEmpty) return
        val ww = win.width; val wh = win.height
        val px = IntArray(ww * wh); src.readRect(win, px)
        val sel = IntArray(ww * wh); mask.readRect(win, sel)
        // Farbstatistik unter dem Pinsel
        var n = 0; var sr = 0L; var sg = 0L; var sb = 0L
        val inBrush = BooleanArray(ww * wh)
        for (j in 0 until wh) for (i in 0 until ww) {
            if (hypot(win.left + i + 0.5f - cx, win.top + j + 0.5f - cy) > r) continue
            val c = px[j * ww + i]
            inBrush[j * ww + i] = true
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
        }
        if (n == 0) return
        val mr = (sr / n).toInt(); val mg = (sg / n).toInt(); val mb = (sb / n).toInt()
        var dev = 0L
        for (k in px.indices) if (inBrush[k]) {
            val c = px[k]
            dev += maxOf(abs(((c shr 16) and 0xFF) - mr), abs(((c shr 8) and 0xFF) - mg), abs((c and 0xFF) - mb))
        }
        val tol = (dev / n * 2 + 14).toInt().coerceIn(14, 70)
        fun ok(c: Int) = abs(((c shr 16) and 0xFF) - mr) <= tol && abs(((c shr 8) and 0xFF) - mg) <= tol && abs((c and 0xFF) - mb) <= tol
        // Wachsen ab allen passenden Pixeln unter dem Pinsel, nur innerhalb der Reichweite
        val seen = BooleanArray(ww * wh)
        val queue = IntArray(ww * wh); var head = 0; var tail = 0
        for (k in px.indices) if (inBrush[k] && ok(px[k])) { seen[k] = true; queue[tail++] = k }
        val reach2 = reach * reach
        while (head < tail) {
            val k = queue[head++]
            sel[k] = 255
            val i = k % ww; val j = k / ww
            for (d in 0 until 4) {
                val ni = i + (if (d == 0) -1 else if (d == 1) 1 else 0)
                val nj = j + (if (d == 2) -1 else if (d == 3) 1 else 0)
                if (ni !in 0 until ww || nj !in 0 until wh) continue
                val nk = nj * ww + ni
                if (seen[nk]) continue
                val dx = win.left + ni + 0.5f - cx; val dy = win.top + nj + 0.5f - cy
                if (dx * dx + dy * dy > reach2 || !ok(px[nk])) continue
                seen[nk] = true; queue[tail++] = nk
            }
        }
        mask.writeRect(win, sel)
    }
}
