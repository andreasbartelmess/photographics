package de.photographics.core.tools

import de.photographics.core.tr
import de.photographics.core.image.Blur
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.Compositor
import kotlin.math.roundToInt

/**
 * Pinsel: trägt die Vordergrundfarbe im Malmodus der Einstellungen auf (auf Masken als Grauwert).
 * [background] dient der Farbdynamik (Vorder-/Hintergrund-Zufall).
 */
class BrushTool(private val color: Int, private val background: Int = color) : PaintTool() {
    override val label = tr("Pinsel")
    override val supportsMask = true
    private val gray = (Colors.luma(Colors.r(color) / 255f, Colors.g(color) / 255f, Colors.b(color) / 255f) * 255f).roundToInt()

    override val dynamicsColors get() = color to background

    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int {
        if (ctx.target == PaintTarget.MASK) return (orig + (gray - orig) * amount).roundToInt()
        val c = if (ctx.colorDynamics) ctx.pixelColor else color
        return ctx.settings.mode.paint(orig, c, amount)
    }
}

/** Radiergummi: verringert die Deckkraft; auf Masken wird weiß (sichtbar) gemalt. */
class EraserTool : PaintTool() {
    override val label = tr("Radiergummi")
    override val supportsMask = true

    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int =
        if (ctx.target == PaintTarget.MASK) (orig + (255 - orig) * amount).roundToInt()
        else Colors.withAlpha(orig, (Colors.a(orig) * (1f - amount)).roundToInt())
}

/**
 * Quelle für Stempel und Reparatur: entweder die aktive Ebene (Zustand vor dem Strich) oder das
 * zusammengesetzte Bild aller übrigen Ebenen – ideal, um auf einer leeren Ebene zu retuschieren.
 */
class SourceSampler(private val ctx: StrokeContext, private val sampleAll: Boolean) {
    private val cache = HashMap<Int, IntArray>()
    private val w = ctx.grid.width
    private val h = ctx.grid.height
    private val tilesX = (w + TileGrid.TILE - 1) shr TileGrid.SHIFT

    fun sample(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0
        if (!sampleAll) return ctx.original(x, y)
        val tx = x shr TileGrid.SHIFT; val ty = y shr TileGrid.SHIFT
        val tile = cache.getOrPut(ty * tilesX + tx) {
            val r = IntRect(tx shl TileGrid.SHIFT, ty shl TileGrid.SHIFT, minOf((tx + 1) shl TileGrid.SHIFT, w), minOf((ty + 1) shl TileGrid.SHIFT, h))
            val wasVisible = ctx.layer.visible
            ctx.layer.visible = false
            try {
                val px = Compositor.renderArgb(ctx.doc, r)
                IntArray(TileGrid.TILE * TileGrid.TILE).also { t ->
                    for (yy in 0 until r.height) System.arraycopy(px, yy * r.width, t, yy shl TileGrid.SHIFT, r.width)
                }
            } finally {
                ctx.layer.visible = wasVisible
            }
        }
        return tile[((y and TileGrid.MASK) shl TileGrid.SHIFT) or (x and TileGrid.MASK)]
    }

    fun read(rect: IntRect, out: IntArray) {
        var i = 0
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) out[i++] = sample(x, y)
    }
}

/** Kopierstempel: überträgt Pixel aus dem Quellbereich (Versatz dx/dy). */
class CloneTool(private val dx: Int, private val dy: Int, private val sampleAll: Boolean) : PaintTool() {
    override val label = tr("Kopierstempel")
    private lateinit var source: SourceSampler

    override fun begin(ctx: StrokeContext) { source = SourceSampler(ctx, sampleAll) }

    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int {
        val s = source.sample(x + dx, y + dy)
        return Colors.srcOver(orig, Colors.withAlpha(s, (Colors.a(s) * amount).roundToInt()))
    }
}

/**
 * Basis für Werkzeuge, die eine weichgezeichnete Version des Originals brauchen.
 * Die Weichzeichnung wird kachelweise beim ersten Zugriff berechnet.
 */
abstract class BlurredSourceTool(private val sigma: Float) : PaintTool() {
    private val cache = HashMap<Int, IntArray>()

    protected fun blurred(ctx: StrokeContext, x: Int, y: Int): Int {
        val g = ctx.grid
        val tx = x shr TileGrid.SHIFT; val ty = y shr TileGrid.SHIFT
        val tile = cache.getOrPut(ty * g.tilesX + tx) {
            val r = g.tileRect(tx, ty)
            val m = (sigma * 3).toInt() + 1
            val big = r.inset(-m).intersect(IntRect.ofSize(g.width, g.height))
            val src = IntArray(big.width * big.height)
            ctx.tx.readOriginal(big, src)
            val bl = Blur.gaussArgb(src, big.width, big.height, sigma)
            IntArray(TileGrid.TILE * TileGrid.TILE).also { t ->
                for (yy in 0 until r.height) {
                    System.arraycopy(bl, (yy + r.top - big.top) * big.width + (r.left - big.left), t, yy shl TileGrid.SHIFT, r.width)
                }
            }
        }
        return tile[((y and TileGrid.MASK) shl TileGrid.SHIFT) or (x and TileGrid.MASK)]
    }
}

class BlurTool(sigma: Float = 3f, private val strength: Float = 1f) : BlurredSourceTool(sigma) {
    override val label = tr("Weichzeichner")
    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int =
        Colors.lerp(orig, blurred(ctx, x, y), amount * strength)
}

/** Scharfzeichner per Unscharf-Maskieren. */
class SharpenTool(sigma: Float = 1.5f, private val strength: Float = 1f) : BlurredSourceTool(sigma) {
    override val label = tr("Scharfzeichner")
    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int {
        val b = blurred(ctx, x, y)
        val k = amount * strength * 1.5f
        fun ch(o: Int, bl: Int) = (o + (o - bl) * k).roundToInt()
        return Colors.argb(Colors.a(orig), ch(Colors.r(orig), Colors.r(b)), ch(Colors.g(orig), Colors.g(b)), ch(Colors.b(orig), Colors.b(b)))
    }
}

enum class ToneRange(val label: String) { SHADOWS(tr("Tiefen")), MIDTONES(tr("Mitteltöne")), HIGHLIGHTS(tr("Lichter")) }

/** Abwedler (aufhellen) bzw. Nachbelichter (abdunkeln), wirkt gewichtet auf einen Tonwertbereich. */
class DodgeBurnTool(private val dodge: Boolean, private val range: ToneRange, private val exposure: Float = 0.5f) : PaintTool() {
    override val label = if (dodge) tr("Abwedler") else tr("Nachbelichter")

    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int {
        val r = Colors.r(orig) / 255f; val g = Colors.g(orig) / 255f; val b = Colors.b(orig) / 255f
        val l = Colors.luma(r, g, b)
        val weight = when (range) {
            ToneRange.SHADOWS -> (1f - l) * (1f - l)
            ToneRange.MIDTONES -> 4f * l * (1f - l)
            ToneRange.HIGHLIGHTS -> l * l
        }
        val k = amount * exposure * weight
        fun f(c: Float) = if (dodge) c + (1f - c) * k else c * (1f - k)
        return Colors.argbF(Colors.a(orig) / 255f, f(r), f(g), f(b))
    }
}

/** Schwamm: Sättigung erhöhen oder verringern. */
class SpongeTool(private val saturate: Boolean, private val strength: Float = 0.5f) : PaintTool() {
    override val label = if (saturate) tr("Schwamm (sättigen)") else tr("Schwamm (entsättigen)")
    private val hsl = FloatArray(3)
    private val rgb = FloatArray(3)

    override fun apply(ctx: StrokeContext, x: Int, y: Int, orig: Int, amount: Float): Int {
        Colors.rgbToHsl(Colors.r(orig) / 255f, Colors.g(orig) / 255f, Colors.b(orig) / 255f, hsl)
        val k = amount * strength
        val s = if (saturate) hsl[1] + (1f - hsl[1]) * k else hsl[1] * (1f - k)
        Colors.hslToRgb(hsl[0], s, hsl[2], rgb)
        return Colors.argbF(Colors.a(orig) / 255f, rgb[0], rgb[1], rgb[2])
    }
}
