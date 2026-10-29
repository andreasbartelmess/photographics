package de.photographics.core.doc

/**
 * „Automatisch auswählen“ (Verschieben-Werkzeug): oberste sichtbare Pixel-, Text- oder Smart-Ebene,
 * die an einem Punkt tatsächlich etwas zeigt – berücksichtigt Deckkraft, Masken, Gruppen und Schnittmasken.
 */
object LayerPick {
    /** Mindestdeckung (0..1), ab der ein Pixel als „getroffen“ zählt. */
    private const val MIN_COVER = 0.1f

    fun at(doc: Document, x: Int, y: Int): RasterLayer? {
        if (x !in 0 until doc.width || y !in 0 until doc.height) return null
        return pick(doc.root.children, x, y, 1f)
    }

    private fun pick(children: List<Layer>, x: Int, y: Int, parentCover: Float): RasterLayer? {
        for (i in children.indices.reversed()) {
            val l = children[i]
            if (!l.visible || l.opacity <= 0f) continue
            val own = parentCover * l.opacity * maskAt(l, x, y)
            if (own <= 0f) continue
            when (l) {
                is GroupLayer -> pick(l.children, x, y, own)?.let { return it }
                is RasterLayer -> {
                    var cover = own * (l.raster[x, y] ushr 24) / 255f
                    // Schnittmaske: nur sichtbar, wo die Basis darunter Deckung hat
                    if (l.clipped) {
                        var b = i - 1
                        while (b >= 0 && children[b].clipped) b--
                        val base = children.getOrNull(b)
                        cover *= if (base is RasterLayer && base.visible) base.opacity * maskAt(base, x, y) * (base.raster[x, y] ushr 24) / 255f else 0f
                    }
                    if (cover >= MIN_COVER) return l
                }
                else -> {}
            }
        }
        return null
    }

    private fun maskAt(l: Layer, x: Int, y: Int): Float {
        val m = l.mask ?: return 1f
        return if (m.enabled) m.grid[x, y] / 255f else 1f
    }
}
