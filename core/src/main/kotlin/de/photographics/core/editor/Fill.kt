package de.photographics.core.editor

import de.photographics.core.image.Colors
import de.photographics.core.render.BlendMode
import de.photographics.core.tr

/** Inhalt für „Fläche füllen“ (Photoshop: Bearbeiten › Fläche füllen, Umschalt+F5). */
enum class FillContent(val label: String) {
    FOREGROUND(tr("Vordergrundfarbe")),
    BACKGROUND(tr("Hintergrundfarbe")),
    COLOR(tr("Farbe …")),
    CONTENT_AWARE(tr("Inhaltsbasiert")),
    BLACK(tr("Schwarz")),
    GRAY(tr("50 % Grau")),
    WHITE(tr("Weiß"));

    /** Füllfarbe für diesen Inhalt (null bei inhaltsbasiert). */
    fun color(foreground: Int, background: Int, custom: Int): Int? = when (this) {
        FOREGROUND -> foreground; BACKGROUND -> background; COLOR -> custom
        BLACK -> 0xFF000000.toInt(); GRAY -> 0xFF808080.toInt(); WHITE -> -1
        CONTENT_AWARE -> null
    }
}

/** Mischen einzelner Pixel mit Mischmodus (Füllen, Füllwerkzeug). */
object PixelBlend {
    /**
     * [src] (Farbe, Deckung im Alphakanal) mit [mode] und Deckkraft [opacity] über [dst] legen, wie
     * ein Ebenenpixel über dem darunterliegenden (W3C-Compositing, nicht vormultipliziert).
     * [preserveAlpha]: Deckung von [dst] bleibt (Photoshop: „Transparenz erhalten“).
     */
    fun over(dst: Int, src: Int, mode: BlendMode, opacity: Float, preserveAlpha: Boolean = false): Int {
        val ab = Colors.a(dst) / 255f
        val asrc = Colors.a(src) / 255f * opacity.coerceIn(0f, 1f)
        if (asrc <= 0f) return dst
        if (preserveAlpha && ab <= 0f) return dst
        val br = Colors.r(dst) / 255f; val bg = Colors.g(dst) / 255f; val bb = Colors.b(dst) / 255f
        val sr = Colors.r(src) / 255f; val sg = Colors.g(src) / 255f; val sb = Colors.b(src) / 255f
        val out = FloatArray(3)
        if (mode.separable) { out[0] = mode.blendChannel(br, sr); out[1] = mode.blendChannel(bg, sg); out[2] = mode.blendChannel(bb, sb) }
        else mode.blendNonSeparable(br, bg, bb, sr, sg, sb, out)
        // Gemischte Quellfarbe: auf transparentem Grund die Quelle selbst
        val mr = (1 - ab) * sr + ab * out[0]; val mg = (1 - ab) * sg + ab * out[1]; val mb = (1 - ab) * sb + ab * out[2]
        if (preserveAlpha) {
            fun ch(b: Float, m: Float) = (b + (m - b) * asrc).coerceIn(0f, 1f)
            return rgba(ch(br, mr), ch(bg, mg), ch(bb, mb), ab)
        }
        val ao = asrc + ab * (1 - asrc)
        if (ao <= 0f) return 0
        fun ch(b: Float, m: Float) = ((asrc * m + ab * (1 - asrc) * b) / ao).coerceIn(0f, 1f)
        return rgba(ch(br, mr), ch(bg, mg), ch(bb, mb), ao)
    }

    private fun rgba(r: Float, g: Float, b: Float, a: Float) =
        ((a * 255f + 0.5f).toInt() shl 24) or ((r * 255f + 0.5f).toInt() shl 16) or ((g * 255f + 0.5f).toInt() shl 8) or (b * 255f + 0.5f).toInt()
}
