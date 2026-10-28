package de.photographics.core.history

import de.photographics.core.doc.Document
import de.photographics.core.image.IntRect
import de.photographics.core.image.SlabTileMemory
import de.photographics.core.image.Tile
import de.photographics.core.image.TileMemory
import de.photographics.core.image.TileGrid

interface HistoryEntry {
    val label: String
    /** Ungefährer Speicherbedarf im Arbeitsspeicher, für das Begrenzen des Verlaufs. */
    val byteSize: Long get() = 0
    fun undo(doc: Document)
    fun redo(doc: Document)
    /** Lagert die Daten auf das Arbeitsvolume aus; true, wenn danach Speicher frei ist. */
    fun spill(disk: ScratchDisk): Boolean = false
    /** Aufräumen, wenn der Schritt endgültig aus dem Verlauf fällt (ausgelagerte Dateien löschen). */
    fun dispose() {}
}

/**
 * Verlauf mit Begrenzung auf Anzahl und Speicher. Das Speicherbudget richtet sich standardmäßig
 * nach dem Kachelspeicher (ein Viertel davon); ist der Speicher knapp, wird früher ausgelagert.
 */
class History(
    private val maxEntries: Int = 60,
    /** Feste Obergrenze in Bytes; ≤ 0 = ein Viertel des Kachelspeichers. */
    var maxBytes: Long = 0,
) {
    private val memory get() = TileMemory.current
    private val budgetBytes get() = if (maxBytes > 0) maxBytes else memory.limitBytes / 4

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    /** Optionales Arbeitsvolume; ohne fallen alte Schritte bei Speichermangel weg. */
    var scratch: ScratchDisk? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val undoLabel get() = undoStack.lastOrNull()?.label
    val redoLabel get() = redoStack.lastOrNull()?.label
    val entries: List<String> get() = undoStack.map { it.label }

    fun push(e: HistoryEntry) {
        undoStack.addLast(e)
        redoStack.forEach { it.dispose() }
        redoStack.clear()
        while (undoStack.size > maxEntries) undoStack.removeFirst().dispose()
        enforceBudget()
    }

    /**
     * Hält den Verlauf im Speicherbudget: zuerst die ältesten Schritte auslagern, erst wenn das
     * nicht geht (kein Arbeitsvolume, voll), die ältesten Schritte verwerfen.
     */
    fun enforceBudget() {
        var total = bytes
        // Bei hoher Speicherauslastung (große Dokumente) nur noch wenig Verlauf im Arbeitsspeicher
        val budget = if (scratch != null && memory.pressure > 0.6) minOf(budgetBytes, memory.limitBytes / 16) else budgetBytes
        if (total <= budget) return
        scratch?.let { disk ->
            // Älteste zuerst; der jüngste Schritt nur, wenn er allein das Budget sprengt
            val candidates = undoStack.toList() + redoStack.toList()
            for (c in candidates) {
                if (total <= budget) break
                val b = c.byteSize
                if (b == 0L || !disk.hasRoom(b)) continue
                try { if (c.spill(disk)) total -= b - c.byteSize } catch (_: java.io.IOException) { break }
            }
        }
        // Verwerfen nur im Notfall (ohne Arbeitsvolume oder bei wirklich knappem Speicher);
        // sonst bleiben nicht auslagerbare Schritte lieber im Speicher, als Verlauf zu verlieren.
        // Nur von vorn verwerfen (der Verlauf muss lückenlos bleiben).
        val dropAbove = if (scratch == null || total > budget && realPressure() > 0.85) budget else Long.MAX_VALUE
        while (total > dropAbove && undoStack.size > 1) {
            val e = undoStack.removeFirst(); total -= e.byteSize; e.dispose()
            // Ein verworfener, ausgelagerter Schritt gibt Platz im Arbeitsvolume frei: damit die
            // Schritte im Speicher auslagern, statt weiter (womöglich den ganzen) Verlauf zu verwerfen
            val disk = scratch ?: continue
            for (c in undoStack.toList() + redoStack.toList()) {
                if (total <= dropAbove) break
                val b = c.byteSize
                if (b == 0L || !disk.hasRoom(b)) continue
                try { if (c.spill(disk)) total -= b - c.byteSize } catch (_: java.io.IOException) { break }
            }
        }
    }

    /** Auslastung ohne bereits unerreichbare Kacheln (die erst die Speicherbereinigung freigibt). */
    private fun realPressure(): Double {
        if (memory.pressure <= 0.85) return memory.pressure
        (memory as? SlabTileMemory)?.reclaim()
        if (memory.pressure > 0.85) { System.gc(); (memory as? SlabTileMemory)?.reclaim() }
        return memory.pressure
    }

    /**
     * Sorgt vor einer großen Aktion für [bytes] freien Arbeitsspeicher, indem Verlaufsschritte
     * (auch der jüngste) ausgelagert bzw. ohne Arbeitsvolume die ältesten verworfen werden.
     */
    fun makeRoom(bytes: Long) {
        fun free(): Long { (memory as? SlabTileMemory)?.reclaim(); return memory.freeBytes }
        val reserve = bytes + memory.limitBytes / 10
        if (free() >= reserve) return
        System.gc()
        if (free() >= reserve) return
        scratch?.let { disk ->
            for (c in undoStack.toList() + redoStack.toList()) {
                if (c.byteSize == 0L || !disk.hasRoom(c.byteSize)) continue
                try { c.spill(disk) } catch (_: java.io.IOException) { break }
            }
            System.gc()
            if (free() >= reserve) return
        }
        while (undoStack.isNotEmpty() && free() < reserve) {
            redoStack.forEach { it.dispose() }; redoStack.clear()
            undoStack.removeFirst().dispose(); System.gc()
        }
    }

    fun undo(doc: Document): HistoryEntry? {
        val e = undoStack.removeLastOrNull() ?: return null
        e.undo(doc); redoStack.addLast(e)
        enforceBudget()
        return e
    }

    fun redo(doc: Document): HistoryEntry? {
        val e = redoStack.removeLastOrNull() ?: return null
        e.redo(doc); undoStack.addLast(e)
        enforceBudget()
        return e
    }

    fun clear() {
        undoStack.forEach { it.dispose() }; redoStack.forEach { it.dispose() }
        undoStack.clear(); redoStack.clear()
    }

    /** Ungefährer Speicherbedarf aller Schritte. */
    val bytes: Long get() = undoStack.sumOf { it.byteSize } + redoStack.sumOf { it.byteSize }

    /** Alle Schritte auf das Arbeitsvolume auslagern (z. B. wenn die App in den Hintergrund geht). */
    fun spillAll(): Long {
        val disk = scratch ?: return 0
        val before = bytes
        for (c in undoStack.toList() + redoStack.toList()) {
            if (c.byteSize == 0L || !disk.hasRoom(c.byteSize)) continue
            try { c.spill(disk) } catch (_: java.io.IOException) { break }
        }
        return before - bytes
    }

    /** Bei Speichermangel: Wiederholen-Schritte und die ältere Hälfte des Verlaufs verwerfen. */
    fun trim(): Boolean {
        val before = bytes
        if (before == 0L) return false
        scratch?.let { disk ->
            for (c in undoStack.toList() + redoStack.toList()) if (c.byteSize > 0 && disk.hasRoom(c.byteSize))
                try { c.spill(disk) } catch (_: java.io.IOException) { break }
            if (bytes < before) return true
        }
        redoStack.forEach { it.dispose() }; redoStack.clear()
        repeat(undoStack.size / 2 + 1) { undoStack.removeFirstOrNull()?.dispose() }
        return true
    }
}

/**
 * Sammelt vor einer Pixeländerung die Originalkacheln eines Rasters (Copy-on-Write) und
 * erzeugt daraus einen [TilePatch]. Liefert außerdem den Originalzustand für Werkzeuge,
 * die immer vom Zustand vor dem Strich ausgehen (Deckkraftbegrenzung, Kopierstempel).
 */
class TileTransaction(val grid: TileGrid) {
    private val originals = HashMap<Int, Tile?>()

    /** Sichert alle Kacheln im Rechteck, bevor sie verändert werden. */
    fun touch(rect: IntRect) {
        grid.forTilesIn(rect) { tx, ty ->
            val key = ty * grid.tilesX + tx
            if (!originals.containsKey(key)) originals[key] = grid.tile(tx, ty)?.copy()
        }
    }

    /** Originalkachel (vor der Transaktion); null = leer. Nur für bereits gesicherte Kacheln gültig. */
    fun originalTile(tx: Int, ty: Int): Tile? {
        val key = ty * grid.tilesX + tx
        return if (originals.containsKey(key)) originals[key] else grid.tile(tx, ty)
    }

    /** Wert vor Beginn der Transaktion. */
    fun original(x: Int, y: Int): Int {
        if (!grid.extent.contains(x, y)) return grid.defaultValue
        val key = grid.tileYOf(y) * grid.tilesX + grid.tileXOf(x)
        if (!originals.containsKey(key)) return grid[x, y]
        val t = originals[key] ?: return grid.defaultValue
        return t[((y and TileGrid.MASK) shl TileGrid.SHIFT) or (x and TileGrid.MASK)]
    }

    /** Liest ein Rechteck im Originalzustand (kachelweise, schnell). */
    fun readOriginal(rect: IntRect, out: IntArray) {
        val w = rect.width
        val inside = rect.intersect(grid.extent)
        if (inside != rect) out.fill(grid.defaultValue, 0, rect.width * rect.height)
        if (inside.isEmpty) return
        grid.forTilesIn(inside) { tx, ty ->
            val tr = grid.tileRect(tx, ty).intersect(inside)
            val t = originalTile(tx, ty)
            for (y in tr.top until tr.bottom) {
                val o = (y - rect.top) * w + (tr.left - rect.left)
                if (t == null) out.fill(grid.defaultValue, o, o + tr.width)
                else t.read(((y and TileGrid.MASK) shl TileGrid.SHIFT) or (tr.left and TileGrid.MASK), out, o, tr.width)
            }
        }
    }

    val isEmpty get() = originals.isEmpty()

    /** Schließt die Transaktion ab. Der Patch hält nur die Originalkacheln (keine zweite Kopie). */
    fun commit(label: String): TilePatch? {
        if (originals.isEmpty()) return null
        val patch = TilePatch(label, grid, HashMap(originals))
        originals.clear()
        return patch
    }

    fun rollback() {
        for ((key, t) in originals) grid.setTile(key % grid.tilesX, key / grid.tilesX, t)
        originals.clear()
    }
}

/**
 * Pixeländerung im Verlauf. Hält genau eine Fassung der betroffenen Kacheln: Rückgängig und
 * Wiederholen tauschen gespeicherte und aktuelle Kacheln aus (halber Speicherbedarf, keine Kopien).
 */
class TilePatch(
    override val label: String,
    private val grid: TileGrid,
    private var stash: HashMap<Int, Tile?>,
) : HistoryEntry {
    private val keys = stash.keys.toIntArray()
    private var file: java.io.File? = null
    private var disk: ScratchDisk? = null

    override val byteSize: Long
        get() = stash.values.count { it != null }.toLong() * Tile.BYTES

    /** Betroffener Bereich in Dokumentkoordinaten (zum Neuzeichnen). */
    val bounds: IntRect
        get() = keys.fold(IntRect.EMPTY) { acc, k -> acc.union(grid.tileRect(k % grid.tilesX, k / grid.tilesX)) }

    override fun undo(doc: Document) = swap()
    override fun redo(doc: Document) = swap()

    override fun spill(disk: ScratchDisk): Boolean {
        if (file != null) return false
        file = disk.writeTiles(stash); this.disk = disk
        stash = HashMap()
        return true
    }

    override fun dispose() { file?.delete(); file = null }

    private fun swap() {
        file?.let { f -> stash = disk!!.readTiles(f); f.delete(); file = null }
        for (key in stash.keys.toList()) {
            val tx = key % grid.tilesX; val ty = key / grid.tilesX
            val current = grid.tile(tx, ty)
            grid.setTile(tx, ty, stash[key])
            stash[key] = current
        }
    }
}

/** Beliebige Änderung, beschrieben durch zwei Funktionen (Ebenen hinzufügen, Eigenschaften ...). */
class LambdaEntry(
    override val label: String,
    private val onUndo: (Document) -> Unit,
    private val onRedo: (Document) -> Unit,
    override val byteSize: Long = 0,
) : HistoryEntry {
    override fun undo(doc: Document) = onUndo(doc)
    override fun redo(doc: Document) = onRedo(doc)
}

/** Mehrere Einträge als ein Schritt. */
class CompoundEntry(override val label: String, private val parts: List<HistoryEntry>) : HistoryEntry {
    override val byteSize: Long get() = parts.sumOf { it.byteSize }
    override fun spill(disk: ScratchDisk) = parts.map { it.spill(disk) }.any { it }
    override fun dispose() = parts.forEach { it.dispose() }
    override fun undo(doc: Document) { for (p in parts.asReversed()) p.undo(doc) }
    override fun redo(doc: Document) { for (p in parts) p.redo(doc) }
}

/**
 * Änderung des ganzen Dokuments (Bildgröße, Drehen, Freistellen, …). Hält nur den jeweils
 * inaktiven Zustand (vor der Änderung bzw. nach dem Rückgängigmachen) – Raster, die mit dem
 * aktuellen Dokument geteilt sind, werden nicht gehalten. Rückgängig/Wiederholen tauschen.
 */
class DocumentChangeEntry(
    override val label: String,
    before: Document.Snapshot,
    after: Document.Snapshot,
) : HistoryEntry {
    private var stash: Document.Snapshot = exclusive(before, after)
    /** Ausgelagerte Raster: (Ebenen-ID, Maske?) → Datei. */
    private val spilled = HashMap<Pair<Int, Boolean>, java.io.File>()
    private var disk: ScratchDisk? = null

    private fun grids() = stash.layers.flatMap { (id, d) ->
        listOfNotNull(d.pixels?.let { (id to false) to it }, d.mask?.let { (id to true) to it })
    }

    override val byteSize: Long get() = grids().sumOf { it.second.byteSize }

    override fun spill(disk: ScratchDisk): Boolean {
        val grids = grids().filter { it.second.byteSize > 0 }
        if (grids.isEmpty()) return false
        this.disk = disk
        for ((key, g) in grids) spilled[key] = disk.writeGrid(g)
        stash = mapStash { id, d ->
            Document.LayerData(if ((id to false) in spilled) null else d.pixels, if ((id to true) in spilled) null else d.mask, d.transform, d.style, d.warp)
        }
        return true
    }

    private fun mapStash(f: (Int, Document.LayerData) -> Document.LayerData) =
        Document.Snapshot(stash.width, stash.height, stash.layers.mapValues { (id, d) -> f(id, d) }, stash.selection, stash.guides)

    private fun swap(doc: Document) {
        if (spilled.isNotEmpty()) {
            val d0 = disk!!
            stash = mapStash { id, d ->
                Document.LayerData(
                    spilled[id to false]?.let(d0::readGrid) ?: d.pixels,
                    spilled[id to true]?.let(d0::readGrid) ?: d.mask,
                    d.transform, d.style, d.warp,
                )
            }
            dispose()
        }
        val current = doc.snapshot()
        doc.restore(stash)
        stash = exclusive(current, doc.snapshot())
    }

    override fun undo(doc: Document) = swap(doc)
    override fun redo(doc: Document) = swap(doc)
    override fun dispose() { spilled.values.forEach { it.delete() }; spilled.clear() }

    private companion object {
        /** [s] ohne Raster, die (als dasselbe Objekt) auch in [other] vorkommen. */
        fun exclusive(s: Document.Snapshot, other: Document.Snapshot): Document.Snapshot {
            val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TileGrid, Boolean>())
            for (d in other.layers.values) { d.pixels?.let(live::add); d.mask?.let(live::add) }
            return Document.Snapshot(s.width, s.height, s.layers.mapValues { (_, d) ->
                Document.LayerData(d.pixels?.takeIf { it !in live }, d.mask?.takeIf { it !in live }, d.transform, d.style, d.warp)
            }, s.selection, s.guides)
        }
    }
}

/**
 * Ebenen-Operation (Löschen, Zusammenfügen, Reduzieren, Ersetzen). Ebenen aus [involved], die gerade
 * nicht im Dokument hängen, werden nur vom Verlauf gehalten – ihre Pixel und Masken lassen sich
 * auslagern und werden vor dem Rückgängigmachen/Wiederholen zurückgeladen.
 */
class LayerOperationEntry(
    override val label: String,
    private val onUndo: (Document) -> Unit,
    private val onRedo: (Document) -> Unit,
    private val involved: List<de.photographics.core.doc.Layer>,
    private val isAttached: (de.photographics.core.doc.Layer) -> Boolean,
) : HistoryEntry {
    private class Stashed(val pixels: java.io.File?, val mask: java.io.File?)
    private val stashed = java.util.IdentityHashMap<de.photographics.core.doc.PixelLayer, Stashed>()
    private val stashedMasks = java.util.IdentityHashMap<de.photographics.core.doc.Layer, java.io.File>()
    private var disk: ScratchDisk? = null

    private fun detached(): List<de.photographics.core.doc.Layer> {
        val out = ArrayList<de.photographics.core.doc.Layer>()
        fun walk(l: de.photographics.core.doc.Layer) {
            out += l
            if (l is de.photographics.core.doc.GroupLayer) l.children.forEach(::walk)
        }
        for (l in involved) if (!isAttached(l)) walk(l)
        return out
    }

    override val byteSize: Long get() = detached().sumOf { l ->
        val px = when (l) {
            is de.photographics.core.doc.PixelLayer -> if (stashed.containsKey(l)) 0L else l.pixels.byteSize
            is de.photographics.core.doc.SmartLayer -> l.source.byteSize + l.raster.byteSize
            is de.photographics.core.doc.RasterLayer -> l.raster.byteSize
            else -> 0L
        }
        px + (if (stashedMasks.containsKey(l)) 0L else l.mask?.grid?.byteSize ?: 0L)
    }

    override fun spill(disk: ScratchDisk): Boolean {
        var any = false
        this.disk = disk
        // Masken/Raster, die eine eingehängte Ebene mitbenutzt (z. B. nach dem Rastern), nicht anfassen
        val sharedMasks = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val sharedGrids = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        for (l in involved) if (isAttached(l)) {
            fun walk(x: de.photographics.core.doc.Layer) {
                x.mask?.let { sharedMasks += it }
                if (x is de.photographics.core.doc.RasterLayer) sharedGrids += x.raster
                if (x is de.photographics.core.doc.GroupLayer) x.children.forEach(::walk)
            }
            walk(l)
        }
        for (l in detached()) {
            if (l is de.photographics.core.doc.PixelLayer && !stashed.containsKey(l) && l.pixels.byteSize > 0 && l.pixels !in sharedGrids) {
                val g = l.pixels
                stashed[l] = Stashed(disk.writeGrid(g), null)
                l.pixels = TileGrid(g.width, g.height, g.defaultValue, g.memory) // leerer Platzhalter
                any = true
            }
            val m = l.mask
            if (m != null && m !in sharedMasks && !stashedMasks.containsKey(l) && m.grid.byteSize > 0) {
                val g = m.grid
                stashedMasks[l] = disk.writeGrid(g)
                m.grid = TileGrid(g.width, g.height, g.defaultValue, g.memory)
                any = true
            }
        }
        return any
    }

    private fun reload() {
        val d = disk ?: return
        for ((l, s) in stashed) s.pixels?.let { l.pixels = d.readGrid(it); it.delete() }
        for ((l, f) in stashedMasks) { l.mask?.grid = d.readGrid(f); f.delete() }
        stashed.clear(); stashedMasks.clear()
    }

    override fun undo(doc: Document) { reload(); onUndo(doc) }
    override fun redo(doc: Document) { reload(); onRedo(doc) }
    override fun dispose() {
        stashed.values.forEach { it.pixels?.delete() }; stashedMasks.values.forEach { it.delete() }
        stashed.clear(); stashedMasks.clear()
    }
}
