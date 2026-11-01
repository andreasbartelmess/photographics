package de.photographics.core.render

import de.photographics.core.color.ColorTransform
import de.photographics.core.color.IccProfiles
import de.photographics.core.color.RenderingIntent
import de.photographics.core.doc.Document
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.image.Resample
import de.photographics.core.image.ResampleMethod

/**
 * Zusammengefügtes Bild für den Export (PNG/JPEG) in Zielgröße [width]×[height], streifenweise:
 * kleiner als das Dokument wird mit Lanczos verkleinert. Mit [toSrgb] aus dem Dokumentprofil in
 * sRGB umgerechnet, mit [onWhite] auf Weiß reduziert (JPEG kennt keine Transparenz).
 */
object ExportRender {
    fun bands(doc: Document, width: Int, height: Int, toSrgb: Boolean, onWhite: Boolean, consume: (IntRect, IntArray) -> Unit) {
        val conv = if (!toSrgb || doc.colorProfile.bytes.contentEquals(IccProfiles.sRGB.bytes)) null
            else ColorTransform.rgb(doc.colorProfile, IccProfiles.sRGB, RenderingIntent.RELATIVE, true)
        fun post(px: IntArray) {
            for (i in px.indices) {
                var c = px[i]
                if (onWhite) c = Colors.srcOver(0xFFFFFFFF.toInt(), c)
                if (conv != null && (c ushr 24) != 0) c = (c and 0xFF000000.toInt()) or conv.map(c and 0xFFFFFF)
                px[i] = c
            }
        }
        if (width == doc.width && height == doc.height) {
            Compositor.renderBands(doc, doc.bounds) { r, px -> post(px); consume(r, px) }
            return
        }
        // Kleiner (oder größer): erst zusammenfügen, dann neu berechnen – die Kacheln liegen außerhalb des Heaps
        val scaled = Resample.resizeGrid(Compositor.renderGrid(doc), width, height, if (width < doc.width) ResampleMethod.LANCZOS else ResampleMethod.BICUBIC)
        val band = maxOf(1, (1 shl 20) / width)
        var y = 0
        while (y < height) {
            val r = IntRect(0, y, width, minOf(height, y + band))
            val px = IntArray(r.width * r.height)
            scaled.readRect(r, px)
            post(px)
            consume(r, px)
            y = r.bottom
        }
    }
}
