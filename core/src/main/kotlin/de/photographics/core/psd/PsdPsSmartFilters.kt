package de.photographics.core.psd

import de.photographics.core.doc.SmartFilter
import de.photographics.core.doc.SmartFilters
import de.photographics.core.filters.FilterCatalog
import de.photographics.core.render.BlendMode
import de.photographics.core.tr
import kotlin.math.roundToInt

/**
 * Smartfilter im Photoshop-Format („filterFX“ im Smartobjekt-Block „SoLd“), damit Photoshop sie als
 * bearbeitbare Smartfilter zeigt – und umgekehrt Photoshops Smartfilter in photographics bearbeitbar sind.
 * Format, Kennungen, Werttypen und Einheiten aus Dateien von Photoshop 2026 übernommen.
 * Nur Filter mit Photoshop-Gegenstück; sonst null (die Ebene bleibt dann gerastert).
 */
object PsdPsSmartFilters {
    private class Mapping(
        val id: String,
        val code: String,
        val toPs: (List<Float>) -> List<Pair<String, Any>>,
        val fromPs: (PsdDescriptor) -> List<Float>?,
        /** Photoshops „filterID“; bei Kennungen aus vier Zeichen deren Zahlwert, sonst feste Nummer. */
        val filterId: Int? = null,
        /** Filter ohne Einstellungen (Photoshop schreibt dann kein „Fltr“). */
        val noOptions: Boolean = false,
    )

    private fun px(v: Float) = PsdDescriptor.UnitFloat("#Pxl", v.toDouble())
    private fun pct(v: Float) = PsdDescriptor.UnitFloat("#Prc", v.toDouble())
    private fun num(d: PsdDescriptor, k: String): Float? = d.double(k)?.toFloat()

    private val MAPPINGS = listOf(
        Mapping("gaussian-blur", "GsnB", { v -> listOf("Rds " to px(v[0])) }, { d -> num(d, "Rds ")?.let { listOf(it) } }),
        Mapping("unsharp-mask", "UnsM", { v -> listOf("Amnt" to pct(v[0]), "Rds " to px(v[1]), "Thsh" to v[2].toInt()) },
            { d -> listOf(num(d, "Amnt") ?: return@Mapping null, num(d, "Rds ") ?: return@Mapping null, num(d, "Thsh") ?: 0f) }),
        Mapping("add-noise", "AdNs", { v ->
            listOf("Dstr" to PsdDescriptor.EnumValue("Dstr", if (v[1] >= 0.5f) "Gsn " else "Unfr"), "Nose" to pct(v[0]), "Mnch" to (v[2] >= 0.5f))
        }, { d ->
            val gauss = (d["Dstr"] as? PsdDescriptor.EnumValue)?.value != "Unfr"
            listOf(num(d, "Nose") ?: return@Mapping null, if (gauss) 1f else 0f, if (d.bool("Mnch") == true) 1f else 0f)
        }),
        Mapping("median", "Mdn ", { v -> listOf("Rds " to px(v[0])) }, { d -> num(d, "Rds ")?.let { listOf(it) } }),
        Mapping("high-pass", "HghP", { v -> listOf("Rds " to px(v[0])) }, { d -> num(d, "Rds ")?.let { listOf(it) } }),
        Mapping("motion-blur", "MtnB", { v -> listOf("Angl" to v[0].toInt(), "Dstn" to px(v[1])) },
            { d -> listOf(num(d, "Angl") ?: return@Mapping null, num(d, "Dstn") ?: return@Mapping null) }),
        Mapping("dust-scratches", "DstS", { v -> listOf("Rds " to v[0].toInt(), "Thsh" to v[1].toInt()) },
            { d -> listOf(num(d, "Rds ") ?: return@Mapping null, num(d, "Thsh") ?: 0f) }),
        Mapping("radial-blur", "RdlB", { v ->
            listOf("Amnt" to v[0].toInt(), "BlrM" to PsdDescriptor.EnumValue("BlrM", if (v[1] >= 0.5f) "Zm  " else "Spn "), "BlrQ" to PsdDescriptor.EnumValue("BlrQ", "Gd  "))
        }, { d -> listOf(num(d, "Amnt") ?: return@Mapping null, if ((d["BlrM"] as? PsdDescriptor.EnumValue)?.value == "Zm  ") 1f else 0f) }),
        Mapping("twirl", "Twrl", { v -> listOf("Angl" to v[0].toInt()) }, { d -> num(d, "Angl")?.let { listOf(it) } }),
        Mapping("mosaic", "Msc ", { v -> listOf("ClSz" to px(v[0])) }, { d -> num(d, "ClSz")?.let { listOf(it) } }),
        Mapping("emboss", "Embs", { v -> listOf("Angl" to v[0].toInt(), "Hght" to v[1].toInt(), "Amnt" to v[2].toInt()) },
            { d -> listOf(num(d, "Angl") ?: return@Mapping null, num(d, "Hght") ?: return@Mapping null, num(d, "Amnt") ?: return@Mapping null) }),
        Mapping("find-edges", "FndE", { emptyList() }, { emptyList() }, noOptions = true),
        Mapping("surface-blur", "surfaceBlur", { v -> listOf("Rds " to px(v[0]), "Thsh" to v[1].toInt()) },
            { d -> listOf(num(d, "Rds ") ?: return@Mapping null, num(d, "Thsh") ?: return@Mapping null) }, filterId = 645),
        Mapping("smart-sharpen", "smartSharpen", { v ->
            listOf("Amnt" to pct(v[0]), "Rds " to px(v[1]), "noiseReduction" to pct(v[2]), "blur" to PsdDescriptor.EnumValue("blurType", "GsnB"))
        }, { d -> listOf(num(d, "Amnt") ?: return@Mapping null, num(d, "Rds ") ?: return@Mapping null, num(d, "noiseReduction") ?: 0f) }, filterId = 641),
    )
    private val BY_ID = MAPPINGS.associateBy { it.id }
    private val BY_CODE = MAPPINGS.associateBy { it.code }
    private val BY_FILTER_ID by lazy { MAPPINGS.associateBy { it.filterId ?: fourCc(it.code) } }

    /** Photoshops Füllmethoden-Kennungen (stringID) in Smartfilter-Fülloptionen. */
    private val BLEND = mapOf(
        BlendMode.NORMAL to "normal", BlendMode.DISSOLVE to "dissolve", BlendMode.DARKEN to "darken", BlendMode.MULTIPLY to "multiply",
        BlendMode.COLOR_BURN to "colorBurn", BlendMode.LINEAR_BURN to "linearBurn", BlendMode.DARKER_COLOR to "darkerColor",
        BlendMode.LIGHTEN to "lighten", BlendMode.SCREEN to "screen", BlendMode.COLOR_DODGE to "colorDodge", BlendMode.LINEAR_DODGE to "linearDodge",
        BlendMode.LIGHTER_COLOR to "lighterColor", BlendMode.OVERLAY to "overlay", BlendMode.SOFT_LIGHT to "softLight", BlendMode.HARD_LIGHT to "hardLight",
        BlendMode.VIVID_LIGHT to "vividLight", BlendMode.LINEAR_LIGHT to "linearLight", BlendMode.PIN_LIGHT to "pinLight", BlendMode.HARD_MIX to "hardMix",
        BlendMode.DIFFERENCE to "difference", BlendMode.EXCLUSION to "exclusion", BlendMode.SUBTRACT to "subtract", BlendMode.DIVIDE to "divide",
        BlendMode.HUE to "hue", BlendMode.SATURATION to "saturation", BlendMode.COLOR to "color", BlendMode.LUMINOSITY to "luminosity",
    )
    /** Ältere Dateien schreiben teils die kurzen Kennungen (charID). */
    private val BLEND_CHAR = mapOf(
        "Nrml" to BlendMode.NORMAL, "Dslv" to BlendMode.DISSOLVE, "Drkn" to BlendMode.DARKEN, "Mltp" to BlendMode.MULTIPLY, "CBrn" to BlendMode.COLOR_BURN,
        "Lghn" to BlendMode.LIGHTEN, "Scrn" to BlendMode.SCREEN, "CDdg" to BlendMode.COLOR_DODGE, "Ovrl" to BlendMode.OVERLAY, "SftL" to BlendMode.SOFT_LIGHT,
        "HrdL" to BlendMode.HARD_LIGHT, "Dfrn" to BlendMode.DIFFERENCE, "Xclu" to BlendMode.EXCLUSION, "H   " to BlendMode.HUE, "Strt" to BlendMode.SATURATION,
        "Clr " to BlendMode.COLOR, "Lmns" to BlendMode.LUMINOSITY,
    )

    private fun fourCc(code: String): Int = code.fold(0) { acc, c -> (acc shl 8) or (c.code and 0xFF) }

    private fun rgb(c: Int) = PsdDescriptor("RGBC").also { d ->
        d["Rd  "] = ((c shr 16) and 0xFF).toDouble(); d["Grn "] = ((c shr 8) and 0xFF).toDouble(); d["Bl  "] = (c and 0xFF).toDouble()
    }

    private fun argb(d: PsdDescriptor?, fallback: Int): Int = if (d == null) fallback else
        (0xFF shl 24) or ((d.double("Rd  ") ?: 0.0).roundToInt().coerceIn(0, 255) shl 16) or
            ((d.double("Grn ") ?: 0.0).roundToInt().coerceIn(0, 255) shl 8) or (d.double("Bl  ") ?: 0.0).roundToInt().coerceIn(0, 255)

    private fun entry(f: SmartFilter, name: String, hasOptions: Boolean, fg: PsdDescriptor, bg: PsdDescriptor, fltr: PsdDescriptor?, filterId: Int) =
        PsdDescriptor("filterFX").also { e ->
            e["Nm  "] = name
            e["blendOptions"] = PsdDescriptor("blendOptions").also { b ->
                b["Opct"] = PsdDescriptor.UnitFloat("#Prc", (f.opacity * 100.0))
                b["Md  "] = PsdDescriptor.EnumValue("BlnM", BLEND.getValue(f.blendMode))
            }
            e["enab"] = f.enabled
            e["hasoptions"] = hasOptions
            e["FrgC"] = fg
            e["BckC"] = bg
            if (fltr != null) e["Fltr"] = fltr
            e["filterID"] = filterId
        }

    /** Lässt sich [sf] vollständig als Photoshop-Smartfilter speichern? (Filtermaske über „FEid“.) */
    fun representable(sf: SmartFilters): Boolean =
        sf.filters.isNotEmpty() && sf.filters.all {
            it.blendMode in BLEND && (it.gallery?.let(PsdPsGallery::representable) ?: (it.filterId in BY_ID))
        }

    /** „filterFX“-Descriptor für „SoLd“, null = nicht darstellbar. */
    fun write(sf: SmartFilters): PsdDescriptor? {
        if (!representable(sf)) return null
        val list = sf.filters.map { f ->
            f.gallery?.let { g ->
                val name = tr("Filtergalerie")
                return@map entry(f, name + "...", true, rgb(f.galleryFg), rgb(f.galleryBg), PsdPsGallery.write(g, name)!!, fourCc("GEfc"))
            }
            val m = BY_ID.getValue(f.filterId)
            val spec = FilterCatalog.byId(f.filterId)!!
            val values = List(spec.params.size) { i -> f.values.getOrNull(i) ?: spec.params[i].default }
            entry(f, if (m.noOptions) spec.label else spec.label + "...", !m.noOptions, rgb(0xFF000000.toInt()), rgb(0xFFFFFFFF.toInt()),
                if (m.noOptions) null else PsdDescriptor(m.code, spec.label).also { d -> for ((k, v) in m.toPs(values)) d[k] = v },
                m.filterId ?: fourCc(m.code))
        }
        return PsdDescriptor("filterFXStyle").also { d ->
            d["enab"] = sf.visible
            d["validAtPosition"] = true
            d["filterMaskEnable"] = sf.mask?.enabled ?: true
            d["filterMaskLinked"] = sf.mask?.linked ?: false
            d["filterMaskExtendWithWhite"] = true
            d["filterFXList"] = list
        }
    }

    /** Photoshops „filterFX“ als unsere Smartfilter; null = mindestens ein Filter ohne Gegenstück. */
    fun read(d: PsdDescriptor): SmartFilters? {
        val list = d["filterFXList"] as? List<*> ?: return null
        val filters = list.map { item ->
            val e = item as? PsdDescriptor ?: return null
            val fl = e.descriptor("Fltr")
            val bo = e.descriptor("blendOptions")
            val mode = (bo?.get("Md  ") as? PsdDescriptor.EnumValue)?.value?.let { v -> BLEND.entries.firstOrNull { it.value == v }?.key ?: BLEND_CHAR[v] } ?: BlendMode.NORMAL
            val opacity = ((bo?.double("Opct") ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f)
            if (fl?.classId == "GEfc") {
                val g = PsdPsGallery.read(fl) ?: return null
                return@map SmartFilter(SmartFilter.GALLERY_ID, gallery = g, galleryFg = argb(e.descriptor("FrgC"), 0xFF000000.toInt()),
                    galleryBg = argb(e.descriptor("BckC"), 0xFFFFFFFF.toInt()), enabled = e.bool("enab") != false, blendMode = mode, opacity = opacity)
            }
            val m = (if (fl != null) BY_CODE[fl.classId] else BY_FILTER_ID[e.int("filterID")]?.takeIf { it.noOptions }) ?: return null
            val values = if (fl != null) m.fromPs(fl) ?: return null else emptyList()
            SmartFilter(m.id, values, enabled = e.bool("enab") != false, blendMode = mode, opacity = opacity)
        }
        return SmartFilters(filters, visible = d.bool("enab") != false)
    }
}
