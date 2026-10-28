package de.photographics.core.editor

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

    fun push(e: HistoryEntry) = history.push(e)

    // ------------------------------------------------------------ Ebenen

    private fun nextName() = "Ebene ${++layerCounter}"

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

    fun addLayer(name: String = nextName(), grid: TileGrid = doc.newGrid(), label: String = "Neue Ebene"): PixelLayer {
        val layer = PixelLayer(name, grid)
        // Innerhalb einer Schnittmaske angelegte Ebenen gehören wie in Photoshop dazu
        layer.clipped = doc.activeLayer?.clipped == true
        val (parent, index) = insertionPoint()
        structural(label, { parent.add(layer, index); doc.activeLayer = layer }, { parent.remove(layer) })
        return layer
    }

    fun addGroup(): GroupLayer {
        val g = GroupLayer("Gruppe ${++layerCounter}")
        val (parent, index) = insertionPoint()
        structural("Neue Gruppe", { parent.add(g, index); doc.activeLayer = g }, { parent.remove(g) })
        return g
    }

    /** Fasst die aktive Ebene in eine neue Gruppe. */
    fun groupActive() {
        val a = doc.activeLayer ?: return
        val p = doc.parentOf(a)
        val idx = p.children.indexOf(a)
        val g = GroupLayer("Gruppe ${++layerCounter}")
        structural("Ebene gruppieren", {
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
        structural("Ebene duplizieren", { p.add(copy, idx); doc.activeLayer = copy }, { p.remove(copy) })
    }

    fun deleteLayer() {
        val a = doc.activeLayer ?: return
        val p = doc.parentOf(a)
        val idx = p.children.indexOf(a)
        layerOperation("Ebene löschen", listOf(a), {
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
        structural("Ebene verschieben", { np.add(layer, ni) }, { p.add(layer, idx) })
    }

    fun setActive(layer: Layer?) { doc.activeLayer = layer }

    /** Übernimmt interaktiv geänderte Eigenschaften (z. B. nach dem Loslassen eines Reglers) in den Verlauf. */
    fun commitProps(layer: Layer, before: Layer.Props, label: String = "Ebeneneigenschaften") {
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
        val merged = PixelLayer(lower.name, Compositor.renderGrid(tmp, area)).apply {
            opacity = lower.opacity; fillOpacity = lower.fillOpacity; blendMode = lower.blendMode
            visible = lower.visible; clipped = lower.clipped; psdId = lower.psdId
        }
        layerOperation("Auf eine Ebene reduzieren", listOf(upper, lower, merged), {
            p.remove(upper); p.remove(lower); p.add(merged, idx - 1); doc.activeLayer = merged
        }, {
            p.remove(merged); p.add(lower, idx - 1); p.add(upper, idx)
        })
        return true
    }

    fun flatten() {
        history.makeRoom(doc.width.toLong() * doc.height * 12)
        // Auf Weiß reduzieren wie Photoshops "Auf Hintergrundebene reduzieren"
        val flat = PixelLayer("Hintergrund", Compositor.renderGrid(doc) { px ->
            for (i in px.indices) px[i] = Colors.srcOver(0xFFFFFFFF.toInt(), px[i])
        })
        val old = doc.root.children.toList()
        layerOperation("Auf Hintergrundebene reduzieren", old + flat, {
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
        structural("Ebenenmaske hinzufügen", { a.mask = mask }, { a.mask = null })
    }

    fun deleteMask() {
        val a = doc.activeLayer ?: return
        val m = a.mask ?: return
        structural("Ebenenmaske löschen", m.grid.byteSize, { a.mask = null }, { a.mask = m })
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
        structural("Ebenenmaske anwenden", oldGrid.byteSize + m.grid.byteSize, { a.pixels = newGrid; a.mask = null }, { a.pixels = oldGrid; a.mask = m })
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
            structural("Maske verschieben", oldMask!!.byteSize, { mask!!.grid = newMask!! }, { mask!!.grid = oldMask })
            return
        }
        when (layer) {
            is PixelLayer -> {
                val oldPx = layer.pixels; val newPx = oldPx.shifted(dx, dy)
                structural("Verschieben", oldPx.byteSize + (oldMask?.byteSize ?: 0L), {
                    layer.pixels = newPx; if (newMask != null) layer.mask?.grid = newMask
                }, {
                    layer.pixels = oldPx; if (oldMask != null) layer.mask?.grid = oldMask
                })
            }
            is TextLayer, is SmartLayer -> {
                val old = geometryOf(layer)
                val shift = Affine.translate(dx.toDouble(), dy.toDouble())
                val new = Geometry(old.transform.then(shift), old.warp?.let { SmartLayer.mapQuad(it, shift) })
                structural("Verschieben", {
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
            structural("Verzerren", { setGeometry(layer, after) }, { setGeometry(layer, before) })
            return
        }
        val sw = layer.source.width.toDouble(); val sh = layer.source.height.toDouble()
        fun hom(g: Geometry) = g.warp?.let { de.photographics.core.image.Homography.rectToQuad(sw, sh, it) } ?: de.photographics.core.image.Homography.of(g.transform)
        val oldMask = mask.grid
        val m = hom(before).inverse().then(hom(after))
        val newMask = transformMaskWith(oldMask, doc.width, doc.height) { argb -> de.photographics.core.image.Resample.renderProjective(argb, m, doc.width, doc.height) }
        structural("Verzerren", oldMask.byteSize, { setGeometry(layer, after); mask.grid = newMask }, { setGeometry(layer, before); mask.grid = oldMask })
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
        documentChange("Perspektivisch freistellen") {
            for (l in doc.allLayers()) {
                when (l) {
                    is PixelLayer -> l.pixels = de.photographics.core.image.Resample.renderProjective(l.pixels, m, outW, outH)
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
        }
    }

    fun transformOf(layer: Layer): Affine = when (layer) {
        is TextLayer -> layer.transform
        is SmartLayer -> layer.transform
        else -> Affine.IDENTITY
    }

    private fun setTransform(layer: Layer, t: Affine) {
        when (layer) {
            is TextLayer -> { layer.transform = t; layer.unchangedFromPsd = false }
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
        replaceLayer(layer, smart, "In Smartobjekt konvertieren")
        return smart
    }

    /** Text, Smartobjekt oder Einstellung in eine normale Pixelebene umwandeln. */
    fun rasterize(layer: Layer) {
        val px: TileGrid = when (layer) {
            is TextLayer -> layer.raster.copy()
            is SmartLayer -> layer.raster.copy()
            else -> return
        }
        val pl = PixelLayer(layer.name, px)
        copyLayerProps(layer, pl)
        replaceLayer(layer, pl, "Ebene rastern")
    }

    private fun copyLayerProps(from: Layer, to: Layer) {
        to.applyProps(from.props()); to.mask = from.mask; to.psdId = from.psdId
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
            else -> {}
        }
    }

    /** Übernimmt eine Transformation in den Verlauf; [before] ist der Ausgangszustand. */
    fun commitTransform(layer: Layer, before: Affine, after: Affine) {
        if (before == after) { setTransform(layer, before); (layer as? SmartLayer)?.fastPreview = false; return }
        val mask = layer.mask?.takeIf { it.linked }
        if (mask == null) {
            structural("Frei transformieren", { setTransform(layer, after) }, { setTransform(layer, before) })
            return
        }
        // Verbundene Maske mittransformieren (Abbildung vom alten in den neuen Zustand)
        val oldMask = mask.grid
        val newMask = transformMask(oldMask, before.inverse().then(after))
        structural("Frei transformieren", oldMask.byteSize, { setTransform(layer, after); mask.grid = newMask }, { setTransform(layer, before); mask.grid = oldMask })
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
        setProps(layer, if (m.linked) "Maske lösen" else "Maske verbinden") { mask?.linked = !(mask?.linked ?: true) }
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
            setProps(layer, "Schnittmaske erstellen") { clipped = true }
            return true
        }
        if (!layer.clipped) return false
        val affected = parent.children.drop(idx).takeWhile { it.clipped }
        structural("Schnittmaske zurückwandeln", { affected.forEach { it.clipped = false } }, { affected.forEach { it.clipped = true } })
        return true
    }

    // ------------------------------------------------------------ Einstellungs- und Textebenen

    fun addAdjustmentLayer(adjustment: Adjustment): AdjustmentLayer {
        val l = AdjustmentLayer(adjustment.label, adjustment)
        // Wie in Photoshop: mit Maske, bei aktiver Auswahl aus der Auswahl
        l.mask = LayerMask(doc.selection?.mask?.copy() ?: TileGrid(doc.width, doc.height, 255))
        l.clipped = doc.activeLayer?.clipped == true
        val (parent, index) = insertionPoint()
        structural("Neue Einstellungsebene", { parent.add(l, index); doc.activeLayer = l }, { parent.remove(l) })
        return l
    }

    /** Live-Änderung (ohne Verlauf); mit [commitAdjustment] abschließen. */
    fun previewAdjustment(layer: AdjustmentLayer, a: Adjustment) { layer.adjustment = a }

    fun commitAdjustment(layer: AdjustmentLayer, before: Adjustment) {
        val after = layer.adjustment
        if (before == after) return
        push(LambdaEntry("${after.label} ändern", { layer.adjustment = before }, { layer.adjustment = after }))
    }

    fun addTextLayer(style: TextStyle, x: Double, y: Double): TextLayer {
        val l = TextLayer(style.text.lineSequence().first().take(30).ifBlank { "Text" }, style,
            Affine.translate(x, y), doc.width, doc.height)
        val (parent, index) = insertionPoint()
        structural("Text erstellen", { parent.add(l, index); doc.activeLayer = l }, { parent.remove(l) })
        return l
    }

    fun updateText(layer: TextLayer, style: TextStyle) {
        val before = layer.style; val beforeName = layer.name; val beforeOrig = layer.unchangedFromPsd
        if (before == style) return
        val newName = style.text.lineSequence().first().take(30).ifBlank { "Text" }
        val apply = { layer.style = style; layer.name = newName }
        apply()
        push(LambdaEntry("Text bearbeiten", {
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
        documentChange("Freistellen") { doc.resizeCanvas(r.width, r.height, -r.left, -r.top, keepOutside = !deleteCropped) }
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
        transformDocument("Freistellen (gedreht)", m, w, h)
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
        transformDocument("Bild drehen", m, nw, nh) {
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
                    is PixelLayer -> l.pixels = de.photographics.core.image.Resample.renderAffine(l.pixels, m, nw, nh)
                    is TextLayer -> l.transform = l.transform.then(m)
                    is SmartLayer -> l.applyDocTransform(m)
                    else -> {}
                }
                l.mask?.let { it.grid = transformMask(it.grid, m, nw, nh) }
            }
            doc.guides = mapGuides(doc.guides, m).filter { it.position in 0.0..(if (it.vertical) nw else nh).toDouble() }
            doc.setSize(nw, nh)
            doc.selection = null
            after()
        }
    }

    fun rotate(clockwise: Boolean) {
        val w = doc.width.toDouble(); val h = doc.height.toDouble()
        // Abbildung alter Koordinaten auf die gedrehte Fläche (für Text/Smartobjekte)
        val m = if (clockwise) Affine(0.0, 1.0, -1.0, 0.0, h, 0.0) else Affine(0.0, -1.0, 1.0, 0.0, 0.0, w)
        transformAll(if (clockwise) "90° im UZS drehen" else "90° gegen UZS drehen", swap = true, m) {
            Transforms.rotate90(it, clockwise)
        }
    }

    fun flip(horizontal: Boolean) {
        val m = if (horizontal) Affine(-1.0, 0.0, 0.0, 1.0, doc.width.toDouble(), 0.0) else Affine(1.0, 0.0, 0.0, -1.0, 0.0, doc.height.toDouble())
        transformAll(if (horizontal) "Horizontal spiegeln" else "Vertikal spiegeln", swap = false, m) {
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
        documentChange("Bildgröße", (documentBytes() * newW.toDouble() * newH / (w.toDouble() * h)).toLong()) {
            for (l in doc.allLayers()) {
                when (l) {
                    // KI nur für Ebenen innerhalb der Arbeitsfläche; herausragende Ebenen klassisch (verlustfrei mitskaliert)
                    is PixelLayer -> l.pixels = if (upscaler != null && (newW > w || newH > h) && !l.pixels.extendsBeyondCanvas) upscaler.upscale(l.pixels, newW, newH)
                        else Resample.resizeGrid(l.pixels, newW, newH, method)
                    is TextLayer -> l.transform = l.transform.then(scale)
                    is SmartLayer -> l.applyDocTransform(scale)
                    else -> {}
                }
                l.mask?.let { m -> m.grid = Resample.resizeGrid(m.grid, newW, newH, method, gray = true) }
            }
            doc.guides = mapGuides(doc.guides, scale)
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
        documentChange("Arbeitsfläche") {
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
                    is PixelLayer -> l.pixels = f(l.pixels)
                    is TextLayer -> l.transform = l.transform.then(m)
                    is SmartLayer -> l.applyDocTransform(m)
                    else -> {}
                }
                l.mask?.let { it.grid = f(it.grid) }
            }
            doc.guides = mapGuides(doc.guides, m)
            if (swap) doc.setSize(doc.height, doc.width) else doc.selection = null
        }
    }

    /**
     * Hilfslinien mit derselben Abbildung wie den Inhalt mitführen; Linien, die dabei nicht
     * waagerecht oder senkrecht bleiben (z. B. beim schrägen Drehen), fallen weg.
     */
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
        push(LambdaEntry("Profil zuweisen", { it.colorProfile = before }, { it.colorProfile = p }))
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
                    tx.commit("In Profil umwandeln")?.let { entries += it }
                }
                is TextLayer -> {
                    val old = l.style
                    val c = intArrayOf(old.color); mapPx(c)
                    val new = old.copy(color = c[0])
                    l.style = new
                    entries += LambdaEntry("Textfarbe", { l.style = old }, { l.style = new })
                }
                is SmartLayer -> smart++
                else -> {}
            }
        }
        doc.colorProfile = p
        entries += LambdaEntry("Profil", { it.colorProfile = before }, { it.colorProfile = p })
        push(de.photographics.core.history.CompoundEntry("In Profil umwandeln", entries))
        return smart
    }

    // ------------------------------------------------------------ Auswahl

    fun setSelection(sel: Selection?, label: String = "Auswahl") {
        val before = doc.selection
        val after = sel?.takeIf { !it.isEmpty }
        if (before == null && after == null) return
        doc.selection = after
        push(LambdaEntry(label, { it.selection = before }, { it.selection = after }))
    }

    fun selectAll() = setSelection(Selection.all(doc.width, doc.height), "Alles auswählen")
    fun deselect() = setSelection(null, "Auswahl aufheben")
    fun invertSelection() { doc.selection?.let { setSelection(it.inverted(), "Auswahl umkehren") } }
    fun featherSelection(radius: Float) { doc.selection?.let { setSelection(it.feathered(radius), "Weiche Auswahlkante") } }

    /** Ausgewählte Pixel der aktiven Ebene löschen. */
    fun clearSelection(): IntRect? = editSelected("Löschen") { orig, s -> Colors.withAlpha(orig, (orig ushr 24) * (255 - s) / 255) }

    fun fillSelection(color: Int): IntRect? = editSelected("Fläche füllen") { orig, s ->
        Colors.srcOver(orig, Colors.withAlpha(color, Colors.a(color) * s / 255))
    }

    private fun editSelected(label: String, f: (Int, Int) -> Int): IntRect? {
        val layer = doc.activeLayer as? PixelLayer ?: return null
        val sel = doc.selection ?: Selection.all(doc.width, doc.height)
        val r = sel.bounds
        if (r.isEmpty) return null
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
        structural("Ebene durch Kopie", { p.add(newLayer, idx); doc.activeLayer = newLayer }, { p.remove(newLayer) })
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
        tx.commit("Inhaltsbasiert füllen")?.let { push(it) }
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
        tx.commit("Ausbessern")?.let { push(it) }
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
        tx.commit("Rote Augen entfernen")?.let { push(it) }
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
        addLayer(name, grid, "Platzieren")
    }

    fun placeImage(name: String, w: Int, h: Int, px: IntArray) {
        val grid = doc.newGrid()
        val dx = (doc.width - w) / 2; val dy = (doc.height - h) / 2
        grid.writeRect(IntRect(dx, dy, dx + w, dy + h), px)
        grid.compact()
        addLayer(name, grid, "Platzieren")
    }
}

/** Vergrößert ein Raster direkt auf die Zielgröße (z. B. KI-Super-Resolution, kachelweise). */
fun interface Upscaler {
    fun upscale(src: TileGrid, targetW: Int, targetH: Int): TileGrid
}
