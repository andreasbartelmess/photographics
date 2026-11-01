package de.photographics.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.photographics.core.editor.FillContent
import de.photographics.core.render.BlendMode
import de.photographics.core.tr
import kotlin.math.roundToInt

/** Fläche füllen (Umschalt+F5): Inhalt, eigene Farbe, Mischmodus, Deckkraft, Transparenz erhalten. */
@Composable
fun FillDialog(
    initialColor: Int,
    onApply: (FillContent, Int, BlendMode, Float, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf(FillContent.FOREGROUND) }
    var color by remember { mutableIntStateOf(initialColor) }
    var mode by remember { mutableStateOf(BlendMode.NORMAL) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var keep by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val blendable = content != FillContent.CONTENT_AWARE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Fläche füllen")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FillChoice(tr("Inhalt"), FillContent.entries, content, { it.label }) { content = it }
                if (content == FillContent.COLOR) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Farbe"), Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.size(width = 56.dp, height = 32.dp).background(Color(color or 0xFF000000.toInt()), RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).clickable { picking = true })
                }
                FillChoice(tr("Modus"), de.photographics.core.doc.LayerStyles.blendModes, mode, { it.label }, blendable) { mode = it }
                LabeledSlider(tr("Deckkraft"), opacity, 0.01f..1f, "${(opacity * 100).roundToInt()} %") { opacity = it }
                Row(Modifier.clickable(enabled = blendable) { keep = !keep }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it }, enabled = blendable)
                    Text(tr("Transparenz erhalten"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(content, color, mode, opacity, keep) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
    if (picking) ColorPickerDialog(color, onPick = { color = it; picking = false }, onDismiss = { picking = false })
}

@Composable
private fun <T> FillChoice(label: String, items: List<T>, value: T, name: (T) -> String, enabled: Boolean = true, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(name(value)) }
            DropdownMenu(open, { open = false }) { for (item in items) DropdownMenuItem(text = { Text(name(item)) }, onClick = { open = false; onPick(item) }) }
        }
    }
}
