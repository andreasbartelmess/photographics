package de.photographics.core

import de.photographics.core.color.ProofCondition
import de.photographics.core.color.SoftProof
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftProofTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun gamutWarningMatchesExpectations() {
        val t0 = System.nanoTime()
        val coated = SoftProof(ProofCondition.COATED, simulatePaper = false)
        println("Tabelle aufgebaut in %.0f ms".format((System.nanoTime() - t0) / 1e6))
        // Typisch außerhalb von CMYK: reines Bildschirm-Blau, -Grün, Neon-Cyan
        assertTrue(coated.outOfGamut(rgb(0, 0, 255)))
        assertTrue(coated.outOfGamut(rgb(0, 255, 0)))
        assertTrue(coated.outOfGamut(rgb(0, 255, 255)))
        // Druckbar: Grautöne, Hauttöne, Weiß, Schwarz, gedecktes Rot
        for (c in listOf(rgb(255, 255, 255), rgb(0, 0, 0), rgb(128, 128, 128), rgb(224, 180, 150), rgb(160, 40, 40), rgb(60, 90, 140)))
            assertFalse("%06X".format(c and 0xFFFFFF), coated.outOfGamut(c))
        // Zeitung hat einen deutlich kleineren Farbumfang
        val news = SoftProof(ProofCondition.NEWSPAPER, simulatePaper = true)
        assertTrue(news.outOfGamut(rgb(220, 30, 30)))
        assertFalse(news.outOfGamut(rgb(128, 128, 128)))

        // Papiersimulation: Weiß wird zum (dunkleren, gelblichen) Zeitungspapier
        val px = intArrayOf(rgb(255, 255, 255), rgb(0, 0, 0))
        news.apply(px)
        val wr = (px[0] shr 16) and 0xFF; val wb = px[0] and 0xFF
        assertTrue("Papierweiß ${"%06X".format(px[0] and 0xFFFFFF)}", wr in 185..235 && wb < wr)
        assertTrue("Schwarz ${"%06X".format(px[1] and 0xFFFFFF)}", ((px[1] shr 8) and 0xFF) in 50..120)

        // Geschwindigkeit der Anzeige-Umrechnung
        val big = IntArray(2_000_000) { it * 2654435761L.toInt() or (0xFF shl 24) }
        val t1 = System.nanoTime()
        coated.apply(big, gamutWarning = true)
        println("2 MP umgerechnet in %.0f ms".format((System.nanoTime() - t1) / 1e6))
    }

    /** Proof mit echtem CMYK-Profil (Ghostscript) – Farbumfang über Rundlauf-ΔE. */
    @Test
    fun iccProofWithPrinterProfile() {
        val f = java.io.File("/usr/share/ghostscript/iccprofiles/default_cmyk.icc")
        org.junit.Assume.assumeTrue(f.exists())
        val cmyk = de.photographics.core.color.IccProfile(f.readBytes())
        val p = SoftProof.icc(de.photographics.core.color.IccProfiles.sRGB, cmyk, de.photographics.core.color.RenderingIntent.RELATIVE, true, false)
        assertTrue(p.outOfGamut(rgb(0, 0, 255)))
        assertTrue(p.outOfGamut(rgb(0, 255, 0)))
        assertFalse(p.outOfGamut(rgb(128, 128, 128)))
        assertFalse(p.outOfGamut(rgb(200, 160, 130)))
        val px = intArrayOf(rgb(255, 255, 255), rgb(0, 0, 255))
        p.apply(px)
        assertTrue("Weiß bleibt Weiß", (px[0] and 0xFF) >= 250)
        // Reines Blau wird im Druck deutlich stumpfer (Rot- oder Grünanteil steigt)
        assertTrue(((px[1] shr 16) and 0xFF) > 20 || ((px[1] shr 8) and 0xFF) > 20)
    }

    /** Anzeige eines Adobe-RGB-Dokuments auf sRGB: Grün wird beschnitten, Grau bleibt Grau. */
    @Test
    fun displayTransformForWideGamutDocument() {
        val p = SoftProof.display(de.photographics.core.color.IccProfiles.Builtin.ADOBE_RGB.profile)
        val px = intArrayOf(rgb(0, 255, 0), rgb(128, 128, 128))
        p.apply(px)
        assertTrue(((px[0] shr 16) and 0xFF) < 10 && ((px[0] shr 8) and 0xFF) > 240)
        val g = px[1]; assertTrue(kotlin.math.abs(((g shr 16) and 0xFF) - (g and 0xFF)) <= 2)
    }

    @Test
    fun colorBlindnessCollapsesRedGreen() {
        val p = SoftProof(ProofCondition.DEUTERANOPIA, false)
        val px = intArrayOf(rgb(200, 60, 60), rgb(90, 150, 60))
        p.apply(px)
        val dr = (px[0] shr 16 and 0xFF) - (px[0] shr 8 and 0xFF)
        // Rot und Grün werden für Deuteranope einander ähnlich (R≈G je Farbe)
        assertTrue("R−G nach Simulation $dr", kotlin.math.abs(dr) < 25)
    }
}
