package de.photographics.core.filters

import de.photographics.core.adjust.Adjustment
import de.photographics.core.adjust.RgbMap
import de.photographics.core.tr

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

/**
 * Beschreibt einen Filter für die generische Regler-Oberfläche. [id] ist eine feste Kennung (auch in
 * gespeicherten Smartfiltern), [build] erzeugt den Filter aus den Reglerwerten.
 */
class FilterSpec(
    val id: String,
    val label: String,
    val group: String,
    val params: List<FilterParam>,
    val build: (FloatArray) -> ImageFilter,
) {
    val defaults: FloatArray get() = FloatArray(params.size) { params[it].default }
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

/** Alle Filter mit Reglern, gruppiert wie in Photoshop – gemeinsam für Android und GNOME. */
object FilterCatalog {
    /** Filter-Menü. */
    val specs: List<FilterSpec> = listOf(
        // Weichzeichnungsfilter
        FilterSpec("gaussian-blur", tr("Gaußscher Weichzeichner"), tr("Weichzeichnungsfilter"), listOf(s(tr("Radius (px)"), 0.5f, 100f, 4f, 1))) { GaussianBlurFilter(it[0]) },
        FilterSpec("motion-blur", tr("Bewegungsunschärfe"), tr("Weichzeichnungsfilter"), listOf(s(tr("Winkel (°)"), -90f, 90f, 0f), s(tr("Distanz (px)"), 1f, 200f, 20f))) { MotionBlur(it[0], it[1].toInt()) },
        FilterSpec("radial-blur", tr("Radialer Weichzeichner"), tr("Weichzeichnungsfilter"), listOf(s(tr("Stärke"), 1f, 100f, 15f), choice(tr("Methode"), tr("Kreisförmig"), tr("Strahlenförmig")))) { RadialBlur(it[0], it[1] >= 0.5f) },
        FilterSpec("surface-blur", tr("Matter machen"), tr("Weichzeichnungsfilter"), listOf(s(tr("Radius (px)"), 1f, 40f, 5f), s(tr("Schwellenwert"), 2f, 100f, 15f))) { SurfaceBlur(it[0].toInt(), it[1].toInt()) },
        FilterSpec("tilt-shift", tr("Tilt-Shift (Miniatur)"), tr("Weichzeichnungsfilter"), listOf(s(tr("Unschärfe"), 1f, 40f, 10f, 1), s(tr("Position (%)"), 0f, 100f, 50f), s(tr("Schärfebereich (%)"), 2f, 60f, 15f))) {
            TiltShift(it[0], it[1] / 100f, it[2] / 100f)
        },
        // Scharfzeichnungsfilter
        FilterSpec("unsharp-mask", tr("Unscharf maskieren"), tr("Scharfzeichnungsfilter"),
            listOf(s(tr("Stärke (%)"), 0f, 500f, 80f), s(tr("Radius (px)"), 0.3f, 50f, 1.5f, 1), s(tr("Schwellenwert"), 0f, 64f, 2f))) { UnsharpMask(it[0], it[1], it[2].toInt()) },
        FilterSpec("smart-sharpen", tr("Selektiver Scharfzeichner"), tr("Scharfzeichnungsfilter"),
            listOf(s(tr("Stärke (%)"), 0f, 300f, 120f), s(tr("Radius (px)"), 0.3f, 10f, 1.2f, 1), s(tr("Rauschen reduzieren (%)"), 0f, 100f, 20f))) { SmartSharpen(it[0], it[1], it[2]) },
        FilterSpec("high-pass", tr("Hochpass"), tr("Sonstige Filter"), listOf(s(tr("Radius (px)"), 0.5f, 100f, 6f, 1))) { HighPass(it[0]) },
        // Rauschfilter
        FilterSpec("reduce-noise", tr("Rauschen reduzieren"), tr("Rauschfilter"), listOf(s(tr("Stärke"), 0f, 100f, 40f))) { ReduceNoise(it[0]) },
        FilterSpec("median", tr("Median"), tr("Rauschfilter"), listOf(s(tr("Radius (px)"), 1f, 20f, 2f))) { Median(it[0].toInt()) },
        FilterSpec("dust-scratches", tr("Staub & Kratzer"), tr("Rauschfilter"), listOf(s(tr("Radius (px)"), 1f, 20f, 2f), s(tr("Schwellenwert"), 0f, 128f, 20f))) { DustAndScratches(it[0].toInt(), it[1].toInt()) },
        FilterSpec("add-noise", tr("Rauschen hinzufügen"), tr("Rauschfilter"), listOf(s(tr("Stärke (%)"), 0.5f, 50f, 6f, 1), sw(tr("Gaußsche Verteilung"), true), sw(tr("Monochromatisch"), true))) {
            AddNoise(it[0], it[1] >= 0.5f, it[2] >= 0.5f)
        },
        // Fotografisch
        FilterSpec("skin-smooth", tr("Haut glätten"), tr("Retusche"), listOf(s(tr("Glättung"), 0f, 100f, 50f), s(tr("Struktur erhalten (%)"), 0f, 100f, 40f))) { SkinSmooth(it[0], it[1]) },
        FilterSpec("clarity", tr("Klarheit"), tr("Retusche"), listOf(s(tr("Stärke"), -100f, 100f, 30f))) { Clarity(it[0]) },
        FilterSpec("dehaze", tr("Dunst entfernen"), tr("Retusche"), listOf(s(tr("Stärke"), 0f, 100f, 40f))) { Dehaze(it[0]) },
        FilterSpec("vignette", tr("Vignette"), tr("Objektiv"), listOf(s(tr("Stärke"), -100f, 100f, -40f), s(tr("Mittelpunkt (%)"), 0f, 100f, 50f))) { Vignette(it[0], it[1]) },
        FilterSpec("lens-distortion", tr("Objektivkorrektur (Verzeichnung)"), tr("Objektiv"), listOf(s(tr("Verzeichnung"), -100f, 100f, 0f))) { LensDistortion(it[0]) },
        // Stilisierungsfilter
        FilterSpec("oil-paint", tr("Ölfarbe"), tr("Stilisierungsfilter"), listOf(s(tr("Pinselgröße"), 1f, 12f, 4f))) { OilPaint(it[0].toInt()) },
        FilterSpec("emboss", tr("Relief"), tr("Stilisierungsfilter"), listOf(s(tr("Winkel (°)"), -180f, 180f, 135f), s(tr("Höhe (px)"), 1f, 10f, 3f), s(tr("Stärke (%)"), 1f, 500f, 100f))) {
            Emboss(it[0], it[1].toInt(), it[2])
        },
        FilterSpec("find-edges", tr("Konturen finden"), tr("Stilisierungsfilter"), emptyList()) { FindEdges() },
        FilterSpec("mosaic", tr("Mosaik"), tr("Vergröberungsfilter"), listOf(s(tr("Zellengröße (px)"), 2f, 200f, 16f))) { Mosaic(it[0].toInt()) },
        FilterSpec("twirl", tr("Strudel"), tr("Verzerrungsfilter"), listOf(s(tr("Winkel (°)"), -999f, 999f, 180f))) { Twirl(it[0]) },
    )

    /** Direkte Korrektur (Bild → Korrekturen) als Filter: Kennung `adjust:<Typ>`. */
    fun adjustment(t: de.photographics.core.adjust.AdjustmentType): FilterSpec {
        val def = t.createDefault()
        val params = def.params().map { p ->
            FilterParam(p.label, p.min, p.max, p.value, p.decimals, when (p.kind) {
                de.photographics.core.adjust.AdjParam.Kind.SWITCH -> ParamKind.SWITCH
                de.photographics.core.adjust.AdjParam.Kind.HUE -> ParamKind.HUE
                else -> ParamKind.SLIDER
            })
        }
        return FilterSpec("adjust:${t.name}", t.label, tr("Korrekturen"), params) { v -> AdjustmentFilter(def.withValues(v)) }
    }

    /** Filter zu einer Kennung (auch `adjust:<Typ>`), null = unbekannt (z. B. aus einer neueren Version). */
    fun byId(id: String): FilterSpec? = specs.firstOrNull { it.id == id }
        ?: id.removePrefix("adjust:").takeIf { id.startsWith("adjust:") }?.let { n ->
            de.photographics.core.adjust.AdjustmentType.entries.firstOrNull { it.name == n }?.let { adjustment(it) }
        }
}
