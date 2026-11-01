package de.photographics.app

import de.photographics.core.tr
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.Compositor
import de.photographics.core.color.IccProfile
import de.photographics.core.color.IccProfiles
import de.photographics.core.color.RenderingIntent
import de.photographics.core.image.IntRect
import de.photographics.core.tiff.TiffReader
import de.photographics.core.tiff.TiffWriter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Laden und Speichern über das Storage Access Framework. */
object ImageIO {
    /** Größte Kantenlänge für importierte Rasterbilder (Speicherschutz). */
    private const val MAX_IMPORT_EDGE = 8192

    class Loaded(val doc: Document, val name: String, val isPsd: Boolean)

    fun displayName(cr: ContentResolver, uri: Uri): String {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: tr("Unbenannt")
    }

    /**
     * Stellt die Datei als speicherabgebildeten Puffer bereit – sie belegt so keinen Java-Heap.
     * Liefert der Anbieter keine echte Datei (z. B. Cloud), wird sie erst in [cache] kopiert.
     */
    fun mapFile(cr: ContentResolver, uri: Uri, cache: File): ByteBuffer {
        runCatching {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val size = ch.size()
                    if (size > 0) return ch.map(FileChannel.MapMode.READ_ONLY, 0, size)
                }
            }
        }
        val tmp = File(cache, "import.tmp")
        (cr.openInputStream(uri) ?: throw IOException(tr("Datei kann nicht geöffnet werden"))).use { inp ->
            tmp.outputStream().use { inp.copyTo(it, 1 shl 16) }
        }
        try {
            java.io.RandomAccessFile(tmp, "r").use { f -> return f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
        } finally {
            tmp.delete()   // Die Abbildung bleibt gültig, bis der Puffer freigegeben wird
        }
    }

    fun isPsd(buf: ByteBuffer) = buf.limit() >= 4 && buf.get(0) == '8'.code.toByte() && buf.get(1) == 'B'.code.toByte() &&
        buf.get(2) == 'P'.code.toByte() && buf.get(3) == 'S'.code.toByte()

    private class BufferInputStream(private val b: ByteBuffer) : InputStream() {
        override fun read(): Int = if (b.hasRemaining()) b.get().toInt() and 0xFF else -1
        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (!b.hasRemaining()) return -1
            val n = minOf(len, b.remaining()); b.get(dst, off, n); return n
        }
        override fun available() = b.remaining()
    }

    private fun stream(buf: ByteBuffer): InputStream = BufferInputStream(buf.duplicate().also { it.position(0) })

    /** Öffnet eine Datei; CMYK/Graustufen/Lab-PSDs werden in den Arbeitsfarbraum [workingRgb] umgewandelt. */
    fun open(cr: ContentResolver, uri: Uri, cache: File, workingRgb: IccProfile): Loaded {
        val buf = mapFile(cr, uri, cache)
        val name = displayName(cr, uri)
        if (isPsd(buf)) return Loaded(PsdReader.read(buf, workingRgb), name, true)
        if (TiffReader.isTiff(buf)) return Loaded(TiffReader.read(buf, workingRgb), name, true)
        if (de.photographics.core.xcf.XcfReader.isXcf(buf)) return Loaded(de.photographics.core.xcf.XcfReader.read(buf, workingRgb), name, true)
        val (grid, profile) = decodeRaster(buf)
        val doc = Document(grid.width, grid.height)
        doc.colorProfile = profile
        val layer = PixelLayer(tr("Hintergrund"), grid)
        doc.root.add(layer)
        doc.activeLayer = layer
        return Loaded(doc, name, false)
    }

    /** Liest eine Datei als Raster (PSD: zusammengeführt) samt Farbprofil – zum Platzieren als Ebene. */
    fun readAsGrid(cr: ContentResolver, uri: Uri, cache: File, workingRgb: IccProfile): Pair<TileGrid, IccProfile> {
        val buf = mapFile(cr, uri, cache)
        val d = when {
            isPsd(buf) -> PsdReader.read(buf, workingRgb)
            TiffReader.isTiff(buf) -> TiffReader.read(buf, workingRgb)
            de.photographics.core.xcf.XcfReader.isXcf(buf) -> de.photographics.core.xcf.XcfReader.read(buf, workingRgb)
            else -> return decodeRaster(buf)
        }
        val g = TileGrid(d.width, d.height)
        val band = maxOf(1, (1 shl 20) / d.width)
        var y = 0
        while (y < d.height) {
            val r = IntRect(0, y, d.width, minOf(d.height, y + band))
            g.writeRect(r, Compositor.renderArgb(d, r)); y = r.bottom
        }
        g.compact()
        return g to d.colorProfile
    }

    /**
     * Dekodiert PNG/JPEG/WebP/HEIF inkl. EXIF-Drehung direkt in ein Kachelraster. Die Bitmap liegt
     * im nativen Speicher; in den Java-Heap wird nur streifenweise kopiert.
     */
    fun decodeRaster(buf: ByteBuffer): Pair<TileGrid, IccProfile> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream(buf), null, bounds)
        if (bounds.outWidth <= 0) throw IOException(tr("Unbekanntes Bildformat"))
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMPORT_EDGE || bounds.outHeight / sample > MAX_IMPORT_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        var bmp = BitmapFactory.decodeStream(stream(buf), null, opts) ?: throw IOException(tr("Bild konnte nicht dekodiert werden"))
        val orientation = runCatching {
            ExifInterface(stream(buf)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            val c = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle(); bmp = c
        }
        // Farbprofil übernehmen: Bitmap nur als sRGB kennzeichnen (ohne Umrechnung), damit getPixels
        // die Originalwerte liefert statt sie in sRGB umzurechnen und zu beschneiden
        var profile = IccProfiles.sRGB
        val cs = bmp.colorSpace
        if (cs != null && !cs.isSrgb && android.os.Build.VERSION.SDK_INT >= 29) {
            val icc = ColorSpaces.toIcc(cs)
            if (icc != null && runCatching { bmp.setColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)) }.isSuccess) {
                profile = icc
            }
        }
        val w = bmp.width; val h = bmp.height
        val grid = TileGrid(w, h)
        val band = maxOf(1, minOf(h, (1 shl 20) / w))
        val px = IntArray(w * band)
        var y = 0
        while (y < h) {
            val n = minOf(band, h - y)
            bmp.getPixels(px, 0, w, 0, y, w, n)
            grid.writeRect(IntRect(0, y, w, y + n), if (n == band) px else px.copyOf(w * n))
            y += n
        }
        bmp.recycle()
        return grid to profile
    }

    /** Speichert gestreamt; große Kanaldaten werden in [tmpDir] zwischengelagert. */
    fun savePsd(cr: ContentResolver, uri: Uri, doc: Document, tmpDir: File) {
        tmpDir.mkdirs()
        (cr.openOutputStream(uri, "wt") ?: throw IOException(tr("Speichern nicht möglich"))).buffered(1 shl 16).use { out ->
            PsdWriter.write(doc, out, tmpDir)
        }
    }

    /** Als GIMP-Datei (XCF) speichern; liefert Hinweise auf nicht übertragbare Ebenen. */
    fun saveXcf(cr: ContentResolver, uri: Uri, doc: Document, tmpDir: File): List<String> {
        tmpDir.mkdirs()
        return (cr.openOutputStream(uri, "wt") ?: throw IOException(tr("Speichern nicht möglich"))).buffered(1 shl 16).use { out ->
            de.photographics.core.xcf.XcfWriter.write(doc, out, tmpDir)
        }
    }

    /** TIFF mit Ebenen (Photoshop-Format) gestreamt speichern; Zwischendaten in [tmpDir]. */
    fun saveTiff(cr: ContentResolver, uri: Uri, doc: Document, tmpDir: File, compression: TiffWriter.Compression, layers: Boolean) {
        tmpDir.mkdirs()
        (cr.openOutputStream(uri, "wt") ?: throw IOException(tr("Speichern nicht möglich"))).buffered(1 shl 16).use { out ->
            TiffWriter.write(doc, out, tmpDir, compression, layers)
        }
    }

    /**
     * Zusammengefügtes Bild direkt in eine (native) Bitmap rendern, streifenweise. Mit [toSrgb] wird
     * aus dem Dokumentprofil in sRGB umgerechnet (Web, Druck), sonst wird die Bitmap mit dem
     * Dokumentprofil gekennzeichnet (wird beim Export eingebettet).
     */
    fun renderBitmap(doc: Document, flattenOnWhite: Boolean, toSrgb: Boolean = true, width: Int = doc.width, height: Int = doc.height): Bitmap {
        if (width != doc.width || height != doc.height) return renderScaled(doc, flattenOnWhite, toSrgb, width, height)
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val tagAs = if (toSrgb || android.os.Build.VERSION.SDK_INT < 29) null else ColorSpaces.toAndroid(doc.colorProfile)
        val conv = if (tagAs == null) de.photographics.core.color.ColorTransform.rgb(doc.colorProfile, IccProfiles.sRGB, RenderingIntent.RELATIVE, true) else null
        val band = maxOf(1, (1 shl 20) / doc.width)
        var y = 0
        while (y < doc.height) {
            val r = IntRect(0, y, doc.width, minOf(doc.height, y + band))
            val px = Compositor.renderArgb(doc, r)
            if (flattenOnWhite) for (i in px.indices) px[i] = de.photographics.core.image.Colors.srcOver(0xFFFFFFFF.toInt(), px[i])
            if (conv != null) for (i in px.indices) { val c = px[i]; if ((c ushr 24) != 0) px[i] = (c and 0xFF000000.toInt()) or conv.map(c and 0xFFFFFF) }
            bmp.setPixels(px, 0, r.width, 0, r.top, r.width, r.height)
            y = r.bottom
        }
        // Erst nach dem Befüllen kennzeichnen: setPixels würde sonst aus sRGB umrechnen
        if (tagAs != null && android.os.Build.VERSION.SDK_INT >= 29) bmp.setColorSpace(tagAs)
        return bmp
    }

    /** Verkleinert (Lanczos im Kern) in eine Bitmap der Zielgröße, streifenweise. */
    private fun renderScaled(doc: Document, flattenOnWhite: Boolean, toSrgb: Boolean, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tagAs = if (toSrgb || android.os.Build.VERSION.SDK_INT < 29) null else ColorSpaces.toAndroid(doc.colorProfile)
        de.photographics.core.render.ExportRender.bands(doc, width, height, toSrgb = tagAs == null, onWhite = flattenOnWhite) { r, px ->
            bmp.setPixels(px, 0, r.width, 0, r.top, r.width, r.height)
        }
        if (tagAs != null && android.os.Build.VERSION.SDK_INT >= 29) bmp.setColorSpace(tagAs)
        return bmp
    }

    enum class ExportFormat(val mime: String, val ext: String) { PNG("image/png", "png"), JPEG("image/jpeg", "jpg") }

    /** [width]/[height]: Zielgröße (kleiner = verkleinern), [quality] nur für JPEG (1..100). */
    fun export(cr: ContentResolver, uri: Uri, doc: Document, format: ExportFormat, toSrgb: Boolean, quality: Int = 92, width: Int = doc.width, height: Int = doc.height) {
        val bmp = renderBitmap(doc, flattenOnWhite = format == ExportFormat.JPEG, toSrgb = toSrgb, width = width, height = height)
        try {
            cr.openOutputStream(uri, "wt")?.use { out ->
                val fmt = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (!bmp.compress(fmt, quality, out)) throw IOException(tr("Export fehlgeschlagen"))
            } ?: throw IOException(tr("Export nicht möglich"))
        } finally {
            bmp.recycle()
        }
    }
}
