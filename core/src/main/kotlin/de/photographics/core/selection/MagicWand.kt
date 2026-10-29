package de.photographics.core.selection

import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid

/**
 * Zauberstab: wählt Pixel, deren Farbe in jedem Kanal (auch Deckung) höchstens [tolerance] vom
 * angetippten Pixel abweicht – zusammenhängend (Füllung ab dem Startpunkt) oder im ganzen Bild.
 */
object MagicWand {
    fun select(src: TileGrid, x: Int, y: Int, tolerance: Int, contiguous: Boolean): Selection {
        val w = src.width; val h = src.height
        val mask = TileGrid(w, h, 0)
        if (x !in 0 until w || y !in 0 until h) return Selection(mask, emptyList())
        val seed = src[x, y]
        val tol = tolerance.coerceIn(0, 255)
        fun ok(c: Int): Boolean {
            if (c == seed) return true
            for (s in intArrayOf(24, 16, 8, 0)) if (kotlin.math.abs(((c shr s) and 0xFF) - ((seed shr s) and 0xFF)) > tol) return false
            return true
        }
        if (!contiguous) {
            // Ganzes Bild streifenweise
            var y0 = 0
            while (y0 < h) {
                val r = IntRect(0, y0, w, minOf(h, y0 + TileGrid.TILE))
                val px = IntArray(r.width * r.height); src.readRect(r, px)
                val out = IntArray(px.size) { if (ok(px[it])) 255 else 0 }
                if (out.any { it != 0 }) mask.writeRect(r, out)
                y0 = r.bottom
            }
        } else {
            // Scanline-Füllung: Spannen statt einzelner Pixel auf den Stapel
            val stack = ArrayDeque<Long>()
            fun push(px: Int, py: Int) { stack.addLast((py.toLong() shl 32) or (px.toLong() and 0xFFFFFFFFL)) }
            push(x, y)
            while (stack.isNotEmpty()) {
                val v = stack.removeLast()
                val sx = (v and 0xFFFFFFFFL).toInt(); val sy = (v ushr 32).toInt()
                if (mask[sx, sy] != 0 || !ok(src[sx, sy])) continue
                var l = sx; while (l > 0 && mask[l - 1, sy] == 0 && ok(src[l - 1, sy])) l--
                var r = sx; while (r < w - 1 && mask[r + 1, sy] == 0 && ok(src[r + 1, sy])) r++
                mask.writeRect(IntRect(l, sy, r + 1, sy + 1), IntArray(r - l + 1) { 255 })
                for (ny in intArrayOf(sy - 1, sy + 1)) {
                    if (ny !in 0 until h) continue
                    var inRun = false
                    for (nx in l..r) {
                        val match = mask[nx, ny] == 0 && ok(src[nx, ny])
                        if (match && !inRun) push(nx, ny)
                        inRun = match
                    }
                }
            }
        }
        mask.compact()
        return Selection(mask, Selection.outlinesOf(mask))
    }
}
