package de.photographics.core

import de.photographics.core.color.ColorTransform
import de.photographics.core.color.IccProfile
import de.photographics.core.color.IccProfiles
import de.photographics.core.color.RenderingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Eigene ICC-Engine gegen LittleCMS (Referenzwerte aus tools/icc_reference.py, erzeugt mit
 * Pillow/ImageCms). Die Profile stammen aus Ghostscript und colord; fehlen sie, wird übersprungen.
 */
class IccTest {
    private class Case(val src: String, val dst: String, val intent: Int, val bpc: Boolean) {
        val pairs = ArrayList<Pair<IntArray, IntArray>>()
    }

    private fun cases(): List<Case> {
        val text = javaClass.getResourceAsStream("/icc/lcms-reference.txt")!!.bufferedReader().readText()
        val list = ArrayList<Case>()
        for (line in text.lineSequence()) {
            if (line.startsWith("CASE ")) {
                val p = line.split(' ')
                list += Case(p[1], p[2], p[5].toInt(), p[6] == "1")
            } else if (line.isNotBlank()) {
                val (i, o) = line.split(';')
                list.last().pairs += i.split(',').map { it.toInt() }.toIntArray() to o.split(',').map { it.toInt() }.toIntArray()
            }
        }
        return list
    }

    /** Toleranz in 8-Bit-Stufen: Matrixprofile exakt, Tabellenprofile mit Interpolationsunterschieden. */
    private fun tolerance(c: Case, src: IccProfile, dst: IccProfile): Pair<Int, Double> = when {
        src.isRgb && dst.isRgb -> 1 to 0.1
        else -> 4 to 0.3 // eine Farbe bei perzeptiv+Tiefenkompensierung: Schwarzpunkt minimal anders bestimmt
    }

    @Test
    fun matchesLittleCms() {
        val all = cases()
        assumeTrue("Systemprofile fehlen", all.all { File(it.src).exists() && File(it.dst).exists() })
        val report = StringBuilder()
        var failed = false
        for (c in all) {
            val src = IccProfile(File(c.src).readBytes()); val dst = IccProfile(File(c.dst).readBytes())
            val t = ColorTransform(src, dst, RenderingIntent.entries[c.intent], c.bpc)
            var worst = 0; var sum = 0.0; var n = 0
            for ((i, expected) in c.pairs) {
                val got = t.map8(*i)
                for (k in expected.indices) { val d = abs(got[k] - expected[k]); worst = maxOf(worst, d); sum += d; n++ }
            }
            val (maxTol, meanTol) = tolerance(c, src, dst)
            val mean = sum / n
            val ok = worst <= maxTol && mean <= meanTol
            if (!ok) failed = true
            report.append("%s %-16s → %-16s Priorität %d BPC %-5s max %3d  Ø %.2f\n".format(
                if (ok) "ok " else "FEHLER", File(c.src).name, File(c.dst).name, c.intent, c.bpc, worst, mean))
        }
        println(report)
        assertTrue(report.toString(), !failed)
    }

    @Test
    fun builtinProfilesMatchReferenceProfiles() {
        assumeTrue(File("/usr/share/ghostscript/iccprofiles/srgb.icc").exists())
        val pairs = listOf(
            IccProfiles.Builtin.SRGB.profile to "/usr/share/ghostscript/iccprofiles/srgb.icc",
            IccProfiles.Builtin.ADOBE_RGB.profile to "/usr/share/ghostscript/iccprofiles/a98.icc",
            IccProfiles.Builtin.PROPHOTO.profile to "/usr/share/color/icc/colord/ProPhotoRGB.icc",
        )
        for ((ours, path) in pairs) {
            if (!File(path).exists()) continue
            val t = ColorTransform(ours, IccProfile(File(path).readBytes()), RenderingIntent.RELATIVE, false)
            var worst = 0
            for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
                val o = t.map8(r, g, b)
                worst = maxOf(worst, abs(o[0] - r), abs(o[1] - g), abs(o[2] - b))
            }
            assertTrue("${ours.description} weicht um $worst ab", worst <= 1)
        }
    }

    /** Abstraktes Profil mit Farbtabelle (mft2, Lab ↔ Lab, Altkodierung) gegen LittleCMS. */
    @Test
    fun abstractProfileWithClutMatchesLittleCms() {
        val p = IccProfile(javaClass.getResourceAsStream("/icc/bchsw-v2.icc")!!.readBytes())
        val f = p.toPcs(RenderingIntent.PERCEPTUAL)
        val k = 65280.0 / 65535.0 // mft2: Lab in Altkodierung (100 ↔ 0xFF00)
        val xyz = DoubleArray(3); val lab = DoubleArray(3)
        var worst = 0.0
        for (line in javaClass.getResourceAsStream("/icc/bchsw-reference.txt")!!.bufferedReader().readLines()) {
            val (i, o) = line.split(';').map { s -> s.split(',').map { it.toDouble() } }
            f.apply(doubleArrayOf(i[0] / 100 * k, (i[1] + 128) / 255 * k, (i[2] + 128) / 255 * k), xyz)
            de.photographics.core.color.Pcs.xyzToLab(xyz[0], xyz[1], xyz[2], lab)
            worst = maxOf(worst, de.photographics.core.color.Pcs.deltaE(lab, o.toDoubleArray()))
        }
        assertTrue("ΔE $worst", worst < 0.5)
    }

    /** v4-Lab-Profil (mAB) von LittleCMS: Gerät-Lab → PCS-Lab ist die Identität. */
    @Test
    fun v4LabProfileIsIdentity() {
        val p = IccProfile(javaClass.getResourceAsStream("/icc/lab4-lcms.icc")!!.readBytes())
        assertEquals(4, p.versionMajor)
        val f = p.toPcs(RenderingIntent.PERCEPTUAL)
        val xyz = DoubleArray(3); val lab = DoubleArray(3)
        for (l in doubleArrayOf(0.0, 25.0, 50.0, 99.0)) for (a in doubleArrayOf(-80.0, 0.0, 60.0)) for (b in doubleArrayOf(-60.0, 0.0, 90.0)) {
            f.apply(doubleArrayOf(l / 100, (a + 128) / 255, (b + 128) / 255), xyz)
            de.photographics.core.color.Pcs.xyzToLab(xyz[0], xyz[1], xyz[2], lab)
            assertTrue("($l,$a,$b) → ${lab.toList()}", de.photographics.core.color.Pcs.deltaE(lab, doubleArrayOf(l, a, b)) < 0.05)
        }
    }

    @Test
    fun writerRoundTrip() {
        for (b in IccProfiles.Builtin.entries) {
            val p = IccProfile(b.profile.bytes)
            assertEquals(b.label, p.description)
            assertTrue(p.isRgb)
            // Weiß bleibt Weiß, Schwarz bleibt Schwarz (relativ farbmetrisch zu sich selbst)
            val t = ColorTransform(p, IccProfiles.sRGB, RenderingIntent.RELATIVE, false)
            assertTrue(t.map8(255, 255, 255).all { it >= 254 })
            assertTrue(t.map8(0, 0, 0).all { it <= 1 })
        }
        val gray = IccProfile(IccProfiles.grayProfile("Test", 2.2))
        assertTrue(gray.isGray)
    }
}
