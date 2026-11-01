package de.photographics.core.doc

import de.photographics.core.tr
import de.photographics.core.adjust.Adjustment
import de.photographics.core.adjust.RgbMap
import de.photographics.core.image.Affine
import de.photographics.core.image.IntRect
import de.photographics.core.image.Resample
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdTaggedBlock
import de.photographics.core.render.BlendMode
import java.util.concurrent.atomic.AtomicInteger

/** Herkunft/Art einer Ebene. Spezialebenen aus Photoshop werden als Pixel angezeigt und ihre Daten beim Speichern erhalten. */
enum class LayerKind(val label: String) {
    PIXEL(tr("Pixelebene")),
    TEXT(tr("Textebene (gerastert angezeigt)")),
    SMART_OBJECT(tr("Smartobjekt (gerastert angezeigt)")),
    ADJUSTMENT(tr("Einstellungsebene (nicht gerendert)")),
    FILL(tr("Füllebene (gerastert angezeigt)")),
}

/** Ebenenmaske: Graustufen 0 (verdeckt) .. 255 (sichtbar). */
class LayerMask(
    var grid: TileGrid,
    var enabled: Boolean = true,
    /** Mit der Ebene verbunden (Kettensymbol): Verschieben und Transformieren wirken auf beide. */
    var linked: Boolean = true,
) {
    val defaultColor: Int get() = grid.defaultValue
    /** Vorschau-Verschiebung beim Ziehen einer nicht verbundenen Maske. */
    @Volatile var previewDx = 0
    @Volatile var previewDy = 0
    fun copy() = LayerMask(grid.copy(), enabled, linked)
}

sealed class Layer(var name: String) {
    val id: Int = nextId.incrementAndGet()

    var visible = true
    /** Deckkraft 0..1. */
    var opacity = 1f
    /** Fläche (Fill) 0..1 – ohne Ebeneneffekte wirkt sie wie die Deckkraft. */
    var fillOpacity = 1f
    var blendMode = BlendMode.NORMAL
    /** Schnittmaske: Ebene wird auf die darunterliegende Basisebene beschnitten. */
    var clipped = false
    /** Transparente Pixel fixieren. */
    var lockTransparency = false
    var mask: LayerMask? = null
    var kind = LayerKind.PIXEL
    /** Photoshop-Ebenen-ID (lyid), bleibt beim Speichern erhalten. */
    var psdId: Int = 0

    /** Unbekannte PSD-Zusatzblöcke (Effekte, Text-Engine-Daten, ...), werden unverändert zurückgeschrieben. */
    var preservedBlocks: List<PsdTaggedBlock> = emptyList()

    /**
     * Ebeneneffekte aus den erhaltenen PSD-Blöcken (lmfx/lfx2), nur zur Anzeige. Weil sie aus
     * [preservedBlocks] gelesen werden, überstehen sie Kopieren, Rückgängig und Speichern von selbst.
     */
    val effects: LayerEffects?
        get() {
            val b = preservedBlocks
            if (effectsFor !== b) { effectsCache = de.photographics.core.psd.PsdEffects.parse(b); effectsFor = b }
            return effectsCache
        }
    @Volatile private var effectsFor: List<PsdTaggedBlock>? = null
    @Volatile private var effectsCache: LayerEffects? = null

    internal var parentGroup: GroupLayer? = null
    val parent: GroupLayer? get() = parentGroup

    abstract fun deepCopy(): Layer

    protected fun copyPropsTo(o: Layer) {
        o.visible = visible; o.opacity = opacity; o.fillOpacity = fillOpacity
        o.blendMode = blendMode; o.clipped = clipped; o.lockTransparency = lockTransparency
        o.mask = mask?.copy(); o.kind = kind; o.preservedBlocks = preservedBlocks
    }

    /** Momentaufnahme der veränderbaren Eigenschaften (für Undo). */
    data class Props(
        val name: String, val visible: Boolean, val opacity: Float, val fillOpacity: Float,
        val blendMode: BlendMode, val clipped: Boolean, val lockTransparency: Boolean,
        val maskEnabled: Boolean?,
        val maskLinked: Boolean? = null,
    )

    fun props() = Props(name, visible, opacity, fillOpacity, blendMode, clipped, lockTransparency, mask?.enabled, mask?.linked)

    fun applyProps(p: Props) {
        name = p.name; visible = p.visible; opacity = p.opacity; fillOpacity = p.fillOpacity
        blendMode = p.blendMode; clipped = p.clipped; lockTransparency = p.lockTransparency
        if (p.maskEnabled != null) mask?.enabled = p.maskEnabled
        if (p.maskLinked != null) mask?.linked = p.maskLinked
    }

    companion object {
        private val nextId = AtomicInteger(0)
    }
}

/** Ebene mit Pixeldarstellung, die der Compositor direkt zeichnet. */
abstract class RasterLayer(name: String) : Layer(name) {
    /** Die aktuell darzustellenden Pixel (Dokumentgröße). */
    abstract val raster: TileGrid

    /** Vorschau-Verschiebung während das Verschieben-Werkzeug aktiv ist (nicht gespeichert). */
    @Volatile var previewDx = 0
    @Volatile var previewDy = 0
}

class PixelLayer(name: String, var pixels: TileGrid) : RasterLayer(name) {
    override val raster: TileGrid get() = pixels

    /**
     * 16-Bit-Dokumente: untere 8 Bit je Kanal (gleicher Aufbau wie [pixels]); 16-Bit-Wert =
     * oberes Byte · 256 + unteres Byte. Werkzeuge, die nur [pixels] ändern, verfälschen den Wert
     * höchstens innerhalb einer 8-Bit-Stufe; 16-Bit-fähige Befehle schreiben beide Raster.
     */
    var low: TileGrid? = null

    override fun deepCopy(): PixelLayer =
        PixelLayer(tr("{0} Kopie", name), pixels.copy()).also { copyPropsTo(it); it.low = low?.copy() }
}

/** Nicht-destruktive Korrektur, die alles darunter (innerhalb der Gruppe) verändert. */
class AdjustmentLayer(name: String, var adjustment: Adjustment) : Layer(name) {
    private var compiledFor: Adjustment? = null
    private var compiled: RgbMap? = null

    /** Kompilierte Abbildung, zwischengespeichert bis sich die Einstellung ändert. */
    fun rgbMap(): RgbMap {
        val a = adjustment
        if (compiledFor !== a) { compiled = a.compile(); compiledFor = a }
        return compiled!!
    }

    override fun deepCopy(): AdjustmentLayer = AdjustmentLayer(tr("{0} Kopie", name), adjustment).also { copyPropsTo(it) }
}

/**
 * Textebene: bleibt editierbar; die Pixel werden über den [TextRasterizer] der Plattform erzeugt.
 * Aus Photoshop gelesene Texte behalten ihre Originalpixel, bis der Text geändert wird.
 */
class TextLayer(name: String, style: TextStyle, transform: Affine, width: Int, height: Int) : RasterLayer(name) {
    var style: TextStyle = style
        set(v) { field = v; invalidate() }
    var transform: Affine = transform
        set(v) { field = v; invalidate() }
    var docWidth = width
    var docHeight = height

    private var cache: TileGrid? = null
    /** Pixel aus der PSD-Datei, solange der Text unverändert ist. */
    var originalRaster: TileGrid? = null
    /** true, solange Text und Lage noch dem Photoshop-Original entsprechen. */
    var unchangedFromPsd = false

    fun invalidate() { cache = null; originalRaster = null; unchangedFromPsd = false }

    override val raster: TileGrid
        get() {
            originalRaster?.let { return it }
            cache?.let { return it }
            val r = TextRasterizer.current?.render(style, transform, docWidth, docHeight) ?: TileGrid(docWidth, docHeight)
            cache = r
            return r
        }

    /** Bereich, den der Text (ungefähr) einnimmt – für Auswahlrahmen und Transformieren. */
    fun bounds(): IntRect = raster.contentBounds().takeIf { !it.isEmpty } ?: IntRect.EMPTY

    override fun deepCopy(): TextLayer =
        TextLayer(tr("{0} Kopie", name), style, transform, docWidth, docHeight).also { copyPropsTo(it) }
}

/**
 * Smartobjekt: hält das Originalraster in voller Auflösung und eine Transformation.
 * Skalieren und Drehen rechnen immer vom Original – ohne Qualitätsverlust.
 */
class SmartLayer(name: String, val source: TileGrid, transform: Affine, width: Int, height: Int) : RasterLayer(name) {
    var transform: Affine = transform
        set(v) { field = v; cache = null; fastPreview = false }
    /**
     * Verzerrt/perspektivisch: Zielpunkte der vier Ecken der Quelle im Dokument (oben links, oben
     * rechts, unten rechts, unten links); null = nur [transform].
     */
    var warp: DoubleArray? = null
        set(v) { field = v?.copyOf(); cache = null; fastPreview = false }

    /** Abbildung Quelle → Dokument. */
    fun homography(): de.photographics.core.image.Homography = warp?.let {
        de.photographics.core.image.Homography.rectToQuad(source.width.toDouble(), source.height.toDouble(), it)
    } ?: de.photographics.core.image.Homography.of(transform)

    /** Eckpunkte im Dokument (8 Werte). */
    fun corners(): DoubleArray = warp?.copyOf() ?: de.photographics.core.image.Homography.of(transform).corners(source.width.toDouble(), source.height.toDouble())

    fun setPreviewWarp(q: DoubleArray) { warp = q; fastPreview = true }

    /** Lage im Dokument mit [m] (alte → neue Dokumentkoordinaten) nachführen. */
    fun applyDocTransform(m: Affine) {
        val w = warp
        transform = transform.then(m)
        if (w != null) warp = mapQuad(w, m)
    }
    var docWidth = width
    var docHeight = height
    /** Während des Ziehens schneller bilinear rendern, danach bikubisch. */
    var fastPreview = false
        set(v) { if (field != v) { field = v; cache = null } }

    private var cache: TileGrid? = null

    fun setPreviewTransform(t: Affine) { transform = t; fastPreview = true }

    /** Bereits gerenderte Pixel übernehmen (z. B. aus der PSD-Datei). */
    fun setCachedRaster(g: TileGrid) { cache = g }

    /** Während des Transformierens: Vorschau direkt aus der Quelle statt über das volle Raster. */
    val samplesDirectly: Boolean get() = fastPreview && cache == null

    /**
     * Ausgabepixel (x, y) von [rect] (Dokumentpixel x·[step] + [step]/2) bilinear aus der Quelle,
     * aus der zur Verkleinerung passenden Mipmap-Stufe – kostet nur so viel wie die Ausgabe groß ist.
     */
    fun samplePreview(rect: IntRect, step: Int, dx: Int, dy: Int, out: IntArray) {
        val hom = homography()
        val inv = hom.inverse()
        val perOut = step / maxOf(1e-6, hom.scaleFor(source.width.toDouble(), source.height.toDouble()))
        val level = de.photographics.core.image.MipMap.levelFor(perOut.toInt())
        val g = if (level > 0) source.mip(level) else source
        val f = (1 shl level).toDouble()
        val half = step / 2.0
        var i = 0
        for (y in rect.top until rect.bottom) {
            val docY = y * step + half - dy
            for (x in rect.left until rect.right) {
                val docX = x * step + half - dx
                if (inv.w(docX, docY) <= 0) { out[i++] = 0; continue }
                val sx = inv.mapX(docX, docY) / f - 0.5; val sy = inv.mapY(docX, docY) / f - 0.5
                out[i++] = bilinear(g, sx, sy)
            }
        }
    }

    private fun bilinear(g: TileGrid, sx: Double, sy: Double): Int {
        val x0 = kotlin.math.floor(sx).toInt(); val y0 = kotlin.math.floor(sy).toInt()
        if (x0 < -1 || y0 < -1 || x0 >= g.width || y0 >= g.height) return 0
        val fx = (sx - x0).toFloat(); val fy = (sy - y0).toFloat()
        val c00 = g[x0, y0]; val c10 = g[x0 + 1, y0]; val c01 = g[x0, y0 + 1]; val c11 = g[x0 + 1, y0 + 1]
        val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy); val w01 = (1 - fx) * fy; val w11 = fx * fy
        fun a(c: Int) = (c ushr 24).toFloat()
        val a = a(c00) * w00 + a(c10) * w10 + a(c01) * w01 + a(c11) * w11
        if (a < 0.5f) return 0
        fun ch(s: Int) = (((c00 shr s) and 0xFF) * a(c00) * w00 + ((c10 shr s) and 0xFF) * a(c10) * w10 +
            ((c01 shr s) and 0xFF) * a(c01) * w01 + ((c11 shr s) and 0xFF) * a(c11) * w11) / a
        return ((a + 0.5f).toInt().coerceAtMost(255) shl 24) or ((ch(16) + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((ch(8) + 0.5f).toInt().coerceIn(0, 255) shl 8) or (ch(0) + 0.5f).toInt().coerceIn(0, 255)
    }

    fun invalidate() { cache = null }

    override val raster: TileGrid
        get() = cache ?: (warp?.let { Resample.renderProjective(source, homography(), docWidth, docHeight, fast = fastPreview) }
            ?: Resample.renderAffine(source, transform, docWidth, docHeight, fast = fastPreview)).also { cache = it }

    override fun deepCopy(): SmartLayer =
        SmartLayer(tr("{0} Kopie", name), source, transform, docWidth, docHeight).also { copyPropsTo(it); it.warp = warp }

    companion object {
        fun mapQuad(q: DoubleArray, m: Affine) = DoubleArray(8) { i -> if (i % 2 == 0) m.mapX(q[i], q[i + 1]) else m.mapY(q[i - 1], q[i]) }
    }
}

class GroupLayer(name: String) : Layer(name) {
    /** Kinder von unten nach oben (gleiche Reihenfolge wie im PSD). */
    val children: MutableList<Layer> = mutableListOf()
    var expanded = true

    init { blendMode = BlendMode.PASS_THROUGH }

    fun add(layer: Layer, index: Int = children.size) {
        layer.parentGroup?.children?.remove(layer)
        layer.parentGroup = this
        children.add(index.coerceIn(0, children.size), layer)
    }

    fun remove(layer: Layer) {
        if (children.remove(layer)) layer.parentGroup = null
    }

    override fun deepCopy(): GroupLayer {
        val g = GroupLayer(tr("{0} Kopie", name))
        copyPropsTo(g)
        g.expanded = expanded
        for (c in children) g.add(c.deepCopy().also { it.name = c.name })
        return g
    }
}

/**
 * Formebene (Rechteck, Ellipse, Vieleck, Linie, eigener Pfad): Vektorpfad mit Füllung und Kontur,
 * bleibt editierbar. [path] liegt in eigenen Koordinaten, [transform] bildet ins Dokument ab.
 */
class ShapeLayer(
    name: String, path: de.photographics.core.vector.VectorPath, fill: Int?, stroke: de.photographics.core.vector.ShapeStroke?,
    transform: Affine, width: Int, height: Int,
) : RasterLayer(name) {
    var path = path
        set(v) { field = v; invalidate() }
    /** Füllfarbe (ARGB), null = keine Füllung. */
    var fill = fill
        set(v) { field = v; invalidate() }
    var stroke = stroke
        set(v) { field = v; invalidate() }
    var transform = transform
        set(v) { field = v; invalidate() }
    var docWidth = width
    var docHeight = height

    private var cache: TileGrid? = null
    fun invalidate() { cache = null }

    /** Pfad in Dokumentkoordinaten. */
    fun docPath() = path.map(transform)

    /** Eigene Begrenzung (für den Transformationsrahmen): minX, minY, maxX, maxY. */
    fun localBounds(): DoubleArray = path.bounds()

    /** Größe des Transformationsrahmens (Begrenzung des Pfads), mindestens 1 × 1. */
    fun boxSize(): Pair<Double, Double> = localBounds().let { b -> maxOf(1.0, b[2] - b[0]) to maxOf(1.0, b[3] - b[1]) }

    /**
     * Transformation des Rahmens: bildet (0, 0 … [boxSize]) ins Dokument ab. Der Pfad beginnt nicht
     * unbedingt bei (0, 0) (Ankerpunkte nach links/oben gezogen, Formen aus PSD-Dateien).
     */
    var boxTransform: Affine
        get() = localBounds().let { b -> Affine.translate(b[0], b[1]).then(transform) }
        set(v) { val b = localBounds(); transform = Affine.translate(-b[0], -b[1]).then(v) }

    override val raster: TileGrid
        get() = cache ?: render().also { cache = it }

    private fun render(): TileGrid {
        val out = TileGrid(docWidth, docHeight)
        val polys = docPath().flatten()
        if (polys.isEmpty()) return out
        val s = stroke
        val pad = ((s?.width ?: 0f) + 2f).toInt()
        val area = docPath().bounds().let {
            IntRect(kotlin.math.floor(it[0]).toInt() - pad, kotlin.math.floor(it[1]).toInt() - pad, kotlin.math.ceil(it[2]).toInt() + pad, kotlin.math.ceil(it[3]).toInt() + pad)
        }.intersect(IntRect.ofSize(docWidth, docHeight))
        if (area.isEmpty) return out
        val closed = polys.filter { it.second }.map { it.first }
        val fillMask = if (closed.isNotEmpty() && (fill != null || (s != null && s.align != de.photographics.core.vector.ShapeStroke.Align.CENTER)))
            de.photographics.core.vector.PathRaster.fill(docWidth, docHeight, closed, clip = area) else null
        val strokeMask = s?.takeIf { it.width > 0f }?.let {
            de.photographics.core.vector.PathRaster.stroke(docWidth, docHeight, polys, if (it.align == de.photographics.core.vector.ShapeStroke.Align.CENTER) it.width else it.width * 2, area)
        }
        val f = fill
        // Streifenweise: nie den ganzen Bereich im Java-Speicher
        val bandH = maxOf(1, 262_144 / area.width)
        var y0 = area.top
        while (y0 < area.bottom) {
        val band = IntRect(area.left, y0, area.right, minOf(area.bottom, y0 + bandH))
        y0 = band.bottom
        val n = band.width * band.height
        val fm = IntArray(n).also { fillMask?.readRect(band, it) }
        val sm = IntArray(n).also { strokeMask?.readRect(band, it) }
        val px = IntArray(n)
        for (i in 0 until n) {
            var c = if (f != null && fm[i] != 0) de.photographics.core.image.Colors.withAlpha(f, (f ushr 24) * fm[i] / 255) else 0
            if (s != null && sm[i] != 0) {
                val k = when (s.align) {
                    de.photographics.core.vector.ShapeStroke.Align.INSIDE -> sm[i] * fm[i] / 255
                    de.photographics.core.vector.ShapeStroke.Align.OUTSIDE -> sm[i] * (255 - fm[i]) / 255
                    else -> sm[i]
                }
                if (k > 0) c = de.photographics.core.image.Colors.srcOver(c, de.photographics.core.image.Colors.withAlpha(s.color, (s.color ushr 24) * k / 255))
            }
            px[i] = c
        }
        out.writeRect(band, px)
        }
        out.compact()
        return out
    }

    override fun deepCopy(): ShapeLayer = ShapeLayer(tr("{0} Kopie", name), path, fill, stroke, transform, docWidth, docHeight).also { copyPropsTo(it) }
}
