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
