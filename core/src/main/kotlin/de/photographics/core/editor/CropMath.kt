package de.photographics.core.editor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Geometrie fürs Freistellen und Gerade-Ausrichten. */
object CropMath {
    /**
     * Größtes achsparalleles Rechteck (Breite, Höhe) innerhalb eines um [angleDeg] gedrehten
     * Rechtecks [w]×[h] – der Ausschnitt ohne leere Ecken nach dem Gerade-Ausrichten.
     */
    fun maxInscribed(w: Double, h: Double, angleDeg: Double): Pair<Double, Double> {
        if (w <= 0 || h <= 0) return 0.0 to 0.0
        val a = Math.toRadians(angleDeg)
        val sinA = abs(sin(a)); val cosA = abs(cos(a))
        if (sinA < 1e-9) return w to h
        val widthLonger = w >= h
        val long = if (widthLonger) w else h; val short = if (widthLonger) h else w
        return if (short <= 2 * sinA * cosA * long || abs(sinA - cosA) < 1e-10) {
            val x = 0.5 * short
            if (widthLonger) x / sinA to x / cosA else x / cosA to x / sinA
        } else {
            val cos2a = cosA * cosA - sinA * sinA
            (w * cosA - h * sinA) / cos2a to (h * cosA - w * sinA) / cos2a
        }
    }

    /**
     * Winkel (Grad, im Uhrzeigersinn), um den das Bild gedreht werden muss, damit die Linie
     * ([x0], [y0])–([x1], [y1]) waagerecht – oder bei steilen Linien senkrecht – wird.
     */
    fun straightenAngle(x0: Double, y0: Double, x1: Double, y1: Double): Double {
        var deg = Math.toDegrees(atan2(y1 - y0, x1 - x0))
        // Auf −90…90 bringen, dann auf die nächste Achse beziehen
        while (deg > 90) deg -= 180
        while (deg < -90) deg += 180
        val toAxis = if (abs(deg) > 45) (if (deg > 0) deg - 90 else deg + 90) else deg
        return -toAxis
    }
}
