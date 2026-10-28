package de.photographics.core.image

import kotlin.math.max
import kotlin.math.min

/** Ganzzahliges Rechteck, `right`/`bottom` sind exklusiv. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun intersect(o: IntRect): IntRect =
        IntRect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))

    fun union(o: IntRect): IntRect = when {
        isEmpty -> o
        o.isEmpty -> this
        else -> IntRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    }

    fun inset(d: Int): IntRect = IntRect(left + d, top + d, right - d, bottom - d)
    fun offset(dx: Int, dy: Int): IntRect = IntRect(left + dx, top + dy, right + dx, bottom + dy)
    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
        fun ofSize(w: Int, h: Int) = IntRect(0, 0, w, h)
    }
}
