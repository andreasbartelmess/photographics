package de.photographics.core

import de.photographics.core.adjust.VibranceAdj
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dynamik gegen Photoshop 2026: Eingabe, (Dynamik, Sättigung), Ergebnis aus Photoshop (je Kanal ±2). */
class VibranceTest {
    private val samples = listOf(
        Triple(255, 0, 0) to (-50 to 0) to Triple(255, 99, 99),
        Triple(0, 0, 255) to (0 to -50) to Triple(0, 0, 188),          // Blau zählt im Grau der Sättigung nicht
        Triple(255, 0, 0) to (0 to -50) to Triple(210, 106, 106),
        Triple(128, 64, 64) to (-100 to 0) to Triple(111, 88, 88),
        Triple(27, 0, 0) to (-100 to 0) to Triple(27, 23, 23),         // dunkle Farben gelten als wenig gesättigt
        Triple(128, 255, 128) to (100 to 0) to Triple(83, 255, 83),
        Triple(255, 128, 128) to (100 to 0) to Triple(255, 95, 95),    // Hautton gedämpft
        Triple(128, 64, 64) to (100 to 0) to Triple(132, 43, 43),
        Triple(200, 160, 120) to (50 to 0) to Triple(201, 158, 113),
        Triple(90, 90, 200) to (50 to 0) to Triple(75, 75, 201),
        Triple(60, 180, 60) to (-25 to 0) to Triple(81, 178, 81),
        Triple(230, 230, 180) to (25 to -75) to Triple(231, 231, 219),
        Triple(170, 105, 40) to (50 to 50) to Triple(188, 88, 0),
        Triple(40, 40, 120) to (-50 to -50) to Triple(63, 63, 94),
        Triple(120, 40, 120) to (50 to -50) to Triple(100, 56, 100),
        Triple(250, 180, 250) to (-50 to 50) to Triple(255, 204, 255),
    )

    @Test fun matchesPhotoshop() {
        for ((inAndParams, expected) in samples) {
            val (input, params) = inAndParams
            val map = VibranceAdj(params.first, params.second).compile()
            val out = map.map((input.first shl 16) or (input.second shl 8) or input.third)
            val got = Triple((out shr 16) and 0xFF, (out shr 8) and 0xFF, out and 0xFF)
            val ok = abs(got.first - expected.first) <= 2 && abs(got.second - expected.second) <= 2 && abs(got.third - expected.third) <= 2
            assertTrue("Dynamik ${params.first}, Sättigung ${params.second}: $input → $got, Photoshop $expected", ok)
        }
    }

    @Test fun grayAndNeutralSettingsStayUnchanged() {
        for (v in listOf(0, 64, 128, 200, 255)) {
            val c = (v shl 16) or (v shl 8) or v
            for ((vib, sat) in listOf(100 to 100, -100 to -100, 50 to -50)) {
                assertTrue("Grau $v verändert bei $vib/$sat", VibranceAdj(vib, sat).compile().map(c) == c)
            }
        }
        val map = VibranceAdj(0, 0).compile()
        for (c in listOf(0x123456, 0xFF8000, 0x00FFAA)) assertTrue(map.map(c) == c)
    }
}
