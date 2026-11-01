package de.photographics.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.photographics.app.EditorUiState
import de.photographics.app.EditorViewModel
import de.photographics.core.tr

/** Eine Zeile wie im Ebenenbedienfeld: gewählt hervorgehoben, langes Drücken = umbenennen. */
@Composable
private fun PaneRow(text: String, selected: Boolean, dot: Color? = null, dim: Boolean = false, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) { Box(Modifier.size(12.dp).background(dot, RoundedCornerShape(3.dp))); Spacer(Modifier.width(12.dp)) }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Commands(content: @Composable () -> Unit) {
    HorizontalDivider()
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Kanäle: RGB und Farbkanäle ansehen, Alphakanäle (gespeicherte Auswahlen) laden und verwalten. */
@Composable
fun ColumnScope.ChannelsPane(state: EditorUiState, vm: EditorViewModel) {
    var alpha by remember { mutableStateOf(-1) }
    var rename by remember { mutableStateOf<Int?>(null) }
    LazyColumn(Modifier.weight(1f)) {
        val base = listOf(Triple("RGB", null, 0), Triple(tr("Rot"), Color(0xFFE03030), 1), Triple(tr("Grün"), Color(0xFF30B040), 2), Triple(tr("Blau"), Color(0xFF3060E0), 3))
        itemsIndexed(base) { _, (n, dot, v) -> PaneRow(n, alpha < 0 && state.channelView == v, dot, onClick = { alpha = -1; vm.setChannelView(v) }) }
        itemsIndexed(state.channelNames) { i, n -> PaneRow(n, alpha == i, Color.Gray, onClick = { alpha = i; vm.setChannelView(0) }, onLong = { rename = i }) }
    }
    if (state.channelNames.isEmpty()) Text(tr("Mit „Auswahl speichern“ entsteht ein Alphakanal."), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    Commands {
        AssistChip(onClick = { vm.saveSelectionChannel() }, label = { Text(tr("Auswahl speichern")) }, enabled = state.hasSelection)
        AssistChip(onClick = { vm.channelToSelection(alpha) }, label = { Text(tr("Als Auswahl laden")) }, enabled = alpha >= 0)
        AssistChip(onClick = { vm.deleteChannel(alpha); alpha = -1 }, label = { Text(tr("Löschen")) }, enabled = alpha >= 0)
    }
    rename?.let { i -> RenameDialog(state.channelNames.getOrElse(i) { "" }, onRename = { vm.renameChannel(i, it); rename = null }, onDismiss = { rename = null }) }
}

/** Pfade: Arbeitspfad und gesicherte Pfade; als Auswahl laden, füllen, Kontur, aus Auswahl, speichern, löschen. */
@Composable
fun ColumnScope.PathsPane(state: EditorUiState, vm: EditorViewModel) {
    var rename by remember { mutableStateOf<Int?>(null) }
    var saveWork by remember { mutableStateOf(false) }
    LazyColumn(Modifier.weight(1f)) {
        if (state.workPath != null) item { PaneRow(tr("Arbeitspfad"), state.selectedPath < 0, dim = true, onClick = { vm.selectPath(-1) }, onLong = { saveWork = true }) }
        itemsIndexed(state.paths) { i, p -> PaneRow(p.name, state.selectedPath == i, onClick = { vm.selectPath(i) }, onLong = { rename = i }) }
    }
    val has = state.workPath != null || state.paths.isNotEmpty()
    if (!has) Text(tr("Noch keine Pfade – Zeichenstift oder Formwerkzeug im Modus „Pfad“"), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    Commands {
        AssistChip(onClick = { vm.pathToSelection() }, label = { Text(tr("Als Auswahl laden")) }, enabled = has)
        AssistChip(onClick = { vm.fillPath() }, label = { Text(tr("Füllen")) }, enabled = has)
        AssistChip(onClick = { vm.strokePath() }, label = { Text(tr("Kontur")) }, enabled = has)
        AssistChip(onClick = { vm.pathFromSelection() }, label = { Text(tr("Aus Auswahl")) }, enabled = state.hasSelection)
        AssistChip(onClick = { saveWork = true }, label = { Text(tr("Speichern")) }, enabled = state.workPath != null)
        AssistChip(onClick = { vm.deletePath() }, label = { Text(tr("Löschen")) }, enabled = has)
    }
    rename?.let { i -> RenameDialog(state.paths.getOrNull(i)?.name ?: "", onRename = { vm.renamePath(i, it); rename = null }, onDismiss = { rename = null }) }
    if (saveWork) RenameDialog(tr("Pfad {0}", state.paths.size + 1), onRename = { vm.saveWorkPath(it); saveWork = false }, onDismiss = { saveWork = false })
}
