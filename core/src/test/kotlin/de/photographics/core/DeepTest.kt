package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.editor.Gradient
import de.photographics.core.editor.GradientType
import de.photographics.core.image.IntRect
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.Compositor
import de.photographics.core.selection.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16 Bit je Kanal: Moduswechsel, stufenloser Verlauf, Korrekturen ohne Banding, PSD-Rundlauf. */
class DeepTest {
    /** 16-Bit-Werte des Rots einer Zeile (obere · 256 + untere Bits). */
    private fun red16(l: PixelLayer, y: Int, w: Int): IntArray = IntArray(w) { x -> (((l.pixels[x, y] shr 16) and 0xFF) shl 8) or ((l.low!![x, y] shr 16) and 0xFF) }

    /** Sehr flacher Verlauf (Rot 100 → 104 über 1000 px): in 8 Bit nur 5 Stufen, in 16 Bit fast jedes Pixel anders. */
    private fun setup(): Pair<Editor, PixelLayer> {
        val e = Editor(Document.blank(1000, 4, 0xFF000000.toInt()))
        e.setBitDepth(16)
        val l = e.doc.root.children[0] as PixelLayer
        e.fillGradient(de.photographics.core.tools.PaintTarget.PIXELS, Gradient(GradientType.LINEAR, 0f, 0f, 1000f, 0f, 0xFF640000.toInt(), 0xFF680000.toInt()), 1f)
        return e to l
    }

    /** 8-Bit-Werkzeuge auf 16-Bit-Ebenen: Gelöschtes ist ganz durchsichtig (keine Reste im unteren Byte). */
    @Test
    fun erasedAreaStaysTransparentIn16Bit() {
        val (e, l) = setup()
        e.setSelection(Selection.rect(1000, 4, 0f, 0f, 500f, 4f))
        e.clearSelection()
        val px = Compositor.renderArgb(e.doc, IntRect(0, 0, 1000, 4))
        assertEquals("gelöscht", 0, px[1000 + 10] ushr 24)
        assertEquals("unverändert", 255, px[1000 + 900] ushr 24)
        e.setSelection(null)
        e.setBitDepth(8)
        assertEquals(0, l.pixels[10, 1] ushr 24)
        assertEquals(IntRect(500, 0, 1000, 4), l.pixels.contentBounds())
    }

    @Test
    fun modeSwitchAndSmoothGradient() {
        val (e, l) = setup()
        assertEquals(16, e.doc.bitDepth)
        val r = red16(l, 1, 1000)
        assertTrue("mehr als 5 Stufen: ${r.toSet().size}", r.toSet().size > 500)
        assertTrue(kotlin.math.abs(r[0] - 100 * 257) < 60 && kotlin.math.abs(r[999] - 104 * 257) < 60)
        // Undo/Redo nimmt die unteren Bits mit
        e.undo()
        assertEquals(0, (l.pixels[500, 1] shr 16) and 0xFF)
        e.redo()
        assertTrue(red16(l, 1, 1000).toSet().size > 500)
        // Zurück nach 8 Bit: gerundet, untere Bits weg
        e.setBitDepth(8)
        assertNull(l.low)
        assertEquals(102, (l.pixels[500, 1] shr 16) and 0xFF)
        e.undo()
        assertNotNull(l.low)
    }

    @Test
    fun adjustmentsKeepPrecision() {
        val (e, l) = setup()
        // Starke Tonwertkorrektur (Kontrast ×20) als Einstellungsebene: 8 Bit würde in Stufen zerfallen
        e.addAdjustmentLayer(de.photographics.core.adjust.LevelsAdj(inBlack = 98, inWhite = 106))
        val f = Compositor.render(e.doc, IntRect(0, 1, 1000, 2))
        val reds = (0 until 1000).map { (f[it * 4] * 65535).toInt() }.toSet()
        assertTrue("stufenlos nach der Korrektur: ${reds.size}", reds.size > 300)
        // Direkt angewendet ebenfalls in 16 Bit
        val (tx, _) = de.photographics.core.filters.FilterRunner.apply(e.doc, l, de.photographics.core.filters.Levels(98, 106, 1f, 0, 255))
        e.commitFilter(tx, "Tonwert")
        assertTrue(red16(l, 1, 1000).toSet().size > 300)
    }

    @Test
    fun psdRoundTrip16() {
        val (e, l) = setup()
        e.setSelection(Selection.rect(1000, 4, 0f, 0f, 500f, 4f))
        e.saveSelectionAsChannel("Halb")
        val before = red16(l, 2, 1000)
        val back = PsdReader.read(PsdWriter.write(e.doc))
        assertEquals(16, back.bitDepth)
        val bl = back.root.children.filterIsInstance<PixelLayer>().single()
        assertNotNull(bl.low)
        assertTrue(before.contentEquals(red16(bl, 2, 1000)))
        assertEquals(255, back.channels.single().grid[100, 1])
        // Zusammengeführtes Bild ebenfalls 16 Bit: Reader ohne Ebenen liest es
        val flat = PsdReader.read(java.nio.ByteBuffer.wrap(PsdWriter.write(e.doc)))
        assertEquals(16, flat.bitDepth)
    }

    @Test
    fun mergeAndFlattenStayDeep() {
        val (e, _) = setup()
        e.addLayer()
        e.flatten()
        val f = e.doc.root.children.single() as PixelLayer
        assertNotNull(f.low)
        assertTrue(red16(f, 1, 1000).toSet().size > 500)
    }

    @Test
    fun tiffRoundTrip16() {
        val (e, l) = setup()
        val before = red16(l, 2, 1000)
        val dir = java.nio.file.Files.createTempDirectory("pgtiff").toFile()
        val out = java.io.ByteArrayOutputStream()
        de.photographics.core.tiff.TiffWriter.write(e.doc, out, dir, layers = false)
        val back = de.photographics.core.tiff.TiffReader.read(java.nio.ByteBuffer.wrap(out.toByteArray()))
        assertEquals(16, back.bitDepth)
        val bl = back.root.children.filterIsInstance<PixelLayer>().single()
        val after = red16(bl, 2, 1000)
        // Gesamtbild aus dem Compositor: höchstens Rundung (±1 von 65535)
        assertTrue((0 until 1000).all { kotlin.math.abs(after[it] - before[it]) <= 1 })
        dir.deleteRecursively()
    }
}
