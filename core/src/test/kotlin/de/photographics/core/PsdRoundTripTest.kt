package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PackBits
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class PsdRoundTripTest {

    @Test
    fun packBitsRoundTrip() {
        val rnd = Random(1)
        repeat(50) {
            val n = rnd.nextInt(1, 2000)
            val src = ByteArray(n) { i -> if (rnd.nextInt(4) == 0) rnd.nextInt().toByte() else (i / 37).toByte() }
            val out = ByteArrayOutputStream()
            PackBits.encode(src, 0, n, out)
            val enc = out.toByteArray()
            val dec = ByteArray(n)
            PackBits.decode(enc, 0, enc.size, dec, 0, n)
            assertArrayEquals(src, dec)
        }
    }

    @Test
    fun tileGridReadWrite() {
        val g = TileGrid(600, 300)
        val r = IntRect(200, 100, 520, 290)
        val data = IntArray(r.width * r.height) { it * 7 + 1 }
        g.writeRect(r, data)
        val back = IntArray(data.size)
        g.readRect(r, back)
        assertArrayEquals(data, back)
        assertEquals(r, g.contentBounds(alphaOnly = false))
    }

    @Test
    fun layeredDocumentSurvivesRoundTrip() {
        val doc = Document.blank(300, 200)
        val red = PixelLayer("Rot – Überzug", TileGrid(300, 200)).apply {
            for (y in 20 until 120) for (x in 30 until 180) pixels[x, y] = 0x80FF0000.toInt()
            blendMode = BlendMode.MULTIPLY
            opacity = 0.5f
        }
        val group = GroupLayer("Gruppe Ä").apply { blendMode = BlendMode.PASS_THROUGH; expanded = false }
        val inGroup = PixelLayer("In Gruppe", TileGrid(300, 200)).apply {
            for (y in 0 until 50) for (x in 0 until 50) pixels[x, y] = 0xFF00FF00.toInt()
            mask = LayerMask(TileGrid(300, 200, 255).also { m -> for (y in 0 until 25) for (x in 0 until 50) m[x, y] = 0 })
            visible = false
        }
        val clipped = PixelLayer("Geschnitten", TileGrid(300, 200)).apply {
            for (y in 0 until 200) for (x in 0 until 300) pixels[x, y] = 0xFF0000FF.toInt()
            clipped = true
            fillOpacity = 0.25f
        }
        doc.root.add(red)
        doc.root.add(clipped)
        doc.root.add(group)
        group.add(inGroup)

        val bytes = PsdWriter.write(doc)
        val back = PsdReader.read(bytes)

        assertEquals(300, back.width)
        assertEquals(200, back.height)
        val top = back.root.children
        assertEquals(listOf("Hintergrund", "Rot – Überzug", "Geschnitten", "Gruppe Ä"), top.map { it.name })
        val r2 = top[1] as PixelLayer
        assertEquals(BlendMode.MULTIPLY, r2.blendMode)
        assertEquals(0.5f, r2.opacity, 0.01f)
        assertEquals(0x80FF0000.toInt(), r2.pixels[50, 50])
        assertEquals(0, r2.pixels[5, 5])
        assertTrue(top[2].clipped)
        assertEquals(0.25f, top[2].fillOpacity, 0.01f)
        val g2 = top[3] as GroupLayer
        assertEquals(BlendMode.PASS_THROUGH, g2.blendMode)
        assertEquals(false, g2.expanded)
        val ig = g2.children.single() as PixelLayer
        assertEquals(false, ig.visible)
        assertEquals(0, ig.mask!!.grid[10, 10])
        assertEquals(255, ig.mask!!.grid[10, 40])
        assertEquals(255, ig.mask!!.grid[200, 150])

        // Zusammengesetztes Bild muss (bis auf 8-Bit-Rundung der Deckkraft) gleich bleiben
        val a = Compositor.renderArgb(doc, doc.bounds)
        val b = Compositor.renderArgb(back, back.bounds)
        for (i in a.indices) for (s in intArrayOf(0, 8, 16, 24)) {
            val d = kotlin.math.abs(((a[i] ushr s) and 0xFF) - ((b[i] ushr s) and 0xFF))
            assertTrue("Pixel $i weicht um $d ab", d <= 2)
        }
    }

    @Test
    fun singleBackgroundLayerRoundTrip() {
        val doc = Document.blank(64, 32, background = 0xFF336699.toInt())
        val bytes = PsdWriter.write(doc)
        val back = PsdReader.read(bytes)
        assertEquals(0xFF336699.toInt(), (back.root.children.single() as PixelLayer).pixels[10, 10])
    }
}
