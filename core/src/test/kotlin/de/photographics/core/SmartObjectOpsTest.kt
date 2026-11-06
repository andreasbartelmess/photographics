package de.photographics.core

import de.photographics.core.doc.*
import de.photographics.core.editor.Editor
import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import org.junit.Assert.*
import org.junit.Test

class SmartObjectOpsTest {
    private fun solid(w: Int, h: Int, c: Int) = TileGrid.fromPixels(w, h, IntArray(w * h) { c })
    private fun px(g: TileGrid, x: Int, y: Int) = g[x, y]

    @Test fun placeFitsLargeImagesAndKeepsSmallOnes() {
        val doc = Document(400, 300); val ed = Editor(doc)
        val big = ed.placeSmartObject("gross", solid(800, 400, 0xFFFF0000.toInt()))
        // 800×400 → 400×200, mittig (Rand oben/unten 50 px)
        assertEquals(0xFFFF0000.toInt(), px(big.raster, 200, 150))
        assertEquals(0, px(big.raster, 200, 20))
        assertEquals(0xFFFF0000.toInt(), px(big.raster, 2, 60))
        val small = ed.placeSmartObject("klein", solid(100, 50, 0xFF00FF00.toInt()))
        assertEquals(Affine.translate(150.0, 125.0), small.transform)
        assertSame(small, doc.activeLayer)
        ed.undo(); assertFalse(small in doc.root.children)
    }

    @Test fun replaceContentsFitsIntoOldFrameAndKeepsFilters() {
        val doc = Document(400, 300); val ed = Editor(doc)
        val l = ed.placeSmartObject("a", solid(100, 100, 0xFFFF0000.toInt()))
        ed.commitGeometry(l, ed.geometryOf(l), Editor.Geometry(Affine.scale(2.0).then(Affine.translate(50.0, 50.0))))
        ed.addSmartFilter(l, SmartFilter.of("adjust:INVERT", floatArrayOf()))
        l.opacity = 0.5f
        val r = ed.replaceSmartContents(l, solid(50, 100, 0xFF0000FF.toInt()))
        // Rahmen 200×200 ab (50,50); neuer Inhalt 1:2 → 100×200 mittig ab x = 100
        assertEquals(0xFFFFFF00.toInt(), px(r.raster, 150, 150))   // Blau invertiert
        assertEquals(0, px(r.raster, 75, 150))
        assertEquals(0.5f, r.opacity)
        assertEquals(1, r.smartFilters.filters.size)
        ed.undo(); assertSame(l, doc.root.children.single())
    }

    @Test fun resetTransformRestoresNativeSizeAroundCenter() {
        val doc = Document(400, 300); val ed = Editor(doc)
        val l = ed.placeSmartObject("a", solid(100, 60, 0xFFFF0000.toInt()))
        ed.commitGeometry(l, ed.geometryOf(l), Editor.Geometry(l.transform, doubleArrayOf(10.0, 10.0, 300.0, 40.0, 280.0, 250.0, 30.0, 200.0)))
        ed.resetSmartTransform(l)
        assertNull(l.warp)
        val c = l.corners()
        assertEquals(100.0, c[2] - c[0], 1e-9); assertEquals(60.0, c[5] - c[3], 1e-9)
        ed.undo(); assertNotNull(l.warp)
    }

    @Test fun convertTextAndSeveralLayers() {
        val doc = Document(200, 100); val ed = Editor(doc)
        val a = PixelLayer("a", TileGrid(200, 100).also { g -> g.writeRect(IntRect(10, 10, 60, 60), IntArray(2500) { 0xFFFF0000.toInt() }) })
        val b = PixelLayer("b", TileGrid(200, 100).also { g -> g.writeRect(IntRect(100, 20, 150, 90), IntArray(3500) { 0xFF00FF00.toInt() }) })
        b.opacity = 0.5f
        doc.root.add(a); doc.root.add(b); doc.activeLayer = b
        val s = ed.convertToSmartObject(listOf(a, b))!!
        assertEquals(1, doc.root.children.size)
        assertEquals(1f, s.opacity)
        assertEquals(140 to 80, s.source.width to s.source.height)
        assertEquals(0xFFFF0000.toInt(), px(s.raster, 20, 20))
        assertEquals(0x80, px(s.raster, 120, 50) ushr 24)
        ed.undo(); assertEquals(listOf(a, b), doc.root.children)
        // Einzelne Smartobjekte bleiben unverändert
        assertNull(ed.convertToSmartObject(listOf(ed.convertToSmartObject(a)!!)))
    }
}

class SmartContentsTest {
    private fun rectLayer(name: String, w: Int, h: Int, r: IntRect, c: Int) =
        PixelLayer(name, TileGrid(w, h).also { g -> g.writeRect(r, IntArray(r.width * r.height) { c }) })

    private fun setup(): Triple<Document, Editor, SmartLayer> {
        val doc = Document(200, 100); val ed = Editor(doc)
        val a = rectLayer("a", 200, 100, IntRect(10, 10, 60, 60), 0xFFFF0000.toInt())
        val t = TextLayer("Text", TextStyle(text = "Hallo", sizePx = 20f, color = 0xFF0000FF.toInt()), Affine.translate(100.0, 30.0), 200, 100)
        doc.root.add(a); doc.root.add(t); doc.activeLayer = t
        val s = ed.convertToSmartObject(listOf(a, t))!!
        return Triple(doc, ed, s)
    }

    @Test fun convertKeepsLayersAsContents() {
        val (_, ed, s) = setup()
        val c = s.contents!!
        assertEquals(listOf("a", "Text"), c.root.children.map { it.name })
        assertTrue(c.root.children[1] is TextLayer)
        assertEquals(s.source.width to s.source.height, c.width to c.height)
        // Bild des Inhalts == Quelle
        val src = de.photographics.core.render.Compositor.renderGrid(c, c.bounds)
        assertEquals(s.source[5, 5], src[5, 5])
        assertNotNull(ed)
    }

    @Test fun editContentsUpdatesImageKeepsPlacementAndUndoes() {
        val (doc, ed, s) = setup()
        val corner = s.corners()
        val edit = ed.editableContents(s)
        // Im Inhalt eine Ebene ausblenden: Rot verschwindet
        edit.root.children[0].visible = false
        val n = ed.updateSmartContents(s, edit)
        assertEquals(n.source.width, s.source.width)
        assertArrayEquals(corner, n.corners(), 1e-9)
        assertEquals(0, n.raster[30, 30] ushr 24)
        assertEquals(0xFFFF0000.toInt(), s.raster[30, 30])
        // Der übernommene Inhalt ist eine Kopie: spätere Änderungen am Bearbeitungsdokument wirken nicht
        edit.root.children[0].visible = true
        assertFalse(n.contents!!.root.children[0].visible)
        ed.undo(); assertSame(s, doc.root.children.single())
    }

    @Test fun convertToLayersRestoresOriginalLayers() {
        val (doc, ed, s) = setup()
        assertTrue(ed.convertSmartToLayers(s))
        val g = doc.root.children.single() as GroupLayer
        assertEquals(listOf("a", "Text"), g.children.map { it.name })
        val a = g.children[0] as PixelLayer
        assertEquals(0xFFFF0000.toInt(), a.pixels[30, 30])
        assertEquals(0, a.pixels[5, 5] ushr 24)
        assertEquals(Affine.translate(100.0, 30.0), (g.children[1] as TextLayer).transform)
        ed.undo(); assertSame(s, doc.root.children.single())
        // Skaliert: geht nicht (rastern)
        ed.commitGeometry(s, ed.geometryOf(s), Editor.Geometry(s.transform.then(Affine.scale(1.5))))
        assertFalse(ed.convertSmartToLayers(s))
    }

    @Test fun contentsSurvivePsdRoundTrip() {
        val (doc, _, s) = setup()
        s.opacity = 0.7f
        val back = de.photographics.core.psd.PsdReader.read(de.photographics.core.psd.PsdWriter.write(doc))
        val r = back.root.children.single() as SmartLayer
        assertEquals(listOf("a", "Text"), r.contents!!.root.children.map { it.name })
        assertTrue(r.contents!!.root.children[1] is TextLayer)
        assertEquals(s.source.width, r.source.width)
    }

    @Test fun duplicateIsIndependent() {
        val (doc, ed, s) = setup()
        val d = ed.duplicateSmartObject(s)
        assertEquals(2, doc.root.children.size)
        assertNotSame(s.contents, d.contents)
        ed.updateSmartContents(d, ed.editableContents(d).also { it.root.children[0].visible = false })
        assertEquals(0xFFFF0000.toInt(), s.raster[30, 30])
    }
}

class PhotoshopSmartObjectTest {
    private fun rect(w: Int, h: Int, r: IntRect, c: Int) = TileGrid(w, h).also { g -> g.writeRect(r, IntArray(r.width * r.height) { c }) }

    private fun docWithSmart(): Pair<Document, SmartLayer> {
        val doc = Document(200, 100); val ed = Editor(doc)
        val a = PixelLayer("a", rect(200, 100, IntRect(10, 10, 60, 60), 0xFFFF0000.toInt()))
        val b = PixelLayer("b", rect(200, 100, IntRect(40, 30, 90, 80), 0xFF00FF00.toInt()))
        doc.root.add(a); doc.root.add(b)
        val s = ed.convertToSmartObject(listOf(a, b))!!
        ed.commitGeometry(s, ed.geometryOf(s), Editor.Geometry(s.transform.then(Affine.scale(1.2))))
        return doc to s
    }

    @Test fun ownSmartObjectsRoundTripThroughPhotoshopBlocks() {
        de.photographics.core.psd.PsdWriter.photoshopSmartObjects = true
        try {
            val (doc, s) = docWithSmart()
            val bytes = de.photographics.core.psd.PsdWriter.write(doc)
            val text = String(bytes, Charsets.ISO_8859_1)
            assertTrue("SoLd fehlt", text.contains("8BIMSoLd"))
            assertTrue("lnk2 fehlt", text.contains("8BIMlnk2"))
            assertFalse("Inhalt doppelt gespeichert", text.contains("8BIMpgSC"))
            val back = de.photographics.core.psd.PsdReader.read(bytes)
            val r = back.root.children.single() as SmartLayer
            assertEquals(listOf("a", "b"), r.contents!!.root.children.map { it.name })
            assertEquals(s.transform, r.transform)
            // Zweimal speichern: eingebettete Dateien vermehren sich nicht
            val again = de.photographics.core.psd.PsdWriter.write(back)
            assertEquals(1, Regex("liFD").findAll(String(again, Charsets.ISO_8859_1)).count())
        } finally { de.photographics.core.psd.PsdWriter.photoshopSmartObjects = true }
    }

    @Test fun readsPhotoshopStyleSmartObject() {
        // Wie Photoshop: Pixelebene mit „SoLd“, eingebettete PSD in „lnk2“, keine eigenen Blöcke
        val content = Document(50, 40).also { it.root.add(PixelLayer("innen", rect(50, 40, IntRect(0, 0, 50, 40), 0xFF3366CC.toInt()))) }
        val uuid = "a1b2c3d4-0000-1111-2222-333344445555"
        val corners = doubleArrayOf(20.0, 10.0, 120.0, 10.0, 120.0, 90.0, 20.0, 90.0)   // doppelt so groß
        val doc = Document(200, 100)
        val shown = rect(200, 100, IntRect(20, 10, 120, 90), 0xFF3366CC.toInt())
        val l = PixelLayer("Smart", shown)
        l.preservedBlocks = listOf(de.photographics.core.psd.PsdTaggedBlock("8BIM", "SoLd", de.photographics.core.psd.PsdLinked.writePlaced(uuid, corners, 50, 40, 72.0)))
        doc.root.add(l)
        doc.preservedGlobalBlocks = listOf(de.photographics.core.psd.PsdTaggedBlock("8BIM", "lnk2",
            de.photographics.core.psd.PsdLinked.writeEntry(uuid, "innen.psd", "8BPS", de.photographics.core.psd.PsdWriter.write(content))))
        val back = de.photographics.core.psd.PsdReader.read(de.photographics.core.psd.PsdWriter.write(doc))
        val s = back.root.children.single() as SmartLayer
        assertEquals(listOf("innen"), s.contents!!.root.children.map { it.name })
        assertEquals(50 to 40, s.source.width to s.source.height)
        assertArrayEquals(corners, s.corners(), 1e-9)
        // Neu gerechnet (nicht nur das gespeicherte Bild) liegt der Inhalt richtig
        s.invalidate()
        assertEquals(0xFF3366CC.toInt(), s.raster[70, 50])
        assertEquals(0, s.raster[10, 50] ushr 24)
        // Die nun übernommene Datei wird nicht zusätzlich als fremder Eintrag aufbewahrt
        assertTrue(back.preservedGlobalBlocks.none { it.key == "lnk2" })
    }

    @Test fun keepsEmbeddedFileOfWarpedPhotoshopSmartObject() {
        // Photoshop 2026 schreibt in „SoLd“ ein Gitter (customEnvelopeWarp → meshPoints als „ObAr“).
        // Früher scheiterte das Lesen daran: lnk2 ging verloren, Photoshop konnte die Datei danach nicht speichern.
        val uuid = "50c20ffe-fde5-2443-adb5-3b5dee7a7634"
        val corners = doubleArrayOf(20.0, 10.0, 120.0, 10.0, 120.0, 90.0, 20.0, 90.0)
        val plain = de.photographics.core.psd.PsdLinked.writePlaced(uuid, corners, 50, 40, 72.0)
        val r = de.photographics.core.psd.BinaryReader(plain); r.ascii(4); r.i32(); r.i32()
        val d = de.photographics.core.psd.PsdDescriptor.read(r)
        val mesh = linkedMapOf<String, Any?>(
            "Hrzn" to de.photographics.core.psd.PsdDescriptor.UnitFloats("#Pxl", DoubleArray(16) { (it % 4) * 50.0 / 3 }),
            "Vrtc" to de.photographics.core.psd.PsdDescriptor.UnitFloats("#Pxl", DoubleArray(16) { (it / 4) * 40.0 / 3 + if (it == 5) 7.0 else 0.0 }))
        d.descriptor("warp")!!["customEnvelopeWarp"] = de.photographics.core.psd.PsdDescriptor("customEnvelopeWarp").also {
            it["meshPoints"] = de.photographics.core.psd.PsdDescriptor.ObjectArray(16, "", "rationalPoint", mesh)
        }
        val w = de.photographics.core.psd.BinaryWriter(); w.ascii("soLD"); w.i32(4); w.i32(16); de.photographics.core.psd.PsdDescriptor.write(w, d)
        val soLd = w.toByteArray()
        // ObAr verlustfrei: lesen + schreiben ergibt dieselben Bytes
        val r2 = de.photographics.core.psd.BinaryReader(soLd); r2.ascii(4); r2.i32(); r2.i32()
        val w2 = de.photographics.core.psd.BinaryWriter(); w2.ascii("soLD"); w2.i32(4); w2.i32(16); de.photographics.core.psd.PsdDescriptor.write(w2, de.photographics.core.psd.PsdDescriptor.read(r2))
        assertArrayEquals(soLd, w2.toByteArray())
        val placed = de.photographics.core.psd.PsdLinked.readPlaced(soLd)!!
        assertEquals(uuid, placed.uuid)
        assertTrue(placed.warped)

        val doc = Document(200, 100)
        val l = PixelLayer("QR", rect(200, 100, IntRect(20, 10, 120, 90), 0xFF000000.toInt()))
        l.preservedBlocks = listOf(de.photographics.core.psd.PsdTaggedBlock("8BIM", "SoLd", soLd))
        doc.root.add(l)
        val png = ByteArray(300) { it.toByte() }
        doc.preservedGlobalBlocks = listOf(de.photographics.core.psd.PsdTaggedBlock("8BIM", "lnk2",
            de.photographics.core.psd.PsdLinked.writeEntry(uuid, "qr.png", "png ", png)))
        val back = de.photographics.core.psd.PsdReader.read(de.photographics.core.psd.PsdWriter.write(doc))
        assertTrue("verkrümmt → bleibt Pixelebene", back.root.children.single() !is SmartLayer)
        // Die eingebettete Datei bleibt erhalten (sonst kann Photoshop nicht mehr speichern)
        val again = de.photographics.core.psd.PsdReader.read(de.photographics.core.psd.PsdWriter.write(back))
        val entries = again.preservedGlobalBlocks.filter { it.key == "lnk2" }.flatMap { de.photographics.core.psd.PsdLinked.readEntries(it.data) }
        assertEquals(listOf(uuid), entries.map { it.uuid })
        assertArrayEquals(png, entries.single().data)
    }

    @Test fun nestedWriteKeeps16BitState() {
        val (doc, _) = docWithSmart()
        val ed = Editor(doc)
        val top = PixelLayer("oben", rect(200, 100, IntRect(150, 10, 190, 50), 0xFF102030.toInt()))
        doc.root.add(top)
        ed.setBitDepth(16)
        val back = de.photographics.core.psd.PsdReader.read(de.photographics.core.psd.PsdWriter.write(doc))
        assertEquals(16, back.bitDepth)
        val t = back.root.children.last() as PixelLayer
        assertEquals("oben", t.name)
        assertEquals(top.pixels[160, 20], t.pixels[160, 20])
        assertNotNull(t.low)
    }
}

/** PG_PS_SMART_OUT=Verzeichnis: Testdateien mit Photoshop-Smartobjekten zum Prüfen in Photoshop schreiben. */
class PhotoshopSmartObjectFixtures {
    @Test fun writeFixtures() {
        val out = System.getenv("PG_PS_SMART_OUT")?.let { java.io.File(it) } ?: return
        out.mkdirs()
        de.photographics.core.psd.PsdWriter.photoshopSmartObjects = true
        try {
            fun base(): Pair<Document, Editor> {
                val doc = Document(800, 600); val ed = Editor(doc)
                val bg = PixelLayer("Hintergrund", TileGrid.fromPixels(800, 600, IntArray(800 * 600) { i -> val x = i % 800; val y = i / 800
                    (0xFF shl 24) or ((x * 255 / 800) shl 16) or ((y * 255 / 600) shl 8) or 0x80 }))
                doc.root.add(bg); doc.activeLayer = bg
                return doc to ed
            }
            fun layers(doc: Document): List<Layer> {
                val a = PixelLayer("Rotes Quadrat", TileGrid(800, 600).also { g -> g.writeRect(IntRect(100, 100, 300, 300), IntArray(200 * 200) { 0xFFE03020.toInt() }) })
                // Kreis als zweite Ebene (Text braucht eine Schriftausgabe der Oberfläche)
                val t = PixelLayer("Blauer Kreis", TileGrid.fromPixels(800, 600, IntArray(800 * 600) { i -> val x = i % 800 - 300; val y = i / 800 - 300
                    if (x * x + y * y < 90 * 90) 0xFF2050D0.toInt() else 0 }))
                doc.root.add(a); doc.root.add(t)
                return listOf(a, t)
            }
            run {
                val (doc, ed) = base()
                ed.convertToSmartObject(layers(doc))!!
                java.io.File(out, "06-smartobjekt-ebenen.psd").writeBytes(de.photographics.core.psd.PsdWriter.write(doc))
            }
            run {
                val (doc, ed) = base()
                val s = ed.convertToSmartObject(layers(doc))!!
                ed.commitGeometry(s, ed.geometryOf(s), Editor.Geometry(s.transform.then(Affine.around(400.0, 300.0, Affine.rotate(15.0).then(Affine.scale(1.3))))))
                java.io.File(out, "07-smartobjekt-gedreht-skaliert.psd").writeBytes(de.photographics.core.psd.PsdWriter.write(doc))
            }
            run {
                val (doc, ed) = base()
                val a = layers(doc).first()
                ed.convertToSmartObject(a as PixelLayer)!!
                java.io.File(out, "08-smartobjekt-bild.psd").writeBytes(de.photographics.core.psd.PsdWriter.write(doc))
            }
        } finally { de.photographics.core.psd.PsdWriter.photoshopSmartObjects = true }
    }
}
