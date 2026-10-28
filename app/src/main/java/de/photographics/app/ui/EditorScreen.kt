package de.photographics.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.platform.LocalContext
import androidx.print.PrintHelper
import de.photographics.app.ai.AiPurpose
import de.photographics.core.adjust.AdjParam
import de.photographics.core.adjust.AdjustmentType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.photographics.app.BarPosition
import de.photographics.app.EditorViewModel
import de.photographics.app.Side
import de.photographics.app.ImageIO
import de.photographics.app.Tool
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val OPEN_TYPES = arrayOf(
    "image/*", "image/vnd.adobe.photoshop", "application/x-photoshop", "application/photoshop", "application/psd", "image/x-xcf", "application/octet-stream",
)

private enum class DialogKind { NEW, IMAGE_SIZE, CANVAS_SIZE, COLOR, TEXT_COLOR, FEATHER, SHORTCUTS, AI_MODELS, SETTINGS, ASSIGN_PROFILE, CONVERT_PROFILE, TIFF_OPTIONS, BG_COLOR, BRUSH, SWATCHES, CROP_SIZE, ROTATE, NEW_GUIDE, GUIDE_LAYOUT }

/** Ab dieser Breite wird das Ebenen-Panel fest angedockt (Tablet quer, große Tablets hoch). */
private val DOCK_WIDTH = 840.dp

/** Filter-Spezifikation für eine direkt angewendete Korrektur. */
private fun directAdjustmentSpec(t: AdjustmentType): FilterSpec {
    val def = t.createDefault()
    val params = def.params().map { p ->
        FilterParam(p.label, p.min, p.max, p.value, p.decimals,
            when (p.kind) { AdjParam.Kind.SWITCH -> ParamKind.SWITCH; AdjParam.Kind.HUE -> ParamKind.HUE; else -> ParamKind.SLIDER })
    }
    return FilterSpec(t.label, "Korrekturen", t.icon, params) { v -> AdjustmentFilter(def.withValues(v)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, adjustSettings: (de.photographics.app.AppSettings) -> de.photographics.app.AppSettings = { it }) {
    val state by vm.state.collectAsStateWithLifecycle()
    val storedSettings by vm.settings.collectAsStateWithLifecycle()
    val settings = adjustSettings(storedSettings)
    var settingsTab by remember { mutableStateOf(SettingsTab.APPEARANCE) }
    val aiStatus by vm.ai.status.collectAsStateWithLifecycle()
    var layersOverlay by remember { mutableStateOf(false) }
    var layersDocked by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    var spacePressed by remember { mutableStateOf(false) }
    var keyMods by remember { mutableStateOf(KeyModifiers()) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var activeSpec by remember { mutableStateOf<FilterSpec?>(null) }
    var adjustSheet by remember { mutableStateOf(false) }
    var adjustAsLayer by remember { mutableStateOf(true) }
    var overflow by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val viewport = remember { ViewportState() }
    val focus = remember { FocusRequester() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lastTools = remember { mutableStateMapOf<ToolGroup, Tool>() }
    LaunchedEffect(state.tool) { lastTools[state.tool.group] = state.tool }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::open) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::open) }
    val placeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::placeAsLayer) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/vnd.adobe.photoshop")) { it?.let(vm::savePsd) }
    val tiffLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/tiff")) { it?.let(vm::saveTiff) }
    val xcfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/x-xcf")) { it?.let(vm::saveXcf) }
    val paletteImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importPalette) }
    var paletteExportExt by remember { mutableStateOf("aco") }
    val paletteExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.exportPalette(it, paletteExportExt) }
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        uri?.let { vm.export(it, ImageIO.ExportFormat.PNG) }
    }
    val jpgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        uri?.let { vm.export(it, ImageIO.ExportFormat.JPEG) }
    }
    val baseName = state.fileName.substringBeforeLast('.')
    val lutLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importLut) }
    val iccLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importIcc) }
    val lutExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(vm::exportLut)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    LaunchedEffect(state.requestAiModels) {
        if (state.requestAiModels != null) dialog = DialogKind.AI_MODELS
    }
    // Tastaturfokus zurückholen, sobald kein Dialog mehr offen ist
    LaunchedEffect(dialog, activeSpec, adjustSheet, drawer.isOpen, state.showStart, state.textEdit) {
        if (dialog == null && !adjustSheet && !drawer.isOpen && state.textEdit == null) runCatching { focus.requestFocus() }
    }

    // Vollbild: Status- und Navigationsleiste ausblenden
    val view = LocalView.current
    LaunchedEffect(fullscreen) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val ctrl = WindowCompat.getInsetsController(window, view)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) ctrl.hide(WindowInsetsCompat.Type.systemBars()) else ctrl.show(WindowInsetsCompat.Type.systemBars())
    }

    fun runFilter(spec: FilterSpec) {
        vm.startFilter(spec.label)
        if (spec.params.isEmpty()) {
            vm.previewFilter(spec.build(FloatArray(0)))
            vm.applyFilter(spec.label)
        } else {
            activeSpec = spec
        }
    }

    fun runAdjustment(t: AdjustmentType) {
        // Die Farbtabelle braucht die LUT-Auswahl im Panel → immer als Einstellungsebene
        if (adjustAsLayer || t == AdjustmentType.COLOR_LOOKUP) vm.addAdjustmentLayer(t) else runFilter(directAdjustmentSpec(t))
    }

    fun print() {
        vm.renderForPrint { bmp ->
            PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT; colorMode = PrintHelper.COLOR_MODE_COLOR }
                .printBitmap("photographics – $baseName", bmp)
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        // Modifikatoren für Maus/Stift (Alt-Klick = Stempelquelle, Shift = proportional)
        keyMods = KeyModifiers(e.isShiftPressed, e.isAltPressed, e.isCtrlPressed || e.isMetaPressed)
        if (state.textEdit != null) return false // Texteingabe nicht abfangen
        if (e.key == Key.Spacebar) {
            spacePressed = e.type == KeyEventType.KeyDown
            return true
        }
        if (e.type != KeyEventType.KeyDown) return false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        if (ctrl) {
            when (e.key) {
                Key.Z -> if (shift) vm.redo() else vm.undo()
                Key.Y -> if (shift) vm.toggleGamutWarning() else vm.toggleProof()
                Key.S -> saveLauncher.launch("$baseName.psd")
                Key.O -> openLauncher.launch(OPEN_TYPES)
                Key.N -> if (shift) vm.addLayer() else dialog = DialogKind.NEW
                Key.A -> vm.selectAll()
                Key.D -> vm.deselect()
                Key.Backspace, Key.Delete -> vm.fillSelected(background = true)
                Key.I -> when { alt -> dialog = DialogKind.IMAGE_SIZE; shift -> vm.invertSelection(); else -> runFilter(directAdjustmentSpec(AdjustmentType.INVERT)) }
                Key.C -> if (alt) dialog = DialogKind.CANVAS_SIZE else return false
                Key.J -> vm.layerViaCopy()
                Key.E -> vm.mergeDown()
                Key.G -> if (alt) vm.toggleClippingActive() else vm.groupActive()
                Key.T -> vm.selectTool(Tool.TRANSFORM)
                Key.P -> print()
                Key.R -> vm.updateSettings { it.copy(showRulers = !it.showRulers) }
                Key.Semicolon -> if (shift) vm.updateSettings { it.copy(snap = !it.snap) } else vm.updateSettings { it.copy(showGuides = !it.showGuides) }
                Key.L -> runAdjustment(AdjustmentType.LEVELS)
                Key.M -> runAdjustment(AdjustmentType.CURVES)
                Key.U -> runAdjustment(AdjustmentType.HUE_SATURATION)
                Key.B -> runAdjustment(AdjustmentType.COLOR_BALANCE)
                Key.Zero -> viewport.fit()
                Key.One -> viewport.zoomTo(1f)
                Key.Equals, Key.Plus, Key.NumPadAdd -> viewport.transform(1.25f)
                Key.Minus, Key.NumPadSubtract -> viewport.transform(0.8f)
                else -> return false
            }
            return true
        }
        // Pfeiltasten: aktive Ebene verschieben (Shift: 10 px)
        val step = if (shift) 10 else 1
        when (e.key) {
            Key.DirectionLeft -> { vm.nudge(-step, 0); return true }
            Key.DirectionRight -> { vm.nudge(step, 0); return true }
            Key.DirectionUp -> { vm.nudge(0, -step); return true }
            Key.DirectionDown -> { vm.nudge(0, step); return true }
            else -> {}
        }
        if (shift && e.key == Key.F5) { vm.contentAwareFill(); return true }
        if (e.key == Key.F5) { dialog = DialogKind.BRUSH; return true }
        if (alt && (e.key == Key.Backspace || e.key == Key.Delete)) { vm.fillSelected(background = false); return true }
        when (e.key) {
            Key.X -> { vm.swapColors(); return true }
            Key.D -> { vm.resetColors(); return true }
            else -> {}
        }
        val tool = when (e.key) {
            Key.V -> Tool.MOVE
            Key.M -> Tool.SELECT
            Key.C -> Tool.CROP
            Key.B -> Tool.BRUSH
            Key.E -> Tool.ERASER
            Key.S -> Tool.CLONE
            Key.J -> when { shift && alt -> Tool.RED_EYE; shift -> Tool.HEAL; alt -> Tool.PATCH; else -> Tool.SPOT_HEAL }
            Key.K -> Tool.AI_REMOVE
            Key.R -> if (shift) Tool.SHARPEN else Tool.BLUR
            Key.W -> Tool.SMUDGE
            Key.O -> if (shift) Tool.BURN else Tool.DODGE
            Key.I -> Tool.EYEDROPPER
            Key.T -> Tool.TEXT
            else -> null
        }
        if (tool != null) { vm.selectTool(tool); return true }
        when (e.key) {
            Key.LeftBracket -> vm.stepBrushSize(up = false)
            Key.RightBracket -> vm.stepBrushSize(up = true)
            Key.Delete, Key.Backspace -> vm.clearSelected()
            Key.Tab -> { layersDocked = !layersDocked; layersOverlay = !layersOverlay }
            Key.F -> fullscreen = !fullscreen
            Key.Enter, Key.NumPadEnter -> when {
                state.transform != null -> vm.applyTransform()
                state.tool == Tool.CROP -> vm.applyCrop()
                else -> return false
            }
            Key.Escape -> when {
                activeSpec != null -> { vm.cancelFilter(); activeSpec = null }
                state.transform != null -> vm.cancelTransform()
                state.adjustEdit != null -> vm.closeAdjustment()
                state.tool == Tool.CROP -> vm.cancelCrop()
                else -> vm.deselect()
            }
            else -> {
                // Ziffern setzen die Deckkraft wie in Photoshop (1 = 10 %, 0 = 100 %)
                val digit = when (e.key) {
                    Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4; Key.Five -> 5
                    Key.Six -> 6; Key.Seven -> 7; Key.Eight -> 8; Key.Nine -> 9; Key.Zero -> 10
                    else -> return false
                }
                vm.setBrushOpacity(digit / 10f)
            }
        }
        return true
    }

    // Zurück-Taste schließt zuerst offene Panels, statt die App zu beenden
    BackHandler(enabled = activeSpec != null) { vm.cancelFilter(); activeSpec = null }
    BackHandler(enabled = activeSpec == null && state.textEdit != null) { vm.commitTextEdit() }
    BackHandler(enabled = activeSpec == null && state.adjustEdit != null) { vm.closeAdjustment() }
    BackHandler(enabled = activeSpec == null && state.transform != null) { vm.cancelTransform() }
    BackHandler(enabled = activeSpec == null && layersOverlay) { layersOverlay = false }
    BackHandler(enabled = activeSpec == null && !layersOverlay && fullscreen) { fullscreen = false }
    BackHandler(enabled = !state.showStart && activeSpec == null && state.textEdit == null && state.adjustEdit == null &&
        state.transform == null && !layersOverlay && !fullscreen) { vm.showStartScreen(true) }

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawer.close() }
        action()
    }

    if (state.showStart) {
        StartScreen(
            recent = state.recent,
            hasDocument = state.hasDocument && state.fileName != "Unbenannt" || state.canUndo,
            loadThumb = vm::loadRecentThumb,
            onNew = { w, h, dpi -> vm.newDocument(w, h, transparent = false, dpi = dpi) },
            onCustomNew = { dialog = DialogKind.NEW },
            onOpen = { openLauncher.launch(OPEN_TYPES) },
            onGallery = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onOpenRecent = { vm.open(it.uri) },
            onRemoveRecent = { vm.removeRecent(it.uri) },
            onContinue = { vm.showStartScreen(false) },
        )
    } else BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent(::onKey)
            .focusRequester(focus)
            .focusable(),
    ) {
        val screenWidth = maxWidth
        // Andocken nur, wenn neben der Breite auch die Höhe reicht (Tablet, nicht Handy quer)
        val wide = screenWidth >= DOCK_WIDTH && maxHeight >= 520.dp
        val compact = screenWidth < 600.dp
        val showDocked = wide && layersDocked && !fullscreen
        val layersVisible = if (wide) layersDocked else layersOverlay

        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = drawer.isOpen,
            drawerContent = {
                ModalDrawerSheet {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                        Row(Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppLogo(40.dp)
                            Wordmark(MaterialTheme.typography.titleLarge, Modifier.padding(start = 14.dp))
                        }
                        Text(
                            "${state.fileName} · ${state.docWidth} × ${state.docHeight} px",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        DrawerItem(Icons.Filled.Home, "Startbildschirm") { closeDrawerThen { vm.showStartScreen(true) } }
                        DrawerItem(Icons.Filled.Add, "Neues Dokument", "Strg+N") { closeDrawerThen { dialog = DialogKind.NEW } }
                        DrawerItem(Icons.Filled.FolderOpen, "Öffnen", "Strg+O") { closeDrawerThen { openLauncher.launch(OPEN_TYPES) } }
                        DrawerItem(Icons.Filled.AddPhotoAlternate, "Als Ebene platzieren") { closeDrawerThen { placeLauncher.launch(OPEN_TYPES) } }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text("Speichern & Ausgeben", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp))
                        DrawerItem(Icons.Filled.Save, "Als PSD speichern", "Strg+S") { closeDrawerThen { saveLauncher.launch("$baseName.psd") } }
                        DrawerItem(Icons.Filled.Layers, "Als TIFF speichern …") { closeDrawerThen { dialog = DialogKind.TIFF_OPTIONS } }
                        DrawerItem(Icons.Filled.Pets, "Als GIMP-Datei speichern (.xcf)") { closeDrawerThen { xcfLauncher.launch("$baseName.xcf") } }
                        DrawerItem(Icons.Filled.Image, "Als PNG exportieren") { closeDrawerThen { pngLauncher.launch("$baseName.png") } }
                        DrawerItem(Icons.Filled.Photo, "Als JPEG exportieren") { closeDrawerThen { jpgLauncher.launch("$baseName.jpg") } }
                        DrawerItem(Icons.Filled.Gradient, "Farbtabelle exportieren (.cube)") { closeDrawerThen { lutExportLauncher.launch("$baseName.cube") } }
                        DrawerItem(Icons.Filled.Print, "Drucken", "Strg+P") { closeDrawerThen { print() } }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        DrawerItem(Icons.Filled.AutoAwesome, "KI-Modelle") { closeDrawerThen { dialog = DialogKind.AI_MODELS } }
                        DrawerItem(Icons.Filled.Keyboard, "Gesten & Tastaturkürzel") { closeDrawerThen { dialog = DialogKind.SHORTCUTS } }
                        DrawerItem(Icons.Filled.School, "Einführung") { closeDrawerThen { vm.updateSettings { it.copy(introSeen = false) } } }
                        DrawerItem(Icons.Filled.Settings, "Einstellungen") { closeDrawerThen { settingsTab = SettingsTab.APPEARANCE; dialog = DialogKind.SETTINGS } }
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    if (!fullscreen) TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menü") }
                        },
                        title = {
                            Column {
                                Text(state.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!compact) Text(
                                    "${state.docWidth} × ${state.docHeight} px" +
                                        " · ${state.docProfileName}" +
                                        (if (state.proofOn) " · Proof: ${state.proofLabel}" else "") +
                                        (if (state.gamutWarning) " · Farbumfang-Warnung" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Rückgängig") }
                            IconButton(onClick = vm::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Wiederholen") }
                            if (!compact) IconToggleButton(checked = state.stylusOnly, onCheckedChange = { vm.setStylusOnly(it) }) {
                                Icon(if (state.stylusOnly) Icons.Filled.Draw else Icons.Filled.TouchApp, if (state.stylusOnly) "Nur Stift malt" else "Finger malen")
                            }
                            IconButton(onClick = { adjustSheet = true }) { Icon(Icons.Filled.Tune, "Korrekturen & Filter") }
                            IconToggleButton(
                                checked = layersVisible,
                                onCheckedChange = { if (wide) layersDocked = it else layersOverlay = it },
                            ) { Icon(Icons.Filled.Layers, "Ebenen") }
                            Box {
                                IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "Weitere Optionen") }
                                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                    fun go(action: () -> Unit) { overflow = false; action() }
                                    MenuHeader("Bild")
                                    Item("Bildgröße …", "Strg+Alt+I", Icons.Filled.PhotoSizeSelectLarge) { go { dialog = DialogKind.IMAGE_SIZE } }
                                    Item("Arbeitsfläche …", "Strg+Alt+C", Icons.Filled.AspectRatio) { go { dialog = DialogKind.CANVAS_SIZE } }
                                    Item("Profil zuweisen …", icon = Icons.Filled.Style) { go { dialog = DialogKind.ASSIGN_PROFILE } }
                                    Item("In Profil umwandeln …", icon = Icons.Filled.Palette) { go { dialog = DialogKind.CONVERT_PROFILE } }
                                    Item("Farbeinstellungen …", icon = Icons.Filled.Tune) { go { settingsTab = SettingsTab.COLOR; dialog = DialogKind.SETTINGS } }
                                    Item("Freistellen", "C", Icons.Filled.Crop) { go { vm.selectTool(Tool.CROP) } }
                                    Item("Nach rechts drehen", icon = Icons.Filled.Rotate90DegreesCw) { go { vm.rotate(true) } }
                                    Item("Nach links drehen", icon = Icons.Filled.Rotate90DegreesCcw) { go { vm.rotate(false) } }
                                    Item("Horizontal spiegeln", icon = Icons.Filled.Flip) { go { vm.flip(true) } }
                                    Item("Vertikal spiegeln") { go { vm.flip(false) } }
                                    Item("Drehen um Winkel …") { go { dialog = DialogKind.ROTATE } }
                                    Item("Auf Hintergrund reduzieren", icon = Icons.Filled.Layers) { go { vm.flatten() } }
                                    HorizontalDivider()
                                    MenuHeader("Bearbeiten")
                                    Item("Frei transformieren", "Strg+T", Icons.Filled.Transform) { go { vm.selectTool(Tool.TRANSFORM) } }
                                    Item("Inhaltsbasiert füllen", "⇧F5", Icons.Filled.AutoFixHigh) { go { vm.contentAwareFill() } }
                                    Item("KI-Füllen (Auswahl entfernen)", icon = Icons.Filled.AutoAwesome) { go { vm.aiFillSelection() } }
                                    HorizontalDivider()
                                    MenuHeader("Auswahl")
                                    Item("Alles auswählen", "Strg+A", Icons.Filled.SelectAll) { go { vm.selectAll() } }
                                    Item("Auswahl aufheben", "Strg+D", Icons.Filled.Deselect) { go { vm.deselect() } }
                                    Item("Auswahl umkehren", "Strg+⇧+I") { go { vm.invertSelection() } }
                                    Item("Weiche Auswahlkante …") { go { dialog = DialogKind.FEATHER } }
                                    HorizontalDivider()
                                    MenuHeader("Ansicht")
                                    CheckItem("Farbproof", "Strg+Y", state.proofOn) { go { vm.toggleProof() } }
                                    CheckItem("Farbumfang-Warnung", "Strg+⇧+Y", state.gamutWarning) { go { vm.toggleGamutWarning() } }
                                    CheckItem("Lineale", "Strg+R", settings.showRulers) { go { vm.updateSettings { it.copy(showRulers = !it.showRulers) } } }
                                    CheckItem("Hilfslinien", "Strg+;", settings.showGuides) { go { vm.updateSettings { it.copy(showGuides = !it.showGuides) } } }
                                    CheckItem("Ausrichten", "Strg+⇧+;", settings.snap) { go { vm.updateSettings { it.copy(snap = !it.snap) } } }
                                    CheckItem("Intelligente Hilfslinien", null, settings.smartGuides) { go { vm.updateSettings { it.copy(smartGuides = !it.smartGuides) } } }
                                    Item("Neue Hilfslinie …") { go { dialog = DialogKind.NEW_GUIDE } }
                                    Item("Hilfslinienlayout …") { go { dialog = DialogKind.GUIDE_LAYOUT } }
                                    Item("Hilfslinien löschen") { go { vm.clearGuides() } }
                                    Item("Proof einrichten …", icon = Icons.Filled.Palette) { go { settingsTab = SettingsTab.PROOF; dialog = DialogKind.SETTINGS } }
                                    Item("Arbeitsbereich …", icon = Icons.Filled.Dashboard) { go { settingsTab = SettingsTab.WORKSPACE; dialog = DialogKind.SETTINGS } }
                                    Item("Vollbild", "F", Icons.Filled.Fullscreen) { go { fullscreen = true } }
                                    HorizontalDivider()
                                    Item("Drucken …", "Strg+P", Icons.Filled.Print) { go { print() } }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                val rail: @Composable () -> Unit = {
                    ToolRail(
                        state,
                        lastTool = { g -> lastTools[g] ?: g.tools.first() },
                        onTool = { vm.selectTool(it) },
                        onColorClick = { dialog = DialogKind.COLOR },
                        onBgColorClick = { dialog = DialogKind.BG_COLOR },
                        onSwapColors = vm::swapColors,
                        onResetColors = vm::resetColors,
                        onSwatches = { dialog = DialogKind.SWATCHES },
                        showLabels = settings.toolLabels,
                    )
                }
                val dockedLayers: @Composable () -> Unit = {
                    if (showDocked) LayersPanel(
                        state, vm, width = settings.layersWidth.dp,
                        onAddAdjustment = { adjustAsLayer = true; adjustSheet = true },
                    )
                }
                val layersLeft = settings.layersSide == Side.LEFT
                Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    // Reihenfolge nach Einstellungen: Werkzeugleiste außen, Ebenen innen, wenn beide auf einer Seite
                    if (settings.toolbarSide == Side.LEFT) rail()
                    if (layersLeft) dockedLayers()
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        CanvasArea(
                            state, vm, viewport, spacePressed, Modifier.fillMaxSize(), keys = keyMods,
                            sliders = settings.quickSliders, optionsAtBottom = settings.optionsBar == BarPosition.BOTTOM,
                            showRulers = settings.showRulers, showGuides = settings.showGuides,
                            contextMenu = { close ->
                                fun go(action: () -> Unit) { close(); action() }
                                Item("Rückgängig", "Strg+Z") { go { vm.undo() } }
                                Item("Wiederholen", "Strg+⇧+Z") { go { vm.redo() } }
                                HorizontalDivider()
                                if (state.hasSelection) {
                                    Item("Auswahl aufheben", "Strg+D") { go { vm.deselect() } }
                                    Item("Auswahl umkehren", "Strg+⇧+I") { go { vm.invertSelection() } }
                                    Item("Ebene durch Kopie", "Strg+J") { go { vm.layerViaCopy() } }
                                    Item("Inhaltsbasiert füllen", "⇧F5") { go { vm.contentAwareFill() } }
                                    Item("KI-Füllen") { go { vm.aiFillSelection() } }
                                    Item("Löschen", "Entf") { go { vm.clearSelected() } }
                                } else {
                                    Item("Alles auswählen", "Strg+A") { go { vm.selectAll() } }
                                }
                                HorizontalDivider()
                                Item("Frei transformieren", "Strg+T") { go { vm.selectTool(Tool.TRANSFORM) } }
                                Item("Neue Ebene", "Strg+⇧+N") { go { vm.addLayer() } }
                                Item("Einpassen", "Strg+0") { go { viewport.fit() } }
                            },
                        )

                        ToolOptionsBar(
                            state, vm,
                            onFeatherClick = { dialog = DialogKind.FEATHER },
                            onManageAi = { dialog = DialogKind.AI_MODELS },
                            onBrushSettings = { dialog = DialogKind.BRUSH },
                            onTargetSize = { dialog = DialogKind.CROP_SIZE },
                            modifier = Modifier.align(if (settings.optionsBar == BarPosition.TOP) Alignment.TopCenter else Alignment.BottomCenter)
                                .padding(12.dp, 12.dp, 12.dp, if (settings.optionsBar == BarPosition.TOP) 12.dp else 76.dp),
                        )
                        if (state.busy) {
                            val p = state.progress
                            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                                if (p != null) LinearProgressIndicator({ p }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                                state.progressLabel?.let {
                                    Text("$it · ${((p ?: 0f) * 100).toInt()} %", style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 72.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
                                }
                            }
                        }
                        if (fullscreen) {
                            SmallFloatingActionButton(
                                onClick = { fullscreen = false },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            ) { Icon(Icons.Filled.FullscreenExit, "Vollbild beenden") }
                        }

                        val panelModifier = Modifier.align(if (!wide) Alignment.BottomCenter else if (layersLeft) Alignment.BottomStart else Alignment.BottomEnd)
                            .padding(16.dp).widthIn(max = if (wide) 420.dp else 640.dp)
                        val spec = activeSpec
                        val adj = state.adjustEdit
                        val text = state.textEdit
                        when {
                            spec != null && state.filterActive != null -> FilterPanel(
                                spec,
                                onPreview = { values -> vm.previewFilter(spec.build(values)) },
                                onApply = { vm.applyFilter(spec.label); activeSpec = null },
                                onCancel = { vm.cancelFilter(); activeSpec = null },
                                modifier = panelModifier,
                            )
                            adj != null -> AdjustmentPanel(
                                adj.adjustment, onChange = vm::previewAdjustment, onClose = vm::closeAdjustment, modifier = panelModifier,
                                lutLibrary = state.lutLibrary,
                                onLoadLut = { lutLauncher.launch(arrayOf("*/*")) },
                                clipped = state.layers.firstOrNull { it.id == adj.layerId }?.clipped,
                                onToggleClip = { vm.toggleClipping(adj.layerId) },
                            )
                            text != null -> TextPanel(
                                text.style, onChange = vm::updateTextLive, onDone = vm::commitTextEdit, onCancel = vm::cancelTextEdit,
                                onPickColor = { dialog = DialogKind.TEXT_COLOR }, modifier = panelModifier,
                            )
                        }

                        // Ebenen als modales Side Sheet auf schmalen Bildschirmen
                        if (!wide && layersOverlay) {
                            Box(
                                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                ) { layersOverlay = false },
                            )
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !wide && layersOverlay,
                            enter = slideInHorizontally { if (layersLeft) -it else it },
                            exit = slideOutHorizontally { if (layersLeft) -it else it },
                            modifier = Modifier.align(if (layersLeft) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight(),
                        ) {
                            LayersPanel(
                                state, vm,
                                width = if (compact) screenWidth * 0.88f else maxOf(settings.layersWidth.dp, 320.dp),
                                shape = if (layersLeft) RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp) else RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                                elevated = true,
                                onAddAdjustment = { adjustAsLayer = true; adjustSheet = true },
                            )
                        }
                    }
                    if (!layersLeft) dockedLayers()
                    if (settings.toolbarSide == Side.RIGHT) rail()
                }
            }
        }
    }

    if (adjustSheet) {
        AdjustmentsSheet(
            asLayer = adjustAsLayer,
            onModeChange = { adjustAsLayer = it },
            onAdjustment = { t -> adjustSheet = false; runAdjustment(t) },
            onFilter = { spec -> adjustSheet = false; runFilter(spec) },
            onDismiss = { adjustSheet = false },
        )
    }

    when (dialog) {
        DialogKind.NEW -> NewDocumentDialog(onCreate = { w, h, t -> vm.newDocument(w, h, t); dialog = null }, onDismiss = { dialog = null })
        DialogKind.IMAGE_SIZE -> ImageSizeDialog(
            state.docWidth, state.docHeight, state.docDpi, aiReady = vm.ai.firstReady(AiPurpose.UPSCALE, state.aiUpscaleModel) != null,
            onResize = { w, h, m, ai, dpi -> vm.resizeImage(w, h, m, ai, dpi); dialog = null },
            onManageAi = { dialog = DialogKind.AI_MODELS },
            onDismiss = { dialog = null },
        )
        DialogKind.CANVAS_SIZE -> CanvasSizeDialog(state.docWidth, state.docHeight, state.color, state.bgColor,
            onResize = { w, h, ax, ay, ext -> vm.resizeCanvas(w, h, ax, ay, ext); dialog = null }, onDismiss = { dialog = null })
        DialogKind.COLOR -> ColorPickerDialog(state.color, onPick = { vm.setColor(it); dialog = null }, onDismiss = { dialog = null },
            recent = state.recentColors, palette = state.palettes.firstOrNull { it.name == state.activePalette })
        DialogKind.BG_COLOR -> ColorPickerDialog(state.bgColor, onPick = { vm.setBgColor(it); dialog = null }, onDismiss = { dialog = null },
            recent = state.recentColors, palette = state.palettes.firstOrNull { it.name == state.activePalette })
        DialogKind.BRUSH -> BrushSettingsDialog(vm, onDismiss = { dialog = null })
        DialogKind.CROP_SIZE -> CropTargetDialog(state.cropTarget, state.docDpi, onConfirm = { vm.setCropTarget(it); dialog = null }, onDismiss = { dialog = null })
        DialogKind.NEW_GUIDE -> NewGuideDialog(onAdd = { v, p -> vm.addGuide(v, p); dialog = null }, onDismiss = { dialog = null })
        DialogKind.GUIDE_LAYOUT -> GuideLayoutDialog(onCreate = { c, r, m, g -> vm.guideLayout(c, r, m, g); dialog = null }, onDismiss = { dialog = null })
        DialogKind.ROTATE -> RotateDialog(onRotate = { vm.rotateArbitrary(it); dialog = null }, onDismiss = { dialog = null })
        DialogKind.SWATCHES -> SwatchesDialog(
            vm, onImport = { paletteImportLauncher.launch(arrayOf("*/*")) },
            onExport = { ext -> paletteExportExt = ext; paletteExportLauncher.launch("${state.activePalette}.$ext") },
            onDismiss = { dialog = null },
        )
        DialogKind.TEXT_COLOR -> ColorPickerDialog(state.textEdit?.style?.color ?: state.color, onPick = { c ->
            state.textEdit?.let { vm.updateTextLive(it.style.copy(color = c or 0xFF000000.toInt())) }; dialog = null
        }, onDismiss = { dialog = null })
        DialogKind.FEATHER -> FeatherDialog(onApply = { vm.featherSelection(it); dialog = null }, onDismiss = { dialog = null })
        DialogKind.SHORTCUTS -> ShortcutsDialog(onDismiss = { dialog = null })
        DialogKind.SETTINGS -> SettingsDialog(
            settings, state.proofOn, state.gamutWarning, settingsTab,
            onChange = vm::updateSettings, onToggleProof = vm::toggleProof, onToggleGamut = vm::toggleGamutWarning,
            iccLibrary = state.iccLibrary, onImportIcc = { iccLauncher.launch(arrayOf("*/*")) }, onDeleteIcc = vm::deleteIcc,
            onDismiss = { dialog = null },
        )
        DialogKind.TIFF_OPTIONS -> TiffOptionsDialog(settings.tiffCompression, settings.tiffLayers,
            onConfirm = { c, l -> vm.updateSettings { it.copy(tiffCompression = c, tiffLayers = l) }; dialog = null; tiffLauncher.launch("$baseName.tif") },
            onDismiss = { dialog = null },
        )
        DialogKind.ASSIGN_PROFILE, DialogKind.CONVERT_PROFILE -> {
            val convert = dialog == DialogKind.CONVERT_PROFILE
            ProfileDialog(
                convert, state.docProfileName, state.iccLibrary, settings.conversionIntent, settings.conversionBpc,
                onConfirm = { c, intent, bpc ->
                    if (convert) vm.convertToProfile(c.builtin, c.library, intent, bpc) else vm.assignProfile(c.builtin, c.library)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
        DialogKind.AI_MODELS -> AiModelsDialog(
            status = aiStatus, selectedInpaint = state.aiInpaintModel, selectedUpscale = state.aiUpscaleModel,
            highlight = state.requestAiModels,
            onDownload = vm::downloadModel, onDelete = vm::deleteModel, onSelect = vm::setAiModel,
            onDismiss = { dialog = null; vm.clearAiRequest() },
        )
        null -> {}
    }
    if (state.warnings.isNotEmpty()) WarningsDialog(state.warnings, onDismiss = vm::clearWarnings)
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, shortcut: String? = null, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null) },
        label = { Text(label) },
        badge = shortcut?.let { { Text(it, style = MaterialTheme.typography.labelMedium) } },
        selected = false,
        onClick = onClick,
    )
}

@Composable
private fun MenuHeader(text: String) = Text(
    text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
)

@Composable
private fun CheckItem(label: String, shortcut: String?, checked: Boolean, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(label) },
    leadingIcon = { Icon(if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null) },
    trailingIcon = shortcut?.let { { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    onClick = onClick,
)

@Composable
private fun Item(label: String, shortcut: String? = null, icon: ImageVector? = null, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(label) },
    leadingIcon = icon?.let { { Icon(it, null) } },
    trailingIcon = shortcut?.let { { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    onClick = onClick,
)

/** Korrekturen (als Einstellungsebene oder direkt) und Filter als Material Bottom Sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustmentsSheet(
    asLayer: Boolean,
    onModeChange: (Boolean) -> Unit,
    onAdjustment: (AdjustmentType) -> Unit,
    onFilter: (FilterSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Korrekturen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
                SegmentedButton(asLayer, { onModeChange(true) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Als Einstellungsebene") }
                SegmentedButton(!asLayer, { onModeChange(false) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Direkt anwenden") }
            }
            Text(
                if (asLayer) "Nicht-destruktiv: jederzeit änderbar, mit Maske, bleibt in der PSD erhalten." else "Verändert die Pixel der aktiven Ebene.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            for (t in AdjustmentType.entries) {
                ListItem(
                    headlineContent = { Text(t.label) },
                    leadingContent = { Icon(t.icon, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onAdjustment(t) }.padding(horizontal = 8.dp),
                )
            }
            for ((group, specs) in FilterSpecs.groupBy { it.group }) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                for (spec in specs) SpecItem(spec, onFilter)
            }
        }
    }
}

@Composable
private fun SpecItem(spec: FilterSpec, onPick: (FilterSpec) -> Unit) {
    ListItem(
        headlineContent = { Text(spec.label) },
        supportingContent = {
            Text(if (spec.params.isEmpty()) "Wird direkt angewendet" else spec.params.joinToString { it.label }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(spec.icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onPick(spec) }.padding(horizontal = 8.dp),
    )
}

@Composable
private fun FeatherDialog(onApply: (Float) -> Unit, onDismiss: () -> Unit) {
    var r by remember { mutableFloatStateOf(5f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weiche Auswahlkante") },
        text = { LabeledSlider("Radius", r, 0.5f..100f, "${r.roundToInt()} px") { r = it } },
        confirmButton = { TextButton(onClick = { onApply(r) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
