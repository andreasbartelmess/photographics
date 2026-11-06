package de.photographics.core

import de.photographics.core.doc.*
import de.photographics.core.editor.Editor
import de.photographics.core.filters.FilterCatalog
import de.photographics.core.filters.FilterRunner
import de.photographics.core.filters.Gallery
import de.photographics.core.filters.GalleryEffect
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import org.junit.Assert.*
import org.junit.Test

class SmartFilterTest {
    private val w = 300; private val h = 200

    private fun setup(): Triple<Document, Editor, SmartLayer> {
        val doc = Document(w, h)
        val px = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (x in 60 until 240 && y in 40 until 160) (0xFF shl 24) or ((x * 3 and 0xFF) shl 16) or ((y * 5 and 0xFF) shl 8) or ((x xor y) and 0xFF) else 0 })
        val l = PixelLayer("Bild", px); doc.root.add(l); doc.activeLayer = l
        val ed = Editor(doc)
        val smart = ed.convertToSmartObject(l)!!
        return Triple(doc, ed, smart)
    }

    private fun pixels(g: TileGrid) = IntArray(w * h).also { g.readRect(IntRect(0, 0, w, h), it) }

    /** Dasselbe wie der Filter direkt auf einer Pixelebene mit dem transformierten Inhalt. */
    private fun direct(base: TileGrid, id: String, values: FloatArray): IntArray {
        val doc = Document(w, h); val l = PixelLayer("x", base.copy()); doc.root.add(l)
        FilterRunner.apply(doc, l, FilterCatalog.byId(id)!!.build(values))
        return pixels(l.pixels)
    }

    @Test fun filterMatchesDirectApplication() {
        val (_, ed, smart) = setup()
        val base = pixels(smart.raster)
        val blur = floatArrayOf(6f)
        ed.addSmartFilter(smart, SmartFilter.of("gaussian-blur", blur))
        val want = direct(TileGrid.fromPixels(w, h, base), "gaussian-blur", blur)
        assertArrayEquals(want, pixels(smart.raster))
        // Ausschalten (einzeln und über das Augensymbol) zeigt wieder das Original
        ed.updateSmartFilter(smart, 0, smart.smartFilters.filters[0].copy(enabled = false))
        assertArrayEquals(base, pixels(smart.raster))
        ed.undo()
        ed.setSmartFiltersVisible(smart, false)
        assertArrayEquals(base, pixels(smart.raster))
        ed.undo(); assertArrayEquals(want, pixels(smart.raster))
        // Rückgängig entfernt den Filter, Wiederholen bringt ihn zurück
        ed.undo(); assertArrayEquals(base, pixels(smart.raster))
        ed.redo(); assertArrayEquals(want, pixels(smart.raster))
    }

    @Test fun opacityOrderAndMask() {
        val (doc, ed, smart) = setup()
        val base = pixels(smart.raster)
        ed.addSmartFilter(smart, SmartFilter.of("adjust:INVERT", floatArrayOf()).copy(opacity = 0.5f))
        val half = pixels(smart.raster)
        val i = 100 * w + 150
        val o = base[i]; val r = half[i]
        // Halbe Deckkraft: jeder Kanal liegt zwischen Original und Umkehrung (≈ 127/128)
        for (s in intArrayOf(16, 8, 0)) assertEquals(127.5, ((r shr s) and 0xFF).toDouble(), 1.0)
        assertEquals(o ushr 24, r ushr 24)
        // Reihenfolge: erst Weichzeichnen, dann Mosaik ≠ erst Mosaik, dann Weichzeichnen
        ed.removeSmartFilter(smart, 0)
        ed.addSmartFilter(smart, SmartFilter.of("gaussian-blur", floatArrayOf(4f)))
        ed.addSmartFilter(smart, SmartFilter.of("mosaic", floatArrayOf(12f)))
        val a = pixels(smart.raster)
        ed.moveSmartFilter(smart, 1, 0)
        val b = pixels(smart.raster)
        assertFalse(a.contentEquals(b))
        // Filtermaske: links schwarz → dort das Original
        ed.setFilterMask(smart, true)
        val m = smart.smartFilters.mask!!
        for (y in 0 until h) for (x in 0 until w / 2) m.grid[x, y] = 0
        smart.invalidate()
        val masked = pixels(smart.raster)
        val left = 100 * w + 80; val right = 100 * w + 200
        assertEquals(base[left], masked[left])
        assertEquals(b[right], masked[right])
        assertNotNull(doc)
    }

    @Test fun rasterBelowUsesOnlyLowerFilters() {
        val (_, ed, smart) = setup()
        val base = pixels(smart.raster)
        ed.addSmartFilter(smart, SmartFilter.of("gaussian-blur", floatArrayOf(4f)))
        val blurred = pixels(smart.raster)
        ed.addSmartFilter(smart, SmartFilter.of("mosaic", floatArrayOf(12f)))
        assertArrayEquals(base, pixels(smart.rasterBelow(0)))
        assertArrayEquals(blurred, pixels(smart.rasterBelow(1)))
        assertArrayEquals(pixels(smart.raster), pixels(smart.rasterBelow(2)))
    }

    @Test fun paintingFilterMaskMatchesFullRecomputeAndUndoes() {
        val (_, ed, smart) = setup()
        ed.addSmartFilter(smart, SmartFilter.of("adjust:INVERT", floatArrayOf()))
        val inverted = pixels(smart.raster)
        ed.setFilterMask(smart, true)
        // Schwarzer Pinsel auf der Filtermaske: dort wieder das Original
        val st = ed.beginStroke(de.photographics.core.tools.BrushTool(0xFF000000.toInt()),
            de.photographics.core.tools.BrushSettings(size = 40f, hardness = 1f), de.photographics.core.tools.PaintTarget.FILTER_MASK)!!
        st.addPoint(100f, 100f); st.addPoint(180f, 100f)
        ed.endStroke(st)
        val painted = pixels(smart.raster)
        smart.invalidate()
        assertArrayEquals(pixels(smart.raster), painted)   // schrittweise == ganz neu gerechnet
        val i = 100 * w + 140
        assertNotEquals(inverted[i], painted[i])
        assertEquals(inverted[150 * w + 70], painted[150 * w + 70])
        ed.undo(); assertArrayEquals(inverted, pixels(smart.raster))
        ed.redo(); assertArrayEquals(painted, pixels(smart.raster))
        // Umkehren: jetzt wirkt der Filter nur im bemalten Streifen
        ed.invertFilterMask(smart)
        val inv = pixels(smart.raster)
        assertEquals(inverted[i], inv[i])
        assertNotEquals(inverted[150 * w + 70], inv[150 * w + 70])
    }

    @Test fun firstFilterWithSelectionGetsFilterMask() {
        val (doc, ed, smart) = setup()
        val base = pixels(smart.raster)
        doc.selection = de.photographics.core.selection.Selection.rect(w, h, 150f, 0f, w.toFloat(), h.toFloat())
        ed.addSmartFilter(smart, SmartFilter.of("adjust:INVERT", floatArrayOf()))
        assertNotNull(smart.smartFilters.mask)
        val r = pixels(smart.raster)
        assertEquals(base[100 * w + 100], r[100 * w + 100])
        assertNotEquals(base[100 * w + 200], r[100 * w + 200])
        // Weitere Filter übernehmen die vorhandene Maske
        val m = smart.smartFilters.mask
        ed.addSmartFilter(smart, SmartFilter.of("gaussian-blur", floatArrayOf(2f)))
        assertSame(m, smart.smartFilters.mask)
    }

    @Test fun psdRoundTripKeepsFilters() {
        val (doc, ed, smart) = setup()
        val effect = GalleryEffect(Gallery.byId("watercolor")!!, Gallery.byId("watercolor")!!.params.map { it.default }.toFloatArray())
        ed.addSmartFilter(smart, SmartFilter.of("unsharp-mask", floatArrayOf(120f, 2f, 1f)).copy(blendMode = BlendMode.LUMINOSITY, opacity = 0.7f))
        ed.addSmartFilter(smart, SmartFilter.gallery(listOf(effect), 0xFF112233.toInt(), 0xFFEEDDCC.toInt()).copy(enabled = false))
        ed.setFilterMask(smart, true)
        smart.smartFilters.mask!!.grid[10, 10] = 77
        val before = pixels(smart.raster)
        val bytes = de.photographics.core.psd.PsdWriter.write(doc)
        val back = de.photographics.core.psd.PsdReader.read(bytes)
        val s2 = back.root.children.single() as SmartLayer
        val sf = s2.smartFilters
        assertEquals(2, sf.filters.size)
        assertEquals(smart.smartFilters.filters[0], sf.filters[0])
        assertEquals("watercolor", sf.filters[1].gallery!!.single().spec.id)
        assertFalse(sf.filters[1].enabled)
        assertEquals(0xFF112233.toInt(), sf.filters[1].galleryFg)
        assertEquals(77, sf.mask!!.grid[10, 10]); assertEquals(255, sf.mask!!.grid[200, 100])
        // Gespeichertes Raster und neu gerechnetes stimmen überein
        assertArrayEquals(before, pixels(s2.raster))
        s2.invalidate()
        assertArrayEquals(before, pixels(s2.raster))
    }

    @Test fun unknownFilterIsSkipped() {
        val (_, ed, smart) = setup()
        val base = pixels(smart.raster)
        ed.addSmartFilter(smart, SmartFilter("aus-einer-neueren-version", listOf(1f)))
        assertArrayEquals(base, pixels(smart.raster))
        assertEquals("aus-einer-neueren-version", smart.smartFilters.filters[0].label)
    }

    /** Testdokument für Sichttests: PG_SMART_PSD=datei.psd. */
    @Test fun writeFixture() {
        val out = System.getenv("PG_SMART_PSD")?.takeIf { it.isNotBlank() } ?: return
        val W = 1200; val H = 800
        val doc = Document(W, H)
        doc.root.add(PixelLayer("Hintergrund", TileGrid.fromPixels(W, H, IntArray(W * H) { i -> (0xFF shl 24) or ((i % W * 255 / W) shl 16) or 0x3060 })))
        val px = TileGrid.fromPixels(W, H, IntArray(W * H) { i -> val x = i % W; val y = i / W
            if (x in 200 until 1000 && y in 150 until 650) (0xFF shl 24) or (((x / 40 + y / 40) % 2 * 200) shl 8) or (x * 255 / W) else 0 })
        val l = PixelLayer("Motiv", px); doc.root.add(l); doc.activeLayer = l
        val ed = Editor(doc)
        val smart = ed.convertToSmartObject(l)!!
        smart.name = "Motiv (Smartobjekt)"
        ed.addSmartFilter(smart, SmartFilter.of("gaussian-blur", floatArrayOf(5f)))
        ed.addSmartFilter(smart, SmartFilter.of("mosaic", floatArrayOf(30f)).copy(blendMode = BlendMode.LUMINOSITY, opacity = 0.6f))
        ed.setFilterMask(smart, true)
        for (y in 0 until H) for (x in 0 until W / 3) smart.smartFilters.mask!!.grid[x, y] = 0
        smart.invalidate()
        java.io.File(out).writeBytes(de.photographics.core.psd.PsdWriter.write(doc))
    }
}
