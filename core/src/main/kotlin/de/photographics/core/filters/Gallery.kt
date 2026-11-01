package de.photographics.core.filters

import de.photographics.core.image.Blur
import de.photographics.core.image.Colors
import de.photographics.core.tr
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filtergalerie wie in Photoshop: künstlerische Effekte in sechs Gruppen, als Stapel nacheinander
 * angewendet ([GalleryChain]). Die Algorithmen sind eigene; Rauschen, Strukturen und Zellen hängen
 * von Dokumentkoordinaten ab, damit streifenweise Berechnung nahtlos bleibt.
 */
enum class GalleryCategory(val label: String) {
    ARTISTIC(tr("Kunstfilter")),
    BRUSH(tr("Malfilter")),
    DISTORT(tr("Verzerrungsfilter")),
    SKETCH(tr("Zeichenfilter")),
    STYLIZE(tr("Stilisierungsfilter")),
    TEXTURE(tr("Strukturierungsfilter")),
}

/** Regler eines Effekts; [options] macht daraus eine Auswahlliste (Wert = Index), [pixels] = Länge in Pixeln. */
class GalleryParam(
    val label: String, val min: Float, val max: Float, val default: Float,
    val options: List<String>? = null, val pixels: Boolean = false,
)

/** Vorder- und Hintergrundfarbe (Zeichenfilter) und Maßstab (< 1 für verkleinerte Vorschauen). */
class GalleryContext(val fg: Int = 0xFF000000.toInt(), val bg: Int = 0xFFFFFFFF.toInt(), val scale: Float = 1f)

class GallerySpec internal constructor(
    val id: String, val label: String, val category: GalleryCategory, val params: List<GalleryParam>,
    /** Nutzt Vorder-/Hintergrundfarbe. */
    val usesColors: Boolean = false,
    internal val make: (V) -> Effect,
) {
    fun defaults() = FloatArray(params.size) { params[it].default }
}

/** Ein Effekt im Stapel. */
data class GalleryEffect(val spec: GallerySpec, val values: FloatArray, val visible: Boolean = true) {
    override fun equals(other: Any?) = other is GalleryEffect && other.spec === spec && other.values.contentEquals(values) && other.visible == visible
    override fun hashCode() = spec.id.hashCode() * 31 + values.contentHashCode() + if (visible) 1 else 0
}

/** Werte eines Effekts mit Maßstab: [px] rechnet Pixellängen auf die Vorschaugröße um. */
class V internal constructor(private val v: FloatArray, val ctx: GalleryContext) {
    operator fun get(i: Int) = v[i]
    fun int(i: Int) = v[i].roundToInt()
    fun px(i: Int) = v[i] * ctx.scale
    fun px(f: Float) = f * ctx.scale
}

/** Ein berechneter Effekt: [margin] Pixel Rand, [run] auf einem Ausschnitt mit Dokumentlage. */
internal class Effect(val margin: Int, val run: (Img) -> IntArray)

/** Ausschnitt: ARGB-Pixel, Größe, Lage im Dokument. */
internal class Img(val px: IntArray, val w: Int, val h: Int, val ox: Int, val oy: Int) {
    val n get() = w * h
    fun lum(): FloatArray = FloatArray(n) { G.lum(px[it]) }
    fun channel(shift: Int) = FloatArray(n) { ((px[it] shr shift) and 0xFF).toFloat() }
    /** Ergebnis mit dem Alpha des Originals. */
    inline fun map(f: (Int, Int, Int) -> Int): IntArray = IntArray(n) { i -> (px[i] and 0xFF000000.toInt()) or (f(i, i % w, i / w) and 0xFFFFFF) }
}

/** Stapel der Filtergalerie als ein Filter (sichtbare Effekte nacheinander). */
class GalleryChain(effects: List<GalleryEffect>, ctx: GalleryContext = GalleryContext()) : GeometryFilter {
    private val steps: List<Effect> = effects.filter { it.visible }.map { it.spec.make(V(it.values, ctx)) }
    override val label = tr("Filtergalerie")
    override val margin: Int = steps.sumOf { it.margin }.coerceAtMost(2048)
    val isEmpty get() = steps.isEmpty()

    override fun process(px: IntArray, w: Int, h: Int, offX: Int, offY: Int, docW: Int, docH: Int): IntArray {
        var cur = px
        for (s in steps) cur = s.run(Img(cur, w, h, offX, offY))
        return cur
    }
}

// ---------------------------------------------------------------- Bausteine

internal object G {
    fun lum(c: Int) = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
    fun c8(v: Float) = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()
    fun rgb(r: Float, g: Float, b: Float) = (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
    fun r(c: Int) = ((c shr 16) and 0xFF).toFloat()
    fun g(c: Int) = ((c shr 8) and 0xFF).toFloat()
    fun b(c: Int) = (c and 0xFF).toFloat()
    fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    fun mix(a: Int, b: Int, t: Float): Int { val u = t.coerceIn(0f, 1f); return rgb(r(a) + (r(b) - r(a)) * u, g(a) + (g(b) - g(a)) * u, b(a) + (b(b) - b(a)) * u) }
    /** Farbe [c] mit Faktor [k] (abdunkeln < 1 < aufhellen). */
    fun scale(c: Int, k: Float) = rgb(r(c) * k, g(c) * k, b(c) * k)
    fun screen(c: Int, add: Float) = rgb(255 - (255 - r(c)) * (1 - add / 255f), 255 - (255 - g(c)) * (1 - add / 255f), 255 - (255 - b(c)) * (1 - add / 255f))

    /** Gleichverteilte Zufallszahl 0..1 je Dokumentpixel. */
    fun hash(x: Int, y: Int, salt: Int): Float {
        var h = x * 374761393 + y * 668265263 + salt * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    /** Weiches Wertrauschen 0..1 mit Gitterabstand 1 (Dokumentkoordinaten / Maßstab übergeben). */
    fun noise(x: Float, y: Float, salt: Int): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = hash(x0, y0, salt); val b = hash(x0 + 1, y0, salt); val c = hash(x0, y0 + 1, salt); val d = hash(x0 + 1, y0 + 1, salt)
        return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy
    }

    fun fbm(x: Float, y: Float, octaves: Int, salt: Int): Float {
        var sum = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        for (o in 0 until octaves) { sum += noise(x * f, y * f, salt + o * 31) * amp; norm += amp; amp *= 0.5f; f *= 2f }
        return sum / norm
    }

    /**
     * Scharfe Trennung von [p] an [thr] mit einem Pixel Kantenglättung (Abstand zur Kante über den
     * Gradienten): 255 wo [p] über der Schwelle liegt (bei [invert] darunter), sonst 0.
     */
    fun crisp(p: FloatArray, w: Int, h: Int, thr: Float, invert: Boolean): FloatArray = FloatArray(p.size) { i ->
        val x = i % w; val y = i / w
        val xm = max(0, x - 1); val xp = min(w - 1, x + 1); val ym = max(0, y - 1); val yp = min(h - 1, y + 1)
        val gx = (p[y * w + xp] - p[y * w + xm]) / max(1, xp - xm)
        val gy = (p[yp * w + x] - p[ym * w + x]) / max(1, yp - ym)
        val d = (p[i] - thr) / max(sqrt(gx * gx + gy * gy), 1e-3f)
        val a = (d + 0.5f).coerceIn(0f, 1f)
        (if (invert) 1f - a else a) * 255f
    }

    fun blurred(p: FloatArray, w: Int, h: Int, sigma: Float): FloatArray = p.copyOf().also { if (sigma > 0.05f) Blur.gaussPlane(it, w, h, sigma) }

    /** Ränder für einen Gauß mit [sigma]. */
    fun gaussMargin(sigma: Float) = (sigma * 3f).roundToInt() + 2

    /** Betrag des Sobel-Gradienten (0..~1020). */
    fun edges(p: FloatArray, w: Int, h: Int): FloatArray = FloatArray(p.size) { i ->
        val x = i % w; val y = i / w
        val xm = max(0, x - 1); val xp = min(w - 1, x + 1); val ym = max(0, y - 1); val yp = min(h - 1, y + 1)
        fun at(xx: Int, yy: Int) = p[yy * w + xx]
        val gx = at(xp, ym) + 2 * at(xp, y) + at(xp, yp) - at(xm, ym) - 2 * at(xm, y) - at(xm, yp)
        val gy = at(xm, yp) + 2 * at(x, yp) + at(xp, yp) - at(xm, ym) - 2 * at(x, ym) - at(xp, ym)
        sqrt(gx * gx + gy * gy)
    }

    /** Gradient (Ableitung nach x und y) über zentrale Differenzen. */
    fun gradient(p: FloatArray, w: Int, h: Int): Pair<FloatArray, FloatArray> {
        val gx = FloatArray(p.size); val gy = FloatArray(p.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            gx[i] = (p[y * w + min(w - 1, x + 1)] - p[y * w + max(0, x - 1)]) * 0.5f
            gy[i] = (p[min(h - 1, y + 1) * w + x] - p[max(0, y - 1) * w + x]) * 0.5f
        }
        return gx to gy
    }

    /** Lichteinfall auf ein Höhenfeld (Relief), 0 = flach; [angle] in Grad, [depth] Stärke. */
    fun relief(height: FloatArray, w: Int, h: Int, angle: Float, depth: Float): FloatArray {
        val (gx, gy) = gradient(height, w, h)
        val a = Math.toRadians(angle.toDouble())
        val lx = cos(a).toFloat(); val ly = -sin(a).toFloat()
        return FloatArray(height.size) { i -> -(gx[i] * lx + gy[i] * ly) * depth }
    }

    /** Weichzeichnung entlang einer Richtung (Grad) über [len] Pixel, je Kanal. */
    fun streak(p: FloatArray, w: Int, h: Int, angle: Float, len: Float): FloatArray {
        val n = max(1, (len / 2).roundToInt())
        if (len < 1f) return p.copyOf()
        val a = Math.toRadians(angle.toDouble())
        val dx = IntArray(2 * n + 1) { k -> ((k - n) * cos(a) * len / (2 * n)).roundToInt() }
        val dy = IntArray(2 * n + 1) { k -> (-(k - n) * sin(a) * len / (2 * n)).roundToInt() }
        val out = FloatArray(p.size)
        val inv = 1f / (2 * n + 1)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (k in dx.indices) s += p[(y + dy[k]).coerceIn(0, h - 1) * w + (x + dx[k]).coerceIn(0, w - 1)]
            out[y * w + x] = s * inv
        }
        return out
    }

    fun streakArgb(img: Img, angle: Float, len: Float): Triple<FloatArray, FloatArray, FloatArray> =
        Triple(streak(img.channel(16), img.w, img.h, angle, len), streak(img.channel(8), img.w, img.h, angle, len), streak(img.channel(0), img.w, img.h, angle, len))

    /**
     * Kuwahara (Ölfarben-Glättung) über Integralbilder: je Pixel die Farbe des Quadranten mit der
     * geringsten Helligkeitsstreuung – Kosten unabhängig vom Radius.
     */
    fun kuwahara(img: Img, radius: Int): IntArray {
        val w = img.w; val h = img.h; val r = max(1, radius)
        val W = w + 1
        // Ganzzahlige Summen: exakt, damit Streifen unabhängig von ihrer Lage gleich entscheiden
        val sr = LongArray(W * (h + 1)); val sg = LongArray(W * (h + 1)); val sb = LongArray(W * (h + 1))
        val sl = LongArray(W * (h + 1)); val sl2 = LongArray(W * (h + 1))
        for (y in 0 until h) {
            var ar = 0L; var ag = 0L; var ab = 0L; var al = 0L; var al2 = 0L
            for (x in 0 until w) {
                val c = img.px[y * w + x]
                val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                val l = (299 * cr + 587 * cg + 114 * cb) / 1000L
                ar += cr; ag += cg; ab += cb; al += l; al2 += l * l
                val o = (y + 1) * W + x + 1; val u = y * W + x + 1
                sr[o] = sr[u] + ar; sg[o] = sg[u] + ag; sb[o] = sb[u] + ab; sl[o] = sl[u] + al; sl2[o] = sl2[u] + al2
            }
        }
        fun sum(t: LongArray, x0: Int, y0: Int, x1: Int, y1: Int) = t[y1 * W + x1] - t[y0 * W + x1] - t[y1 * W + x0] + t[y0 * W + x0]
        return img.map { _, x, y ->
            var best = Double.MAX_VALUE; var res = 0
            for (q in 0..3) {
                val x0 = if (q and 1 == 0) max(0, x - r) else x; val x1 = if (q and 1 == 0) x + 1 else min(w, x + r + 1)
                val y0 = if (q < 2) max(0, y - r) else y; val y1 = if (q < 2) y + 1 else min(h, y + r + 1)
                val cnt = ((x1 - x0) * (y1 - y0)).toLong()
                val m = sum(sl, x0, y0, x1, y1)
                val v = (cnt * sum(sl2, x0, y0, x1, y1) - m * m).toDouble() / (cnt * cnt)
                if (v < best) {
                    best = v
                    res = (((sum(sr, x0, y0, x1, y1) + cnt / 2) / cnt).toInt() shl 16) or (((sum(sg, x0, y0, x1, y1) + cnt / 2) / cnt).toInt() shl 8) or ((sum(sb, x0, y0, x1, y1) + cnt / 2) / cnt).toInt()
                }
            }
            res
        }
    }

    fun posterize(v: Float, levels: Int): Float { val l = max(2, levels) - 1; return (v / 255f * l).roundToInt() * 255f / l }

    /**
     * Zufällig verschobenes Gitter (Voronoi) in Dokumentkoordinaten: nächster und zweitnächster
     * Zellpunkt zu ([x], [y]); liefert (Abstand1, Abstand2, ZellpunktX, ZellpunktY).
     */
    fun cells(x: Float, y: Float, size: Float, jitter: Float, salt: Int, out: FloatArray) {
        val cx = floor(x / size).toInt(); val cy = floor(y / size).toInt()
        var d1 = Float.MAX_VALUE; var d2 = Float.MAX_VALUE; var px = 0f; var py = 0f
        for (j in -1..1) for (i in -1..1) {
            val gx = cx + i; val gy = cy + j
            val sx = (gx + 0.5f + (hash(gx, gy, salt) - 0.5f) * jitter) * size
            val sy = (gy + 0.5f + (hash(gx, gy, salt + 7) - 0.5f) * jitter) * size
            val d = (sx - x) * (sx - x) + (sy - y) * (sy - y)
            if (d < d1) { d2 = d1; d1 = d; px = sx; py = sy } else if (d < d2) d2 = d
        }
        out[0] = sqrt(d1); out[1] = sqrt(d2); out[2] = px; out[3] = py
    }

    /** Prozedurale Struktur (Höhe 0..1) in Dokumentkoordinaten: Leinwand, Sackleinen, Ziegel, Sandstein. */
    fun texture(kind: Int, x: Float, y: Float, scale: Float): Float {
        val s = max(0.05f, scale)
        val u = x / s; val v = y / s
        return when (kind) {
            0 -> { // Leinwand: Kett- und Schussfäden
                val a = 0.5f + 0.5f * sin(u * 1.6f) * sin(v * 0.4f); val b = 0.5f + 0.5f * sin(v * 1.6f) * sin(u * 0.4f)
                (max(a, b) * 0.8f + noise(u * 0.7f, v * 0.7f, 5) * 0.2f)
            }
            1 -> { // Sackleinen: gröber, unregelmäßiger
                val a = abs(sin(u * 0.8f + noise(u * 0.1f, v * 0.1f, 9) * 3f)); val b = abs(sin(v * 0.8f + noise(u * 0.1f, v * 0.1f, 11) * 3f))
                (a * b) * 0.7f + noise(u * 0.5f, v * 0.5f, 13) * 0.3f
            }
            2 -> { // Ziegel: versetzte Reihen mit Fugen
                val bh = 14f; val bw = 30f
                val row = floor(v / bh).toInt()
                val off = if (row and 1 == 0) 0f else bw / 2
                val fx = ((u + off) % bw + bw) % bw; val fy = ((v % bh) + bh) % bh
                val mortar = min(smooth(0f, 2f, fx) * smooth(0f, 2f, bw - fx), smooth(0f, 2f, fy) * smooth(0f, 2f, bh - fy))
                mortar * (0.85f + noise(u * 0.3f, v * 0.3f, row) * 0.15f)
            }
            else -> fbm(u * 0.35f, v * 0.35f, 4, 17) // Sandstein
        }
    }

    val GLOW_NAMES = listOf(tr("Blau"), tr("Cyan"), tr("Grün"), tr("Gelb"), tr("Orange"), tr("Rot"), tr("Magenta"), tr("Weiß"))
    val GLOWS = intArrayOf(0x2A6CFF, 0x30E0FF, 0x40FF60, 0xFFF040, 0xFF9020, 0xFF3030, 0xFF40E0, 0xFFFFFF)
    val TEXTURES = listOf(tr("Leinwand"), tr("Sackleinen"), tr("Ziegel"), tr("Sandstein"))
    val LIGHTS = listOf(tr("Oben"), tr("Oben rechts"), tr("Rechts"), tr("Unten rechts"), tr("Unten"), tr("Unten links"), tr("Links"), tr("Oben links"))
    fun lightAngle(i: Int) = 90f - i * 45f

    /** Struktur als Relief auf ein Bild legen (wie Photoshops „Mit Struktur versehen“). */
    fun texturize(img: Img, src: IntArray, kind: Int, scale: Float, relief: Float, light: Int, ctxScale: Float): IntArray {
        val w = img.w; val h = img.h
        val hf = FloatArray(img.n) { i -> texture(kind, (img.ox + i % w) / ctxScale, (img.oy + i / w) / ctxScale, scale / 100f) * 255f }
        val sh = relief(hf, w, h, lightAngle(light), relief / 25f)
        return IntArray(img.n) { i -> (img.px[i] and 0xFF000000.toInt()) or (src[i].let { c -> rgb(r(c) + sh[i], g(c) + sh[i], b(c) + sh[i]) }) }
    }
}

// ---------------------------------------------------------------- Effekte

private fun p(label: String, min: Float, max: Float, def: Float, pixels: Boolean = false) = GalleryParam(label, min, max, def, pixels = pixels)
private fun choice(label: String, options: List<String>, def: Int = 0) = GalleryParam(label, 0f, (options.size - 1).toFloat(), def.toFloat(), options)

object Gallery {
    private val C = GalleryCategory.entries

    /** Je Gruppe alphabetisch nach der angezeigten Bezeichnung, wie in Photoshops Filtergalerie. */
    val specs: List<GallerySpec> by lazy {
        val coll = java.text.Collator.getInstance(java.util.Locale.GERMAN)
        listOf(artistic(), brush(), distort(), sketch(), stylize(), texture()).flatMap { g -> g.sortedWith { a, b -> coll.compare(a.label, b.label) } }
    }

    fun byId(id: String) = specs.firstOrNull { it.id == id }

    private fun artistic() = listOf(
        GallerySpec("watercolor", tr("Aquarell"), GalleryCategory.ARTISTIC, listOf(p(tr("Pinseldetail"), 1f, 14f, 9f), p(tr("Schattenintensität"), 0f, 10f, 1f), p(tr("Struktur"), 1f, 3f, 1f))) { v ->
            val sigma = v.px((15 - v[0]) * 0.35f + 0.5f)
            Effect(G.gaussMargin(sigma) + 2) { img ->
                val w = img.w; val h = img.h
                val rr = G.blurred(img.channel(16), w, h, sigma); val gg = G.blurred(img.channel(8), w, h, sigma); val bb = G.blurred(img.channel(0), w, h, sigma)
                val l = FloatArray(img.n) { 0.299f * rr[it] + 0.587f * gg[it] + 0.114f * bb[it] }
                val e = G.edges(l, w, h)
                val levels = 5 + v.int(0)
                img.map { i, x, y ->
                    // Farbpfützen: weich abgestufte Töne, dunkle Ränder an Kanten, Papier
                    val dark = 1f - v[1] * 0.03f - min(0.5f, e[i] / 255f * (0.2f + v[1] * 0.05f))
                    val paper = 1f + (G.noise((img.ox + x) / v.px(3f), (img.oy + y) / v.px(3f), 3) - 0.5f) * 0.04f * v[2]
                    G.rgb(G.posterize(rr[i], levels) * 0.6f + rr[i] * 0.4f, G.posterize(gg[i], levels) * 0.6f + gg[i] * 0.4f, G.posterize(bb[i], levels) * 0.6f + bb[i] * 0.4f).let { G.scale(it, dark * paper) }
                }
            }
        },
        GallerySpec("cutout", tr("Farbpapier-Collage"), GalleryCategory.ARTISTIC, listOf(p(tr("Anzahl Stufen"), 2f, 8f, 4f), p(tr("Kantenvereinfachung"), 0f, 10f, 4f), p(tr("Kantenschärfe"), 1f, 3f, 2f))) { v ->
            val r = max(1, v.px(v[1] * 0.8f + 1f).roundToInt())
            Effect(r + 2) { img ->
                val k = G.kuwahara(img, r)
                val levels = v.int(0) + 1
                // Helligkeit in wenige Stufen, Farbton bleibt: flache Papierflächen in den Bildfarben
                IntArray(img.n) { i ->
                    val c = k[i]
                    val l = max(1f, G.lum(c))
                    val q = G.posterize(l, levels).coerceAtLeast(255f / (levels * 2)) * (0.9f + 0.05f * v[2]) + l * (0.15f - 0.05f * v[2])
                    (img.px[i] and 0xFF000000.toInt()) or G.scale(c, q / l)
                }
            }
        },
        GallerySpec("dry-brush", tr("Grobe Malerei"), GalleryCategory.ARTISTIC, listOf(p(tr("Pinselgröße"), 0f, 10f, 2f), p(tr("Pinseldetail"), 0f, 10f, 8f), p(tr("Struktur"), 1f, 3f, 1f))) { v ->
            val r = max(1, v.px(v[0] * 1.2f + 2f).roundToInt())
            Effect(r + 2) { img ->
                val k = G.kuwahara(img, r)
                val levels = 4 + v.int(1)
                IntArray(img.n) { i ->
                    val c = k[i]
                    val t = 1f + (G.hash(img.ox + i % img.w, img.oy + i / img.w, 21) - 0.5f) * 0.06f * v[2]
                    (img.px[i] and 0xFF000000.toInt()) or G.rgb(G.posterize(G.r(c), levels) * t, G.posterize(G.g(c), levels) * t, G.posterize(G.b(c), levels) * t)
                }
            }
        },
        GallerySpec("film-grain", tr("Körnung (Film)"), GalleryCategory.ARTISTIC, listOf(p(tr("Körnung"), 0f, 20f, 4f), p(tr("Lichterbereich"), 0f, 20f, 0f), p(tr("Intensität"), 0f, 10f, 10f))) { v ->
            Effect(0) { img ->
                img.map { i, x, y ->
                    val c = img.px[i]; val l = G.lum(c)
                    // Korn vor allem in Schatten und Mitteltönen, Lichter strahlen etwas auf
                    val grain = (G.hash(img.ox + x, img.oy + y, 33) - 0.5f) * v[0] * 6f * (1f - l / 300f)
                    val glow = if (l > 255 - v[1] * 8) (l - (255 - v[1] * 8)) * v[2] * 0.08f else 0f
                    G.rgb(G.r(c) + grain + glow, G.g(c) + grain + glow, G.b(c) + grain + glow)
                }
            }
        },
        GallerySpec("fresco", tr("Fresko"), GalleryCategory.ARTISTIC, listOf(p(tr("Pinselgröße"), 0f, 10f, 2f), p(tr("Pinseldetail"), 0f, 10f, 8f), p(tr("Struktur"), 1f, 3f, 1f))) { v ->
            val r = max(1, v.px(v[0] + 2f).roundToInt())
            Effect(r + 8) { img ->
                val k = G.kuwahara(img, r)
                val l = FloatArray(img.n) { G.lum(k[it]) }
                val bl = G.blurred(l, img.w, img.h, v.px(2f))
                IntArray(img.n) { i ->
                    // Starker lokaler Kontrast und dunkle Farbtupfer wie auf nassem Putz
                    val c = k[i]
                    val boost = 1f + (l[i] - bl[i]) / 255f * (1.5f + v[1] * 0.15f) - 0.08f
                    val t = 1f + (G.noise((img.ox + i % img.w) / v.px(2f), (img.oy + i / img.w) / v.px(2f), 41) - 0.5f) * 0.12f * v[2]
                    (img.px[i] and 0xFF000000.toInt()) or G.scale(c, boost * t)
                }
            }
        },
        GallerySpec("neon-glow", tr("Neonschein"), GalleryCategory.ARTISTIC, listOf(p(tr("Größe"), -24f, 24f, 5f, pixels = true), p(tr("Helligkeit"), 0f, 50f, 15f), choice(tr("Leuchtfarbe"), G.GLOW_NAMES)), usesColors = true) { v ->
            val sigma = max(0.5f, abs(v.px(0)) * 0.6f)
            Effect(G.gaussMargin(sigma)) { img ->
                val l = img.lum()
                val e = G.blurred(G.edges(l, img.w, img.h), img.w, img.h, sigma)
                val glow = G.GLOWS[v.int(2)]
                img.map { i, _, _ ->
                    // Einfarbiges Bild zwischen Vorder- und Hintergrundfarbe, leuchtende Kanten (innen oder außen)
                    val t = l[i] / 255f
                    val mono = G.mix(v.ctx.fg, v.ctx.bg, t * 0.85f)
                    val g = (if (v[0] >= 0) e[i] / 255f else (1 - t) * e[i] / 128f) * v[1] / 25f
                    G.mix(mono, glow, g)
                }
            }
        },
        GallerySpec("paint-daubs", tr("Ölfarbe getupft"), GalleryCategory.ARTISTIC, listOf(p(tr("Pinselgröße"), 1f, 50f, 8f, pixels = true), p(tr("Schärfe"), 0f, 40f, 7f))) { v ->
            val r = max(1, (v.px(0) / 3f).roundToInt())
            Effect(r + 4) { img ->
                val k = G.kuwahara(img, r)
                val l = FloatArray(img.n) { G.lum(k[it]) }
                val bl = G.blurred(l, img.w, img.h, 1.2f)
                IntArray(img.n) { i -> (img.px[i] and 0xFF000000.toInt()) or G.scale(k[i], 1f + (l[i] - bl[i]) / max(1f, l[i]) * v[1] * 0.08f) }
            }
        },
        GallerySpec("palette-knife", tr("Spachtel"), GalleryCategory.ARTISTIC, listOf(p(tr("Strichgröße"), 1f, 50f, 25f, pixels = true), p(tr("Strichdetail"), 1f, 3f, 3f), p(tr("Weichheit"), 0f, 10f, 0f))) { v ->
            val r = max(1, (v.px(0) / 5f).roundToInt())
            val sigma = v.px(v[2] * 0.3f)
            Effect(r + G.gaussMargin(sigma)) { img ->
                val k = G.kuwahara(img, r)
                val levels = 4 + v.int(1) * 3
                val soft = if (sigma > 0.05f) Blur.gaussArgb(k, img.w, img.h, sigma) else k
                IntArray(img.n) { i -> soft[i].let { c -> (img.px[i] and 0xFF000000.toInt()) or G.rgb(G.posterize(G.r(c), levels), G.posterize(G.g(c), levels), G.posterize(G.b(c), levels)) } }
            }
        },
        GallerySpec("plastic-wrap", tr("Kunststofffolie"), GalleryCategory.ARTISTIC, listOf(p(tr("Lichterstärke"), 0f, 20f, 15f), p(tr("Detail"), 1f, 15f, 9f), p(tr("Glättung"), 1f, 15f, 7f))) { v ->
            val sigma = v.px(v[2] * 0.6f + 0.5f)
            Effect(G.gaussMargin(sigma) + 2) { img ->
                val hgt = G.blurred(img.lum(), img.w, img.h, sigma)
                val (gx, gy) = G.gradient(hgt, img.w, img.h)
                val sharp = 12f + v[1] * 3f
                img.map { i, _, _ ->
                    // Spiegelndes Glanzlicht der „Folie“ auf dem Höhenfeld der Helligkeit
                    val nx = -gx[i] * 0.08f; val ny = -gy[i] * 0.08f
                    val ndotl = ((nx * -0.6f + ny * -0.6f + 0.53f) / sqrt(nx * nx + ny * ny + 0.28f)).coerceIn(0f, 1f)
                    G.screen(img.px[i], ndotl.pow(sharp) * v[0] * 8f)
                }
            }
        },
        GallerySpec("poster-edges", tr("Tontrennung & Kantenbetonung"), GalleryCategory.ARTISTIC, listOf(p(tr("Kantenstärke"), 0f, 10f, 2f), p(tr("Kantenintensität"), 0f, 10f, 1f), p(tr("Tontrennung"), 0f, 6f, 2f))) { v ->
            Effect(3) { img ->
                val l = img.lum()
                val e = G.edges(G.blurred(l, img.w, img.h, 0.8f), img.w, img.h)
                val levels = v.int(2) + 3
                val th = 260f - v[0] * 20f
                img.map { i, _, _ ->
                    val c = img.px[i]
                    val ink = G.smooth(th, th + 60f, e[i]) * (0.3f + v[1] * 0.07f)
                    G.scale(G.rgb(G.posterize(G.r(c), levels), G.posterize(G.g(c), levels), G.posterize(G.b(c), levels)), 1f - ink.coerceAtMost(1f))
                }
            }
        },
        GallerySpec("rough-pastels", tr("Grobes Pastell"), GalleryCategory.ARTISTIC, listOf(p(tr("Strichlänge"), 0f, 40f, 6f, pixels = true), p(tr("Strichdetail"), 1f, 20f, 4f), choice(tr("Struktur"), G.TEXTURES), p(tr("Relief"), 0f, 50f, 20f))) { v ->
            val len = v.px(0)
            Effect(len.roundToInt() + 4) { img ->
                val (r, g, b) = G.streakArgb(img, 45f, len)
                val stroked = IntArray(img.n) { i ->
                    val k = 1f + (G.hash(img.ox + i % img.w, img.oy + i / img.w, 51) - 0.5f) * (21 - v[1]) * 0.012f
                    G.rgb(r[i] * k, g[i] * k, b[i] * k)
                }
                G.texturize(img, stroked, v.int(2), 100f, v[3], 1, v.ctx.scale)
            }
        },
        GallerySpec("smudge-stick", tr("Diagonal verwischen"), GalleryCategory.ARTISTIC, listOf(p(tr("Strichlänge"), 0f, 10f, 2f), p(tr("Lichterbereich"), 0f, 20f, 0f), p(tr("Intensität"), 0f, 10f, 10f))) { v ->
            val len = v.px(v[0] * 2.5f + 2f)
            Effect(len.roundToInt() + 2) { img ->
                val (r, g, b) = G.streakArgb(img, -45f, len)
                img.map { i, _, _ ->
                    val l = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
                    val glow = if (l > 255 - v[1] * 10) (l - (255 - v[1] * 10)) * v[2] * 0.1f else 0f
                    G.rgb(r[i] + glow, g[i] + glow, b[i] + glow)
                }
            }
        },
        GallerySpec("sponge", tr("Schwamm"), GalleryCategory.ARTISTIC, listOf(p(tr("Pinselgröße"), 0f, 10f, 2f), p(tr("Bilddefinition"), 0f, 25f, 12f), p(tr("Glättung"), 1f, 15f, 5f))) { v ->
            val sigma = v.px(v[2] * 0.25f)
            Effect(G.gaussMargin(sigma)) { img ->
                val base = if (sigma > 0.05f) Blur.gaussArgb(img.px, img.w, img.h, sigma) else img.px
                val cell = v.px(v[0] * 1.2f + 2f)
                img.map { i, x, y ->
                    // Poröse Schwammabdrücke: Flecken im Rauschen abdunkeln
                    val n = G.fbm((img.ox + x) / cell, (img.oy + y) / cell, 3, 61)
                    val dark = G.smooth(0.45f, 0.75f, n) * (0.15f + v[1] * 0.02f)
                    G.scale(base[i], 1f - dark)
                }
            }
        },
        GallerySpec("underpainting", tr("Malgrund"), GalleryCategory.ARTISTIC, listOf(p(tr("Pinselgröße"), 0f, 40f, 6f), p(tr("Strukturdeckung"), 0f, 40f, 16f), choice(tr("Struktur"), G.TEXTURES), p(tr("Relief"), 0f, 50f, 4f))) { v ->
            val r = max(1, v.px(v[0] * 0.4f + 1f).roundToInt())
            Effect(r + 4) { img ->
                val k = G.kuwahara(img, r)
                val cov = v[1] / 40f
                val faded = IntArray(img.n) { i -> G.mix(k[i], G.scale(k[i], 1.08f), cov * 0.5f) }
                G.texturize(img, faded, v.int(2), 100f, v[3] + v[1] * 0.6f, 1, v.ctx.scale)
            }
        },
        GallerySpec("colored-pencil", tr("Farbstift"), GalleryCategory.ARTISTIC, listOf(p(tr("Strichbreite"), 1f, 24f, 4f, pixels = true), p(tr("Strichdruck"), 0f, 15f, 8f), p(tr("Papierhelligkeit"), 0f, 50f, 25f)), usesColors = true) { v ->
            val len = v.px(0) * 3f + 4f
            Effect(len.roundToInt() + 4) { img ->
                // Schraffur: diagonales Rauschen verwischen, dort Farbe auftragen, wo das Bild dunkel genug ist
                val hatch = FloatArray(img.n) { i -> G.hash((img.ox + i % img.w) / max(1, v.px(1f).roundToInt()), img.oy + i / img.w, 71) }
                val strokes = G.streak(hatch, img.w, img.h, 45f, len)
                val paper = G.mix(G.scale(v.ctx.bg, 0.5f), v.ctx.bg, v[2] / 50f)
                val l = img.lum()
                img.map { i, _, _ ->
                    val dark = 1f - l[i] / 255f
                    val cover = G.smooth(0.5f - dark * 0.5f, 0.6f - dark * 0.4f, strokes[i] + v[1] * 0.012f)
                    G.mix(paper, img.px[i], cover)
                }
            }
        },
    )

    private fun brush() = listOf(
        GallerySpec("accented-edges", tr("Kanten betonen"), GalleryCategory.BRUSH, listOf(p(tr("Kantenbreite"), 1f, 14f, 2f), p(tr("Kantenhelligkeit"), 0f, 50f, 38f), p(tr("Glättung"), 1f, 15f, 5f))) { v ->
            val sigma = v.px(v[2] * 0.25f)
            Effect(G.gaussMargin(sigma) + v.px(v[0]).roundToInt() + 2) { img ->
                val e = G.blurred(G.edges(G.blurred(img.lum(), img.w, img.h, sigma), img.w, img.h), img.w, img.h, v.px(v[0] * 0.4f))
                val bright = v[1] > 25f
                img.map { i, _, _ ->
                    val t = (e[i] / 400f * (abs(v[1] - 25f) / 25f + 0.2f)).coerceIn(0f, 0.85f)
                    if (bright) G.mix(img.px[i], 0xFFFFFF, t) else G.mix(img.px[i], 0, t)
                }
            }
        },
        GallerySpec("angled-strokes", tr("Gekreuzte Malstriche"), GalleryCategory.BRUSH, listOf(p(tr("Richtungsbalance"), 0f, 100f, 50f), p(tr("Strichlänge"), 3f, 50f, 15f, pixels = true), p(tr("Schärfe"), 0f, 10f, 3f))) { v ->
            val len = v.px(1)
            Effect(len.roundToInt() + 4) { img ->
                val a = G.streakArgb(img, 45f, len); val b = G.streakArgb(img, -45f, len)
                val l = img.lum()
                img.map { i, _, _ ->
                    // Helle Bereiche in die eine, dunkle in die andere Richtung
                    val t = G.smooth(0.3f, 0.7f, l[i] / 255f + (v[0] - 50f) / 100f)
                    val k = 1f + v[2] * 0.02f * (l[i] / 255f - 0.5f)
                    G.rgb((a.first[i] * t + b.first[i] * (1 - t)) * k, (a.second[i] * t + b.second[i] * (1 - t)) * k, (a.third[i] * t + b.third[i] * (1 - t)) * k)
                }
            }
        },
        GallerySpec("crosshatch", tr("Kreuzschraffur"), GalleryCategory.BRUSH, listOf(p(tr("Strichlänge"), 3f, 50f, 9f, pixels = true), p(tr("Schärfe"), 0f, 20f, 6f), p(tr("Stärke"), 1f, 3f, 1f))) { v ->
            val len = v.px(0)
            Effect(len.roundToInt() + 4) { img ->
                val n1 = G.streak(FloatArray(img.n) { i -> G.hash(img.ox + i % img.w, img.oy + i / img.w, 81) }, img.w, img.h, 45f, len)
                val n2 = G.streak(FloatArray(img.n) { i -> G.hash(img.ox + i % img.w, img.oy + i / img.w, 83) }, img.w, img.h, -45f, len)
                img.map { i, _, _ ->
                    val hatch = ((n1[i] + n2[i]) - 1f) * (v[1] + 4f) * 18f * v[2]
                    val c = img.px[i]
                    G.rgb(G.r(c) + hatch, G.g(c) + hatch, G.b(c) + hatch)
                }
            }
        },
        GallerySpec("dark-strokes", tr("Dunkle Malstriche"), GalleryCategory.BRUSH, listOf(p(tr("Balance"), 0f, 10f, 5f), p(tr("Schwarzintensität"), 0f, 10f, 6f), p(tr("Weißintensität"), 0f, 10f, 2f))) { v ->
            val len = v.px(12f)
            Effect(len.roundToInt() + 2) { img ->
                val a = G.streakArgb(img, -45f, len * 0.5f); val b = G.streakArgb(img, 45f, len)
                val l = img.lum()
                img.map { i, _, _ ->
                    val light = l[i] / 255f > 0.3f + v[0] * 0.04f
                    if (light) G.scale(G.rgb(b.first[i], b.second[i], b.third[i]), 1f + v[2] * 0.04f)
                    else G.scale(G.rgb(a.first[i], a.second[i], a.third[i]), 1f - v[1] * 0.08f)
                }
            }
        },
        GallerySpec("ink-outlines", tr("Konturen mit Tinte nachzeichnen"), GalleryCategory.BRUSH, listOf(p(tr("Strichlänge"), 1f, 50f, 4f, pixels = true), p(tr("Dunkle Intensität"), 0f, 50f, 20f), p(tr("Helle Intensität"), 0f, 50f, 10f))) { v ->
            val len = v.px(0)
            Effect(len.roundToInt() + 4) { img ->
                val (r, g, b) = G.streakArgb(img, 45f, len)
                val l = FloatArray(img.n) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
                val e = G.edges(l, img.w, img.h)
                img.map { i, _, _ ->
                    val ink = G.smooth(60f, 220f, e[i]) * v[1] / 30f
                    val k = (1f - ink).coerceAtLeast(0f) * (1f + (l[i] / 255f - 0.5f) * v[2] * 0.02f)
                    G.rgb(r[i] * k, g[i] * k, b[i] * k)
                }
            }
        },
        GallerySpec("sprayed-strokes", tr("Verwackelte Striche"), GalleryCategory.BRUSH, listOf(p(tr("Strichlänge"), 0f, 20f, 12f, pixels = true), p(tr("Sprühradius"), 0f, 25f, 7f, pixels = true),
                choice(tr("Strichrichtung"), listOf(tr("Rechts diagonal"), tr("Horizontal"), tr("Links diagonal"), tr("Vertikal"))))) { v ->
            val len = v.px(0); val r = v.px(1)
            val angle = when (v.int(2)) { 0 -> 45f; 1 -> 0f; 2 -> -45f; else -> 90f }
            Effect(len.roundToInt() + r.roundToInt() + 4) { img ->
                // Erst versprühen (Pixel zufällig versetzt), dann in Strichrichtung verwischen
                val cell = maxOf(1f, r * 0.35f)
                val sprayed = IntArray(img.n) { i ->
                    val x = i % img.w; val y = i / img.w
                    val ax = (img.ox + x) / cell; val ay = (img.oy + y) / cell
                    val dx = ((G.noise(ax, ay, 97) - 0.5f) * 2f * r).roundToInt(); val dy = ((G.noise(ax, ay, 99) - 0.5f) * 2f * r).roundToInt()
                    img.px[(y + dy).coerceIn(0, img.h - 1) * img.w + (x + dx).coerceIn(0, img.w - 1)]
                }
                val (sr, sg, sb) = G.streakArgb(Img(sprayed, img.w, img.h, img.ox, img.oy), angle, len)
                img.map { i, _, _ -> G.rgb(sr[i], sg[i], sb[i]) }
            }
        },
        GallerySpec("spatter", tr("Spritzer"), GalleryCategory.BRUSH, listOf(p(tr("Sprühradius"), 0f, 25f, 10f, pixels = true), p(tr("Glättung"), 1f, 15f, 5f))) { v ->
            val r = v.px(0); val cell = v.px(v[1] * 0.6f + 1f)
            Effect(r.roundToInt() + 2) { img ->
                img.map { _, x, y ->
                    val ax = (img.ox + x) / cell; val ay = (img.oy + y) / cell
                    val dx = ((G.noise(ax, ay, 91) - 0.5f) * 2f * r).roundToInt(); val dy = ((G.noise(ax, ay, 93) - 0.5f) * 2f * r).roundToInt()
                    img.px[(y + dy).coerceIn(0, img.h - 1) * img.w + (x + dx).coerceIn(0, img.w - 1)]
                }
            }
        },
        GallerySpec("sumi-e", tr("Sumi-e"), GalleryCategory.BRUSH, listOf(p(tr("Strichbreite"), 3f, 15f, 10f, pixels = true), p(tr("Strichdruck"), 0f, 15f, 2f), p(tr("Kontrast"), 0f, 40f, 16f))) { v ->
            val sigma = v.px(0) * 0.3f
            Effect(G.gaussMargin(sigma) + v.px(10f).roundToInt()) { img ->
                val (r, g, b) = G.streakArgb(img, 45f, v.px(10f))
                val l = FloatArray(img.n) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
                val soft = G.blurred(l, img.w, img.h, sigma)
                img.map { i, _, _ ->
                    // Dunkle Bereiche als satte Tusche, Kontrast angehoben
                    val ink = G.smooth(90f + v[1] * 4f, 15f, soft[i]) * 0.75f
                    val k = (1f + (l[i] / 255f - 0.5f) * v[2] / 20f) * (1f - ink)
                    G.rgb(r[i] * k, g[i] * k, b[i] * k)
                }
            }
        },
    )

    private fun distort() = listOf(
        GallerySpec("diffuse-glow", tr("Weiches Licht"), GalleryCategory.DISTORT, listOf(p(tr("Körnung"), 0f, 10f, 6f), p(tr("Lichtmenge"), 0f, 20f, 10f), p(tr("Verringerung"), 0f, 20f, 15f)), usesColors = true) { v ->
            val sigma = v.px(6f)
            Effect(G.gaussMargin(sigma)) { img ->
                val glow = G.blurred(img.lum(), img.w, img.h, sigma)
                val tint = v.ctx.bg
                img.map { i, x, y ->
                    // Leuchten aus den Lichtern in der Hintergrundfarbe, durch Korn gestreut
                    val g = (G.smooth(255f - (20 - v[2]) * 10f - 30f, 255f, glow[i]) * v[1] / 20f + (G.hash(img.ox + x, img.oy + y, 101) - 0.5f) * v[0] * 0.03f).coerceIn(0f, 1f)
                    G.mix(img.px[i], tint, g)
                }
            }
        },
        GallerySpec("glass", tr("Glas"), GalleryCategory.DISTORT, listOf(p(tr("Verzerrung"), 0f, 20f, 5f), p(tr("Glättung"), 1f, 15f, 3f), choice(tr("Struktur"), listOf(tr("Milchglas"), tr("Blöcke"), tr("Leinwand"), tr("Kleine Linsen"))), p(tr("Skalierung"), 50f, 200f, 100f))) { v ->
            val amount = v.px(v[0] * 1.5f); val sc = v.px(v[3] / 100f)
            Effect(amount.roundToInt() + 2) { img ->
                img.map { _, x, y ->
                    val ax = (img.ox + x).toFloat(); val ay = (img.oy + y).toFloat()
                    val smooth = v[1] * 0.6f + 1f
                    fun height(px: Float, py: Float) = when (v.int(2)) {
                        0 -> G.fbm(px / (smooth * sc), py / (smooth * sc), 2, 111)
                        1 -> { val s = 24f * sc; G.smooth(0f, 0.3f, ((px / s) % 1f + 1f) % 1f) * G.smooth(0f, 0.3f, ((py / s) % 1f + 1f) % 1f) }
                        2 -> G.texture(0, px, py, sc)
                        else -> { val s = 12f * sc; val fx = ((px / s) % 1f + 1f) % 1f - 0.5f; val fy = ((py / s) % 1f + 1f) % 1f - 0.5f; 1f - (fx * fx + fy * fy) * 4f }
                    }
                    val dx = (height(ax + 1, ay) - height(ax - 1, ay)) * amount * 2f; val dy = (height(ax, ay + 1) - height(ax, ay - 1)) * amount * 2f
                    img.px[(y + dy.roundToInt()).coerceIn(0, img.h - 1) * img.w + (x + dx.roundToInt()).coerceIn(0, img.w - 1)]
                }
            }
        },
        GallerySpec("ocean-ripple", tr("Ozeanwellen"), GalleryCategory.DISTORT, listOf(p(tr("Wellengröße"), 1f, 15f, 9f), p(tr("Wellenstärke"), 0f, 20f, 9f))) { v ->
            val size = v.px(v[0] * 1.5f + 2f); val amp = v.px(v[1] * 0.8f)
            Effect(amp.roundToInt() + 2) { img ->
                img.map { _, x, y ->
                    val ax = (img.ox + x) / size; val ay = (img.oy + y) / size
                    val dx = (G.noise(ax, ay, 121) - 0.5f) * 2f * amp; val dy = (G.noise(ax, ay, 123) - 0.5f) * 2f * amp
                    img.px[(y + dy.roundToInt()).coerceIn(0, img.h - 1) * img.w + (x + dx.roundToInt()).coerceIn(0, img.w - 1)]
                }
            }
        },
    )

    private fun sketch() = listOf(
        GallerySpec("bas-relief", tr("Basrelief"), GalleryCategory.SKETCH, listOf(p(tr("Detail"), 1f, 15f, 13f), p(tr("Glättung"), 1f, 15f, 3f), choice(tr("Licht"), G.LIGHTS, 5)), usesColors = true) { v ->
            val sigma = v.px(v[1] * 0.4f)
            Effect(G.gaussMargin(sigma) + 2) { img ->
                val hgt = G.blurred(img.lum(), img.w, img.h, sigma)
                val sh = G.relief(hgt, img.w, img.h, G.lightAngle(v.int(2)), v[0] / 6f)
                img.map { i, _, _ -> G.mix(v.ctx.fg, v.ctx.bg, 0.5f + sh[i] / 255f + (hgt[i] - 128f) / 900f) }
            }
        },
        GallerySpec("chalk-charcoal", tr("Kreide & Kohle"), GalleryCategory.SKETCH, listOf(p(tr("Kohlebereich"), 0f, 20f, 6f), p(tr("Kreidebereich"), 0f, 20f, 6f), p(tr("Strichdruck"), 0f, 5f, 1f)), usesColors = true) { v ->
            val len = v.px(10f)
            Effect(len.roundToInt() + 2) { img ->
                val l = G.streak(img.lum(), img.w, img.h, 45f, len)
                val n = G.streak(FloatArray(img.n) { i -> G.hash(img.ox + i % img.w, img.oy + i / img.w, 131) }, img.w, img.h, 45f, len)
                img.map { i, _, _ ->
                    val t = l[i] / 255f + (n[i] - 0.5f) * (0.3f + v[2] * 0.1f)
                    val charcoal = G.smooth(0.55f, 0.35f - v[0] * 0.01f, t) * (0.5f + v[0] * 0.025f)
                    val chalk = G.smooth(0.45f, 0.65f + v[1] * 0.01f, t) * (0.5f + v[1] * 0.025f)
                    G.mix(G.mix(G.mix(v.ctx.fg, v.ctx.bg, 0.5f), v.ctx.fg, charcoal), v.ctx.bg, chalk)
                }
            }
        },
        GallerySpec("charcoal", tr("Kohleumsetzung"), GalleryCategory.SKETCH, listOf(p(tr("Kohlestärke"), 1f, 7f, 1f), p(tr("Detail"), 0f, 5f, 5f), p(tr("Hell/Dunkel-Balance"), 0f, 100f, 50f)), usesColors = true) { v ->
            val len = v.px(8f + v[0] * 2f)
            Effect(len.roundToInt() + 2) { img ->
                val l = G.streak(img.lum(), img.w, img.h, 45f, len)
                val n = G.streak(FloatArray(img.n) { i -> G.hash(img.ox + i % img.w, img.oy + i / img.w, 141) }, img.w, img.h, 45f, len)
                img.map { i, _, _ ->
                    val t = l[i] / 255f + (n[i] - 0.5f) * (0.2f + v[1] * 0.06f) - (v[2] - 50f) / 100f
                    G.mix(v.ctx.fg, v.ctx.bg, G.smooth(0.42f, 0.58f, t))
                }
            }
        },
        GallerySpec("chrome", tr("Chrom"), GalleryCategory.SKETCH, listOf(p(tr("Detail"), 0f, 10f, 4f), p(tr("Glättung"), 0f, 10f, 7f))) { v ->
            val sigma = v.px(v[1] * 0.6f + 0.5f)
            Effect(G.gaussMargin(sigma)) { img ->
                val l = G.blurred(img.lum(), img.w, img.h, sigma)
                val f = 2f + v[0] * 0.6f
                img.map { i, _, _ -> val g = 128f + 127f * sin(l[i] / 255f * f * Math.PI.toFloat()); G.rgb(g, g, g) }
            }
        },
        GallerySpec("conte-crayon", tr("Conté-Stifte"), GalleryCategory.SKETCH, listOf(p(tr("Vordergrundstufe"), 1f, 15f, 11f), p(tr("Hintergrundstufe"), 1f, 15f, 7f),
                choice(tr("Struktur"), G.TEXTURES), p(tr("Skalierung"), 50f, 200f, 100f), p(tr("Relief"), 0f, 50f, 4f)), usesColors = true) { v ->
            val len = v.px(6f)
            Effect(len.roundToInt() + 4) { img ->
                // Dunkle Töne in Vordergrund-, helle in Hintergrundfarbe, als kurze Kreidestriche auf Papierstruktur
                val l = G.streak(img.lum(), img.w, img.h, 45f, len)
                val n = G.streak(FloatArray(img.n) { i -> G.hash(img.ox + i % img.w, img.oy + i / img.w, 161) }, img.w, img.h, 45f, len)
                val mid = G.mix(v.ctx.fg, v.ctx.bg, 0.5f)
                val drawn = IntArray(img.n) { i ->
                    val t = l[i] / 255f + (n[i] - 0.5f) * 0.35f
                    val dark = G.smooth(0.55f, 0.15f, t) * (v[0] / 15f)
                    val light = G.smooth(0.45f, 0.85f, t) * (v[1] / 15f)
                    G.mix(G.mix(mid, v.ctx.fg, dark), v.ctx.bg, light)
                }
                G.texturize(img, drawn, v.int(2), v[3], v[4], 1, v.ctx.scale)
            }
        },
        GallerySpec("graphic-pen", tr("Grafikstift"), GalleryCategory.SKETCH, listOf(p(tr("Strichlänge"), 1f, 15f, 15f, pixels = true), p(tr("Hell/Dunkel-Balance"), 0f, 100f, 50f), choice(tr("Strichrichtung"), listOf(tr("Rechts diagonal"), tr("Horizontal"), tr("Links diagonal"), tr("Vertikal")))), usesColors = true) { v ->
            val len = v.px(0) * 2f
            val angle = when (v.int(2)) { 0 -> 45f; 1 -> 0f; 2 -> -45f; else -> 90f }
            Effect(len.roundToInt() + 2) { img ->
                val n = G.streak(FloatArray(img.n) { i -> G.hash(img.ox + i % img.w, img.oy + i / img.w, 151) }, img.w, img.h, angle, len)
                val l = img.lum()
                img.map { i, _, _ ->
                    val t = l[i] / 255f + (n[i] - 0.5f) * 3f - (v[1] - 50f) / 100f
                    if (t < 0.5f) v.ctx.fg else v.ctx.bg
                }
            }
        },
        GallerySpec("halftone", tr("Rasterungseffekt"), GalleryCategory.SKETCH, listOf(p(tr("Größe"), 1f, 12f, 1f, pixels = true), p(tr("Kontrast"), 0f, 50f, 5f), choice(tr("Musterart"), listOf(tr("Kreis"), tr("Punkt"), tr("Linie")), 1)), usesColors = true) { v ->
            val cell = v.px(v[0] * 3f + 3f)
            Effect(1) { img ->
                img.map { i, x, y ->
                    val c = G.lum(img.px[i]) / 255f
                    val ax = img.ox + x.toFloat(); val ay = img.oy + y.toFloat()
                    val screen = when (v.int(2)) {
                        0 -> { val r = sqrt(ax * ax + ay * ay) / cell; 0.5f + 0.5f * sin(r * 2f * Math.PI.toFloat()) }
                        1 -> { val fx = ((ax / cell) % 1f + 1f) % 1f - 0.5f; val fy = ((ay / cell) % 1f + 1f) % 1f - 0.5f; sqrt(fx * fx + fy * fy) * 1.41f }
                        else -> 0.5f + 0.5f * sin(ay / cell * 2f * Math.PI.toFloat())
                    }
                    val k = 0.5f + v[1] / 50f * 6f
                    G.mix(v.ctx.fg, v.ctx.bg, ((c - (if (v.int(2) == 1) 1f - screen else screen)) * k + 0.5f).coerceIn(0f, 1f))
                }
            }
        },
        GallerySpec("note-paper", tr("Prägepapier"), GalleryCategory.SKETCH, listOf(p(tr("Bildbalance"), 0f, 50f, 25f), p(tr("Körnung"), 0f, 20f, 10f), p(tr("Relief"), 0f, 25f, 11f)), usesColors = true) { v ->
            Effect(4) { img ->
                val l = G.blurred(img.lum(), img.w, img.h, 1f)
                val mask = FloatArray(img.n) { i -> G.smooth(0.45f, 0.55f, l[i] / 255f + (v[0] - 25f) / 50f) * 255f }
                val sh = G.relief(mask, img.w, img.h, 135f, v[2] / 10f)
                img.map { i, x, y ->
                    val grain = (G.hash(img.ox + x, img.oy + y, 161) - 0.5f) * v[1] * 2f
                    G.scale(G.mix(v.ctx.fg, v.ctx.bg, mask[i] / 255f), 1f + (sh[i] + grain) / 255f)
                }
            }
        },
        GallerySpec("photocopy", tr("Fotokopie"), GalleryCategory.SKETCH, listOf(p(tr("Detail"), 1f, 24f, 7f, pixels = true), p(tr("Verdunkelung"), 1f, 50f, 8f)), usesColors = true) { v ->
            val sigma = v.px(0) * 0.5f + 0.5f
            Effect(G.gaussMargin(sigma)) { img ->
                val l = img.lum()
                val bl = G.blurred(l, img.w, img.h, sigma)
                img.map { i, _, _ -> G.mix(v.ctx.fg, v.ctx.bg, G.smooth(-4f - v[1] * 0.6f, 2f, l[i] - bl[i])) }
            }
        },
        GallerySpec("plaster", tr("Gipsabdruck"), GalleryCategory.SKETCH, listOf(p(tr("Bildbalance"), 0f, 50f, 20f), p(tr("Glättung"), 1f, 15f, 2f), choice(tr("Licht"), G.LIGHTS))) { v ->
            val sigma = v.px(v[1] * 0.5f + 0.5f)
            Effect(G.gaussMargin(sigma) + 2) { img ->
                val l = G.blurred(img.lum(), img.w, img.h, sigma)
                val mask = FloatArray(img.n) { i -> G.smooth(0.4f, 0.6f, l[i] / 255f + (v[0] - 25f) / 50f) * 255f }
                val sh = G.relief(mask, img.w, img.h, G.lightAngle(v.int(2)), 0.6f)
                img.map { i, _, _ -> val g = 150f + (mask[i] - 128f) * 0.25f + sh[i]; G.rgb(g, g, g * 0.97f) }
            }
        },
        GallerySpec("reticulation", tr("Punktierstich"), GalleryCategory.SKETCH, listOf(p(tr("Dichte"), 0f, 50f, 12f), p(tr("Vordergrundstufe"), 0f, 50f, 40f), p(tr("Hintergrundstufe"), 0f, 50f, 5f)), usesColors = true) { v ->
            Effect(0) { img ->
                img.map { i, x, y ->
                    val t = G.lum(img.px[i]) / 255f + (G.hash(img.ox + x, img.oy + y, 171) - 0.5f) * (0.2f + v[0] * 0.02f)
                    G.mix(G.mix(v.ctx.fg, v.ctx.bg, v[2] / 100f), G.mix(v.ctx.bg, v.ctx.fg, v[1] / 200f), G.smooth(0.45f, 0.55f, t))
                }
            }
        },
        GallerySpec("stamp", tr("Stempel"), GalleryCategory.SKETCH, listOf(p(tr("Hell/Dunkel-Balance"), 0f, 50f, 25f), p(tr("Glättung"), 1f, 50f, 5f)), usesColors = true) { v ->
            // Wie ein Gummi- oder Holzstempel: Schwellwert auf die Helligkeit (höhere Balance = mehr
            // Vordergrundfarbe), danach werden die Ränder der Formen geglättet – kleine Sprenkel
            // verschwinden, Ecken runden sich, große Formen bleiben an ihrem Platz. Nur zwei Farben,
            // Kanten mit genau einem Pixel Kantenglättung.
            val pre = v.px(0.6f)
            val sigma = v.px((v[1] - 1f) * 0.12f)
            val thr = v[0] / 50f * 257f - 1f
            Effect(G.gaussMargin(sigma) + G.gaussMargin(pre) + 4) { img ->
                val w = img.w; val h = img.h
                val l = G.blurred(img.lum(), w, h, pre)
                // Dunkelanteil 0..255 mit 1-Pixel-Kante, dann Ränder glätten und erneut scharf trennen
                val ink = G.blurred(G.crisp(l, w, h, thr, invert = true), w, h, sigma)
                val out = G.crisp(ink, w, h, 127.5f, invert = false)
                img.map { i, _, _ -> G.mix(v.ctx.bg, v.ctx.fg, out[i] / 255f) }
            }
        },
        GallerySpec("torn-edges", tr("Gerissene Kanten"), GalleryCategory.SKETCH, listOf(p(tr("Bildbalance"), 0f, 50f, 25f), p(tr("Glättung"), 1f, 15f, 11f), p(tr("Kontrast"), 1f, 25f, 17f)), usesColors = true) { v ->
            val sigma = v.px(v[1] * 0.25f)
            Effect(G.gaussMargin(sigma)) { img ->
                val l = G.blurred(img.lum(), img.w, img.h, sigma)
                val cell = v.px(3f)
                img.map { i, x, y ->
                    val n = (G.fbm((img.ox + x) / cell, (img.oy + y) / cell, 3, 181) - 0.5f) * 0.25f
                    val k = 0.02f + (26f - v[2]) * 0.006f
                    G.mix(v.ctx.fg, v.ctx.bg, G.smooth(0.5f - k, 0.5f + k, l[i] / 255f + n + (v[0] - 25f) / 50f))
                }
            }
        },
        GallerySpec("water-paper", tr("Feuchtes Papier"), GalleryCategory.SKETCH, listOf(p(tr("Faserlänge"), 3f, 50f, 15f, pixels = true), p(tr("Helligkeit"), 0f, 100f, 60f), p(tr("Kontrast"), 0f, 100f, 80f))) { v ->
            val len = v.px(0)
            Effect(len.roundToInt() + 2) { img ->
                val fibers = G.streak(FloatArray(img.n) { i -> G.hash(img.ox + i % img.w, img.oy + i / img.w, 191) }, img.w, img.h, 90f, len)
                val (r, g, b) = G.streakArgb(img, 90f, len * 0.3f)
                img.map { i, _, _ ->
                    val k = (v[1] + 20f) / 80f; val c = v[2] / 60f
                    fun t(x: Float) = ((x / 255f - 0.5f) * c + 0.5f) * 255f * k + (fibers[i] - 0.5f) * 40f
                    G.rgb(t(r[i]), t(g[i]), t(b[i]))
                }
            }
        },
    )

    private fun stylize() = listOf(
        GallerySpec("glowing-edges", tr("Leuchtende Konturen"), GalleryCategory.STYLIZE, listOf(p(tr("Kantenbreite"), 1f, 14f, 2f), p(tr("Kantenhelligkeit"), 0f, 20f, 6f), p(tr("Glättung"), 1f, 15f, 5f))) { v ->
            val sigma = v.px(v[2] * 0.25f); val wid = v.px(v[0] * 0.5f)
            Effect(G.gaussMargin(sigma) + G.gaussMargin(wid)) { img ->
                val w = img.w; val h = img.h
                // Kanten je Kanal: die Farbe des Bildes leuchtet auf Schwarz
                val er = G.blurred(G.edges(G.blurred(img.channel(16), w, h, sigma), w, h), w, h, wid)
                val eg = G.blurred(G.edges(G.blurred(img.channel(8), w, h, sigma), w, h), w, h, wid)
                val eb = G.blurred(G.edges(G.blurred(img.channel(0), w, h, sigma), w, h), w, h, wid)
                val k = 0.4f + v[1] * 0.15f
                img.map { i, _, _ -> G.rgb(er[i] * k, eg[i] * k, eb[i] * k) }
            }
        },
    )

    private fun texture() = listOf(
        GallerySpec("craquelure", tr("Risse"), GalleryCategory.TEXTURE, listOf(p(tr("Rissabstand"), 2f, 100f, 15f, pixels = true), p(tr("Risstiefe"), 0f, 10f, 6f), p(tr("Risshelligkeit"), 0f, 10f, 9f))) { v ->
            val size = v.px(0) * 2f + 4f
            Effect(3) { img ->
                val out = FloatArray(4)
                val crack = FloatArray(img.n) { i ->
                    G.cells((img.ox + i % img.w).toFloat(), (img.oy + i / img.w).toFloat(), size, 0.9f, 201, out)
                    G.smooth(0f, 1.5f + v[1] * 0.2f, out[1] - out[0]) * 255f
                }
                val sh = G.relief(crack, img.w, img.h, 135f, v[1] / 8f)
                img.map { i, _, _ -> G.scale(img.px[i], (1f - (1f - crack[i] / 255f) * (1f - v[2] / 12f)) * (1f + sh[i] / 255f)) }
            }
        },
        GallerySpec("grain", tr("Körnung"), GalleryCategory.TEXTURE, listOf(p(tr("Intensität"), 0f, 100f, 40f), p(tr("Kontrast"), 0f, 100f, 50f),
            choice(tr("Körnungsart"), listOf(tr("Normal"), tr("Weich"), tr("Gesprenkelt"), tr("Klumpen"), tr("Horizontal"), tr("Vertikal"))))) { v ->
            Effect(2) { img ->
                img.map { i, x, y ->
                    val ax = img.ox + x; val ay = img.oy + y
                    val n = when (v.int(2)) {
                        0 -> G.hash(ax, ay, 211) - 0.5f
                        1 -> G.noise(ax / v.px(2f), ay / v.px(2f), 213) - 0.5f
                        2 -> if (G.hash(ax, ay, 215) > 0.97f) -0.5f else 0f
                        3 -> G.fbm(ax / v.px(4f), ay / v.px(4f), 2, 217) - 0.5f
                        4 -> G.hash(0, ay, 219) - 0.5f
                        else -> G.hash(ax, 0, 221) - 0.5f
                    }
                    val c = img.px[i]
                    val k = 1f + (v[1] - 50f) / 100f
                    val d = n * v[0] * 2.4f
                    fun ch(f: Float) = (f - 128f) * k + 128f + d
                    G.rgb(ch(G.r(c)), ch(G.g(c)), ch(G.b(c)))
                }
            }
        },
        GallerySpec("mosaic-tiles", tr("Mosaikfliesen"), GalleryCategory.TEXTURE, listOf(p(tr("Fliesengröße"), 2f, 100f, 12f, pixels = true), p(tr("Fugenbreite"), 1f, 15f, 3f, pixels = true), p(tr("Fugenhelligkeit"), 0f, 10f, 9f))) { v ->
            val size = v.px(0) + 2f; val grout = v.px(1)
            Effect(3) { img ->
                val out = FloatArray(4)
                val tile = FloatArray(img.n) { i ->
                    G.cells((img.ox + i % img.w).toFloat(), (img.oy + i / img.w).toFloat(), size, 0.5f, 231, out)
                    G.smooth(grout * 0.5f, grout * 0.5f + 1.5f, (out[1] - out[0]) * 0.5f) * 255f
                }
                val sh = G.relief(tile, img.w, img.h, 135f, 0.25f)
                img.map { i, _, _ ->
                    val t = tile[i] / 255f
                    G.scale(G.mix(G.scale(img.px[i], v[2] / 10f), img.px[i], t), 1f + sh[i] / 255f)
                }
            }
        },
        GallerySpec("patchwork", tr("Patchwork"), GalleryCategory.TEXTURE, listOf(p(tr("Quadratgröße"), 0f, 10f, 4f), p(tr("Relief"), 0f, 25f, 8f))) { v ->
            val size = max(2, v.px(v[0] * 2f + 4f).roundToInt())
            Effect(size + 2) { img ->
                val hgt = FloatArray(img.n)
                val col = IntArray(img.n)
                for (i in 0 until img.n) {
                    val ax = img.ox + i % img.w; val ay = img.oy + i / img.w
                    // Feld-Farbe aus der Mitte des Quadrats (im Ausschnitt, dank Rand)
                    val cx = Math.floorDiv(ax, size) * size + size / 2 - img.ox; val cy = Math.floorDiv(ay, size) * size + size / 2 - img.oy
                    val c = img.px[cy.coerceIn(0, img.h - 1) * img.w + cx.coerceIn(0, img.w - 1)]
                    col[i] = c
                    val fx = Math.floorMod(ax, size); val fy = Math.floorMod(ay, size)
                    hgt[i] = G.lum(c) * 0.5f + min(min(fx, size - 1 - fx), min(fy, size - 1 - fy)).coerceAtMost(2) * 60f
                }
                val sh = G.relief(hgt, img.w, img.h, 135f, v[1] / 12f)
                img.map { i, _, _ -> G.scale(col[i], 1f + sh[i] / 255f) }
            }
        },
        GallerySpec("stained-glass", tr("Buntglas-Mosaik"), GalleryCategory.TEXTURE, listOf(p(tr("Zellgröße"), 2f, 50f, 10f, pixels = true), p(tr("Rahmenstärke"), 1f, 20f, 4f, pixels = true), p(tr("Lichtintensität"), 0f, 10f, 3f)), usesColors = true) { v ->
            val size = v.px(0) * 2f + 2f; val border = v.px(1) * 0.5f
            Effect(size.roundToInt() * 2 + 2) { img ->
                val out = FloatArray(4)
                img.map { _, x, y ->
                    val ax = (img.ox + x).toFloat(); val ay = (img.oy + y).toFloat()
                    G.cells(ax, ay, size, 0.9f, 241, out)
                    val sx = (out[2] - img.ox).roundToInt().coerceIn(0, img.w - 1); val sy = (out[3] - img.oy).roundToInt().coerceIn(0, img.h - 1)
                    val c = img.px[sy * img.w + sx]
                    // Licht zur Zellmitte hin, Bleirahmen in der Vordergrundfarbe
                    val center = 1f + v[2] * 0.05f * (1f - (out[0] / size).coerceIn(0f, 1f))
                    G.mix(v.ctx.fg, G.scale(c, center), G.smooth(border, border + 1f, (out[1] - out[0]) * 0.5f))
                }
            }
        },
        GallerySpec("texturizer", tr("Mit Struktur versehen"), GalleryCategory.TEXTURE, listOf(choice(tr("Struktur"), G.TEXTURES), p(tr("Skalierung"), 50f, 200f, 100f), p(tr("Relief"), 0f, 50f, 4f), choice(tr("Licht"), G.LIGHTS))) { v ->
            Effect(3) { img -> G.texturize(img, img.px, v.int(0), v[1], v[2] * 2f, v.int(3), v.ctx.scale) }
        },
    )
}

/** Vorschau-Hilfe: [px] (ARGB, w×h) mit dem Stapel rechnen – ohne Dokument, z. B. für Miniaturen. */
fun GalleryChain.preview(px: IntArray, w: Int, h: Int, ox: Int = 0, oy: Int = 0): IntArray = process(px, w, h, ox, oy, w, h)
