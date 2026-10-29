package de.photographics.app

import de.photographics.core.tr
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.photographics.app.ai.AiModel
import de.photographics.app.ai.AiModelManager
import de.photographics.app.ai.AiPurpose
import de.photographics.app.ai.OnnxInpainter
import de.photographics.app.ai.OnnxUpscaler
import de.photographics.core.adjust.Adjustment
import de.photographics.core.adjust.AdjustmentType
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.RasterLayer
import de.photographics.core.doc.SmartLayer
import de.photographics.core.doc.TextLayer
import de.photographics.core.doc.TextRasterizer
import de.photographics.core.doc.TextStyle
import de.photographics.core.image.Affine
import de.photographics.core.image.Resample
import de.photographics.core.image.ResampleMethod
import de.photographics.core.tools.SmudgeTool
import de.photographics.core.render.StrokeRenderCache
import kotlinx.coroutines.withContext
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer
import de.photographics.core.doc.LayerKind
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.filters.ImageFilter
import de.photographics.core.history.TileTransaction
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdFormatException
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import de.photographics.core.selection.Selection
import de.photographics.core.selection.SelectionOp
import de.photographics.core.selection.SelectionShape
import de.photographics.core.tools.BlurTool
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.BrushTool
import de.photographics.core.tools.CloneTool
import de.photographics.core.tools.DodgeBurnTool
import de.photographics.core.tools.EraserTool
import de.photographics.core.tools.HealTool
import de.photographics.core.tools.PaintTarget
import de.photographics.core.tools.PaintTool
import de.photographics.core.tools.SharpenTool
import de.photographics.core.tools.SpongeTool
import de.photographics.core.tools.SpotHealTool
import de.photographics.core.tools.Stroke
import de.photographics.core.tools.ToneRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import de.photographics.core.color.SoftProof
import de.photographics.core.color.IccProfiles
import de.photographics.core.image.SlabTileMemory
import de.photographics.core.image.TileMemory
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

enum class Tool(val label: String) {
    MOVE(tr("Verschieben")),
    SELECT(tr("Auswahl")),
    CROP(tr("Freistellen")),
    BRUSH(tr("Pinsel")),
    ERASER(tr("Radiergummi")),
    CLONE(tr("Kopierstempel")),
    HEAL(tr("Reparatur-Pinsel")),
    SPOT_HEAL(tr("Bereichsreparatur")),
    BLUR(tr("Weichzeichner")),
    SHARPEN(tr("Scharfzeichner")),
    DODGE(tr("Abwedler")),
    BURN(tr("Nachbelichter")),
    SPONGE(tr("Schwamm")),
    EYEDROPPER(tr("Pipette")),
    TRANSFORM(tr("Transformieren")),
    TEXT(tr("Text")),
    SMUDGE(tr("Wischfinger")),
    PATCH(tr("Ausbessern")),
    RED_EYE(tr("Rote Augen")),
    AI_REMOVE(tr("KI-Entfernen")),
    LIQUIFY(tr("Verflüssigen")),
    GRADIENT(tr("Verlauf"));

    val isPaintTool get() = this in setOf(BRUSH, ERASER, CLONE, HEAL, SPOT_HEAL, BLUR, SHARPEN, DODGE, BURN, SPONGE, SMUDGE, AI_REMOVE, LIQUIFY)
}

enum class LayerType { PIXEL, GROUP, ADJUSTMENT, TEXT, SMART }

data class LayerItem(
    val id: Int,
    val name: String,
    val depth: Int,
    val isGroup: Boolean,
    val expanded: Boolean,
    val visible: Boolean,
    val opacity: Float,
    val fillOpacity: Float,
    val blendMode: BlendMode,
    val clipped: Boolean,
    val lockTransparency: Boolean,
    val hasMask: Boolean,
    val maskEnabled: Boolean,
    val kind: LayerKind,
    val thumb: ImageBitmap?,
    val maskThumb: ImageBitmap?,
    val type: LayerType = LayerType.PIXEL,
    /** Zusatzinfo (z. B. Art der Einstellung). */
    val detail: String? = null,
    /** Maske mit der Ebene verbunden (Kettensymbol). */
    val maskLinked: Boolean = true,
    /** Basis einer Schnittmaske (die Ebene darüber ist beschnitten) – Name wird unterstrichen. */
    val clipBase: Boolean = false,
)

/** Seitenverhältnis beim Freistellen; [w]/[h] = Breite zu Höhe (Querformat), null = frei. */
data class CropAspect(val label: String, val w: Double, val h: Double)

/** Überlagerung im Freistellungsrahmen. */
enum class CropOverlay(val label: String) { THIRDS(tr("Drittelregel")), GOLDEN(tr("Goldener Schnitt")), GRID(tr("Raster")), DIAGONAL(tr("Diagonalen")), NONE(tr("Keine")) }

/** Zielgröße beim Freistellen (Photoshop: „B × H × Auflösung“). */
data class CropTarget(val width: Int, val height: Int, val dpi: Float)

/** Laufende Textbearbeitung. */
data class TextEdit(val layerId: Int, val style: TextStyle)

/**
 * Laufendes Transformieren: Grundrechteck (w×h vor der Transformation) und aktuelle Matrix; bei
 * Verzerren/Perspektive zusätzlich die vier Ecken im Dokument ([quad]: oben links, oben rechts,
 * unten rechts, unten links).
 */
data class TransformBox(val layerId: Int, val w: Double, val h: Double, val affine: Affine, val quad: DoubleArray? = null) {
    /** Ecken im Dokument (aus [quad] oder der Matrix). */
    fun corners(): DoubleArray = quad?.copyOf() ?: de.photographics.core.image.Homography.of(affine).corners(w, h)
}

/** Modus des Frei-Transformierens (Photoshop: Bearbeiten → Transformieren). */
enum class TransformMode(val label: String) { FREE(tr("Frei")), SKEW(tr("Neigen")), DISTORT(tr("Verzerren")), PERSPECTIVE(tr("Perspektive")) }

/** Offene Einstellungsebene im Eigenschaften-Panel. */
/** [histogram]: Tonwerte unter der Ebene (nur für Kurven), siehe [de.photographics.core.adjust.Histogram]. */
data class AdjustEdit(val layerId: Int, val adjustment: Adjustment, val histogram: IntArray? = null)

data class EditorUiState(
    val hasDocument: Boolean = false,
    val docWidth: Int = 0,
    val docHeight: Int = 0,
    val fileName: String = tr("Unbenannt"),
    val layers: List<LayerItem> = emptyList(),
    val activeLayerId: Int? = null,
    val tool: Tool = Tool.BRUSH,
    val brush: BrushSettings = BrushSettings(),
    val color: Int = 0xFF000000.toInt(),
    /** Hintergrundfarbe (Photoshop: zweites Farbfeld). */
    val bgColor: Int = 0xFFFFFFFF.toInt(),
    /** Pipette nimmt in die Hintergrundfarbe auf (ohne Tastatur: Schalter in den Werkzeugoptionen). */
    val eyedropperToBg: Boolean = false,
    val paintTarget: PaintTarget = PaintTarget.PIXELS,
    val selectionShape: SelectionShape = SelectionShape.RECT,
    val selectionOp: SelectionOp = SelectionOp.REPLACE,
    val featherRadius: Float = 0f,
    /** Zauberstab: Toleranz je Kanal (0..255) und ob nur zusammenhängende Pixel gewählt werden. */
    val wandTolerance: Int = 32,
    /** Verschieben: angetippte Ebene automatisch auswählen (Photoshop „Automatisch auswählen“). */
    val autoSelectLayer: Boolean = false,
    val wandContiguous: Boolean = true,
    val gradientType: de.photographics.core.editor.GradientType = de.photographics.core.editor.GradientType.LINEAR,
    /** Verlauf Vordergrund → transparent statt Vordergrund → Hintergrund. */
    val gradientToTransparent: Boolean = false,
    val gradientOpacity: Float = 1f,
    val cloneSource: Pair<Float, Float>? = null,
    val settingCloneSource: Boolean = false,
    val sampleAllLayers: Boolean = false,
    val toneRange: ToneRange = ToneRange.MIDTONES,
    val toolStrength: Float = 0.5f,
    val spongeSaturate: Boolean = false,
    val liquifyMode: de.photographics.core.tools.LiquifyMode = de.photographics.core.tools.LiquifyMode.PUSH,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
    val selectionOutlines: List<FloatArray> = emptyList(),
    val hasSelection: Boolean = false,
    val cropRect: IntRect? = null,
    /** Perspektivisch freistellen: vier Ecken (oben links, oben rechts, unten rechts, unten links) oder null. */
    val cropQuad: List<Float>? = null,
    val transformMode: TransformMode = TransformMode.FREE,
    /** Hilfslinien des Dokuments und gerade angezeigte Ausrichtungslinien (intelligente Hilfslinien). */
    val guides: List<de.photographics.core.doc.Guide> = emptyList(),
    val smartLines: List<de.photographics.core.editor.Snap.Line> = emptyList(),
    /** Seitenverhältnis (null = frei), Hochformat, Überlagerung, Drehung des Bildes (Grad, im UZS). */
    val cropAspect: CropAspect? = null,
    val cropPortrait: Boolean = false,
    val cropOverlay: CropOverlay = CropOverlay.THIRDS,
    val cropAngle: Float = 0f,
    val cropDeleteCropped: Boolean = true,
    val cropTarget: CropTarget? = null,
    /** Gerade-ausrichten-Linie wird gezogen. */
    val cropStraighten: Boolean = false,
    val bitmap: TiledBitmap? = null,
    /** Farbproof (Strg+Y) und Farbumfang-Warnung (Strg+Shift+Y) – nur Anzeige. */
    val proofOn: Boolean = false,
    val gamutWarning: Boolean = false,
    /** Schnelle Vorschau in reduzierter Auflösung während Live-Änderungen (sonst null). */
    val previewBitmap: TiledBitmap? = null,
    /** Lage der Vorschau im Dokument (die Bitmap wird auf diese Fläche gestreckt). */
    val previewRect: IntRect = IntRect.EMPTY,
    /** Sichtbarer Ausschnitt in feinerer Auflösung als [bitmap] (Hineinzoomen in große Bilder), sonst null. */
    val detail: DetailView? = null,
    /** Pixel des Dokuments pro Pixel der Anzeige-Bitmap (>1 bei sehr großen Bildern). */
    val displayScale: Int = 1,
    val filterActive: String? = null,
    /** Nur der Stift malt; Finger zoomen/verschieben (Handballen-Schutz). */
    val stylusOnly: Boolean = false,
    val stylusSeen: Boolean = false,
    /** Fortschritt lang laufender Aktionen (KI) 0..1, sonst null. */
    val progress: Float? = null,
    val progressLabel: String? = null,
    val textEdit: TextEdit? = null,
    val transform: TransformBox? = null,
    val adjustEdit: AdjustEdit? = null,
    val aiInpaintModel: AiModel = AiModel.MIGAN,
    val aiUpscaleModel: AiModel = AiModel.ESRGAN_GENERAL,
    /** Wird ein KI-Modell benötigt, aber nicht installiert, öffnet die Oberfläche die Modellverwaltung. */
    val requestAiModels: AiPurpose? = null,
    val showStart: Boolean = true,
    val recent: List<RecentFiles.Entry> = emptyList(),
    /** Farbprofil des Dokuments (Anzeige im Titel). */
    val docProfileName: String = "sRGB IEC61966-2.1",
    val docProfileIsSrgb: Boolean = true,
    /** Name der Proof-Bedingung bzw. des Proof-Profils (Anzeige im Titel). */
    val proofLabel: String = "",
    /** Eigene ICC-Profile (Drucker, Druckerei, Kameras). */
    val iccLibrary: List<IccEntry> = emptyList(),
    /** Eigene Farbtabellen (LUT-Dateien) für die Einstellungsebene „Farbtabelle“. */
    val lutLibrary: List<de.photographics.core.adjust.ColorLookupAdj> = emptyList(),
    /** Farbfeldbibliotheken: eingebaute und eigene (eigene zuerst). */
    val palettes: List<de.photographics.core.color.Palette> = de.photographics.core.color.BuiltinPalettes.all,
    /** Name der aktiven Bibliothek. */
    val activePalette: String = "Standard",
    /** Zuletzt verwendete Farben (neueste zuerst). */
    val recentColors: List<Int> = emptyList(),
    /** Pinselvorgaben: eingebaute und eigene. */
    val brushPresets: List<de.photographics.core.tools.BrushPreset> = de.photographics.core.tools.BrushPresets.builtin,
    val docDpi: Float = 72f,
)

/** Bis zu dieser Fläche wird auch live in voller Auflösung gerendert. */
/**
 * Ausschnitt [rect] (Dokument) in der Auflösung 1/[step], über der Anzeige-Bitmap gezeichnet; fertig
 * gerechnet ist erst [shown] (der sichtbare Teil kommt zuerst, der Rand danach).
 */
class DetailView(val bitmap: TiledBitmap, val rect: IntRect, val step: Int, val shown: IntRect = rect)

/**
 * GPU-Vorschau beim Verschieben, Transformieren und Ändern der Deckkraft einer Ebene („Sandwich“):
 * [below] (alles darunter) und [above] (alles darüber) für den sichtbaren Bereich [region] bei
 * [step] einmal gerechnet; die Ebene selbst als eigene Bitmap, die die Leinwand bei jedem Bild mit
 * [matrix] (Bitmap → Dokument), [alpha] und [blend] zeichnet – ohne Rechenarbeit auf dem Prozessor.
 */
class GpuLive(
    val layerId: Int,
    val region: IntRect,
    val step: Int,
    val below: TiledBitmap?,
    val layer: TiledBitmap,
    val above: TiledBitmap?,
    val blend: de.photographics.core.render.BlendMode,
) {
    /** 3×3 zeilenweise wie [android.graphics.Matrix.setValues]. */
    @Volatile var matrix: FloatArray = FloatArray(9)
    @Volatile var alpha: Float = 1f
}

/** Bis zu dieser Größe (Pixel in Anzeigeauflösung) werden Live-Änderungen direkt gezeichnet. */
private const val LIVE_DIRECT_PIXELS = 1_200_000L
/** Zielgröße der schnellen Vorschau. */
private const val PROXY_PIXELS = 1_500_000.0
private const val SETTLE_MS = 300L
/** Pixel pro Render-Streifen der Anzeige. */
private const val RENDER_BAND_PIXELS = 2_000_000

/** Eintrag der ICC-Profilbibliothek. */
data class IccEntry(val fileName: String, val description: String, val colorSpace: String, val deviceClass: String) {
    val isRgb get() = colorSpace == "RGB"
    /** Ausgabeprofil (Drucker) – sinnvoll für den Proof. */
    val isOutput get() = deviceClass == "prtr" || colorSpace == "CMYK"
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    /** Alle Zugriffe auf Dokument und Editor laufen über diesen einen Thread. */
    private val engine = Executors.newSingleThreadExecutor { r -> Thread(r, "photographics-engine") }.asCoroutineDispatcher()

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var editor: Editor? = null
    private var bitmap: TiledBitmap? = null
    /**
     * Zählt fertige Render-Schritte. Bewusst nicht im [EditorUiState]: nur die Zeichenfunktion der
     * Leinwand liest ihn, ein Render-Schritt setzt also nicht die ganze Oberfläche neu zusammen.
     */
    private val renderTickState = androidx.compose.runtime.mutableLongStateOf(0L)
    val renderTick: androidx.compose.runtime.LongState get() = renderTickState
    /** Neues Bild anfordern – vorher alles Gerechnete an die Anzeige übergeben ([DisplayWrites]). */
    private fun bumpRender() {
        DisplayWrites.flush()
        // GPU-Vorschau erst ausblenden, wenn die fertige Anzeige übergeben ist (kein Aufblitzen des alten Stands)
        if (gpuDropPending) { gpuDropPending = false; gpuLiveState.value = null }
        renderTickState.longValue++
    }

    private val gpuLiveState = androidx.compose.runtime.mutableStateOf<GpuLive?>(null)
    /** Laufende GPU-Vorschau (nur die Leinwand liest sie beim Zeichnen). */
    val gpuLive: androidx.compose.runtime.State<GpuLive?> get() = gpuLiveState
    private var gpuDropPending = false
    private fun dropGpu() { if (gpuLiveState.value != null) gpuDropPending = true }

    private var displayScale = 1
    /** Detailansicht: aktueller Ausschnitt, sichtbarer Bereich (Dokument) und Zoom der Leinwand. */
    private var detail: DetailView? = null
    private var detailDirty = IntRect.EMPTY
    private var viewVisible = IntRect.EMPTY
    private var viewScale = 0f
    private var detailJob: Job? = null
    private val thumbs = HashMap<Int, ImageBitmap>()
    private val maskThumbs = HashMap<Int, ImageBitmap>()

    private var stroke: Stroke? = null
    /** Schnelles Neuzeichnen während eines Strichs (nur Ebenen darunter zwischengespeichert). */
    private var strokeCache: StrokeRenderCache? = null
    /** Gesammelte Eingabepunkte, die der Engine-Thread gebündelt abarbeitet. */
    private val pendingPoints = ArrayList<FloatArray>()
    private var pointsQueued = false
    private var cloneOffset: Pair<Int, Int>? = null
    private var moveLayer: RasterLayer? = null
    private var moveBounds = IntRect.EMPTY
    private var propEditLayer: Layer? = null
    private var propEditBefore: Layer.Props? = null
    private var filterTx: TileTransaction? = null
    private var filterRegion = IntRect.EMPTY
    private var filterJob: Job? = null

    private var pendingDirty = IntRect.EMPTY
    private var renderQueued = false
    /** Ebene der gesammelten Live-Änderungen (null = unbekannt oder mehrere). */
    private var pendingLiveLayer: Layer? = null

    val ai = AiModelManager(app)
    private val settingsStore = SettingsStore(app)
    val settings: StateFlow<AppSettings> = settingsStore.settings
    private val recentFiles = RecentFiles(app)
    private var transformBefore: Affine? = null
    private var transformBeforeGeom: Editor.Geometry? = null
    private var adjustBefore: Adjustment? = null
    private var textBefore: TextStyle? = null

    /**
     * Speicherwarnungen des Systems: bevor Android die App im Hintergrund beendet oder der RAM knapp
     * wird, den Verlauf auf den Gerätespeicher auslagern und den Kachel-Cache leeren.
     */
    private val memoryCallbacks = object : android.content.ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) releaseMemory()
        }
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() { releaseMemory() }
    }

    /** Speicher-Selbsttest auf dem Gerät; Ergebnis im Logcat (Tag PG-Selbsttest) und als Meldung. */
    fun runSelfTest() = onEngine {
        val tag = "PG-Selbsttest"
        val mem = TileMemory.current
        android.util.Log.i(tag, "Start: Java-Heap max. ${Runtime.getRuntime().maxMemory() shr 20} MB, Kachelbudget ${mem.limitBytes shr 20} MB")
        busy(true)
        val r = try {
            de.photographics.core.diagnostics.MemorySelfTest.run(java.io.File(getApplication<Application>().cacheDir, "selbsttest"), full = false) {
                android.util.Log.i(tag, it)
            }
        } finally { busy(false) }
        val pss = android.os.Debug.getPss() shr 10
        val summary = tr("Heap max. {0} MB, Kacheln max. {1} MB, PSS {2} MB, {3} s", r.maxHeapMb, r.maxTileMb, pss, r.totalMs / 1000)
        if (r.ok) {
            android.util.Log.i(tag, "ERGEBNIS: OK – $summary")
            message(tr("Selbsttest bestanden ({0})", summary))
        } else {
            android.util.Log.e(tag, "ERGEBNIS: FEHLER bei „${r.failedStep}“: ${r.error} – $summary", r.error)
            message(tr("Selbsttest fehlgeschlagen bei „{0}“", r.failedStep))
        }
    }

    /**
     * Bedien-Benchmark auf dem Gerät (Logcat-Tag PG-Bench): typische Interaktionen auf einem großen
     * Dokument mit den echten Render-Wegen – Gesamtansicht, Verschieben, Deckkraft-Regler,
     * Einstellungsebene, Transformieren, Pinselstrich – je Einzelbild gemessen.
     */
    fun runBenchmark(megapixel: Int) = onEngine {
        val tag = "PG-Bench"
        fun log(s: String) = android.util.Log.i(tag, s)
        val w = kotlin.math.sqrt(megapixel * 1_000_000 * 1.5).toInt(); val h = w * 2 / 3
        log("Start: ${w}×$h (${w.toLong() * h / 1_000_000} MP), ${Runtime.getRuntime().availableProcessors()} Kerne")
        val doc = de.photographics.core.doc.Document(w, h)
        fun photo(seed: Int) = de.photographics.core.image.TileGrid(w, h).also { g ->
            var y = 0
            while (y < h) {
                val r = IntRect(0, y, w, minOf(h, y + 256))
                g.writeRect(r, IntArray(r.width * r.height) { i -> val x = i % w; val yy = r.top + i / w
                    (0xFF shl 24) or ((((x * 7) xor (yy * 3)) + seed) and 0xFF shl 16) or (((x + yy) / 8 + seed) and 0xFF shl 8) or ((x * yy / 97 + seed) and 0xFF) })
                y = r.bottom
            }
        }
        doc.root.add(PixelLayer(tr("Hintergrund"), photo(1)))
        doc.root.add(PixelLayer(tr("Retusche"), photo(9)).also { it.blendMode = de.photographics.core.render.BlendMode.SOFT_LIGHT; it.opacity = 0.6f })
        val curves = AdjustmentLayer(tr("Kurven"), de.photographics.core.adjust.AdjustmentType.CURVES.createDefault())
        doc.root.add(curves)
        val top = PixelLayer(tr("Motiv"), de.photographics.core.image.TileGrid(w, h).also { g ->
            val r = IntRect(w / 3, h / 3, w / 3 + w / 4, h / 3 + h / 4); g.writeRect(r, IntArray(r.width * r.height) { 0xFFCC5522.toInt() })
        })
        doc.root.add(top); doc.activeLayer = top
        setDocument(doc, tr("Benchmark"), emptyList())
        // In den Editor wechseln: die Leinwand soll sichtbar sein (Bildzeiten), nicht der Startbildschirm
        _state.update { it.copy(showStart = false) }
        val ed = editor ?: return@onEngine
        fun frames(label: String, n: Int, f: (Int) -> Unit) {
            val t = LongArray(n)
            for (i in 0 until n) { val t0 = System.nanoTime(); f(i); t[i] = (System.nanoTime() - t0) / 1_000_000 }
            log("%-34s Ø %4d ms  max %4d ms  (erstes %d ms)".format(label, t.drop(1).average().toInt(), t.maxOrNull() ?: 0, t[0]))
        }
        frames(tr("Gesamtansicht neu zeichnen"), 3) { renderNow(null) }
        run {
            val sc = displayScale; val bw = (w + sc - 1) / sc; val bh = (h + sc - 1) / sc
            val px = IntArray(bw * 64)
            frames(tr("  davon nur rechnen (1 Kern)"), 2) { var y = 0; while (y < bh) { Compositor.renderArgbSampled(doc, IntRect(0, y, bw, minOf(bh, y + 64)), sc); y += 64 } }
            frames(tr("  davon nur rechnen (alle Kerne)"), 2) {
                de.photographics.core.image.Parallel.bands((bh + 63) / 64, bw * 64L * 40, compute = { i -> Compositor.renderArgbSampled(doc, IntRect(0, i * 64, bw, minOf(bh, i * 64 + 64)), sc) }, consume = { _, _ -> })
            }
            val bmp = bitmap
            if (bmp != null) frames(tr("  davon nur Bitmap schreiben"), 2) { var y = 0; while (y < bh) { val n = minOf(64, bh - y); bmp.setPixels(px, 0, bw, 0, y, bw, n); y += 64 } }
        }
        // Hineinzoomen auf 100 % (Bildschirm 2560×1600): Detailausschnitt in voller Auflösung
        viewVisible = IntRect(w / 2 - 1280, h / 2 - 800, w / 2 + 1280, h / 2 + 800); viewScale = 1f
        run {
            // Ein vom Neuzeichnen angestoßener Aufbau würde sich sonst bei jedem yield() dazwischenschieben
            detailJob?.cancel()
            val t0 = System.nanoTime(); buildDetail()
            val d = detail
            log("%-34s %5d ms  (%s)".format("Detail 100 %% aufbauen", (System.nanoTime() - t0) / 1_000_000, d?.let { "${it.bitmap.width}×${it.bitmap.height}, 1/${it.step}" } ?: "keins"))
            log("%-34s %5d ms".format("  davon bis der sichtbare Teil scharf ist", (detailShownAt - t0) / 1_000_000))
        }
        run {
            detail = null; detailJob?.cancel()
            val t0 = System.nanoTime(); buildDetail()
            log("%-34s %5d ms, sichtbar nach %d ms".format("Detail 100 %% zweites Mal", (System.nanoTime() - t0) / 1_000_000, (detailShownAt - t0) / 1_000_000))
        }
        // Verschieben (wie moveTo): kleine Ebene über das Bild ziehen
        val bounds = top.pixels.contentBounds()
        frames(tr("Verschieben (je Bild)"), 20) { i ->
            val old = bounds.offset(top.previewDx, top.previewDy)
            top.previewDx = i * 15; top.previewDy = i * 8
            renderLive(old.union(bounds.offset(top.previewDx, top.previewDy)), top)
        }
        top.previewDx = 0; top.previewDy = 0; clearPreview(); renderNow(null)
        frames(tr("Deckkraft-Regler (je Bild)"), 20) { i -> doc.root.children[1].opacity = 0.3f + i * 0.02f; renderLive(null, doc.root.children[1]) }
        clearPreview(); renderNow(null)
        frames(tr("Einstellungsebene ziehen (je Bild)"), 20) { i ->
            val c = de.photographics.core.adjust.CurvesAdj(master = listOf(0 to 0, 128 to 128 + i * 3, 255 to 255))
            ed.previewAdjustment(curves, c); renderLive(null, curves)
        }
        clearPreview(); renderNow(null)
        val smart = ed.convertToSmartObject(top)
        if (smart != null) {
            val base = smart.transform
            frames(tr("Transformieren (je Bild)"), 20) { i ->
                val t = base.then(de.photographics.core.image.Affine.around(w / 2.0, h / 2.0, de.photographics.core.image.Affine.rotate(i * 1.5)))
                ed.previewTransform(smart, t); renderLive(doc.bounds, smart)
            }
            frames(tr("Transformieren übernehmen"), 1) { ed.commitTransform(smart, base, smart.transform); renderNow(null) }
        }
        clearPreview()
        doc.activeLayer = doc.root.children[0]
        val st = ed.beginStroke(BrushTool(0xFF2050A0.toInt()), BrushSettings(size = 200f, hardness = 0.6f), PaintTarget.PIXELS)
        if (st != null) {
            strokeCache = StrokeRenderCache.create(doc, doc.root.children[0] as PixelLayer)
            frames(tr("Pinsel bei 100 % (je Ereignis, 200 px)"), 60) { i -> renderNow(st.addPoint(w * 0.4f + i * 25f, h * 0.5f + (i % 10) * 12f, 0.8f)) }
            strokeCache = null
            frames(tr("Pinsel absetzen"), 1) { renderNow(ed.endStroke(st)) }
        }
        // Echtzeit-Phase für die Bildzeiten der Oberfläche (dumpsys gfxinfo, siehe tools/device_bench.sh)
        clearPreview(); renderNow(null)
        log("UI-PHASE: Start"); Thread.sleep(1500)
        // Phasengrenzen in CLOCK_MONOTONIC (wie die Zeitstempel von gfxinfo framestats)
        FrameStats.phase = tr("Leerlauf")
        for (i in 0 until 90) { bumpRender(); Thread.sleep(8) }
        FrameStats.phase = tr("Verschieben")
        val tb = top.pixels.contentBounds()
        for (i in 0 until 90) {
            val old = tb.offset(top.previewDx, top.previewDy)
            top.previewDx = (i % 45) * 20; top.previewDy = (i % 45) * 10
            renderLive(old.union(tb.offset(top.previewDx, top.previewDy)), top); Thread.sleep(16)
        }
        top.previewDx = 0; top.previewDy = 0; clearPreview(); renderNow(null)
        FrameStats.phase = tr("Malen")
        val st2 = ed.beginStroke(BrushTool(0xFF20A050.toInt()), BrushSettings(size = 120f, hardness = 0.6f), PaintTarget.PIXELS)
        if (st2 != null) {
            strokeCache = StrokeRenderCache.create(doc, doc.root.children[0] as PixelLayer)
            for (i in 0 until 120) { renderNow(st2.addPoint(w * 0.1f + i * 30f, h * 0.3f + (i % 20) * 15f, 0.8f)); Thread.sleep(8) }
            strokeCache = null; renderNow(ed.endStroke(st2))
        }
        FrameStats.phase = null
        Thread.sleep(500)
        val hz = getApplication<Application>().getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)?.refreshRate ?: 60f
        FrameStats.report(1000.0 / hz).forEach { log("BILD $it") }
        log("UI-PHASE: Ende")
        log("ERGEBNIS: fertig")
    }

    private fun releaseMemory() = onEngine {
        val freed = editor?.history?.spillAll() ?: 0L
        TileMemory.current.flush()
        System.gc()
        (TileMemory.current as? SlabTileMemory)?.reclaim()
        if (BuildConfig.DEBUG) android.util.Log.d("PG-Memory", "Ausgelagert ${freed shr 20} MB, Kacheln ${TileMemory.current.usedBytes shr 20} MB")
    }

    init {
        app.registerComponentCallbacks(memoryCallbacks)
        TextRasterizer.current = AndroidTextRasterizer
        _state.update { it.copy(recent = recentFiles.list()) }
        newDocument(2000, 1500, transparent = false, showEditor = false)
        loadLutLibrary()
        loadIccLibrary()
    }

    private fun onEngine(block: suspend () -> Unit) = viewModelScope.launch(engine) {
        try {
            try {
                block()
            } catch (e: OutOfMemoryError) {
                // Verlauf auslagern bzw. kürzen und einmal erneut versuchen
                val h = editor?.history
                if (h == null || !h.trim()) throw e
                System.gc()
                block()
                message(tr("Wenig Arbeitsspeicher: Ältere Verlaufsschritte wurden ausgelagert bzw. verworfen."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            message(tr("Nicht genug Arbeitsspeicher für diese Aktion. Tipp: Bild verkleinern oder Ebenen zusammenfügen."))
        } catch (e: Exception) {
            message(tr("Fehler: {0}", e.message ?: e.javaClass.simpleName))
        }
    }

    private fun message(text: String) = _state.update { it.copy(message = text) }
    fun clearMessage() = _state.update { it.copy(message = null) }
    fun clearWarnings() = _state.update { it.copy(warnings = emptyList()) }

    // ---------------------------------------------------------------- Dokument & Dateien

    fun newDocument(w: Int, h: Int, transparent: Boolean, showEditor: Boolean = true, dpi: Float = 72f) = onEngine {
        val doc = Document.blank(w, h, if (transparent) null else 0xFFFFFFFF.toInt())
        doc.dpi = dpi
        doc.colorProfile = settings.value.workingRgb.profile
        setDocument(doc, tr("Unbenannt"), emptyList())
        rebuildProof()
        if (showEditor) _state.update { it.copy(showStart = false) }
    }

    /** [remember]: in „Zuletzt verwendet“ eintragen (nicht bei Beispieldokumenten für Screenshots). */
    fun open(uri: Uri, remember: Boolean = true) = onEngine {
        busy(true)
        val cr = getApplication<Application>().contentResolver
        try {
            // Dauerhafte Leseberechtigung, damit die Datei unter „Zuletzt verwendet“ wieder geöffnet werden kann
            if (remember) runCatching { cr.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            // Altes Dokument samt Verlauf vorher freigeben – sonst liegen beim Laden zwei Dokumente im Speicher
            editor?.history?.clear()
            System.gc()
            val s = settings.value
            val working = s.workingRgb.profile
            val loaded = ImageIO.open(cr, uri, getApplication<Application>().cacheDir, working)
            val warnings = loaded.doc.importWarnings.toMutableList()
            setDocument(loaded.doc, loaded.name, emptyList())
            // Farbmanagement-Richtlinie: abweichendes Profil beibehalten oder in den Arbeitsfarbraum umwandeln
            val docProfile = loaded.doc.colorProfile
            if (!docProfile.bytes.contentEquals(working.bytes) && s.profilePolicy == ProfilePolicy.CONVERT) {
                editor?.let { ed ->
                    ed.convertToProfile(working, s.conversionIntent, s.conversionBpc)
                    ed.history.clear()
                    warnings += tr("Profil „{0}“ wurde in den Arbeitsfarbraum „{1}“ umgewandelt.", docProfile.description, working.description)
                }
            }
            rebuildProof()
            if (warnings.isNotEmpty()) _state.update { it.copy(warnings = warnings) }
            _state.update { it.copy(showStart = false) }
            if (remember) addRecent(uri, loaded.name, loaded.doc)
        } catch (e: PsdFormatException) {
            message(tr("PSD konnte nicht gelesen werden: {0}", e.message))
        } catch (e: de.photographics.core.tiff.TiffFormatException) {
            message(tr("TIFF konnte nicht gelesen werden: {0}", e.message))
        } catch (e: de.photographics.core.xcf.XcfFormatException) {
            message(tr("GIMP-Datei konnte nicht gelesen werden: {0}", e.message))
        } catch (e: SecurityException) {
            recentFiles.remove(uri)
            _state.update { it.copy(recent = recentFiles.list()) }
            message(tr("Kein Zugriff mehr auf die Datei – bitte erneut über „Öffnen“ auswählen."))
        } finally {
            busy(false)
        }
    }

    private fun addRecent(uri: Uri, name: String, doc: Document) {
        val scale = 360f / max(doc.width, doc.height)
        val tw = max(1, (doc.width * scale).roundToInt()); val th = max(1, (doc.height * scale).roundToInt())
        val bmp = thumbnail(doc, tw, th)
        recentFiles.add(uri, name, tr("{0} × {1} px · {2} Ebenen", doc.width, doc.height, doc.allLayers().size), bmp)
        bmp.recycle()
        _state.update { it.copy(recent = recentFiles.list()) }
    }

    /** Vorschaubild mit 2×2-Überabtastung, ohne das ganze Dokument auf einmal zu rendern. */
    private fun thumbnail(doc: Document, tw: Int, th: Int): Bitmap {
        val px = IntArray(tw * th)
        val sw = tw * 2
        for (ty in 0 until th) {
            val acc = IntArray(tw * 4)
            for (sub in 0..1) {
                val sy = (((ty * 2 + sub) + 0.5) * doc.height / (th * 2)).toInt().coerceIn(0, doc.height - 1)
                val line = Compositor.renderArgb(doc, IntRect(0, sy, doc.width, sy + 1))
                for (x in 0 until sw) {
                    val c = line[((x + 0.5) * doc.width / sw).toInt().coerceIn(0, doc.width - 1)]
                    val o = (x / 2) * 4
                    acc[o] += c ushr 24; acc[o + 1] += (c shr 16) and 0xFF; acc[o + 2] += (c shr 8) and 0xFF; acc[o + 3] += c and 0xFF
                }
            }
            for (x in 0 until tw) {
                val o = x * 4
                px[ty * tw + x] = ((acc[o] / 4) shl 24) or ((acc[o + 1] / 4) shl 16) or ((acc[o + 2] / 4) shl 8) or (acc[o + 3] / 4)
            }
        }
        return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888)
    }

    fun removeRecent(uri: Uri) { recentFiles.remove(uri); _state.update { it.copy(recent = recentFiles.list()) } }
    fun loadRecentThumb(e: RecentFiles.Entry): Bitmap? = recentFiles.loadThumb(e)
    fun showStartScreen(show: Boolean) = _state.update { it.copy(showStart = show, recent = recentFiles.list()) }

    fun placeAsLayer(uri: Uri) = withEditor { ed ->
        busy(true)
        try {
            val cr = getApplication<Application>().contentResolver
            val name = ImageIO.displayName(cr, uri).substringBeforeLast('.')
            val (grid, profile) = ImageIO.readAsGrid(cr, uri, getApplication<Application>().cacheDir, settings.value.workingRgb.profile)
            // Wie Photoshop: platzierte Bilder in das Dokumentprofil umrechnen
            de.photographics.core.color.ColorTransform.rgb(profile, ed.doc.colorProfile, settings.value.conversionIntent, settings.value.conversionBpc)?.let { conv ->
                val e = grid.extent; val row = IntArray(e.width)
                for (y in e.top until e.bottom) {
                    val r = IntRect(e.left, y, e.right, y + 1)
                    grid.readRect(r, row)
                    for (i in row.indices) { val c = row[i]; if ((c ushr 24) != 0) row[i] = (c and 0xFF000000.toInt()) or conv.map(c and 0xFFFFFF) }
                    grid.writeRect(r, row)
                }
            }
            ed.placeImage(name, grid)
            fullRefresh()
        } finally {
            busy(false)
        }
    }

    fun savePsd(uri: Uri) = withEditor { ed ->
        busy(true)
        try {
            val app = getApplication<Application>()
            ImageIO.savePsd(app.contentResolver, uri, ed.doc, java.io.File(app.cacheDir, "psd-write"))
            message(tr("Als PSD gespeichert."))
        } finally {
            busy(false)
        }
    }

    fun saveXcf(uri: Uri) = withEditor { ed ->
        busy(true)
        try {
            val app = getApplication<Application>()
            val warnings = ImageIO.saveXcf(app.contentResolver, uri, ed.doc, java.io.File(app.cacheDir, "xcf-write"))
            if (warnings.isEmpty()) message(tr("Als GIMP-Datei gespeichert."))
            else _state.update { it.copy(warnings = listOf(tr("Als GIMP-Datei gespeichert.")) + warnings) }
        } finally {
            busy(false)
        }
    }

    fun saveTiff(uri: Uri) = withEditor { ed ->
        busy(true)
        try {
            val app = getApplication<Application>()
            val s = settings.value
            ImageIO.saveTiff(app.contentResolver, uri, ed.doc, java.io.File(app.cacheDir, "tiff-write"), s.tiffCompression, s.tiffLayers)
            message(tr("Als TIFF gespeichert ({0}{1}).", tr(s.tiffCompression.label), if (s.tiffLayers) tr(", mit Ebenen") else ""))
        } finally {
            busy(false)
        }
    }

    fun export(uri: Uri, format: ImageIO.ExportFormat) = withEditor { ed ->
        busy(true)
        try {
            val toSrgb = settings.value.exportToSrgb
            ImageIO.export(getApplication<Application>().contentResolver, uri, ed.doc, format, toSrgb)
            val profileNote = if (toSrgb || ed.doc.colorProfile.bytes.contentEquals(IccProfiles.sRGB.bytes)) "sRGB" else tr("Profil „{0}“ eingebettet", ed.doc.colorProfile.description)
            message(tr("Exportiert als {0} ({1}).", format.ext.uppercase(), profileNote))
        } finally {
            busy(false)
        }
    }

    private fun busy(b: Boolean) = _state.update { it.copy(busy = b) }

    private fun withEditor(block: suspend (Editor) -> Unit) = onEngine { editor?.let { block(it) } }

    private fun setDocument(doc: Document, name: String, warnings: List<String>) {
        val ed = editor
        if (ed == null) editor = Editor(doc).also { e ->
            // Arbeitsvolume für ausgelagerte Verlaufsschritte (wie bei Photoshop)
            e.history.scratch = de.photographics.core.history.ScratchDisk(java.io.File(getApplication<Application>().cacheDir, "scratch"))
        } else ed.replaceDocument(doc)
        thumbs.clear(); maskThumbs.clear()
        stroke = null; cloneOffset = null; filterTx = null
        clearPreview()
        // Anzeige-Bitmap auf max. ~4096 px Kantenlänge begrenzen
        displayScale = 1
        while (max(doc.width, doc.height) / displayScale > 4096) displayScale *= 2
        val bw = (doc.width + displayScale - 1) / displayScale
        val bh = (doc.height + displayScale - 1) / displayScale
        val bmp = TiledBitmap(bw, bh)
        bitmap = bmp
        resetDetail()
        _state.update {
            it.copy(
                hasDocument = true, docWidth = doc.width, docHeight = doc.height, fileName = name,
                bitmap = bmp, displayScale = displayScale, warnings = warnings, cropRect = null,
                cloneSource = null, paintTarget = PaintTarget.PIXELS, filterActive = null,
                textEdit = null, transform = null, adjustEdit = null, docDpi = doc.dpi,
            )
        }
        fullRefresh()
    }

    // ---------------------------------------------------------------- Rendern

    /** Überträgt einen gerenderten Streifen (Dokumentkoordinaten) in die Anzeige-Bitmap, ggf. verkleinert. */
    /** [ox]/[oy]: Lage der Bitmap in Anzeigepixeln (Detailausschnitt), sonst 0. */
    private fun blitBand(bmp: TiledBitmap, r: IntRect, s: Int, px: IntArray, ox: Int = 0, oy: Int = 0) {
        applyProof(px)
        if (s == 1) {
            bmp.setPixels(px, 0, r.width, r.left - ox, r.top - oy, r.width, r.height)
            return
        }
        val ow = (r.width + s - 1) / s; val oh = (r.height + s - 1) / s
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) for (ox in 0 until ow) {
            var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
            for (yy in oy * s until minOf(r.height, oy * s + s)) for (xx in ox * s until minOf(r.width, ox * s + s)) {
                val c = px[yy * r.width + xx]
                val ca = c ushr 24
                a += ca; rr += ((c shr 16) and 0xFF) * ca; g += ((c shr 8) and 0xFF) * ca; b += (c and 0xFF) * ca; n++
            }
            out[oy * ow + ox] = if (a == 0) 0 else ((a / n) shl 24) or ((rr / a) shl 16) or ((g / a) shl 8) or (b / a)
        }
        val ox0 = r.left / s - ox; val oy0 = r.top / s - oy
        val cw = minOf(ow, bmp.width - ox0); val ch = minOf(oh, bmp.height - oy0)
        if (cw > 0 && ch > 0) bmp.setPixels(out, 0, ow, ox0, oy0, cw, ch)
    }

    /** Rendert sofort (nur auf dem Engine-Thread aufrufen). */
    private fun renderNow(rect: IntRect?, notify: Boolean = true, withDetail: Boolean = true) {
        dropLiveBelow()
        if (notify) dropGpu()
        val ed = editor ?: return
        val bmp = bitmap ?: return
        val doc = ed.doc
        val s = displayScale
        var r = (rect ?: doc.bounds).intersect(doc.bounds)
        if (r.isEmpty) return
        if (s > 1) {
            r = IntRect(r.left / s * s, r.top / s * s, minOf(doc.width, (r.right + s - 1) / s * s), minOf(doc.height, (r.bottom + s - 1) / s * s))
        }
        val t0 = System.nanoTime()
        // Streifenweise rendern (Höhe ein Vielfaches des Anzeigemaßstabs): nie ein Puffer in Dokumentgröße
        val cache = strokeCache
        if (cache != null || r.width.toLong() * r.height < 500_000) {
            // Kleine Bereiche und Pinselstriche (Render-Cache ist nicht threadsicher) direkt
            val bandH = maxOf(s, (RENDER_BAND_PIXELS / maxOf(1, r.width)) / s * s)
            var y0 = r.top
            while (y0 < r.bottom) {
                val band = IntRect(r.left, y0, r.right, minOf(r.bottom, y0 + bandH))
                blitBand(bmp, band, s, cache?.renderArgb(band) ?: Compositor.renderArgb(doc, band))
                y0 = band.bottom
            }
        } else if (s > 1) {
            // Verkleinerte Anzeige: direkt in Anzeigeauflösung aus den Mipmaps der Ebenen komponieren
            // (statt in voller Auflösung zu rechnen und danach zu verkleinern), auf allen Kernen
            val out = IntRect(r.left / s, r.top / s, minOf(bmp.width, (r.right + s - 1) / s), minOf(bmp.height, (r.bottom + s - 1) / s))
            Compositor.prepare(doc, s, out)
            val bandH = maxOf(1, 65_536 / maxOf(1, out.width))
            val count = (out.height + bandH - 1) / bandH
            de.photographics.core.image.Parallel.bands(count, out.width.toLong() * bandH * 40, compute = { i ->
                val band = IntRect(out.left, out.top + i * bandH, out.right, minOf(out.bottom, out.top + (i + 1) * bandH))
                band to Compositor.renderArgbSampled(doc, band, s)
            }, consume = { _, (band, px) ->
                applyProof(px)
                bmp.setPixels(px, 0, band.width, band.left, band.top, band.width, band.height)
            })
        } else {
            // Große Bereiche auf allen Kernen
            Compositor.prepare(doc, 1, r)
            val bandH = maxOf(s, (65_536 / maxOf(1, r.width) + s - 1) / s * s)
            val count = (r.height + bandH - 1) / bandH
            de.photographics.core.image.Parallel.bands(count, r.width.toLong() * bandH * 40, compute = { i ->
                val band = IntRect(r.left, r.top + i * bandH, r.right, minOf(r.bottom, r.top + (i + 1) * bandH))
                band to Compositor.renderArgb(doc, band)
            }, consume = { _, (band, px) -> blitBand(bmp, band, s, px) })
        }
        if (withDetail) renderDetail(r)
        if (BuildConfig.DEBUG) android.util.Log.d("PG-Render", "${r.width}x${r.height} ${(System.nanoTime() - t0) / 1_000_000} ms cache=${strokeCache != null}")
        // Ganzes Bild in voller Qualität: eine laufende Vorschau ist damit überholt
        if (notify && _state.value.previewBitmap != null && r == doc.bounds) clearPreview()
        if (notify) bumpRender()
    }

    /**
     * Fasst mehrere Änderungen zu einem Render-Durchlauf zusammen. [live]: Vorschau erlaubt;
     * [layer]: nur diese Ebene hat sich geändert (die Ebenen darunter werden zwischengespeichert).
     */
    private fun requestRender(rect: IntRect?, live: Boolean = false, layer: Layer? = null) {
        val first = !renderQueued
        pendingDirty = if (rect == null) (editor?.doc?.bounds ?: IntRect.EMPTY) else pendingDirty.union(rect)
        pendingLiveLayer = if (first || pendingLiveLayer === layer) layer else null
        if (renderQueued) return
        renderQueued = true
        viewModelScope.launch(engine) {
            renderQueued = false
            val r = pendingDirty
            val l = pendingLiveLayer
            pendingDirty = IntRect.EMPTY; pendingLiveLayer = null
            if (live) renderLive(r, l) else renderNow(r)
        }
    }

    // ---------------------------------------------------------------- Einstellungen & Farbproof

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val old = settings.value
        settingsStore.update(f)
        val s = settings.value
        if (s.proofCondition != old.proofCondition || s.proofSimulatePaper != old.proofSimulatePaper || s.gamutWarningColor != old.gamutWarningColor ||
            s.proofIcc != old.proofIcc || s.proofIntent != old.proofIntent || s.proofBpc != old.proofBpc
        ) {
            rebuildProof()
        }
    }

    @Volatile private var proof: SoftProof? = null
    /** Dokumentprofil → Bildschirm (null = Dokument ist sRGB). */
    @Volatile private var displayProof: SoftProof? = null
    private val proofCache = object : LinkedHashMap<String, SoftProof>(4, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, SoftProof>?) = size > 4
    }

    fun toggleProof() { _state.update { it.copy(proofOn = !it.proofOn) }; rebuildProof() }
    fun toggleGamutWarning() { _state.update { it.copy(gamutWarning = !it.gamutWarning) }; rebuildProof() }

    private fun rebuildProof() = onEngine {
        val st = _state.value; val s = settings.value
        val docProfile = editor?.doc?.colorProfile ?: IccProfiles.sRGB
        val docKey = docProfile.bytes.contentHashCode()
        val isSrgb = docProfile.bytes.contentEquals(IccProfiles.sRGB.bytes)
        displayProof = if (isSrgb) null else proofCache.getOrPut("display:$docKey") { SoftProof.display(docProfile) }
        val icc = s.proofIcc?.let { iccProfile(it) }
        proof = when {
            !st.proofOn && !st.gamutWarning -> null
            icc != null -> proofCache.getOrPut("icc:${s.proofIcc}:$docKey:${s.proofIntent}:${s.proofBpc}:${s.proofSimulatePaper}") {
                SoftProof.icc(docProfile, icc, s.proofIntent, s.proofBpc, s.proofSimulatePaper)
            }
            else -> proofCache.getOrPut("model:${s.proofCondition}:$docKey:${s.proofSimulatePaper}") {
                SoftProof(s.proofCondition, s.proofSimulatePaper, if (isSrgb) null else docProfile)
            }
        }
        _state.update { it.copy(docProfileName = docProfile.description, docProfileIsSrgb = isSrgb, proofLabel = icc?.description ?: s.proofCondition.label) }
        clearPreview()
        renderNow(null)
    }

    /** Proof bzw. Farbumrechnung nur für die Anzeige (Export und Speichern bleiben unverändert). */
    private fun applyProof(px: IntArray) {
        val st = _state.value
        val p = proof
        if (p != null && st.proofOn) {
            p.apply(px, gamutWarning = st.gamutWarning, warnColor = settings.value.gamutWarningColor, transform = true)
            return
        }
        if (p != null && st.gamutWarning) p.apply(px, gamutWarning = true, warnColor = settings.value.gamutWarningColor, transform = false)
        displayProof?.apply(px)
    }

    // ---------------------------------------------------------------- ICC-Profile (Bibliothek) und Profilbefehle

    private val iccDir get() = java.io.File(getApplication<Application>().filesDir, "icc")
    private val iccLoaded = HashMap<String, de.photographics.core.color.IccProfile>()

    private fun iccProfile(name: String): de.photographics.core.color.IccProfile? = iccLoaded[name] ?: runCatching {
        de.photographics.core.color.IccProfile(java.io.File(iccDir, name).readBytes()).also { iccLoaded[name] = it }
    }.getOrNull()

    private fun loadIccLibrary() = onEngine {
        val list = (iccDir.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }.mapNotNull { f ->
            iccProfile(f.name)?.let { p -> IccEntry(f.name, p.description, p.colorSpace.trim(), p.deviceClass) }
        }
        _state.update { it.copy(iccLibrary = list) }
    }

    /** ICC-Profil (z. B. der Druckerei) in die Bibliothek übernehmen. */
    fun importIcc(uri: Uri) = onEngine {
        val cr = getApplication<Application>().contentResolver
        val name = ImageIO.displayName(cr, uri).replace(Regex("[/\\\\]"), "_")
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@onEngine
        val p = try { de.photographics.core.color.IccProfile(bytes).also { it.toPcs(de.photographics.core.color.RenderingIntent.RELATIVE) } }
            catch (e: Exception) { message(tr("Kein verwendbares ICC-Profil: {0}", e.message)); return@onEngine }
        iccDir.mkdirs()
        java.io.File(iccDir, name).writeBytes(bytes)
        iccLoaded[name] = p
        loadIccLibrary()
        message(tr("Profil „{0}“ ({1}) hinzugefügt.", p.description, p.colorSpace.trim()))
    }

    fun deleteIcc(name: String) = onEngine {
        java.io.File(iccDir, name).delete(); iccLoaded.remove(name)
        if (settings.value.proofIcc == name) updateSettings { it.copy(proofIcc = null) }
        loadIccLibrary()
    }

    /** Ziel für Profil zuweisen/umwandeln: eingebautes Profil oder RGB-Profil aus der Bibliothek. */
    private fun targetProfile(builtin: IccProfiles.Builtin?, libraryName: String?) =
        builtin?.profile ?: libraryName?.let { iccProfile(it) }

    fun assignProfile(builtin: IccProfiles.Builtin?, libraryName: String?) = withEditor { ed ->
        val p = targetProfile(builtin, libraryName)?.takeIf { it.isRgb } ?: run { message(tr("Nur RGB-Profile können zugewiesen werden.")); return@withEditor }
        ed.assignProfile(p)
        rebuildProof()
        message(tr("Profil „{0}“ zugewiesen.", p.description))
    }

    fun convertToProfile(builtin: IccProfiles.Builtin?, libraryName: String?, intent: de.photographics.core.color.RenderingIntent, bpc: Boolean) = withEditor { ed ->
        val p = targetProfile(builtin, libraryName)?.takeIf { it.isRgb } ?: run { message(tr("Nur in RGB-Profile kann umgewandelt werden.")); return@withEditor }
        busy(true)
        try {
            val smart = ed.convertToProfile(p, intent, bpc)
            message(tr("In „{0}“ umgewandelt", p.description) + if (smart > 0) tr(" – {0} Smartobjekt(e) unverändert.", smart) else ".")
        } finally { busy(false) }
        thumbs.clear()
        rebuildProof()
        refreshLayers()
    }

    // ---------------------------------------------------------------- Live-Vorschau

    private var proxyBitmap: TiledBitmap? = null
    private var proxyRegion = IntRect.EMPTY
    private var proxyStep = 1
    private var settleJob: Job? = null
    /** Bereich, der nach der Vorschau noch in voller Qualität gerendert werden muss. */
    private var settleDirty = IntRect.EMPTY

    /**
     * Live-Änderung (Regler, Verschieben, Transformieren): Große Bereiche werden zunächst in
     * reduzierter Auflösung gerendert (≈ 1,5 MP, ein Bruchteil der Rechenzeit), die volle Qualität
     * folgt automatisch kurz nachdem die Änderung zur Ruhe gekommen ist.
     */
    /**
     * Zwischenspeicher während einer Live-Änderung: das fertige Bild aller Ebenen unter der geänderten
     * (ARGB in Vorschaukoordinaten) – pro Bild rechnen dann nur noch die Ebene selbst und die darüber.
     */
    private var liveBelow: IntArray? = null
    private var liveBelowKey: Triple<IntRect, Int, Int>? = null

    /** Jede Änderung außerhalb der Live-Vorschau kann die Ebenen darunter betreffen. */
    private fun dropLiveBelow() { liveBelow = null; liveBelowKey = null }

    private fun renderLive(rect: IntRect?, layer: Layer? = null) {
        val doc = editor?.doc ?: return
        val r = (rect ?: doc.bounds).intersect(doc.bounds)
        if (r.isEmpty) return
        if (layer != null && gpuPreview(doc, layer, r)) return
        // Andere Live-Änderung: eine laufende GPU-Vorschau endet mit der neuen Anzeige
        dropGpu()
        val s = displayScale
        // Kleine Änderungen (z. B. eine Ebene verschieben): direkt in Anzeigeauflösung aus den Mipmaps,
        // nur der betroffene Bereich – kein Umweg über die Vorschau
        // (nicht während einer GPU-Vorschau: die Anzeige darunter ist dann noch nicht nachgeführt)
        if (_state.value.previewBitmap == null && gpuLiveState.value == null && r.width.toLong() * r.height / (s * s) <= LIVE_DIRECT_PIXELS) {
            renderNow(r, withDetail = false); staleDetail(r); return
        }
        staleDetail(r)
        // Vorschau nur für den sichtbaren Ausschnitt (hineingezoomt: kleiner und schärfer), so fein,
        // wie das Pixelbudget erlaubt – aber nie feiner als der Bildschirm
        val running = _state.value.previewBitmap != null
        val region = if (running) proxyRegion else viewVisible.intersect(doc.bounds).takeIf { viewScale > 0f && !it.isEmpty } ?: doc.bounds
        val step = if (running) proxyStep else {
            val budget = kotlin.math.ceil(kotlin.math.sqrt(region.width.toDouble() * region.height / PROXY_PIXELS)).toInt()
            maxOf(1, budget, detailLevel().coerceAtMost(s))
        }
        val ox = region.left / step; val oy = region.top / step
        val pw = (region.right + step - 1) / step - ox; val ph = (region.bottom + step - 1) / step - oy
        // Nicht recyceln: die Oberfläche kann die alte Bitmap noch zeichnen
        val pb = proxyBitmap?.takeIf { it.width == pw && it.height == ph } ?: TiledBitmap(pw, ph).also { proxyBitmap = it }
        proxyRegion = region; proxyStep = step
        val t0 = System.nanoTime()
        // Vorschau läuft schon: nur den betroffenen Teil erneuern; sonst einmal ganz aufbauen
        val area = (if (running) IntRect(r.left / step, r.top / step, (r.right + step - 1) / step, (r.bottom + step - 1) / step)
            else IntRect(ox, oy, ox + pw, oy + ph)).intersect(IntRect(ox, oy, ox + pw, oy + ph))
        if (area.isEmpty) { settleDirty = settleDirty.union(r); scheduleSettle(); return }
        Compositor.prepare(doc, step, area)
        val full = IntRect(ox, oy, ox + pw, oy + ph)
        val from = layer?.let { Compositor.liveSplit(doc, it) } ?: 0
        val key = Triple(region, step, from)
        val below: IntArray? = when {
            from == 0 -> null
            running && liveBelowKey == key -> liveBelow
            else -> {
                // Einmal je Live-Änderung: alles unter der geänderten Ebene für die ganze Vorschau
                val b = IntArray(pw * ph)
                bandsOf(full) { band -> Compositor.renderRangeSampled(doc, band, step, 0, from, null) }.let { run ->
                    run { band, px -> for (y in 0 until band.height) System.arraycopy(px, y * band.width, b, (band.top - oy + y) * pw + band.left - ox, band.width) }
                }
                liveBelow = b; liveBelowKey = key
                b
            }
        }
        val top = doc.root.children.size
        bandsOf(area) { band ->
            if (below != null) Compositor.renderRangeSampled(doc, band, step, from, top, below, pw, ox, oy)
            else Compositor.renderArgbSampled(doc, band, step)
        }.let { run -> run { band, px ->
            applyProof(px)
            pb.setPixels(px, 0, band.width, band.left - ox, band.top - oy, band.width, band.height)
        } }
        if (BuildConfig.DEBUG) android.util.Log.d("PG-Render", "Vorschau ${area.width}x${area.height} (1/$step) ${(System.nanoTime() - t0) / 1_000_000} ms")
        settleDirty = settleDirty.union(r)
        val rect = IntRect(ox * step, oy * step, (ox + pw) * step, (oy + ph) * step)
        DisplayWrites.flush()
        _state.update { it.copy(previewBitmap = pb, previewRect = rect) }; bumpRender()
        scheduleSettle()
    }

    /** Mischmodi, die Android genauso rechnet wie photographics (sonst bliebe ein Sprung nach dem Loslassen). */
    private fun gpuBlendOk(m: de.photographics.core.render.BlendMode) = when (m) {
        de.photographics.core.render.BlendMode.NORMAL -> true
        de.photographics.core.render.BlendMode.MULTIPLY, de.photographics.core.render.BlendMode.SCREEN,
        de.photographics.core.render.BlendMode.DARKEN, de.photographics.core.render.BlendMode.LIGHTEN,
        de.photographics.core.render.BlendMode.DIFFERENCE, de.photographics.core.render.BlendMode.EXCLUSION,
        de.photographics.core.render.BlendMode.OVERLAY, de.photographics.core.render.BlendMode.HARD_LIGHT ->
            android.os.Build.VERSION.SDK_INT >= 29
        else -> false
    }

    /**
     * Kann [l] über die GPU gezeichnet werden? Oberste Ebene ohne Schnittmaske, Maske nur verbunden
     * (bei Smartobjekten keine), darüber nur normale Pixel-, Text- und Smartobjekt-Ebenen – dann ist
     * „darunter + Ebene + darüber“ exakt das Ergebnis des Compositors. Kein Farbproof (nicht linear).
     */
    private fun gpuEligible(doc: de.photographics.core.doc.Document, l: Layer): Boolean {
        if (l !is de.photographics.core.doc.RasterLayer || l.parent != null && l.parent !== doc.root) return false
        if (!l.visible || l.clipped || !gpuBlendOk(l.blendMode) || _state.value.proofOn) return false
        if (l.effects?.visible == true) return false   // Effekte rechnet nur der Compositor
        val m = l.mask
        if (m != null && m.enabled && (l is SmartLayer || !m.linked || m.previewDx != 0 || m.previewDy != 0)) return false
        val kids = doc.root.children
        val i = kids.indexOf(l)
        if (i < 0 || kids.getOrNull(i + 1)?.clipped == true) return false
        for (k in i + 1 until kids.size) {
            val a = kids[k]
            if (!a.visible) continue
            if (a !is de.photographics.core.doc.RasterLayer) return false
            if (!a.clipped && a.blendMode != de.photographics.core.render.BlendMode.NORMAL) return false
        }
        return true
    }

    /**
     * Live-Änderung an [layer] über die GPU: beim ersten Aufruf die drei Bitmaps aufbauen, danach nur
     * Lage, Deckkraft und Matrix setzen. false = nicht möglich (normale Vorschau auf dem Prozessor).
     */
    private fun gpuPreview(doc: de.photographics.core.doc.Document, layer: Layer, r: IntRect): Boolean {
        if (!gpuEligible(doc, layer)) return false
        val l = layer as de.photographics.core.doc.RasterLayer
        var g = gpuLiveState.value?.takeIf { it.layerId == l.id }
        if (g == null) {
            g = buildGpuLive(doc, l) ?: return false
            DisplayWrites.flush()
            gpuLiveState.value = g
        }
        g.matrix = gpuMatrix(l, g)
        g.alpha = (l.opacity * l.fillOpacity).coerceIn(0f, 1f)
        staleDetail(r)
        settleDirty = settleDirty.union(r)
        bumpRender()
        scheduleSettle()
        return true
    }

    /** Größte Ebenen-Bitmap der GPU-Vorschau (Pixel). */
    private val gpuLayerPixels = 12_000_000L

    private fun buildGpuLive(doc: de.photographics.core.doc.Document, l: de.photographics.core.doc.RasterLayer): GpuLive? {
        val region = viewVisible.intersect(doc.bounds).takeIf { viewScale > 0f && !it.isEmpty } ?: doc.bounds
        val budget = kotlin.math.ceil(kotlin.math.sqrt(region.width.toDouble() * region.height / PROXY_PIXELS)).toInt()
        val step = maxOf(1, budget, detailLevel().coerceAtMost(displayScale))
        val kids = doc.root.children
        val from = kids.indexOf(l)
        val ox = region.left / step; val oy = region.top / step
        val out = IntRect(ox, oy, (region.right + step - 1) / step, (region.bottom + step - 1) / step)
        Compositor.prepare(doc, step, out)
        fun stack(start: Int, end: Int): TiledBitmap? {
            if (kids.subList(start, end).none { it.visible }) return null
            val b = TiledBitmap(out.width, out.height)
            bandsOf(out) { band -> Compositor.renderRangeSampled(doc, band, step, start, end, null) }.let { run ->
                run { band, px -> b.setPixels(px, 0, band.width, band.left - ox, band.top - oy, band.width, band.height) }
            }
            return b
        }
        val layerBmp: TiledBitmap
        if (l is SmartLayer) {
            // Smartobjekt: die Quelle selbst (passende Mipmap-Stufe); die Matrix übernimmt Skalieren, Drehen, Perspektive
            val perOut = step / maxOf(1e-6, l.homography().scaleFor(l.source.width.toDouble(), l.source.height.toDouble()))
            var level = de.photographics.core.image.MipMap.levelFor(perOut.toInt())
            while ((l.source.width shr level).toLong() * (l.source.height shr level) > gpuLayerPixels) level++
            val src = if (level > 0) l.source.mip(level) else l.source
            val b = TiledBitmap(src.width, src.height)
            bandsOf(IntRect(0, 0, src.width, src.height)) { band -> IntArray(band.width * band.height).also { src.readRect(band, it) } }.let { run ->
                run { band, px -> b.setPixels(px, 0, band.width, band.left, band.top, band.width, band.height) }
            }
            layerBmp = b
            gpuSourceLevel = level
        } else {
            // Pixelebene: ihr Inhalt (unverschoben) in Vorschauauflösung; die Matrix verschiebt
            val cb = l.raster.contentBounds()
            if (cb.isEmpty) return null
            val lr = IntRect(Math.floorDiv(cb.left, step), Math.floorDiv(cb.top, step), (cb.right + step - 1) / step, (cb.bottom + step - 1) / step)
            if (lr.width.toLong() * lr.height > gpuLayerPixels) return null
            val b = TiledBitmap(lr.width, lr.height)
            bandsOf(lr) { band -> Compositor.renderLayerSampled(l, band, step) }.let { run ->
                run { band, px -> b.setPixels(px, 0, band.width, band.left - lr.left, band.top - lr.top, band.width, band.height) }
            }
            layerBmp = b
            gpuLayerRect = lr
        }
        return GpuLive(l.id, IntRect(ox * step, oy * step, out.right * step, out.bottom * step), step,
            stack(0, from), layerBmp, stack(from + 1, kids.size), l.blendMode)
    }

    /** Lage der Ebenen-Bitmap beim Aufbau (Ausgabekoordinaten) bzw. Mipmap-Stufe der Smartobjekt-Quelle. */
    private var gpuLayerRect = IntRect.EMPTY
    private var gpuSourceLevel = 0

    /** Bitmap → Dokument für den aktuellen Stand der Ebene. */
    private fun gpuMatrix(l: de.photographics.core.doc.RasterLayer, g: GpuLive): FloatArray {
        if (l is SmartLayer) {
            val f = (1 shl gpuSourceLevel).toDouble()
            val h = l.homography().m
            // H · Skalierung(f): Mipmap-Pixel → Quellpixel → Dokument; Vorschau-Verschiebung dazu
            val dx = l.previewDx.toDouble(); val dy = l.previewDy.toDouble()
            return floatArrayOf(
                ((h[0] + dx * h[6]) * f).toFloat(), ((h[1] + dx * h[7]) * f).toFloat(), (h[2] + dx * h[8]).toFloat(),
                ((h[3] + dy * h[6]) * f).toFloat(), ((h[4] + dy * h[7]) * f).toFloat(), (h[5] + dy * h[8]).toFloat(),
                (h[6] * f).toFloat(), (h[7] * f).toFloat(), h[8].toFloat(),
            )
        }
        val s = g.step.toFloat(); val lr = gpuLayerRect
        return floatArrayOf(s, 0f, lr.left * s + l.previewDx, 0f, s, lr.top * s + l.previewDy, 0f, 0f, 1f)
    }

    /**
     * Teilt [area] in Streifen, rechnet sie mit [compute] auf allen Kernen und liefert eine Funktion,
     * die die Ergebnisse der Reihe nach auf dem aufrufenden Thread übergibt.
     */
    private inline fun bandsOf(area: IntRect, crossinline compute: (IntRect) -> IntArray): ((IntRect, IntArray) -> Unit) -> Unit = { consume ->
        val bandH = maxOf(1, 32_768 / maxOf(1, area.width))
        val count = (area.height + bandH - 1) / bandH
        de.photographics.core.image.Parallel.bands(count, area.width.toLong() * bandH * 40, compute = { i ->
            val band = IntRect(area.left, area.top + i * bandH, area.right, minOf(area.bottom, area.top + (i + 1) * bandH))
            band to compute(band)
        }, consume = { _, (band, px) -> consume(band, px) })
    }

    /** Nach kurzer Ruhe in voller Qualität nachrendern – streifenweise und abbrechbar. */
    private fun scheduleSettle() {
        settleJob?.cancel()
        settleJob = viewModelScope.launch(engine) {
            kotlinx.coroutines.delay(SETTLE_MS)
            val doc = editor?.doc ?: return@launch
            val r = settleDirty.intersect(doc.bounds)
            var y = r.top
            while (y < r.bottom) {
                val y1 = minOf(r.bottom, y + 256)
                renderNow(IntRect(r.left, y, r.right, y1), notify = false, withDetail = false)
                y = y1
                kotlinx.coroutines.yield()   // neue Live-Änderungen haben Vorrang
            }
            settleDirty = IntRect.EMPTY
            dropGpu()
            _state.update { it.copy(previewBitmap = null) }; bumpRender()
        }
    }

    // ---------------------------------------------------------------- Detailansicht

    /**
     * Sichtbarer Dokumentausschnitt und Zoom der Leinwand (nach dem Zoomen/Verschieben gemeldet).
     * Ist die Anzeige-Bitmap gröber als der Bildschirm (große Bilder, hineingezoomt), wird der
     * Ausschnitt samt Rand in feinerer Auflösung gerechnet – bis 1:1, damit 100 % echte Pixel zeigt.
     */
    fun setViewport(visible: IntRect, scale: Float) {
        viewVisible = visible; viewScale = scale
        scheduleDetail(0)
    }

    /** Feinste sinnvolle Stufe: ein Detailpixel nicht kleiner als ein Bildschirmpixel. */
    private fun detailLevel(): Int {
        val s = displayScale
        if (s <= 1 || viewScale <= 0f) return s
        var l = 1
        while (l * 2 <= s && l * 2 <= 1f / viewScale) l *= 2
        return l
    }

    private fun resetDetail() {
        detailJob?.cancel()
        detail = null; detailDirty = IntRect.EMPTY
        if (_state.value.detail != null) _state.update { it.copy(detail = null) }
        if (viewScale > 0f) scheduleDetail(0)
    }

    /** Live-Änderung im Ausschnitt: Detail ausblenden (die Vorschau zeigt den Stand) und nach kurzer Ruhe nachziehen. */
    private fun staleDetail(r: IntRect) {
        // Wird gerade ein Ausschnitt aufgebaut, mit dem neuen Stand von vorn beginnen
        val d = detail ?: run {
            if (detailJob?.isActive == true) {
                // Halb fertiger Ausschnitt zeigt den alten Stand: ausblenden und neu beginnen
                if (_state.value.detail != null) _state.update { it.copy(detail = null) }
                scheduleDetail(SETTLE_MS)
            }
            return
        }
        val a = r.intersect(d.rect)
        if (a.isEmpty) return
        detailDirty = detailDirty.union(a)
        if (_state.value.detail != null) _state.update { it.copy(detail = null) }
        scheduleDetail(SETTLE_MS)
    }

    /** Zeitpunkt, zu dem der sichtbare Teil des letzten Ausschnitts erschien (für den Benchmark). */
    private var detailShownAt = 0L

    private fun scheduleDetail(delayMs: Long) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch(engine) {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            buildDetail()
        }
    }

    private suspend fun buildDetail() {
        val doc = editor?.doc ?: return
        val level = detailLevel()
        val vis = viewVisible.intersect(doc.bounds)
        if (level >= displayScale || vis.isEmpty) {
            detail = null; detailDirty = IntRect.EMPTY
            if (_state.value.detail != null) _state.update { it.copy(detail = null) }
            return
        }
        val cur = detail
        if (cur != null && cur.step == level && cur.rect.intersect(vis) == vis) {
            // Ausschnitt passt: nur veraltete Teile nachrechnen
            if (!detailDirty.isEmpty) {
                val dirty = detailDirty
                var y = dirty.top
                while (y < dirty.bottom) {
                    val y1 = minOf(dirty.bottom, y + 512)
                    renderDetail(IntRect(dirty.left, y, dirty.right, y1))
                    y = y1
                    kotlinx.coroutines.yield()
                }
                detailDirty = IntRect.EMPTY
            }
            if (_state.value.detail !== cur) {
                DisplayWrites.flush()
                _state.update { it.copy(detail = cur) }; bumpRender()
            }
            return
        }
        // Neuer Ausschnitt mit einem Sechstel Rand je Seite, damit kleines Verschieben nichts neu rechnet
        val l = level
        val mx = vis.width / 6; val my = vis.height / 6
        val out = IntRect(maxOf(0, vis.left - mx) / l, maxOf(0, vis.top - my) / l, (minOf(doc.width, vis.right + mx) + l - 1) / l, (minOf(doc.height, vis.bottom + my) + l - 1) / l)
        val bmp = TiledBitmap(out.width, out.height)
        Compositor.prepare(doc, l, out)
        suspend fun renderPart(part: IntRect) {
            if (part.isEmpty) return
            val bandH = maxOf(1, 65_536 / maxOf(1, part.width))
            var y = part.top
            while (y < part.bottom) {
                // in Abschnitten rechnen: eine neue Zoom-/Verschiebegeste bricht hier ab
                val y1 = minOf(part.bottom, y + bandH * 16)
                val chunk = IntRect(part.left, y, part.right, y1)
                val count = (chunk.height + bandH - 1) / bandH
                de.photographics.core.image.Parallel.bands(count, chunk.width.toLong() * bandH * 40, compute = { i ->
                    val band = IntRect(chunk.left, chunk.top + i * bandH, chunk.right, minOf(chunk.bottom, chunk.top + (i + 1) * bandH))
                    band to Compositor.renderArgbSampled(doc, band, l)
                }, consume = { _, (band, px) ->
                    applyProof(px)
                    bmp.setPixels(px, 0, band.width, band.left - out.left, band.top - out.top, band.width, band.height)
                })
                y = y1
                kotlinx.coroutines.yield()
            }
        }
        val docRect = IntRect(out.left * l, out.top * l, out.right * l, out.bottom * l)
        // Zuerst der sichtbare Teil (sofort zeigen), dann der Rand oben, unten, links und rechts
        val v = IntRect(vis.left / l, vis.top / l, (vis.right + l - 1) / l, (vis.bottom + l - 1) / l).intersect(out)
        renderPart(v)
        DisplayWrites.flush()
        _state.update { it.copy(detail = DetailView(bmp, docRect, l, IntRect(v.left * l, v.top * l, v.right * l, v.bottom * l))) }; bumpRender()
        detailShownAt = System.nanoTime()
        renderPart(IntRect(out.left, out.top, out.right, v.top))
        renderPart(IntRect(out.left, v.bottom, out.right, out.bottom))
        renderPart(IntRect(out.left, v.top, v.left, v.bottom))
        renderPart(IntRect(v.right, v.top, out.right, v.bottom))
        detail = DetailView(bmp, docRect, l)
        detailDirty = IntRect.EMPTY
        DisplayWrites.flush()
        _state.update { it.copy(detail = detail) }; bumpRender()
    }

    /** Bereich [r] (Dokument) im Detailausschnitt neu rechnen – z. B. nach jedem Pinselabdruck. */
    private fun renderDetail(r: IntRect) {
        val d = detail ?: return
        val doc = editor?.doc ?: return
        val l = d.step
        val a = r.intersect(d.rect).intersect(doc.bounds)
        if (a.isEmpty) return
        val ox = d.rect.left / l; val oy = d.rect.top / l
        val cache = strokeCache
        if (cache != null) {
            // Render-Cache liefert volle Auflösung (nicht threadsicher): seriell, auf Detailstufe verkleinern
            val full = IntRect(a.left / l * l, a.top / l * l, minOf(doc.width, (a.right + l - 1) / l * l), minOf(doc.height, (a.bottom + l - 1) / l * l))
            val bandH = maxOf(l, (RENDER_BAND_PIXELS / maxOf(1, full.width)) / l * l)
            var y0 = full.top
            while (y0 < full.bottom) {
                val band = IntRect(full.left, y0, full.right, minOf(full.bottom, y0 + bandH))
                blitBand(d.bitmap, band, l, cache.renderArgb(band), ox, oy)
                y0 = band.bottom
            }
            return
        }
        val out = IntRect(a.left / l, a.top / l, minOf(d.bitmap.width + ox, (a.right + l - 1) / l), minOf(d.bitmap.height + oy, (a.bottom + l - 1) / l))
        if (out.isEmpty) return
        Compositor.prepare(doc, l, out)
        val bandH = maxOf(1, 65_536 / maxOf(1, out.width))
        val count = (out.height + bandH - 1) / bandH
        de.photographics.core.image.Parallel.bands(count, out.width.toLong() * bandH * 40, compute = { i ->
            val band = IntRect(out.left, out.top + i * bandH, out.right, minOf(out.bottom, out.top + (i + 1) * bandH))
            band to Compositor.renderArgbSampled(doc, band, l)
        }, consume = { _, (band, px) ->
            applyProof(px)
            d.bitmap.setPixels(px, 0, band.width, band.left - ox, band.top - oy, band.width, band.height)
        })
    }

    private fun clearPreview() {
        dropLiveBelow()
        if (gpuLiveState.value != null) { gpuDropPending = false; gpuLiveState.value = null }
        settleJob?.cancel(); settleJob = null
        settleDirty = IntRect.EMPTY
        if (_state.value.previewBitmap != null) _state.update { it.copy(previewBitmap = null) }
    }

    private fun fullRefresh() {
        thumbs.clear(); maskThumbs.clear()
        renderNow(null)
        refreshLayers()
    }

    private fun refreshLayers(thumbFor: Layer? = null) {
        val ed = editor ?: return
        val doc = ed.doc
        if (_state.value.guides != doc.guides) _state.update { it.copy(guides = doc.guides) }
        if (thumbFor != null) { thumbs.remove(thumbFor.id); maskThumbs.remove(thumbFor.id) }
        val items = mutableListOf<LayerItem>()
        fun walk(g: GroupLayer, depth: Int) {
            for ((idx, c) in g.children.withIndex().reversed()) {
                val clipBase = !c.clipped && g.children.getOrNull(idx + 1)?.clipped == true
                val thumb = when (c) {
                    is RasterLayer -> thumbs.getOrPut(c.id) { makeThumb(c.raster, doc, isMask = false) }
                    else -> null
                }
                val type = when (c) {
                    is GroupLayer -> LayerType.GROUP
                    is AdjustmentLayer -> LayerType.ADJUSTMENT
                    is TextLayer -> LayerType.TEXT
                    is SmartLayer -> LayerType.SMART
                    else -> LayerType.PIXEL
                }
                val detail = when (c) {
                    is AdjustmentLayer -> c.adjustment.label
                    is TextLayer -> tr("Text")
                    is SmartLayer -> tr("Smartobjekt")
                    else -> null
                }
                val mThumb = c.mask?.let { m -> maskThumbs.getOrPut(c.id) { makeThumb(m.grid, doc, isMask = true) } }
                items += LayerItem(
                    c.id, c.name, depth, c is GroupLayer, (c as? GroupLayer)?.expanded ?: false, c.visible,
                    c.opacity, c.fillOpacity, c.blendMode, c.clipped, c.lockTransparency, c.mask != null,
                    c.mask?.enabled ?: false, c.kind, thumb, mThumb, type, detail,
                    maskLinked = c.mask?.linked ?: true, clipBase = clipBase,
                )
                if (c is GroupLayer && c.expanded) walk(c, depth + 1)
            }
        }
        walk(doc.root, 0)
        val h = ed.history
        val sel = doc.selection
        _state.update {
            it.copy(
                layers = items, activeLayerId = doc.activeLayer?.id,
                docWidth = doc.width, docHeight = doc.height,
                canUndo = h.canUndo, canRedo = h.canRedo, undoLabel = h.undoLabel, redoLabel = h.redoLabel,
                hasSelection = sel != null, selectionOutlines = sel?.outlines ?: emptyList(),
                paintTarget = if (doc.activeLayer?.mask == null) PaintTarget.PIXELS else it.paintTarget,
            )
        }
    }

    private fun makeThumb(grid: TileGrid, doc: Document, isMask: Boolean): ImageBitmap {
        val size = 56
        val scale = max(doc.width, doc.height).toFloat() / size
        val tw = max(1, (doc.width / scale).roundToInt()); val th = max(1, (doc.height / scale).roundToInt())
        val px = IntArray(tw * th)
        for (y in 0 until th) for (x in 0 until tw) {
            val v = grid[((x + 0.5f) * scale).toInt(), ((y + 0.5f) * scale).toInt()]
            px[y * tw + x] = if (isMask) (0xFF shl 24) or (v * 0x010101) else v
        }
        return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Größe/Bitmap hat sich geändert (Freistellen, Drehen, Bildgröße). */
    private fun documentGeometryChanged() {
        val ed = editor ?: return
        setDocumentKeepHistory(ed.doc)
    }

    private fun setDocumentKeepHistory(doc: Document) {
        displayScale = 1
        while (max(doc.width, doc.height) / displayScale > 4096) displayScale *= 2
        val bw = (doc.width + displayScale - 1) / displayScale
        val bh = (doc.height + displayScale - 1) / displayScale
        if (bitmap?.width != bw || bitmap?.height != bh) {
            bitmap = TiledBitmap(bw, bh)
        } else {
            bitmap?.eraseColor(0)
        }
        resetDetail()
        DisplayWrites.flush()
        _state.update { it.copy(bitmap = bitmap, displayScale = displayScale, docWidth = doc.width, docHeight = doc.height, cropRect = null) }
        fullRefresh()
    }

    // ---------------------------------------------------------------- Verlauf

    fun undo() = withEditor { ed ->
        val w = ed.doc.width; val h = ed.doc.height
        val dirty = ed.undo()
        afterHistoryJump(ed, w, h, dirty)
    }

    fun redo() = withEditor { ed ->
        val w = ed.doc.width; val h = ed.doc.height
        val dirty = ed.redo()
        afterHistoryJump(ed, w, h, dirty)
    }

    private fun afterHistoryJump(ed: Editor, oldW: Int, oldH: Int, dirty: IntRect?) {
        // Profil zugewiesen/umgewandelt rückgängig gemacht: Anzeige-Umrechnung neu aufbauen
        if (ed.doc.colorProfile.description != _state.value.docProfileName) rebuildProof()
        if (ed.doc.width != oldW || ed.doc.height != oldH) { setDocumentKeepHistory(ed.doc); return }
        if (dirty != null) {
            renderNow(dirty)
            thumbs.clear(); maskThumbs.clear()
            refreshLayers()
        } else {
            fullRefresh()
        }
    }

    // ---------------------------------------------------------------- Werkzeuge

    fun selectTool(t: Tool) {
        val prev = _state.value
        // Laufende Bearbeitungen abschließen, bevor das Werkzeug wechselt
        if (prev.transform != null && t != Tool.TRANSFORM) applyTransform()
        if (prev.textEdit != null && t != Tool.TEXT) commitTextEdit()
        _state.update { it.copy(tool = t, settingCloneSource = false, cropRect = if (t == Tool.CROP) it.cropRect else null) }
        if (t == Tool.CROP) withEditor { ed -> _state.update { it.copy(cropRect = ed.doc.bounds) } }
        if (t == Tool.TRANSFORM && prev.transform == null) beginTransform()
    }

    fun updateBrush(b: BrushSettings) = _state.update { it.copy(brush = b) }

    // ---------------------------------------------------------------- Farbfelder

    private val colorPrefs by lazy { getApplication<Application>().getSharedPreferences("colors", android.content.Context.MODE_PRIVATE) }
    private val paletteDir get() = java.io.File(getApplication<Application>().filesDir, "palettes")
    // Fester Name (Schlüssel der Datei); angezeigt wird die Übersetzung
    private val userPaletteName = "Eigene Farbfelder"

    private fun addRecentColor(c: Int) {
        val col = c or 0xFF000000.toInt()
        _state.update { it.copy(recentColors = (listOf(col) + it.recentColors.filter { r -> r != col }).take(16)) }
        val s = _state.value
        colorPrefs.edit().putString("recent", s.recentColors.joinToString(",")).putInt("fg", s.color).putInt("bg", s.bgColor).apply()
    }

    private fun paletteFile(name: String) = java.io.File(paletteDir, name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_") + ".gpl")

    private fun userPalettes(): List<de.photographics.core.color.Palette> =
        (paletteDir.listFiles { f -> f.extension == "gpl" } ?: emptyArray()).sortedBy { it.name.lowercase() }.mapNotNull { f ->
            runCatching { de.photographics.core.color.PaletteIO.readGpl(f.readText(), f.nameWithoutExtension) }.getOrNull()
        }

    fun loadPalettes() {
        val recent = colorPrefs.getString("recent", "")!!.split(',').mapNotNull { it.toIntOrNull() }
        val active = colorPrefs.getString("palette", "Standard")!!
        _state.update {
            it.copy(
                palettes = userPalettes() + de.photographics.core.color.BuiltinPalettes.all, activePalette = active, recentColors = recent,
                color = colorPrefs.getInt("fg", it.color), bgColor = colorPrefs.getInt("bg", it.bgColor),
            )
        }
    }

    private fun isBuiltinPalette(name: String) = de.photographics.core.color.BuiltinPalettes.all.any { it.name == name } &&
        userPalettes().none { it.name == name }

    private fun savePalette(p: de.photographics.core.color.Palette) {
        paletteDir.mkdirs()
        paletteFile(p.name).writeText(de.photographics.core.color.PaletteIO.writeGpl(p))
        _state.update { it.copy(palettes = userPalettes() + de.photographics.core.color.BuiltinPalettes.all, activePalette = p.name) }
        colorPrefs.edit().putString("palette", p.name).apply()
    }

    fun selectPalette(name: String) {
        _state.update { it.copy(activePalette = name) }
        colorPrefs.edit().putString("palette", name).apply()
    }

    /** Farbe zur aktiven Bibliothek hinzufügen; eingebaute Bibliotheken bleiben unverändert → „Eigene Farbfelder“. */
    fun addSwatch(color: Int, name: String = "") {
        val st = _state.value
        val target = if (isBuiltinPalette(st.activePalette)) userPalettes().firstOrNull { it.name == userPaletteName }
            ?: de.photographics.core.color.Palette(userPaletteName, emptyList())
            else st.palettes.first { it.name == st.activePalette }
        savePalette(target.copy(swatches = target.swatches + de.photographics.core.color.Swatch(color or 0xFF000000.toInt(), name)))
    }

    fun removeSwatch(paletteName: String, index: Int) {
        if (isBuiltinPalette(paletteName)) return
        val p = _state.value.palettes.firstOrNull { it.name == paletteName } ?: return
        savePalette(p.copy(swatches = p.swatches.filterIndexed { i, _ -> i != index }))
    }

    fun newPalette(name: String) {
        val n = name.trim().ifEmpty { return }
        if (_state.value.palettes.any { it.name == n }) { selectPalette(n); return }
        savePalette(de.photographics.core.color.Palette(n, emptyList()))
    }

    fun deletePalette(name: String) {
        if (isBuiltinPalette(name)) return
        paletteFile(name).delete()
        _state.update { it.copy(palettes = userPalettes() + de.photographics.core.color.BuiltinPalettes.all) }
        selectPalette("Standard")
    }

    /** Farbfelder importieren (.aco, .ase, .gpl, .act) – als eigene Bibliothek. */
    fun importPalette(uri: Uri) = onEngine {
        val cr = getApplication<Application>().contentResolver
        val fileName = ImageIO.displayName(cr, uri)
        try {
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@onEngine
            var p = de.photographics.core.color.PaletteIO.read(bytes, fileName)
            if (p.swatches.isEmpty()) { message(tr("Keine Farbfelder in „{0}“.", fileName)); return@onEngine }
            if (isBuiltinPalette(p.name)) p = p.copy(name = p.name + tr(" (importiert)"))
            withContext(kotlinx.coroutines.Dispatchers.Main) { savePalette(p) }
            message(tr("{0} Farbfelder aus „{1}“ übernommen.", p.swatches.size, fileName))
        } catch (e: Exception) {
            message(tr("Farbfelder konnten nicht gelesen werden: {0}", e.message))
        }
    }

    /** Aktive Bibliothek exportieren; Format nach Dateiendung (.aco, .ase, sonst .gpl). */
    fun exportPalette(uri: Uri, ext: String) = onEngine {
        val st = _state.value
        val p = st.palettes.firstOrNull { it.name == st.activePalette } ?: return@onEngine
        val bytes = when (ext) {
            "aco" -> de.photographics.core.color.PaletteIO.writeAco(p)
            "ase" -> de.photographics.core.color.PaletteIO.writeAse(p)
            else -> de.photographics.core.color.PaletteIO.writeGpl(p).toByteArray()
        }
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        message(tr("Farbfelder „{0}“ exportiert (.{1}).", p.name, ext))
    }

    // ---------------------------------------------------------------- Pinselvorgaben

    // lazy: wird schon im init-Block (weiter oben) benutzt
    private val brushPrefs by lazy { getApplication<Application>().getSharedPreferences("brushes", android.content.Context.MODE_PRIVATE) }

    private fun userPresets(): List<de.photographics.core.tools.BrushPreset> =
        (brushPrefs.getString("presets", "") ?: "").lines().filter { '\t' in it }.map { line ->
            val (name, enc) = line.split('\t', limit = 2)
            de.photographics.core.tools.BrushPreset(name, BrushSettings.decode(enc))
        }

    private fun storeUserPresets(list: List<de.photographics.core.tools.BrushPreset>) {
        brushPrefs.edit().putString("presets", list.joinToString("\n") { "${it.name.replace('\t', ' ').replace('\n', ' ')}\t${it.settings.encode()}" }).apply()
        _state.update { it.copy(brushPresets = de.photographics.core.tools.BrushPresets.builtin + list) }
    }

    fun loadBrushPresets() {
        _state.update { it.copy(brushPresets = de.photographics.core.tools.BrushPresets.builtin + userPresets()) }
        // Zuletzt benutzte Pinseleinstellungen
        brushPrefs.getString("current", null)?.let { enc -> _state.update { it.copy(brush = BrushSettings.decode(enc)) } }
    }

    /** Aktuelle Pinseleinstellungen merken (beim Schließen des Panels bzw. der App). */
    fun rememberBrush() = brushPrefs.edit().putString("current", _state.value.brush.encode()).apply()

    fun applyBrushPreset(p: de.photographics.core.tools.BrushPreset) {
        _state.update { st -> st.copy(brush = if (p.keepSize) p.settings.copy(size = st.brush.size) else p.settings) }
        rememberBrush()
    }

    fun saveBrushPreset(name: String) {
        val n = name.trim().ifEmpty { return }
        storeUserPresets(userPresets().filter { it.name != n } + de.photographics.core.tools.BrushPreset(n, _state.value.brush, keepSize = false))
        message(tr("Pinselvorgabe „{0}“ gespeichert.", n))
    }

    fun deleteBrushPreset(name: String) = storeUserPresets(userPresets().filter { it.name != name })

    /**
     * Vorschau eines Strichs mit [settings] (S-Kurve, Druck an- und abschwellend) – unabhängig vom
     * Dokument, daher auf einem eigenen Thread.
     */
    suspend fun brushPreview(settings: BrushSettings, w: Int, h: Int): Bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val st = _state.value
        val doc = de.photographics.core.doc.Document(w, h)
        val layer = PixelLayer(tr("Vorschau"), de.photographics.core.image.TileGrid(w, h))
        doc.root.add(layer); doc.activeLayer = layer
        val ed = Editor(doc)
        // Große Pinsel verkleinert zeigen, Streuung bleibt im Verhältnis
        val shown = settings.copy(size = settings.size.coerceIn(2f, h * 0.45f), smoothing = 0f)
        val stroke = de.photographics.core.tools.Stroke(
            de.photographics.core.tools.StrokeContext(doc, layer, layer.pixels, PaintTarget.PIXELS, shown),
            BrushTool(st.color, st.bgColor), seed = 7,
        )
        val n = 80
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = w * (0.08f + 0.84f * t)
            val y = h * (0.5f + 0.28f * kotlin.math.sin(t * 2 * Math.PI).toFloat())
            stroke.addPoint(x, y, (kotlin.math.sin(t * Math.PI).toFloat() * 0.9f + 0.1f))
            if (shown.airbrush && i % 20 == 10) repeat(3) { stroke.airbrushTick() }
        }
        stroke.finish()
        val px = IntArray(w * h); layer.pixels.readRect(IntRect.ofSize(w, h), px)
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Pinselgröße in Stufen ändern (Tasten [ und ]). */
    fun stepBrushSize(up: Boolean) = _state.update {
        val s = if (up) it.brush.size * 1.2f + 1f else (it.brush.size - 1f) / 1.2f
        it.copy(brush = it.brush.copy(size = s.coerceIn(1f, 500f)))
    }

    fun setBrushOpacity(v: Float) = _state.update { it.copy(brush = it.brush.copy(opacity = v.coerceIn(0.01f, 1f))) }

    /** Erster Stift-Kontakt: automatisch in den Stift-Modus wechseln. */
    fun onStylusDetected() {
        if (_state.value.stylusSeen) return
        _state.update { it.copy(stylusSeen = true, stylusOnly = true) }
        message(tr("Stift erkannt: Nur der Stift malt, Finger zoomen und verschieben. Umschalten über das Stift-Symbol oben."))
    }

    fun setStylusOnly(b: Boolean) = _state.update { it.copy(stylusOnly = b, stylusSeen = true) }
    fun setColor(c: Int) { _state.update { it.copy(color = c or 0xFF000000.toInt()) }; addRecentColor(c) }
    fun setBgColor(c: Int) { _state.update { it.copy(bgColor = c or 0xFF000000.toInt()) }; addRecentColor(c) }
    /** Vorder- und Hintergrundfarbe tauschen (X). */
    fun swapColors() = _state.update { it.copy(color = it.bgColor, bgColor = it.color) }
    /** Standardfarben Schwarz/Weiß (D). */
    fun resetColors() = _state.update { it.copy(color = 0xFF000000.toInt(), bgColor = 0xFFFFFFFF.toInt()) }
    fun setEyedropperToBg(b: Boolean) = _state.update { it.copy(eyedropperToBg = b) }
    fun setPaintTarget(t: PaintTarget) = _state.update { it.copy(paintTarget = t) }
    fun setSelectionShape(s: SelectionShape) = _state.update { it.copy(selectionShape = s) }
    fun setSelectionOp(o: SelectionOp) = _state.update { it.copy(selectionOp = o) }
    fun setSampleAll(b: Boolean) = _state.update { it.copy(sampleAllLayers = b) }
    fun setToneRange(r: ToneRange) = _state.update { it.copy(toneRange = r) }
    fun setToolStrength(v: Float) = _state.update { it.copy(toolStrength = v) }
    fun setSpongeSaturate(b: Boolean) = _state.update { it.copy(spongeSaturate = b) }
    fun setLiquifyMode(m: de.photographics.core.tools.LiquifyMode) = _state.update { it.copy(liquifyMode = m) }
    fun startSettingCloneSource() = _state.update { it.copy(settingCloneSource = true) }

    fun setCloneSource(x: Float, y: Float) {
        cloneOffset = null
        _state.update { it.copy(cloneSource = x to y, settingCloneSource = false) }
    }

    private fun createTool(s: EditorUiState, x: Float, y: Float): PaintTool? = when (s.tool) {
        Tool.BRUSH -> BrushTool(s.color, s.bgColor)
        // Wie Photoshop: Radieren auf Ebenen mit fixierter Transparenz malt mit der Hintergrundfarbe
        Tool.ERASER -> if (editor?.doc?.activeLayer?.lockTransparency == true && s.paintTarget == PaintTarget.PIXELS) BrushTool(s.bgColor) else EraserTool()
        Tool.CLONE, Tool.HEAL -> {
            val src = s.cloneSource
            if (src == null) {
                message(tr("Zuerst eine Quelle festlegen („Quelle setzen“, dann ins Bild tippen)."))
                null
            } else {
                val off = cloneOffset ?: ((src.first - x).roundToInt() to (src.second - y).roundToInt()).also { cloneOffset = it }
                if (s.tool == Tool.CLONE) CloneTool(off.first, off.second, s.sampleAllLayers)
                else HealTool(off.first, off.second, s.sampleAllLayers)
            }
        }
        Tool.SPOT_HEAL -> SpotHealTool(s.sampleAllLayers)
        Tool.SMUDGE -> SmudgeTool(0.3f + s.toolStrength * 0.6f)
        Tool.LIQUIFY -> de.photographics.core.tools.LiquifyTool(s.liquifyMode, 0.15f + s.toolStrength * 0.85f)
        Tool.AI_REMOVE -> {
            val m = ai.firstReady(AiPurpose.INPAINT, s.aiInpaintModel)
            if (m == null) {
                _state.update { it.copy(requestAiModels = AiPurpose.INPAINT) }
                message(tr("Für „KI-Entfernen“ zuerst ein KI-Modell laden."))
                null
            } else SpotHealTool(s.sampleAllLayers, OnnxInpainter(ai, m), tr("KI-Entfernen"))
        }
        Tool.BLUR -> BlurTool(sigma = 1f + s.brush.size * 0.04f, strength = s.toolStrength * 2f)
        Tool.SHARPEN -> SharpenTool(strength = s.toolStrength * 2f)
        Tool.DODGE -> DodgeBurnTool(true, s.toneRange, s.toolStrength)
        Tool.BURN -> DodgeBurnTool(false, s.toneRange, s.toolStrength)
        Tool.SPONGE -> SpongeTool(s.spongeSaturate, s.toolStrength)
        else -> null
    }

    /** [toolOverride]: z. B. Radierer, wenn mit dem Radier-Ende des Stifts gemalt wird. */
    fun strokeBegin(x: Float, y: Float, pressure: Float, toolOverride: Tool? = null) {
        val s = _state.value.let { if (toolOverride != null) it.copy(tool = toolOverride) else it }
        withEditor { ed ->
            val tool = createTool(s, x, y) ?: return@withEditor
            val target = if (s.paintTarget == PaintTarget.MASK && tool.supportsMask) PaintTarget.MASK else PaintTarget.PIXELS
            val layer = ed.doc.activeLayer
            val st = ed.beginStroke(tool, s.brush, target)
            if (st == null) {
                message(
                    when {
                        layer == null -> tr("Keine Ebene ausgewählt.")
                        !layer.visible -> tr("Die Ebene ist ausgeblendet.")
                        s.paintTarget == PaintTarget.MASK && !tool.supportsMask -> tr("{0} funktioniert nicht auf Masken.", tool.label)
                        else -> tr("Auf dieser Ebene kann nicht gemalt werden (Gruppe?).")
                    },
                )
                return@withEditor
            }
            stroke = st
            strokeCache = if (target == PaintTarget.PIXELS && layer is PixelLayer && tool !is SpotHealTool) StrokeRenderCache.create(ed.doc, layer) else null
            requestRender(st.addPoint(x, y, pressure))
            // Airbrush: Farbe baut sich auch bei ruhendem Stift weiter auf (20 Abdrücke pro Sekunde)
            airbrushJob?.cancel()
            if (s.brush.airbrush) airbrushJob = viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(50)
                    onEngine { if (stroke === st) requestRender(st.airbrushTick()) }
                }
            }
        }
    }

    fun strokeMove(x: Float, y: Float, pressure: Float) {
        synchronized(pendingPoints) {
            pendingPoints += floatArrayOf(x, y, pressure)
            if (pointsQueued) return
            pointsQueued = true
        }
        onEngine {
            // Alle bis jetzt eingetroffenen Punkte in einem Durchgang verarbeiten (kein Rückstau)
            val pts = synchronized(pendingPoints) { pointsQueued = false; ArrayList(pendingPoints).also { pendingPoints.clear() } }
            val st = stroke ?: return@onEngine
            var dirty = IntRect.EMPTY
            val t0 = System.nanoTime()
            for (p in pts) dirty = dirty.union(st.addPoint(p[0], p[1], p[2]))
            if (BuildConfig.DEBUG) android.util.Log.d("PG-Dab", "${pts.size} Punkte ${(System.nanoTime() - t0) / 1_000_000} ms")
            requestRender(dirty)
        }
    }

    /** Noch nicht verarbeitete Punkte sofort abarbeiten (vor Strichende); liefert den geänderten Bereich. */
    private fun drainPoints(): IntRect {
        val pts = synchronized(pendingPoints) { pointsQueued = false; ArrayList(pendingPoints).also { pendingPoints.clear() } }
        val st = stroke ?: return IntRect.EMPTY
        var dirty = IntRect.EMPTY
        for (p in pts) dirty = dirty.union(st.addPoint(p[0], p[1], p[2]))
        return dirty
    }

    private var airbrushJob: Job? = null

    fun strokeEnd() = withEditor { ed ->
        airbrushJob?.cancel(); airbrushJob = null
        // Restliche Punkte noch mit dem schnellen Zwischenbild zeichnen
        val tail = drainPoints().union(stroke?.catchUp() ?: IntRect.EMPTY)
        if (!tail.isEmpty) renderNow(tail)
        val st = stroke ?: return@withEditor
        stroke = null
        val usedCache = strokeCache != null
        strokeCache = null
        val tool = _state.value.tool
        // Nur diese Werkzeuge verändern das Bild noch beim Absetzen
        val changesOnFinish = tool in setOf(Tool.SPOT_HEAL, Tool.AI_REMOVE, Tool.HEAL, Tool.SMUDGE)
        if (changesOnFinish) busy(true)
        val tEnd = System.nanoTime()
        val dirty = try { ed.endStroke(st) } finally { if (changesOnFinish) busy(false) }
        if (BuildConfig.DEBUG) android.util.Log.d("PG-Finish", "${tool.name}: ${(System.nanoTime() - tEnd) / 1_000_000} ms")
        if (changesOnFinish || !usedCache) renderNow(dirty)
        refreshLayers(thumbFor = ed.doc.activeLayer)
    }

    fun strokeCancel() = onEngine {
        airbrushJob?.cancel(); airbrushJob = null
        synchronized(pendingPoints) { pendingPoints.clear(); pointsQueued = false }
        val st = stroke ?: return@onEngine
        stroke = null
        strokeCache = null
        renderNow(st.cancel())
    }

    fun pickColor(x: Float, y: Float, background: Boolean = false) = withEditor { ed ->
        val c = ed.sampleColor(x.toInt(), y.toInt())
        if ((c ushr 24) == 0) return@withEditor
        val toBg = background || (_state.value.tool == Tool.EYEDROPPER && _state.value.eyedropperToBg)
        _state.update { if (toBg) it.copy(bgColor = c or 0xFF000000.toInt()) else it.copy(color = c or 0xFF000000.toInt()) }
    }

    // ---------------------------------------------------------------- Verschieben

    fun setAutoSelectLayer(b: Boolean) = _state.update { it.copy(autoSelectLayer = b) }

    /** [pickX]/[pickY]: Ebene unter diesem Punkt vorher aktiv machen (automatisch auswählen). */
    fun moveBegin(pickX: Float? = null, pickY: Float? = null) = withEditor { ed ->
        if (pickX != null && pickY != null) {
            val hit = de.photographics.core.doc.LayerPick.at(ed.doc, pickX.toInt(), pickY.toInt())
            if (hit != null && hit !== ed.doc.activeLayer) { ed.setActive(hit); refreshLayers() }
        }
        val l = ed.doc.activeLayer
        if (l !is RasterLayer) { message(tr("Gruppen und Einstellungsebenen können nicht verschoben werden.")); return@withEditor }
        moveLayer = l
        snapTargets = if (settings.value.snap) de.photographics.core.editor.Snap.targets(ed.doc, l) else null
        val m = l.mask
        moveMaskOnly = m != null && !m.linked && _state.value.paintTarget == PaintTarget.MASK
        // Bereich, der sich beim Ziehen ändern kann: Ebeneninhalt bzw. Maskeninhalt (Maske verdeckt außerhalb)
        moveBounds = if (moveMaskOnly) ed.doc.bounds else l.raster.contentBounds().let { b -> if (m != null && m.linked) b.union(m.grid.contentBounds()) else b }
    }

    private var moveMaskOnly = false
    private var snapTargets: de.photographics.core.editor.Snap.Targets? = null

    /** Verschieben mit Einrasten: [snapTolerance] in Dokumentpixeln (vom Bildschirm abhängig). */
    fun moveTo(dxIn: Float, dyIn: Float, snapTolerance: Float = 0f) = onEngine {
        val l = moveLayer ?: return@onEngine
        var dx = dxIn; var dy = dyIn
        val targets = snapTargets
        if (targets != null && snapTolerance > 0f && !moveMaskOnly && !moveBounds.isEmpty) {
            val r = de.photographics.core.editor.Snap.snap(moveBounds.offset(dx.roundToInt(), dy.roundToInt()), targets, snapTolerance.toDouble())
            dx += r.dx.toFloat(); dy += r.dy.toFloat()
            val lines = if (settings.value.smartGuides) r.lines else emptyList()
            if (lines != _state.value.smartLines) _state.update { it.copy(smartLines = lines) }
        }
        if (moveMaskOnly) {
            val m = l.mask ?: return@onEngine
            m.previewDx = dx.roundToInt(); m.previewDy = dy.roundToInt()
            requestRender(moveBounds, live = true, layer = l)
            return@onEngine
        }
        val old = moveBounds.offset(l.previewDx, l.previewDy)
        l.previewDx = dx.roundToInt(); l.previewDy = dy.roundToInt()
        requestRender(old.union(moveBounds.offset(l.previewDx, l.previewDy)), live = true, layer = l)
    }

    fun moveEnd() = withEditor { ed ->
        val l = moveLayer ?: return@withEditor
        moveLayer = null; snapTargets = null
        if (_state.value.smartLines.isNotEmpty()) _state.update { it.copy(smartLines = emptyList()) }
        val m = l.mask
        if (moveMaskOnly && m != null) {
            ed.commitMove(l, m.previewDx, m.previewDy, PaintTarget.MASK)
            maskThumbs.remove(l.id); renderNow(null); refreshLayers(thumbFor = l)
            return@withEditor
        }
        maskThumbs.remove(l.id)
        ed.commitMove(l, l.previewDx, l.previewDy, PaintTarget.PIXELS)
        renderNow(null)
        refreshLayers(thumbFor = l)
    }

    // ---------------------------------------------------------------- Auswahl & Freistellen

    fun finishSelection(points: FloatArray) = withEditor { ed ->
        val s = _state.value
        val d = ed.doc
        val sel = when (s.selectionShape) {
            SelectionShape.ELLIPSE -> Selection.ellipse(d.width, d.height, points[0], points[1], points[2], points[3])
            SelectionShape.LASSO -> Selection.polygon(d.width, d.height, points)
            else -> Selection.rect(d.width, d.height, points[0], points[1], points[2], points[3])
        }
        commitSelection(ed, sel, tr("Auswahl"))
    }

    fun setFeather(r: Float) = _state.update { it.copy(featherRadius = r) }
    fun setWandTolerance(t: Int) = _state.update { it.copy(wandTolerance = t) }
    fun setWandContiguous(b: Boolean) = _state.update { it.copy(wandContiguous = b) }

    /** Neue Auswahl mit Weicher Kante und Verknüpfung (Neu/Hinzu/Weg) übernehmen. */
    private fun commitSelection(ed: Editor, selection: Selection, label: String) {
        val s = _state.value
        val sel = if (s.featherRadius > 0f) selection.feathered(s.featherRadius) else selection
        val existing = ed.doc.selection
        ed.setSelection(if (existing != null) existing.combine(sel, s.selectionOp) else sel, label)
        refreshLayers()
    }

    /** Zauberstab an ([x], [y]): aktive Ebene oder (Alle Ebenen) das sichtbare Bild. */
    fun magicWand(x: Float, y: Float) = withEditor { ed ->
        val s = _state.value
        val d = ed.doc
        val px = x.toInt(); val py = y.toInt()
        if (px !in 0 until d.width || py !in 0 until d.height) return@withEditor
        busy(true)
        try {
            val src = if (s.sampleAllLayers) d.newGrid().also { g -> Compositor.renderBands(d, d.bounds) { r, buf -> g.writeRect(r, buf) } }
                else (d.activeLayer as? RasterLayer)?.raster ?: run { message(tr("Zauberstab braucht eine Pixelebene oder „Alle Ebenen“.")); return@withEditor }
            commitSelection(ed, de.photographics.core.selection.MagicWand.select(src, px, py, s.wandTolerance, s.wandContiguous), tr("Zauberstab"))
        } finally { busy(false) }
    }

    /** Objektauswahl im aufgezogenen Rechteck (GrabCut auf dem sichtbaren Bild). */
    fun objectSelect(x0: Float, y0: Float, x1: Float, y1: Float) = withEditor { ed ->
        val d = ed.doc
        val r = IntRect(minOf(x0, x1).toInt(), minOf(y0, y1).toInt(), maxOf(x0, x1).toInt() + 1, maxOf(y0, y1).toInt() + 1)
        busy(true)
        try {
            val sel = de.photographics.core.selection.ObjectSelect.select(d.width, d.height, r, { rr, st -> Compositor.renderArgbSampled(d, rr, st) })
            if (sel.bounds.isEmpty) { message(tr("Kein Objekt im Rechteck gefunden.")); return@withEditor }
            commitSelection(ed, sel, tr("Objektauswahl"))
        } finally { busy(false) }
    }

    fun setGradientType(t: de.photographics.core.editor.GradientType) = _state.update { it.copy(gradientType = t) }
    fun setGradientToTransparent(b: Boolean) = _state.update { it.copy(gradientToTransparent = b) }
    fun setGradientOpacity(v: Float) = _state.update { it.copy(gradientOpacity = v) }

    /** Verlauf von ([x0], [y0]) nach ([x1], [y1]) auf Pixel oder Maske der aktiven Ebene. */
    fun applyGradient(x0: Float, y0: Float, x1: Float, y1: Float) = withEditor { ed ->
        val s = _state.value
        val l = ed.doc.activeLayer
        val target = if (s.paintTarget == PaintTarget.MASK && l?.mask != null) PaintTarget.MASK else PaintTarget.PIXELS
        val c1 = if (s.gradientToTransparent) s.color and 0x00FFFFFF else s.bgColor
        busy(true)
        try {
            val r = ed.fillGradient(target, de.photographics.core.editor.Gradient(s.gradientType, x0, y0, x1, y1, s.color, c1), s.gradientOpacity)
            if (r == null) { message(tr("Verlauf nur auf Pixelebenen oder Masken.")); return@withEditor }
            if (target == PaintTarget.MASK) l?.let { maskThumbs.remove(it.id) }
            renderNow(r)
        } finally { busy(false) }
        refreshLayers(ed.doc.activeLayer)
    }

    /** Lasso-Auswahl unabhängig von der eingestellten Form (z. B. für das Ausbessern-Werkzeug). */
    fun finishLasso(points: FloatArray) = withEditor { ed ->
        val d = ed.doc
        ed.setSelection(Selection.polygon(d.width, d.height, points))
        refreshLayers()
    }

    fun selectAll() = withEditor { it.selectAll(); refreshLayers() }
    fun deselect() = withEditor { it.deselect(); refreshLayers() }
    fun invertSelection() = withEditor { it.invertSelection(); refreshLayers() }
    fun featherSelection(r: Float) = withEditor { it.featherSelection(r); refreshLayers() }
    fun clearSelected() = withEditor { ed -> ed.clearSelection()?.let { renderNow(it) }; refreshLayers(ed.doc.activeLayer) }
    fun fillSelected(background: Boolean = false) = withEditor { ed ->
        val s = _state.value
        ed.fillSelection(if (background) s.bgColor else s.color)?.let { renderNow(it) }; refreshLayers(ed.doc.activeLayer)
    }
    fun layerViaCopy() = withEditor { it.layerViaCopy(); fullRefresh() }

    fun setCropRect(r: IntRect) = _state.update { it.copy(cropRect = r) }

    /** Verhältnis Breite/Höhe der aktuellen Vorgabe (mit Hoch-/Querformat) oder null. */
    private fun cropRatio(s: EditorUiState): Double? {
        val a = s.cropAspect ?: return null
        val r = a.w / a.h
        return if (s.cropPortrait == (r > 1)) 1 / r else r
    }

    /**
     * Rahmen beim Ziehen: neuer Rahmen von [ax],[ay] bis [px],[py] (mit Seitenverhältnis), oder –
     * mit [move] – den bestehenden Rahmen um den Weg verschieben.
     */
    fun cropDrag(ax: Float, ay: Float, px: Float, py: Float, move: Boolean, startRect: IntRect?) {
        val s = _state.value
        val dw = s.docWidth; val dh = s.docHeight
        if (move && startRect != null) {
            var dx = (px - ax).roundToInt(); var dy = (py - ay).roundToInt()
            if (s.cropDeleteCropped && s.cropAngle == 0f) {
                dx = dx.coerceIn(-startRect.left, dw - startRect.right); dy = dy.coerceIn(-startRect.top, dh - startRect.bottom)
            }
            _state.update { it.copy(cropRect = startRect.offset(dx, dy)) }
            return
        }
        var w = kotlin.math.abs(px - ax); var h = kotlin.math.abs(py - ay)
        cropRatio(s)?.let { r -> if (w / maxOf(h, 1e-3f) > r) w = h * r.toFloat() else h = w / r.toFloat() }
        val sx = if (px >= ax) 1 else -1; val sy = if (py >= ay) 1 else -1
        var l = minOf(ax, ax + sx * w); var t = minOf(ay, ay + sy * h)
        var rr = l + w; var b = t + h
        if (s.cropDeleteCropped && s.cropAngle == 0f) {
            // Innerhalb der Arbeitsfläche halten (bei festem Verhältnis proportional verkleinern)
            val k = minOf(1f, if (l < 0) (ax) / maxOf(1e-3f, ax - l) else 1f, if (t < 0) ay / maxOf(1e-3f, ay - t) else 1f,
                if (rr > dw) (dw - ax) / maxOf(1e-3f, rr - ax) else 1f, if (b > dh) (dh - ay) / maxOf(1e-3f, b - ay) else 1f).coerceAtLeast(0f)
            if (k < 1f) { w *= k; h *= k; l = minOf(ax, ax + sx * w); t = minOf(ay, ay + sy * h); rr = l + w; b = t + h }
        }
        _state.update { it.copy(cropRect = IntRect(l.roundToInt(), t.roundToInt(), maxOf(l.roundToInt() + 1, rr.roundToInt()), maxOf(t.roundToInt() + 1, b.roundToInt()))) }
    }

    /** Größtes Rechteck mit der Vorgabe im Rahmen bzw. in der (gedrehten) Arbeitsfläche, mittig. */
    private fun fitCrop(s: EditorUiState): IntRect {
        val (mw, mh) = de.photographics.core.editor.CropMath.maxInscribed(s.docWidth.toDouble(), s.docHeight.toDouble(), s.cropAngle.toDouble())
        var w = mw; var h = mh
        cropRatio(s)?.let { r -> if (w / h > r) w = h * r else h = w / r }
        val cx = s.docWidth / 2.0; val cy = s.docHeight / 2.0
        return IntRect((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt(), (cx + w / 2).roundToInt(), (cy + h / 2).roundToInt())
    }

    fun setCropAspect(a: CropAspect?) {
        _state.update { st ->
            // „Original“: Verhältnis des Dokuments, in dessen Ausrichtung
            val portrait = if (a != null && a.label == tr("Original")) st.docHeight > st.docWidth else st.cropPortrait
            st.copy(cropAspect = a, cropPortrait = portrait, cropTarget = null)
        }
        _state.update { it.copy(cropRect = fitCrop(it)) }
    }

    fun toggleCropOrientation() { _state.update { it.copy(cropPortrait = !it.cropPortrait) }; _state.update { it.copy(cropRect = fitCrop(it)) } }
    fun setCropOverlay(o: CropOverlay) = _state.update { it.copy(cropOverlay = o) }
    fun setCropDeleteCropped(b: Boolean) = _state.update { it.copy(cropDeleteCropped = b) }

    /** Zielgröße: Seitenverhältnis daraus, nach dem Freistellen auf genau diese Pixel umrechnen. */
    fun setCropTarget(t: CropTarget?) {
        _state.update { st ->
            if (t == null) st.copy(cropTarget = null)
            else st.copy(cropTarget = t, cropAspect = CropAspect("${t.width} × ${t.height}", maxOf(t.width, t.height).toDouble(), minOf(t.width, t.height).toDouble()), cropPortrait = t.height > t.width)
        }
        _state.update { it.copy(cropRect = fitCrop(it)) }
    }

    /** Drehung des Bildes beim Freistellen; der Rahmen wird auf den größten Ausschnitt ohne leere Ecken gesetzt. */
    fun setCropAngle(deg: Float) {
        _state.update { it.copy(cropAngle = deg.coerceIn(-45f, 45f)) }
        _state.update { it.copy(cropRect = fitCrop(it)) }
    }

    fun setCropStraighten(on: Boolean) = _state.update { it.copy(cropStraighten = on) }

    /** Perspektivisch freistellen ein/aus: Ecken starten auf dem aktuellen Rahmen. */
    fun setCropPerspective(on: Boolean) = _state.update { st ->
        val r = st.cropRect ?: IntRect.ofSize(st.docWidth, st.docHeight)
        st.copy(
            cropQuad = if (on) listOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom).map { it.toFloat() } else null,
            cropAngle = 0f, cropStraighten = false,
        )
    }

    /** Ecke [i] (0 = oben links … 3 = unten links) des perspektivischen Rahmens setzen; null = alle um ([x], [y]) verschieben ab [start]. */
    fun setCropQuad(q: List<Float>) {
        val d = q.map { it.toDouble() }.toDoubleArray()
        if (de.photographics.core.image.Homography.isConvex(d)) _state.update { it.copy(cropQuad = q) }
    }

    /** Gerade ausrichten: die gezogene Linie wird waagerecht (bzw. senkrecht). */
    fun straighten(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (kotlin.math.hypot(x1 - x0, y1 - y0) < 4f) { setCropStraighten(false); return }
        val deg = de.photographics.core.editor.CropMath.straightenAngle(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble())
        _state.update { it.copy(cropStraighten = false) }
        setCropAngle(deg.toFloat())
    }

    fun applyCrop() = withEditor { ed ->
        val s = _state.value
        val r = s.cropRect ?: return@withEditor
        busy(true)
        try {
            val quad = s.cropQuad
            if (quad != null) {
                val q = quad.map { it.toDouble() }.toDoubleArray()
                fun len(i: Int, j: Int) = kotlin.math.hypot(q[j * 2] - q[i * 2], q[j * 2 + 1] - q[i * 2 + 1])
                // Zielgröße: mittlere Kantenlängen (oder die gewählte Größe)
                val ow = s.cropTarget?.width ?: ((len(0, 1) + len(3, 2)) / 2).roundToInt().coerceAtLeast(1)
                val oh = s.cropTarget?.height ?: ((len(0, 3) + len(1, 2)) / 2).roundToInt().coerceAtLeast(1)
                ed.perspectiveCrop(q, ow, oh)
            } else if (s.cropAngle == 0f) ed.crop(r, s.cropDeleteCropped)
            else ed.cropRotated(r.left + r.width / 2.0, r.top + r.height / 2.0, r.width, r.height, -s.cropAngle.toDouble())
            s.cropTarget?.let { t ->
                if (t.width != ed.doc.width || t.height != ed.doc.height) ed.resizeImage(t.width, t.height, de.photographics.core.image.ResampleMethod.LANCZOS)
                ed.doc.dpi = t.dpi
            }
        } finally { busy(false) }
        _state.update { it.copy(cropAngle = 0f, cropStraighten = false, cropQuad = null, docDpi = ed.doc.dpi) }
        documentGeometryChanged()
    }

    // ---------------------------------------------------------------- Hilfslinien

    fun addGuide(vertical: Boolean, pos: Double) = withEditor { ed ->
        if (pos < 0 || pos > (if (vertical) ed.doc.width else ed.doc.height)) return@withEditor
        ed.setGuides(tr("Neue Hilfslinie"), ed.doc.guides + de.photographics.core.doc.Guide(vertical, pos))
        refreshLayers()
    }

    /** Hilfslinie verschieben; außerhalb der Arbeitsfläche abgelegt wird sie gelöscht (wie in Photoshop). */
    fun moveGuide(index: Int, pos: Double) = withEditor { ed ->
        val g = ed.doc.guides.getOrNull(index) ?: return@withEditor
        val inside = pos >= 0 && pos <= (if (g.vertical) ed.doc.width else ed.doc.height)
        val list = ed.doc.guides.toMutableList()
        if (inside) list[index] = g.copy(position = pos) else list.removeAt(index)
        ed.setGuides(if (inside) tr("Hilfslinie verschieben") else tr("Hilfslinie löschen"), list)
        refreshLayers()
    }

    fun clearGuides() = withEditor { ed -> ed.setGuides(tr("Hilfslinien löschen"), emptyList()); refreshLayers() }

    /** Hilfslinien-Layout: Spalten und Zeilen mit Rand (Photoshop: Neues Hilfslinienlayout). */
    fun guideLayout(columns: Int, rows: Int, margin: Double, gutter: Double) = withEditor { ed ->
        val w = ed.doc.width.toDouble(); val h = ed.doc.height.toDouble()
        val list = mutableListOf<de.photographics.core.doc.Guide>()
        fun axis(vertical: Boolean, n: Int, size: Double) {
            if (n < 1) return
            if (margin > 0) { list += de.photographics.core.doc.Guide(vertical, margin); list += de.photographics.core.doc.Guide(vertical, size - margin) }
            val inner = size - 2 * margin
            val cell = (inner - gutter * (n - 1)) / n
            for (i in 1 until n) {
                val x = margin + i * cell + (i - 1) * gutter
                list += de.photographics.core.doc.Guide(vertical, x)
                if (gutter > 0) list += de.photographics.core.doc.Guide(vertical, x + gutter)
            }
        }
        axis(true, columns, w); axis(false, rows, h)
        ed.setGuides(tr("Hilfslinienlayout"), ed.doc.guides + list.filter { it.position in 0.0..(if (it.vertical) w else h) })
        refreshLayers()
    }

    /** Einrastziel für eine gezogene Hilfslinie (Ränder, Mitte, Ebenenkanten). */
    fun snapGuide(vertical: Boolean, pos: Double, tolerance: Double): Double {
        val doc = editor?.doc ?: return pos
        if (!settings.value.snap) return pos
        val t = guideTargets ?: de.photographics.core.editor.Snap.targets(doc, null, guides = false).also { guideTargets = it }
        return de.photographics.core.editor.Snap.snapValue(pos, if (vertical) t.xs else t.ys, tolerance)
    }
    private var guideTargets: de.photographics.core.editor.Snap.Targets? = null
    fun endGuideDrag() { guideTargets = null }

    fun cancelCrop() = _state.update { it.copy(tool = Tool.MOVE, cropRect = null, cropAngle = 0f, cropStraighten = false, cropQuad = null) }

    /** Bild um einen beliebigen Winkel drehen (Arbeitsfläche wächst, Ecken der Hintergrundebene in Hintergrundfarbe). */
    fun rotateArbitrary(deg: Float) = withEditor { ed ->
        busy(true)
        try { ed.rotateArbitrary(deg.toDouble(), _state.value.bgColor) } finally { busy(false) }
        documentGeometryChanged()
    }

    fun rotate(clockwise: Boolean) = withEditor { ed -> busy(true); ed.rotate(clockwise); documentGeometryChanged(); busy(false) }
    fun flip(horizontal: Boolean) = withEditor { ed -> busy(true); ed.flip(horizontal); fullRefresh(); busy(false) }
    fun resizeImage(w: Int, h: Int, method: ResampleMethod, useAi: Boolean, dpi: Float) = withEditor { ed ->
        busy(true)
        try {
            val upscaler = if (useAi) {
                val m = ai.firstReady(AiPurpose.UPSCALE, _state.value.aiUpscaleModel)
                if (m == null) { _state.update { it.copy(requestAiModels = AiPurpose.UPSCALE) }; return@withEditor }
                _state.update { it.copy(progress = 0f, progressLabel = tr("KI-Hochskalierung ({0})", m.title)) }
                OnnxUpscaler(ai, m) { p -> _state.update { it.copy(progress = p) } }
            } else null
            ed.resizeImage(w, h, method, upscaler)
            ed.doc.dpi = dpi
            documentGeometryChanged()
        } finally {
            busy(false)
            _state.update { it.copy(progress = null, progressLabel = null, docDpi = editor?.doc?.dpi ?: 72f) }
        }
    }

    fun resizeCanvas(w: Int, h: Int, ax: Float, ay: Float, extension: Int? = null) = withEditor { ed -> ed.resizeCanvas(w, h, ax, ay, extension); documentGeometryChanged() }
    fun flatten() = withEditor { ed -> ed.flatten(); fullRefresh() }

    // ---------------------------------------------------------------- Ebenen

    private fun layerById(ed: Editor, id: Int) = ed.doc.findLayer(id)

    fun selectLayer(id: Int) = withEditor { ed -> ed.setActive(layerById(ed, id)); refreshLayers() }
    fun addLayer() = withEditor { it.addLayer(); refreshLayers() }
    fun addGroup() = withEditor { it.addGroup(); refreshLayers() }
    fun groupActive() = withEditor { it.groupActive(); fullRefresh() }
    fun duplicateLayer() = withEditor { it.duplicateLayer(); fullRefresh() }
    fun deleteLayer() = withEditor { it.deleteLayer(); fullRefresh() }
    fun mergeDown() = withEditor { ed ->
        if (!ed.mergeDown()) message(tr("Darunter liegt keine Pixelebene.")) else fullRefresh()
    }

    fun moveLayer(id: Int, up: Boolean) = withEditor { ed ->
        layerById(ed, id)?.let { ed.moveLayer(it, up) }
        fullRefresh()
    }

    fun toggleVisibility(id: Int) = withEditor { ed ->
        val l = layerById(ed, id) ?: return@withEditor
        ed.setProps(l, if (l.visible) tr("Ebene ausblenden") else tr("Ebene einblenden")) { visible = !visible }
        renderNow(null); refreshLayers()
    }

    fun toggleExpanded(id: Int) = withEditor { ed ->
        (layerById(ed, id) as? GroupLayer)?.let { it.expanded = !it.expanded }
        refreshLayers()
    }

    fun renameLayer(id: Int, name: String) = withEditor { ed ->
        layerById(ed, id)?.let { l -> ed.setProps(l, tr("Ebene umbenennen")) { this.name = name } }
        refreshLayers()
    }

    fun setBlendMode(id: Int, mode: BlendMode) = withEditor { ed ->
        layerById(ed, id)?.let { l -> ed.setProps(l, tr("Mischmodus")) { blendMode = mode } }
        renderNow(null); refreshLayers()
    }

    fun toggleClipping(id: Int) = withEditor { ed ->
        val l = layerById(ed, id) ?: return@withEditor
        if (!ed.setClipping(l, !l.clipped)) message(tr("Die unterste Ebene einer Gruppe kann keine Schnittmaske sein."))
        renderNow(null); refreshLayers()
    }

    /** Strg+Alt+G: Schnittmaske für die aktive Ebene erstellen bzw. zurückwandeln. */
    fun toggleClippingActive() = withEditor { ed -> ed.doc.activeLayer?.let { toggleClipping(it.id) } }

    fun toggleMaskLink(id: Int) = withEditor { ed ->
        layerById(ed, id)?.let { ed.toggleMaskLink(it) }
        refreshLayers()
    }

    fun toggleLockTransparency(id: Int) = withEditor { ed ->
        layerById(ed, id)?.let { l -> ed.setProps(l, tr("Transparenz fixieren")) { lockTransparency = !lockTransparency } }
        refreshLayers()
    }

    /** Live-Änderung von Deckkraft/Fläche während ein Regler gezogen wird. */
    fun setOpacityLive(id: Int, opacity: Float? = null, fill: Float? = null) = withEditor { ed ->
        val l = layerById(ed, id) ?: return@withEditor
        if (propEditLayer !== l) {
            propEditLayer?.let { prev -> propEditBefore?.let { ed.commitProps(prev, it) } }
            propEditLayer = l; propEditBefore = l.props()
        }
        opacity?.let { l.opacity = it }
        fill?.let { l.fillOpacity = it }
        requestRender(null, live = true, layer = l)
        refreshLayers()
    }

    fun endOpacityEdit() = withEditor { ed ->
        val l = propEditLayer ?: return@withEditor
        propEditBefore?.let { ed.commitProps(l, it, tr("Deckkraft")) }
        propEditLayer = null; propEditBefore = null
        refreshLayers()
    }

    fun addMask(fromSelection: Boolean) = withEditor { ed ->
        ed.addMask(fromSelection)
        _state.update { it.copy(paintTarget = PaintTarget.MASK) }
        fullRefresh()
    }

    fun deleteMask() = withEditor { it.deleteMask(); fullRefresh() }
    fun applyMask() = withEditor { it.applyMask(); fullRefresh() }

    fun toggleMaskEnabled(id: Int) = withEditor { ed ->
        layerById(ed, id)?.let { l -> if (l.mask != null) ed.setProps(l, tr("Maske aktivieren/deaktivieren")) { mask?.enabled = !(mask?.enabled ?: true) } }
        renderNow(null); refreshLayers()
    }

    // ---------------------------------------------------------------- Filter mit Vorschau

    fun startFilter(label: String) = withEditor { ed ->
        if (ed.doc.activeLayer !is PixelLayer) {
            message(tr("Filter benötigen eine Pixelebene (Text/Smartobjekt ggf. rastern oder eine Einstellungsebene verwenden)."))
            return@withEditor
        }
        filterTx = null
        _state.update { it.copy(filterActive = label) }
    }

    fun previewFilter(filter: ImageFilter) {
        filterJob?.cancel()
        filterJob = withEditor { ed ->
            filterTx?.let { tx -> tx.rollback() }
            val res = ed.previewFilter(filter) ?: return@withEditor
            val dirty = filterRegion.union(res.second)
            filterTx = res.first
            filterRegion = res.second
            renderNow(dirty)
        }
    }

    fun applyFilter(label: String) = withEditor { ed ->
        filterJob?.join()
        filterTx?.let { ed.commitFilter(it, label) }
        filterTx = null; filterRegion = IntRect.EMPTY
        _state.update { it.copy(filterActive = null) }
        refreshLayers(thumbFor = ed.doc.activeLayer)
    }

    fun cancelFilter() = withEditor {
        filterJob?.join()
        filterTx?.rollback()
        filterTx = null
        renderNow(filterRegion)
        filterRegion = IntRect.EMPTY
        _state.update { it.copy(filterActive = null) }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterComponentCallbacks(memoryCallbacks)
        engine.close()
    }

    // ---------------------------------------------------------------- Retusche-Befehle

    fun contentAwareFill() = withEditor { ed ->
        if (ed.doc.selection == null) { message(tr("Zuerst einen Bereich auswählen.")); return@withEditor }
        busy(true)
        try { ed.contentAwareFill()?.let { renderNow(it) } ?: message(tr("Nur auf Pixelebenen möglich.")) } finally { busy(false) }
        refreshLayers(ed.doc.activeLayer)
    }

    fun aiFillSelection() = withEditor { ed ->
        if (ed.doc.selection == null) { message(tr("Zuerst einen Bereich auswählen.")); return@withEditor }
        val m = ai.firstReady(AiPurpose.INPAINT, _state.value.aiInpaintModel)
            ?: run { _state.update { it.copy(requestAiModels = AiPurpose.INPAINT) }; return@withEditor }
        busy(true)
        try { ed.contentAwareFill(OnnxInpainter(ai, m))?.let { renderNow(it) } } finally { busy(false) }
        refreshLayers(ed.doc.activeLayer)
    }

    fun patchSelection(dx: Float, dy: Float) = withEditor { ed ->
        if (ed.doc.selection == null) { message(tr("Ausbessern: zuerst den Fehler mit der Auswahl umranden.")); return@withEditor }
        busy(true)
        try { ed.patchSelection(dx.roundToInt(), dy.roundToInt())?.let { renderNow(it) } } finally { busy(false) }
        refreshLayers(ed.doc.activeLayer)
    }

    fun redEye(x: Float, y: Float) = withEditor { ed ->
        val r = (_state.value.brush.size / 2).roundToInt().coerceAtLeast(4)
        val dirty = ed.redEye(x.roundToInt(), y.roundToInt(), r)
        if (dirty == null) message(tr("Keine roten Pupillen im Pinselkreis gefunden.")) else { renderNow(dirty); refreshLayers(ed.doc.activeLayer) }
    }

    /** Aktive Ebene mit Pfeiltasten verschieben. */
    fun nudge(dx: Int, dy: Int) = withEditor { ed ->
        val l = ed.doc.activeLayer as? RasterLayer ?: return@withEditor
        ed.commitMove(l, dx, dy, _state.value.paintTarget)
        maskThumbs.remove(l.id)
        renderNow(null); refreshLayers(l)
    }

    // ---------------------------------------------------------------- KI-Modelle

    fun downloadModel(m: AiModel) { viewModelScope.launch { ai.download(m) } }
    fun deleteModel(m: AiModel) = ai.delete(m)
    fun clearAiRequest() = _state.update { it.copy(requestAiModels = null) }
    fun setAiModel(m: AiModel) = _state.update {
        if (m.purpose == AiPurpose.INPAINT) it.copy(aiInpaintModel = m) else it.copy(aiUpscaleModel = m)
    }

    // ---------------------------------------------------------------- Einstellungsebenen

    fun addAdjustmentLayer(type: AdjustmentType) = withEditor { ed ->
        val l = ed.addAdjustmentLayer(type.createDefault())
        adjustBefore = l.adjustment
        _state.update { it.copy(adjustEdit = AdjustEdit(l.id, l.adjustment, histogramFor(ed, l))) }
        renderNow(null); refreshLayers()
    }

    /**
     * Screenshots (tools/screenshots.sh): Beispieldokument öffnen, ohne es in „Zuletzt verwendet“
     * einzutragen; danach Werkzeug wählen bzw. das Panel der aktiven Einstellungsebene öffnen.
     */
    fun openDemo(file: java.io.File, tool: String?, panel: String?) {
        open(Uri.fromFile(file), remember = false)
        onEngine {
            Tool.entries.firstOrNull { it.name == tool }?.let { t -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { selectTool(t) } }
            if (panel == "adjust") (editor?.doc?.activeLayer as? AdjustmentLayer)?.let { openAdjustment(it.id) }
        }
    }

    fun openAdjustment(id: Int) = withEditor { ed ->
        val l = ed.doc.findLayer(id) as? AdjustmentLayer ?: return@withEditor
        ed.setActive(l)
        adjustBefore = l.adjustment
        _state.update { it.copy(adjustEdit = AdjustEdit(l.id, l.adjustment, histogramFor(ed, l))) }
        refreshLayers()
    }

    private fun histogramFor(ed: Editor, l: AdjustmentLayer): IntArray? =
        if (l.adjustment is de.photographics.core.adjust.CurvesAdj) de.photographics.core.adjust.Histogram.below(ed.doc, l) else null

    private var adjustJob: Job? = null

    fun previewAdjustment(a: Adjustment) {
        val e = _state.value.adjustEdit ?: return
        _state.update { it.copy(adjustEdit = e.copy(adjustment = a)) }
        adjustJob?.cancel()
        adjustJob = withEditor { ed ->
            val l = ed.doc.findLayer(e.layerId) as? AdjustmentLayer ?: return@withEditor
            ed.previewAdjustment(l, a)
            renderLive(null, l)
        }
    }

    fun closeAdjustment() = withEditor { ed ->
        val e = _state.value.adjustEdit ?: return@withEditor
        adjustJob?.join()
        val l = ed.doc.findLayer(e.layerId) as? AdjustmentLayer
        val before = adjustBefore
        if (l != null && before != null) ed.commitAdjustment(l, before)
        adjustBefore = null
        _state.update { it.copy(adjustEdit = null) }
        refreshLayers()
    }

    // ---------------------------------------------------------------- Farbtabellen (LUT)

    private val lutDir get() = java.io.File(getApplication<Application>().filesDir, "luts")

    /** Eigene LUT-Dateien (im App-Speicher abgelegt, bleiben erhalten). */
    private fun loadLutLibrary() = onEngine {
        val list = (lutDir.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }.mapNotNull { f ->
            runCatching { de.photographics.core.adjust.ColorLookupAdj.fromFile(f.readBytes(), f.name) }.getOrNull()
        }
        _state.update { it.copy(lutLibrary = list) }
    }

    /** LUT-Datei laden, in die Bibliothek übernehmen und in der offenen Farbtabelle verwenden. */
    fun importLut(uri: Uri) = onEngine {
        val cr = getApplication<Application>().contentResolver
        val name = ImageIO.displayName(cr, uri)
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@onEngine
        val adj = try {
            de.photographics.core.adjust.ColorLookupAdj.fromFile(bytes, name)
        } catch (e: IllegalArgumentException) {
            message(tr("LUT konnte nicht gelesen werden: {0}", e.message)); return@onEngine
        }
        lutDir.mkdirs()
        java.io.File(lutDir, name.replace(Regex("[/\\\\]"), "_")).writeBytes(bytes)
        _state.update { s -> s.copy(lutLibrary = (s.lutLibrary.filter { it.lutName != adj.lutName } + adj).sortedBy { it.lutName.lowercase() }) }
        withContext(kotlinx.coroutines.Dispatchers.Main) { previewAdjustment(adj) }
        message(tr("LUT „{0}“ geladen ({1}³)", adj.lutName, adj.lut?.size))
    }

    /** Wirkung der sichtbaren Einstellungsebenen als .cube-Datei speichern. */
    fun exportLut(uri: Uri) = withEditor { ed ->
        val title = _state.value.fileName.substringBeforeLast('.')
        val lut = de.photographics.core.color.LutExport.fromAdjustments(ed.doc, 33, title)
            ?: run { message(tr("Keine sichtbaren Einstellungsebenen – nichts zu exportieren.")); return@withEditor }
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(lut.toCube()) }
        message(tr("Farbtabelle exportiert (33³, .cube)."))
    }

    // ---------------------------------------------------------------- Text

    /** Textebene aus der Ebenenliste zum Bearbeiten öffnen. */
    fun editTextLayer(id: Int) = withEditor { ed ->
        val l = ed.doc.findLayer(id) as? TextLayer ?: return@withEditor
        ed.setActive(l)
        textBefore = l.style
        _state.update { it.copy(textEdit = TextEdit(l.id, l.style), tool = Tool.TEXT) }
        refreshLayers()
    }

    /** Tippen mit dem Textwerkzeug: vorhandenen Text bearbeiten oder neuen anlegen. */
    fun textTap(x: Float, y: Float) = withEditor { ed ->
        if (_state.value.textEdit != null) { commitTextEditInternal(ed); return@withEditor }
        val hit = ed.doc.allLayers().asReversed().firstOrNull { l ->
            l is TextLayer && l.visible && l.bounds().let { b -> b.inset(-8).contains(x.toInt(), y.toInt()) }
        } as? TextLayer
        val layer = hit ?: run {
            // Startgröße passend zum Dokument (≈ 1/12 der kürzeren Seite), solange keine eigene gewählt wurde
            val base = if (textSizeChosen) lastTextStyle else lastTextStyle.copy(sizePx = (minOf(ed.doc.width, ed.doc.height) / 12f).coerceIn(24f, 600f))
            val st = base.copy(color = _state.value.color, text = tr("Text"))
            ed.addTextLayer(st, x.toDouble(), y.toDouble())
        }
        ed.setActive(layer)
        textBefore = layer.style
        _state.update { it.copy(textEdit = TextEdit(layer.id, layer.style)) }
        renderNow(null); refreshLayers(layer)
    }

    private var lastTextStyle = TextStyle(sizePx = 96f)
    private var textSizeChosen = false

    fun updateTextLive(style: TextStyle) {
        val e = _state.value.textEdit ?: return
        _state.update { it.copy(textEdit = e.copy(style = style)) }
        withEditor { ed ->
            val l = ed.doc.findLayer(e.layerId) as? TextLayer ?: return@withEditor
            val old = l.bounds()
            l.style = style
            l.unchangedFromPsd = false
            requestRender(old.union(l.bounds()).inset(-4))
        }
    }

    fun commitTextEdit() = withEditor { ed -> commitTextEditInternal(ed) }

    private fun commitTextEditInternal(ed: Editor) {
        val e = _state.value.textEdit ?: return
        val l = ed.doc.findLayer(e.layerId) as? TextLayer
        val before = textBefore
        if (l != null && before != null) {
            val after = l.style
            l.style = before
            ed.updateText(l, after)
            if (after.sizePx != before.sizePx) textSizeChosen = true
            lastTextStyle = after
        }
        textBefore = null
        _state.update { it.copy(textEdit = null) }
        renderNow(null); refreshLayers(l)
    }

    fun cancelTextEdit() = withEditor { ed ->
        val e = _state.value.textEdit ?: return@withEditor
        val l = ed.doc.findLayer(e.layerId) as? TextLayer
        textBefore?.let { b -> l?.style = b }
        textBefore = null
        _state.update { it.copy(textEdit = null) }
        renderNow(null); refreshLayers(l)
    }

    // ---------------------------------------------------------------- Frei transformieren

    /** Startet das Transformieren der aktiven Ebene (Pixelebenen werden zum Smartobjekt). */
    fun beginTransform() = withEditor { ed ->
        var l = ed.doc.activeLayer
        if (l is PixelLayer) {
            l = ed.convertToSmartObject(l) ?: run { message(tr("Die Ebene ist leer.")); return@withEditor }
            message(tr("Ebene in Smartobjekt umgewandelt – Transformieren ohne Qualitätsverlust."))
        }
        val box = when (l) {
            is SmartLayer -> TransformBox(l.id, l.source.width.toDouble(), l.source.height.toDouble(), l.transform, l.warp)
            is TextLayer -> AndroidTextRasterizer.measure(l.style).let { (w, h) -> TransformBox(l.id, w.toDouble(), h.toDouble(), l.transform) }
            else -> { message(tr("Diese Ebene kann nicht transformiert werden.")); _state.update { it.copy(tool = Tool.MOVE) }; return@withEditor }
        }
        transformBefore = box.affine
        transformBeforeGeom = ed.geometryOf(l)
        _state.update { it.copy(transform = box, transformMode = if (l is TextLayer) TransformMode.FREE else it.transformMode) }
        refreshLayers()
    }

    fun setTransformMode(m: TransformMode) = withEditor { ed ->
        val box = _state.value.transform
        if (box != null && m != TransformMode.FREE && ed.doc.findLayer(box.layerId) is TextLayer) {
            message(tr("Text lässt sich nur frei transformieren – für Verzerren zuerst in ein Smartobjekt umwandeln."))
            return@withEditor
        }
        _state.update { it.copy(transformMode = m) }
    }

    /** Verzerren/Perspektive: neue Ecken als Vorschau (direkt aus der Quelle gerendert). */
    fun transformPreviewQuad(q: DoubleArray) {
        val box = _state.value.transform ?: return
        if (!de.photographics.core.image.Homography.isConvex(q)) return
        val old = box.corners()
        _state.update { it.copy(transform = box.copy(quad = q.copyOf())) }
        transformJob?.cancel()
        transformJob = withEditor { ed ->
            val l = ed.doc.findLayer(box.layerId) as? SmartLayer ?: return@withEditor
            fun bounds(c: DoubleArray) = IntRect(
                kotlin.math.floor(minOf(c[0], c[2], c[4], c[6])).toInt(), kotlin.math.floor(minOf(c[1], c[3], c[5], c[7])).toInt(),
                kotlin.math.ceil(maxOf(c[0], c[2], c[4], c[6])).toInt(), kotlin.math.ceil(maxOf(c[1], c[3], c[5], c[7])).toInt(),
            )
            ed.previewWarp(l, q)
            renderLive(bounds(old).union(bounds(q)).inset(-2), l)
        }
    }

    private var transformJob: Job? = null

    fun transformPreview(t: Affine) {
        val box = _state.value.transform ?: return
        _state.update { it.copy(transform = box.copy(affine = t)) }
        transformJob?.cancel()
        transformJob = withEditor { ed ->
            val l = ed.doc.findLayer(box.layerId) ?: return@withEditor
            // Geänderter Bereich aus den Umrissen (das volle Raster wird während der Vorschau nicht berechnet)
            fun outline(m: Affine) = m.bounds(box.w, box.h).let { b ->
                IntRect(kotlin.math.floor(b[0]).toInt(), kotlin.math.floor(b[1]).toInt(), kotlin.math.ceil(b[2]).toInt(), kotlin.math.ceil(b[3]).toInt())
            }
            val old = outline(ed.transformOf(l))
            ed.previewTransform(l, t)
            renderLive(old.union(outline(t)).inset(-2), l)
        }
    }

    fun applyTransform() = withEditor { ed ->
        val box = _state.value.transform ?: return@withEditor
        transformJob?.join()
        val l = ed.doc.findLayer(box.layerId)
        val before = transformBefore
        val beforeGeom = transformBeforeGeom
        if (l is SmartLayer && beforeGeom != null) {
            ed.commitGeometry(l, beforeGeom, if (box.quad != null) Editor.Geometry(beforeGeom.transform, box.quad) else Editor.Geometry(box.affine, null))
        } else if (l != null && before != null) ed.commitTransform(l, before, box.affine)
        transformBefore = null; transformBeforeGeom = null
        _state.update { it.copy(transform = null, tool = Tool.MOVE) }
        renderNow(null); refreshLayers(l)
    }

    fun cancelTransform() = withEditor { ed ->
        val box = _state.value.transform ?: return@withEditor
        transformJob?.join()
        val l = ed.doc.findLayer(box.layerId)
        val before = transformBefore
        val beforeGeom = transformBeforeGeom
        if (l is SmartLayer && beforeGeom != null) ed.commitGeometry(l, beforeGeom, beforeGeom)
        else if (l != null && before != null) ed.commitTransform(l, before, before)
        transformBefore = null; transformBeforeGeom = null
        _state.update { it.copy(transform = null, tool = Tool.MOVE) }
        renderNow(null); refreshLayers(l)
    }

    fun convertToSmart(id: Int) = withEditor { ed ->
        val l = ed.doc.findLayer(id) as? PixelLayer ?: return@withEditor
        ed.convertToSmartObject(l) ?: message(tr("Die Ebene ist leer."))
        refreshLayers()
    }

    fun rasterizeLayer(id: Int) = withEditor { ed ->
        ed.doc.findLayer(id)?.let { ed.rasterize(it) }
        fullRefresh()
    }

    // ---------------------------------------------------------------- Drucken & Export

    /** Zusammengesetztes Bild für den Druck (max. ~4000 px Kantenlänge). */
    fun renderForPrint(onReady: (Bitmap) -> Unit) = withEditor { ed ->
        val d = ed.doc
        val f = minOf(1f, 4000f / max(d.width, d.height))
        val bmp = if (f >= 1f) ImageIO.renderBitmap(d, flattenOnWhite = true) else {
            val w = max(1, (d.width * f).roundToInt()); val h = max(1, (d.height * f).roundToInt())
            val small = Resample.resizeGrid(Compositor.renderGrid(d), w, h, ResampleMethod.LANCZOS)
            val out = IntArray(w * h); small.readRect(IntRect(0, 0, w, h), out)
            // Drucken: Druckdienste erwarten sRGB
            val conv = de.photographics.core.color.ColorTransform.rgb(d.colorProfile, IccProfiles.sRGB, de.photographics.core.color.RenderingIntent.RELATIVE, true)
            for (i in out.indices) {
                val c = de.photographics.core.image.Colors.srcOver(0xFFFFFFFF.toInt(), out[i])
                out[i] = if (conv != null) (c and 0xFF000000.toInt()) or conv.map(c and 0xFFFFFF) else c
            }
            Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }
        withContext(kotlinx.coroutines.Dispatchers.Main) { onReady(bmp) }
    }

    // Erst hier, nach allen Feldern: Kotlin initialisiert in Deklarationsreihenfolge, die
    // Einstellungsspeicher für Pinsel und Farbfelder stehen weiter unten als der erste init-Block.
    init {
        loadBrushPresets()
        loadPalettes()
    }

}
