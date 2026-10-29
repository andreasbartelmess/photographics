package de.photographics.core.psd

import de.photographics.core.doc.LayerEffects
import de.photographics.core.render.BlendMode

/**
 * Liest Ebeneneffekte aus den Photoshop-Blöcken `lmfx` (mehrere Effekte je Art, neuere Versionen)
 * bzw. `lfx2`. Aufbau: Version der Effekte (0), Descriptor-Version (16), Descriptor.
 */
object PsdEffects {
    /** Globaler Lichtwinkel (Bildressource 1037), Photoshop-Standard 120°. */
    fun globalAngle(resources: List<PsdImageResource>): Double =
        resources.firstOrNull { it.id == 1037 }?.data?.takeIf { it.size >= 4 }?.let { BinaryReader(it).i32().toDouble() } ?: 120.0

    fun parse(blocks: List<PsdTaggedBlock>): LayerEffects? {
        for (key in listOf("lmfx", "lfx2")) {
            val block = blocks.firstOrNull { it.key == key } ?: continue
            val d = runCatching {
                val r = BinaryReader(block.data)
                r.i32(); r.i32()
                PsdDescriptor.read(r)
            }.getOrNull() ?: continue
            return runCatching { fromDescriptor(d) }.getOrNull() ?: continue
        }
        return null
    }

    fun fromDescriptor(d: PsdDescriptor): LayerEffects {
        val scale = (d.double("Scl ") ?: 100.0) / 100.0
        fun list(single: String, multi: String): List<PsdDescriptor> =
            ((d[multi] as? List<*>)?.filterIsInstance<PsdDescriptor>() ?: listOfNotNull(d.descriptor(single)))
                .filter { it.bool("enab") != false && it.bool("present") != false }
        fun shadow(e: PsdDescriptor) = LayerEffects.Shadow(
            color = color(e.descriptor("Clr ")), opacity = opacity(e), blend = blend(e, BlendMode.MULTIPLY),
            angle = e.double("lagl") ?: 120.0, useGlobalAngle = e.bool("uglg") ?: true,
            distance = (e.double("Dstn") ?: 0.0) * scale, spread = ((e.double("Ckmt") ?: 0.0) / 100.0).coerceIn(0.0, 1.0),
            size = (e.double("blur") ?: 0.0) * scale, knockOut = e.bool("layerConceals") ?: true,
        )
        fun glow(e: PsdDescriptor, inner: Boolean) = LayerEffects.Glow(
            color = e.descriptor("Clr ")?.let { color(it) } ?: 0xFFFFBE,
            opacity = opacity(e), blend = blend(e, BlendMode.SCREEN),
            spread = ((e.double("Ckmt") ?: 0.0) / 100.0).coerceIn(0.0, 1.0), size = (e.double("blur") ?: 0.0) * scale,
            fromEdge = !inner || (e["glwS"] as? PsdDescriptor.EnumValue)?.value != "SrcC",
        )
        val strokes = list("FrFX", "frameFXMulti").mapNotNull { e ->
            // Nur Kontur mit Farbe; Verlaufs- und Musterkonturen erscheinen in der Farbe des Descriptors
            LayerEffects.Stroke(
                color = color(e.descriptor("Clr ")), opacity = opacity(e), blend = blend(e, BlendMode.NORMAL),
                size = ((e.double("Sz  ") ?: 3.0) * scale).takeIf { it > 0 } ?: return@mapNotNull null,
                position = when ((e["Styl"] as? PsdDescriptor.EnumValue)?.value) {
                    "InsF" -> LayerEffects.StrokePosition.INSIDE
                    "CtrF" -> LayerEffects.StrokePosition.CENTER
                    else -> LayerEffects.StrokePosition.OUTSIDE
                },
            )
        }
        val unsupported = buildList {
            if (list("ebbl", "bevelEmbossMulti").isNotEmpty()) add("Abgeflachte Kante und Relief")
            if (list("ChFX", "satinMulti").isNotEmpty()) add("Glanz")
            if (list("GrFl", "gradientFillMulti").isNotEmpty()) add("Verlaufsüberlagerung")
            if (list("patternFill", "patternFillMulti").isNotEmpty()) add("Musterüberlagerung")
        }
        return LayerEffects(
            enabled = d.bool("masterFXSwitch") ?: true,
            dropShadows = list("DrSh", "dropShadowMulti").map(::shadow),
            innerShadows = list("IrSh", "innerShadowMulti").map(::shadow),
            outerGlow = list("OrGl", "outerGlowMulti").firstOrNull()?.let { glow(it, inner = false) },
            innerGlow = list("IrGl", "innerGlowMulti").firstOrNull()?.let { glow(it, inner = true) },
            strokes = strokes,
            colorOverlays = list("SoFi", "solidFillMulti").map { LayerEffects.ColorOverlay(color(it.descriptor("Clr ")), opacity(it), blend(it, BlendMode.NORMAL)) },
            unsupported = unsupported,
        )
    }

    /** Block `lfx2` für [fx] (Gegenstück zu [parse]; Photoshop liest ihn als Ebenenstil). */
    fun toBlock(fx: LayerEffects): PsdTaggedBlock {
        fun pct(v: Double) = PsdDescriptor.UnitFloat("#Prc", v)
        fun px(v: Double) = PsdDescriptor.UnitFloat("#Pxl", v)
        fun clr(c: Int) = PsdDescriptor("RGBC").apply {
            this["Rd  "] = ((c shr 16) and 0xFF).toDouble(); this["Grn "] = ((c shr 8) and 0xFF).toDouble(); this["Bl  "] = (c and 0xFF).toDouble()
        }
        fun md(m: BlendMode) = PsdDescriptor.EnumValue("BlnM", BLEND.entries.firstOrNull { it.value == m }?.key ?: "Nrml")
        fun shadow(cls: String, s: LayerEffects.Shadow) = PsdDescriptor(cls).apply {
            this["enab"] = true; this["Md  "] = md(s.blend); this["Clr "] = clr(s.color); this["Opct"] = pct(s.opacity * 100.0)
            this["uglg"] = s.useGlobalAngle; this["lagl"] = PsdDescriptor.UnitFloat("#Ang", s.angle); this["Dstn"] = px(s.distance)
            this["Ckmt"] = px(s.spread * 100.0); this["blur"] = px(s.size); if (cls == "DrSh") this["layerConceals"] = s.knockOut
        }
        fun glow(cls: String, g: LayerEffects.Glow) = PsdDescriptor(cls).apply {
            this["enab"] = true; this["Md  "] = md(g.blend); this["Clr "] = clr(g.color); this["Opct"] = pct(g.opacity * 100.0)
            this["Ckmt"] = px(g.spread * 100.0); this["blur"] = px(g.size)
            if (cls == "IrGl") this["glwS"] = PsdDescriptor.EnumValue("IGSr", if (g.fromEdge) "SrcE" else "SrcC")
        }
        val d = PsdDescriptor("null").apply {
            this["Scl "] = pct(100.0); this["masterFXSwitch"] = fx.enabled
            fx.dropShadows.firstOrNull()?.let { this["DrSh"] = shadow("DrSh", it) }
            fx.innerShadows.firstOrNull()?.let { this["IrSh"] = shadow("IrSh", it) }
            fx.outerGlow?.let { this["OrGl"] = glow("OrGl", it) }
            fx.innerGlow?.let { this["IrGl"] = glow("IrGl", it) }
            fx.colorOverlays.firstOrNull()?.let { o -> this["SoFi"] = PsdDescriptor("SoFi").apply {
                this["enab"] = true; this["Md  "] = md(o.blend); this["Opct"] = pct(o.opacity * 100.0); this["Clr "] = clr(o.color)
            } }
            fx.strokes.firstOrNull()?.let { k -> this["FrFX"] = PsdDescriptor("FrFX").apply {
                this["enab"] = true
                this["Styl"] = PsdDescriptor.EnumValue("FStl", when (k.position) {
                    LayerEffects.StrokePosition.OUTSIDE -> "OutF"; LayerEffects.StrokePosition.INSIDE -> "InsF"; LayerEffects.StrokePosition.CENTER -> "CtrF"
                })
                this["PntT"] = PsdDescriptor.EnumValue("FrFl", "SClr"); this["Md  "] = md(k.blend); this["Opct"] = pct(k.opacity * 100.0)
                this["Sz  "] = px(k.size); this["Clr "] = clr(k.color)
            } }
        }
        val w = BinaryWriter(); w.i32(0); w.i32(16); PsdDescriptor.write(w, d)
        return PsdTaggedBlock("8BIM", "lfx2", w.toByteArray())
    }

    private fun opacity(e: PsdDescriptor) = ((e.double("Opct") ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f)

    private fun blend(e: PsdDescriptor, def: BlendMode): BlendMode =
        (e["Md  "] as? PsdDescriptor.EnumValue)?.value?.let { BLEND[it] } ?: def

    /** Farbe aus einem Descriptor (RGB, Graustufen, HSB, CMYK) als 0xRRGGBB. */
    fun color(c: PsdDescriptor?): Int {
        if (c == null) return 0
        fun ch(v: Double) = v.coerceIn(0.0, 255.0).toInt()
        return when (c.classId) {
            "RGBC" -> {
                val r = c.double("Rd  ") ?: c.double("redFloat")?.times(255) ?: 0.0
                val g = c.double("Grn ") ?: c.double("greenFloat")?.times(255) ?: 0.0
                val b = c.double("Bl  ") ?: c.double("blueFloat")?.times(255) ?: 0.0
                (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            }
            "Grsc" -> { val v = ch(255.0 * (1 - (c.double("Gry ") ?: 0.0) / 100.0)); (v shl 16) or (v shl 8) or v }
            "HSBC" -> {
                val h = (c.double("H   ") ?: 0.0) / 60.0; val s = (c.double("Strt") ?: 0.0) / 100.0; val v = (c.double("Brgh") ?: 0.0) / 100.0
                val i = kotlin.math.floor(h).toInt().mod(6); val f = h - kotlin.math.floor(h)
                val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
                val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
                (ch(r * 255) shl 16) or (ch(g * 255) shl 8) or ch(b * 255)
            }
            "CMYC" -> {
                val k = (c.double("Blck") ?: 0.0) / 100.0
                fun one(x: Double?) = ch(255 * (1 - (x ?: 0.0) / 100.0) * (1 - k))
                (one(c.double("Cyn ")) shl 16) or (one(c.double("Mgnt")) shl 8) or one(c.double("Ylw "))
            }
            else -> 0
        }
    }

    private val BLEND = mapOf(
        "Nrml" to BlendMode.NORMAL, "Dslv" to BlendMode.DISSOLVE, "Drkn" to BlendMode.DARKEN, "Mltp" to BlendMode.MULTIPLY,
        "CBrn" to BlendMode.COLOR_BURN, "linearBurn" to BlendMode.LINEAR_BURN, "darkerColor" to BlendMode.DARKER_COLOR,
        "Lghn" to BlendMode.LIGHTEN, "Scrn" to BlendMode.SCREEN, "CDdg" to BlendMode.COLOR_DODGE,
        "linearDodge" to BlendMode.LINEAR_DODGE, "lighterColor" to BlendMode.LIGHTER_COLOR, "Ovrl" to BlendMode.OVERLAY,
        "SftL" to BlendMode.SOFT_LIGHT, "HrdL" to BlendMode.HARD_LIGHT, "vividLight" to BlendMode.VIVID_LIGHT,
        "linearLight" to BlendMode.LINEAR_LIGHT, "pinLight" to BlendMode.PIN_LIGHT, "hardMix" to BlendMode.HARD_MIX,
        "Dfrn" to BlendMode.DIFFERENCE, "Xclu" to BlendMode.EXCLUSION, "blendSubtraction" to BlendMode.SUBTRACT,
        "blendDivide" to BlendMode.DIVIDE, "H   " to BlendMode.HUE, "Strt" to BlendMode.SATURATION,
        "Clr " to BlendMode.COLOR, "Lmns" to BlendMode.LUMINOSITY,
    )
}
