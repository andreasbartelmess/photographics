package de.photographics.app.ui

import de.photographics.core.tr
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.photographics.app.AndroidTextRasterizer
import de.photographics.core.adjust.AdjParam
import de.photographics.core.adjust.Adjustment
import de.photographics.core.adjust.CurvesAdj
import de.photographics.core.doc.TextAlign
import de.photographics.core.doc.TextStyle
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Einstellungsebenen

/** Eigenschaften einer Einstellungsebene (live); Gradationskurven mit Kurveneditor. */
@Composable
fun AdjustmentPanel(
    adjustment: Adjustment,
    onChange: (Adjustment) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tonwerte unter der Ebene für den Kurveneditor (siehe Histogram). */
    histogram: IntArray? = null,
    lutLibrary: List<de.photographics.core.adjust.ColorLookupAdj> = emptyList(),
    onLoadLut: () -> Unit = {},
    /** Schnittmaske der Einstellungsebene (null = keine Ebene, z. B. direkte Korrektur). */
    clipped: Boolean? = null,
    onToggleClip: () -> Unit = {},
) {
    val current by rememberUpdatedState(adjustment)
    PanelCard(adjustment.type.icon, adjustment.label, modifier) {
        if (clipped != null) {
            // Photoshop: „Diese Einstellung wirkt sich nur auf die darunterliegende Ebene aus“
            FilterChip(
                selected = clipped, onClick = onToggleClip,
                label = { Text(tr("Nur auf Ebene darunter")) },
                leadingIcon = { Icon(Icons.Filled.SubdirectoryArrowRight, null, Modifier.size(18.dp)) },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (adjustment is de.photographics.core.adjust.ColorLookupAdj) {
            LutChooser(adjustment, lutLibrary, onPick = onChange, onLoad = onLoadLut)
        } else if (adjustment is CurvesAdj) {
            // Kanal: 0 = RGB (Gesamtkurve), 1–3 = Rot/Grün/Blau
            var channel by remember { mutableStateOf(0) }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val labels = listOf(tr("RGB"), tr("Rot"), tr("Grün"), tr("Blau"))
                labels.forEachIndexed { i, label ->
                    val edited = i > 0 && curveOf(adjustment, i)?.let { it != IDENTITY } == true
                    SegmentedButton(
                        selected = channel == i, onClick = { channel = i },
                        shape = SegmentedButtonDefaults.itemShape(i, labels.size), icon = {},
                    ) { Text(if (edited) "$label •" else label, maxLines = 1) }
                }
            }
            val ch = channel
            CurveEditor(
                curveOf(adjustment, ch) ?: IDENTITY,
                onChange = { pts -> onChange(withCurve(current as CurvesAdj, ch, pts)) },
                histogram = histogram, channel = ch,
            )
            Text(tr("Punkt antippen/ziehen; neue Punkte durch Tippen auf die Kurve, entfernen durch Ziehen aus dem Feld."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val params = adjustment.params()
            val values = remember(adjustment.type) { mutableStateListOf(*params.map { it.value }.toTypedArray()) }
            ParamControls(params.map { it.toFilterParam() }, values) { onChange(current.withValues(values.toFloatArray())) }
            if (params.isEmpty()) Text(tr("Diese Einstellung hat keine Regler."), style = MaterialTheme.typography.bodyMedium)
        }
        PanelActions(onReset = { onChange(adjustment.type.createDefault()) }, onCancel = null, onApply = onClose, applyLabel = tr("Fertig"))
    }
}

/** Auswahl der Farbtabelle: eingebaute Looks, eigene LUT-Dateien, Datei laden. */
@Composable
private fun LutChooser(
    current: de.photographics.core.adjust.ColorLookupAdj,
    library: List<de.photographics.core.adjust.ColorLookupAdj>,
    onPick: (Adjustment) -> Unit,
    onLoad: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        for ((category, looks) in de.photographics.core.color.Looks.all.groupBy { it.category }) {
            Text(tr(category), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            for (look in looks) {
                LutRow(tr(look.name), look.description, selected = current.fileData == null && current.lutName == look.name) {
                    onPick(de.photographics.core.adjust.ColorLookupAdj(look.name))
                }
            }
        }
        if (library.isNotEmpty()) {
            Text(tr("Eigene LUTs"), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            for (l in library) {
                LutRow(l.lutName, "${l.fileFormat.lowercase()} · ${l.lut?.size ?: 0}³", selected = current.fileData != null && current.lutName == l.lutName) { onPick(l) }
            }
        }
    }
    androidx.compose.material3.OutlinedButton(onClick = onLoad, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(tr("LUT-Datei laden (.cube, .3dl) …"))
    }
    Text(tr("Die Stärke regelst du über die Deckkraft der Einstellungsebene."),
        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun LutRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun AdjParam.toFilterParam() = FilterParam(
    label, min, max, value, decimals,
    when (kind) { AdjParam.Kind.SWITCH -> ParamKind.SWITCH; AdjParam.Kind.HUE -> ParamKind.HUE; else -> ParamKind.SLIDER },
)

private val IDENTITY = listOf(0 to 0, 255 to 255)

private fun curveOf(a: CurvesAdj, channel: Int) = when (channel) { 1 -> a.red; 2 -> a.green; 3 -> a.blue; else -> a.master }

/** Kanalkurve setzen; eine gerade Kanalkurve wird wieder entfernt (null = unverändert). */
private fun withCurve(a: CurvesAdj, channel: Int, pts: List<Pair<Int, Int>>): CurvesAdj {
    val v = pts.takeIf { channel == 0 || it != IDENTITY }
    return when (channel) { 1 -> a.copy(red = v); 2 -> a.copy(green = v); 3 -> a.copy(blue = v); else -> a.copy(master = pts) }
}

/**
 * Gradationskurve mit verschiebbaren Stützpunkten (wie Photoshop „Kurven“). [channel] 0 = RGB,
 * 1–3 = Rot/Grün/Blau (Farbe von Kurve und Histogramm); [histogram] im Hintergrund (optional).
 */
@Composable
fun CurveEditor(points: List<Pair<Int, Int>>, onChange: (List<Pair<Int, Int>>) -> Unit, histogram: IntArray? = null, channel: Int = 0) {
    val bars = remember(histogram, channel) { histogram?.let { de.photographics.core.adjust.Histogram.display(it, channel) } }
    val pts by rememberUpdatedState(points.sortedBy { it.first })
    var dragging by remember { mutableStateOf(-1) }
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 8.dp)
            .background(cs.surfaceContainerLowest, RoundedCornerShape(12.dp))
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp)
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val x = (p.x / size.width * 255).roundToInt().coerceIn(0, 255)
                        val y = (255 - p.y / size.height * 255).roundToInt().coerceIn(0, 255)
                        if (pts.none { abs(it.first - x) < 8 }) onChange((pts + (x to y)).sortedBy { it.first })
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val x = p.x / size.width * 255; val y = 255 - p.y / size.height * 255
                            dragging = pts.indices.minByOrNull { abs(pts[it].first - x) + abs(pts[it].second - y) }
                                ?.takeIf { abs(pts[it].first - x) + abs(pts[it].second - y) < 40 } ?: -1
                        },
                        onDragEnd = { dragging = -1 },
                    ) { ch, _ ->
                        val i = dragging
                        if (i < 0 || i >= pts.size) return@detectDragGestures
                        ch.consume()
                        val xRaw = ch.position.x / size.width * 255
                        val yRaw = 255 - ch.position.y / size.height * 255
                        val outside = ch.position.y < -40 || ch.position.y > size.height + 40
                        val list = pts.toMutableList()
                        if (outside && i != 0 && i != list.lastIndex) {
                            list.removeAt(i); dragging = -1
                        } else {
                            // Punkte bleiben horizontal zwischen ihren Nachbarn
                            val lo = if (i == 0) 0 else list[i - 1].first + 1
                            val hi = if (i == list.lastIndex) 255 else list[i + 1].first - 1
                            list[i] = xRaw.roundToInt().coerceIn(lo, maxOf(lo, hi)) to yRaw.roundToInt().coerceIn(0, 255)
                        }
                        onChange(list)
                    }
                },
        ) {
            val w = size.width; val h = size.height
            // Raster
            for (k in 1..3) {
                drawLine(cs.outlineVariant, Offset(w * k / 4, 0f), Offset(w * k / 4, h), 1f)
                drawLine(cs.outlineVariant, Offset(0f, h * k / 4), Offset(w, h * k / 4), 1f)
            }
            val tint = when (channel) { 1 -> Color(0xFFE53935); 2 -> Color(0xFF43A047); 3 -> Color(0xFF1E88E5); else -> cs.primary }
            if (bars != null) {
                val hist = Path().apply {
                    moveTo(0f, h)
                    for (x in 0..255) lineTo(x / 255f * w, h - bars[x] * h * 0.9f)
                    lineTo(w, h); close()
                }
                drawPath(hist, (if (channel == 0) cs.onSurfaceVariant else tint).copy(alpha = 0.22f))
            }
            drawLine(cs.outline.copy(alpha = 0.5f), Offset(0f, h), Offset(w, 0f), 1f)
            val table = CurvesAdj.table(pts)
            val path = Path()
            for (x in 0..255) {
                val px = x / 255f * w; val py = h - table[x] / 255f * h
                if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, tint, style = Stroke(3f))
            for (p in pts) {
                val c = Offset(p.first / 255f * w, h - p.second / 255f * h)
                drawCircle(cs.surface, 9f, c)
                drawCircle(tint, 9f, c, style = Stroke(3f))
            }
        }
    }
}

// ---------------------------------------------------------------- Text

/** Textbearbeitung: Inhalt, Schrift, Größe, Farbe, Stil, Ausrichtung – Änderungen erscheinen sofort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPanel(style: TextStyle, onChange: (TextStyle) -> Unit, onDone: () -> Unit, onCancel: () -> Unit, onPickColor: () -> Unit, modifier: Modifier = Modifier) {
    var fontMenu by remember { mutableStateOf(false) }
    PanelCard(Icons.Filled.TextFields, tr("Text"), modifier) {
        OutlinedTextField(
            value = style.text,
            onValueChange = { onChange(style.copy(text = it)) },
            label = { Text(tr("Text")) },
            minLines = 2, maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(expanded = fontMenu, onExpandedChange = { fontMenu = it }) {
            OutlinedTextField(
                value = AndroidTextRasterizer.families.firstOrNull { it.first == style.fontFamily }?.second ?: style.fontFamily,
                onValueChange = {}, readOnly = true, singleLine = true, label = { Text(tr("Schrift")) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(fontMenu) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                for ((key, label) in AndroidTextRasterizer.families) {
                    DropdownMenuItem(
                        text = { Text(label, fontFamily = previewFamily(key)) },
                        onClick = { onChange(style.copy(fontFamily = key)); fontMenu = false },
                    )
                }
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text(tr("Größe"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(tr("{0} px", style.sizePx.roundToInt()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(kotlin.math.sqrt(style.sizePx / 1000f), { onChange(style.copy(sizePx = (it * it * 1000f).coerceAtLeast(4f))) })
        Row(Modifier.padding(top = 4.dp)) {
            Text(tr("Zeilenabstand"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("%.2f".format(style.lineHeight), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(style.lineHeight, { onChange(style.copy(lineHeight = it)) }, valueRange = 0.7f..3f)
        Row(Modifier.padding(top = 4.dp)) {
            Text(tr("Laufweite"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${(style.letterSpacing * 1000).roundToInt()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(style.letterSpacing, { onChange(style.copy(letterSpacing = it)) }, valueRange = -0.1f..0.5f)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(36.dp).background(Color(style.color), CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = onPickColor))
            Spacer(Modifier.width(8.dp))
            IconToggleButton(style.bold, { onChange(style.copy(bold = it)) }) { Icon(Icons.Filled.FormatBold, tr("Fett")) }
            IconToggleButton(style.italic, { onChange(style.copy(italic = it)) }) { Icon(Icons.Filled.FormatItalic, tr("Kursiv")) }
            Spacer(Modifier.width(8.dp))
            IconToggleButton(style.align == TextAlign.LEFT, { onChange(style.copy(align = TextAlign.LEFT)) }) { Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, tr("Links")) }
            IconToggleButton(style.align == TextAlign.CENTER, { onChange(style.copy(align = TextAlign.CENTER)) }) { Icon(Icons.Filled.FormatAlignCenter, tr("Zentriert")) }
            IconToggleButton(style.align == TextAlign.RIGHT, { onChange(style.copy(align = TextAlign.RIGHT)) }) { Icon(Icons.AutoMirrored.Filled.FormatAlignRight, tr("Rechts")) }
        }
        PanelActions(onReset = null, onCancel = onCancel, onApply = onDone, applyLabel = tr("Fertig"))
    }
}

private fun previewFamily(key: String): FontFamily = when {
    key.startsWith("serif") -> FontFamily.Serif
    key.startsWith("monospace") -> FontFamily.Monospace
    key == "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

