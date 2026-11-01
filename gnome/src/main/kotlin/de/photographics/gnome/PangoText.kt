package de.photographics.gnome

import de.photographics.core.doc.TextAlign
import de.photographics.core.doc.TextRasterizer
import de.photographics.core.doc.TextStyle
import de.photographics.core.image.Affine
import de.photographics.core.image.TileGrid
import org.freedesktop.cairo.Context
import org.freedesktop.cairo.Format
import org.freedesktop.cairo.ImageSurface
import org.freedesktop.cairo.Matrix
import org.gnome.pango.Alignment
import org.gnome.pango.AttrList
import org.gnome.pango.FontDescription
import org.gnome.pango.Layout
import org.gnome.pango.Style
import org.gnome.pango.Weight
import org.gnome.pango.cairo.PangoCairo
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.math.ceil
import kotlin.math.max

/** Rastert Textebenen mit Pango/Cairo – direkt in der Zieltransformation (gestochen scharf). */
object PangoText : TextRasterizer {

    /** Android-Schriftfamilien (aus PSDs der App) → Fontconfig-Namen. */
    fun family(f: String): String = when (f) {
        "sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-black", "sans-serif-condensed", "sans-serif-smallcaps" -> "Sans"
        "serif" -> "Serif"
        "monospace" -> "Monospace"
        "cursive", "casual" -> "Cursive"
        else -> f
    }

    private fun layout(cr: Context, s: TextStyle): Layout {
        val l = PangoCairo.createLayout(cr)
        PangoCairo.contextSetResolution(l.context, 72.0)
        val fd = FontDescription()
        fd.family = family(s.fontFamily)
        fd.setAbsoluteSize(s.sizePx * org.gnome.pango.Pango.SCALE.toDouble())
        fd.weight = if (s.bold) Weight.BOLD else when { s.fontFamily.endsWith("-light") -> Weight.LIGHT; s.fontFamily.endsWith("-black") -> Weight.HEAVY; s.fontFamily.endsWith("-medium") -> Weight.MEDIUM; else -> Weight.NORMAL }
        fd.style = if (s.italic) Style.ITALIC else Style.NORMAL
        l.fontDescription = fd
        l.alignment = when (s.align) { TextAlign.LEFT -> Alignment.LEFT; TextAlign.CENTER -> Alignment.CENTER; TextAlign.RIGHT -> Alignment.RIGHT }
        // Zeilenabstand als Faktor der Schriftgröße (wie die Android-App: 1,17 ≈ natürlicher Abstand)
        l.lineSpacing = (s.lineHeight / 1.17f).coerceAtLeast(0.01f)
        if (s.letterSpacing != 0f) {
            val attrs = AttrList.fromString("0 -1 letter-spacing ${(s.letterSpacing * s.sizePx * org.gnome.pango.Pango.SCALE).toInt()}")
            if (attrs != null) l.attributes = attrs
        }
        l.setText(s.text.ifEmpty { " " }, -1)
        return l
    }

    private fun pixelSize(l: Layout): Pair<Int, Int> {
        val w = org.javagi.base.Out(0); val h = org.javagi.base.Out(0)
        l.getPixelSize(w, h)
        return max(1, (w.get() ?: 1) + 2) to max(1, h.get() ?: 1)
    }

    override fun measure(style: TextStyle): Pair<Float, Float> {
        val surf = ImageSurface.create(Format.ARGB32, 1, 1)
        val cr = Context.create(surf)
        val (w, h) = pixelSize(layout(cr, style))
        return w.toFloat() to h.toFloat()
    }

    override fun render(style: TextStyle, transform: Affine, docWidth: Int, docHeight: Int): TileGrid {
        val out = TileGrid(docWidth, docHeight)
        val (lw, lh) = measure(style).let { ceil(it.first).toInt() to ceil(it.second).toInt() }
        val r = Affine.clampRect(transform.bounds(lw.toDouble(), lh.toDouble()).let { doubleArrayOf(it[0] - 2, it[1] - 2, it[2] + 2, it[3] + 2) }, docWidth, docHeight)
        if (r.isEmpty) return out
        val surf = ImageSurface.create(Format.ARGB32, r.width, r.height)
        val cr = Context.create(surf)
        cr.translate(-r.left.toDouble(), -r.top.toDouble())
        Arena.ofConfined().use { arena ->
            cr.transform(Matrix.create(arena).init(transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty))
        }
        val c = style.color
        cr.setSourceRGBA(((c shr 16) and 0xFF) / 255.0, ((c shr 8) and 0xFF) / 255.0, (c and 0xFF) / 255.0, ((c ushr 24) and 0xFF) / 255.0)
        val l = layout(cr, style)
        PangoCairo.showLayout(cr, l)
        surf.flush()
        // Cairo: vormultipliziertes ARGB in nativer Bytefolge → gerades ARGB
        val stride = surf.stride
        val data = surf.data.reinterpret(stride.toLong() * r.height)
        val px = IntArray(r.width * r.height)
        for (y in 0 until r.height) for (x in 0 until r.width) {
            val v = data.get(ValueLayout.JAVA_INT, y.toLong() * stride + x * 4L)
            val a = v ushr 24
            px[y * r.width + x] = if (a == 0) 0 else if (a == 255) v else {
                val rr = minOf(255, ((v shr 16) and 0xFF) * 255 / a); val gg = minOf(255, ((v shr 8) and 0xFF) * 255 / a); val bb = minOf(255, (v and 0xFF) * 255 / a)
                (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        out.writeRect(r, px)
        out.compact()
        return out
    }
}
