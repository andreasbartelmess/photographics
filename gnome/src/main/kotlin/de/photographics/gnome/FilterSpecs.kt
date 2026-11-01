package de.photographics.gnome

import de.photographics.core.tr
import de.photographics.core.adjust.Adjustment
import de.photographics.core.adjust.RgbMap
import de.photographics.core.filters.AddNoise
import de.photographics.core.filters.Clarity
import de.photographics.core.filters.Dehaze
import de.photographics.core.filters.DustAndScratches
import de.photographics.core.filters.Emboss
import de.photographics.core.filters.FindEdges
import de.photographics.core.filters.GaussianBlurFilter
import de.photographics.core.filters.HighPass
import de.photographics.core.filters.ImageFilter
import de.photographics.core.filters.LensDistortion
import de.photographics.core.filters.Median
import de.photographics.core.filters.Mosaic
import de.photographics.core.filters.MotionBlur
import de.photographics.core.filters.OilPaint
import de.photographics.core.filters.PixelAdjustment
import de.photographics.core.filters.RadialBlur
import de.photographics.core.filters.ReduceNoise
import de.photographics.core.filters.SkinSmooth
import de.photographics.core.filters.SmartSharpen
import de.photographics.core.filters.SurfaceBlur
import de.photographics.core.filters.TiltShift
import de.photographics.core.filters.Twirl
import de.photographics.core.filters.UnsharpMask
import de.photographics.core.filters.Vignette

// Filterbeschreibungen wie in der Android-App (app/…/ui/FilterSpecs.kt), ohne Material-Symbole.

/** Art eines Reglers in den Filter-/Korrektur-Panels. */
enum class ParamKind { SLIDER, SWITCH, HUE, CHOICE }

class FilterParam(
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val decimals: Int = 0,
    val kind: ParamKind = ParamKind.SLIDER,
    /** Beschriftungen für [ParamKind.CHOICE] (Wert = Index). */
    val choices: List<String> = emptyList(),
)

/** Beschreibt einen Filter für die generische Regler-Oberfläche. */
class FilterSpec(
    val label: String,
    val group: String,
    val params: List<FilterParam>,
    val build: (FloatArray) -> ImageFilter,
)

/** Korrektur als direkt angewendeter Filter. */
class AdjustmentFilter(private val adjustment: Adjustment) : PixelAdjustment() {
    override val label = adjustment.label
    private val map: RgbMap = adjustment.compile()
    override fun map(c: Int) = map.map(c and 0xFFFFFF)
}

private fun s(label: String, min: Float, max: Float, def: Float, dec: Int = 0) = FilterParam(label, min, max, def, dec)
private fun sw(label: String, def: Boolean) = FilterParam(label, 0f, 1f, if (def) 1f else 0f, kind = ParamKind.SWITCH)
private fun choice(label: String, vararg options: String) = FilterParam(label, 0f, (options.size - 1).toFloat(), 0f, kind = ParamKind.CHOICE, choices = options.toList())

/** Filter-Menü, gruppiert wie in Photoshop. */
val FilterSpecs = listOf(
    // Weichzeichnungsfilter
    FilterSpec(tr("Gaußscher Weichzeichner"), tr("Weichzeichnungsfilter"), listOf(s(tr("Radius (px)"), 0.5f, 100f, 4f, 1))) { GaussianBlurFilter(it[0]) },
    FilterSpec(tr("Bewegungsunschärfe"), tr("Weichzeichnungsfilter"), listOf(s(tr("Winkel (°)"), -90f, 90f, 0f), s(tr("Distanz (px)"), 1f, 200f, 20f))) { MotionBlur(it[0], it[1].toInt()) },
    FilterSpec(tr("Radialer Weichzeichner"), tr("Weichzeichnungsfilter"), listOf(s(tr("Stärke"), 1f, 100f, 15f), choice(tr("Methode"), tr("Kreisförmig"), tr("Strahlenförmig")))) { RadialBlur(it[0], it[1] >= 0.5f) },
    FilterSpec(tr("Matter machen"), tr("Weichzeichnungsfilter"), listOf(s(tr("Radius (px)"), 1f, 40f, 5f), s(tr("Schwellenwert"), 2f, 100f, 15f))) { SurfaceBlur(it[0].toInt(), it[1].toInt()) },
    FilterSpec(tr("Tilt-Shift (Miniatur)"), tr("Weichzeichnungsfilter"), listOf(s(tr("Unschärfe"), 1f, 40f, 10f, 1), s(tr("Position (%)"), 0f, 100f, 50f), s(tr("Schärfebereich (%)"), 2f, 60f, 15f))) {
        TiltShift(it[0], it[1] / 100f, it[2] / 100f)
    },
    // Scharfzeichnungsfilter
    FilterSpec(tr("Unscharf maskieren"), tr("Scharfzeichnungsfilter"),
        listOf(s(tr("Stärke (%)"), 0f, 500f, 80f), s(tr("Radius (px)"), 0.3f, 50f, 1.5f, 1), s(tr("Schwellenwert"), 0f, 64f, 2f))) { UnsharpMask(it[0], it[1], it[2].toInt()) },
    FilterSpec(tr("Selektiver Scharfzeichner"), tr("Scharfzeichnungsfilter"),
        listOf(s(tr("Stärke (%)"), 0f, 300f, 120f), s(tr("Radius (px)"), 0.3f, 10f, 1.2f, 1), s(tr("Rauschen reduzieren (%)"), 0f, 100f, 20f))) { SmartSharpen(it[0], it[1], it[2]) },
    FilterSpec(tr("Hochpass"), tr("Sonstige Filter"), listOf(s(tr("Radius (px)"), 0.5f, 100f, 6f, 1))) { HighPass(it[0]) },
    // Rauschfilter
    FilterSpec(tr("Rauschen reduzieren"), tr("Rauschfilter"), listOf(s(tr("Stärke"), 0f, 100f, 40f))) { ReduceNoise(it[0]) },
    FilterSpec(tr("Median"), tr("Rauschfilter"), listOf(s(tr("Radius (px)"), 1f, 20f, 2f))) { Median(it[0].toInt()) },
    FilterSpec(tr("Staub & Kratzer"), tr("Rauschfilter"), listOf(s(tr("Radius (px)"), 1f, 20f, 2f), s(tr("Schwellenwert"), 0f, 128f, 20f))) { DustAndScratches(it[0].toInt(), it[1].toInt()) },
    FilterSpec(tr("Rauschen hinzufügen"), tr("Rauschfilter"), listOf(s(tr("Stärke (%)"), 0.5f, 50f, 6f, 1), sw(tr("Gaußsche Verteilung"), true), sw(tr("Monochromatisch"), true))) {
        AddNoise(it[0], it[1] >= 0.5f, it[2] >= 0.5f)
    },
    // Fotografisch
    FilterSpec(tr("Haut glätten"), tr("Retusche"), listOf(s(tr("Glättung"), 0f, 100f, 50f), s(tr("Struktur erhalten (%)"), 0f, 100f, 40f))) { SkinSmooth(it[0], it[1]) },
    FilterSpec(tr("Klarheit"), tr("Retusche"), listOf(s(tr("Stärke"), -100f, 100f, 30f))) { Clarity(it[0]) },
    FilterSpec(tr("Dunst entfernen"), tr("Retusche"), listOf(s(tr("Stärke"), 0f, 100f, 40f))) { Dehaze(it[0]) },
    FilterSpec(tr("Vignette"), tr("Objektiv"), listOf(s(tr("Stärke"), -100f, 100f, -40f), s(tr("Mittelpunkt (%)"), 0f, 100f, 50f))) { Vignette(it[0], it[1]) },
    FilterSpec(tr("Objektivkorrektur (Verzeichnung)"), tr("Objektiv"), listOf(s(tr("Verzeichnung"), -100f, 100f, 0f))) { LensDistortion(it[0]) },
    // Stilisierungsfilter
    FilterSpec(tr("Ölfarbe"), tr("Stilisierungsfilter"), listOf(s(tr("Pinselgröße"), 1f, 12f, 4f))) { OilPaint(it[0].toInt()) },
    FilterSpec(tr("Relief"), tr("Stilisierungsfilter"), listOf(s(tr("Winkel (°)"), -180f, 180f, 135f), s(tr("Höhe (px)"), 1f, 10f, 3f), s(tr("Stärke (%)"), 1f, 500f, 100f))) {
        Emboss(it[0], it[1].toInt(), it[2])
    },
    FilterSpec(tr("Konturen finden"), tr("Stilisierungsfilter"), emptyList()) { FindEdges() },
    FilterSpec(tr("Mosaik"), tr("Vergröberungsfilter"), listOf(s(tr("Zellengröße (px)"), 2f, 200f, 16f))) { Mosaic(it[0].toInt()) },
    FilterSpec(tr("Strudel"), tr("Verzerrungsfilter"), listOf(s(tr("Winkel (°)"), -999f, 999f, 180f))) { Twirl(it[0]) },
)
