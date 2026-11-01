package de.photographics.gnome

import de.photographics.core.color.BuiltinPalettes
import de.photographics.core.color.Palette
import de.photographics.core.color.PaletteIO
import de.photographics.core.color.Swatch
import de.photographics.core.tr
import org.gnome.gdk.ModifierType
import org.gnome.gdk.RGBA
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.DropDown
import org.gnome.gtk.FlowBox
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SelectionMode
import org.gnome.gtk.Snapshot
import org.gnome.gtk.Widget
import java.io.File
import java.lang.foreign.MemorySegment

/**
 * Farbfelder wie in Photoshop: Klick = Vordergrundfarbe, Strg/Alt+Klick = Hintergrundfarbe.
 * Oben die zuletzt benutzten Farben; „+“ legt die Vordergrundfarbe in „Eigene“ ab.
 */
class SwatchesPanel(
    private val window: () -> org.gnome.gtk.Window,
    private val pick: (color: Int, background: Boolean) -> Unit,
    private val foreground: () -> Int,
) {
    val widget = Box(Orientation.VERTICAL, 6)
    private val recent = FlowBox()
    private val grid = FlowBox()
    private var palettes: List<Palette> = emptyList()
    private val chooser = DropDown.fromStrings(arrayOf())

    init {
        widget.marginStart = 12; widget.marginEnd = 12; widget.marginTop = 8
        widget.append(Label(tr("Zuletzt verwendet")).apply { xalign = 0f; addCssClass("dim-label"); addCssClass("caption") })
        for (fb in listOf(recent, grid)) {
            fb.selectionMode = SelectionMode.NONE
            fb.maxChildrenPerLine = 12; fb.minChildrenPerLine = 6
            fb.rowSpacing = 3; fb.columnSpacing = 3
            fb.homogeneous = true
            fb.valign = Align.START
        }
        widget.append(recent)
        val head = Box(Orientation.HORIZONTAL, 4)
        chooser.hexpand = true
        chooser.onNotify("selected") { _ -> palettes.getOrNull(chooser.selected)?.let { Settings.activePalette = it.name; showPalette(it) } }
        head.append(chooser)
        head.append(Button.fromIconName("list-add-symbolic").apply {
            tooltipText = tr("Vordergrundfarbe zu „Eigene“ hinzufügen"); addCssClass("flat")
            onClicked { addOwn(foreground()) }
        })
        head.append(Button.fromIconName("document-open-symbolic").apply {
            tooltipText = tr("Farbfelder importieren (ACO, ASE, GPL, ACT)"); addCssClass("flat")
            onClicked { import() }
        })
        widget.append(head)
        widget.append(ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            vexpand = true
            child = grid
        })
        widget.append(Label(tr("Klick: Vordergrund · Strg+Klick: Hintergrund · Rechtsklick auf eigene: entfernen")).apply {
            addCssClass("dim-label"); addCssClass("caption"); wrap = true; xalign = 0f; marginBottom = 12
        })
        reload()
        updateRecent()
    }

    private fun reload(select: String = Settings.activePalette) {
        val own = Settings.userPalettes()
        palettes = own + BuiltinPalettes.all.filter { b -> own.none { it.name == b.name } }
        if (palettes.none { it.name == OWN }) palettes = listOf(Palette(OWN, emptyList())) + palettes
        chooser.model = org.gnome.gtk.StringList(palettes.map { it.name }.toTypedArray())
        val i = palettes.indexOfFirst { it.name == select }.coerceAtLeast(0)
        chooser.selected = i
        showPalette(palettes[i])
    }

    private fun showPalette(p: Palette) {
        grid.removeAll()
        for ((idx, s) in p.swatches.withIndex()) grid.append(square(s.color, s.name) { if (p.name == OWN || Settings.userPalettes().any { it.name == p.name }) remove(p, idx) })
    }

    fun updateRecent() {
        recent.removeAll()
        for (c in Settings.recentColors.take(12)) recent.append(square(c, null, null))
    }

    /** Farbe merken (neueste zuerst). */
    fun used(c: Int) {
        val list = (listOf(c or 0xFF000000.toInt()) + Settings.recentColors.filter { it != (c or 0xFF000000.toInt()) }).take(24)
        if (list == Settings.recentColors) return
        Settings.recentColors = list
        updateRecent()
    }

    private fun square(color: Int, name: String?, onRemove: (() -> Unit)?): Widget {
        val sq = ColorSquare(color)
        sq.tooltipText = (name?.takeIf { it.isNotBlank() }?.let { tr(it) + " – " } ?: "") + "#%06X".format(color and 0xFFFFFF)
        val click = GestureClick().apply { button = 0 }
        click.onReleased { _, _, _ ->
            val m = click.currentEventState
            if (click.currentButton == 3) onRemove?.invoke()
            else pick(color, ModifierType.CONTROL_MASK in m || ModifierType.ALT_MASK in m)
        }
        sq.addController(click)
        return sq
    }

    private fun addOwn(c: Int) {
        val own = Settings.userPalettes().firstOrNull { it.name == OWN } ?: Palette(OWN, emptyList())
        if (own.swatches.any { it.color == c }) return
        Settings.savePalette(own.copy(swatches = own.swatches + Swatch(c or 0xFF000000.toInt())))
        reload(OWN)
    }

    private fun remove(p: Palette, index: Int) {
        Settings.savePalette(p.copy(swatches = p.swatches.filterIndexed { i, _ -> i != index }))
        reload(p.name)
    }

    private fun import() {
        val d = org.gnome.gtk.FileDialog()
        d.title = tr("Farbfelder importieren")
        val f = org.gnome.gtk.FileFilter().apply { name = tr("Farbfelder"); for (e in listOf("aco", "ase", "gpl", "act")) addSuffix(e) }
        d.filters = org.gnome.gio.ListStore<org.gnome.gtk.FileFilter>(org.gnome.gtk.FileFilter.getType()).apply { append(f) }
        d.open(window(), null) { res ->
            val file: File = runCatching { d.openFinish(res) }.getOrNull()?.local() ?: return@open
            runCatching { PaletteIO.read(file.readBytes(), file.name) }.onSuccess { p -> Settings.savePalette(p); reload(p.name) }
        }
    }

    companion object { val OWN = tr("Eigene") }
}

/** Farbquadrat für Farbfelder und zuletzt benutzte Farben. */
class ColorSquare : Widget {
    constructor(color: Int) : super() { this.color = color; setSizeRequest(20, 20); halign = Align.CENTER; valign = Align.CENTER; vexpand = false }
    constructor(address: MemorySegment) : super(address)

    var color = 0
        set(v) { field = v; queueDraw() }

    override fun snapshot(snapshot: Snapshot) = guarded {
        val w = width.toFloat(); val h = height.toFloat()
        snapshot.appendColor(RGBA(0.5f, 0.5f, 0.5f, 0.6f), Draw.rect(0f, 0f, w, h))
        val c = color
        snapshot.appendColor(RGBA(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, 1f), Draw.rect(1f, 1f, w - 2, h - 2))
    }
}
