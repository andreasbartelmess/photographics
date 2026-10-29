package de.photographics.app

import de.photographics.core.tr
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import de.photographics.core.doc.TextAlign
import de.photographics.core.doc.TextRasterizer
import de.photographics.core.doc.TextStyle
import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import kotlin.math.ceil
import kotlin.math.max

/** Rastert Textebenen mit dem Android-Textsystem – direkt in der Zieltransformation (gestochen scharf). */
object AndroidTextRasterizer : TextRasterizer {

    /** Verfügbare Schriftfamilien (Android-Systemschriften). */
    val families = listOf(
        "sans-serif" to tr("Sans Serif"), "sans-serif-medium" to tr("Sans Serif Medium"), "sans-serif-light" to tr("Sans Serif Light"),
        "sans-serif-black" to tr("Sans Serif Black"), "sans-serif-condensed" to tr("Sans Serif Condensed"), "serif" to tr("Serif"),
        "monospace" to tr("Monospace"), "cursive" to tr("Schreibschrift"), "casual" to tr("Casual"), "sans-serif-smallcaps" to tr("Kapitälchen"),
    )

    private fun paint(s: TextStyle) = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        val st = when {
            s.bold && s.italic -> Typeface.BOLD_ITALIC
            s.bold -> Typeface.BOLD
            s.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        typeface = Typeface.create(s.fontFamily, st)
        textSize = s.sizePx
        color = s.color
        letterSpacing = s.letterSpacing
    }

    private fun layout(s: TextStyle): StaticLayout {
        val p = paint(s)
        val text = s.text.ifEmpty { " " }
        val width = max(1, ceil(text.lines().maxOf { p.measureText(it) }).toInt() + 2)
        val align = when (s.align) {
            TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(align)
            .setLineSpacing(0f, s.lineHeight / 1.17f)
            .setIncludePad(false)
            .build()
    }

    override fun measure(style: TextStyle): Pair<Float, Float> {
        val l = layout(style)
        return l.width.toFloat() to l.height.toFloat()
    }

    override fun render(style: TextStyle, transform: Affine, docWidth: Int, docHeight: Int): TileGrid {
        val out = TileGrid(docWidth, docHeight)
        val l = layout(style)
        val r: IntRect = Affine.clampRect(transform.bounds(l.width.toDouble(), l.height.toDouble()).let {
            doubleArrayOf(it[0] - 2, it[1] - 2, it[2] + 2, it[3] + 2)
        }, docWidth, docHeight)
        if (r.isEmpty) return out
        val bmp = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.translate(-r.left.toFloat(), -r.top.toFloat())
            c.concat(Matrix().apply {
                setValues(floatArrayOf(
                    transform.a.toFloat(), transform.c.toFloat(), transform.tx.toFloat(),
                    transform.b.toFloat(), transform.d.toFloat(), transform.ty.toFloat(),
                    0f, 0f, 1f,
                ))
            })
            l.draw(c)
            val px = IntArray(r.width * r.height)
            bmp.getPixels(px, 0, r.width, 0, 0, r.width, r.height)
            out.writeRect(r, px)
            out.compact()
        } finally {
            bmp.recycle()
        }
        return out
    }
}
