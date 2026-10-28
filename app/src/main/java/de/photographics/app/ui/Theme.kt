package de.photographics.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import de.photographics.app.AppSettings
import de.photographics.app.CanvasBg
import de.photographics.app.ColorSource
import de.photographics.app.ThemeMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Markenfarben (Ausweich-Schema ohne Material You), abgeleitet aus einem Indigo-Seed
private val LightScheme = lightColorScheme(
    primary = Color(0xFF4A5C92),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE1FF),
    onPrimaryContainer = Color(0xFF32447A),
    secondary = Color(0xFF595E72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEE1F9),
    onSecondaryContainer = Color(0xFF424659),
    tertiary = Color(0xFF75546F),
    tertiaryContainer = Color(0xFFFFD7F5),
    background = Color(0xFFFAF8FF),
    surface = Color(0xFFFAF8FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    onSurface = Color(0xFF1A1B21),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757680),
    outlineVariant = Color(0xFFC5C6D0),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF1A2D60),
    primaryContainer = Color(0xFF32447A),
    onPrimaryContainer = Color(0xFFDBE1FF),
    secondary = Color(0xFFC2C5DD),
    onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDEE1F9),
    tertiary = Color(0xFFE4BADA),
    tertiaryContainer = Color(0xFF5C3D57),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33343A),
    onSurface = Color(0xFFE2E2E9),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A),
    outlineVariant = Color(0xFF45464F),
)

/**
 * Material-3-Theme nach den App-Einstellungen: Hell/Dunkel/System, Farben aus dem
 * Hintergrundbild (Material You, ab Android 12) oder aus einer eigenen Akzentfarbe,
 * optional tiefschwarz für OLED-Displays.
 */
@Composable
fun PhotographicsTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = remember(darkTheme, settings.colorSource, settings.seedColor, settings.pureBlack) {
        val dynamic = settings.colorSource == ColorSource.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val base = when {
            dynamic && darkTheme -> dynamicDarkColorScheme(ctx)
            dynamic -> dynamicLightColorScheme(ctx)
            settings.colorSource == ColorSource.SEED && darkTheme -> SeedScheme.dark(settings.seedColor)
            settings.colorSource == ColorSource.SEED -> SeedScheme.light(settings.seedColor)
            darkTheme -> DarkScheme
            else -> LightScheme
        }
        if (darkTheme && settings.pureBlack) with(SeedScheme) { base.toPureBlack() } else base
    }
    val canvas = when (settings.canvasBg) {
        CanvasBg.AUTO -> null
        CanvasBg.CUSTOM -> Color(settings.canvasCustom)
        else -> Color(settings.canvasBg.color!!)
    }
    // Symbole der Systemleisten passend zum gewählten (nicht nur zum System-)Design
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalCanvasBackground provides canvas) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Vom Nutzer gewählter Hintergrund der Arbeitsfläche (null = Standard). */
val LocalCanvasBackground = staticCompositionLocalOf<Color?> { null }

/**
 * Hintergrund rund um das Bild: standardmäßig neutrales Grau ohne Farbstich, damit Farben
 * beim Retuschieren nicht verfälscht wahrgenommen werden.
 */
val canvasBackground: Color
    @Composable @ReadOnlyComposable
    get() = LocalCanvasBackground.current
        ?: if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF202020) else Color(0xFFD4D4D4)

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
