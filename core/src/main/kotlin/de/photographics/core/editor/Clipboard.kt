package de.photographics.core.editor

import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid

/** Kopierte Pixel: [grid] in Größe des Ausschnitts, [rect] = Lage im Quelldokument. */
class PixelClip(val grid: TileGrid, val rect: IntRect) {
    /** Pixel als ARGB (zeilenweise, [rect]-Größe). */
    fun pixels(): IntArray = IntArray(rect.width * rect.height).also { grid.readRect(IntRect.ofSize(rect.width, rect.height), it) }
}

/** Zwischenablage für Pixel – gemeinsam für alle offenen Dokumente (wie in Photoshop). */
object Clipboard {
    @Volatile var clip: PixelClip? = null
}

/**
 * Schwebende Auswahl: die ausgewählten Pixel der Ebene [source] liegen während des Verschiebens oder
 * Transformierens als Smartobjekt [layer] darüber; [tx] hält die Quellebene offen (ausgeschnittene
 * Stelle), bis [Editor.commitFloating] oder [Editor.cancelFloating] den Vorgang abschließt.
 */
class Floating internal constructor(
    val source: de.photographics.core.doc.PixelLayer,
    val layer: de.photographics.core.doc.SmartLayer,
    internal val tx: de.photographics.core.history.TileTransaction,
    /** Auswahl vor dem Anheben; null = ganze Ebene (ohne Auswahl transformiert). */
    internal val selectionBefore: de.photographics.core.selection.Selection?,
    /** Ausgangslage der Pixel im Dokument (Quellkoordinaten = Ausschnitt ab links oben). */
    val bounds: IntRect,
    val duplicate: Boolean,
)
