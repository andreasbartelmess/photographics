package de.photographics.core.tools

import de.photographics.core.doc.Document
import de.photographics.core.doc.Layer
import de.photographics.core.history.TilePatch
import de.photographics.core.history.TileTransaction
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Was ein Strich bearbeitet: die Pixel oder die Maske der aktiven Ebene. */
enum class PaintTarget { PIXELS, MASK }

/**
 * Laufzeitkontext eines Pinselstrichs. Jeder Pixel wird immer aus seinem Originalzustand und der
 * akkumulierten Deckung neu berechnet – so begrenzt "Deckkraft" den Strich wie in Photoshop,
 * während "Fluss" den Auftrag pro Abdruck steuert.
 */
class StrokeContext(
    val doc: Document,
    val layer: Layer,
    val grid: TileGrid,
    val target: PaintTarget,
    val settings: BrushSettings,
) {
    val tx = TileTransaction(grid)
    /** Deckung pro Pixel, 0..65535. */
    val coverage = TileGrid(grid.width, grid.height, 0, area = grid.extent) // gleiche Kachelaufteilung wie das Ziel
    var touched: IntRect = IntRect.EMPTY
        private set
    private val selection = doc.selection

    fun original(x: Int, y: Int) = tx.original(x, y)

    fun selectionFactor(x: Int, y: Int): Float = selection?.let { it.at(x, y) / 255f } ?: 1f

    /** Auswahlmaske (oder null = alles) für schnellen kachelweisen Zugriff. */
    val selectionMask: TileGrid? get() = selection?.mask

    internal fun markTouched(r: IntRect) { touched = touched.union(r) }

    /** Farbdynamik: Farbe je Pixel, gemischt aus den Abdrücken des Strichs (sonst null). */
    internal var colorGrid: TileGrid? = null
    /** Farbe des gerade berechneten Pixels bei Farbdynamik (für [PaintTool.apply]). */
    var pixelColor = 0
        internal set
    val colorDynamics: Boolean get() = colorGrid != null
}

/** Ein Werkzeug, das pro Pixel aus Original und Deckung den neuen Wert berechnet. */
abstract class PaintTool {
    abstract val label: String

    /** Neuer Wert für (x, y). [amount] = Deckung * Deckkraft * Auswahl, 0..1. */
    abstract fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int

    open fun begin(ctx: StrokeContext) {}

    /** Nachbearbeitung am Strichende; liefert zusätzlich geänderten Bereich. */
    open fun finish(ctx: StrokeContext): IntRect = IntRect.EMPTY

    /** Ob das Werkzeug Masken bearbeiten kann. */
    open val supportsMask: Boolean get() = false

    /** true: das Werkzeug verarbeitet ganze Abdrücke selbst ([dab]) statt Pixel einzeln. */
    open val customDab: Boolean get() = false

    /** Eigener Abdruck; [shape] liefert die Deckung 0..1 für einen relativen Abstand zum Mittelpunkt. */
    open fun dab(ctx: StrokeContext, cx: Float, cy: Float, radius: Float, shape: (Float) -> Float) {}

    /** Vorder-/Hintergrundfarbe für die Farbdynamik; null = Werkzeug malt keine Farbe. */
    open val dynamicsColors: Pair<Int, Int>? get() = null

    /** Harte Kante ohne Glättung (Buntstift): ein Pixel ist ganz oder gar nicht getroffen. */
    open val aliased: Boolean get() = false
}

/** Führt einen Strich aus: Interpolation der Eingabepunkte, Abdrücke, Undo-Patch. */
class Stroke(private val ctx: StrokeContext, private val tool: PaintTool, seed: Long = System.nanoTime()) {
    private var lastX = 0f
    private var lastY = 0f
    private var started = false
    /** Restweg bis zum nächsten Abdruck. */
    private var carry = 0f
    private val rnd = java.util.Random(seed)
    /** Strichrichtung (Bogenmaß) für Winkel-Steuerung und Streuung. */
    private var direction = 0f
    /** Anzahl der Abdruckpositionen (für „Verblassen“). */
    private var dabIndex = 0
    private val colors = tool.dynamicsColors

    init {
        tool.begin(ctx)
        if (colors != null && ctx.settings.hasColorDynamics && ctx.target == PaintTarget.PIXELS) {
            ctx.colorGrid = TileGrid(ctx.grid.width, ctx.grid.height, 0, area = ctx.grid.extent)
        }
    }

    /** Airbrush: an der letzten Position weiter Farbe auftragen (vom Aufrufer zeitgesteuert). */
    fun airbrushTick(): IntRect = if (started && ctx.settings.airbrush) dab(lastX, lastY, rawPressure) else IntRect.EMPTY

    private var rawX = 0f
    private var rawY = 0f
    private var rawPressure = 1f

    /** Fügt einen Punkt hinzu und gibt den neu zu zeichnenden Bereich zurück. */
    fun addPoint(x: Float, y: Float, pressure: Float = 1f): IntRect {
        rawX = x; rawY = y; rawPressure = pressure
        if (!started) {
            started = true
            lastX = x; lastY = y
            return dab(x, y, pressure)
        }
        // Glättung: nur einen Teil des Weges zum neuen Punkt gehen
        val k = 1f - ctx.settings.smoothing.coerceIn(0f, 0.95f)
        return segmentTo(lastX + (x - lastX) * k, lastY + (y - lastY) * k, pressure)
    }

    private fun segmentTo(x: Float, y: Float, pressure: Float): IntRect {
        val step = max(1f, ctx.settings.size * ctx.settings.spacing)
        val dist = hypot(x - lastX, y - lastY)
        if (dist > 0.01f) direction = kotlin.math.atan2(y - lastY, x - lastX)
        var dirty = IntRect.EMPTY
        var t = step - carry
        while (t <= dist) {
            val k = t / dist
            dirty = dirty.union(dab(lastX + (x - lastX) * k, lastY + (y - lastY) * k, pressure))
            t += step
        }
        carry = dist - (t - step)
        lastX = x; lastY = y
        return dirty
    }

    /** Geglätteten Strich bis zum letzten echten Zeigerpunkt nachziehen. */
    fun catchUp(): IntRect =
        if (started && (lastX != rawX || lastY != rawY)) segmentTo(rawX, rawY, rawPressure) else IntRect.EMPTY

    fun finish(): Pair<TilePatch?, IntRect> {
        val caught = catchUp()
        val extra = tool.finish(ctx).union(caught)
        val dirty = ctx.touched.union(extra)
        return ctx.tx.commit(tool.label) to dirty
    }

    fun cancel(): IntRect {
        val r = ctx.touched
        ctx.tx.rollback()
        return r
    }

    private fun control(c: DynControl, pressure: Float, min: Float): Float = when (c) {
        DynControl.OFF -> 1f
        DynControl.PRESSURE -> min + (1f - min) * pressure.coerceIn(0f, 1f)
        DynControl.FADE -> 1f - (1f - min) * minOf(1f, dabIndex.toFloat() / ctx.settings.fadeSteps)
    }

    /** Abdruck(e) an einer Position: Form-, Streu-, Übertragungs- und Farbdynamik. */
    private fun dab(cx: Float, cy: Float, pressure: Float): IntRect {
        val s = ctx.settings
        var n = s.count
        if (s.countJitter > 0f) n = maxOf(1, n - (rnd.nextFloat() * s.countJitter * n).toInt())
        var dirty = IntRect.EMPTY
        repeat(n) {
            val sizeF = control(s.sizeControl, pressure, s.minSize) * (1f - s.sizeJitter * rnd.nextFloat())
            val radius = max(0.5f, s.size * 0.5f * sizeF)
            var ang = Math.toRadians(s.angle.toDouble()).toFloat()
            if (s.angleControl == AngleControl.DIRECTION) ang += direction
            if (s.angleJitter > 0f) ang += (rnd.nextFloat() - 0.5f) * 2f * Math.PI.toFloat() * s.angleJitter
            val round = (s.roundness * (1f - s.roundnessJitter * (1f - s.minRoundness) * rnd.nextFloat())).coerceIn(0.05f, 1f)
            var x = cx; var y = cy
            if (s.scatter > 0f) {
                if (s.scatterBothAxes) {
                    x += (rnd.nextFloat() * 2f - 1f) * s.scatter * s.size; y += (rnd.nextFloat() * 2f - 1f) * s.scatter * s.size
                } else {
                    val d = (rnd.nextFloat() * 2f - 1f) * s.scatter * s.size
                    x += -kotlin.math.sin(direction) * d; y += kotlin.math.cos(direction) * d
                }
            }
            val flow = (s.flow * control(s.flowControl, pressure, 0f) * (1f - s.flowJitter * rnd.nextFloat())).coerceIn(0f, 1f)
            val cap = (control(s.opacityControl, pressure, 0f) * (1f - s.opacityJitter * rnd.nextFloat())).coerceIn(0f, 1f)
            val color = colors?.let { if (ctx.colorDynamics) dynamicColor(it.first, it.second) else it.first } ?: 0
            dirty = dirty.union(stamp(x, y, radius, ang, round, flow, cap, color))
        }
        dabIndex++
        return dirty
    }

    /** Farbe eines Abdrucks: Mischung Vorder-/Hintergrund, Farbton/Sättigung/Helligkeit gestreut. */
    private fun dynamicColor(fg: Int, bg: Int): Int {
        val s = ctx.settings
        val t = s.fgBgJitter * rnd.nextFloat()
        val r = Colors.r(fg) + (Colors.r(bg) - Colors.r(fg)) * t
        val g = Colors.g(fg) + (Colors.g(bg) - Colors.g(fg)) * t
        val b = Colors.b(fg) + (Colors.b(bg) - Colors.b(fg)) * t
        val hsb = Colors.rgbToHsb(r / 255f, g / 255f, b / 255f)
        hsb[0] = (hsb[0] + (rnd.nextFloat() - 0.5f) * s.hueJitter + 1f) % 1f
        hsb[1] = (hsb[1] * (1f - s.saturationJitter * rnd.nextFloat())).coerceIn(0f, 1f)
        hsb[2] = (hsb[2] * (1f - s.brightnessJitter * rnd.nextFloat())).coerceIn(0f, 1f)
        return Colors.hsbToRgb(hsb[0], hsb[1], hsb[2])
    }

    /**
     * Ein Abdruck: elliptische Spitze ([angle], [round]), Auftrag [flow], Deckkraft-Obergrenze
     * [cap] (die Deckung eines Pixels steigt durch diesen Abdruck höchstens bis [cap]).
     */
    private fun stamp(cx: Float, cy: Float, radius: Float, angle: Float, round: Float, flowIn: Float, cap: Float, dabColor: Int): IntRect {
        val s = ctx.settings
        val rect = IntRect(floor(cx - radius).toInt(), floor(cy - radius).toInt(), ceil(cx + radius).toInt() + 1, ceil(cy + radius).toInt() + 1)
            .intersect(IntRect.ofSize(ctx.grid.width, ctx.grid.height))
        if (rect.isEmpty || flowIn <= 0f || cap <= 0f) return IntRect.EMPTY
        ctx.tx.touch(rect)
        ctx.markTouched(rect)
        val hard = s.hardness.coerceIn(0f, 1f)
        val inner = radius * hard
        val flow = flowIn
        val lockAlpha = ctx.target == PaintTarget.PIXELS && ctx.layer.lockTransparency
        if (tool.customDab) {
            tool.dab(ctx, cx, cy, radius) { d ->
                if (d >= 1f) 0f else if (d * radius <= inner) flow else {
                    val t = 1f - (d * radius - inner) / (radius - inner); t * t * (3f - 2f * t) * flow
                }
            }
            return rect
        }
        val elliptic = round < 0.999f
        val cosA = kotlin.math.cos(angle); val sinA = kotlin.math.sin(angle)
        val invRound = 1f / round
        val colorGrid = ctx.colorGrid
        // Kachelweise mit direktem Array-Zugriff: pro Kachel einmal die Arrays holen,
        // pro Zeile nur den Bereich innerhalb des Kreises bearbeiten.
        val grid = ctx.grid
        val cov = ctx.coverage
        val sel = ctx.selectionMask
        val r2 = radius * radius
        val soft = radius - inner
        val opacity = s.opacity
        val aliased = tool.aliased
        grid.forTilesIn(rect) { tx, ty ->
            val tr = grid.tileRect(tx, ty).intersect(rect)
            // Arbeitskopien im Kachel-Cache: direkter Array-Zugriff auch bei nativem Speicher
            val gT = grid.tileOrCreate(tx, ty); val cT = cov.tileOrCreate(tx, ty)
            val kT = colorGrid?.tileOrCreate(tx, ty)
            // Auswahl hat ggf. eine andere Ausdehnung als die Ebene → über Dokumentkoordinaten nachschlagen
            val oT = ctx.tx.originalTile(tx, ty); val sT = sel?.tileAt(tr.left, tr.top)
            val gTile = gT.pin(); val cTile = cT.pin(); val kTile = kT?.pin()
            val oTile = oT?.pin(forWrite = false); val sTile = sT?.pin(forWrite = false)
            val sDefault = sel?.defaultValue ?: 255
            try { for (y in tr.top until tr.bottom) {
                val dy = y + 0.5f - cy
                val rem = r2 - dy * dy
                if (rem <= 0f) continue
                val half = sqrt(rem)
                val x0 = maxOf(tr.left, floor(cx - half).toInt())
                val x1 = minOf(tr.right, ceil(cx + half).toInt() + 1)
                val rowBase = (y and TileGrid.MASK) shl TileGrid.SHIFT
                for (x in x0 until x1) {
                    val dx = x + 0.5f - cx
                    val d2 = if (!elliptic) dx * dx + dy * dy else {
                        val u = dx * cosA + dy * sinA; val v = (-dx * sinA + dy * cosA) * invRound
                        u * u + v * v
                    }
                    if (d2 >= r2) continue
                    val shape = if (aliased || d2 <= inner * inner) 1f else {
                        val t = 1f - (sqrt(d2) - inner) / soft
                        t * t * (3f - 2f * t)
                    }
                    val a = shape * flow
                    if (a <= 0f) continue
                    val i = rowBase or (x and TileGrid.MASK)
                    // Volle Deckung: Ergebnis hängt nur von Original und Deckung ab → unverändert
                    if (cTile[i] >= 65535 && kTile == null) continue
                    val old = cTile[i] / 65535f
                    // Deckkraft-Dynamik: dieser Abdruck hebt die Deckung höchstens bis cap
                    val c = max(old, minOf(cap, old + (1f - old) * a))
                    if (c <= old && kTile == null) continue
                    cTile[i] = if (c >= 0.9999f) 65535 else (c * 65535f).toInt()
                    if (kTile != null) {
                        // Farbdynamik: Farbe gewichtet nach dem Anteil dieses Abdrucks an der Deckung
                        val f = if (c > 0f) (c - old) / c else 1f
                        kTile[i] = if (old <= 0f) dabColor else Colors.lerpRgb(kTile[i], dabColor, f)
                        ctx.pixelColor = kTile[i]
                    }
                    val selF = if (sel == null) 1f else (sTile?.get(i) ?: sDefault) / 255f
                    val amount = c * opacity * selF
                    if (amount <= 0f) continue
                    val orig = oTile?.get(i) ?: grid.defaultValue
                    var v = tool.apply(ctx, x, y, orig, amount)
                    if (lockAlpha) v = (orig and 0xFF000000.toInt()) or (v and 0xFFFFFF)
                    gTile[i] = v
                }
            } } finally { gT.unpin(); cT.unpin(); kT?.unpin(); oT?.unpin(); sT?.unpin() }
        }
        return rect
    }
}
