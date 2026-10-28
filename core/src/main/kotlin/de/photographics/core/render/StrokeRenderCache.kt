package de.photographics.core.render

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid

/**
 * Schnelles Neuzeichnen während eines Pinselstrichs: Alles unterhalb der bemalten Ebene ändert
 * sich nicht und wird kachelweise einmal zusammengesetzt und zwischengespeichert. Pro Bewegung
 * wird nur noch die bemalte Ebene darübergelegt (Ganzzahl-Arithmetik statt kompletter Stapel).
 *
 * Nur für den einfachen, häufigsten Fall: oberste sichtbare Ebene der Wurzel, Modus Normal,
 * keine Schnittmaske. Sonst liefert [create] null und es wird normal gerendert.
 */
class StrokeRenderCache private constructor(private val doc: Document, private val layer: PixelLayer) {
    private val below = HashMap<Int, IntArray>()
    private val tilesX = (doc.width + TileGrid.TILE - 1) shr TileGrid.SHIFT

    private fun belowTile(tx: Int, ty: Int): IntArray = below.getOrPut(ty * tilesX + tx) {
        val r = IntRect(tx shl TileGrid.SHIFT, ty shl TileGrid.SHIFT, minOf((tx + 1) shl TileGrid.SHIFT, doc.width), minOf((ty + 1) shl TileGrid.SHIFT, doc.height))
        val was = layer.visible
        layer.visible = false
        try {
            val px = Compositor.renderArgb(doc, r)
            IntArray(TileGrid.TILE * TileGrid.TILE).also { t ->
                for (y in 0 until r.height) System.arraycopy(px, y * r.width, t, y shl TileGrid.SHIFT, r.width)
            }
        } finally {
            layer.visible = was
        }
    }

    /** Zusammengesetztes Bild (nicht vormultipliziertes ARGB) für [rect]. */
    fun renderArgb(rect: IntRect, out: IntArray = IntArray(rect.width * rect.height)): IntArray {
        val opacity = (layer.opacity * layer.fillOpacity * 255f + 0.5f).toInt()
        val mask = layer.mask?.takeIf { it.enabled }?.grid
        val px = layer.pixels
        px.forTilesIn(rect) { tx, ty ->
            val tr = px.tileRect(tx, ty).intersect(rect)
            // Ebene und Maske können über die Arbeitsfläche hinausragen → Kacheln über Dokumentkoordinaten
            val b = belowTile(tr.left shr TileGrid.SHIFT, tr.top shr TileGrid.SHIFT)
            val lT = px.tile(tx, ty); val mT = mask?.tileAt(tr.left, tr.top)
            val l = lT?.pin(forWrite = false)
            val m = mT?.pin(forWrite = false)
            val mDef = mask?.defaultValue ?: 255
            try { for (y in tr.top until tr.bottom) {
                val rowT = (y and TileGrid.MASK) shl TileGrid.SHIFT
                var o = (y - rect.top) * rect.width + (tr.left - rect.left)
                for (x in tr.left until tr.right) {
                    val i = rowT or (x and TileGrid.MASK)
                    val dst = b[i]
                    val src = if (l != null) l[i] else 0
                    var a = src ushr 24
                    if (a != 0) {
                        a = a * opacity / 255
                        if (mask != null) a = a * (if (m != null) m[i] else mDef) / 255
                    }
                    out[o++] = if (a == 0) dst else blend(dst, src, a)
                }
            } } finally { lT?.unpin(); mT?.unpin() }
        }
        return out
    }

    private fun blend(dst: Int, src: Int, a: Int): Int {
        val da = dst ushr 24
        if (da == 255) {
            // Häufigster Fall: deckender Hintergrund – reine Ganzzahl-Mischung
            val ia = 255 - a
            val r = (((src shr 16) and 0xFF) * a + ((dst shr 16) and 0xFF) * ia + 127) / 255
            val g = (((src shr 8) and 0xFF) * a + ((dst shr 8) and 0xFF) * ia + 127) / 255
            val bl = ((src and 0xFF) * a + (dst and 0xFF) * ia + 127) / 255
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return de.photographics.core.image.Colors.srcOver(dst, (a shl 24) or (src and 0xFFFFFF))
    }

    companion object {
        fun create(doc: Document, layer: PixelLayer): StrokeRenderCache? {
            if (layer.parent !== doc.root && layer.parent != null) return null
            if (layer.blendMode != BlendMode.NORMAL || layer.clipped) return null
            val children = doc.root.children
            val idx = children.indexOf(layer)
            if (idx < 0) return null
            // Darüber darf nichts Sichtbares liegen (auch keine Schnittmasken auf dieser Ebene)
            for (k in idx + 1 until children.size) if (children[k].visible) return null
            return StrokeRenderCache(doc, layer)
        }
    }
}
