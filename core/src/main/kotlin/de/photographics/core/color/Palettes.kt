package de.photographics.core.color

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Farbfeld: deckende ARGB-Farbe mit optionalem Namen. */
data class Swatch(val color: Int, val name: String = "")

/** Farbfeldbibliothek (Photoshop: „Farbfelder“). */
data class Palette(val name: String, val swatches: List<Swatch>)

class PaletteFormatException(msg: String) : Exception(msg)

/**
 * Lesen und Schreiben von Farbfeldern: Photoshop-Farbfelder (.aco, Version 1 und 2 mit Namen),
 * Adobe Swatch Exchange (.ase, auch aus Illustrator/InDesign), GIMP-Paletten (.gpl) und
 * Photoshop-Farbtabellen (.act, nur lesen). CMYK, Lab, HSB und Graustufen werden ohne
 * Farbprofil nach sRGB umgerechnet (wie GIMP); Lab exakt über D50.
 */
object PaletteIO {
    fun read(bytes: ByteArray, fileName: String): Palette {
        val base = fileName.substringBeforeLast('.').ifBlank { "Farbfelder" }
        return when {
            bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "ASEF" -> readAse(bytes, base)
            bytes.size >= 12 && String(bytes, 0, 12, Charsets.ISO_8859_1) == "GIMP Palette" -> readGpl(String(bytes, Charsets.UTF_8), base)
            fileName.endsWith(".act", ignoreCase = true) && (bytes.size == 768 || bytes.size == 772) -> readAct(bytes, base)
            bytes.size >= 4 && (bytes[1].toInt() == 1 || bytes[1].toInt() == 2) && bytes[0].toInt() == 0 -> readAco(bytes, base)
            else -> throw PaletteFormatException("Unbekanntes Farbfeld-Format")
        }
    }

    // ------------------------------------------------------------ Photoshop .aco

    fun readAco(bytes: ByteArray, name: String): Palette {
        val b = ByteBuffer.wrap(bytes)
        fun u16() = b.short.toInt() and 0xFFFF
        if (b.remaining() < 4) throw PaletteFormatException("ACO-Datei zu kurz")
        var version = u16(); var count = u16()
        var list = readAcoSection(b, version, count)
        // Version 2 folgt auf Version 1 und enthält zusätzlich Namen
        if (version == 1 && b.remaining() >= 4) {
            val v2 = runCatching {
                version = u16(); count = u16()
                if (version == 2) readAcoSection(b, 2, count) else null
            }.getOrNull()
            if (v2 != null && v2.size == list.size) list = v2
        }
        return Palette(name, list)
    }

    private fun readAcoSection(b: ByteBuffer, version: Int, count: Int): List<Swatch> {
        fun u16() = b.short.toInt() and 0xFFFF
        val out = ArrayList<Swatch>(count)
        repeat(count) {
            if (b.remaining() < 10) throw PaletteFormatException("ACO-Datei unvollständig")
            val space = u16(); val w = u16(); val x = u16(); val y = u16(); val z = u16()
            var name = ""
            if (version == 2) {
                b.short // 0
                val len = u16()
                val sb = StringBuilder()
                repeat(len) { val c = b.char; if (c != '\u0000') sb.append(c) }
                name = sb.toString()
            }
            colorFromAco(space, w, x, y, z)?.let { out += Swatch(it, name) }
        }
        return out
    }

    private fun colorFromAco(space: Int, w: Int, x: Int, y: Int, z: Int): Int? = when (space) {
        0 -> rgb(w / 65535.0, x / 65535.0, y / 65535.0)
        1 -> hsb(w / 65535.0, x / 65535.0, y / 65535.0)
        // CMYK: 0 = 100 % Farbe
        2 -> cmyk(1 - w / 65535.0, 1 - x / 65535.0, 1 - y / 65535.0, 1 - z / 65535.0)
        7 -> lab(w / 100.0, x.toShort() / 100.0, y.toShort() / 100.0)
        // Graustufen: 0 … 10000 = Anteil Schwarz
        8 -> (1 - w / 10000.0).let { rgb(it, it, it) }
        // Breites CMYK (Werte 0 … 10000, ohne Umkehrung)
        9 -> cmyk(w / 10000.0, x / 10000.0, y / 10000.0, z / 10000.0)
        else -> null
    }

    fun writeAco(p: Palette): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v shr 8); o.write(v) }
        for (version in 1..2) {
            u16(version); u16(p.swatches.size)
            for (s in p.swatches) {
                u16(0)
                u16(((s.color shr 16) and 0xFF) * 257); u16(((s.color shr 8) and 0xFF) * 257); u16((s.color and 0xFF) * 257); u16(0)
                if (version == 2) {
                    u16(0); u16(s.name.length + 1)
                    for (c in s.name) u16(c.code)
                    u16(0)
                }
            }
        }
        return o.toByteArray()
    }

    // ------------------------------------------------------------ Adobe Swatch Exchange .ase

    fun readAse(bytes: ByteArray, name: String): Palette {
        val b = ByteBuffer.wrap(bytes)
        b.position(4)
        b.short; b.short // Version 1.0
        val blocks = b.int
        val out = ArrayList<Swatch>()
        var paletteName = name
        repeat(blocks) {
            if (b.remaining() < 6) return@repeat
            val type = b.short.toInt() and 0xFFFF
            val len = b.int
            val end = b.position() + len
            if (len < 0 || end > bytes.size) throw PaletteFormatException("ASE-Datei beschädigt")
            when (type) {
                0x0001 -> {
                    val n = b.short.toInt() and 0xFFFF
                    val sb = StringBuilder(); repeat(n) { val c = b.char; if (c != '\u0000') sb.append(c) }
                    val model = String(ByteArray(4).also { b.get(it) }, Charsets.ISO_8859_1).trim()
                    val c = when (model) {
                        "RGB" -> rgb(b.float.toDouble(), b.float.toDouble(), b.float.toDouble())
                        "CMYK" -> cmyk(b.float.toDouble(), b.float.toDouble(), b.float.toDouble(), b.float.toDouble())
                        "LAB" -> { val l = b.float.toDouble(); lab(if (l <= 1.0) l * 100 else l, b.float.toDouble(), b.float.toDouble()) }
                        "Gray" -> b.float.toDouble().let { rgb(it, it, it) }
                        else -> null
                    }
                    if (c != null) out += Swatch(c, sb.toString())
                }
                0xC001 -> {
                    val n = b.short.toInt() and 0xFFFF
                    val sb = StringBuilder(); repeat(n) { val c = b.char; if (c != '\u0000') sb.append(c) }
                    if (out.isEmpty() && sb.isNotBlank()) paletteName = sb.toString()
                }
            }
            b.position(end)
        }
        return Palette(paletteName, out)
    }

    fun writeAse(p: Palette): ByteArray {
        val o = ByteArrayOutputStream()
        val bb = ByteBuffer.allocate(64)
        fun i32(v: Int) { o.write(v ushr 24); o.write(v ushr 16); o.write(v ushr 8); o.write(v) }
        fun u16(v: Int) { o.write(v shr 8); o.write(v) }
        o.write("ASEF".toByteArray(Charsets.ISO_8859_1)); u16(1); u16(0); i32(p.swatches.size)
        for (s in p.swatches) {
            u16(0x0001)
            i32(2 + (s.name.length + 1) * 2 + 4 + 12 + 2)
            u16(s.name.length + 1); for (c in s.name) u16(c.code); u16(0)
            o.write("RGB ".toByteArray(Charsets.ISO_8859_1))
            for (sh in intArrayOf(16, 8, 0)) { bb.clear(); bb.putFloat(((s.color shr sh) and 0xFF) / 255f); o.write(bb.array(), 0, 4) }
            u16(2) // normale Farbe
        }
        return o.toByteArray()
    }

    // ------------------------------------------------------------ GIMP .gpl

    fun readGpl(text: String, name: String): Palette {
        var paletteName = name
        val out = ArrayList<Swatch>()
        for (raw in text.lines().drop(1)) {
            val line = raw.trim()
            when {
                line.startsWith("Name:") -> paletteName = line.removePrefix("Name:").trim()
                line.isEmpty() || line.startsWith("#") || line.startsWith("Columns:") -> {}
                else -> {
                    val parts = line.split(Regex("\\s+"), limit = 4)
                    val r = parts.getOrNull(0)?.toIntOrNull(); val g = parts.getOrNull(1)?.toIntOrNull(); val b = parts.getOrNull(2)?.toIntOrNull()
                    if (r != null && g != null && b != null) {
                        out += Swatch((0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255), parts.getOrNull(3)?.trim() ?: "")
                    }
                }
            }
        }
        return Palette(paletteName, out)
    }

    fun writeGpl(p: Palette): String = buildString {
        append("GIMP Palette\nName: ${p.name}\nColumns: 8\n#\n")
        for (s in p.swatches) {
            append("%3d %3d %3d".format((s.color shr 16) and 0xFF, (s.color shr 8) and 0xFF, s.color and 0xFF))
            append('\t').append(s.name.ifEmpty { "#%06X".format(s.color and 0xFFFFFF) }).append('\n')
        }
    }

    // ------------------------------------------------------------ Photoshop-Farbtabelle .act

    fun readAct(bytes: ByteArray, name: String): Palette {
        val n = if (bytes.size == 772) (((bytes[768].toInt() and 0xFF) shl 8) or (bytes[769].toInt() and 0xFF)).coerceIn(1, 256) else 256
        return Palette(name, List(n) { i ->
            Swatch((0xFF shl 24) or ((bytes[i * 3].toInt() and 0xFF) shl 16) or ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 3 + 2].toInt() and 0xFF))
        })
    }

    // ------------------------------------------------------------ Farbmodelle

    private fun to8(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
    private fun rgb(r: Double, g: Double, b: Double) = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    private fun cmyk(c: Double, m: Double, y: Double, k: Double) = rgb((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k))
    private fun hsb(h: Double, s: Double, v: Double): Int {
        val hh = (h % 1.0) * 6; val i = hh.toInt(); val f = hh - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        return when (i % 6) { 0 -> rgb(v, t, p); 1 -> rgb(q, v, p); 2 -> rgb(p, v, t); 3 -> rgb(p, q, v); 4 -> rgb(t, p, v); else -> rgb(v, p, q) }
    }
    private val srgbEncoder by lazy { RgbEncoder(IccProfiles.sRGB) }
    private fun lab(l: Double, a: Double, b: Double): Int {
        val xyz = DoubleArray(3); Pcs.labToXyz(l, a, b, xyz)
        return (0xFF shl 24) or srgbEncoder.encode(xyz[0], xyz[1], xyz[2])
    }
}

/** Eingebaute Farbfelder. */
object BuiltinPalettes {
    private fun p(name: String, vararg c: Pair<Long, String>) = Palette(name, c.map { (v, n) -> Swatch(v.toInt(), n) })

    val all: List<Palette> = listOf(
        p("Standard",
            0xFF000000 to "Schwarz", 0xFF404040 to "Dunkelgrau", 0xFF808080 to "Grau", 0xFFC0C0C0 to "Hellgrau", 0xFFFFFFFF to "Weiß",
            0xFFFF0000 to "Rot", 0xFFFF8000 to "Orange", 0xFFFFFF00 to "Gelb", 0xFF80FF00 to "Gelbgrün", 0xFF00FF00 to "Grün",
            0xFF00FF80 to "Frühlingsgrün", 0xFF00FFFF to "Cyan", 0xFF0080FF to "Azur", 0xFF0000FF to "Blau", 0xFF8000FF to "Violett",
            0xFFFF00FF to "Magenta", 0xFFFF0080 to "Rosa", 0xFF800000 to "Dunkelrot", 0xFF804000 to "Braun", 0xFF808000 to "Oliv",
            0xFF008000 to "Dunkelgrün", 0xFF008080 to "Petrol", 0xFF000080 to "Marine", 0xFF400080 to "Indigo", 0xFF800040 to "Weinrot",
            0xFFFF9999 to "Pastellrot", 0xFFFFCC99 to "Pastellorange", 0xFFFFFF99 to "Pastellgelb", 0xFF99FF99 to "Pastellgrün",
            0xFF99FFFF to "Pastellcyan", 0xFF9999FF to "Pastellblau", 0xFFFF99FF to "Pastellmagenta",
        ),
        Palette("Graustufen", (0..10).map { i -> val v = (255 - i * 25.5).roundToInt(); Swatch((0xFF shl 24) or (v * 0x010101), "${i * 10} % Schwarz") }),
        p("Hauttöne",
            0xFFFFE0D0 to "Sehr hell", 0xFFF8D5C2 to "Hell rosig", 0xFFF1C27D to "Hell warm", 0xFFE0AC69 to "Mittel hell",
            0xFFD9A383 to "Mittel rosig", 0xFFC68642 to "Mittel", 0xFFB07A5A to "Oliv", 0xFF8D5524 to "Dunkel warm",
            0xFF7A4A33 to "Dunkel", 0xFF5C3A21 to "Sehr dunkel", 0xFF3D2314 to "Tief", 0xFFE8B4A0 to "Wangenrot",
        ),
        p("Material",
            0xFFF44336 to "Red", 0xFFE91E63 to "Pink", 0xFF9C27B0 to "Purple", 0xFF673AB7 to "Deep Purple", 0xFF3F51B5 to "Indigo",
            0xFF2196F3 to "Blue", 0xFF03A9F4 to "Light Blue", 0xFF00BCD4 to "Cyan", 0xFF009688 to "Teal", 0xFF4CAF50 to "Green",
            0xFF8BC34A to "Light Green", 0xFFCDDC39 to "Lime", 0xFFFFEB3B to "Yellow", 0xFFFFC107 to "Amber", 0xFFFF9800 to "Orange",
            0xFFFF5722 to "Deep Orange", 0xFF795548 to "Brown", 0xFF9E9E9E to "Grey", 0xFF607D8B to "Blue Grey",
        ),
    )
}
