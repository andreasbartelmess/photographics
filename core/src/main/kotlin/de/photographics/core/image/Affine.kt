package de.photographics.core.image

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 2D-Affintransformation: x' = a·x + c·y + tx, y' = b·x + d·y + ty
 * (gleiche Konvention wie android.graphics.Matrix bzw. PostScript).
 */
data class Affine(
    val a: Double = 1.0, val b: Double = 0.0,
    val c: Double = 0.0, val d: Double = 1.0,
    val tx: Double = 0.0, val ty: Double = 0.0,
) {
    fun mapX(x: Double, y: Double) = a * x + c * y + tx
    fun mapY(x: Double, y: Double) = b * x + d * y + ty

    val determinant get() = a * d - b * c

    fun inverse(): Affine {
        val det = determinant
        require(det != 0.0) { "Transformation ist nicht umkehrbar" }
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    /** Erst [this], dann [o] anwenden. */
    fun then(o: Affine) = Affine(
        o.a * a + o.c * b, o.b * a + o.d * b,
        o.a * c + o.c * d, o.b * c + o.d * d,
        o.a * tx + o.c * ty + o.tx, o.b * tx + o.d * ty + o.ty,
    )

    /** Mittlerer Skalierungsfaktor (für Mipmap-Auswahl). */
    val scale get() = kotlin.math.sqrt(kotlin.math.abs(determinant))
    val scaleX get() = hypot(a, b)
    val scaleY get() = hypot(c, d)
    val rotationDegrees get() = Math.toDegrees(atan2(b, a))

    /** Begrenzungsrahmen eines w×h-Rechtecks nach der Transformation. */
    fun bounds(w: Double, h: Double): DoubleArray {
        val xs = doubleArrayOf(mapX(0.0, 0.0), mapX(w, 0.0), mapX(0.0, h), mapX(w, h))
        val ys = doubleArrayOf(mapY(0.0, 0.0), mapY(w, 0.0), mapY(0.0, h), mapY(w, h))
        return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    fun toArray() = doubleArrayOf(a, b, c, d, tx, ty)

    companion object {
        val IDENTITY = Affine()
        fun translate(x: Double, y: Double) = Affine(tx = x, ty = y)
        fun scale(sx: Double, sy: Double = sx) = Affine(a = sx, d = sy)
        fun rotate(degrees: Double): Affine {
            val r = Math.toRadians(degrees); val cs = cos(r); val sn = sin(r)
            return Affine(cs, sn, -sn, cs)
        }
        fun of(v: DoubleArray) = Affine(v[0], v[1], v[2], v[3], v[4], v[5])

        /** Skalieren/Drehen um einen Punkt (px, py). */
        fun around(px: Double, py: Double, t: Affine) = translate(-px, -py).then(t).then(translate(px, py))

        fun clampRect(b: DoubleArray, w: Int, h: Int): IntRect = IntRect(
            max(0, kotlin.math.floor(b[0]).toInt()), max(0, kotlin.math.floor(b[1]).toInt()),
            min(w, kotlin.math.ceil(b[2]).toInt()), min(h, kotlin.math.ceil(b[3]).toInt()),
        )
    }
}
