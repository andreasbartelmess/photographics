package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorFeaturesTest {
    private fun px(g: TileGrid, x: Int, y: Int) = IntArray(1).also { g.readRect(IntRect(x, y, x + 1, y + 1), it) }[0]

    /** Arbeitsfläche erweitern: unterste Ebene bekommt die Hintergrundfarbe, Ebenen darüber bleiben transparent. */
    @Test
    fun canvasExtensionColor() {
        val doc = Document(40, 30)
        val bottom = PixelLayer("Hintergrund", TileGrid.fromPixels(40, 30, IntArray(40 * 30) { 0xFF336699.toInt() }))
        val top = PixelLayer("Ebene", TileGrid(40, 30))
        doc.root.add(bottom); doc.root.add(top)
        val ed = Editor(doc)
        ed.resizeCanvas(60, 50, 0.5f, 0.5f, extensionColor = 0xFFFFEE00.toInt())
        assertEquals(0xFFFFEE00.toInt(), px(bottom.pixels, 0, 0))
        assertEquals(0xFFFFEE00.toInt(), px(bottom.pixels, 59, 49))
        assertEquals(0xFF336699.toInt(), px(bottom.pixels, 30, 25))
        assertEquals(0, px(top.pixels, 0, 0))
        ed.undo()
        assertEquals(40, doc.width)
        assertEquals(0xFF336699.toInt(), px((doc.root.children[0] as PixelLayer).pixels, 0, 0))
    }

    private fun maskedDoc(): Triple<Document, Editor, PixelLayer> {
        val doc = Document(64, 64)
        val g = TileGrid(64, 64); g.writeRect(IntRect(10, 10, 20, 20), IntArray(100) { 0xFFFF0000.toInt() })
        val layer = PixelLayer("Ebene", g)
        doc.root.add(layer); doc.activeLayer = layer
        val m = TileGrid(64, 64, 0); m.writeRect(IntRect(10, 10, 20, 20), IntArray(100) { 255 })
        layer.mask = de.photographics.core.doc.LayerMask(m)
        return Triple(doc, Editor(doc), layer)
    }

    @Test
    fun linkedAndUnlinkedMaskMove() {
        val (_, ed, layer) = maskedDoc()
        ed.commitMove(layer, 5, 0)
        assertEquals(0xFFFF0000.toInt(), px(layer.pixels, 24, 15)); assertEquals(255, px(layer.mask!!.grid, 24, 15))
        ed.undo()
        ed.toggleMaskLink(layer)
        assertEquals(false, layer.mask!!.linked)
        // Nicht verbunden: Ebene allein
        ed.commitMove(layer, 5, 0, de.photographics.core.tools.PaintTarget.PIXELS)
        assertEquals(0xFFFF0000.toInt(), px(layer.pixels, 24, 15)); assertEquals(0, px(layer.mask!!.grid, 24, 15))
        // … und Maske allein
        ed.commitMove(layer, 0, 7, de.photographics.core.tools.PaintTarget.MASK)
        assertEquals(255, px(layer.mask!!.grid, 15, 25)); assertEquals(0, px(layer.pixels, 15, 25))
        ed.undo(); ed.undo(); ed.undo()
        assertEquals(true, layer.mask!!.linked)
    }

    @Test
    fun maskLinkSurvivesPsd() {
        val (doc, ed, layer) = maskedDoc()
        ed.toggleMaskLink(layer)
        val back = de.photographics.core.psd.PsdReader.read(de.photographics.core.psd.PsdWriter.write(doc))
        assertEquals(false, back.allLayers().first().mask!!.linked)
        ed.toggleMaskLink(layer)
        assertEquals(true, de.photographics.core.psd.PsdReader.read(de.photographics.core.psd.PsdWriter.write(doc)).allLayers().first().mask!!.linked)
    }

    /** Freies Transformieren eines Smartobjekts nimmt die verbundene Maske mit. */
    @Test
    fun linkedMaskFollowsTransform() {
        val (_, ed, layer) = maskedDoc()
        val smart = ed.convertToSmartObject(layer)!!
        val before = ed.transformOf(smart)
        ed.commitTransform(smart, before, before.then(de.photographics.core.image.Affine.translate(20.0, 0.0)))
        assertEquals(255, px(smart.mask!!.grid, 35, 15))
        assertEquals(0, px(smart.mask!!.grid, 15, 15))
        ed.undo()
        assertEquals(255, px(smart.mask!!.grid, 15, 15))
    }

    @Test
    fun clippingRules() {
        val doc = Document(32, 32)
        val ed = Editor(doc)
        val base = ed.addLayer("Basis"); val a = ed.addLayer("A"); val b = ed.addLayer("B")
        // Unterste Ebene kann nicht beschnitten werden
        assertEquals(false, ed.setClipping(base, true))
        assertEquals(true, ed.setClipping(a, true)); assertEquals(true, ed.setClipping(b, true))
        // Neue Ebene in der Schnittmaske gehört dazu
        doc.activeLayer = b
        val c = ed.addLayer("C")
        assertEquals(true, c.clipped)
        // Zurückwandeln von A löst A, B und C
        ed.setClipping(a, false)
        assertEquals(listOf(false, false, false, false), listOf(base, a, b, c).map { it.clipped })
        ed.undo()
        assertEquals(listOf(false, true, true, true), listOf(base, a, b, c).map { it.clipped })
    }

    /** Marker-Dokument: rotes Quadrat oben links, sonst Blau. */
    private fun markerDoc(): Pair<Document, Editor> {
        val doc = Document(200, 100)
        val g = TileGrid.fromPixels(200, 100, IntArray(200 * 100) { 0xFF2050C0.toInt() })
        g.writeRect(IntRect(10, 10, 30, 30), IntArray(400) { 0xFFFF0000.toInt() })
        val l = PixelLayer("Bild", g); doc.root.add(l); doc.activeLayer = l
        return doc to Editor(doc)
    }

    @Test
    fun rotateArbitraryGrowsCanvasAndFillsCorners() {
        val (doc, ed) = markerDoc()
        ed.rotateArbitrary(30.0, fill = 0xFFFFFFFF.toInt())
        // Breite = 200·cos30 + 100·sin30 ≈ 223,2; Höhe = 200·sin30 + 100·cos30 ≈ 186,6
        assertEquals(224, doc.width); assertEquals(187, doc.height)
        assertEquals(0xFFFFFFFF.toInt(), px((doc.root.children[0] as PixelLayer).pixels, 1, 1))
        ed.undo(); assertEquals(200, doc.width)
    }

    @Test
    fun rotatedCropStraightensContent() {
        val (doc, ed) = markerDoc()
        // 90° gedreht um die Mitte des Quadrats: der rote Marker liegt danach in der Mitte
        ed.cropRotated(20.0, 20.0, 40, 40, 90.0)
        assertEquals(40, doc.width); assertEquals(40, doc.height)
        val g = (doc.root.children[0] as PixelLayer).pixels
        assertEquals(0xFFFF0000.toInt(), px(g, 20, 20))
        assertEquals(0xFF2050C0.toInt() and 0xFFFFFF, px(g, 39, 20) and 0xFFFFFF)
    }

    @Test
    fun cropGeometry() {
        val (w, h) = de.photographics.core.editor.CropMath.maxInscribed(400.0, 300.0, 0.0)
        assertEquals(400.0, w, 1e-9); assertEquals(300.0, h, 1e-9)
        val (w2, h2) = de.photographics.core.editor.CropMath.maxInscribed(400.0, 300.0, 5.0)
        org.junit.Assert.assertTrue(w2 < 400 && h2 < 300 && w2 > 340 && h2 > 250)
        // Ecken des Ausschnitts liegen im gedrehten Bild
        val a = Math.toRadians(5.0)
        for ((sx, sy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
            val x = sx * w2 / 2; val y = sy * h2 / 2
            val u = x * kotlin.math.cos(a) + y * kotlin.math.sin(a); val v = -x * kotlin.math.sin(a) + y * kotlin.math.cos(a)
            org.junit.Assert.assertTrue(kotlin.math.abs(u) <= 200.0001 && kotlin.math.abs(v) <= 150.0001)
        }
        assertEquals(-10.0, de.photographics.core.editor.CropMath.straightenAngle(0.0, 0.0, 100.0, 100 * kotlin.math.tan(Math.toRadians(10.0))), 1e-6)
        // Nach rechts oben kippende, fast senkrechte Linie (−87°): 3° gegen den Uhrzeigersinn drehen
        assertEquals(-3.0, de.photographics.core.editor.CropMath.straightenAngle(0.0, 0.0, 100 * kotlin.math.tan(Math.toRadians(3.0)), -100.0), 1e-6)
    }

    @Test
    fun guidesFollowTransformsAndSurvivePsd() {
        val (doc, ed) = markerDoc()   // 200 × 100
        ed.setGuides("Hilfslinien", listOf(de.photographics.core.doc.Guide(true, 50.0), de.photographics.core.doc.Guide(false, 20.0)))
        ed.flip(true)
        assertEquals(listOf(150.0, 20.0), doc.guides.map { it.position })
        ed.rotate(true)   // 90° im UZS: senkrechte x → waagerechte y = x, waagerechte y → senkrechte x = h − y
        assertEquals(listOf(false to 150.0, true to 80.0), doc.guides.map { it.vertical to it.position })
        ed.resizeCanvas(140, 220, 0.5f, 0.5f)   // je 20 px mehr Rand
        assertEquals(listOf(false to 160.0, true to 100.0), doc.guides.map { it.vertical to it.position })
        val back = de.photographics.core.psd.PsdReader.read(de.photographics.core.psd.PsdWriter.write(doc))
        assertEquals(doc.guides, back.guides)
        ed.undo(); ed.undo(); ed.undo()
        assertEquals(listOf(true to 50.0, false to 20.0), doc.guides.map { it.vertical to it.position })
        ed.undo(); assertEquals(emptyList<de.photographics.core.doc.Guide>(), doc.guides)
    }

    @Test
    fun snappingToCanvasGuidesAndLayers() {
        val doc = Document(400, 300)
        val other = PixelLayer("Andere", TileGrid(400, 300).also { it.writeRect(IntRect(300, 50, 350, 120), IntArray(50 * 70) { -1 }) })
        val moving = PixelLayer("Bewegt", TileGrid(400, 300).also { it.writeRect(IntRect(10, 10, 60, 40), IntArray(50 * 30) { -1 }) })
        doc.root.add(other); doc.root.add(moving)
        doc.guides = listOf(de.photographics.core.doc.Guide(false, 200.0))
        val t = de.photographics.core.editor.Snap.targets(doc, moving)
        // Mitte (bei x = 195) rastet auf die Bildmitte 200 ein
        val r1 = de.photographics.core.editor.Snap.snap(IntRect(170, 150, 220, 180), t, 8.0)
        assertEquals(5.0, r1.dx, 1e-9); org.junit.Assert.assertTrue(r1.lines.any { it.vertical && it.pos == 200.0 })
        // Unterkante 196 → Hilfslinie 200; linke Kante 297 → Kante der anderen Ebene 300
        val r2 = de.photographics.core.editor.Snap.snap(IntRect(297, 166, 347, 196), t, 8.0)
        assertEquals(3.0, r2.dx, 1e-9); assertEquals(4.0, r2.dy, 1e-9)
        // Außerhalb der Toleranz bleibt alles
        val r3 = de.photographics.core.editor.Snap.snap(IntRect(100, 100, 130, 120), t, 4.0)
        assertEquals(0.0, r3.dx, 1e-9); assertEquals(0.0, r3.dy, 1e-9)
    }
}
