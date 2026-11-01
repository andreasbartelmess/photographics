package de.photographics.gnome

import org.gnome.gdk.RGBA
import org.gnome.graphene.Point
import org.gnome.graphene.Rect
import org.gnome.gsk.PathBuilder
import org.gnome.gsk.Stroke
import org.gnome.gtk.Snapshot

/** Zeichenhilfen für Überlagerungen auf der Zeichenfläche (Koordinaten in Widget-Pixeln). */
object Draw {
    val WHITE = RGBA(1f, 1f, 1f, 1f)
    val BLACK = RGBA(0f, 0f, 0f, 1f)
    val SHADE = RGBA(0f, 0f, 0f, 0.5f)
    val ACCENT = RGBA(0.21f, 0.52f, 0.89f, 1f)

    /** Linienzug [pts] (x0, y0, x1, y1, …); [closed] verbindet das Ende mit dem Anfang. */
    fun path(pts: FloatArray, closed: Boolean): org.gnome.gsk.Path? {
        if (pts.size < 4) return null
        val b = PathBuilder()
        b.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) { b.lineTo(pts[i], pts[i + 1]); i += 2 }
        if (closed) b.close()
        return b.toPath()
    }

    /** Schwarz-weiß gestrichelt („laufende Ameisen“), [phase] verschiebt das Muster. */
    fun ants(s: Snapshot, p: org.gnome.gsk.Path, phase: Float = 0f) {
        s.appendStroke(p, Stroke(1f), WHITE)
        val dash = Stroke(1f).apply { setDash(floatArrayOf(4f, 4f)); dashOffset = phase }
        s.appendStroke(p, dash, BLACK)
    }

    /** Gut sichtbare Linie auf jedem Untergrund: dunkler Rand, helle Mitte. */
    fun line(s: Snapshot, p: org.gnome.gsk.Path, width: Float = 1f) {
        s.appendStroke(p, Stroke(width + 2f), RGBA(0f, 0f, 0f, 0.6f))
        s.appendStroke(p, Stroke(width), WHITE)
    }

    fun circle(s: Snapshot, cx: Float, cy: Float, r: Float) {
        val b = PathBuilder()
        b.addCircle(Point().init(cx, cy), maxOf(0.5f, r))
        val p = b.toPath()
        s.appendStroke(p, Stroke(3f), RGBA(0f, 0f, 0f, 0.45f))
        s.appendStroke(p, Stroke(1f), WHITE)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float) = Rect().init(x, y, w, h)

    /** Quadratischer Anfasser um ([x], [y]). */
    fun handle(s: Snapshot, x: Float, y: Float, size: Float = 9f) {
        val h = size / 2
        s.appendColor(RGBA(0f, 0f, 0f, 0.6f), rect(x - h - 1, y - h - 1, size + 2, size + 2))
        s.appendColor(WHITE, rect(x - h, y - h, size, size))
    }

    /** Kurzer Text mit dunklem Hintergrund, mittig über ([cx], [cy]). */
    fun label(s: Snapshot, cx: Float, cy: Float, layout: org.gnome.pango.Layout) {
        val w = org.javagi.base.Out(0); val h = org.javagi.base.Out(0)
        layout.getPixelSize(w, h)
        val tw = (w.get() ?: 0).toFloat(); val th = (h.get() ?: 0).toFloat()
        val x = cx - tw / 2; val y = cy - th / 2
        val bg = org.gnome.gsk.RoundedRect().init(rect(x - 8, y - 4, tw + 16, th + 8), org.gnome.graphene.Size().init(6f, 6f), org.gnome.graphene.Size().init(6f, 6f), org.gnome.graphene.Size().init(6f, 6f), org.gnome.graphene.Size().init(6f, 6f))
        s.pushRoundedClip(bg)
        s.appendColor(RGBA(0f, 0f, 0f, 0.7f), rect(x - 8, y - 4, tw + 16, th + 8))
        s.pop()
        s.save()
        s.translate(Point().init(x, y))
        s.appendLayout(layout, WHITE)
        s.restore()
    }

    fun rectPath(x0: Float, y0: Float, x1: Float, y1: Float) = path(floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), true)!!
}
