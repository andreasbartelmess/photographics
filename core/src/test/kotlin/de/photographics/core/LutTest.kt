package de.photographics.core

import de.photographics.core.adjust.ColorLookupAdj
import de.photographics.core.adjust.InvertAdj
import de.photographics.core.color.Looks
import de.photographics.core.color.Lut3D
import de.photographics.core.color.LutExport
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LutTest {
    /** Affine Abbildung – tetraedrische Interpolation muss sie exakt wiedergeben. */
    private fun affine(r: Float, g: Float, b: Float, o: FloatArray) {
        o[0] = 0.8f * r + 0.1f * g + 0.05f * b + 0.02f
        o[1] = 0.2f * r + 0.6f * g + 0.1f * b
        o[2] = 0.1f * r + 0.2f * g + 0.65f * b + 0.01f // bleibt ≤ 1 (sonst gilt eine .3dl-Datei als 16 Bit)
    }

    private fun maxDiff(a: Lut3D, f: (Float, Float, Float, FloatArray) -> Unit): Float {
        val x = FloatArray(3); val y = FloatArray(3); var worst = 0f
        val rnd = java.util.Random(7)
        repeat(5000) {
            val r = rnd.nextFloat(); val g = rnd.nextFloat(); val b = rnd.nextFloat()
            a.eval(r, g, b, x); f(r, g, b, y)
            for (c in 0..2) worst = maxOf(worst, abs(x[c] - y[c]))
        }
        return worst
    }

    @Test
    fun tetrahedralIsExactForAffineMaps() {
        assertTrue(maxDiff(Lut3D.of(9, "A", ::affine), ::affine) < 1e-5f)
        assertTrue(maxDiff(Lut3D.identity(2)) { r, g, b, o -> o[0] = r; o[1] = g; o[2] = b } < 1e-6f)
    }

    @Test
    fun cubeRoundTripAndDomain() {
        val lut = Looks.byName("Teal & Orange")!!.lut
        val back = Lut3D.parse(lut.toCube(), "x.cube")
        assertEquals(lut.size, back.size)
        for (i in lut.data.indices) assertEquals(lut.data[i], back.data[i], 1e-5f)
        // DOMAIN 0..2: Eingabe 1.0 liegt in der Mitte
        val cube = "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" +
            (0 until 8).joinToString("\n") { i -> "${i and 1} ${(i shr 1) and 1} ${(i shr 2) and 1}" }
        val o = FloatArray(3)
        Lut3D.parse(cube.toByteArray(), "d.cube").eval(0.5f, 0.5f, 0.5f, o)
        assertEquals(0.25f, o[0], 0.02f)
    }

    @Test
    fun parses1dCubeAnd3dl() {
        val oneD = "LUT_1D_SIZE 3\n0 0 0\n0.25 0.5 0.75\n1 1 1\n"
        val o = FloatArray(3)
        Lut3D.parse(oneD.toByteArray(), "k.cube").eval(0.5f, 0.5f, 0.5f, o)
        assertEquals(0.25f, o[0], 0.01f); assertEquals(0.5f, o[1], 0.01f); assertEquals(0.75f, o[2], 0.01f)
        // .3dl: 17er-Gitter, 12 Bit, Blau läuft am schnellsten
        val n = 17
        val sb = StringBuilder((0 until n).joinToString(" ") { (it * 1023 / (n - 1)).toString() }).append('\n')
        for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) {
            val y = FloatArray(3); affine(r / 16f, g / 16f, b / 16f, y)
            sb.append(y.joinToString(" ") { Math.round(it * 4095).toString() }).append('\n')
        }
        val lut = Lut3D.parse(sb.toString().toByteArray(), "a.3dl")
        assertEquals(n, lut.size)
        assertTrue(maxDiff(lut, ::affine) < 1f / 2000)
    }

    @Test
    fun colorLookupLayerSurvivesPsd() {
        val doc = Document.blank(64, 48)
        val look = ColorLookupAdj("Sepia")
        val file = ColorLookupAdj.fromFile(Looks.byName("Kühl")!!.lut.toCube(), "Meine Kühle.cube")
        doc.root.add(AdjustmentLayer("LUT 1", look)); doc.root.add(AdjustmentLayer("LUT 2", file))
        val back = PsdReader.read(PsdWriter.write(doc))
        val adjs = back.allLayers().filterIsInstance<AdjustmentLayer>().map { it.adjustment as ColorLookupAdj }
        assertEquals(listOf("Sepia", "Meine Kühle"), adjs.map { it.lutName })
        for ((a, b) in listOf(look, file).zip(adjs)) {
            val m1 = a.compile(); val m2 = b.compile()
            for (c in intArrayOf(0x000000, 0x336699, 0xFF8040, 0xFFFFFF)) assertEquals(m1.map(c), m2.map(c))
        }
    }

    @Test
    fun exportsAdjustmentStackAsLut() {
        val doc = Document.blank(8, 8)
        doc.root.add(AdjustmentLayer("Umkehren", InvertAdj).apply { opacity = 0.5f })
        val lut = LutExport.fromAdjustments(doc, 17)!!
        val o = FloatArray(3)
        lut.eval(0f, 1f, 0.2f, o)
        // Halbe Deckkraft: Mitte zwischen Original und Umkehrung
        assertEquals(0.5f, o[0], 0.01f); assertEquals(0.5f, o[1], 0.01f); assertEquals(0.5f, o[2], 0.01f)
    }

    /** Filmemulationen: gültige Tabellen, Schwarzweiß-Filme bleiben neutral, Weiß bleibt hell. */
    @Test
    fun filmLooks() {
        val films = de.photographics.core.color.Looks.all.filter { it.category == "Film" }
        assertTrue(films.size >= 12)
        for (f in films) {
            val white = f.lut.map(0xFFFFFF, FloatArray(3))
            assertTrue(f.name, ((white shr 8) and 0xFF) > 200)
            if (f.name.startsWith("Schwarzweiß") && "getont" !in f.name) {
                val c = f.lut.map(0xC04020, FloatArray(3))
                assertTrue(f.name, kotlin.math.abs(((c shr 16) and 0xFF) - (c and 0xFF)) <= 2)
            }
        }
    }
}
