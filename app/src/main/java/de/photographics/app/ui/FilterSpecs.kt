package de.photographics.app.ui

import de.photographics.core.tr
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

// Filterbeschreibungen liegen im Core (de.photographics.core.filters.FilterCatalog), gemeinsam mit GNOME;
// hier nur die Material-Symbole dazu.
typealias FilterSpec = de.photographics.core.filters.FilterSpec
typealias FilterParam = de.photographics.core.filters.FilterParam
typealias ParamKind = de.photographics.core.filters.ParamKind
typealias AdjustmentFilter = de.photographics.core.filters.AdjustmentFilter

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

private val filterIcons: Map<String, ImageVector> = mapOf(
    "gaussian-blur" to Icons.Filled.BlurOn,
    "motion-blur" to Icons.Filled.BlurLinear,
    "radial-blur" to Icons.Filled.BlurCircular,
    "surface-blur" to Icons.Filled.BlurOn,
    "tilt-shift" to Icons.Filled.FilterTiltShift,
    "unsharp-mask" to Icons.Filled.CenterFocusStrong,
    "smart-sharpen" to Icons.Filled.CenterFocusStrong,
    "high-pass" to Icons.Filled.Tune,
    "reduce-noise" to Icons.Filled.Grain,
    "median" to Icons.Filled.Grain,
    "dust-scratches" to Icons.Filled.Grain,
    "add-noise" to Icons.Filled.Grain,
    "skin-smooth" to Icons.Filled.Face,
    "clarity" to Icons.Filled.WbSunny,
    "dehaze" to Icons.Filled.Cloud,
    "vignette" to Icons.Filled.Vignette,
    "lens-distortion" to Icons.Filled.Lens,
    "oil-paint" to Icons.Filled.FilterVintage,
    "emboss" to Icons.Filled.FilterVintage,
    "find-edges" to Icons.Filled.FilterVintage,
    "mosaic" to Icons.Filled.GridOn,
    "twirl" to Icons.Filled.BlurCircular,
)

/** Symbol eines Filters (Korrekturen: Symbol des Korrekturtyps). */
val FilterSpec.icon: ImageVector
    get() = filterIcons[id]
        ?: AdjustmentType.entries.firstOrNull { "adjust:${it.name}" == id }?.icon
        ?: Icons.Filled.Tune

/** Filter-Menü, gruppiert wie in Photoshop. */
val FilterSpecs: List<FilterSpec> get() = de.photographics.core.filters.FilterCatalog.specs
