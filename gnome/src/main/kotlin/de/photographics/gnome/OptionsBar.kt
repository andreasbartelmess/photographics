package de.photographics.gnome

import de.photographics.core.editor.GradientType
import de.photographics.core.selection.SelectionOp
import de.photographics.core.selection.SelectionShape
import de.photographics.core.tools.BrushMode
import de.photographics.core.tools.BrushPresets
import de.photographics.core.tools.LiquifyMode
import de.photographics.core.tools.PaintTarget
import de.photographics.core.tools.ToneRange
import de.photographics.core.tr
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.CheckButton
import org.gnome.gtk.DropDown
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.Separator
import org.gnome.gtk.SpinButton
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.Widget

/**
 * Werkzeugoptionen unter der Kopfleiste (wie Photoshops Optionsleiste). Wird bei jedem
 * Werkzeugwechsel neu aufgebaut; Änderungen gehen über [change] an den Controller.
 */
class OptionsBar(
    private val change: ((ToolOptions) -> ToolOptions) -> Unit,
    private val onApply: () -> Unit,
    private val onCancel: () -> Unit,
) {
    val widget = Box(Orientation.HORIZONTAL, 8)
    private var builtFor: Tool? = null
    private var builtMask: Boolean? = null
    private var updating = false
    private val updaters = ArrayList<(ToolOptions) -> Unit>()

    init {
        widget.addCssClass("toolbar")
        widget.marginStart = 12; widget.marginEnd = 12
        widget.addCssClass("options-bar")
    }

    /** Beim nächsten [update] neu aufbauen (z. B. nach dem Speichern einer Pinselvorgabe). */
    fun rebuild() { builtFor = null }

    /** [hasMask]: aktive Ebene hat eine Maske (dann gibt es die Wahl Pixel/Maske). */
    fun update(o: ToolOptions, hasMask: Boolean) {
        if (builtFor != o.tool || builtMask != hasMask) build(o, hasMask)
        updating = true
        for (u in updaters) u(o)
        updating = false
    }

    private fun clear() {
        var c = widget.firstChild
        while (c != null) { val n = c.nextSibling; widget.remove(c); c = n }
        updaters.clear()
    }

    private fun add(w: Widget) = widget.append(w)
    private fun sep() = add(Separator(Orientation.VERTICAL))
    private fun label(t: String) = add(Label(t).apply { addCssClass("dim-label") })

    private fun spin(text: String, min: Double, max: Double, step: Double, digits: Int = 0, get: (ToolOptions) -> Double, set: (ToolOptions, Double) -> ToolOptions) {
        label(text)
        val s = SpinButton.withRange(min, max, step)
        s.digits = digits
        s.widthChars = 4
        s.valign = Align.CENTER
        s.onValueChanged { if (!updating) { val v = s.value; change { set(it, v) } } }
        updaters += { o -> if (s.value != get(o)) s.value = get(o) }
        add(s)
    }

    private fun check(text: String, get: (ToolOptions) -> Boolean, set: (ToolOptions, Boolean) -> ToolOptions) {
        val c = CheckButton.withLabel(text)
        c.focusOnClick = false
        c.onToggled { if (!updating) { val v = c.active; change { set(it, v) } } }
        updaters += { o -> c.active = get(o) }
        add(c)
    }

    private fun <E> choice(text: String?, items: List<E>, name: (E) -> String, get: (ToolOptions) -> E, set: (ToolOptions, E) -> ToolOptions) {
        if (text != null) label(text)
        val d = DropDown.fromStrings(items.map(name).toTypedArray())
        d.valign = Align.CENTER
        d.onNotify("selected") { _ -> if (!updating) items.getOrNull(d.selected)?.let { v -> change { set(it, v) } } }
        updaters += { o -> val i = items.indexOf(get(o)); if (i >= 0 && d.selected != i) d.selected = i }
        add(d)
    }

    /** Gruppe gekoppelter Knöpfe (nur einer aktiv). */
    private fun <E> toggles(items: List<Pair<E, String>>, get: (ToolOptions) -> E, set: (ToolOptions, E) -> ToolOptions) {
        val box = Box(Orientation.HORIZONTAL, 0)
        box.addCssClass("linked")
        val buttons = items.map { (v, t) ->
            ToggleButton.withLabel(t).apply {
                focusOnClick = false
                onToggled { if (!updating && active) change { set(it, v) } else if (!updating && !active && get(currentOpts) == v) active = true }
            } to v
        }
        for ((b, _) in buttons) box.append(b)
        updaters += { o -> for ((b, v) in buttons) b.active = get(o) == v }
        add(box)
    }

    private var currentOpts = ToolOptions()

    private fun build(o: ToolOptions, hasMask: Boolean) {
        clear()
        builtFor = o.tool; builtMask = hasMask
        updaters += { currentOpts = it }
        add(Label(o.tool.label).apply { addCssClass("heading") })
        sep()
        val t = o.tool
        if (t.isPaintTool && t != Tool.RED_EYE) {
            if (t == Tool.BRUSH || t == Tool.ERASER) {
                // Vorgaben: Auswahl wendet an, die Liste zeigt danach wieder „Vorgaben“
                val presets = listOf(null) + Settings.userBrushes() + BrushPresets.builtin
                choice(null, presets, { it?.name ?: tr("Vorgaben") }, { null }) { o2, p -> if (p == null) o2 else o2.copy(brush = if (p.keepSize) p.settings.copy(size = o2.brush.size) else p.settings) }
            }
            spin(tr("Größe"), 1.0, 5000.0, 1.0, get = { it.brush.size.toDouble() }) { o2, v -> o2.copy(brush = o2.brush.copy(size = v.toFloat())) }
            spin(tr("Härte"), 0.0, 100.0, 5.0, get = { (it.brush.hardness * 100.0) }) { o2, v -> o2.copy(brush = o2.brush.copy(hardness = (v / 100).toFloat())) }
            if (t == Tool.BRUSH || t == Tool.PENCIL || t == Tool.ERASER || t == Tool.CLONE) {
                spin(tr("Deckkraft"), 1.0, 100.0, 5.0, get = { (it.brush.opacity * 100.0) }) { o2, v -> o2.copy(brush = o2.brush.copy(opacity = (v / 100).toFloat())) }
                spin(tr("Fluss"), 1.0, 100.0, 5.0, get = { (it.brush.flow * 100.0) }) { o2, v -> o2.copy(brush = o2.brush.copy(flow = (v / 100).toFloat())) }
            }
            if (t == Tool.BRUSH || t == Tool.PENCIL) choice(tr("Modus"), BrushMode.entries, { it.label }, { it.brush.mode }) { o2, m -> o2.copy(brush = o2.brush.copy(mode = m)) }
            if (t == Tool.BRUSH || t == Tool.ERASER) check(tr("Airbrush"), { it.brush.airbrush }) { o2, v -> o2.copy(brush = o2.brush.copy(airbrush = v)) }
            if (t == Tool.BRUSH || t == Tool.PENCIL || t == Tool.ERASER || t == Tool.CLONE) add(brushButton())
            when (t) {
                Tool.CLONE, Tool.HEAL, Tool.SPOT_HEAL -> {
                    check(tr("Alle Ebenen"), { it.sampleAll }) { o2, v -> o2.copy(sampleAll = v) }
                    if (t != Tool.SPOT_HEAL) label(tr("Strg+Klick: Quelle festlegen"))
                }
                Tool.BG_ERASER -> {
                    spin(tr("Toleranz"), 0.0, 255.0, 1.0, get = { it.bgEraserTolerance.toDouble() }) { o2, v -> o2.copy(bgEraserTolerance = v.toInt()) }
                    toggles(listOf(false to tr("Kontinuierlich"), true to tr("Einmal")), { it.bgEraserOnce }) { o2, v -> o2.copy(bgEraserOnce = v) }
                    check(tr("Vordergrundfarbe schützen"), { it.bgEraserProtect }) { o2, v -> o2.copy(bgEraserProtect = v) }
                }
                Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE, Tool.LIQUIFY, Tool.DODGE, Tool.BURN, Tool.SPONGE ->
                    spin(tr("Stärke"), 1.0, 100.0, 5.0, get = { it.strength * 100.0 }) { o2, v -> o2.copy(strength = (v / 100).toFloat()) }
                else -> {}
            }
            if (t == Tool.DODGE || t == Tool.BURN) choice(tr("Bereich"), ToneRange.entries, { it.label }, { it.toneRange }) { o2, v -> o2.copy(toneRange = v) }
            if (t == Tool.SPONGE) toggles(listOf(false to tr("Entsättigen"), true to tr("Sättigen")), { it.spongeSaturate }) { o2, v -> o2.copy(spongeSaturate = v) }
            if (t == Tool.LIQUIFY) choice(tr("Modus"), LiquifyMode.entries, { it.label }, { it.liquifyMode }) { o2, v -> o2.copy(liquifyMode = v) }
            if (hasMask && t in setOf(Tool.BRUSH, Tool.PENCIL, Tool.ERASER, Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE, Tool.DODGE, Tool.BURN)) {
                sep()
                toggles(listOf(PaintTarget.PIXELS to tr("Pixel"), PaintTarget.MASK to tr("Maske")), { it.paintTarget }) { o2, v -> o2.copy(paintTarget = v) }
            }
        }
        when (t) {
            Tool.RED_EYE -> {
                spin(tr("Größe"), 4.0, 2000.0, 1.0, get = { it.brush.size.toDouble() }) { o2, v -> o2.copy(brush = o2.brush.copy(size = v.toFloat())) }
                label(tr("Auf die Pupille klicken"))
            }
            Tool.SELECT, Tool.LASSO, Tool.POLY_LASSO, Tool.WAND, Tool.OBJECT_SELECT, Tool.QUICK_SELECT -> {
                if (t == Tool.SELECT) toggles(listOf(SelectionShape.RECT to tr("Rechteck"), SelectionShape.ELLIPSE to tr("Ellipse")), { it.selectionShape }) { o2, v -> o2.copy(selectionShape = v) }
                if (t == Tool.OBJECT_SELECT) label(tr("Rechteck um das Objekt ziehen"))
                if (t == Tool.QUICK_SELECT) {
                    spin(tr("Größe"), 1.0, 2000.0, 1.0, get = { it.brush.size.toDouble() }) { o2, v -> o2.copy(brush = o2.brush.copy(size = v.toFloat())) }
                    check(tr("Alle Ebenen"), { it.sampleAll }) { o2, v -> o2.copy(sampleAll = v) }
                }
                if (t == Tool.WAND) {
                    spin(tr("Toleranz"), 0.0, 255.0, 1.0, get = { it.wandTolerance.toDouble() }) { o2, v -> o2.copy(wandTolerance = v.toInt()) }
                    check(tr("Benachbart"), { it.wandContiguous }) { o2, v -> o2.copy(wandContiguous = v) }
                    check(tr("Alle Ebenen"), { it.sampleAll }) { o2, v -> o2.copy(sampleAll = v) }
                }
                sep()
                toggles(listOf(SelectionOp.REPLACE to tr("Neu"), SelectionOp.ADD to tr("Hinzufügen"), SelectionOp.SUBTRACT to tr("Abziehen")), { it.selectionOp }) { o2, v -> o2.copy(selectionOp = v) }
                spin(tr("Weiche Kante"), 0.0, 250.0, 1.0, 1, get = { it.feather.toDouble() }) { o2, v -> o2.copy(feather = v.toFloat()) }
                label(tr("Umschalt: hinzufügen · Alt: abziehen"))
            }
            Tool.PATCH -> label(tr("Fehler umranden, dann die Auswahl auf eine saubere Stelle ziehen"))
            Tool.MOVE -> check(tr("Automatisch auswählen"), { it.autoSelectLayer }) { o2, v -> o2.copy(autoSelectLayer = v) }
            Tool.GRADIENT -> {
                choice(null, GradientType.entries, { it.label }, { it.gradientType }) { o2, v -> o2.copy(gradientType = v) }
                check(tr("Zu transparent"), { it.gradientToTransparent }) { o2, v -> o2.copy(gradientToTransparent = v) }
                spin(tr("Deckkraft"), 1.0, 100.0, 5.0, get = { it.gradientOpacity * 100.0 }) { o2, v -> o2.copy(gradientOpacity = (v / 100).toFloat()) }
                if (hasMask) { sep(); toggles(listOf(PaintTarget.PIXELS to tr("Pixel"), PaintTarget.MASK to tr("Maske")), { it.paintTarget }) { o2, v -> o2.copy(paintTarget = v) } }
            }
            Tool.EYEDROPPER -> check(tr("In Hintergrundfarbe"), { it.eyedropperToBg }) { o2, v -> o2.copy(eyedropperToBg = v) }
            Tool.BUCKET, Tool.MAGIC_ERASER -> {
                if (t == Tool.BUCKET) choice(tr("Modus"), de.photographics.core.doc.LayerStyles.blendModes, { it.label }, { it.fillBlend }) { o2, v -> o2.copy(fillBlend = v) }
                spin(tr("Deckkraft"), 1.0, 100.0, 5.0, get = { it.fillOpacity * 100.0 }) { o2, v -> o2.copy(fillOpacity = (v / 100).toFloat()) }
                spin(tr("Toleranz"), 0.0, 255.0, 1.0, get = { it.fillTolerance.toDouble() }) { o2, v -> o2.copy(fillTolerance = v.toInt()) }
                check(tr("Glätten"), { it.antiAlias }) { o2, v -> o2.copy(antiAlias = v) }
                check(tr("Benachbart"), { it.wandContiguous }) { o2, v -> o2.copy(wandContiguous = v) }
                check(tr("Alle Ebenen"), { it.sampleAll }) { o2, v -> o2.copy(sampleAll = v) }
            }
            Tool.RULER -> {
                val info = Label("").apply { addCssClass("numeric") }
                updaters += { o -> info.label = o.rulerLine?.let { l ->
                    val dx = l[2] - l[0]; val dy = l[3] - l[1]
                    tr("B: {0} px  H: {1} px  L: {2} px  W: {3}°", "%.0f".format(dx), "%.0f".format(-dy), "%.1f".format(kotlin.math.hypot(dx, dy)), "%.1f".format(Math.toDegrees(kotlin.math.atan2(-dy, dx))))
                } ?: tr("Linie ziehen, um zu messen") }
                add(info)
                val spacer = Box(Orientation.HORIZONTAL, 0).apply { hexpand = true }
                add(spacer)
                add(Button.withLabel(tr("Gerade ausrichten")).apply { tooltipText = tr("Bild drehen, sodass die Linie waagerecht bzw. senkrecht wird (Eingabe)"); onClicked { onApply() } })
                add(Button.withLabel(tr("Löschen")).apply { addCssClass("flat"); onClicked { onCancel() } })
            }
            Tool.ZOOM -> label(tr("Klicken: vergrößern · Alt+Klick: verkleinern · Rahmen ziehen: Bereich einpassen"))
            Tool.CROP -> {
                choice(tr("Verhältnis"), listOf<CropAspect?>(null) + CROP_ASPECTS, { it?.label ?: tr("Frei") }, { it.cropAspect }) { o2, v -> o2.copy(cropAspect = v) }
                check(tr("Beschnittene Pixel löschen"), { it.cropDeleteCropped }) { o2, v -> o2.copy(cropDeleteCropped = v) }
                sep()
                spin(tr("Drehen (°)"), -45.0, 45.0, 0.1, 1, get = { it.cropAngle.toDouble() }) { o2, v -> o2.copy(cropAngle = v.toFloat(), cropPerspective = false) }
                check(tr("Gerade ausrichten"), { it.cropStraighten }) { o2, v -> o2.copy(cropStraighten = v, cropPerspective = false) }
                check(tr("Perspektivisch"), { it.cropPerspective }) { o2, v -> o2.copy(cropPerspective = v) }
                applyCancel()
            }
            Tool.TRANSFORM -> {
                label(tr("Ecken: skalieren (Umschalt: frei, Strg: verzerren) · außerhalb: drehen"))
                applyCancel()
            }
            Tool.TEXT -> label(tr("Ins Bild klicken, um Text zu setzen oder zu bearbeiten"))
            Tool.SHAPE_RECT, Tool.SHAPE_ELLIPSE, Tool.SHAPE_POLYGON, Tool.SHAPE_LINE, Tool.PEN -> {
                toggles(listOf(true to tr("Form"), false to tr("Pfad")), { it.shapeLayerMode }) { o2, v -> o2.copy(shapeLayerMode = v) }
                sep()
                check(tr("Füllung"), { it.shapeFill }) { o2, v -> o2.copy(shapeFill = v) }
                check(tr("Kontur"), { it.shapeStroke }) { o2, v -> o2.copy(shapeStroke = v) }
                add(strokeColorButton())
                spin(tr("Breite"), 0.5, 500.0, 1.0, 1, get = { it.strokeWidth.toDouble() }) { o2, v -> o2.copy(strokeWidth = v.toFloat()) }
                choice(null, de.photographics.core.vector.ShapeStroke.Align.entries, { it.label }, { it.strokeAlign }) { o2, v -> o2.copy(strokeAlign = v) }
                when (t) {
                    Tool.SHAPE_RECT -> { sep(); spin(tr("Radius"), 0.0, 2000.0, 1.0, get = { it.cornerRadius.toDouble() }) { o2, v -> o2.copy(cornerRadius = v.toFloat()) } }
                    Tool.SHAPE_POLYGON -> {
                        sep()
                        spin(tr("Seiten"), 3.0, 100.0, 1.0, get = { it.polygonSides.toDouble() }) { o2, v -> o2.copy(polygonSides = v.toInt()) }
                        spin(tr("Stern (%)"), 0.0, 99.0, 5.0, get = { it.starIndent.toDouble() }) { o2, v -> o2.copy(starIndent = v.toInt()) }
                    }
                    Tool.SHAPE_LINE -> { sep(); spin(tr("Stärke"), 1.0, 1000.0, 1.0, get = { it.lineWidth.toDouble() }) { o2, v -> o2.copy(lineWidth = v.toFloat()) } }
                    Tool.PEN -> label(tr("Klicken: Ecke · Ziehen: Kurve · Startpunkt oder Eingabe: fertig"))
                    else -> label(tr("Umschalt: gleichseitig · Alt: von der Mitte"))
                }
            }
            Tool.DIRECT_SELECT -> label(tr("Anker und Griffe ziehen · Alt+Klick auf einen Anker: Ecke ↔ Kurve · Entf: Anker löschen"))
            Tool.HAND -> label(tr("Ziehen verschiebt die Ansicht · Leertaste: überall"))
            else -> {}
        }
    }

    /** Konturfarbe der Formen (Farbfeld, Klick öffnet die Farbauswahl). */
    private fun strokeColorButton(): Widget {
        val b = Button()
        b.tooltipText = tr("Konturfarbe")
        val swatch = ColorSquare(currentOpts.strokeColor)
        b.child = swatch
        b.valign = Align.CENTER
        b.onClicked {
            val d = org.gnome.gtk.ColorDialog()
            val c = currentOpts.strokeColor
            d.chooseRgba(b.root as? org.gnome.gtk.Window, org.gnome.gdk.RGBA(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, ((c ushr 24) and 0xFF) / 255f), null) { res ->
                val rgba = runCatching { d.chooseRgbaFinish(res) }.getOrNull() ?: return@chooseRgba
                val v = (argbOf(rgba) and 0xFFFFFF) or ((rgba.readAlpha() * 255).toInt().coerceIn(0, 255) shl 24)
                change { it.copy(strokeColor = v) }
            }
        }
        updaters += { o -> swatch.color = o.strokeColor }
        return b
    }

    /** Weitere Pinseleinstellungen (wie Photoshops Pinsel-Bedienfeld) in einem Popover. */
    private fun brushButton(): Widget {
        val mb = org.gnome.gtk.MenuButton()
        mb.iconName = "pg-brush-symbolic"
        mb.label = tr("Pinsel …")
        mb.tooltipText = tr("Pinseleinstellungen")
        mb.setCreatePopupFunc { btn ->
            val o = currentOpts.brush
            val grid = org.gnome.gtk.Grid().apply { rowSpacing = 6; columnSpacing = 12; marginStart = 6; marginEnd = 6; marginTop = 6; marginBottom = 6 }
            var row = 0
            fun head(t: String) { grid.attach(Label(t).apply { addCssClass("heading"); xalign = 0f; marginTop = if (row == 0) 0 else 8 }, 0, row++, 2, 1) }
            fun num(t: String, min: Double, max: Double, step: Double, v: Double, digits: Int = 0, set: (de.photographics.core.tools.BrushSettings, Double) -> de.photographics.core.tools.BrushSettings) {
                grid.attach(Label(t).apply { xalign = 0f; addCssClass("dim-label") }, 0, row, 1, 1)
                grid.attach(SpinButton.withRange(min, max, step).apply {
                    this.digits = digits; value = v; widthChars = 5
                    onValueChanged { val x = value; change { it.copy(brush = set(it.brush, x)) } }
                }, 1, row++, 1, 1)
            }
            fun <E> pick(t: String, items: List<E>, name: (E) -> String, v: E, set: (de.photographics.core.tools.BrushSettings, E) -> de.photographics.core.tools.BrushSettings) {
                grid.attach(Label(t).apply { xalign = 0f; addCssClass("dim-label") }, 0, row, 1, 1)
                grid.attach(DropDown.fromStrings(items.map(name).toTypedArray()).apply {
                    selected = items.indexOf(v).coerceAtLeast(0)
                    onNotify("selected") { _ -> items.getOrNull(selected)?.let { x -> change { it.copy(brush = set(it.brush, x)) } } }
                }, 1, row++, 1, 1)
            }
            val dyn = de.photographics.core.tools.DynControl.entries
            head(tr("Pinselspitze"))
            num(tr("Abstand (%)"), 1.0, 1000.0, 1.0, o.spacing * 100.0) { b, x -> b.copy(spacing = (x / 100).toFloat()) }
            num(tr("Glättung (%)"), 0.0, 95.0, 5.0, o.smoothing * 100.0) { b, x -> b.copy(smoothing = (x / 100).toFloat()) }
            num(tr("Winkel (°)"), -180.0, 180.0, 1.0, o.angle.toDouble()) { b, x -> b.copy(angle = x.toFloat()) }
            num(tr("Rundheit (%)"), 5.0, 100.0, 1.0, o.roundness * 100.0) { b, x -> b.copy(roundness = (x / 100).toFloat()) }
            head(tr("Formeigenschaften"))
            pick(tr("Größe – Steuerung"), dyn, { it.label }, o.sizeControl) { b, x -> b.copy(sizeControl = x) }
            num(tr("Mindestdurchmesser (%)"), 0.0, 100.0, 5.0, o.minSize * 100.0) { b, x -> b.copy(minSize = (x / 100).toFloat()) }
            num(tr("Größen-Jitter (%)"), 0.0, 100.0, 5.0, o.sizeJitter * 100.0) { b, x -> b.copy(sizeJitter = (x / 100).toFloat()) }
            num(tr("Winkel-Jitter (%)"), 0.0, 100.0, 5.0, o.angleJitter * 100.0) { b, x -> b.copy(angleJitter = (x / 100).toFloat()) }
            pick(tr("Winkel – Steuerung"), de.photographics.core.tools.AngleControl.entries, { it.label }, o.angleControl) { b, x -> b.copy(angleControl = x) }
            head(tr("Streuung"))
            num(tr("Streuung"), 0.0, 10.0, 0.1, o.scatter.toDouble(), 1) { b, x -> b.copy(scatter = x.toFloat()) }
            num(tr("Anzahl"), 1.0, 16.0, 1.0, o.count.toDouble()) { b, x -> b.copy(count = x.toInt()) }
            head(tr("Übertragung"))
            pick(tr("Deckkraft – Steuerung"), dyn, { it.label }, o.opacityControl) { b, x -> b.copy(opacityControl = x) }
            num(tr("Deckkraft-Jitter (%)"), 0.0, 100.0, 5.0, o.opacityJitter * 100.0) { b, x -> b.copy(opacityJitter = (x / 100).toFloat()) }
            pick(tr("Fluss – Steuerung"), dyn, { it.label }, o.flowControl) { b, x -> b.copy(flowControl = x) }
            head(tr("Farbeinstellungen"))
            num(tr("Vordergrund/Hintergrund (%)"), 0.0, 100.0, 5.0, o.fgBgJitter * 100.0) { b, x -> b.copy(fgBgJitter = (x / 100).toFloat()) }
            num(tr("Farbton-Jitter (%)"), 0.0, 100.0, 1.0, o.hueJitter * 100.0) { b, x -> b.copy(hueJitter = (x / 100).toFloat()) }
            num(tr("Sättigungs-Jitter (%)"), 0.0, 100.0, 5.0, o.saturationJitter * 100.0) { b, x -> b.copy(saturationJitter = (x / 100).toFloat()) }
            num(tr("Helligkeits-Jitter (%)"), 0.0, 100.0, 5.0, o.brightnessJitter * 100.0) { b, x -> b.copy(brightnessJitter = (x / 100).toFloat()) }
            btn.popover = org.gnome.gtk.Popover().apply {
                child = org.gnome.gtk.ScrolledWindow().apply {
                    setPolicy(org.gnome.gtk.PolicyType.NEVER, org.gnome.gtk.PolicyType.AUTOMATIC)
                    propagateNaturalHeight = true; maxContentHeight = 640
                    child = grid
                }
            }
        }
        return mb
    }

    private fun applyCancel() {
        val spacer = Box(Orientation.HORIZONTAL, 0).apply { hexpand = true }
        add(spacer)
        add(Button.fromIconName("process-stop-symbolic").apply { tooltipText = tr("Abbrechen (Esc)"); addCssClass("flat"); onClicked { onCancel() } })
        add(Button.fromIconName("object-select-symbolic").apply { tooltipText = tr("Anwenden (Eingabe)"); addCssClass("suggested-action"); onClicked { onApply() } })
    }
}
