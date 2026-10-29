package de.photographics.core.color

import de.photographics.core.tr
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer

/**
 * „Farbsuchtabellen exportieren“ wie in Photoshop: Die Wirkung aller sichtbaren
 * Einstellungsebenen (von unten nach oben, mit ihrer Deckkraft) wird in eine 3D-LUT gerechnet,
 * die sich in anderen Programmen (Lightroom, Resolve, Premiere, Kameras) verwenden lässt.
 * Pixelebenen und Masken spielen keine Rolle – eine LUT kennt nur Farben.
 */
object LutExport {
    fun fromAdjustments(doc: Document, size: Int = 33, title: String = tr("Photographics")): Lut3D? {
        val layers = ArrayList<AdjustmentLayer>()
        fun walk(children: List<Layer>) {
            for (l in children) {
                if (!l.visible) continue
                when (l) {
                    is AdjustmentLayer -> layers += l
                    is GroupLayer -> walk(l.children)
                    else -> {}
                }
            }
        }
        walk(doc.root.children)
        if (layers.isEmpty()) return null
        val maps = layers.map { it.adjustment.compile() to (it.opacity * it.fillOpacity) }
        return Lut3D.of(size, title) { r, g, b, o ->
            var rr = r; var gg = g; var bb = b
            for ((map, alpha) in maps) {
                // Auf 16 Bit genau: die Stützstellen liegen zwischen den 8-Bit-Werten
                val c = (Math.round(rr * 255f) shl 16) or (Math.round(gg * 255f) shl 8) or Math.round(bb * 255f)
                val m = map.map(c)
                val mr = ((m shr 16) and 0xFF) / 255f; val mg = ((m shr 8) and 0xFF) / 255f; val mb = (m and 0xFF) / 255f
                rr += (mr - rr) * alpha; gg += (mg - gg) * alpha; bb += (mb - bb) * alpha
            }
            o[0] = rr; o[1] = gg; o[2] = bb
        }
    }
}
