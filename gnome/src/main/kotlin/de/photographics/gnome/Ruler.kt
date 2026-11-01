package de.photographics.gnome

import org.gnome.gdk.RGBA
import org.gnome.graphene.Point
import org.gnome.gtk.GestureDrag
import org.gnome.gtk.Snapshot
import org.gnome.gtk.Widget
import java.lang.foreign.MemorySegment
import kotlin.math.floor

/**
 * Lineal (oben waagerecht, links senkrecht) in Dokumentpixeln, passend zu Zoom und Lage der
 * Zeichenfläche. Aus dem Lineal gezogen entsteht eine Hilfslinie (wie Photoshop).
 */
class Ruler : Widget {
    constructor(vertical: Boolean, canvas: CanvasView) : super() { this.vertical = vertical; this.canvas = canvas; init() }
    constructor(address: MemorySegment) : super(address)

    private var vertical = false
    private lateinit var canvas: CanvasView
    /** Hilfslinie herausziehen: Lage in Dokumentkoordinaten (null = Abbruch, weil außerhalb). */
    var onGuideDrag: (phase: Int, pos: Double?) -> Unit = { _, _ -> }

    private fun init() {
        if (vertical) setSizeRequest(SIZE, -1) else setSizeRequest(-1, SIZE)
        val drag = GestureDrag()
        var sx = 0.0; var sy = 0.0
        drag.onDragBegin { x, y -> sx = x; sy = y; onGuideDrag(0, null) }
        drag.onDragUpdate { dx, dy -> onGuideDrag(1, docPos(sx + dx, sy + dy)) }
        drag.onDragEnd { dx, dy -> onGuideDrag(2, docPos(sx + dx, sy + dy)) }
        addController(drag)
    }

    /** Zeigerlage (in diesem Lineal) → Dokumentkoordinate quer zum Lineal; null, solange der Zeiger nicht über der Zeichenfläche ist. */
    private fun docPos(x: Double, y: Double): Double? {
        val p = Point()
        if (!computePoint(canvas, Point().init(x.toFloat(), y.toFloat()), p)) return null
        val cx = p.readX().toDouble(); val cy = p.readY().toDouble()
        val inside = if (vertical) cx >= 0 else cy >= 0
        if (!inside) return null
        return if (vertical) canvas.docX(cx) else canvas.docY(cy)
    }

    override fun snapshot(snapshot: Snapshot) = guarded {
        val fg = RGBA().also { getColor(it) }
        val w = width.toFloat(); val h = height.toFloat()
        snapshot.appendColor(RGBA(fg.readRed(), fg.readGreen(), fg.readBlue(), 0.06f), Draw.rect(0f, 0f, w, h))
        // Abstand der Beschriftung: mindestens 60 Bildschirmpixel
        val z = canvas.zoom
        if (z <= 0) return@guarded
        val steps = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 250.0, 500.0, 1000.0, 2000.0, 2500.0, 5000.0, 10000.0)
        val major = steps.firstOrNull { it * z >= 60 } ?: 10000.0
        val minor = major / (if (major.toString().startsWith("2") || major.toString().startsWith("5")) 5 else 10)
        val origin = Point()
        // Lage des Dokumentursprungs in diesem Lineal
        canvas.computePoint(this, Point().init(canvas.viewX(0.0), canvas.viewY(0.0)), origin)
        val o = if (vertical) origin.readY().toDouble() else origin.readX().toDouble()
        val len = if (vertical) h else w
        val first = floor((-o / z) / minor) * minor
        val tick = RGBA(fg.readRed(), fg.readGreen(), fg.readBlue(), 0.45f)
        var v = first
        while (o + v * z <= len) {
            val p = (o + v * z).toFloat()
            val isMajor = kotlin.math.abs(v / major - Math.round(v / major)) < 1e-6
            val t = if (isMajor) SIZE.toFloat() else SIZE * 0.3f
            if (vertical) snapshot.appendColor(tick, Draw.rect(w - t, p, t, 1f)) else snapshot.appendColor(tick, Draw.rect(p, h - t, 1f, t))
            if (isMajor) {
                val layout = createPangoLayout(if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v))
                layout.fontDescription = org.gnome.pango.FontDescription.fromString("Sans 7")
                snapshot.save()
                if (vertical) {
                    snapshot.translate(Point().init(2f, p + 2f))
                    snapshot.rotate(90f)
                    snapshot.translate(Point().init(0f, -SIZE + 4f))
                } else snapshot.translate(Point().init(p + 3f, 1f))
                snapshot.appendLayout(layout, fg)
                snapshot.restore()
            }
            v += minor
        }
        // Zeigerlage als feine Marke
        if (canvas.pointerInside) {
            val pp = Point()
            canvas.computePoint(this, Point().init(canvas.viewX(canvas.pointerDocX), canvas.viewY(canvas.pointerDocY)), pp)
            if (vertical) snapshot.appendColor(Draw.ACCENT, Draw.rect(0f, pp.readY(), w, 1f)) else snapshot.appendColor(Draw.ACCENT, Draw.rect(pp.readX(), 0f, 1f, h))
        }
        val edge = RGBA(fg.readRed(), fg.readGreen(), fg.readBlue(), 0.2f)
        if (vertical) snapshot.appendColor(edge, Draw.rect(w - 1, 0f, 1f, h)) else snapshot.appendColor(edge, Draw.rect(0f, h - 1, w, 1f))
    }

    companion object { const val SIZE = 18 }
}
