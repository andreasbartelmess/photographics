package de.photographics.core.editor

import de.photographics.core.doc.Document
import de.photographics.core.doc.Layer
import de.photographics.core.doc.RasterLayer
import de.photographics.core.image.IntRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ausrichten beim Verschieben (Photoshop: Ansicht → Ausrichten an, intelligente Hilfslinien):
 * Kanten und Mitte der bewegten Ebene rasten an Hilfslinien, Rand und Mitte der Arbeitsfläche
 * sowie an Kanten und Mitten anderer Ebenen ein. Die Treffer werden als Linien zurückgegeben.
 */
object Snap {
    /** Ziel: Lage und Ausdehnung quer dazu (für die angezeigte Linie). */
    class Target(val pos: Double, val from: Double, val to: Double)

    class Targets(val xs: List<Target>, val ys: List<Target>)

    /** Angezeigte Ausrichtungslinie (Dokumentkoordinaten). */
    data class Line(val vertical: Boolean, val pos: Double, val from: Double, val to: Double)

    class Result(val dx: Double, val dy: Double, val lines: List<Line>)

    /** Ziele im Dokument; [exclude] = die bewegte Ebene. Ebenenkanten werden einmal beim Start ermittelt. */
    fun targets(doc: Document, exclude: Layer?, layers: Boolean = true, guides: Boolean = true): Targets {
        val w = doc.width.toDouble(); val h = doc.height.toDouble()
        val xs = mutableListOf(Target(0.0, 0.0, h), Target(w / 2, 0.0, h), Target(w, 0.0, h))
        val ys = mutableListOf(Target(0.0, 0.0, w), Target(h / 2, 0.0, w), Target(h, 0.0, w))
        if (guides) for (g in doc.guides) (if (g.vertical) xs else ys) += Target(g.position, 0.0, if (g.vertical) h else w)
        if (layers) for (l in doc.allLayers()) {
            if (l === exclude || !l.visible || l !is RasterLayer) continue
            val b = l.raster.contentBounds().intersect(doc.bounds)
            if (b.isEmpty || b == doc.bounds) continue
            for (x in listOf(b.left, (b.left + b.right) / 2, b.right)) xs += Target(x.toDouble(), b.top.toDouble(), b.bottom.toDouble())
            for (y in listOf(b.top, (b.top + b.bottom) / 2, b.bottom)) ys += Target(y.toDouble(), b.left.toDouble(), b.right.toDouble())
        }
        return Targets(xs, ys)
    }

    /**
     * Rechteck [r] (bereits um den Zeigerweg verschoben) ausrichten: liefert die Korrektur und die
     * anzuzeigenden Linien. [tolerance] in Dokumentpixeln (typisch 8 Bildschirmpixel / Zoom).
     */
    fun snap(r: IntRect, t: Targets, tolerance: Double): Result {
        val (dx, lx) = axis(doubleArrayOf(r.left.toDouble(), (r.left + r.right) / 2.0, r.right.toDouble()), t.xs, tolerance)
        val (dy, ly) = axis(doubleArrayOf(r.top.toDouble(), (r.top + r.bottom) / 2.0, r.bottom.toDouble()), t.ys, tolerance)
        val lines = mutableListOf<Line>()
        // Linien über die Ausdehnung von Ziel und (korrigiertem) bewegtem Rechteck
        lx?.let { lines += Line(true, it.pos, min(it.from, r.top + dy), max(it.to, r.bottom + dy)) }
        ly?.let { lines += Line(false, it.pos, min(it.from, r.left + dx), max(it.to, r.right + dx)) }
        return Result(dx, dy, lines)
    }

    private fun axis(edges: DoubleArray, targets: List<Target>, tol: Double): Pair<Double, Target?> {
        var best: Target? = null; var bestD = Double.MAX_VALUE; var shift = 0.0
        for (e in edges) for (t in targets) {
            val d = t.pos - e
            if (abs(d) <= tol && abs(d) < abs(bestD)) { bestD = d; best = t; shift = d }
        }
        return if (best == null) 0.0 to null else shift to best
    }

    /** Einzelner Wert (z. B. eine gezogene Hilfslinie) an Rand, Mitte und Ebenenkanten. */
    fun snapValue(v: Double, targets: List<Target>, tolerance: Double): Double =
        targets.filter { abs(it.pos - v) <= tolerance }.minByOrNull { abs(it.pos - v) }?.pos ?: v
}
