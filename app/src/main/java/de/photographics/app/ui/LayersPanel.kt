package de.photographics.app.ui

import de.photographics.core.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FilterFrames
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.photographics.app.EditorUiState
import de.photographics.app.EditorViewModel
import de.photographics.app.LayerItem
import de.photographics.app.LayerType
import de.photographics.core.doc.LayerKind
import de.photographics.core.render.BlendMode
import de.photographics.core.tools.PaintTarget
import kotlin.math.roundToInt

/**
 * Ebenen als Material Side Sheet: Kopf mit Aktionen, Eigenschaften der aktiven Ebene,
 * Liste (oben = oberste Ebene) und Werkzeugleiste am unteren Rand.
 */
@Composable
fun LayersPanel(
    state: EditorUiState,
    vm: EditorViewModel,
    modifier: Modifier = Modifier,
    width: Dp = 320.dp,
    shape: Shape = RoundedCornerShape(0.dp),
    elevated: Boolean = false,
    onAddAdjustment: () -> Unit = {},
) {
    var renameTarget by remember { mutableStateOf<LayerItem?>(null) }
    var styleTarget by remember { mutableStateOf<Pair<Int, de.photographics.core.doc.LayerEffects>?>(null) }
    val openStyle = { id: Int -> vm.openLayerStyle(id) { styleTarget = id to it } }
    val active = state.layers.firstOrNull { it.id == state.activeLayerId }

    Surface(
        modifier.width(width).fillMaxHeight(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = if (elevated) 6.dp else 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Auf niedrigen Bildschirmen (Handy quer) sind die Eigenschaften eingeklappt,
        // damit die Ebenenliste Platz behält
        val roomy = maxHeight >= 560.dp
        var showProps by remember(roomy) { mutableStateOf(roomy) }
        var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize()) {
            // Ebenen | Kanäle | Pfade (wie Photoshops Bedienfeldgruppe)
            androidx.compose.material3.SecondaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                for ((k, t) in listOf(tr("Ebenen"), tr("Kanäle"), tr("Pfade")).withIndex()) androidx.compose.material3.Tab(tab == k, { tab = k }, text = { Text(t) })
            }
            if (tab == 1) ChannelsPane(state, vm) else if (tab == 2) PathsPane(state, vm) else Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (active != null) {
                    IconToggleButton(checked = showProps, onCheckedChange = { showProps = it }) {
                        Icon(Icons.Filled.Tune, tr("Ebeneneigenschaften"))
                    }
                }
                if (active != null && active.type.hasStyle) TipIconButton(Icons.Filled.AutoAwesome, tr("Ebenenstil")) { openStyle(active.id) }
                TipIconButton(Icons.Filled.Contrast, tr("Neue Einstellungsebene")) { onAddAdjustment() }
                TipIconButton(Icons.Filled.CreateNewFolder, tr("Neue Gruppe")) { vm.addGroup() }
                TipIconButton(Icons.Filled.Add, tr("Neue Ebene")) { vm.addLayer() }
            }

            if (active != null && showProps) ActiveLayerProperties(active, vm)

            // Neue/aktive Ebene immer sichtbar halten (z. B. nach dem Einfügen oberhalb)
            val listState = rememberLazyListState()
            // Drag & Drop: langes Drücken auf eine Zeile hebt sie an
            var drag by remember { mutableStateOf<LayerDragState?>(null) }
            val layersNow = rememberUpdatedState(state.layers)
            LaunchedEffect(state.activeLayerId, state.layers.size) {
                val idx = state.layers.indexOfFirst { it.id == state.activeLayerId }
                if (idx >= 0) {
                    val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == idx && it.offset >= 0 }
                    if (!visible) listState.animateScrollToItem(idx)
                }
            }
            val lineColor = MaterialTheme.colorScheme.primary
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 8.dp).drawWithContent {
                    drawContent()
                    // Einfügestelle über allen Zeilen (auch über der gezogenen)
                    val (tid, where) = drag?.target ?: return@drawWithContent
                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tid } ?: return@drawWithContent
                    val w = 3.dp.toPx(); val inset = (8 + (layersNow.value.firstOrNull { it.id == tid }?.depth ?: 0) * 20).dp.toPx()
                    val top = info.offset.toFloat(); val bottom = (info.offset + info.size).toFloat()
                    when (where) {
                        EditorViewModel.LayerDrop.ABOVE -> drawRoundRect(lineColor, Offset(inset, top - w / 2 - 1.dp.toPx()), Size(size.width - inset, w), CornerRadius(w / 2))
                        EditorViewModel.LayerDrop.BELOW -> drawRoundRect(lineColor, Offset(inset, bottom - w / 2 + 1.dp.toPx()), Size(size.width - inset, w), CornerRadius(w / 2))
                        EditorViewModel.LayerDrop.INTO -> drawRoundRect(lineColor, Offset(0f, top), Size(size.width, bottom - top), CornerRadius(16.dp.toPx()), style = Stroke(w))
                    }
                },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.layers, key = { it.id }) { item ->
                    val d = drag
                    val dragging = d?.id == item.id
                    Box(
                        Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                if (dragging && d != null) {
                                    val cur = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id }?.offset ?: 0
                                    translationY = d.pointerY - d.grab - cur
                                    shadowElevation = 8.dp.toPx(); this.shape = RoundedCornerShape(16.dp); clip = true
                                    alpha = 0.92f
                                }
                            }
                            .pointerInput(item.id) {
                                // Maus: sofort ziehen (nach kurzer Mindeststrecke); Finger/Stift: erst nach langem
                                // Drücken, damit die Liste mit dem Finger scrollbar bleibt
                                fun start(y: Float): Boolean {
                                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id } ?: return false
                                    vm.selectLayer(item.id)
                                    drag = LayerDragState(item.id, info.offset + y, y, layersNow.value.descendantsOf(item.id))
                                    return true
                                }
                                fun move(dy: Float) {
                                    val st = drag ?: return
                                    st.pointerY += dy
                                        // Am Rand der Liste mitscrollen
                                        val li = listState.layoutInfo
                                        val edge = 56.dp.toPx()
                                        val endY = li.viewportEndOffset.toFloat()
                                        if (st.pointerY < edge) listState.dispatchRawDelta(-(edge - st.pointerY) / 4f)
                                        else if (st.pointerY > endY - edge) listState.dispatchRawDelta((st.pointerY - (endY - edge)) / 4f)
                                    st.target = dropTarget(listState, layersNow.value, st)
                                }
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val first = if (down.type == PointerType.Mouse) {
                                        var acc = 0f
                                        awaitTouchSlopOrCancellation(down.id) { c, over -> c.consume(); acc = over.y }?.also { start(down.position.y); move(acc) }
                                    } else {
                                        awaitLongPressOrCancellation(down.id)?.also { start(it.position.y) }
                                    }
                                    if (first == null || drag == null) return@awaitEachGesture
                                    val done = drag(first.id) { c -> move(c.positionChange().y); c.consume() }
                                    drag?.let { st -> if (done) st.target?.let { (t, w) -> vm.dropLayer(st.id, t, w) } }
                                    drag = null
                                }
                            },
                    ) {
                        LayerRow(
                            item = item,
                            selected = item.id == state.activeLayerId,
                            maskTargeted = item.id == state.activeLayerId && state.paintTarget == PaintTarget.MASK,
                            vm = vm,
                            onRename = { renameTarget = item },
                            onStyle = { openStyle(item.id) },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val enabled = active != null
                TipTonalButton(Icons.Filled.ContentCopy, tr("Duplizieren"), enabled) { vm.duplicateLayer() }
                TipTonalButton(Icons.Filled.ArrowUpward, tr("Nach oben"), enabled) { active?.let { vm.moveLayer(it.id, up = true) } }
                TipTonalButton(Icons.Filled.ArrowDownward, tr("Nach unten"), enabled) { active?.let { vm.moveLayer(it.id, up = false) } }
                TipTonalButton(Icons.Filled.VerticalAlignBottom, tr("Auf darunterliegende reduzieren"), enabled && active?.isGroup == false) { vm.mergeDown() }
                TipTonalButton(Icons.Filled.Gradient, tr("Maske hinzufügen"), enabled && active?.hasMask == false) { vm.addMask(fromSelection = state.hasSelection) }
                TipTonalButton(Icons.Filled.Delete, tr("Löschen"), enabled) { vm.deleteLayer() }
            }
        }
        }
        }
    }

    renameTarget?.let { t ->
        RenameDialog(t.name, onRename = { vm.renameLayer(t.id, it); renameTarget = null }, onDismiss = { renameTarget = null })
    }
    styleTarget?.let { (id, fx) ->
        LayerStyleDialog(fx, onPreview = { vm.previewLayerStyle(id, it) }, onFinish = { vm.finishLayerStyle(id, it); styleTarget = null })
    }
}

/** Laufender Ziehvorgang: Zeiger-Y im Listen-Viewport, Griffpunkt in der Zeile, ungültige Ziele (eigene Unterebenen). */
private class LayerDragState(val id: Int, pointerY: Float, val grab: Float, val excluded: Set<Int>) {
    var pointerY by mutableFloatStateOf(pointerY)
    var target by mutableStateOf<Pair<Int, EditorViewModel.LayerDrop>?>(null)
}

/** Ids der in der Liste unter einer aufgeklappten Gruppe [id] angezeigten Ebenen. */
private fun List<LayerItem>.descendantsOf(id: Int): Set<Int> {
    val i = indexOfFirst { it.id == id }
    if (i < 0 || !this[i].isGroup) return emptySet()
    val depth = this[i].depth
    return drop(i + 1).takeWhile { it.depth > depth }.map { it.id }.toSet()
}

/** Zielzeile und Ablageort unter dem Zeiger; Gruppen nehmen im mittleren Bereich die Ebene auf. */
private fun dropTarget(list: LazyListState, layers: List<LayerItem>, st: LayerDragState): Pair<Int, EditorViewModel.LayerDrop>? {
    val vis = list.layoutInfo.visibleItemsInfo
    if (vis.isEmpty()) return null
    val y = st.pointerY
    val info = vis.firstOrNull { y >= it.offset && y < it.offset + it.size }
        ?: if (y < vis.first().offset) vis.first() else vis.last()
    val id = info.key as? Int ?: return null
    if (id == st.id || id in st.excluded) return null
    val item = layers.firstOrNull { it.id == id } ?: return null
    val rel = ((y - info.offset) / info.size).coerceIn(0f, 1f)
    val where = when {
        item.isGroup && rel in 0.3f..0.7f -> EditorViewModel.LayerDrop.INTO
        rel < 0.5f -> EditorViewModel.LayerDrop.ABOVE
        else -> EditorViewModel.LayerDrop.BELOW
    }
    return id to where
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TipIconButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TipTonalButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveLayerProperties(item: LayerItem, vm: EditorViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = item.blendMode.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(tr("Mischmodus")) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val modes = if (item.isGroup) BlendMode.entries else BlendMode.layerModes
                for (m in modes) {
                    DropdownMenuItem(
                        text = { Text(m.label) },
                        onClick = { vm.setBlendMode(item.id, m); expanded = false },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        PropertySlider(tr("Deckkraft"), item.opacity, onDone = { vm.endOpacityEdit() }) { vm.setOpacityLive(item.id, opacity = it) }
        PropertySlider(tr("Fläche"), item.fillOpacity, onDone = { vm.endOpacityEdit() }) { vm.setOpacityLive(item.id, fill = it) }
        if (item.kind != LayerKind.PIXEL) {
            Text(item.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun PropertySlider(label: String, value: Float, onDone: () -> Unit, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(76.dp))
        Slider(value, onChange, Modifier.weight(1f), onValueChangeFinished = onDone)
        Text("${(value * 100).roundToInt()} %", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp).padding(start = 8.dp))
    }
}

@Composable
private fun LayerRow(item: LayerItem, selected: Boolean, maskTargeted: Boolean, vm: EditorViewModel, onRename: () -> Unit, onStyle: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) cs.secondaryContainer else Color.Transparent)
            .clickable {
                vm.selectLayer(item.id); vm.setPaintTarget(PaintTarget.PIXELS)
                if (item.type == LayerType.ADJUSTMENT) vm.openAdjustment(item.id)
            }
            .heightIn(min = 64.dp)
            .padding(start = (8 + item.depth * 20).dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isGroup) {
            IconButton(onClick = { vm.toggleExpanded(item.id) }, modifier = Modifier.size(32.dp)) {
                Icon(if (item.expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, tr("Auf-/Zuklappen"))
            }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Folder, null, tint = cs.onTertiaryContainer)
            }
        } else if (item.type == LayerType.ADJUSTMENT) {
            if (item.clipped) Text("↳", Modifier.padding(end = 4.dp), color = cs.onSurfaceVariant)
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Contrast, null, tint = cs.onSecondaryContainer)
            }
        } else {
            if (item.clipped) Text("↳", Modifier.padding(end = 4.dp), color = cs.onSurfaceVariant)
            Box {
                Thumb(item.thumb, highlighted = selected && !maskTargeted && item.hasMask)
                // Kennzeichnung für Text und Smartobjekte
                val badge = when (item.type) { LayerType.TEXT -> Icons.Filled.TextFields; LayerType.SMART -> Icons.Filled.FilterFrames; else -> null }
                if (badge != null) {
                    Icon(badge, null, Modifier.align(Alignment.BottomEnd).size(18.dp).clip(RoundedCornerShape(5.dp)).background(cs.primary).padding(2.dp), tint = cs.onPrimary)
                }
            }
        }
        if (item.hasMask) {
            // Kettensymbol: Maske mit der Ebene verbunden – antippen löst/verbindet
            Icon(
                if (item.maskLinked) Icons.Filled.Link else Icons.Filled.LinkOff,
                if (item.maskLinked) tr("Maske von Ebene lösen") else tr("Maske mit Ebene verbinden"),
                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).clickable { vm.toggleMaskLink(item.id) }.padding(1.dp),
                tint = if (item.maskLinked) cs.onSurfaceVariant else cs.error,
            )
            Box(Modifier.clickable { vm.selectLayer(item.id); vm.setPaintTarget(PaintTarget.MASK) }) {
                Thumb(item.maskThumb, highlighted = maskTargeted, dimmed = !item.maskEnabled)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                // Basis einer Schnittmaske: unterstrichen wie in Photoshop
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (item.clipBase) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                ),
                color = if (selected) cs.onSecondaryContainer else cs.onSurface,
            )
            val info = buildString {
                if (item.blendMode != BlendMode.NORMAL && item.blendMode != BlendMode.PASS_THROUGH) append(item.blendMode.label).append(" · ")
                if (item.opacity < 1f) append("${(item.opacity * 100).roundToInt()} % · ")
                if (item.detail != null) append(item.detail)
                else if (item.kind != LayerKind.PIXEL) append(item.kind.label.substringBefore(" ("))
            }.trimEnd(' ', '·')
            if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
        }
        if (item.lockTransparency) Icon(Icons.Filled.Lock, tr("Transparenz fixiert"), Modifier.size(16.dp), tint = cs.onSurfaceVariant)
        IconButton(onClick = { vm.toggleVisibility(item.id) }) {
            Icon(
                if (item.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, tr("Sichtbarkeit"),
                tint = if (item.visible) cs.onSurfaceVariant else cs.outline,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Mehr")) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun act(label: String, f: () -> Unit) = DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; vm.selectLayer(item.id); f() })
                act(tr("Umbenennen …"), onRename)
                if (item.type.hasStyle) act(tr("Ebenenstil") + " …", onStyle)
                if (item.type.hasStyle) act(tr("Ebenenstil rastern")) { vm.rasterizeStyle(item.id) }
                act(if (item.clipped) tr("Schnittmaske zurückwandeln (Strg+Alt+G)") else tr("Schnittmaske erstellen (Strg+Alt+G)")) { vm.toggleClipping(item.id) }
                if (item.hasMask) act(if (item.maskLinked) tr("Maske von Ebene lösen") else tr("Maske mit Ebene verbinden")) { vm.toggleMaskLink(item.id) }
                act(if (item.lockTransparency) tr("Transparenz freigeben") else tr("Transparenz fixieren")) { vm.toggleLockTransparency(item.id) }
                act(tr("In Gruppe verschieben")) { vm.groupActive() }
                when (item.type) {
                    LayerType.PIXEL -> act(tr("In Smartobjekt konvertieren")) { vm.convertToSmart(item.id) }
                    LayerType.TEXT -> { act(tr("Text bearbeiten")) { vm.editTextLayer(item.id) }; act(tr("Ebene rastern")) { vm.rasterizeLayer(item.id) } }
                    LayerType.SMART -> act(tr("Ebene rastern")) { vm.rasterizeLayer(item.id) }
                    LayerType.ADJUSTMENT -> act(tr("Einstellungen bearbeiten")) { vm.openAdjustment(item.id) }
                    else -> {}
                }
                if (!item.isGroup) act(tr("Auf darunterliegende reduzieren")) { vm.mergeDown() }
                HorizontalDivider()
                if (!item.hasMask) {
                    act(tr("Maske hinzufügen (alles zeigen)")) { vm.addMask(fromSelection = false) }
                    act(tr("Maske aus Auswahl")) { vm.addMask(fromSelection = true) }
                } else {
                    act(if (item.maskEnabled) tr("Maske deaktivieren") else tr("Maske aktivieren")) { vm.toggleMaskEnabled(item.id) }
                    if (!item.isGroup) act(tr("Maske anwenden")) { vm.applyMask() }
                    act(tr("Maske löschen")) { vm.deleteMask() }
                }
                HorizontalDivider()
                act(tr("Duplizieren")) { vm.duplicateLayer() }
                act(tr("Löschen")) { vm.deleteLayer() }
            }
        }
    }
}

@Composable
private fun Thumb(bmp: ImageBitmap?, highlighted: Boolean, dimmed: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(48.dp).clip(shape).background(Color(0xFFCFCFCF))
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) cs.primary else cs.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit, alpha = if (dimmed) 0.35f else 1f)
        }
    }
}
