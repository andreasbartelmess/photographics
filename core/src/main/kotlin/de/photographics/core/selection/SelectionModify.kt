package de.photographics.core.selection

import de.photographics.core.image.Blur
import de.photographics.core.image.IntRect
import de.photographics.core.image.Parallel
import de.photographics.core.image.TileGrid
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import de.photographics.core.tr

enum class SelectionModifyKind(val label: String) {
    EXPAND(tr("Auswahl erweitern")), CONTRACT(tr("Auswahl verkleinern")), SMOOTH(tr("Auswahl abrunden")), BORDER(tr("Auswahlrand"))
}

/**
 * Auswahl → Auswahl verändern (wie Photoshop): Erweitern, Verkleinern, Abrunden, Rand.
 *
 * Erweitern/Verkleinern rechnen mit einer exakten euklidischen Distanztransformation (Felzenszwalb),
 * dadurch bleiben Ecken rund. Gerechnet wird kachelweise (je Kachel ein Fenster ± Radius), damit
 * nie die ganze Maske im Java-Speicher liegt. Die neue Kante wird über
 * einen Pixel geglättet; weiche Bereiche der alten Auswahl bleiben erhalten, soweit sie nicht
 * überdeckt (Erweitern) bzw. abgeschnitten (Verkleinern) werden.
 */
object SelectionModify {
    const val MAX_RADIUS = 500

    /** Auswahl um [radius] Pixel vergrößern. */
    fun expand(sel: Selection, radius: Int): Selection {
        val r = radius.coerceIn(0, MAX_RADIUS)
        if (r == 0 || sel.isEmpty) return sel
        val area = sel.bounds.inset(-(r + 2))
        // Außerhalb der Arbeitsfläche liegt nichts Ausgewähltes
        return tileWise(sel, area, r + 2, outside = 0, unchanged = 0) { win, ww, wh, t, tw, th ->
            if (win.none { it >= 128 }) return@tileWise null
            if (win.all { it >= 128 }) return@tileWise allOf(win, ww, t, tw, th, 255)
            if (isSoft(win)) return@tileWise shifted(win, ww, wh, t, tw, th, r, erode = false)
            // Abstand jedes Pixels zum nächsten ausgewählten Pixel (Quellen = ausgewählt)
            val d = distance(ww, wh) { i -> win[i] >= 128 }
            val res = IntArray(tw * th)
            var changed = false
            for (y in 0 until th) for (x in 0 until tw) {
                val i = (t.top + y) * ww + t.left + x
                val v = ((r + 1f - sqrt(d[i])).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val o = max(win[i], v)
                if (o != win[i]) changed = true
                res[y * tw + x] = o
            }
            if (changed) res else null
        }
    }

    /**
     * Auswahl um [radius] Pixel verkleinern. [atCanvasBounds] = Arbeitsflächenrand zählt als
     * nicht ausgewählt (Photoshop: "Effekt an Arbeitsflächenbegrenzungen anwenden").
     */
    fun contract(sel: Selection, radius: Int, atCanvasBounds: Boolean = false): Selection {
        val r = radius.coerceIn(0, MAX_RADIUS)
        if (r == 0 || sel.isEmpty) return sel
        return tileWise(sel, sel.bounds, r + 2, outside = if (atCanvasBounds) 0 else 255, unchanged = 255) { win, ww, wh, t, tw, th ->
            if (win.none { it < 128 }) return@tileWise null
            if (win.all { it < 128 }) return@tileWise allOf(win, ww, t, tw, th, 0)
            if (isSoft(win)) return@tileWise shifted(win, ww, wh, t, tw, th, r, erode = true)
            // Abstand jedes Pixels zum nächsten nicht ausgewählten Pixel
            val d = distance(ww, wh) { i -> win[i] < 128 }
            val res = IntArray(tw * th)
            var changed = false
            for (y in 0 until th) for (x in 0 until tw) {
                val i = (t.top + y) * ww + t.left + x
                val v = ((sqrt(d[i]) - r).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val o = min(win[i], v)
                if (o != win[i]) changed = true
                res[y * tw + x] = o
            }
            if (changed) res else null
        }
    }

    /**
     * Abrunden: entfernt Zacken und kleine Inseln/Löcher bis etwa [radius] Pixel. Die Maske wird
     * weichgezeichnet und wieder scharf (mit 1 Pixel Kantenglättung) geschnitten.
     */
    fun smooth(sel: Selection, radius: Int, atCanvasBounds: Boolean = false): Selection {
        val r = radius.coerceIn(0, MAX_RADIUS)
        if (r == 0 || sel.isEmpty) return sel
        val sigma = r * 0.5f
        val pad = (sigma * 3).toInt() + 2
        // Steigung der weichgezeichneten Kante ≈ 1/(σ·√2π): Schwelle 0,5 mit etwa einem Pixel Rampe
        val slope = 1f / (max(0.5f, sigma) * 2.5066f)
        return tileWise(sel, sel.bounds.inset(-pad), pad, outside = if (atCanvasBounds) 0 else -1, unchanged = -1) { win, ww, wh, t, tw, th ->
            val v0 = win[0]
            if (win.all { it == v0 }) return@tileWise null
            // Großer Radius: auf einem verkleinerten Raster weichzeichnen (das Ergebnis ist ohnehin glatt)
            val k = if (sigma > 6f) (sigma / 3f).toInt().coerceAtLeast(1) else 1
            val sw = (ww + k - 1) / k; val sh = (wh + k - 1) / k
            val f = FloatArray(sw * sh)
            for (y in 0 until wh) for (x in 0 until ww) f[(y / k) * sw + x / k] += win[y * ww + x] / 255f
            for (y in 0 until sh) for (x in 0 until sw) {
                val n = (min(k, wh - y * k) * min(k, ww - x * k)).toFloat()
                f[y * sw + x] /= n
            }
            Blur.gaussPlane(f, sw, sh, sigma / k)
            val res = IntArray(tw * th)
            for (y in 0 until th) for (x in 0 until tw) {
                val v = if (k == 1) f[(t.top + y) * ww + t.left + x] else {
                    // bilinear zwischen den Zellmitten
                    val fx = ((t.left + x + 0.5f) / k - 0.5f).coerceIn(0f, sw - 1f); val fy = ((t.top + y + 0.5f) / k - 0.5f).coerceIn(0f, sh - 1f)
                    val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, sw - 1); val y1 = min(y0 + 1, sh - 1)
                    val ax = fx - x0; val ay = fy - y0
                    val top = f[y0 * sw + x0] * (1 - ax) + f[y0 * sw + x1] * ax
                    val bot = f[y1 * sw + x0] * (1 - ax) + f[y1 * sw + x1] * ax
                    top * (1 - ay) + bot * ay
                }
                res[y * tw + x] = (((v - 0.5f) / slope + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            }
            res
        }
    }

    /** Rand: ein Band der Breite [width] entlang der Auswahlkante (je zur Hälfte innen und außen), leicht weich. */
    fun border(sel: Selection, width: Int, atCanvasBounds: Boolean = false): Selection {
        val w = width.coerceIn(1, 200)
        if (sel.isEmpty) return sel
        val outer = expand(sel, (w + 1) / 2)
        val inner = contract(sel, w / 2, atCanvasBounds)
        val ring = outer.combine(inner, SelectionOp.SUBTRACT)
        val soft = ring.feathered(max(0.6f, w / 6f))
        return Selection(soft.mask, Selection.outlinesOf(soft.mask))
    }

    // ------------------------------------------------------------------------------

    /**
     * Rechnet die Maske kachelweise neu: je Kachel in [area] ein Fenster mit [pad] Pixeln Rand
     * (außerhalb der Arbeitsfläche [outside], -1 = Randpixel fortsetzen). Kacheln, deren ganzes
     * Fenster ungespeichert ist und als [unchanged] gilt, werden übersprungen. [block] bekommt das
     * Fenster (Breite, Höhe), die Lage der Kachel darin und ihre Größe; null = Kachel unverändert.
     */
    private fun tileWise(
        sel: Selection, area: IntRect, pad: Int, outside: Int, unchanged: Int,
        block: (win: IntArray, ww: Int, wh: Int, tile: IntRect, tw: Int, th: Int) -> IntArray?,
    ): Selection {
        val mask = sel.mask
        val canvas = IntRect.ofSize(sel.width, sel.height)
        val a = area.intersect(canvas)
        if (a.isEmpty) return sel
        val parts = ArrayList<IntRect>()
        mask.forTilesIn(a) { tx, ty -> parts += mask.tileRect(tx, ty).intersect(canvas) }
        val side = TileGrid.TILE + 2L * pad
        val out = mask.copy()
        var any = false
        Parallel.bands(parts.size, side * side * 16, compute = { i ->
            val t = parts[i]
            val win = t.inset(-pad)
            if (unchanged >= 0 && mask.defaultValue == unchanged && (outside == unchanged || win == win.intersect(canvas))) {
                var stored = false
                mask.forTilesIn(win.intersect(canvas)) { tx, ty -> if (mask.tile(tx, ty) != null) stored = true }
                if (!stored) return@bands null
            }
            val inner = win.intersect(canvas)
            val buf = IntArray(win.width * win.height)
            if (inner == win) mask.readRect(win, buf) else {
                val tmp = IntArray(inner.width * inner.height)
                mask.readRect(inner, tmp)
                for (y in 0 until win.height) {
                    val sy = (win.top + y).coerceIn(inner.top, inner.bottom - 1) - inner.top
                    val inRow = win.top + y in inner.top until inner.bottom
                    for (x in 0 until win.width) {
                        val wx = win.left + x
                        val inside = inRow && wx in inner.left until inner.right
                        buf[y * win.width + x] = when {
                            inside -> tmp[sy * inner.width + (wx - inner.left)]
                            outside >= 0 -> outside
                            else -> tmp[sy * inner.width + (wx.coerceIn(inner.left, inner.right - 1) - inner.left)]
                        }
                    }
                }
            }
            block(buf, win.width, win.height, IntRect(pad, pad, pad + t.width, pad + t.height), t.width, t.height)
        }, consume = { i, res -> if (res != null) { out.writeRect(parts[i], res); any = true } })
        if (!any) return sel
        out.compact()
        return Selection(out, Selection.outlinesOf(out))
    }

    /** Mehr als kantengeglättete Ränder: weiche Auswahl (z. B. mit weicher Kante). */
    private fun isSoft(win: IntArray): Boolean {
        var n = 0
        for (v in win) if (v in 24..231) n++
        return n > win.size / 100
    }

    /**
     * Weiche Masken (Graustufen-Dilatation bzw. -Erosion um [r]): der weiche Verlauf wird als Ganzes
     * um [r] verschoben. Jedes Pixel nimmt den Wert der Stelle, die [r] Pixel weiter zur Auswahl hin
     * liegt (Erweitern) bzw. von ihr weg (Verkleinern). Die Richtung kommt außerhalb der 50-%-Linie
     * vom nächsten Pixel auf der anderen Seite (Distanztransformation), im Verlauf selbst aus dem
     * Gefälle der Maske. Eine einzige Distanztransformation statt einer je Graustufe.
     */
    private fun shifted(win: IntArray, ww: Int, wh: Int, t: IntRect, tw: Int, th: Int, r: Int, erode: Boolean): IntArray {
        val near = IntArray(win.size)
        distance(ww, wh, near) { i -> if (erode) win[i] < 128 else win[i] >= 128 }
        fun at(x: Int, y: Int) = win[y.coerceIn(0, wh - 1) * ww + x.coerceIn(0, ww - 1)]
        fun sample(fx: Float, fy: Float): Float {
            val x0 = kotlin.math.floor(fx).toInt(); val y0 = kotlin.math.floor(fy).toInt()
            val ax = fx - x0; val ay = fy - y0
            val top = at(x0, y0) * (1 - ax) + at(x0 + 1, y0) * ax
            val bot = at(x0, y0 + 1) * (1 - ax) + at(x0 + 1, y0 + 1) * ax
            return top * (1 - ay) + bot * ay
        }
        val res = IntArray(tw * th)
        for (y in 0 until th) for (x in 0 until tw) {
            val px = t.left + x; val py = t.top + y
            val i = py * ww + px
            val v = win[i]
            val q = near[i]
            var dx: Float; var dy: Float
            if (q >= 0 && q != i) {
                // Zur nächsten Quelle (Erweitern: ausgewählt, Verkleinern: nicht ausgewählt)
                dx = (q % ww - px).toFloat(); dy = (q / ww - py).toFloat()
            } else {
                // Im Verlauf: Gefälle (zeigt zu höheren Werten = in die Auswahl)
                dx = (at(px + 1, py) - at(px - 1, py)).toFloat(); dy = (at(px, py + 1) - at(px, py - 1)).toFloat()
                if (erode) { dx = -dx; dy = -dy }
            }
            val len = sqrt(dx * dx + dy * dy)
            val out = if (len < 1e-3f) {
                // Kein Gefälle: Fläche ohne Kante in der Nähe
                if (q == i) (if (erode) 0 else 255) else v
            } else {
                val sv = (sample(px + dx / len * r, py + dy / len * r) + 0.5f).toInt().coerceIn(0, 255)
                if (erode) min(v, sv) else max(v, sv)
            }
            res[y * tw + x] = out
        }
        return res
    }

    /** Kachel ganz auf [v] (null, wenn sie das schon ist). */
    private fun allOf(win: IntArray, ww: Int, t: IntRect, tw: Int, th: Int, v: Int): IntArray? {
        for (y in 0 until th) for (x in 0 until tw) if (win[(t.top + y) * ww + t.left + x] != v) return IntArray(tw * th) { v }
        return null
    }

    /**
     * Quadrierte euklidische Distanz jedes Pixels zum nächsten Pixel mit [source] = true
     * (Felzenszwalb & Huttenlocher, zeilen- und spaltenweise auf allen Kernen).
     */
    internal fun distance(w: Int, h: Int, source: (Int) -> Boolean): FloatArray = distance(w, h, null, source)

    /**
     * Wie [distance]; zusätzlich steht in [nearest] (Länge w·h) für jedes Pixel der Index des
     * nächsten Quellpixels (-1, wenn es keines gibt).
     */
    internal fun distance(w: Int, h: Int, nearest: IntArray?, source: (Int) -> Boolean): FloatArray {
        val inf = 1e20f
        val g = FloatArray(w * h) { if (source(it)) 0f else inf }
        // Spalten (bei [nearest]: Zeile der nächsten Quelle je Pixel merken)
        val ny = if (nearest != null) IntArray(w * h) else null
        val colBands = (w + 63) / 64
        Parallel.bands(colBands, h * 20L * 64, compute = { b ->
            val f = FloatArray(h); val d = FloatArray(h); val v = IntArray(h); val z = FloatArray(h + 1)
            val arg = if (ny != null) IntArray(h) else null
            for (x in b * 64 until min(w, b * 64 + 64)) {
                for (y in 0 until h) f[y] = g[y * w + x]
                edt1d(f, h, d, v, z, arg)
                for (y in 0 until h) g[y * w + x] = d[y]
                if (arg != null) for (y in 0 until h) ny!![y * w + x] = arg[y]
            }
        }, consume = { _, _ -> })
        // Zeilen
        val rowBands = (h + 63) / 64
        Parallel.bands(rowBands, w * 20L * 64, compute = { b ->
            val f = FloatArray(w); val d = FloatArray(w); val v = IntArray(w); val z = FloatArray(w + 1)
            val arg = if (nearest != null) IntArray(w) else null
            for (y in b * 64 until min(h, b * 64 + 64)) {
                System.arraycopy(g, y * w, f, 0, w)
                edt1d(f, w, d, v, z, arg)
                System.arraycopy(d, 0, g, y * w, w)
                if (arg != null) for (x in 0 until w) {
                    val px = arg[x]
                    nearest!![y * w + x] = if (d[x] >= 1e20f) -1 else ny!![y * w + px] * w + px
                }
            }
        }, consume = { _, _ -> })
        return g
    }

    /** Eindimensionale quadrierte Distanztransformation (untere Hülle von Parabeln). */
    private fun edt1d(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray, arg: IntArray? = null) {
        var k = 0
        v[0] = 0; z[0] = Float.NEGATIVE_INFINITY; z[1] = Float.POSITIVE_INFINITY
        for (q in 1 until n) {
            if (f[q] >= 1e20f && f[v[k]] >= 1e20f) { continue }
            var s: Float
            while (true) {
                val p = v[k]
                s = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * (q - p))
                if (s <= z[k] && k > 0) k-- else break
            }
            if (s <= z[k]) { v[k] = q; z[k + 1] = Float.POSITIVE_INFINITY; continue }
            k++
            v[k] = q; z[k] = s; z[k + 1] = Float.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            val dq = (q - p).toFloat()
            d[q] = if (f[p] >= 1e20f) 1e20f else dq * dq + f[p]
            if (arg != null) arg[q] = p
        }
    }
}
