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

    /** Wie [renderGrid], aber mit 16 Bit je Kanal: obere und untere Bits als zwei Raster. */
    fun renderGridDeep(doc: Document, area: IntRect = doc.bounds, onWhite: Boolean = false): Pair<de.photographics.core.image.TileGrid, de.photographics.core.image.TileGrid> {
        val hi = de.photographics.core.image.TileGrid(doc.width, doc.height, area = area)
        val lo = de.photographics.core.image.TileGrid(doc.width, doc.height, area = area)
        if (area.isEmpty) return hi to lo
        prepare(doc, 1, area)
        val band = 64
        val count = (area.height + band - 1) / band
        de.photographics.core.image.Parallel.bands(count, area.width.toLong() * band * 40, compute = { i ->
            val r = IntRect(area.left, area.top + i * band, area.right, minOf(area.bottom, area.top + (i + 1) * band))
            val f = render(doc, r)
            // Auf Weiß reduzieren (vormultipliziert: Farbe + (1 − Deckung))
            if (onWhite) for (k in 0 until f.size / 4) { val a = f[k * 4 + 3]; f[k * 4] += 1 - a; f[k * 4 + 1] += 1 - a; f[k * 4 + 2] += 1 - a; f[k * 4 + 3] = 1f }
            val s = ShortArray(f.size); toRgba16(f, s)
            val h = IntArray(r.width * r.height); val l = IntArray(r.width * r.height)
            for (k in h.indices) {
                var ch = 0; var cl = 0
                val shifts = Colors.SHIFTS_RGBA
                for (j in 0 until 4) { val sh = shifts[j]; val v = s[k * 4 + j].toInt() and 0xFFFF; ch = ch or ((v ushr 8) shl sh); cl = cl or ((v and 0xFF) shl sh) }
                h[k] = ch; l[k] = cl
            }
            Triple(r, h, l)
        }, consume = { _, (r, h, l) -> hi.writeRect(r, h); lo.writeRect(r, l) })
        hi.compact(); lo.compact()
        return hi to lo
    }

    /**
     * Rendert [area] in Streifen parallel auf allen Kernen; [consume] erhält die Streifen der Reihe
     * nach auf dem aufrufenden Thread.
     */
    fun renderBands(doc: Document, area: IntRect, consume: (IntRect, IntArray) -> Unit) {
        if (area.isEmpty) return
        prepare(doc, 1, area)
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
    fun prepare(doc: Document, step: Int = 1, area: IntRect? = null) {
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
        // Ebeneneffekte für den Bereich (Ausgabekoordinaten) vorab zwischenspeichern
        if (area != null) prepareEffects(doc, step, area)
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
            compositeChildren(doc.root.children, buf, Area(part, step, lightAngle(doc)))
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
            compositeChildren(layers, buf, Area(part, maxOf(1, step), lightAngle(doc), deep = step <= 1 && doc.bitDepth == 16))
            val tmp = IntArray(part.width * part.height)
            toArgb(buf, tmp)
            System.arraycopy(tmp, 0, out, (y - rect.top) * rect.width, tmp.size)
            y = y1
        }
        return out
    }

    /**
     * Nur die Rasterebene [layer] für [rect] (Ausgabekoordinaten bei [step]), ohne Vorschau-Verschiebung,
     * mit ihrer (verbundenen) Ebenenmaske; Deckkraft und Mischmodus bleiben außen vor. Für die
     * GPU-Vorschau beim Verschieben: die Ebene wird dort als eigene Bitmap gezeichnet.
     */
    fun renderLayerSampled(layer: RasterLayer, rect: IntRect, step: Int): IntArray {
        val area = Area(rect, maxOf(1, step))
        val n = rect.width * rect.height
        val px = IntArray(n)
        area.read(layer.raster, 0, 0, px)
        val m = layer.mask
        if (m != null && m.enabled) {
            val mv = IntArray(n)
            area.read(m.grid, m.previewDx, m.previewDy, mv, scalar = true)
            for (k in 0 until n) {
                val c = px[k]; val a = c ushr 24
                if (a == 0) continue
                px[k] = (((a * mv[k] + 127) / 255) shl 24) or (c and 0xFFFFFF)
            }
        }
        return px
    }

    /** Vormultiplizierter RGBA-Float-Puffer (4 Werte pro Pixel). */
    fun render(doc: Document, rect: IntRect): FloatArray {
        val buf = FloatArray(rect.width * rect.height * 4)
        if (!rect.isEmpty) compositeChildren(doc.root.children, buf, Area(rect, 1, lightAngle(doc), deep = doc.bitDepth == 16))
        return buf
    }

    /**
     * Zu rendernder Bereich: [rect] in Ausgabekoordinaten; Ausgabepixel (x, y) entspricht dem
     * Dokumentpixel (x·step + step/2, y·step + step/2).
     */
    /** Globaler Lichtwinkel für Schatten der Ebeneneffekte. */
    private fun lightAngle(doc: Document) = de.photographics.core.psd.PsdEffects.globalAngle(doc.preservedResources)

    /** [deep]: 16-Bit-Dokument in voller Auflösung – Ebenen mit unteren Bits lesen, Korrekturen ohne 8-Bit-Rundung. */
    private class Area(val rect: IntRect, val step: Int, val lightAngle: Double = 120.0, val deep: Boolean = false) {
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

    private val rgbOut get() = RGB_OUT.get()
    private val RGB_OUT = ThreadLocal.withInitial { FloatArray(3) }

    /**
     * Abbildung [map] (8 Bit je Kanal) für Zwischenwerte ([r], [g], [b] in 0..255): trilinear aus den
     * acht umgebenden Gitterpunkten. Liegt der Wert auf dem Gitter, genügt ein Aufruf.
     */
    fun mapSmooth(map: de.photographics.core.adjust.RgbMap, r: Float, g: Float, b: Float, out: FloatArray) {
        val rc = r.coerceIn(0f, 255f); val gc = g.coerceIn(0f, 255f); val bc = b.coerceIn(0f, 255f)
        val r0 = rc.toInt().coerceAtMost(254); val g0 = gc.toInt().coerceAtMost(254); val b0 = bc.toInt().coerceAtMost(254)
        val fr = rc - r0; val fg = gc - g0; val fb = bc - b0
        if (fr < 1e-3f && fg < 1e-3f && fb < 1e-3f) {
            val m = map.map((r0 shl 16) or (g0 shl 8) or b0)
            out[0] = ((m shr 16) and 0xFF).toFloat(); out[1] = ((m shr 8) and 0xFF).toFloat(); out[2] = (m and 0xFF).toFloat()
            return
        }
        var or = 0f; var og = 0f; var ob = 0f
        for (c in 0 until 8) {
            val dr = c and 1; val dg = (c shr 1) and 1; val db = (c shr 2) and 1
            val w = (if (dr == 1) fr else 1 - fr) * (if (dg == 1) fg else 1 - fg) * (if (db == 1) fb else 1 - fb)
            if (w <= 0f) continue
            val m = map.map(((r0 + dr) shl 16) or ((g0 + dg) shl 8) or (b0 + db))
            or += ((m shr 16) and 0xFF) * w; og += ((m shr 8) and 0xFF) * w; ob += (m and 0xFF) * w
        }
        out[0] = or; out[1] = og; out[2] = ob
    }

    /** Premultiplizierter Float-Puffer → 16 Bit je Kanal (R, G, B, A; nicht vormultipliziert). */
    fun toRgba16(buf: FloatArray, out: ShortArray) {
        val n = buf.size / 4
        for (i in 0 until n) {
            val a = buf[i * 4 + 3]
            if (a <= 1f / 131072f) { out[i * 4] = 0; out[i * 4 + 1] = 0; out[i * 4 + 2] = 0; out[i * 4 + 3] = 0; continue }
            val inv = 65535f / a
            fun q(v: Float) = (v + 0.5f).toInt().coerceIn(0, 65535).toShort()
            out[i * 4] = q(buf[i * 4] * inv); out[i * 4 + 1] = q(buf[i * 4 + 1] * inv); out[i * 4 + 2] = q(buf[i * 4 + 2] * inv); out[i * 4 + 3] = q(a * 65535f)
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
                val fx = layer.effects?.takeIf { it.visible }
                if (fx != null) { drawWithEffects(layer, fx, dst, rect, atop, alpha, mode, forceNormalOpaque); return }
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
                val low = if (rect.deep && rect.step == 1) (layer as? de.photographics.core.doc.PixelLayer)?.low else null
                if (low != null) {
                    // 16 Bit: oberes und unteres Byte je Kanal
                    val lo = pool.ints(n)
                    rect.read(low, layer.previewDx, layer.previewDy, lo)
                    val k16 = 1f / 65535f
                    for (k in 0 until n) {
                        val c = px[k]; val l = lo[k]
                        val a = (((c ushr 24) shl 8) or Colors.lowAlpha(c ushr 24, l ushr 24)) * k16
                        if (a == 0f) continue
                        src[k * 4] = ((((c shr 16) and 0xFF) shl 8) or ((l shr 16) and 0xFF)) * k16 * a
                        src[k * 4 + 1] = ((((c shr 8) and 0xFF) shl 8) or ((l shr 8) and 0xFF)) * k16 * a
                        src[k * 4 + 2] = (((c and 0xFF) shl 8) or (l and 0xFF)) * k16 * a
                        src[k * 4 + 3] = a
                    }
                    pool.release(lo)
                } else for (k in 0 until n) {
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
                    if (rect.deep) {
                        // Ohne Rundung auf 8 Bit: zwischen den Tabellenpunkten trilinear interpolieren
                        mapSmooth(map, dst[o] * inv, dst[o + 1] * inv, dst[o + 2] * inv, rgbOut)
                        src[o] = rgbOut[0] / 255f * a; src[o + 1] = rgbOut[1] / 255f * a; src[o + 2] = rgbOut[2] / 255f * a
                        src[o + 3] = a
                        continue
                    }
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

    // ---------------------------------------------------------------- Ebeneneffekte

    /** Ein Effekt in Zeichenreihenfolge; [exterior] = unter dem Inhalt (Schlagschatten, Schein außen). */
    private class FxPart(val color: Int, val opacity: Float, val blend: BlendMode, val exterior: Boolean)

    private fun fxParts(fx: de.photographics.core.doc.LayerEffects): List<FxPart> = buildList {
        for (s in fx.dropShadows) add(FxPart(s.color, s.opacity, s.blend, true))
        fx.outerGlow?.let { add(FxPart(it.color, it.opacity, it.blend, true)) }
        fx.innerGlow?.let { add(FxPart(it.color, it.opacity, it.blend, false)) }
        for (s in fx.innerShadows) add(FxPart(s.color, s.opacity, s.blend, false))
        for (s in fx.strokes) add(FxPart(s.color, s.opacity, s.blend, false))
        fx.satin?.let { add(FxPart(it.color, it.opacity, it.blend, false)) }
        fx.bevel?.let { b ->
            add(FxPart(b.highlightColor, b.highlightOpacity, b.highlightBlend, false))
            add(FxPart(b.shadowColor, b.shadowOpacity, b.shadowBlend, false))
        }
    }

    private fun fxMargin(fx: de.photographics.core.doc.LayerEffects, step: Int) = kotlin.math.ceil(fx.reach() / step).toInt() + 2

    /** Deckungen aller [fxParts] über einer Fläche [w]×[h] mit Inhaltsdeckung [a] (Ausgabepixel bei [step]). */
    private fun fxMaps(fx: de.photographics.core.doc.LayerEffects, a: FloatArray, w: Int, h: Int, step: Int, lightAngle: Double): List<FloatArray> {
        val n = a.size
        var any = false; var full = true
        for (v in a) { if (v > 0f) any = true; if (v < 1f) full = false; if (any && !full) break }
        val parts = fxParts(fx).size
        if (!any) return List(parts) { FloatArray(n) }
        fun ones() = FloatArray(n) { 1f }
        if (full) return buildList {
            // Ganz deckend: keine Kanten – außen verdeckt bzw. flächig, innen nichts außer Schein aus der Mitte
            for (s in fx.dropShadows) add(if (s.knockOut) FloatArray(n) else ones())
            fx.outerGlow?.let { add(ones()) }
            fx.innerGlow?.let { add(if (it.fromEdge) FloatArray(n) else a.copyOf()) }
            for (s in fx.innerShadows) add(FloatArray(n))
            for (s in fx.strokes) add(FloatArray(n))
            fx.satin?.let { add(if (it.invert) a.copyOf() else FloatArray(n)) }
            fx.bevel?.let { add(FloatArray(n)); add(FloatArray(n)) }
        }
        val scale = step.toDouble()
        fun angleOf(s: de.photographics.core.doc.LayerEffects.Shadow) = if (s.useGlobalAngle) lightAngle else s.angle
        val dist by lazy { Effects.distanceToInside(a, w, h) }
        return buildList {
            for (s in fx.dropShadows) add(Effects.dropShadow(a, w, h, s, angleOf(s), scale))
            fx.outerGlow?.let { add(Effects.outerGlow(a, w, h, it, scale)) }
            fx.innerGlow?.let { add(Effects.innerGlow(a, w, h, it, scale)) }
            for (s in fx.innerShadows) add(Effects.innerShadow(a, w, h, s, angleOf(s), scale))
            for (s in fx.strokes) add(Effects.stroke(a, w, h, s, scale, dist))
            fx.satin?.let { add(Effects.satin(a, w, h, it, scale)) }
            fx.bevel?.let { b -> val (hi, lo) = Effects.bevel(a, w, h, b, if (b.useGlobalAngle) lightAngle else b.angle, scale); add(hi); add(lo) }
        }
    }

    /** Inhaltsdeckung (Alpha × Maske) und Pixel der Ebene über [area]. */
    private fun fxContent(layer: RasterLayer, area: Area): Pair<IntArray, FloatArray> {
        val n = area.width * area.height
        val px = IntArray(n)
        if (layer is SmartLayer && layer.samplesDirectly) layer.samplePreview(area.rect, area.step, layer.previewDx, layer.previewDy, px)
        else area.read(layer.raster, layer.previewDx, layer.previewDy, px)
        val m = maskValues(layer, area)
        return px to FloatArray(n) { i -> (px[i] ushr 24) / 255f * (if (m != null) m[i] else 1f) }
    }

    /**
     * Zwischenspeicher der Effekt-Deckungen einer Ebene bei einem Abtastschritt: je vier Effekte als
     * Bytes in einem Kachelraster (nativer Speicher). Jede Kachel merkt sich eine Signatur der
     * Quellkacheln in Reichweite – nach einem Pinselstrich wird nur dessen Umgebung neu gerechnet.
     */
    private class FxCache(
        val fx: de.photographics.core.doc.LayerEffects, val angle: Double, val dx: Int, val dy: Int,
        val margin: Int, val parts: Int, w: Int, h: Int, area: IntRect,
    ) {
        val grids = Array((parts + 3) / 4) { de.photographics.core.image.TileGrid(w, h, area = area) }
        val sig = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    }

    /** Je Ebene die Zwischenspeicher der zuletzt benutzten Abtastschritte (Gesamtansicht, Ausschnitt, Vorschau). */
    private val fxCaches = java.util.WeakHashMap<Layer, LinkedHashMap<Int, FxCache>>()

    init {
        // Effekt-Zwischenspeicher lassen sich jederzeit neu rechnen: bei Speichermangel zuerst weg damit
        de.photographics.core.image.TileMemory.onPressure += { synchronized(fxCaches) { fxCaches.clear() } }
    }

    private fun fxCache(layer: Layer, step: Int): FxCache? = synchronized(fxCaches) { fxCaches[layer]?.get(step) }

    /** Signatur der Quellkacheln (Raster und Maske), aus denen die Effekte im Ausgabebereich [out] entstehen. */
    private fun fxSignature(layer: RasterLayer, out: IntRect, step: Int, margin: Int): Long {
        val docR = IntRect((out.left - margin) * step, (out.top - margin) * step, (out.right + margin) * step, (out.bottom + margin) * step)
        var h = 1125899906842597L
        fun mix(g: de.photographics.core.image.TileGrid, r: IntRect) = g.forTilesIn(r) { tx, ty ->
            val t = g.tile(tx, ty)
            h = h * 31 + (tx * 73856093L xor ty * 19349663L) + (if (t == null) 0L else System.identityHashCode(t) * 1000003L + t.version)
        }
        mix(layer.raster, docR.offset(-layer.previewDx, -layer.previewDy))
        layer.mask?.takeIf { it.enabled }?.let { m ->
            val ldx = if (m.linked) layer.previewDx else 0; val ldy = if (m.linked) layer.previewDy else 0
            mix(m.grid, docR.offset(-m.previewDx - ldx, -m.previewDy - ldy))
        }
        return h
    }

    /**
     * Effekt-Deckungen für den Ausgabebereich [area] (bei [step]) vorab auf allen Kernen rechnen und
     * zwischenspeichern – vor dem parallelen Rendern; fehlt etwas, rechnet [drawWithEffects] direkt.
     */
    fun prepareEffects(doc: Document, step: Int, area: IntRect) {
        if (area.isEmpty) return
        val angle = lightAngle(doc)
        for (l in doc.allLayers()) {
            if (l !is RasterLayer || !l.visible || (l is SmartLayer && l.samplesDirectly)) continue
            val fx = l.effects?.takeIf { it.visible } ?: continue
            val margin = fxMargin(fx, step)
            val parts = fxParts(fx).size
            if (parts == 0) continue
            val ow = (doc.width + step - 1) / step; val oh = (doc.height + step - 1) / step
            val ext = l.raster.extent
            val lArea = IntRect(Math.floorDiv(ext.left, step) - margin, Math.floorDiv(ext.top, step) - margin, (ext.right + step - 1) / step + margin, (ext.bottom + step - 1) / step + margin)
            val cache = synchronized(fxCaches) {
                val byStep = fxCaches.getOrPut(l) { LinkedHashMap() }
                (byStep.remove(step)?.takeIf { it.fx == fx && it.angle == angle && it.dx == l.previewDx && it.dy == l.previewDy && it.margin == margin }
                    ?: FxCache(fx, angle, l.previewDx, l.previewDy, margin, parts, ow, oh, lArea)).also { c ->
                    byStep[step] = c   // zuletzt benutzt ans Ende
                    while (byStep.size > 3) byStep.remove(byStep.keys.first())
                }
            }
            val g0 = cache.grids[0]
            val todo = ArrayList<Triple<Int, IntRect, Long>>()
            g0.forTilesIn(area.intersect(lArea)) { tx, ty ->
                val r = IntRect(g0.extent.left + (tx shl 8), g0.extent.top + (ty shl 8), g0.extent.left + ((tx + 1) shl 8), g0.extent.top + ((ty + 1) shl 8))
                val sig = fxSignature(l, r, step, margin)
                val idx = ty * g0.tilesX + tx
                if (cache.sig[idx] != sig) todo += Triple(idx, r, sig)
            }
            if (todo.isEmpty()) continue
            val tileBytes = (256L + 2 * margin) * (256L + 2 * margin) * 4 * (parts + 6)
            de.photographics.core.image.Parallel.bands(todo.size, tileBytes, compute = { i ->
                val r = todo[i].second
                val er = IntRect(r.left - margin, r.top - margin, r.right + margin, r.bottom + margin)
                val (_, a) = fxContent(l, Area(er, step, angle))
                val maps = fxMaps(fx, a, er.width, er.height, step, angle)
                // auf die Kachel zuschneiden und je vier Deckungen in ein ARGB-Wort packen
                val ew = er.width
                Array(cache.grids.size) { gi ->
                    var nonZero = false
                    val packed = IntArray(r.width * r.height) { k ->
                        val src = (k / r.width + margin) * ew + k % r.width + margin
                        var v = 0
                        for (c in 0 until 4) {
                            val mi = gi * 4 + c
                            if (mi < maps.size) v = v or ((maps[mi][src] * 255f + 0.5f).toInt().coerceIn(0, 255) shl (24 - 8 * c))
                        }
                        if (v != 0) nonZero = true
                        v
                    }
                    if (nonZero) packed else null
                }
            }, consume = { i, packed ->
                val (idx, r, sig) = todo[i]
                for (gi in packed.indices) {
                    val p = packed[gi]
                    val g = cache.grids[gi]
                    if (p != null) g.writeRect(r, p) else if (g.tile(idx % g0.tilesX, idx / g0.tilesX) != null) g.writeRect(r, IntArray(r.width * r.height))
                }
                cache.sig[idx] = sig
            })
        }
    }

    /** Deckungen für den Ausschnitt aus dem Zwischenspeicher, falls dort alles aktuell ist. */
    private fun cachedFxMaps(layer: RasterLayer, fx: de.photographics.core.doc.LayerEffects, rect: Area): List<FloatArray>? {
        if (layer is SmartLayer && layer.samplesDirectly) return null
        val c = fxCache(layer, rect.step) ?: return null
        if (c.fx != fx || c.angle != rect.lightAngle || c.dx != layer.previewDx || c.dy != layer.previewDy) return null
        val g0 = c.grids[0]
        var ok = true
        g0.forTilesIn(rect.rect) { tx, ty ->
            if (!ok) return@forTilesIn
            val r = IntRect(g0.extent.left + (tx shl 8), g0.extent.top + (ty shl 8), g0.extent.left + ((tx + 1) shl 8), g0.extent.top + ((ty + 1) shl 8))
            if (c.sig[ty * g0.tilesX + tx] != fxSignature(layer, r, rect.step, c.margin)) ok = false
        }
        // Teile des Ausschnitts außerhalb des gespeicherten Bereichs: dort gibt es keine Effekte
        if (!ok) return null
        val n = rect.width * rect.height
        val maps = List(c.parts) { FloatArray(n) }
        val buf = IntArray(n)
        val inside = rect.rect.intersect(g0.extent)
        for ((gi, g) in c.grids.withIndex()) {
            if (inside.isEmpty) break
            if (inside == rect.rect) g.readRect(rect.rect, buf) else { java.util.Arrays.fill(buf, 0); val tmp = IntArray(inside.width * inside.height); g.readRect(inside, tmp)
                for (y in 0 until inside.height) System.arraycopy(tmp, y * inside.width, buf, (inside.top - rect.rect.top + y) * rect.width + inside.left - rect.rect.left, inside.width) }
            for (ch in 0 until 4) {
                val mi = gi * 4 + ch
                if (mi >= c.parts) break
                val m = maps[mi]; val sh = 24 - 8 * ch
                for (k in 0 until n) m[k] = ((buf[k] shr sh) and 0xFF) / 255f
            }
        }
        return maps
    }

    /**
     * Rasterebene mit Ebeneneffekten, Reihenfolge wie in Photoshop (von unten): Schlagschatten,
     * Schein nach außen, Inhalt (Deckkraft × Fläche, Mischmodus der Ebene), Farbüberlagerung, Schein
     * nach innen, Schatten nach innen, Kontur. Jeder Effekt mischt mit eigenem Modus und eigener
     * Deckkraft; die Deckkraft der Ebene wirkt auf alles, die Fläche nur auf den Inhalt.
     */
    private fun drawWithEffects(layer: RasterLayer, fx: de.photographics.core.doc.LayerEffects, dst: FloatArray, rect: Area,
                                atop: Boolean, alpha: Float, mode: BlendMode, forceNormalOpaque: Boolean) {
        val r = rect.rect
        val w = r.width; val h = r.height
        val (px, a) = fxContent(layer, rect)
        val maps = cachedFxMaps(layer, fx, rect) ?: run {
            // Nicht vorbereitet: direkt über einer um die Reichweite erweiterten Fläche rechnen
            val margin = fxMargin(fx, rect.step)
            val er = IntRect(r.left - margin, r.top - margin, r.right + margin, r.bottom + margin)
            val (_, ea) = fxContent(layer, Area(er, rect.step, rect.lightAngle))
            val ew = er.width
            fxMaps(fx, ea, ew, er.height, rect.step, rect.lightAngle).map { e ->
                FloatArray(w * h).also { o -> for (y in 0 until h) System.arraycopy(e, (y + margin) * ew + margin, o, y * w, w) }
            }
        }
        val layerAlpha = if (forceNormalOpaque) 1f else layer.opacity
        fun put(e: FloatArray, color: Int, opacity: Float, m: BlendMode) {
            val src = FloatArray(w * h * 4)
            val cr = ((color shr 16) and 0xFF) / 255f; val cg = ((color shr 8) and 0xFF) / 255f; val cb = (color and 0xFF) / 255f
            var any = false
            for (k in 0 until w * h) {
                val ak = e[k] * opacity
                if (ak <= 0f) continue
                any = true
                src[k * 4] = cr * ak; src[k * 4 + 1] = cg * ak; src[k * 4 + 2] = cb * ak; src[k * 4 + 3] = ak
            }
            if (!any) return
            val bm = m.normalizedForBuffer()
            if (atop) blendAtop(src, dst, bm, layerAlpha, null) else blendBuffer(src, dst, bm, layerAlpha, r, null)
        }
        val parts = fxParts(fx)
        // Ganz deckender, normal gemischter Inhalt verdeckt Schatten und Schein darunter
        var full = true
        for (v in a) if (v < 1f) { full = false; break }
        val covered = full && layer.fillOpacity >= 1f && mode == BlendMode.NORMAL
        for ((k, p) in parts.withIndex()) if (p.exterior && !covered) put(maps[k], p.color, p.opacity, p.blend)
        run {
            val src = FloatArray(w * h * 4)
            for (k in 0 until w * h) {
                val c = px[k]
                val ca = (c ushr 24) / 255f
                if (ca == 0f) continue
                src[k * 4] = ((c shr 16) and 0xFF) / 255f * ca
                src[k * 4 + 1] = ((c shr 8) and 0xFF) / 255f * ca
                src[k * 4 + 2] = (c and 0xFF) / 255f * ca
                src[k * 4 + 3] = ca
            }
            val maskR = maskValues(layer, rect)
            // [alpha] ist Deckkraft × Fläche (bzw. 1 als isolierte Basis einer Schnittmaske)
            if (atop) blendAtop(src, dst, mode, alpha, maskR) else blendBuffer(src, dst, mode, alpha, r, maskR)
        }
        fx.gradientOverlay?.let { g ->
            // Verlauf über die Begrenzung der Ebene (Dokumentkoordinaten), wie Photoshops „An Ebene ausrichten“
            val b = layer.raster.contentBounds()
            if (!b.isEmpty) {
                val src = FloatArray(w * h * 4)
                val cx = (b.left + b.right) / 2.0; val cy = (b.top + b.bottom) / 2.0
                val ang = Math.toRadians(g.angle); val ux = kotlin.math.cos(ang); val uy = -kotlin.math.sin(ang)
                val half = (kotlin.math.abs(ux) * b.width + kotlin.math.abs(uy) * b.height) / 2 * g.scale
                val radius = kotlin.math.hypot(b.width.toDouble(), b.height.toDouble()) / 2 * g.scale
                var any = false
                for (k in 0 until w * h) {
                    val ak = a[k] * g.opacity
                    if (ak <= 0f) continue
                    any = true
                    val dx = (r.left + k % w + 0.5) * rect.step - cx; val dy = (r.top + k / w + 0.5) * rect.step - cy
                    val t = if (g.radial) (kotlin.math.hypot(dx, dy) / radius.coerceAtLeast(1.0)).toFloat()
                        else ((dx * ux + dy * uy) / (2 * half.coerceAtLeast(1.0)) + 0.5).toFloat()
                    val c = g.color(t)
                    src[k * 4] = ((c shr 16) and 0xFF) / 255f * ak; src[k * 4 + 1] = ((c shr 8) and 0xFF) / 255f * ak; src[k * 4 + 2] = (c and 0xFF) / 255f * ak; src[k * 4 + 3] = ak
                }
                if (any) { val bm = g.blend.normalizedForBuffer(); if (atop) blendAtop(src, dst, bm, layerAlpha, null) else blendBuffer(src, dst, bm, layerAlpha, r, null) }
            }
        }
        for (o in fx.colorOverlays) put(a, o.color, o.opacity, o.blend)
        for ((k, p) in parts.withIndex()) if (!p.exterior) put(maps[k], p.color, p.opacity, p.blend)
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
