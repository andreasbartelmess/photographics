package de.photographics.core.filters

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.doc.SmartFilters
import de.photographics.core.editor.PixelBlend
import de.photographics.core.image.IntRect
import de.photographics.core.image.Parallel
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode

/**
 * Wendet die Smartfilter einer Ebene auf ihr Raster an (Dokumentgröße [w]×[h]) – nacheinander von
 * unten nach oben, jeder mit seinen Fülloptionen über das Bild davor, zum Schluss die Filtermaske
 * zwischen Original und Ergebnis. Das Original bleibt unverändert.
 */
object SmartFilterRunner {
    fun apply(base: TileGrid, sf: SmartFilters, w: Int, h: Int): TileGrid {
        if (!sf.active) return base
        val cur = applyFilters(base, sf, w, h)
        val m = sf.mask?.takeIf { it.enabled } ?: return cur
        return maskBlend(base, cur, m.grid).also { it.compact() }
    }

    /** Nur die Filter (ohne Filtermaske); [base] bleibt unverändert. */
    fun applyFilters(base: TileGrid, sf: SmartFilters, w: Int, h: Int): TileGrid {
        if (!sf.active) return base
        var cur = base.copy()
        for (f in sf.filters) {
            if (!f.enabled) continue
            val filter = f.build() ?: continue
            val blended = f.blendMode != BlendMode.NORMAL || f.opacity < 1f
            val before = if (blended) cur.copy() else null
            cur = once(cur, filter, w, h)
            if (before != null) cur = combine(before, cur) { b, a, _ ->
                if (f.blendMode == BlendMode.NORMAL) lerpPremul(b, a, f.opacity) else PixelBlend.over(b, a, f.blendMode, f.opacity)
            }
        }
        cur.compact()
        return cur
    }

    /** Bereich [r] von [out] neu aus [base], [after] und [mask] überblenden (Malen auf der Filtermaske). */
    fun maskBlendInto(out: TileGrid, base: TileGrid, after: TileGrid, mask: TileGrid, r: IntRect) {
        val area = r.intersect(IntRect(0, 0, out.width, out.height))
        if (area.isEmpty) return
        val parts = ArrayList<IntRect>()
        out.forTilesIn(area) { tx, ty -> out.tileRect(tx, ty).intersect(area).takeIf { !it.isEmpty }?.let { parts += it } }
        Parallel.bands(parts.size, TileGrid.TILE.toLong() * TileGrid.TILE * 16, compute = { i ->
            val q = parts[i]; val n = q.width * q.height
            val pb = IntArray(n); base.readRect(q, pb)
            val pa = IntArray(n); after.readRect(q, pa)
            val pm = IntArray(n); mask.readRect(q, pm)
            for (k in 0 until n) pa[k] = lerpPremul(pb[k], pa[k], pm[k] / 255f)
            pa
        }, consume = { i, px -> out.writeRect(parts[i], px) })
    }

    /** Ein Filter auf das ganze Raster (wie Filter → … auf einer Pixelebene, ohne Auswahl). */
    private fun once(g: TileGrid, filter: ImageFilter, w: Int, h: Int): TileGrid {
        val doc = Document(w, h)
        val l = PixelLayer("", g)
        doc.root.add(l)
        FilterRunner.apply(doc, l, filter)
        return l.pixels
    }

    /** Bereich, den zwei Raster gemeinsam abdecken (gespeicherter Bereich, auf Kacheln ausgerichtet). */
    private fun area(a: TileGrid, b: TileGrid): IntRect = a.extent.union(b.extent)

    /** Kachelweise auf allen Kernen: neues Raster aus [b] (vorher) und [a] (nachher) über [f]. */
    private fun combine(b: TileGrid, a: TileGrid, f: (Int, Int, Int) -> Int): TileGrid {
        val out = TileGrid(a.width, a.height, 0, a.memory, area(a, b))
        val parts = ArrayList<IntRect>()
        out.forTilesIn(out.extent) { tx, ty -> parts += out.tileRect(tx, ty) }
        Parallel.bands(parts.size, TileGrid.TILE.toLong() * TileGrid.TILE * 12, compute = { i ->
            val r = parts[i]; val n = r.width * r.height
            val pb = IntArray(n); b.readRect(r, pb)
            val pa = IntArray(n); a.readRect(r, pa)
            var any = false
            for (k in 0 until n) { val v = f(pb[k], pa[k], 0); pa[k] = v; if (v != 0) any = true }
            if (any) pa else null
        }, consume = { i, px -> if (px != null) out.writeRect(parts[i], px) })
        return out
    }

    /** Filtermaske: weiß = Ergebnis, schwarz = Original, dazwischen vormultipliziert überblendet. */
    fun maskBlend(base: TileGrid, after: TileGrid, mask: TileGrid): TileGrid {
        val out = TileGrid(after.width, after.height, 0, after.memory, area(after, base))
        val parts = ArrayList<IntRect>()
        out.forTilesIn(out.extent) { tx, ty -> parts += out.tileRect(tx, ty) }
        Parallel.bands(parts.size, TileGrid.TILE.toLong() * TileGrid.TILE * 16, compute = { i ->
            val r = parts[i]; val n = r.width * r.height
            val pb = IntArray(n); base.readRect(r, pb)
            val pa = IntArray(n); after.readRect(r, pa)
            val pm = IntArray(n); mask.readRect(r, pm)
            var any = false
            for (k in 0 until n) { val v = lerpPremul(pb[k], pa[k], pm[k] / 255f); pa[k] = v; if (v != 0) any = true }
            if (any) pa else null
        }, consume = { i, px -> if (px != null) out.writeRect(parts[i], px) })
        return out
    }

    /** Zwischen [c0] und [c1] überblenden, mit Deckung gewichtet (keine Farbsäume an transparenten Rändern). */
    fun lerpPremul(c0: Int, c1: Int, t: Float): Int {
        if (t <= 0f) return c0
        if (t >= 1f) return c1
        val a0 = (c0 ushr 24) / 255f; val a1 = (c1 ushr 24) / 255f
        val u = 1f - t
        val a = a0 * u + a1 * t
        if (a <= 0f) return 0
        fun ch(s: Int) = ((((c0 shr s) and 0xFF) * a0 * u + ((c1 shr s) and 0xFF) * a1 * t) / a + 0.5f).toInt().coerceIn(0, 255)
        return ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
