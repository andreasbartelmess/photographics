package de.photographics.core.psd

import de.photographics.core.filters.G
import de.photographics.core.filters.Gallery
import de.photographics.core.filters.GalleryEffect
import kotlin.math.roundToInt

/**
 * Filtergalerie im Photoshop-Format: der Smartfilter „Filtergalerie“ („Fltr“ der Klasse „GEfc“)
 * mit der Effektliste „GEfs“. Kennungen, Werttypen und Aufzählungen aus der Terminologie des
 * Photoshop-2026-Moduls „Filter Gallery.8bf“; jede Kennung in Photoshop geprüft (Regler ändern →
 * Bild ändert sich; ~/vm-tools/parity/gal).
 */
internal object PsdPsGallery {
    /** Ein Photoshop-Regler. */
    sealed class P(val key: String)
    class L(key: String) : P(key)
    class B(key: String) : P(key)
    /** Farbe (RGBC) – bei uns eine der Leuchtfarben [G.GLOWS]. */
    class C(key: String) : P(key)
    /** Aufzählung; [codes] in der Reihenfolge unserer Auswahlliste. */
    class E(key: String, val type: String, vararg val codes: String) : P(key)

    /**
     * [params] je unserem Regler (null = nur bei uns, muss auf dem Standardwert stehen);
     * [extras] = Photoshop-Regler, die unser Effekt nicht hat, mit dem Wert, dem unser Effekt entspricht.
     */
    class Mapping(val code: String, val params: List<P?>, val extras: List<Pair<P, Any>> = emptyList())

    private fun G(code: String, params: List<P?>, extras: List<Pair<P, Any>> = emptyList()) = Mapping(code, params, extras)
    private fun TEX(key: String) = E(key, "TxtT", "TxCa", "TxBu", "TxBr", "TxSt")
    private fun GLASS_TEX(key: String) = E(key, "TxtT", "TxFr", "TxBl", "TxCa", "TxTL")
    private fun LIGHT_D(key: String) = E(key, "LghD", "LDTp", "LDTR", "LDRg", "LDBR", "LDBt", "LDBL", "LDLf", "LDTL")
    private fun LIGHT_P(key: String) = E(key, "LghP", "LPTp", "LPTR", "LPRg", "LPBr", "LPBt", "LPBL", "LPLf", "LPTL")
    private fun DIR(key: String) = E(key, "StrD", "SDRD", "SDHz", "SDLD", "SDVt")

    val MAPPINGS: Map<String, Mapping> = mapOf(
        "watercolor" to G("Wtrc", listOf(L("BrsD"), L("ShdI"), L("Txtr"))),
        "cutout" to G("Ct  ", listOf(L("NmbL"), L("EdgS"), L("EdgF"))),
        "dry-brush" to G("DryB", listOf(L("BrsS"), L("BrsD"), L("Txtr"))),
        "film-grain" to G("FlmG", listOf(L("Grn "), L("HghA"), L("Intn"))),
        "fresco" to G("Frsc", listOf(L("BrsS"), L("BrsD"), L("Txtr"))),
        "neon-glow" to G("NGlw", listOf(L("Sz  "), L("Brgh"), C("Clr "))),
        "paint-daubs" to G("PntD", listOf(L("Sz  "), L("Shrp"), E("BrsT", "BrsT", "BrSm", "BrsL", "BrDR", "BrsW", "BrbW", "BrSp"))),
        "palette-knife" to G("PltK", listOf(L("StrS"), L("StDt"), L("Sftn"))),
        "plastic-wrap" to G("PlsW", listOf(L("HghS"), L("Dtl "), L("Smth"))),
        "poster-edges" to G("PstE", listOf(L("EdgT"), L("EdgI"), L("Pstr"))),
        "rough-pastels" to G("RghP", listOf(L("StrL"), L("StDt"), TEX("TxtT"), L("Scln"), L("Rlf "), LIGHT_D("LghD"), B("InvT"))),
        "smudge-stick" to G("SmdS", listOf(L("StrL"), L("HghA"), L("Intn"))),
        "sponge" to G("Spng", listOf(L("BrsS"), L("Dfnt"), L("Smth"))),
        "underpainting" to G("Undr", listOf(L("BrsS"), L("TxtC"), TEX("TxtT"), L("Scln"), L("Rlf "), LIGHT_D("LghD"), B("InvT"))),
        "colored-pencil" to G("ClrP", listOf(L("Pncl"), L("StrP"), L("PprB"))),
        "accented-edges" to G("AccE", listOf(L("EdgW"), L("EdgB"), L("Smth"))),
        "angled-strokes" to G("AngS", listOf(L("DrcB"), L("StrL"), L("Shrp"))),
        "crosshatch" to G("Crsh", listOf(L("StrL"), L("Shrp"), L("Strg"))),
        "dark-strokes" to G("DrkS", listOf(L("Blnc"), L("BlcI"), L("WhtI"))),
        "ink-outlines" to G("InkO", listOf(L("StrL"), L("DrkI"), L("LghI"))),
        "sprayed-strokes" to G("SprS", listOf(L("StrL"), L("SprR"), DIR("SDir"))),
        "spatter" to G("Spt ", listOf(L("SprR"), L("Smth"))),
        "sumi-e" to G("Smie", listOf(L("StrW"), L("StrP"), L("Cntr"))),
        "diffuse-glow" to G("DfsG", listOf(L("Grns"), L("GlwA"), L("ClrA"))),
        "glass" to G("Gls ", listOf(L("Dstr"), L("Smth"), GLASS_TEX("TxtT"), L("Scln"), B("InvT"))),
        "ocean-ripple" to G("OcnR", listOf(L("RplS"), L("RplM"))),
        "bas-relief" to G("BsRl", listOf(L("Dtl "), L("Smth"), LIGHT_D("LghD"))),
        "chalk-charcoal" to G("ChlC", listOf(L("ChrA"), L("ChlA"), L("StrP"))),
        "charcoal" to G("Chrc", listOf(L("ChAm"), L("Dtl "), L("LgDr"))),
        "chrome" to G("Chrm", listOf(L("Dtl "), L("Smth"))),
        "conte-crayon" to G("CntC", listOf(L("FrgL"), L("BckL"), TEX("TxtT"), L("Scln"), L("Rlf "), LIGHT_D("LghD"), B("InvT"))),
        "graphic-pen" to G("GraP", listOf(L("StrL"), L("LgDr"), DIR("SDir"))),
        "halftone" to G("HlfS", listOf(L("HlSz"), L("Cntr"), E("ScrT", "ScrT", "ScrC", "ScrD", "ScrL"))),
        "note-paper" to G("NtPr", listOf(L("ImgB"), L("Grns"), L("Rlf "))),
        "photocopy" to G("Phtc", listOf(L("Dtl "), L("Drkn"))),
        "plaster" to G("Plst", listOf(L("ImgB"), L("Smth"), LIGHT_P("LghP"))),
        "reticulation" to G("Rtcl", listOf(L("Dnst"), L("FrgL"), L("BckL"))),
        "stamp" to G("Stmp", listOf(L("LgDr"), L("Smth"))),
        "torn-edges" to G("TrnE", listOf(L("ImgB"), L("Smth"), L("Cntr"))),
        "water-paper" to G("WtrP", listOf(L("FbrL"), L("Brgh"), L("Cntr"))),
        "glowing-edges" to G("GlwE", listOf(L("EdgW"), L("EdgB"), L("Smth"))),
        "craquelure" to G("Crql", listOf(L("CrcS"), L("CrcD"), L("CrcB"))),
        "grain" to G("Grn ", listOf(L("Intn"), L("Cntr"), E("Grnt", "Grnt", "GrnR", "GrSf", "GrSr", "GrnC", "GrnH", "GrnV", "GrCn", "GrnE", "GrSt", "GrSp"), null)),
        "mosaic-tiles" to G("MscT", listOf(L("TlSz"), L("GrtW"), L("LghG"))),
        "patchwork" to G("Ptch", listOf(L("SqrS"), L("Rlf "))),
        "stained-glass" to G("StnG", listOf(L("ClSz"), L("BrdT"), L("LghI"))),
        "texturizer" to G("Txtz", listOf(TEX("TxtT"), L("Scln"), L("Rlf "), LIGHT_D("LghD"), B("InvT"))),
    )
    private val BY_CODE = MAPPINGS.entries.associate { it.value.code to it.key }

    /** Lässt sich die Kette ohne Verlust als Photoshop-Filtergalerie speichern? */
    fun representable(effects: List<GalleryEffect>): Boolean = effects.isNotEmpty() && effects.all { e ->
        val m = MAPPINGS[e.spec.id] ?: return@all false
        m.params.size == e.spec.params.size && m.params.indices.all { i ->
            val v = e.values.getOrElse(i) { e.spec.params[i].default }
            when (m.params[i]) {
                null -> v == e.spec.params[i].default
                is C -> v.roundToInt() in G.GLOWS.indices
                else -> true
            }
        }
    }

    private fun rgb(c: Int) = PsdDescriptor("RGBC").also { d ->
        d["Rd  "] = ((c shr 16) and 0xFF).toDouble(); d["Grn "] = ((c shr 8) and 0xFF).toDouble(); d["Bl  "] = (c and 0xFF).toDouble()
    }

    private fun value(p: P, v: Any): Any = when (p) {
        is L -> (v as Number).toFloat().roundToInt()
        is B -> if (v is Boolean) v else (v as Number).toFloat() >= 0.5f
        is C -> rgb(G.GLOWS[(v as Number).toFloat().roundToInt()])
        is E -> PsdDescriptor.EnumValue(p.type, p.codes[(v as Number).toFloat().roundToInt().coerceIn(0, p.codes.size - 1)])
    }

    /** „Fltr“-Descriptor der Filtergalerie; null = nicht darstellbar. */
    fun write(effects: List<GalleryEffect>, name: String): PsdDescriptor? {
        if (!representable(effects)) return null
        val list = effects.map { e ->
            val m = MAPPINGS.getValue(e.spec.id)
            PsdDescriptor("GEfc", name).also { d ->
                d["GEfk"] = PsdDescriptor.EnumValue("GEft", m.code)
                if (!e.visible) d["GELv"] = false
                m.params.forEachIndexed { i, p -> if (p != null) d[p.key] = value(p, e.values.getOrElse(i) { e.spec.params[i].default }) }
                for ((p, v) in m.extras) d[p.key] = value(p, v)
            }
        }
        return PsdDescriptor("GEfc", name).also { it["GEfs"] = list }
    }

    /** Photoshops Filtergalerie als unsere Effektkette; null = Effekt oder Einstellung ohne Gegenstück. */
    fun read(fltr: PsdDescriptor): List<GalleryEffect>? {
        val list = fltr["GEfs"] as? List<*> ?: return null
        return list.map { item ->
            val d = item as? PsdDescriptor ?: return null
            val code = (d["GEfk"] as? PsdDescriptor.EnumValue)?.value ?: return null
            val id = BY_CODE[code] ?: return null
            val m = MAPPINGS.getValue(id)
            val spec = Gallery.byId(id) ?: return null
            // Photoshop-Regler, die wir nicht haben: nur der Wert, dem unser Effekt entspricht
            for ((p, v) in m.extras) if (d[p.key] != null && read(p, d)?.let { it != v.toComparable() } == true) return null
            val values = spec.defaults()
            for (i in values.indices) {
                val p = m.params.getOrNull(i) ?: continue
                val v = read(p, d) ?: continue
                values[i] = if (v is Boolean) (if (v) 1f else 0f) else (v as Number).toFloat()
                if (values[i].isNaN()) return null
            }
            GalleryEffect(spec, values, visible = d.bool("GELv") != false)
        }
    }

    private fun Any.toComparable(): Any = if (this is Number) toFloat() else this

    /** Wert in unserer Darstellung (Zahl/Index/Boolean); NaN = Farbe/Aufzählung ohne Gegenstück, null = fehlt. */
    private fun read(p: P, d: PsdDescriptor): Any? = when (p) {
        is L -> d.int(p.key)?.toFloat()
        is B -> d.bool(p.key)
        is C -> d.descriptor(p.key)?.let { c ->
            val argb = ((c.double("Rd  ") ?: 0.0).roundToInt() shl 16) or ((c.double("Grn ") ?: 0.0).roundToInt() shl 8) or (c.double("Bl  ") ?: 0.0).roundToInt()
            G.GLOWS.indexOf(argb).let { if (it < 0) Float.NaN else it.toFloat() }
        }
        is E -> (d[p.key] as? PsdDescriptor.EnumValue)?.value?.let { v -> p.codes.indexOf(v).let { if (it < 0) Float.NaN else it.toFloat() } }
    }
}
