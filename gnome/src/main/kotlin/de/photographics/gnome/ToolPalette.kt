package de.photographics.gnome

import de.photographics.core.tr
import org.gnome.gdk.RGBA
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Image
import org.gnome.gtk.Orientation
import org.gnome.gtk.Popover
import org.gnome.gtk.PositionType
import org.gnome.gtk.Separator
import org.gnome.gtk.Snapshot
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.Widget
import java.lang.foreign.MemorySegment

/**
 * Werkzeugleiste links: Farbfelder und ein Knopf je Werkzeuggruppe. Ein Klick wählt das zuletzt
 * benutzte Werkzeug der Gruppe; ein weiterer Klick (oder Rechtsklick) zeigt alle Werkzeuge der Gruppe.
 */
class ToolPalette(
    private val onTool: (Tool) -> Unit,
    private val onColor: (background: Boolean) -> Unit,
    private val onSwap: () -> Unit,
    private val onReset: () -> Unit,
) {
    val widget = Box(Orientation.VERTICAL, 2)
    private val buttons = LinkedHashMap<ToolGroup, ToggleButton>()
    private val last = HashMap<ToolGroup, Tool>()
    private val swatches = ColorSwatches()
    private var current = Tool.BRUSH
    private var updating = false

    init {
        widget.addCssClass("tool-palette")
        widget.marginTop = 8; widget.marginBottom = 8; widget.marginStart = 6; widget.marginEnd = 6
        swatches.onPick = { bg -> onColor(bg) }
        swatches.onSwap = onSwap
        swatches.onReset = onReset
        widget.append(swatches)
        widget.append(Separator(Orientation.HORIZONTAL).apply { marginTop = 4; marginBottom = 4 })
        for (g in ToolGroup.entries) {
            val b = ToggleButton()
            b.addCssClass("flat")
            b.focusOnClick = false
            b.child = Image.fromIconName(g.tools.first().icon)
            b.onClicked {
                if (updating) return@onClicked
                val t = last[g] ?: g.tools.first()
                if (current.group == g && g.tools.size > 1) { b.active = true; showGroup(g, b) } else onTool(t)
            }
            val rc = GestureClick().apply { button = 3 }
            rc.onPressed { _, _, _ -> if (g.tools.size > 1) showGroup(g, b) }
            b.addController(rc)
            buttons[g] = b
            widget.append(b)
            if (g == ToolGroup.CROP || g == ToolGroup.PICK || g == ToolGroup.LIGHT) widget.append(Separator(Orientation.HORIZONTAL).apply { marginTop = 2; marginBottom = 2 })
        }
        update(ToolOptions())
    }

    private fun tip(t: Tool) = t.label + (t.key?.let { " (${it.uppercaseChar()})" } ?: "")

    private fun showGroup(g: ToolGroup, anchor: Widget) {
        val pop = Popover()
        pop.position = PositionType.RIGHT
        val box = Box(Orientation.VERTICAL, 0)
        for (t in g.tools) {
            val row = Box(Orientation.HORIZONTAL, 8)
            row.append(Image.fromIconName(t.icon))
            row.append(org.gnome.gtk.Label(tip(t)).apply { xalign = 0f })
            box.append(Button().apply {
                child = row; addCssClass("flat")
                onClicked { pop.popdown(); onTool(t) }
            })
        }
        pop.child = box
        pop.setParent(anchor)
        pop.onClosed { pop.unparent() }
        pop.popup()
    }

    fun update(o: ToolOptions) {
        updating = true
        current = o.tool
        last[o.tool.group] = o.tool
        for ((g, b) in buttons) {
            val t = last[g] ?: g.tools.first()
            (b.child as? Image)?.setFromIconName(t.icon)
            b.tooltipText = if (g.tools.size > 1) tip(t) + " – " + tr("weitere: Rechtsklick") else tip(t)
            b.active = g == o.tool.group
        }
        swatches.set(o.color, o.bgColor)
        updating = false
    }
}

/** Vorder- und Hintergrundfarbe wie in Photoshop, mit Tauschen (X) und Standard (D). */
class ColorSwatches : Widget {
    constructor() : super() { init() }
    constructor(address: MemorySegment) : super(address)

    var onPick: (Boolean) -> Unit = {}
    var onSwap: () -> Unit = {}
    var onReset: () -> Unit = {}
    private var fg = 0xFF000000.toInt()
    private var bg = 0xFFFFFFFF.toInt()

    private fun init() {
        setSizeRequest(40, 44)
        halign = Align.CENTER
        tooltipText = tr("Vordergrund-/Hintergrundfarbe (X: tauschen, D: Standard)")
        val click = GestureClick()
        click.onReleased { _, x, y ->
            when {
                x > 28 && y < 14 -> onSwap()
                x < 12 && y > 30 -> onReset()
                x < 26 && y < 28 -> onPick(false)
                else -> onPick(true)
            }
        }
        addController(click)
    }

    fun set(f: Int, b: Int) { fg = f; bg = b; queueDraw() }

    private fun rgba(c: Int) = RGBA(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, 1f)

    override fun snapshot(snapshot: Snapshot) = guarded { draw(snapshot) }

    private fun draw(snapshot: Snapshot) {
        val edge = RGBA(0.5f, 0.5f, 0.5f, 1f)
        fun square(x: Float, y: Float, s: Float, c: Int) {
            snapshot.appendColor(edge, Draw.rect(x - 1, y - 1, s + 2, s + 2))
            snapshot.appendColor(rgba(c), Draw.rect(x, y, s, s))
        }
        square(14f, 16f, 22f, bg)
        square(2f, 2f, 22f, fg)
        // Tauschen (Pfeil oben rechts) und Standard (kleine Felder unten links)
        val fgc = (cssColor())
        Draw.path(floatArrayOf(29f, 4f, 36f, 4f, 36f, 11f), false)?.let { snapshot.appendStroke(it, org.gnome.gsk.Stroke(1.5f), fgc) }
        snapshot.appendColor(RGBA(1f, 1f, 1f, 1f), Draw.rect(5f, 35f, 6f, 6f))
        snapshot.appendColor(RGBA(0f, 0f, 0f, 1f), Draw.rect(2f, 32f, 6f, 6f))
    }

    private fun cssColor(): RGBA = RGBA().also { getColor(it) }
}
