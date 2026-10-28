package de.photographics.core.image

import kotlin.math.abs

/**
 * Projektive Abbildung (3×3, zeilenweise) für Verzerren, Perspektive und perspektivisches
 * Freistellen: (x, y) → ((a·x + b·y + c) / w, (d·x + e·y + f) / w) mit w = g·x + h·y + 1.
 */
class Homography(val m: DoubleArray) {
    init { require(m.size == 9) }

    fun mapX(x: Double, y: Double) = (m[0] * x + m[1] * y + m[2]) / (m[6] * x + m[7] * y + m[8])
    fun mapY(x: Double, y: Double) = (m[3] * x + m[4] * y + m[5]) / (m[6] * x + m[7] * y + m[8])
    /** Nenner (Tiefe); ≤ 0 = Punkt liegt „hinter“ der Abbildung. */
    fun w(x: Double, y: Double) = m[6] * x + m[7] * y + m[8]

    /** Zuerst diese, dann [o] anwenden. */
    fun then(o: Homography): Homography {
        val a = o.m; val b = m
        return Homography(DoubleArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] })
    }

    fun inverse(): Homography {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]; val c01 = a[5] * a[6] - a[3] * a[8]; val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(abs(det) > 1e-18) { "Abbildung nicht umkehrbar" }
        val inv = doubleArrayOf(
            c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return Homography(DoubleArray(9) { inv[it] / det })
    }

    /** Bildpunkte der vier Ecken (0,0), (w,0), (w,h), (0,h). */
    fun corners(w: Double, h: Double): DoubleArray {
        val xs = doubleArrayOf(0.0, w, w, 0.0); val ys = doubleArrayOf(0.0, 0.0, h, h)
        return DoubleArray(8) { i -> val k = i / 2; if (i % 2 == 0) mapX(xs[k], ys[k]) else mapY(xs[k], ys[k]) }
    }

    /** Ungefährer Maßstab (Flächenverhältnis) – für die Wahl der Mipmap-Stufe. */
    fun scaleFor(w: Double, h: Double): Double = kotlin.math.sqrt(abs(quadArea(corners(w, h))) / maxOf(1e-9, w * h))

    companion object {
        fun of(a: Affine): Homography { val t = a.toArray(); return Homography(doubleArrayOf(t[0], t[2], t[4], t[1], t[3], t[5], 0.0, 0.0, 1.0)) }

        /** Einheitsquadrat → Viereck [q] (x0,y0 … x3,y3 für Ecken oben links, oben rechts, unten rechts, unten links), nach Heckbert. */
        fun squareToQuad(q: DoubleArray): Homography {
            val x0 = q[0]; val y0 = q[1]; val x1 = q[2]; val y1 = q[3]; val x2 = q[4]; val y2 = q[5]; val x3 = q[6]; val y3 = q[7]
            val dx3 = x0 - x1 + x2 - x3; val dy3 = y0 - y1 + y2 - y3
            if (abs(dx3) < 1e-12 && abs(dy3) < 1e-12) {
                return Homography(doubleArrayOf(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0.0, 0.0, 1.0))
            }
            val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
            val det = dx1 * dy2 - dx2 * dy1
            require(abs(det) > 1e-18) { "Viereck entartet" }
            val g = (dx3 * dy2 - dx2 * dy3) / det
            val h = (dx1 * dy3 - dx3 * dy1) / det
            return Homography(doubleArrayOf(
                x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                g, h, 1.0,
            ))
        }

        /** Rechteck [w]×[h] (Ursprung oben links) → Viereck [q]. */
        fun rectToQuad(w: Double, h: Double, q: DoubleArray): Homography =
            Homography(doubleArrayOf(1 / w, 0.0, 0.0, 0.0, 1 / h, 0.0, 0.0, 0.0, 1.0)).then(squareToQuad(q))

        /** Viereck [q] → Rechteck [w]×[h]. */
        fun quadToRect(q: DoubleArray, w: Double, h: Double): Homography = rectToQuad(w, h, q).inverse()

        /** Vorzeichenbehaftete Fläche eines Vierecks (Schnürsenkelformel). */
        fun quadArea(q: DoubleArray): Double {
            var s = 0.0
            for (i in 0 until 4) { val j = (i + 1) % 4; s += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1] }
            return s / 2
        }

        /** Konvex und nicht entartet (Voraussetzung für eine sinnvolle Abbildung)? */
        fun isConvex(q: DoubleArray): Boolean {
            var sign = 0
            for (i in 0 until 4) {
                val ax = q[((i + 1) % 4) * 2] - q[i * 2]; val ay = q[((i + 1) % 4) * 2 + 1] - q[i * 2 + 1]
                val bx = q[((i + 2) % 4) * 2] - q[((i + 1) % 4) * 2]; val by = q[((i + 2) % 4) * 2 + 1] - q[((i + 1) % 4) * 2 + 1]
                val cross = ax * by - ay * bx
                if (abs(cross) < 1e-9) return false
                val s = if (cross > 0) 1 else -1
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }
    }
}
