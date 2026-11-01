package de.photographics.gnome

import de.photographics.core.color.ColorTransform
import de.photographics.core.color.IccProfile
import de.photographics.core.color.IccProfiles
import de.photographics.core.color.RenderingIntent
import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.Compositor
import de.photographics.core.tiff.TiffReader
import de.photographics.core.tiff.TiffWriter
import de.photographics.core.tr
import de.photographics.core.xcf.XcfReader
import de.photographics.core.xcf.XcfWriter
import org.gnome.gdkpixbuf.Colorspace
import org.gnome.gdkpixbuf.Pixbuf
import java.io.File
import java.io.IOException
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Base64

/** Lesen und Schreiben von Dateien; läuft auf dem Engine-Thread. */
object FileIO {
    /** Ebenen-Formate des Kerns; alles andere liest GdkPixbuf (PNG, JPEG, WebP, …). */
    enum class Format(val ext: String, val layered: Boolean) {
        PSD("psd", true), TIFF("tif", true), XCF("xcf", true), PNG("png", false), JPEG("jpg", false);

        companion object {
            fun of(f: File): Format? = when (f.extension.lowercase()) {
                "psd", "psb" -> PSD
                "tif", "tiff" -> TIFF
                "xcf" -> XCF
                "png" -> PNG
                "jpg", "jpeg" -> JPEG
                else -> null
            }
        }
    }

    class Loaded(val doc: Document, val layered: Boolean)

    val cacheDir: File by lazy {
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.cache")
        File(base, "photographics").apply { mkdirs() }
    }

    private fun map(f: File): ByteBuffer = FileChannel.open(f.toPath(), StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

    private fun isPsd(b: ByteBuffer) = b.limit() >= 4 && b.get(0) == '8'.code.toByte() && b.get(1) == 'B'.code.toByte() &&
        b.get(2) == 'P'.code.toByte() && b.get(3) == 'S'.code.toByte()

    fun open(f: File, workingRgb: IccProfile = IccProfiles.sRGB): Loaded {
        val buf = map(f)
        if (isPsd(buf)) return Loaded(PsdReader.read(buf, workingRgb), true)
        if (TiffReader.isTiff(buf)) return Loaded(TiffReader.read(buf, workingRgb), true)
        if (XcfReader.isXcf(buf)) return Loaded(XcfReader.read(buf, workingRgb), true)
        val (grid, profile) = decodeRaster(f)
        val doc = Document(grid.width, grid.height)
        doc.colorProfile = profile
        val layer = PixelLayer(tr("Hintergrund"), grid)
        doc.root.add(layer)
        doc.activeLayer = layer
        return Loaded(doc, false)
    }

    /** Dekodiert mit GdkPixbuf (inkl. EXIF-Drehung); kopiert streifenweise aus dem nativen Speicher ins Kachelraster. */
    private fun decodeRaster(f: File): Pair<TileGrid, IccProfile> {
        val pb = try { Pixbuf.fromFile(f.path) } catch (e: Exception) { throw IOException(tr("Unbekanntes Bildformat"), e) }
            ?.applyEmbeddedOrientation() ?: throw IOException(tr("Bild konnte nicht dekodiert werden"))
        val profile = pb.getOption("icc-profile")?.let { runCatching { IccProfile(Base64.getDecoder().decode(it)) }.getOrNull() } ?: IccProfiles.sRGB
        val w = pb.width; val h = pb.height
        val n = pb.nChannels; val stride = pb.rowstride
        val seg = pb.readPixels().reinterpret(stride.toLong() * (h - 1) + w.toLong() * n)
        val grid = TileGrid(w, h)
        val band = maxOf(1, minOf(h, (1 shl 20) / w))
        val px = IntArray(w * band)
        val row = ByteArray(w * n)
        var y = 0
        while (y < h) {
            val rows = minOf(band, h - y)
            for (r in 0 until rows) {
                java.lang.foreign.MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, (y + r).toLong() * stride, row, 0, w * n)
                val o = r * w
                if (n == 4) for (x in 0 until w) {
                    val i = x * 4
                    px[o + x] = ((row[i + 3].toInt() and 0xFF) shl 24) or ((row[i].toInt() and 0xFF) shl 16) or ((row[i + 1].toInt() and 0xFF) shl 8) or (row[i + 2].toInt() and 0xFF)
                } else for (x in 0 until w) {
                    val i = x * n
                    px[o + x] = (0xFF shl 24) or ((row[i].toInt() and 0xFF) shl 16) or ((row[i + 1].toInt() and 0xFF) shl 8) or (row[i + 2].toInt() and 0xFF)
                }
            }
            grid.writeRect(IntRect(0, y, w, y + rows), if (rows == band) px else px.copyOf(w * rows))
            y += rows
        }
        grid.compact()
        return grid to profile
    }

    /** Speichert über eine temporäre Datei, damit ein Fehler die alte Fassung nicht zerstört. */
    fun save(doc: Document, f: File, format: Format) {
        val tmp = File(f.parentFile, ".${f.name}.speichern")
        val spool = File(cacheDir, "spool").apply { mkdirs() }
        try {
            when (format) {
                Format.PSD -> tmp.outputStream().buffered(1 shl 16).use { PsdWriter.write(doc, it, spool) }
                Format.TIFF -> tmp.outputStream().buffered(1 shl 16).use { TiffWriter.write(doc, it, spool, Settings.tiffCompression) }
                Format.XCF -> tmp.outputStream().buffered(1 shl 16).use { XcfWriter.write(doc, it, spool) }
                Format.PNG, Format.JPEG -> { export(doc, f, format); return }
            }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }

    /** Exportoptionen (Photoshop „Exportieren als“): Größe in Pixeln, JPEG-Qualität 1..100, sRGB-Umwandlung. */
    data class ExportOptions(val width: Int, val height: Int, val quality: Int = 90, val toSrgb: Boolean = true)

    /** Zusammengefügtes Bild als PNG/JPEG (JPEG auf Weiß), auf Wunsch verkleinert. */
    fun export(doc: Document, f: File, format: Format, o: ExportOptions = ExportOptions(doc.width, doc.height, Settings.jpegQuality, Settings.exportToSrgb)) {
        val tmp = File(f.parentFile, ".${f.name}.speichern")
        try {
            val w = o.width; val h = o.height
            val alpha = format == Format.PNG
            val n = if (alpha) 4 else 3
            val bytes = ByteArray(w * h * n)
            de.photographics.core.render.ExportRender.bands(doc, w, h, o.toSrgb, onWhite = !alpha) { r, px ->
                var i = r.top * w * n
                for (c in px) {
                    bytes[i] = (c shr 16).toByte(); bytes[i + 1] = (c shr 8).toByte(); bytes[i + 2] = c.toByte()
                    if (alpha) bytes[i + 3] = (c ushr 24).toByte()
                    i += n
                }
            }
            val pb = Pixbuf.fromBytes(bytes, Colorspace.RGB, alpha, 8, w, h, w * n)
            val icc = Base64.getEncoder().encodeToString(if (o.toSrgb) IccProfiles.sRGB.bytes else doc.colorProfile.bytes)
            if (format == Format.PNG) pb.savev(tmp.path, "png", arrayOf("icc-profile"), arrayOf(icc))
            else pb.savev(tmp.path, "jpeg", arrayOf("quality", "icc-profile"), arrayOf(o.quality.coerceIn(1, 100).toString(), icc))
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }
}

/** Lokaler Pfad einer GIO-Datei (null bei Dateien ohne Pfad, z. B. im Netz). */
fun org.gnome.gio.File.local(): File? = path?.let { File(it.toString()) }
