package de.photographics.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.BlurLinear
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.FilterTiltShift
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import de.photographics.core.adjust.Adjustment
import de.photographics.core.adjust.AdjustmentType
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
    val icon: ImageVector,
    val params: List<FilterParam>,
    val build: (FloatArray) -> ImageFilter,
)

/** Symbol je Korrekturtyp (Einstellungsebenen und direkte Korrekturen). */
val AdjustmentType.icon: ImageVector
    get() = when (this) {
        AdjustmentType.BRIGHTNESS_CONTRAST -> Icons.Filled.BrightnessMedium
        AdjustmentType.LEVELS -> Icons.Filled.BarChart
        AdjustmentType.CURVES -> Icons.Filled.ShowChart
        AdjustmentType.EXPOSURE -> Icons.Filled.Exposure
        AdjustmentType.VIBRANCE -> Icons.Filled.AutoAwesome
        AdjustmentType.HUE_SATURATION -> Icons.Filled.Palette
        AdjustmentType.COLOR_BALANCE -> Icons.Filled.Tune
        AdjustmentType.BLACK_WHITE -> Icons.Filled.FilterBAndW
        AdjustmentType.PHOTO_FILTER -> Icons.Filled.CameraAlt
        AdjustmentType.INVERT -> Icons.Filled.InvertColors
        AdjustmentType.POSTERIZE -> Icons.Filled.Tonality
        AdjustmentType.THRESHOLD -> Icons.Filled.Contrast
        AdjustmentType.COLOR_LOOKUP -> Icons.Filled.Gradient
    }

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
    FilterSpec("Gaußscher Weichzeichner", "Weichzeichnungsfilter", Icons.Filled.BlurOn, listOf(s("Radius (px)", 0.5f, 100f, 4f, 1))) { GaussianBlurFilter(it[0]) },
    FilterSpec("Bewegungsunschärfe", "Weichzeichnungsfilter", Icons.Filled.BlurLinear, listOf(s("Winkel (°)", -90f, 90f, 0f), s("Distanz (px)", 1f, 200f, 20f))) { MotionBlur(it[0], it[1].toInt()) },
    FilterSpec("Radialer Weichzeichner", "Weichzeichnungsfilter", Icons.Filled.BlurCircular, listOf(s("Stärke", 1f, 100f, 15f), choice("Methode", "Kreisförmig", "Strahlenförmig"))) { RadialBlur(it[0], it[1] >= 0.5f) },
    FilterSpec("Matter machen", "Weichzeichnungsfilter", Icons.Filled.BlurOn, listOf(s("Radius (px)", 1f, 40f, 5f), s("Schwellenwert", 2f, 100f, 15f))) { SurfaceBlur(it[0].toInt(), it[1].toInt()) },
    FilterSpec("Tilt-Shift (Miniatur)", "Weichzeichnungsfilter", Icons.Filled.FilterTiltShift, listOf(s("Unschärfe", 1f, 40f, 10f, 1), s("Position (%)", 0f, 100f, 50f), s("Schärfebereich (%)", 2f, 60f, 15f))) {
        TiltShift(it[0], it[1] / 100f, it[2] / 100f)
    },
    // Scharfzeichnungsfilter
    FilterSpec("Unscharf maskieren", "Scharfzeichnungsfilter", Icons.Filled.CenterFocusStrong,
        listOf(s("Stärke (%)", 0f, 500f, 80f), s("Radius (px)", 0.3f, 50f, 1.5f, 1), s("Schwellenwert", 0f, 64f, 2f))) { UnsharpMask(it[0], it[1], it[2].toInt()) },
    FilterSpec("Selektiver Scharfzeichner", "Scharfzeichnungsfilter", Icons.Filled.CenterFocusStrong,
        listOf(s("Stärke (%)", 0f, 300f, 120f), s("Radius (px)", 0.3f, 10f, 1.2f, 1), s("Rauschen reduzieren (%)", 0f, 100f, 20f))) { SmartSharpen(it[0], it[1], it[2]) },
    FilterSpec("Hochpass", "Sonstige Filter", Icons.Filled.Tune, listOf(s("Radius (px)", 0.5f, 100f, 6f, 1))) { HighPass(it[0]) },
    // Rauschfilter
    FilterSpec("Rauschen reduzieren", "Rauschfilter", Icons.Filled.Grain, listOf(s("Stärke", 0f, 100f, 40f))) { ReduceNoise(it[0]) },
    FilterSpec("Median", "Rauschfilter", Icons.Filled.Grain, listOf(s("Radius (px)", 1f, 20f, 2f))) { Median(it[0].toInt()) },
    FilterSpec("Staub & Kratzer", "Rauschfilter", Icons.Filled.Grain, listOf(s("Radius (px)", 1f, 20f, 2f), s("Schwellenwert", 0f, 128f, 20f))) { DustAndScratches(it[0].toInt(), it[1].toInt()) },
    FilterSpec("Rauschen hinzufügen", "Rauschfilter", Icons.Filled.Grain, listOf(s("Stärke (%)", 0.5f, 50f, 6f, 1), sw("Gaußsche Verteilung", true), sw("Monochromatisch", true))) {
        AddNoise(it[0], it[1] >= 0.5f, it[2] >= 0.5f)
    },
    // Fotografisch
    FilterSpec("Haut glätten", "Retusche", Icons.Filled.Face, listOf(s("Glättung", 0f, 100f, 50f), s("Struktur erhalten (%)", 0f, 100f, 40f))) { SkinSmooth(it[0], it[1]) },
    FilterSpec("Klarheit", "Retusche", Icons.Filled.WbSunny, listOf(s("Stärke", -100f, 100f, 30f))) { Clarity(it[0]) },
    FilterSpec("Dunst entfernen", "Retusche", Icons.Filled.Cloud, listOf(s("Stärke", 0f, 100f, 40f))) { Dehaze(it[0]) },
    FilterSpec("Vignette", "Objektiv", Icons.Filled.Vignette, listOf(s("Stärke", -100f, 100f, -40f), s("Mittelpunkt (%)", 0f, 100f, 50f))) { Vignette(it[0], it[1]) },
    FilterSpec("Objektivkorrektur (Verzeichnung)", "Objektiv", Icons.Filled.Lens, listOf(s("Verzeichnung", -100f, 100f, 0f))) { LensDistortion(it[0]) },
    // Stilisierungsfilter
    FilterSpec("Ölfarbe", "Stilisierungsfilter", Icons.Filled.FilterVintage, listOf(s("Pinselgröße", 1f, 12f, 4f))) { OilPaint(it[0].toInt()) },
    FilterSpec("Relief", "Stilisierungsfilter", Icons.Filled.FilterVintage, listOf(s("Winkel (°)", -180f, 180f, 135f), s("Höhe (px)", 1f, 10f, 3f), s("Stärke (%)", 1f, 500f, 100f))) {
        Emboss(it[0], it[1].toInt(), it[2])
    },
    FilterSpec("Konturen finden", "Stilisierungsfilter", Icons.Filled.FilterVintage, emptyList()) { FindEdges() },
    FilterSpec("Mosaik", "Vergröberungsfilter", Icons.Filled.GridOn, listOf(s("Zellengröße (px)", 2f, 200f, 16f))) { Mosaic(it[0].toInt()) },
    FilterSpec("Strudel", "Verzerrungsfilter", Icons.Filled.BlurCircular, listOf(s("Winkel (°)", -999f, 999f, 180f))) { Twirl(it[0]) },
)
