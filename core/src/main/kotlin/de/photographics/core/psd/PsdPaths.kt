package de.photographics.core.psd

import de.photographics.core.vector.Anchor
import de.photographics.core.vector.ShapeStroke
import de.photographics.core.vector.SubPath
import de.photographics.core.vector.VectorPath

/**
 * Photoshop-Pfaddatensätze (je 26 Byte): gesicherte Pfade (Bildressourcen 2000–2997, Arbeitspfad
 * 1025) und Vektormasken von Formebenen (Block „vmsk“). Koordinaten als 8.24-Festkomma relativ zu
 * Dokumentbreite und -höhe, jeweils y vor x.
 */
object PsdPaths {
    const val WORK_PATH = 1025
    const val FIRST_SAVED = 2000
    const val LAST_SAVED = 2997

    fun decode(data: ByteArray, w: Int, h: Int): VectorPath {
        val r = BinaryReader(data)
        val subs = ArrayList<SubPath>()
        var cur: ArrayList<Anchor>? = null
        var closed = false
        var remaining = 0
        fun fix() = r.i32() / 16777216.0
        while (r.pos + 26 <= data.size) {
            val start = r.pos
            when (val sel = r.u16()) {
                0, 3 -> {
                    cur?.let { if (it.isNotEmpty()) subs += SubPath(it, closed) }
                    remaining = r.u16(); closed = sel == 0; cur = ArrayList()
                }
                1, 2, 4, 5 -> {
                    val iy = fix() * h; val ix = fix() * w
                    val ay = fix() * h; val ax = fix() * w
                    val oy = fix() * h; val ox = fix() * w
                    if (remaining > 0) { cur?.add(Anchor(ax, ay, ix, iy, ox, oy, smooth = sel == 1 || sel == 4)); remaining-- }
                }
                else -> {}
            }
            r.pos = start + 26
        }
        cur?.let { if (it.isNotEmpty()) subs += SubPath(it, closed) }
        return VectorPath(subs)
    }

    fun encode(p: VectorPath, w: Int, h: Int): ByteArray {
        val b = BinaryWriter()
        // Füllregel-Datensatz am Anfang
        b.u16(6); b.zeros(24)
        fun fix(v: Double, size: Int) = Math.round(v / size * 16777216.0).toInt()
        for (s in p.subpaths) {
            if (s.anchors.isEmpty()) continue
            b.u16(if (s.closed) 0 else 3); b.u16(s.anchors.size); b.zeros(22)
            for (a in s.anchors) {
                b.u16(if (s.closed) (if (a.smooth) 1 else 2) else (if (a.smooth) 4 else 5))
                b.i32(fix(a.inY, h)); b.i32(fix(a.inX, w))
                b.i32(fix(a.y, h)); b.i32(fix(a.x, w))
                b.i32(fix(a.outY, h)); b.i32(fix(a.outX, w))
            }
        }
        return b.toByteArray()
    }

    /** Vektormaske einer Formebene (Block „vmsk“: Version 3, Flags, Datensätze). */
    fun vectorMask(p: VectorPath, w: Int, h: Int): ByteArray = BinaryWriter().also { it.i32(3); it.i32(0); it.bytes(encode(p, w, h)) }.toByteArray()

    fun readVectorMask(data: ByteArray, w: Int, h: Int): VectorPath? = if (data.size < 8) null else decode(data.copyOfRange(8, data.size), w, h)

    /** Volltonfüllung (Block „SoCo“). */
    fun solidColor(c: Int): ByteArray = PsdDescriptor.writeVersioned(PsdDescriptor().apply {
        this["Clr "] = PsdDescriptor("RGBC").apply {
            this["Rd  "] = ((c shr 16) and 0xFF).toDouble(); this["Grn "] = ((c shr 8) and 0xFF).toDouble(); this["Bl  "] = (c and 0xFF).toDouble()
        }
    })

    fun readSolidColor(data: ByteArray): Int? = PsdDescriptor.readVersioned(data)?.descriptor("Clr ")?.let { PsdEffects.color(it) or 0xFF000000.toInt() }

    /** Konturdaten einer Form (Block „vstk“), wie Photoshop sie für Formebenen schreibt. */
    fun stroke(s: ShapeStroke?, hasFill: Boolean): ByteArray = PsdDescriptor.writeVersioned(PsdDescriptor("strokeStyle").apply {
        val c = s?.color ?: 0xFF000000.toInt()
        this["strokeStyleVersion"] = 2
        this["strokeEnabled"] = s != null && s.width > 0f
        this["fillEnabled"] = hasFill
        this["strokeStyleLineWidth"] = PsdDescriptor.UnitFloat("#Pxl", (s?.width ?: 1f).toDouble())
        this["strokeStyleLineDashOffset"] = PsdDescriptor.UnitFloat("#Pnt", 0.0)
        this["strokeStyleMiterLimit"] = 100.0
        this["strokeStyleLineCapType"] = PsdDescriptor.EnumValue("strokeStyleLineCapType", "strokeStyleButtCap")
        this["strokeStyleLineJoinType"] = PsdDescriptor.EnumValue("strokeStyleLineJoinType", "strokeStyleRoundJoin")
        this["strokeStyleLineAlignment"] = PsdDescriptor.EnumValue("strokeStyleLineAlignment", when (s?.align) {
            ShapeStroke.Align.INSIDE -> "strokeStyleAlignInside"; ShapeStroke.Align.OUTSIDE -> "strokeStyleAlignOutside"; else -> "strokeStyleAlignCenter"
        })
        this["strokeStyleScaleLock"] = false
        this["strokeStyleStrokeAdjust"] = false
        this["strokeStyleLineDashSet"] = emptyList<Any>()
        this["strokeStyleBlendMode"] = PsdDescriptor.EnumValue("BlnM", "Nrml")
        this["strokeStyleOpacity"] = PsdDescriptor.UnitFloat("#Prc", (c ushr 24) * 100.0 / 255.0)
        this["strokeStyleContent"] = PsdDescriptor("solidColorLayer").apply {
            this["Clr "] = PsdDescriptor("RGBC").apply {
                this["Rd  "] = ((c shr 16) and 0xFF).toDouble(); this["Grn "] = ((c shr 8) and 0xFF).toDouble(); this["Bl  "] = (c and 0xFF).toDouble()
            }
        }
        this["strokeStyleResolution"] = 72.0
    })

    /** Kontur und Füllung-an aus „vstk“; null = keine Konturdaten. */
    fun readStroke(data: ByteArray): Pair<ShapeStroke?, Boolean>? {
        val d = PsdDescriptor.readVersioned(data) ?: return null
        val fillOn = d.bool("fillEnabled") ?: true
        if (d.bool("strokeEnabled") != true) return null to fillOn
        val width = d.double("strokeStyleLineWidth")?.toFloat() ?: 1f
        val color = d.descriptor("strokeStyleContent")?.descriptor("Clr ")?.let { PsdEffects.color(it) } ?: 0
        val opacity = d.double("strokeStyleOpacity") ?: 100.0
        val align = when ((d["strokeStyleLineAlignment"] as? PsdDescriptor.EnumValue)?.value) {
            "strokeStyleAlignInside" -> ShapeStroke.Align.INSIDE; "strokeStyleAlignOutside" -> ShapeStroke.Align.OUTSIDE; else -> ShapeStroke.Align.CENTER
        }
        return ShapeStroke(width, ((opacity * 2.55).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF), align) to fillOn
    }
}
