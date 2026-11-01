package de.photographics.gnome

import de.photographics.core.color.SoftProof
import de.photographics.core.doc.Document
import de.photographics.core.doc.Layer
import de.photographics.core.doc.PixelLayer
import de.photographics.core.doc.RasterLayer
import de.photographics.core.doc.SmartLayer
import de.photographics.core.doc.TextLayer
import de.photographics.core.image.Homography
import de.photographics.core.image.IntRect
import de.photographics.core.image.Parallel
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import org.gnome.gdk.MemoryFormat
import org.gnome.gdk.MemoryTexture
import org.gnome.gdk.Texture

/**
 * GPU-Vorschau („Sandwich“) für Live-Änderungen an einer Ebene – Verschieben, Transformieren,
 * Deckkraft: alles darunter und alles darüber wird für den sichtbaren Bereich einmal gerechnet, die
 * Ebene selbst liegt als eigene Textur dazwischen. GTK setzt die drei bei jedem Bild auf der
 * Grafikkarte zusammen (Lage per Matrix, Deckkraft, Mischmodus); der Prozessor rechnet nichts mehr.
 */
class GpuLive(
    val layerId: Int,
    /** Bereich der Texturen [below]/[above] in Dokumentpixeln. */
    val region: IntRect,
    val below: Texture?,
    val layer: Texture,
    val above: Texture?,
    val blend: BlendMode,
) {
    /** Ebenentextur (Pixel) → Dokument. */
    @Volatile var matrix: Homography = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    @Volatile var alpha = 1f
    /** Die Änderung ist übernommen: ausblenden, sobald die Kacheln den neuen Stand zeigen. */
    var dropWhenReady = false
}

/** Was die Anzeige gerade zeigt (im Hauptthread erfasst, für den Aufbau auf dem Engine-Thread). */
class LiveView(val region: IntRect, val step: Int, val proof: SoftProof?, val proofIsDisplay: Boolean, val gamutWarning: Boolean)

/** Aufbau und Matrix der [GpuLive] auf dem Engine-Thread. */
object GpuSandwich {
    /** Gerechnete Pixel; die Texturen entstehen im Hauptthread ([toLive]). */
    class Built(
        val layerId: Int, val region: IntRect,
        val below: Pair<IntRect, ByteArray>?, val layer: Pair<IntRect, ByteArray>, val above: Pair<IntRect, ByteArray>?,
        val blend: BlendMode,
        /** Ebenentextur → Dokument beim Aufbau, und die Lage der Ebene ([placement]) zu diesem Zeitpunkt. */
        val base: Homography, val placedAt: Homography,
    ) {
        fun toLive(): GpuLive {
            fun tex(p: Pair<IntRect, ByteArray>) = MemoryTexture(p.first.width, p.first.height, MemoryFormat.B8G8R8A8, p.second, p.first.width * 4L)
            return GpuLive(layerId, region, below?.let(::tex), tex(layer), above?.let(::tex), blend)
        }
    }

    /** Größte Ebenentextur (Pixel); größere Ebenen werden gröber aufgelöst. */
    private const val LAYER_PIXELS = 12_000_000L
    private const val MAX_SIDE = 8192

    /** Mischmodi, die GTK genauso rechnet wie photographics (sonst gäbe es beim Loslassen einen Sprung). */
    fun gskBlend(m: BlendMode): org.gnome.gsk.BlendMode? = when (m) {
        BlendMode.NORMAL -> org.gnome.gsk.BlendMode.DEFAULT
        BlendMode.MULTIPLY -> org.gnome.gsk.BlendMode.MULTIPLY
        BlendMode.SCREEN -> org.gnome.gsk.BlendMode.SCREEN
        BlendMode.DARKEN -> org.gnome.gsk.BlendMode.DARKEN
        BlendMode.LIGHTEN -> org.gnome.gsk.BlendMode.LIGHTEN
        BlendMode.DIFFERENCE -> org.gnome.gsk.BlendMode.DIFFERENCE
        BlendMode.EXCLUSION -> org.gnome.gsk.BlendMode.EXCLUSION
        BlendMode.OVERLAY -> org.gnome.gsk.BlendMode.OVERLAY
        BlendMode.HARD_LIGHT -> org.gnome.gsk.BlendMode.HARD_LIGHT
        else -> null
    }

    /**
     * Kann [l] über die GPU gezeichnet werden? Ebene auf oberster Stufe ohne Schnittmaske und
     * Effekte, Maske höchstens verbunden (nur bei Pixelebenen), darüber nur normale Rasterebenen –
     * dann ist „darunter + Ebene + darüber“ genau das Ergebnis des Compositors. Eine reine
     * Anzeige-Umrechnung (Dokumentprofil → Bildschirm) geht nur beim normalen Mischmodus, ein
     * Farbproof gar nicht.
     */
    fun eligible(doc: Document, l: Layer, v: LiveView): Boolean {
        if (l !is RasterLayer || l.parent !== doc.root) return false
        if (!l.visible || l.clipped || gskBlend(l.blendMode) == null || v.gamutWarning) return false
        if (v.proof != null && (!v.proofIsDisplay || l.blendMode != BlendMode.NORMAL)) return false
        if (l.effects?.visible == true) return false
        val m = l.mask
        if (m != null && m.enabled && (l !is PixelLayer || !m.linked || m.previewDx != 0 || m.previewDy != 0)) return false
        val kids = doc.root.children
        val i = kids.indexOf(l)
        if (i < 0 || kids.getOrNull(i + 1)?.clipped == true) return false
        for (k in i + 1 until kids.size) {
            val a = kids[k]
            if (!a.visible) continue
            if (a !is RasterLayer || a.effects?.visible == true) return false
            if (!a.clipped && a.blendMode != BlendMode.NORMAL) return false
        }
        return true
    }

    /** Lage der Ebene im Dokument (ohne Vorschau-Verschiebung). */
    private fun placement(l: RasterLayer): Homography = when (l) {
        is SmartLayer -> l.homography()
        is TextLayer -> Homography.of(l.transform)
        is de.photographics.core.doc.ShapeLayer -> Homography.of(l.transform)
        else -> IDENTITY
    }

    private val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

    private fun translate(x: Double, y: Double) = Homography(doubleArrayOf(1.0, 0.0, x, 0.0, 1.0, y, 0.0, 0.0, 1.0))
    private fun scale(s: Double) = Homography(doubleArrayOf(s, 0.0, 0.0, 0.0, s, 0.0, 0.0, 0.0, 1.0))

    fun build(doc: Document, l: RasterLayer, v: LiveView): Built? {
        val region = v.region.intersect(doc.bounds)
        if (region.isEmpty) return null
        val step = maxOf(1, v.step)
        val kids = doc.root.children
        val from = kids.indexOf(l)
        val out = IntRect(region.left / step, region.top / step, (region.right + step - 1) / step, (region.bottom + step - 1) / step)
        if (out.width > MAX_SIDE || out.height > MAX_SIDE) return null
        // Ebene in eigener Auflösung (bei sehr großen Ebenen gröber): ihr Inhalt ohne Vorschau-Verschiebung
        // Smartobjekte direkt aus der Quelle (sonst entstünde hier das ganze Raster in voller Größe)
        val cb = if (l is SmartLayer) l.corners().let { c ->
            IntRect(
                kotlin.math.floor(minOf(c[0], c[2], c[4], c[6])).toInt(), kotlin.math.floor(minOf(c[1], c[3], c[5], c[7])).toInt(),
                kotlin.math.ceil(maxOf(c[0], c[2], c[4], c[6])).toInt(), kotlin.math.ceil(maxOf(c[1], c[3], c[5], c[7])).toInt(),
            ).intersect(doc.bounds)
        } else l.raster.contentBounds()
        if (cb.isEmpty) return null
        var ls = step
        fun layerRect() = IntRect(Math.floorDiv(cb.left, ls), Math.floorDiv(cb.top, ls), (cb.right + ls - 1) / ls, (cb.bottom + ls - 1) / ls)
        while (layerRect().let { it.width.toLong() * it.height > LAYER_PIXELS || it.width > MAX_SIDE || it.height > MAX_SIDE }) ls *= 2
        val lr = layerRect()
        Compositor.prepare(doc, step, out)
        if (ls != step) Compositor.prepare(doc, ls, lr)
        fun stack(start: Int, end: Int): Pair<IntRect, ByteArray>? {
            if (kids.subList(start, end).none { it.visible }) return null
            return out to render(out) { band -> Compositor.renderRangeSampled(doc, band, step, start, end, null).also { px -> v.proof?.apply(px) } }
        }
        val layerPx = lr to render(lr) { band ->
            val px = if (l is SmartLayer) IntArray(band.width * band.height).also { l.samplePreview(band, ls, 0, 0, it) } else Compositor.renderLayerSampled(l, band, ls)
            px.also { v.proof?.apply(it) }
        }
        val base = scale(ls.toDouble()).then(translate(lr.left * ls.toDouble(), lr.top * ls.toDouble()))
        return Built(l.id, IntRect(out.left * step, out.top * step, out.right * step, out.bottom * step),
            stack(0, from), layerPx, stack(from + 1, kids.size), l.blendMode, base, placement(l))
    }

    /** Ebenentextur → Dokument für den aktuellen Stand der Ebene (Lage geändert, Vorschau-Verschiebung). */
    fun matrix(l: RasterLayer, b: Built): Homography =
        b.base.then(b.placedAt.inverse()).then(placement(l)).then(translate(l.previewDx.toDouble(), l.previewDy.toDouble()))

    /** [area] streifenweise auf allen Kernen rechnen, als B,G,R,A-Bytes. */
    private fun render(area: IntRect, compute: (IntRect) -> IntArray): ByteArray {
        val bytes = ByteArray(area.width * area.height * 4)
        val bandH = maxOf(1, 65_536 / maxOf(1, area.width))
        val count = (area.height + bandH - 1) / bandH
        Parallel.bands(count, area.width.toLong() * bandH * 40, compute = { i ->
            val band = IntRect(area.left, area.top + i * bandH, area.right, minOf(area.bottom, area.top + (i + 1) * bandH))
            band to CanvasView.toBytes(compute(band))
        }, consume = { _, (band, b) -> System.arraycopy(b, 0, bytes, (band.top - area.top) * area.width * 4, b.size) })
        return bytes
    }
}
