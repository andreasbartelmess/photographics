package de.photographics.core

import de.photographics.core.color.BuiltinPalettes
import de.photographics.core.color.Palette
import de.photographics.core.color.PaletteIO
import de.photographics.core.color.Swatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs

class PaletteTest {
    private fun close(a: Int, b: Int, tol: Int = 2) =
        intArrayOf(16, 8, 0).all { s -> abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)) <= tol }

    /** ACO nach Spezifikation von Hand: Version 1 und 2 (mit Namen), RGB/HSB/CMYK/Lab/Grau. */
    @Test
    fun readHandmadeAco() {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v shr 8); o.write(v) }
        val entries = listOf(
            intArrayOf(0, 65535, 0, 0, 0),          // RGB Rot
            intArrayOf(1, 65535 / 3, 65535, 65535, 0), // HSB 120° → Grün
            intArrayOf(2, 65535, 65535, 0, 65535),  // CMYK: nur Gelb (0 = 100 %)
            intArrayOf(7, 5000, 0, 0, 0),           // Lab L=50 → Grau 119
            intArrayOf(8, 2500, 0, 0, 0),           // 25 % Schwarz
        )
        val names = listOf("Rot", "Grün", "Gelb", "Lab-Grau", "Hellgrau")
        for (version in 1..2) {
            u16(version); u16(entries.size)
            for ((i, e) in entries.withIndex()) {
                e.forEach(::u16)
                if (version == 2) { u16(0); u16(names[i].length + 1); names[i].forEach { u16(it.code) }; u16(0) }
            }
        }
        val p = PaletteIO.read(o.toByteArray(), "Test.aco")
        assertEquals(names, p.swatches.map { it.name })
        val expected = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(), 0xFF777777.toInt(), 0xFFBFBFBF.toInt())
        for ((i, s) in p.swatches.withIndex()) assertTrue("${names[i]}: %08X".format(s.color), close(s.color, expected[i]))
    }

    /** ASE nach Spezifikation von Hand: Gruppe, RGB, CMYK, LAB (L 0..100), Grau. */
    @Test
    fun readHandmadeAse() {
        val o = ByteArrayOutputStream()
        val bb = ByteBuffer.allocate(4)
        fun u16(v: Int) { o.write(v shr 8); o.write(v) }
        fun i32(v: Int) { o.write(v ushr 24); o.write(v ushr 16); o.write(v ushr 8); o.write(v) }
        fun f(v: Float) { bb.clear(); bb.putFloat(v); o.write(bb.array()) }
        fun block(type: Int, body: ByteArray) { u16(type); i32(body.size); o.write(body) }
        fun body(name: String, model: String?, vararg vals: Float): ByteArray {
            val b = ByteArrayOutputStream(); val bf = ByteBuffer.allocate(4)
            fun bu16(v: Int) { b.write(v shr 8); b.write(v) }
            bu16(name.length + 1); name.forEach { bu16(it.code) }; bu16(0)
            if (model != null) {
                b.write(model.toByteArray(Charsets.ISO_8859_1))
                for (v in vals) { bf.clear(); bf.putFloat(v); b.write(bf.array()) }
                bu16(2)
            }
            return b.toByteArray()
        }
        o.write("ASEF".toByteArray()); u16(1); u16(0); i32(6)
        block(0xC001, body("Marke", null))
        block(0x0001, body("Blau", "RGB ", 0f, 0f, 1f))
        block(0x0001, body("Cyan", "CMYK", 1f, 0f, 0f, 0f))
        block(0x0001, body("Lab", "LAB ", 50f, 0f, 0f))
        block(0x0001, body("Grau", "Gray", 0.5f))
        block(0xC002, ByteArray(0))
        val p = PaletteIO.read(o.toByteArray(), "x.ase")
        assertEquals("Marke", p.name)
        assertEquals(listOf("Blau", "Cyan", "Lab", "Grau"), p.swatches.map { it.name })
        val exp = intArrayOf(0xFF0000FF.toInt(), 0xFF00FFFF.toInt(), 0xFF777777.toInt(), 0xFF808080.toInt())
        for ((i, s) in p.swatches.withIndex()) assertTrue("%08X".format(s.color), close(s.color, exp[i]))
    }

    @Test
    fun writersRoundTrip() {
        val p = Palette("Meine Farben", listOf(Swatch(0xFF123456.toInt(), "Nachtblau"), Swatch(0xFFFFEE00.toInt(), "Gelb"), Swatch(0xFF000000.toInt(), "")))
        val aco = PaletteIO.read(PaletteIO.writeAco(p), "Meine Farben.aco")
        assertEquals(p.swatches, aco.swatches)
        val ase = PaletteIO.read(PaletteIO.writeAse(p), "Meine Farben.ase")
        assertEquals(p.swatches.map { it.color }, ase.swatches.map { it.color })
        assertEquals(p.swatches.map { it.name }, ase.swatches.map { it.name })
        val gpl = PaletteIO.read(PaletteIO.writeGpl(p).toByteArray(), "x.gpl")
        assertEquals("Meine Farben", gpl.name)
        assertEquals(p.swatches.map { it.color }, gpl.swatches.map { it.color })
        assertEquals("Nachtblau", gpl.swatches[0].name)
    }

    @Test
    fun actAndBuiltins() {
        val act = ByteArray(772); act[3] = 0xFF.toByte(); act[768] = 0; act[769] = 2
        val p = PaletteIO.read(act, "tabelle.act")
        assertEquals(listOf(0xFF000000.toInt(), 0xFFFF0000.toInt()), p.swatches.map { it.color })
        assertTrue(BuiltinPalettes.all.all { pal -> pal.swatches.isNotEmpty() && pal.swatches.all { (it.color ushr 24) == 0xFF } })
    }
}
