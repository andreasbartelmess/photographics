package de.photographics.core.psd

import de.photographics.core.doc.TextAlign
import de.photographics.core.doc.TextStyle
import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import java.io.ByteArrayOutputStream

/** Text- und Smartobjekt-Daten im PSD. */
internal object PsdTextSmart {
    /** Eigener Block für editierbaren Text (Photoshop ignoriert unbekannte Schlüssel). */
    const val TEXT_KEY = "pgTX"
    /** Eigener Block für Smartobjekte: Originalraster + Transformation. */
    const val SMART_KEY = "pgSO"

    // ------------------------------------------------------------ Photoshop-Text (TySh)

    /** Liest Text, Formatierung und Lage aus einem Photoshop-Textblock. */
    fun parseTySh(data: ByteArray): Pair<TextStyle, Affine>? = runCatching {
        val r = BinaryReader(data)
        r.u16()
        val m = DoubleArray(6) { r.f64() }
        r.u16(); r.i32()
        val desc = PsdDescriptor.read(r)
        val text = (desc.string("Txt ") ?: "").replace('\r', '\n').trimEnd('\u0000', '\n')
        val engine = (desc["EngineData"] as? PsdDescriptor.RawData)?.bytes
        val e = engine?.let { String(it, Charsets.ISO_8859_1) } ?: ""

        val size = Regex("/FontSize ([0-9.]+)").find(e)?.groupValues?.get(1)?.toFloatOrNull() ?: 48f
        val color = Regex("/FillColor << /Type 1 /Values \\[ ([0-9.]+) ([0-9.]+) ([0-9.]+) ([0-9.]+) \\]").find(e)?.let { mt ->
            val (a, rr, g, b) = mt.destructured
            fun c(s: String) = ((s.toFloatOrNull() ?: 0f) * 255f).toInt().coerceIn(0, 255)
            (c(a) shl 24) or (c(rr) shl 16) or (c(g) shl 8) or c(b)
        } ?: 0xFF000000.toInt()
        val just = Regex("/Justification (\\d)").find(e)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val fontName = firstFontName(engine)
        val tracking = Regex("/Tracking (-?[0-9.]+)").find(e)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val leadingAuto = Regex("/AutoLeading (true|false)").find(e)?.groupValues?.get(1) != "false"
        val leading = Regex("/Leading ([0-9.]+)").find(e)?.groupValues?.get(1)?.toFloatOrNull()

        val style = TextStyle(
            text = text,
            fontFamily = mapFont(fontName),
            sizePx = size,
            color = color,
            bold = fontName.contains("Bold", true) || e.contains("/FauxBold true"),
            italic = fontName.contains("Italic", true) || fontName.contains("Oblique", true) || e.contains("/FauxItalic true"),
            align = when (just) { 1 -> TextAlign.RIGHT; 2 -> TextAlign.CENTER; else -> TextAlign.LEFT },
            lineHeight = if (leadingAuto || leading == null) 1.2f else leading / size,
            letterSpacing = tracking / 1000f,
        )
        // Photoshop-Ursprung ist die Grundlinie der ersten Zeile; unserer ist die obere Kante
        val ps = Affine(m[0], m[1], m[2], m[3], m[4], m[5])
        style to Affine.translate(0.0, -size * 0.93).then(ps)
    }.getOrNull()

    /** Erster Schriftname aus /FontSet [ << /Name (þÿ…) … ] (UTF-16BE mit BOM, geklammert). */
    private fun firstFontName(engine: ByteArray?): String {
        engine ?: return ""
        val s = String(engine, Charsets.ISO_8859_1)
        val i = s.indexOf("/FontSet")
        if (i < 0) return ""
        val n = s.indexOf("/Name (", i)
        if (n < 0) return ""
        var k = n + 7
        val out = ByteArrayOutputStream()
        while (k < engine.size) {
            val b = engine[k].toInt() and 0xFF
            if (b == '\\'.code && k + 1 < engine.size) { out.write(engine[k + 1].toInt()); k += 2; continue }
            if (b == ')'.code) break
            out.write(b); k++
        }
        val bytes = out.toByteArray()
        val start = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) 2 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_16BE)
    }

    /** Photoshop-Schrift grob auf eine Android-Systemschrift abbilden. */
    private fun mapFont(ps: String): String {
        val n = ps.lowercase()
        return when {
            n.contains("mono") || n.contains("courier") || n.contains("consol") -> "monospace"
            n.contains("times") || n.contains("serif") && !n.contains("sans") || n.contains("georgia") || n.contains("garamond") || n.contains("minion") -> "serif"
            n.contains("condensed") || n.contains("narrow") -> "sans-serif-condensed"
            n.contains("script") || n.contains("brush") || n.contains("hand") -> "cursive"
            else -> "sans-serif"
        }
    }

    // ------------------------------------------------------------ Eigene Blöcke

    fun writeText(style: TextStyle, t: Affine): ByteArray {
        val s = "v=1\ntransform=" + t.toArray().joinToString(",") + "\n" + style.serialize()
        return s.toByteArray(Charsets.UTF_8)
    }

    fun readText(data: ByteArray): Pair<TextStyle, Affine>? = runCatching {
        val s = String(data, Charsets.UTF_8)
        val tLine = s.lineSequence().first { it.startsWith("transform=") }
        val t = Affine.of(tLine.removePrefix("transform=").split(',').map { it.toDouble() }.toDoubleArray())
        TextStyle.parse(s) to t
    }.getOrNull()

    /** [warp]: verzerrte Ecken (8 Werte) – am Ende als „PGWP“-Abschnitt angehängt (ältere Leser ignorieren ihn). */
    fun writeSmart(source: TileGrid, t: Affine, warp: DoubleArray? = null): ByteArray {
        val w = BinaryWriter()
        w.u16(1)
        for (v in t.toArray()) w.f64(v)
        w.i32(source.width); w.i32(source.height)
        // Zeilenweise aus den Kacheln lesen – kein Puffer in Bildgröße
        val sw = source.width
        val row = IntArray(sw); val plane = ByteArray(sw)
        val rowOut = ByteArrayOutputStream()
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val enc = ByteArrayOutputStream()
            for (y in 0 until source.height) {
                source.readRect(IntRect(0, y, sw, y + 1), row)
                for (x in 0 until sw) plane[x] = (row[x] ushr shift).toByte()
                rowOut.reset()
                PackBits.encode(plane, 0, sw, rowOut)
                // Zeilenlänge als u32, damit auch sehr breite Bilder funktionieren
                val n = rowOut.size()
                enc.write(n ushr 24); enc.write(n ushr 16); enc.write(n ushr 8); enc.write(n)
                rowOut.writeTo(enc)
            }
            w.bytes(enc.toByteArray())
        }
        if (warp != null) { w.ascii("PGWP"); for (v in warp) w.f64(v) }
        return w.toByteArray()
    }

    /** Wie [readSmart], zusätzlich die verzerrten Ecken (oder null). */
    fun readSmartWarp(data: ByteArray): DoubleArray? = runCatching {
        if (data.size < 68) return@runCatching null
        val r = BinaryReader(data); r.pos = data.size - 68
        if (r.ascii(4) == "PGWP") DoubleArray(8) { r.f64() } else null
    }.getOrNull()

    fun readSmart(data: ByteArray): Pair<TileGrid, Affine>? = runCatching {
        val r = BinaryReader(data)
        r.u16()
        val t = Affine.of(DoubleArray(6) { r.f64() })
        val w = r.i32(); val h = r.i32()
        // Kanäle nacheinander direkt in die Kacheln (zeilenweise Lesen-Ändern-Schreiben)
        val g = TileGrid(w, h)
        val row = ByteArray(w); val line = IntArray(w)
        for (shift in intArrayOf(24, 16, 8, 0)) {
            for (y in 0 until h) {
                val n = r.i32()
                PackBits.decode(r, r.pos, n, row, 0, w)
                r.skip(n.toLong())
                val rr = IntRect(0, y, w, y + 1)
                g.readRect(rr, line)
                for (x in 0 until w) line[x] = line[x] or ((row[x].toInt() and 0xFF) shl shift)
                g.writeRect(rr, line)
            }
        }
        g.compact()
        g to t
    }.getOrNull()
}
