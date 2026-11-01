package de.photographics.gnome

import de.photographics.core.adjust.AdjParam
import de.photographics.core.adjust.Adjustment
import de.photographics.core.adjust.AdjustmentType
import de.photographics.core.adjust.CurvesAdj
import de.photographics.core.adjust.Histogram
import de.photographics.core.doc.TextAlign
import de.photographics.core.doc.TextStyle
import de.photographics.core.tr
import org.gnome.gdk.RGBA
import org.gnome.gtk.Adjustment as GtkAdjustment
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.CheckButton
import org.gnome.gtk.DropDown
import org.gnome.gtk.GestureClick
import org.gnome.gtk.GestureDrag
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.Scale
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.Snapshot
import org.gnome.gtk.SpinButton
import org.gnome.gtk.TextView
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.Widget
import org.gnome.gtk.WrapMode
import java.lang.foreign.MemorySegment
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Eigenschaften in der Seitenleiste (statt eines Dialogs, damit das Bild sichtbar bleibt): Regler
 * für Filter, direkte Korrekturen, Einstellungsebenen und die Textbearbeitung. Oben Titel mit
 * „Abbrechen“ und „Anwenden“.
 */
class PropertiesPanel(private val onClosed: () -> Unit) {
    val widget = Box(Orientation.VERTICAL, 0)
    private val title = Label("").apply { addCssClass("heading"); hexpand = true; xalign = 0f; ellipsize = org.gnome.pango.EllipsizeMode.END }
    private val content = Box(Orientation.VERTICAL, 10).apply { marginStart = 12; marginEnd = 12; marginTop = 6; marginBottom = 12 }
    private var finish: ((Boolean) -> Unit)? = null
    val open get() = finish != null

    init {
        val head = Box(Orientation.VERTICAL, 8).apply { marginStart = 12; marginEnd = 12; marginTop = 12; marginBottom = 6 }
        head.append(title)
        val buttons = Box(Orientation.HORIZONTAL, 6).apply { homogeneous = true }
        buttons.append(Button.withLabel(tr("Abbrechen")).apply { onClicked { close(false) } })
        buttons.append(Button.withLabel(tr("Anwenden")).apply { addCssClass("suggested-action"); onClicked { close(true) } })
        head.append(buttons)
        widget.append(head)
        widget.append(ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            vexpand = true
            child = content
        })
    }

    /** Schließt das Panel; [apply] = übernehmen, sonst verwerfen. */
    fun close(apply: Boolean) {
        val f = finish ?: return
        finish = null
        f(apply)
        onClosed()
    }

    private fun reset(heading: String, done: (Boolean) -> Unit) {
        finish?.invoke(false)
        var c = content.firstChild
        while (c != null) { val n = c.nextSibling; content.remove(c); c = n }
        title.label = heading
        finish = done
    }

    // ------------------------------------------------------------ Regler

    /** Regler je Parameter; [changed] erhält alle Werte nach jeder Änderung. */
    private fun params(params: List<FilterParam>, changed: (FloatArray) -> Unit) {
        val values = FloatArray(params.size) { params[it].default }
        for ((i, p) in params.withIndex()) {
            when (p.kind) {
                ParamKind.SWITCH -> content.append(CheckButton.withLabel(p.label).apply {
                    active = p.default >= 0.5f
                    onToggled { values[i] = if (active) 1f else 0f; changed(values.copyOf()) }
                })
                ParamKind.CHOICE -> {
                    content.append(Label(p.label).apply { xalign = 0f; addCssClass("dim-label") })
                    content.append(DropDown.fromStrings(p.choices.toTypedArray()).apply {
                        selected = p.default.toInt()
                        onNotify("selected") { _ -> values[i] = selected.toFloat(); changed(values.copyOf()) }
                    })
                }
                else -> {
                    val adj = GtkAdjustment(p.default.toDouble(), p.min.toDouble(), p.max.toDouble(), stepOf(p), stepOf(p) * 10, 0.0)
                    val row = Box(Orientation.HORIZONTAL, 6)
                    row.append(Label(p.label).apply { xalign = 0f; hexpand = true; addCssClass("dim-label") })
                    row.append(SpinButton(adj, 1.0, p.decimals.toUInt().toInt()).apply { widthChars = 5; valign = Align.CENTER })
                    content.append(row)
                    content.append(Scale(Orientation.HORIZONTAL, adj).apply { drawValue = false; hexpand = true })
                    adj.onValueChanged { values[i] = adj.value.toFloat(); changed(values.copyOf()) }
                }
            }
        }
    }

    private fun stepOf(p: FilterParam) = when (p.decimals) { 0 -> 1.0; 1 -> 0.1; else -> 0.01 }

    private fun AdjParam.toFilterParam() = FilterParam(label, min, max, value, decimals,
        when (kind) { AdjParam.Kind.SWITCH -> ParamKind.SWITCH; AdjParam.Kind.HUE -> ParamKind.HUE; else -> ParamKind.SLIDER })

    /** Filter mit Live-Vorschau. */
    fun showFilter(spec: FilterSpec, c: Controller) {
        reset(spec.label) { apply -> c.filterFinish(apply, spec.label) }
        if (spec.params.isEmpty()) content.append(Label(tr("Keine Einstellungen")).apply { addCssClass("dim-label") })
        params(spec.params) { v -> c.filterPreview(spec.build(v)) }
        c.filterPreview(spec.build(FloatArray(spec.params.size) { spec.params[it].default }))
    }

    /** Korrektur direkt auf die Pixelebene (wie ein Filter). */
    fun showDirectAdjustment(t: AdjustmentType, c: Controller) {
        val def = t.createDefault()
        val ps = def.params().map { it.toFilterParam() }
        reset(t.label) { apply -> c.filterFinish(apply, t.label) }
        params(ps) { v -> c.filterPreview(AdjustmentFilter(def.withValues(v))) }
        c.filterPreview(AdjustmentFilter(def))
    }

    /** Einstellungsebene bearbeiten (nicht destruktiv). */
    fun showAdjustment(id: Int, a: Adjustment, histogram: IntArray?, c: Controller) {
        reset(a.label) { apply -> c.finishAdjustment(id, apply) }
        if (a is CurvesAdj) {
            var cur: CurvesAdj = a
            val editor = CurveEditor()
            editor.histogram = histogram
            var channel = 0
            fun pts() = when (channel) { 1 -> cur.red; 2 -> cur.green; 3 -> cur.blue; else -> cur.master } ?: listOf(0 to 0, 255 to 255)
            editor.points = pts()
            editor.onChange = { p ->
                cur = when (channel) { 1 -> cur.copy(red = p); 2 -> cur.copy(green = p); 3 -> cur.copy(blue = p); else -> cur.copy(master = p) }
                c.previewAdjustment(id, cur)
            }
            val chans = Box(Orientation.HORIZONTAL, 0).apply { addCssClass("linked"); halign = Align.CENTER }
            val btns = listOf("RGB", tr("Rot"), tr("Grün"), tr("Blau")).mapIndexed { i, n -> ToggleButton.withLabel(n).apply { active = i == 0 } }
            for ((i, b) in btns.withIndex()) {
                b.onToggled {
                    if (!b.active) { if (channel == i) b.active = true; return@onToggled }
                    channel = i
                    for ((j, o) in btns.withIndex()) if (j != i) o.active = false
                    editor.channel = i; editor.points = pts()
                }
                chans.append(b)
            }
            content.append(chans)
            content.append(editor)
            content.append(Label(tr("Klicken: Punkt hinzufügen · aus dem Feld ziehen: entfernen")).apply { addCssClass("dim-label"); wrap = true; xalign = 0f })
            return
        }
        if (a is de.photographics.core.adjust.ColorLookupAdj) { lookup(id, a, c); return }
        val ps = a.params().map { it.toFilterParam() }
        if (ps.isEmpty()) content.append(Label(tr("Keine Einstellungen")).apply { addCssClass("dim-label") })
        params(ps) { v -> c.previewAdjustment(id, a.withValues(v)) }
    }

    /** Farbtabelle: eingebaute Looks und Filmsimulationen oder eine eigene .cube/.3dl-Datei. */
    private fun lookup(id: Int, a: de.photographics.core.adjust.ColorLookupAdj, c: Controller) {
        val looks = de.photographics.core.color.Looks.all
        val names = looks.map { "${it.name} (${tr(it.category)})" }.toMutableList()
        val fileName = if (a.fileData != null) a.lutName else null
        if (fileName != null) names.add(0, fileName)
        val dd = DropDown.fromStrings(names.toTypedArray())
        dd.selected = if (fileName != null) 0 else looks.indexOfFirst { it.name == a.lutName }.coerceAtLeast(0)
        val desc = Label("").apply { wrap = true; xalign = 0f; addCssClass("dim-label") }
        fun showDesc(i: Int) { desc.label = looks.getOrNull(i - (if (fileName != null) 1 else 0))?.description ?: "" }
        showDesc(dd.selected)
        dd.onNotify("selected") { _ ->
            val i = dd.selected - (if (fileName != null) 1 else 0)
            showDesc(dd.selected)
            if (i >= 0) looks.getOrNull(i)?.let { c.previewAdjustment(id, de.photographics.core.adjust.ColorLookupAdj(it.name)) }
            else c.previewAdjustment(id, a)
        }
        content.append(dd)
        content.append(desc)
        content.append(Button.withLabel(tr("Datei laden (.cube, .3dl) …")).apply {
            onClicked {
                val d = org.gnome.gtk.FileDialog()
                d.title = tr("Farbtabelle laden")
                val f = org.gnome.gtk.FileFilter().apply { name = tr("Farbtabellen"); addSuffix("cube"); addSuffix("3dl") }
                d.filters = org.gnome.gio.ListStore<org.gnome.gtk.FileFilter>(org.gnome.gtk.FileFilter.getType()).apply { append(f) }
                d.open(widget.root as? org.gnome.gtk.Window, null) { res ->
                    val file = runCatching { d.openFinish(res) }.getOrNull()?.local() ?: return@open
                    runCatching { de.photographics.core.adjust.ColorLookupAdj.fromFile(file.readBytes(), file.name) }
                        .onSuccess { lut -> c.previewAdjustment(id, lut); names.add(0, lut.lutName); dd.model = org.gnome.gtk.StringList(arrayOf(lut.lutName) + looks.map { it.name }.toTypedArray()) }
                        .onFailure { e -> c.toastError(tr("Farbtabelle konnte nicht gelesen werden: {0}", e.message ?: "")) }
                }
            }
        })
    }

    /** Textebene bearbeiten: Inhalt, Schrift, Größe, Farbe, Stil, Ausrichtung – Änderungen erscheinen sofort. */
    fun showText(style0: TextStyle, c: Controller, window: org.gnome.gtk.Window) {
        var style = style0
        reset(tr("Text")) { apply -> c.finishText(apply) }
        fun upd(s: TextStyle) { style = s; c.updateTextLive(s) }
        val tv = TextView().apply {
            wrapMode = WrapMode.WORD_CHAR
            buffer.setText(style.text, -1)
            topMargin = 6; bottomMargin = 6; leftMargin = 6; rightMargin = 6
            buffer.onChanged {
                val a = org.gnome.gtk.TextIter(); val b = org.gnome.gtk.TextIter()
                buffer.getBounds(a, b)
                upd(style.copy(text = buffer.getText(a, b, false) ?: ""))
            }
        }
        content.append(ScrolledWindow().apply { child = tv; minContentHeight = 90; addCssClass("card") })
        val fontBtn = org.gnome.gtk.FontDialogButton(org.gnome.gtk.FontDialog()).apply {
            level = org.gnome.gtk.FontLevel.FAMILY
            // Android-Familiennamen („sans-serif“) auf die Fontconfig-Namen abbilden, die der Dialog kennt
            fontDesc = org.gnome.pango.FontDescription.fromString(PangoText.family(style.fontFamily))
            onNotify("font-desc") { _ -> fontDesc?.family?.let { upd(style.copy(fontFamily = it)) } }
        }
        content.append(row(tr("Schrift"), fontBtn))
        content.append(row(tr("Größe (px)"), SpinButton.withRange(4.0, 4000.0, 1.0).apply { value = style.sizePx.toDouble(); onValueChanged { upd(style.copy(sizePx = value.toFloat())) } }))
        val colorBtn = org.gnome.gtk.ColorDialogButton(org.gnome.gtk.ColorDialog().apply { withAlpha = false }).apply {
            val s = style.color
            rgba = RGBA(((s shr 16) and 0xFF) / 255f, ((s shr 8) and 0xFF) / 255f, (s and 0xFF) / 255f, 1f)
            onNotify("rgba") { _ -> rgba?.let { upd(style.copy(color = argbOf(it))) } }
        }
        content.append(row(tr("Farbe"), colorBtn))
        val styleBox = Box(Orientation.HORIZONTAL, 0).apply { addCssClass("linked") }
        styleBox.append(ToggleButton().apply { iconName = "format-text-bold-symbolic"; active = style.bold; tooltipText = tr("Fett"); onToggled { upd(style.copy(bold = active)) } })
        styleBox.append(ToggleButton().apply { iconName = "format-text-italic-symbolic"; active = style.italic; tooltipText = tr("Kursiv"); onToggled { upd(style.copy(italic = active)) } })
        content.append(row(tr("Stil"), styleBox))
        val al = DropDown.fromStrings(TextAlign.entries.map { it.label }.toTypedArray()).apply {
            selected = style.align.ordinal
            onNotify("selected") { _ -> upd(style.copy(align = TextAlign.entries[selected])) }
        }
        content.append(row(tr("Ausrichtung"), al))
        content.append(row(tr("Zeilenabstand"), SpinButton.withRange(0.5, 4.0, 0.05).apply { digits = 2; value = style.lineHeight.toDouble(); onValueChanged { upd(style.copy(lineHeight = value.toFloat())) } }))
        content.append(row(tr("Laufweite (em)"), SpinButton.withRange(-0.5, 2.0, 0.01).apply { digits = 2; value = style.letterSpacing.toDouble(); onValueChanged { upd(style.copy(letterSpacing = value.toFloat())) } }))
        tv.grabFocus()
    }

    private fun row(label: String, w: Widget) = Box(Orientation.HORIZONTAL, 8).apply {
        append(Label(label).apply { xalign = 0f; hexpand = true; addCssClass("dim-label") })
        w.valign = Align.CENTER
        append(w)
    }
}

fun argbOf(c: RGBA): Int = (0xFF shl 24) or ((c.readRed() * 255).roundToInt().coerceIn(0, 255) shl 16) or
    ((c.readGreen() * 255).roundToInt().coerceIn(0, 255) shl 8) or (c.readBlue() * 255).roundToInt().coerceIn(0, 255)

/** Gradationskurve: Punkte ziehen, Klick fügt hinzu, aus dem Feld ziehen entfernt (Ecken bleiben). */
class CurveEditor : Widget {
    constructor() : super() { init() }
    constructor(address: MemorySegment) : super(address)

    var points: List<Pair<Int, Int>> = listOf(0 to 0, 255 to 255)
        set(v) { field = v.sortedBy { it.first }; queueDraw() }
    var histogram: IntArray? = null
        set(v) { field = v; queueDraw() }
    var channel = 0
        set(v) { field = v; queueDraw() }
    var onChange: (List<Pair<Int, Int>>) -> Unit = {}
    private var dragging = -1
    private val pad = 10f

    private fun init() {
        setSizeRequest(256, 256)
        hexpand = true
        val drag = GestureDrag()
        var sx = 0.0; var sy = 0.0
        drag.onDragBegin { x, y ->
            sx = x; sy = y
            val (vx, vy) = toValue(x, y)
            dragging = points.indices.minByOrNull { abs(points[it].first - vx) + abs(points[it].second - vy) }
                ?.takeIf { abs(points[it].first - vx) + abs(points[it].second - vy) < 24 } ?: -1
            if (dragging < 0 && points.none { abs(it.first - vx) < 8 }) {
                val np = (points + (vx.roundToInt().coerceIn(0, 255) to vy.roundToInt().coerceIn(0, 255))).sortedBy { it.first }
                points = np
                dragging = np.indexOfFirst { it.first == vx.roundToInt().coerceIn(0, 255) }
                onChange(np)
            }
        }
        drag.onDragUpdate { dx, dy ->
            val i = dragging
            if (i < 0 || i >= points.size) return@onDragUpdate
            val x = sx + dx; val y = sy + dy
            val (vx, vy) = toValue(x, y)
            val list = points.toMutableList()
            val outside = y < -30 || y > height + 30
            if (outside && i != 0 && i != list.lastIndex) { list.removeAt(i); dragging = -1 }
            else {
                val lo = if (i == 0) 0 else list[i - 1].first + 1
                val hi = if (i == list.lastIndex) 255 else list[i + 1].first - 1
                list[i] = vx.roundToInt().coerceIn(lo, maxOf(lo, hi)) to vy.roundToInt().coerceIn(0, 255)
            }
            points = list
            onChange(list)
        }
        drag.onDragEnd { _, _ -> dragging = -1 }
        addController(drag)
    }

    private fun toValue(x: Double, y: Double): Pair<Double, Double> {
        val w = width - 2 * pad; val h = height - 2 * pad
        return (x - pad) / w * 255 to 255 - (y - pad) / h * 255
    }

    override fun snapshot(s: Snapshot) = guarded { draw(s) }

    private fun draw(s: Snapshot) {
        val w = width - 2 * pad; val h = height - 2 * pad
        val fg = RGBA().also { getColor(it) }
        val dim = RGBA(fg.readRed(), fg.readGreen(), fg.readBlue(), 0.15f)
        s.appendColor(RGBA(0f, 0f, 0f, 0.15f), Draw.rect(pad, pad, w, h))
        for (k in 1..3) {
            Draw.path(floatArrayOf(pad + w * k / 4, pad, pad + w * k / 4, pad + h), false)?.let { s.appendStroke(it, org.gnome.gsk.Stroke(1f), dim) }
            Draw.path(floatArrayOf(pad, pad + h * k / 4, pad + w, pad + h * k / 4), false)?.let { s.appendStroke(it, org.gnome.gsk.Stroke(1f), dim) }
        }
        val tint = when (channel) { 1 -> RGBA(0.9f, 0.22f, 0.21f, 1f); 2 -> RGBA(0.26f, 0.63f, 0.28f, 1f); 3 -> RGBA(0.12f, 0.53f, 0.9f, 1f); else -> fg }
        histogram?.let { hist ->
            val bars = Histogram.display(hist, channel)
            val pts = FloatArray(516)
            for (x in 0..255) { pts[x * 2] = pad + x / 255f * w; pts[x * 2 + 1] = pad + h - bars[x] * h * 0.9f }
            pts[512] = pad + w; pts[513] = pad + h; pts[514] = pad; pts[515] = pad + h
            Draw.path(pts, true)?.let { s.appendFill(it, org.gnome.gsk.FillRule.WINDING, RGBA(tint.readRed(), tint.readGreen(), tint.readBlue(), 0.22f)) }
        }
        Draw.path(floatArrayOf(pad, pad + h, pad + w, pad), false)?.let { s.appendStroke(it, org.gnome.gsk.Stroke(1f), dim) }
        val table = CurvesAdj.table(points)
        val curve = FloatArray(512) { i -> val x = i / 2; if (i % 2 == 0) pad + x / 255f * w else pad + h - table[x] / 255f * h }
        Draw.path(curve, false)?.let { s.appendStroke(it, org.gnome.gsk.Stroke(2f), tint) }
        for ((px, py) in points) {
            val cx = pad + px / 255f * w; val cy = pad + h - py / 255f * h
            val b = org.gnome.gsk.PathBuilder(); b.addCircle(org.gnome.graphene.Point().init(cx, cy), 5f)
            val p = b.toPath()
            s.appendFill(p, org.gnome.gsk.FillRule.WINDING, RGBA(0.15f, 0.15f, 0.15f, 1f))
            s.appendStroke(p, org.gnome.gsk.Stroke(2f), tint)
        }
    }
}
