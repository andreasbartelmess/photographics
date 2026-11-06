package de.photographics.core.psd

/**
 * Photoshops eingebettete Smartobjekte: je Ebene ein „SoLd“-Block (Descriptor mit Kennung, Lage der
 * vier Ecken, Größe des Inhalts) und global „lnk2“ mit den eingebetteten Dateien („liFD“), über die
 * Kennung verbunden. Aufbau wie in psd-tools/ag-psd beschrieben.
 */
object PsdLinked {
    /** Globale Blöcke mit eingebetteten/verknüpften Dateien. */
    val GLOBAL_KEYS = setOf("lnk2", "lnk3", "lnkD")

    /** Eine Datei aus „lnk2“: [raw] = vollständiger Eintrag zum unveränderten Zurückschreiben. */
    class Entry(val kind: String, val uuid: String, val name: String, val fileType: String, val data: ByteArray?, val raw: ByteArray)

    /** Lage eines platzierten Inhalts: Kennung, Ecken im Dokument (oben links, oben rechts, unten rechts, unten links), Größe. */
    class Placed(val uuid: String, val corners: DoubleArray, val width: Double, val height: Double, val warped: Boolean, val filtered: Boolean)

    // ------------------------------------------------------------ lnk2

    fun readEntries(data: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        val r = BinaryReader(data)
        while (r.remaining >= 8) {
            val len = r.i64()
            if (len <= 0 || len > r.remaining) break
            val start = r.pos
            val raw = ByteArray(len.toInt()).also { r.copy(start, it, 0, it.size) }
            runCatching { out += parseEntry(raw) }
            r.seek(start + len.toInt())
            var n = len; while (n % 4 != 0L && r.remaining > 0) { r.skip(1); n++ }
        }
        return out
    }

    private fun parseEntry(raw: ByteArray): Entry {
        val r = BinaryReader(raw)
        val kind = r.ascii(4)
        r.i32() // Version
        val uuid = r.pascal(1)
        val name = r.unicode()
        val type = r.ascii(4); r.ascii(4)
        val size = r.i64()
        if (r.u8() != 0) { r.i32(); PsdDescriptor.read(r) }
        val data = if (kind == "liFD" && size in 0..r.remaining.toLong()) r.bytes(size.toInt()) else null
        return Entry(kind, uuid, name, type, data, raw)
    }

    /** Eintrag „eingebettete Datei“ (Version 7) samt Längenangabe und Auffüllung. */
    fun writeEntry(uuid: String, name: String, fileType: String, data: ByteArray): ByteArray {
        val e = BinaryWriter()
        e.ascii("liFD"); e.i32(7)
        e.pascal(uuid, 1)
        e.unicodeNoTerm(name)
        e.ascii(fileType); e.ascii("8BIM")
        e.i32((data.size.toLong() ushr 32).toInt()); e.i32(data.size)
        e.u8(0)                      // kein Descriptor zum Öffnen
        e.bytes(data)
        e.unicodeNoTerm("")          // Kennung des Kinddokuments
        e.f64(0.0)                   // Änderungszeit
        e.u8(0)                      // nicht gesperrt
        return rawToBlockEntry(e.toByteArray())
    }

    /** Vollständigen Eintrag mit 8-Byte-Länge und Auffüllung auf 4 Bytes versehen. */
    fun rawToBlockEntry(raw: ByteArray): ByteArray {
        val w = BinaryWriter()
        w.i32((raw.size.toLong() ushr 32).toInt()); w.i32(raw.size)
        w.bytes(raw)
        var n = raw.size; while (n % 4 != 0) { w.u8(0); n++ }
        return w.toByteArray()
    }

    // ------------------------------------------------------------ SoLd

    fun readPlaced(data: ByteArray): Placed? = runCatching {
        val r = BinaryReader(data)
        if (r.ascii(4) != "soLD") return@runCatching null
        r.i32(); r.i32()
        val d = PsdDescriptor.read(r)
        val uuid = d.string("Idnt") ?: return@runCatching null
        val corners = (d["Trnf"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 8 }?.toDoubleArray() ?: return@runCatching null
        val size = d.descriptor("Sz  ")
        val w = size?.double("Wdth") ?: return@runCatching null
        val h = size.double("Hght") ?: return@runCatching null
        val warp = d.descriptor("warp")
        val style = (warp?.get("warpStyle") as? PsdDescriptor.EnumValue)?.value
        val nonAffine = (d["nonAffineTransform"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
        // Verkrümmt (Photoshop-Verkrümmen oder Gitter) können wir nicht nachbilden
        val warped = (style != null && style != "warpNone") || warp?.get("customEnvelopeWarp") != null ||
            (nonAffine != null && nonAffine.size == 8 && nonAffine.indices.any { kotlin.math.abs(nonAffine[it] - corners[it]) > 1e-6 })
        Placed(uuid, corners, w, h, warped, d["filterFX"] != null)
    }.getOrNull()

    /**
     * „SoLd“ für einen eingebetteten Inhalt [uuid] der Größe [w]×[h], dessen Ecken im Dokument bei
     * [corners] liegen (Photoshops Werte für ein frisch umgewandeltes Smartobjekt).
     */
    fun writePlaced(uuid: String, corners: DoubleArray, w: Int, h: Int, dpi: Double): ByteArray {
        fun obj(cls: String, vararg kv: Pair<String, Any?>) = PsdDescriptor(cls).also { d -> for ((k, v) in kv) d[k] = v }
        fun px(v: Double) = PsdDescriptor.UnitFloat("#Pxl", v)
        val d = obj("null",
            "Idnt" to uuid,
            "placed" to uuid,
            "PgNm" to 1,
            "totalPages" to 1,
            "frameStep" to obj("null", "numerator" to 0, "denominator" to 600),
            "duration" to obj("null", "numerator" to 0, "denominator" to 600),
            "frameCount" to 1,
            "Annt" to 16,
            "Type" to 2,
            "Trnf" to corners.toList(),
            "nonAffineTransform" to corners.toList(),
            "warp" to noWarp(w, h),
            "Sz  " to obj("Pnt ", "Wdth" to w.toDouble(), "Hght" to h.toDouble()),
            "Rslt" to PsdDescriptor.UnitFloat("#Rsl", dpi),
            "comp" to -1,
            "compInfo" to obj("null", "compID" to -1, "originalCompID" to -1),
        )
        val out = BinaryWriter()
        out.ascii("soLD"); out.i32(4); out.i32(16)
        PsdDescriptor.write(out, d)
        return out.toByteArray()
    }

    /** Verkrümmung „keine“ über den ganzen Inhalt. */
    private fun noWarp(w: Int, h: Int): PsdDescriptor {
        fun px(v: Double) = PsdDescriptor.UnitFloat("#Pxl", v)
        return PsdDescriptor("warp").also { d ->
            d["warpStyle"] = PsdDescriptor.EnumValue("warpStyle", "warpNone")
            d["warpValue"] = 0.0
            d["warpPerspective"] = 0.0
            d["warpPerspectiveOther"] = 0.0
            d["warpRotate"] = PsdDescriptor.EnumValue("Ornt", "Hrzn")
            d["bounds"] = PsdDescriptor("classFloatRect").also { b -> b["Top "] = px(0.0); b["Left"] = px(0.0); b["Btom"] = px(h.toDouble()); b["Rght"] = px(w.toDouble()) }
            d["uOrder"] = 4
            d["vOrder"] = 4
        }
    }

    /** Älterer Block „PlLd“ (schreibt Photoshop zusätzlich zu „SoLd“; manche Programme lesen nur ihn). */
    fun writePlacedLegacy(uuid: String, corners: DoubleArray, w: Int, h: Int): ByteArray {
        val out = BinaryWriter()
        out.ascii("plcL"); out.i32(3)
        out.pascal(uuid, 1)
        out.i32(1); out.i32(1); out.i32(16); out.i32(2)   // Seite, Seiten, Glättung, Rasterbild
        for (v in corners) out.f64(v)
        out.i32(0); out.i32(16)
        PsdDescriptor.write(out, noWarp(w, h))
        while (out.size % 4 != 0) out.u8(0)
        return out.toByteArray()
    }

    /**
     * Abbildung Inhalt ([w]×[h]) → Dokument aus den vier Ecken: affin, wenn die Ecken ein
     * Parallelogramm bilden, sonst null (dann perspektivisch über die Ecken).
     */
    fun affineFor(corners: DoubleArray, w: Double, h: Double): de.photographics.core.image.Affine? {
        val (x0, y0) = corners[0] to corners[1]
        val (x1, y1) = corners[2] to corners[3]
        val (x2, y2) = corners[4] to corners[5]
        val (x3, y3) = corners[6] to corners[7]
        // Parallelogramm: gegenüberliegende Seiten gleich
        val tol = 1e-3 * maxOf(1.0, kotlin.math.abs(x1 - x0) + kotlin.math.abs(y3 - y0))
        if (kotlin.math.abs((x1 - x0) - (x2 - x3)) > tol || kotlin.math.abs((y1 - y0) - (y2 - y3)) > tol) return null
        return de.photographics.core.image.Affine((x1 - x0) / w, (y1 - y0) / w, (x3 - x0) / h, (y3 - y0) / h, x0, y0)
    }
}
