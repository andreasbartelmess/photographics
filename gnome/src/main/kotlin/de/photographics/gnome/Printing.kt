package de.photographics.gnome

import de.photographics.core.color.ColorTransform
import de.photographics.core.color.IccProfiles
import de.photographics.core.color.RenderingIntent
import de.photographics.core.doc.Document
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.render.Compositor
import de.photographics.core.tr
import org.freedesktop.cairo.Format
import org.freedesktop.cairo.ImageSurface
import org.gnome.gtk.PrintOperation
import org.gnome.gtk.PrintOperationAction
import java.lang.foreign.ValueLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Drucken über den GNOME-Druckdialog: zusammengefügtes Bild (sRGB, auf Weiß), auf die Seite eingepasst. */
object Printing {
    /** Höchstens so viele Pixel an der langen Kante (reicht für 300 dpi auf A4). */
    private const val MAX_EDGE = 4000

    /** Rendert auf dem Engine-Thread (verkleinert, sRGB, auf Weiß); Ergebnis: ARGB, Breite, Höhe. */
    class Image(val px: IntArray, val w: Int, val h: Int, val step: Int)

    fun render(doc: Document): Image {
        val f = min(1.0, MAX_EDGE.toDouble() / max(doc.width, doc.height))
        var step = 1
        while (doc.width / (step * 2) >= doc.width * f && step < 64) step *= 2
        val w = (doc.width + step - 1) / step; val h = (doc.height + step - 1) / step
        Compositor.prepare(doc, step)
        val px = Compositor.renderArgbSampled(doc, IntRect(0, 0, w, h), step)
        val conv = if (doc.colorProfile.bytes.contentEquals(IccProfiles.sRGB.bytes)) null
            else ColorTransform.rgb(doc.colorProfile, IccProfiles.sRGB, RenderingIntent.RELATIVE, true)
        for (i in px.indices) {
            var c = Colors.srcOver(0xFFFFFFFF.toInt(), px[i])
            if (conv != null) c = (c and 0xFF000000.toInt()) or conv.map(c and 0xFFFFFF)
            px[i] = c
        }
        return Image(px, w, h, step)
    }

    /** [pdf]: statt des Dialogs direkt in diese PDF-Datei drucken (für Tests). */
    fun print(window: org.gnome.gtk.Window, name: String, img: Image, dpi: Float, pdf: java.io.File? = null) {
        val px = img.px; val w = img.w; val h = img.h
        val surf = ImageSurface.create(Format.ARGB32, w, h)
        val stride = surf.stride
        val data = surf.data.reinterpret(stride.toLong() * h)
        for (y in 0 until h) for (x in 0 until w) data.set(ValueLayout.JAVA_INT, y.toLong() * stride + x * 4L, px[y * w + x])
        surf.markDirty()
        val op = PrintOperation()
        op.setJobName(name)
        op.setNPages(1)
        op.onDrawPage { ctx0, _ ->
            guarded {
                val ctx = ctx0 ?: return@guarded
                val cr = ctx.cairoContext
                // Bildgröße aus der Dokumentauflösung, höchstens die Seite; mittig
                val pageW = ctx.width; val pageH = ctx.height
                // Natürliche Größe: ein Dokumentpixel = 1/dpi Zoll (gerenderte Pixel umfassen [step] Dokumentpixel)
                val natural = ctx.dpiX * img.step / dpi.coerceAtLeast(1f)
                val s = min(min(pageW / w, pageH / h), natural)
                cr.save()
                cr.translate((pageW - w * s) / 2, (pageH - h * s) / 2)
                cr.scale(s, s)
                cr.setSource(surf, 0.0, 0.0)
                cr.paint()
                cr.restore()
            }
        }
        if (pdf != null) op.setExportFilename(pdf.path)
        runCatching { op.run(if (pdf != null) PrintOperationAction.EXPORT else PrintOperationAction.PRINT_DIALOG, window) }
            .onFailure { e -> System.err.println(tr("Drucken fehlgeschlagen") + ": " + e.message) }
    }
}
