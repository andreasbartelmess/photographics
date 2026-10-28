package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.doc.SmartLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.Affine
import de.photographics.core.image.Homography
import de.photographics.core.image.IntRect
import de.photographics.core.image.Resample
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.Compositor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PerspectiveTest {
    private fun px(g: TileGrid, x: Int, y: Int) = IntArray(1).also { g.readRect(IntRect(x, y, x + 1, y + 1), it) }[0]

    @Test
    fun homographyMapsCornersAndInverts() {
        val q = doubleArrayOf(10.0, 20.0, 310.0, 5.0, 290.0, 250.0, 30.0, 220.0)
        val h = Homography.rectToQuad(400.0, 300.0, q)
        assertArrayEquals(q, h.corners(400.0, 300.0), 1e-9)
        val inv = h.inverse()
        for ((x, y) in listOf(0.0 to 0.0, 123.0 to 45.0, 400.0 to 300.0)) {
            assertEquals(x, inv.mapX(h.mapX(x, y), h.mapY(x, y)), 1e-7); assertEquals(y, inv.mapY(h.mapX(x, y), h.mapY(x, y)), 1e-7)
        }
        assertTrue(Homography.isConvex(q))
        assertTrue(!Homography.isConvex(doubleArrayOf(0.0, 0.0, 10.0, 10.0, 10.0, 0.0, 0.0, 10.0)))
    }

    @Test
    fun projectiveEqualsAffineForParallelograms() {
        val w = 120; val h = 80
        val src = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> (0xFF shl 24) or ((i % w * 2) shl 16) or ((i / w * 3) shl 8) or 0x40 })
        val a = Affine.rotate(17.0).then(Affine.scale(1.3)).then(Affine.translate(60.0, 20.0))
        val viaAffine = Resample.renderAffine(src, a, 300, 250)
        val viaProj = Resample.renderProjective(src, Homography.of(a), 300, 250)
        var worst = 0
        for (y in 0 until 250 step 3) for (x in 0 until 300 step 3) {
            val p = px(viaAffine, x, y); val q = px(viaProj, x, y)
            for (s in intArrayOf(24, 16, 8, 0)) worst = maxOf(worst, abs(((p shr s) and 0xFF) - ((q shr s) and 0xFF)))
        }
        assertTrue("max $worst", worst <= 1)
    }

    /** Ein schräg „fotografiertes“ Schachbrett wird durch perspektivisches Freistellen wieder gerade. */
    @Test
    fun perspectiveCropRectifies() {
        val sw = 200; val sh = 120
        val board = IntArray(sw * sh) { i -> if ((i % sw / 20 + i / sw / 20) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val quad = doubleArrayOf(60.0, 40.0, 340.0, 70.0, 320.0, 230.0, 80.0, 260.0)
        val photo = Resample.renderProjective(TileGrid.fromPixels(sw, sh, board), Homography.rectToQuad(sw.toDouble(), sh.toDouble(), quad), 400, 300)
        val doc = Document(400, 300)
        val layer = PixelLayer("Foto", photo); doc.root.add(layer); doc.activeLayer = layer
        val ed = Editor(doc)
        ed.perspectiveCrop(quad, sw, sh)
        assertEquals(sw, doc.width); assertEquals(sh, doc.height)
        val g = (doc.root.children[0] as PixelLayer).pixels
        var wrong = 0; var n = 0
        for (y in 5 until sh - 5 step 2) for (x in 5 until sw - 5 step 2) {
            // Ränder der Felder auslassen (Interpolation)
            if (x % 20 in 18..19 || x % 20 in 0..1 || y % 20 in 18..19 || y % 20 in 0..1) continue
            val expect = board[y * sw + x] and 0xFF; val got = px(g, x, y) and 0xFF
            if (abs(expect - got) > 60) wrong++; n++
        }
        assertTrue("$wrong von $n falsch", wrong * 50 < n)
        ed.undo(); assertEquals(400, doc.width)
    }

    @Test
    fun warpedSmartObjectUndoPsdAndMask() {
        val doc = Document(300, 200)
        val g = TileGrid(300, 200); g.writeRect(IntRect(50, 50, 150, 130), IntArray(100 * 80) { 0xFF3366CC.toInt() })
        val layer = PixelLayer("E", g); doc.root.add(layer); doc.activeLayer = layer
        layer.mask = LayerMask(TileGrid(300, 200, 0).also { it.writeRect(IntRect(50, 50, 150, 130), IntArray(100 * 80) { 255 }) })
        val ed = Editor(doc)
        val smart = ed.convertToSmartObject(layer)!!
        val before = ed.geometryOf(smart)
        val q = smart.corners()
        // Rechte Kante nach unten-rechts ziehen (Verzerren)
        q[2] += 40.0; q[4] += 40.0; q[5] += 30.0
        ed.commitGeometry(smart, before, Editor.Geometry(before.transform, q))
        val r = Compositor.renderArgb(doc, doc.bounds)
        assertTrue("verzerrter Bereich gefüllt", (r[150 * 300 + 175] ushr 24) > 200)
        assertTrue("Maske folgt", px(smart.mask!!.grid, 175, 150) > 200)
        // PSD-Rundlauf behält die Ecken
        val back = PsdReader.read(PsdWriter.write(doc)).allLayers().first() as SmartLayer
        assertArrayEquals(q, back.warp, 1e-9)
        ed.undo()
        assertEquals(null, smart.warp)
        assertTrue((Compositor.renderArgb(doc, doc.bounds)[150 * 300 + 175] ushr 24) < 10)
    }

    /** Rückgängig nach Freistellen stellt auch die verzerrten Ecken wieder her (Verlauf baut Schnappschüsse um). */
    @Test
    fun warpSurvivesUndoOfDocumentChange() {
        val doc = Document(300, 200)
        val g = TileGrid(300, 200); g.writeRect(IntRect(50, 50, 150, 130), IntArray(100 * 80) { -1 })
        val layer = PixelLayer("E", g); doc.root.add(layer); doc.activeLayer = layer
        val ed = Editor(doc)
        val smart = ed.convertToSmartObject(layer)!!
        val before = ed.geometryOf(smart)
        val q = smart.corners(); q[4] += 30.0; q[5] += 20.0
        ed.commitGeometry(smart, before, Editor.Geometry(before.transform, q))
        ed.crop(IntRect(20, 20, 280, 180))
        ed.undo()
        assertArrayEquals(q, smart.warp, 1e-9)
    }
}
