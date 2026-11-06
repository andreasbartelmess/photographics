package de.photographics.core.selection

import de.photographics.core.image.IntRect
import de.photographics.core.image.Parallel
import de.photographics.core.image.TileGrid
import java.lang.Long.numberOfLeadingZeros
import java.lang.Long.numberOfTrailingZeros

/**
 * Zauberstab: wählt Pixel, deren Farbe in jedem Kanal (auch Deckung) höchstens [tolerance] vom
 * angetippten Pixel abweicht – zusammenhängend (Füllung ab dem Startpunkt) oder im ganzen Bild.
 *
 * Ablauf: Erst wird auf allen Kernen für jedes Pixel ein Treffer-Bit berechnet (1 Bit je Pixel,
 * zeilenweise auf 64 Bit aufgefüllt), dann läuft die Füllung nur noch über diese Bits – wortweise,
 * ohne einzelne Kachelzugriffe. Zum Schluss wird die Maske kachelweise geschrieben.
 */
object MagicWand {
    /** [outlines] = false spart die Umrisse (laufende Ameisen), wenn die Maske nur zum Füllen dient. */
    fun select(src: TileGrid, x: Int, y: Int, tolerance: Int, contiguous: Boolean, outlines: Boolean = true): Selection {
        val w = src.width; val h = src.height
        val mask = TileGrid(w, h, 0)
        if (x !in 0 until w || y !in 0 until h) return Selection(mask, emptyList())
        val match = Bits(w, h)
        matches(src, src[x, y], tolerance.coerceIn(0, 255), match)
        val out = if (contiguous) fill(match, x, y) else match
        writeMask(out, mask)
        mask.compact()
        return Selection(mask, if (outlines) Selection.outlinesOf(mask) else emptyList())
    }

    /** Bitfeld w×h, jede Zeile auf ganze 64-Bit-Wörter aufgefüllt (Füllbits bleiben 0). */
    internal class Bits(val w: Int, val h: Int) {
        val stride = (w + 63) ushr 6
        val words = LongArray(stride * h)
        operator fun get(x: Int, y: Int) = (words[y * stride + (x ushr 6)] ushr x) and 1L != 0L

        /** Erstes x in [from, end) mit gesetztem Bit, sonst [end]. */
        fun nextSet(y: Int, from: Int, end: Int): Int {
            val base = y * stride; var x = from
            while (x < end) {
                val word = words[base + (x ushr 6)] and (-1L shl x)
                if (word != 0L) return minOf(end, (x and 63.inv()) + numberOfTrailingZeros(word))
                x = (x or 63) + 1
            }
            return end
        }

        /** Erstes x in [from, end) mit gelöschtem Bit, sonst [end]. */
        fun nextClear(y: Int, from: Int, end: Int): Int {
            val base = y * stride; var x = from
            while (x < end) {
                val word = words[base + (x ushr 6)].inv() and (-1L shl x)
                if (word != 0L) return minOf(end, (x and 63.inv()) + numberOfTrailingZeros(word))
                x = (x or 63) + 1
            }
            return end
        }

        /** Letztes x ≤ [from] mit gelöschtem Bit, sonst -1. */
        fun prevClear(y: Int, from: Int): Int {
            val base = y * stride; var x = from
            while (x >= 0) {
                val word = words[base + (x ushr 6)].inv() and (-1L ushr (63 - (x and 63)))
                if (word != 0L) return (x and 63.inv()) + 63 - numberOfLeadingZeros(word)
                x = (x and 63.inv()) - 1
            }
            return -1
        }

        /** Bits [l, r) der Zeile [y] setzen oder löschen. */
        fun set(y: Int, l: Int, r: Int, on: Boolean) {
            if (l >= r) return
            val base = y * stride
            val wl = l ushr 6; val wr = (r - 1) ushr 6
            for (wi in wl..wr) {
                var m = -1L
                if (wi == wl) m = m and (-1L shl l)
                if (wi == wr) m = m and (-1L ushr (63 - ((r - 1) and 63)))
                words[base + wi] = if (on) words[base + wi] or m else words[base + wi] and m.inv()
            }
        }

        fun rowEmpty(y: Int, l: Int, r: Int) = nextSet(y, l, r) >= r
    }

    /** Treffer-Bits für alle Pixel, streifenweise auf allen Kernen (jeder Streifen schreibt nur seine Zeilen). */
    private fun matches(src: TileGrid, seed: Int, tol: Int, out: Bits) {
        val w = out.w; val h = out.h
        val sa = seed ushr 24; val sr = (seed shr 16) and 0xFF; val sg = (seed shr 8) and 0xFF; val sb = seed and 0xFF
        val bandH = 64
        Parallel.bands((h + bandH - 1) / bandH, w.toLong() * bandH * 4, compute = { i ->
            val r = IntRect(0, i * bandH, w, minOf(h, (i + 1) * bandH))
            val px = IntArray(r.width * r.height); src.readRect(r, px)
            for (yy in 0 until r.height) {
                val base = (r.top + yy) * out.stride; val row = yy * w
                var word = 0L
                for (xx in 0 until w) {
                    val c = px[row + xx]
                    val hit = c == seed || (
                        kotlin.math.abs((c ushr 24) - sa) <= tol &&
                        kotlin.math.abs(((c shr 16) and 0xFF) - sr) <= tol &&
                        kotlin.math.abs(((c shr 8) and 0xFF) - sg) <= tol &&
                        kotlin.math.abs((c and 0xFF) - sb) <= tol)
                    if (hit) word = word or (1L shl xx)
                    if (xx and 63 == 63 || xx == w - 1) { out.words[base + (xx ushr 6)] = word; word = 0L }
                }
            }
        }, consume = { _, _ -> })
    }

    /** Scanline-Füllung über den Treffer-Bits ab ([x], [y]); [match] wird dabei verbraucht. */
    private fun fill(match: Bits, x: Int, y: Int): Bits {
        val w = match.w; val h = match.h
        val out = Bits(w, h)
        if (!match[x, y]) return out
        val stack = ArrayDeque<Long>()
        fun push(px: Int, py: Int) { stack.addLast((py.toLong() shl 32) or (px.toLong() and 0xFFFFFFFFL)) }
        push(x, y)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            val sx = (v and 0xFFFFFFFFL).toInt(); val sy = (v ushr 32).toInt()
            if (!match[sx, sy]) continue
            val l = match.prevClear(sy, sx) + 1
            val r = match.nextClear(sy, sx, w)
            match.set(sy, l, r, false)
            out.set(sy, l, r, true)
            for (ny in intArrayOf(sy - 1, sy + 1)) {
                if (ny !in 0 until h) continue
                var nx = match.nextSet(ny, l, r)
                while (nx < r) {
                    push(nx, ny)
                    nx = match.nextSet(ny, match.nextClear(ny, nx, r), r)
                }
            }
        }
        return out
    }

    /** Bits als Maske 0/255 schreiben – nur Kacheln, die etwas enthalten. */
    private fun writeMask(bits: Bits, mask: TileGrid) {
        val tilesX = (bits.w + TileGrid.TILE - 1) / TileGrid.TILE
        val tilesY = (bits.h + TileGrid.TILE - 1) / TileGrid.TILE
        Parallel.bands(tilesX * tilesY, TileGrid.TILE.toLong() * TileGrid.TILE * 4, compute = { i ->
            val l = (i % tilesX) * TileGrid.TILE; val t = (i / tilesX) * TileGrid.TILE
            val r = IntRect(l, t, minOf(bits.w, l + TileGrid.TILE), minOf(bits.h, t + TileGrid.TILE))
            if ((r.top until r.bottom).all { bits.rowEmpty(it, r.left, r.right) }) null
            else {
                val data = IntArray(r.width * r.height)
                for (yy in r.top until r.bottom) {
                    var xx = bits.nextSet(yy, r.left, r.right)
                    while (xx < r.right) {
                        val e = bits.nextClear(yy, xx, r.right)
                        val o = (yy - r.top) * r.width - r.left
                        data.fill(255, o + xx, o + e)
                        xx = bits.nextSet(yy, e, r.right)
                    }
                }
                r to data
            }
        }, consume = { _, res -> if (res != null) mask.writeRect(res.first, res.second) })
    }
}
