package de.photographics.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Senkrechter Schnellregler am Bildrand (wie in Zeichen-Apps), bequem mit dem Daumen der
 * Nicht-Stift-Hand zu bedienen. [value] liegt in 0..1.
 */
@Composable
fun VerticalSlider(
    label: String,
    value: Float,
    display: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 180.dp,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var active by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val thumb = MaterialTheme.colorScheme.onPrimary
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(36.dp)
                .height(trackHeight)
                .shadow(3.dp, RoundedCornerShape(18.dp))
                .background(track, RoundedCornerShape(18.dp))
                .pointerInput(Unit) {
                    detectTapGestures { p -> change((1f - p.y / size.height).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    var v = 0f
                    detectDragGestures(
                        onDragStart = { v = current; active = true },
                        onDragEnd = { active = false },
                        onDragCancel = { active = false },
                    ) { ch, drag ->
                        ch.consume()
                        v = (v - drag.y / size.height).coerceIn(0f, 1f)
                        change(v)
                    }
                },
        ) {
            Canvas(Modifier.width(36.dp).height(trackHeight)) {
                val h = size.height * value.coerceIn(0f, 1f)
                drawRoundRect(accent, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(size.width / 2))
                // Knopf innerhalb der Spur halten; bei sehr kurzer Spur mittig (sonst leerer Bereich)
                val half = size.width / 2
                val y = if (size.height > size.width) (size.height - h).coerceIn(half, size.height - half) else size.height / 2
                drawCircle(thumb, radius = size.width * 0.32f, center = Offset(half, y))
            }
        }
        Text(
            if (active) display else label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
