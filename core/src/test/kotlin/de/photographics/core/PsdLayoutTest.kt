package de.photographics.core

import de.photographics.core.adjust.CurvesAdj
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.editor.Gradient
import de.photographics.core.editor.GradientType
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Aufbau der geschriebenen PSD wie in Photoshop-Dateien (Photoshop lehnt Abweichungen mit „nicht
 * kompatibel mit dieser Version“ ab, andere Programme sind nachsichtiger). Ein strenger Parser
 * prüft alle Längenfelder bis zum Dateiende.
 */
class PsdLayoutTest {

    private class Layout(
        val depth: Int,
        val resources: Set<Int>,
        val globalMaskLen: Int,
        val globalKeys: List<String>,
        val layers: List<Rec>,
        val compositeCompression: Int,
    )

    private class Rec(val name: String, val blendRanges: Int, val luniCount: Int, val keys: Map<String, ByteArray>, val compressions: List<Int>)

    private fun parse(bytes: ByteArray): Layout {
        val b = ByteBuffer.wrap(bytes)
        assertEquals(0x38425053, b.getInt(0))
        val channels = b.getShort(12).toInt()
        val height = b.getInt(14); val width = b.getInt(18)
        val depth = b.getShort(22).toInt()
        var p = 26
        p += 4 + b.getInt(p)
        val resEnd = p + 4 + b.getInt(p); p += 4
        val res = HashSet<Int>()
        while (p < resEnd) {
            assertEquals("8BIM", String(bytes, p, 4, Charsets.ISO_8859_1))
            res += b.getShort(p + 4).toInt() and 0xFFFF
            val nl = bytes[p + 6].toInt() and 0xFF
            p += 6 + ((nl + 2) and 1.inv())
            val sz = b.getInt(p); p += 4 + ((sz + 1) and 1.inv())
        }
        assertEquals(resEnd, p)
        val lmEnd = p + 4 + b.getInt(p); p += 4

        fun blocks(end: Int, into: MutableMap<String, ByteArray>, keys: MutableList<String>? = null, onBlock: (String, Int, Int) -> Unit = { _, _, _ -> }) {
            while (p + 12 <= end) {
                assertEquals("8BIM", String(bytes, p, 4, Charsets.ISO_8859_1))
                val key = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
                val n = b.getInt(p + 8); p += 12
                into[key] = bytes.copyOfRange(p, p + n); keys?.add(key)
                onBlock(key, p, p + n)
                p += n
            }
            assertEquals(end, p)
        }

        val layers = ArrayList<Rec>()
        fun layerInfo(end: Int) {
            val count = Math.abs(b.getShort(p).toInt()); p += 2
            val chans = ArrayList<List<Long>>()
            val pending = ArrayList<Triple<String, Int, Pair<Int, Map<String, ByteArray>>>>()
            repeat(count) {
                p += 16
                val nc = b.getShort(p).toInt(); p += 2
                chans += List(nc) { k -> b.getInt(p + 6 * k + 2).toLong() }; p += 6 * nc
                assertEquals("8BIM", String(bytes, p, 4, Charsets.ISO_8859_1)); p += 12
                val extraEnd = p + 4 + b.getInt(p); p += 4
                p += 4 + b.getInt(p)
                val br = b.getInt(p); p += 4 + br
                val nl = bytes[p].toInt() and 0xFF
                val name = String(bytes, p + 1, nl, Charsets.ISO_8859_1)
                p += (nl + 4) and 3.inv()
                val keys = LinkedHashMap<String, ByteArray>()
                blocks(extraEnd, keys)
                pending += Triple(name, br, (keys["luni"]?.let { ByteBuffer.wrap(it).getInt(0) } ?: -1) to keys)
            }
            for ((i, rec) in pending.withIndex()) {
                val (name, br, lk) = rec
                val comps = chans[i].map { len -> (b.getShort(p).toInt()).also { p += len.toInt() } }
                layers += Rec(name, br, lk.first, lk.second, comps)
            }
            assertTrue("Auffüllung nach den Kanaldaten", end - p in 0..3)
            p = end
        }

        val liLen = b.getInt(p); p += 4
        if (liLen > 0) layerInfo(p + liLen)
        val gm = b.getInt(p); p += 4 + gm
        val gKeys = ArrayList<String>()
        blocks(lmEnd, HashMap(), gKeys) { key, start, end ->
            if (key == "Lr16") { val save = p; p = start; layerInfo(end); p = save }
        }
        val comp = b.getShort(p).toInt(); p += 2
        val rows = channels * height
        if (comp == 1) {
            var data = 0L
            repeat(rows) { data += b.getShort(p).toInt() and 0xFFFF; p += 2 }
            assertEquals(bytes.size.toLong(), p + data)
        } else {
            assertEquals(0, comp)
            assertEquals(bytes.size.toLong(), p + rows.toLong() * width * (depth / 8))
        }
        return Layout(depth, res, gm, gKeys, layers, comp)
    }

    private fun doc(): Editor {
        val e = Editor(Document.blank(300, 40, 0xFF204060.toInt()))
        e.addLayer()
        e.fillGradient(de.photographics.core.tools.PaintTarget.PIXELS, Gradient(GradientType.LINEAR, 0f, 0f, 300f, 0f, 0x80FF0000.toInt(), 0x400000FF), 1f)
        e.doc.root.add(AdjustmentLayer("Gradationskurven", CurvesAdj(listOf(0 to 0, 128 to 150, 255 to 255))))
        return e
    }

    @Test
    fun eightBitLikePhotoshop() {
        val e = doc()
        val bytes = PsdWriter.write(e.doc)
        val l = parse(bytes)
        assertEquals(8, l.depth)
        assertTrue("Versionsinfo 1057", 1057 in l.resources)
        assertEquals(16, l.globalMaskLen)
        assertEquals(1, l.compositeCompression)
        assertEquals(3, l.layers.size)
        for (r in l.layers) {
            assertEquals("Füllbereiche wie Photoshop", 40, r.blendRanges)
            assertEquals("luni ohne abschließende Null: ${r.name}", r.name.length, r.luniCount)
            assertTrue(r.compressions.all { it == 0 || it == 1 })
        }
        val curv = l.layers.last().keys.getValue("curv")
        assertTrue("Kurven mit „Crv “-Erweiterung", String(curv, Charsets.ISO_8859_1).contains("Crv "))
        val back = PsdReader.read(bytes)
        assertEquals(CurvesAdj(listOf(0 to 0, 128 to 150, 255 to 255)), (back.root.children.last() as AdjustmentLayer).adjustment)
    }

    @Test
    fun sixteenBitLikePhotoshop() {
        val e = doc()
        e.setBitDepth(16)
        val bytes = PsdWriter.write(e.doc)
        val l = parse(bytes)
        assertEquals(16, l.depth)
        assertTrue("Ebenen im Block Lr16", "Lr16" in l.globalKeys)
        assertEquals("Gesamtbild in 16 Bit unkomprimiert", 0, l.compositeCompression)
        val px = l.layers.filter { it.keys["curv"] == null }
        assertTrue("16-Bit-Ebenen als ZIP mit Prädiktion", px.all { r -> r.compressions.all { it == 3 } })
        // Rundlauf: ZIP-Kanäle werden zeilenweise gelesen
        val back = PsdReader.read(ByteBuffer.wrap(bytes))
        val a = e.doc.root.children[1] as PixelLayer
        val bl = back.root.children[1] as PixelLayer
        for (y in listOf(0, 17, 39)) for (x in listOf(0, 150, 299)) {
            assertEquals(a.pixels[x, y], bl.pixels[x, y])
            assertEquals(a.low!![x, y], bl.low!![x, y])
        }
    }
}
