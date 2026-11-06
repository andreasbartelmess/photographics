package de.photographics.core.doc

import de.photographics.core.filters.FilterCatalog
import de.photographics.core.filters.GalleryChain
import de.photographics.core.filters.GalleryContext
import de.photographics.core.filters.GalleryEffect
import de.photographics.core.filters.ImageFilter
import de.photographics.core.render.BlendMode
import de.photographics.core.tr

/**
 * Smartfilter (Photoshop: Filter auf einem Smartobjekt): bleibt als Eintrag unter der Ebene stehen
 * und lässt sich jederzeit nachregeln, ausschalten, umsortieren oder löschen.
 *
 * Entweder ein Filter aus dem [FilterCatalog] ([filterId] + [values]) oder eine Filtergalerie-Kette
 * ([gallery], mit Vorder-/Hintergrundfarbe zum Zeitpunkt des Anwendens). Fülloptionen wie in
 * Photoshop: [blendMode] und [opacity] legen das Ergebnis über das Bild davor.
 */
data class SmartFilter(
    val filterId: String,
    val values: List<Float> = emptyList(),
    val gallery: List<GalleryEffect>? = null,
    val galleryFg: Int = 0xFF000000.toInt(),
    val galleryBg: Int = 0xFFFFFFFF.toInt(),
    val enabled: Boolean = true,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val opacity: Float = 1f,
) {
    /** Angezeigter Name (Filter aus einer neueren Version: die Kennung). */
    val label: String get() = if (gallery != null) tr("Filtergalerie") else FilterCatalog.byId(filterId)?.label ?: filterId

    /** Der Filter zum Anwenden; null = unbekannt (wird übersprungen). */
    fun build(): ImageFilter? =
        if (gallery != null) GalleryChain(gallery, GalleryContext(galleryFg, galleryBg)).takeUnless { it.isEmpty }
        else FilterCatalog.byId(filterId)?.let { spec ->
            val v = FloatArray(spec.params.size) { i -> values.getOrNull(i) ?: spec.params[i].default }
            spec.build(v)
        }

    companion object {
        const val GALLERY_ID = "gallery"

        fun of(filterId: String, values: FloatArray) = SmartFilter(filterId, values.toList())
        fun gallery(effects: List<GalleryEffect>, fg: Int, bg: Int) = SmartFilter(GALLERY_ID, gallery = effects, galleryFg = fg, galleryBg = bg)
    }
}

/**
 * Alle Smartfilter einer Ebene, unten zuerst angewendet (wie in Photoshops Ebenenbedienfeld von
 * unten nach oben). [visible] = Augensymbol „Smartfilter“, [mask] = gemeinsame Filtermaske
 * (weiß = Filter wirken, schwarz = Original).
 */
data class SmartFilters(
    val filters: List<SmartFilter> = emptyList(),
    val visible: Boolean = true,
    val mask: LayerMask? = null,
) {
    /** Wirkt überhaupt ein Filter? */
    val active: Boolean get() = visible && filters.any { it.enabled }
}

/**
 * [f] an Stelle [index] einsetzen (außerhalb der Liste = neu oben). Wie in Photoshop: kommt der
 * erste Filter bei aktiver [selection] dazu und gibt es noch keine Filtermaske, wird die Auswahl
 * zur Filtermaske (der Filter wirkt nur dort).
 */
fun SmartFilters.with(index: Int, f: SmartFilter, selection: de.photographics.core.selection.Selection? = null): SmartFilters {
    val list = filters.toMutableList()
    val added = index !in list.indices
    if (added) list += f else list[index] = f
    val m = if (added && mask == null && selection != null) LayerMask(selection.mask.copy()) else mask
    return copy(filters = list, mask = m)
}
