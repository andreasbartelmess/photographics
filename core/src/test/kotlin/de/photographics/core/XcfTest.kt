package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.IntRect
import de.photographics.core.render.BlendMode
import de.photographics.core.xcf.XcfReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.abs

/** XCF-Dateien aus GIMP 3 (tools/make_xcf_fixtures.py), verglichen mit GIMPs eigenem Export jeder Ebene. */
class XcfTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/xcf/$name")
    private fun open(name: String) = XcfReader.read(ByteBuffer.wrap(res("$name.xcf")!!.readBytes()))

    /** Ebenen in Dateireihenfolge (oben zuerst, Gruppen vor ihren Kindern) – wie GIMPs Nummerierung. */
    private fun fileOrder(doc: Document): List<Layer> {
        val out = ArrayList<Layer>()
        fun walk(g: GroupLayer) { for (l in g.children.asReversed()) { out += l; if (l is GroupLayer) walk(l) } }
        walk(doc.root); return out
    }

    /** Größte Abweichung der Ebene (bzw. Maske) zum GIMP-Export, nur wo GIMP deckende Pixel hat. */
    private fun compareLayer(doc: Document, name: String, index: Int, layer: Layer, mask: Boolean): Int {
        val img = ImageIO.read(res("$name.${if (mask) "mask" else "layer"}$index.png") ?: return -1)
        val w = doc.width; val h = doc.height
        val px = IntArray(w * h)
        if (mask) layer.mask!!.grid.readRect(IntRect.ofSize(w, h), px) else (layer as PixelLayer).pixels.readRect(IntRect.ofSize(w, h), px)
        var worst = 0
        for (y in 0 until h) for (x in 0 until w) {
            val ref = img.getRGB(x, y)
            val refA = ref ushr 24
            val c = px[y * w + x]
            if (mask) {
                if (refA != 255) continue
                worst = maxOf(worst, abs((ref and 0xFF) - c))
            } else {
                worst = maxOf(worst, abs(refA - (c ushr 24)))
                if (refA < 8) continue
                for (s in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs(((ref shr s) and 0xFF) - ((c shr s) and 0xFF)))
            }
        }
        return worst
    }

    @Test
    fun structureMatchesGimp() {
        val doc = open("rgb8")
        assertEquals(listOf("Hintergrund", "Rot Multiplizieren", "Gruppe"), doc.root.children.map { it.name })
        val group = doc.root.children[2] as GroupLayer
        assertEquals(listOf("Blau unsichtbar", "Grün"), group.children.map { it.name })
        val red = doc.root.children[1] as PixelLayer
        assertEquals(BlendMode.MULTIPLY, red.blendMode); assertEquals(0.6f, red.opacity, 0.01f)
        // Ebene ragt links über die Arbeitsfläche hinaus (Versatz −15)
        assertTrue(red.pixels.extent.left < 0)
        val green = group.children[1]
        assertEquals(BlendMode.SCREEN, green.blendMode); assertTrue(green.mask != null)
        assertEquals(false, group.children[0].visible)
        assertEquals(setOf(true to 40.0, false to 90.0), doc.guides.map { it.vertical to it.position }.toSet())
        assertEquals(300f, doc.dpi, 0.01f)
    }

    @Test
    fun pixelsAndMasksMatchGimpForAllPrecisions() {
        val report = StringBuilder(); var failed = false
        for ((name, tol) in listOf("rgb8" to 1, "gray8" to 1, "indexed" to 1, "rgb16lin" to 2, "float32" to 2)) {
            val doc = open(name)
            for ((i, l) in fileOrder(doc).withIndex()) {
                if (l is GroupLayer) continue
                val d = compareLayer(doc, name, i, l, mask = false)
                val m = if (l.mask != null) compareLayer(doc, name, i, l, mask = true) else 0
                report.append("%-9s %-20s Pixel %3d  Maske %3d\n".format(name, l.name, d, m))
                if (d > tol || m > tol) failed = true
            }
        }
        println(report)
        assertTrue(report.toString(), !failed)
    }

    /** Schreiben und wieder lesen: Struktur, Pixel, Masken, Hilfslinien, Auflösung, Farbprofil. */
    @Test
    fun writeAndReadBack() {
        val src = open("rgb8")
        src.colorProfile = de.photographics.core.color.IccProfiles.Builtin.DISPLAY_P3.profile
        val tmp = java.nio.file.Files.createTempDirectory("xcf").toFile()
        try {
            val out = java.io.ByteArrayOutputStream()
            val warnings = de.photographics.core.xcf.XcfWriter.write(src, out, tmp)
            assertTrue(warnings.joinToString(), warnings.isEmpty())
            System.getenv("XCF_OUT")?.let { java.io.File(it).writeBytes(out.toByteArray()) }
            val back = XcfReader.read(ByteBuffer.wrap(out.toByteArray()))
            assertEquals(fileOrder(src).map { it.name }, fileOrder(back).map { it.name })
            assertEquals(fileOrder(src).map { it.blendMode to it.visible }, fileOrder(back).map { it.blendMode to it.visible })
            assertEquals(src.guides.toSet(), back.guides.toSet())
            assertEquals("Display P3", back.colorProfile.description)
            val a = de.photographics.core.render.Compositor.renderArgb(src, src.bounds)
            val b = de.photographics.core.render.Compositor.renderArgb(back, back.bounds)
            var worst = 0
            for (k in a.indices) for (sh in intArrayOf(24, 16, 8, 0)) worst = maxOf(worst, abs(((a[k] shr sh) and 0xFF) - ((b[k] shr sh) and 0xFF)))
            assertTrue("max $worst", worst <= 1)
        } finally { tmp.deleteRecursively() }
    }

    /**
     * Von uns geschriebenes XCF (aus rgb8.xcf) hat GIMP 3 zusammengefügt (written.gimpflat.png):
     * mit „wahrnehmungsgleichem“ Mischen stimmt GIMPs Ergebnis mit unserem Compositor überein.
     */
    @Test
    fun gimpRendersOurXcfLikeWeDo() {
        val tmp = java.nio.file.Files.createTempDirectory("xcf").toFile()
        try {
            val out = java.io.ByteArrayOutputStream()
            de.photographics.core.xcf.XcfWriter.write(open("rgb8"), out, tmp)
            val doc = XcfReader.read(ByteBuffer.wrap(out.toByteArray()))
            val ours = de.photographics.core.render.Compositor.renderArgb(doc, doc.bounds)
            val gimp = ImageIO.read(res("written.gimpflat.png"))
            var worst = 0
            for (y in 0 until doc.height) for (x in 0 until doc.width) for (sh in intArrayOf(16, 8, 0))
                worst = maxOf(worst, abs(((ours[y * doc.width + x] shr sh) and 0xFF) - ((gimp.getRGB(x, y) shr sh) and 0xFF)))
            assertTrue("max $worst", worst <= 1)
        } finally { tmp.deleteRecursively() }
    }
}
