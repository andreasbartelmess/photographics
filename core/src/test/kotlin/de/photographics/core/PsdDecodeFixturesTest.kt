package de.photographics.core

import de.photographics.core.image.IntRect
import de.photographics.core.image.SlabTileMemory
import de.photographics.core.image.TileMemory
import de.photographics.core.psd.PsdReader
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/** Streifenweises Dekodieren: Ebenen- und Gesamtbild-PSDs (RLE, unkomprimiert, 16 Bit, Alpha) gegen PNG-Referenz. */
class PsdDecodeFixturesTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/psd/$name")!!.readBytes()

    private fun maxDiff(psd: String, png: String): Int {
        val doc = PsdReader.read(res(psd))
        val ref = ImageIO.read(javaClass.getResourceAsStream("/psd/$png"))
        // Dekodierte Ebenenpixel direkt vergleichen (nicht das zusammengesetzte Bild)
        val layer = doc.pixelLayers().first()
        val px = IntArray(doc.width * doc.height); layer.pixels.readRect(IntRect.ofSize(doc.width, doc.height), px)
        var worst = 0
        for (y in 0 until doc.height) for (x in 0 until doc.width) {
            val a = px[y * doc.width + x]; val b = ref.getRGB(x, y)
            worst = maxOf(worst, kotlin.math.abs((a ushr 24) - (b ushr 24)))
            if ((b ushr 24) < 8) continue // Farbe bei (fast) transparenten Pixeln ist bedeutungslos
            for (s in intArrayOf(16, 8, 0)) worst = maxOf(worst, kotlin.math.abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)))
        }
        return worst
    }

    private fun checkAll() {
        for (f in listOf("flat-rle.psd", "flat-raw.psd", "flat-16.psd", "merged-rle.psd", "merged-raw.psd", "merged-16.psd")) {
            val d = maxDiff(f, "ref.png")
            assertTrue("$f weicht um $d ab", d <= 1)
        }
        val d = maxDiff("flat-alpha.psd", "ref-alpha.png")
        assertTrue("flat-alpha.psd weicht um $d ab", d <= 2)
    }

    @Test
    fun decodesFixturesOnHeap() = checkAll()

    @Test
    fun decodesFixturesInSlabMemory() {
        val prev = TileMemory.current
        TileMemory.current = SlabTileMemory(256L shl 20, { ByteBuffer.allocateDirect(it) }, tilesPerSlab = 16, hotCapacity = 4)
        try { checkAll() } finally { TileMemory.current = prev }
    }
}
