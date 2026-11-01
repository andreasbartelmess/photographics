package de.photographics.gnome

import de.photographics.core.tr
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.Label
import org.gnome.gtk.ListBox
import org.gnome.gtk.ListBoxRow
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SelectionMode

/** Liste mit Befehlsknöpfen darunter (gemeinsamer Aufbau von Pfade- und Kanäle-Bedienfeld). */
private fun panel(list: ListBox, buttons: List<Button>): Box {
    val box = Box(Orientation.VERTICAL, 0)
    list.selectionMode = SelectionMode.BROWSE
    list.addCssClass("navigation-sidebar")
    box.append(ScrolledWindow().apply { setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC); vexpand = true; marginStart = 6; marginEnd = 6; marginTop = 4; child = list })
    box.append(org.gnome.gtk.Separator(Orientation.HORIZONTAL))
    box.append(org.gnome.gtk.FlowBox().apply {
        selectionMode = SelectionMode.NONE; marginStart = 12; marginEnd = 12; marginTop = 6; marginBottom = 6
        columnSpacing = 4; rowSpacing = 4; maxChildrenPerLine = 3; homogeneous = true
        for (b in buttons) append(b.apply { addCssClass("flat") })
    })
    return box
}

private fun row(text: String, italic: Boolean = false, dot: Int? = null) = ListBoxRow().apply {
    child = Box(Orientation.HORIZONTAL, 8).apply {
        marginStart = 8; marginTop = 6; marginBottom = 6
        if (dot != null) append(ColorSquare(dot or 0xFF000000.toInt()).apply { setSizeRequest(12, 12) })
        append(Label(text).apply { xalign = 0f; hexpand = true; ellipsize = org.gnome.pango.EllipsizeMode.END; if (italic) addCssClass("dim-label") })
    }
}

/**
 * Pfade-Bedienfeld (wie Photoshop): Arbeitspfad und gesicherte Pfade; Befehle Auswahl laden,
 * füllen, Kontur füllen, Arbeitspfad aus Auswahl, speichern, löschen. Doppelklick: umbenennen.
 */
class PathsPanel(private val c: Controller, private val window: () -> org.gnome.gtk.Window) {
    private val list = ListBox()
    private var entries: List<Int> = emptyList()
    private var updating = false
    private var lastState: DocState? = null

    val widget = panel(list, listOf(
        Button.withLabel(tr("Als Auswahl")).apply { tooltipText = tr("Pfad als Auswahl laden (Strg+Eingabe)"); onClicked { withPath { p -> c.edit(tr("Pfad als Auswahl laden")) { it.selectionFromPath(p); de.photographics.core.image.IntRect.EMPTY } } } },
        Button.withLabel(tr("Füllen")).apply { tooltipText = tr("Pfad mit der Vordergrundfarbe füllen"); onClicked { val col = c.opts.color; withPath { p -> c.edit(tr("Pfad füllen")) { it.fillPath(p, col) } } } },
        Button.withLabel(tr("Kontur")).apply { tooltipText = tr("Pfadkontur mit Vordergrundfarbe und Pinselgröße füllen"); onClicked { val col = c.opts.color; val w = c.opts.brush.size; withPath { p -> c.edit(tr("Pfadkontur füllen")) { it.strokePath(p, col, w) } } } },
        Button.withLabel(tr("Aus Auswahl")).apply { tooltipText = tr("Arbeitspfad aus der Auswahl erstellen"); onClicked { c.selectedPath = -1; c.edit(tr("Arbeitspfad erstellen")) { it.pathFromSelection(); de.photographics.core.image.IntRect.EMPTY } } },
        Button.withLabel(tr("Speichern")).apply { tooltipText = tr("Arbeitspfad als Pfad speichern"); onClicked { saveWork() } },
        Button.withLabel(tr("Löschen")).apply {
            onClicked {
                val sel = c.selectedPath
                c.edit(tr("Pfad löschen")) { e -> if (sel >= 0) e.setPaths(e.doc.paths.filterIndexed { i, _ -> i != sel }, tr("Pfad löschen")) else e.setWorkPath(null, tr("Arbeitspfad löschen")); de.photographics.core.image.IntRect.EMPTY }
                c.selectedPath = -1
            }
        },
    ))

    init {
        list.onRowSelected { r -> if (!updating && r != null) c.selectedPath = entries.getOrElse(r.index) { -1 } }
        list.onRowActivated { r -> if (r == null) return@onRowActivated; val i = entries.getOrElse(r.index) { -1 }; if (i < 0) saveWork() else rename(i) }
        list.activateOnSingleClick = false
    }

    /** Gewählter Pfad (Arbeitspfad oder gesicherter). */
    private fun withPath(f: (de.photographics.core.vector.VectorPath) -> Unit) {
        val st = lastState ?: return
        val p = if (c.selectedPath >= 0) st.paths.getOrNull(c.selectedPath)?.path else st.workPath
        if (p == null) c.toastError(tr("Kein Pfad vorhanden.")) else f(p)
    }

    private fun saveWork() {
        if (lastState?.workPath == null) return
        ask(tr("Pfad speichern"), tr("Pfad {0}", (lastState?.paths?.size ?: 0) + 1)) { n -> c.edit(tr("Pfad speichern")) { it.saveWorkPath(n); de.photographics.core.image.IntRect.EMPTY } }
    }

    private fun rename(i: Int) {
        val old = lastState?.paths?.getOrNull(i)?.name ?: return
        ask(tr("Pfad umbenennen"), old) { n -> c.edit(tr("Pfad umbenennen")) { e -> e.setPaths(e.doc.paths.toMutableList().also { it[i] = it[i].copy(name = n) }, tr("Pfad umbenennen")); de.photographics.core.image.IntRect.EMPTY } }
    }

    private fun ask(title: String, initial: String, ok: (String) -> Unit) {
        val entry = org.gnome.gtk.Entry().apply { text = initial; activatesDefault = true }
        val d = org.gnome.adw.AlertDialog(title, null)
        d.extraChild = entry
        d.addResponse("cancel", tr("Abbrechen")); d.addResponse("ok", tr("OK"))
        d.setResponseAppearance("ok", org.gnome.adw.ResponseAppearance.SUGGESTED); d.defaultResponse = "ok"; d.closeResponse = "cancel"
        d.onResponse(null) { r -> val n = entry.text.trim(); if (r == "ok" && n.isNotEmpty()) ok(n) }
        d.present(window())
    }

    fun update(st: DocState) {
        val old = lastState
        lastState = st
        if (old != null && old.workPath == st.workPath && old.paths.map { it.name } == st.paths.map { it.name } && entries.contains(c.selectedPath)) return
        updating = true
        list.removeAll()
        val e = ArrayList<Int>()
        if (st.workPath != null) { list.append(row(tr("Arbeitspfad"), italic = true)); e += -1 }
        for ((i, p) in st.paths.withIndex()) { list.append(row(p.name)); e += i }
        if (e.isEmpty()) list.append(ListBoxRow().apply { activatable = false; selectable = false; child = Label(tr("Noch keine Pfade – Zeichenstift (P) oder Formwerkzeuge im Modus „Pfad“")).apply { wrap = true; maxWidthChars = 24; widthChars = 10; justify = org.gnome.gtk.Justification.CENTER; marginTop = 12; marginStart = 8; marginEnd = 8; addCssClass("dim-label") } })
        entries = e
        if (c.selectedPath !in e) c.selectedPath = e.firstOrNull() ?: -1
        e.indexOf(c.selectedPath).takeIf { it >= 0 }?.let { list.selectRow(list.getRowAtIndex(it)) }
        updating = false
    }
}

/**
 * Kanäle-Bedienfeld: RGB und die Farbkanäle einzeln ansehen (Graustufen), Alphakanäle (gespeicherte
 * Auswahlen) ansehen, als Auswahl laden, löschen. Doppelklick auf einen Alphakanal: umbenennen.
 */
class ChannelsPanel(private val c: Controller, private val canvas: CanvasView, private val window: () -> org.gnome.gtk.Window) {
    private val list = ListBox()
    private var names: List<String>? = null
    private var updating = false

    val widget = panel(list, listOf(
        Button.withLabel(tr("Auswahl speichern")).apply { tooltipText = tr("Auswahl als neuen Alphakanal speichern"); onClicked { c.edit(tr("Auswahl speichern")) { it.saveSelectionAsChannel(); de.photographics.core.image.IntRect.EMPTY } } },
        Button.withLabel(tr("Als Auswahl")).apply { tooltipText = tr("Alphakanal als Auswahl laden"); onClicked { val i = alphaIndex() ?: return@onClicked; c.edit(tr("Auswahl laden")) { it.selectionFromChannel(i); de.photographics.core.image.IntRect.EMPTY } } },
        Button.withLabel(tr("Löschen")).apply {
            onClicked {
                val i = alphaIndex() ?: return@onClicked
                canvas.channelView = 0
                c.edit(tr("Kanal löschen")) { e -> e.setChannels(e.doc.channels.filterIndexed { k, _ -> k != i }, tr("Kanal löschen")); de.photographics.core.image.IntRect.EMPTY }
            }
        },
    ))

    init {
        list.onRowSelected { r -> if (!updating && r != null) canvas.channelView = if (r.index < 4) r.index else CanvasView.ALPHA_VIEW + r.index - 4 }
        list.onRowActivated { r -> if (r != null && r.index >= 4) rename(r.index - 4) }
        list.activateOnSingleClick = false
    }

    private fun alphaIndex(): Int? = (canvas.channelView - CanvasView.ALPHA_VIEW).takeIf { canvas.channelView >= CanvasView.ALPHA_VIEW }
        ?: run { c.toastError(tr("Zuerst einen Alphakanal wählen.")); null }

    private fun rename(i: Int) {
        val old = names?.getOrNull(i) ?: return
        val entry = org.gnome.gtk.Entry().apply { text = old; activatesDefault = true }
        val d = org.gnome.adw.AlertDialog(tr("Kanal umbenennen"), null)
        d.extraChild = entry
        d.addResponse("cancel", tr("Abbrechen")); d.addResponse("ok", tr("OK"))
        d.setResponseAppearance("ok", org.gnome.adw.ResponseAppearance.SUGGESTED); d.defaultResponse = "ok"; d.closeResponse = "cancel"
        d.onResponse(null) { r ->
            val n = entry.text.trim()
            if (r == "ok" && n.isNotEmpty()) c.edit(tr("Kanal umbenennen")) { e -> e.setChannels(e.doc.channels.toMutableList().also { it[i] = it[i].copy(name = n) }, tr("Kanal umbenennen")); de.photographics.core.image.IntRect.EMPTY }
        }
        d.present(window())
    }

    fun update(st: DocState) {
        if (names == st.channels) return
        names = st.channels
        updating = true
        list.removeAll()
        list.append(row("RGB")); list.append(row(tr("Rot"), dot = 0xE03030)); list.append(row(tr("Grün"), dot = 0x30B040)); list.append(row(tr("Blau"), dot = 0x3060E0))
        for (n in st.channels) list.append(row(n, dot = 0x808080))
        if (canvas.channelView >= CanvasView.ALPHA_VIEW + st.channels.size) canvas.channelView = 0
        val sel = if (canvas.channelView >= CanvasView.ALPHA_VIEW) 4 + canvas.channelView - CanvasView.ALPHA_VIEW else canvas.channelView
        list.selectRow(list.getRowAtIndex(sel))
        updating = false
    }
}
