package de.photographics.core.tools

import de.photographics.core.tr
import de.photographics.core.image.Colors
import de.photographics.core.render.BlendMode
import kotlin.math.roundToInt

/** Steuerung einer Pinsel-Eigenschaft (Photoshop: „Steuerung“ in den Pinseleinstellungen). */
enum class DynControl(val label: String) { OFF(tr("Aus")), PRESSURE(tr("Stiftdruck")), FADE(tr("Verblassen")) }

enum class AngleControl(val label: String) { OFF(tr("Aus")), DIRECTION(tr("Strichrichtung")) }

/** Malmodus des Pinsels: alle Mischmodi plus „Dahinter auftragen“ und „Löschen“. */
enum class BrushMode(val blend: BlendMode?, private val ownLabel: String? = null) {
    NORMAL(BlendMode.NORMAL), BEHIND(null, tr("Dahinter auftragen")), CLEAR(null, tr("Löschen")),
    DARKEN(BlendMode.DARKEN), MULTIPLY(BlendMode.MULTIPLY), COLOR_BURN(BlendMode.COLOR_BURN), LINEAR_BURN(BlendMode.LINEAR_BURN),
    LIGHTEN(BlendMode.LIGHTEN), SCREEN(BlendMode.SCREEN), COLOR_DODGE(BlendMode.COLOR_DODGE), LINEAR_DODGE(BlendMode.LINEAR_DODGE),
    OVERLAY(BlendMode.OVERLAY), SOFT_LIGHT(BlendMode.SOFT_LIGHT), HARD_LIGHT(BlendMode.HARD_LIGHT), VIVID_LIGHT(BlendMode.VIVID_LIGHT),
    LINEAR_LIGHT(BlendMode.LINEAR_LIGHT), PIN_LIGHT(BlendMode.PIN_LIGHT), HARD_MIX(BlendMode.HARD_MIX),
    DIFFERENCE(BlendMode.DIFFERENCE), EXCLUSION(BlendMode.EXCLUSION), SUBTRACT(BlendMode.SUBTRACT), DIVIDE(BlendMode.DIVIDE),
    HUE(BlendMode.HUE), SATURATION(BlendMode.SATURATION), COLOR(BlendMode.COLOR), LUMINOSITY(BlendMode.LUMINOSITY);

    val label: String get() = ownLabel ?: blend!!.label

    /** Farbe [color] mit Deckung [amount] (0..1) auf [orig] auftragen. */
    fun paint(orig: Int, color: Int, amount: Float): Int {
        val a = (Colors.a(color) * amount).roundToInt()
        return when (this) {
            NORMAL -> Colors.srcOver(orig, Colors.withAlpha(color, a))
            BEHIND -> Colors.srcOver(Colors.withAlpha(color, a), orig)
            CLEAR -> Colors.withAlpha(orig, (Colors.a(orig) * (1f - amount)).roundToInt())
            else -> {
                val ob = Colors.a(orig) / 255f
                if (ob <= 0f) return Colors.srcOver(orig, Colors.withAlpha(color, a))
                val cbR = Colors.r(orig) / 255f; val cbG = Colors.g(orig) / 255f; val cbB = Colors.b(orig) / 255f
                val csR = Colors.r(color) / 255f; val csG = Colors.g(color) / 255f; val csB = Colors.b(color) / 255f
                val m = blend!!
                val out = FloatArray(3)
                if (m.separable) { out[0] = m.blendChannel(cbR, csR); out[1] = m.blendChannel(cbG, csG); out[2] = m.blendChannel(cbB, csB) }
                else m.blendNonSeparable(cbR, cbG, cbB, csR, csG, csB, out)
                // W3C: über transparentem Untergrund wirkt die reine Quellfarbe
                val mixed = Colors.argbF(1f, csR + (out[0] - csR) * ob, csG + (out[1] - csG) * ob, csB + (out[2] - csB) * ob)
                Colors.srcOver(orig, Colors.withAlpha(mixed, a))
            }
        }
    }
}

/**
 * Pinsel-Einstellungen wie in Photoshops Pinsel-Bedienfeld (F5). Sind alle Dynamiken aus, malt
 * der Pinsel genau wie die einfache Spitze (Größe, Härte, Deckkraft, Fluss, Abstand, Glättung).
 */
data class BrushSettings(
    /** Durchmesser in Dokumentpixeln. */
    val size: Float = 40f,
    /** 0 = sehr weich, 1 = harte Kante. */
    val hardness: Float = 0.7f,
    /** Maximale Deckkraft pro Strich. */
    val opacity: Float = 1f,
    /** Farbauftrag pro Pinselabdruck. */
    val flow: Float = 1f,
    /** Abstand der Abdrücke relativ zum Durchmesser. */
    val spacing: Float = 0.15f,
    /** Strichglättung 0..0.95: Pinsel folgt dem Zeiger verzögert (gleicht Zittern aus). */
    val smoothing: Float = 0.25f,
    // Pinselspitze
    /** Winkel der Spitze in Grad. */
    val angle: Float = 0f,
    /** Rundheit 0.05..1 (1 = Kreis). */
    val roundness: Float = 1f,
    // Formeigenschaften
    val sizeJitter: Float = 0f,
    val sizeControl: DynControl = DynControl.PRESSURE,
    /** Mindestdurchmesser bei Steuerung (Anteil). */
    val minSize: Float = 0.25f,
    val angleJitter: Float = 0f,
    val angleControl: AngleControl = AngleControl.OFF,
    val roundnessJitter: Float = 0f,
    val minRoundness: Float = 0.25f,
    // Streuung
    /** Streuung in Pinseldurchmessern (0..10). */
    val scatter: Float = 0f,
    val scatterBothAxes: Boolean = false,
    /** Abdrücke je Position (1..16). */
    val count: Int = 1,
    val countJitter: Float = 0f,
    // Übertragung
    val opacityJitter: Float = 0f,
    val opacityControl: DynControl = DynControl.OFF,
    val flowJitter: Float = 0f,
    val flowControl: DynControl = DynControl.OFF,
    // Farbeinstellungen (je Spitze)
    val fgBgJitter: Float = 0f,
    val hueJitter: Float = 0f,
    val saturationJitter: Float = 0f,
    val brightnessJitter: Float = 0f,
    // Sonstiges
    val mode: BrushMode = BrushMode.NORMAL,
    /** Airbrush: Farbe baut sich auch bei ruhendem Stift weiter auf. */
    val airbrush: Boolean = false,
    /** Schritte (Abdrücke) für die Steuerung „Verblassen“. */
    val fadeSteps: Int = 25,
) {
    val hasColorDynamics get() = fgBgJitter > 0f || hueJitter > 0f || saturationJitter > 0f || brightnessJitter > 0f

    /** Als Text (für gespeicherte Vorgaben); unbekannte Schlüssel werden beim Lesen ignoriert. */
    fun encode(): String = fields().joinToString(";") { (k, v) -> "$k=$v" }

    private fun fields(): List<Pair<String, Any>> = listOf(
        "size" to size, "hardness" to hardness, "opacity" to opacity, "flow" to flow, "spacing" to spacing, "smoothing" to smoothing,
        "angle" to angle, "roundness" to roundness, "sizeJitter" to sizeJitter, "sizeControl" to sizeControl.name, "minSize" to minSize,
        "angleJitter" to angleJitter, "angleControl" to angleControl.name, "roundnessJitter" to roundnessJitter, "minRoundness" to minRoundness,
        "scatter" to scatter, "scatterBothAxes" to scatterBothAxes, "count" to count, "countJitter" to countJitter,
        "opacityJitter" to opacityJitter, "opacityControl" to opacityControl.name, "flowJitter" to flowJitter, "flowControl" to flowControl.name,
        "fgBgJitter" to fgBgJitter, "hueJitter" to hueJitter, "saturationJitter" to saturationJitter, "brightnessJitter" to brightnessJitter,
        "mode" to mode.name, "airbrush" to airbrush, "fadeSteps" to fadeSteps,
    )

    companion object {
        fun decode(s: String): BrushSettings {
            val m = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val d = BrushSettings()
            fun f(k: String, def: Float) = m[k]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: def
            fun i(k: String, def: Int) = m[k]?.toIntOrNull() ?: def
            fun b(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
            fun <E : Enum<E>> e(k: String, def: E, all: Array<E>) = all.firstOrNull { it.name == m[k] } ?: def
            return BrushSettings(
                size = f("size", d.size).coerceIn(1f, 5000f), hardness = f("hardness", d.hardness).coerceIn(0f, 1f),
                opacity = f("opacity", d.opacity).coerceIn(0.01f, 1f), flow = f("flow", d.flow).coerceIn(0.01f, 1f),
                spacing = f("spacing", d.spacing).coerceIn(0.01f, 10f), smoothing = f("smoothing", d.smoothing).coerceIn(0f, 0.95f),
                angle = f("angle", d.angle), roundness = f("roundness", d.roundness).coerceIn(0.05f, 1f),
                sizeJitter = f("sizeJitter", 0f).coerceIn(0f, 1f), sizeControl = e("sizeControl", d.sizeControl, DynControl.entries.toTypedArray()),
                minSize = f("minSize", d.minSize).coerceIn(0f, 1f), angleJitter = f("angleJitter", 0f).coerceIn(0f, 1f),
                angleControl = e("angleControl", d.angleControl, AngleControl.entries.toTypedArray()),
                roundnessJitter = f("roundnessJitter", 0f).coerceIn(0f, 1f), minRoundness = f("minRoundness", d.minRoundness).coerceIn(0.05f, 1f),
                scatter = f("scatter", 0f).coerceIn(0f, 10f), scatterBothAxes = b("scatterBothAxes", false),
                count = i("count", 1).coerceIn(1, 16), countJitter = f("countJitter", 0f).coerceIn(0f, 1f),
                opacityJitter = f("opacityJitter", 0f).coerceIn(0f, 1f), opacityControl = e("opacityControl", d.opacityControl, DynControl.entries.toTypedArray()),
                flowJitter = f("flowJitter", 0f).coerceIn(0f, 1f), flowControl = e("flowControl", d.flowControl, DynControl.entries.toTypedArray()),
                fgBgJitter = f("fgBgJitter", 0f).coerceIn(0f, 1f), hueJitter = f("hueJitter", 0f).coerceIn(0f, 1f),
                saturationJitter = f("saturationJitter", 0f).coerceIn(0f, 1f), brightnessJitter = f("brightnessJitter", 0f).coerceIn(0f, 1f),
                mode = e("mode", d.mode, BrushMode.entries.toTypedArray()), airbrush = b("airbrush", false),
                fadeSteps = i("fadeSteps", d.fadeSteps).coerceIn(1, 1000),
            )
        }
    }
}

/** Benannte Pinselvorgabe; [keepSize]: beim Auswählen die aktuelle Größe behalten. */
data class BrushPreset(val name: String, val settings: BrushSettings, val builtin: Boolean = false, val keepSize: Boolean = true)

/** Eingebaute Vorgaben – Ausgangspunkte für typische Arbeiten am Tablet. */
object BrushPresets {
    val builtin: List<BrushPreset> = listOf(
        BrushPreset("Hart rund", BrushSettings(hardness = 1f, spacing = 0.1f, smoothing = 0.2f), true),
        BrushPreset("Weich rund", BrushSettings(hardness = 0f, spacing = 0.1f), true),
        BrushPreset("Airbrush", BrushSettings(hardness = 0f, flow = 0.08f, spacing = 0.1f, airbrush = true, opacityControl = DynControl.PRESSURE), true),
        BrushPreset("Bleistift", BrushSettings(size = 4f, hardness = 1f, spacing = 0.05f, smoothing = 0f, minSize = 0.6f), true, keepSize = false),
        BrushPreset("Tusche", BrushSettings(size = 12f, hardness = 0.95f, spacing = 0.05f, smoothing = 0.55f, minSize = 0.05f), true, keepSize = false),
        BrushPreset("Kalligrafie", BrushSettings(hardness = 0.9f, roundness = 0.22f, angle = 45f, spacing = 0.04f, smoothing = 0.45f, minSize = 0.4f), true),
        BrushPreset("Marker", BrushSettings(hardness = 0.8f, roundness = 0.6f, angle = 30f, opacity = 0.85f, flow = 0.35f, spacing = 0.08f, sizeControl = DynControl.OFF), true),
        BrushPreset("Sprenkel", BrushSettings(hardness = 0.85f, spacing = 0.6f, sizeJitter = 0.7f, scatter = 2.5f, scatterBothAxes = true, count = 4, countJitter = 0.6f, sizeControl = DynControl.OFF), true),
        BrushPreset("Kreide", BrushSettings(hardness = 0.6f, flow = 0.5f, spacing = 0.2f, sizeJitter = 0.4f, angleJitter = 1f, roundness = 0.6f, roundnessJitter = 0.6f, scatter = 0.35f, flowJitter = 0.5f), true),
        BrushPreset("Laub", BrushSettings(hardness = 0.8f, roundness = 0.4f, spacing = 0.7f, sizeJitter = 0.8f, angleJitter = 1f, scatter = 3f, scatterBothAxes = true, count = 3, fgBgJitter = 1f, hueJitter = 0.08f, brightnessJitter = 0.2f, sizeControl = DynControl.OFF), true),
        BrushPreset("Retusche weich", BrushSettings(hardness = 0.3f, flow = 0.25f, spacing = 0.1f, opacityControl = DynControl.PRESSURE, sizeControl = DynControl.OFF), true),
        BrushPreset("Verblassend", BrushSettings(hardness = 0.9f, spacing = 0.05f, sizeControl = DynControl.FADE, minSize = 0f, opacityControl = DynControl.FADE, fadeSteps = 120), true),
    )
}
