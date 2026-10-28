package de.photographics.core.doc

import de.photographics.core.image.Affine
import de.photographics.core.image.TileGrid

enum class TextAlign(val label: String) { LEFT("Links"), CENTER("Zentriert"), RIGHT("Rechts") }

/** Formatierung einer Textebene. Größen in Dokumentpixeln vor der Transformation. */
data class TextStyle(
    val text: String = "Text",
    val fontFamily: String = "sans-serif",
    val sizePx: Float = 96f,
    val color: Int = 0xFF000000.toInt(),
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: TextAlign = TextAlign.LEFT,
    /** Zeilenabstand als Faktor der Schriftgröße. */
    val lineHeight: Float = 1.2f,
    /** Laufweite in em (Photoshop „Tracking“ / 1000). */
    val letterSpacing: Float = 0f,
) {
    /** Einfache, robuste Serialisierung für den eigenen PSD-Block. */
    fun serialize(): String = buildString {
        fun put(k: String, v: Any) = append(k).append('=').append(v.toString().replace("\\", "\\\\").replace("\n", "\\n")).append('\n')
        put("text", text); put("font", fontFamily); put("size", sizePx); put("color", Integer.toHexString(color))
        put("bold", bold); put("italic", italic); put("align", align.name); put("lineHeight", lineHeight); put("letterSpacing", letterSpacing)
    }

    companion object {
        fun parse(s: String): TextStyle {
            val m = HashMap<String, String>()
            for (line in s.split('\n')) {
                val i = line.indexOf('='); if (i <= 0) continue
                val raw = line.substring(i + 1)
                val sb = StringBuilder(); var k = 0
                while (k < raw.length) {
                    val ch = raw[k]
                    if (ch == '\\' && k + 1 < raw.length) { sb.append(if (raw[k + 1] == 'n') '\n' else raw[k + 1]); k += 2 } else { sb.append(ch); k++ }
                }
                m[line.substring(0, i)] = sb.toString()
            }
            val d = TextStyle()
            return TextStyle(
                text = m["text"] ?: d.text,
                fontFamily = m["font"] ?: d.fontFamily,
                sizePx = m["size"]?.toFloatOrNull() ?: d.sizePx,
                color = m["color"]?.toLongOrNull(16)?.toInt() ?: d.color,
                bold = m["bold"]?.toBoolean() ?: false,
                italic = m["italic"]?.toBoolean() ?: false,
                align = runCatching { TextAlign.valueOf(m["align"] ?: "") }.getOrDefault(TextAlign.LEFT),
                lineHeight = m["lineHeight"]?.toFloatOrNull() ?: d.lineHeight,
                letterSpacing = m["letterSpacing"]?.toFloatOrNull() ?: 0f,
            )
        }
    }
}

/**
 * Plattform-Schnittstelle zum Rastern von Text (Android: StaticLayout/Canvas).
 * Der Ursprung (0,0) ist die obere linke Ecke des Textblocks vor der Transformation.
 */
interface TextRasterizer {
    fun render(style: TextStyle, transform: Affine, docWidth: Int, docHeight: Int): TileGrid

    /** Größe des Textblocks (Breite, Höhe) in Pixeln vor der Transformation. */
    fun measure(style: TextStyle): Pair<Float, Float>

    companion object {
        @Volatile var current: TextRasterizer? = null
    }
}
