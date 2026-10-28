package de.photographics.core.image

/**
 * Verkleinerte Fassungen eines Rasters (Stufe L = 1/2^L) für die Anzeige großer Dokumente: statt
 * in voller Auflösung zu komponieren und danach zu verkleinern, liest der Compositor jede Ebene
 * aus der passenden Stufe. Die Stufen liegen selbst in Kachelrastern (nativer Speicher) und werden
 * kachelweise nachgeführt: nur Quellkacheln, deren Objekt oder [Tile.version] sich geändert hat,
 * werden neu verkleinert.
 *
 * [scalar]: Werte sind Grauwerte (Masken) statt ARGB – dann einfacher Mittelwert, sonst
 * deckkraftgewichtet (vormultipliziert), damit transparente Ränder nicht dunkel werden.
 */
class MipMap internal constructor(private val src: TileGrid, val scalar: Boolean) {
    private class Level(val grid: TileGrid, val tiles: Array<Tile?>, val versions: IntArray, val known: BooleanArray)

    private val levels = arrayOfNulls<Level>(MAX_LEVEL + 1)

    /** Stufe [level] (1..[MAX_LEVEL]) auf aktuellem Stand. */
    @Synchronized
    fun level(level: Int): TileGrid {
        require(level in 1..MAX_LEVEL)
        val lv = levels[level] ?: create(level).also { levels[level] = it }
        update(lv, level)
        return lv.grid
    }

    private fun create(level: Int): Level {
        val f = 1 shl level
        val e = src.extent
        val area = IntRect(e.left shr level, e.top shr level, ceilDiv(e.right, f), ceilDiv(e.bottom, f))
        val g = TileGrid(ceilDiv(src.width, f), ceilDiv(src.height, f), src.defaultValue, src.memory, area)
        val n = src.tilesX * src.tilesY
        return Level(g, arrayOfNulls(n), IntArray(n), BooleanArray(n))
    }

    private fun update(lv: Level, level: Int) {
        val f = 1 shl level
        // Veraltete Kacheln sammeln, auf allen Kernen verkleinern, dann der Reihe nach eintragen
        val stale = ArrayList<Int>()
        for (i in 0 until src.tilesX * src.tilesY) {
            val t = src.tile(i % src.tilesX, i / src.tilesX)
            if (!(lv.known[i] && lv.tiles[i] === t && (t == null || lv.versions[i] == t.version))) stale += i
        }
        if (stale.isEmpty()) return
        val block = TileGrid.TILE shr level
        Parallel.bands(stale.size, Tile.BYTES + block * block * 4L, compute = { k ->
            val i = stale[k]
            val tx = i % src.tilesX; val ty = i / src.tilesX
            val t = src.tile(tx, ty)
            val r = src.tileRect(tx, ty)
            val out = IntRect(r.left shr level, r.top shr level, ceilDiv(r.right, f), ceilDiv(r.bottom, f))
            val b = IntArray(out.width * out.height)
            var version = 0
            if (t == null) b.fill(src.defaultValue)
            else {
                version = t.version
                val s = IntArray(Tile.SIZE)
                t.read(0, s, 0, Tile.SIZE)
                downsample(s, r.width, r.height, level, b, out.width, out.height)
            }
            Triple(out, b, t to version)
        }, consume = { k, (out, b, tv) ->
            val i = stale[k]
            lv.grid.writeRect(out, b)
            lv.tiles[i] = tv.first; lv.versions[i] = tv.second; lv.known[i] = true
        })
    }

    /** Kachelinhalt (Zeilenlänge 256) um 2^[level] verkleinern. */
    private fun downsample(s: IntArray, w: Int, h: Int, level: Int, out: IntArray, ow: Int, oh: Int) {
        val f = 1 shl level
        for (oy in 0 until oh) for (ox in 0 until ow) {
            val x0 = ox * f; val y0 = oy * f
            val x1 = minOf(w, x0 + f); val y1 = minOf(h, y0 + f)
            var n = 0
            if (scalar) {
                var sum = 0
                for (y in y0 until y1) for (x in x0 until x1) { sum += s[(y shl TileGrid.SHIFT) or x]; n++ }
                out[oy * ow + ox] = if (n == 0) src.defaultValue else (sum + n / 2) / n
            } else {
                var a = 0; var r = 0; var g = 0; var bl = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val c = s[(y shl TileGrid.SHIFT) or x]
                    val ca = c ushr 24
                    a += ca; r += ((c shr 16) and 0xFF) * ca; g += ((c shr 8) and 0xFF) * ca; bl += (c and 0xFF) * ca
                    n++
                }
                out[oy * ow + ox] = if (a == 0) 0 else ((a / n) shl 24) or ((r / a) shl 16) or ((g / a) shl 8) or (bl / a)
            }
        }
    }

    companion object {
        /** Höchste Stufe (1/8): darüber hinaus wird aus Stufe 3 punktweise gelesen. */
        const val MAX_LEVEL = 3

        private fun ceilDiv(a: Int, b: Int) = Math.floorDiv(a + b - 1, b)

        /** Größte Stufe, die zum Abtastschritt [step] passt (0 = volle Auflösung). */
        fun levelFor(step: Int): Int {
            var l = 0
            while (l < MAX_LEVEL && (2 shl l) <= step) l++
            return l
        }
    }
}
