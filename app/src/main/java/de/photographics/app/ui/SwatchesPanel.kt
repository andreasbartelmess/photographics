package de.photographics.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.photographics.app.EditorViewModel

/**
 * Farbfelder (Photoshop: Fenster → Farbfelder): Bibliotheken, zuletzt verwendete Farben.
 * Antippen = Vordergrundfarbe, lange drücken = Hintergrundfarbe bzw. entfernen.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SwatchesDialog(vm: EditorViewModel, onImport: () -> Unit, onExport: (String) -> Unit, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val palette = state.palettes.firstOrNull { it.name == state.activePalette } ?: state.palettes.first()
    // Eigene Bibliotheken heißen nie wie eingebaute (Import benennt um)
    val builtin = de.photographics.core.color.BuiltinPalettes.all.any { it.name == palette.name }
    var choose by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 620.dp).fillMaxWidth().heightIn(max = 720.dp),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Farbfelder", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    ColorSwatchPair(state.color, state.bgColor, onFg = {}, onBg = {}, onSwap = vm::swapColors, onReset = vm::resetColors, Modifier.padding(end = 8.dp))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Schließen") }
                }
                Column(Modifier.padding(end = 12.dp).verticalScroll(rememberScrollState())) {
                    if (state.recentColors.isNotEmpty()) {
                        Text("Zuletzt verwendet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in state.recentColors) Field(c, onClick = { vm.setColor(c) }, onLong = { vm.setBgColor(c) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { choose = true }) { Text(palette.name); Icon(Icons.Filled.ArrowDropDown, null) }
                            DropdownMenu(choose, { choose = false }) {
                                for (p in state.palettes) DropdownMenuItem(text = { Text("${p.name} (${p.swatches.size})") }, onClick = { vm.selectPalette(p.name); choose = false })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Neue Bibliothek …") }, onClick = { choose = false; naming = true })
                            }
                        }
                        TextButton(onClick = { vm.addSwatch(state.color) }) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("Vordergrundfarbe", Modifier.padding(start = 4.dp)) }
                    }
                    if (naming) Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newName, { newName = it }, label = { Text("Name der Bibliothek") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.newPalette(newName); naming = false; newName = "" }, enabled = newName.isNotBlank()) { Text("Anlegen") }
                    }
                    if (builtin) Text(
                        "Eingebaute Bibliothek – neue Farben landen in „Eigene Farbfelder“.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        palette.swatches.forEachIndexed { i, s ->
                            Box {
                                Field(s.color, onClick = { vm.setColor(s.color) }, onLong = { menuFor = i })
                                DropdownMenu(menuFor == i, { menuFor = null }) {
                                    if (s.name.isNotEmpty()) Text(s.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                    DropdownMenuItem(text = { Text("Als Vordergrundfarbe") }, onClick = { vm.setColor(s.color); menuFor = null })
                                    DropdownMenuItem(text = { Text("Als Hintergrundfarbe") }, onClick = { vm.setBgColor(s.color); menuFor = null })
                                    if (!builtin) DropdownMenuItem(text = { Text("Entfernen") }, onClick = { vm.removeSwatch(palette.name, i); menuFor = null })
                                }
                            }
                        }
                    }
                    Text(
                        "Antippen: Vordergrundfarbe · lange drücken: weitere Aktionen",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport) { Text("Importieren …") }
                        Box {
                            OutlinedButton(onClick = { exportMenu = true }) { Text("Exportieren …") }
                            DropdownMenu(exportMenu, { exportMenu = false }) {
                                DropdownMenuItem(text = { Text("Photoshop-Farbfelder (.aco)") }, onClick = { exportMenu = false; onExport("aco") })
                                DropdownMenuItem(text = { Text("Adobe Swatch Exchange (.ase)") }, onClick = { exportMenu = false; onExport("ase") })
                                DropdownMenuItem(text = { Text("GIMP-Palette (.gpl)") }, onClick = { exportMenu = false; onExport("gpl") })
                            }
                        }
                        if (!builtin) TextButton(onClick = { vm.deletePalette(palette.name) }) { Text("Bibliothek löschen") }
                    }
                    Text(
                        "Import: Photoshop (.aco, .act), Illustrator/InDesign (.ase), GIMP (.gpl)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Field(c: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(36.dp).background(Color(c), shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLong),
    )
}
