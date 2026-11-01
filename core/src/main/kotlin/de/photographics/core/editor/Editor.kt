package de.photographics.core.editor

import de.photographics.core.tr
import de.photographics.core.adjust.Adjustment
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.RasterLayer
import de.photographics.core.doc.SmartLayer
import de.photographics.core.doc.TextLayer
import de.photographics.core.doc.TextStyle
import de.photographics.core.image.Affine
import de.photographics.core.image.Resample
import de.photographics.core.image.ResampleMethod
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.filters.FilterRunner
import de.photographics.core.filters.ImageFilter
import de.photographics.core.history.History
import de.photographics.core.history.HistoryEntry
import de.photographics.core.history.DocumentChangeEntry
import de.photographics.core.history.LambdaEntry
import de.photographics.core.history.LayerOperationEntry
import de.photographics.core.history.TilePatch
import de.photographics.core.history.TileTransaction
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.image.Transforms
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import de.photographics.core.selection.Selection
import de.photographics.core.selection.SelectionOp
import de.photographics.core.retouch.Inpainter
import de.photographics.core.retouch.PatchMatchInpainter
import de.photographics.core.retouch.Poisson
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.Retouch
import de.photographics.core.tools.PaintTarget
import kotlin.math.roundToInt
import de.photographics.core.tools.PaintTool
import de.photographics.core.tools.Stroke
import de.photographics.core.tools.StrokeContext

/**
 * Alle Bearbeitungsbefehle mit Undo-Unterstützung. Nicht threadsicher: Aufrufe (und das Rendern)
 * müssen auf demselben Thread erfolgen.
 */
/** Photoshop-Blöcke mit Ebeneneffekten (neu, mehrfach, alt). */
private val EFFECT_KEYS = setOf("lfx2", "lmfx", "lrFX")

class Editor(doc: Document) {
    var doc: Document = doc
        private set
    val history = History()
    private var layerCounter = doc.allLayers().size

    fun replaceDocument(d: Document) {
        doc = d
        history.clear()
        layerCounter = d.allLayers().size
    }

    // ------------------------------------------------------------ Verlauf

    /** Rückgabe: Bereich zum Neuzeichnen (null = alles). */
    fun undo(): IntRect? = history.undo(doc)?.let { (it as? TilePatch)?.bounds }
    fun redo(): IntRect? = history.redo(doc)?.let { (it as? TilePatch)?.bounds }

    fun push(e: HistoryEntry) { collecting?.add(e) ?: history.push(e) }

    /** Während [grouped] gesammelte Schritte. */
    private var collecting: ArrayList<HistoryEntry>? = null

    /** Alle Verlaufsschritte aus [block] als einen Schritt [label] zusammenfassen. */
    fun <T> grouped(label: String, block: () -> T): T {
        val outer = collecting
        val sink = ArrayList<HistoryEntry>()
        collecting = sink
        try {
            return block()
        } finally {
            collecting = outer
            if (sink.isNotEmpty()) push(if (sink.size == 1) sink[0] else de.photographics.core.history.CompoundEntry(label, sink))
        }
    }

    // ------------------------------------------------------------ Ebenen

    private fun nextName() = tr("Ebene {0}", ++layerCounter)

    /** [retained]: Raster, die nur noch der Verlauf festhält (zählen zum Speicherbudget). */
    private fun structural(label: String, apply: () -> Unit, revert: () -> Unit) = structural(label, 0L, apply, revert)

    private fun structural(label: String, retained: Long, apply: () -> Unit, revert: () -> Unit) {
        val activeBefore = doc.activeLayer
        apply()
        val activeAfter = doc.activeLayer
        push(LambdaEntry(label, { revert(); it.activeLayer = activeBefore }, { apply(); it.activeLayer = activeAfter }, retained))
    }

    /** Ebenen-Operation, deren nicht eingehängte Ebenen ([involved]) ausgelagert werden können. */
    private fun layerOperation(label: String, involved: List<Layer>, apply: () -> Unit, revert: () -> Unit) {
        val activeBefore = doc.activeLayer
        apply()
        val activeAfter = doc.activeLayer
        push(LayerOperationEntry(label, { revert(); it.activeLayer = activeBefore }, { apply(); it.activeLayer = activeAfter },
            involved) { l -> doc.allLayers().any { it === l } })
    }

    private fun Layer.retainedBytes(): Long = when (this) {
        is GroupLayer -> children.sumOf { it.retainedBytes() }
        is SmartLayer -> source.byteSize + raster.byteSize
        is RasterLayer -> raster.byteSize
        else -> 0L
    } + (mask?.grid?.byteSize ?: 0L)

    private fun insertionPoint(): Pair<GroupLayer, Int> {
        val a = doc.activeLayer ?: return doc.root to doc.root.children.size
        if (a is GroupLayer && a.expanded) return a to a.children.size
        val p = doc.parentOf(a)
        return p to p.children.indexOf(a) + 1
    }

    fun addLayer(name: String = nextName(), grid: TileGrid = doc.newGrid(), label: String = tr("Neue Ebene")): PixelLayer {
        val layer = PixelLayer(name, grid)
        // Innerhalb einer Schnittmaske angelegte Ebenen gehören wie in Photoshop dazu
        layer.clipped = doc.activeLayer?.clipped == true
        val (parent, index) = insertionPoint()
        structural(label, { parent.add(layer, index); doc.activeLayer = layer }, { parent.remove(layer) })
        return layer
    }

    fun addGroup(): GroupLayer {
        val g = GroupLayer(tr("Gruppe {0}", ++layerCounter))
        val (parent, index) = insertionPoint()
        structural(tr("Neue Gruppe"), { parent.add(g, index); doc.activeLayer = g }, { parent.remove(g) })
        return g
    }

    /** Fasst die aktive Ebene in eine neue Gruppe. */
    fun groupActive() {
        val a = doc.activeLayer ?: return
        val p = doc.parentOf(a)
        val idx = p.children.indexOf(a)
        val g = GroupLayer(tr("Gruppe {0}", ++layerCounter))
        structural(tr("Ebene gruppieren"), {
            p.add(g, idx); g.add(a); doc.activeLayer = g
        }, {
            p.remove(g); p.add(a, idx)
        })
    }

    fun duplicateLayer() {
        val a = doc.activeLayer ?: return
        val copy = a.deepCopy()
        val p = doc.parentOf(a)
        val idx = p.children.indexOf(a) + 1
        structural(tr("Ebene duplizieren"), { p.add(copy, idx); doc.activeLayer = copy }, { p.remove(copy) })
    }

    fun deleteLayer() {
        val a = doc.activeLayer ?: return
        val p = doc.parentOf(a)
        val idx = p.children.indexOf(a)
        layerOperation(tr("Ebene löschen"), listOf(a), {
            p.remove(a)
            doc.activeLayer = p.children.getOrNull(idx - 1) ?: p.children.getOrNull(idx) ?: (if (p !== doc.root) p else doc.allLayers().lastOrNull())
        }, { p.add(a, idx) })
    }

    /** Verschiebt die Ebene im Stapel nach oben (+1) oder unten (-1), auch in Gruppen hinein/heraus. */
    fun moveLayer(layer: Layer, up: Boolean) {
        val p = doc.parentOf(layer)
        val idx = p.children.indexOf(layer)
        val target: Pair<GroupLayer, Int>?
        if (up) {
            val above = p.children.getOrNull(idx + 1)
            target = when {
                above is GroupLayer && above.expanded -> above to 0
                above != null -> p to idx + 1
                p !== doc.root -> doc.parentOf(p).let { gp -> gp to gp.children.indexOf(p) + 1 }
                else -> null
            }
        } else {
            val below = p.children.getOrNull(idx - 1)
            target = when {
                below is GroupLayer && below.expanded -> below to below.children.size
                below != null -> p to idx - 1
                p !== doc.root -> doc.parentOf(p).let { gp -> gp to gp.children.indexOf(p) }
                else -> null
            }
        }
        val (np, ni) = target ?: return
        structural(tr("Ebene verschieben"), { np.add(layer, ni) }, { p.add(layer, idx) })
    }

    // ------------------------------------------------------------ Mehrere Ebenen (Ebenenbedienfeld)

    /** [layers] ohne Ebenen, die schon über eine ausgewählte Gruppe enthalten sind; in Stapelreihenfolge (unten zuerst). */
    private fun topLevel(layers: Collection<Layer>): List<Layer> {
        val set = layers.toSet()
        val order = doc.allLayers()
        return order.filter { l -> l in set && set.none { g -> g !== l && g is GroupLayer && doc.isDescendant(l, g) } }
    }

    /**
     * Verschiebt [layers] (Drag & Drop im Ebenenbedienfeld) nach [parent] an die Stelle [index]
     * (Index in den Kindern von [parent] vor dem Verschieben, unten = 0). Liefert false, wenn das Ziel
     * in einer der verschobenen Gruppen liegt.
     */
    fun moveLayersTo(layers: Collection<Layer>, parent: GroupLayer, index: Int): Boolean {
        val moving = topLevel(layers)
        if (moving.isEmpty()) return false
        if (moving.any { it === parent || (it is GroupLayer && doc.isDescendant(parent, it)) }) return false
        val before = moving.map { doc.parentOf(it) to doc.parentOf(it).children.indexOf(it) }
        // Zielposition unter den verbleibenden Kindern: verschobene Ebenen darunter verschieben den Index
        val stay = parent.children.filter { it !in moving }
        val anchor = parent.children.subList(0, index.coerceIn(0, parent.children.size)).count { it !in moving }
        val after = stay.toMutableList().also { it.addAll(anchor, moving) }
        if (after == parent.children && moving.all { doc.parentOf(it) === parent }) return false
        structural(tr("Ebene verschieben"), {
            for (l in moving) doc.parentOf(l).remove(l)
            var i = anchor
            for (l in moving) parent.add(l, i++)
        }, {
            for (l in moving) doc.parentOf(l).remove(l)
            // In umgekehrter Reihenfolge der Entnahme zurücklegen, damit die alten Indizes stimmen
            for ((k, l) in moving.withIndex().sortedBy { (k, _) -> before[k].second }) before[k].first.add(l, before[k].second)
        })
        return true
    }

    /** Fasst [layers] in eine neue Gruppe an der Stelle der obersten (Strg+G mit Mehrfachauswahl). */
    fun groupLayers(layers: Collection<Layer>): GroupLayer? {
        val moving = topLevel(layers)
        if (moving.isEmpty()) return null
        val top = moving.last()
        val p = doc.parentOf(top)
        val before = moving.map { doc.parentOf(it) to doc.parentOf(it).children.indexOf(it) }
        val g = GroupLayer(tr("Gruppe {0}", ++layerCounter))
        structural(tr("Ebenen gruppieren"), {
            val idx = p.children.indexOf(top) + 1 - moving.count { doc.parentOf(it) === p && p.children.indexOf(it) < p.children.indexOf(top) + 1 }
            for (l in moving) doc.parentOf(l).remove(l)
            p.add(g, idx.coerceAtLeast(0))
            for (l in moving) g.add(l)
            doc.activeLayer = g
        }, {
            p.remove(g)
            for ((k, l) in moving.withIndex().sortedBy { (k, _) -> before[k].second }) before[k].first.add(l, before[k].second)
        })
        return g
    }

    /** Löscht [layers]; mindestens eine Ebene bleibt im Dokument. */
    fun deleteLayers(layers: Collection<Layer>) {
        val del = topLevel(layers)
        if (del.isEmpty() || del.size >= doc.root.children.size && del.all { doc.parentOf(it) === doc.root }) return
        val before = del.map { doc.parentOf(it) to doc.parentOf(it).children.indexOf(it) }
        layerOperation(tr("Ebenen löschen"), del, {
            for (l in del) doc.parentOf(l).remove(l)
            val (p, i) = before.first()
            doc.activeLayer = p.children.getOrNull(i - 1) ?: p.children.getOrNull(i) ?: (if (p !== doc.root) p else doc.allLayers().lastOrNull())
        }, {
            for ((k, l) in del.withIndex().sortedBy { (k, _) -> before[k].second }) before[k].first.add(l, before[k].second)
        })
    }

    /** Dupliziert [layers]; jede Kopie liegt über ihrem Original, die oberste Kopie wird aktiv. */
    fun duplicateLayers(layers: Collection<Layer>): List<Layer> {
        val src = topLevel(layers)
        val copies = src.map { it.deepCopy() }
        structural(tr("Ebene duplizieren"), {
            for ((l, c) in src.zip(copies)) { val p = doc.parentOf(l); p.add(c, p.children.indexOf(l) + 1) }
            doc.activeLayer = copies.lastOrNull()
        }, {
            for (c in copies) doc.parentOf(c).remove(c)
        })
        return copies
    }

    /** Reduziert [layers] auf eine Pixelebene an der Stelle der obersten (Strg+E mit Mehrfachauswahl). */
    fun mergeLayers(layers: Collection<Layer>): Boolean {
        val src = topLevel(layers)
        if (src.size < 2) return false
        history.makeRoom(doc.width.toLong() * doc.height * 12)
        val tmp = Document(doc.width, doc.height)
        var area = doc.bounds
        for (l in src) {
            if (!l.visible) continue
            tmp.root.add(l.deepCopy().also { c -> c.name = l.name })
            if (l is RasterLayer) area = area.union(l.raster.extent)
        }
        val top = src.last()
        val merged = renderedLayer(top.name, tmp, area)
        val p = doc.parentOf(top)
        val before = src.map { doc.parentOf(it) to doc.parentOf(it).children.indexOf(it) }
        layerOperation(tr("Auf eine Ebene reduzieren"), src + merged, {
            val idx = p.children.indexOf(top) + 1 - src.count { doc.parentOf(it) === p && p.children.indexOf(it) <= p.children.indexOf(top) }
            for (l in src) doc.parentOf(l).remove(l)
            p.add(merged, idx.coerceAtLeast(0))
            doc.activeLayer = merged
        }, {
            p.remove(merged)
            for ((k, l) in src.withIndex().sortedBy { (k, _) -> before[k].second }) before[k].first.add(l, before[k].second)
        })
        return true
    }

    /** Transparenz einer Ebene (Raster × Maske) als Auswahl laden (Photoshop: Strg+Klick aufs Miniaturbild). */
    fun selectionFromLayer(layer: Layer, mask: Boolean = false, op: SelectionOp = SelectionOp.REPLACE) {
        val g = TileGrid(doc.width, doc.height)
        val band = maxOf(1, (1 shl 20) / doc.width)
        var y = 0
        val src = if (mask) layer.mask?.grid else (layer as? RasterLayer)?.raster
        if (src == null) return
        while (y < doc.height) {
            val r = IntRect(0, y, doc.width, minOf(doc.height, y + band))
            val px = IntArray(r.width * r.height)
            src.readRect(r, px)
            if (mask) for (i in px.indices) px[i] = px[i] and 0xFF
            else for (i in px.indices) px[i] = px[i] ushr 24
            g.writeRect(r, px)
            y = r.bottom
        }
        g.compact()
        val sel = Selection(g, Selection.outlinesOf(g))
        val existing = doc.selection
        setSelection(if (existing != null && op != SelectionOp.REPLACE) existing.combine(sel, op) else sel, tr("Auswahl laden"))
    }

    fun setActive(layer: Layer?) { doc.activeLayer = layer }

    /** Übernimmt interaktiv geänderte Eigenschaften (z. B. nach dem Loslassen eines Reglers) in den Verlauf. */
    fun commitProps(layer: Layer, before: Layer.Props, label: String = tr("Ebeneneigenschaften")) {
        val after = layer.props()
        if (after == before) return
        push(LambdaEntry(label, { layer.applyProps(before) }, { layer.applyProps(after) }))
    }

    fun setProps(layer: Layer, label: String, change: Layer.() -> Unit) {
        val before = layer.props()
        layer.change()
        commitProps(layer, before, label)
    }

    /** Auf die darunterliegende Ebene reduzieren. */
    fun mergeDown(): Boolean {
        history.makeRoom(doc.width.toLong() * doc.height * 12)
        val upper = doc.activeLayer ?: return false
        val p = doc.parentOf(upper)
        val idx = p.children.indexOf(upper)
        val lower = p.children.getOrNull(idx - 1) as? RasterLayer ?: return false

        val tmp = Document(doc.width, doc.height)
        val lc = lower.deepCopy().apply { opacity = 1f; fillOpacity = 1f; blendMode = BlendMode.NORMAL; visible = true; clipped = false }
        val uc = upper.deepCopy().apply { visible = true }
        tmp.root.add(lc); tmp.root.add(uc)
        // Auch Bereiche außerhalb der Arbeitsfläche zusammenfügen (wie Photoshop)
        val area = lower.raster.extent.union((upper as? RasterLayer)?.raster?.extent ?: doc.bounds)
        val merged = renderedLayer(lower.name, tmp, area).apply {
            opacity = lower.opacity; fillOpacity = lower.fillOpacity; blendMode = lower.blendMode
            visible = lower.visible; clipped = lower.clipped; psdId = lower.psdId
        }
        layerOperation(tr("Auf eine Ebene reduzieren"), listOf(upper, lower, merged), {
            p.remove(upper); p.remove(lower); p.add(merged, idx - 1); doc.activeLayer = merged
        }, {
            p.remove(merged); p.add(lower, idx - 1); p.add(upper, idx)
        })
        return true
    }

    fun flatten() {
        history.makeRoom(doc.width.toLong() * doc.height * 12)
        // Auf Weiß reduzieren wie Photoshops "Auf Hintergrundebene reduzieren"
        val flat = if (doc.bitDepth == 16) Compositor.renderGridDeep(doc, onWhite = true).let { (hi, lo) -> PixelLayer(tr("Hintergrund"), hi).also { it.low = lo } }
        else PixelLayer(tr("Hintergrund"), Compositor.renderGrid(doc) { px ->
            for (i in px.indices) px[i] = Colors.srcOver(0xFFFFFFFF.toInt(), px[i])
        })
        val old = doc.root.children.toList()
        layerOperation(tr("Auf Hintergrundebene reduzieren"), old + flat, {
            for (c in old) doc.root.remove(c)
            doc.root.add(flat); doc.activeLayer = flat
        }, {
            doc.root.remove(flat)
            for (c in old) doc.root.add(c)
        })
    }

    // ------------------------------------------------------------ Masken

    fun addMask(fromSelection: Boolean) {
        val a = doc.activeLayer ?: return
        if (a.mask != null) return
        val sel = doc.selection
        val grid = if (fromSelection && sel != null) sel.mask.copy() else TileGrid(doc.width, doc.height, 255)
        val mask = LayerMask(grid)
        structural(tr("Ebenenmaske hinzufügen"), { a.mask = mask }, { a.mask = null })
    }

    fun deleteMask() {
        val a = doc.activeLayer ?: return
        val m = a.mask ?: return
        structural(tr("Ebenenmaske löschen"), m.grid.byteSize, { a.mask = null }, { a.mask = m })
    }

    /** Maske auf die Pixel anwenden und entfernen. */
    fun applyMask() {
        val a = doc.activeLayer as? PixelLayer ?: return
        val m = a.mask ?: return
        val oldGrid = a.pixels
        val newGrid = oldGrid.copy()
        for (ty in 0 until newGrid.tilesY) for (tx in 0 until newGrid.tilesX) {
            val tT = newGrid.tile(tx, ty) ?: continue
            val tr = newGrid.tileRect(tx, ty)
            val mT = m.grid.tileAt(tr.left, tr.top) // Maske kann eine andere Ausdehnung haben
            val t = tT.pin(); val mt = mT?.pin(forWrite = false)
            for (i in t.indices) {
                val mv = mt?.get(i) ?: m.defaultColor
                if (mv == 255) continue
                t[i] = Colors.withAlpha(t[i], (t[i] ushr 24) * mv / 255)
            }
            tT.unpin(); mT?.unpin()
        }
        newGrid.compact()
        structural(tr("Ebenenmaske anwenden"), oldGrid.byteSize + m.grid.byteSize, { a.pixels = newGrid; a.mask = null }, { a.pixels = oldGrid; a.mask = m })
    }

    // ------------------------------------------------------------ Pinselstriche & Filter

    fun beginStroke(tool: PaintTool, settings: BrushSettings, target: PaintTarget): Stroke? {
        val layer = doc.activeLayer ?: return null
        val grid = when (target) {
            PaintTarget.PIXELS -> (layer as? PixelLayer)?.pixels
            PaintTarget.MASK -> if (tool.supportsMask) layer.mask?.grid else null
        } ?: return null
        if (!layer.visible) return null
        return Stroke(StrokeContext(doc, layer, grid, target, settings), tool)
    }

    /** Beendet einen Strich und legt ihn im Verlauf ab. */
    fun endStroke(stroke: Stroke): IntRect {
        val (patch, dirty) = stroke.finish()
        if (patch != null) push(patch)
        return dirty
    }

    fun previewFilter(filter: ImageFilter): Pair<TileTransaction, IntRect>? {
        history.makeRoom(doc.width.toLong() * doc.height * 4 + 64L * 1_000_000)
        val layer = doc.activeLayer as? PixelLayer ?: return null
        return FilterRunner.apply(doc, layer, filter)
    }

    fun commitFilter(tx: TileTransaction, label: String) {
        tx.commit(label)?.let { push(it) }
    }

    // ------------------------------------------------------------ Verschieben

    /**
     * Verschieben übernehmen. Eine verbundene Maske wandert mit; bei einer nicht verbundenen wird
     * je nach [target] nur die Ebene oder nur die Maske verschoben (wie in Photoshop).
     */
    fun commitMove(layer: RasterLayer, dx: Int, dy: Int, target: PaintTarget = PaintTarget.PIXELS) {
        layer.previewDx = 0; layer.previewDy = 0
        layer.mask?.let { it.previewDx = 0; it.previewDy = 0 }
        if (dx == 0 && dy == 0) return
        val mask = layer.mask
        val maskOnly = mask != null && !mask.linked && target == PaintTarget.MASK
        val moveMask = mask != null && (mask.linked || maskOnly)
        val oldMask = mask?.grid; val newMask = if (moveMask) oldMask?.shifted(dx, dy) else null
        if (maskOnly) {
            structural(tr("Maske verschieben"), oldMask!!.byteSize, { mask!!.grid = newMask!! }, { mask!!.grid = oldMask })
            return
        }
        when (layer) {
            is PixelLayer -> {
                val oldPx = layer.pixels; val newPx = oldPx.shifted(dx, dy)
                val oldLow = layer.low; val newLow = oldLow?.shifted(dx, dy)
                structural(tr("Verschieben"), oldPx.byteSize + (oldMask?.byteSize ?: 0L) + (oldLow?.byteSize ?: 0L), {
                    layer.pixels = newPx; layer.low = newLow; if (newMask != null) layer.mask?.grid = newMask
                }, {
                    layer.pixels = oldPx; layer.low = oldLow; if (oldMask != null) layer.mask?.grid = oldMask
                })
            }
            is TextLayer, is SmartLayer, is de.photographics.core.doc.ShapeLayer -> {
                val old = geometryOf(layer)
                val shift = Affine.translate(dx.toDouble(), dy.toDouble())
                val new = Geometry(old.transform.then(shift), old.warp?.let { SmartLayer.mapQuad(it, shift) })
                structural(tr("Verschieben"), {
                    setGeometry(layer, new); if (newMask != null) layer.mask?.grid = newMask
                }, {
                    setGeometry(layer, old); if (oldMask != null) layer.mask?.grid = oldMask
                })
            }
        }
    }

    /** Lage einer Text- oder Smartobjektebene: Matrix und (nur Smartobjekte) verzerrte Ecken. */
    class Geometry(val transform: Affine, val warp: DoubleArray? = null)

    fun geometryOf(layer: Layer): Geometry = Geometry(transformOf(layer), (layer as? SmartLayer)?.warp)

    private fun setGeometry(layer: Layer, g: Geometry) {
        setTransform(layer, g.transform)
        if (layer is SmartLayer) layer.warp = g.warp
    }

    /** Verzerrung während des Ziehens (schnelle Vorschau direkt aus der Quelle). */
    fun previewWarp(layer: SmartLayer, quad: DoubleArray) = layer.setPreviewWarp(quad)

    /**
     * Verzerren/Perspektive übernehmen: [before] → [after] in den Verlauf; eine verbundene Maske
     * wird mit derselben (projektiven) Abbildung mitgeführt.
     */
    fun commitGeometry(layer: SmartLayer, before: Geometry, after: Geometry) {
        val mask = layer.mask?.takeIf { it.linked }
        val same = before.transform == after.transform && (before.warp?.contentEquals(after.warp ?: DoubleArray(0)) ?: (after.warp == null))
        if (same) { setGeometry(layer, before); return }
        if (mask == null) {
            structural(tr("Verzerren"), { setGeometry(layer, after) }, { setGeometry(layer, before) })
            return
        }
        val sw = layer.source.width.toDouble(); val sh = layer.source.height.toDouble()
        fun hom(g: Geometry) = g.warp?.let { de.photographics.core.image.Homography.rectToQuad(sw, sh, it) } ?: de.photographics.core.image.Homography.of(g.transform)
        val oldMask = mask.grid
        val m = hom(before).inverse().then(hom(after))
        val newMask = transformMaskWith(oldMask, doc.width, doc.height) { argb -> de.photographics.core.image.Resample.renderProjective(argb, m, doc.width, doc.height) }
        structural(tr("Verzerren"), oldMask.byteSize, { setGeometry(layer, after); mask.grid = newMask }, { setGeometry(layer, before); mask.grid = oldMask })
    }

    /**
     * Perspektivisch freistellen: das Viereck [quad] (Ecken oben links, oben rechts, unten rechts,
     * unten links im Dokument) wird zum geraden Bild [outW]×[outH]. Textebenen werden dafür vorher
     * in Pixel umgewandelt (Text lässt sich nicht perspektivisch verzerren); Smartobjekte bleiben
     * verlustfrei (Ecken).
     */
    fun perspectiveCrop(quad: DoubleArray, outW: Int, outH: Int) {
        require(quad.size == 8 && outW > 0 && outH > 0)
        if (!de.photographics.core.image.Homography.isConvex(quad)) return
        for (l in doc.allLayers().filterIsInstance<TextLayer>()) rasterize(l)
        val m = de.photographics.core.image.Homography.quadToRect(quad, outW.toDouble(), outH.toDouble())
        history.makeRoom(outW.toLong() * outH * 8)
        documentChange(tr("Perspektivisch freistellen")) {
            for (l in doc.allLayers()) {
                when (l) {
                    is PixelLayer -> { l.pixels = de.photographics.core.image.Resample.renderProjective(l.pixels, m, outW, outH); l.low = l.low?.let { de.photographics.core.image.Resample.renderProjective(it, m, outW, outH) } }
                    is SmartLayer -> {
                        val q = l.corners()
                        l.warp = DoubleArray(8) { i -> if (i % 2 == 0) m.mapX(q[i], q[i + 1]) else m.mapY(q[i - 1], q[i]) }
                    }
                    else -> {}
                }
                l.mask?.let { mk -> mk.grid = transformMaskWith(mk.grid, outW, outH) { argb -> de.photographics.core.image.Resample.renderProjective(argb, m, outW, outH) } }
            }
            doc.setSize(outW, outH)
            doc.guides = emptyList()
            doc.selection = null
            doc.paths = emptyList(); doc.workPath = null
            doc.channels = doc.channels.map { ch -> ch.copy(grid = transformMaskWith(ch.grid, outW, outH) { argb -> de.photographics.core.image.Resample.renderProjective(argb, m, outW, outH) }) }
        }
    }

    fun transformOf(layer: Layer): Affine = when (layer) {
        is TextLayer -> layer.transform
        is de.photographics.core.doc.ShapeLayer -> layer.transform
        is SmartLayer -> layer.transform
        else -> Affine.IDENTITY
    }

    private fun setTransform(layer: Layer, t: Affine) {
        when (layer) {
            is TextLayer -> { layer.transform = t; layer.unchangedFromPsd = false }
            is de.photographics.core.doc.ShapeLayer -> layer.boxTransform = t
            is SmartLayer -> layer.transform = t
            else -> {}
        }
    }

    // ------------------------------------------------------------ Smartobjekte & Transformieren

    /** Wandelt eine Pixelebene in ein Smartobjekt (Originalpixel bleiben erhalten). */
    fun convertToSmartObject(layer: PixelLayer): SmartLayer? {
        val b = layer.pixels.contentBounds()
        if (b.isEmpty) return null
        val smart = SmartLayer(layer.name, layer.pixels.resized(b.width, b.height, -b.left, -b.top),
            Affine.translate(b.left.toDouble(), b.top.toDouble()), doc.width, doc.height)
        copyLayerProps(layer, smart)
        smart.setCachedRaster(layer.pixels)
        replaceLayer(layer, smart, tr("In Smartobjekt konvertieren"))
        return smart
    }

    /** Text, Smartobjekt oder Einstellung in eine normale Pixelebene umwandeln. */
    fun rasterize(layer: Layer) {
        val px: TileGrid = when (layer) {
            is TextLayer -> layer.raster.copy()
            is SmartLayer -> layer.raster.copy()
            is de.photographics.core.doc.ShapeLayer -> layer.raster.copy()
            else -> return
        }
        val pl = PixelLayer(layer.name, px)
        copyLayerProps(layer, pl)
        replaceLayer(layer, pl, tr("Ebene rastern"))
    }

    private fun copyLayerProps(from: Layer, to: Layer) {
        to.applyProps(from.props()); to.mask = from.mask; to.psdId = from.psdId
        // Ebenenstil bleibt beim Rastern und Umwandeln erhalten (wie in Photoshop)
        to.preservedBlocks = from.preservedBlocks.filter { it.key in EFFECT_BLOCKS }
    }

    private fun replaceLayer(old: Layer, new: Layer, label: String) {
        val p = doc.parentOf(old)
        val idx = p.children.indexOf(old)
        val retained = old.retainedBytes() - (old.mask?.grid?.byteSize ?: 0L)
        layerOperation(label, listOf(old, new), { p.remove(old); p.add(new, idx); doc.activeLayer = new }, { p.remove(new); p.add(old, idx) })
    }

    /** Vorschau einer Transformation während des Ziehens (ohne Verlauf). */
    fun previewTransform(layer: Layer, t: Affine) {
        when (layer) {
            is SmartLayer -> layer.setPreviewTransform(t)
            is TextLayer -> layer.transform = t
            is de.photographics.core.doc.ShapeLayer -> layer.boxTransform = t
            else -> {}
        }
    }

    /** Übernimmt eine Transformation in den Verlauf; [before] ist der Ausgangszustand. */
    fun commitTransform(layer: Layer, before: Affine, after: Affine) {
        if (before == after) { setTransform(layer, before); (layer as? SmartLayer)?.fastPreview = false; return }
        val mask = layer.mask?.takeIf { it.linked }
        if (mask == null) {
            structural(tr("Frei transformieren"), { setTransform(layer, after) }, { setTransform(layer, before) })
            return
        }
        // Verbundene Maske mittransformieren (Abbildung vom alten in den neuen Zustand)
        val oldMask = mask.grid
        val newMask = transformMask(oldMask, before.inverse().then(after))
        structural(tr("Frei transformieren"), oldMask.byteSize, { setTransform(layer, after); mask.grid = newMask }, { setTransform(layer, before); mask.grid = oldMask })
    }

    /** Maske (Grauwerte) affin abbilden: über ein graues ARGB-Raster, außerhalb gilt der Standardwert. */
    private fun transformMask(g: TileGrid, m: Affine, outW: Int = doc.width, outH: Int = doc.height): TileGrid =
        transformMaskWith(g, outW, outH) { argb -> de.photographics.core.image.Resample.renderAffine(argb, m, outW, outH) }

    /** Maske (Grauwerte) mit [render] abbilden: über ein graues ARGB-Raster, außerhalb gilt der Standardwert. */
    private fun transformMaskWith(g: TileGrid, outW: Int, outH: Int, render: (TileGrid) -> TileGrid): TileGrid {
        val w = g.width; val h = g.height
        val def = g.defaultValue
        val argb = TileGrid(w, h)
        val band = maxOf(1, (1 shl 18) / w)
        var y = 0
        while (y < h) {
            val r = IntRect(0, y, w, minOf(h, y + band))
            val px = IntArray(r.width * r.height); g.readRect(r, px)
            for (i in px.indices) px[i] = (0xFF shl 24) or (px[i] * 0x010101)
            argb.writeRect(r, px); y = r.bottom
        }
        val moved = render(argb)
        val out = TileGrid(outW, outH, def)
        val outBand = maxOf(1, (1 shl 18) / outW)
        y = 0
        while (y < outH) {
            val r = IntRect(0, y, outW, minOf(outH, y + outBand))
            val px = IntArray(r.width * r.height); moved.readRect(r, px)
            for (i in px.indices) { val c = px[i]; val a = c ushr 24; px[i] = ((c and 0xFF) * a + def * (255 - a) + 127) / 255 }
            out.writeRect(r, px); y = r.bottom
        }
        out.compact()
        return out
    }

    /** Maske mit der Ebene verbinden bzw. lösen (Kettensymbol). */
    fun toggleMaskLink(layer: Layer) {
        val m = layer.mask ?: return
        setProps(layer, if (m.linked) tr("Maske lösen") else tr("Maske verbinden")) { mask?.linked = !(mask?.linked ?: true) }
    }

    /**
     * Schnittmaske erstellen: [layer] wird auf die Ebene darunter beschnitten (nicht für die unterste
     * Ebene einer Gruppe). Zurückwandeln löst wie in Photoshop auch alle darüberliegenden
     * beschnittenen Ebenen derselben Gruppe – sonst würden sie an einer anderen Basis hängen.
     */
    fun setClipping(layer: Layer, clip: Boolean): Boolean {
        val parent = doc.parentOf(layer) ?: return false
        val idx = parent.children.indexOf(layer)
        if (clip) {
            if (idx <= 0 || layer.clipped) return false
            setProps(layer, tr("Schnittmaske erstellen")) { clipped = true }
            return true
        }
        if (!layer.clipped) return false
        val affected = parent.children.drop(idx).takeWhile { it.clipped }
        structural(tr("Schnittmaske zurückwandeln"), { affected.forEach { it.clipped = false } }, { affected.forEach { it.clipped = true } })
        return true
    }

    // ------------------------------------------------------------ Einstellungs- und Textebenen

    fun addAdjustmentLayer(adjustment: Adjustment): AdjustmentLayer {
        val l = AdjustmentLayer(adjustment.label, adjustment)
        // Wie in Photoshop: mit Maske, bei aktiver Auswahl aus der Auswahl
        l.mask = LayerMask(doc.selection?.mask?.copy() ?: TileGrid(doc.width, doc.height, 255))
        l.clipped = doc.activeLayer?.clipped == true
        val (parent, index) = insertionPoint()
        structural(tr("Neue Einstellungsebene"), { parent.add(l, index); doc.activeLayer = l }, { parent.remove(l) })
        return l
    }

    /** Live-Änderung (ohne Verlauf); mit [commitAdjustment] abschließen. */
    fun previewAdjustment(layer: AdjustmentLayer, a: Adjustment) { layer.adjustment = a }

    fun commitAdjustment(layer: AdjustmentLayer, before: Adjustment) {
        val after = layer.adjustment
        if (before == after) return
        push(LambdaEntry(tr("{0} ändern", after.label), { layer.adjustment = before }, { layer.adjustment = after }))
    }

    /**
     * Ebenenstil live setzen (ohne Verlauf; null oder ohne Effekte entfernt ihn); mit [commitEffects]
     * abschließen. Gespeichert wird er wie in Photoshop als Block `lfx2` der Ebene.
     */
    fun previewEffects(layer: Layer, fx: de.photographics.core.doc.LayerEffects?) {
        val keep = layer.preservedBlocks.filter { it.key !in EFFECT_KEYS }
        layer.preservedBlocks = if (fx == null || !fx.hasAny) keep else keep + de.photographics.core.psd.PsdEffects.toBlock(fx)
    }

    /** Ebenenstil-Änderung seit [before] (vorherige Blöcke der Ebene) als ein Schritt in den Verlauf. */
    fun commitEffects(layer: Layer, before: List<de.photographics.core.psd.PsdTaggedBlock>) {
        val after = layer.preservedBlocks
        if (after == before) return
        push(LambdaEntry(tr("Ebenenstil"), { layer.preservedBlocks = before }, { layer.preservedBlocks = after }))
    }

    fun setEffects(layer: Layer, fx: de.photographics.core.doc.LayerEffects?) {
        val before = layer.preservedBlocks
        previewEffects(layer, fx)
        commitEffects(layer, before)
    }

    fun addTextLayer(style: TextStyle, x: Double, y: Double): TextLayer {
        val l = TextLayer(style.text.lineSequence().first().take(30).ifBlank { tr("Text") }, style,
            Affine.translate(x, y), doc.width, doc.height)
        val (parent, index) = insertionPoint()
        structural(tr("Text erstellen"), { parent.add(l, index); doc.activeLayer = l }, { parent.remove(l) })
        return l
    }

    fun updateText(layer: TextLayer, style: TextStyle) {
        val before = layer.style; val beforeName = layer.name; val beforeOrig = layer.unchangedFromPsd
        if (before == style) return
        val newName = style.text.lineSequence().first().take(30).ifBlank { tr("Text") }
        val apply = { layer.style = style; layer.name = newName }
        apply()
        push(LambdaEntry(tr("Text bearbeiten"), {
            layer.style = before; layer.name = beforeName; layer.unchangedFromPsd = beforeOrig
        }, { apply() }))
    }

    // ------------------------------------------------------------ Bild

    /** Gesamtgröße aller Raster des Dokuments (für Speicherreservierungen). */
    private fun documentBytes(): Long = doc.allLayers().sumOf { l ->
        ((l as? RasterLayer)?.raster?.byteSize ?: 0L) + (l.mask?.grid?.byteSize ?: 0L)
    }

    private fun documentChange(label: String, newBytes: Long = documentBytes(), change: () -> Unit) {
        history.makeRoom(newBytes)
        val before = doc.snapshot()
        change()
        push(DocumentChangeEntry(label, before, doc.snapshot()))
    }

    /** Freistellen; mit [deleteCropped] = false bleiben Pixel außerhalb erhalten (Photoshop: „Beschnittene Pixel löschen“ aus). */
    fun crop(rect: IntRect, deleteCropped: Boolean = true) {
        val r = if (deleteCropped) rect.intersect(doc.bounds) else rect
        if (r.isEmpty || r == doc.bounds) return
        documentChange(tr("Freistellen")) { doc.resizeCanvas(r.width, r.height, -r.left, -r.top, keepOutside = !deleteCropped) }
    }

    /**
     * Gedreht freistellen: Rechteck der Größe [w]×[h] mit Mittelpunkt ([cx], [cy]) im
     * Dokument, um [angleDeg] gedreht (im Uhrzeigersinn), wird zum neuen, geraden Bild.
     */
    fun cropRotated(cx: Double, cy: Double, w: Int, h: Int, angleDeg: Double) {
        if (w <= 0 || h <= 0) return
        if (kotlin.math.abs(angleDeg) < 1e-6) {
            crop(IntRect((cx - w / 2.0).roundToInt(), (cy - h / 2.0).roundToInt(), (cx - w / 2.0).roundToInt() + w, (cy - h / 2.0).roundToInt() + h))
            return
        }
        val m = Affine.translate(-cx, -cy).then(Affine.rotate(-angleDeg)).then(Affine.translate(w / 2.0, h / 2.0))
        transformDocument(tr("Freistellen (gedreht)"), m, w, h)
    }

    /**
     * Ganzes Bild um [angleDeg] drehen (im Uhrzeigersinn); die Arbeitsfläche wächst, damit nichts
     * abgeschnitten wird. Mit [fill] werden die neuen Ecken der untersten Ebene gefüllt, wenn diese
     * deckend ist (wie Photoshops Hintergrundebene).
     */
    fun rotateArbitrary(angleDeg: Double, fill: Int? = null) {
        if (kotlin.math.abs(angleDeg % 360.0) < 1e-6) return
        val r = Math.toRadians(angleDeg)
        val c = kotlin.math.abs(kotlin.math.cos(r)); val sn = kotlin.math.abs(kotlin.math.sin(r))
        val nw = kotlin.math.ceil(doc.width * c + doc.height * sn - 1e-6).toInt()
        val nh = kotlin.math.ceil(doc.width * sn + doc.height * c - 1e-6).toInt()
        val m = Affine.translate(-doc.width / 2.0, -doc.height / 2.0).then(Affine.rotate(angleDeg)).then(Affine.translate(nw / 2.0, nh / 2.0))
        val bottom = doc.root.children.firstOrNull() as? PixelLayer
        val fillBottom = fill != null && bottom != null && isOpaque(bottom.pixels)
        transformDocument(tr("Bild drehen"), m, nw, nh) {
            if (fillBottom) {
                val g = bottom!!.pixels
                val band = maxOf(1, (1 shl 18) / nw)
                var y = 0
                while (y < nh) {
                    val b = IntRect(0, y, nw, minOf(nh, y + band)); val px = IntArray(b.width * b.height); g.readRect(b, px)
                    for (i in px.indices) px[i] = de.photographics.core.image.Colors.srcOver(fill!!, px[i])
                    g.writeRect(b, px); y = b.bottom
                }
            }
        }
    }

    private fun isOpaque(g: TileGrid): Boolean {
        val band = maxOf(1, (1 shl 18) / doc.width)
        var y = 0
        while (y < doc.height) {
            val r = IntRect(0, y, doc.width, minOf(doc.height, y + band)); val px = IntArray(r.width * r.height); g.readRect(r, px)
            for (c in px) if ((c ushr 24) != 0xFF) return false
            y = r.bottom
        }
        return true
    }

    /**
     * Alle Ebenen mit [m] (alte → neue Dokumentkoordinaten) auf eine Fläche [nw]×[nh] abbilden:
     * Pixelebenen und Masken bikubisch, Text und Smartobjekte über ihre Matrix (verlustfrei).
     */
    fun transformDocument(label: String, m: Affine, nw: Int, nh: Int, after: () -> Unit = {}) {
        history.makeRoom(nw.toLong() * nh * 8)
        documentChange(label) {
            for (l in doc.allLayers()) {
                when (l) {
                    is PixelLayer -> { l.pixels = de.photographics.core.image.Resample.renderAffine(l.pixels, m, nw, nh); l.low = l.low?.let { de.photographics.core.image.Resample.renderAffine(it, m, nw, nh) } }
                    is TextLayer -> l.transform = l.transform.then(m)
                    is de.photographics.core.doc.ShapeLayer -> l.transform = l.transform.then(m)
                    is SmartLayer -> l.applyDocTransform(m)
                    else -> {}
                }
                l.mask?.let { it.grid = transformMask(it.grid, m, nw, nh) }
            }
            doc.guides = mapGuides(doc.guides, m).filter { it.position in 0.0..(if (it.vertical) nw else nh).toDouble() }
            mapExtras(m) { g -> transformMask(g, m, nw, nh) }
            doc.setSize(nw, nh)
            doc.selection = null
            after()
        }
    }

    fun rotate(clockwise: Boolean) {
        val w = doc.width.toDouble(); val h = doc.height.toDouble()
        // Abbildung alter Koordinaten auf die gedrehte Fläche (für Text/Smartobjekte)
        val m = if (clockwise) Affine(0.0, 1.0, -1.0, 0.0, h, 0.0) else Affine(0.0, -1.0, 1.0, 0.0, 0.0, w)
        transformAll(if (clockwise) tr("90° im UZS drehen") else tr("90° gegen UZS drehen"), swap = true, m) {
            Transforms.rotate90(it, clockwise)
        }
    }

    fun flip(horizontal: Boolean) {
        val m = if (horizontal) Affine(-1.0, 0.0, 0.0, 1.0, doc.width.toDouble(), 0.0) else Affine(1.0, 0.0, 0.0, -1.0, 0.0, doc.height.toDouble())
        transformAll(if (horizontal) tr("Horizontal spiegeln") else tr("Vertikal spiegeln"), swap = false, m) {
            if (horizontal) Transforms.flipHorizontal(it) else Transforms.flipVertical(it)
        }
    }

    /**
     * Ändert die Bildgröße. [upscaler] (optional, z. B. KI) vergrößert Pixelebenen vorab um einen
     * ganzzahligen Faktor; danach wird mit [method] auf die Zielgröße gebracht.
     */
    fun resizeImage(newW: Int, newH: Int, method: ResampleMethod = ResampleMethod.BICUBIC, upscaler: Upscaler? = null) {
        if (newW <= 0 || newH <= 0 || (newW == doc.width && newH == doc.height)) return
        val w = doc.width; val h = doc.height
        val scale = Affine.scale(newW.toDouble() / w, newH.toDouble() / h)
        documentChange(tr("Bildgröße"), (documentBytes() * newW.toDouble() * newH / (w.toDouble() * h)).toLong()) {
            for (l in doc.allLayers()) {
                when (l) {
                    // KI nur für Ebenen innerhalb der Arbeitsfläche; herausragende Ebenen klassisch (verlustfrei mitskaliert)
                    is PixelLayer -> l.pixels = if (upscaler != null && (newW > w || newH > h) && !l.pixels.extendsBeyondCanvas) upscaler.upscale(l.pixels, newW, newH)
                        else Resample.resizeGrid(l.pixels, newW, newH, method)
                    is TextLayer -> l.transform = l.transform.then(scale)
                    is de.photographics.core.doc.ShapeLayer -> l.transform = l.transform.then(scale)
                    is SmartLayer -> l.applyDocTransform(scale)
                    else -> {}
                }
                // 16 Bit: untere Bits mitskalieren (Abweichung höchstens eine 8-Bit-Stufe)
                if (l is PixelLayer) l.low = l.low?.let { lo -> Resample.resizeGrid(lo, newW, newH, method) }
                l.mask?.let { m -> m.grid = Resample.resizeGrid(m.grid, newW, newH, method, gray = true) }
            }
            doc.guides = mapGuides(doc.guides, scale)
            mapExtras(scale) { g -> transformMask(g, scale, newW, newH) }
            doc.setSize(newW, newH)
        }
    }

    /** Arbeitsfläche ändern; [anchorX]/[anchorY] in 0..1 (0 = links/oben). */
    /**
     * Arbeitsfläche ändern. Mit [extensionColor] wird der neu hinzugekommene Bereich der untersten
     * Ebene damit gefüllt (Photoshop: „Farbe für erweiterte Arbeitsfläche“, meist die Hintergrundfarbe).
     */
    fun resizeCanvas(newW: Int, newH: Int, anchorX: Float, anchorY: Float, extensionColor: Int? = null) {
        if (newW <= 0 || newH <= 0 || (newW == doc.width && newH == doc.height)) return
        val oldW = doc.width; val oldH = doc.height
        val dx = ((newW - doc.width) * anchorX).toInt(); val dy = ((newH - doc.height) * anchorY).toInt()
        documentChange(tr("Arbeitsfläche")) {
            doc.resizeCanvas(newW, newH, dx, dy)
            val bottom = doc.root.children.firstOrNull() as? PixelLayer
            if (extensionColor != null && bottom != null) {
                val old = IntRect(dx, dy, dx + oldW, dy + oldH)
                val canvas = IntRect.ofSize(newW, newH)
                val parts = listOf(
                    IntRect(0, 0, newW, old.top), IntRect(0, old.bottom, newW, newH),
                    IntRect(0, maxOf(0, old.top), old.left, minOf(newH, old.bottom)), IntRect(old.right, maxOf(0, old.top), newW, minOf(newH, old.bottom)),
                ).map { it.intersect(canvas) }.filter { !it.isEmpty }
                for (r in parts) {
                    val band = maxOf(1, (1 shl 18) / r.width)
                    var y = r.top
                    while (y < r.bottom) {
                        val b = IntRect(r.left, y, r.right, minOf(r.bottom, y + band))
                        val px = IntArray(b.width * b.height)
                        bottom.pixels.readRect(b, px)
                        // Hinausragende Pixel der Ebene bleiben sichtbar, darunter die Erweiterungsfarbe
                        for (i in px.indices) px[i] = de.photographics.core.image.Colors.srcOver(extensionColor, px[i])
                        bottom.pixels.writeRect(b, px)
                        y = b.bottom
                    }
                }
            }
        }
    }

    private fun transformAll(label: String, swap: Boolean, m: Affine, f: (TileGrid) -> TileGrid) {
        documentChange(label) {
            for (l in doc.allLayers()) {
                when (l) {
                    is PixelLayer -> { l.pixels = f(l.pixels); l.low = l.low?.let(f) }
                    is TextLayer -> l.transform = l.transform.then(m)
                    is de.photographics.core.doc.ShapeLayer -> l.transform = l.transform.then(m)
                    is SmartLayer -> l.applyDocTransform(m)
                    else -> {}
                }
                l.mask?.let { it.grid = f(it.grid) }
            }
            doc.guides = mapGuides(doc.guides, m)
            mapExtras(m, f)
            if (swap) doc.setSize(doc.height, doc.width) else doc.selection = null
        }
    }

    /**
     * Hilfslinien mit derselben Abbildung wie den Inhalt mitführen; Linien, die dabei nicht
     * waagerecht oder senkrecht bleiben (z. B. beim schrägen Drehen), fallen weg.
     */
    /** Pfade (über [m]) und Alphakanäle (über [grid]) einer Dokumenttransformation folgen lassen. */
    private fun mapExtras(m: Affine, grid: (TileGrid) -> TileGrid) {
        doc.paths = doc.paths.map { it.copy(path = it.path.map(m)) }
        doc.workPath = doc.workPath?.map(m)
        doc.channels = doc.channels.map { it.copy(grid = grid(it.grid)) }
    }

    private fun mapGuides(guides: List<de.photographics.core.doc.Guide>, m: Affine) = guides.mapNotNull { g ->
        val (x0, y0, x1, y1) = if (g.vertical) listOf(g.position, 0.0, g.position, 1000.0) else listOf(0.0, g.position, 1000.0, g.position)
        val ax = m.mapX(x0, y0); val ay = m.mapY(x0, y0); val bx = m.mapX(x1, y1); val by = m.mapY(x1, y1)
        when {
            kotlin.math.abs(ax - bx) < 1e-6 -> de.photographics.core.doc.Guide(true, ax)
            kotlin.math.abs(ay - by) < 1e-6 -> de.photographics.core.doc.Guide(false, ay)
            else -> null
        }
    }

    /** Hilfslinien ersetzen (Hinzufügen, Verschieben, Löschen – mit Rückgängig). */
    fun setGuides(label: String, guides: List<de.photographics.core.doc.Guide>) {
        val before = doc.guides
        if (before == guides) return
        doc.guides = guides
        push(LambdaEntry(label, { it.guides = before }, { it.guides = guides }))
    }

    // ------------------------------------------------------------ Farbmanagement

    /** Profil zuweisen: Pixelwerte bleiben, ihre Bedeutung (und damit die Darstellung) ändert sich. */
    fun assignProfile(p: de.photographics.core.color.IccProfile) {
        val before = doc.colorProfile
        if (before.bytes.contentEquals(p.bytes)) return
        doc.colorProfile = p
        push(LambdaEntry(tr("Profil zuweisen"), { it.colorProfile = before }, { it.colorProfile = p }))
    }

    /**
     * In Profil umwandeln: Farben bleiben (soweit im Zielfarbraum darstellbar), die Pixelwerte werden
     * umgerechnet. Pixelebenen und Textfarben werden umgewandelt; liefert die Anzahl der
     * Smartobjekte, deren Inhalt unverändert bleibt.
     */
    fun convertToProfile(p: de.photographics.core.color.IccProfile, intent: de.photographics.core.color.RenderingIntent, bpc: Boolean): Int {
        val before = doc.colorProfile
        val conv = de.photographics.core.color.ColorTransform.rgb(before, p, intent, bpc) ?: run { assignProfile(p); return 0 }
        history.makeRoom(documentBytes())
        val entries = ArrayList<HistoryEntry>()
        fun mapPx(px: IntArray) {
            for (i in px.indices) {
                val c = px[i]
                if ((c ushr 24) == 0) continue
                px[i] = (c and 0xFF000000.toInt()) or conv.map(c and 0xFFFFFF)
            }
        }
        var smart = 0
        for (l in doc.allLayers()) {
            when (l) {
                is PixelLayer -> {
                    val content = l.pixels.contentBounds()
                    if (content.isEmpty) continue
                    val tx = TileTransaction(l.pixels)
                    tx.touch(content)
                    val band = maxOf(16, 262_144 / maxOf(1, content.width))
                    val count = (content.height + band - 1) / band
                    de.photographics.core.image.Parallel.bands(count, content.width.toLong() * band * 8, compute = { i ->
                        val r = IntRect(content.left, content.top + i * band, content.right, minOf(content.bottom, content.top + (i + 1) * band))
                        val px = IntArray(r.width * r.height)
                        tx.readOriginal(r, px)
                        mapPx(px)
                        r to px
                    }, consume = { _, (r, px) -> l.pixels.writeRect(r, px) })
                    tx.commit(tr("In Profil umwandeln"))?.let { entries += it }
                }
                is TextLayer -> {
                    val old = l.style
                    val c = intArrayOf(old.color); mapPx(c)
                    val new = old.copy(color = c[0])
                    l.style = new
                    entries += LambdaEntry(tr("Textfarbe"), { l.style = old }, { l.style = new })
                }
                is SmartLayer -> smart++
                is de.photographics.core.doc.ShapeLayer -> {
                    val oldFill = l.fill; val oldStroke = l.stroke
                    val c = intArrayOf(oldFill ?: 0, oldStroke?.color ?: 0); mapPx(c)
                    val nf = oldFill?.let { c[0] }; val ns = oldStroke?.copy(color = c[1])
                    l.fill = nf; l.stroke = ns
                    entries += LambdaEntry(tr("Formfarbe"), { l.fill = oldFill; l.stroke = oldStroke }, { l.fill = nf; l.stroke = ns })
                }
                else -> {}
            }
        }
        doc.colorProfile = p
        entries += LambdaEntry(tr("Profil"), { it.colorProfile = before }, { it.colorProfile = p })
        push(de.photographics.core.history.CompoundEntry(tr("In Profil umwandeln"), entries))
        return smart
    }

    // ------------------------------------------------------------ Auswahl

    fun setSelection(sel: Selection?, label: String = tr("Auswahl")) {
        val before = doc.selection
        val after = sel?.takeIf { !it.isEmpty }
        if (before == null && after == null) return
        doc.selection = after
        push(LambdaEntry(label, { it.selection = before }, { it.selection = after }))
    }

    fun selectAll() = setSelection(Selection.all(doc.width, doc.height), tr("Alles auswählen"))
    fun deselect() = setSelection(null, tr("Auswahl aufheben"))
    fun invertSelection() { doc.selection?.let { setSelection(it.inverted(), tr("Auswahl umkehren")) } }
    fun featherSelection(radius: Float) { doc.selection?.let { setSelection(it.feathered(radius), tr("Weiche Auswahlkante")) } }

    /** Ausgewählte Pixel der aktiven Ebene löschen. */
    fun clearSelection(): IntRect? = editSelected(tr("Löschen")) { orig, s -> Colors.withAlpha(orig, (orig ushr 24) * (255 - s) / 255) }

    /**
     * Verlauf auf die aktive Ebene (Pixel) oder ihre Maske, innerhalb der Auswahl, als ein Schritt
     * im Verlauf. Liefert den geänderten Bereich oder null (keine passende Ebene).
     */
    fun fillGradient(target: de.photographics.core.tools.PaintTarget, g: Gradient, opacity: Float): IntRect? {
        val layer = doc.activeLayer ?: return null
        val grid = when (target) {
            de.photographics.core.tools.PaintTarget.PIXELS -> (layer as? PixelLayer)?.pixels
            de.photographics.core.tools.PaintTarget.MASK -> layer.mask?.grid
        } ?: return null
        val sel = doc.selection
        val area = (sel?.bounds ?: doc.bounds).intersect(doc.bounds)
        if (area.isEmpty) return null
        history.makeRoom(area.width.toLong() * area.height * 8)
        val tx = TileTransaction(grid)
        tx.touch(area)
        val low = (layer as? PixelLayer)?.low?.takeIf { target == de.photographics.core.tools.PaintTarget.PIXELS && doc.bitDepth == 16 }
        if (low != null) {
            // 16 Bit: stufenloser Verlauf in beide Raster
            tx.linked = TileTransaction(low).also { it.touch(area) }
            g.apply16(grid, low, area, opacity, sel?.mask, layer.lockTransparency)
        } else g.apply(grid, area, opacity, sel?.mask, scalar = target == de.photographics.core.tools.PaintTarget.MASK,
            lockAlpha = target == de.photographics.core.tools.PaintTarget.PIXELS && layer.lockTransparency)
        tx.commit(tr("Verlauf"))?.let { push(it) }
        return area
    }

    fun fillSelection(color: Int): IntRect? = editSelected(tr("Fläche füllen")) { orig, s ->
        Colors.srcOver(orig, Colors.withAlpha(color, Colors.a(color) * s / 255))
    }

    /**
     * Fläche füllen (Umschalt+F5): Auswahl der aktiven Pixelebene – ohne Auswahl die ganze Ebene –
     * mit [color] in Mischmodus [mode] und [opacity]; [preserveTransparency] wie in Photoshop.
     */
    fun fillArea(color: Int, mode: de.photographics.core.render.BlendMode = de.photographics.core.render.BlendMode.NORMAL,
                 opacity: Float = 1f, preserveTransparency: Boolean = false): IntRect? {
        val keep = preserveTransparency || doc.activeLayer?.lockTransparency == true
        return editSelected(tr("Fläche füllen")) { orig, s -> PixelBlend.over(orig, color, mode, opacity * s / 255f, keep) }
    }

    /**
     * Füllwerkzeug: zusammenhängende (oder alle) Pixel ähnlicher Farbe ab ([x], [y]) füllen, innerhalb
     * einer vorhandenen Auswahl. [allLayers]: Farben aus dem Gesamtbild statt der aktiven Ebene;
     * [antiAlias] glättet die Füllkante.
     */
    fun bucketFill(x: Int, y: Int, color: Int, tolerance: Int, contiguous: Boolean, allLayers: Boolean,
                   mode: de.photographics.core.render.BlendMode = de.photographics.core.render.BlendMode.NORMAL,
                   opacity: Float = 1f, antiAlias: Boolean = true): IntRect? {
        val layer = doc.activeLayer as? PixelLayer ?: return null
        val region = similarRegion(layer, x, y, tolerance, contiguous, allLayers, antiAlias) ?: return null
        val keep = layer.lockTransparency
        return editRegion(tr("Füllwerkzeug"), region) { orig, s -> PixelBlend.over(orig, color, mode, opacity * s / 255f, keep) }
    }

    /** Magischer Radiergummi: ähnliche Pixel ab ([x], [y]) mit [opacity] transparent machen. */
    fun magicErase(x: Int, y: Int, tolerance: Int, contiguous: Boolean, allLayers: Boolean, opacity: Float = 1f, antiAlias: Boolean = true): IntRect? {
        val layer = doc.activeLayer as? PixelLayer ?: return null
        val region = similarRegion(layer, x, y, tolerance, contiguous, allLayers, antiAlias) ?: return null
        return editRegion(tr("Magischer Radiergummi"), region) { orig, s ->
            Colors.withAlpha(orig, ((orig ushr 24) * (1f - opacity * s / 255f) + 0.5f).toInt())
        }
    }

    /** Bereich ähnlicher Farbe wie beim Zauberstab (die Dokumentauswahl begrenzt erst [editRegion]). */
    private fun similarRegion(layer: PixelLayer, x: Int, y: Int, tolerance: Int, contiguous: Boolean, allLayers: Boolean, antiAlias: Boolean): Selection? {
        if (x !in 0 until doc.width || y !in 0 until doc.height) return null
        val src = if (allLayers) doc.newGrid().also { g -> de.photographics.core.render.Compositor.renderBands(doc, doc.bounds) { r, buf -> g.writeRect(r, buf) } }
            else layer.pixels
        var region = de.photographics.core.selection.MagicWand.select(src, x, y, tolerance, contiguous)
        if (region.isEmpty) return null
        if (antiAlias) region = region.feathered(0.7f)
        return region
    }

    /** Wie [editSelected], aber für einen eigenen Bereich – innerhalb der Dokumentauswahl, falls vorhanden. */
    private fun editRegion(label: String, region: Selection, f: (Int, Int) -> Int): IntRect? {
        val layer = doc.activeLayer as? PixelLayer ?: return null
        val sel = doc.selection
        val r = region.bounds.intersect(sel?.bounds ?: doc.bounds).intersect(doc.bounds)
        if (r.isEmpty) return null
        history.makeRoom(r.width.toLong() * r.height * 8)
        val tx = TileTransaction(layer.pixels)
        tx.touch(r)
        for (yy in r.top until r.bottom) for (xx in r.left until r.right) {
            val s = if (sel == null) region.at(xx, yy) else region.at(xx, yy) * sel.at(xx, yy) / 255
            if (s == 0) continue
            layer.pixels[xx, yy] = f(layer.pixels[xx, yy], s)
        }
        tx.commit(label)?.let { push(it) }
        return r
    }

    private fun editSelected(label: String, f: (Int, Int) -> Int): IntRect? {
        val layer = doc.activeLayer as? PixelLayer ?: return null
        val sel = doc.selection ?: Selection.all(doc.width, doc.height)
        val r = sel.bounds
        if (r.isEmpty) return null
        history.makeRoom(r.width.toLong() * r.height * 8)
        val tx = TileTransaction(layer.pixels)
        tx.touch(r)
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            val s = sel.at(x, y)
            if (s == 0) continue
            layer.pixels[x, y] = f(layer.pixels[x, y], s)
        }
        tx.commit(label)?.let { push(it) }
        return r
    }

    /** "Ebene durch Kopie": Auswahlinhalt der aktiven Ebene in eine neue Ebene kopieren. */
    fun layerViaCopy() {
        val layer = doc.activeLayer as? RasterLayer ?: return
        val sel = doc.selection ?: run { duplicateLayer(); return }
        val grid = doc.newGrid()
        val r = sel.bounds
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            val s = sel.at(x, y)
            if (s == 0) continue
            val c = layer.raster[x, y]
            if ((c ushr 24) == 0) continue
            grid[x, y] = Colors.withAlpha(c, (c ushr 24) * s / 255)
        }
        val newLayer = PixelLayer(nextName(), grid)
        val p = doc.parentOf(layer)
        val idx = p.children.indexOf(layer) + 1
        structural(tr("Ebene durch Kopie"), { p.add(newLayer, idx); doc.activeLayer = newLayer }, { p.remove(newLayer) })
    }

    /** Ebene durch Ausschneiden (Strg+Umschalt+J): Auswahl in eine neue Ebene, an der Quelle gelöscht. */
    fun layerViaCut() {
        val layer = doc.activeLayer as? PixelLayer ?: return
        if (doc.selection == null) return
        grouped(tr("Ebene durch Ausschneiden")) {
            layerViaCopy()
            val copy = doc.activeLayer
            doc.activeLayer = layer
            clearSelection()
            doc.activeLayer = copy
        }
    }

    // ------------------------------------------------------------ Zwischenablage

    /**
     * Kopieren (Strg+C): Auswahl der aktiven Ebene, mit [merged] des sichtbaren Bildes (Strg+Umschalt+C).
     * Ohne Auswahl die ganze Ebene bzw. das ganze Bild. null = nichts Sichtbares ausgewählt.
     */
    fun copy(merged: Boolean = false): PixelClip? {
        val sel = doc.selection
        val layer = doc.activeLayer as? RasterLayer
        if (!merged && layer == null) return null
        val area = when {
            sel != null -> sel.bounds.intersect(doc.bounds)
            merged -> doc.bounds
            else -> layer!!.raster.contentBounds().intersect(doc.bounds)
        }
        if (area.isEmpty) return null
        val out = TileGrid(area.width, area.height)
        var any = false
        val band = maxOf(1, 262_144 / area.width)
        var y = area.top
        while (y < area.bottom) {
            val r = IntRect(area.left, y, area.right, minOf(area.bottom, y + band))
            val px = if (merged) Compositor.renderArgb(doc, r) else IntArray(r.width * r.height).also { layer!!.raster.readRect(r, it) }
            if (sel != null) {
                var i = 0
                for (yy in r.top until r.bottom) for (xx in r.left until r.right) {
                    val s = sel.at(xx, yy)
                    val c = px[i]
                    px[i++] = if (s >= 255) c else Colors.withAlpha(c, (c ushr 24) * s / 255)
                }
            }
            if (!any) any = px.any { (it ushr 24) != 0 }
            out.writeRect(r.offset(-area.left, -area.top), px)
            y = r.bottom
        }
        if (!any) return null
        out.compact()
        return PixelClip(out, area)
    }

    /** Ausschneiden (Strg+X): kopieren und auf der Pixelebene löschen. */
    fun cut(): PixelClip? {
        if (doc.activeLayer !is PixelLayer) return null
        val clip = copy() ?: return null
        clearSelection()
        return clip
    }

    /**
     * Einfügen als neue Ebene über der aktiven. [inPlace]: an der Originalposition (Strg+Umschalt+V);
     * sonst mittig in der Auswahl, an der Originalposition (wenn sie im sichtbaren Bereich [view] liegt)
     * oder mittig in der Ansicht – wie Photoshop. Liefert den Bereich der eingefügten Pixel.
     */
    fun paste(clip: PixelClip, inPlace: Boolean, view: IntRect? = null): IntRect {
        val w = clip.rect.width; val h = clip.rect.height
        val sel = doc.selection
        val vis = (view ?: doc.bounds).intersect(doc.bounds).takeIf { !it.isEmpty } ?: doc.bounds
        val at = when {
            inPlace -> clip.rect
            sel != null && !sel.isEmpty -> sel.bounds.let { b -> IntRect.ofSize(w, h).offset(b.left + (b.width - w) / 2, b.top + (b.height - h) / 2) }
            !clip.rect.intersect(vis).isEmpty && clip.rect.right <= doc.width + w && clip.rect.bottom <= doc.height + h -> clip.rect
            else -> IntRect.ofSize(w, h).offset(vis.left + (vis.width - w) / 2, vis.top + (vis.height - h) / 2)
        }
        // Pixel außerhalb der Arbeitsfläche bleiben erhalten (wie Photoshop)
        val grid = TileGrid(doc.width, doc.height, area = if (at.left < 0 || at.top < 0 || at.right > doc.width || at.bottom > doc.height) at else null)
        grid.writeRect(at, clip.pixels())
        grid.compact()
        grouped(tr("Einfügen")) {
            addLayer(nextName(), grid, tr("Einfügen"))
            if (sel != null) setSelection(null, tr("Einfügen"))
        }
        return at
    }

    // ------------------------------------------------------------ Farbtiefe

    /**
     * Modus → 8/16 Bit je Kanal (Photoshop: Bild → Modus). Nach 16 Bit: untere Bits = obere (exakte
     * Werte v·257); nach 8 Bit: auf die nächste 8-Bit-Stufe gerundet.
     */
    fun setBitDepth(depth: Int) {
        val d = if (depth >= 16) 16 else 8
        if (d == doc.bitDepth) return
        val layers = doc.allLayers().filterIsInstance<PixelLayer>()
        val before = layers.associateWith { it.pixels to it.low }
        val after = layers.associateWith { l ->
            if (d == 16) l.pixels to l.pixels.copy()
            else {
                val lo = l.low ?: return@associateWith l.pixels to null
                // Runden statt Abschneiden: hi = round(v / 257)
                val out = l.pixels.copy()
                val area = l.pixels.extent
                val band = maxOf(1, 262_144 / maxOf(1, area.width))
                var y = area.top
                while (y < area.bottom) {
                    val r = IntRect(area.left, y, area.right, minOf(area.bottom, y + band))
                    val h = IntArray(r.width * r.height); l.pixels.readRect(r, h)
                    val o = IntArray(r.width * r.height); lo.readRect(r, o)
                    for (k in h.indices) {
                        var c = 0
                        for (s in Colors.SHIFTS_ARGB) { val hb = (h[k] shr s) and 0xFF; val lb = (o[k] shr s) and 0xFF; val v = (hb shl 8) or (if (s == 24) Colors.lowAlpha(hb, lb) else lb); c = c or (((v + 128) / 257).coerceIn(0, 255) shl s) }
                        h[k] = c
                    }
                    out.writeRect(r, h)
                    y = r.bottom
                }
                out.compact()
                out to null
            }
        }
        val old = doc.bitDepth
        fun set(m: Map<PixelLayer, Pair<TileGrid, TileGrid?>>, depthNow: Int) { for ((l, p) in m) { l.pixels = p.first; l.low = p.second }; doc.bitDepth = depthNow }
        set(after, d)
        push(LambdaEntry(if (d == 16) tr("16 Bit/Kanal") else tr("8 Bit/Kanal"), { set(before, old) }, { set(after, d) },
            before.values.sumOf { (it.second?.byteSize ?: 0L) + if (d == 8) it.first.byteSize else 0L }))
    }

    /** Ebene aus dem Ergebnis von [src] (16 Bit mit unteren Bits, wenn das Dokument 16 Bit hat). */
    private fun renderedLayer(name: String, src: Document, area: IntRect, post: ((IntArray) -> Unit)? = null): PixelLayer {
        if (doc.bitDepth != 16 || post != null) return PixelLayer(name, Compositor.renderGrid(src, area, post))
        src.bitDepth = 16
        val (hi, lo) = Compositor.renderGridDeep(src, area)
        return PixelLayer(name, hi).also { it.low = lo }
    }

    // ------------------------------------------------------------ Formen

    /** Neue Formebene über der aktiven Ebene; [docPath] in Dokumentkoordinaten. */
    fun addShapeLayer(docPath: de.photographics.core.vector.VectorPath, fill: Int?, stroke: de.photographics.core.vector.ShapeStroke?, name: String = tr("Form {0}", ++layerCounter)): de.photographics.core.doc.ShapeLayer {
        // Eigene Koordinaten ab der linken oberen Ecke: der Transformationsrahmen beginnt dort
        val b = docPath.bounds()
        val origin = Affine.translate(b[0], b[1])
        val l = de.photographics.core.doc.ShapeLayer(name, docPath.map(origin.inverse()), fill, stroke, origin, doc.width, doc.height)
        l.clipped = doc.activeLayer?.clipped == true
        val (parent, index) = insertionPoint()
        structural(tr("Form erstellen"), { parent.add(l, index); doc.activeLayer = l }, { parent.remove(l) })
        return l
    }

    /** Füllung und Kontur einer Formebene ändern. */
    fun setShapeStyle(l: de.photographics.core.doc.ShapeLayer, fill: Int?, stroke: de.photographics.core.vector.ShapeStroke?) {
        val f0 = l.fill; val s0 = l.stroke
        if (f0 == fill && s0 == stroke) return
        l.fill = fill; l.stroke = stroke
        push(LambdaEntry(tr("Formeigenschaften"), { l.fill = f0; l.stroke = s0 }, { l.fill = fill; l.stroke = stroke }))
    }

    /** Pfad einer Formebene ersetzen ([docPath] in Dokumentkoordinaten, z. B. nach dem Bearbeiten der Anker). */
    fun setShapePath(l: de.photographics.core.doc.ShapeLayer, docPath: de.photographics.core.vector.VectorPath) {
        val p0 = l.path
        val p1 = docPath.map(l.transform.inverse())
        if (p0 == p1) return
        l.path = p1
        push(LambdaEntry(tr("Pfad bearbeiten"), { l.path = p0 }, { l.path = p1 }))
    }

    // ------------------------------------------------------------ Pfade

    fun setWorkPath(p: de.photographics.core.vector.VectorPath?, label: String = tr("Arbeitspfad")) {
        val before = doc.workPath
        val after = p?.takeIf { !it.isEmpty }
        if (before == after) return
        doc.workPath = after
        push(LambdaEntry(label, { it.workPath = before }, { it.workPath = after }))
    }

    fun setPaths(list: List<de.photographics.core.vector.NamedPath>, label: String) {
        val before = doc.paths
        if (before == list) return
        doc.paths = list
        push(LambdaEntry(label, { it.paths = before }, { it.paths = list }))
    }

    /** Arbeitspfad unter [name] sichern (wie „Pfad speichern“). */
    fun saveWorkPath(name: String) {
        val w = doc.workPath ?: return
        grouped(tr("Pfad speichern")) {
            setPaths(doc.paths + de.photographics.core.vector.NamedPath(name, w), tr("Pfad speichern"))
            setWorkPath(null)
        }
    }

    /** Pfad als Auswahl laden (Strg+Eingabe); geschlossene Teilpfade, Nonzero-Regel. */
    fun selectionFromPath(p: de.photographics.core.vector.VectorPath, op: SelectionOp = SelectionOp.REPLACE, feather: Float = 0f) {
        val polys = p.flatten().filter { it.second }.map { it.first }
        if (polys.isEmpty()) return
        val mask = de.photographics.core.vector.PathRaster.fill(doc.width, doc.height, polys)
        var sel = Selection(mask, polys)
        if (feather > 0f) sel = sel.feathered(feather)
        val existing = doc.selection
        setSelection(if (existing != null) existing.combine(sel, op) else sel, tr("Pfad als Auswahl laden"))
    }

    /** Arbeitspfad aus der Auswahl (Umrisse vereinfacht, [tolerance] in Pixeln). */
    fun pathFromSelection(tolerance: Double = 1.0) {
        val sel = doc.selection ?: return
        setWorkPath(de.photographics.core.vector.VectorPath.fromOutlines(sel.outlines, tolerance), tr("Arbeitspfad erstellen"))
    }

    /** Pfad mit [color] auf der aktiven Pixelebene füllen (innerhalb einer Auswahl). */
    fun fillPath(p: de.photographics.core.vector.VectorPath, color: Int): IntRect? {
        val polys = p.flatten().filter { it.second }.map { it.first }
        return paintCoverage(tr("Pfad füllen"), de.photographics.core.vector.PathRaster.fill(doc.width, doc.height, polys), color)
    }

    /** Pfadkontur mit [color] und Breite [width] auf die aktive Pixelebene (wie „Pfadkontur füllen“ mit dem Pinsel). */
    fun strokePath(p: de.photographics.core.vector.VectorPath, color: Int, width: Float): IntRect? =
        paintCoverage(tr("Pfadkontur füllen"), de.photographics.core.vector.PathRaster.stroke(doc.width, doc.height, p.flatten(), width), color)

    private fun paintCoverage(label: String, cov: TileGrid, color: Int): IntRect? {
        val layer = doc.activeLayer as? PixelLayer ?: return null
        val area = cov.contentBounds(alphaOnly = false).intersect(doc.bounds)
        if (area.isEmpty) return IntRect.EMPTY
        val sel = doc.selection
        val tx = TileTransaction(layer.pixels)
        tx.touch(area)
        val bandH = maxOf(1, 262_144 / area.width)
        var y0 = area.top
        while (y0 < area.bottom) {
            val band = IntRect(area.left, y0, area.right, minOf(area.bottom, y0 + bandH))
            y0 = band.bottom
            val c = IntArray(band.width * band.height); cov.readRect(band, c)
            val px = IntArray(band.width * band.height); layer.pixels.readRect(band, px)
            var i = 0
            for (y in band.top until band.bottom) for (x in band.left until band.right) {
                var k = c[i]
                if (sel != null && k > 0) k = k * sel.at(x, y) / 255
                if (k > 0) {
                    val src = Colors.withAlpha(color, Colors.a(color) * k / 255)
                    px[i] = if (layer.lockTransparency) Colors.withAlpha(Colors.srcOver(px[i], src), Colors.a(px[i])) else Colors.srcOver(px[i], src)
                }
                i++
            }
            layer.pixels.writeRect(band, px)
        }
        tx.commit(label)?.let { push(it) }
        return area
    }

    // ------------------------------------------------------------ Alphakanäle

    fun setChannels(list: List<de.photographics.core.doc.AlphaChannel>, label: String) {
        val before = doc.channels
        if (before == list) return
        doc.channels = list
        push(LambdaEntry(label, { it.channels = before }, { it.channels = list }))
    }

    /** Auswahl speichern (als neuer Alphakanal). */
    fun saveSelectionAsChannel(name: String = tr("Alpha {0}", doc.channels.size + 1)) {
        val sel = doc.selection ?: return
        setChannels(doc.channels + de.photographics.core.doc.AlphaChannel(name, sel.mask.copy()), tr("Auswahl speichern"))
    }

    /** Alphakanal [index] als Auswahl laden (Strg+Klick auf den Kanal). */
    fun selectionFromChannel(index: Int, op: SelectionOp = SelectionOp.REPLACE) {
        val ch = doc.channels.getOrNull(index) ?: return
        val g = ch.grid.copy()
        val sel = Selection(g, Selection.outlinesOf(g))
        val existing = doc.selection
        setSelection(if (existing != null) existing.combine(sel, op) else sel, tr("Auswahl laden"))
    }

    // ------------------------------------------------------------ Schwebende Auswahl

    /**
     * Ausgewählte Pixel der aktiven Pixelebene anheben, um sie zu verschieben oder zu transformieren:
     * Sie liegen danach als Smartobjekt über der Ebene, an der Quelle sind sie ausgeschnitten (mit
     * [duplicate] bleiben sie dort, wie Alt+Ziehen in Photoshop). Noch kein Verlaufsschritt –
     * abschließen mit [commitFloating] oder [cancelFloating]. Ohne Auswahl die ganze Ebene (Strg+T
     * auf einer Pixelebene). null = keine Pixelebene oder nichts darauf.
     */
    fun floatSelection(duplicate: Boolean = false): Floating? {
        val src = doc.activeLayer as? PixelLayer ?: return null
        val before = doc.selection
        val sel = before ?: Selection.all(doc.width, doc.height)
        val b = (if (before != null) sel.bounds else src.pixels.contentBounds()).intersect(doc.bounds)
        if (b.isEmpty) return null
        history.makeRoom(b.width.toLong() * b.height * 12)
        val cropped = TileGrid(b.width, b.height)
        val tx = TileTransaction(src.pixels)
        if (!duplicate) tx.touch(b)
        val band = maxOf(1, 262_144 / b.width)
        var y = b.top
        while (y < b.bottom) {
            val r = IntRect(b.left, y, b.right, minOf(b.bottom, y + band))
            val px = IntArray(r.width * r.height)
            src.pixels.readRect(r, px)
            val rest = if (duplicate) null else px.copyOf()
            var i = 0
            for (yy in r.top until r.bottom) for (xx in r.left until r.right) {
                val s = sel.at(xx, yy)
                val c = px[i]
                if (s < 255) px[i] = Colors.withAlpha(c, (c ushr 24) * s / 255)
                if (rest != null && s > 0) rest[i] = Colors.withAlpha(c, (c ushr 24) * (255 - s) / 255)
                i++
            }
            cropped.writeRect(r.offset(-b.left, -b.top), px)
            if (rest != null) src.pixels.writeRect(r, rest)
            y = r.bottom
        }
        cropped.compact()
        val f = SmartLayer(src.name, cropped, Affine.translate(b.left.toDouble(), b.top.toDouble()), doc.width, doc.height)
        // Wie ein Teil der Ebene darstellen
        f.opacity = src.opacity; f.fillOpacity = src.fillOpacity; f.blendMode = src.blendMode; f.clipped = src.clipped
        val p = doc.parentOf(src)
        p.add(f, p.children.indexOf(src) + 1)
        doc.activeLayer = f
        doc.selection = null
        return Floating(src, f, tx, before, b, duplicate)
    }

    /**
     * Schwebende Pixel in ihrer jetzigen Lage (Transformation/Verzerrung des Smartobjekts plus
     * Vorschau-Verschiebung) in die Quellebene übernehmen; die Auswahl folgt. Ein Verlaufsschritt.
     */
    fun commitFloating(f: Floating, label: String = tr("Auswahl transformieren")): IntRect {
        val l = f.layer
        if (l.previewDx != 0 || l.previewDy != 0) {
            val m = Affine.translate(l.previewDx.toDouble(), l.previewDy.toDouble())
            l.previewDx = 0; l.previewDy = 0
            l.applyDocTransform(m)
        }
        l.fastPreview = false
        val p = doc.parentOf(l)
        p.remove(l)
        doc.activeLayer = f.source
        val placed = l.raster
        val area = placed.contentBounds().intersect(f.source.pixels.extent)
        if (!area.isEmpty) {
            f.tx.touch(area)
            val band = maxOf(1, 262_144 / area.width)
            var y = area.top
            while (y < area.bottom) {
                val r = IntRect(area.left, y, area.right, minOf(area.bottom, y + band))
                val top = IntArray(r.width * r.height); placed.readRect(r, top)
                val dst = IntArray(r.width * r.height); f.source.pixels.readRect(r, dst)
                for (i in dst.indices) if ((top[i] ushr 24) != 0) dst[i] = Colors.srcOver(dst[i], top[i])
                f.source.pixels.writeRect(r, dst)
                y = r.bottom
            }
        }
        // Die Auswahl macht dieselbe Bewegung wie die Pixel
        val before = f.selectionBefore
        val sel = before?.let { transformedSelection(it, f.bounds, l) }
        grouped(label) {
            f.tx.commit(label)?.let { push(it) }
            doc.selection = sel
            if (before != null) push(LambdaEntry(label, { it.selection = before }, { it.selection = sel }))
        }
        return area.union(f.bounds)
    }

    /** Schwebende Auswahl verwerfen: alles wie vorher. */
    fun cancelFloating(f: Floating): IntRect {
        doc.parentOf(f.layer).remove(f.layer)
        f.tx.rollback()
        doc.activeLayer = f.source
        doc.selection = f.selectionBefore
        return f.layer.corners().let { c ->
            IntRect(
                kotlin.math.floor(minOf(c[0], c[2], c[4], c[6])).toInt(), kotlin.math.floor(minOf(c[1], c[3], c[5], c[7])).toInt(),
                kotlin.math.ceil(maxOf(c[0], c[2], c[4], c[6])).toInt(), kotlin.math.ceil(maxOf(c[1], c[3], c[5], c[7])).toInt(),
            )
        }.union(f.bounds)
    }

    /** Auswahlmaske mit der Lage des Smartobjekts [l] abbilden (Quelle = Ausschnitt [b] der alten Maske). */
    private fun transformedSelection(sel: de.photographics.core.selection.Selection, b: IntRect, l: SmartLayer): de.photographics.core.selection.Selection? {
        val src = TileGrid(b.width, b.height)
        val band = maxOf(1, 262_144 / b.width)
        var y = b.top
        while (y < b.bottom) {
            val r = IntRect(b.left, y, b.right, minOf(b.bottom, y + band))
            val px = IntArray(r.width * r.height)
            var i = 0
            for (yy in r.top until r.bottom) for (xx in r.left until r.right) px[i++] = (sel.at(xx, yy) shl 24) or 0xFFFFFF
            src.writeRect(r.offset(-b.left, -b.top), px)
            y = r.bottom
        }
        val w = l.warp
        val out = if (w != null) de.photographics.core.image.Resample.renderProjective(src, l.homography(), doc.width, doc.height)
            else de.photographics.core.image.Resample.renderAffine(src, l.transform, doc.width, doc.height)
        val mask = TileGrid(doc.width, doc.height)
        val area = out.contentBounds().intersect(doc.bounds)
        if (area.isEmpty) return null
        val bandH = maxOf(1, 262_144 / area.width)
        y = area.top
        while (y < area.bottom) {
            val r = IntRect(area.left, y, area.right, minOf(area.bottom, y + bandH))
            val px = IntArray(r.width * r.height); out.readRect(r, px)
            for (k in px.indices) px[k] = px[k] ushr 24
            mask.writeRect(r, px)
            y = r.bottom
        }
        mask.compact()
        return de.photographics.core.selection.Selection(mask, de.photographics.core.selection.Selection.outlinesOf(mask))
    }

    /** Nur den Auswahlrahmen verschieben (Auswahlwerkzeug in der Auswahl ziehen). */
    fun moveSelection(dx: Int, dy: Int) {
        val sel = doc.selection ?: return
        if (dx == 0 && dy == 0) return
        val mask = TileGrid(doc.width, doc.height, sel.mask.defaultValue)
        val b = sel.bounds
        val t = b.offset(dx, dy).intersect(doc.bounds)
        if (!t.isEmpty) {
            val s = t.offset(-dx, -dy)
            val px = IntArray(s.width * s.height); sel.mask.readRect(s, px)
            mask.writeRect(t, px)
        }
        mask.compact()
        setSelection(de.photographics.core.selection.Selection(mask, sel.outlines.map { o -> FloatArray(o.size) { i -> o[i] + if (i % 2 == 0) dx else dy } }), tr("Auswahl verschieben"))
    }

    // ------------------------------------------------------------ Retusche-Befehle

    /** Inhaltsbasiert füllen: Auswahl auf der aktiven Pixelebene aus der Umgebung neu aufbauen. */
    fun contentAwareFill(inpainter: Inpainter = PatchMatchInpainter()): IntRect? {
        history.makeRoom(128L * 1_000_000)
        val layer = doc.activeLayer as? PixelLayer ?: return null
        val sel = doc.selection ?: return null
        val b = sel.bounds
        if (b.isEmpty) return null
        val res = Retouch.fillHole(doc.bounds, b, { x, y -> sel.at(x, y) > 0 }, { r, out -> layer.pixels.readRect(r, out) }, inpainter) ?: return null
        val (r, filled, _) = res
        val tx = TileTransaction(layer.pixels)
        tx.touch(b)
        for (y in b.top until b.bottom) for (x in b.left until b.right) {
            val s = sel.at(x, y)
            if (s == 0) continue
            val c = filled[(y - r.top) * r.width + (x - r.left)]
            layer.pixels[x, y] = Colors.lerp(layer.pixels[x, y], c, s / 255f)
        }
        tx.commit(tr("Inhaltsbasiert füllen"))?.let { push(it) }
        return b
    }

    /**
     * Ausbessern: Auswahl wird durch den um (dx, dy) versetzten Bereich ersetzt; Farbe und
     * Helligkeit passen sich per Poisson-Blending nahtlos an.
     */
    fun patchSelection(dx: Int, dy: Int): IntRect? {
        val layer = doc.activeLayer as? PixelLayer ?: return null
        val sel = doc.selection ?: return null
        if (dx == 0 && dy == 0) return null
        val b = sel.bounds.inset(-2).intersect(doc.bounds)
        if (b.isEmpty) return null
        val n = b.width * b.height
        val target = IntArray(n); layer.pixels.readRect(b, target)
        val source = IntArray(n); layer.pixels.readRect(b.offset(dx, dy), source)
        val inside = BooleanArray(n)
        for (y in b.top until b.bottom) for (x in b.left until b.right) if (sel.at(x, y) > 127) inside[(y - b.top) * b.width + (x - b.left)] = true
        val healed = Poisson.blend(target, source, inside, b.width, b.height)
        val tx = TileTransaction(layer.pixels)
        tx.touch(b)
        for (y in b.top until b.bottom) for (x in b.left until b.right) {
            val s = sel.at(x, y)
            if (s == 0) continue
            val i = (y - b.top) * b.width + (x - b.left)
            layer.pixels[x, y] = Colors.lerp(target[i], healed[i], s / 255f)
        }
        tx.commit(tr("Ausbessern"))?.let { push(it) }
        return b
    }

    /** Rote Augen im Kreis um (cx, cy) neutralisieren. */
    fun redEye(cx: Int, cy: Int, radius: Int): IntRect? {
        val layer = doc.activeLayer as? PixelLayer ?: return null
        val r = IntRect(cx - radius, cy - radius, cx + radius + 1, cy + radius + 1).intersect(doc.bounds)
        if (r.isEmpty) return null
        val tx = TileTransaction(layer.pixels)
        tx.touch(r)
        var changed = false
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            val d = kotlin.math.hypot((x - cx).toFloat(), (y - cy).toFloat()) / radius
            if (d > 1f) continue
            val c = layer.pixels[x, y]
            val rr = Colors.r(c); val gg = Colors.g(c); val bb = Colors.b(c)
            val redness = rr - maxOf(gg, bb)
            if (rr < 70 || redness < 25) continue
            // Rot durch dunklen Mittelwert von Grün/Blau ersetzen, weich zum Rand
            val k = ((1f - d) * 3f).coerceIn(0f, 1f) * (redness / 80f).coerceIn(0f, 1f)
            val nr = ((gg + bb) / 2 * 0.8f).toInt()
            layer.pixels[x, y] = Colors.argb(Colors.a(c), (rr + (nr - rr) * k).toInt(), (gg * (1 - 0.2f * k)).toInt(), (bb * (1 - 0.2f * k)).toInt())
            changed = true
        }
        if (!changed) { tx.rollback(); return null }
        tx.commit(tr("Rote Augen entfernen"))?.let { push(it) }
        return r
    }

    /** Zusammengesetzte Farbe an einer Stelle (Pipette). */
    fun sampleColor(x: Int, y: Int): Int = Compositor.sample(doc, x, y)

    /** Fügt ein importiertes Bild als neue Ebene ein (zentriert). */
    /** Platziert ein Raster zentriert als neue Ebene (streifenweise kopiert). */
    fun placeImage(name: String, src: TileGrid) {
        val grid = doc.newGrid()
        val dx = (doc.width - src.width) / 2; val dy = (doc.height - src.height) / 2
        val band = maxOf(1, (1 shl 20) / src.width)
        val buf = IntArray(src.width * band)
        var y = 0
        while (y < src.height) {
            val n = minOf(band, src.height - y)
            src.readRect(IntRect(0, y, src.width, y + n), buf)
            grid.writeRect(IntRect(dx, dy + y, dx + src.width, dy + y + n), if (n == band) buf else buf.copyOf(src.width * n))
            y += n
        }
        grid.compact()
        addLayer(name, grid, tr("Platzieren"))
    }

    fun placeImage(name: String, w: Int, h: Int, px: IntArray) {
        val grid = doc.newGrid()
        val dx = (doc.width - w) / 2; val dy = (doc.height - h) / 2
        grid.writeRect(IntRect(dx, dy, dx + w, dy + h), px)
        grid.compact()
        addLayer(name, grid, tr("Platzieren"))
    }
}

/** Vergrößert ein Raster direkt auf die Zielgröße (z. B. KI-Super-Resolution, kachelweise). */
fun interface Upscaler {
    fun upscale(src: TileGrid, targetW: Int, targetH: Int): TileGrid
}

/** PSD-Blöcke mit Ebeneneffekten. */
private val EFFECT_BLOCKS = setOf("lfx2", "lmfx", "lrFX")
