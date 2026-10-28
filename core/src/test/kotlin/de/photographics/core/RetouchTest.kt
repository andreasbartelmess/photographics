package de.photographics.core

import de.photographics.core.retouch.PatchMatchInpainter
import de.photographics.core.retouch.Poisson
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RetouchTest {
    private fun stripes(w: Int, h: Int) = IntArray(w * h) { i -> if ((i % w / 6) % 2 == 0) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }

    @Test
    fun patchMatchRestoresRegularTexture() {
        val w = 120; val h = 90
        val orig = stripes(w, h)
        val hole = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; x in 50..69 && y in 35..54 }
        val damaged = orig.copyOf().also { for (i in it.indices) if (hole[i]) it[i] = 0xFFFF0000.toInt() }
        val t0 = System.currentTimeMillis()
        val out = PatchMatchInpainter().inpaint(damaged, w, h, hole)
        val ms = System.currentTimeMillis() - t0
        var err = 0.0; var n = 0
        for (i in out.indices) if (hole[i]) { err += abs(((out[i] shr 8) and 0xFF) - ((orig[i] shr 8) and 0xFF)); n++ }
        val mean = err / n
        println("PatchMatch: mittlerer Fehler $mean in $ms ms")
        // Rot muss verschwunden sein und die Streifen weitgehend stimmen
        assertTrue(out.indices.none { hole[it] && ((out[it] shr 16) and 0xFF) > 200 && ((out[it] shr 8) and 0xFF) < 80 })
        assertTrue("mittlerer Fehler $mean", mean < 40)
    }

    @Test
    fun poissonMatchesSurroundingBrightness() {
        val w = 40; val h = 40
        val target = IntArray(w * h) { 0xFF404040.toInt() }
        val source = IntArray(w * h) { i -> if ((i % w) % 8 < 4) 0xFFB0B0B0.toInt() else 0xFFC0C0C0.toInt() } // heller, mit Struktur
        val inside = BooleanArray(w * h) { i -> (i % w) in 10..29 && (i / w) in 10..29 }
        val out = Poisson.blend(target, source, inside, w, h)
        val mid = out[20 * w + 20] and 0xFF
        // Ergebnis liegt im Helligkeitsbereich des Ziels (0x40 ± Strukturamplitude), nicht bei 0xB0
        assertTrue("Mitte $mid", mid in 0x30..0x58)
        // Struktur (Differenz benachbarter Spalten) bleibt erhalten
        val d = abs((out[20 * w + 19] and 0xFF) - (out[20 * w + 20] and 0xFF))
        assertTrue("Struktur $d", d >= 8 || abs((out[20 * w + 23] and 0xFF) - (out[20 * w + 24] and 0xFF)) >= 8)
    }
}

class RetouchPerfTest {
    @Test
    fun largeSpotHealTiming() {
        val doc = de.photographics.core.doc.Document.blank(4000, 3000)
        val bg = doc.root.children[0] as de.photographics.core.doc.PixelLayer
        // Struktur + langer dicker Strich (wie im Emulator-Test)
        for (y in 0 until 3000 step 7) for (x in 0 until 4000) bg.pixels[x, y] = 0xFFDDDDDD.toInt()
        val hole = { x: Int, y: Int -> val t = (x - 1000) / 1400.0; t in 0.0..1.0 && kotlin.math.abs(y - (1300 + t * 700)) < 230 }
        for (y in 1000..2300) for (x in 1000..2400) if (hole(x, y)) bg.pixels[x, y] = 0xFF000000.toInt()
        val rect = de.photographics.core.image.IntRect(1000, 1070, 2400, 2230)
        val t0 = System.currentTimeMillis()
        val res = de.photographics.core.tools.Retouch.fillHole(doc.bounds, rect, hole, { r, out -> bg.pixels.readRect(r, out) },
            de.photographics.core.retouch.PatchMatchInpainter())!!
        val ms = System.currentTimeMillis() - t0
        val (r, filled, _) = res
        var black = 0; var n = 0
        for (y in rect.top until rect.bottom step 5) for (x in rect.left until rect.right step 5) if (hole(x, y)) {
            n++; if ((filled[(y - r.top) * r.width + (x - r.left)] and 0xFF) < 100) black++
        }
        println("Große Bereichsreparatur: ${r.width}x${r.height} Ausschnitt, $ms ms, schwarze Reste ${100 * black / n} %")
    }
}
