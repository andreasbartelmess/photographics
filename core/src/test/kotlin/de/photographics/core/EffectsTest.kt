package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.LayerEffects
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.BinaryWriter
import de.photographics.core.psd.PsdDescriptor
import de.photographics.core.psd.PsdEffects
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdTaggedBlock
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.Compositor
import de.photographics.core.render.Effects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {
    private fun rgb(r: Int, g: Int, b: Int) = PsdDescriptor("RGBC").apply { this["Rd  "] = r.toDouble(); this["Grn "] = g.toDouble(); this["Bl  "] = b.toDouble() }
    private fun pct(v: Double) = PsdDescriptor.UnitFloat("#Prc", v)
    private fun px(v: Double) = PsdDescriptor.UnitFloat("#Pxl", v)
    private fun mode(m: String) = PsdDescriptor.EnumValue("BlnM", m)

    /** lfx2-Block wie Photoshop: Version 0, Descriptor-Version 16, Descriptor. */
    private fun lfx2(d: PsdDescriptor): PsdTaggedBlock {
        val w = BinaryWriter(); w.i32(0); w.i32(16); PsdDescriptor.write(w, d)
        return PsdTaggedBlock("8BIM", "lfx2", w.toByteArray())
    }

    private fun effects(
        shadow: Boolean = false, stroke: Boolean = false, overlay: Boolean = false, innerShadow: Boolean = false,
    ) = PsdDescriptor("null").apply {
        this["Scl "] = pct(100.0); this["masterFXSwitch"] = true
        if (shadow) this["DrSh"] = PsdDescriptor("DrSh").apply {
            this["enab"] = true; this["Md  "] = mode("Mltp"); this["Clr "] = rgb(0, 0, 0); this["Opct"] = pct(100.0)
            this["uglg"] = false; this["lagl"] = PsdDescriptor.UnitFloat("#Ang", 90.0); this["Dstn"] = px(12.0)
            this["Ckmt"] = px(100.0); this["blur"] = px(0.0)
        }
        if (stroke) this["FrFX"] = PsdDescriptor("FrFX").apply {
            this["enab"] = true; this["Styl"] = PsdDescriptor.EnumValue("FStl", "OutF"); this["PntT"] = PsdDescriptor.EnumValue("FrFl", "SClr")
            this["Md  "] = mode("Nrml"); this["Opct"] = pct(100.0); this["Sz  "] = px(4.0); this["Clr "] = rgb(255, 0, 0)
        }
        if (overlay) this["SoFi"] = PsdDescriptor("SoFi").apply {
            this["enab"] = true; this["Md  "] = mode("Nrml"); this["Opct"] = pct(100.0); this["Clr "] = rgb(0, 200, 0)
        }
        if (innerShadow) this["IrSh"] = PsdDescriptor("IrSh").apply {
            this["enab"] = true; this["Md  "] = mode("Nrml"); this["Clr "] = rgb(0, 0, 255); this["Opct"] = pct(100.0)
            this["uglg"] = false; this["lagl"] = PsdDescriptor.UnitFloat("#Ang", 90.0); this["Dstn"] = px(6.0)
            this["Ckmt"] = px(100.0); this["blur"] = px(0.0)
        }
    }

    /** Weißer Hintergrund, darauf ein grauer Würfel 40..80 × 40..80 mit den Effekten. */
    private fun doc(fx: PsdDescriptor): Pair<Document, PixelLayer> {
        val w = 120; val h = 120
        val doc = Document(w, h)
        doc.root.add(PixelLayer("Hintergrund", TileGrid.fromPixels(w, h, IntArray(w * h) { -1 })))
        val sq = PixelLayer("Würfel", TileGrid.fromPixels(w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 40 until 80 && y in 40 until 80) 0xFF808080.toInt() else 0 }))
        sq.preservedBlocks = listOf(lfx2(fx))
        doc.root.add(sq)
        return doc to sq
    }

    private fun at(img: IntArray, w: Int, x: Int, y: Int) = img[y * w + x] and 0xFFFFFF

    @Test
    fun parsesDescriptor() {
        val fx = PsdEffects.fromDescriptor(effects(shadow = true, stroke = true, overlay = true))
        assertEquals(1, fx.dropShadows.size)
        assertEquals(12.0, fx.dropShadows[0].distance, 1e-9)
        assertEquals(1.0, fx.dropShadows[0].spread, 1e-9)
        assertEquals(LayerEffects.StrokePosition.OUTSIDE, fx.strokes[0].position)
        assertEquals(0xFF0000, fx.strokes[0].color)
        assertEquals(0x00C800, fx.colorOverlays[0].color)
        assertTrue(fx.visible)
    }

    @Test
    fun dropShadowFallsAwayFromLight() {
        val (doc, _) = doc(effects(shadow = true))
        val img = Compositor.renderArgb(doc, doc.bounds)
        // Licht von oben (90°): Schatten 12 px nach unten versetzt, harte Kante (Größe 0)
        assertEquals("unter dem Würfel dunkel", 0x000000, at(img, 120, 60, 86))
        assertEquals("über dem Würfel hell", 0xFFFFFF, at(img, 120, 60, 34))
        assertEquals("Würfel selbst grau", 0x808080, at(img, 120, 60, 60))
        assertEquals("seitlich hell", 0xFFFFFF, at(img, 120, 30, 60))
    }

    @Test
    fun strokeAndOverlay() {
        val (doc, _) = doc(effects(stroke = true, overlay = true))
        val img = Compositor.renderArgb(doc, doc.bounds)
        assertEquals("Kontur 2 px außerhalb rot", 0xFF0000, at(img, 120, 38, 60))
        assertEquals("6 px außerhalb unberührt", 0xFFFFFF, at(img, 120, 34, 60))
        assertEquals("Farbüberlagerung im Würfel", 0x00C800, at(img, 120, 60, 60))
    }

    @Test
    fun innerShadowAtLitEdge() {
        val (doc, _) = doc(effects(innerShadow = true))
        val img = Compositor.renderArgb(doc, doc.bounds)
        // Licht von oben: der Schatten innen liegt an der Oberkante
        assertEquals(0x0000FF, at(img, 120, 60, 42))
        assertEquals(0x808080, at(img, 120, 60, 70))
    }

    @Test
    fun previewMatchesAtLowerResolution() {
        val (doc, _) = doc(effects(shadow = true, stroke = true))
        Compositor.prepare(doc, 2)
        val img = Compositor.renderArgbSampled(doc, IntRect(0, 0, 60, 60), 2)
        assertEquals(0x000000, at(img, 60, 30, 43))
        assertEquals(0xFF0000, at(img, 60, 19, 30))
    }

    @Test
    fun survivesPsdRoundTripAndMergeBakesOnce() {
        val (doc, _) = doc(effects(shadow = true, stroke = true))
        val back = PsdReader.read(PsdWriter.write(doc))
        val fx = back.root.children[1].effects
        assertNotNull(fx)
        assertEquals(1, fx!!.dropShadows.size)
        val before = Compositor.renderArgb(back, back.bounds)
        // Zusammenfügen rechnet die Effekte in die Pixel ein – danach keine Effekte mehr an der Ebene
        val ed = Editor(back)
        back.activeLayer = back.root.children[1]
        assertTrue(ed.mergeDown())
        assertEquals(1, back.root.children.size)
        assertTrue(back.root.children[0].effects == null)
        val after = Compositor.renderArgb(back, back.bounds)
        var diff = 0
        for (i in before.indices) for (s in intArrayOf(16, 8, 0)) diff = maxOf(diff, kotlin.math.abs(((before[i] shr s) and 0xFF) - ((after[i] shr s) and 0xFF)))
        assertTrue("Abweichung $diff", diff <= 1)
    }

    @Test
    fun distanceTransformIsEuclidean() {
        val w = 41; val h = 41
        val a = FloatArray(w * h); a[20 * w + 20] = 1f
        val d = Effects.distanceToInside(a, w, h)
        assertEquals(0f, d[20 * w + 20], 1e-4f)
        assertEquals(5f, d[20 * w + 25], 1e-4f)
        assertEquals(5f, d[24 * w + 23], 1e-4f)   // 3-4-5
    }

    @Test
    fun cacheMatchesDirectAndFollowsChanges() {
        val (doc, sq) = doc(effects(shadow = true, stroke = true, innerShadow = true))
        val direct = Compositor.renderArgb(doc, doc.bounds)
        Compositor.prepare(doc, 1, doc.bounds)
        val cached = Compositor.renderArgb(doc, doc.bounds)
        fun maxDiff(x: IntArray, y: IntArray): Int {
            var d = 0
            for (i in x.indices) for (sh in intArrayOf(24, 16, 8, 0)) d = maxOf(d, kotlin.math.abs(((x[i] shr sh) and 0xFF) - ((y[i] shr sh) and 0xFF)))
            return d
        }
        assertTrue("Zwischenspeicher weicht ab: ${maxDiff(direct, cached)}", maxDiff(direct, cached) <= 2)
        // Ebene ändern: der Zwischenspeicher darf nicht den alten Stand liefern
        sq.pixels.writeRect(IntRect(10, 10, 30, 30), IntArray(400) { 0xFF808080.toInt() })
        Compositor.prepare(doc, 1, doc.bounds)
        val after = Compositor.renderArgb(doc, doc.bounds)
        assertEquals("Kontur um das neue Quadrat", 0xFF0000, at(after, 120, 8, 20))
        val fresh = Compositor.renderArgb(doc.also { }, doc.bounds)
        assertTrue(maxDiff(after, fresh) <= 2)
    }

    @Test
    fun bevelSatinGradientRoundTrip() {
        val fx = LayerEffects(
            bevel = LayerEffects.Bevel(style = LayerEffects.BevelStyle.EMBOSS, depth = 2.5, up = false, size = 9.0, soften = 2.0,
                angle = 45.0, useGlobalAngle = false, altitude = 40.0, highlightColor = 0xFFEEDD, highlightOpacity = 0.6f,
                shadowColor = 0x112233, shadowOpacity = 0.4f),
            satin = LayerEffects.Satin(color = 0x334455, opacity = 0.3f, angle = 30.0, distance = 7.0, size = 5.0, invert = false),
            gradientOverlay = LayerEffects.GradientOverlay(color0 = 0xFF0000, color1 = 0x0000FF, opacity = 0.8f, angle = 45.0, radial = true, reverse = true, scale = 0.5),
            dropShadows = listOf(LayerEffects.Shadow(0, 0.5f, de.photographics.core.render.BlendMode.MULTIPLY, 120.0, true, 5.0, 0.0, 5.0)),
        )
        val back = PsdEffects.parse(listOf(PsdEffects.toBlock(fx)))!!
        assertEquals(fx.bevel, back.bevel)
        assertEquals(fx.satin, back.satin)
        assertEquals(fx.gradientOverlay, back.gradientOverlay)
        assertEquals(fx.dropShadows, back.dropShadows)
        assertTrue(back.unsupported.isEmpty())
        // Photoshop-Reihenfolge der Schlüssel: Hauptschalter zuerst; Schatten mit Konturkurve
        val d = PsdDescriptor.read(de.photographics.core.psd.BinaryReader(PsdEffects.toBlock(fx).data).also { it.i32(); it.i32() })
        assertEquals("masterFXSwitch", d.items.keys.first())
        assertNotNull(d.descriptor("DrSh")!!.descriptor("TrnS"))
        // Dargestellt: Relief hellt/verdunkelt die Kanten, Verlauf färbt die Fläche
        val (doc, sq) = doc(PsdDescriptor("null"))
        sq.preservedBlocks = listOf(PsdEffects.toBlock(fx))
        val img = Compositor.renderArgb(doc, doc.bounds)
        assertTrue("Verlauf statt Grau in der Fläche", at(img, 120, 60, 60) != 0x808080)
    }
}
