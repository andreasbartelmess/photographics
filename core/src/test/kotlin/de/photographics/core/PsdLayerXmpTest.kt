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
        // Lage, Inhalt und Smartfilter stehen in Photoshops eigenen Blöcken (SoLd/lnk2/filterFX), im XMP nur der Rest
        assertEquals(setOf("pgML"), PsdLayerXmp.read(kept).keys)
        val sold = l.preservedBlocks.singleOrNull { it.key == "SoLd" }
        assertTrue("SoLd mit filterFX", sold == null || String(sold.data, Charsets.ISO_8859_1).contains("filterFX"))

        // Photoshops Zeitstempel bleibt; ohne eigene Daten verschwindet unser layerXMP
        val plain = PsdLayerXmp.shmd(kept, emptyMap())!!
        assertTrue(PsdLayerXmp.read(plain).isEmpty())
        assertTrue(String(plain, Charsets.ISO_8859_1).contains("layerTime"))
        // Kein shmd vorhanden und nichts zu schreiben: kein Block
        assertNull(PsdLayerXmp.shmd(null, emptyMap()))
    }

    @Test fun filterMaskAsPhotoshopFilterEffects() {
        // Smartfilter mit Filtermaske: als Photoshop-Smartfilter („filterFX“) + Maske und Bild in „FEid“
        val doc = Document(200, 100); val ed = Editor(doc)
        val a = PixelLayer("a", rect(200, 100, IntRect(20, 20, 180, 80), 0xFFFF0000.toInt()))
        doc.root.add(a)
        val s = ed.convertToSmartObject(listOf(a))!!
        ed.addSmartFilter(s, SmartFilter.of("gaussian-blur", floatArrayOf(4f)))
        s.smartFilters = s.smartFilters.copy(mask = LayerMask(rect(200, 100, IntRect(100, 0, 200, 100), 255).also { g ->
            g.writeRect(IntRect(0, 0, 100, 100), IntArray(100 * 100) { 0 }) }))
        val bytes = PsdWriter.write(doc)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("8BIMFEid")); assertTrue(text.contains("8BIMFMsk")); assertTrue(text.contains("filterFX"))
        assertFalse("keine eigene Filterkopie", text.contains("pgSF"))
        val back = PsdReader.read(bytes).root.children.single() as SmartLayer
        assertEquals("gaussian-blur", back.smartFilters.filters.single().filterId)
        val m = back.smartFilters.mask!!.grid
        assertEquals(0, m[50, 50]); assertEquals(255, m[150, 50])
        // Zweites Speichern: genau ein Eintrag, Maske bleibt
        val again = PsdReader.read(PsdWriter.write(PsdReader.read(bytes))).root.children.single() as SmartLayer
        assertEquals(0, again.smartFilters.mask!!.grid[50, 50])
    }

    @Test fun galleryAsPhotoshopFilterGallery() {
        // Filtergalerie-Kette als Photoshop-Smartfilter „Filtergalerie“ (GEfc), Werte/Sichtbarkeit/Farben bleiben erhalten
        val doc = Document(200, 100); val ed = Editor(doc)
        val a = PixelLayer("a", rect(200, 100, IntRect(20, 20, 180, 80), 0xFFFF0000.toInt()))
        doc.root.add(a)
        val s = ed.convertToSmartObject(listOf(a))!!
        val chain = listOf(
            de.photographics.core.filters.GalleryEffect(de.photographics.core.filters.Gallery.byId("poster-edges")!!, floatArrayOf(4f, 3f, 5f)),
            de.photographics.core.filters.GalleryEffect(de.photographics.core.filters.Gallery.byId("glass")!!, floatArrayOf(7f, 2f, 1f, 150f, 1f), visible = false),
            de.photographics.core.filters.GalleryEffect(de.photographics.core.filters.Gallery.byId("neon-glow")!!, floatArrayOf(-3f, 20f, 5f)),
        )
        ed.addSmartFilter(s, SmartFilter.gallery(chain, 0xFF102030.toInt(), 0xFFF0E0D0.toInt()).copy(opacity = 0.5f))
        val bytes = PsdWriter.write(doc)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("GEfs")); assertFalse("keine eigene Filterkopie", text.contains("pgSF"))
        val back = (PsdReader.read(bytes).root.children.single() as SmartLayer).smartFilters.filters.single()
        assertEquals(0.5f, back.opacity, 1e-4f)
        assertEquals(0xFF102030.toInt(), back.galleryFg); assertEquals(0xFFF0E0D0.toInt(), back.galleryBg)
        assertEquals(chain, back.gallery)
    }

    @Test fun galleryWithoutPhotoshopCounterpartStaysOwn() {
        // Körnung mit eigener Korngröße gibt es in Photoshop nicht → eigene Daten, Ebene bleibt Smartobjekt
        val doc = Document(100, 100); val ed = Editor(doc)
        val a = PixelLayer("a", rect(100, 100, IntRect(10, 10, 90, 90), 0xFF808080.toInt()))
        doc.root.add(a)
        val s = ed.convertToSmartObject(listOf(a))!!
        val g = de.photographics.core.filters.Gallery.byId("grain")!!
        ed.addSmartFilter(s, SmartFilter.gallery(listOf(de.photographics.core.filters.GalleryEffect(g, floatArrayOf(40f, 50f, 0f, 4f))), 0xFF000000.toInt(), -1))
        val back = PsdReader.read(PsdWriter.write(doc)).root.children.single() as SmartLayer
        assertEquals(4f, back.smartFilters.filters.single().gallery!!.single().values[3])
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

/** PG_SF_OUT=Verzeichnis: je Photoshop-Smartfilter eine Testdatei (Smartobjekt + Filter) zum Prüfen in Photoshop. */
class PsSmartFilterFixtures {
    @Test fun writeFixtures() {
        val out = System.getenv("PG_SF_OUT")?.let { java.io.File(it) } ?: return
        out.mkdirs()
        fun doc(filters: List<SmartFilter>): Document {
            val doc = Document(400, 300); val ed = Editor(doc)
            val bg = PixelLayer("Hintergrund", TileGrid.fromPixels(400, 300, IntArray(400 * 300) { i -> val x = i % 400; val y = i / 400
                (0xFF shl 24) or ((x * 255 / 400) shl 16) or ((y * 255 / 300) shl 8) or 0x80 }))
            val a = PixelLayer("Inhalt", TileGrid(400, 300).also { g -> g.writeRect(IntRect(50, 50, 250, 200), IntArray(200 * 150) { 0xFFDC281E.toInt() }) })
            doc.root.add(bg); doc.root.add(a); doc.activeLayer = a
            val s = ed.convertToSmartObject(listOf(a))!!
            for (f in filters) ed.addSmartFilter(s, f)
            return doc
        }
        val single = mapOf(
            "gaussian-blur" to floatArrayOf(6f), "unsharp-mask" to floatArrayOf(120f, 2.5f, 3f), "add-noise" to floatArrayOf(12f, 1f, 1f),
            "median" to floatArrayOf(4f), "high-pass" to floatArrayOf(7f), "motion-blur" to floatArrayOf(25f, 30f),
            "dust-scratches" to floatArrayOf(3f, 12f), "radial-blur" to floatArrayOf(15f, 0f), "twirl" to floatArrayOf(90f),
            "mosaic" to floatArrayOf(16f), "emboss" to floatArrayOf(135f, 3f, 100f), "find-edges" to floatArrayOf(),
            "surface-blur" to floatArrayOf(5f, 15f), "smart-sharpen" to floatArrayOf(120f, 1.2f, 20f),
        )
        for ((id, v) in single) java.io.File(out, "sf-$id.psd").writeBytes(PsdWriter.write(doc(listOf(SmartFilter.of(id, v)))))
        val multi = doc(listOf(
            SmartFilter.of("gaussian-blur", floatArrayOf(3f)),
            SmartFilter.of("add-noise", floatArrayOf(5f, 0f, 0f)).copy(opacity = 0.6f, blendMode = de.photographics.core.render.BlendMode.MULTIPLY),
            SmartFilter.of("twirl", floatArrayOf(45f)).copy(enabled = false),
        ))
        java.io.File(out, "sf-mehrere.psd").writeBytes(PsdWriter.write(multi))
        val masked = doc(listOf(SmartFilter.of("gaussian-blur", floatArrayOf(8f))))
        (masked.allLayers().filterIsInstance<SmartLayer>().single()).let { s ->
            s.smartFilters = s.smartFilters.copy(mask = LayerMask(TileGrid(400, 300, 255).also { g -> g.writeRect(IntRect(0, 0, 200, 300), IntArray(200 * 300) { 0 }) }))
            s.invalidate()
        }
        java.io.File(out, "sf-maske.psd").writeBytes(PsdWriter.write(masked))
        fun ge(id: String, vararg v: Float, visible: Boolean = true) = de.photographics.core.filters.GalleryEffect(de.photographics.core.filters.Gallery.byId(id)!!, v, visible)
        java.io.File(out, "sf-galerie.psd").writeBytes(PsdWriter.write(doc(listOf(
            SmartFilter.gallery(listOf(ge("poster-edges", 4f, 3f, 5f), ge("glass", 7f, 2f, 1f, 150f, visible = false), ge("watercolor", 9f, 1f, 1f)), 0xFF000000.toInt(), -1)))))
        java.io.File(out, "sf-galerie-gemischt.psd").writeBytes(PsdWriter.write(doc(listOf(
            SmartFilter.of("gaussian-blur", floatArrayOf(2f)),
            SmartFilter.gallery(listOf(ge("texturizer", 1f, 120f, 8f, 2f), ge("bas-relief", 10f, 4f, 2f), ge("neon-glow", 8f, 30f, 3f)), 0xFF203080.toInt(), 0xFFFFF0C0.toInt())
                .copy(blendMode = de.photographics.core.render.BlendMode.OVERLAY, opacity = 0.7f)))))
        for (spec in de.photographics.core.filters.Gallery.specs) java.io.File(out, "gal-${spec.id}.psd").writeBytes(PsdWriter.write(doc(listOf(
            SmartFilter.gallery(listOf(de.photographics.core.filters.GalleryEffect(spec, spec.defaults())), 0xFF000000.toInt(), -1)))))
    }
}

