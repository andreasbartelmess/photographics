package de.photographics.core.doc

import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdImageResource
import de.photographics.core.psd.PsdTaggedBlock
import de.photographics.core.selection.Selection

class Document(width: Int, height: Int) {
    var width = width
        private set
    var height = height
        private set

    /** Unsichtbare Wurzelgruppe; ihre Kinder sind die oberste Ebenenhierarchie. */
    val root = GroupLayer("<root>")

    var activeLayer: Layer? = null
    var selection: Selection? = null

    /** Auflösung in dpi. */
    var dpi = 72f

    /** Farbprofil des Dokuments (RGB). Die Pixelwerte sind in diesem Farbraum gemeint. */
    var colorProfile: de.photographics.core.color.IccProfile = de.photographics.core.color.IccProfiles.sRGB

    /** Aus der Quelldatei übernommene Bildressourcen (XMP, EXIF, Hilfslinien ...; das Profil steht in [colorProfile]). */
    var preservedResources: List<PsdImageResource> = emptyList()
    /** Globale Zusatzblöcke (z. B. verknüpfte Smartobjekt-Daten). */
    var preservedGlobalBlocks: List<PsdTaggedBlock> = emptyList()
    /** Hinweise beim Import (nicht unterstützte Features, Konvertierungen). */
    val importWarnings = mutableListOf<String>()
    /** Hilfslinien (unveränderliche Liste; Änderungen ersetzen sie). */
    var guides: List<Guide> = emptyList()
    /** PSD: Photoshop hat ein echtes zusammengeführtes Bild gespeichert (Ressource 1057); null = unbekannt. */
    var hasRealComposite: Boolean? = null

    val bounds: IntRect get() = IntRect.ofSize(width, height)

    fun newGrid() = TileGrid(width, height)

    /** Alle Ebenen in Zeichenreihenfolge (Tiefensuche, unten nach oben). */
    fun allLayers(): List<Layer> {
        val out = mutableListOf<Layer>()
        fun walk(g: GroupLayer) {
            for (c in g.children) { out += c; if (c is GroupLayer) walk(c) }
        }
        walk(root)
        return out
    }

    fun pixelLayers(): List<PixelLayer> = allLayers().filterIsInstance<PixelLayer>()

    fun findLayer(id: Int): Layer? = allLayers().firstOrNull { it.id == id }

    fun parentOf(layer: Layer): GroupLayer = layer.parent ?: root

    /** Fügt [layer] direkt über [above] ein (oder ganz oben, wenn null). */
    fun insertAbove(layer: Layer, above: Layer?) {
        if (above == null) { root.add(layer); return }
        val p = parentOf(above)
        p.add(layer, p.children.indexOf(above) + 1)
    }

    fun removeLayer(layer: Layer) {
        parentOf(layer).remove(layer)
        if (activeLayer === layer || (activeLayer != null && isDescendant(activeLayer!!, layer))) {
            activeLayer = pixelLayers().lastOrNull() ?: allLayers().lastOrNull()
        }
    }

    fun isDescendant(layer: Layer, ancestor: Layer): Boolean {
        var p = layer.parent
        while (p != null) { if (p === ancestor) return true; p = p.parent }
        return false
    }

    /**
     * Ändert die Arbeitsfläche; (dx, dy) ist der Versatz des alten Inhalts. Mit [keepOutside] bleiben
     * Pixel außerhalb der neuen Fläche in den Ebenen erhalten (Arbeitsfläche), sonst werden sie
     * verworfen (Freistellen mit „Beschnittene Pixel löschen“).
     */
    fun resizeCanvas(newW: Int, newH: Int, dx: Int, dy: Int, keepOutside: Boolean = true) {
        val shift = Affine.translate(dx.toDouble(), dy.toDouble())
        for (l in allLayers()) {
            when (l) {
                is PixelLayer -> l.pixels = l.pixels.resized(newW, newH, dx, dy, keepOutside)
                is TextLayer -> { l.docWidth = newW; l.docHeight = newH; l.transform = l.transform.then(shift) }
                is SmartLayer -> { l.docWidth = newW; l.docHeight = newH; l.applyDocTransform(shift) }
                else -> {}
            }
            l.mask?.let { it.grid = it.grid.resized(newW, newH, dx, dy, keepOutside) }
        }
        width = newW; height = newH
        // Hilfslinien wandern mit dem Inhalt; außerhalb der neuen Fläche fallen sie weg
        guides = guides.map { if (it.vertical) it.copy(position = it.position + dx) else it.copy(position = it.position + dy) }
            .filter { it.position >= 0 && it.position <= (if (it.vertical) newW else newH) }
        selection = null
    }

    /** Setzt die Größe ohne Inhalte anzufassen (Ebenen wurden bereits umgerechnet). */
    fun setSize(newW: Int, newH: Int) {
        width = newW; height = newH
        for (l in allLayers()) {
            if (l is TextLayer) { l.docWidth = newW; l.docHeight = newH; l.invalidate() }
            if (l is SmartLayer) { l.docWidth = newW; l.docHeight = newH; l.invalidate() }
        }
        selection = null
    }

    /** Vollständige Kopie (für Undo von Strukturänderungen wie Freistellen). */
    fun snapshot(): Snapshot = Snapshot(width, height, allLayers().associate { l ->
        l.id to LayerData(
            (l as? PixelLayer)?.pixels, l.mask?.grid,
            when (l) { is TextLayer -> l.transform; is SmartLayer -> l.transform; else -> null },
            (l as? TextLayer)?.style,
            (l as? SmartLayer)?.warp,
        )
    }, selection, guides)

    fun restore(s: Snapshot) {
        width = s.width; height = s.height
        guides = s.guides
        for (l in allLayers()) {
            val d = s.layers[l.id] ?: continue
            if (l is PixelLayer && d.pixels != null) l.pixels = d.pixels
            if (l is TextLayer) { l.docWidth = width; l.docHeight = height; d.style?.let { l.style = it }; d.transform?.let { l.transform = it } }
            if (l is SmartLayer) { l.docWidth = width; l.docHeight = height; d.transform?.let { l.transform = it }; l.warp = d.warp }
            if (d.mask != null) l.mask?.grid = d.mask
        }
        selection = s.selection
    }

    /** Hält nur Referenzen; Aufrufer müssen Raster ersetzen statt sie zu verändern. */
    class Snapshot(val width: Int, val height: Int, val layers: Map<Int, LayerData>, val selection: Selection?, val guides: List<Guide> = emptyList())
    class LayerData(val pixels: TileGrid?, val mask: TileGrid?, val transform: Affine? = null, val style: TextStyle? = null, val warp: DoubleArray? = null)

    companion object {
        fun blank(w: Int, h: Int, background: Int? = 0xFFFFFFFF.toInt()): Document {
            val d = Document(w, h)
            val grid = TileGrid(w, h)
            if (background != null) {
                for (ty in 0 until grid.tilesY) for (tx in 0 until grid.tilesX) grid.tileOrCreate(tx, ty).fill(background)
            }
            val bg = PixelLayer(if (background != null) "Hintergrund" else "Ebene 1", grid)
            d.root.add(bg)
            d.activeLayer = bg
            return d
        }
    }
}

/** Hilfslinie: senkrecht ([vertical], Lage = x) oder waagerecht (Lage = y), in Dokumentpixeln. */
data class Guide(val vertical: Boolean, val position: Double)
