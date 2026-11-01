package de.photographics.app.ui

import de.photographics.core.tr
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FormatColorFill
import de.photographics.core.render.BlendMode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Pentagon
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness3
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.photographics.app.EditorUiState
import de.photographics.app.EditorViewModel
import de.photographics.app.Tool
import de.photographics.core.selection.SelectionOp
import de.photographics.core.selection.SelectionShape
import de.photographics.core.tools.PaintTarget
import de.photographics.core.tools.ToneRange
import kotlin.math.roundToInt

val Tool.icon: ImageVector
    get() = when (this) {
        Tool.MOVE -> Icons.Filled.OpenWith
        Tool.SELECT -> Icons.Filled.HighlightAlt
        Tool.CROP -> Icons.Filled.Crop
        Tool.BRUSH -> Icons.Filled.Brush
        Tool.ERASER -> Icons.Filled.AutoFixOff
        Tool.CLONE -> Icons.Filled.ContentCopy
        Tool.HEAL -> Icons.Filled.Healing
        Tool.SPOT_HEAL -> Icons.Filled.AutoFixHigh
        Tool.BLUR -> Icons.Filled.BlurOn
        Tool.SHARPEN -> Icons.Filled.CenterFocusStrong
        Tool.DODGE -> Icons.Filled.WbSunny
        Tool.BURN -> Icons.Filled.Brightness3
        Tool.SPONGE -> Icons.Filled.InvertColors
        Tool.EYEDROPPER -> Icons.Filled.Colorize
        Tool.TRANSFORM -> Icons.Filled.Transform
        Tool.TEXT -> Icons.Filled.TextFields
        Tool.SMUDGE -> Icons.Filled.Gesture
        Tool.PATCH -> Icons.Filled.AutoFixNormal
        Tool.RED_EYE -> Icons.Filled.RemoveRedEye
        Tool.AI_REMOVE -> Icons.Filled.AutoAwesome
        Tool.LIQUIFY -> Icons.Filled.Waves
        Tool.GRADIENT -> Icons.Filled.Gradient
        Tool.SHAPE -> Icons.Filled.Pentagon
        Tool.PEN -> Icons.Filled.Polyline
        Tool.PENCIL -> Icons.Filled.Edit
        Tool.BG_ERASER -> Icons.Filled.FormatColorReset
        Tool.MAGIC_ERASER -> Icons.Filled.CleaningServices
        Tool.BUCKET -> Icons.Filled.FormatColorFill
    }

/** Kurzname für Segmented Buttons. */
private val Tool.shortLabel: String
    get() = when (this) {
        Tool.SPOT_HEAL -> tr("Bereich")
        Tool.HEAL -> tr("Reparatur")
        Tool.CLONE -> tr("Stempel")
        Tool.ERASER -> tr("Radierer")
        Tool.BLUR -> tr("Weich")
        Tool.SHARPEN -> tr("Scharf")
        Tool.AI_REMOVE -> tr("KI")
        Tool.RED_EYE -> tr("Rote Augen")
        Tool.TRANSFORM -> tr("Transformieren")
        Tool.BG_ERASER -> tr("Hintergrund")
        Tool.MAGIC_ERASER -> tr("Magisch")
        Tool.BUCKET -> tr("Füllen")
        else -> label
    }

/** Werkzeuggruppen der Navigation Rail; Varianten wählt man in der Optionsleiste. */
enum class ToolGroup(val label: String, val tools: List<Tool>) {
    MOVE(tr("Bewegen"), listOf(Tool.MOVE, Tool.TRANSFORM)),
    SELECT(tr("Auswahl"), listOf(Tool.SELECT)),
    CROP(tr("Zuschnitt"), listOf(Tool.CROP)),
    PAINT(tr("Malen"), listOf(Tool.BRUSH, Tool.PENCIL, Tool.GRADIENT, Tool.BUCKET, Tool.EYEDROPPER)),
    ERASE(tr("Radiergummi"), listOf(Tool.ERASER, Tool.BG_ERASER, Tool.MAGIC_ERASER)),
    RETOUCH(tr("Retusche"), listOf(Tool.SPOT_HEAL, Tool.AI_REMOVE, Tool.HEAL, Tool.CLONE, Tool.PATCH, Tool.RED_EYE)),
    FOCUS(tr("Schärfe"), listOf(Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE, Tool.LIQUIFY)),
    LIGHT(tr("Licht"), listOf(Tool.DODGE, Tool.BURN, Tool.SPONGE)),
    TEXT(tr("Text"), listOf(Tool.TEXT)),
    SHAPE(tr("Formen & Pfade"), listOf(Tool.SHAPE, Tool.PEN));

    val icon: ImageVector get() = tools.first().icon
}

val Tool.group: ToolGroup get() = ToolGroup.entries.first { this in it.tools }

/**
 * Navigation Rail mit Werkzeuggruppen. Oben ein FAB mit der aktuellen Farbe.
 * [lastTool] liefert pro Gruppe das zuletzt benutzte Werkzeug.
 */
@Composable
fun ToolRail(
    state: EditorUiState,
    lastTool: (ToolGroup) -> Tool,
    onTool: (Tool) -> Unit,
    onColorClick: () -> Unit,
    onBgColorClick: () -> Unit,
    onSwapColors: () -> Unit,
    onResetColors: () -> Unit,
    onSwatches: () -> Unit = {},
    showLabels: Boolean = true,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        header = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ColorSwatchPair(state.color, state.bgColor, onColorClick, onBgColorClick, onSwapColors, onResetColors,
                    Modifier.padding(top = 8.dp))
                IconButton(onClick = onSwatches) { Icon(Icons.Filled.Palette, tr("Farbfelder")) }
            }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            val current = state.tool.group
            for (g in ToolGroup.entries) {
                val tool = if (g == current) state.tool else lastTool(g)
                NavigationRailItem(
                    selected = g == current,
                    onClick = { onTool(tool) },
                    icon = { Icon(tool.icon, contentDescription = null) },
                    label = if (showLabels) ({ Text(g.label, maxLines = 1) }) else null,
                )
            }
        }
    }
}

/**
 * Vorder- und Hintergrundfarbe wie in Photoshop: vorderes Feld = Vordergrund, hinteres =
 * Hintergrund; Pfeil tauscht (X), die kleinen Felder setzen Schwarz/Weiß (D).
 */
@Composable
fun ColorSwatchPair(
    fg: Int, bg: Int, onFg: () -> Unit, onBg: () -> Unit, onSwap: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.size(64.dp)) {
        Box(
            Modifier.offset(26.dp, 26.dp).size(34.dp).background(Color(bg), shape).border(1.5.dp, outline, shape)
                .clickable(onClickLabel = tr("Hintergrundfarbe"), onClick = onBg),
        )
        Box(
            Modifier.offset(4.dp, 4.dp).size(34.dp).background(Color(fg), shape).border(1.5.dp, outline, shape)
                .border(3.dp, MaterialTheme.colorScheme.surfaceContainer, shape)
                .clickable(onClickLabel = tr("Vordergrundfarbe"), onClick = onFg),
        )
        Icon(
            Icons.Filled.SwapHoriz, tr("Farben tauschen (X)"),
            Modifier.offset(42.dp, 2.dp).size(20.dp).clickable(onClick = onSwap),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Standardfarben: kleines schwarzes Feld über einem weißen
        Box(Modifier.offset(2.dp, 44.dp).size(20.dp).clickable(onClickLabel = tr("Standardfarben (D)"), onClick = onReset)) {
            Box(Modifier.offset(7.dp, 7.dp).size(10.dp).background(Color.White).border(1.dp, outline))
            Box(Modifier.offset(2.dp, 2.dp).size(10.dp).background(Color.Black).border(1.dp, outline))
        }
    }
}

/** Vorgaben fürs Freistellen (Photoshop: Seitenverhältnis, Überlagerung, Gerade ausrichten, Beschnittene Pixel löschen). */
val CROP_ASPECTS = listOf(
    de.photographics.app.CropAspect(tr("Original"), 0.0, 0.0),
    de.photographics.app.CropAspect(tr("1 : 1 (Quadrat)"), 1.0, 1.0),
    de.photographics.app.CropAspect("5 : 4 (8 × 10)", 5.0, 4.0),
    de.photographics.app.CropAspect(tr("3 : 2 (Kleinbild)"), 3.0, 2.0),
    de.photographics.app.CropAspect("4 : 3", 4.0, 3.0),
    de.photographics.app.CropAspect("7 : 5", 7.0, 5.0),
    de.photographics.app.CropAspect("16 : 9", 16.0, 9.0),
    de.photographics.app.CropAspect("√2 : 1 (DIN A)", kotlin.math.sqrt(2.0), 1.0),
)

@Composable
private fun CropOptions(state: EditorUiState, vm: EditorViewModel, onTargetSize: () -> Unit) {
    var aspectMenu by remember { mutableStateOf(false) }
    var overlayMenu by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { aspectMenu = true }, label = { Text(state.cropAspect?.label ?: tr("Frei")) })
        DropdownMenu(aspectMenu, { aspectMenu = false }) {
            DropdownMenuItem(text = { Text(tr("Frei")) }, onClick = { vm.setCropAspect(null); aspectMenu = false })
            for (a in CROP_ASPECTS) DropdownMenuItem(text = { Text(a.label) }, onClick = {
                aspectMenu = false
                vm.setCropAspect(if (a.label == tr("Original")) a.copy(w = maxOf(state.docWidth, state.docHeight).toDouble(), h = minOf(state.docWidth, state.docHeight).toDouble()) else a)
            })
            DropdownMenuItem(text = { Text(tr("Größe und Auflösung …")) }, onClick = { aspectMenu = false; onTargetSize() })
        }
    }
    if (state.cropAspect != null) IconButton(onClick = vm::toggleCropOrientation) {
        Icon(Icons.Filled.ScreenRotation, if (state.cropPortrait) tr("Querformat") else tr("Hochformat"))
    }
    Box {
        AssistChip(onClick = { overlayMenu = true }, label = { Text(state.cropOverlay.label) }, leadingIcon = { Icon(Icons.Filled.GridOn, null, Modifier.size(18.dp)) })
        DropdownMenu(overlayMenu, { overlayMenu = false }) {
            for (o in de.photographics.app.CropOverlay.entries) DropdownMenuItem(text = { Text(o.label) }, onClick = { vm.setCropOverlay(o); overlayMenu = false })
        }
    }
    OptionSlider(tr("Drehen"), "${"%.1f".format(state.cropAngle)}°", state.cropAngle, -45f..45f) { vm.setCropAngle(it) }
    ToggleChip(tr("Perspektivisch"), state.cropQuad != null) { vm.setCropPerspective(state.cropQuad == null) }
    if (state.cropQuad == null) ToggleChip(tr("Gerade ausrichten"), state.cropStraighten) { vm.setCropStraighten(!state.cropStraighten) }
    if (state.cropQuad != null) Hint(tr("Ecken auf die Kanten eines rechteckigen Motivs ziehen"))
    ToggleChip(tr("Pixel löschen"), state.cropDeleteCropped) { vm.setCropDeleteCropped(!state.cropDeleteCropped) }
    if (state.cropStraighten) Hint(tr("Linie entlang des Horizonts ziehen"))
}

/** Schwebende Optionsleiste (Material „Floating Toolbar“) für das aktive Werkzeug. */
@Composable
fun ToolOptionsBar(state: EditorUiState, vm: EditorViewModel, onFeatherClick: () -> Unit, onManageAi: () -> Unit, onBrushSettings: () -> Unit = {}, onTargetSize: () -> Unit = {}, onFillClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val group = state.tool.group
            if (group.tools.size > 1) {
                SingleChoiceSegmentedButtonRow {
                    group.tools.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = state.tool == t,
                            onClick = { vm.selectTool(t) },
                            shape = SegmentedButtonDefaults.itemShape(i, group.tools.size),
                            icon = { SegmentedButtonDefaults.Icon(state.tool == t) { Icon(t.icon, null, Modifier.size(18.dp)) } },
                        ) { Text(t.shortLabel, maxLines = 1) }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(state.tool.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(state.tool.label, style = MaterialTheme.typography.titleSmall)
                }
            }

            val b = state.brush
            if (state.tool.isPaintTool || state.tool == Tool.RED_EYE) {
                Divider()
                OptionSlider(tr("Größe"), tr("{0} px", b.size.roundToInt()), kotlin.math.sqrt((b.size - 1f) / 499f), 0f..1f) {
                    vm.updateBrush(b.copy(size = 1f + 499f * it * it))
                }
                OptionSlider(tr("Härte"), "${(b.hardness * 100).roundToInt()} %", b.hardness, 0f..1f) { vm.updateBrush(b.copy(hardness = it)) }
                OptionSlider(tr("Deckkraft"), "${(b.opacity * 100).roundToInt()} %", b.opacity, 0.01f..1f) { vm.updateBrush(b.copy(opacity = it)) }
                if (state.tool in setOf(Tool.BRUSH, Tool.PENCIL, Tool.ERASER, Tool.CLONE)) {
                    OptionSlider(tr("Fluss"), "${(b.flow * 100).roundToInt()} %", b.flow, 0.01f..1f) { vm.updateBrush(b.copy(flow = it)) }
                }
                OptionSlider(tr("Glättung"), "${(b.smoothing * 100).roundToInt()} %", b.smoothing, 0f..0.9f) { vm.updateBrush(b.copy(smoothing = it)) }
                if (state.tool in setOf(Tool.BRUSH, Tool.ERASER, Tool.CLONE)) {
                    IconButton(onClick = onBrushSettings) { Icon(Icons.Filled.Tune, tr("Pinseleinstellungen (F5)")) }
                }
            }

            val ft = state.fillTool
            when (state.tool) {
                Tool.BG_ERASER -> {
                    OptionSlider(tr("Toleranz"), "${ft.bgEraserTolerance}", ft.bgEraserTolerance.toFloat(), 0f..255f) { v -> vm.setFillTool { it.copy(bgEraserTolerance = v.roundToInt()) } }
                    Segmented(listOf(tr("Kontinuierlich") to false, tr("Einmal") to true), ft.bgEraserOnce) { v -> vm.setFillTool { it.copy(bgEraserOnce = v) } }
                    ToggleChip(tr("Vordergrundfarbe schützen"), ft.bgEraserProtect) { vm.setFillTool { it.copy(bgEraserProtect = !it.bgEraserProtect) } }
                }
                Tool.BUCKET, Tool.MAGIC_ERASER -> {
                    Divider()
                    if (state.tool == Tool.BUCKET) BlendChoice(ft.blend) { v -> vm.setFillTool { it.copy(blend = v) } }
                    OptionSlider(tr("Deckkraft"), "${(ft.opacity * 100).roundToInt()} %", ft.opacity, 0.01f..1f) { v -> vm.setFillTool { it.copy(opacity = v) } }
                    OptionSlider(tr("Toleranz"), "${ft.tolerance}", ft.tolerance.toFloat(), 0f..255f) { v -> vm.setFillTool { it.copy(tolerance = v.roundToInt()) } }
                    ToggleChip(tr("Glätten"), ft.antiAlias) { vm.setFillTool { it.copy(antiAlias = !it.antiAlias) } }
                    ToggleChip(tr("Benachbart"), ft.contiguous) { vm.setFillTool { it.copy(contiguous = !it.contiguous) } }
                    ToggleChip(tr("Alle Ebenen"), state.sampleAllLayers) { vm.setSampleAll(!state.sampleAllLayers) }
                    Hint(if (state.tool == Tool.BUCKET) tr("Tippen füllt ähnliche Farben mit der Vordergrundfarbe") else tr("Tippen macht ähnliche Farben transparent"))
                }
                Tool.BRUSH, Tool.PENCIL, Tool.ERASER -> {
                    val hasMask = state.layers.firstOrNull { it.id == state.activeLayerId }?.hasMask == true
                    if (hasMask) {
                        Segmented(
                            listOf(tr("Ebene") to PaintTarget.PIXELS, tr("Maske") to PaintTarget.MASK),
                            state.paintTarget,
                        ) { vm.setPaintTarget(it) }
                    }
                }
                Tool.CLONE, Tool.HEAL -> {
                    Divider()
                    ToggleChip(if (state.settingCloneSource) tr("Ins Bild tippen …") else tr("Quelle setzen"), state.settingCloneSource) { vm.startSettingCloneSource() }
                    ToggleChip(tr("Alle Ebenen"), state.sampleAllLayers) { vm.setSampleAll(!state.sampleAllLayers) }
                }
                Tool.SPOT_HEAL -> { Divider(); ToggleChip(tr("Alle Ebenen"), state.sampleAllLayers) { vm.setSampleAll(!state.sampleAllLayers) } }
                Tool.AI_REMOVE -> {
                    Divider()
                    AssistChip(onClick = onManageAi, label = { Text(tr("Modell: {0}", state.aiInpaintModel.title)) },
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) })
                    ToggleChip(tr("Alle Ebenen"), state.sampleAllLayers) { vm.setSampleAll(!state.sampleAllLayers) }
                }
                Tool.GRADIENT -> {
                    if (state.layers.firstOrNull { it.id == state.activeLayerId }?.hasMask == true)
                        Segmented(listOf(tr("Ebene") to PaintTarget.PIXELS, tr("Maske") to PaintTarget.MASK), state.paintTarget) { vm.setPaintTarget(it) }
                    Segmented(de.photographics.core.editor.GradientType.entries.map { it.label to it }, state.gradientType) { vm.setGradientType(it) }
                    Segmented(listOf(tr("Vorder- → Hintergrund") to false, tr("Vordergrund → Transparent") to true), state.gradientToTransparent) { vm.setGradientToTransparent(it) }
                    OptionSlider(tr("Deckkraft"), "${(state.gradientOpacity * 100).roundToInt()} %", state.gradientOpacity, 0.01f..1f) { vm.setGradientOpacity(it) }
                    Hint(tr("Linie im Bild ziehen · Umschalt rastet auf 45° ein"))
                }
                Tool.SMUDGE -> OptionSlider(tr("Stärke"), "${(state.toolStrength * 100).roundToInt()} %", state.toolStrength, 0.05f..1f) { vm.setToolStrength(it) }
                Tool.LIQUIFY -> {
                    Segmented(de.photographics.core.tools.LiquifyMode.entries.map { it.label to it }, state.liquifyMode) { vm.setLiquifyMode(it) }
                    OptionSlider(tr("Stärke"), "${(state.toolStrength * 100).roundToInt()} %", state.toolStrength, 0.05f..1f) { vm.setToolStrength(it) }
                }
                Tool.PATCH -> {
                    Divider()
                    Segmented(SelectionShape.entries.filter { it.isShape }.map { it.label to it }, state.selectionShape) { vm.setSelectionShape(it) }
                    Hint(if (state.hasSelection) tr("Auswahl auf eine saubere Stelle ziehen") else tr("Fehler mit der Auswahl umranden"))
                }
                Tool.RED_EYE -> Hint(tr("Pupille antippen · Suchradius = Pinselgröße ({0} px)", state.brush.size.roundToInt()))
                Tool.TEXT -> Hint(if (state.textEdit != null) tr("Text wird bearbeitet") else tr("Ins Bild tippen für neuen Text · auf Text tippen zum Bearbeiten"))
                Tool.TRANSFORM -> {
                    val t = state.transform
                    if (t != null) {
                        Segmented(de.photographics.app.TransformMode.entries.map { it.label to it }, state.transformMode) { vm.setTransformMode(it) }
                        if (t.quad == null) Text("${(t.affine.scaleX * 100).roundToInt()} % × ${(t.affine.scaleY * 100).roundToInt()} % · ${t.affine.rotationDegrees.roundToInt()}°",
                            style = MaterialTheme.typography.bodyMedium)
                        else Text(tr("verzerrt"), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.cancelTransform() }) { Text(tr("Abbrechen")) }
                        Button(onClick = { vm.applyTransform() }) { Text(tr("Anwenden")) }
                    } else Hint(tr("Ebene wird vorbereitet …"))
                }
                Tool.BLUR, Tool.SHARPEN ->
                    OptionSlider(tr("Stärke"), "${(state.toolStrength * 100).roundToInt()} %", state.toolStrength, 0.05f..1f) { vm.setToolStrength(it) }
                Tool.DODGE, Tool.BURN -> {
                    Segmented(ToneRange.entries.map { it.label to it }, state.toneRange) { vm.setToneRange(it) }
                    OptionSlider(tr("Belichtung"), "${(state.toolStrength * 100).roundToInt()} %", state.toolStrength, 0.05f..1f) { vm.setToolStrength(it) }
                }
                Tool.SPONGE -> {
                    Segmented(listOf(tr("Entsättigen") to false, tr("Sättigen") to true), state.spongeSaturate) { vm.setSpongeSaturate(it) }
                    OptionSlider(tr("Fluss"), "${(state.toolStrength * 100).roundToInt()} %", state.toolStrength, 0.05f..1f) { vm.setToolStrength(it) }
                }
                Tool.SHAPE, Tool.PEN -> {
                    val o = state.shapeOptions
                    Divider()
                    Segmented(listOf(tr("Form") to true, tr("Pfad") to false), o.layerMode) { v -> vm.setShapeOptions { it.copy(layerMode = v) } }
                    if (state.tool == Tool.SHAPE) Segmented(de.photographics.app.ShapeKind.entries.map { it.label to it }, o.kind) { v -> vm.setShapeOptions { it.copy(kind = v) } }
                    if (o.layerMode) {
                        ToggleChip(tr("Füllung"), o.fill) { vm.setShapeOptions { it.copy(fill = !it.fill) } }
                        ToggleChip(tr("Kontur"), o.stroke) { vm.setShapeOptions { it.copy(stroke = !it.stroke) } }
                        if (o.stroke) {
                            OptionSlider(tr("Konturbreite"), tr("{0} px", o.strokeWidth.roundToInt()), o.strokeWidth, 1f..100f) { v -> vm.setShapeOptions { it.copy(strokeWidth = v) } }
                            Segmented(de.photographics.core.vector.ShapeStroke.Align.entries.map { it.label to it }, o.strokeAlign) { v -> vm.setShapeOptions { it.copy(strokeAlign = v) } }
                            AssistChip(onClick = { vm.setShapeOptions { it.copy(strokeColor = state.bgColor) } }, label = { Text(tr("Konturfarbe = Hintergrundfarbe")) },
                                leadingIcon = { Box(Modifier.size(14.dp).background(androidx.compose.ui.graphics.Color(o.strokeColor), RoundedCornerShape(3.dp))) })
                        }
                    }
                    if (state.tool == Tool.SHAPE) when (o.kind) {
                        de.photographics.app.ShapeKind.RECT -> OptionSlider(tr("Eckenradius"), tr("{0} px", o.cornerRadius.roundToInt()), o.cornerRadius, 0f..500f) { v -> vm.setShapeOptions { it.copy(cornerRadius = v) } }
                        de.photographics.app.ShapeKind.POLYGON -> {
                            OptionSlider(tr("Seiten"), "${o.sides}", o.sides.toFloat(), 3f..24f) { v -> vm.setShapeOptions { it.copy(sides = v.roundToInt()) } }
                            OptionSlider(tr("Stern"), "${o.starIndent} %", o.starIndent.toFloat(), 0f..95f) { v -> vm.setShapeOptions { it.copy(starIndent = v.roundToInt()) } }
                        }
                        de.photographics.app.ShapeKind.LINE -> OptionSlider(tr("Stärke"), tr("{0} px", o.lineWidth.roundToInt()), o.lineWidth, 1f..200f) { v -> vm.setShapeOptions { it.copy(lineWidth = v) } }
                        else -> {}
                    }
                    if (state.layers.firstOrNull { it.id == state.activeLayerId }?.type == de.photographics.app.LayerType.SHAPE)
                        AssistChip(onClick = { vm.applyShapeStyleToActive() }, label = { Text(tr("Auf aktive Form anwenden")) })
                    if (state.tool == Tool.SHAPE) Hint(tr("Aufziehen · Umschalt: gleichseitig · Alt: von der Mitte"))
                    else {
                        Hint(tr("Tippen: Ecke · Ziehen: Kurve · Startpunkt: schließen · Anker der Form oder des Pfads verschieben"))
                        AssistChip(onClick = { vm.penFinish(false) }, label = { Text(tr("Fertig")) }, enabled = state.penAnchors.size >= 2)
                        AssistChip(onClick = { vm.penUndo() }, label = { Text(tr("Letzten Anker entfernen")) }, enabled = state.penAnchors.isNotEmpty())
                        AssistChip(onClick = { vm.penCancel() }, label = { Text(tr("Abbrechen")) }, enabled = state.penAnchors.isNotEmpty())
                    }
                }
                Tool.SELECT -> {
                    Divider()
                    Segmented(SelectionShape.entries.map { it.label to it }, state.selectionShape) { vm.setSelectionShape(it) }
                    Segmented(
                        listOf(tr("Neu") to SelectionOp.REPLACE, tr("Hinzu") to SelectionOp.ADD, tr("Weg") to SelectionOp.SUBTRACT),
                        state.selectionOp,
                    ) { vm.setSelectionOp(it) }
                    when (state.selectionShape) {
                        SelectionShape.WAND -> {
                            OptionSlider(tr("Toleranz"), "${state.wandTolerance}", state.wandTolerance.toFloat(), 0f..255f) { vm.setWandTolerance(it.roundToInt()) }
                            ToggleChip(tr("Benachbart"), state.wandContiguous) { vm.setWandContiguous(!state.wandContiguous) }
                            ToggleChip(tr("Alle Ebenen"), state.sampleAllLayers) { vm.setSampleAll(!state.sampleAllLayers) }
                        }
                        SelectionShape.OBJECT -> Hint(tr("Rechteck um das Objekt ziehen"))
                        SelectionShape.QUICK -> {
                            OptionSlider(tr("Größe"), tr("{0} px", b.size.roundToInt()), kotlin.math.sqrt((b.size - 1f) / 499f), 0f..1f) { vm.updateBrush(b.copy(size = 1f + 499f * it * it)) }
                            ToggleChip(tr("Alle Ebenen"), state.sampleAllLayers) { vm.setSampleAll(!state.sampleAllLayers) }
                            Hint(tr("Über den Bereich malen · weitere Striche fügen hinzu"))
                        }
                        SelectionShape.POLYGON -> {
                            Hint(tr("Tippen setzt Ecken · auf den Startpunkt tippen schließt · Umschalt: 45°"))
                            AssistChip(onClick = { vm.polyClose() }, label = { Text(tr("Schließen")) }, enabled = state.polyPoints.size >= 6)
                            AssistChip(onClick = { vm.polyUndo() }, label = { Text(tr("Letzte Ecke entfernen")) }, enabled = state.polyPoints.isNotEmpty())
                            AssistChip(onClick = { vm.polyCancel() }, label = { Text(tr("Abbrechen")) }, enabled = state.polyPoints.isNotEmpty())
                        }
                        else -> {}
                    }
                    OptionSlider(tr("Weiche Kante"), tr("{0} px", state.featherRadius.roundToInt()), state.featherRadius, 0f..100f) { vm.setFeather(it) }
                    Divider()
                    AssistChip(onClick = { vm.selectAll() }, label = { Text(tr("Alles")) })
                    AssistChip(onClick = { vm.invertSelection() }, label = { Text(tr("Umkehren")) }, enabled = state.hasSelection)
                    AssistChip(onClick = onFeatherClick, label = { Text(tr("Kante verbessern")) }, enabled = state.hasSelection)
                    AssistChip(onClick = { vm.clearSelected() }, label = { Text(tr("Löschen")) }, enabled = state.hasSelection)
                    AssistChip(onClick = onFillClick, label = { Text(tr("Füllen …")) })
                    AssistChip(onClick = { vm.layerViaCopy() }, label = { Text(tr("Ebene durch Kopie")) })
                    AssistChip(onClick = { vm.deselect() }, label = { Text(tr("Aufheben")) }, enabled = state.hasSelection)
                }
                Tool.CROP -> {
                    CropOptions(state, vm, onTargetSize)
                    val r = state.cropRect
                    if (r != null) Text(state.cropTarget?.let { tr("{0} × {1} px", it.width, it.height) } ?: tr("{0} × {1} px", r.width, r.height), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.cancelCrop() }) { Text(tr("Abbrechen")) }
                    Button(onClick = { vm.applyCrop() }) { Text(tr("Freistellen")) }
                }
                Tool.MOVE -> {
                    ToggleChip(tr("Automatisch auswählen"), state.autoSelectLayer) { vm.setAutoSelectLayer(!state.autoSelectLayer) }
                    Hint(if (state.autoSelectLayer) tr("Angetippte Ebene ziehen · Strg: aktive Ebene · zwei Finger: Ansicht")
                        else tr("Aktive Ebene ziehen · Strg: angetippte Ebene · zwei Finger: Ansicht"))
                }
                Tool.EYEDROPPER -> {
                    FilterChip(
                        selected = state.eyedropperToBg, onClick = { vm.setEyedropperToBg(!state.eyedropperToBg) },
                        label = { Text(tr("In Hintergrundfarbe")) },
                    )
                    Hint(tr("Tippen, um eine Farbe aufzunehmen · Alt: Hintergrundfarbe"))
                }
            }
        }
    }
}

@Composable
private fun Divider() = VerticalDivider(Modifier.height(32.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label) },
    leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
)

@Composable
private fun <T> Segmented(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { i, (label, value) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** Beschrifteter Regler für die Optionsleiste. */
@Composable
private fun OptionSlider(label: String, display: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.width(168.dp)) {
        Row(Modifier.padding(horizontal = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value, onChange, valueRange = range)
    }
}

/** Mischmodus als Auswahlknopf mit Menü. */
@Composable
private fun BlendChoice(mode: BlendMode, onChange: (BlendMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(mode.label) }
        DropdownMenu(open, { open = false }) {
            for (m in de.photographics.core.doc.LayerStyles.blendModes) DropdownMenuItem(text = { Text(m.label) }, onClick = { open = false; onChange(m) })
        }
    }
}
