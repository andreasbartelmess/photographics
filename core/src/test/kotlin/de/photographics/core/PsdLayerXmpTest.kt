package de.photographics.core

import de.photographics.core.doc.*
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdDescriptor
import de.photographics.core.psd.PsdLayerXmp
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdTaggedBlock
import de.photographics.core.psd.PsdWriter
import org.junit.Assert.*
import org.junit.Test

/** Eigene Ebenendaten stehen im Ebenen-XMP („shmd“), nicht in eigenen Zusatzblöcken (Photoshop warnt sonst). */
class PsdLayerXmpTest {
    private fun rect(w: Int, h: Int, r: IntRect, c: Int) = TileGrid(w, h).also { g -> g.writeRect(r, IntArray(r.width * r.height) { c }) }

    private fun smartDoc(): Pair<Document, SmartLayer> {
        val doc = Document(200, 100); val ed = Editor(doc)
        val a = PixelLayer("a", rect(200, 100, IntRect(10, 10, 60, 60), 0xFFFF0000.toInt()))
        val b = PixelLayer("b", rect(200, 100, IntRect(40, 30, 90, 80), 0xFF00FF00.toInt()))
        doc.root.add(a); doc.root.add(b)
        val s = ed.convertToSmartObject(listOf(a, b))!!
        ed.addSmartFilter(s, SmartFilter.of("gaussian-blur", floatArrayOf(3f)))
        s.mask = LayerMask(rect(200, 100, IntRect(0, 0, 100, 100), 255), linked = false)
        return doc to s
    }

    @Test fun ownDataOnlyInLayerXmp() {
        val (doc, s) = smartDoc()
        val bytes = PsdWriter.write(doc)
        val text = String(bytes, Charsets.ISO_8859_1)
        for (k in PsdLayerXmp.OWN_KEYS.keys) assertFalse("Zusatzblock $k geschrieben", text.contains("8BIM$k"))
        assertTrue(text.contains("8BIMshmd"))
        val back = PsdReader.read(bytes).root.children.single() as SmartLayer
        assertEquals(listOf("a", "b"), back.contents!!.root.children.map { it.name })
        assertEquals(s.transform, back.transform)
        assertEquals(1, back.smartFilters.filters.size)
        assertEquals("gaussian-blur", back.smartFilters.filters[0].filterId)
        assertFalse(back.mask!!.linked)
        // Zweimal speichern: genau ein shmd, Daten bleiben
        val again = PsdWriter.write(PsdReader.read(bytes))
        assertEquals(1, Regex("8BIMshmd").findAll(String(again, Charsets.ISO_8859_1)).count())
        assertEquals(1, (PsdReader.read(again).root.children.single() as SmartLayer).smartFilters.filters.size)
    }

    @Test fun keepsPhotoshopMetadataAndDropsOursWhenGone() {
        // Wie Photoshop: shmd → cust → metadata mit layerTime
        val ps = PsdDescriptor("metadata").also { it["layerTime"] = 1.7e9 }
        val cust = de.photographics.core.psd.BinaryWriter().also { it.i32(16); PsdDescriptor.write(it, ps, terminatedName = true) }.toByteArray()
        val shmd = de.photographics.core.psd.BinaryWriter().also { w ->
            w.i32(1); w.ascii("8BIM"); w.ascii("cust"); w.i32(0); w.i32(cust.size); w.bytes(cust)
        }.toByteArray()
        val (doc, s) = smartDoc()
        s.preservedBlocks = listOf(PsdTaggedBlock("8BIM", "shmd", shmd))
        val back = PsdReader.read(PsdWriter.write(doc))
        val l = back.root.children.single() as SmartLayer
        assertEquals(1, l.smartFilters.filters.size)
        val kept = l.preservedBlocks.single { it.key == "shmd" }.data
        assertEquals(PsdLayerXmp.read(kept).keys, setOf("pgSO", "pgSC", "pgSF", "pgML"))

        // Photoshops Zeitstempel bleibt; ohne eigene Daten verschwindet unser layerXMP
        val plain = PsdLayerXmp.shmd(kept, emptyMap())!!
        assertTrue(PsdLayerXmp.read(plain).isEmpty())
        assertTrue(String(plain, Charsets.ISO_8859_1).contains("layerTime"))
        // Kein shmd vorhanden und nichts zu schreiben: kein Block
        assertNull(PsdLayerXmp.shmd(null, emptyMap()))
    }

    @Test fun encodeDecodeAndPlainMask() {
        val doc = Document(100, 100)
        val l = PixelLayer("x", rect(100, 100, IntRect(0, 0, 50, 50), 0xFF000000.toInt()))
        l.mask = LayerMask(rect(100, 100, IntRect(0, 0, 100, 100), 255))
        doc.root.add(l)
        val bytes = PsdWriter.write(doc)
        val xmp = PsdLayerXmp.encode(mapOf("pgML" to byteArrayOf(0, 0, 0, 0), "pgSC" to ByteArray(4000) { (it % 7).toByte() }))
        val decoded = PsdLayerXmp.decode(xmp)
        assertArrayEquals(ByteArray(4000) { (it % 7).toByte() }, decoded["pgSC"])
        assertTrue("großer Block komprimiert", xmp.contains(">z:"))
        assertTrue(PsdReader.read(bytes).root.children.single().mask!!.linked)
    }
}

/** PG_XMP_OUT=Verzeichnis: Testdateien (eigene Daten im Ebenen-XMP) zum Prüfen in Photoshop schreiben. */
class PsdLayerXmpFixtures {
    @Test fun writeFixtures() {
        val out = System.getenv("PG_XMP_OUT")?.let { java.io.File(it) } ?: return
        out.mkdirs()
        fun doc(): Pair<Document, SmartLayer> {
            val doc = Document(800, 600); val ed = Editor(doc)
            val bg = PixelLayer("Hintergrund", TileGrid.fromPixels(800, 600, IntArray(800 * 600) { i -> val x = i % 800; val y = i / 800
                (0xFF shl 24) or ((x * 255 / 800) shl 16) or ((y * 255 / 600) shl 8) or 0x80 }))
            val a = PixelLayer("Rotes Quadrat", TileGrid(800, 600).also { g -> g.writeRect(IntRect(100, 100, 300, 300), IntArray(200 * 200) { 0xFFE03020.toInt() }) })
            val c = PixelLayer("Blauer Kreis", TileGrid.fromPixels(800, 600, IntArray(800 * 600) { i -> val x = i % 800 - 300; val y = i / 800 - 300
                if (x * x + y * y < 90 * 90) 0xFF2050D0.toInt() else 0 }))
            doc.root.add(bg); doc.root.add(a); doc.root.add(c); doc.activeLayer = c
            return doc to ed.convertToSmartObject(listOf(a, c))!!
        }
        run {
            val (d, s) = doc(); val ed = Editor(d)
            ed.addSmartFilter(s, SmartFilter.of("gaussian-blur", floatArrayOf(6f)))
            s.mask = LayerMask(TileGrid(800, 600).also { g -> g.writeRect(IntRect(0, 0, 400, 600), IntArray(400 * 600) { 255 }) }, linked = false)
            java.io.File(out, "E-smartfilter-maske-im-xmp.psd").writeBytes(PsdWriter.write(d))
        }
        PsdWriter.photoshopSmartObjects = true
        try {
            val (d, _) = doc()
            java.io.File(out, "F-photoshop-smartobjekt-und-xmp.psd").writeBytes(PsdWriter.write(d))
        } finally { PsdWriter.photoshopSmartObjects = true }
    }
}
