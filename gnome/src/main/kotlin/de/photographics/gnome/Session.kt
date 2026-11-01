package de.photographics.gnome

import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer
import de.photographics.core.doc.SmartLayer
import de.photographics.core.doc.TextLayer
import de.photographics.core.editor.Editor
import de.photographics.core.render.BlendMode
import java.io.File

/** Ein geöffnetes Dokument. Alles außer [name]/[file] nur auf dem Engine-Thread anfassen. */
class Session(val editor: Editor, @Volatile var file: File?, @Volatile var name: String, val layered: Boolean) {
    val doc get() = editor.doc
    @Volatile var dirty = false
    /** Angehobene Auswahl (Verschieben/Transformieren): erscheint nicht als eigene Ebene. */
    var floating: de.photographics.core.editor.Floating? = null

    /** Zustand für die Oberfläche (unveränderlich, darf in den Hauptthread). */
    fun state(): DocState {
        val rows = ArrayList<LayerRow>()
        fun walk(g: GroupLayer, depth: Int) {
            val fl = floating
            for ((i, c) in g.children.withIndex().reversed()) {
                if (c === fl?.layer) continue
                val active = c === doc.activeLayer || (fl != null && c === fl.source && doc.activeLayer === fl.layer)
                rows += LayerRow(c.id, c.name, depth, c.visible, kindOf(c), c.opacity, c.blendMode, active, c.clipped, c.mask != null,
                    c.mask?.enabled ?: false, c.lockTransparency, c.fillOpacity, c.mask?.linked ?: true,
                    expanded = (c as? GroupLayer)?.expanded ?: false, parentId = if (g === doc.root) 0 else g.id, index = i,
                    childCount = (c as? GroupLayer)?.children?.size ?: 0)
                // Eingeklappte Gruppen zeigen ihre Ebenen nicht (wie Photoshop)
                if (c is GroupLayer && c.expanded) walk(c, depth + 1)
            }
        }
        walk(doc.root, 0)
        val h = editor.history
        val sel = doc.selection
        return DocState(doc.width, doc.height, rows, h.undoLabel, h.redoLabel, dirty,
            sel?.outlines ?: emptyList(), sel != null, doc.dpi, doc.colorProfile.description, h.entries, doc.guides,
            doc.workPath, doc.paths, (doc.activeLayer as? de.photographics.core.doc.ShapeLayer)?.docPath(), doc.channels.map { it.name }, doc.bitDepth)
    }

    private fun kindOf(l: Layer) = when (l) {
        is GroupLayer -> LayerRow.Kind.GROUP
        is AdjustmentLayer -> LayerRow.Kind.ADJUSTMENT
        is TextLayer -> LayerRow.Kind.TEXT
        is SmartLayer -> LayerRow.Kind.SMART
        is de.photographics.core.doc.ShapeLayer -> LayerRow.Kind.SHAPE
        else -> LayerRow.Kind.PIXEL
    }
}

data class DocState(
    val width: Int, val height: Int, val layers: List<LayerRow>,
    val undoLabel: String?, val redoLabel: String?, val dirty: Boolean,
    /** Umrisse der Auswahl in Dokumentkoordinaten (für die laufenden Ameisen). */
    val selectionOutlines: List<FloatArray> = emptyList(),
    val hasSelection: Boolean = false,
    val dpi: Float = 72f,
    val profileName: String = "",
    /** Verlauf (älteste zuerst). */
    val history: List<String> = emptyList(),
    val guides: List<de.photographics.core.doc.Guide> = emptyList(),
    val workPath: de.photographics.core.vector.VectorPath? = null,
    val paths: List<de.photographics.core.vector.NamedPath> = emptyList(),
    /** Pfad der aktiven Formebene (Dokumentkoordinaten). */
    val shapePath: de.photographics.core.vector.VectorPath? = null,
    val channels: List<String> = emptyList(),
    val bitDepth: Int = 8,
) {
    val canUndo get() = undoLabel != null
    val canRedo get() = redoLabel != null
    val active: LayerRow? get() = layers.firstOrNull { it.active }
}

/** Eine Zeile der Ebenenliste (oben = oberste Ebene), [depth] = Verschachtelung in Gruppen. */
data class LayerRow(
    val id: Int, val name: String, val depth: Int, val visible: Boolean, val kind: Kind,
    val opacity: Float, val blend: BlendMode, val active: Boolean, val clipped: Boolean, val hasMask: Boolean,
    val maskEnabled: Boolean = false, val lockTransparency: Boolean = false, val fillOpacity: Float = 1f, val maskLinked: Boolean = true,
    /** Gruppe aufgeklappt; [parentId] 0 = oberste Ebene; [index] in den Kindern des Elternteils (unten = 0). */
    val expanded: Boolean = false, val parentId: Int = 0, val index: Int = 0, val childCount: Int = 0,
) {
    enum class Kind { PIXEL, GROUP, ADJUSTMENT, TEXT, SMART, SHAPE }
}
