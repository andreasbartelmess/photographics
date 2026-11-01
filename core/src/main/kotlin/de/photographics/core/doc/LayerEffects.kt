package de.photographics.core.doc

import de.photographics.core.render.BlendMode

/**
 * Ebeneneffekte (Ebenenstil) einer Ebene, wie Photoshop sie im Block `lfx2` speichert. Bearbeitete
 * Effekte schreibt photographics selbst als `lfx2`; unveränderte Blöcke bleiben erhalten.
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
    val bevel: Bevel? = null,
    val satin: Satin? = null,
    val gradientOverlay: GradientOverlay? = null,
    /** Effekte, die (noch) nicht dargestellt werden: Musterüberlagerung. */
    val unsupported: List<String> = emptyList(),
) {
    enum class BevelStyle { INNER, OUTER, EMBOSS, PILLOW }

    /**
     * Abgeflachte Kante und Relief: [depth] 1.0 = 100 %, [up] Richtung „nach oben“, [size]/[soften]
     * in Pixeln, Licht aus [angle] in Höhe [altitude] (Grad).
     */
    data class Bevel(
        val style: BevelStyle = BevelStyle.INNER, val depth: Double = 1.0, val up: Boolean = true,
        val size: Double = 5.0, val soften: Double = 0.0,
        val angle: Double = 120.0, val useGlobalAngle: Boolean = true, val altitude: Double = 30.0,
        val highlightColor: Int = 0xFFFFFF, val highlightOpacity: Float = 0.75f, val highlightBlend: BlendMode = BlendMode.SCREEN,
        val shadowColor: Int = 0x000000, val shadowOpacity: Float = 0.75f, val shadowBlend: BlendMode = BlendMode.MULTIPLY,
    )

    /** Glanz: zwei verschobene, weiche Kopien der Form, ihre Differenz färbt das Innere. */
    data class Satin(
        val color: Int = 0x000000, val opacity: Float = 0.5f, val blend: BlendMode = BlendMode.MULTIPLY,
        val angle: Double = 19.0, val distance: Double = 11.0, val size: Double = 14.0, val invert: Boolean = true,
    )

    /** Verlaufsüberlagerung über die Begrenzung der Ebene ([angle] in Grad, [scale] 1.0 = 100 %). */
    data class GradientOverlay(
        val color0: Int = 0x000000, val color1: Int = 0xFFFFFF, val opacity: Float = 1f, val blend: BlendMode = BlendMode.NORMAL,
        val angle: Double = 90.0, val radial: Boolean = false, val reverse: Boolean = false, val scale: Double = 1.0,
    ) {
        /** Farbe bei [t] (0..1). */
        fun color(t: Float): Int {
            val u = (if (reverse) 1 - t else t).coerceIn(0f, 1f)
            fun ch(s: Int) = (((color0 shr s) and 0xFF) + (((color1 shr s) and 0xFF) - ((color0 shr s) and 0xFF)) * u + 0.5f).toInt()
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
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

    /** Mindestens ein Effekt vorhanden (auch wenn ausgeschaltet). */
    val hasAny: Boolean
        get() = dropShadows.isNotEmpty() || innerShadows.isNotEmpty() || outerGlow != null || innerGlow != null ||
            strokes.isNotEmpty() || colorOverlays.isNotEmpty() || bevel != null || satin != null || gradientOverlay != null

    /** Mindestens ein Effekt, der dargestellt wird. */
    val visible: Boolean
        get() = enabled && (dropShadows.isNotEmpty() || innerShadows.isNotEmpty() || outerGlow != null ||
            innerGlow != null || strokes.isNotEmpty() || colorOverlays.isNotEmpty() || bevel != null || satin != null || gradientOverlay != null)

    /** Wie weit die Effekte über den Ebeneninhalt hinausreichen (Dokumentpixel). */
    fun reach(): Double {
        var r = 0.0
        for (s in dropShadows) r = maxOf(r, s.distance + s.size * 1.5 + 2)
        for (s in innerShadows) r = maxOf(r, s.distance + s.size * 1.5 + 2)
        outerGlow?.let { r = maxOf(r, it.size * 1.5 + 2) }
        innerGlow?.let { r = maxOf(r, it.size * 1.5 + 2) }
        for (s in strokes) r = maxOf(r, s.size + 2)
        bevel?.let { r = maxOf(r, it.size * 2 + it.soften * 2 + 4) }
        satin?.let { r = maxOf(r, it.distance + it.size * 1.5 + 2) }
        return r
    }
}
