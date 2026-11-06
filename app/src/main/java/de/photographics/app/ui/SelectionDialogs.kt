package de.photographics.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.photographics.app.EditorViewModel
import de.photographics.core.selection.ColorRange
import de.photographics.core.selection.SelectionModifyKind
import de.photographics.core.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Letzte Einstellungen des Farbbereichs (wie Photoshop beim nächsten Öffnen). */
private var lastColorRange: ColorRange.Spec? = null
private val lastModify = HashMap<SelectionModifyKind, Float>()
private var lastModifyEdge = false

/** Auswahl → Auswahl verändern: Art, Radius/Breite, optional am Arbeitsflächenrand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModifySelectionDialog(onApply: (SelectionModifyKind, Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(SelectionModifyKind.EXPAND) }
    var amount by remember { mutableFloatStateOf(lastModify[SelectionModifyKind.EXPAND] ?: 10f) }
    var edge by remember { mutableStateOf(lastModifyEdge) }
    val max = if (kind == SelectionModifyKind.BORDER) 200f else 500f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Auswahl verändern")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val kinds = listOf(SelectionModifyKind.EXPAND to tr("Auswahl|Erweitern"), SelectionModifyKind.CONTRACT to tr("Auswahl|Verkleinern"),
                    SelectionModifyKind.SMOOTH to tr("Auswahl|Abrunden"), SelectionModifyKind.BORDER to tr("Auswahl|Rand"))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { i, (k, label) ->
                        SegmentedButton(selected = kind == k, onClick = {
                            kind = k; amount = lastModify[k] ?: if (k == SelectionModifyKind.SMOOTH) 5f else 10f
                        }, shape = SegmentedButtonDefaults.itemShape(i, kinds.size)) { Text(label, maxLines = 1) }
                    }
                }
                // Feiner Bereich unten: Quadratwurzel-Skala (1 … max px)
                val t = kotlin.math.sqrt((amount.coerceIn(1f, max) - 1f) / (max - 1f))
                LabeledSlider(if (kind == SelectionModifyKind.BORDER) tr("Breite") else tr("Radius"), t, 0f..1f, tr("{0} px", amount.roundToInt())) {
                    amount = (1f + it * it * (max - 1f)).roundToInt().toFloat()
                }
                // Immer da (bei Erweitern ausgegraut), damit der Dialog beim Umschalten nicht springt
                val edgeOn = kind != SelectionModifyKind.EXPAND
                Row(Modifier.clickable(enabled = edgeOn) { edge = !edge }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(edge && edgeOn, { edge = it }, enabled = edgeOn)
                    Text(tr("Effekt am Arbeitsflächenrand anwenden"), style = MaterialTheme.typography.bodyMedium,
                        color = if (edgeOn) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            lastModify[kind] = amount; lastModifyEdge = edge
            onApply(kind, amount.roundToInt(), kind != SelectionModifyKind.EXPAND && edge)
        }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/**
 * Auswahl → Farbbereich: Vorschau (Auswahl als Graustufen oder das Bild), Farben durch Antippen
 * aufnehmen – je nach Modus ersetzen, hinzufügen oder abziehen; Vorgaben, Toleranz, Umkehren.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorRangeDialog(vm: EditorViewModel, sampleAll0: Boolean, onDismiss: () -> Unit) {
    var spec by remember { mutableStateOf(lastColorRange ?: ColorRange.Spec()) }
    var sampleAll by remember { mutableStateOf(sampleAll0) }
    var source by remember { mutableStateOf<EditorViewModel.ColorRangeSource?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var showImage by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(0) }   // 0 = ersetzen, 1 = hinzufügen, 2 = abziehen
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val close = { vm.colorRangeClosed(); onDismiss() }

    LaunchedEffect(sampleAll) { source = vm.colorRangeSource(sampleAll, 900, 700) ?: run { close(); return@LaunchedEffect } }
    LaunchedEffect(source, spec, showImage) {
        val s = source ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            // Noch keine Farbe aufgenommen: Bild zeigen, damit man sieht, wohin man tippt
            val img = showImage || (spec.preset == ColorRange.Preset.SAMPLED && spec.samples.isEmpty())
            val px = if (img) s.px else ColorRange.preview(s.px, spec)
            Bitmap.createBitmap(px, s.w, s.h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    fun sample(x: Float, y: Float) {
        val s = source ?: return
        if (boxSize.width <= 0) return
        val k = minOf(boxSize.width.toFloat() / s.w, boxSize.height.toFloat() / s.h)
        val ix = ((x - (boxSize.width - s.w * k) / 2) / k).toInt(); val iy = ((y - (boxSize.height - s.h * k) / 2) / k).toInt()
        if (ix !in 0 until s.w || iy !in 0 until s.h) return
        var r = 0; var g = 0; var b = 0; var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = ix + dx; val yy = iy + dy
            if (xx !in 0 until s.w || yy !in 0 until s.h) continue
            val c = s.px[yy * s.w + xx]; if (c ushr 24 == 0) continue
            r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++
        }
        if (n == 0) return
        val sm = ColorRange.Sample((0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n), subtract = pick == 2)
        spec = spec.copy(preset = ColorRange.Preset.SAMPLED, samples = if (pick == 0) listOf(sm) else spec.samples + sm)
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("Farbbereich")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .onSizeChanged { boxSize = it }
                    .pointerInput(source, pick, spec.samples) { detectTapGestures { o -> sample(o.x, o.y) } },
                    contentAlignment = Alignment.Center) {
                    val p = preview
                    if (p == null) CircularProgressIndicator()
                    else Image(p, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                }
                Choice(tr("Auswahl"), ColorRange.Preset.entries, spec.preset, { it.label }) { spec = spec.copy(preset = it) }
                if (spec.preset == ColorRange.Preset.SAMPLED) {
                    val modes = listOf(tr("Pipette"), tr("Hinzufügen"), tr("Abziehen"))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { i, label ->
                            SegmentedButton(selected = pick == i, onClick = { pick = i }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label, maxLines = 1) }
                        }
                    }
                    Text(tr("Ins Bild tippen: Farbe aufnehmen."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LabeledSlider(tr("Toleranz"), spec.fuzziness.toFloat(), 0f..200f, spec.fuzziness.toString()) { spec = spec.copy(fuzziness = it.roundToInt()) }
                Check(tr("Umkehren"), spec.invert) { spec = spec.copy(invert = it) }
                Check(tr("Alle Ebenen"), sampleAll) { sampleAll = it; source = null; preview = null }
                Check(tr("Bild zeigen"), showImage) { showImage = it }
            }
        },
        confirmButton = { TextButton(enabled = source != null, onClick = {
            lastColorRange = spec.copy(samples = if (spec.preset == ColorRange.Preset.SAMPLED) spec.samples else emptyList())
            vm.colorRangeApply(spec, sampleAll); onDismiss()
        }) { Text("OK") } },
        dismissButton = { TextButton(onClick = close) { Text(tr("Abbrechen")) } },
    )
}

@Composable
private fun Check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun <T> Choice(label: String, items: List<T>, value: T, name: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(name(value)) }
            DropdownMenu(open, { open = false }) { for (item in items) DropdownMenuItem(text = { Text(name(item)) }, onClick = { open = false; onPick(item) }) }
        }
    }
}
