package de.photographics.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import de.photographics.core.doc.LayerEffects
import de.photographics.core.doc.LayerStyles
import de.photographics.core.tr
import kotlin.math.roundToInt

/**
 * Ebenenstil wie in Photoshop: Effekte mit Häkchen, daneben die Einstellungen des gewählten Effekts.
 * Als Seitenblatt am rechten Rand ohne Abdunkeln, damit das Bild mit der Live-Vorschau sichtbar
 * bleibt; auf schmalen Geräten bildschirmfüllend mit den Effekten als Chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerStyleDialog(
    initial: LayerEffects,
    onPreview: (LayerEffects) -> Unit,
    onFinish: (Boolean) -> Unit,
) {
    var fx by remember { mutableStateOf(initial) }
    var selected by remember { mutableIntStateOf(LayerStyles.effects.indexOfFirst { it.get(initial) != null }.coerceAtLeast(0)) }
    // Zuletzt eingestellte Werte je Effekt, damit Aus- und wieder Einschalten sie behält
    val remembered = remember { mutableStateMapOf<String, Any>() }

    @Suppress("UNCHECKED_CAST")
    fun effect(i: Int) = LayerStyles.effects[i] as LayerStyles.Effect<Any>
    fun update(next: LayerEffects) { fx = next; onPreview(next) }
    fun toggle(i: Int, on: Boolean) {
        val e = effect(i)
        e.get(fx)?.let { remembered[e.id] = it }
        update(e.with(fx, if (on) remembered[e.id] ?: e.default else null))
        selected = i
    }
    fun set(value: Any) {
        val e = effect(selected)
        remembered[e.id] = value
        update(e.with(fx, value))
    }

    Dialog(onDismissRequest = { onFinish(false) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Bild nicht abdunkeln: die Vorschau läuft im Dokument
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w -> SideEffect { w.setDimAmount(0f) } }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            Surface(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().then(if (wide) Modifier.width(560.dp) else Modifier.fillMaxWidth()),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.safeDrawingPadding()) {
                    TopAppBar(
                        title = { Text(tr("Ebenenstil")) },
                        navigationIcon = { IconButton(onClick = { onFinish(false) }) { Icon(Icons.Filled.Close, tr("Abbrechen")) } },
                        actions = { Button(onClick = { onFinish(true) }, modifier = Modifier.padding(end = 12.dp)) { Text(tr("OK")) } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider()
                    val params: @Composable (Modifier) -> Unit = { m -> StyleParams(effect(selected), fx, remembered, m, ::set) }
                    if (wide) Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(212.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                            for ((i, e) in LayerStyles.effects.withIndex()) {
                                val on = e.get(fx) != null
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                        .background(if (i == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(24.dp))
                                        .clickable { selected = i }.padding(end = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(on, { toggle(i, it) })
                                    Text(e.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        VerticalDivider()
                        params(Modifier.weight(1f))
                    } else Column(Modifier.fillMaxSize()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((i, e) in LayerStyles.effects.withIndex()) {
                                val on = e.get(fx) != null
                                FilterChip(i == selected, { if (i == selected) toggle(i, !on) else selected = i }, label = { Text(e.label) },
                                    leadingIcon = { Checkbox(on, { toggle(i, it) }, Modifier.size(24.dp)) })
                            }
                        }
                        params(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleParams(e: LayerStyles.Effect<Any>, fx: LayerEffects, remembered: Map<String, Any>, modifier: Modifier, set: (Any) -> Unit) {
    val value = e.get(fx) ?: remembered[e.id] ?: e.default
    var colorFor by remember(e.id) { mutableStateOf<LayerStyles.Param.Color<Any>?>(null) }
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(e.label, style = MaterialTheme.typography.titleMedium)
        for (p in e.params) {
            when (p) {
                is LayerStyles.Param.Slider -> Column {
                    val v = p.get(value)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (p.digits == 0) "${v.roundToInt()} ${p.unit}" else "%.1f %s".format(v, p.unit), style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(v.toFloat().coerceIn(p.min.toFloat(), p.max.toFloat()), { x ->
                        set(p.set(value, if (p.digits == 0) x.roundToInt().toDouble() else x.toDouble()))
                    }, valueRange = p.min.toFloat()..p.max.toFloat())
                }
                is LayerStyles.Param.Color -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(width = 56.dp, height = 32.dp).background(Color(p.get(value) or 0xFF000000.toInt()), RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).clickable { colorFor = p })
                }
                is LayerStyles.Param.Blend -> Choice(p.label, LayerStyles.blendModes.map { it.label }, LayerStyles.blendModes.indexOf(p.get(value)).coerceAtLeast(0)) {
                    set(p.set(value, LayerStyles.blendModes[it]))
                }
                is LayerStyles.Param.Choice -> Choice(p.label, p.options, p.get(value)) { set(p.set(value, it)) }
                is LayerStyles.Param.Toggle -> Row(Modifier.clickable { set(p.set(value, !p.get(value))) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.get(value), { set(p.set(value, it)) })
                    Text(p.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        TextButton(onClick = { set(e.default) }) { Text(tr("Standardwerte")) }
        if (fx.unsupported.isNotEmpty()) Text(
            tr("Nicht bearbeitbar und beim Übernehmen entfernt: {0}", fx.unsupported.joinToString { tr(it) }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
    colorFor?.let { p ->
        ColorPickerDialog(p.get(value) or 0xFF000000.toInt(), onPick = { c -> set(p.set(value, c and 0xFFFFFF)); colorFor = null }, onDismiss = { colorFor = null })
    }
}

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(options.getOrElse(selected) { "" })
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(open, { open = false }) {
                for ((i, o) in options.withIndex()) DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}
