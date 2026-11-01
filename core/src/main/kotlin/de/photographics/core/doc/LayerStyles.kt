package de.photographics.core.doc

import de.photographics.core.doc.LayerEffects.BevelStyle
import de.photographics.core.doc.LayerEffects.StrokePosition
import de.photographics.core.render.BlendMode
import de.photographics.core.tr

/**
 * Beschreibung des Dialogs „Ebenenstil“ unabhängig von der Oberfläche: die Effekte in Photoshops
 * Reihenfolge, je Effekt Standardwerte und Parameter (Regler, Farbe, Mischmodus, Auswahl, Schalter).
 * Android und GNOME bauen ihre Formulare daraus.
 */
object LayerStyles {

    sealed class Param<T>(val label: String) {
        /** Zahl im Bereich [min]..[max], angezeigt mit [unit] und [digits] Nachkommastellen. */
        class Slider<T>(label: String, val min: Double, val max: Double, val unit: String, val get: (T) -> Double, val set: (T, Double) -> T, val digits: Int = 0) : Param<T>(label)
        /** Farbe als 0xRRGGBB. */
        class Color<T>(label: String, val get: (T) -> Int, val set: (T, Int) -> T) : Param<T>(label)
        class Blend<T>(label: String, val get: (T) -> BlendMode, val set: (T, BlendMode) -> T) : Param<T>(label)
        class Choice<T>(label: String, val options: List<String>, val get: (T) -> Int, val set: (T, Int) -> T) : Param<T>(label)
        class Toggle<T>(label: String, val get: (T) -> Boolean, val set: (T, Boolean) -> T) : Param<T>(label)
    }

    class Effect<T : Any>(
        val id: String,
        val label: String,
        val default: T,
        private val read: (LayerEffects) -> T?,
        private val write: (LayerEffects, T?) -> LayerEffects,
        val params: List<Param<T>>,
    ) {
        fun get(fx: LayerEffects): T? = read(fx)
        /** [fx] mit diesem Effekt auf [value] (null = aus). */
        fun with(fx: LayerEffects, value: T?): LayerEffects = write(fx, value)
    }

    private fun <T> opacity(get: (T) -> Float, set: (T, Float) -> T) =
        Param.Slider<T>(tr("Deckkraft"), 0.0, 100.0, "%", { get(it) * 100.0 }, { v, x -> set(v, (x / 100.0).toFloat()) })
    private fun <T> angle(get: (T) -> Double, set: (T, Double) -> T) = Param.Slider(tr("Winkel"), -180.0, 180.0, "°", get, set)
    private fun <T> px(label: String, max: Double, get: (T) -> Double, set: (T, Double) -> T) = Param.Slider(label, 0.0, max, "px", get, set)

    private val dropShadow = Effect(
        "dropShadow", tr("Schlagschatten"),
        LayerEffects.Shadow(0x000000, 0.75f, BlendMode.MULTIPLY, 120.0, true, 5.0, 0.0, 5.0),
        { it.dropShadows.firstOrNull() }, { fx, v -> fx.copy(dropShadows = listOfNotNull(v) + fx.dropShadows.drop(1)) },
        shadowParams(knockOut = true),
    )

    private val innerShadow = Effect(
        "innerShadow", tr("Schatten nach innen"),
        LayerEffects.Shadow(0x000000, 0.75f, BlendMode.MULTIPLY, 120.0, true, 5.0, 0.0, 5.0),
        { it.innerShadows.firstOrNull() }, { fx, v -> fx.copy(innerShadows = listOfNotNull(v) + fx.innerShadows.drop(1)) },
        shadowParams(knockOut = false),
    )

    private fun shadowParams(knockOut: Boolean): List<Param<LayerEffects.Shadow>> = buildList {
        add(Param.Blend(tr("Mischmodus"), { it.blend }, { s, v -> s.copy(blend = v) }))
        add(Param.Color(tr("Farbe"), { it.color }, { s, v -> s.copy(color = v) }))
        add(opacity({ it.opacity }, { s, v -> s.copy(opacity = v) }))
        add(angle({ it.angle }, { s, v -> s.copy(angle = v) }))
        add(Param.Toggle(tr("Globalen Lichteinfall verwenden"), { it.useGlobalAngle }, { s, v -> s.copy(useGlobalAngle = v) }))
        add(px(tr("Distanz"), 300.0, { it.distance }, { s, v -> s.copy(distance = v) }))
        add(Param.Slider(if (knockOut) tr("Überfüllen") else tr("Unterfüllen"), 0.0, 100.0, "%", { it.spread * 100.0 }, { s, v -> s.copy(spread = v / 100.0) }))
        add(px(tr("Größe"), 250.0, { it.size }, { s, v -> s.copy(size = v) }))
        if (knockOut) add(Param.Toggle(tr("Ebene spart Schlagschatten aus"), { it.knockOut }, { s, v -> s.copy(knockOut = v) }))
    }

    private fun glowParams(inner: Boolean): List<Param<LayerEffects.Glow>> = buildList {
        add(Param.Blend(tr("Mischmodus"), { it.blend }, { g, v -> g.copy(blend = v) }))
        add(Param.Color(tr("Farbe"), { it.color }, { g, v -> g.copy(color = v) }))
        add(opacity({ it.opacity }, { g, v -> g.copy(opacity = v) }))
        if (inner) add(Param.Choice(tr("Quelle"), listOf(tr("Kante"), tr("Mitte")), { if (it.fromEdge) 0 else 1 }, { g, v -> g.copy(fromEdge = v == 0) }))
        add(Param.Slider(if (inner) tr("Unterfüllen") else tr("Überfüllen"), 0.0, 100.0, "%", { it.spread * 100.0 }, { g, v -> g.copy(spread = v / 100.0) }))
        add(px(tr("Größe"), 250.0, { it.size }, { g, v -> g.copy(size = v) }))
    }

    private val outerGlow = Effect(
        "outerGlow", tr("Schein nach außen"), LayerEffects.Glow(0xFFFFBE, 0.75f, BlendMode.SCREEN, 0.0, 5.0),
        { it.outerGlow }, { fx, v -> fx.copy(outerGlow = v) }, glowParams(inner = false),
    )

    private val innerGlow = Effect(
        "innerGlow", tr("Schein nach innen"), LayerEffects.Glow(0xFFFFBE, 0.75f, BlendMode.SCREEN, 0.0, 5.0),
        { it.innerGlow }, { fx, v -> fx.copy(innerGlow = v) }, glowParams(inner = true),
    )

    private val bevel = Effect(
        "bevel", tr("Abgeflachte Kante und Relief"), LayerEffects.Bevel(),
        { it.bevel }, { fx, v -> fx.copy(bevel = v) },
        listOf(
            Param.Choice(tr("Stil"), listOf(tr("Abgeflachte Kante innen"), tr("Abgeflachte Kante außen"), tr("Relief"), tr("Reliefkontur")),
                { it.style.ordinal }, { b, v -> b.copy(style = BevelStyle.entries[v]) }),
            Param.Slider(tr("Tiefe"), 1.0, 1000.0, "%", { it.depth * 100.0 }, { b, v -> b.copy(depth = v / 100.0) }),
            Param.Choice(tr("Richtung"), listOf(tr("Aufwärts"), tr("Abwärts")), { if (it.up) 0 else 1 }, { b, v -> b.copy(up = v == 0) }),
            px(tr("Größe"), 250.0, { it.size }, { b, v -> b.copy(size = v) }),
            px(tr("Weichzeichnen"), 16.0, { it.soften }, { b, v -> b.copy(soften = v) }),
            angle({ it.angle }, { b, v -> b.copy(angle = v) }),
            Param.Toggle(tr("Globalen Lichteinfall verwenden"), { it.useGlobalAngle }, { b, v -> b.copy(useGlobalAngle = v) }),
            Param.Slider(tr("Lichthöhe"), 0.0, 90.0, "°", { it.altitude }, { b, v -> b.copy(altitude = v) }),
            Param.Blend(tr("Lichtermodus"), { it.highlightBlend }, { b, v -> b.copy(highlightBlend = v) }),
            Param.Color(tr("Lichterfarbe"), { it.highlightColor }, { b, v -> b.copy(highlightColor = v) }),
            opacity({ it.highlightOpacity }, { b, v -> b.copy(highlightOpacity = v) }),
            Param.Blend(tr("Tiefenmodus"), { it.shadowBlend }, { b, v -> b.copy(shadowBlend = v) }),
            Param.Color(tr("Tiefenfarbe"), { it.shadowColor }, { b, v -> b.copy(shadowColor = v) }),
            Param.Slider(tr("Deckkraft der Tiefen"), 0.0, 100.0, "%", { it.shadowOpacity * 100.0 }, { b, v -> b.copy(shadowOpacity = (v / 100.0).toFloat()) }),
        ),
    )

    private val satin = Effect(
        "satin", tr("Glanz"), LayerEffects.Satin(),
        { it.satin }, { fx, v -> fx.copy(satin = v) },
        listOf(
            Param.Blend(tr("Mischmodus"), { it.blend }, { s, v -> s.copy(blend = v) }),
            Param.Color(tr("Farbe"), { it.color }, { s, v -> s.copy(color = v) }),
            opacity({ it.opacity }, { s, v -> s.copy(opacity = v) }),
            angle({ it.angle }, { s, v -> s.copy(angle = v) }),
            px(tr("Distanz"), 250.0, { it.distance }, { s, v -> s.copy(distance = v) }),
            px(tr("Größe"), 250.0, { it.size }, { s, v -> s.copy(size = v) }),
            Param.Toggle(tr("Umkehren"), { it.invert }, { s, v -> s.copy(invert = v) }),
        ),
    )

    private val colorOverlay = Effect(
        "colorOverlay", tr("Farbüberlagerung"), LayerEffects.ColorOverlay(0xFF0000, 1f, BlendMode.NORMAL),
        { it.colorOverlays.firstOrNull() }, { fx, v -> fx.copy(colorOverlays = listOfNotNull(v) + fx.colorOverlays.drop(1)) },
        listOf(
            Param.Blend(tr("Mischmodus"), { it.blend }, { o, v -> o.copy(blend = v) }),
            Param.Color(tr("Farbe"), { it.color }, { o, v -> o.copy(color = v) }),
            opacity({ it.opacity }, { o, v -> o.copy(opacity = v) }),
        ),
    )

    private val gradientOverlay = Effect(
        "gradientOverlay", tr("Verlaufsüberlagerung"), LayerEffects.GradientOverlay(),
        { it.gradientOverlay }, { fx, v -> fx.copy(gradientOverlay = v) },
        listOf(
            Param.Blend(tr("Mischmodus"), { it.blend }, { g, v -> g.copy(blend = v) }),
            opacity({ it.opacity }, { g, v -> g.copy(opacity = v) }),
            Param.Color(tr("Startfarbe"), { it.color0 }, { g, v -> g.copy(color0 = v) }),
            Param.Color(tr("Endfarbe"), { it.color1 }, { g, v -> g.copy(color1 = v) }),
            Param.Toggle(tr("Verlauf umkehren"), { it.reverse }, { g, v -> g.copy(reverse = v) }),
            Param.Choice(tr("Stil"), listOf(tr("Linear"), tr("Radial")), { if (it.radial) 1 else 0 }, { g, v -> g.copy(radial = v == 1) }),
            angle({ it.angle }, { g, v -> g.copy(angle = v) }),
            Param.Slider(tr("Skalieren"), 10.0, 150.0, "%", { it.scale * 100.0 }, { g, v -> g.copy(scale = v / 100.0) }),
        ),
    )

    private val stroke = Effect(
        "stroke", tr("Kontur"), LayerEffects.Stroke(0x000000, 1f, BlendMode.NORMAL, 3.0, StrokePosition.OUTSIDE),
        { it.strokes.firstOrNull() }, { fx, v -> fx.copy(strokes = listOfNotNull(v) + fx.strokes.drop(1)) },
        listOf(
            Param.Slider(tr("Größe"), 1.0, 250.0, "px", { it.size }, { k, v -> k.copy(size = v) }),
            Param.Choice(tr("Position"), listOf(tr("Außen"), tr("Innen"), tr("Mitte")), { it.position.ordinal }, { k, v -> k.copy(position = StrokePosition.entries[v]) }),
            Param.Blend(tr("Mischmodus"), { it.blend }, { k, v -> k.copy(blend = v) }),
            opacity({ it.opacity }, { k, v -> k.copy(opacity = v) }),
            Param.Color(tr("Farbe"), { it.color }, { k, v -> k.copy(color = v) }),
        ),
    )

    /** In der Reihenfolge von Photoshops Dialog. */
    val effects: List<Effect<*>> = listOf(bevel, stroke, innerShadow, innerGlow, satin, colorOverlay, gradientOverlay, outerGlow, dropShadow)

    /** Mischmodi, die für Effekte zur Wahl stehen (ohne „Hindurchwirken“). */
    val blendModes: List<BlendMode> = BlendMode.entries.filter { it != BlendMode.PASS_THROUGH }
}
