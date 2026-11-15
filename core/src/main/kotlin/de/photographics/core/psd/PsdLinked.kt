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
    class Placed(val uuid: String, val corners: DoubleArray, val width: Double, val height: Double, val warped: Boolean, val filtered: Boolean,
        /** „placed“-Kennung (Schlüssel der Filtermaske in „FEid“). */ val placedId: String = uuid, val filterFX: PsdDescriptor? = null)

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
        Placed(uuid, corners, w, h, warped, d["filterFX"] != null, d.string("placed") ?: uuid, d.descriptor("filterFX"))
    }.getOrNull()

    /**
     * „SoLd“ für einen eingebetteten Inhalt [uuid] der Größe [w]×[h], dessen Ecken im Dokument bei
     * [corners] liegen (Photoshops Werte für ein frisch umgewandeltes Smartobjekt).
     */
    fun writePlaced(uuid: String, corners: DoubleArray, w: Int, h: Int, dpi: Double, filterFX: PsdDescriptor? = null): ByteArray {
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
        if (filterFX != null) {
            // Wie Photoshop: Smartfilter nach der Auflösung, vor „comp“
            val items = LinkedHashMap(d.items); d.items.clear()
            for ((k, v) in items) { if (k == "comp") d.items["filterFX"] = filterFX; d.items[k] = v }
        }
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

    // ------------------------------------------------------------ FEid (Smartfilter-Zwischenspeicher)

    /** Ein Eintrag aus „FEid“: Kennung („placed“), Rohdaten und – falls bemalt – die Filtermaske (sonst null). */
    class FilterEffectEntry(val id: String, val raw: ByteArray, val mask: de.photographics.core.image.TileGrid?)

    /**
     * Einträge aus „FEid“ (Photoshop: Bild vor den Smartfiltern als Zwischenspeicher plus Filtermaske).
     * Photoshop schreibt die Maske immer, auch unbemalt – [FilterEffectEntry.mask] ist dann null.
     */
    fun readFilterEffects(data: ByteArray, docW: Int, docH: Int): List<FilterEffectEntry> = runCatching {
        val r = BinaryReader(data)
        r.i32()   // Version
        val out = ArrayList<FilterEffectEntry>()
        while (r.remaining >= 8) {
            val len = r.i64().toInt(); val start = r.pos; val end = start + len
            val raw = data.copyOfRange(start, end.coerceAtMost(data.size))
            val e = BinaryReader(raw)
            val n = e.u8(); val id = e.ascii(n)
            e.i32()                                          // Version
            val bodyLen = e.i64(); e.skip(bodyLen)           // Zwischenspeicher-Kanäle
            var mask: de.photographics.core.image.TileGrid? = null
            if (e.remaining >= 1 && e.u8() != 0) {
                val top = e.i32(); val left = e.i32(); val bottom = e.i32(); val right = e.i32()
                val mlen = e.i64().toInt(); val comp = e.u16()
                val body = e.bytes(mlen - 2)
                val w = right - left; val h = bottom - top
                val px = decodePlane(body, comp, w, h)
                if (px != null && px.any { it != 0xFF.toByte() }) {
                    mask = de.photographics.core.image.TileGrid(docW, docH, 255).also { g ->
                        val rect = de.photographics.core.image.IntRect(left, top, right, bottom).intersect(de.photographics.core.image.IntRect(0, 0, docW, docH))
                        if (!rect.isEmpty) {
                            val vals = IntArray(rect.width * rect.height) { i ->
                                val x = rect.left + i % rect.width - left; val y = rect.top + i / rect.width - top
                                px[y * w + x].toInt() and 0xFF
                            }
                            g.writeRect(rect, vals)
                        }
                    }
                }
            }
            out += FilterEffectEntry(id, raw, mask)
            r.seek((end + (4 - len % 4) % 4).coerceAtMost(data.size))
        }
        out
    }.getOrDefault(emptyList())

    /** Kanal (0 = roh, 1 = RLE mit 4-Byte-Zeilenlängen) entpacken. */
    private fun decodePlane(body: ByteArray, comp: Int, w: Int, h: Int): ByteArray? {
        if (w <= 0 || h <= 0) return ByteArray(0)
        val out = ByteArray(w * h)
        when (comp) {
            0 -> { if (body.size < w * h) return null; System.arraycopy(body, 0, out, 0, w * h) }
            1 -> {
                var p = 4 * h
                for (y in 0 until h) {
                    val n = BinaryReader(body.copyOfRange(4 * y, 4 * y + 4)).i32()
                    PackBits.decode(body, p, n, out, y * w, w)
                    p += n
                }
            }
            else -> return null
        }
        return out
    }

    /** Zeilenweise RLE mit 4-Byte-Zeilenlängen (wie Photoshop in „FEid“). */
    private fun encodePlane(w: Int, h: Int, row: (Int, ByteArray) -> Unit): ByteArray {
        val counts = BinaryWriter(); val data = java.io.ByteArrayOutputStream()
        val buf = ByteArray(w)
        for (y in 0 until h) {
            row(y, buf)
            val before = data.size()
            PackBits.encode(buf, 0, w, data)
            counts.i32(data.size() - before)
        }
        return counts.toByteArray() + data.toByteArray()
    }

    /**
     * „FEid“-Eintrag für ein Smartobjekt mit Filtermaske: Photoshop braucht dazu das Bild vor den Filtern
     * (RGB + Transparenz in Dokumentkoordinaten) – nur die Maske allein lässt Photoshop 2026 abstürzen.
     */
    fun writeFilterEffect(placedId: String, unfiltered: de.photographics.core.image.TileGrid, mask: de.photographics.core.image.TileGrid, docW: Int, docH: Int): ByteArray {
        val rect = de.photographics.core.image.IntRect(0, 0, docW, docH)
        val line = IntArray(docW)
        fun plane(shift: Int?) = encodePlane(docW, docH) { y, buf ->
            unfiltered.readRect(de.photographics.core.image.IntRect(0, y, docW, y + 1), line)
            for (x in 0 until docW) {
                val c = line[x]; val a = c ushr 24
                buf[x] = (if (shift == null) a else if (a == 0) 255 else (c shr shift) and 0xFF).toByte()
            }
        }
        val body = BinaryWriter()
        body.i32(rect.top); body.i32(rect.left); body.i32(rect.bottom); body.i32(rect.right)
        body.i32(8); body.i32(24)
        for (ch in 0 until 26) {
            val d = when (ch) { 0 -> plane(16); 1 -> plane(8); 2 -> plane(0); 25 -> plane(null); else -> null }
            if (d == null) { body.i32(0); continue }
            body.i32(1); body.i32(0); body.i32(d.size + 2); body.u16(1); body.bytes(d)
        }
        val maskData = encodePlane(docW, docH) { y, buf ->
            mask.readRect(de.photographics.core.image.IntRect(0, y, docW, y + 1), line)
            for (x in 0 until docW) buf[x] = line[x].coerceIn(0, 255).toByte()
        }
        val e = BinaryWriter()
        val id = placedId.toByteArray(Charsets.US_ASCII)
        e.u8(id.size); e.bytes(id)
        e.i32(1)
        val b = body.toByteArray(); e.i32(0); e.i32(b.size); e.bytes(b)
        e.u8(1); e.i32(rect.top); e.i32(rect.left); e.i32(rect.bottom); e.i32(rect.right)
        e.i32(0); e.i32(maskData.size + 2); e.u16(1); e.bytes(maskData)
        return e.toByteArray()
    }

    /** „FEid“-Block aus Einträgen (je mit 8-Byte-Länge, auf 4 Byte aufgefüllt). */
    fun filterEffectsBlock(entries: List<ByteArray>): ByteArray {
        val w = BinaryWriter(); w.i32(3)
        for (raw in entries) { w.i32(0); w.i32(raw.size); w.bytes(raw); var n = raw.size; while (n % 4 != 0) { w.u8(0); n++ } }
        return w.toByteArray()
    }

    /** „FMsk“ wie Photoshop: Überlagerungsfarbe Rot, 50 %. */
    val DEFAULT_FILTER_MASK_SETTINGS: ByteArray = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 50)
}
