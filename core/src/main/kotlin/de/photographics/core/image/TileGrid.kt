package de.photographics.core.image

/**
 * Speichersparender, in 256x256-Kacheln aufgeteilter Puffer.
 *
 * Für Ebenen enthalten die Werte nicht vormultiplizierte ARGB-Pixel (gleiches Layout wie
 * `android.graphics.Bitmap`), für Masken und Auswahlen Graustufen 0..255. Nicht angelegte
 * Kacheln gelten als komplett mit [defaultValue] gefüllt – leere Bereiche kosten keinen Speicher,
 * und Undo kann einzelne Kacheln sichern statt ganzer Ebenen.
 *
 * Koordinaten sind immer Dokumentkoordinaten. [width]×[height] ist die Arbeitsfläche; der
 * gespeicherte Bereich [extent] umfasst sie und darf darüber hinausgehen (auch ins Negative) –
 * so bleiben Pixel außerhalb der Arbeitsfläche erhalten, wie bei Photoshop.
 */
class TileGrid(
    val width: Int,
    val height: Int,
    val defaultValue: Int = 0,
    /** Speicher für neue Kacheln (nativ in der App, Java-Heap in Tests). */
    val memory: TileMemory = TileMemory.current,
    /** Zusätzlich zu speichernder Bereich außerhalb der Arbeitsfläche (null = nur Arbeitsfläche). */
    area: IntRect? = null,
) {
    /** Gespeicherter Bereich; links/oben auf Kachelgrenzen (Vielfache von 256) ausgerichtet. */
    val extent: IntRect = alignedExtent(area, width, height)
    val tilesX = (extent.width + TILE - 1) shr SHIFT
    val tilesY = (extent.height + TILE - 1) shr SHIFT
    private val tiles = arrayOfNulls<Tile>(tilesX * tilesY)

    /** Ragt der gespeicherte Bereich über die Arbeitsfläche hinaus? */
    val extendsBeyondCanvas: Boolean
        get() = extent.left < 0 || extent.top < 0 || extent.right > width || extent.bottom > height

    fun tile(tx: Int, ty: Int): Tile? = tiles[ty * tilesX + tx]

    @Volatile private var mipArgb: MipMap? = null
    @Volatile private var mipScalar: MipMap? = null

    /** Verkleinerte Fassung (1/2^[level]), kachelweise nachgeführt; [scalar] für Masken. */
    fun mip(level: Int, scalar: Boolean = false): TileGrid {
        val m = if (scalar) mipScalar ?: synchronized(this) { mipScalar ?: MipMap(this, true).also { mipScalar = it } }
            else mipArgb ?: synchronized(this) { mipArgb ?: MipMap(this, false).also { mipArgb = it } }
        return m.level(level)
    }

    fun tileOrCreate(tx: Int, ty: Int): Tile {
        val i = ty * tilesX + tx
        return tiles[i] ?: memory.allocate(defaultValue).also { tiles[i] = it }
    }

    fun setTile(tx: Int, ty: Int, data: Tile?) {
        tiles[ty * tilesX + tx] = data
    }

    /** Kachelindex (Spalte/Zeile) für eine Dokumentkoordinate. */
    fun tileXOf(x: Int) = (x - extent.left) shr SHIFT
    fun tileYOf(y: Int) = (y - extent.top) shr SHIFT

    /** Kachel, die den Dokumentpunkt (x, y) enthält; null außerhalb oder wenn leer. */
    fun tileAt(x: Int, y: Int): Tile? =
        if (!extent.contains(x, y)) null else tiles[tileYOf(y) * tilesX + tileXOf(x)]

    operator fun get(x: Int, y: Int): Int {
        if (!extent.contains(x, y)) return defaultValue
        val t = tiles[tileYOf(y) * tilesX + tileXOf(x)] ?: return defaultValue
        return t[((y and MASK) shl SHIFT) or (x and MASK)]
    }

    operator fun set(x: Int, y: Int, v: Int) {
        if (!extent.contains(x, y)) return
        tileOrCreate(tileXOf(x), tileYOf(y))[((y and MASK) shl SHIFT) or (x and MASK)] = v
    }

    /** Liest ein Rechteck zeilenweise in [out] (Zeilenlänge = rect.width). */
    fun readRect(rect: IntRect, out: IntArray) {
        val w = rect.width
        val e = extent
        for (y in rect.top until rect.bottom) {
            val row = (y - rect.top) * w
            if (y < e.top || y >= e.bottom) {
                out.fill(defaultValue, row, row + w); continue
            }
            val tileRow = tileYOf(y) * tilesX
            var x = rect.left
            while (x < rect.right) {
                if (x < e.left || x >= e.right) {
                    val stop = if (x < e.left) minOf(e.left, rect.right) else rect.right
                    out.fill(defaultValue, row + x - rect.left, row + stop - rect.left)
                    x = stop
                    continue
                }
                // Kachelgrenzen liegen wegen der Ausrichtung immer auf Vielfachen von 256
                val tileEnd = minOf(((x shr SHIFT) + 1) shl SHIFT, rect.right, e.right)
                val t = tiles[tileRow + tileXOf(x)]
                if (t == null) {
                    out.fill(defaultValue, row + x - rect.left, row + tileEnd - rect.left)
                } else {
                    t.read(((y and MASK) shl SHIFT) or (x and MASK), out, row + x - rect.left, tileEnd - x)
                }
                x = tileEnd
            }
        }
    }

    /** Schreibt [data] (Zeilenlänge = rect.width) in das Rechteck; Bereiche außerhalb von [extent] werden ignoriert. */
    fun writeRect(rect: IntRect, data: IntArray) {
        val r = rect.intersect(extent)
        if (r.isEmpty) return
        val w = rect.width
        for (y in r.top until r.bottom) {
            val ty = tileYOf(y)
            var x = r.left
            while (x < r.right) {
                val tileEnd = minOf(((x shr SHIFT) + 1) shl SHIFT, r.right)
                val t = tileOrCreate(tileXOf(x), ty)
                t.write(((y and MASK) shl SHIFT) or (x and MASK), data, (y - rect.top) * w + (x - rect.left), tileEnd - x)
                x = tileEnd
            }
        }
    }

    fun copy(): TileGrid {
        val c = TileGrid(width, height, defaultValue, memory, extent)
        for (i in tiles.indices) c.tiles[i] = tiles[i]?.copy()
        return c
    }

    fun clear() = tiles.fill(null)

    /** Belegter Speicher (nur angelegte Kacheln). */
    val byteSize: Long get() = tiles.count { it != null }.toLong() * Tile.BYTES

    /** Rechteck einer Kachel in Dokumentkoordinaten. */
    fun tileRect(tx: Int, ty: Int): IntRect {
        val l = extent.left + (tx shl SHIFT)
        val t = extent.top + (ty shl SHIFT)
        return IntRect(l, t, minOf(l + TILE, extent.right), minOf(t + TILE, extent.bottom))
    }

    /** Ruft [block] für alle Kachelindizes auf, die [rect] schneiden. */
    inline fun forTilesIn(rect: IntRect, block: (tx: Int, ty: Int) -> Unit) {
        val r = rect.intersect(extent)
        if (r.isEmpty) return
        for (ty in tileYOf(r.top)..tileYOf(r.bottom - 1))
            for (tx in tileXOf(r.left)..tileXOf(r.right - 1)) block(tx, ty)
    }

    /**
     * Begrenzungsrechteck aller Pixel, deren Wert (bzw. Alpha bei [alphaOnly]) vom Default abweicht.
     * Liefert [IntRect.EMPTY], wenn nichts gesetzt ist. Kann über die Arbeitsfläche hinausragen.
     */
    fun contentBounds(alphaOnly: Boolean = true): IntRect {
        var l = Int.MAX_VALUE; var tp = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val t = tile(tx, ty) ?: continue
            val tr = tileRect(tx, ty)
            // Kachel liegt vollständig im bisherigen Rechteck → nichts Neues zu finden
            if (l != Int.MAX_VALUE && tr.left >= l && tr.right <= r + 1 && tr.top >= tp && tr.bottom <= b + 1) continue
            val tile = t.pin(forWrite = false)
            try {
                for (y in tr.top until tr.bottom) {
                    val base = (y and MASK) shl SHIFT
                    for (x in tr.left until tr.right) {
                        val v = tile[base or (x and MASK)]
                        val set = if (alphaOnly) (v ushr 24) != 0 else v != defaultValue
                        if (set) {
                            if (x < l) l = x; if (x > r) r = x
                            if (y < tp) tp = y; if (y > b) b = y
                        }
                    }
                }
            } finally {
                t.unpin()
            }
        }
        return if (l == Int.MAX_VALUE) IntRect.EMPTY else IntRect(l, tp, r + 1, b + 1)
    }

    /** Entfernt Kacheln, die nur noch den Defaultwert enthalten. */
    fun compact() {
        for (i in tiles.indices) {
            val t = tiles[i] ?: continue
            if (t.isUniform(defaultValue)) tiles[i] = null
        }
    }

    /** Neues Raster, verschoben um (dx, dy). Inhalt außerhalb der Arbeitsfläche bleibt erhalten. */
    fun shifted(dx: Int, dy: Int): TileGrid {
        if (dx == 0 && dy == 0) return copy()
        val content = contentBounds(alphaOnly = false)
        if (content.isEmpty) return TileGrid(width, height, defaultValue, memory)
        val dst = content.offset(dx, dy)
        val out = TileGrid(width, height, defaultValue, memory, dst)
        copyBands(this, content, out, dst)
        out.compact()
        return out
    }

    /**
     * Neues Raster mit anderer Arbeitsfläche; (dx, dy) ist die Position des alten Ursprungs.
     * Mit [keepOutside] bleibt Inhalt außerhalb der neuen Fläche erhalten (Arbeitsfläche ändern),
     * sonst wird er verworfen (Freistellen).
     */
    fun resized(newW: Int, newH: Int, dx: Int, dy: Int, keepOutside: Boolean = true): TileGrid {
        val content = contentBounds(alphaOnly = false)
        if (content.isEmpty) return TileGrid(newW, newH, defaultValue, memory)
        var dst = content.offset(dx, dy)
        if (!keepOutside) dst = dst.intersect(IntRect.ofSize(newW, newH))
        val out = TileGrid(newW, newH, defaultValue, memory, if (keepOutside) dst else null)
        if (dst.isEmpty) return out
        copyBands(this, dst.offset(-dx, -dy), out, dst)
        out.compact()
        return out
    }

    companion object {
        const val TILE = 256
        const val SHIFT = 8
        const val MASK = TILE - 1

        /** Gespeicherter Bereich: Arbeitsfläche ∪ [area], links/oben auf Kachelgrenzen abgerundet. */
        fun alignedExtent(area: IntRect?, w: Int, h: Int): IntRect {
            val canvas = IntRect.ofSize(w, h)
            val u = if (area == null || area.isEmpty) canvas else canvas.union(area)
            return IntRect(Math.floorDiv(u.left, TILE) * TILE, Math.floorDiv(u.top, TILE) * TILE, u.right, u.bottom)
        }

        /** Kopiert [srcRect] aus [src] nach [dstRect] in [dst] (gleiche Größe), in Streifen von einer Kachelhöhe. */
        fun copyBands(src: TileGrid, srcRect: IntRect, dst: TileGrid, dstRect: IntRect) {
            val buf = IntArray(srcRect.width * TILE)
            var y = 0
            while (y < srcRect.height) {
                val n = minOf(TILE, srcRect.height - y)
                val b = if (n == TILE) buf else IntArray(srcRect.width * n)
                src.readRect(IntRect(srcRect.left, srcRect.top + y, srcRect.right, srcRect.top + y + n), b)
                dst.writeRect(IntRect(dstRect.left, dstRect.top + y, dstRect.right, dstRect.top + y + n), b)
                y += n
            }
        }

        fun fromPixels(w: Int, h: Int, pixels: IntArray, defaultValue: Int = 0): TileGrid =
            TileGrid(w, h, defaultValue).apply { writeRect(IntRect.ofSize(w, h), pixels); compact() }
    }
}
