package de.photographics.app.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.photographics.app.AppSettings
import de.photographics.app.BarPosition
import de.photographics.app.CanvasBg
import de.photographics.app.ColorSource
import de.photographics.app.SEED_PRESETS
import de.photographics.app.Side
import de.photographics.app.SliderPlacement
import de.photographics.app.ThemeMode
import de.photographics.core.color.ProofCondition
import de.photographics.core.color.IccProfiles
import de.photographics.core.color.RenderingIntent
import de.photographics.app.IccEntry
import de.photographics.app.ProfilePolicy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.TextButton
import kotlin.math.roundToInt

enum class SettingsTab(val label: String) { APPEARANCE("Darstellung"), WORKSPACE("Arbeitsbereich"), COLOR("Farbmanagement"), PROOF("Farbproof"), ABOUT("Über") }

/** Voreinstellungen: Farben der App, Arbeitsbereich und Farbproof – Änderungen wirken sofort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    settings: AppSettings,
    proofOn: Boolean,
    gamutWarning: Boolean,
    initialTab: SettingsTab,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onToggleProof: () -> Unit,
    onToggleGamut: () -> Unit,
    iccLibrary: List<IccEntry>,
    onImportIcc: () -> Unit,
    onDeleteIcc: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(initialTab.ordinal) }
    var picker by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().heightIn(max = 760.dp),
        ) {
            Column {
                Row(Modifier.padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Schließen") }
                }
                PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, edgePadding = 16.dp) {
                    for (t in SettingsTab.entries) Tab(selected = tab == t.ordinal, onClick = { tab = t.ordinal }, text = { Text(t.label) })
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp, 16.dp, 24.dp, 24.dp)) {
                    when (SettingsTab.entries[tab]) {
                        SettingsTab.APPEARANCE -> Appearance(settings, onChange, onPickSeed = { picker = "seed" }, onPickCanvas = { picker = "canvas" })
                        SettingsTab.WORKSPACE -> Workspace(settings, onChange)
                        SettingsTab.COLOR -> ColorManagement(settings, onChange, iccLibrary, onImportIcc, onDeleteIcc)
                        SettingsTab.ABOUT -> About()
                        SettingsTab.PROOF -> Proof(settings, proofOn, gamutWarning, onChange, onToggleProof, onToggleGamut, onPickWarn = { picker = "warn" }, iccLibrary, onImportIcc)
                    }
                }
            }
        }
    }
    when (picker) {
        "seed" -> ColorPickerDialog(settings.seedColor, onPick = { c -> onChange { it.copy(colorSource = ColorSource.SEED, seedColor = c or 0xFF000000.toInt()) }; picker = null }, onDismiss = { picker = null })
        "canvas" -> ColorPickerDialog(settings.canvasCustom, onPick = { c -> onChange { it.copy(canvasBg = CanvasBg.CUSTOM, canvasCustom = c or 0xFF000000.toInt()) }; picker = null }, onDismiss = { picker = null })
        "warn" -> ColorPickerDialog(settings.gamutWarningColor, onPick = { c -> onChange { it.copy(gamutWarningColor = c or 0xFF000000.toInt()) }; picker = null }, onDismiss = { picker = null })
    }
}

// ---------------------------------------------------------------- Darstellung

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Appearance(s: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit, onPickSeed: () -> Unit, onPickCanvas: () -> Unit) {
    Section("Design")
    Segmented(ThemeMode.entries, s.themeMode, { it.label }) { m -> onChange { it.copy(themeMode = m) } }
    SwitchRow("Tiefschwarz im dunklen Design", "Schwarze Flächen – spart Strom auf OLED-Displays", s.pureBlack) { v -> onChange { it.copy(pureBlack = v) } }

    Section("Farben der App")
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    RadioRow(
        ColorSource.DYNAMIC.label,
        if (dynamicAvailable) "Akzentfarben passend zum Hintergrundbild des Tablets" else "Erst ab Android 12 verfügbar",
        selected = s.colorSource == ColorSource.DYNAMIC, enabled = dynamicAvailable,
    ) { onChange { it.copy(colorSource = ColorSource.DYNAMIC) } }
    RadioRow(ColorSource.SEED.label, "Das ganze Farbschema wird aus dieser Farbe abgeleitet", selected = s.colorSource == ColorSource.SEED) {
        onChange { it.copy(colorSource = ColorSource.SEED) }
    }
    FlowRow(Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((name, c) in SEED_PRESETS) {
            Swatch(Color(c), name, selected = s.colorSource == ColorSource.SEED && s.seedColor == c) {
                onChange { it.copy(colorSource = ColorSource.SEED, seedColor = c) }
            }
        }
        val custom = SEED_PRESETS.none { it.second == s.seedColor }
        Swatch(if (custom) Color(s.seedColor) else MaterialTheme.colorScheme.surfaceContainerHighest, "Eigene Farbe …",
            selected = s.colorSource == ColorSource.SEED && custom, icon = !custom, onClick = onPickSeed)
    }

    Section("Hintergrund der Arbeitsfläche")
    Text("Neutrale Grautöne verfälschen die Farbwahrnehmung beim Retuschieren am wenigsten.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (bg in CanvasBg.entries) {
            val color = when (bg) {
                CanvasBg.AUTO -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF202020) else Color(0xFFD4D4D4)
                CanvasBg.CUSTOM -> Color(s.canvasCustom)
                else -> Color(bg.color!!)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
                Swatch(color, bg.label, selected = s.canvasBg == bg, icon = bg == CanvasBg.CUSTOM && s.canvasBg != CanvasBg.CUSTOM, square = true) {
                    if (bg == CanvasBg.CUSTOM) onPickCanvas() else onChange { it.copy(canvasBg = bg) }
                }
                Text(bg.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Arbeitsbereich

@Composable
private fun Workspace(s: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    LayoutSketch(s, Modifier.fillMaxWidth().height(150.dp))
    Section("Vorlagen")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in AppSettings.Preset.entries) {
            OutlinedCard(onClick = { onChange { it.withPreset(p) } }, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.label, style = MaterialTheme.typography.titleSmall)
                    Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Section("Werkzeugleiste")
    LabeledSegmented("Position", Side.entries, s.toolbarSide, { it.label }) { v -> onChange { it.copy(toolbarSide = v) } }
    SwitchRow("Beschriftungen", "Ohne Beschriftung ist die Leiste schmaler", s.toolLabels) { v -> onChange { it.copy(toolLabels = v) } }
    Section("Ebenen-Panel")
    LabeledSegmented("Position", Side.entries, s.layersSide, { it.label }) { v -> onChange { it.copy(layersSide = v) } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text("Breite", modifier = Modifier.width(120.dp))
        Slider(s.layersWidth.toFloat(), { v -> onChange { it.copy(layersWidth = (v / 10).roundToInt() * 10) } }, valueRange = 260f..520f, modifier = Modifier.weight(1f))
        Text("${s.layersWidth} dp", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp).padding(start = 8.dp))
    }
    Section("Werkzeugoptionen und Schnellregler")
    LabeledSegmented("Optionsleiste", BarPosition.entries, s.optionsBar, { it.label }) { v -> onChange { it.copy(optionsBar = v) } }
    Spacer(Modifier.height(8.dp))
    LabeledSegmented("Größe/Deckkraft", SliderPlacement.entries, s.quickSliders, { it.label }) { v -> onChange { it.copy(quickSliders = v) } }
    Text("Die Schnellregler am Bildrand lassen sich mit dem Daumen der freien Hand bedienen.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

/** Schematische Vorschau des Arbeitsbereichs. */
@Composable
private fun LayoutSketch(s: AppSettings, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val bg = canvasBackground
    Canvas(modifier.background(cs.surfaceContainerLowest, RoundedCornerShape(16.dp)).padding(10.dp)) {
        val w = size.width; val h = size.height
        val r = CornerRadius(6f, 6f)
        val bar = h * 0.12f
        drawRoundRect(cs.surfaceContainer, Offset(0f, 0f), Size(w, bar), r)
        val top = bar + 4f
        val railW = if (s.toolLabels) w * 0.08f else w * 0.055f
        val panelW = w * (s.layersWidth / 1400f)
        val railX = if (s.toolbarSide == Side.LEFT) 0f else w - railW
        val panelX = if (s.layersSide == Side.LEFT) 0f else w - panelW
        // Werkzeugleiste und Ebenen dürfen auf derselben Seite liegen – dann innen/außen
        val sameSide = s.toolbarSide == s.layersSide
        val railXAdj = if (sameSide) (if (s.toolbarSide == Side.LEFT) 0f else w - railW) else railX
        val panelXAdj = if (sameSide) (if (s.layersSide == Side.LEFT) railW + 4f else w - railW - panelW - 4f) else panelX
        drawRoundRect(cs.surfaceContainer, Offset(railXAdj, top), Size(railW, h - top), r)
        for (i in 0 until 6) drawCircle(cs.primary.copy(alpha = if (i == 3) 1f else 0.45f), railW * 0.18f, Offset(railXAdj + railW / 2, top + 16f + i * (h - top - 24f) / 6))
        drawRoundRect(cs.surfaceContainerHigh, Offset(panelXAdj, top), Size(panelW, h - top), r)
        for (i in 0 until 4) drawRoundRect(cs.secondaryContainer, Offset(panelXAdj + 6f, top + 10f + i * 22f), Size(panelW - 12f, 16f), r)
        val cx0 = maxOf(if (railXAdj < w / 2) railXAdj + railW else 0f, if (panelXAdj < w / 2) panelXAdj + panelW else 0f) + 4f
        val cx1 = minOf(if (railXAdj >= w / 2) railXAdj else w, if (panelXAdj >= w / 2) panelXAdj else w) - 4f
        drawRoundRect(bg, Offset(cx0, top), Size(cx1 - cx0, h - top), r)
        val cw = cx1 - cx0
        drawRect(Color.White, Offset(cx0 + cw * 0.22f, top + (h - top) * 0.2f), Size(cw * 0.56f, (h - top) * 0.6f))
        val ob = if (s.optionsBar == BarPosition.TOP) top + 6f else h - 20f
        drawRoundRect(cs.surfaceContainerHighest, Offset(cx0 + cw * 0.2f, ob), Size(cw * 0.6f, 14f), CornerRadius(7f, 7f))
        if (s.quickSliders != SliderPlacement.HIDDEN) {
            val sx = if (s.quickSliders == SliderPlacement.LEFT) cx0 + 8f else cx1 - 18f
            drawRoundRect(cs.primary.copy(alpha = 0.7f), Offset(sx, top + (h - top) * 0.3f), Size(10f, (h - top) * 0.4f), CornerRadius(5f, 5f))
        }
    }
}

// ---------------------------------------------------------------- Farbproof

@Composable
private fun Proof(
    s: AppSettings, proofOn: Boolean, gamutWarning: Boolean,
    onChange: ((AppSettings) -> AppSettings) -> Unit, onToggleProof: () -> Unit, onToggleGamut: () -> Unit, onPickWarn: () -> Unit,
    iccLibrary: List<IccEntry>, onImportIcc: () -> Unit,
) {
    SwitchRow("Farbproof anzeigen", "Strg+Y – simuliert die gewählte Ausgabe auf dem Bildschirm", proofOn) { onToggleProof() }
    SwitchRow("Farbumfang-Warnung", "Strg+⇧+Y – markiert Farben, die sich nicht drucken lassen", gamutWarning) { onToggleGamut() }
    Section("Zu simulierende Bedingung")
    for (c in ProofCondition.entries) {
        RadioRow(c.label, c.description, selected = s.proofIcc == null && s.proofCondition == c) { onChange { it.copy(proofCondition = c, proofIcc = null) } }
    }
    Section("Eigene ICC-Profile")
    val outputs = iccLibrary.filter { it.isOutput || it.isRgb }
    if (outputs.isEmpty()) Text(
        "Noch keine Profile – z. B. das Profil Ihrer Druckerei (FOGRA, PSO) oder Ihres Fotodruckers hinzufügen.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (e in outputs) {
        RadioRow(e.description, "${e.colorSpace} · ${e.fileName}", selected = s.proofIcc == e.fileName) { onChange { it.copy(proofIcc = e.fileName) } }
    }
    TextButton(onClick = onImportIcc) { Icon(Icons.Filled.Add, null); Text("ICC-Profil hinzufügen …", Modifier.padding(start = 8.dp)) }
    if (s.proofIcc != null) {
        Section("Priorität")
        for (i in RenderingIntent.entries) RadioRow(i.label, null, selected = s.proofIntent == i) { onChange { it.copy(proofIntent = i) } }
        SwitchRow("Tiefenkompensierung", "Schwarzpunkt des Dokuments auf den des Ausgabegeräts abbilden", s.proofBpc) { v -> onChange { it.copy(proofBpc = v) } }
    }
    Section("Anzeigeoptionen")
    SwitchRow("Papierfarbe simulieren", "Zeigt Papierweiß und die Schwärze der Druckfarbe (absolut farbmetrisch)",
        s.proofSimulatePaper, enabled = s.proofIcc != null || s.proofCondition.isPrint) { v -> onChange { it.copy(proofSimulatePaper = v) } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Warnfarbe")
            Text("Farbe für nicht druckbare Bereiche", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (c in listOf(0xFF808080.toInt(), 0xFFFF00FF.toInt(), 0xFF00E676.toInt(), 0xFFFFEB3B.toInt())) {
            Swatch(Color(c), "Warnfarbe", selected = s.gamutWarningColor == c, modifier = Modifier.padding(start = 8.dp)) {
                onChange { it.copy(gamutWarningColor = c) }
            }
        }
        IconButton(onClick = onPickWarn) { Icon(Icons.Filled.Colorize, "Eigene Warnfarbe") }
    }
    Text(
        "Die Druckbedingungen werden aus den Normfarbwerten (ISO 12647) berechnet – relativ farbmetrisch mit " +
            "Tiefenkompensierung. Das ist eine gute Näherung für die Bildschirmkontrolle; für verbindliche Proofs " +
            "das ICC-Profil der Druckerei verwenden. Export und gespeicherte Dateien bleiben unverändert.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
    )
}

// ---------------------------------------------------------------- Farbmanagement

@Composable
private fun ColorManagement(
    s: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit,
    iccLibrary: List<IccEntry>, onImportIcc: () -> Unit, onDeleteIcc: (String) -> Unit,
) {
    Section("RGB-Arbeitsfarbraum")
    Text(
        "Profil neuer Dokumente; CMYK-, Graustufen- und Lab-Dateien werden beim Öffnen in diesen Farbraum umgerechnet.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (b in IccProfiles.Builtin.entries) RadioRow(b.label, b.note, selected = s.workingRgb == b) { onChange { it.copy(workingRgb = b) } }

    Section("Richtlinie beim Öffnen")
    for (p in ProfilePolicy.entries) RadioRow(p.label, p.description, selected = s.profilePolicy == p) { onChange { it.copy(profilePolicy = p) } }

    Section("Konvertierungsoptionen")
    Text("Priorität beim Umwandeln (Öffnen, Platzieren, CMYK-Import über Richtlinie)",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (i in RenderingIntent.entries) RadioRow(i.label, null, selected = s.conversionIntent == i) { onChange { it.copy(conversionIntent = i) } }
    SwitchRow("Tiefenkompensierung", "Erhält Zeichnung in den Tiefen bei Profilen mit unterschiedlichem Schwarz", s.conversionBpc) { v -> onChange { it.copy(conversionBpc = v) } }

    Section("Export (PNG/JPEG)")
    RadioRow("Dokumentprofil einbetten", "Farben bleiben exakt; Programme mit Farbmanagement zeigen sie richtig an", selected = !s.exportToSrgb) {
        onChange { it.copy(exportToSrgb = false) }
    }
    RadioRow("In sRGB umwandeln", "Für Web, soziale Netzwerke und Geräte ohne Farbmanagement", selected = s.exportToSrgb) {
        onChange { it.copy(exportToSrgb = true) }
    }

    Section("ICC-Profilbibliothek")
    if (iccLibrary.isEmpty()) Text(
        "Eigene Profile für Proof, Zuweisen und Umwandeln – z. B. vom Drucker, der Druckerei oder einem kalibrierten Bildschirm.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (e in iccLibrary) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Column(Modifier.weight(1f)) {
                Text(e.description)
                Text("${e.colorSpace} · ${deviceClassLabel(e.deviceClass)} · ${e.fileName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onDeleteIcc(e.fileName) }) { Icon(Icons.Filled.Delete, "Profil entfernen") }
        }
    }
    TextButton(onClick = onImportIcc) { Icon(Icons.Filled.Add, null); Text("ICC-Profil hinzufügen …", Modifier.padding(start = 8.dp)) }
}

private fun deviceClassLabel(c: String) = when (c) {
    "prtr" -> "Ausgabe"; "mntr" -> "Bildschirm"; "scnr" -> "Eingabe"; "spac" -> "Farbraum"; "abst" -> "Abstrakt"; "link" -> "Geräteverknüpfung"; else -> c
}

// ---------------------------------------------------------------- Über

@Composable
private fun About() {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Wordmark(MaterialTheme.typography.headlineSmall)
    Text("Version ${de.photographics.app.BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        "Ebenenbasierter Fotoeditor für Android, gemacht für professionelle Workflows unterwegs. Freie Software: Sie dürfen das Programm " +
            "unter den Bedingungen der GNU General Public License, Version 3 oder neuer, weitergeben und verändern. " +
            "Es wird OHNE JEDE GEWÄHRLEISTUNG bereitgestellt.",
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp),
    )
    TextButton(onClick = { uri.openUri("https://www.gnu.org/licenses/gpl-3.0.html") }) { Text("Lizenztext (GPL-3.0)") }
    val source = de.photographics.app.BuildConfig.SOURCE_URL
    if (source.isNotEmpty()) TextButton(onClick = { uri.openUri(source) }) { Text("Quellcode, Fehler melden, Feedback") }

    Section("Verwendete Komponenten")
    for ((name, license) in listOf(
        "AndroidX, Jetpack Compose, Material Icons" to "Apache 2.0",
        "Kotlin, kotlinx.coroutines" to "Apache 2.0",
        "ONNX Runtime" to "MIT",
        "KI-Modell MI-GAN (wird bei Bedarf geladen)" to "MIT",
        "KI-Modell LaMa (wird bei Bedarf geladen)" to "Apache 2.0",
        "KI-Modelle Real-ESRGAN (werden bei Bedarf geladen)" to "BSD 3-Clause",
    )) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(license, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(
        "Adobe und Photoshop sind eingetragene Marken von Adobe Inc. photographics ist ein unabhängiges Projekt " +
            "und steht in keiner Verbindung zu Adobe.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp),
    )
}

// ---------------------------------------------------------------- Bausteine

@Composable
private fun Section(title: String) = Text(
    title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
)

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            SegmentedButton(selected = item == selected, onClick = { onSelect(item) }, shape = SegmentedButtonDefaults.itemShape(i, items.size)) {
                Text(label(item), maxLines = 1)
            }
        }
    }
}

@Composable
private fun <T> LabeledSegmented(title: String, items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.width(120.dp))
        Box(Modifier.weight(1f)) { Segmented(items, selected, label, onSelect) }
    }
}

@Composable
private fun Swatch(
    color: Color, description: String, selected: Boolean, modifier: Modifier = Modifier,
    icon: Boolean = false, square: Boolean = false, onClick: () -> Unit,
) {
    val shape = if (square) RoundedCornerShape(12.dp) else CircleShape
    Box(
        modifier.size(44.dp).background(color, shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val fg = if (color.luminance() > 0.5f) Color.Black else Color.White
        when {
            selected -> Icon(Icons.Filled.Check, null, tint = fg)
            icon -> Icon(Icons.Filled.Colorize, description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
