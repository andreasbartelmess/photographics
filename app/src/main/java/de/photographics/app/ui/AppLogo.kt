package de.photographics.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * photographics-Logo „Ebenen“ in den Farben des aktuellen Themes (wie das Themed Icon): drei
 * überlagerte Kreise auf Primary Container, die Überlagerungen entstehen nur aus der Deckkraft.
 * Geometrie wie im Launcher-Icon (72er Sichtfeld) und in design/logo.svg.
 */
@Composable
fun AppLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.primaryContainer
    val fg = MaterialTheme.colorScheme.onPrimaryContainer
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            // Launcher-Icons zeigen den Bereich 18..90 des 108er-Rasters
            val k = this.size.width / 72f
            fun p(x: Float, y: Float) = Offset((x - 18f) * k, (y - 18f) * k)
            val r = 16f * k
            drawCircle(fg.copy(alpha = 0.30f), r, p(45f, 61f))
            drawCircle(fg.copy(alpha = 0.30f), r, p(63f, 61f))
            drawCircle(fg.copy(alpha = 0.62f), r, p(54f, 45.5f))
        }
    }
}

/** Schriftzug „photographics“ – immer klein geschrieben, in der Schrift der Oberfläche. */
@Composable
fun Wordmark(style: TextStyle, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text("photographics", modifier, color = color, style = style, maxLines = 1)
}
