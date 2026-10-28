package de.photographics.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Erzeugt ein vollständiges Material-3-Farbschema aus einer Akzentfarbe – nach dem Prinzip von
 * Material You (Tonpaletten, Rollen über die Helligkeit L*). Statt CAM16/HCT wird CIE-LCh
 * verwendet; für Oberflächenfarben ist der Unterschied nicht sichtbar.
 */
object SeedScheme {
    private class Palette(val hue: Double, val chroma: Double) {
        fun tone(t: Int): Color = toneColor(hue, chroma, t.toDouble())
    }

    fun light(seed: Int): ColorScheme {
        val p = palettes(seed)
        val (pr, se, te, ne, nv, er) = p
        return lightColorScheme(
            primary = pr.tone(40), onPrimary = pr.tone(100), primaryContainer = pr.tone(90), onPrimaryContainer = pr.tone(30),
            inversePrimary = pr.tone(80),
            secondary = se.tone(40), onSecondary = se.tone(100), secondaryContainer = se.tone(90), onSecondaryContainer = se.tone(30),
            tertiary = te.tone(40), onTertiary = te.tone(100), tertiaryContainer = te.tone(90), onTertiaryContainer = te.tone(30),
            background = ne.tone(98), onBackground = ne.tone(10),
            surface = ne.tone(98), onSurface = ne.tone(10), surfaceVariant = nv.tone(90), onSurfaceVariant = nv.tone(30),
            surfaceTint = pr.tone(40), inverseSurface = ne.tone(20), inverseOnSurface = ne.tone(95),
            error = er.tone(40), onError = er.tone(100), errorContainer = er.tone(90), onErrorContainer = er.tone(30),
            outline = nv.tone(50), outlineVariant = nv.tone(80), scrim = Color.Black,
            surfaceBright = ne.tone(98), surfaceDim = ne.tone(87),
            surfaceContainerLowest = ne.tone(100), surfaceContainerLow = ne.tone(96), surfaceContainer = ne.tone(94),
            surfaceContainerHigh = ne.tone(92), surfaceContainerHighest = ne.tone(90),
        )
    }

    fun dark(seed: Int, pureBlack: Boolean = false): ColorScheme {
        val (pr, se, te, ne, nv, er) = palettes(seed)
        val s = darkColorScheme(
            primary = pr.tone(80), onPrimary = pr.tone(20), primaryContainer = pr.tone(30), onPrimaryContainer = pr.tone(90),
            inversePrimary = pr.tone(40),
            secondary = se.tone(80), onSecondary = se.tone(20), secondaryContainer = se.tone(30), onSecondaryContainer = se.tone(90),
            tertiary = te.tone(80), onTertiary = te.tone(20), tertiaryContainer = te.tone(30), onTertiaryContainer = te.tone(90),
            background = ne.tone(6), onBackground = ne.tone(90),
            surface = ne.tone(6), onSurface = ne.tone(90), surfaceVariant = nv.tone(30), onSurfaceVariant = nv.tone(80),
            surfaceTint = pr.tone(80), inverseSurface = ne.tone(90), inverseOnSurface = ne.tone(20),
            error = er.tone(80), onError = er.tone(20), errorContainer = er.tone(30), onErrorContainer = er.tone(90),
            outline = nv.tone(60), outlineVariant = nv.tone(30), scrim = Color.Black,
            surfaceBright = ne.tone(24), surfaceDim = ne.tone(6),
            surfaceContainerLowest = ne.tone(4), surfaceContainerLow = ne.tone(10), surfaceContainer = ne.tone(12),
            surfaceContainerHigh = ne.tone(17), surfaceContainerHighest = ne.tone(22),
        )
        return if (pureBlack) s.toPureBlack() else s
    }

    /** Dunkles Schema mit schwarzen Flächen (OLED); Container bleiben leicht abgestuft. */
    fun ColorScheme.toPureBlack(): ColorScheme = copy(
        background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0C), surfaceContainer = Color(0xFF111113),
        surfaceContainerHigh = Color(0xFF18181B), surfaceContainerHighest = Color(0xFF202024),
    )

    private data class Palettes(val p: Palette, val s: Palette, val t: Palette, val n: Palette, val nv: Palette, val e: Palette)

    private fun palettes(seed: Int): Palettes {
        val lab = rgbToLab(seed)
        val hue = Math.toDegrees(atan2(lab[2], lab[1])).let { if (it < 0) it + 360 else it }
        val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        // Graue Akzentfarbe → nahezu neutrales Schema; sonst kräftige, aber nicht grelle Primärfarbe
        val pc = if (c < 10) c else c.coerceIn(30.0, 64.0)
        return Palettes(
            Palette(hue, pc), Palette(hue, pc / 3), Palette((hue + 60) % 360, pc / 2),
            Palette(hue, minOf(pc / 12, 4.0)), Palette(hue, minOf(pc / 6, 8.0)), Palette(28.0, 60.0),
        )
    }

    // ------------------------------------------------------------ Farbmathematik (sRGB ↔ CIELAB, D65)

    private fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun gam(v: Double) = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
    private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    private fun fi(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) * 27 / 24389

    private fun rgbToLab(c: Int): DoubleArray {
        val r = lin(((c shr 16) and 0xFF) / 255.0); val g = lin(((c shr 8) and 0xFF) / 255.0); val b = lin((c and 0xFF) / 255.0)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    /** Lineares RGB für L*, a*, b* (kann außerhalb von 0..1 liegen). */
    private fun labToLinear(l: Double, a: Double, b: Double): DoubleArray {
        val fy = (l + 16) / 116; val fx = fy + a / 500; val fz = fy - b / 200
        val x = 0.95047 * fi(fx); val y = fi(fy); val z = 1.08883 * fi(fz)
        return doubleArrayOf(
            3.2406 * x - 1.5372 * y - 0.4986 * z,
            -0.9689 * x + 1.8758 * y + 0.0415 * z,
            0.0557 * x - 0.2040 * y + 1.0570 * z,
        )
    }

    /** Farbe mit Helligkeit [tone] (= L*), Farbton und Buntheit – die Buntheit wird so weit reduziert, bis sie in sRGB passt. */
    fun toneColor(hue: Double, chroma: Double, tone: Double): Color {
        val h = Math.toRadians(hue)
        fun at(c: Double) = labToLinear(tone, c * cos(h), c * sin(h))
        fun ok(v: DoubleArray) = v.all { it in -1e-4..1.0001 }
        var rgb = at(chroma)
        if (!ok(rgb)) {
            var lo = 0.0; var hi = chroma
            repeat(14) { val mid = (lo + hi) / 2; if (ok(at(mid))) lo = mid else hi = mid }
            rgb = at(lo)
        }
        return Color(
            gam(rgb[0].coerceIn(0.0, 1.0)).toFloat(), gam(rgb[1].coerceIn(0.0, 1.0)).toFloat(), gam(rgb[2].coerceIn(0.0, 1.0)).toFloat(),
        )
    }
}
