package de.photographics.gnome

import de.photographics.core.render.BlendMode
import de.photographics.core.selection.SelectionOp
import de.photographics.core.tools.PaintTarget
import de.photographics.core.tr
import org.gnome.gdk.DragAction
import org.gnome.gdk.ModifierType
import org.gnome.gdk.Texture
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.DragSource
import org.gnome.gtk.DropDown
import org.gnome.gtk.DropTarget
import org.gnome.gtk.Entry
import org.gnome.gtk.EventControllerKey
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Image
import org.gnome.gtk.Label
import org.gnome.gtk.ListBox
import org.gnome.gtk.ListBoxRow
import org.gnome.gtk.MenuButton
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.PopoverMenu
import org.gnome.gtk.PropagationPhase
import org.gnome.gtk.Scale
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SelectionMode
import org.gnome.gtk.SpinButton
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.Widget
import org.gnome.pango.EllipsizeMode
import org.javagi.gobject.types.Types

/**
 * Ebenenbedienfeld nach Photoshop: Mehrfachauswahl (Strg/Umschalt), Ziehen zum Umsortieren und in
 * Gruppen, Doppelklick auf den Namen zum Umbenennen, Alt+Klick aufs Auge blendet alle anderen aus,
 * Strg+Klick aufs Miniaturbild lädt die Transparenz als Auswahl, Klick aufs Masken-Miniaturbild malt
 * auf der Maske (Umschalt+Klick schaltet sie ab).
 */
class LayersPanel(private val act: Actions) {
    interface Actions {
        fun select(active: Int, selected: Set<Int>)
        fun setVisible(id: Int, visible: Boolean)
        fun solo(id: Int)
        fun opacity(v: Float)
        fun fill(v: Float)
        fun blend(mode: BlendMode)
        fun lockTransparency(on: Boolean)
        fun open(row: LayerRow)
        fun rename(id: Int, name: String)
        fun target(id: Int, target: PaintTarget)
        fun toggleMaskEnabled(id: Int)
        fun loadSelection(id: Int, mask: Boolean, op: SelectionOp)
        fun toggleExpanded(id: Int)
        fun drop(ids: List<Int>, parentId: Int, index: Int)
        fun deleteLayers(ids: List<Int>)
        fun duplicateLayers(ids: List<Int>)
    }

    val widget = Box(Orientation.VERTICAL, 0)
    private val list = ListBox()
    private val opacity = Scale.withRange(Orientation.HORIZONTAL, 0.0, 100.0, 1.0)
    private val fill = SpinButton.withRange(0.0, 100.0, 1.0)
    private val lock = ToggleButton()
    private val blendModes = BlendMode.entries.filter { it != BlendMode.PASS_THROUGH }
    private val blend = DropDown.fromStrings(blendModes.map { it.label }.toTypedArray())
    private var rows: List<LayerRow> = emptyList()
    private var updating = false
    private var target = PaintTarget.PIXELS
    private var thumbs: Map<Int, Texture> = emptyMap()
    /** Ausgewählte Ebenen (die aktive ist immer dabei). */
    var selected: Set<Int> = emptySet()
        private set
    private var clickedId: Int? = null
    private var dragging: List<Int> = emptyList()
    private var renaming = false

    init {
        widget.addCssClass("layers-panel")
        val props = Box(Orientation.VERTICAL, 6).apply { marginStart = 12; marginEnd = 12; marginTop = 6; marginBottom = 6 }
        blend.hexpand = true
        blend.tooltipText = tr("Mischmodus")
        blend.onNotify("selected") { _ -> if (!updating) blendModes.getOrNull(blend.selected)?.let(act::blend) }
        props.append(blend)
        val opRow = Box(Orientation.HORIZONTAL, 6)
        opRow.append(Label(tr("Deckkraft")).apply { addCssClass("dim-label") })
        opacity.hexpand = true
        opacity.drawValue = true
        opacity.valuePos = org.gnome.gtk.PositionType.RIGHT
        opacity.digits = 0
        opacity.onValueChanged { if (!updating) act.opacity((opacity.value / 100.0).toFloat()) }
        opRow.append(opacity)
        props.append(opRow)
        val lockRow = Box(Orientation.HORIZONTAL, 6)
        lockRow.append(Label(tr("Fixieren:")).apply { addCssClass("dim-label") })
        lock.iconName = "pg-lock-alpha-symbolic"
        lock.tooltipText = tr("Transparente Pixel fixieren")
        lock.addCssClass("flat")
        lock.focusOnClick = false
        lock.onToggled { if (!updating) act.lockTransparency(lock.active) }
        lockRow.append(lock)
        lockRow.append(Box(Orientation.HORIZONTAL, 0).apply { hexpand = true })
        lockRow.append(Label(tr("Fläche")).apply { addCssClass("dim-label") })
        fill.widthChars = 4
        fill.onValueChanged { if (!updating) act.fill((fill.value / 100.0).toFloat()) }
        lockRow.append(fill)
        lockRow.append(Label("%").apply { addCssClass("dim-label") })
        props.append(lockRow)
        widget.append(props)

        list.selectionMode = SelectionMode.MULTIPLE
        list.activateOnSingleClick = false
        list.addCssClass("navigation-sidebar")
        list.addCssClass("layer-list")
        // Merken, auf welche Zeile geklickt wurde: sie wird die aktive Ebene
        val press = GestureClick().apply { button = 1; propagationPhase = PropagationPhase.CAPTURE }
        press.onPressed { _, _, y -> clickedId = list.getRowAtY(y.toInt())?.let { rows.getOrNull(it.index)?.id } }
        list.addController(press)
        list.onSelectedRowsChanged {
            if (updating) return@onSelectedRowsChanged
            val ids = list.selectedRows.filterNotNull().mapNotNull { rows.getOrNull(it.index)?.id }.toSet()
            if (ids.isEmpty()) return@onSelectedRowsChanged
            val active = clickedId?.takeIf { it in ids } ?: rows.firstOrNull { it.active && it.id in ids }?.id ?: ids.first()
            selected = ids
            act.select(active, ids)
        }
        // Rechtsklick: Ebenenmenü
        val rc = GestureClick().apply { button = 3 }
        rc.onPressed { _, x, y ->
            val row = list.getRowAtY(y.toInt()) ?: return@onPressed
            val r = rows.getOrNull(row.index) ?: return@onPressed
            if (r.id !in selected) { clickedId = r.id; list.unselectAll(); list.selectRow(row) }
            val pop = PopoverMenu.fromModel(contextMenu())
            pop.setParent(list)
            pop.setPointingTo(org.gnome.gdk.Rectangle(x.toInt(), y.toInt(), 1, 1))
            pop.hasArrow = false
            pop.onClosed { onMain { pop.unparent() } }
            pop.popup()
        }
        list.addController(rc)
        val scroller = ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            vexpand = true
            // Zeilen haben selbst 6 px Rand: zusammen dieselben 12 px wie die Regler darüber
            marginStart = 6; marginEnd = 6
            child = list
        }
        widget.append(scroller)

        val bar = Box(Orientation.HORIZONTAL, 2).apply {
            marginStart = 12; marginEnd = 12; marginTop = 6; marginBottom = 6
            halign = Align.END
        }
        fun action(icon: String, name: String, tip: String) = Button.fromIconName(icon).apply {
            actionName = name; tooltipText = tip; addCssClass("flat")
        }
        bar.append(action("pg-fx-symbolic", "win.layer-style", tr("Ebenenstil") + " …"))
        bar.append(action("pg-mask-symbolic", "win.add-mask", tr("Ebenenmaske hinzufügen")))
        bar.append(MenuButton().apply {
            iconName = "pg-adjust-symbolic"; tooltipText = tr("Neue Einstellungsebene"); addCssClass("flat")
            setDirection(org.gnome.gtk.ArrowType.UP)
            menuModel = org.gnome.gio.Menu().apply {
                for (t in de.photographics.core.adjust.AdjustmentType.entries) if (t != de.photographics.core.adjust.AdjustmentType.COLOR_LOOKUP)
                    append(t.label, "win.adjust-layer::${t.name}")
            }
        })
        bar.append(action("folder-new-symbolic", "win.group-layers", tr("Ebenen gruppieren") + " (Strg+G)"))
        // Wie Photoshop: Ebene auf „Neu“ ziehen dupliziert, auf den Papierkorb ziehen löscht
        bar.append(action("list-add-symbolic", "win.new-layer", tr("Neue Ebene") + " (Strg+Umschalt+N)").also { acceptDrop(it) { ids -> act.duplicateLayers(ids) } })
        bar.append(action("edit-copy-symbolic", "win.duplicate-layer", tr("Ebene duplizieren") + " (Strg+J)").also { acceptDrop(it) { ids -> act.duplicateLayers(ids) } })
        bar.append(action("user-trash-symbolic", "win.delete-layer", tr("Ebene löschen")).also { acceptDrop(it) { ids -> act.deleteLayers(ids) } })
        widget.append(org.gnome.gtk.Separator(Orientation.HORIZONTAL))
        widget.append(bar)
    }

    private fun acceptDrop(w: Widget, f: (List<Int>) -> Unit) {
        val dt = DropTarget(Types.STRING, DragAction.MOVE)
        dt.onDrop { _, _, _ -> val ids = dragging; if (ids.isEmpty()) false else { f(ids); true } }
        w.addController(dt)
    }

    private fun contextMenu() = org.gnome.gio.Menu().apply {
        append(tr("Ebeneneigenschaften …"), "win.layer-props")
        append(tr("Ebenenstil") + " …", "win.layer-style")
        append(tr("Ebene duplizieren"), "win.duplicate-layer")
        append(tr("Ebene löschen"), "win.delete-layer")
        append(tr("Ebenen gruppieren"), "win.group-layers")
        append(if (selected.size > 1) tr("Ebenen zusammenfügen") else tr("Auf darunterliegende Ebene reduzieren"), "win.merge-down")
        appendSection(null, org.gnome.gio.Menu().apply {
            append(tr("Ebenenmaske hinzufügen"), "win.add-mask")
            append(tr("Maske aktivieren/deaktivieren"), "win.toggle-mask")
            append(tr("Maske anwenden"), "win.apply-mask")
            append(tr("Maske löschen"), "win.delete-mask")
        })
        appendSection(null, org.gnome.gio.Menu().apply {
            append(tr("Schnittmaske erstellen/lösen"), "win.toggle-clip")
            append(tr("In Smartobjekt umwandeln"), "win.smart")
            append(tr("Rastern"), "win.rasterize")
        })
    }

    fun setTarget(t: PaintTarget) {
        if (t == target) return
        target = t
        refreshRows()
    }

    /** Zeilen, für die die Liste zuletzt aufgebaut wurde (ohne die Markierung der aktiven Ebene). */
    private var built: List<LayerRow> = emptyList()

    fun update(state: DocState?, thumbs: Map<Int, Texture>) {
        this.thumbs = thumbs
        rows = state?.layers ?: emptyList()
        val ids = rows.map { it.id }.toSet()
        val active = rows.firstOrNull { it.active }?.id
        // Auswahl halten, solange die aktive Ebene dazugehört; sonst nur die aktive
        selected = if (active != null && active in selected) selected.filter { it in ids }.toSet() else setOfNotNull(active)
        // Nur Auswahl/aktive Ebene geändert: Zeilen behalten. Ein Neuaufbau mitten im Klicken verliert
        // sonst den Ankerpunkt für Umschalt+Klick und lässt Klicks auf schon ersetzte Zeilen fallen.
        if (!renaming) {
            if (built.isNotEmpty() && rows.map { it.copy(active = false) } == built) refreshRows() else rebuild()
        }
        updating = true
        try {
            val a = state?.active
            for (w in listOf<Widget>(opacity, blend, fill, lock)) w.sensitive = a != null
            if (a != null) {
                opacity.value = (a.opacity * 100.0)
                fill.value = (a.fillOpacity * 100.0)
                lock.active = a.lockTransparency
                blendModes.indexOf(a.blend).takeIf { it >= 0 }?.let { blend.selected = it }
            }
        } finally { updating = false }
    }

    private fun rebuild() {
        updating = true
        try {
            list.removeAll()
            for (r in rows) list.append(rowWidget(r))
            for ((i, r) in rows.withIndex()) if (r.id in selected) list.selectRow(list.getRowAtIndex(i))
            built = rows.map { it.copy(active = false) }
        } finally { updating = false }
    }

    /** Vorhandene Zeilen an Auswahl, aktive Ebene und Malziel anpassen, ohne sie neu anzulegen. */
    private fun refreshRows() {
        updating = true
        try {
            for ((i, r) in rows.withIndex()) {
                val row = list.getRowAtIndex(i) ?: continue
                val want = r.id in selected
                if (row.isSelected != want) { if (want) list.selectRow(row) else list.unselectRow(row) }
                val maskTarget = r.active && target == PaintTarget.MASK && r.hasMask
                walk(row) { w ->
                    if (!w.hasCssClass("thumb-frame")) return@walk
                    val img = w.firstChild
                    val on = when {
                        img?.hasCssClass("mask-thumb") == true -> maskTarget
                        img?.hasCssClass("layer-thumb") == true -> r.active && !maskTarget && r.hasMask
                        else -> false
                    }
                    if (on) w.addCssClass("thumb-selected") else w.removeCssClass("thumb-selected")
                }
            }
        } finally { updating = false }
    }

    /** Nur die Vorschaubilder austauschen (ohne die Liste neu aufzubauen). */
    fun updateThumbs(thumbs: Map<Int, Texture>) {
        this.thumbs = thumbs
        for ((i, r) in rows.withIndex()) {
            val row = list.getRowAtIndex(i) ?: continue
            walk(row) { w ->
                val img = w as? Image ?: return@walk
                if (img.hasCssClass("layer-thumb")) thumbs[r.id]?.let { img.setFromPaintable(it) }
                if (img.hasCssClass("mask-thumb")) thumbs[-r.id]?.let { img.setFromPaintable(it) }
            }
        }
    }

    private fun walk(w: Widget, f: (Widget) -> Unit) {
        f(w)
        var c = w.firstChild
        while (c != null) { walk(c, f); c = c.nextSibling }
    }

    private fun mods(g: GestureClick): Set<ModifierType> = g.currentEventState

    private fun rowWidget(r: LayerRow): ListBoxRow {
        val box = Box(Orientation.HORIZONTAL, 4)
        box.marginTop = 1; box.marginBottom = 1
        // Auge: Klick blendet ein/aus, Alt+Klick zeigt nur diese Ebene
        val eye = Button().apply {
            iconName = if (r.visible) "view-reveal-symbolic" else "pg-hidden-symbolic"
            addCssClass("flat"); addCssClass("eye")
            valign = Align.CENTER
            focusOnClick = false
            tooltipText = tr("Sichtbarkeit (Alt+Klick: nur diese Ebene)")
        }
        val eyeClick = GestureClick().apply { button = 1; propagationPhase = PropagationPhase.CAPTURE }
        eyeClick.onReleased { _, _, _ ->
            if (ModifierType.ALT_MASK in mods(eyeClick)) act.solo(r.id) else act.setVisible(r.id, !r.visible)
            eyeClick.setState(org.gnome.gtk.EventSequenceState.CLAIMED)
        }
        eye.addController(eyeClick)
        box.append(eye)
        box.append(Box(Orientation.HORIZONTAL, 0).apply { setSizeRequest(r.depth * 14, -1) })
        if (r.kind == LayerRow.Kind.GROUP) {
            val tri = Button.fromIconName(if (r.expanded) "pan-down-symbolic" else "pan-end-symbolic").apply {
                addCssClass("flat"); addCssClass("disclosure"); valign = Align.CENTER; focusOnClick = false
                tooltipText = if (r.expanded) tr("Gruppe zuklappen") else tr("Gruppe aufklappen")
                onClicked { act.toggleExpanded(r.id) }
            }
            box.append(tri)
        }
        if (r.clipped) box.append(Image.fromIconName("pg-clip-symbolic").apply { addCssClass("dim-label"); tooltipText = tr("Schnittmaske") })
        val maskTarget = r.active && target == PaintTarget.MASK && r.hasMask
        when (r.kind) {
            LayerRow.Kind.GROUP -> box.append(Image.fromIconName("folder-symbolic").apply { pixelSize = 20; setSizeRequest(THUMB, THUMB) })
            LayerRow.Kind.ADJUSTMENT -> box.append(thumbIcon(r, "pg-adjust-symbolic"))
            else -> box.append(thumb(r, "layer-thumb", thumbs[r.id], r.active && !maskTarget && r.hasMask, mask = false))
        }
        if (r.hasMask) box.append(thumb(r, "mask-thumb", thumbs[-r.id], maskTarget, mask = true).apply {
            tooltipText = tr("Ebenenmaske – Klick: auf der Maske malen · Umschalt+Klick: ein/aus · Strg+Klick: als Auswahl laden")
            if (!r.maskEnabled) opacity = 0.4
        })
        box.append(nameWidget(r))
        if (r.kind == LayerRow.Kind.TEXT) box.append(Image.fromIconName("pg-text-symbolic").apply { addCssClass("dim-label") })
        if (r.kind == LayerRow.Kind.SMART) box.append(Image.fromIconName("pg-smart-symbolic").apply { addCssClass("dim-label"); tooltipText = tr("Smartobjekt") })
        if (r.lockTransparency) box.append(Image.fromIconName("pg-lock-alpha-symbolic").apply { addCssClass("dim-label"); tooltipText = tr("Transparente Pixel fixiert") })
        val row = ListBoxRow().apply { child = box }
        if (!r.visible) row.addCssClass("hidden-layer")
        dnd(row, r)
        return row
    }

    /** Name; Doppelklick schaltet auf ein Eingabefeld um (Eingabe übernimmt, Esc bricht ab). */
    private fun nameWidget(r: LayerRow): Widget {
        val stack = org.gnome.gtk.Stack().apply { hexpand = true; hhomogeneous = false }
        val label = Label(r.name).apply { xalign = 0f; ellipsize = EllipsizeMode.END; if (!r.visible) addCssClass("dim-label") }
        val entry = Entry().apply { text = r.name; valign = Align.CENTER }
        stack.addNamed(label, "label"); stack.addNamed(entry, "entry")
        var done = false
        fun finish(save: Boolean) {
            if (done) return
            done = true; renaming = false
            if (save && entry.text.isNotBlank() && entry.text != r.name) act.rename(r.id, entry.text) else stack.visibleChildName = "label"
        }
        val dbl = GestureClick().apply { button = 1 }
        dbl.onPressed { n, _, _ ->
            if (n == 2) {
                done = false; renaming = true
                stack.visibleChildName = "entry"
                entry.grabFocus()
                entry.selectRegion(0, -1)
            }
        }
        label.addController(dbl)
        entry.onActivate { finish(true) }
        val keys = EventControllerKey()
        keys.onKeyPressed { k, _, _ -> if (k == org.gnome.gdk.Gdk.KEY_Escape) { finish(false); true } else false }
        entry.addController(keys)
        val focus = org.gnome.gtk.EventControllerFocus()
        focus.onLeave { if (stack.visibleChildName == "entry") finish(true) }
        entry.addController(focus)
        return stack
    }

    /** Miniaturbild: Klick wählt das Malziel, Doppelklick öffnet Eigenschaften, Strg+Klick lädt die Auswahl. */
    private fun thumb(r: LayerRow, cls: String, tex: Texture?, selectedTarget: Boolean, mask: Boolean): Widget {
        val frame = Box(Orientation.HORIZONTAL, 0).apply {
            addCssClass("thumb-frame")
            if (selectedTarget) addCssClass("thumb-selected")
            valign = Align.CENTER
        }
        val img = Image().apply {
            pixelSize = THUMB
            addCssClass(cls)
            if (tex != null) setFromPaintable(tex)
        }
        frame.append(img)
        val click = GestureClick().apply { button = 1 }
        click.onPressed { n, _, _ ->
            val m = mods(click)
            when {
                ModifierType.CONTROL_MASK in m -> {
                    val op = when { ModifierType.SHIFT_MASK in m -> SelectionOp.ADD; ModifierType.ALT_MASK in m -> SelectionOp.SUBTRACT; else -> SelectionOp.REPLACE }
                    act.loadSelection(r.id, mask, op)
                    click.setState(org.gnome.gtk.EventSequenceState.CLAIMED)
                }
                mask && ModifierType.SHIFT_MASK in m -> { act.toggleMaskEnabled(r.id); click.setState(org.gnome.gtk.EventSequenceState.CLAIMED) }
                n == 2 && !mask -> act.open(r)
                n == 1 -> act.target(r.id, if (mask) PaintTarget.MASK else PaintTarget.PIXELS)
            }
        }
        frame.addController(click)
        return frame
    }

    private fun thumbIcon(r: LayerRow, icon: String): Widget {
        val img = Image.fromIconName(icon).apply { pixelSize = 20; setSizeRequest(THUMB, THUMB); tooltipText = tr("Doppelklick: Einstellungen") }
        val click = GestureClick().apply { button = 1 }
        click.onPressed { n, _, _ -> if (n == 2) act.open(r) }
        img.addController(click)
        return img
    }

    // ------------------------------------------------------------ Ziehen und Ablegen

    /** Ablageziel relativ zu einer Zeile: darüber, darunter oder (bei Gruppen) hinein. */
    private enum class Spot { ABOVE, BELOW, INTO }

    private fun spot(r: LayerRow, y: Double, h: Int): Spot = when {
        r.kind == LayerRow.Kind.GROUP && y > h * 0.3 && y < h * 0.7 -> Spot.INTO
        y < h / 2.0 -> Spot.ABOVE
        else -> Spot.BELOW
    }

    private fun dnd(row: ListBoxRow, r: LayerRow) {
        val src = DragSource()
        src.setActions(DragAction.MOVE)
        src.onPrepare { _, _ ->
            dragging = if (r.id in selected) rows.filter { it.id in selected }.map { it.id } else listOf(r.id)
            org.gnome.gdk.ContentProvider.typed(Types.STRING, dragging.joinToString(","))
        }
        src.onDragBegin { _ -> src.setIcon(org.gnome.gtk.WidgetPaintable(row), 20, 16) }
        src.onDragEnd { _, _ -> dragging = emptyList() }
        row.addController(src)

        val dst = DropTarget(Types.STRING, DragAction.MOVE)
        fun mark(s: Spot?) {
            for (c in listOf("drop-above", "drop-below", "drop-into")) row.removeCssClass(c)
            when (s) { Spot.ABOVE -> row.addCssClass("drop-above"); Spot.BELOW -> row.addCssClass("drop-below"); Spot.INTO -> row.addCssClass("drop-into"); null -> {} }
        }
        dst.onMotion { _, y -> mark(spot(r, y, row.height)); setOf(DragAction.MOVE) }
        dst.onLeave { mark(null) }
        dst.onDrop { _, _, y ->
            mark(null)
            val ids = dragging.ifEmpty { return@onDrop false }
            if (r.id in ids) return@onDrop false
            when (spot(r, y, row.height)) {
                Spot.INTO -> act.drop(ids, r.id, r.childCount)
                Spot.ABOVE -> act.drop(ids, r.parentId, r.index + 1)
                // Unter einer aufgeklappten Gruppe: als oberste Ebene in die Gruppe
                Spot.BELOW -> if (r.kind == LayerRow.Kind.GROUP && r.expanded) act.drop(ids, r.id, r.childCount) else act.drop(ids, r.parentId, r.index)
            }
            true
        }
        row.addController(dst)
    }

    companion object { const val THUMB = 36 }
}
