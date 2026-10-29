package de.photographics.app.ui

import de.photographics.core.tr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.gestures.detectDragGestures
import de.photographics.app.CropOverlay
import androidx.compose.ui.graphics.PathFillType
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.photographics.app.EditorUiState
import de.photographics.app.EditorViewModel
import de.photographics.app.SliderPlacement
import de.photographics.app.Tool
import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.app.TransformBox
import de.photographics.core.selection.SelectionShape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class GestureMode { PENDING, TOOL, TRANSFORM, PAN }

private fun PointerInputChange.isPen() = type == PointerType.Stylus || type == PointerType.Eraser

/**
 * Zeichenfläche. Eingaben:
 * - Stift: malt sofort (mit Druck); Radier-Ende = Radiergummi, Seitentaste = Pipette.
 *   Andere Berührungen während des Stiftstrichs (Handballen) werden ignoriert.
 * - Finger: im Stift-Modus nur Navigation (1 Finger verschieben, 2 Finger zoomen), sonst
 *   1 Finger = Werkzeug, 2 Finger = Zoom/Verschieben.
 * - Kurzes Tippen mit 2 Fingern = Rückgängig, mit 3 Fingern = Wiederholen.
 * - Maus: Linksklick = Werkzeug, Rechts-/Mittelklick oder Leertaste = Verschieben, Mausrad = Zoom.
 */
@Composable
fun CanvasArea(
    state: EditorUiState,
    vm: EditorViewModel,
    viewport: ViewportState,
    spacePressed: Boolean,
    modifier: Modifier = Modifier,
    keys: KeyModifiers = KeyModifiers(),
    contextMenu: @Composable (close: () -> Unit) -> Unit = {},
    sliders: SliderPlacement = SliderPlacement.LEFT,
    optionsAtBottom: Boolean = false,
    showRulers: Boolean = false,
    showGuides: Boolean = true,
) {
    // Gezogene Hilfslinie: Index (−1 = neue aus dem Lineal), senkrecht?, Lage im Dokument
    var guideDrag by remember { mutableStateOf<Triple<Int, Boolean, Double>?>(null) }
    // Overlays in Dokumentkoordinaten
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var hover by remember { mutableStateOf<Offset?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }
    var pathPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // Freistellen: Rahmen verschieben statt neu aufziehen
    var cropMoving by remember { mutableStateOf(false) }
    var cropStartRect by remember { mutableStateOf<IntRect?>(null) }
    // Perspektivisch freistellen: gezogene Ecke (−1 = ganzes Viereck verschieben)
    var cropCorner by remember { mutableStateOf(-1) }
    var cropStartQuad by remember { mutableStateOf<List<Float>?>(null) }

    val current by rememberUpdatedState(state)
    val space by rememberUpdatedState(spacePressed)
    val mods by rememberUpdatedState(keys)
    var contextAt by remember { mutableStateOf<Offset?>(null) }
    // Transformieren: gezogener Anfasser und Ausgangsmatrix
    var handle by remember { mutableStateOf<TransformHandle?>(null) }
    var handleStartT by remember { mutableStateOf<Affine?>(null) }
    var handleStartQuad by remember { mutableStateOf<DoubleArray?>(null) }
    val density = LocalDensity.current

    LaunchedEffect(Unit) { viewport.topInset = with(density) { 88.dp.toPx() } }
    // Nach Größenänderungen des Bildes keinen veralteten Pinselumriss stehen lassen
    LaunchedEffect(state.docWidth, state.docHeight) { cursor = null; hover = null }
    LaunchedEffect(state.docWidth, state.docHeight, viewport.viewSize) {
        viewport.setDocument(state.docWidth, state.docHeight)
    }

    // Während Live-Änderungen die schnelle Vorschau zeigen (wird auf Dokumentgröße gestreckt)
    val preview = state.previewBitmap
    // Feinerer Ausschnitt beim Hineinzoomen (nur ohne laufende Live-Vorschau)
    val detail = state.detail.takeIf { state.previewBitmap == null && vm.gpuLive.value == null }
    val tilePaint = remember { android.graphics.Paint().apply { isAntiAlias = false } }
    // GPU-Vorschau: Ebene mit Matrix (gefiltert), Deckkraft und Mischmodus
    val gpuPaint = remember { android.graphics.Paint().apply { isFilterBitmap = true; isAntiAlias = true } }
    val gpuMatrix = remember { android.graphics.Matrix() }
    val everywhere = remember { android.graphics.RectF(-1e9f, -1e9f, 1e9f, 1e9f) }
    // Sichtbaren Bereich nach dem Zoomen/Verschieben melden (kurz entprellt)
    LaunchedEffect(viewport, state.hasDocument) {
        snapshotFlow { Triple(viewport.scale, viewport.offset, viewport.viewSize) }.collectLatest { (sc, off, size) ->
            if (!state.hasDocument || size.width == 0 || sc <= 0f) return@collectLatest
            kotlinx.coroutines.delay(120)
            val l = kotlin.math.floor(-off.x / sc).toInt(); val t = kotlin.math.floor(-off.y / sc).toInt()
            val r = kotlin.math.ceil((size.width - off.x) / sc).toInt(); val b = kotlin.math.ceil((size.height - off.y) / sc).toInt()
            vm.setViewport(de.photographics.core.image.IntRect(l, t, r, b), sc)
        }
    }
    val backgroundArgb = canvasBackground.toArgb()
    val checkerPaint = remember {
        android.graphics.Paint().apply { shader = android.graphics.BitmapShader(checkerBitmap().asAndroidBitmap(), android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT) }
    }
    // Laufende Ameisen: nur animieren, solange eine Auswahl oder ein Rahmen zu sehen ist, und mit
    // 20 Schritten/s statt der Bildwiederholrate – jeder Schritt zeichnet die Überlagerungen neu
    val needAnts = state.selectionOutlines.isNotEmpty() || dragStart != null || pathPoints.isNotEmpty()
    var antPhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(needAnts) { while (needAnts) { kotlinx.coroutines.delay(50); antPhase = (antPhase + 1f) % 16f } }

    // ------------------------------------------------------------ Werkzeugaktionen
    // Werkzeug des laufenden Strichs (kann vom Stift-Radierer/der Seitentaste überschrieben sein)
    val activeToolRef = remember { arrayOf(Tool.BRUSH) }

    fun toolStart(p: Offset, pressure: Float, override: Tool? = null) {
        val s = current
        val tool = override ?: s.tool
        activeToolRef[0] = tool
        cursor = p
        when {
            (tool == Tool.CLONE || tool == Tool.HEAL) && (s.settingCloneSource || mods.alt) -> vm.setCloneSource(p.x, p.y)
            tool == Tool.BRUSH && mods.alt -> { activeToolRef[0] = Tool.EYEDROPPER; vm.pickColor(p.x, p.y) }
            tool == Tool.TEXT -> vm.textTap(p.x, p.y)
            tool == Tool.RED_EYE -> vm.redEye(p.x, p.y)
            tool == Tool.TRANSFORM -> {
                val box = s.transform
                if (box != null) {
                    handle = hitHandle(box, viewport, viewport.toScreen(p), density.density)
                    handleStartT = box.affine
                    handleStartQuad = box.corners()
                    dragStart = p
                }
            }
            tool == Tool.PATCH -> {
                dragStart = p; dragNow = p
                if (!s.hasSelection) pathPoints = listOf(p)
            }
            tool.isPaintTool -> {
                if (tool == Tool.SPOT_HEAL) pathPoints = listOf(p)
                vm.strokeBegin(p.x, p.y, pressure, override)
            }
            tool == Tool.MOVE -> {
                // Hilfslinie anfassen (8 dp Fangbereich), sonst die Ebene verschieben
                val sp = viewport.toScreen(p)
                val hit = if (showGuides) s.guides.withIndex().firstOrNull { (_, g) ->
                    val gs = viewport.toScreen(Offset(g.position.toFloat(), g.position.toFloat()))
                    abs((if (g.vertical) gs.x - sp.x else gs.y - sp.y)) < 8f * density.density
                } else null
                if (hit != null) guideDrag = Triple(hit.index, hit.value.vertical, hit.value.position)
                else {
                    dragStart = p
                    // Strg/Cmd kehrt „Automatisch auswählen“ für diesen Zug um (wie in Photoshop)
                    if (s.autoSelectLayer != mods.ctrl) vm.moveBegin(p.x, p.y) else vm.moveBegin()
                }
            }
            tool == Tool.SELECT && s.selectionShape == SelectionShape.WAND -> vm.magicWand(p.x, p.y)
            tool == Tool.SELECT || tool == Tool.GRADIENT -> { dragStart = p; dragNow = p; pathPoints = listOf(p) }
            tool == Tool.CROP -> {
                dragStart = p; dragNow = p
                cropStartRect = s.cropRect
                cropMoving = !s.cropStraighten && s.cropRect?.contains(p.x.toInt(), p.y.toInt()) == true
                cropStartQuad = s.cropQuad
                cropCorner = s.cropQuad?.let { q ->
                    val sp = viewport.toScreen(p)
                    (0..3).minByOrNull { i -> (viewport.toScreen(Offset(q[i * 2], q[i * 2 + 1])) - sp).getDistance() }
                        ?.takeIf { i -> (viewport.toScreen(Offset(q[i * 2], q[i * 2 + 1])) - sp).getDistance() < 40f * density.density }
                } ?: -1
            }
            tool == Tool.EYEDROPPER -> vm.pickColor(p.x, p.y, background = mods.alt && s.tool == Tool.EYEDROPPER)
        }
    }

    fun toolMove(p: Offset, pressure: Float) {
        val s = current
        val tool = activeToolRef[0]
        cursor = p
        when {
            (tool == Tool.CLONE || tool == Tool.HEAL) && s.settingCloneSource -> {}
            tool == Tool.EYEDROPPER -> vm.pickColor(p.x, p.y, background = mods.alt && s.tool == Tool.EYEDROPPER)
            tool == Tool.TRANSFORM -> {
                val box = s.transform; val h = handle; val t0 = handleStartT; val a = dragStart
                val q0 = handleStartQuad
                if (box != null && h != null && t0 != null && a != null) {
                    if (q0 != null && (box.quad != null || s.transformMode != de.photographics.app.TransformMode.FREE))
                        vm.transformPreviewQuad(dragQuad(box.copy(affine = t0), s.transformMode, h, q0, a, p, mods.shift))
                    else vm.transformPreview(dragTransform(box.copy(affine = t0), h, a, p, mods.shift))
                }
            }
            tool == Tool.PATCH -> {
                dragNow = p
                if (!s.hasSelection) pathPoints = pathPoints + p
            }
            tool.isPaintTool -> {
                if (tool == Tool.SPOT_HEAL) pathPoints = pathPoints + p
                vm.strokeMove(p.x, p.y, pressure)
            }
            tool == Tool.MOVE -> {
                val gd = guideDrag
                if (gd != null) guideDrag = gd.copy(third = vm.snapGuide(gd.second, (if (gd.second) p.x else p.y).toDouble(), 8.0 / viewport.scale * density.density))
                else dragStart?.let { vm.moveTo(p.x - it.x, p.y - it.y, 8f * density.density / viewport.scale) }
            }
            tool == Tool.GRADIENT -> {
                // Shift: auf 45°-Schritte einrasten
                dragNow = if (mods.shift) dragStart?.let { a ->
                    val ang = Math.round(kotlin.math.atan2(p.y - a.y, p.x - a.x) / (Math.PI / 4)) * (Math.PI / 4)
                    val len = (p - a).getDistance()
                    Offset(a.x + (len * kotlin.math.cos(ang)).toFloat(), a.y + (len * kotlin.math.sin(ang)).toFloat())
                } ?: p else p
            }
            tool == Tool.SELECT && s.selectionShape == SelectionShape.WAND -> {}
            tool == Tool.SELECT -> {
                // Shift: Quadrat/Kreis
                dragNow = if (s.selectionShape != SelectionShape.LASSO && mods.shift) dragStart?.let { a ->
                    val d = max(abs(p.x - a.x), abs(p.y - a.y))
                    Offset(a.x + d * kotlin.math.sign(p.x - a.x), a.y + d * kotlin.math.sign(p.y - a.y))
                } ?: p else p
                if (s.selectionShape == SelectionShape.LASSO) pathPoints = pathPoints + p
            }
            tool == Tool.CROP -> {
                dragNow = p
                val q0 = cropStartQuad
                if (q0 != null) dragStart?.let { a ->
                    val dx = p.x - a.x; val dy = p.y - a.y
                    vm.setCropQuad(q0.mapIndexed { k, v -> if (cropCorner < 0 || k / 2 == cropCorner) v + (if (k % 2 == 0) dx else dy) else v })
                } else if (!s.cropStraighten) dragStart?.let { a -> vm.cropDrag(a.x, a.y, p.x, p.y, cropMoving, cropStartRect) }
            }
            tool == Tool.EYEDROPPER -> vm.pickColor(p.x, p.y, background = mods.alt && s.tool == Tool.EYEDROPPER)
        }
    }

    fun toolEnd(cancelled: Boolean) {
        val s = current
        val tool = activeToolRef[0]
        when {
            tool.isPaintTool -> if (cancelled) vm.strokeCancel() else vm.strokeEnd()
            tool == Tool.MOVE -> {
                val gd = guideDrag
                if (gd != null) { if (!cancelled) vm.moveGuide(gd.first, gd.third); guideDrag = null; vm.endGuideDrag() } else vm.moveEnd()
            }
            tool == Tool.TRANSFORM -> { handle = null; handleStartT = null; handleStartQuad = null }
            tool == Tool.CROP -> {
                val a = dragStart; val b = dragNow
                if (s.cropStraighten && !cancelled && a != null && b != null) vm.straighten(a.x, a.y, b.x, b.y)
            }
            tool == Tool.PATCH -> {
                val a = dragStart; val b = dragNow
                if (!cancelled && a != null && b != null) {
                    if (s.hasSelection) {
                        if (abs(a.x - b.x) + abs(a.y - b.y) > 2) vm.patchSelection(b.x - a.x, b.y - a.y)
                    } else if (pathPoints.size > 2) {
                        // Ohne Auswahl umrandet das Werkzeug zuerst den Fehler (Lasso)
                        val pts = FloatArray(pathPoints.size * 2)
                        pathPoints.forEachIndexed { i, o -> pts[i * 2] = o.x; pts[i * 2 + 1] = o.y }
                        vm.finishLasso(pts)
                    }
                }
            }
            tool == Tool.SELECT -> {
                val a = dragStart; val b = dragNow
                if (!cancelled && a != null && b != null) {
                    val tiny = abs(a.x - b.x) * viewport.scale < 6 && abs(a.y - b.y) * viewport.scale < 6
                    when {
                        tiny -> vm.deselect()
                        s.selectionShape == SelectionShape.OBJECT -> vm.objectSelect(a.x, a.y, b.x, b.y)
                        s.selectionShape == SelectionShape.LASSO -> {
                            val pts = FloatArray(pathPoints.size * 2)
                            pathPoints.forEachIndexed { i, o -> pts[i * 2] = o.x; pts[i * 2 + 1] = o.y }
                            vm.finishSelection(pts)
                        }
                        else -> vm.finishSelection(floatArrayOf(a.x, a.y, b.x, b.y))
                    }
                }
            }
            tool == Tool.GRADIENT -> {
                val a = dragStart; val b = dragNow
                if (!cancelled && a != null && b != null && (b - a).getDistance() * viewport.scale >= 4) vm.applyGradient(a.x, a.y, b.x, b.y)
            }
            else -> {}
        }
        activeToolRef[0] = s.tool
        dragStart = null; dragNow = null; pathPoints = emptyList()
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport.viewSize = it }
            .pointerHoverIcon(
                when {
                    spacePressed -> PointerIcon.Hand
                    state.tool == Tool.TEXT -> PointerIcon.Text
                    state.tool == Tool.MOVE || state.tool == Tool.TRANSFORM -> PointerIcon.Hand
                    state.tool.isPaintTool || state.tool in setOf(Tool.SELECT, Tool.GRADIENT, Tool.CROP, Tool.EYEDROPPER, Tool.RED_EYE, Tool.PATCH) -> PointerIcon.Crosshair
                    else -> PointerIcon.Default
                },
            )
            // Hover (Stift über dem Display, Maus) und Mausrad
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull() ?: continue
                        when (e.type) {
                            PointerEventType.Enter, PointerEventType.Move ->
                                if (!ch.pressed && (ch.isPen() || ch.type == PointerType.Mouse)) {
                                    hover = viewport.toDoc(ch.position)
                                    if (ch.isPen()) vm.onStylusDetected()
                                }
                            PointerEventType.Exit -> hover = null
                            PointerEventType.Scroll -> {
                                val d = ch.scrollDelta
                                if (d.y != 0f) viewport.transform(1.15f.pow(-d.y), ch.position)
                                ch.consume()
                            }
                            else -> {}
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    hover = null
                    val startTime = down.uptimeMillis
                    var mode = GestureMode.PENDING
                    var toolPointer: PointerId? = null
                    var exclusivePointer = false // Stift/Maus: andere Zeiger ignorieren
                    var maxPointers = 1
                    var navMovement = 0f
                    val buttons = currentEvent.buttons
                    val slop = 6f

                    fun startPen(ch: PointerInputChange) {
                        vm.onStylusDetected()
                        toolPointer = ch.id
                        exclusivePointer = true
                        mode = GestureMode.TOOL
                        val p = viewport.toDoc(ch.position)
                        val sideButton = currentEvent.buttons.isSecondaryPressed
                        val override = when {
                            sideButton -> Tool.EYEDROPPER
                            ch.type == PointerType.Eraser -> Tool.ERASER
                            else -> null
                        }
                        toolStart(p, ch.pressure, override)
                    }

                    when {
                        space -> mode = GestureMode.PAN
                        down.isPen() -> startPen(down)
                        down.type == PointerType.Mouse && buttons.isSecondaryPressed -> {
                            // Rechtsklick: Kontextmenü an der Mausposition
                            contextAt = down.position
                            mode = GestureMode.TRANSFORM
                        }
                        down.type == PointerType.Mouse && buttons.isTertiaryPressed -> mode = GestureMode.PAN
                        down.type == PointerType.Mouse -> {
                            toolPointer = down.id; exclusivePointer = true; mode = GestureMode.TOOL
                            toolStart(viewport.toDoc(down.position), 1f)
                        }
                        current.stylusOnly -> mode = GestureMode.PAN
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        // Stift kommt während einer Finger-Navigation dazu -> Stift hat Vorrang
                        if (mode != GestureMode.TOOL) {
                            event.changes.firstOrNull { it.isPen() && it.changedToDown() }?.let { pen ->
                                startPen(pen)
                            }
                        }

                        if (mode == GestureMode.TOOL && toolPointer != null && (exclusivePointer || pressed.size < 2)) {
                            // Nur den malenden Zeiger verfolgen, alles andere (Handballen) ignorieren
                            val ch = event.changes.firstOrNull { it.id == toolPointer }
                            if (ch == null || !ch.pressed) {
                                toolEnd(cancelled = false)
                                break
                            }
                            // Historische Punkte nutzen: Stifte liefern bis zu 240 Hz
                            // Druck nur vom Stift; Finger melden je nach Gerät zufällige Werte
                            val pr = if (ch.isPen()) ch.pressure else 1f
                            for (h in ch.historical) toolMove(viewport.toDoc(h.position), pr)
                            toolMove(viewport.toDoc(ch.position), pr)
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        maxPointers = max(maxPointers, pressed.size)
                        if (pressed.size >= 2) {
                            if (mode == GestureMode.TOOL) toolEnd(cancelled = true)
                            mode = GestureMode.TRANSFORM
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            navMovement += pan.getDistance() + abs(zoom - 1f) * 200f
                            viewport.transform(zoom, event.calculateCentroid(useCurrent = true), pan)
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val ch = pressed[0]
                            when (mode) {
                                GestureMode.PAN -> {
                                    val d = ch.positionChange()
                                    navMovement += d.getDistance()
                                    viewport.pan(d)
                                }
                                GestureMode.PENDING -> if ((ch.position - down.position).getDistance() > slop) {
                                    mode = GestureMode.TOOL
                                    toolPointer = ch.id
                                    toolStart(viewport.toDoc(down.position), 1f)
                                    toolMove(viewport.toDoc(ch.position), 1f)
                                }
                                else -> {}
                            }
                            ch.consume()
                        }

                        if (event.changes.none { it.pressed }) {
                            val quickTap = event.changes.first().uptimeMillis - startTime < 300 && navMovement < 30f
                            when {
                                maxPointers >= 2 && quickTap -> if (maxPointers == 2) vm.undo() else vm.redo()
                                mode == GestureMode.PENDING -> {
                                    // Einfaches Tippen = ein Abdruck bzw. Klick
                                    toolStart(viewport.toDoc(down.position), if (down.isPen()) down.pressure else 1f)
                                    toolEnd(cancelled = false)
                                }
                            }
                            break
                        }
                    }
                }
            },
    ) {
        // Bild und Überlagerungen in getrennten Ebenen: ein neuer Render-Schritt zeichnet nur das Bild neu
        // auf, Ameisen, Pinselumriss und Hilfslinien nur die Überlagerungen – nie die übrige Oberfläche
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            @Suppress("UNUSED_VARIABLE") val tick = vm.renderTick.longValue // nur die Zeichnung erneuern, nicht die Oberfläche
            val scale = viewport.scale
            val offset = viewport.offset
            val docSize = Size(state.docWidth * scale, state.docHeight * scale)
            val base = state.bitmap
            val docRect = android.graphics.RectF(offset.x, offset.y, offset.x + docSize.width, offset.y + docSize.height)
            drawIntoCanvas { c ->
                // Hintergrund nur neben dem Bild (Überzeichnen kostet bei 2560×1600 spürbar GPU-Zeit)
                val nc = c.nativeCanvas
                nc.save(); if (base != null) nc.clipOutRect(docRect); nc.drawColor(backgroundArgb); nc.restore()
            }
            base?.takeIf { de.photographics.app.FrameStats.skip and 1 == 0 }?.let { base ->
                // Kacheln direkt in Bildschirmkoordinaten (nur sichtbare); Pixel ab 200 % scharfkantig
                tilePaint.isFilterBitmap = scale < 2f
                val clip = android.graphics.RectF(0f, 0f, size.width, size.height)
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val ds = state.displayScale * scale
                    // Schachbrett für Transparenz (in Bildschirmgröße), nur unter nicht deckenden Kacheln
                    nc.save(); nc.clipRect(docRect)
                    base.drawBackdrop(nc, offset.x, offset.y, ds, clip, checkerPaint)
                    nc.restore()
                    val pr = state.previewRect
                    val gpu = vm.gpuLive.value
                    if (gpu != null) {
                        // GPU-Vorschau: im sichtbaren Bereich darunter + Ebene (Matrix, Deckkraft, Mischmodus) + darüber
                        val rg = gpu.region
                        val d = android.graphics.RectF(offset.x + rg.left * scale, offset.y + rg.top * scale, offset.x + rg.right * scale, offset.y + rg.bottom * scale)
                        nc.save(); nc.clipOutRect(d)
                        if (!d.contains(clip)) base.draw(nc, offset.x, offset.y, ds, clip, tilePaint)
                        nc.restore()
                        d.intersect(docRect)
                        // Isoliert mischen: Mischmodi wirken nur auf das Bild, nicht auf Schachbrett oder Hintergrund
                        nc.saveLayer(d, null)
                        nc.clipRect(d)
                        val sc = gpu.step * scale
                        gpu.below?.draw(nc, offset.x + rg.left * scale, offset.y + rg.top * scale, sc, clip, tilePaint)
                        gpuPaint.alpha = (gpu.alpha * 255f + 0.5f).toInt()
                        if (android.os.Build.VERSION.SDK_INT >= 29) gpuPaint.blendMode = gpuBlend(gpu.blend)
                        nc.save()
                        nc.translate(offset.x, offset.y); nc.scale(scale, scale)
                        gpuMatrix.setValues(gpu.matrix); nc.concat(gpuMatrix)
                        gpu.layer.draw(nc, 0f, 0f, 1f, everywhere, gpuPaint)
                        nc.restore()
                        gpu.above?.draw(nc, offset.x + rg.left * scale, offset.y + rg.top * scale, sc, clip, tilePaint)
                        nc.restore()
                    } else if (preview != null && !pr.isEmpty) {
                        // Live-Vorschau (sichtbarer Ausschnitt) über der Basis; Basis nur außerhalb
                        val d = android.graphics.RectF(offset.x + pr.left * scale, offset.y + pr.top * scale, offset.x + pr.right * scale, offset.y + pr.bottom * scale)
                        val docClip = android.graphics.RectF(offset.x, offset.y, offset.x + state.docWidth * scale, offset.y + state.docHeight * scale)
                        nc.save(); nc.clipOutRect(d)
                        if (!d.contains(clip)) base.draw(nc, offset.x, offset.y, ds, clip, tilePaint)
                        nc.restore()
                        nc.save(); nc.clipRect(d); nc.clipRect(docClip)
                        preview.draw(nc, d.left, d.top, pr.width.toFloat() / preview.width * scale, clip, tilePaint)
                        nc.restore()
                    } else if (detail == null) base.draw(nc, offset.x, offset.y, ds, clip, tilePaint)
                    else {
                        // Basis nur außerhalb des Ausschnitts: sonst würden halbtransparente Pixel doppelt gedeckt
                        val sh = detail.shown
                        val d = android.graphics.RectF(offset.x + sh.left * scale, offset.y + sh.top * scale,
                            offset.x + minOf(sh.right, state.docWidth) * scale, offset.y + minOf(sh.bottom, state.docHeight) * scale)
                        nc.save(); nc.clipOutRect(d)
                        if (!d.contains(clip)) base.draw(nc, offset.x, offset.y, ds, clip, tilePaint)
                        nc.restore()
                        nc.save(); nc.clipRect(d)
                        detail.bitmap.draw(nc, offset.x + detail.rect.left * scale, offset.y + detail.rect.top * scale, detail.step * scale, clip, tilePaint)
                        nc.restore()
                    }
                }
            }
            drawRect(Color(0x55000000), topLeft = offset, size = docSize, style = Stroke(1f))
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val scale = viewport.scale
            val offset = viewport.offset
            val docSize = Size(state.docWidth * scale, state.docHeight * scale)
            if (de.photographics.app.FrameStats.skip and 2 != 0) return@Canvas

            fun s(p: Offset) = viewport.toScreen(p)

            // Auswahl ("laufende Ameisen")
            for (poly in state.selectionOutlines) {
                if (poly.size < 6) continue
                val path = Path().apply {
                    moveTo(poly[0] * scale + offset.x, poly[1] * scale + offset.y)
                    var i = 2
                    while (i + 1 < poly.size) { lineTo(poly[i] * scale + offset.x, poly[i + 1] * scale + offset.y); i += 2 }
                    close()
                }
                marchingAnts(path, antPhase)
            }

            // Auswahl während des Ziehens
            val a = dragStart; val b = dragNow
            if (state.tool == Tool.SELECT && a != null && b != null) {
                val path = Path()
                when (state.selectionShape) {
                    SelectionShape.ELLIPSE -> path.addOval(rectBetween(s(a), s(b)))
                    SelectionShape.LASSO -> pathPoints.forEachIndexed { i, p -> if (i == 0) path.moveTo(s(p).x, s(p).y) else path.lineTo(s(p).x, s(p).y) }
                    else -> path.addRect(rectBetween(s(a), s(b)))
                }
                marchingAnts(path, antPhase)
            }

            // Verlaufslinie während des Ziehens: Start- und Endpunkt mit Linie
            if (state.tool == Tool.GRADIENT && a != null && b != null) {
                val sa = s(a); val sb = s(b)
                drawLine(Color.Black.copy(alpha = 0.5f), sa, sb, strokeWidth = 3f * density.density)
                drawLine(Color.White, sa, sb, strokeWidth = 1.5f * density.density)
                drawCircle(Color(state.color), 6f * density.density, sa); drawCircle(Color.White, 6f * density.density, sa, style = Stroke(1.5f * density.density))
                drawCircle(if (state.gradientToTransparent) Color.Transparent else Color(state.bgColor), 6f * density.density, sb)
                drawCircle(Color.White, 6f * density.density, sb, style = Stroke(1.5f * density.density))
            }

            // Spur der Bereichsreparatur
            if (state.tool == Tool.SPOT_HEAL && pathPoints.size > 1) {
                val path = Path()
                pathPoints.forEachIndexed { i, p -> if (i == 0) path.moveTo(s(p).x, s(p).y) else path.lineTo(s(p).x, s(p).y) }
                drawPath(path, Color(0x66FF3B30), style = Stroke(width = state.brush.size * scale, cap = StrokeCap.Round))
            }

            // Hilfslinien (über die ganze Ansicht) und gerade gezogene Hilfslinie
            if (showGuides) {
                val guideColor = Color(0xFF00BCD4)
                state.guides.forEachIndexed { i, g ->
                    if (guideDrag?.first == i) return@forEachIndexed
                    val o = s(Offset(g.position.toFloat(), g.position.toFloat()))
                    if (g.vertical) drawLine(guideColor, Offset(o.x, 0f), Offset(o.x, size.height), 1f)
                    else drawLine(guideColor, Offset(0f, o.y), Offset(size.width, o.y), 1f)
                }
            }
            guideDrag?.let { (_, vertical, pos) ->
                val o = s(Offset(pos.toFloat(), pos.toFloat()))
                if (vertical) drawLine(Color(0xFF00BCD4), Offset(o.x, 0f), Offset(o.x, size.height), 2f)
                else drawLine(Color(0xFF00BCD4), Offset(0f, o.y), Offset(size.width, o.y), 2f)
            }
            // Intelligente Hilfslinien beim Verschieben
            for (l in state.smartLines) {
                val a = if (l.vertical) s(Offset(l.pos.toFloat(), l.from.toFloat())) else s(Offset(l.from.toFloat(), l.pos.toFloat()))
                val b = if (l.vertical) s(Offset(l.pos.toFloat(), l.to.toFloat())) else s(Offset(l.to.toFloat(), l.pos.toFloat()))
                drawLine(Color(0xFFFF2D9B), a, b, 1.5f)
            }

            // Perspektivischer Freistellungsrahmen: Viereck mit perspektivischem Raster
            val cq = state.cropQuad
            if (state.tool == Tool.CROP && cq != null) {
                val q = cq.map { it.toDouble() }.toDoubleArray()
                val h = runCatching { de.photographics.core.image.Homography.squareToQuad(q) }.getOrNull()
                val pts = (0..3).map { s(Offset(cq[it * 2], cq[it * 2 + 1])) }
                val frame = Path().apply { moveTo(pts[0].x, pts[0].y); for (k in 1..3) lineTo(pts[k].x, pts[k].y); close() }
                drawPath(Path().apply { fillType = PathFillType.EvenOdd; addRect(androidx.compose.ui.geometry.Rect(offset, docSize)); addPath(frame) }, Color(0x99000000))
                if (h != null) for (k in 1..3) {
                    val t = k / 4.0
                    fun m(u: Double, v: Double) = s(Offset(h.mapX(u, v).toFloat(), h.mapY(u, v).toFloat()))
                    drawLine(Color(0x88FFFFFF), m(t, 0.0), m(t, 1.0), 1f); drawLine(Color(0x88FFFFFF), m(0.0, t), m(1.0, t), 1f)
                }
                drawPath(frame, Color.White, style = Stroke(2f))
                for (pt in pts) { drawCircle(Color.White, 9f * density.density, pt); drawCircle(Color(0xFF1E88E5), 9f * density.density, pt, style = Stroke(2.5f)) }
            }
            // Freistellungsrahmen (bei gedrehtem Freistellen entgegen der Bilddrehung gedreht)
            val crop = state.cropRect
            if (state.tool == Tool.CROP && crop != null && cq == null) {
                val cx = (crop.left + crop.right) / 2f; val cy = (crop.top + crop.bottom) / 2f
                val hw = crop.width / 2f; val hh = crop.height / 2f
                val rad = Math.toRadians(-state.cropAngle.toDouble())
                val cs = kotlin.math.cos(rad).toFloat(); val sn = kotlin.math.sin(rad).toFloat()
                fun corner(u: Float, v: Float): Offset {
                    val x = (u * 2 - 1) * hw; val y = (v * 2 - 1) * hh
                    return s(Offset(cx + x * cs - y * sn, cy + x * sn + y * cs))
                }
                val tl = corner(0f, 0f); val tr = corner(1f, 0f); val br = corner(1f, 1f); val bl = corner(0f, 1f)
                val frame = Path().apply { moveTo(tl.x, tl.y); lineTo(tr.x, tr.y); lineTo(br.x, br.y); lineTo(bl.x, bl.y); close() }
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(offset, docSize)); addPath(frame)
                }
                drawPath(outside, Color(0x99000000))
                drawPath(frame, Color.White, style = Stroke(2f))
                val guide = Color(0x88FFFFFF)
                fun lineUV(u0: Float, v0: Float, u1: Float, v1: Float, w: Float = 1f) = drawLine(guide, corner(u0, v0), corner(u1, v1), w)
                when (state.cropOverlay) {
                    CropOverlay.THIRDS -> for (k in 1..2) { lineUV(k / 3f, 0f, k / 3f, 1f); lineUV(0f, k / 3f, 1f, k / 3f) }
                    CropOverlay.GOLDEN -> for (k in floatArrayOf(0.382f, 0.618f)) { lineUV(k, 0f, k, 1f); lineUV(0f, k, 1f, k) }
                    CropOverlay.GRID -> for (k in 1..7) { lineUV(k / 8f, 0f, k / 8f, 1f, 0.6f); lineUV(0f, k / 8f, 1f, k / 8f, 0.6f) }
                    CropOverlay.DIAGONAL -> { lineUV(0f, 0f, 1f, 1f); lineUV(1f, 0f, 0f, 1f) }
                    CropOverlay.NONE -> {}
                }
                // Gerade-ausrichten-Linie
                val a0 = dragStart; val b0 = dragNow
                if (state.cropStraighten && a0 != null && b0 != null) drawLine(Color(0xFFFFD54F), s(a0), s(b0), 3f)
            }

            // Stempelquelle
            if ((state.tool == Tool.CLONE || state.tool == Tool.HEAL) && state.cloneSource != null) {
                val c = s(Offset(state.cloneSource.first, state.cloneSource.second))
                drawCircle(Color.White, radius = state.brush.size * scale / 2f, center = c, style = Stroke(1.5f))
                drawLine(Color.White, c - Offset(8f, 0f), c + Offset(8f, 0f), 1.5f)
                drawLine(Color.White, c - Offset(0f, 8f), c + Offset(0f, 8f), 1.5f)
            }

            // Ausbessern: verschobene Auswahl bzw. Lasso-Umriss
            if (state.tool == Tool.PATCH && a != null && b != null) {
                if (state.hasSelection) {
                    val d = Offset((b.x - a.x) * scale, (b.y - a.y) * scale)
                    for (poly in state.selectionOutlines) {
                        if (poly.size < 6) continue
                        val path = Path().apply {
                            moveTo(poly[0] * scale + offset.x + d.x, poly[1] * scale + offset.y + d.y)
                            var i = 2
                            while (i + 1 < poly.size) { lineTo(poly[i] * scale + offset.x + d.x, poly[i + 1] * scale + offset.y + d.y); i += 2 }
                            close()
                        }
                        drawPath(path, Color(0xFF4FC3F7), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), antPhase)))
                    }
                } else if (pathPoints.size > 1) {
                    val path = Path()
                    pathPoints.forEachIndexed { i, pp -> if (i == 0) path.moveTo(s(pp).x, s(pp).y) else path.lineTo(s(pp).x, s(pp).y) }
                    marchingAnts(path, antPhase)
                }
            }

            // Transformationsrahmen mit Anfassern
            state.transform?.let { box -> drawTransformBox(box, viewport, density.density) }

            // Pinselumriss (beim Malen oder wenn der Stift über dem Display schwebt)
            val cur = hover ?: cursor
            if (cur != null && state.tool.isPaintTool) {
                val c = s(cur)
                val r = max(2f, state.brush.size * scale / 2f)
                drawCircle(Color.Black, r, c, style = Stroke(2.5f))
                drawCircle(Color.White, r, c, style = Stroke(1f))
            }
        }

        // Schnellregler für Größe und Deckkraft (Daumen der freien Hand). Ihre Höhe richtet sich
        // nach dem Platz; auf sehr niedrigen Flächen (Handy quer) werden sie ausgeblendet.
        if (state.tool.isPaintTool && sliders != SliderPlacement.HIDDEN) {
            val density = LocalDensity.current
            val availDp = with(density) { viewport.viewSize.height.toDp() } - 56.dp - 72.dp // Optionsleiste, Zoom-Pille
            val track = minOf((availDp - 16.dp - 56.dp) / 2, 180.dp) // 2 Beschriftungen à 28 dp
            if (track >= 72.dp) {
                val left = sliders == SliderPlacement.LEFT
                Column(
                    Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(start = 12.dp, end = 12.dp, top = if (optionsAtBottom) 0.dp else 56.dp, bottom = if (optionsAtBottom) 56.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val b = state.brush
                    VerticalSlider(
                        tr("Größe"), sqrt((b.size - 1f) / 499f), tr("{0} px", b.size.roundToInt()),
                        onChange = { v -> vm.updateBrush(current.brush.copy(size = 1f + 499f * v * v)) },
                        trackHeight = track,
                    )
                    VerticalSlider(
                        tr("Deckkr."), b.opacity, "${(b.opacity * 100).roundToInt()} %",
                        onChange = { v -> vm.setBrushOpacity(v) },
                        trackHeight = track,
                    )
                }
            }
        }

        // Lineale: Teilung in Dokumentpixeln; aus dem Lineal ziehen erzeugt eine Hilfslinie
        if (showRulers) {
            val rulerPx = with(density) { 20.dp.toPx() }
            Ruler(viewport, vertical = false, Modifier.align(Alignment.TopStart).fillMaxWidth().height(20.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> guideDrag = Triple(-1, false, viewport.toDoc(o).y.toDouble()) },
                        onDrag = { ch, _ -> val y = viewport.toDoc(ch.position).y.toDouble(); guideDrag = Triple(-1, false, vm.snapGuide(false, y, 8.0 / viewport.scale * density.density)) },
                        onDragEnd = { guideDrag?.let { vm.addGuide(false, it.third) }; guideDrag = null; vm.endGuideDrag() },
                        onDragCancel = { guideDrag = null; vm.endGuideDrag() },
                    )
                })
            Ruler(viewport, vertical = true, Modifier.align(Alignment.TopStart).padding(top = 20.dp).fillMaxHeight().width(20.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> guideDrag = Triple(-1, true, viewport.toDoc(o + Offset(0f, rulerPx)).x.toDouble()) },
                        onDrag = { ch, _ -> val x = viewport.toDoc(ch.position + Offset(0f, rulerPx)).x.toDouble(); guideDrag = Triple(-1, true, vm.snapGuide(true, x, 8.0 / viewport.scale * density.density)) },
                        onDragEnd = { guideDrag?.let { vm.addGuide(true, it.third) }; guideDrag = null; vm.endGuideDrag() },
                        onDragCancel = { guideDrag = null; vm.endGuideDrag() },
                    )
                })
        }

        // Kontextmenü (Rechtsklick)
        contextAt?.let { at ->
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                DropdownMenu(expanded = true, onDismissRequest = { contextAt = null }) {
                    contextMenu { contextAt = null }
                }
            }
        }

        Surface(
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text("${(viewport.scale * 100).roundToInt()} %", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { viewport.fit() }) { Text(tr("Einpassen")) }
                TextButton(onClick = { viewport.zoomTo(1f) }) { Text("100 %") }
            }
        }
    }
}

private fun DrawScope.marchingAnts(path: Path, phase: Float) {
    drawPath(path, Color.Black, style = Stroke(1.5f))
    drawPath(path, Color.White, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), phase)))
}

private fun rectBetween(a: Offset, b: Offset) =
    androidx.compose.ui.geometry.Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

private fun rectOf(a: Offset, b: Offset, w: Int, h: Int): IntRect {
    val l = min(a.x, b.x).roundToInt().coerceIn(0, w)
    val t = min(a.y, b.y).roundToInt().coerceIn(0, h)
    val r = max(a.x, b.x).roundToInt().coerceIn(0, w)
    val btm = max(a.y, b.y).roundToInt().coerceIn(0, h)
    return IntRect(l, t, max(r, l + 1), max(btm, t + 1))
}

private fun checkerBitmap(): ImageBitmap {
    val n = 16
    val px = IntArray(n * n * 4) { i ->
        val x = i % (n * 2); val y = i / (n * 2)
        if ((x / n + y / n) % 2 == 0) 0xFFCCCCCC.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(px, n * 2, n * 2, Bitmap.Config.ARGB_8888).asImageBitmap()
}


/** Gedrückte Modifikatortasten (von der Tastatur-Behandlung des Bildschirms). */
data class KeyModifiers(val shift: Boolean = false, val alt: Boolean = false, val ctrl: Boolean = false)

/** Anfasser des Transformationsrahmens. */
enum class TransformHandle { MOVE, ROTATE, TL, T, TR, R, BR, B, BL, L }

/** Lokale Koordinaten (vor der Transformation) der Anfasser. */
private fun handleLocal(box: TransformBox, h: TransformHandle): Pair<Double, Double> = when (h) {
    TransformHandle.TL -> 0.0 to 0.0
    TransformHandle.T -> box.w / 2 to 0.0
    TransformHandle.TR -> box.w to 0.0
    TransformHandle.R -> box.w to box.h / 2
    TransformHandle.BR -> box.w to box.h
    TransformHandle.B -> box.w / 2 to box.h
    TransformHandle.BL -> 0.0 to box.h
    TransformHandle.L -> 0.0 to box.h / 2
    else -> box.w / 2 to box.h / 2
}

/** Gegenüberliegender (fester) Punkt beim Skalieren. */
private fun opposite(h: TransformHandle) = when (h) {
    TransformHandle.TL -> TransformHandle.BR; TransformHandle.T -> TransformHandle.B; TransformHandle.TR -> TransformHandle.BL
    TransformHandle.R -> TransformHandle.L; TransformHandle.BR -> TransformHandle.TL; TransformHandle.B -> TransformHandle.T
    TransformHandle.BL -> TransformHandle.TR; TransformHandle.L -> TransformHandle.R; else -> h
}

/** Lage eines Anfassers im Dokument – aus den vier Ecken (funktioniert auch verzerrt). */
private fun quadPoint(q: DoubleArray, h: TransformHandle): Pair<Double, Double> {
    fun c(i: Int) = q[i * 2] to q[i * 2 + 1]
    fun mid(i: Int, j: Int) = (q[i * 2] + q[j * 2]) / 2 to (q[i * 2 + 1] + q[j * 2 + 1]) / 2
    return when (h) {
        TransformHandle.TL -> c(0); TransformHandle.TR -> c(1); TransformHandle.BR -> c(2); TransformHandle.BL -> c(3)
        TransformHandle.T -> mid(0, 1); TransformHandle.R -> mid(1, 2); TransformHandle.B -> mid(2, 3); TransformHandle.L -> mid(3, 0)
        else -> (q[0] + q[2] + q[4] + q[6]) / 4 to (q[1] + q[3] + q[5] + q[7]) / 4
    }
}

private fun screenOfHandle(box: TransformBox, vp: ViewportState, h: TransformHandle): Offset =
    quadPoint(box.corners(), h).let { (x, y) -> vp.toScreen(Offset(x.toFloat(), y.toFloat())) }

/** Drehanfasser: oberhalb der oberen Kantenmitte (in Bildschirmabstand). */
private fun rotateHandleScreen(box: TransformBox, vp: ViewportState, density: Float): Offset {
    val top = screenOfHandle(box, vp, TransformHandle.T)
    val center = screenOfHandle(box, vp, TransformHandle.MOVE)
    val dir = top - center
    val len = dir.getDistance().coerceAtLeast(1f)
    return top + dir / len * (36f * density)
}

fun hitHandle(box: TransformBox, vp: ViewportState, p: Offset, density: Float): TransformHandle {
    val r = 22f * density
    if ((rotateHandleScreen(box, vp, density) - p).getDistance() < r) return TransformHandle.ROTATE
    for (h in listOf(TransformHandle.TL, TransformHandle.TR, TransformHandle.BR, TransformHandle.BL, TransformHandle.T, TransformHandle.R, TransformHandle.B, TransformHandle.L)) {
        if ((screenOfHandle(box, vp, h) - p).getDistance() < r) return h
    }
    return TransformHandle.MOVE
}

/**
 * Neue Ecken beim Neigen, Verzerren und Perspektive (Photoshop: Bearbeiten → Transformieren) und
 * beim freien Transformieren bereits verzerrter Objekte. [q0] = Ecken beim Start des Ziehens.
 */
fun dragQuad(box: TransformBox, mode: de.photographics.app.TransformMode, h: TransformHandle, q0: DoubleArray, start: Offset, now: Offset, free: Boolean): DoubleArray {
    val dx = (now.x - start.x).toDouble(); val dy = (now.y - start.y).toDouble()
    val q = q0.copyOf()
    fun move(i: Int, mx: Double, my: Double) { q[i * 2] += mx; q[i * 2 + 1] += my }
    val cornerIndex = mapOf(TransformHandle.TL to 0, TransformHandle.TR to 1, TransformHandle.BR to 2, TransformHandle.BL to 3)
    val edge = mapOf(TransformHandle.T to (0 to 1), TransformHandle.R to (1 to 2), TransformHandle.B to (2 to 3), TransformHandle.L to (3 to 0))
    if (h == TransformHandle.MOVE) { for (i in 0..3) move(i, dx, dy); return q }
    if (mode == de.photographics.app.TransformMode.FREE || h == TransformHandle.ROTATE) {
        // Über das Parallelogramm aus oben links, oben rechts, unten links: Matrix-Bewegung auf alle Ecken übertragen
        val a0 = Affine((q0[2] - q0[0]) / box.w, (q0[3] - q0[1]) / box.w, (q0[6] - q0[0]) / box.h, (q0[7] - q0[1]) / box.h, q0[0], q0[1])
        val a1 = dragTransform(box.copy(affine = a0, quad = null), h, start, now, free)
        val delta = a0.inverse().then(a1)
        return DoubleArray(8) { i -> if (i % 2 == 0) delta.mapX(q0[i], q0[i + 1]) else delta.mapY(q0[i - 1], q0[i]) }
    }
    val ci = cornerIndex[h]
    when (mode) {
        de.photographics.app.TransformMode.DISTORT -> {
            if (ci != null) move(ci, dx, dy) else edge[h]?.let { (i, j) -> move(i, dx, dy); move(j, dx, dy) }
        }
        de.photographics.app.TransformMode.SKEW -> {
            val e = edge[h]
            if (e != null) {
                // Kante gleitet entlang ihrer Richtung
                val (i, j) = e
                val ex = q0[j * 2] - q0[i * 2]; val ey = q0[j * 2 + 1] - q0[i * 2 + 1]
                val len = kotlin.math.hypot(ex, ey).coerceAtLeast(1e-9)
                val t = (dx * ex + dy * ey) / len
                move(i, ex / len * t, ey / len * t); move(j, ex / len * t, ey / len * t)
            } else if (ci != null) move(ci, dx, dy)
        }
        de.photographics.app.TransformMode.PERSPECTIVE -> {
            if (ci != null) {
                // Nachbarecken: waagerecht (0↔1, 3↔2) und senkrecht (0↔3, 1↔2)
                val hn = intArrayOf(1, 0, 3, 2)[ci]; val vn = intArrayOf(3, 2, 1, 0)[ci]
                val hx = q0[hn * 2] - q0[ci * 2]; val hy = q0[hn * 2 + 1] - q0[ci * 2 + 1]
                val vx = q0[vn * 2] - q0[ci * 2]; val vy = q0[vn * 2 + 1] - q0[ci * 2 + 1]
                val hl = kotlin.math.hypot(hx, hy).coerceAtLeast(1e-9); val vl = kotlin.math.hypot(vx, vy).coerceAtLeast(1e-9)
                val ph = (dx * hx + dy * hy) / hl; val pv = (dx * vx + dy * vy) / vl
                if (kotlin.math.abs(ph) >= kotlin.math.abs(pv)) { move(ci, hx / hl * ph, hy / hl * ph); move(hn, -hx / hl * ph, -hy / hl * ph) }
                else { move(ci, vx / vl * pv, vy / vl * pv); move(vn, -vx / vl * pv, -vy / vl * pv) }
            } else edge[h]?.let { (i, j) ->
                val ex = q0[j * 2] - q0[i * 2]; val ey = q0[j * 2 + 1] - q0[i * 2 + 1]
                val len = kotlin.math.hypot(ex, ey).coerceAtLeast(1e-9); val t = (dx * ex + dy * ey) / len
                move(i, ex / len * t, ey / len * t); move(j, ex / len * t, ey / len * t)
            }
        }
        else -> {}
    }
    return q
}

/** Neue Transformation aus Anfasser und Zeigerbewegung (Dokumentkoordinaten). */
fun dragTransform(box: TransformBox, h: TransformHandle, start: Offset, now: Offset, free: Boolean): Affine {
    val t0 = box.affine
    return when (h) {
        TransformHandle.MOVE -> t0.then(Affine.translate((now.x - start.x).toDouble(), (now.y - start.y).toDouble()))
        TransformHandle.ROTATE -> {
            val cx = t0.mapX(box.w / 2, box.h / 2); val cy = t0.mapY(box.w / 2, box.h / 2)
            val a0 = kotlin.math.atan2(start.y - cy, start.x - cx); val a1 = kotlin.math.atan2(now.y - cy, now.x - cx)
            var deg = Math.toDegrees(a1 - a0)
            if (free) deg = Math.round(deg / 15.0) * 15.0 // Shift: in 15°-Schritten
            t0.then(Affine.around(cx, cy, Affine.rotate(deg)))
        }
        else -> {
            val inv = t0.inverse()
            val lx = inv.mapX(now.x.toDouble(), now.y.toDouble()); val ly = inv.mapY(now.x.toDouble(), now.y.toDouble())
            val (hx, hy) = handleLocal(box, h); val (ox, oy) = handleLocal(box, opposite(h))
            var sx = if (abs(hx - ox) > 1e-6) (lx - ox) / (hx - ox) else 1.0
            var sy = if (abs(hy - oy) > 1e-6) (ly - oy) / (hy - oy) else 1.0
            val corner = h in setOf(TransformHandle.TL, TransformHandle.TR, TransformHandle.BR, TransformHandle.BL)
            if (corner && !free) { val k = (sx + sy) / 2; sx = k; sy = k } // Ecken: proportional (Shift: frei)
            if (h == TransformHandle.T || h == TransformHandle.B) sx = 1.0
            if (h == TransformHandle.L || h == TransformHandle.R) sy = 1.0
            if (abs(sx) < 0.01) sx = 0.01 * kotlin.math.sign(sx).let { if (it == 0.0) 1.0 else it }
            if (abs(sy) < 0.01) sy = 0.01 * kotlin.math.sign(sy).let { if (it == 0.0) 1.0 else it }
            Affine.around(ox, oy, Affine.scale(sx, sy)).then(t0)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTransformBox(box: TransformBox, vp: ViewportState, density: Float) {
    val corners = listOf(TransformHandle.TL, TransformHandle.TR, TransformHandle.BR, TransformHandle.BL).map { screenOfHandle(box, vp, it) }
    val path = Path().apply { moveTo(corners[0].x, corners[0].y); for (c in corners.drop(1)) lineTo(c.x, c.y); close() }
    drawPath(path, Color.Black, style = Stroke(3f))
    drawPath(path, Color(0xFF4FC3F7), style = Stroke(1.5f))
    val rot = rotateHandleScreen(box, vp, density)
    val top = screenOfHandle(box, vp, TransformHandle.T)
    drawLine(Color(0xFF4FC3F7), top, rot, 1.5f)
    drawCircle(Color.White, 7f * density, rot)
    drawCircle(Color(0xFF4FC3F7), 7f * density, rot, style = Stroke(2f))
    for (h in listOf(TransformHandle.TL, TransformHandle.T, TransformHandle.TR, TransformHandle.R, TransformHandle.BR, TransformHandle.B, TransformHandle.BL, TransformHandle.L)) {
        val c = screenOfHandle(box, vp, h)
        val half = 6f * density
        drawRect(Color.White, Offset(c.x - half, c.y - half), androidx.compose.ui.geometry.Size(half * 2, half * 2))
        drawRect(Color(0xFF1E88E5), Offset(c.x - half, c.y - half), androidx.compose.ui.geometry.Size(half * 2, half * 2), style = Stroke(2f))
    }
}

/**
 * Lineal am Rand der Arbeitsfläche: Teilung in Dokumentpixeln mit Schrittweite 1/2/5 × 10ⁿ,
 * sodass die beschrifteten Striche mindestens ~60 dp auseinanderliegen.
 */
@Composable
private fun Ruler(vp: ViewportState, vertical: Boolean, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = fg, fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp))
    Canvas(modifier.background(bg)) {
        val minPx = 60.dp.toPx()
        // Kleinste Schrittweite der Folge 1, 2, 5, 10, 20, 50 … mit genug Abstand
        val raw = minPx / vp.scale
        val bases = doubleArrayOf(1.0, 2.0, 5.0)
        var i = 0; var exp = 1.0
        while (bases[i] * exp < raw) { i++; if (i == bases.size) { i = 0; exp *= 10 } }
        val step = bases[i] * exp
        val sub = step / 5
        val origin = if (vertical) vp.offset.y - 20.dp.toPx() else vp.offset.x
        val len = if (vertical) size.height else size.width
        val first = kotlin.math.floor(-origin / vp.scale / sub).toLong()
        val last = kotlin.math.ceil((len - origin) / vp.scale / sub).toLong()
        for (k in first..last) {
            val v = k * sub
            val at = origin + (v * vp.scale).toFloat()
            val major = k % 5 == 0L
            val tick = if (major) size.minDimension * 0.6f else size.minDimension * 0.25f
            if (vertical) drawLine(fg, Offset(size.width - tick, at), Offset(size.width, at), 1f)
            else drawLine(fg, Offset(at, size.height - tick), Offset(at, size.height), 1f)
            if (major) {
                val text = measurer.measure(v.roundToInt().toString(), labelStyle)
                if (vertical) drawText(text, topLeft = Offset(1f, at + 1f)) else drawText(text, topLeft = Offset(at + 2f, 0f))
            }
        }
        if (vertical) drawLine(fg.copy(alpha = 0.4f), Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
        else drawLine(fg.copy(alpha = 0.4f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
    }
}

/** Mischmodus der GPU-Vorschau (nur Modi, die Android wie photographics rechnet, siehe EditorViewModel.gpuBlendOk). */
@androidx.annotation.RequiresApi(29)
private fun gpuBlend(m: de.photographics.core.render.BlendMode): android.graphics.BlendMode = when (m) {
    de.photographics.core.render.BlendMode.MULTIPLY -> android.graphics.BlendMode.MULTIPLY
    de.photographics.core.render.BlendMode.SCREEN -> android.graphics.BlendMode.SCREEN
    de.photographics.core.render.BlendMode.DARKEN -> android.graphics.BlendMode.DARKEN
    de.photographics.core.render.BlendMode.LIGHTEN -> android.graphics.BlendMode.LIGHTEN
    de.photographics.core.render.BlendMode.DIFFERENCE -> android.graphics.BlendMode.DIFFERENCE
    de.photographics.core.render.BlendMode.EXCLUSION -> android.graphics.BlendMode.EXCLUSION
    de.photographics.core.render.BlendMode.OVERLAY -> android.graphics.BlendMode.OVERLAY
    de.photographics.core.render.BlendMode.HARD_LIGHT -> android.graphics.BlendMode.HARD_LIGHT
    else -> android.graphics.BlendMode.SRC_OVER
}
