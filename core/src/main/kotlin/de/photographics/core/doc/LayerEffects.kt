package de.photographics.core.doc

import de.photographics.core.render.BlendMode

/**
 * Ebeneneffekte (Ebenenstil) einer Ebene, wie Photoshop sie im Block `lfx2` speichert. Werden nur
 * angezeigt; beim Speichern bleibt der Originalblock unverändert erhalten.
 *
 * Abstände und Größen in Dokumentpixeln, Winkel in Grad (0° = Licht von rechts, 90° = von oben),
 * Deckkraft 0..1, Farben als RGB ohne Alpha.
 */
data class LayerEffects(
    val enabled: Boolean = true,
    val dropShadows: List<Shadow> = emptyList(),
    val innerShadows: List<Shadow> = emptyList(),
    val outerGlow: Glow? = null,
    val innerGlow: Glow? = null,
    val strokes: List<Stroke> = emptyList(),
    val colorOverlays: List<ColorOverlay> = emptyList(),
    /** Effekte, die (noch) nicht dargestellt werden: Abgeflachte Kante, Glanz, Verlaufs- und Musterüberlagerung. */
    val unsupported: List<String> = emptyList(),
) {
    data class Shadow(
        val color: Int, val opacity: Float, val blend: BlendMode,
        val angle: Double, val useGlobalAngle: Boolean,
        val distance: Double, val spread: Double, val size: Double,
        /** Schlagschatten: Ebene spart den Schatten aus (bei verringerter Fläche sichtbar). */
        val knockOut: Boolean = true,
    )

    data class Glow(
        val color: Int, val opacity: Float, val blend: BlendMode,
        val spread: Double, val size: Double,
        /** Schein nach innen: von der Kante (true) oder aus der Mitte. */
        val fromEdge: Boolean = true,
    )

    enum class StrokePosition { OUTSIDE, INSIDE, CENTER }

    data class Stroke(val color: Int, val opacity: Float, val blend: BlendMode, val size: Double, val position: StrokePosition)

    data class ColorOverlay(val color: Int, val opacity: Float, val blend: BlendMode)

    /** Mindestens ein Effekt, der dargestellt wird. */
    val visible: Boolean
        get() = enabled && (dropShadows.isNotEmpty() || innerShadows.isNotEmpty() || outerGlow != null ||
            innerGlow != null || strokes.isNotEmpty() || colorOverlays.isNotEmpty())

    /** Wie weit die Effekte über den Ebeneninhalt hinausreichen (Dokumentpixel). */
    fun reach(): Double {
        var r = 0.0
        for (s in dropShadows) r = maxOf(r, s.distance + s.size * 1.5 + 2)
        for (s in innerShadows) r = maxOf(r, s.distance + s.size * 1.5 + 2)
        outerGlow?.let { r = maxOf(r, it.size * 1.5 + 2) }
        innerGlow?.let { r = maxOf(r, it.size * 1.5 + 2) }
        for (s in strokes) r = maxOf(r, s.size + 2)
        return r
    }
}
