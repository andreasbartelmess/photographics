package de.photographics.gnome

import de.photographics.core.doc.Layer
import de.photographics.core.doc.PixelLayer
import de.photographics.core.doc.RasterLayer
import de.photographics.core.doc.SmartLayer
import de.photographics.core.doc.TextLayer
import de.photographics.core.doc.TextStyle
import de.photographics.core.editor.Editor
import de.photographics.core.editor.Gradient
import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.render.Compositor
import de.photographics.core.selection.MagicWand
import de.photographics.core.selection.ObjectSelect
import de.photographics.core.selection.Selection
import de.photographics.core.selection.SelectionOp
import de.photographics.core.selection.SelectionShape
import de.photographics.core.tools.BackgroundEraserTool
import de.photographics.core.tools.BrushTool
import de.photographics.core.tools.CloneTool
import de.photographics.core.tools.DodgeBurnTool
import de.photographics.core.tools.EraserTool
import de.photographics.core.tools.HealTool
import de.photographics.core.tools.LiquifyTool
import de.photographics.core.tools.PaintTarget
import de.photographics.core.tools.PaintTool
import de.photographics.core.tools.SharpenTool
import de.photographics.core.tools.SmudgeTool
import de.photographics.core.tools.SpongeTool
import de.photographics.core.tools.SpotHealTool
import de.photographics.core.tools.Stroke
import de.photographics.core.tools.BlurTool
import de.photographics.core.tr
import org.gnome.gdk.ModifierType
import org.gnome.gtk.Snapshot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Werkzeuge auf der Zeichenfläche und alle Bearbeitungsbefehle eines Fensters (entspricht dem
 * ViewModel der Android-App). Felder mit `e`-Präfix gehören dem Engine-Thread.
 */
class Controller(private val canvas: CanvasView, private val ui: Ui) : CanvasInput {
    /** Rückmeldungen an das Fenster (immer im Hauptthread). */
    interface Ui {
        fun stateChanged(st: DocState, dirty: IntRect?)
        fun optionsChanged(o: ToolOptions)
        fun toast(msg: String)
        fun error(heading: String, e: Throwable)
        fun busy(on: Boolean)
        fun editText(layerId: Int, style: TextStyle)
        fun requestAiModels()
    }

    var session: Session? = null
        private set
    var state: DocState? = null
        private set
    var opts = ToolOptions()
        private set

    fun setOptions(f: (ToolOptions) -> ToolOptions) {
        val old = opts
        opts = f(opts)
        if (old.tool != opts.tool) toolChanged(old.tool)
        else if (opts.tool == Tool.CROP) cropOptionsChanged(old)
        ui.optionsChanged(opts)
        canvas.updateCursor()
    }

    fun attach(s: Session, st: DocState) {
        session = s; state = st
        crop = null; transform = null; drag = null
        if (opts.tool == Tool.CROP) startCrop()
    }

    // ------------------------------------------------------------ Bearbeiten

    /** Bearbeitung auf dem Engine-Thread; [block] liefert den neu zu zeichnenden Bereich (null = alles, leer = nichts). */
    fun edit(label: String?, busy: Boolean = false, block: (Editor) -> IntRect?) {
        val s = session ?: return
        if (busy) ui.busy(true)
        Engine.call({
            gpuEnd()
            val rect = block(s.editor)
            s.dirty = true
            rect to s.state()
        }) { r ->
            if (busy) ui.busy(false)
            r.onSuccess { (rect, st) -> publish(st, rect) }
                .onFailure { e -> ui.error(label ?: tr("Bearbeitung fehlgeschlagen"), e) }
        }
    }

    /** Wie [edit], aber ohne Änderungsmarke (Auswahl einer Ebene, Ansicht). */
    fun query(block: (Editor) -> IntRect?) {
        val s = session ?: return
        Engine.call({ gpuEnd(); block(s.editor) to s.state() }) { r -> r.onSuccess { (rect, st) -> publish(st, rect) } }
    }

    // ------------------------------------------------------------ GPU-Vorschau

    private var eGpu: GpuSandwich.Built? = null
    private var eGpuDirty = IntRect.EMPTY

    /**
     * Engine-Thread: Live-Änderung an [l] zeigen (Verschieben, Transformieren, Deckkraft) – wenn
     * möglich über die GPU ([GpuLive]), sonst die Kacheln im Bereich [r] neu rechnen. [v] im
     * Hauptthread erfasst ([CanvasView.liveView]).
     */
    fun liveLayer(l: Layer, r: IntRect, v: LiveView) {
        val s = session ?: return
        var g = eGpu?.takeIf { it.layerId == l.id }
        if (g == null && l is RasterLayer && GpuSandwich.eligible(s.doc, l, v)) {
            gpuEnd()
            g = GpuSandwich.build(s.doc, l, v)
            if (g != null) { eGpu = g; val built = g; onMain { canvas.gpuLive = built.toLive() } }
        }
        if (g == null || l !is RasterLayer) { repaint(r); return }
        eGpuDirty = eGpuDirty.union(r)
        val m = GpuSandwich.matrix(l, g); val a = (l.opacity * l.fillOpacity).coerceIn(0f, 1f)
        val id = l.id
        onMain { canvas.updateGpu(id, m, a) }
    }

    /** Engine-Thread: GPU-Vorschau beenden – die Anzeige rechnet den betroffenen Bereich neu und blendet sie dann aus. */
    private fun gpuEnd() {
        if (eGpu == null) return
        val d = eGpuDirty
        eGpu = null; eGpuDirty = IntRect.EMPTY
        onMain { state?.let { canvas.endGpu(d, it) } }
    }

    private fun publish(st: DocState, rect: IntRect?) {
        state = st
        ui.stateChanged(st, rect)
        // Größe geändert (Freistellen, Drehen …): laufende Rahmen passen nicht mehr
        if (crop != null && opts.tool == Tool.CROP && (crop!![2] > st.width + 0.5 || crop!![3] > st.height + 0.5)) startCrop()
    }

    /** Vom Engine-Thread: nur die Anzeige im Bereich [r] auffrischen (ohne neuen Zustand). */
    private fun repaint(r: IntRect?) {
        if (r != null && r.isEmpty) return
        onMain { state?.let { canvas.invalidate(r, it, live = true) } }
    }

    private fun message(msg: String) = onMain { ui.toast(msg) }
    fun toastError(msg: String) = ui.toast(msg)

    // ------------------------------------------------------------ Werkzeugwechsel

    private fun toolChanged(old: Tool) {
        if (old == Tool.CROP) crop = null
        poly = null
        if (old == Tool.PEN) penFinish(false)
        anchorSel = null
        if (old == Tool.TRANSFORM && transform != null) cancelTransform()
        drag = null
        when (opts.tool) {
            Tool.CROP -> startCrop()
            Tool.TRANSFORM -> beginTransform()
            else -> {}
        }
        canvas.queueDraw()
    }

    // ------------------------------------------------------------ Eingaben

    /** Laufender Zug: Art und Punkte in Dokumentkoordinaten. */
    private class Drag(val kind: Kind, val x0: Double, val y0: Double, val mods: Set<ModifierType>) {
        enum class Kind { STROKE, MOVE, MOVE_SELECTION, SHAPE, PEN, ANCHOR, RECT, ELLIPSE, LASSO, OBJECT, QUICK, GRADIENT, PICK, CROP, TRANSFORM, PATCH, TEXT, RED_EYE, GUIDE, RULER, ZOOM, NONE }
        var x = x0; var y = y0
        val points = ArrayList<Float>().apply { add(x0.toFloat()); add(y0.toFloat()) }
    }

    private var drag: Drag? = null

    private fun selectionOp(mods: Set<ModifierType>) = when {
        ModifierType.SHIFT_MASK in mods -> SelectionOp.ADD
        ModifierType.ALT_MASK in mods -> SelectionOp.SUBTRACT
        else -> opts.selectionOp
    }

    override fun press(x: Double, y: Double, pressure: Float, mods: Set<ModifierType>): Boolean {
        val st = state ?: return false
        val kind = when (opts.tool) {
            Tool.HAND -> return false
            Tool.MOVE -> {
                // Hilfslinie unter dem Zeiger: verschieben (aus dem Bild ziehen = löschen)
                val g = guideAt(x, y)
                if (g >= 0) { guideDrag = g; guidePos = st.guides[g].position; Drag.Kind.GUIDE }
                else { moveBegin(x, y, mods); Drag.Kind.MOVE }
            }
            Tool.TRANSFORM -> if (transformPress(x, y, mods)) Drag.Kind.TRANSFORM else return false
            // In der Auswahl ohne Zusatztaste ziehen: nur den Auswahlrahmen verschieben (wie Photoshop)
            Tool.SELECT, Tool.LASSO -> if (mods.isEmpty() && st.hasSelection && insideSelection(x, y)) Drag.Kind.MOVE_SELECTION
                else if (opts.tool == Tool.LASSO) Drag.Kind.LASSO
                else if (opts.selectionShape == SelectionShape.ELLIPSE) Drag.Kind.ELLIPSE else Drag.Kind.RECT
            Tool.POLY_LASSO -> { polyPress(x, y, mods); Drag.Kind.NONE }
            Tool.SHAPE_RECT, Tool.SHAPE_ELLIPSE, Tool.SHAPE_POLYGON, Tool.SHAPE_LINE -> Drag.Kind.SHAPE
            Tool.PEN -> if (penPress(x, y)) Drag.Kind.PEN else Drag.Kind.NONE
            Tool.DIRECT_SELECT -> if (anchorPress(x, y, mods)) Drag.Kind.ANCHOR else return false
            Tool.WAND -> { magicWand(x, y, mods); Drag.Kind.NONE }
            Tool.OBJECT_SELECT -> Drag.Kind.OBJECT
            Tool.QUICK_SELECT -> Drag.Kind.QUICK
            Tool.BUCKET -> { bucketFill(x, y); Drag.Kind.NONE }
            Tool.MAGIC_ERASER -> { magicErase(x, y); Drag.Kind.NONE }
            Tool.RULER -> Drag.Kind.RULER
            Tool.ZOOM -> Drag.Kind.ZOOM
            Tool.CROP -> { cropPress(x, y); Drag.Kind.CROP }
            Tool.GRADIENT -> Drag.Kind.GRADIENT
            Tool.EYEDROPPER -> { pick(x, y, mods); Drag.Kind.PICK }
            Tool.PATCH -> if (st.selectionOutlines.isNotEmpty() && insideSelection(x, y)) Drag.Kind.PATCH else Drag.Kind.LASSO
            Tool.TEXT -> Drag.Kind.TEXT
            Tool.RED_EYE -> Drag.Kind.RED_EYE
            Tool.CLONE, Tool.HEAL -> {
                if (ModifierType.ALT_MASK in mods || ModifierType.CONTROL_MASK in mods) {
                    cloneSource = x to y; cloneOffset = null
                    ui.toast(tr("Quelle gesetzt"))
                    canvas.queueDraw()
                    Drag.Kind.NONE
                } else if (cloneSource == null) {
                    ui.toast(tr("Zuerst mit Strg+Klick eine Quelle festlegen."))
                    Drag.Kind.NONE
                } else { strokeBegin(x, y, pressure); Drag.Kind.STROKE }
            }
            else -> if (opts.tool.isPaintTool) { strokeBegin(x, y, pressure); Drag.Kind.STROKE } else return false
        }
        drag = Drag(kind, x, y, mods)
        return true
    }

    override fun drag(x: Double, y: Double, pressure: Float, mods: Set<ModifierType>) {
        val d = drag ?: return
        d.x = x; d.y = y
        when (d.kind) {
            Drag.Kind.STROKE -> { strokeMove(x, y, pressure); d.points += x.toFloat(); d.points += y.toFloat() }
            Drag.Kind.MOVE -> moveTo(x - d.x0, y - d.y0)
            Drag.Kind.GUIDE -> state?.guides?.getOrNull(guideDrag)?.let { g -> guidePos = snapGuide(g.vertical, if (g.vertical) x else y) }
            Drag.Kind.LASSO, Drag.Kind.QUICK -> { d.points += x.toFloat(); d.points += y.toFloat() }
            Drag.Kind.RULER -> setOptions { it.copy(rulerLine = listOf(d.x0, d.y0, x, y)) }
            Drag.Kind.PEN -> penDrag(x, y)
            Drag.Kind.ANCHOR -> anchorDrag(x, y)
            Drag.Kind.PICK -> pick(x, y, mods)
            Drag.Kind.CROP -> cropDrag(d, x, y, mods)
            Drag.Kind.TRANSFORM -> transformDrag(d, x, y, mods)
            else -> {}
        }
        canvas.queueDraw()
    }

    override fun release(x: Double, y: Double, mods: Set<ModifierType>) {
        val d = drag ?: return
        drag = null
        val moved = hypot(x - d.x0, y - d.y0) * canvas.zoom > 3
        when (d.kind) {
            Drag.Kind.STROKE -> strokeEnd()
            Drag.Kind.MOVE -> moveEnd()
            Drag.Kind.GUIDE -> {
                val i = guideDrag; val pos = guidePos
                guideDrag = -1
                val st = state
                val g = st?.guides?.getOrNull(i)
                if (g != null) {
                    val size = if (g.vertical) st.width else st.height
                    val list = st.guides.toMutableList()
                    if (pos < 0 || pos > size) list.removeAt(i) else list[i] = g.copy(position = pos)
                    edit(tr("Hilfslinie verschieben")) { it.setGuides(tr("Hilfslinie verschieben"), list); IntRect.EMPTY }
                }
            }
            Drag.Kind.SHAPE -> if (moved) createShape(shapeFromDrag(d.x0, d.y0, x, y, mods))
            Drag.Kind.ANCHOR -> anchorRelease()
            Drag.Kind.MOVE_SELECTION -> if (moved) {
                val dx = (x - d.x0).roundToInt(); val dy = (y - d.y0).roundToInt()
                edit(tr("Auswahl verschieben")) { it.moveSelection(dx, dy); IntRect.EMPTY }
            }
            Drag.Kind.RECT, Drag.Kind.ELLIPSE -> if (moved) selectShape(d, x, y) else if (d.mods.isEmpty()) edit(null) { it.deselect(); IntRect.EMPTY }
            Drag.Kind.LASSO -> if (d.points.size >= 6) selectLasso(d) else if (d.mods.isEmpty()) edit(null) { it.deselect(); IntRect.EMPTY }
            Drag.Kind.OBJECT -> if (moved) objectSelect(d.x0, d.y0, x, y, d.mods)
            Drag.Kind.QUICK -> quickSelect(d.points.toFloatArray(), d.mods)
            Drag.Kind.RULER -> if (!moved) setOptions { it.copy(rulerLine = null) }
            Drag.Kind.ZOOM -> if (moved) canvas.zoomToDocRect(min(d.x0, x), min(d.y0, y), max(d.x0, x), max(d.y0, y))
                else canvas.zoomStepAt(ModifierType.ALT_MASK !in d.mods, x, y)
            Drag.Kind.GRADIENT -> if (moved) applyGradient(d.x0, d.y0, x, y)
            Drag.Kind.PATCH -> if (moved) edit(tr("Ausbessern"), busy = true) { it.patchSelection((x - d.x0).roundToInt(), (y - d.y0).roundToInt()) ?: IntRect.EMPTY }
            Drag.Kind.TEXT -> textTap(x, y)
            Drag.Kind.RED_EYE -> redEye(x, y)
            Drag.Kind.TRANSFORM -> transformRelease()
            Drag.Kind.CROP -> cropRelease(d, x, y)
            else -> {}
        }
        canvas.queueDraw()
    }

    override fun hover(x: Double, y: Double) {
        if (opts.tool == Tool.MOVE) canvas.updateCursor()
        if (opts.tool.isPaintTool || opts.tool == Tool.CROP || opts.tool == Tool.TRANSFORM || poly != null || pen != null) canvas.queueDraw()
    }

    override fun doubleClick(x: Double, y: Double) {
        when (opts.tool) {
            Tool.CROP -> applyCrop()
            Tool.TRANSFORM -> applyTransform()
            Tool.POLY_LASSO -> polyClose()
            else -> {}
        }
    }

    override fun cursor(): String? = when (opts.tool) {
        Tool.HAND -> "grab"
        Tool.MOVE -> if (canvas.pointerInside) when (state?.guides?.getOrNull(guideAt(canvas.pointerDocX, canvas.pointerDocY))?.vertical) { true -> "col-resize"; false -> "row-resize"; null -> "move" } else "move"
        Tool.TEXT -> "text"
        Tool.ZOOM -> "zoom-in"
        Tool.BUCKET, Tool.MAGIC_ERASER, Tool.RULER, Tool.OBJECT_SELECT, Tool.QUICK_SELECT,
        Tool.EYEDROPPER, Tool.SELECT, Tool.LASSO, Tool.POLY_LASSO, Tool.WAND, Tool.SHAPE_RECT, Tool.SHAPE_ELLIPSE, Tool.SHAPE_POLYGON, Tool.SHAPE_LINE, Tool.PEN, Tool.GRADIENT, Tool.CROP, Tool.PATCH, Tool.RED_EYE -> "crosshair"
        Tool.TRANSFORM -> transformCursor()
        else -> if (opts.tool.isPaintTool) "none" else null
    }

    /** Escape: laufende Aktion abbrechen. */
    fun cancel() {
        when {
            transform != null -> cancelTransform()
            poly != null -> poly = null
            // Wie Photoshop: Esc beendet den Pfad (er bleibt erhalten)
            pen != null -> penFinish(false)
            opts.tool == Tool.CROP -> { crop = null; setOptions { it.copy(tool = Tool.MOVE) } }
            opts.tool == Tool.RULER && opts.rulerLine != null -> setOptions { it.copy(rulerLine = null) }
            drag != null -> { if (drag!!.kind == Drag.Kind.STROKE) strokeCancel(); drag = null }
        }
        canvas.queueDraw()
    }

    /** Eingabetaste: Freistellen/Transformieren anwenden. */
    fun confirm() {
        when {
            transform != null -> applyTransform()
            opts.tool == Tool.CROP -> applyCrop()
            opts.tool == Tool.RULER -> straighten()
            poly != null -> polyClose()
            pen != null -> penFinish(false)
        }
    }

    /** Eingabetaste/Doppelklick braucht das Fenster nur, wenn etwas zu bestätigen ist. */
    val confirmable get() = transform != null || opts.tool == Tool.CROP || poly != null || pen != null || (opts.tool == Tool.RULER && opts.rulerLine != null)

    // ------------------------------------------------------------ Formen, Zeichenstift, Direktauswahl

    /** Im Pfade-Bedienfeld gewählter Pfad: -1 = Arbeitspfad, sonst Index der gesicherten Pfade. */
    var selectedPath = -1
        set(v) { field = v; canvas.queueDraw() }
    /** Pfade-Bedienfeld sichtbar: dann den gewählten Pfad auch ohne Pfadwerkzeug zeigen. */
    var pathsVisible = false
        set(v) { field = v; canvas.queueDraw() }

    /** Pfad, den Zeichenstift und Direktauswahl bearbeiten: aktive Formebene, sonst der gewählte Pfad. */
    private fun targetPath(st: DocState): de.photographics.core.vector.VectorPath? =
        st.shapePath ?: if (selectedPath >= 0) st.paths.getOrNull(selectedPath)?.path else st.workPath

    /** Form aus einem Zug: Umschalt = gleichseitig (Linie: 45°), Alt = vom Mittelpunkt aus. */
    private fun shapeFromDrag(x0: Double, y0: Double, x1: Double, y1: Double, mods: Set<ModifierType>): de.photographics.core.vector.VectorPath {
        val shift = ModifierType.SHIFT_MASK in mods; val alt = ModifierType.ALT_MASK in mods
        val o = opts
        if (o.tool == Tool.SHAPE_LINE) {
            var ex = x1; var ey = y1
            if (shift) {
                val a = Math.round(atan2(y1 - y0, x1 - x0) / (Math.PI / 4)) * (Math.PI / 4); val len = hypot(x1 - x0, y1 - y0)
                ex = x0 + len * kotlin.math.cos(a); ey = y0 + len * kotlin.math.sin(a)
            }
            return de.photographics.core.vector.VectorPath.line(x0, y0, ex, ey, o.lineWidth.toDouble())
        }
        var dx = x1 - x0; var dy = y1 - y0
        if (shift) { val s = max(abs(dx), abs(dy)); dx = if (dx < 0) -s else s; dy = if (dy < 0) -s else s }
        val l: Double; val t: Double; val r: Double; val b: Double
        if (alt) { l = x0 - abs(dx); r = x0 + abs(dx); t = y0 - abs(dy); b = y0 + abs(dy) }
        else { l = min(x0, x0 + dx); r = max(x0, x0 + dx); t = min(y0, y0 + dy); b = max(y0, y0 + dy) }
        return when (o.tool) {
            Tool.SHAPE_ELLIPSE -> de.photographics.core.vector.VectorPath.ellipse(l, t, r, b)
            Tool.SHAPE_POLYGON -> de.photographics.core.vector.VectorPath.polygon(l, t, r, b, o.polygonSides, if (o.starIndent > 0) 1.0 - o.starIndent / 100.0 else 1.0)
            else -> de.photographics.core.vector.VectorPath.rect(l, t, r, b, o.cornerRadius.toDouble())
        }
    }

    /** Neue Formebene (Modus „Form“) oder Teilpfad im Arbeitspfad (Modus „Pfad“). */
    private fun createShape(p: de.photographics.core.vector.VectorPath) {
        if (p.isEmpty) return
        val o = opts
        if (o.shapeLayerMode) {
            val stroke = if (o.shapeStroke) de.photographics.core.vector.ShapeStroke(o.strokeWidth, o.strokeColor, o.strokeAlign) else null
            val fill = if (o.shapeFill || stroke == null) o.color else null
            edit(tr("Form erstellen")) { ed -> ed.addShapeLayer(p, fill, stroke); null }
        } else {
            selectedPath = -1
            edit(tr("Arbeitspfad")) { ed ->
                // Neben einer Formebene entsteht der Arbeitspfad unabhängig von ihr
                ed.setWorkPath(de.photographics.core.vector.VectorPath((ed.doc.workPath?.subpaths ?: emptyList()) + p.subpaths)); IntRect.EMPTY
            }
        }
    }

    /** Laufender Teilpfad des Zeichenstifts (Dokument). */
    private var pen: ArrayList<de.photographics.core.vector.Anchor>? = null

    /** Klick des Zeichenstifts: neuer Anker; Klick auf den ersten schließt. false = Pfad beendet. */
    private fun penPress(x: Double, y: Double): Boolean {
        val p = pen
        if (p != null && p.size >= 2 && hypot(x - p[0].x, y - p[0].y) * canvas.zoom <= 8) { penFinish(true); return false }
        (p ?: ArrayList<de.photographics.core.vector.Anchor>().also { pen = it }).add(de.photographics.core.vector.Anchor(x, y))
        canvas.queueDraw()
        return true
    }

    /** Ziehen nach dem Klick: Griffe des neuen Ankers (gegenüberliegend, Übergangspunkt). */
    private fun penDrag(x: Double, y: Double) {
        val p = pen ?: return
        val a = p.last()
        p[p.size - 1] = a.copy(outX = x, outY = y, inX = 2 * a.x - x, inY = 2 * a.y - y, smooth = true)
    }

    /** Teilpfad übernehmen: als Formebene oder in den Arbeitspfad. */
    fun penFinish(closed: Boolean) {
        val p = pen ?: return
        pen = null
        canvas.queueDraw()
        if (p.size < 2) return
        val sub = de.photographics.core.vector.SubPath(p.toList(), closed)
        val o = opts
        if (o.shapeLayerMode) {
            val stroke = if (o.shapeStroke || !closed) de.photographics.core.vector.ShapeStroke(o.strokeWidth, o.strokeColor, if (closed) o.strokeAlign else de.photographics.core.vector.ShapeStroke.Align.CENTER) else null
            val fill = if (closed && (o.shapeFill || stroke == null)) o.color else null
            edit(tr("Form erstellen")) { ed -> ed.addShapeLayer(de.photographics.core.vector.VectorPath(listOf(sub)), fill, stroke); null }
        } else {
            val sel = selectedPath
            edit(tr("Zeichenstift")) { ed ->
                if (sel >= 0 && sel < ed.doc.paths.size) {
                    val list = ed.doc.paths.toMutableList()
                    list[sel] = list[sel].copy(path = de.photographics.core.vector.VectorPath(list[sel].path.subpaths + sub))
                    ed.setPaths(list, tr("Zeichenstift"))
                } else ed.setWorkPath(de.photographics.core.vector.VectorPath((ed.doc.workPath?.subpaths ?: emptyList()) + sub), tr("Zeichenstift"))
                IntRect.EMPTY
            }
        }
    }

    /** Direktauswahl: gewählter Anker (Teilpfad, Anker) und das gezogene Teil (0 = Anker, 1 = Griff ein, 2 = Griff aus). */
    private var anchorSel: Pair<Int, Int>? = null
    private var anchorPart = 0
    private var anchorWork: de.photographics.core.vector.VectorPath? = null

    private fun anchorPress(x: Double, y: Double, mods: Set<ModifierType>): Boolean {
        val st = state ?: return false
        val path = targetPath(st) ?: return false
        val tol = 8 / canvas.zoom
        var best: Triple<Int, Int, Int>? = null; var bd = tol
        for ((si, s) in path.subpaths.withIndex()) for ((ai, a) in s.anchors.withIndex()) {
            for ((part, px, py) in listOf(Triple(0, a.x, a.y), Triple(1, a.inX, a.inY), Triple(2, a.outX, a.outY))) {
                // Griffe nur am gewählten Anker greifbar (wie Photoshop)
                if (part != 0 && anchorSel != si to ai) continue
                val d = hypot(px - x, py - y)
                if (d < bd || (d == bd && part == 0)) { bd = d; best = Triple(si, ai, part) }
            }
        }
        val b = best ?: run { anchorSel = null; canvas.queueDraw(); return false }
        anchorSel = b.first to b.second; anchorPart = b.third
        anchorWork = path
        if (ModifierType.ALT_MASK in mods && b.third == 0) {
            // Ankerpunkt umwandeln: Ecke ↔ Kurve
            val s = path.subpaths[b.first]; val a = s.anchors[b.second]
            val n = s.anchors.size
            val prev = s.anchors[(b.second - 1 + n) % n]; val next = s.anchors[(b.second + 1) % n]
            val na = if (!a.isCorner) de.photographics.core.vector.Anchor(a.x, a.y) else {
                val tx = (next.x - prev.x) / 4; val ty = (next.y - prev.y) / 4
                de.photographics.core.vector.Anchor(a.x, a.y, a.x - tx, a.y - ty, a.x + tx, a.y + ty, true)
            }
            commitTargetPath(replaceAnchor(path, b.first, b.second, na))
            return false
        }
        canvas.queueDraw()
        return true
    }

    private fun replaceAnchor(p: de.photographics.core.vector.VectorPath, si: Int, ai: Int, a: de.photographics.core.vector.Anchor) =
        de.photographics.core.vector.VectorPath(p.subpaths.mapIndexed { i, s -> if (i != si) s else s.copy(anchors = s.anchors.mapIndexed { k, o -> if (k == ai) a else o }) })

    private fun anchorDrag(x: Double, y: Double) {
        val (si, ai) = anchorSel ?: return
        val p = anchorWork ?: return
        val a = p.subpaths[si].anchors[ai]
        val na = when (anchorPart) {
            0 -> a.moved(x - a.x, y - a.y)
            1 -> if (a.smooth) { val len = hypot(a.outX - a.x, a.outY - a.y); val d = hypot(x - a.x, y - a.y).coerceAtLeast(1e-6)
                a.copy(inX = x, inY = y, outX = a.x - (x - a.x) / d * len, outY = a.y - (y - a.y) / d * len) } else a.copy(inX = x, inY = y)
            else -> if (a.smooth) { val len = hypot(a.inX - a.x, a.inY - a.y); val d = hypot(x - a.x, y - a.y).coerceAtLeast(1e-6)
                a.copy(outX = x, outY = y, inX = a.x - (x - a.x) / d * len, inY = a.y - (y - a.y) / d * len) } else a.copy(outX = x, outY = y)
        }
        anchorWork = replaceAnchor(p, si, ai, na)
    }

    private fun anchorRelease() { anchorWork?.let { commitTargetPath(it) } }

    /** Entf mit der Direktauswahl: gewählten Anker löschen. */
    fun deleteAnchor(): Boolean {
        if (opts.tool != Tool.DIRECT_SELECT) return false
        val (si, ai) = anchorSel ?: return false
        val st = state ?: return false
        val p = targetPath(st) ?: return false
        val subs = p.subpaths.mapIndexedNotNull { i, s -> if (i != si) s else s.copy(anchors = s.anchors.filterIndexed { k, _ -> k != ai }).takeIf { it.anchors.size >= 2 } }
        anchorSel = null
        commitTargetPath(de.photographics.core.vector.VectorPath(subs))
        return true
    }

    /** Bearbeiteten Pfad übernehmen: in die Formebene, den gewählten gesicherten Pfad oder den Arbeitspfad. */
    private fun commitTargetPath(p: de.photographics.core.vector.VectorPath) {
        anchorWork = null
        val sel = selectedPath
        edit(tr("Pfad bearbeiten")) { ed ->
            val l = ed.doc.activeLayer
            when {
                l is de.photographics.core.doc.ShapeLayer -> { ed.setShapePath(l, p); null }
                sel >= 0 && sel < ed.doc.paths.size -> { ed.setPaths(ed.doc.paths.toMutableList().also { it[sel] = it[sel].copy(path = p) }, tr("Pfad bearbeiten")); IntRect.EMPTY }
                else -> { ed.setWorkPath(p, tr("Pfad bearbeiten")); IntRect.EMPTY }
            }
        }
    }

    /** Pfad mit Bézierkurven in Widget-Koordinaten. */
    private fun viewPath(p: de.photographics.core.vector.VectorPath): List<org.gnome.gsk.Path> = p.flatten(0.5 / canvas.zoom).mapNotNull { (pts, closed) ->
        Draw.path(FloatArray(pts.size) { i -> if (i % 2 == 0) canvas.viewX(pts[i].toDouble()) else canvas.viewY(pts[i].toDouble()) }, closed)
    }

    /** Pfad-Überlagerungen: gezogene Form, Zielpfad mit Ankern, laufender Zeichenstift-Teilpfad. */
    private fun drawPaths(sn: Snapshot, st: DocState) {
        val t = opts.tool
        drag?.takeIf { it.kind == Drag.Kind.SHAPE }?.let { d -> for (gp in viewPath(shapeFromDrag(d.x0, d.y0, d.x, d.y, canvasMods))) Draw.line(sn, gp) }
        val showTarget = t == Tool.PEN || t == Tool.DIRECT_SELECT || (pathsVisible && st.shapePath == null)
        val target = (anchorWork ?: targetPath(st))?.takeIf { showTarget || t.isShapeTool && st.shapePath != null }
        if (target != null) {
            for (gp in viewPath(target)) sn.appendStroke(gp, org.gnome.gsk.Stroke(1.5f), Draw.ACCENT)
            if (t == Tool.PEN || t == Tool.DIRECT_SELECT) for ((si, s) in target.subpaths.withIndex()) for ((ai, a) in s.anchors.withIndex()) {
                val ax = canvas.viewX(a.x); val ay = canvas.viewY(a.y)
                if (t == Tool.DIRECT_SELECT && anchorSel == si to ai && !a.isCorner) {
                    for ((hx, hy) in listOf(a.inX to a.inY, a.outX to a.outY)) {
                        Draw.path(floatArrayOf(ax, ay, canvas.viewX(hx), canvas.viewY(hy)), false)?.let { sn.appendStroke(it, org.gnome.gsk.Stroke(1f), Draw.ACCENT) }
                        Draw.circle(sn, canvas.viewX(hx), canvas.viewY(hy), 3.5f)
                    }
                }
                Draw.handle(sn, ax, ay, if (anchorSel == si to ai) 8f else 6f)
            }
        }
        pen?.let { p ->
            val cur = de.photographics.core.vector.VectorPath(listOf(de.photographics.core.vector.SubPath(p, false)))
            for (gp in viewPath(cur)) Draw.line(sn, gp)
            val last = p.last()
            if (canvas.pointerInside && drag == null) Draw.path(floatArrayOf(canvas.viewX(last.x), canvas.viewY(last.y), canvas.viewX(canvas.pointerDocX), canvas.viewY(canvas.pointerDocY)), false)?.let { sn.appendStroke(it, org.gnome.gsk.Stroke(1f), Draw.ACCENT) }
            for (a in p) Draw.handle(sn, canvas.viewX(a.x), canvas.viewY(a.y), 6f)
            if (!last.isCorner) Draw.circle(sn, canvas.viewX(last.outX), canvas.viewY(last.outY), 3.5f)
        }
    }

    // ------------------------------------------------------------ Polygon-Lasso

    /** Gesetzte Ecken (Dokument); null = kein Polygon in Arbeit. */
    private var poly: ArrayList<Float>? = null
    /** Zusatztasten beim ersten Klick (Umschalt: hinzufügen, Alt: abziehen). */
    private var polyMods: Set<ModifierType> = emptySet()
    val polyActive get() = poly != null

    /** Umschalt hält die neue Kante in 45°-Schritten zur letzten Ecke (wie Photoshop). */
    private fun polyConstrain(x: Double, y: Double): Pair<Double, Double> {
        val p = poly ?: return x to y
        if (ModifierType.SHIFT_MASK !in canvasMods || p.size < 2) return x to y
        val lx = p[p.size - 2].toDouble(); val ly = p[p.size - 1].toDouble()
        val dx = x - lx; val dy = y - ly
        val a = Math.round(atan2(dy, dx) / (Math.PI / 4)) * (Math.PI / 4)
        val len = hypot(dx, dy) * kotlin.math.cos(atan2(dy, dx) - a)
        return lx + len * kotlin.math.cos(a) to ly + len * kotlin.math.sin(a)
    }

    private fun polyPress(x: Double, y: Double, mods: Set<ModifierType>) {
        val p = poly
        if (p == null) { poly = arrayListOf(x.toFloat(), y.toFloat()); polyMods = mods; canvas.queueDraw(); return }
        // Klick auf den Startpunkt schließt das Polygon
        if (p.size >= 6 && hypot(x - p[0], y - p[1]) * canvas.zoom <= 8) { polyClose(); return }
        val (cx, cy) = polyConstrain(x, y)
        // Doppelklick: der zweite Klick setzt keine zusätzliche Ecke
        if (hypot(cx - p[p.size - 2], cy - p[p.size - 1]) * canvas.zoom < 2) return
        p += cx.toFloat(); p += cy.toFloat()
        canvas.queueDraw()
    }

    /** Rücktaste: letzte Ecke entfernen (die erste entfernt das ganze Polygon). */
    fun polyBack() {
        val p = poly ?: return
        if (p.size <= 2) poly = null else { p.removeAt(p.size - 1); p.removeAt(p.size - 1) }
        canvas.queueDraw()
    }

    private fun polyClose() {
        val p = poly ?: return
        poly = null
        canvas.queueDraw()
        if (p.size < 6) return
        val op = selectionOp(polyMods); val feather = opts.feather
        val pts = p.toFloatArray()
        edit(null) { ed ->
            commitSelection(ed, Selection.polygon(ed.doc.width, ed.doc.height, pts), tr("Polygon-Lasso"), op, feather)
            IntRect.EMPTY
        }
    }

    // ------------------------------------------------------------ Pinsel mit der Maus einstellen

    /** Laufende Einstellung per Alt+Rechtsziehen: Ausgangswerte und Mittelpunkt (Dokument). */
    private var adjust: Triple<Float, Float, Pair<Double, Double>>? = null
    var onContext: (Double, Double) -> Unit = { _, _ -> }

    override fun context(vx: Double, vy: Double) { if (opts.tool.isPaintTool) onContext(vx, vy) }

    override fun brushAdjust(phase: Int, dx: Double, dy: Double) {
        if (!opts.tool.isPaintTool) return
        when (phase) {
            0 -> adjust = Triple(opts.brush.size, opts.brush.hardness, canvas.pointerDocX to canvas.pointerDocY)
            1 -> adjust?.let { (size, hard, _) ->
                // Wie Photoshop: Durchmesser folgt dem Zeiger (Bildschirmpixel), nach oben härter
                val z = canvas.zoom
                val ns = ((size * z + 2 * dx) / z).toFloat().coerceIn(1f, 5000f)
                val nh = (hard - dy / 200.0).toFloat().coerceIn(0f, 1f)
                setOptions { it.copy(brush = it.brush.copy(size = ns, hardness = nh)) }
            }
            else -> { adjust = null; canvas.queueDraw() }
        }
    }

    // ------------------------------------------------------------ Malen

    private var eStroke: Stroke? = null
    private var cloneSource: Pair<Double, Double>? = null
    private var cloneOffset: Pair<Int, Int>? = null
    private val pending = ArrayList<FloatArray>()
    private var pendingQueued = false
    private var airbrush = 0

    private fun createTool(o: ToolOptions, off: Pair<Int, Int>?, lockTransparency: Boolean): PaintTool? = when (o.tool) {
        Tool.BRUSH -> BrushTool(o.color, o.bgColor)
        Tool.PENCIL -> BrushTool(o.color, o.bgColor, pencil = true)
        Tool.BG_ERASER -> BackgroundEraserTool(o.bgEraserTolerance, o.bgEraserOnce, if (o.bgEraserProtect) o.color else null)
        Tool.ERASER -> if (lockTransparency && o.paintTarget == PaintTarget.PIXELS) BrushTool(o.bgColor) else EraserTool()
        Tool.CLONE, Tool.HEAL -> off?.let { if (o.tool == Tool.CLONE) CloneTool(it.first, it.second, o.sampleAll) else HealTool(it.first, it.second, o.sampleAll) }
        Tool.SPOT_HEAL -> SpotHealTool(o.sampleAll)
        Tool.AI_REMOVE -> de.photographics.gnome.ai.AiModelManager.firstReady(de.photographics.gnome.ai.AiPurpose.INPAINT)?.let { m ->
            SpotHealTool(o.sampleAll, de.photographics.gnome.ai.OnnxInpainter(de.photographics.gnome.ai.AiModelManager, m), tr("KI-Entfernen"))
        }
        Tool.SMUDGE -> SmudgeTool(0.3f + o.strength * 0.6f)
        Tool.LIQUIFY -> LiquifyTool(o.liquifyMode, 0.15f + o.strength * 0.85f)
        Tool.BLUR -> BlurTool(sigma = 1f + o.brush.size * 0.04f, strength = o.strength * 2f)
        Tool.SHARPEN -> SharpenTool(strength = o.strength * 2f)
        Tool.DODGE -> DodgeBurnTool(true, o.toneRange, o.strength)
        Tool.BURN -> DodgeBurnTool(false, o.toneRange, o.strength)
        Tool.SPONGE -> SpongeTool(o.spongeSaturate, o.strength)
        else -> null
    }

    private fun strokeBegin(x: Double, y: Double, pressure: Float) {
        val s = session ?: return
        val o = opts
        if (o.tool == Tool.AI_REMOVE && de.photographics.gnome.ai.AiModelManager.firstReady(de.photographics.gnome.ai.AiPurpose.INPAINT) == null) {
            ui.toast(tr("Für „KI-Entfernen“ zuerst ein KI-Modell laden."))
            ui.requestAiModels()
            return
        }
        if (o.tool == Tool.CLONE || o.tool == Tool.HEAL) {
            val src = cloneSource
            if (src != null && cloneOffset == null) cloneOffset = (src.first - x).roundToInt() to (src.second - y).roundToInt()
        }
        val off = cloneOffset
        Engine.post {
            val ed = s.editor
            val layer = ed.doc.activeLayer
            val tool = createTool(o, off, layer?.lockTransparency == true) ?: return@post
            val target = if (o.paintTarget == PaintTarget.MASK && tool.supportsMask && layer?.mask != null) PaintTarget.MASK else PaintTarget.PIXELS
            val st = ed.beginStroke(tool, o.brush, target)
            if (st == null) {
                message(when {
                    layer == null -> tr("Keine Ebene ausgewählt.")
                    !layer.visible -> tr("Die Ebene ist ausgeblendet.")
                    o.paintTarget == PaintTarget.MASK && !tool.supportsMask -> tr("{0} funktioniert nicht auf Masken.", tool.label)
                    else -> tr("Auf dieser Ebene kann nicht gemalt werden (Gruppe?).")
                })
                return@post
            }
            eStroke = st
            repaint(st.addPoint(x.toFloat(), y.toFloat(), pressure))
        }
        if (o.brush.airbrush) airbrush = org.gnome.glib.GLib.timeoutAdd(0, 50) {
            Engine.post { eStroke?.let { repaint(it.airbrushTick()) } }
            drag?.kind == Drag.Kind.STROKE
        }
    }

    private fun strokeMove(x: Double, y: Double, pressure: Float) {
        synchronized(pending) {
            pending += floatArrayOf(x.toFloat(), y.toFloat(), pressure)
            if (pendingQueued) return
            pendingQueued = true
        }
        Engine.post { repaint(drainPoints()) }
    }

    /** Alle bis jetzt eingetroffenen Punkte in einem Durchgang verarbeiten (Engine-Thread). */
    private fun drainPoints(): IntRect {
        val pts = synchronized(pending) { pendingQueued = false; ArrayList(pending).also { pending.clear() } }
        val st = eStroke ?: return IntRect.EMPTY
        var dirty = IntRect.EMPTY
        for (p in pts) dirty = dirty.union(st.addPoint(p[0], p[1], p[2]))
        return dirty
    }

    private fun strokeEnd() {
        if (airbrush != 0) { org.gnome.glib.Source.remove(airbrush); airbrush = 0 }
        val slow = opts.tool in setOf(Tool.SPOT_HEAL, Tool.AI_REMOVE, Tool.HEAL, Tool.SMUDGE)
        edit(null, busy = slow) { ed ->
            val tail = drainPoints().union(eStroke?.catchUp() ?: IntRect.EMPTY)
            val st = eStroke ?: return@edit IntRect.EMPTY
            eStroke = null
            tail.union(ed.endStroke(st))
        }
    }

    private fun strokeCancel() {
        Engine.post {
            synchronized(pending) { pending.clear(); pendingQueued = false }
            val st = eStroke ?: return@post
            eStroke = null
            repaint(st.cancel())
        }
    }

    private fun pick(x: Double, y: Double, mods: Set<ModifierType>) {
        val s = session ?: return
        val toBg = opts.eyedropperToBg || ModifierType.ALT_MASK in mods
        Engine.call({ s.editor.sampleColor(x.toInt(), y.toInt()) }) { r ->
            val c = r.getOrNull() ?: return@call
            if ((c ushr 24) == 0) return@call
            setOptions { if (toBg) it.copy(bgColor = c or 0xFF000000.toInt()) else it.copy(color = c or 0xFF000000.toInt()) }
        }
    }

    private fun redEye(x: Double, y: Double) {
        val radius = (opts.brush.size / 2).roundToInt().coerceAtLeast(4)
        edit(tr("Rote Augen")) { ed ->
            ed.redEye(x.roundToInt(), y.roundToInt(), radius) ?: run { message(tr("Keine roten Pupillen im Pinselkreis gefunden.")); IntRect.EMPTY }
        }
    }

    // ------------------------------------------------------------ Verschieben

    private var eMove: RasterLayer? = null
    private var eSnap: de.photographics.core.editor.Snap.Targets? = null
    /** Zoom beim Ziehen (für die Einrasttoleranz auf dem Engine-Thread). */
    @Volatile private var zoomNow = 1.0
    /** Angezeigte Ausrichtungslinien (intelligente Hilfslinien) beim Verschieben. */
    private var smartLines: List<de.photographics.core.editor.Snap.Line> = emptyList()

    // ------------------------------------------------------------ Hilfslinien

    private var guideDrag = -1
    private var guidePos = 0.0
    /** Aus dem Lineal gezogene, noch nicht abgelegte Hilfslinie (senkrecht?, Lage). */
    private var pendingGuide: Pair<Boolean, Double>? = null

    private fun guideAt(x: Double, y: Double): Int {
        if (!Settings.showGuides) return -1
        val tol = 5 / canvas.zoom
        return state?.guides?.indexOfFirst { g -> kotlin.math.abs((if (g.vertical) x else y) - g.position) <= tol } ?: -1
    }

    /** Hilfslinie an Bildrand und -mitte einrasten lassen. */
    private fun snapGuide(vertical: Boolean, v: Double): Double {
        val st = state ?: return v
        if (!Settings.snap) return Math.round(v).toDouble()
        val size = (if (vertical) st.width else st.height).toDouble()
        val targets = listOf(0.0, size / 2, size).map { de.photographics.core.editor.Snap.Target(it, 0.0, 0.0) }
        return de.photographics.core.editor.Snap.snapValue(Math.round(v).toDouble(), targets, 8 / canvas.zoom)
    }

    /** Aus dem Lineal: 0 = Beginn, 1 = Ziehen, 2 = Ablegen; [pos] null = (noch) nicht über dem Bild. */
    fun rulerDrag(vertical: Boolean, phase: Int, pos: Double?) {
        when (phase) {
            0 -> pendingGuide = null
            1 -> pendingGuide = pos?.let { vertical to snapGuide(vertical, it) }
            else -> {
                val p = pendingGuide
                pendingGuide = null
                if (pos != null && p != null) addGuide(vertical, p.second)
            }
        }
        canvas.queueDraw()
    }

    fun addGuide(vertical: Boolean, pos: Double) {
        val st = state ?: return
        if (pos < 0 || pos > (if (vertical) st.width else st.height)) return
        edit(tr("Neue Hilfslinie")) { it.setGuides(tr("Neue Hilfslinie"), it.doc.guides + de.photographics.core.doc.Guide(vertical, pos)); IntRect.EMPTY }
    }
    private var eMoveBounds = IntRect.EMPTY
    /** Angehobene Auswahl während Verschieben/Transformieren (Engine-Thread). */
    private var eFloat: de.photographics.core.editor.Floating?
        get() = session?.floating
        set(v) { session?.floating = v }
    private var eMoveMask = false

    private fun moveBegin(x: Double, y: Double, mods: Set<ModifierType>) {
        val s = session ?: return
        zoomNow = canvas.zoom
        // In der Auswahl: die ausgewählten Pixel verschieben (Alt: eine Kopie), wie Photoshop
        val inSel = insideSelection(x, y)
        val pick = !inSel && opts.autoSelectLayer != (ModifierType.CONTROL_MASK in mods)
        val target = opts.paintTarget
        val dup = ModifierType.ALT_MASK in mods
        Engine.call({
            val ed = s.editor
            if (inSel && target == PaintTarget.PIXELS && ed.doc.activeLayer is PixelLayer) {
                val f = ed.floatSelection(duplicate = dup)
                if (f != null) {
                    eFloat = f; eMove = f.layer; eMoveMask = false; eMoveBounds = f.bounds
                    eSnap = if (Settings.snap) de.photographics.core.editor.Snap.targets(ed.doc, f.layer) else null
                    repaint(f.bounds)
                    return@call null
                }
            }
            var changed = false
            if (pick) {
                val hit = de.photographics.core.doc.LayerPick.at(ed.doc, x.toInt(), y.toInt())
                if (hit != null && hit !== ed.doc.activeLayer) { ed.setActive(hit); changed = true }
            }
            val l = ed.doc.activeLayer
            if (l !is RasterLayer) {
                message(tr("Gruppen und Einstellungsebenen können nicht verschoben werden."))
                eMove = null
            } else {
                eMove = l
                eSnap = if (Settings.snap) de.photographics.core.editor.Snap.targets(ed.doc, l) else null
                val m = l.mask
                eMoveMask = m != null && !m.linked && target == PaintTarget.MASK
                eMoveBounds = if (eMoveMask) ed.doc.bounds else l.raster.contentBounds().let { b -> if (m != null && m.linked) b.union(m.grid.contentBounds()) else b }
            }
            if (changed) s.state() else null
        }) { r -> r.getOrNull()?.let { publish(it, IntRect.EMPTY) } }
    }

    private fun moveTo(dx: Double, dy: Double) {
        val v = canvas.liveView()
        Engine.post {
            val l = eMove ?: return@post
            val ix = dx.roundToInt(); val iy = dy.roundToInt()
            if (eMoveMask) {
                val m = l.mask ?: return@post
                m.previewDx = ix; m.previewDy = iy
                repaint(eMoveBounds)
                return@post
            }
            var sx = ix; var sy = iy
            val t = eSnap
            if (t != null && !eMoveBounds.isEmpty) {
                val r = de.photographics.core.editor.Snap.snap(eMoveBounds.offset(ix, iy), t, 8 / zoomNow)
                sx += r.dx.roundToInt(); sy += r.dy.roundToInt()
                val lines = r.lines
                onMain { if (smartLines != lines) { smartLines = lines; canvas.queueDraw() } }
            }
            if (sx == l.previewDx && sy == l.previewDy) return@post
            val old = eMoveBounds.offset(l.previewDx, l.previewDy)
            l.previewDx = sx; l.previewDy = sy
            liveLayer(l, old.union(eMoveBounds.offset(sx, sy)), v)
        }
    }

    private fun moveEnd() {
        smartLines = emptyList()
        edit(tr("Verschieben")) { ed ->
            // Ein noch laufender Zug-Auftrag kann die Ausrichtungslinien gerade wieder gesetzt haben
            onMain { smartLines = emptyList(); canvas.queueDraw() }
            val l = eMove ?: return@edit IntRect.EMPTY
            eMove = null; eSnap = null
            eFloat?.let { f -> eFloat = null; return@edit ed.commitFloating(f, tr("Auswahl verschieben")).union(f.bounds.offset(l.previewDx, l.previewDy)) }
            val m = l.mask
            if (eMoveMask && m != null) {
                ed.commitMove(l, m.previewDx, m.previewDy, PaintTarget.MASK)
                return@edit eMoveBounds
            }
            val r = eMoveBounds.union(eMoveBounds.offset(l.previewDx, l.previewDy))
            ed.commitMove(l, l.previewDx, l.previewDy, PaintTarget.PIXELS)
            r
        }
    }

    /** Pfeiltasten: aktive Ebene um ([dx], [dy]) verschieben. */
    fun nudge(dx: Int, dy: Int) {
        val target = opts.paintTarget
        val withSel = state?.hasSelection == true
        edit(tr("Verschieben")) { ed ->
            // Mit Auswahl: die ausgewählten Pixel verschieben
            if (withSel && target == PaintTarget.PIXELS && ed.doc.activeLayer is PixelLayer) {
                val f = ed.floatSelection() ?: return@edit IntRect.EMPTY
                f.layer.previewDx = dx; f.layer.previewDy = dy
                return@edit ed.commitFloating(f, tr("Auswahl verschieben"))
            }
            val l = ed.doc.activeLayer as? RasterLayer ?: return@edit IntRect.EMPTY
            val b = l.raster.contentBounds()
            ed.commitMove(l, dx, dy, target)
            b.union(b.offset(dx, dy))
        }
    }

    // ------------------------------------------------------------ Auswahl

    private fun commitSelection(ed: Editor, sel: Selection, label: String, op: SelectionOp, feather: Float) {
        val s = if (feather > 0f) sel.feathered(feather) else sel
        val existing = ed.doc.selection
        ed.setSelection(if (existing != null) existing.combine(s, op) else s, label)
    }

    private fun selectShape(d: Drag, x: Double, y: Double) {
        val op = selectionOp(d.mods); val feather = opts.feather
        val (x0, y0, x1, y1) = constrained(d, x, y)
        edit(null) { ed ->
            val doc = ed.doc
            val sel = if (d.kind == Drag.Kind.ELLIPSE) Selection.ellipse(doc.width, doc.height, x0, y0, x1, y1)
                else Selection.rect(doc.width, doc.height, x0, y0, x1, y1)
            commitSelection(ed, sel, tr("Auswahl"), op, feather)
            IntRect.EMPTY
        }
    }

    /** Rechteck/Ellipse; mit Strg quadratisch bzw. kreisrund. */
    private fun constrained(d: Drag, x: Double, y: Double): List<Float> {
        var x1 = x; var y1 = y
        if (ModifierType.CONTROL_MASK in d.mods || ModifierType.CONTROL_MASK in canvasMods) {
            val s = max(abs(x - d.x0), abs(y - d.y0))
            x1 = d.x0 + s * (if (x >= d.x0) 1 else -1); y1 = d.y0 + s * (if (y >= d.y0) 1 else -1)
        }
        return listOf(min(d.x0, x1).toFloat(), min(d.y0, y1).toFloat(), max(d.x0, x1).toFloat(), max(d.y0, y1).toFloat())
    }

    /** Vom Fenster gesetzt: aktuell gedrückte Zusatztasten. */
    var canvasMods: Set<ModifierType> = emptySet()

    private fun selectLasso(d: Drag) {
        val op = selectionOp(d.mods); val feather = opts.feather
        val pts = d.points.toFloatArray()
        edit(null) { ed ->
            val doc = ed.doc
            val sel = Selection.polygon(doc.width, doc.height, pts)
            if (opts.tool == Tool.PATCH) ed.setSelection(sel) else commitSelection(ed, sel, tr("Lasso"), op, feather)
            IntRect.EMPTY
        }
    }

    private fun magicWand(x: Double, y: Double, mods: Set<ModifierType>) {
        val o = opts; val op = selectionOp(mods)
        edit(tr("Zauberstab"), busy = true) { ed ->
            val d = ed.doc
            val px = x.toInt(); val py = y.toInt()
            if (px !in 0 until d.width || py !in 0 until d.height) return@edit IntRect.EMPTY
            val src = if (o.sampleAll) d.newGrid().also { g -> Compositor.renderBands(d, d.bounds) { r, buf -> g.writeRect(r, buf) } }
                else (d.activeLayer as? RasterLayer)?.raster ?: run { message(tr("Zauberstab braucht eine Pixelebene oder „Alle Ebenen“.")); return@edit IntRect.EMPTY }
            commitSelection(ed, MagicWand.select(src, px, py, o.wandTolerance, o.wandContiguous), tr("Zauberstab"), op, o.feather)
            IntRect.EMPTY
        }
    }

    /** Schnellauswahl: nach dem ersten Strich fügt jeder weitere hinzu (wie Photoshop), Alt zieht ab. */
    private fun quickSelect(points: FloatArray, mods: Set<ModifierType>) {
        val o = opts
        val op = when {
            ModifierType.ALT_MASK in mods -> SelectionOp.SUBTRACT
            ModifierType.SHIFT_MASK in mods -> SelectionOp.ADD
            o.selectionOp == SelectionOp.REPLACE && state?.hasSelection == true -> SelectionOp.ADD
            else -> o.selectionOp
        }
        edit(tr("Schnellauswahl"), busy = true) { ed ->
            val d = ed.doc
            val src = if (o.sampleAll) d.newGrid().also { g -> Compositor.renderBands(d, d.bounds) { r, buf -> g.writeRect(r, buf) } }
                else (d.activeLayer as? RasterLayer)?.raster ?: run { message(tr("Schnellauswahl braucht eine Pixelebene oder „Alle Ebenen“.")); return@edit IntRect.EMPTY }
            val sel = de.photographics.core.selection.QuickSelect.select(src, points, o.brush.size / 2f)
            if (!sel.isEmpty) commitSelection(ed, sel, tr("Schnellauswahl"), op, o.feather)
            IntRect.EMPTY
        }
    }

    private fun bucketFill(x: Double, y: Double) {
        val o = opts
        edit(tr("Füllwerkzeug"), busy = true) { ed ->
            if (ed.doc.activeLayer !is de.photographics.core.doc.PixelLayer) { message(tr("Das Füllwerkzeug braucht eine Pixelebene.")); return@edit IntRect.EMPTY }
            ed.bucketFill(x.toInt(), y.toInt(), o.color, o.fillTolerance, o.wandContiguous, o.sampleAll, o.fillBlend, o.fillOpacity, o.antiAlias) ?: IntRect.EMPTY
        }
    }

    private fun magicErase(x: Double, y: Double) {
        val o = opts
        edit(tr("Magischer Radiergummi"), busy = true) { ed ->
            if (ed.doc.activeLayer !is de.photographics.core.doc.PixelLayer) { message(tr("Der Magische Radiergummi braucht eine Pixelebene.")); return@edit IntRect.EMPTY }
            ed.magicErase(x.toInt(), y.toInt(), o.fillTolerance, o.wandContiguous, o.sampleAll, o.fillOpacity, o.antiAlias) ?: IntRect.EMPTY
        }
    }

    /** Linealwerkzeug: Winkel der gemessenen Linie zur nächsten Waagerechten bzw. Senkrechten (Grad). */
    fun rulerStraightenAngle(l: List<Double>): Double {
        val a = Math.toDegrees(atan2(l[3] - l[1], l[2] - l[0]))
        // Auf den kleinsten Winkel zu 0°/90°/180° bringen
        var r = a % 90.0
        if (r > 45) r -= 90.0 else if (r < -45) r += 90.0
        return r
    }

    /** Bild so drehen, dass die gemessene Linie waagerecht bzw. senkrecht wird (Photoshop: „Ebene gerade ausrichten“). */
    private fun straighten() {
        val l = opts.rulerLine ?: return
        val angle = -rulerStraightenAngle(l)
        val bg = opts.bgColor
        setOptions { it.copy(rulerLine = null) }
        if (abs(angle) < 0.01) return
        edit(tr("Gerade ausrichten"), busy = true) { it.rotateArbitrary(angle, bg); null }
    }

    private fun objectSelect(x0: Double, y0: Double, x1: Double, y1: Double, mods: Set<ModifierType>) {
        val op = selectionOp(mods); val feather = opts.feather
        edit(tr("Objektauswahl"), busy = true) { ed ->
            val d = ed.doc
            val r = IntRect(min(x0, x1).toInt(), min(y0, y1).toInt(), max(x0, x1).toInt() + 1, max(y0, y1).toInt() + 1)
            val sel = ObjectSelect.select(d.width, d.height, r, { rr, st -> Compositor.renderArgbSampled(d, rr, st) })
            if (sel.bounds.isEmpty) message(tr("Kein Objekt im Rechteck gefunden.")) else commitSelection(ed, sel, tr("Objektauswahl"), op, feather)
            IntRect.EMPTY
        }
    }

    private fun insideSelection(x: Double, y: Double): Boolean {
        val outlines = state?.selectionOutlines ?: return false
        var inside = false
        for (poly in outlines) {
            var j = poly.size - 2
            var i = 0
            while (i < poly.size) {
                val xi = poly[i]; val yi = poly[i + 1]; val xj = poly[j]; val yj = poly[j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i; i += 2
            }
        }
        return inside
    }

    private fun applyGradient(x0: Double, y0: Double, x1: Double, y1: Double) {
        val o = opts
        edit(tr("Verlauf"), busy = true) { ed ->
            val l = ed.doc.activeLayer
            val target = if (o.paintTarget == PaintTarget.MASK && l?.mask != null) PaintTarget.MASK else PaintTarget.PIXELS
            val c1 = if (o.gradientToTransparent) o.color and 0x00FFFFFF else o.bgColor
            ed.fillGradient(target, Gradient(o.gradientType, x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), o.color, c1), o.gradientOpacity)
                ?: run { message(tr("Verlauf nur auf Pixelebenen oder Masken.")); IntRect.EMPTY }
        }
    }

    // ------------------------------------------------------------ Freistellen

    /**
     * Rahmen (links, oben, rechts, unten) in Rahmenkoordinaten: bei gedrehtem Freistellen ist das
     * ein um −[ToolOptions.cropAngle] um die Rahmenmitte gedrehtes Rechteck im Dokument.
     */
    private var crop: DoubleArray? = null
    private var cropStart: DoubleArray? = null
    /** Gezogener Teil: 0..7 = Anfasser (im Uhrzeigersinn ab oben links), 8 = verschieben, -1 = neu aufziehen. */
    private var cropPart = -1
    /** Perspektivisch freistellen: vier Ecken im Dokument (oben links, oben rechts, unten rechts, unten links). */
    private var cropQuad: DoubleArray? = null
    private var cropQuadStart: DoubleArray? = null
    /** Zugbeginn in Rahmenkoordinaten. */
    private var cropX0 = 0.0
    private var cropY0 = 0.0

    private fun startCrop() {
        val st = state ?: return
        crop = doubleArrayOf(0.0, 0.0, st.width.toDouble(), st.height.toDouble())
        cropQuad = null
        if (opts.cropAngle != 0f || opts.cropAspect != null) fitCropAspect()
        canvas.queueDraw()
    }

    /** Optionen des Freistellens geändert (aus [setOptions]). */
    private fun cropOptionsChanged(old: ToolOptions) {
        if (old.cropAngle != opts.cropAngle || old.cropAspect != opts.cropAspect) fitCropAspect()
        if (old.cropPerspective != opts.cropPerspective) {
            val c = crop
            cropQuad = if (opts.cropPerspective && c != null) doubleArrayOf(c[0], c[1], c[2], c[1], c[2], c[3], c[0], c[3]) else null
            if (opts.cropPerspective && opts.cropAngle != 0f) { setOptions { it.copy(cropAngle = 0f) }; return }
        }
        canvas.queueDraw()
    }

    private fun cropRatio(): Double? {
        val a = opts.cropAspect ?: return null
        val st = state ?: return null
        if (a.w == 0.0) return st.width.toDouble() / st.height
        val r = a.w / a.h
        return if (st.height > st.width) 1 / r else r
    }

    /** Größtes Rechteck mit dem gewählten Verhältnis in der (gedrehten) Arbeitsfläche, mittig. */
    fun fitCropAspect() {
        val st = state ?: return
        val (mw, mh) = de.photographics.core.editor.CropMath.maxInscribed(st.width.toDouble(), st.height.toDouble(), opts.cropAngle.toDouble())
        var w = mw; var h = mh
        cropRatio()?.let { r -> if (w / h > r) w = h * r else h = w / r }
        val cx = st.width / 2.0; val cy = st.height / 2.0
        crop = doubleArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        canvas.queueDraw()
    }

    /** Dokument → Rahmenkoordinaten (um die Mitte von [c] zurückgedreht) und umgekehrt. */
    private fun toFrame(c: DoubleArray, x: Double, y: Double, inverse: Boolean = false): Pair<Double, Double> {
        val a = Math.toRadians(opts.cropAngle.toDouble()) * (if (inverse) -1 else 1)
        if (a == 0.0) return x to y
        val cx = (c[0] + c[2]) / 2; val cy = (c[1] + c[3]) / 2
        val dx = x - cx; val dy = y - cy
        return (cx + dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)) to (cy + dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a))
    }
    private fun toDoc(c: DoubleArray, x: Double, y: Double) = toFrame(c, x, y, inverse = true)

    private fun cropHandles(c: DoubleArray): List<Pair<Double, Double>> {
        val mx = (c[0] + c[2]) / 2; val my = (c[1] + c[3]) / 2
        return listOf(c[0] to c[1], mx to c[1], c[2] to c[1], c[2] to my, c[2] to c[3], mx to c[3], c[0] to c[3], c[0] to my)
    }

    private fun cropPress(x: Double, y: Double) {
        val c = crop ?: run { startCrop(); crop!! }
        val tol = 10 / canvas.zoom
        if (opts.cropStraighten) { cropPart = -2; return }
        val q = cropQuad
        if (q != null) {
            cropQuadStart = q.copyOf()
            cropPart = (0..3).indexOfFirst { i -> abs(q[i * 2] - x) <= tol && abs(q[i * 2 + 1] - y) <= tol }
            if (cropPart < 0) cropPart = if (insidePoly(q, x, y)) 8 else -3
            return
        }
        cropStart = c.copyOf()
        val (fx, fy) = toFrame(c, x, y)
        cropX0 = fx; cropY0 = fy
        cropPart = cropHandles(c).indexOfFirst { (hx, hy) -> abs(hx - fx) <= tol && abs(hy - fy) <= tol }
        if (cropPart < 0 && fx in c[0]..c[2] && fy in c[1]..c[3]) cropPart = 8
    }

    private fun cropDrag(d: Drag, x: Double, y: Double, mods: Set<ModifierType>) {
        val st = state ?: return
        if (cropPart == -2 || cropPart == -3) return                 // Linie ziehen bzw. außerhalb des Vierecks
        val q0 = cropQuadStart
        if (cropQuad != null && q0 != null) {
            val q = q0.copyOf(); val dx = x - d.x0; val dy = y - d.y0
            if (cropPart == 8) for (i in 0..3) { q[i * 2] += dx; q[i * 2 + 1] += dy } else { q[cropPart * 2] += dx; q[cropPart * 2 + 1] += dy }
            if (de.photographics.core.image.Homography.isConvex(q)) cropQuad = q
            return
        }
        val s = cropStart ?: return
        val (fx, fy) = toFrame(s, x, y)
        val dx = fx - cropX0; val dy = fy - cropY0
        // Innerhalb der Arbeitsfläche halten (nur ungedreht; gedreht passt [fitCropAspect] den Rahmen ein)
        val keep = opts.cropDeleteCropped && opts.cropAngle == 0f
        fun clampX(v: Double) = if (keep) v.coerceIn(0.0, st.width.toDouble()) else v
        fun clampY(v: Double) = if (keep) v.coerceIn(0.0, st.height.toDouble()) else v
        val r = cropRatio() ?: if (ModifierType.SHIFT_MASK in mods) (s[2] - s[0]) / max(1e-6, s[3] - s[1]) else null
        val c = when (cropPart) {
            8 -> {
                var ddx = dx; var ddy = dy
                if (keep) { ddx = ddx.coerceIn(-s[0], st.width - s[2]); ddy = ddy.coerceIn(-s[1], st.height - s[3]) }
                doubleArrayOf(s[0] + ddx, s[1] + ddy, s[2] + ddx, s[3] + ddy)
            }
            -1 -> {
                var w = abs(fx - cropX0); var h = abs(fy - cropY0)
                if (r != null) { if (w / max(h, 1e-6) > r) w = h * r else h = w / r }
                val l = if (fx >= cropX0) cropX0 else cropX0 - w; val t = if (fy >= cropY0) cropY0 else cropY0 - h
                doubleArrayOf(clampX(l), clampY(t), clampX(l + w), clampY(t + h))
            }
            else -> {
                val c = s.copyOf()
                val p = cropPart
                if (p == 0 || p == 6 || p == 7) c[0] = clampX(min(s[0] + dx, s[2] - 1))
                if (p == 2 || p == 3 || p == 4) c[2] = clampX(max(s[2] + dx, s[0] + 1))
                if (p == 0 || p == 1 || p == 2) c[1] = clampY(min(s[1] + dy, s[3] - 1))
                if (p == 4 || p == 5 || p == 6) c[3] = clampY(max(s[3] + dy, s[1] + 1))
                if (r != null) {
                    // Verhältnis halten: die gezogene Kante bestimmt, die andere folgt
                    val w = c[2] - c[0]; val h = c[3] - c[1]
                    if (p == 1 || p == 5) { val nw = h * r; val mx = (s[0] + s[2]) / 2; c[0] = mx - nw / 2; c[2] = mx + nw / 2 }
                    else if (p == 3 || p == 7) { val nh = w / r; val my = (s[1] + s[3]) / 2; c[1] = my - nh / 2; c[3] = my + nh / 2 }
                    else {
                        val nh = w / r
                        if (p == 0 || p == 2) c[1] = c[3] - nh else c[3] = c[1] + nh
                    }
                }
                c
            }
        }
        // Gedreht: die neue Mitte liegt im Dokument dort, wohin die Rahmenmitte gewandert ist
        crop = if (opts.cropAngle != 0f) {
            val (ncx, ncy) = toDoc(s, (c[0] + c[2]) / 2, (c[1] + c[3]) / 2)
            val hw = (c[2] - c[0]) / 2; val hh = (c[3] - c[1]) / 2
            doubleArrayOf(ncx - hw, ncy - hh, ncx + hw, ncy + hh)
        } else c
    }

    /** Loslassen beim Freistellen: Gerade-ausrichten-Linie in einen Drehwinkel umsetzen. */
    private fun cropRelease(d: Drag, x: Double, y: Double) {
        if (cropPart != -2) return
        if (hypot(x - d.x0, y - d.y0) * canvas.zoom < 8) { setOptions { it.copy(cropStraighten = false) }; return }
        val deg = de.photographics.core.editor.CropMath.straightenAngle(d.x0, d.y0, x, y).toFloat()
        setOptions { it.copy(cropStraighten = false, cropAngle = (opts.cropAngle + deg).coerceIn(-45f, 45f)) }
    }

    fun applyCrop() {
        val c = crop ?: return
        val q = cropQuad
        val angle = opts.cropAngle.toDouble()
        val delete = opts.cropDeleteCropped
        crop = null; cropQuad = null
        when {
            q != null -> {
                fun len(i: Int, j: Int) = hypot(q[j * 2] - q[i * 2], q[j * 2 + 1] - q[i * 2 + 1])
                // Zielgröße: mittlere Kantenlängen
                val ow = ((len(0, 1) + len(3, 2)) / 2).roundToInt().coerceAtLeast(1)
                val oh = ((len(0, 3) + len(1, 2)) / 2).roundToInt().coerceAtLeast(1)
                edit(tr("Perspektivisch freistellen"), busy = true) { ed -> ed.perspectiveCrop(q, ow, oh); null }
            }
            angle != 0.0 -> {
                val w = (c[2] - c[0]).roundToInt(); val h = (c[3] - c[1]).roundToInt()
                if (w < 1 || h < 1) return
                edit(tr("Freistellen"), busy = true) { ed -> ed.cropRotated((c[0] + c[2]) / 2, (c[1] + c[3]) / 2, w, h, -angle); null }
            }
            else -> {
                val r = IntRect(c[0].roundToInt(), c[1].roundToInt(), c[2].roundToInt(), c[3].roundToInt())
                if (r.width < 1 || r.height < 1) return
                edit(tr("Freistellen"), busy = true) { ed -> ed.crop(r, delete); null }
            }
        }
        setOptions { it.copy(tool = Tool.MOVE, cropAngle = 0f, cropPerspective = false, cropStraighten = false) }
    }

    // ------------------------------------------------------------ Transformieren

    /** Grundrechteck [w]×[h] (vor der Transformation) und aktuelle Matrix. */
    private class TransformBox(val layerId: Int, val w: Double, val h: Double, var affine: Affine, val smart: Boolean) {
        /** Freie Ecken (Verzerren, oben links … unten links) oder null = Matrix. */
        var quad: DoubleArray? = null
        fun corners(): DoubleArray = quad?.copyOf() ?: doubleArrayOf(
            affine.mapX(0.0, 0.0), affine.mapY(0.0, 0.0), affine.mapX(w, 0.0), affine.mapY(w, 0.0),
            affine.mapX(w, h), affine.mapY(w, h), affine.mapX(0.0, h), affine.mapY(0.0, h),
        )
    }

    private var transform: TransformBox? = null
    private var transformBefore: Affine? = null
    private var transformBeforeGeom: Editor.Geometry? = null
    private var tStart: Affine? = null
    /** 0..7 = Anfasser (Ecken und Kanten, im Uhrzeigersinn ab oben links), 8 = verschieben, 9 = drehen. */
    private var tPart = 8

    private fun beginTransform() {
        val s = session ?: return
        Engine.call({
            val ed = s.editor
            var l = ed.doc.activeLayer
            val converted = false
            // Pixelebene: die Auswahl (ohne Auswahl die ganze Ebene) schwebend transformieren –
            // die Ebene bleibt eine Pixelebene, die Auswahl folgt (wie Photoshop)
            if (l is PixelLayer) {
                val f = ed.floatSelection() ?: run { message(tr("Die Ebene ist leer.")); return@call null }
                eFloat = f
                l = f.layer
                repaint(f.bounds)
            }
            val box = when (l) {
                is SmartLayer -> TransformBox(l.id, l.source.width.toDouble(), l.source.height.toDouble(), l.transform, true).also { b -> b.quad = l.warp?.copyOf() }
                is TextLayer -> PangoText.measure(l.style).let { (w, h) -> TransformBox(l.id, w.toDouble(), h.toDouble(), l.transform, false) }
                is de.photographics.core.doc.ShapeLayer -> l.boxSize().let { (w, h) -> TransformBox(l.id, w, h, l.boxTransform, false) }
                else -> { message(tr("Diese Ebene kann nicht transformiert werden.")); return@call null }
            }
            transformBeforeGeom = ed.geometryOf(l)
            if (converted) s.dirty = true
            Triple(box, converted, s.state())
        }) { r ->
            val (box, converted, st) = r.getOrNull() ?: run { if (opts.tool == Tool.TRANSFORM) setOptions { it.copy(tool = Tool.MOVE) }; return@call }
            transform = box; transformBefore = box.affine
            if (converted) ui.toast(tr("Ebene in Smartobjekt umgewandelt – Transformieren ohne Qualitätsverlust."))
            publish(st, IntRect.EMPTY)
            canvas.queueDraw()
        }
    }

    private fun transformHandles(b: TransformBox): List<Pair<Double, Double>> {
        val c = b.corners()
        fun mid(i: Int, j: Int) = (c[i * 2] + c[j * 2]) / 2 to (c[i * 2 + 1] + c[j * 2 + 1]) / 2
        return listOf(c[0] to c[1], mid(0, 1), c[2] to c[3], mid(1, 2), c[4] to c[5], mid(2, 3), c[6] to c[7], mid(3, 0))
    }

    private fun transformHit(x: Double, y: Double): Int {
        val b = transform ?: return -1
        val tol = 10 / canvas.zoom
        val h = transformHandles(b).indexOfFirst { (hx, hy) -> abs(hx - x) <= tol && abs(hy - y) <= tol }
        if (h >= 0) return h
        val q = b.quad
        if (q != null) return if (insidePoly(q, x, y)) 8 else 9
        val inv = b.affine.inverse()
        val lx = inv.mapX(x, y); val ly = inv.mapY(x, y)
        return if (lx in 0.0..b.w && ly in 0.0..b.h) 8 else 9
    }

    private fun transformCursor(): String {
        if (!canvas.pointerInside || transform == null) return "default"
        return when (transformHit(canvas.pointerDocX, canvas.pointerDocY)) {
            0, 4 -> "nwse-resize"; 2, 6 -> "nesw-resize"; 1, 5 -> "ns-resize"; 3, 7 -> "ew-resize"; 8 -> "move"; else -> "crosshair"
        }
    }

    private var tStartQuad: DoubleArray? = null

    private fun transformPress(x: Double, y: Double, mods: Set<ModifierType>): Boolean {
        val b = transform ?: return false
        tPart = transformHit(x, y)
        tStart = b.affine
        // Strg+Ecke (Photoshop: Verzerren) oder bereits verzerrt: mit freien Ecken weiterarbeiten
        val distort = ModifierType.CONTROL_MASK in mods && tPart in 0..7 && tPart % 2 == 0
        if (distort && !b.smart) { ui.toast(tr("Text lässt sich nur frei transformieren – für Verzerren zuerst in ein Smartobjekt umwandeln.")); return true }
        if (distort && b.quad == null) b.quad = b.corners()
        tStartQuad = b.quad?.copyOf()
        return true
    }

    private fun insidePoly(q: DoubleArray, x: Double, y: Double): Boolean {
        var inside = false; var j = 3
        for (i in 0..3) {
            val xi = q[i * 2]; val yi = q[i * 2 + 1]; val xj = q[j * 2]; val yj = q[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun transformDrag(d: Drag, x: Double, y: Double, mods: Set<ModifierType>) {
        val b = transform ?: return
        val s0 = tStart ?: return
        val q0 = tStartQuad
        if (q0 != null) {
            // Verzerren: Ecke frei ziehen, Kante/innen verschieben (alle Ecken), außen nicht drehen
            val q = q0.copyOf()
            val dx = x - d.x0; val dy = y - d.y0
            when (tPart) {
                0, 2, 4, 6 -> { val i = tPart / 2; q[i * 2] += dx; q[i * 2 + 1] += dy }
                1, 3, 5, 7 -> { val i = tPart / 2; val j = (i + 1) % 4; q[i * 2] += dx; q[i * 2 + 1] += dy; q[j * 2] += dx; q[j * 2 + 1] += dy }
                else -> for (i in 0..3) { q[i * 2] += dx; q[i * 2 + 1] += dy }
            }
            if (de.photographics.core.image.Homography.isConvex(q)) { b.quad = q; previewWarp(b) }
            return
        }
        b.affine = when (tPart) {
            8 -> s0.then(Affine.translate(x - d.x0, y - d.y0))
            9 -> {
                val cx = s0.mapX(b.w / 2, b.h / 2); val cy = s0.mapY(b.w / 2, b.h / 2)
                var deg = Math.toDegrees(atan2(y - cy, x - cx) - atan2(d.y0 - cy, d.x0 - cx))
                if (ModifierType.SHIFT_MASK in mods) deg = Math.round(deg / 15.0) * 15.0
                s0.then(Affine.around(cx, cy, Affine.rotate(deg)))
            }
            else -> {
                // Skalieren in Ebenenkoordinaten: Gegenpunkt bleibt stehen
                val inv = s0.inverse()
                val lx = inv.mapX(x, y); val ly = inv.mapY(x, y)
                val p = tPart
                val ax = when (p) { 0, 6, 7 -> b.w; 2, 3, 4 -> 0.0; else -> b.w / 2 }
                val ay = when (p) { 0, 1, 2 -> b.h; 4, 5, 6 -> 0.0; else -> b.h / 2 }
                val hx = when (p) { 0, 6, 7 -> 0.0; 2, 3, 4 -> b.w; else -> b.w / 2 }
                val hy = when (p) { 0, 1, 2 -> 0.0; 4, 5, 6 -> b.h; else -> b.h / 2 }
                var sx = if (p == 1 || p == 5) 1.0 else (lx - ax) / (hx - ax)
                var sy = if (p == 3 || p == 7) 1.0 else (ly - ay) / (hy - ay)
                // Ecken skalieren proportional (Umschalt: frei), wie Photoshop
                if (p % 2 == 0 && ModifierType.SHIFT_MASK !in mods) { val k = (abs(sx) + abs(sy)) / 2; sx = k * Math.signum(sx).let { if (it == 0.0) 1.0 else it }; sy = k * Math.signum(sy).let { if (it == 0.0) 1.0 else it } }
                if (abs(sx) < 1e-3) sx = 1e-3; if (abs(sy) < 1e-3) sy = 1e-3
                Affine.translate(-ax, -ay).then(Affine.scale(sx, sy)).then(Affine.translate(ax, ay)).then(s0)
            }
        }
        previewTransform(b)
    }

    private var tPreviewQueued = false

    private fun previewTransform(b: TransformBox) {
        val s = session ?: return
        if (tPreviewQueued) return
        tPreviewQueued = true
        val v = canvas.liveView()
        Engine.post {
            tPreviewQueued = false
            val t = b.affine
            val l = s.doc.findLayer(b.layerId) ?: return@post
            fun outline(m: Affine) = m.bounds(b.w, b.h).let { r ->
                IntRect(kotlin.math.floor(r[0]).toInt(), kotlin.math.floor(r[1]).toInt(), kotlin.math.ceil(r[2]).toInt(), kotlin.math.ceil(r[3]).toInt())
            }
            val old = outline(s.editor.transformOf(l))
            s.editor.previewTransform(l, t)
            liveLayer(l, old.union(outline(t)).inset(-2), v)
        }
    }

    private fun transformRelease() { canvas.updateCursor() }

    private fun previewWarp(b: TransformBox) {
        val s = session ?: return
        if (tPreviewQueued) return
        tPreviewQueued = true
        val v = canvas.liveView()
        Engine.post {
            tPreviewQueued = false
            val q = b.quad ?: return@post
            val l = s.doc.findLayer(b.layerId) as? SmartLayer ?: return@post
            fun bounds(c: DoubleArray) = IntRect(
                kotlin.math.floor(minOf(c[0], c[2], c[4], c[6])).toInt(), kotlin.math.floor(minOf(c[1], c[3], c[5], c[7])).toInt(),
                kotlin.math.ceil(maxOf(c[0], c[2], c[4], c[6])).toInt(), kotlin.math.ceil(maxOf(c[1], c[3], c[5], c[7])).toInt(),
            )
            val old = bounds(l.corners())
            s.editor.previewWarp(l, q)
            liveLayer(l, old.union(bounds(q)).inset(-2), v)
        }
    }

    fun applyTransform() {
        val b = transform ?: return
        transform = null
        val before = transformBefore; val beforeGeom = transformBeforeGeom
        edit(tr("Transformieren"), busy = true) { ed ->
            eFloat?.takeIf { it.layer.id == b.layerId }?.let { f -> eFloat = null; ed.commitFloating(f, tr("Frei transformieren")); return@edit null }
            val l = ed.doc.findLayer(b.layerId)
            if (l is SmartLayer && beforeGeom != null) ed.commitGeometry(l, beforeGeom, if (b.quad != null) Editor.Geometry(beforeGeom.transform, b.quad) else Editor.Geometry(b.affine, null))
            else if (l != null && before != null) ed.commitTransform(l, before, b.affine)
            null
        }
        if (opts.tool == Tool.TRANSFORM) setOptions { it.copy(tool = Tool.MOVE) }
    }

    fun cancelTransform() {
        val b = transform ?: return
        transform = null
        val before = transformBefore; val beforeGeom = transformBeforeGeom
        query { ed ->
            eFloat?.takeIf { it.layer.id == b.layerId }?.let { f -> eFloat = null; ed.cancelFloating(f); return@query null }
            val l = ed.doc.findLayer(b.layerId)
            if (l is SmartLayer && beforeGeom != null) ed.commitGeometry(l, beforeGeom, beforeGeom)
            else if (l != null && before != null) ed.commitTransform(l, before, before)
            null
        }
        if (opts.tool == Tool.TRANSFORM) setOptions { it.copy(tool = Tool.MOVE) }
    }

    val transforming get() = transform != null

    // ------------------------------------------------------------ Text

    private var eTextBefore: TextStyle? = null
    private var lastTextStyle = TextStyle(sizePx = 96f)
    private var textSizeChosen = false
    var editingText: Int? = null
        private set

    private fun textTap(x: Double, y: Double) {
        val s = session ?: return
        if (editingText != null) { ui.toast(tr("Text zuerst mit „Fertig“ abschließen.")); return }
        val color = opts.color
        Engine.call({
            val ed = s.editor
            val hit = ed.doc.allLayers().asReversed().firstOrNull { l ->
                l is TextLayer && l.visible && l.bounds().inset(-8).contains(x.toInt(), y.toInt())
            } as? TextLayer
            val layer = hit ?: run {
                val base = if (textSizeChosen) lastTextStyle else lastTextStyle.copy(sizePx = (min(ed.doc.width, ed.doc.height) / 12f).coerceIn(24f, 600f))
                ed.addTextLayer(base.copy(color = color, text = tr("Text")), x, y)
            }
            ed.setActive(layer)
            eTextBefore = layer.style
            s.dirty = true
            Triple(layer.id, layer.style, s.state())
        }) { r ->
            val (id, style, st) = r.getOrNull() ?: return@call
            editingText = id
            publish(st, null)
            ui.editText(id, style)
        }
    }

    /** Textebene [id] bearbeiten (Doppelklick in der Ebenenliste). */
    fun editTextLayer(id: Int) {
        val s = session ?: return
        Engine.call({
            val l = s.doc.findLayer(id) as? TextLayer ?: return@call null
            s.editor.setActive(l)
            eTextBefore = l.style
            l.style
        }) { r ->
            val style = r.getOrNull() ?: return@call
            editingText = id
            ui.editText(id, style)
        }
    }

    fun updateTextLive(style: TextStyle) {
        val s = session ?: return
        val id = editingText ?: return
        Engine.post {
            val l = s.doc.findLayer(id) as? TextLayer ?: return@post
            val old = l.bounds()
            l.style = style
            l.unchangedFromPsd = false
            repaint(old.union(l.bounds()).inset(-4))
        }
    }

    fun finishText(commit: Boolean) {
        val id = editingText ?: return
        editingText = null
        edit(tr("Text")) { ed ->
            val l = ed.doc.findLayer(id) as? TextLayer ?: return@edit null
            val before = eTextBefore ?: return@edit null
            eTextBefore = null
            if (commit) {
                val after = l.style
                l.style = before
                ed.updateText(l, after)
                if (after.sizePx != before.sizePx) textSizeChosen = true
                lastTextStyle = after
            } else l.style = before
            null
        }
    }

    // ------------------------------------------------------------ Filter und Korrekturen

    private var eFilterTx: de.photographics.core.history.TileTransaction? = null
    private var eFilterRegion = IntRect.EMPTY
    private var filterWanted: de.photographics.core.filters.ImageFilter? = null
    private var filterRunning = false

    /** Prüft, ob die aktive Ebene gefiltert werden kann; dann [ok] im Hauptthread. */
    fun filterStart(ok: () -> Unit) {
        val s = session ?: return
        Engine.call({ s.doc.activeLayer is PixelLayer }) { r ->
            if (r.getOrNull() == true) ok()
            else ui.toast(tr("Filter benötigen eine Pixelebene (Text/Smartobjekt ggf. rastern oder eine Einstellungsebene verwenden)."))
        }
    }

    /** Vorschau; schnelle Folgen von Änderungen werden zusammengefasst (nur die letzte zählt). */
    fun filterPreview(f: de.photographics.core.filters.ImageFilter) {
        filterWanted = f
        if (!filterRunning) runFilterPreview()
    }

    private fun runFilterPreview() {
        val s = session ?: return
        val f = filterWanted ?: return
        filterWanted = null
        filterRunning = true
        ui.busy(true)
        Engine.call({
            eFilterTx?.rollback()
            val res = s.editor.previewFilter(f)
            val dirty = eFilterRegion.union(res?.second ?: IntRect.EMPTY)
            eFilterTx = res?.first
            eFilterRegion = res?.second ?: IntRect.EMPTY
            dirty
        }) { r ->
            filterRunning = false
            ui.busy(false)
            r.getOrNull()?.let { rect -> state?.let { canvas.invalidate(rect, it, live = true) } }
            if (filterWanted != null) runFilterPreview()
        }
    }

    fun filterFinish(apply: Boolean, label: String) {
        filterWanted = null
        edit(label) { ed ->
            val tx = eFilterTx
            val region = eFilterRegion
            eFilterTx = null; eFilterRegion = IntRect.EMPTY
            if (tx != null) { if (apply) ed.commitFilter(tx, label) else tx.rollback() }
            region
        }
    }

    private var eAdjustBefore: de.photographics.core.adjust.Adjustment? = null
    private var adjustWanted: Pair<Int, de.photographics.core.adjust.Adjustment>? = null
    private var adjustRunning = false

    /** Neue Einstellungsebene über der aktiven Ebene; danach öffnet das Fenster ihre Regler. */
    fun addAdjustment(type: de.photographics.core.adjust.AdjustmentType, open: (Int, de.photographics.core.adjust.Adjustment, IntArray?) -> Unit) {
        val s = session ?: return
        Engine.call({
            val l = s.editor.addAdjustmentLayer(type.createDefault())
            eAdjustBefore = l.adjustment
            s.dirty = true
            Triple(l, histogramFor(s, l), s.state())
        }) { r ->
            val (l, h, st) = r.getOrNull() ?: return@call
            publish(st, null)
            open(l.id, l.adjustment, h)
        }
    }

    fun openAdjustment(id: Int, open: (Int, de.photographics.core.adjust.Adjustment, IntArray?) -> Unit) {
        val s = session ?: return
        Engine.call({
            val l = s.doc.findLayer(id) as? de.photographics.core.doc.AdjustmentLayer ?: return@call null
            s.editor.setActive(l)
            eAdjustBefore = l.adjustment
            Triple(l.adjustment, histogramFor(s, l), s.state())
        }) { r ->
            val (a, h, st) = r.getOrNull() ?: return@call
            publish(st, IntRect.EMPTY)
            open(id, a, h)
        }
    }

    private fun histogramFor(s: Session, l: de.photographics.core.doc.AdjustmentLayer): IntArray? =
        if (l.adjustment is de.photographics.core.adjust.CurvesAdj || l.adjustment is de.photographics.core.adjust.LevelsAdj)
            de.photographics.core.adjust.Histogram.below(s.doc, l) else null

    // ------------------------------------------------------------ Ebenenstil

    /** Engine-Thread: Blöcke der Ebene vor dem Öffnen des Dialogs (für Abbrechen und den Verlauf). */
    private var eFxBefore: List<de.photographics.core.psd.PsdTaggedBlock>? = null
    private var fxWanted: Pair<Int, de.photographics.core.doc.LayerEffects>? = null
    private var fxRunning = false

    /** Ebenenstil von [id] bearbeiten: [open] erhält die aktuellen Effekte (leer, wenn keine). */
    fun startEffects(id: Int, open: (de.photographics.core.doc.LayerEffects) -> Unit) {
        val s = session ?: return
        Engine.call({
            gpuEnd()
            val l = s.doc.findLayer(id) ?: return@call null
            eFxBefore = l.preservedBlocks
            l.effects ?: de.photographics.core.doc.LayerEffects()
        }) { r -> r.getOrNull()?.let(open) }
    }

    fun previewEffects(id: Int, fx: de.photographics.core.doc.LayerEffects) {
        fxWanted = id to fx
        if (!fxRunning) runFxPreview()
    }

    private fun runFxPreview() {
        val s = session ?: return
        val (id, fx) = fxWanted ?: return
        fxWanted = null
        fxRunning = true
        Engine.call({
            s.doc.findLayer(id)?.let { s.editor.previewEffects(it, fx) }
            null
        }) { _ ->
            fxRunning = false
            state?.let { canvas.invalidate(null, it, live = true) }
            if (fxWanted != null) runFxPreview()
        }
    }

    fun finishEffects(id: Int, commit: Boolean) {
        fxWanted = null
        edit(tr("Ebenenstil")) { ed ->
            val l = ed.doc.findLayer(id) ?: return@edit null
            val before = eFxBefore ?: return@edit null
            eFxBefore = null
            if (commit) ed.commitEffects(l, before) else l.preservedBlocks = before
            null
        }
    }

    fun previewAdjustment(id: Int, a: de.photographics.core.adjust.Adjustment) {
        adjustWanted = id to a
        if (!adjustRunning) runAdjustPreview()
    }

    private fun runAdjustPreview() {
        val s = session ?: return
        val (id, a) = adjustWanted ?: return
        adjustWanted = null
        adjustRunning = true
        Engine.call({
            val l = s.doc.findLayer(id) as? de.photographics.core.doc.AdjustmentLayer ?: return@call null
            s.editor.previewAdjustment(l, a)
            null
        }) { _ ->
            adjustRunning = false
            state?.let { canvas.invalidate(null, it, live = true) }
            if (adjustWanted != null) runAdjustPreview()
        }
    }

    fun finishAdjustment(id: Int, commit: Boolean) {
        adjustWanted = null
        edit(tr("Einstellungsebene")) { ed ->
            val l = ed.doc.findLayer(id) as? de.photographics.core.doc.AdjustmentLayer ?: return@edit null
            val before = eAdjustBefore ?: return@edit null
            eAdjustBefore = null
            if (commit) ed.commitAdjustment(l, before) else ed.previewAdjustment(l, before)
            null
        }
    }

    // ------------------------------------------------------------ Überlagerungen

    var antsPhase = 0f

    fun drawOverlay(sn: Snapshot) {
        val st = state ?: return
        val z = canvas.zoom
        fun vx(x: Double) = canvas.viewX(x)
        fun vy(y: Double) = canvas.viewY(y)

        // Hilfslinien (Cyan wie in Photoshop), beim Ziehen an der neuen Stelle
        val W = canvas.width.toFloat(); val H = canvas.height.toFloat()
        val cyan = org.gnome.gdk.RGBA(0f, 0.8f, 1f, 0.9f)
        fun guideLine(vertical: Boolean, pos: Double, color: org.gnome.gdk.RGBA) {
            if (vertical) sn.appendColor(color, Draw.rect(vx(pos) - 0.5f, 0f, 1f, H)) else sn.appendColor(color, Draw.rect(0f, vy(pos) - 0.5f, W, 1f))
        }
        if (Settings.showGuides) for ((i, g) in st.guides.withIndex()) guideLine(g.vertical, if (i == guideDrag && drag?.kind == Drag.Kind.GUIDE) guidePos else g.position, cyan)
        pendingGuide?.let { (v, p) -> guideLine(v, p, cyan) }
        val magenta = org.gnome.gdk.RGBA(1f, 0.1f, 0.8f, 1f)
        for (l in smartLines) {
            if (l.vertical) sn.appendColor(magenta, Draw.rect(vx(l.pos) - 0.5f, vy(l.from), 1f, vy(l.to) - vy(l.from)))
            else sn.appendColor(magenta, Draw.rect(vx(l.from), vy(l.pos) - 0.5f, vx(l.to) - vx(l.from), 1f))
        }

        // Auswahl
        // Beim Verschieben des Auswahlrahmens folgen die Ameisen dem Zeiger
        val md = drag?.takeIf { it.kind == Drag.Kind.MOVE_SELECTION }
        val sdx = md?.let { (it.x - it.x0).roundToInt() } ?: 0; val sdy = md?.let { (it.y - it.y0).roundToInt() } ?: 0
        for (poly in st.selectionOutlines) {
            val v = FloatArray(poly.size) { i -> if (i % 2 == 0) vx(poly[i].toDouble() + sdx) else vy(poly[i].toDouble() + sdy) }
            Draw.path(v, true)?.let { Draw.ants(sn, it, antsPhase) }
        }

        drawPaths(sn, st)

        // Polygon-Lasso: gesetzte Kanten, Gummiband zum Zeiger, Startpunkt (Klick dort schließt)
        poly?.let { p ->
            val (px, py) = if (canvas.pointerInside) polyConstrain(canvas.pointerDocX, canvas.pointerDocY) else p[p.size - 2].toDouble() to p[p.size - 1].toDouble()
            val v = FloatArray(p.size + 2) { i -> when {
                i < p.size -> if (i % 2 == 0) vx(p[i].toDouble()) else vy(p[i].toDouble())
                i % 2 == 0 -> vx(px)
                else -> vy(py)
            } }
            Draw.path(v, false)?.let { Draw.ants(sn, it) }
            val closeHit = p.size >= 6 && hypot(px - p[0], py - p[1]) * canvas.zoom <= 8
            Draw.handle(sn, vx(p[0].toDouble()), vy(p[1].toDouble()), if (closeHit) 8f else 5f)
        }

        // Laufender Zug
        drag?.let { d ->
            when (d.kind) {
                Drag.Kind.RECT, Drag.Kind.OBJECT -> {
                    val c = constrained(d, d.x, d.y)
                    Draw.ants(sn, Draw.rectPath(vx(c[0].toDouble()), vy(c[1].toDouble()), vx(c[2].toDouble()), vy(c[3].toDouble())))
                }
                Drag.Kind.ELLIPSE -> {
                    val c = constrained(d, d.x, d.y)
                    val cx = (c[0] + c[2]) / 2.0; val cy = (c[1] + c[3]) / 2.0; val rx = (c[2] - c[0]) / 2.0; val ry = (c[3] - c[1]) / 2.0
                    val n = 72
                    val pts = FloatArray(n * 2) { i -> val a = (i / 2) * 2 * Math.PI / n; if (i % 2 == 0) vx(cx + rx * kotlin.math.cos(a)) else vy(cy + ry * kotlin.math.sin(a)) }
                    Draw.path(pts, true)?.let { Draw.ants(sn, it) }
                }
                Drag.Kind.LASSO -> {
                    val p = d.points
                    val v = FloatArray(p.size) { i -> if (i % 2 == 0) vx(p[i].toDouble()) else vy(p[i].toDouble()) }
                    Draw.path(v, false)?.let { Draw.ants(sn, it) }
                }
                Drag.Kind.ZOOM -> if (hypot(d.x - d.x0, d.y - d.y0) * z > 3)
                    Draw.ants(sn, Draw.rectPath(vx(min(d.x0, d.x)), vy(min(d.y0, d.y)), vx(max(d.x0, d.x)), vy(max(d.y0, d.y))))
                Drag.Kind.QUICK -> {
                    val p = d.points
                    val v = FloatArray(p.size) { i -> if (i % 2 == 0) vx(p[i].toDouble()) else vy(p[i].toDouble()) }
                    if (v.size == 2) Draw.circle(sn, v[0], v[1], (opts.brush.size / 2 * z).toFloat())
                    else Draw.path(v, false)?.let {
                        val st2 = org.gnome.gsk.Stroke((opts.brush.size * z).toFloat().coerceAtLeast(1f)).apply { lineCap = org.gnome.gsk.LineCap.ROUND; lineJoin = org.gnome.gsk.LineJoin.ROUND }
                        sn.appendStroke(it, st2, org.gnome.gdk.RGBA(0.25f, 0.55f, 0.95f, 0.35f))
                    }
                }
                Drag.Kind.GRADIENT -> {
                    Draw.path(floatArrayOf(vx(d.x0), vy(d.y0), vx(d.x), vy(d.y)), false)?.let { Draw.line(sn, it) }
                    Draw.handle(sn, vx(d.x0), vy(d.y0), 7f); Draw.handle(sn, vx(d.x), vy(d.y), 7f)
                }
                // Bereichsreparatur: das Bild ändert sich erst beim Absetzen – bis dahin die Pinselspur zeigen
                Drag.Kind.STROKE -> if (opts.tool == Tool.SPOT_HEAL || opts.tool == Tool.AI_REMOVE) {
                    val p = d.points
                    val v = FloatArray(p.size) { i -> if (i % 2 == 0) vx(p[i].toDouble()) else vy(p[i].toDouble()) }
                    if (v.size == 2) Draw.circle(sn, v[0], v[1], (opts.brush.size / 2 * z).toFloat())
                    else Draw.path(v, false)?.let {
                        val st2 = org.gnome.gsk.Stroke((opts.brush.size * z).toFloat().coerceAtLeast(1f)).apply { lineCap = org.gnome.gsk.LineCap.ROUND; lineJoin = org.gnome.gsk.LineJoin.ROUND }
                        sn.appendStroke(it, st2, org.gnome.gdk.RGBA(0.9f, 0.25f, 0.3f, 0.45f))
                    }
                }
                Drag.Kind.PATCH -> for (poly in st.selectionOutlines) {
                    val dx = d.x - d.x0; val dy = d.y - d.y0
                    val v = FloatArray(poly.size) { i -> if (i % 2 == 0) vx(poly[i] + dx) else vy(poly[i] + dy) }
                    Draw.path(v, true)?.let { Draw.line(sn, it) }
                }
                else -> {}
            }
        }

        // Linealwerkzeug: gemessene Linie
        if (opts.tool == Tool.RULER) opts.rulerLine?.let { l ->
            Draw.path(floatArrayOf(vx(l[0]), vy(l[1]), vx(l[2]), vy(l[3])), false)?.let { Draw.line(sn, it) }
            Draw.handle(sn, vx(l[0]), vy(l[1]), 6f); Draw.handle(sn, vx(l[2]), vy(l[3]), 6f)
        }

        // Freistellungsrahmen: außen abdunkeln, Drittel, Anfasser (gedreht bzw. als freies Viereck)
        if (opts.tool == Tool.CROP) crop?.let { c ->
            val W = canvas.width.toFloat(); val H = canvas.height.toFloat()
            val q = cropQuad
            val corners: DoubleArray = q ?: run {
                val pts = listOf(c[0] to c[1], c[2] to c[1], c[2] to c[3], c[0] to c[3]).map { (fx, fy) -> toDoc(c, fx, fy) }
                DoubleArray(8) { i -> if (i % 2 == 0) pts[i / 2].first else pts[i / 2].second }
            }
            val v = FloatArray(8) { i -> if (i % 2 == 0) vx(corners[i]) else vy(corners[i]) }
            val shade = org.gnome.gsk.PathBuilder()
            shade.addRect(Draw.rect(0f, 0f, W, H))
            shade.moveTo(v[0], v[1]); shade.lineTo(v[2], v[3]); shade.lineTo(v[4], v[5]); shade.lineTo(v[6], v[7]); shade.close()
            sn.appendFill(shade.toPath(), org.gnome.gsk.FillRule.EVEN_ODD, Draw.SHADE)
            Draw.path(v, true)?.let { Draw.line(sn, it) }
            // Drittel (im Viereck bilinear verteilt)
            fun lerp(a: Int, b: Int, t: Float) = floatArrayOf(v[a * 2] + (v[b * 2] - v[a * 2]) * t, v[a * 2 + 1] + (v[b * 2 + 1] - v[a * 2 + 1]) * t)
            val thin = org.gnome.gdk.RGBA(1f, 1f, 1f, 0.5f)
            for (k in 1..2) {
                val t = k / 3f
                val top = lerp(0, 1, t); val bot = lerp(3, 2, t); val left = lerp(0, 3, t); val right = lerp(1, 2, t)
                Draw.path(floatArrayOf(top[0], top[1], bot[0], bot[1]), false)?.let { sn.appendStroke(it, org.gnome.gsk.Stroke(1f), thin) }
                Draw.path(floatArrayOf(left[0], left[1], right[0], right[1]), false)?.let { sn.appendStroke(it, org.gnome.gsk.Stroke(1f), thin) }
            }
            if (q != null) for (i in 0..3) Draw.handle(sn, v[i * 2], v[i * 2 + 1])
            else for ((hx, hy) in cropHandles(c)) { val (dx, dy) = toDoc(c, hx, hy); Draw.handle(sn, vx(dx), vy(dy)) }
            // Gerade ausrichten: gezogene Linie
            val dd = drag
            if (dd != null && dd.kind == Drag.Kind.CROP && cropPart == -2)
                Draw.path(floatArrayOf(vx(dd.x0), vy(dd.y0), vx(dd.x), vy(dd.y)), false)?.let { Draw.line(sn, it, 2f) }
        }

        // Transformieren
        transform?.let { b ->
            val c = b.corners()
            val v = FloatArray(8) { i -> if (i % 2 == 0) vx(c[i]) else vy(c[i]) }
            Draw.path(v, true)?.let { Draw.line(sn, it) }
            for ((hx, hy) in transformHandles(b)) Draw.handle(sn, vx(hx), vy(hy))
        }

        // Kopierquelle
        if (opts.tool == Tool.CLONE || opts.tool == Tool.HEAL) cloneSource?.let { (sx, sy) ->
            val x = vx(sx); val y = vy(sy)
            Draw.path(floatArrayOf(x - 6, y, x + 6, y), false)?.let { Draw.line(sn, it) }
            Draw.path(floatArrayOf(x, y - 6, x, y + 6), false)?.let { Draw.line(sn, it) }
        }

        // Pinsel einstellen: Kreis am Ausgangspunkt, weicher Rand rötlich wie in Photoshop
        adjust?.let { (_, _, c) ->
            val r = (opts.brush.size / 2 * z).toFloat().coerceAtLeast(1f)
            val cx = vx(c.first); val cy = vy(c.second)
            val pb = org.gnome.gsk.PathBuilder(); pb.addCircle(org.gnome.graphene.Point().init(cx, cy), r)
            sn.appendFill(pb.toPath(), org.gnome.gsk.FillRule.WINDING, org.gnome.gdk.RGBA(0.9f, 0.15f, 0.15f, 0.35f))
            val hr = r * opts.brush.hardness
            if (hr > 0.5f) {
                val hb = org.gnome.gsk.PathBuilder(); hb.addCircle(org.gnome.graphene.Point().init(cx, cy), hr)
                sn.appendFill(hb.toPath(), org.gnome.gsk.FillRule.WINDING, org.gnome.gdk.RGBA(0.9f, 0.15f, 0.15f, 0.45f))
            }
            Draw.circle(sn, cx, cy, r)
            Draw.label(sn, cx, cy + r + 18, canvas.createPangoLayout(tr("Ø {0} px · Härte {1} %", opts.brush.size.roundToInt(), (opts.brush.hardness * 100).roundToInt())))
            return
        }

        // Pinselumriss
        if ((opts.tool.isPaintTool) && canvas.pointerInside && !canvas.panMode) {
            val r = (opts.brush.size / 2 * z).toFloat()
            val px = vx(canvas.pointerDocX); val py = vy(canvas.pointerDocY)
            Draw.circle(sn, px, py, max(r, 1f))
            if (r < 4f) {
                Draw.path(floatArrayOf(px - 5, py, px + 5, py), false)?.let { Draw.line(sn, it) }
                Draw.path(floatArrayOf(px, py - 5, px, py + 5), false)?.let { Draw.line(sn, it) }
            }
        }
    }
}
