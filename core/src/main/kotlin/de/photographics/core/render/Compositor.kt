package de.photographics.core.render

import de.photographics.core.image.MipMap

import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer
import de.photographics.core.doc.LayerKind
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.RasterLayer
import de.photographics.core.doc.SmartLayer
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect

/**
 * Setzt den Ebenenstapel eines Dokuments für einen Bildausschnitt zusammen.
 *
 * Gerechnet wird in vormultipliziertem Float-RGBA. Unterstützt werden alle Mischmodi,
 * Deckkraft/Fläche, Ebenenmasken, Schnittmasken sowie isolierte und hindurchwirkende Gruppen.
 */
object Compositor {

    /**
     * Rendert das Dokument streifenweise in ein Kachelraster – ohne Puffer in Bildgröße.
     * [area] darf über die Arbeitsfläche hinausgehen (z. B. beim Zusammenfügen von Ebenen, die
     * herausragen). [post] kann jeden Streifen nachbearbeiten (z. B. auf Weiß reduzieren).
     */
    fun renderGrid(doc: Document, area: IntRect = doc.bounds, post: ((IntArray) -> Unit)? = null): de.photographics.core.image.TileGrid {
        val g = de.photographics.core.image.TileGrid(doc.width, doc.height, area = area)
        renderBands(doc, area) { r, px ->
            post?.invoke(px)
            g.writeRect(r, px)
        }
        g.compact()
        return g
    }

    /**
     * Rendert [area] in Streifen parallel auf allen Kernen; [consume] erhält die Streifen der Reihe
     * nach auf dem aufrufenden Thread.
     */
    fun renderBands(doc: Document, area: IntRect, consume: (IntRect, IntArray) -> Unit) {
        if (area.isEmpty) return
        prepare(doc)
        val band = 64
        val count = (area.height + band - 1) / band
        // Ausgabe (4 B) + Float-Puffer (16 B) + Ebenenpuffer aus dem Pool (≈ 20 B) je Pixel
        val bytesPerBand = area.width.toLong() * band * 40
        de.photographics.core.image.Parallel.bands(count, bytesPerBand, compute = { i ->
            val r = IntRect(area.left, area.top + i * band, area.right, minOf(area.bottom, area.top + (i + 1) * band))
            r to renderArgb(doc, r)
        }, consume = { _, (r, px) -> consume(r, px) })
    }

    /**
     * Vor parallelem Rendern: träge berechnete Raster (Text, Smartobjekte) und Korrekturtabellen
     * einmal auf dem aufrufenden Thread erzeugen, damit die Rechen-Threads nur noch lesen.
     */
    fun prepare(doc: Document, step: Int = 1) {
        val level = MipMap.levelFor(step)
        for (l in doc.allLayers()) {
            when (l) {
                // Smartobjekte im Vorschaumodus lesen direkt aus der Quelle (ihre Mipmaps entstehen beim ersten Zugriff)
                is SmartLayer -> if (!l.samplesDirectly) { val r = l.raster; if (level > 0 && l.visible) r.mip(level) }
                is RasterLayer -> { val r = l.raster; if (level > 0 && l.visible) r.mip(level) }
                is AdjustmentLayer -> l.rgbMap()
                else -> {}
            }
            if (level > 0 && l.visible) l.mask?.grid?.mip(level, scalar = true)
        }
    }

    fun renderArgb(doc: Document, rect: IntRect, out: IntArray = IntArray(rect.width * rect.height)): IntArray {
        // In Streifen rechnen, damit der Float-Puffer (16 Byte/Pixel) klein bleibt
        val band = maxOf(1, BAND_PIXELS / maxOf(1, rect.width))
        if (rect.height <= band) {
            toArgb(render(doc, rect), out)
            return out
        }
        var y = rect.top
        while (y < rect.bottom) {
            val y1 = minOf(rect.bottom, y + band)
            val part = IntRect(rect.left, y, rect.right, y1)
            val tmp = IntArray(part.width * part.height)
            toArgb(render(doc, part), tmp)
            System.arraycopy(tmp, 0, out, (y - rect.top) * rect.width, tmp.size)
            y = y1
        }
        return out
    }

    private const val BAND_PIXELS = 1 shl 19

    /**
     * Schnelle Vorschau: rendert nur jedes [step]-te Pixel (Ausgabe in verkleinerten Koordinaten,
     * [rect] ist bereits durch [step] geteilt). Für Live-Änderungen an großen Dokumenten.
     */
    fun renderArgbSampled(doc: Document, rect: IntRect, step: Int, out: IntArray = IntArray(rect.width * rect.height)): IntArray {
        if (step <= 1) return renderArgb(doc, rect, out)
        val band = maxOf(1, BAND_PIXELS / maxOf(1, rect.width))
        var y = rect.top
        while (y < rect.bottom) {
            val y1 = minOf(rect.bottom, y + band)
            val part = IntRect(rect.left, y, rect.right, y1)
            val buf = FloatArray(part.width * part.height * 4)
            compositeChildren(doc.root.children, buf, Area(part, step))
            val tmp = IntArray(part.width * part.height)
            toArgb(buf, tmp)
            System.arraycopy(tmp, 0, out, (y - rect.top) * rect.width, tmp.size)
            y = y1
        }
        return out
    }

    /**
     * Index in `doc.root.children`, ab dem sich das Bild ändert, wenn sich nur [layer] ändert: seine
     * oberste Gruppe bzw. die Basis seiner Schnittmaske. Alles darunter darf während einer
     * Live-Änderung zwischengespeichert werden (siehe [renderRangeSampled]).
     */
    fun liveSplit(doc: Document, layer: Layer): Int {
        var top = layer
        while (true) { val p = top.parent ?: break; if (p === doc.root) break; top = p }
        var i = doc.root.children.indexOf(top)
        if (i < 0) return 0
        while (i > 0 && doc.root.children[i].clipped) i--
        return i
    }

    /**
     * Wie [renderArgbSampled], aber nur mit den Ebenen `doc.root.children[from until until]`, gemischt
     * über [below] (ARGB, Ausgabepixel (x, y) liegt bei `below[(y - belowY) * belowStride + x - belowX]`;
     * null = transparent). [from] und [until] dürfen keine Schnittmaske trennen ([liveSplit]).
     */
    fun renderRangeSampled(
        doc: Document, rect: IntRect, step: Int, from: Int, until: Int,
        below: IntArray?, belowStride: Int = rect.width, belowX: Int = rect.left, belowY: Int = rect.top,
        out: IntArray = IntArray(rect.width * rect.height),
    ): IntArray {
        val layers = doc.root.children.subList(from, until)
        val band = maxOf(1, BAND_PIXELS / maxOf(1, rect.width))
        var y = rect.top
        while (y < rect.bottom) {
            val y1 = minOf(rect.bottom, y + band)
            val part = IntRect(rect.left, y, rect.right, y1)
            val buf = FloatArray(part.width * part.height * 4)
            if (below != null) {
                var k = 0
                for (yy in y until y1) {
                    var o = (yy - belowY) * belowStride + (rect.left - belowX)
                    for (x in 0 until part.width) {
                        val c = below[o++]
                        val ca = c ushr 24
                        if (ca != 0) {
                            val a = ca / 255f
                            buf[k] = ((c shr 16) and 0xFF) / 255f * a
                            buf[k + 1] = ((c shr 8) and 0xFF) / 255f * a
                            buf[k + 2] = (c and 0xFF) / 255f * a
                            buf[k + 3] = a
                        }
                        k += 4
                    }
                }
            }
            compositeChildren(layers, buf, Area(part, maxOf(1, step)))
            val tmp = IntArray(part.width * part.height)
            toArgb(buf, tmp)
            System.arraycopy(tmp, 0, out, (y - rect.top) * rect.width, tmp.size)
            y = y1
        }
        return out
    }

    /** Vormultiplizierter RGBA-Float-Puffer (4 Werte pro Pixel). */
    fun render(doc: Document, rect: IntRect): FloatArray {
        val buf = FloatArray(rect.width * rect.height * 4)
        if (!rect.isEmpty) compositeChildren(doc.root.children, buf, Area(rect, 1))
        return buf
    }

    /**
     * Zu rendernder Bereich: [rect] in Ausgabekoordinaten; Ausgabepixel (x, y) entspricht dem
     * Dokumentpixel (x·step + step/2, y·step + step/2).
     */
    private class Area(val rect: IntRect, val step: Int) {
        val width get() = rect.width
        val height get() = rect.height
        /** Abgedeckter Bereich in Dokumentkoordinaten. */
        val docRect: IntRect = if (step == 1) rect else IntRect(rect.left * step, rect.top * step, rect.right * step, rect.bottom * step)
        /** Mipmap-Stufe zum Abtastschritt: jedes Ausgabepixel mittelt über 2^level × 2^level Dokumentpixel. */
        val level = MipMap.levelFor(step)

        fun read(g: de.photographics.core.image.TileGrid, dx: Int, dy: Int, out: IntArray, scalar: Boolean = false) {
            if (step == 1) { g.readRect(rect.offset(-dx, -dy), out); return }
            val lg = if (level > 0) g.mip(level, scalar) else g
            val f = 1 shl level
            if (step == f) { lg.readRect(rect.offset(-Math.floorDiv(dx, f), -Math.floorDiv(dy, f)), out); return }
            val half = step / 2
            var i = 0
            for (y in rect.top until rect.bottom) {
                val sy = Math.floorDiv(y * step + half - dy, f)
                for (x in rect.left until rect.right) out[i++] = lg[Math.floorDiv(x * step + half - dx, f), sy]
            }
        }
    }

    /** Wiederverwendbare Arbeitspuffer je Thread (vermeidet Speicherbereinigung beim Malen). */
    private class Pool {
        val floats = ArrayDeque<FloatArray>()
        val ints = ArrayDeque<IntArray>()
        fun floats(n: Int): FloatArray {
            val it = floats.iterator()
            while (it.hasNext()) { val a = it.next(); if (a.size == n) { it.remove(); a.fill(0f); return a } }
            return FloatArray(n)
        }
        fun ints(n: Int): IntArray {
            val it = ints.iterator()
            while (it.hasNext()) { val a = it.next(); if (a.size == n) { it.remove(); return a } }
            return IntArray(n)
        }
        // Nur kleine Puffer behalten (Pinselstriche): große Streifenpuffer würden sonst in jedem
        // Rechen-Thread dauerhaft Java-Speicher belegen
        fun release(a: FloatArray) { if (floats.size < 8 && a.size <= POOL_MAX) floats.addLast(a) }
        fun release(a: IntArray) { if (ints.size < 4 && a.size <= POOL_MAX) ints.addLast(a) }
    }

    private const val POOL_MAX = 1 shl 18

    private val pools = ThreadLocal.withInitial { Pool() }

    /** true, wenn das Raster im Bereich keine einzige angelegte Kachel hat (also leer ist). */
    private fun isEmptyIn(g: de.photographics.core.image.TileGrid, r: IntRect): Boolean {
        if (g.defaultValue != 0) return false
        var empty = true
        g.forTilesIn(r) { tx, ty -> if (g.tile(tx, ty) != null) empty = false }
        return empty
    }

    /** Farbe des zusammengesetzten Bildes an einer Stelle (Pipette). */
    fun sample(doc: Document, x: Int, y: Int): Int = renderArgb(doc, IntRect(x, y, x + 1, y + 1))[0]

    fun toArgb(buf: FloatArray, out: IntArray) {
        for (i in out.indices) {
            val a = buf[i * 4 + 3]
            out[i] = if (a <= 1f / 512f) 0 else {
                val inv = 255f / a
                (to8(a * 255f) shl 24) or (to8(buf[i * 4] * inv) shl 16) or (to8(buf[i * 4 + 1] * inv) shl 8) or to8(buf[i * 4 + 2] * inv)
            }
        }
    }

    /** 0..255 (bereits skaliert) → gerundeter Kanalwert, begrenzt. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun to8(v: Float): Int { val x = (v + 0.5f).toInt(); return if (x < 0) 0 else if (x > 255) 255 else x }

    private fun compositeChildren(children: List<Layer>, dst: FloatArray, rect: Area) {
        var i = 0
        while (i < children.size) {
            val base = children[i]
            var j = i + 1
            while (j < children.size && children[j].clipped) j++
            val clippedLayers = children.subList(i + 1, j).filter { it.visible }
            if (base.visible) {
                if (clippedLayers.isEmpty()) {
                    drawLayer(base, dst, rect, atop = false)
                } else {
                    // Schnittmaske: Basis isoliert rendern, darüberliegende Ebenen "atop" einmischen,
                    // dann das Ergebnis mit Modus/Deckkraft der Basis auf den Hintergrund bringen.
                    val pool = pools.get()
                    val tmp = pool.floats(dst.size)
                    drawLayer(base, tmp, rect, atop = false, forceNormalOpaque = true)
                    for (c in clippedLayers) drawLayer(c, tmp, rect, atop = true)
                    blendBuffer(tmp, dst, base.blendMode.normalizedForBuffer(), base.opacity * base.fillOpacity, rect.rect, null)
                    pool.release(tmp)
                }
            }
            i = j
        }
    }

    private fun BlendMode.normalizedForBuffer() = if (this == BlendMode.PASS_THROUGH) BlendMode.NORMAL else this

    private fun drawLayer(layer: Layer, dst: FloatArray, rect: Area, atop: Boolean, forceNormalOpaque: Boolean = false) {
        // Unbekannte Photoshop-Einstellungsebenen (als Pixelebene gelesen) werden übersprungen
        if (layer.kind == LayerKind.ADJUSTMENT && layer !is AdjustmentLayer) return
        val alpha = if (forceNormalOpaque) 1f else layer.opacity * layer.fillOpacity
        val mode = if (forceNormalOpaque) BlendMode.NORMAL else layer.blendMode.normalizedForBuffer()
        val maskBuf = maskValues(layer, rect)
        when (layer) {
            is RasterLayer -> {
                val srcRect = rect.docRect.offset(-layer.previewDx, -layer.previewDy)
                val direct = layer is SmartLayer && layer.samplesDirectly
                val raster = if (direct) null else layer.raster
                if (raster != null && isEmptyIn(raster, srcRect)) return // nichts zu zeichnen
                val n = rect.width * rect.height
                val pool = pools.get()
                val px = pool.ints(n)
                if (direct) (layer as SmartLayer).samplePreview(rect.rect, rect.step, layer.previewDx, layer.previewDy, px)
                else rect.read(raster!!, layer.previewDx, layer.previewDy, px)
                val src = pool.floats(n * 4)
                for (k in 0 until n) {
                    val c = px[k]
                    val a = (c ushr 24) / 255f
                    if (a == 0f) continue
                    src[k * 4] = ((c shr 16) and 0xFF) / 255f * a
                    src[k * 4 + 1] = ((c shr 8) and 0xFF) / 255f * a
                    src[k * 4 + 2] = (c and 0xFF) / 255f * a
                    src[k * 4 + 3] = a
                }
                if (atop) blendAtop(src, dst, mode, alpha, maskBuf) else blendBuffer(src, dst, mode, alpha, rect.rect, maskBuf)
                pool.release(src); pool.release(px)
            }
            is AdjustmentLayer -> {
                // Korrigierte Version des Darunterliegenden erzeugen und wie eine Ebene einmischen
                val map = layer.rgbMap()
                val pool = pools.get()
                val src = pool.floats(dst.size)
                for (k in 0 until rect.width * rect.height) {
                    val o = k * 4
                    val a = dst[o + 3]
                    if (a <= 0f) continue
                    val inv = 255f / a
                    val rgb = (to8(dst[o] * inv) shl 16) or (to8(dst[o + 1] * inv) shl 8) or to8(dst[o + 2] * inv)
                    val m = map.map(rgb)
                    src[o] = ((m shr 16) and 0xFF) / 255f * a
                    src[o + 1] = ((m shr 8) and 0xFF) / 255f * a
                    src[o + 2] = (m and 0xFF) / 255f * a
                    src[o + 3] = a
                }
                if (atop) blendAtop(src, dst, mode, alpha, maskBuf) else blendAdjustment(src, dst, mode, alpha, maskBuf)
                pool.release(src)
            }
            is GroupLayer -> {
                if (layer.blendMode == BlendMode.PASS_THROUGH && !atop && !forceNormalOpaque) {
                    if (alpha >= 1f && maskBuf == null) {
                        compositeChildren(layer.children, dst, rect)
                    } else {
                        val copy = dst.copyOf()
                        compositeChildren(layer.children, copy, rect)
                        for (k in 0 until rect.width * rect.height) {
                            val t = alpha * (if (maskBuf != null) maskBuf[k] else 1f)
                            for (c in 0..3) {
                                val o = k * 4 + c
                                dst[o] += (copy[o] - dst[o]) * t
                            }
                        }
                    }
                } else {
                    val tmp = FloatArray(dst.size)
                    compositeChildren(layer.children, tmp, rect)
                    if (atop) blendAtop(tmp, dst, mode, alpha, maskBuf) else blendBuffer(tmp, dst, mode, alpha, rect.rect, maskBuf)
                }
            }
        }
    }

    private fun maskValues(layer: Layer, rect: Area): FloatArray? {
        val m = layer.mask ?: return null
        if (!m.enabled) return null
        val n = rect.width * rect.height
        val raw = IntArray(n)
        // Verbundene Masken bewegen sich in der Vorschau mit der Ebene, nicht verbundene einzeln
        val linkedDx = if (m.linked && layer is RasterLayer) layer.previewDx else 0
        val linkedDy = if (m.linked && layer is RasterLayer) layer.previewDy else 0
        rect.read(m.grid, m.previewDx + linkedDx, m.previewDy + linkedDy, raw, scalar = true)
        return FloatArray(n) { raw[it] / 255f }
    }

    /** W3C-Compositing: Quelle (vormultipliziert) mit Mischmodus über das Ziel legen. */
    private fun blendBuffer(src: FloatArray, dst: FloatArray, mode: BlendMode, alpha: Float, rect: IntRect, mask: FloatArray?) {
        val n = src.size / 4
        val tmp = FloatArray(3)
        val w = rect.width
        for (k in 0 until n) {
            var sa = src[k * 4 + 3] * alpha * (if (mask != null) mask[k] else 1f)
            if (sa <= 0f) continue
            val o = k * 4
            val srcA = src[o + 3]
            if (mode == BlendMode.DISSOLVE) {
                sa = if (noise(rect.left + k % w, rect.top + k / w) < sa) 1f else continue
            }
            val scale = sa / srcA
            val da = dst[o + 3]
            if (mode == BlendMode.NORMAL || mode == BlendMode.DISSOLVE || da <= 0f) {
                val inv = 1f - sa
                dst[o] = src[o] * scale + dst[o] * inv
                dst[o + 1] = src[o + 1] * scale + dst[o + 1] * inv
                dst[o + 2] = src[o + 2] * scale + dst[o + 2] * inv
                dst[o + 3] = sa + da * inv
                continue
            }
            val csR = src[o] / srcA; val csG = src[o + 1] / srcA; val csB = src[o + 2] / srcA
            val cbR = dst[o] / da; val cbG = dst[o + 1] / da; val cbB = dst[o + 2] / da
            mix(mode, cbR, cbG, cbB, csR, csG, csB, tmp)
            // Cs' = (1 - ab) * Cs + ab * B(Cb, Cs); co = as * Cs' + (1 - as) * cb_premult
            val inv = 1f - sa
            dst[o] = sa * ((1f - da) * csR + da * tmp[0]) + dst[o] * inv
            dst[o + 1] = sa * ((1f - da) * csG + da * tmp[1]) + dst[o + 1] * inv
            dst[o + 2] = sa * ((1f - da) * csB + da * tmp[2]) + dst[o + 2] * inv
            dst[o + 3] = sa + da * inv
        }
    }

    /**
     * Einstellungsebene: das korrigierte Bild ersetzt das Ziel gemäß Deckkraft und Maske,
     * die Deckkraft des Ziels selbst bleibt unverändert.
     */
    private fun blendAdjustment(src: FloatArray, dst: FloatArray, mode: BlendMode, alpha: Float, mask: FloatArray?) {
        val n = src.size / 4
        val tmp = FloatArray(3)
        for (k in 0 until n) {
            val o = k * 4
            val da = dst[o + 3]
            if (da <= 0f) continue
            val t = alpha * (if (mask != null) mask[k] else 1f)
            if (t <= 0f) continue
            if (mode == BlendMode.NORMAL || mode == BlendMode.PASS_THROUGH) {
                dst[o] += (src[o] - dst[o]) * t; dst[o + 1] += (src[o + 1] - dst[o + 1]) * t; dst[o + 2] += (src[o + 2] - dst[o + 2]) * t
            } else {
                val inv = 1f / da
                val cbR = dst[o] * inv; val cbG = dst[o + 1] * inv; val cbB = dst[o + 2] * inv
                mix(mode, cbR, cbG, cbB, src[o] * inv, src[o + 1] * inv, src[o + 2] * inv, tmp)
                dst[o] = da * (cbR + (tmp[0] - cbR) * t); dst[o + 1] = da * (cbG + (tmp[1] - cbG) * t); dst[o + 2] = da * (cbB + (tmp[2] - cbB) * t)
            }
        }
    }

    /** Wie [blendBuffer], aber das Ziel-Alpha bleibt erhalten (Schnittmasken). */
    private fun blendAtop(src: FloatArray, dst: FloatArray, mode: BlendMode, alpha: Float, mask: FloatArray?) {
        val n = src.size / 4
        val tmp = FloatArray(3)
        for (k in 0 until n) {
            val o = k * 4
            val da = dst[o + 3]
            if (da <= 0f) continue
            val srcA = src[o + 3]
            val sa = srcA * alpha * (if (mask != null) mask[k] else 1f)
            if (sa <= 0f) continue
            val csR = src[o] / srcA; val csG = src[o + 1] / srcA; val csB = src[o + 2] / srcA
            val cbR = dst[o] / da; val cbG = dst[o + 1] / da; val cbB = dst[o + 2] / da
            mix(mode, cbR, cbG, cbB, csR, csG, csB, tmp)
            dst[o] = da * (cbR + (tmp[0] - cbR) * sa)
            dst[o + 1] = da * (cbG + (tmp[1] - cbG) * sa)
            dst[o + 2] = da * (cbB + (tmp[2] - cbB) * sa)
        }
    }

    private fun mix(mode: BlendMode, br: Float, bg: Float, bb: Float, sr: Float, sg: Float, sb: Float, out: FloatArray) {
        if (mode.separable) {
            out[0] = mode.blendChannel(br, sr); out[1] = mode.blendChannel(bg, sg); out[2] = mode.blendChannel(bb, sb)
        } else {
            mode.blendNonSeparable(br, bg, bb, sr, sg, sb, out)
        }
    }

    /** Deterministisches Rauschen 0..1 für "Sprenkeln". */
    private fun noise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }
}
