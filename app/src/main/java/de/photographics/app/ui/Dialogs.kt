package de.photographics.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Switch
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Schwebende Karte mit Live-Vorschau (das Bild bleibt sichtbar). */
@Composable
fun FilterPanel(spec: FilterSpec, onPreview: (FloatArray) -> Unit, onApply: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val values = remember(spec) { mutableStateListOf(*spec.params.map { it.default }.toTypedArray()) }
    LaunchedEffect(spec) { onPreview(values.toFloatArray()) }
    PanelCard(spec.icon, spec.label, modifier) {
        ParamControls(spec.params, values) { onPreview(values.toFloatArray()) }
        PanelActions(
            onReset = { for (i in values.indices) values[i] = spec.params[i].default; onPreview(values.toFloatArray()) },
            onCancel = onCancel, onApply = onApply,
        )
    }
}

/** Gemeinsamer Rahmen der schwebenden Panels. */
@Composable
fun PanelCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun PanelActions(onReset: (() -> Unit)?, onCancel: (() -> Unit)?, onApply: () -> Unit, applyLabel: String = "Anwenden") {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onReset != null) TextButton(onClick = onReset) { Text("Zurücksetzen") }
        Spacer(Modifier.weight(1f))
        if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
        Button(onClick = onApply) { Text(applyLabel) }
    }
}

/** Regler, Schalter, Farbtonleisten und Auswahlfelder für eine Parameterliste. */
@Composable
fun ParamControls(params: List<FilterParam>, values: MutableList<Float>, onChange: () -> Unit) {
    for ((i, p) in params.withIndex()) {
        when (p.kind) {
            ParamKind.SWITCH -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Switch(values[i] >= 0.5f, { values[i] = if (it) 1f else 0f; onChange() })
            }
            ParamKind.CHOICE -> {
                Text(p.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    p.choices.forEachIndexed { k, label ->
                        SegmentedButton(
                            selected = values[i].toInt() == k,
                            onClick = { values[i] = k.toFloat(); onChange() },
                            shape = SegmentedButtonDefaults.itemShape(k, p.choices.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
            }
            else -> {
                Row(Modifier.padding(top = 8.dp)) {
                    Text(p.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(format(values[i], p.decimals), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (p.kind == ParamKind.HUE) HueStrip(p.min, p.max)
                Slider(values[i], { values[i] = it; onChange() }, valueRange = p.min..p.max)
            }
        }
    }
}

/** Farbspektrum über einem Farbton-Regler. */
@Composable
private fun HueStrip(min: Float, max: Float) {
    val colors = (0..12).map { k ->
        val h = ((min + (max - min) * k / 12f) % 360f + 360f) % 360f
        Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 0.75f, 0.95f)))
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).height(6.dp).background(Brush.horizontalGradient(colors), RoundedCornerShape(3.dp)))
}

private fun format(v: Float, decimals: Int) = if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)

@Composable
fun NewDocumentDialog(onCreate: (Int, Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf("2000") }
    var h by remember { mutableStateOf("1500") }
    var transparent by remember { mutableStateOf(false) }
    val presets = listOf("1920×1080" to (1920 to 1080), "3000×2000" to (3000 to 2000), "A4 300 dpi" to (2480 to 3508), "1080×1080" to (1080 to 1080))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues Dokument") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, size) in presets.take(2)) FilterChip(false, { w = size.first.toString(); h = size.second.toString() }, { Text(label) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, size) in presets.drop(2)) FilterChip(false, { w = size.first.toString(); h = size.second.toString() }, { Text(label) })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Breite", w, { w = it }, Modifier.weight(1f))
                    NumberField("Höhe", h, { h = it }, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(transparent, { transparent = it })
                    Text("Transparenter Hintergrund")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ww = w.toIntOrNull()?.coerceIn(1, 30000); val hh = h.toIntOrNull()?.coerceIn(1, 30000)
                if (ww != null && hh != null) onCreate(ww, hh, transparent)
            }) { Text("Erstellen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun RenameDialog(initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ebene umbenennen") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun WarningsDialog(warnings: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hinweise zum Import") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (w in warnings) Text("• $w", Modifier.padding(vertical = 2.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** HSV-Farbwähler mit Hex-Eingabe, zuletzt verwendeten Farben und der aktiven Farbfeldbibliothek. */
@Composable
fun ColorPickerDialog(
    initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit,
    recent: List<Int> = emptyList(), palette: de.photographics.core.color.Palette? = null,
) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var s by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    var hex by remember(color) { mutableStateOf("%06X".format(color and 0xFFFFFF)) }
    val swatches = listOf(
        0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
        0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFF5D0B5, 0xFFC68642, 0xFF6D4C41,
    ).map { it.toInt() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Farbe") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(48.dp).background(Color(color), RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(8.dp))
                LabeledSlider("Farbton", h, 0f..360f) { h = it }
                LabeledSlider("Sättigung", s, 0f..1f) { s = it }
                LabeledSlider("Helligkeit", v, 0f..1f) { v = it }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { t ->
                        hex = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        if (hex.length == 6) {
                            val c = hex.toInt(16) or 0xFF000000.toInt()
                            val out = FloatArray(3); android.graphics.Color.colorToHSV(c, out)
                            h = out[0]; s = out[1]; v = out[2]
                        }
                    },
                    label = { Text("Hex") },
                    singleLine = true,
                )
                val take = { c: Int -> val o = FloatArray(3); android.graphics.Color.colorToHSV(c, o); h = o[0]; s = o[1]; v = o[2] }
                if (recent.isNotEmpty()) {
                    Text("Zuletzt verwendet", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in recent) Swatch(c) { take(c) }
                    }
                }
                Text(palette?.name ?: "Schnellfarben", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in palette?.swatches?.map { it.color } ?: swatches) Swatch(c) { take(c) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun Swatch(c: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).background(Color(c), CircleShape).border(1.dp, Color.Gray, CircleShape).clickable(onClick = onClick),
    )
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String? = null, onDone: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value, onChange, Modifier.weight(1f), valueRange = range, onValueChangeFinished = onDone)
        if (display != null) Text(display, Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    val gestures = listOf(
        "Stift" to "malt mit Druckstärke; Radier-Ende = Radiergummi; Seitentaste = Pipette",
        "Stift schwebend" to "zeigt die Pinselgröße vorab an",
        "1 Finger" to "im Stift-Modus verschieben, sonst Werkzeug",
        "2 Finger ziehen/spreizen" to "verschieben und zoomen",
        "2 Finger tippen" to "Rückgängig",
        "3 Finger tippen" to "Wiederholen",
        "Regler am linken Rand" to "Pinselgröße und Deckkraft",
        "Maus" to "Rad = Zoom, Rechts-/Mittelklick = verschieben",
    )
    val keys = listOf(
        "V / M / C" to "Verschieben / Auswahl / Freistellen",
        "B / E / I" to "Pinsel / Radiergummi / Pipette",
        "S / J / ⇧J" to "Kopierstempel / Bereichsreparatur / Reparatur-Pinsel",
        "R / ⇧R / O / ⇧O" to "Weich- / Scharfzeichner / Abwedler / Nachbelichter",
        "[  ]" to "Pinsel kleiner / größer",
        "1 … 0" to "Deckkraft 10 … 100 %",
        "Leertaste halten" to "Ansicht verschieben",
        "Strg+Z / Strg+⇧Z" to "Rückgängig / Wiederholen",
        "Strg+Y / Strg+⇧Y" to "Farbproof / Farbumfang-Warnung",
        "Strg+S / Strg+O / Strg+N" to "Speichern / Öffnen / Neu",
        "Strg+A / Strg+D / Strg+⇧I" to "Alles / Aufheben / Auswahl umkehren",
        "Strg+J / Strg+E" to "Ebene durch Kopie / Auf eine Ebene reduzieren",
        "Strg+L / Strg+U / Strg+I" to "Tonwert / Farbton/Sättigung / Umkehren",
        "Strg+0 / Strg+1 / Strg± " to "Einpassen / 100 % / Zoom",
        "Tab / F / Entf" to "Panels / Vollbild / Auswahl löschen",
        "X / D" to "Vorder- und Hintergrundfarbe tauschen / Schwarz-Weiß",
        "F5" to "Pinseleinstellungen",
        "Strg+Alt+G" to "Schnittmaske erstellen / zurückwandeln",
        "Strg+R / Strg+; / Strg+⇧+;" to "Lineale / Hilfslinien / Ausrichten ein-aus",
        "Alt+Entf / Strg+Entf" to "Mit Vordergrund- / Hintergrundfarbe füllen",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gesten & Tastaturkürzel") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Gesten", style = MaterialTheme.typography.titleSmall)
                for ((k, v) in gestures) ShortcutRow(k, v)
                Spacer(Modifier.height(12.dp))
                Text("Tastatur", style = MaterialTheme.typography.titleSmall)
                for ((k, v) in keys) ShortcutRow(k, v)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun ShortcutRow(key: String, desc: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(key, Modifier.width(150.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(desc, style = MaterialTheme.typography.bodySmall)
    }
}
