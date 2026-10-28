package de.photographics.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.photographics.app.EditorViewModel
import de.photographics.core.tools.AngleControl
import de.photographics.core.tools.BrushMode
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.DynControl
import kotlin.math.roundToInt

/** Pinseleinstellungen (Photoshop: Fenster → Pinseleinstellungen, F5) mit Vorgaben und Live-Vorschau. */
@Composable
fun BrushSettingsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val b = state.brush
    fun set(f: (BrushSettings) -> BrushSettings) = vm.updateBrush(f(state.brush))
    val close = { vm.rememberBrush(); onDismiss() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 780.dp),
        ) {
            BoxWithConstraints {
                val wide = maxWidth > 640.dp
                Column {
                    Row(Modifier.padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Pinseleinstellungen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = close) { Icon(Icons.Filled.Close, "Schließen") }
                    }
                    if (wide) Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                        PresetList(vm, Modifier.width(230.dp).fillMaxHeight())
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            BrushPreview(vm, b)
                            Column(Modifier.verticalScroll(rememberScrollState())) { BrushSections(b, ::set) }
                        }
                    } else Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                        BrushPreview(vm, b)
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PresetList(vm, Modifier.fillMaxWidth().heightIn(max = 260.dp))
                            BrushSections(b, ::set)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrushPreview(vm: EditorViewModel, b: BrushSettings) {
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(b) {
        kotlinx.coroutines.delay(40)
        img = vm.brushPreview(b, 640, 140).asImageBitmap()
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(640f / 140f).clip(RoundedCornerShape(16.dp)).background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        img?.let { Image(it, "Vorschau des Pinselstrichs", Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun PresetList(vm: EditorViewModel, modifier: Modifier) {
    val state by vm.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(modifier) {
        Text("Vorgaben", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 8.dp))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(state.brushPresets, key = { (if (it.builtin) "b:" else "u:") + it.name }) { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.applyBrushPreset(p) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (p.builtin) FontWeight.Normal else FontWeight.Medium)
                    if (!p.builtin) IconButton(onClick = { vm.deleteBrushPreset(p.name) }, Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Delete, "Vorgabe löschen", Modifier.size(18.dp))
                    }
                }
            }
        }
        if (naming) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { naming = false }) { Text("Abbrechen") }
                TextButton(onClick = { vm.saveBrushPreset(name); naming = false; name = "" }, enabled = name.isNotBlank()) { Text("Speichern") }
            }
        } else OutlinedButton(onClick = { naming = true }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("Als Vorgabe speichern", Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun BrushSections(b: BrushSettings, set: ((BrushSettings) -> BrushSettings) -> Unit) {
    Head("Pinselspitze")
    Param("Größe", "${b.size.roundToInt()} px", b.size, 1f..500f) { v -> set { it.copy(size = v) } }
    Param("Härte", pct(b.hardness), b.hardness, 0f..1f) { v -> set { it.copy(hardness = v) } }
    Param("Winkel", "${b.angle.roundToInt()}°", b.angle, -180f..180f) { v -> set { it.copy(angle = v) } }
    Param("Rundheit", pct(b.roundness), b.roundness, 0.05f..1f) { v -> set { it.copy(roundness = v) } }
    Param("Abstand", pct(b.spacing), b.spacing, 0.01f..2f) { v -> set { it.copy(spacing = v) } }
    Param("Glättung", pct(b.smoothing), b.smoothing, 0f..0.9f) { v -> set { it.copy(smoothing = v) } }

    Head("Formeigenschaften")
    Param("Größen-Jitter", pct(b.sizeJitter), b.sizeJitter, 0f..1f) { v -> set { it.copy(sizeJitter = v) } }
    Choice("Größe steuern", DynControl.entries, b.sizeControl, { it.label }) { v -> set { it.copy(sizeControl = v) } }
    if (b.sizeControl != DynControl.OFF) Param("Mindestdurchmesser", pct(b.minSize), b.minSize, 0f..1f) { v -> set { it.copy(minSize = v) } }
    Param("Winkel-Jitter", pct(b.angleJitter), b.angleJitter, 0f..1f) { v -> set { it.copy(angleJitter = v) } }
    Choice("Winkel steuern", AngleControl.entries, b.angleControl, { it.label }) { v -> set { it.copy(angleControl = v) } }
    Param("Rundheit-Jitter", pct(b.roundnessJitter), b.roundnessJitter, 0f..1f) { v -> set { it.copy(roundnessJitter = v) } }
    if (b.roundnessJitter > 0f) Param("Mindestrundheit", pct(b.minRoundness), b.minRoundness, 0.05f..1f) { v -> set { it.copy(minRoundness = v) } }

    Head("Streuung")
    Param("Streuung", pct(b.scatter), b.scatter, 0f..10f) { v -> set { it.copy(scatter = v) } }
    Toggle("Beide Achsen", "Auch entlang des Strichs streuen", b.scatterBothAxes) { v -> set { it.copy(scatterBothAxes = v) } }
    Param("Anzahl", "${b.count}", b.count.toFloat(), 1f..16f, steps = 14) { v -> set { it.copy(count = v.roundToInt()) } }
    Param("Anzahl-Jitter", pct(b.countJitter), b.countJitter, 0f..1f) { v -> set { it.copy(countJitter = v) } }

    Head("Übertragung")
    Param("Deckkraft", pct(b.opacity), b.opacity, 0.01f..1f) { v -> set { it.copy(opacity = v) } }
    Param("Deckkraft-Jitter", pct(b.opacityJitter), b.opacityJitter, 0f..1f) { v -> set { it.copy(opacityJitter = v) } }
    Choice("Deckkraft steuern", DynControl.entries, b.opacityControl, { it.label }) { v -> set { it.copy(opacityControl = v) } }
    Param("Fluss", pct(b.flow), b.flow, 0.01f..1f) { v -> set { it.copy(flow = v) } }
    Param("Fluss-Jitter", pct(b.flowJitter), b.flowJitter, 0f..1f) { v -> set { it.copy(flowJitter = v) } }
    Choice("Fluss steuern", DynControl.entries, b.flowControl, { it.label }) { v -> set { it.copy(flowControl = v) } }
    if (DynControl.FADE in listOf(b.sizeControl, b.opacityControl, b.flowControl)) {
        Param("Verblassen nach", "${b.fadeSteps} Abdrücken", b.fadeSteps.toFloat(), 5f..400f) { v -> set { it.copy(fadeSteps = v.roundToInt()) } }
    }

    Head("Farbeinstellungen")
    Text("Wirkt je Pinselabdruck; Vorder- und Hintergrundfarbe aus dem Farbfeld.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Param("Vorder-/Hintergrund-Jitter", pct(b.fgBgJitter), b.fgBgJitter, 0f..1f) { v -> set { it.copy(fgBgJitter = v) } }
    Param("Farbton-Jitter", pct(b.hueJitter), b.hueJitter, 0f..1f) { v -> set { it.copy(hueJitter = v) } }
    Param("Sättigungs-Jitter", pct(b.saturationJitter), b.saturationJitter, 0f..1f) { v -> set { it.copy(saturationJitter = v) } }
    Param("Helligkeits-Jitter", pct(b.brightnessJitter), b.brightnessJitter, 0f..1f) { v -> set { it.copy(brightnessJitter = v) } }

    Head("Sonstiges")
    ModeChooser(b.mode) { v -> set { it.copy(mode = v) } }
    Toggle("Airbrush", "Farbe baut sich auf, solange der Stift aufliegt", b.airbrush) { v -> set { it.copy(airbrush = v) } }
}

private fun pct(v: Float) = "${(v * 100).roundToInt()} %"

@Composable
private fun Head(t: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Param(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(170.dp))
        Slider(v.coerceIn(range.start, range.endInclusive), onChange, Modifier.weight(1f), valueRange = range, steps = steps)
        Text(value, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp).padding(start = 8.dp))
    }
}

@Composable
private fun <T> Choice(label: String, items: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(170.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            items.forEachIndexed { i, item ->
                SegmentedButton(selected = item == selected, onClick = { onSelect(item) }, shape = SegmentedButtonDefaults.itemShape(i, items.size)) {
                    Text(name(item), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ModeChooser(mode: BrushMode, onSelect: (BrushMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text("Modus", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(170.dp))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(mode.label); Icon(Icons.Filled.ArrowDropDown, null) }
            DropdownMenu(open, { open = false }) {
                for (m in BrushMode.entries) DropdownMenuItem(text = { Text(m.label) }, onClick = { onSelect(m); open = false })
            }
        }
    }
}
