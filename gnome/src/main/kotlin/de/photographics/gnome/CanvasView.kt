package de.photographics.gnome

import de.photographics.core.image.IntRect
import de.photographics.core.image.Parallel
import de.photographics.core.render.Compositor
import org.gnome.gdk.MemoryFormat
import org.gnome.gdk.MemoryTexture
import org.gnome.gdk.ModifierType
import org.gnome.gdk.RGBA
import org.gnome.gdk.ScrollUnit
import org.gnome.gdk.Texture
import org.gnome.graphene.Rect
import org.gnome.gsk.ScalingFilter
import org.gnome.gtk.EventControllerMotion
import org.gnome.gtk.EventControllerScroll
import org.gnome.gtk.EventControllerScrollFlags
import org.gnome.gtk.GestureDrag
import org.gnome.gtk.GestureZoom
import org.gnome.gtk.Snapshot
import org.gnome.gtk.Widget
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Eingaben auf der Zeichenfläche in Dokumentkoordinaten. [press] liefert false, wenn das Werkzeug
 * nichts damit anfängt – dann verschiebt der Zug die Ansicht.
 */
interface CanvasInput {
    fun press(x: Double, y: Double, pressure: Float, mods: Set<ModifierType>): Boolean
    fun drag(x: Double, y: Double, pressure: Float, mods: Set<ModifierType>)
    fun release(x: Double, y: Double, mods: Set<ModifierType>)
    fun hover(x: Double, y: Double) {}
    fun leave() {}
    /** Doppelklick (z. B. Freistellen/Transformieren anwenden). */
    fun doubleClick(x: Double, y: Double) {}
    /** Mauszeiger über der Zeichenfläche (CSS-Name), null = Standard. */
    fun cursor(): String? = null
    /** Rechtsklick ohne Ziehen an ([vx], [vy]) in Widget-Koordinaten (z. B. Pinselauswahl). */
    fun context(vx: Double, vy: Double) {}
    /** Alt+Rechtsziehen (Photoshop): waagerecht Größe, senkrecht Härte; [dx]/[dy] in Bildschirmpixeln. */
    fun brushAdjust(phase: Int, dx: Double, dy: Double) {}
}

/**
 * Zeichenfläche: das Dokument in Kacheln je Verkleinerungsstufe (1, 2, 4, … Dokumentpixel je
 * Kachelpixel), gerendert auf dem Engine-Thread und als Texturen von GTK skaliert. Fehlende Kacheln
 * werden vorläufig aus einer gröberen Stufe gezeichnet.
 */
class CanvasView : Widget {
    constructor() : super()
    constructor(address: MemorySegment) : super(address)

    var session: Session? = null
        private set
    private var docW = 0
    private var docH = 0

    /** Bildschirmpixel (logisch) je Dokumentpixel und Lage des Dokumentursprungs im Widget. */
    var zoom = 1.0
        private set
    private var ox = 0.0
    private var oy = 0.0
    private var fitted = false

    var onZoomChanged: (Double) -> Unit = {}
    /** Nach jedem Zeichnen (Lineale folgen Zoom und Lage). */
    var onDrawn: () -> Unit = {}

    private val tiles = object : LinkedHashMap<Long, TileTex>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TileTex>?) = size > MAX_TILES
    }
    private val stale = HashSet<Long>()
    private var wanted = LinkedHashSet<Long>()
    private var rendering = false
    private var generation = 0

    private var pointerX = 0.0
    private var pointerY = 0.0
    /** Zeiger über der Zeichenfläche (für den Pinselumriss). */
    var pointerInside = false
        private set

    /** Werkzeug-Eingaben; null = nur Ansicht verschieben. */
    var input: CanvasInput? = null
    /** Zeichnet Werkzeug-Überlagerungen (Auswahl, Rahmen, Pinselumriss) über dem Bild. */
    var overlay: ((Snapshot) -> Unit)? = null
    /** Leertaste gehalten: Ziehen verschiebt die Ansicht (wie in Photoshop). */
    var panMode = false
        set(v) { field = v; updateCursor() }

    private val checker: Texture by lazy {
        val s = CHECK * 2
        val px = IntArray(s * s) { i -> if (((i % s) / CHECK + (i / s) / CHECK) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt() }
        texture(s, s, px)
    }

    init {
        hexpand = true; vexpand = true
        focusable = true
        overflow = org.gnome.gtk.Overflow.HIDDEN
        addCssClass("view")

        val motion = EventControllerMotion()
        motion.onMotion { x, y ->
            pointerX = x; pointerY = y; pointerInside = true
            if (docW > 0) input?.hover(docX(x), docY(y))
            updateCursor()
        }
        motion.onLeave { pointerInside = false; input?.leave(); queueDraw() }
        addController(motion)

        val scroll = EventControllerScroll(EventControllerScrollFlags.BOTH_AXES)
        scroll.onScroll { dx, dy ->
            val ctrl = scroll.currentEventState.contains(ModifierType.CONTROL_MASK)
            val wheel = scroll.unit == ScrollUnit.WHEEL
            if (ctrl) {
                zoomAt(zoom * 1.15.pow(-dy), pointerX, pointerY)
            } else {
                val f = if (wheel) 48.0 else 1.0
                val shift = scroll.currentEventState.contains(ModifierType.SHIFT_MASK)
                if (shift && dx == 0.0) panBy(-dy * f, 0.0) else panBy(-dx * f, -dy * f)
            }
            true
        }
        addController(scroll)

        val pinch = GestureZoom()
        var pinchStart = 1.0
        pinch.onBegin { _ -> pinchStart = zoom }
        pinch.onScaleChanged { scale ->
            val cx = org.javagi.base.Out(0.0); val cy = org.javagi.base.Out(0.0)
            if (pinch.getBoundingBoxCenter(cx, cy)) zoomAt(pinchStart * scale, cx.get() ?: 0.0, cy.get() ?: 0.0)
            else zoomAt(pinchStart * scale, width / 2.0, height / 2.0)
        }
        addController(pinch)

        // Linke Taste: Werkzeug; mittlere Taste, Leertaste oder Hand: Ansicht verschieben
        val drag = GestureDrag()
        drag.button = 0
        var startX = 0.0; var startY = 0.0
        var bx = 0.0; var by = 0.0
        var panning = false
        // 0 = Werkzeug, 1 = Ansicht verschieben, 2 = Pinsel einstellen, 3 = Rechtsklick (Kontext)
        var mode = 0
        drag.onDragBegin { x, y ->
            grabFocus()
            bx = x; by = y; startX = ox; startY = oy
            val mods = drag.currentEventState
            val alt = ModifierType.ALT_MASK in mods; val ctrl = ModifierType.CONTROL_MASK in mods
            val b = drag.currentButton
            mode = when {
                docW <= 0 -> 1
                (b == 3 && alt) || (b == 1 && alt && ctrl) -> 2
                b == 3 -> 3
                b == 2 || panMode || input == null -> 1
                b == 1 && input!!.press(docX(x), docY(y), pressureOf(drag), mods) -> 0
                else -> 1
            }
            panning = mode == 1
            if (panning) setCursorFromName("grabbing")
            if (mode == 2) input?.brushAdjust(0, 0.0, 0.0)
        }
        drag.onDragUpdate { dx, dy ->
            when (mode) {
                1 -> { ox = startX + dx; oy = startY + dy; clamp(); queueDraw() }
                2 -> input?.brushAdjust(1, dx, dy)
                0 -> input?.drag(docX(bx + dx), docY(by + dy), pressureOf(drag), drag.currentEventState)
            }
        }
        drag.onDragEnd { dx, dy ->
            when (mode) {
                0 -> input?.release(docX(bx + dx), docY(by + dy), drag.currentEventState)
                2 -> input?.brushAdjust(2, dx, dy)
                3 -> if (kotlin.math.hypot(dx, dy) < 4) input?.context(bx, by)
            }
            panning = false; mode = 0
            updateCursor()
        }
        addController(drag)

        val click = org.gnome.gtk.GestureClick()
        click.onPressed { n, x, y -> if (n == 2 && docW > 0) input?.doubleClick(docX(x), docY(y)) }
        addController(click)
    }

    /** Stiftdruck des aktuellen Ereignisses (Maus: 1). */
    private fun pressureOf(g: GestureDrag): Float {
        val e = g.currentEvent ?: return 1f
        val out = org.javagi.base.Out(1.0)
        return if (e.getAxis(org.gnome.gdk.AxisUse.PRESSURE, out)) (out.get() ?: 1.0).toFloat().coerceIn(0.01f, 1f) else 1f
    }

    fun updateCursor() {
        val name = if (panMode) "grab" else input?.cursor()
        setCursorFromName(name)
        queueDraw()
    }

    // ------------------------------------------------------------ Koordinaten

    fun docX(vx: Double) = (vx - ox) / zoom
    fun docY(vy: Double) = (vy - oy) / zoom
    fun viewX(x: Double) = (ox + x * zoom).toFloat()
    fun viewY(y: Double) = (oy + y * zoom).toFloat()
    /** Zeigerposition in Dokumentkoordinaten. */
    val pointerDocX get() = docX(pointerX)
    val pointerDocY get() = docY(pointerY)

    fun setSession(s: Session?, state: DocState?) {
        session = s
        docW = state?.width ?: 0; docH = state?.height ?: 0
        clearTiles()
        fitted = false
        if (width > 0) fit()
        queueDraw()
    }

    /**
     * Nach einer Änderung: [rect] in Dokumentpixeln neu rendern (null = alles). [live] = Zwischenstand
     * einer laufenden Vorschau (Transformieren, Filter, Korrekturen, Deckkraft): Betrifft sie einen
     * großen Teil der Ansicht, rechnet die Anzeige so lange in halber Auflösung und schärft erst
     * nach einer kurzen Pause nach – jeweils alle Kacheln gemeinsam.
     */
    fun invalidate(rect: IntRect?, state: DocState, live: Boolean = false) {
        if (state.width != docW || state.height != docH) {
            docW = state.width; docH = state.height
            clearTiles(); fit()
            queueDraw()
            return
        }
        generation++
        val view = IntRect(docX(0.0).toInt() - 1, docY(0.0).toInt() - 1, docX(width.toDouble()).toInt() + 2, docY(height.toDouble()).toInt() + 2).intersect(IntRect.ofSize(docW, docH))
        val hit = if (rect == null) view else rect.intersect(view)
        val big = live && view.width > 0 && hit.width.toLong() * hit.height > view.width.toLong() * view.height / 4
        val fine = levelFor(zoom * deviceScale)
        if (big && fine < MAX_LEVEL) {
            liveBoost = 1
            if (liveTimer != 0) org.gnome.glib.Source.remove(liveTimer)
            liveTimer = org.gnome.glib.GLib.timeoutAddOnce(350) { liveTimer = 0; liveBoost = 0; queueDraw() }
        }
        for (k in tiles.keys.toList()) {
            if (rect != null && tileDocRect(k).intersect(rect).isEmpty) continue
            // Feine Kacheln verwerfen statt sie veraltet stehen zu lassen: bis zum Nachschärfen zeigt
            // die Anzeige die aktuelle gröbere Stufe
            if (big && unkey(k).first <= fine) { tiles.remove(k); stale -= k; tilesVersion++ } else stale += k
        }
        queueDraw()
    }

    /** Anzeige-Umrechnung (Farbproof bzw. Dokumentprofil → Bildschirm); null = unverändert. */
    var proof: de.photographics.core.color.SoftProof? = null
        set(v) { field = v; generation++; tilesVersion++; tiles.clear(); stale.clear(); composite = null; queueDraw() }
    var gamutWarning = false
        set(v) { field = v; proof = proof }

    /**
     * Kanalansicht (Kanäle-Bedienfeld): 0 = RGB, 1–3 = Rot/Grün/Blau als Graustufen,
     * ab [ALPHA_VIEW] ein Alphakanal.
     */
    @Volatile var channelView = 0
        set(v) { if (field != v) { field = v; clearTiles(); queueDraw() } }

    /** [proof] ist nur die Umrechnung Dokumentprofil → Bildschirm (kein Farbproof). */
    @Volatile var proofIsDisplay = true

    /** Laufende GPU-Vorschau einer Ebene (siehe [GpuLive]). */
    var gpuLive: GpuLive? = null
        set(v) { field = v; queueDraw() }

    /** Was gerade sichtbar ist – für den Aufbau einer [GpuLive] (im Hauptthread aufrufen). */
    fun liveView(): LiveView {
        val r = IntRect(floor(docX(0.0)).toInt() - 1, floor(docY(0.0)).toInt() - 1, docX(width.toDouble()).toInt() + 2, docY(height.toDouble()).toInt() + 2)
        return LiveView(r.intersect(IntRect.ofSize(docW, docH)), 1 shl levelFor(zoom * deviceScale), proof, proofIsDisplay, gamutWarning)
    }

    /** Neuer Stand der GPU-Vorschau (Matrix, Deckkraft) – nur zeichnen, nichts rechnen. */
    fun updateGpu(layerId: Int, m: de.photographics.core.image.Homography, alpha: Float) {
        val g = gpuLive?.takeIf { it.layerId == layerId && !it.dropWhenReady } ?: return
        g.matrix = m; g.alpha = alpha
        queueDraw()
    }

    /** Die Änderung ist übernommen: [dirty] neu rechnen, die GPU-Vorschau verschwindet, sobald das fertig ist. */
    fun endGpu(dirty: IntRect?, state: DocState) {
        val g = gpuLive ?: return
        g.dropWhenReady = true
        if (dirty == null || !dirty.isEmpty) invalidate(dirty, state)
    }

    /** Während großer Live-Vorschauen eine Stufe gröber rechnen (siehe [invalidate]). */
    private var liveBoost = 0
    private var liveTimer = 0

    private fun clearTiles() { generation++; tilesVersion++; tiles.clear(); stale.clear(); wanted.clear(); composite = null }

    // ------------------------------------------------------------ Ansicht

    fun fit() {
        if (docW <= 0 || width <= 0) return
        val m = 24.0
        zoom = min((width - 2 * m) / docW, (height - 2 * m) / docH).coerceIn(MIN_ZOOM, 1.0)
        center()
        fitted = true
        onZoomChanged(zoom)
        queueDraw()
    }

    fun setZoom(z: Double) = zoomAt(z, width / 2.0, height / 2.0)
    fun zoomIn() = setZoom(nextStep(zoom, true))
    fun zoomOut() = setZoom(nextStep(zoom, false))

    private fun nextStep(z: Double, up: Boolean): Double {
        val steps = doubleArrayOf(1 / 32.0, 1 / 16.0, 1 / 12.0, 1 / 8.0, 1 / 6.0, 1 / 4.0, 1 / 3.0, 0.5, 2 / 3.0, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0)
        return if (up) steps.firstOrNull { it > z * 1.001 } ?: MAX_ZOOM else steps.lastOrNull { it < z * 0.999 } ?: MIN_ZOOM
    }

    private fun zoomAt(z: Double, x: Double, y: Double) {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (docW <= 0 || nz == zoom) return
        // Der Dokumentpunkt unter (x, y) bleibt stehen
        val dx = (x - ox) / zoom; val dy = (y - oy) / zoom
        zoom = nz
        ox = x - dx * zoom; oy = y - dy * zoom
        clamp()
        onZoomChanged(zoom)
        queueDraw()
    }

    /** Zoomwerkzeug: eine Stufe hinein/heraus, der Dokumentpunkt ([docX], [docY]) bleibt stehen. */
    fun zoomStepAt(up: Boolean, docX: Double, docY: Double) = zoomAt(nextStep(zoom, up), ox + docX * zoom, oy + docY * zoom)

    /** Zoomwerkzeug mit aufgezogenem Rahmen: den Dokumentbereich fensterfüllend zeigen. */
    fun zoomToDocRect(l: Double, t: Double, r: Double, b: Double) {
        if (docW <= 0 || width <= 0 || r - l < 1 || b - t < 1) return
        zoom = min(width / (r - l), height / (b - t)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        ox = width / 2.0 - (l + r) / 2 * zoom; oy = height / 2.0 - (t + b) / 2 * zoom
        clamp()
        fitted = false
        onZoomChanged(zoom)
        queueDraw()
    }

    fun debugPan(dx: Double, dy: Double) = panBy(dx, dy)

    private fun panBy(dx: Double, dy: Double) { ox += dx; oy += dy; clamp(); queueDraw() }

    private fun center() {
        ox = (width - docW * zoom) / 2; oy = (height - docH * zoom) / 2
    }

    /** Das Bild bleibt zur Hälfte im Fenster; kleiner als das Fenster → mittig. */
    private fun clamp() {
        val w = docW * zoom; val h = docH * zoom
        ox = if (w <= width) (width - w) / 2 else ox.coerceIn(width / 2.0 - w, width / 2.0)
        oy = if (h <= height) (height - h) / 2 else oy.coerceIn(height / 2.0 - h, height / 2.0)
    }

    override fun sizeAllocate(width: Int, height: Int, baseline: Int) {
        super.sizeAllocate(width, height, baseline)
        if (!fitted) fit() else clamp()
    }

    // ------------------------------------------------------------ Zeichnen

    /** Tatsächlicher Skalierungsfaktor der Anzeige (z. B. 1,5); [scaleFactor] ist auf ganze Zahlen gerundet. */
    val deviceScale: Double get() = screenshotScale ?: native?.surface?.scale ?: scaleFactor.toDouble()
    /** Lage der Zeichenfläche auf der Fensterfläche (logische Pixel). */
    private fun surfaceOrigin(): Pair<Double, Double> {
        val n = native ?: return 0.0 to 0.0
        val out = org.gnome.graphene.Point()
        if (!computePoint(n as Widget, org.gnome.graphene.Point().init(0f, 0f), out)) return 0.0 to 0.0
        // Auf dem Bildschirm beginnt die Fläche am Fensterschatten; Bildschirmfotos am Fensterinhalt
        if (screenshotScale != null) return out.readX().toDouble() to out.readY().toDouble()
        val tx = org.javagi.base.Out(0.0); val ty = org.javagi.base.Out(0.0)
        n.getSurfaceTransform(tx, ty)
        return (out.readX() + (tx.get() ?: 0.0)) to (out.readY() + (ty.get() ?: 0.0))
    }

    /** Bildschirmfotos werden mit diesem Faktor gerendert (siehe PhotographicsApp.screenshotLater). */
    var screenshotScale: Double? = null

    override fun snapshot(snapshot: Snapshot) = guarded { draw(snapshot) }

    private fun draw(snapshot: Snapshot) {
        if (docW <= 0) return
        // Kachelkanten auf echte Gerätepixel legen (auch bei 125 %/150 %): sonst glättet GTK die
        // Kanten und zwischen den Kacheln scheint der Hintergrund durch
        val sf = deviceScale
        // Gerätepixel zählen ab der Fensterfläche: die Zeichenfläche selbst kann auf einem halben
        // Gerätepixel liegen (z. B. unter einer 47 px hohen Kopfleiste bei 150 %)
        val (bx, by) = surfaceOrigin()
        fun snapX(v: Double) = (Math.round((bx + v) * sf) / sf - bx).toFloat()
        fun snapY(v: Double) = (Math.round((by + v) * sf) / sf - by).toFloat()
        val x0 = snapX(ox); val y0 = snapY(oy); val x1 = snapX(ox + docW * zoom); val y1 = snapY(oy + docH * zoom)
        val docRect = Rect().init(x0, y0, x1 - x0, y1 - y0)
        snapshot.appendColor(RGBA(0f, 0f, 0f, 0.25f), Rect().init(x0 + 1, y0 + 2, x1 - x0, y1 - y0))
        snapshot.pushRepeat(docRect, Rect().init(x0, y0, CHECK * 2f, CHECK * 2f))
        snapshot.appendTexture(checker, Rect().init(x0, y0, CHECK * 2f, CHECK * 2f))
        snapshot.pop()

        val level = min(MAX_LEVEL, levelFor(zoom * sf) + liveBoost)
        val step = 1 shl level
        val span = TILE * step * zoom                 // Kachelbreite auf dem Bildschirm
        val tx0 = max(0, floor(-ox / span).toInt()); val ty0 = max(0, floor(-oy / span).toInt())
        val tx1 = min(tilesAcross(docW, step) - 1, floor((width - ox) / span).toInt())
        val ty1 = min(tilesAcross(docH, step) - 1, floor((height - oy) / span).toInt())
        // Ab doppelter Größe Pixel scharf zeigen, darunter glätten (sonst ungleich breite Pixel bei 150 %)
        val filter = when {
            zoom * sf >= 2.0 || zoom * sf == 1.0 -> ScalingFilter.NEAREST
            zoom * sf > 1.0 -> ScalingFilter.LINEAR
            else -> ScalingFilter.TRILINEAR
        }
        val missing = ArrayList<Long>()

        // Sichtbare Kacheln zuerst 1:1 (ganzzahlige Lage, keine Skalierung) zu einem Bild zusammensetzen,
        // dann nur dieses Bild skalieren: so gibt es keine Kachelkanten, an denen gefiltert, geglättet
        // oder doppelt gezeichnet würde – unabhängig von Bildschirmskalierung und Transparenz
        val lw = (docW + step - 1) / step; val lh = (docH + step - 1) / step
        val X0 = tx0 * TILE; val Y0 = ty0 * TILE
        val W = min(lw, (tx1 + 1) * TILE) - X0; val H = min(lh, (ty1 + 1) * TILE) - Y0
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val k = key(level, tx, ty)
            if (tiles[k] == null || k in stale) missing += k
        }
        visibleNow = (ty0..ty1).flatMap { ty -> (tx0..tx1).map { tx -> key(level, tx, ty) } }.toHashSet()
        if (W > 0 && H > 0) {
            val ck = CompositeKey(level, tx0, ty0, tx1, ty1, tilesVersion)
            var tex = composite?.takeIf { compositeKey == ck }
            if (tex == null) {
                tex = buildComposite(level, tx0, ty0, tx1, ty1, X0, Y0, W, H)
                composite = tex; compositeKey = ck
            }
            val s = step * zoom
            val l = snapX(ox + X0 * s); val t = snapY(oy + Y0 * s)
            if (tex != null) snapshot.appendScaledTexture(tex, filter, Rect().init(l, t, (W * s).toFloat(), (H * s).toFloat()))
        }
        gpuLive?.let { g ->
            // Übernommen und die Kacheln zeigen den neuen Stand: Vorschau ausblenden
            if (g.dropWhenReady && missing.isEmpty()) gpuLive = null
            else drawGpu(snapshot, g, docRect, filter)
        }
        overlay?.invoke(snapshot)
        onDrawn()
        // Zuerst die Mitte rendern
        val cx = (tx0 + tx1) / 2.0; val cy = (ty0 + ty1) / 2.0
        missing.sortBy { k -> val (_, x, y) = unkey(k); (x - cx) * (x - cx) + (y - cy) * (y - cy) }
        // Vorrang: grobe Übersicht des ganzen Bildes (Ersatz beim Zoomen/Verschieben), dann das Sichtbare,
        // zuletzt ein Kachelring um den sichtbaren Bereich (damit beim Verschieben nichts fehlt)
        val order = LinkedHashSet<Long>()
        val base = baseLevel()
        if (base > level) for (ty in 0 until tilesAcross(docH, 1 shl base)) for (tx in 0 until tilesAcross(docW, 1 shl base)) {
            val k = key(base, tx, ty); if (tiles[k] == null) order += k
        }
        order.addAll(missing)
        val nx = tilesAcross(docW, step); val ny = tilesAcross(docH, step)
        for (ty in max(0, ty0 - 1)..min(ny - 1, ty1 + 1)) for (tx in max(0, tx0 - 1)..min(nx - 1, tx1 + 1)) {
            val k = key(level, tx, ty); if (tiles[k] == null) order += k
        }
        wanted = order
        if (order.isNotEmpty()) requestTiles()
    }

    /** GPU-Vorschau: im Bereich der Vorschau darunter + Ebene (Matrix, Deckkraft, Mischmodus) + darüber. */
    private fun drawGpu(sn: Snapshot, g: GpuLive, docRect: Rect, filter: ScalingFilter) {
        val rg = g.region
        val l = viewX(rg.left.toDouble()); val t = viewY(rg.top.toDouble())
        val dest = Rect().init(l, t, (rg.width * zoom).toFloat(), (rg.height * zoom).toFloat())
        val clip = Rect(); if (!dest.intersection(docRect, clip)) return
        sn.pushClip(clip)
        sn.pushRepeat(clip, Rect().init(docRect.x, docRect.y, CHECK * 2f, CHECK * 2f))
        sn.appendTexture(checker, Rect().init(docRect.x, docRect.y, CHECK * 2f, CHECK * 2f))
        sn.pop()
        val mode = GpuSandwich.gskBlend(g.blend)
        val blended = mode != null && mode != org.gnome.gsk.BlendMode.DEFAULT
        if (blended) sn.pushBlend(mode)
        g.below?.let { sn.appendScaledTexture(it, filter, dest) }
        if (blended) sn.pop()
        sn.pushOpacity(g.alpha.toDouble())
        sn.save()
        // Ebenentextur → Dokument → Ansicht
        val h = g.matrix.then(de.photographics.core.image.Homography(doubleArrayOf(zoom, 0.0, ox, 0.0, zoom, oy, 0.0, 0.0, 1.0))).m
        val n = h[8]
        val mat = org.gnome.graphene.Matrix()
        if (h[6] == 0.0 && h[7] == 0.0) mat.initFrom2d(h[0] / n, h[3] / n, h[1] / n, h[4] / n, h[2] / n, h[5] / n)
        else mat.initFromFloat(floatArrayOf(
            h[0].toFloat(), h[3].toFloat(), 0f, h[6].toFloat(),
            h[1].toFloat(), h[4].toFloat(), 0f, h[7].toFloat(),
            0f, 0f, 1f, 0f,
            h[2].toFloat(), h[5].toFloat(), 0f, h[8].toFloat(),
        ))
        sn.transform(org.gnome.gsk.Transform().matrix(mat))
        sn.appendScaledTexture(g.layer, if (filter == ScalingFilter.NEAREST && h[1] == 0.0 && h[3] == 0.0) ScalingFilter.NEAREST else ScalingFilter.LINEAR,
            Rect().init(0f, 0f, g.layer.width.toFloat(), g.layer.height.toFloat()))
        sn.restore()
        sn.pop()
        if (blended) sn.pop()
        g.above?.let { sn.appendScaledTexture(it, filter, dest) }
        sn.pop()
    }

    private data class CompositeKey(val level: Int, val tx0: Int, val ty0: Int, val tx1: Int, val ty1: Int, val version: Int)
    private var composite: Texture? = null
    private var compositeKey: CompositeKey? = null
    /** Zählt jede Änderung am Kachelbestand (dann wird das zusammengesetzte Bild neu gebaut). */
    private var tilesVersion = 0

    /**
     * Setzt die Kacheln [tx0]..[tx1] × [ty0]..[ty1] der Stufe [level] auf der Grafikkarte zu einer
     * Textur zusammen (Ursprung = Kachel tx0/ty0). Fehlende Kacheln kommen vorläufig aus einer
     * gröberen Stufe.
     */
    private fun buildComposite(level: Int, tx0: Int, ty0: Int, tx1: Int, ty1: Int, X0: Int, Y0: Int, W: Int, H: Int): Texture? {
        val renderer = native?.renderer ?: return null
        val sn = Snapshot()
        val step = 1 shl level
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val cl = tx * TILE - X0; val ct = ty * TILE - Y0
            val cw = min(TILE, W - cl); val ch = min(TILE, H - ct)
            if (cw <= 0 || ch <= 0) continue
            val t = tiles[key(level, tx, ty)]
            sn.pushClip(Rect().init(cl.toFloat(), ct.toFloat(), cw.toFloat(), ch.toFloat()))
            if (t != null) {
                val r = t.rect
                sn.appendTexture(t.texture, Rect().init((r.left - X0).toFloat(), (r.top - Y0).toFloat(), r.width.toFloat(), r.height.toFloat()))
            } else {
                var l = level + 1
                var found = false
                while (l <= MAX_LEVEL) {
                    val s = TILE shl l
                    val c = tiles[key(l, tx * TILE * step / s, ty * TILE * step / s)]
                    if (c != null) {
                        val f = (1 shl (l - level)).toFloat()
                        val r = c.rect
                        sn.appendScaledTexture(c.texture, ScalingFilter.LINEAR, Rect().init(r.left * f - X0, r.top * f - Y0, r.width * f, r.height * f))
                        found = true
                        break
                    }
                    l++
                }
                // Beim Herauszoomen: feinere Kacheln verkleinert zeigen, bis die passenden fertig sind
                if (!found && level > 0) {
                    val fl = level - 1
                    for (sy in 0..1) for (sx in 0..1) {
                        val c = tiles[key(fl, tx * 2 + sx, ty * 2 + sy)] ?: continue
                        val r = c.rect
                        sn.appendScaledTexture(c.texture, ScalingFilter.LINEAR, Rect().init(r.left * 0.5f - X0, r.top * 0.5f - Y0, r.width * 0.5f, r.height * 0.5f))
                    }
                }
            }
            sn.pop()
        }
        val node = sn.toNode() ?: return null
        return renderer.renderTexture(node, Rect().init(0f, 0f, W.toFloat(), H.toFloat()))
    }

    /** Textur einer Kachel; [rect] = gerenderter Bereich in Pixeln der Stufe, mit [PAD] Rand zu den Nachbarn. */
    private class TileTex(val texture: Texture, val rect: IntRect)

    private fun tileDocRect(k: Long): IntRect {
        val (l, tx, ty) = unkey(k)
        val s = TILE shl l
        return IntRect(tx * s, ty * s, min(docW, (tx + 1) * s), min(docH, (ty + 1) * s))
    }

    /** Rendert gewünschte Kacheln stapelweise; der Stapel wird nach jedem Durchlauf neu bestimmt. */
    private fun hasCoarser(k: Long): Boolean {
        val (level, tx, ty) = unkey(k)
        for (l in level + 1..MAX_LEVEL) {
            val f = 1 shl (l - level)
            if (tiles.containsKey(key(l, tx / f, ty / f))) return true
        }
        return false
    }

    /** Stufe, auf der das ganze Bild in höchstens 2 × 2 Kacheln passt. */
    private fun baseLevel(): Int {
        var l = 0
        while (l < MAX_LEVEL && maxOf(docW, docH) > (TILE * 2) shl l) l++
        return l
    }

    /** Sichtbare Kacheln der aktuellen Stufe (für den gemeinsamen Austausch veralteter Kacheln). */
    private var visibleNow: Set<Long> = emptySet()

    private fun requestTiles() {
        val s = session ?: return
        if (rendering || wanted.isEmpty()) return
        rendering = true
        // Fehlende Kacheln dürfen nach und nach erscheinen. Veraltete sichtbare Kacheln (nach einer
        // Änderung) werden dagegen alle in einem Durchgang gerechnet und gemeinsam getauscht – sonst
        // zeigen Nachbarkacheln kurz verschiedene Stände (z. B. beim Verschieben einer Ebene versetzt)
        val base = baseLevel()
        val fresh = wanted.filter { tiles[it] == null && (it in visibleNow || unkey(it).first == base) }
        val staleVisible = wanted.filter { it in visibleNow && tiles[it] != null }
        // Gibt es für alle fehlenden Kacheln schon eine gröbere Fassung (Nachschärfen), kommen sie
        // gemeinsam; nur beim ersten Aufbau erscheinen sie nach und nach
        val refine = fresh.isNotEmpty() && fresh.all { k -> unkey(k).first == base || hasCoarser(k) }
        val batch = when {
            refine -> fresh.take(MAX_TILES / 4)
            fresh.isNotEmpty() -> fresh.take(Parallel.threads * 2)
            staleVisible.isNotEmpty() -> staleVisible.take(MAX_TILES / 4)
            else -> wanted.take(Parallel.threads * 2)          // Ring um den sichtbaren Bereich
        }
        val gen = generation
        val w = docW; val h = docH
        Engine.call({
            val byLevel = batch.groupBy { unkey(it).first }
            val out = ArrayList<Triple<Long, IntRect, ByteArray>>()
            for ((level, keys) in byLevel) {
                val step = 1 shl level
                val lw = (w + step - 1) / step; val lh = (h + step - 1) / step
                val rects = keys.map { k ->
                    val (_, tx, ty) = unkey(k)
                    IntRect(max(0, tx * TILE - PAD), max(0, ty * TILE - PAD), min((tx + 1) * TILE + PAD, lw), min((ty + 1) * TILE + PAD, lh))
                }
                var area = rects[0]
                for (r in rects) area = area.union(r)
                Compositor.prepare(s.doc, step, area)
                val pf = proof; val gw = gamutWarning
                val view = channelView
                // Alphakanal: Graustufen aus der passenden Verkleinerungsstufe (im Engine-Thread vorbereitet)
                val alpha = if (view >= ALPHA_VIEW) s.doc.channels.getOrNull(view - ALPHA_VIEW)?.grid?.let { if (level > 0) it.mip(level, scalar = true) else it } else null
                Parallel.bands(keys.size, TILE.toLong() * TILE * 40, compute = { i ->
                    val px = if (alpha != null) IntArray(rects[i].width * rects[i].height).also { a ->
                        alpha.readRect(rects[i], a)
                        for (k in a.indices) { val g = a[k] and 0xFF; a[k] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                    } else Compositor.renderArgbSampled(s.doc, rects[i], step)
                    if (alpha == null) pf?.apply(px, gw && pf.supportsGamutWarning)
                    if (view in 1..3) {
                        val sh = 24 - view * 8
                        for (k in px.indices) { val c = px[k]; val g = (c shr sh) and 0xFF; px[k] = (c and 0xFF000000.toInt()) or (g shl 16) or (g shl 8) or g }
                    }
                    toBytes(px)
                }, consume = { i, bytes -> out += Triple(keys[i], rects[i], bytes) })
            }
            out
        }) { res ->
            rendering = false
            if (session !== s) return@call
            res.getOrNull()?.forEach { (k, r, bytes) ->
                tilesVersion++
                tiles[k] = TileTex(MemoryTexture(r.width, r.height, MemoryFormat.B8G8R8A8, bytes, r.width * 4L), r)
                if (gen == generation) stale -= k else stale += k
            }
            if (res.isSuccess) queueDraw()
        }
    }

    private fun texture(w: Int, h: Int, px: IntArray): Texture = MemoryTexture(w, h, MemoryFormat.B8G8R8A8, toBytes(px), w * 4L)

    companion object {
        const val TILE = 256
        const val ALPHA_VIEW = 100
        const val CHECK = 8
        const val PAD = 4
        const val MAX_LEVEL = 6
        const val MAX_TILES = 1200
        const val MIN_ZOOM = 1 / 64.0
        const val MAX_ZOOM = 64.0

        /** ARGB (Ganzzahl) → B,G,R,A-Bytes (Little Endian), wie [MemoryFormat.B8G8R8A8]. */
        fun toBytes(px: IntArray): ByteArray {
            val b = ByteArray(px.size * 4)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(px)
            return b
        }

        /** Gröbste Stufe, deren Kachelpixel noch höchstens einen Gerätepixel groß sind. */
        fun levelFor(devicePxPerDocPx: Double): Int {
            if (devicePxPerDocPx >= 1.0) return 0
            return min(MAX_LEVEL, floor(ln(1.0 / devicePxPerDocPx) / ln(2.0) + 1e-9).toInt())
        }

        private fun tilesAcross(size: Int, step: Int) = ((size + step - 1) / step + TILE - 1) / TILE
        private fun key(level: Int, tx: Int, ty: Int) = (level.toLong() shl 48) or (tx.toLong() shl 24) or ty.toLong()
        private fun unkey(k: Long) = Triple((k ushr 48).toInt(), ((k ushr 24) and 0xFFFFFF).toInt(), (k and 0xFFFFFF).toInt())
    }
}
