package de.photographics.gnome

import de.photographics.core.image.IntRect
import de.photographics.core.render.Compositor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Öffnen und Speichern über die GNOME-Wege (GdkPixbuf für PNG/JPEG, Kern für Ebenenformate). */
class FileIOTest {
    private val fixture = File("../core/src/test/resources/tiff/layers-lsb.tif")
    private val dir = File(System.getProperty("java.io.tmpdir"), "pg-gnome-${System.nanoTime()}").apply { mkdirs() }

    @After fun clean() { dir.deleteRecursively() }

    private fun pixels(f: File): IntArray {
        val d = FileIO.open(f).doc
        return Compositor.renderArgb(d, IntRect.ofSize(d.width, d.height))
    }

    @Test
    fun layeredFormatsRoundTrip() {
        val src = FileIO.open(fixture)
        assertTrue(src.layered)
        val layers = src.doc.allLayers().map { it.name }
        val ref = pixels(fixture)
        for (fmt in listOf(FileIO.Format.PSD, FileIO.Format.TIFF, FileIO.Format.XCF)) {
            val out = File(dir, "test.${fmt.ext}")
            FileIO.save(src.doc, out, fmt)
            val back = FileIO.open(out)
            assertEquals("$fmt: Ebenen", layers, back.doc.allLayers().map { it.name })
            val px = pixels(out)
            val diff = px.indices.maxOf { i -> maxOf(0, (0..3).maxOf { c -> Math.abs(((px[i] shr (c * 8)) and 0xFF) - ((ref[i] shr (c * 8)) and 0xFF)) }) }
            assertTrue("$fmt: Abweichung $diff", diff <= 1)
            assertTrue("keine temporäre Datei", dir.listFiles()!!.none { it.name.startsWith(".") })
        }
    }

    @Test
    fun exportsAndReadsPngAndJpeg() {
        val src = FileIO.open(fixture).doc
        val ref = Compositor.renderArgb(src, src.bounds)
        val png = File(dir, "test.png")
        FileIO.save(src, png, FileIO.Format.PNG)
        val back = FileIO.open(png)
        assertEquals(false, back.layered)
        assertEquals(src.width, back.doc.width)
        assertTrue("PNG verlustfrei", ref.contentEquals(pixels(png)))

        val jpg = File(dir, "test.jpg")
        FileIO.save(src, jpg, FileIO.Format.JPEG)
        val j = pixels(jpg)
        assertEquals(ref.size, j.size)
        // JPEG auf Weiß: nur grob vergleichbar, aber vollständig deckend
        assertTrue(j.all { (it ushr 24) == 0xFF })
    }
}
