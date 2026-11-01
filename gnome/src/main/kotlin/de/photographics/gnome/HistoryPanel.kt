package de.photographics.gnome

import de.photographics.core.tr
import org.gnome.gtk.Label
import org.gnome.gtk.ListBox
import org.gnome.gtk.ListBoxRow
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SelectionMode

/** Protokoll: alle Schritte, die sich rückgängig machen lassen; Klick springt zu diesem Stand zurück. */
class HistoryPanel(private val undoSteps: (Int) -> Unit) {
    private val list = ListBox()
    val widget = ScrolledWindow().apply {
        setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
        vexpand = true
        marginStart = 6; marginEnd = 6; marginTop = 4
        child = list
    }
    private var entries: List<String> = emptyList()
    private var updating = false

    init {
        list.selectionMode = SelectionMode.BROWSE
        list.addCssClass("navigation-sidebar")
        list.onRowSelected { row ->
            if (updating || row == null) return@onRowSelected
            // Zeile 0 = Ausgangszustand, Zeile i = nach Schritt i
            val steps = entries.size - row.index
            if (steps > 0) undoSteps(steps)
        }
    }

    fun update(st: DocState) {
        if (st.history == entries) return
        entries = st.history
        updating = true
        try {
            list.removeAll()
            list.append(row(tr("Geöffnet")))
            for (e in entries) list.append(row(e))
            list.selectRow(list.getRowAtIndex(entries.size))
        } finally { updating = false }
    }

    private fun row(text: String) = ListBoxRow().apply {
        child = Label(text).apply { xalign = 0f; marginStart = 8; marginTop = 4; marginBottom = 4; ellipsize = org.gnome.pango.EllipsizeMode.END }
    }
}
