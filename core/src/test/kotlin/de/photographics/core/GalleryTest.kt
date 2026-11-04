package de.photographics.core

import de.photographics.core.filters.Gallery
import de.photographics.core.filters.GalleryChain
import de.photographics.core.filters.GalleryContext
import de.photographics.core.filters.GalleryEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtergalerie: jeder Effekt läuft, behält Transparenz und rechnet streifenweise nahtlos. */
class GalleryTest {
    /** Farbverlauf mit Kreis und Kanten – genug Struktur für alle Effekte. */
    private fun sample(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val inCircle = (x - w / 2) * (x - w / 2) + (y - h / 2) * (y - h / 2) < (h / 3) * (h / 3)
        val a = if (x < 4) 0 else 255
        (a shl 24) or ((x * 255 / w) shl 16) or ((if (inCircle) 220 else y * 255 / h) shl 8) or (if ((x / 16 + y / 16) % 2 == 0) 40 else 200)
    }

    @Test
    fun everyEffectRunsAndKeepsAlpha() {
        val w = 96; val h = 64
        val src = sample(w, h)
        assertTrue(Gallery.specs.size >= 40)
        assertEquals("eindeutige Kennungen", Gallery.specs.size, Gallery.specs.map { it.id }.toSet().size)
        for (s in Gallery.specs) {
            val out = GalleryChain(listOf(GalleryEffect(s, s.defaults()))).process(src.copyOf(), w, h, 0, 0, w, h)
            assertEquals(s.id, src.size, out.size)
            for (i in src.indices) assertEquals("${s.id}: Alpha", src[i] ushr 24, out[i] ushr 24)
            assertTrue("${s.id}: verändert das Bild", out.indices.any { out[it] != src[it] })
        }
    }

    @Test
    fun bandsAreSeamless() {
        val w = 120; val h = 200
        val src = sample(w, h)
        val ctx = GalleryContext(0xFF102030.toInt(), 0xFFF0E0D0.toInt())
        for (s in Gallery.specs) {
            val chain = GalleryChain(listOf(GalleryEffect(s, s.defaults())), ctx)
            val whole = chain.process(src.copyOf(), w, h, 0, 0, w, h)
            // Untere Hälfte mit Rand getrennt rechnen, wie der FilterRunner
            val m = chain.margin
            val top = h / 2
            val readTop = maxOf(0, top - m)
            val part = src.copyOfRange(readTop * w, h * w)
            val out = chain.process(part, w, h - readTop, 0, readTop, w, h)
            // Bis auf Rundung (laufende Summen des Weichzeichners) gleich; Schwellen dürfen ganz
            // vereinzelt kippen
            var flips = 0; var n = 0
            for (y in top until h - m) for (x in m until w - m) {
                val a = whole[y * w + x]; val b = out[(y - readTop) * w + x]
                val d = maxOf(kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)), kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), kotlin.math.abs((a and 0xFF) - (b and 0xFF)))
                if (d > 2) flips++
                n++
            }
            assertTrue("${s.id}: $flips von $n Pixeln weichen ab", flips <= n / 500)
        }
    }

    @Test
    fun stackAppliesInOrderAndSkipsHidden() {
        val w = 64; val h = 48
        val src = sample(w, h)
        val a = Gallery.byId("cutout")!!; val b = Gallery.byId("glowing-edges")!!
        val ab = GalleryChain(listOf(GalleryEffect(a, a.defaults()), GalleryEffect(b, b.defaults()))).process(src.copyOf(), w, h, 0, 0, w, h)
        val ba = GalleryChain(listOf(GalleryEffect(b, b.defaults()), GalleryEffect(a, a.defaults()))).process(src.copyOf(), w, h, 0, 0, w, h)
        assertTrue("Reihenfolge zählt", !ab.contentEquals(ba))
        val hidden = GalleryChain(listOf(GalleryEffect(a, a.defaults(), visible = false)))
        assertTrue(hidden.isEmpty)
    }

    /**
     * Galerie-Vorschau „ganzes Bild“ (volle Auflösung, danach gemittelt) = Ergebnis von „Anwenden“,
     * verkleinert – auch bei Effekten mit Pixelstruktur, die verkleinert gerechnet anders aussähen.
     */
    @Test
    fun scaledPreviewMatchesAppliedResult() {
        val w = 300; val h = 200; val k = 4
        for (id in listOf("graphic-pen", "glowing-edges", "stained-glass", "stamp")) {
            val doc = de.photographics.core.doc.Document(w, h)
            val layer = de.photographics.core.doc.PixelLayer("A", de.photographics.core.image.TileGrid.fromPixels(w, h, sample(w, h).map { it or 0xFF000000.toInt() }.toIntArray()))
            doc.root.add(layer)
            val spec = Gallery.byId(id)!!
            val chain = GalleryChain(listOf(GalleryEffect(spec, spec.defaults())), GalleryContext(0xFF102030.toInt(), 0xFFF0E0D0.toInt()))
            val preview = de.photographics.core.filters.FilterRunner.previewScaled(doc, layer, chain, w / k, h / k)!!
            de.photographics.core.filters.FilterRunner.apply(doc, layer, chain)
            val out = IntArray(w * h).also { layer.pixels.readRect(doc.bounds, it) }
            for (y in 0 until h / k) for (x in 0 until w / k) {
                val p = preview[y * (w / k) + x]
                for (sh in intArrayOf(16, 8, 0)) {
                    var sum = 0
                    for (yy in 0 until k) for (xx in 0 until k) sum += (out[(y * k + yy) * w + x * k + xx] shr sh) and 0xFF
                    assertEquals("$id ($x,$y)", sum / (k * k).toFloat(), ((p shr sh) and 0xFF).toFloat(), 1.01f)
                }
            }
        }
    }

    @Test
    fun scaledPreviewCanBeCancelled() {
        val w = 64; val h = 64
        val doc = de.photographics.core.doc.Document(w, h)
        val layer = de.photographics.core.doc.PixelLayer("A", de.photographics.core.image.TileGrid.fromPixels(w, h, sample(w, h)))
        doc.root.add(layer)
        val s = Gallery.specs.first()
        assertEquals(null, de.photographics.core.filters.FilterRunner.previewScaled(doc, layer, GalleryChain(listOf(GalleryEffect(s, s.defaults()))), 16, 16) { true })
    }

    /** Kontaktblatt aller Effekte (für die Entwicklung): PG_GALLERY_SHEET=bild.jpg:ausgabe.png */
    @Test
    fun contactSheet() {
        val arg = System.getenv("PG_GALLERY_SHEET")?.takeIf { it.isNotBlank() } ?: return
        val (inPath, outPath) = arg.split(':')
        val src = javax.imageio.ImageIO.read(java.io.File(inPath))
        val tw = 360; val th = src.height * tw / src.width
        val small = java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply { setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC); drawImage(src, 0, 0, tw, th, null); dispose() }
        val px = small.getRGB(0, 0, tw, th, null, 0, tw)
        val ctx = GalleryContext(scale = tw.toFloat() / src.width * 2.5f)
        val cols = 6; val rows = (Gallery.specs.size + cols - 1) / cols
        val sheet = java.awt.image.BufferedImage(cols * (tw + 8), rows * (th + 28), java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = java.awt.Color(40, 40, 44); g.fillRect(0, 0, sheet.width, sheet.height)
        for ((k, s) in Gallery.specs.withIndex()) {
            val out = GalleryChain(listOf(GalleryEffect(s, s.defaults())), ctx).process(px.copyOf(), tw, th, 0, 0, tw, th)
            val im = java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            im.setRGB(0, 0, tw, th, out, 0, tw)
            val x = (k % cols) * (tw + 8); val y = (k / cols) * (th + 28)
            g.drawImage(im, x, y + 22, null)
            g.color = java.awt.Color.WHITE; g.drawString(s.label, x + 4, y + 16)
        }
        g.dispose()
        javax.imageio.ImageIO.write(sheet, "png", java.io.File(outPath))
    }

    /** Regler-Raster eines Effekts (Entwicklung): PG_GALLERY_PARAMS=id:bild.jpg:ausgabe.png – Zeilen = Regler 1, Spalten = Regler 2. */
    @Test
    fun paramSheet() {
        val arg = System.getenv("PG_GALLERY_PARAMS")?.takeIf { it.isNotBlank() } ?: return
        val (id, inPath, outPath) = arg.split(':')
        val spec = Gallery.byId(id)!!
        val src = javax.imageio.ImageIO.read(java.io.File(inPath))
        val tw = System.getenv("PG_GALLERY_WIDTH")?.toIntOrNull() ?: 400; val th = src.height * tw / src.width
        val small = java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply { setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC); drawImage(src, 0, 0, tw, th, null); dispose() }
        val px = small.getRGB(0, 0, tw, th, null, 0, tw)
        val p0 = spec.params[0]; val p1 = spec.params.getOrNull(1)
        val rows = 4; val cols = if (p1 == null) 1 else 4
        val sheet = java.awt.image.BufferedImage(cols * (tw + 8), rows * (th + 28), java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = java.awt.Color(40, 40, 44); g.fillRect(0, 0, sheet.width, sheet.height)
        for (r in 0 until rows) for (c in 0 until cols) {
            val vals = spec.defaults()
            vals[0] = p0.min + (p0.max - p0.min) * (r + 0.5f) / rows
            if (p1 != null) vals[1] = p1.min + (p1.max - p1.min) * c / (cols - 1)
            val out = GalleryChain(listOf(GalleryEffect(spec, vals))).process(px.copyOf(), tw, th, 0, 0, tw, th)
            val im = java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            im.setRGB(0, 0, tw, th, out, 0, tw)
            val x = c * (tw + 8); val y = r * (th + 28)
            g.drawImage(im, x, y + 22, null)
            g.color = java.awt.Color.WHITE; g.drawString(vals.joinToString(" / ") { "%.0f".format(it) }, x + 4, y + 16)
        }
        g.dispose()
        javax.imageio.ImageIO.write(sheet, "png", java.io.File(outPath))
    }
}
