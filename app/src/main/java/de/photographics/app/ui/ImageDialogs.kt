package de.photographics.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.photographics.app.ai.AiModel
import de.photographics.app.ai.AiPurpose
import de.photographics.app.ai.ModelStatus
import de.photographics.core.image.ResampleMethod
import kotlin.math.roundToInt

/** Bildgröße wie in Photoshop: Pixel/Prozent, Auflösung, Proportionen, Interpolation (inkl. KI). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSizeDialog(
    width: Int, height: Int, dpi: Float, aiReady: Boolean,
    onResize: (w: Int, h: Int, method: ResampleMethod, ai: Boolean, dpi: Float) -> Unit,
    onManageAi: () -> Unit,
    onDismiss: () -> Unit,
) {
    var percent by remember { mutableStateOf(false) }
    var w by remember { mutableStateOf(width.toString()) }
    var h by remember { mutableStateOf(height.toString()) }
    var res by remember { mutableStateOf(dpi.roundToInt().toString()) }
    var keep by remember { mutableStateOf(true) }
    var method by remember { mutableStateOf<ResampleMethod?>(ResampleMethod.BICUBIC) } // null = KI
    var menu by remember { mutableStateOf(false) }

    fun px(v: String, base: Int) = v.toFloatOrNull()?.let { if (percent) (base * it / 100f).roundToInt() else it.roundToInt() }
    val nw = px(w, width); val nh = px(h, height)
    val upscaling = (nw ?: 0) > width || (nh ?: 0) > height

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bildgröße") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Aktuell: $width × $height px · ${"%.1f".format(width * height / 1e6)} MP", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!percent, { if (percent) { w = width.toString(); h = height.toString(); percent = false } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Pixel") }
                    SegmentedButton(percent, { if (!percent) { w = "100"; h = "100"; percent = true } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Prozent") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Breite", w, { v ->
                        w = v
                        if (keep) v.toFloatOrNull()?.let { if (percent) h = v else h = (it * height / width).roundToInt().coerceAtLeast(1).toString() }
                    }, Modifier.weight(1f))
                    NumberField("Höhe", h, { v ->
                        h = v
                        if (keep) v.toFloatOrNull()?.let { if (percent) w = v else w = (it * width / height).roundToInt().coerceAtLeast(1).toString() }
                    }, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it })
                    Text("Proportionen beibehalten")
                }
                NumberField("Auflösung (Pixel/Zoll)", res, { res = it }, Modifier.fillMaxWidth())
                if (nw != null && nh != null) {
                    val d = res.toFloatOrNull()?.takeIf { it > 0 } ?: 72f
                    Text("Neu: $nw × $nh px · Druckgröße ${"%.1f".format(nw / d * 2.54)} × ${"%.1f".format(nh / d * 2.54)} cm",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                    OutlinedTextField(
                        value = method?.label ?: "KI-Hochskalierung (Real-ESRGAN)",
                        onValueChange = {}, readOnly = true, label = { Text("Interpolation") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (m in ResampleMethod.entries) DropdownMenuItem(text = { Text(m.label) }, onClick = { method = m; menu = false })
                        DropdownMenuItem(
                            text = { Text("KI-Hochskalierung (Real-ESRGAN)" + if (!aiReady) " – Modell laden …" else "") },
                            onClick = { menu = false; if (aiReady) method = null else onManageAi() },
                        )
                    }
                }
                if (method == null && !upscaling) {
                    Text("KI-Hochskalierung wirkt nur beim Vergrößern; beim Verkleinern wird Lanczos verwendet.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = res.toFloatOrNull()?.takeIf { it > 0 } ?: dpi
                if (nw != null && nh != null && nw in 1..40000 && nh in 1..40000) {
                    onResize(nw, nh, method ?: ResampleMethod.LANCZOS, method == null && upscaling, d)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Arbeitsfläche mit Ankerpunkt (3×3) wie in Photoshop. */
@Composable
fun CanvasSizeDialog(width: Int, height: Int, fg: Int, bg: Int, onResize: (Int, Int, Float, Float, Int?) -> Unit, onDismiss: () -> Unit) {
    // Farbe für erweiterte Arbeitsfläche (gilt für die unterste Ebene)
    val extensions = listOf<Pair<String, Int?>>("Transparent" to null, "Hintergrund" to bg, "Vordergrund" to fg, "Weiß" to 0xFFFFFFFF.toInt(), "Schwarz" to 0xFF000000.toInt())
    var ext by remember { mutableStateOf(1) }
    var w by remember { mutableStateOf(width.toString()) }
    var h by remember { mutableStateOf(height.toString()) }
    var ax by remember { mutableStateOf(1) }
    var ay by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Arbeitsfläche") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Breite", w, { w = it }, Modifier.weight(1f))
                    NumberField("Höhe", h, { h = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text("Anker", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (yy in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (xx in 0..2) {
                            val sel = xx == ax && yy == ay
                            Box(
                                Modifier.size(40.dp)
                                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable { ax = xx; ay = yy },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Farbe für erweiterte Arbeitsfläche", style = MaterialTheme.typography.labelLarge)
                Text("Gilt für die unterste Ebene", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp),
                ) {
                    extensions.forEachIndexed { i, (label, c) ->
                        androidx.compose.material3.FilterChip(
                            selected = ext == i, onClick = { ext = i }, label = { Text(label) },
                            leadingIcon = c?.let { { Box(Modifier.size(14.dp).background(androidx.compose.ui.graphics.Color(it), RoundedCornerShape(3.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))) } },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ww = w.toIntOrNull(); val hh = h.toIntOrNull()
                if (ww != null && hh != null && ww in 1..40000 && hh in 1..40000) onResize(ww, hh, ax / 2f, ay / 2f, extensions[ext].second)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Verwaltung der lokalen KI-Modelle: Herunterladen, Auswahl, Löschen. */
@Composable
fun AiModelsDialog(
    status: Map<AiModel, ModelStatus>,
    selectedInpaint: AiModel,
    selectedUpscale: AiModel,
    highlight: AiPurpose?,
    onDownload: (AiModel) -> Unit,
    onDelete: (AiModel) -> Unit,
    onSelect: (AiModel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lokale KI-Modelle") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Die Modelle laufen vollständig auf diesem Gerät; Bilder verlassen es nicht. Heruntergeladen wird nur das Modell selbst.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (purpose in AiPurpose.entries) {
                    Spacer(Modifier.height(16.dp))
                    Text(purpose.label, style = MaterialTheme.typography.titleSmall,
                        color = if (purpose == highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    for (m in AiModel.entries.filter { it.purpose == purpose }) {
                        val st = status[m] ?: ModelStatus.Missing
                        val selected = m == (if (purpose == AiPurpose.INPAINT) selectedInpaint else selectedUpscale)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = { onSelect(m) })
                            Column(Modifier.weight(1f)) {
                                Text("${m.title} · ${if (m.sizeMb < 10) "%.1f".format(m.sizeMb) else m.sizeMb.roundToInt()} MB", style = MaterialTheme.typography.bodyLarge)
                                Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Lizenz: ${m.license}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                when (st) {
                                    is ModelStatus.Downloading -> LinearProgressIndicator({ st.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
                                    is ModelStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(4.dp))
                                        Text(st.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    else -> {}
                                }
                            }
                            when (st) {
                                ModelStatus.Ready -> {
                                    Icon(Icons.Filled.CheckCircle, "Bereit", tint = MaterialTheme.colorScheme.primary)
                                    IconButton(onClick = { onDelete(m) }) { Icon(Icons.Filled.Delete, "Löschen") }
                                }
                                is ModelStatus.Downloading -> Text("${(st.progress * 100).roundToInt()} %", style = MaterialTheme.typography.labelLarge)
                                else -> FilledTonalButton(onClick = { onDownload(m) }) {
                                    Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Laden")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

/** Optionen beim Speichern als TIFF (wie Photoshops „TIFF-Optionen“). */
@Composable
fun TiffOptionsDialog(
    compression: de.photographics.core.tiff.TiffWriter.Compression,
    layers: Boolean,
    onConfirm: (de.photographics.core.tiff.TiffWriter.Compression, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var c by remember { mutableStateOf(compression) }
    var l by remember { mutableStateOf(layers) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TIFF-Optionen") },
        text = {
            Column {
                Text("Bildkomprimierung", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    val all = de.photographics.core.tiff.TiffWriter.Compression.entries
                    all.forEachIndexed { i, v ->
                        SegmentedButton(selected = c == v, onClick = { c = v }, shape = SegmentedButtonDefaults.itemShape(i, all.size)) { Text(v.label) }
                    }
                }
                Text(
                    "LZW und ZIP verlieren keine Bildinformationen; ZIP ist meist etwas kleiner, LZW wird von mehr Programmen gelesen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().clickable { l = !l }.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(l, { l = it })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("Ebenen speichern")
                        Text(
                            "Photoshop öffnet die Datei mit allen Ebenen; andere Programme sehen das zusammengefügte Bild.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(c, l) }) { Text("Speichern …") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Zielgröße beim Freistellen: Breite × Höhe in Pixel oder cm, mit Auflösung. */
@Composable
fun CropTargetDialog(current: de.photographics.app.CropTarget?, dpi: Float, onConfirm: (de.photographics.app.CropTarget) -> Unit, onDismiss: () -> Unit) {
    var cm by remember { mutableStateOf(false) }
    var w by remember { mutableStateOf(current?.width?.toString() ?: "3000") }
    var h by remember { mutableStateOf(current?.height?.toString() ?: "2000") }
    var res by remember { mutableStateOf(((current?.dpi ?: dpi).takeIf { it > 0 } ?: 300f).toInt().toString()) }
    fun px(v: String): Int? {
        val d = v.replace(',', '.').toDoubleOrNull() ?: return null
        val r = res.toDoubleOrNull() ?: return null
        return (if (cm) d / 2.54 * r else d).let { kotlin.math.round(it).toInt() }.takeIf { it in 1..40000 }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Größe und Auflösung") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !cm, onClick = { cm = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Pixel") }
                    SegmentedButton(selected = cm, onClick = { cm = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("cm") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Breite", w, { w = it }, Modifier.weight(1f))
                    NumberField("Höhe", h, { h = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                NumberField("Auflösung (dpi)", res, { res = it }, Modifier.fillMaxWidth())
                val pw = px(w); val ph = px(h)
                if (cm && pw != null && ph != null) Text("= $pw × $ph Pixel", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Der Rahmen bekommt dieses Seitenverhältnis; nach dem Freistellen wird genau auf diese Größe umgerechnet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pw = px(w); val ph = px(h); val r = res.toFloatOrNull()
                if (pw != null && ph != null && r != null && r > 0) onConfirm(de.photographics.app.CropTarget(pw, ph, r))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Bild um einen beliebigen Winkel drehen (Photoshop: Bild → Bilddrehung → Per Eingabe). */
@Composable
fun RotateDialog(onRotate: (Float) -> Unit, onDismiss: () -> Unit) {
    var angle by remember { mutableStateOf("0") }
    var clockwise by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bild drehen") },
        text = {
            Column {
                NumberField("Winkel (°)", angle, { angle = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = clockwise, onClick = { clockwise = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Im UZS") }
                    SegmentedButton(selected = !clockwise, onClick = { clockwise = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Gegen UZS") }
                }
                Text(
                    "Die Arbeitsfläche wächst mit; freie Ecken der Hintergrundebene bekommen die Hintergrundfarbe.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { angle.replace(',', '.').toFloatOrNull()?.let { onRotate(if (clockwise) it else -it) } }) { Text("Drehen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Neue Hilfslinie an genauer Position. */
@Composable
fun NewGuideDialog(onAdd: (Boolean, Double) -> Unit, onDismiss: () -> Unit) {
    var vertical by remember { mutableStateOf(true) }
    var pos by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Hilfslinie") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = vertical, onClick = { vertical = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Senkrecht") }
                    SegmentedButton(selected = !vertical, onClick = { vertical = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Waagerecht") }
                }
                Spacer(Modifier.height(8.dp))
                NumberField("Position (px)", pos, { pos = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { pos.replace(',', '.').toDoubleOrNull()?.let { onAdd(vertical, it) } }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Hilfslinienlayout: Spalten, Zeilen, Rand und Zwischenraum (Photoshop: Neues Hilfslinienlayout). */
@Composable
fun GuideLayoutDialog(onCreate: (Int, Int, Double, Double) -> Unit, onDismiss: () -> Unit) {
    var cols by remember { mutableStateOf("3") }
    var rows by remember { mutableStateOf("3") }
    var margin by remember { mutableStateOf("0") }
    var gutter by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hilfslinienlayout") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Spalten", cols, { cols = it }, Modifier.weight(1f))
                    NumberField("Zeilen", rows, { rows = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Rand (px)", margin, { margin = it }, Modifier.weight(1f))
                    NumberField("Abstand (px)", gutter, { gutter = it }, Modifier.weight(1f))
                }
                Text("Drittelregel: 3 × 3 ohne Rand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(cols.toIntOrNull()?.coerceIn(0, 50) ?: 0, rows.toIntOrNull()?.coerceIn(0, 50) ?: 0,
                    margin.replace(',', '.').toDoubleOrNull() ?: 0.0, gutter.replace(',', '.').toDoubleOrNull() ?: 0.0)
            }) { Text("Erstellen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
