package de.photographics.core.psd

import de.photographics.core.adjust.Adjustment
import de.photographics.core.adjust.BlackWhiteAdj
import de.photographics.core.adjust.BrightnessContrastAdj
import de.photographics.core.adjust.ColorBalanceAdj
import de.photographics.core.adjust.ColorLookupAdj
import de.photographics.core.color.Lut3D
import de.photographics.core.adjust.CurvesAdj
import de.photographics.core.adjust.ExposureAdj
import de.photographics.core.adjust.HueSaturationAdj
import de.photographics.core.adjust.InvertAdj
import de.photographics.core.adjust.LevelsAdj
import de.photographics.core.adjust.PhotoFilterAdj
import de.photographics.core.adjust.PosterizeAdj
import de.photographics.core.adjust.ThresholdAdj
import de.photographics.core.adjust.VibranceAdj
import de.photographics.core.image.Colors
import kotlin.math.roundToInt

/** Einstellungsebenen im PSD-Format (Blöcke levl, curv, hue2, brit/CgEd, expA, vibA, blnc, blwh, phfl, nvrt, post, thrs). */
object PsdAdjustments {
    val KEYS = setOf("levl", "curv", "hue2", "hue ", "brit", "CgEd", "expA", "vibA", "blnc", "blwh", "phfl", "nvrt", "post", "thrs", "clrL")

    fun read(blocks: Map<String, PsdTaggedBlock>): Adjustment? = runCatching { readUnsafe(blocks) }.getOrNull()

    private fun readUnsafe(b: Map<String, PsdTaggedBlock>): Adjustment? {
        b["CgEd"]?.let { blk ->
            val d = PsdDescriptor.readVersioned(blk.data) ?: return@let
            return BrightnessContrastAdj(d.int("Brgh") ?: 0, d.int("Cntr") ?: 0)
        }
        b["brit"]?.let { blk -> val r = BinaryReader(blk.data); return BrightnessContrastAdj(r.i16(), r.i16()) }
        b["levl"]?.let { blk ->
            val r = BinaryReader(blk.data); r.u16()
            val inLo = r.u16(); val inHi = r.u16(); val outLo = r.u16(); val outHi = r.u16(); val gamma = r.u16()
            return LevelsAdj(inLo, inHi, gamma / 100f, outLo, outHi)
        }
        b["curv"]?.let { blk ->
            val r = BinaryReader(blk.data)
            val isMap = r.u8() != 0
            r.u16() // Version
            val bits = r.i32()
            val curves = arrayOfNulls<List<Pair<Int, Int>>>(4)
            for (i in 0 until 32) {
                if (bits and (1 shl i) == 0) continue
                val curve = if (isMap) {
                    val map = r.bytes(256)
                    // Abbildungskurve in Stützpunkte umwandeln (jeder 16. Wert)
                    (0..255 step 15).map { it to (map[it].toInt() and 0xFF) }
                } else {
                    val n = r.u16()
                    List(n) { val out = r.u16(); val inp = r.u16(); inp to out }
                }
                if (i < 4) curves[i] = curve
            }
            return CurvesAdj(curves[0] ?: listOf(0 to 0, 255 to 255), curves[1], curves[2], curves[3])
        }
        (b["hue2"] ?: b["hue "])?.let { blk ->
            val r = BinaryReader(blk.data); r.u16()
            val colorize = r.u8() != 0; r.u8()
            val cHue = r.i16(); val cSat = r.i16(); val cLight = r.i16()
            val mHue = r.i16(); val mSat = r.i16(); val mLight = r.i16()
            return if (colorize) HueSaturationAdj(cHue, cSat * 2 - 100, cLight, colorize = true)
            else HueSaturationAdj(mHue, mSat, mLight, false)
        }
        b["expA"]?.let { blk -> val r = BinaryReader(blk.data); r.u16(); return ExposureAdj(r.f32(), r.f32(), r.f32()) }
        b["vibA"]?.let { blk ->
            val d = PsdDescriptor.readVersioned(blk.data) ?: return@let
            return VibranceAdj(d.int("vibrance") ?: 0, d.int("Strt") ?: 0)
        }
        b["blnc"]?.let { blk ->
            val r = BinaryReader(blk.data)
            val sh = List(3) { r.i16() }; val mid = List(3) { r.i16() }; val hi = List(3) { r.i16() }
            val keep = if (r.remaining > 0) r.u8() != 0 else true
            return ColorBalanceAdj(sh, mid, hi, keep)
        }
        b["blwh"]?.let { blk ->
            val d = PsdDescriptor.readVersioned(blk.data) ?: return@let
            val tintColor = d.descriptor("tintColor")
            var hue = 35; var sat = 25
            if (tintColor != null) {
                val hsl = FloatArray(3)
                Colors.rgbToHsl(((tintColor.double("Rd  ") ?: 0.0) / 255).toFloat(), ((tintColor.double("Grn ") ?: 0.0) / 255).toFloat(), ((tintColor.double("Bl  ") ?: 0.0) / 255).toFloat(), hsl)
                hue = hsl[0].roundToInt(); sat = (hsl[1] * 100).roundToInt()
            }
            return BlackWhiteAdj(
                d.int("Rd  ") ?: 40, d.int("Yllw") ?: 60, d.int("Grn ") ?: 40, d.int("Cyn ") ?: 60, d.int("Bl  ") ?: 20, d.int("Mgnt") ?: 80,
                d.bool("useTint") ?: false, hue, sat,
            )
        }
        b["phfl"]?.let { blk ->
            val r = BinaryReader(blk.data)
            val version = r.u16()
            var hue = 30
            if (version == 3) {
                val x = r.i32() / 100.0; val y = r.i32() / 100.0; val z = r.i32() / 100.0
                hue = hueFromXyz(x, y, z)
            } else {
                val space = r.u16(); val c1 = r.u16(); val c2 = r.u16(); val c3 = r.u16(); r.u16()
                if (space == 0) {
                    val hsl = FloatArray(3); Colors.rgbToHsl(c1 / 65535f, c2 / 65535f, c3 / 65535f, hsl); hue = hsl[0].roundToInt()
                }
            }
            val density = r.i32()
            val keep = r.u8() != 0
            return PhotoFilterAdj(hue, density, keep)
        }
        if (b.containsKey("nvrt")) return InvertAdj
        b["post"]?.let { blk -> return PosterizeAdj(BinaryReader(blk.data).u16().coerceIn(2, 255)) }
        b["thrs"]?.let { blk -> return ThresholdAdj(BinaryReader(blk.data).u16().coerceIn(1, 255)) }
        b["clrL"]?.let { blk -> return readColorLookup(blk.data) }
        return null
    }

    /**
     * Farbtabelle: Version (u16 = 1), dann Descriptor (Version 16) mit der eingebetteten LUT-Datei
     * („LUT3DFileData“) und ihrem Format. Nicht unterstützte Arten (z. B. abstrakte ICC-Profile)
     * liefern null – der Block bleibt dann unverändert erhalten.
     */
    private fun readColorLookup(data: ByteArray): Adjustment? {
        if (data.size < 6) return null
        val d = PsdDescriptor.readVersioned(data.copyOfRange(2, data.size)) ?: return null
        val file = (d["LUT3DFileData"] as? PsdDescriptor.RawData)?.bytes ?: return null
        val format = (d["LUTFormat"] as? PsdDescriptor.EnumValue)?.value ?: "LUTFormatCUBE"
        val name = d.string("Nm  ")?.takeIf { it.isNotBlank() } ?: d.string("LUT3DFileName")?.substringBeforeLast('.') ?: "Farbtabelle"
        val is3dl = format.contains("3DL", ignoreCase = true)
        if (!is3dl && !format.contains("CUBE", ignoreCase = true)) return null
        val lut = runCatching { if (is3dl) Lut3D.parse3dl(String(file, Charsets.UTF_8), name) else Lut3D.parseCube(String(file, Charsets.UTF_8), name) }
            .getOrNull() ?: return null
        return ColorLookupAdj(name, lut, file, if (is3dl) "3DL" else "CUBE")
    }

    private fun writeColorLookup(a: ColorLookupAdj): ByteArray {
        // Eingebaute Looks werden als .cube-Datei eingebettet, damit Photoshop sie ebenfalls lesen kann
        val file = a.fileData ?: a.lut?.toCube() ?: ByteArray(0)
        val is3dl = a.fileData != null && a.fileFormat == "3DL"
        val d = PsdDescriptor("null").apply {
            this["lookupType"] = PsdDescriptor.EnumValue("colorLookupType", "3DLUT")
            this["Nm  "] = a.lutName
            this["Dthr"] = true
            this["LUTFormat"] = PsdDescriptor.EnumValue("LUTFormatType", if (is3dl) "LUTFormat3DL" else "LUTFormatCUBE")
            this["LUT3DFileData"] = PsdDescriptor.RawData(file)
            this["LUT3DFileName"] = a.lutName + if (is3dl) ".3dl" else ".cube"
        }
        val w = BinaryWriter()
        w.u16(1)
        w.bytes(PsdDescriptor.writeVersioned(d))
        return w.toByteArray()
    }

    private fun hueFromXyz(x: Double, y: Double, z: Double): Int {
        // XYZ (D50) grob nach linearem sRGB, nur für den Farbton
        val r = 3.1339 * x / 100 - 1.6169 * y / 100 - 0.4906 * z / 100
        val g = -0.9788 * x / 100 + 1.9161 * y / 100 + 0.0335 * z / 100
        val bb = 0.0719 * x / 100 - 0.2290 * y / 100 + 1.4052 * z / 100
        val hsl = FloatArray(3)
        Colors.rgbToHsl(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), bb.toFloat().coerceIn(0f, 1f), hsl)
        return hsl[0].roundToInt()
    }

    /** Liefert die zu schreibenden Zusatzblöcke (Schlüssel, Daten). */
    fun write(a: Adjustment): List<Pair<String, ByteArray>> {
        val w = BinaryWriter()
        return when (a) {
            is BrightnessContrastAdj -> {
                w.u16(a.brightness and 0xFFFF); w.u16(a.contrast and 0xFFFF); w.u16(127); w.u8(0); w.u8(0)
                val d = PsdDescriptor("null").apply {
                    this["Vrsn"] = 1; this["Brgh"] = a.brightness; this["Cntr"] = a.contrast; this["means"] = 127
                    this["Lab "] = false; this["useLegacy"] = false; this["Auto"] = false
                }
                listOf("brit" to w.toByteArray(), "CgEd" to PsdDescriptor.writeVersioned(d))
            }
            is LevelsAdj -> {
                w.u16(2)
                for (i in 0 until 29) {
                    if (i == 0) { w.u16(a.inBlack); w.u16(a.inWhite); w.u16(a.outBlack); w.u16(a.outWhite); w.u16((a.gamma * 100).roundToInt().coerceIn(10, 999)) }
                    else { w.u16(0); w.u16(255); w.u16(0); w.u16(255); w.u16(100) }
                }
                listOf("levl" to w.toByteArray())
            }
            is CurvesAdj -> {
                val curves = listOf(a.master, a.red, a.green, a.blue)
                var bits = 0
                curves.forEachIndexed { i, c -> if (c != null) bits = bits or (1 shl i) }
                fun points(c: List<Pair<Int, Int>>) {
                    val pts = c.sortedBy { it.first }
                    w.u16(pts.size)
                    for ((inp, out) in pts) { w.u16(out); w.u16(inp) }
                }
                w.u8(0); w.u16(1); w.i32(bits)
                for (c in curves) if (c != null) points(c)
                // Erweiterung „Crv “ (Version 4) mit denselben Kurven, wie Photoshop sie zusätzlich schreibt
                w.ascii("Crv "); w.u16(4); w.i32(curves.count { it != null })
                curves.forEachIndexed { i, c -> if (c != null) { w.u16(i); points(c) } }
                listOf("curv" to w.toByteArray())
            }
            is HueSaturationAdj -> {
                w.u16(2); w.u8(if (a.colorize) 1 else 0); w.u8(0)
                if (a.colorize) { w.u16(a.hue and 0xFFFF); w.u16(((a.saturation + 100) / 2) and 0xFFFF); w.u16(a.lightness and 0xFFFF); w.zeros(6) }
                else { w.u16(0); w.u16(25); w.u16(0); w.u16(a.hue and 0xFFFF); w.u16(a.saturation and 0xFFFF); w.u16(a.lightness and 0xFFFF) }
                // Sechs Farbbereiche (Rot, Gelb, Grün, Cyan, Blau, Magenta) mit Standardgrenzen, ohne Änderung
                val ranges = intArrayOf(315, 345, 15, 45, 15, 45, 75, 105, 75, 105, 135, 165, 135, 165, 195, 225, 195, 225, 255, 285, 255, 285, 315, 345)
                for (i in 0 until 6) { for (k in 0 until 4) w.u16(ranges[i * 4 + k]); w.zeros(6) }
                listOf("hue2" to w.toByteArray())
            }
            is ExposureAdj -> { w.u16(1); w.f32(a.exposure); w.f32(a.offset); w.f32(a.gamma); listOf("expA" to w.toByteArray()) }
            is VibranceAdj -> {
                val d = PsdDescriptor("null").apply { this["vibrance"] = a.vibrance; this["Strt"] = a.saturation }
                listOf("vibA" to PsdDescriptor.writeVersioned(d))
            }
            is ColorBalanceAdj -> {
                for (l in listOf(a.shadows, a.midtones, a.highlights)) l.forEach { w.u16(it and 0xFFFF) }
                w.u8(if (a.preserveLuminosity) 1 else 0); w.u8(0)
                listOf("blnc" to w.toByteArray())
            }
            is BlackWhiteAdj -> {
                val rgb = FloatArray(3); Colors.hslToRgb(a.tintHue.toFloat(), a.tintSaturation / 100f, 0.5f, rgb)
                val d = PsdDescriptor("null").apply {
                    this["Rd  "] = a.red; this["Yllw"] = a.yellow; this["Grn "] = a.green
                    this["Cyn "] = a.cyan; this["Bl  "] = a.blue; this["Mgnt"] = a.magenta
                    this["useTint"] = a.tint
                    this["tintColor"] = PsdDescriptor("RGBC").apply {
                        this["Rd  "] = (rgb[0] * 255.0); this["Grn "] = (rgb[1] * 255.0); this["Bl  "] = (rgb[2] * 255.0)
                    }
                    this["bwPresetKind"] = 1
                    this["blackAndWhitePresetFileName"] = ""
                }
                listOf("blwh" to PsdDescriptor.writeVersioned(d))
            }
            is PhotoFilterAdj -> {
                val c = a.color
                w.u16(2); w.u16(0)
                w.u16(((c shr 16) and 0xFF) * 257); w.u16(((c shr 8) and 0xFF) * 257); w.u16((c and 0xFF) * 257); w.u16(0)
                w.i32(a.density); w.u8(if (a.preserveLuminosity) 1 else 0); w.u8(0)
                listOf("phfl" to w.toByteArray())
            }
            InvertAdj -> listOf("nvrt" to ByteArray(0))
            is PosterizeAdj -> { w.u16(a.levels); w.u16(0); listOf("post" to w.toByteArray()) }
            is ThresholdAdj -> { w.u16(a.level); w.u16(0); listOf("thrs" to w.toByteArray()) }
            is ColorLookupAdj -> listOf("clrL" to writeColorLookup(a))
        }
    }
}
