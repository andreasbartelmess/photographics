package de.photographics.core

import de.photographics.core.color.IccProfiles
import de.photographics.core.color.RenderingIntent
import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.abs

class DocumentProfileTest {
    private fun pixels(g: TileGrid, w: Int, h: Int) = IntArray(w * h).also { g.readRect(IntRect.ofSize(w, h), it) }

    @Test
    fun psdKeepsDocumentProfile() {
        val doc = Document.blank(40, 30)
        doc.colorProfile = IccProfiles.Builtin.ADOBE_RGB.profile
        val back = PsdReader.read(PsdWriter.write(doc))
        assertEquals("Adobe RGB (1998)", back.colorProfile.description)
        assertArrayEquals(doc.colorProfile.bytes, back.colorProfile.bytes)
    }

    /** CMYK-PSD mit eingebettetem Profil: Umrechnung wie LittleCMS (ImageMagick-Referenz). */
    @Test
    fun cmykPsdIsConvertedWithEmbeddedProfile() {
        val doc = PsdReader.read(javaClass.getResourceAsStream("/psd/cmyk-icc.psd")!!.readBytes())
        assertTrue(doc.importWarnings.any { "eingebetteten Profil" in it })
        val ref = ImageIO.read(javaClass.getResourceAsStream("/psd/cmyk-icc-ref.png"))
        val px = pixels(doc.pixelLayers().first().pixels, doc.width, doc.height)
        var worst = 0; var sum = 0L
        for (y in 0 until doc.height) for (x in 0 until doc.width) {
            val a = px[y * doc.width + x]; val b = ref.getRGB(x, y)
            for (s in intArrayOf(16, 8, 0)) { val d = abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)); worst = maxOf(worst, d); sum += d }
        }
        val mean = sum.toDouble() / (doc.width * doc.height * 3)
        assertTrue("max $worst, Ø $mean", worst <= 4 && mean < 0.6)
    }

    @Test
    fun convertToProfileAndBack() {
        val w = 64; val h = 48
        val doc = Document(w, h)
        val px0 = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 37) and 0xFF shl 16) or ((i / w * 5) and 0xFF shl 8) or ((i % w * 4) and 0xFF) }
        val layer = PixelLayer("Bild", TileGrid.fromPixels(w, h, px0))
        doc.root.add(layer); doc.activeLayer = layer
        val ed = Editor(doc)
        ed.convertToProfile(IccProfiles.Builtin.ADOBE_RGB.profile, RenderingIntent.RELATIVE, true)
        assertEquals("Adobe RGB (1998)", doc.colorProfile.description)
        val inAdobe = pixels(layer.pixels, w, h)
        assertTrue(!inAdobe.contentEquals(px0))
        // Schnellweg (Matrix-Shaper) = exakte Rechnung der Engine
        val exact = de.photographics.core.color.ColorTransform(IccProfiles.sRGB, IccProfiles.Builtin.ADOBE_RGB.profile, RenderingIntent.RELATIVE, true)
        for (i in px0.indices step 7) {
            val c = px0[i]; val e = exact.map8((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF); val f = inAdobe[i]
            for ((k, s) in intArrayOf(16, 8, 0).withIndex()) assertTrue(abs(((f shr s) and 0xFF) - e[k]) <= 1)
        }
        // Rückweg: bei nicht extremen Farben fast verlustfrei (gesättigte Ränder verlieren durch 8 Bit, wie in Photoshop)
        ed.convertToProfile(IccProfiles.sRGB, RenderingIntent.RELATIVE, true)
        val back = pixels(layer.pixels, w, h)
        var worst = 0
        for (i in back.indices) {
            val c = px0[i]
            val chroma = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) - minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            val minCh = minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            if (chroma > 160 || minCh < 40) continue // nahe Null verstärkt die sRGB-Kurve die 8-Bit-Rundung
            for (s in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs(((back[i] shr s) and 0xFF) - ((c shr s) and 0xFF)))
        }
        assertTrue("Rundlauf weicht um $worst ab", worst <= 3)
        // Rückgängig stellt exakt wieder her
        ed.undo(); ed.undo()
        assertArrayEquals(px0, pixels(layer.pixels, w, h))
        assertEquals(IccProfiles.sRGB.description, doc.colorProfile.description)
        // Zuweisen ändert nur das Profil
        ed.assignProfile(IccProfiles.Builtin.DISPLAY_P3.profile)
        assertArrayEquals(px0, pixels(layer.pixels, w, h))
        assertEquals("Display P3", doc.colorProfile.description)
    }
}
