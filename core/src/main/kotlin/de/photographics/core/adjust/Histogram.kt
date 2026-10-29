package de.photographics.core.adjust

import de.photographics.core.doc.Document
import de.photographics.core.doc.Layer
import de.photographics.core.image.IntRect
import de.photographics.core.render.Compositor

/** Tonwertverteilung: je 256 Zähler für Helligkeit, Rot, Grün, Blau (in dieser Reihenfolge). */
object Histogram {
    const val LUMA = 0; const val RED = 1; const val GREEN = 2; const val BLUE = 3

    /** Zählt deckende Pixel (Alpha > 0) aus ARGB-Werten. */
    fun of(px: IntArray): IntArray {
        val h = IntArray(4 * 256)
        for (c in px) {
            if ((c ushr 24) == 0) continue
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            h[(r * 77 + g * 150 + b * 29) shr 8]++
            h[256 + r]++; h[512 + g]++; h[768 + b]++
        }
        return h
    }

    /**
     * Verteilung des Bildes unter [layer] (Eingang einer Einstellungsebene), abgetastet auf etwa
     * [maxPixels] Pixel. Liegt [layer] in einer Gruppe oder Schnittmaske, zählt alles unter deren Basis.
     */
    fun below(doc: Document, layer: Layer, maxPixels: Int = 250_000): IntArray {
        var step = 1
        while ((doc.width.toLong() / step) * (doc.height / step) > maxPixels) step++
        val rect = IntRect(0, 0, (doc.width + step - 1) / step, (doc.height + step - 1) / step)
        val until = Compositor.liveSplit(doc, layer)
        if (until <= 0) return IntArray(4 * 256)
        return of(Compositor.renderRangeSampled(doc, rect, step, 0, until, null))
    }

    /** Zähler eines Kanals, logarithmisch-ähnlich geglättet auf 0..1 (für die Anzeige). */
    fun display(h: IntArray, channel: Int): FloatArray {
        val part = FloatArray(256) { kotlin.math.sqrt(h[channel * 256 + it].toFloat()) }
        // Höchstwert ohne die Ränder (abgesoffene Tiefen/Lichter würden alles andere plattdrücken)
        val mx = (2..253).maxOf { part[it] }.coerceAtLeast(1f)
        return FloatArray(256) { (part[it] / mx).coerceIn(0f, 1f) }
    }
}
