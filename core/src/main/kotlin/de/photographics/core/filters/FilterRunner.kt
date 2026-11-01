package de.photographics.core.filters

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.history.TileTransaction
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.image.Parallel
import de.photographics.core.image.TileGrid

object FilterRunner {
    /**
     * Wendet [filter] auf die Ebene an (begrenzt auf die Auswahl, weiche Auswahlkanten werden
     * überblendet). Die zurückgegebene Transaktion kann für eine Vorschau zurückgerollt
     * oder als Undo-Schritt übernommen werden.
     */
    /** Pixel pro Streifen (begrenzt den Speicherbedarf auch bei sehr großen Bildern). */
    private const val BAND_PIXELS = 2_000_000

    fun apply(doc: Document, layer: PixelLayer, filter: ImageFilter): Pair<TileTransaction, IntRect> {
        val low = layer.low
        if (filter is PixelAdjustment && low != null && doc.bitDepth == 16) return apply16(doc, layer, low, filter)
        val tx = TileTransaction(layer.pixels)
        val content = layer.pixels.contentBounds()
        val ra = filter is RandomAccessFilter
        val margin = if (ra) 0 else minOf(filter.margin, 4096)
        // Lokale Filter wirken wie in Photoshop auf die ganze Ebene, auch außerhalb der Arbeitsfläche;
        // Verzerrungen beziehen sich auf die Arbeitsfläche
        val layerArea = layer.pixels.extent
        var region = (if (ra) doc.bounds else content.inset(-margin)).intersect(layerArea)
        doc.selection?.let { region = region.intersect(it.bounds) }
        if (region.isEmpty) return tx to IntRect.EMPTY

        // Originale sichern; alle Streifen lesen den unveränderten Zustand
        tx.touch(region)
        val sel = doc.selection
        val source = originalSource(tx)
        if (filter is PreparedFilter) filter.prepare(source, doc.width, doc.height)
        // Streifen parallel auf allen Kernen rechnen (nur lesend), der Reihe nach zurückschreiben.
        // Quelle, Ergebnis und Zwischenpuffer der Filter: grob 10 Werte à 4 Byte je gelesenem Pixel.
        val readW = region.width + 2 * margin
        val bytesPerRow = readW * 40L
        // Streifenhöhe abwägen: niedrige Streifen erlauben mehr Kerne im Speicherbudget, kosten aber
        // mehr Überlappung (Filterrand oben und unten). Geschätzter Durchsatz = Nutzanteil × Kerne.
        val maxH = maxOf(16, BAND_PIXELS / maxOf(1, readW)).coerceAtMost(maxOf(16, region.height))
        val budgetH = (Parallel.maxInFlightBytes / Parallel.threads / bytesPerRow - 2 * margin).toInt()
        val bandH = listOf(budgetH, 2 * margin, 4 * margin, 8 * margin, 16 * margin, maxH)
            .map { it.coerceIn(16, maxH) }
            .maxBy { h ->
                val window = (Parallel.maxInFlightBytes / ((h + 2L * margin) * bytesPerRow)).coerceIn(1L, Parallel.threads.toLong())
                val bands = (region.height + h - 1) / h
                h.toDouble() / (h + 2 * margin) * minOf(window, bands.toLong())
            }
        val count = (region.height + bandH - 1) / bandH
        val bytesPerBand = (bandH + 2L * margin) * bytesPerRow
        Parallel.bands(count, bytesPerBand, compute = { i ->
            val y0 = region.top + i * bandH
            val band = IntRect(region.left, y0, region.right, minOf(region.bottom, y0 + bandH))
            if (filter is RandomAccessFilter) {
                val src = IntArray(band.width * band.height)
                tx.readOriginal(band, src)
                // Eigene Quelle je Streifen: deren Kachel-Zwischenspeicher ist nicht threadsicher
                BandResult(band, band, src, filter.processBand(originalSource(tx), band, doc.width, doc.height))
            } else {
                val read = band.inset(-margin).intersect(layerArea)
                val src = IntArray(read.width * read.height)
                tx.readOriginal(read, src)
                BandResult(band, read, src, filter.process(src, read.width, read.height, read.left, read.top, doc.width, doc.height))
            }
        }, consume = { _, r -> writeBack(layer, r.band, r.read, r.src, r.out, sel) })
        return tx to region
    }

    /**
     * Korrektur in 16 Bit: jeder Kanalwert (0..65535) geht ohne Rundung auf 8 Bit durch die Abbildung
     * (zwischen den 8-Bit-Stützstellen interpoliert); obere und untere Bits werden geschrieben.
     */
    private fun apply16(doc: Document, layer: PixelLayer, low: TileGrid, filter: PixelAdjustment): Pair<TileTransaction, IntRect> {
        val tx = TileTransaction(layer.pixels)
        val ltx = TileTransaction(low).also { tx.linked = it }
        var region = layer.pixels.contentBounds().intersect(layer.pixels.extent)
        val sel = doc.selection
        sel?.let { region = region.intersect(it.bounds) }
        if (region.isEmpty) return tx to IntRect.EMPTY
        tx.touch(region); ltx.touch(region)
        val map = de.photographics.core.adjust.RgbMap { rgb -> filter.map(0xFF000000.toInt() or rgb) and 0xFFFFFF }
        val bandH = maxOf(1, 65_536 / region.width)
        val count = (region.height + bandH - 1) / bandH
        Parallel.bands(count, region.width.toLong() * bandH * 24, compute = { i ->
            val band = IntRect(region.left, region.top + i * bandH, region.right, minOf(region.bottom, region.top + (i + 1) * bandH))
            val h = IntArray(band.width * band.height); tx.readOriginal(band, h)
            val l = IntArray(band.width * band.height); ltx.readOriginal(band, l)
            val out = FloatArray(3)
            for (k in h.indices) {
                if ((h[k] ushr 24) == 0 && (l[k] ushr 24) == 0) continue
                fun v(s: Int) = ((((h[k] shr s) and 0xFF) shl 8) or ((l[k] shr s) and 0xFF)) / 257f
                de.photographics.core.render.Compositor.mapSmooth(map, v(16), v(8), v(0), out)
                val s = sel?.at(band.left + k % band.width, band.top + k / band.width) ?: 255
                var ch = h[k] and 0xFF000000.toInt(); var cl = l[k] and 0xFF000000.toInt()
                for (j in 0 until 3) {
                    val sh = de.photographics.core.image.Colors.SHIFTS_RGB[j]
                    val orig = v(sh)
                    val nv = orig + (out[j] - orig) * s / 255f
                    val q = (nv * 257f + 0.5f).toInt().coerceIn(0, 65535)
                    ch = ch or ((q ushr 8) shl sh); cl = cl or ((q and 0xFF) shl sh)
                }
                h[k] = ch; l[k] = cl
            }
            Triple(band, h, l)
        }, consume = { _, (band, h, l) -> layer.pixels.writeRect(band, h); low.writeRect(band, l) })
        return tx to region
    }

    private class BandResult(val band: IntRect, val read: IntRect, val src: IntArray, val out: IntArray)

    private fun ImageFilter.process(px: IntArray, w: Int, h: Int, offX: Int, offY: Int, docW: Int, docH: Int) =
        if (this is GeometryFilter) process(px, w, h, offX, offY, docW, docH) else process(px, w, h)

    /** Originalpixel mit Zwischenspeicher der zuletzt benutzten Kachel (schnell bei zusammenhängendem Zugriff). */
    private fun originalSource(tx: TileTransaction): PixelSource {
        val g = tx.grid
        var lastKey = -1
        var lastTile: de.photographics.core.image.Tile? = null
        return PixelSource { x, y ->
            if (!g.extent.contains(x, y)) g.defaultValue else {
                val tX = g.tileXOf(x); val tY = g.tileYOf(y)
                val key = tY * g.tilesX + tX
                if (key != lastKey) { lastKey = key; lastTile = tx.originalTile(tX, tY) }
                lastTile?.get(((y and TileGrid.MASK) shl TileGrid.SHIFT) or (x and TileGrid.MASK)) ?: g.defaultValue
            }
        }
    }

    /** Ergebnis für [target] zurückschreiben; weiche Auswahlkanten werden überblendet. */
    private fun writeBack(layer: PixelLayer, target: IntRect, read: IntRect, src: IntArray, out: IntArray, sel: de.photographics.core.selection.Selection?) {
        val row = IntArray(target.width)
        for (y in target.top until target.bottom) {
            for (x in target.left until target.right) {
                val i = (y - read.top) * read.width + (x - read.left)
                val s = sel?.at(x, y) ?: 255
                row[x - target.left] = when (s) {
                    255 -> out[i]
                    0 -> src[i]
                    else -> Colors.lerp(src[i], out[i], s / 255f)
                }
            }
            layer.pixels.writeRect(IntRect(target.left, y, target.right, y + 1), row)
        }
    }
}
