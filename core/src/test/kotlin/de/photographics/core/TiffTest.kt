package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.image.Colors
import de.photographics.core.render.Compositor
import de.photographics.core.tiff.TiffReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/** TIFF-Varianten aus tools/make_tiff_fixtures.sh, verglichen mit der Dekodierung durch libtiff/ImageMagick. */
class TiffTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/tiff/$name")!!.readBytes()
    private fun open(name: String, layers: Boolean = true) = TiffReader.read(ByteBuffer.wrap(res(name)), layers = layers)
    private fun render(doc: Document) = Compositor.renderArgb(doc, doc.bounds)

    /** Abweichung auf Weiß (max, Mittel) und größte Alpha-Abweichung gegenüber der Referenz-PNG. */
    private fun compare(doc: Document, ref: String): Triple<Int, Double, Int> {
        val img = ImageIO.read(javaClass.getResourceAsStream("/tiff/$ref"))
        assertEquals(img.width, doc.width); assertEquals(img.height, doc.height)
        val px = render(doc)
        var worst = 0; var sum = 0L; var alpha = 0
        for (y in 0 until doc.height) for (x in 0 until doc.width) {
            val a = px[y * doc.width + x]; val b = img.getRGB(x, y)
            alpha = max(alpha, abs((a ushr 24) - (b ushr 24)))
            val fa = Colors.srcOver(-1, a); val fb = Colors.srcOver(-1, b)
            for (s in intArrayOf(16, 8, 0)) { val d = abs(((fa shr s) and 0xFF) - ((fb shr s) and 0xFF)); worst = max(worst, d); sum += d }
        }
        return Triple(worst, sum.toDouble() / (doc.width * doc.height * 3), alpha)
    }

    @Test
    fun variantsMatchLibtiff() {
        val tolerance = mapOf("lab8" to 2, "cmyk8-icc" to 4, "float32-pred" to 2, "rgba8-assoc" to 3)
        val names = listOf(
            "rgb8-none-mm", "rgb8-lzw-ii", "rgb8-lzw-pred", "rgb8-zip-tiled", "rgb16-zip-pred", "rgb16-rle-planar-mm",
            "rgba8-unassoc", "rgba8-assoc", "gray8", "gray16-mm", "gray1-white", "palette8", "palette4", "lab8",
            "float32-pred", "cmyk8-icc",
        )
        val report = StringBuilder()
        var failed = false
        for (n in names) {
            val doc = open("$n.tif")
            val (worst, mean, alpha) = compare(doc, "$n.ref.png")
            val ok = worst <= (tolerance[n] ?: 1) && alpha <= 1 && mean < 0.6
            report.append("%-22s max %2d  Ø %.3f  Alpha %d %s\n".format(n, worst, mean, alpha, if (ok) "" else "✗"))
            if (!ok) failed = true
        }
        println(report)
        assertTrue(report.toString(), !failed)
    }

    @Test
    fun cmykUsesEmbeddedProfile() {
        val doc = open("cmyk8-icc.tif")
        assertTrue(doc.importWarnings.joinToString(), doc.importWarnings.any { "eingebetteten Profil" in it })
    }

    /** Photoshop-Ebenen in TIFF (Tag 37724), Big- und Little-Endian: gleiche Ebenen, gleiches Bild. */
    @Test
    fun photoshopLayersInBothByteOrders() {
        val mm = open("layers-msb.tif"); val ii = open("layers-lsb.tif")
        for (doc in listOf(mm, ii)) {
            val layers = doc.allLayers()
            assertEquals(listOf("Hintergrund", "Rot 70%", "Grün weich"), layers.map { it.name })
            val red = layers[1] as de.photographics.core.doc.PixelLayer
            assertEquals(de.photographics.core.image.IntRect(10, 12, 60, 52), red.pixels.contentBounds())
            val (worst, mean, _) = compare(doc, "layers.ref.png")
            assertTrue("max $worst Ø $mean", worst <= 2 && mean < 0.5)
        }
        assertTrue(render(mm).contentEquals(render(ii)))
        // Nur das zusammengefügte Bild
        assertEquals(1, open("layers-msb.tif", layers = false).allLayers().size)
    }

    /** Schreiben und wieder lesen: Ebenen, Gesamtbild, Transparenz, Profil, Auflösung – alle Kompressionen. */
    @Test
    fun writeAndReadBack() {
        val src = open("layers-msb.tif")
        src.colorProfile = de.photographics.core.color.IccProfiles.Builtin.ADOBE_RGB.profile
        src.dpi = 300f
        val tmp = java.nio.file.Files.createTempDirectory("tiff").toFile()
        try {
            for (c in de.photographics.core.tiff.TiffWriter.Compression.entries) {
                val out = java.io.ByteArrayOutputStream()
                de.photographics.core.tiff.TiffWriter.write(src, out, tmp, c)
                val back = TiffReader.read(ByteBuffer.wrap(out.toByteArray()))
                assertEquals(c.name, src.allLayers().map { it.name }, back.allLayers().map { it.name })
                assertTrue(c.name, render(src).contentEquals(render(back)))
                assertEquals("Adobe RGB (1998)", back.colorProfile.description)
                assertEquals(300f, back.dpi, 0.01f)
                // Gesamtbild (für Programme ohne Ebenen-Unterstützung) = Ebenen
                assertTrue(c.name, render(src).contentEquals(render(TiffReader.read(ByteBuffer.wrap(out.toByteArray()), layers = false))))
                java.io.File(tmp, "out-${c.name}.tif").writeBytes(out.toByteArray())
            }
            println("Testdateien: $tmp")
        } finally {
            if (System.getenv("KEEP_TIFF") == null) tmp.deleteRecursively()
        }
    }

    /** Größere Fläche: LZW-Kodierer über viele Tabellen-Neustarts und mehrere Streifen. */
    @Test
    fun lzwLargeImage() {
        val w = 1500; val h = 900
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or ((x * 7 xor y * 3) and 0xFF shl 16) or ((x + y) and 0xFF shl 8) or ((x * y) and 0xFF) }
        val doc = Document(w, h).also { d -> d.root.add(de.photographics.core.doc.PixelLayer("Bild", de.photographics.core.image.TileGrid.fromPixels(w, h, px))) }
        val tmp = java.nio.file.Files.createTempDirectory("tiff").toFile()
        try {
            val out = java.io.ByteArrayOutputStream()
            de.photographics.core.tiff.TiffWriter.write(doc, out, tmp, de.photographics.core.tiff.TiffWriter.Compression.LZW, layers = false)
            val back = TiffReader.read(ByteBuffer.wrap(out.toByteArray()))
            assertTrue(render(back).contentEquals(px))
        } finally { tmp.deleteRecursively() }
    }
}
