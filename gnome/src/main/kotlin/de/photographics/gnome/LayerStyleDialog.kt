package de.photographics.gnome

import de.photographics.core.doc.LayerEffects
import de.photographics.core.doc.LayerStyles
import de.photographics.core.tr
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.CheckButton
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.Widget
import kotlin.math.roundToInt

/**
 * Ebenenstil wie in Photoshop: links die Effekte mit Häkchen, rechts die Einstellungen des gewählten
 * Effekts. Jede Änderung erscheint sofort im Bild ([onPreview]); „OK“ übernimmt sie als einen Schritt
 * in den Verlauf, „Abbrechen“ stellt den vorherigen Stil wieder her ([onFinish]).
 */
class LayerStyleDialog(
    private val parent: Widget,
    initial: LayerEffects,
    private val onPreview: (LayerEffects) -> Unit,
    private val onFinish: (Boolean) -> Unit,
) {
    private val dialog = org.gnome.adw.Dialog()
    private val params = Box(Orientation.VERTICAL, 10).apply { marginStart = 18; marginEnd = 18; marginTop = 12; marginBottom = 18 }
    private val list = org.gnome.gtk.ListBox().apply { addCssClass("navigation-sidebar"); selectionMode = org.gnome.gtk.SelectionMode.SINGLE }
    private val checks = ArrayList<CheckButton>()
    private var fx = initial
    /** Zuletzt eingestellte Werte je Effekt, damit Aus- und wieder Einschalten sie behält. */
    private val remembered = HashMap<String, Any>()
    private var selected = LayerStyles.effects.indexOfFirst { it.get(initial) != null }.coerceAtLeast(0)
    private var finished = false
    private var updating = false

    init {
        dialog.title = tr("Ebenenstil")
        dialog.contentWidth = 700; dialog.contentHeight = 640
        val header = org.gnome.adw.HeaderBar().apply { showEndTitleButtons = false; showStartTitleButtons = false }
        header.packStart(Button.withLabel(tr("Abbrechen")).apply { onClicked { finish(false) } })
        header.packEnd(Button.withLabel(tr("OK")).apply { addCssClass("suggested-action"); onClicked { finish(true) } })

        for ((i, e) in LayerStyles.effects.withIndex()) {
            val check = CheckButton().apply {
                active = e.get(fx) != null; valign = Align.CENTER
                tooltipText = tr("Effekt ein-/ausblenden")
                onToggled { if (!updating) toggle(i, active) }
            }
            checks += check
            list.append(Box(Orientation.HORIZONTAL, 8).apply {
                marginTop = 2; marginBottom = 2
                append(check)
                append(Label(e.label).apply { xalign = 0f; hexpand = true })
            })
        }
        list.onRowSelected { row -> if (row != null && !updating && row.index != selected) { selected = row.index; refresh() } }

        val side = Box(Orientation.VERTICAL, 0).apply {
            setSizeRequest(240, -1)
            append(Label(tr("Stile")).apply { xalign = 0f; addCssClass("heading"); marginStart = 12; marginTop = 12; marginBottom = 6 })
            append(ScrolledWindow().apply { setPolicy(org.gnome.gtk.PolicyType.NEVER, org.gnome.gtk.PolicyType.AUTOMATIC); vexpand = true; child = list })
        }
        val body = Box(Orientation.HORIZONTAL, 0)
        body.append(side)
        body.append(org.gnome.gtk.Separator(Orientation.VERTICAL))
        body.append(ScrolledWindow().apply {
            setPolicy(org.gnome.gtk.PolicyType.NEVER, org.gnome.gtk.PolicyType.AUTOMATIC); hexpand = true; child = params
        })
        dialog.child = org.gnome.adw.ToolbarView().apply { addTopBar(header); content = body }
        dialog.onClosed { if (!finished) { finished = true; onFinish(false) } }
        refresh()
    }

    fun present() = dialog.present(parent)

    /** Testaktion: Effekt [id] einschalten und anzeigen, „ok“/„cancel“ schließt. */
    fun debug(id: String) {
        when (id) {
            "ok" -> finish(true)
            "cancel" -> finish(false)
            else -> LayerStyles.effects.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { checks[it].active = true }
        }
    }

    private fun finish(commit: Boolean) {
        if (finished) return
        finished = true
        onFinish(commit)
        dialog.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun effect(i: Int) = LayerStyles.effects[i] as LayerStyles.Effect<Any>

    private fun toggle(i: Int, on: Boolean) {
        val e = effect(i)
        val cur = e.get(fx)
        if (cur != null) remembered[e.id] = cur
        fx = e.with(fx, if (on) remembered[e.id] ?: e.default else null)
        if (selected != i) { selected = i; refresh() } else refresh()
        onPreview(fx)
    }

    private fun set(value: Any) {
        val e = effect(selected)
        fx = e.with(fx, value)
        remembered[e.id] = value
        if (!checks[selected].active) { updating = true; checks[selected].active = true; updating = false }
        onPreview(fx)
    }

    /** Einstellungen des gewählten Effekts neu aufbauen. */
    private fun refresh() {
        updating = true
        list.selectRow(list.getRowAtIndex(selected))
        for ((i, c) in checks.withIndex()) c.active = LayerStyles.effects[i].get(fx) != null
        updating = false
        while (true) { val c = params.firstChild ?: break; params.remove(c) }
        val e = effect(selected)
        params.append(Label(e.label).apply { xalign = 0f; addCssClass("title-4") })
        var value = e.get(fx) ?: remembered[e.id] ?: e.default
        fun rgba(c: Int) = org.gnome.gdk.RGBA(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, 1f)
        val grid = org.gnome.gtk.Grid().apply { rowSpacing = 10; columnSpacing = 12 }
        var row = 0
        fun label(text: String) = Label(text).apply { xalign = 0f; addCssClass("dim-label") }
        for (p in e.params) {
            when (p) {
                is LayerStyles.Param.Slider -> {
                    val scale = org.gnome.gtk.Scale.withRange(Orientation.HORIZONTAL, p.min, p.max, if (p.digits == 0) 1.0 else 0.1).apply {
                        drawValue = false; hexpand = true; this.value = p.get(value).coerceIn(p.min, p.max)
                    }
                    val spin = org.gnome.gtk.SpinButton.withRange(p.min, p.max, if (p.digits == 0) 1.0 else 0.1).apply {
                        digits = p.digits; widthChars = 5; this.value = p.get(value)
                    }
                    var syncing = false
                    fun apply(v: Double) {
                        if (syncing) return
                        syncing = true
                        val r = if (p.digits == 0) v.roundToInt().toDouble() else v
                        scale.value = r; spin.value = r
                        syncing = false
                        value = p.set(value, r); set(value)
                    }
                    scale.onValueChanged { apply(scale.value) }
                    spin.onValueChanged { apply(spin.value) }
                    grid.attach(label(p.label), 0, row, 1, 1)
                    grid.attach(scale, 1, row, 1, 1)
                    grid.attach(Box(Orientation.HORIZONTAL, 4).apply { append(spin); append(Label(p.unit).apply { addCssClass("dim-label"); widthChars = 2; xalign = 0f }) }, 2, row, 1, 1)
                }
                is LayerStyles.Param.Color -> {
                    val b = org.gnome.gtk.ColorDialogButton(org.gnome.gtk.ColorDialog().apply { withAlpha = false }).apply {
                        rgba = rgba(p.get(value)); halign = Align.START
                    }
                    b.onNotify("rgba") { _ -> value = p.set(value, argbOf(b.rgba) and 0xFFFFFF); set(value) }
                    grid.attach(label(p.label), 0, row, 1, 1)
                    grid.attach(b, 1, row, 2, 1)
                }
                is LayerStyles.Param.Blend -> {
                    val modes = LayerStyles.blendModes
                    val dd = org.gnome.gtk.DropDown.fromStrings(modes.map { it.label }.toTypedArray()).apply {
                        selected = modes.indexOf(p.get(value)).coerceAtLeast(0); halign = Align.START
                    }
                    dd.onNotify("selected") { _ -> value = p.set(value, modes[dd.selected]); set(value) }
                    grid.attach(label(p.label), 0, row, 1, 1)
                    grid.attach(dd, 1, row, 2, 1)
                }
                is LayerStyles.Param.Choice -> {
                    val dd = org.gnome.gtk.DropDown.fromStrings(p.options.toTypedArray()).apply { selected = p.get(value); halign = Align.START }
                    dd.onNotify("selected") { _ -> value = p.set(value, dd.selected); set(value) }
                    grid.attach(label(p.label), 0, row, 1, 1)
                    grid.attach(dd, 1, row, 2, 1)
                }
                is LayerStyles.Param.Toggle -> {
                    val c = CheckButton.withLabel(p.label).apply { active = p.get(value) }
                    c.onToggled { value = p.set(value, c.active); set(value) }
                    grid.attach(c, 1, row, 2, 1)
                }
            }
            row++
        }
        params.append(grid)
        params.append(Button.withLabel(tr("Standardwerte")).apply {
            halign = Align.START; addCssClass("flat")
            onClicked { remembered.remove(e.id); set(e.default); refresh() }
        })
        if (fx.unsupported.isNotEmpty()) params.append(Label(tr("Nicht bearbeitbar und beim Übernehmen entfernt: {0}", fx.unsupported.joinToString { tr(it) })).apply {
            xalign = 0f; wrap = true; addCssClass("caption"); addCssClass("dim-label"); marginTop = 12
        })
    }
}
