package de.photographics.core.gpu

import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer
import de.photographics.core.doc.LayerKind
import de.photographics.core.doc.RasterLayer
import de.photographics.core.doc.SmartLayer
import de.photographics.core.gpu.Gles.C
import de.photographics.core.image.IntRect
import de.photographics.core.image.MipMap
import de.photographics.core.image.Tile
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Setzt das Dokument auf der Grafikkarte zusammen – dieselbe Rechnung wie
 * [de.photographics.core.render.Compositor.renderArgbSampled], aber als Shader-Durchgänge:
 * je Ebene wird ihr Ausschnitt aus den Kacheltexturen in einen Puffer gelesen und dann mit
 * Mischmodus, Deckkraft und Maske über das bisherige Bild gelegt (Ping-Pong zwischen Puffern,
 * vormultipliziert, 16-Bit-Gleitkomma, wenn der Treiber das kann).
 *
 * Die Kacheln bleiben im Kachelspeicher die Quelle der Wahrheit; hochgeladen werden nur Kacheln,
 * deren Objekt oder [Tile.version] sich geändert hat. Verkleinerte Stufen kommen (vorerst) aus
 * den [MipMap]s der CPU.
 *
 * Nicht threadsicher: alle Aufrufe auf dem GL-Thread, während niemand das Dokument ändert.
 */
class GpuCompositor(private val gl: Gles, preferHalfFloat: Boolean = false, allowFetch: Boolean = true) {
    /** Zwischenpuffer: Textur + Framebuffer gleicher Größe. */
    /**
     * Zwischenpuffer: Textur + Framebuffer mit fester Kapazität [capW]×[capH]; genutzt wird der Teil
     * [width]×[height] ab (0, 0). So entstehen beim Wechsel der Stückgröße keine neuen GL-Objekte
     * (auf Mobiltreibern teuer).
     */
    class Buffer internal constructor(val texture: Int, internal val fbo: Int, internal val capW: Int, internal val capH: Int) {
        var width = capW; internal set
        var height = capH; internal set
    }

    /** Zwischenpuffer mit Gleitkomma (sonst 8 Bit je Kanal). */
    val floatBuffers: Boolean
    /** 32-Bit-Gleitkomma wie die CPU (sonst 16 Bit): Divisionen in Mischmodi bleiben genau. */
    val fullFloat: Boolean
    val renderer: String

    private val vao: Int
    /** Kantenlänge der Stücke von [renderArgb]: [MAX_CHUNK], höchstens die größte Textur des Treibers. */
    val chunk: Int get() = minOf(MAX_CHUNK, maxTexture)
    private val maxTexture: Int
    private val sampleProg: Program
    private val blendProg: Program
    private val outputProg: Program
    /** Mischen an Ort und Stelle (`EXT_shader_framebuffer_fetch`, Mobil-GPUs); null = Ping-Pong. */
    private val fetchProg: Program?
    /** Mischt an Ort und Stelle (siehe [fetchProg]). */
    val inPlace: Boolean get() = fetchProg != null
    /** 8-Bit-Puffer für die Ausgabe (wiederverwendet, solange die Größe gleich bleibt). */
    private var out8: Buffer? = null
    private var readBuf: ByteBuffer? = null
    private val free = ArrayList<Buffer>()
    private val uploadBuf: ByteBuffer = ByteBuffer.allocateDirect(Tile.SIZE * 4).order(ByteOrder.nativeOrder())
    private val uploadInts = IntArray(Tile.SIZE)
    private var frame = 0L
    /** Zähler für Messungen: Zeichenaufrufe und hochgeladene Kacheln seit dem letzten [resetStats]. */
    var draws = 0; private set
    var uploads = 0; private set
    fun resetStats() { draws = 0; uploads = 0 }

    init {
        val ext = gl.getString(C.EXTENSIONS).orEmpty()
        fullFloat = "GL_EXT_color_buffer_float" in ext && !preferHalfFloat
        floatBuffers = fullFloat || "GL_EXT_color_buffer_half_float" in ext
        renderer = gl.getString(C.RENDERER).orEmpty()
        maxTexture = gl.getInteger(C.MAX_TEXTURE_SIZE).takeIf { it >= 1024 } ?: 2048
        vao = gl.genVertexArray()
        sampleProg = Program(gl, Shaders.vertex, Shaders.sample)
        blendProg = Program(gl, Shaders.vertex, Shaders.blend)
        outputProg = Program(gl, Shaders.vertex, Shaders.output)
        fetchProg = if (allowFetch && "GL_EXT_shader_framebuffer_fetch" in ext)
            runCatching { Program(gl, Shaders.vertex, Shaders.blendSource(fetch = true)) }.getOrNull()?.also { fp ->
                gl.useProgram(fp.id)
                gl.uniform1i(fp.u("uLut"), 3); gl.uniform1i(fp.u("uLut3"), 4)
                gl.uniform1i(fp.u("uTile"), 0); gl.uniform1i(fp.u("uSrc"), 5); gl.uniform1i(fp.u("uDst"), 6); gl.uniform1i(fp.u("uMask"), 2)
            }
        else null
        // Feste Einheiten für die Tabellen: Sampler verschiedener Typen dürfen sich keine Einheit teilen
        gl.useProgram(blendProg.id)
        gl.uniform1i(blendProg.u("uLut"), 3); gl.uniform1i(blendProg.u("uLut3"), 4)
        gl.pixelStorei(C.UNPACK_ALIGNMENT, 1)
        gl.pixelStorei(C.PACK_ALIGNMENT, 1)
    }

    // ------------------------------------------------------------ Was die GPU schon kann

    /**
     * Kann die GPU dieses Dokument bei Schritt [step] zusammensetzen? Sonst bleibt es bei der CPU
     * (gleiches Ergebnis).
     */
    fun supports(doc: Document, step: Int = 1): Boolean = doc.bitDepth != 16 && doc.root.children.all { supports(it, step) }

    private fun supports(l: Layer, step: Int): Boolean {
        if (!l.visible) return true
        if (l.kind == LayerKind.ADJUSTMENT && l !is AdjustmentLayer) return true // wird übersprungen
        return when (l) {
            // Harte Stufen rechnet die CPU in verkleinerten Ansichten aus voller Auflösung (renderExact)
            is AdjustmentLayer -> step == 1 || !l.adjustment.needsFullResolution
            is SmartLayer -> !l.samplesDirectly && l.effects?.visible != true
            is RasterLayer -> l.effects?.visible != true
            is GroupLayer -> l.children.all { supports(it, step) }
            else -> false
        }
    }

    // ------------------------------------------------------------ Zusammensetzen

    /**
     * Rendert [rect] (Ausgabekoordinaten, Ausgabepixel (x, y) = Dokumentpixel (x·step + step/2, …))
     * in einen Puffer; der Aufrufer gibt ihn mit [release] zurück.
     */
    fun render(doc: Document, rect: IntRect, step: Int): Buffer {
        require(supports(doc, step)) { "Dokument enthält Ebenen, die die GPU noch nicht kann" }
        frame++
        curRect = rect
        val ctx = Ctx(rect, step)
        val out = composite(doc.root.children, acquire(rect.width, rect.height, clear = true), ctx)
        trimTextures()
        return out
    }

    /**
     * [rect] bei Schritt [step] als ARGB in [out] (Zeilenlänge rect.width) – in Stücken von höchstens
     * [MAX_CHUNK]² Pixeln, damit Puffer klein bleiben und jede GPU sie anlegen kann.
     */
    fun renderArgb(doc: Document, rect: IntRect, step: Int, out: IntArray = IntArray(rect.width * rect.height)): IntArray {
        var y = rect.top
        while (y < rect.bottom) {
            val y1 = minOf(rect.bottom, y + chunk)
            var x = rect.left
            while (x < rect.right) {
                val x1 = minOf(rect.right, x + chunk)
                val part = IntRect(x, y, x1, y1)
                val b = render(doc, part, step)
                try {
                    if (part.width == rect.width) readArgb(b, chunk(part.width * part.height)).copyInto(out, (y - rect.top) * rect.width, 0, part.width * part.height)
                    else {
                        val px = readArgb(b, chunk(part.width * part.height))
                        for (r in 0 until part.height) System.arraycopy(px, r * part.width, out, (y - rect.top + r) * rect.width + (x - rect.left), part.width)
                    }
                } finally { release(b) }
                x = x1
            }
            y = y1
        }
        return out
    }

    private var chunkBuf = IntArray(0)
    private fun chunk(n: Int): IntArray { if (chunkBuf.size < n) chunkBuf = IntArray(n); return chunkBuf }

    /** Ergebnis als ARGB (nicht vormultipliziert, gerundet wie `Compositor.toArgb`), auf der GPU umgerechnet. */
    fun readArgb(b: Buffer, out: IntArray = IntArray(b.width * b.height)): IntArray {
        val n = b.width * b.height
        val o8 = out8?.takeIf { it.capW >= b.width && it.capH >= b.height } ?: run {
            out8?.let { gl.deleteFramebuffer(it.fbo); gl.deleteTexture(it.texture) }
            val cw = roundUp(b.width); val ch = roundUp(b.height)
            val t = gl.genTexture()
            gl.activeTexture(C.TEXTURE0); gl.bindTexture(C.TEXTURE_2D, t); nearest()
            gl.texImage2D(C.TEXTURE_2D, 0, C.RGBA8, cw, ch, C.RGBA, C.UNSIGNED_BYTE, null)
            val fb = gl.genFramebuffer()
            gl.bindFramebuffer(C.FRAMEBUFFER, fb)
            gl.framebufferTexture2D(C.FRAMEBUFFER, C.COLOR_ATTACHMENT0, C.TEXTURE_2D, t, 0)
            Buffer(t, fb, cw, ch).also { out8 = it }
        }
        o8.width = b.width; o8.height = b.height
        target(o8)
        gl.useProgram(outputProg.id)
        bindTex(0, b.texture); gl.uniform1i(outputProg.u("uSrc"), 0)
        gl.uniform2i(outputProg.u("uOut0"), 0, 0)
        quad(outputProg, 0, 0, o8.width, o8.height, o8)
        val rb = readBuf?.takeIf { it.capacity() >= n * 4 } ?: ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).also { readBuf = it }
        rb.clear().limit(n * 4)
        gl.readPixels(0, 0, b.width, b.height, C.RGBA, C.UNSIGNED_BYTE, rb)
        gl.bindFramebuffer(C.FRAMEBUFFER, 0)
        rb.position(0)
        val ib = rb.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        ib.get(out, 0, n)
        rb.order(ByteOrder.nativeOrder())
        return out
    }

    private class Ctx(val rect: IntRect, val step: Int) {
        val level = MipMap.levelFor(step)
        val f = 1 shl level
        val docRect = if (step == 1) rect else IntRect(rect.left * step, rect.top * step, rect.right * step, rect.bottom * step)
    }

    private fun composite(children: List<Layer>, dstIn: Buffer, c: Ctx): Buffer {
        var dst = dstIn
        var i = 0
        while (i < children.size) {
            val base = children[i]
            var j = i + 1
            while (j < children.size && children[j].clipped) j++
            val clipped = children.subList(i + 1, j).filter { it.visible }
            if (base.visible) {
                if (clipped.isEmpty()) dst = drawLayer(base, dst, c, atop = false)
                else {
                    // Schnittmaske: Basis isoliert, darüber atop, dann mit Modus/Deckkraft der Basis
                    var tmp = acquire(dst.width, dst.height, clear = true)
                    tmp = drawLayer(base, tmp, c, atop = false, forceNormalOpaque = true)
                    for (cl in clipped) tmp = drawLayer(cl, tmp, c, atop = true)
                    dst = blend(tmp, dst, null, base.blendMode.forBuffer(), base.opacity * base.fillOpacity, KIND_NORMAL)
                    release(tmp)
                }
            }
            i = j
        }
        return dst
    }

    private fun BlendMode.forBuffer() = if (this == BlendMode.PASS_THROUGH) BlendMode.NORMAL else this

    private fun drawLayer(layer: Layer, dst: Buffer, c: Ctx, atop: Boolean, forceNormalOpaque: Boolean = false): Buffer {
        if (layer.kind == LayerKind.ADJUSTMENT && layer !is AdjustmentLayer) return dst
        val alpha = if (forceNormalOpaque) 1f else layer.opacity * layer.fillOpacity
        val mode = if (forceNormalOpaque) BlendMode.NORMAL else layer.blendMode.forBuffer()
        val kind = if (atop) KIND_ATOP else KIND_NORMAL
        when (layer) {
            is RasterLayer -> {
                val raster = layer.raster
                if (isEmptyIn(raster, c.docRect.offset(-layer.previewDx, -layer.previewDy))) return dst
                val mask = maskOf(layer, c)
                val fp = fetchProg
                if (fp != null && raster.defaultValue == 0) {
                    // An Ort und Stelle: jede Kachel direkt aus ihrer Textur über das Ziel mischen
                    target(dst)
                    setBlend(fp, mask, mode, alpha, kind, srcTile = true)
                    drawTiles(raster, layer.previewDx, layer.previewDy, scalar = false, c, fp, dst)
                    mask?.let { release(it) }
                    return dst
                }
                val src = sampleGrid(raster, layer.previewDx, layer.previewDy, scalar = false, c)
                val out = blend(src, dst, mask, mode, alpha, kind)
                release(src); mask?.let { release(it) }
                return out
            }
            is AdjustmentLayer -> {
                val mask = maskOf(layer, c)
                val out = adjust(layer, dst, mask, mode, alpha, atop)
                mask?.let { release(it) }
                return out
            }
            is GroupLayer -> {
                val mask = maskOf(layer, c)
                val out = if (layer.blendMode == BlendMode.PASS_THROUGH && !atop && !forceNormalOpaque) {
                    if (alpha >= 1f && mask == null) composite(layer.children, dst, c)
                    else {
                        var copy = acquire(dst.width, dst.height, clear = false)
                        copy = blend(dst, copy, null, BlendMode.NORMAL, 1f, KIND_COPY, releaseDst = true)
                        copy = composite(layer.children, copy, c)
                        val r = blend(copy, dst, mask, BlendMode.NORMAL, alpha, KIND_LERP)
                        release(copy); r
                    }
                } else {
                    var tmp = acquire(dst.width, dst.height, clear = true)
                    tmp = composite(layer.children, tmp, c)
                    val r = blend(tmp, dst, mask, mode, alpha, kind)
                    release(tmp); r
                }
                mask?.let { release(it) }
                return out
            }
            else -> error("GPU: Ebenentyp ${layer::class.simpleName} nicht unterstützt")
        }
    }

    private fun maskOf(layer: Layer, c: Ctx): Buffer? {
        val m = layer.mask ?: return null
        if (!m.enabled) return null
        val linkedDx = if (m.linked && layer is RasterLayer) layer.previewDx else 0
        val linkedDy = if (m.linked && layer is RasterLayer) layer.previewDy else 0
        return sampleGrid(m.grid, m.previewDx + linkedDx, m.previewDy + linkedDy, scalar = true, c)
    }

    private fun isEmptyIn(g: TileGrid, r: IntRect): Boolean {
        if (g.defaultValue != 0) return false
        var empty = true
        g.forTilesIn(r) { tx, ty -> if (g.tile(tx, ty) != null) empty = false }
        return empty
    }

    /** Mischt [src] über [dst] in einen neuen Puffer; [dst] wird zurückgegeben (außer bei [releaseDst] = false). */
    private fun blend(src: Buffer, dst: Buffer, mask: Buffer?, mode: BlendMode, alpha: Float, kind: Int, releaseDst: Boolean = true): Buffer {
        val fp = fetchProg
        if (fp != null && (kind == KIND_NORMAL || kind == KIND_ATOP) && releaseDst) {
            target(dst)
            setBlend(fp, mask, mode, alpha, kind, srcTile = false)
            bindTex(5, src.texture)
            quad(fp, 0, 0, dst.width, dst.height, dst)
            return dst
        }
        val out = acquire(dst.width, dst.height, clear = false)
        target(out)
        val p = blendProg
        gl.useProgram(p.id)
        bindTex(0, src.texture); gl.uniform1i(p.u("uSrc"), 0)
        bindTex(1, dst.texture); gl.uniform1i(p.u("uDst"), 1)
        bindTex(2, (mask ?: src).texture); gl.uniform1i(p.u("uMask"), 2)
        gl.uniform1i(p.u("uHasMask"), if (mask != null) 1 else 0)
        gl.uniform1i(p.u("uMode"), mode.ordinal)
        gl.uniform1i(p.u("uKind"), kind)
        gl.uniform1f(p.u("uAlpha"), alpha)
        gl.uniform2i(p.u("uOut0"), curRect.left, curRect.top)
        quad(p, 0, 0, out.width, out.height, out)
        if (releaseDst) release(dst)
        return out
    }

    /** Einstellungsebene: das korrigierte Ziel nach Deckkraft und Maske einmischen (ein Durchgang). */
    private fun adjust(layer: AdjustmentLayer, dst: Buffer, mask: Buffer?, mode: BlendMode, alpha: Float, atop: Boolean): Buffer {
        val fn = shaderParams(layer.adjustment)
        val lut = if (fn != null) null else layer.rgbMap().let { map -> luts.getOrPut(map) { Lut(map) } }
        lut?.used = frame
        val fp = fetchProg
        val out = if (fp != null) dst else acquire(dst.width, dst.height, clear = false)
        target(out)
        val p = fp ?: blendProg
        gl.useProgram(p.id)
        if (fp != null) setBlend(fp, mask, mode, alpha, if (atop) KIND_ADJUST_ATOP else KIND_ADJUST, srcTile = false)
        else {
            bindTex(1, dst.texture); gl.uniform1i(p.u("uDst"), 1)
            bindTex(2, (mask ?: dst).texture); gl.uniform1i(p.u("uMask"), 2)
            gl.uniform1i(p.u("uSrc"), 1)
        }
        when {
            fn != null -> {
                gl.uniform1i(p.u("uLutMode"), 3)
                gl.uniform1i(p.u("uAdjType"), fn.first)
                for ((i, v) in fn.second.withIndex()) gl.uniform1f(p.u("uP[$i]"), v)
            }
            lut!!.is3d -> { gl.activeTexture(C.TEXTURE0 + 4); gl.bindTexture(C.TEXTURE_3D, lut.texture); gl.uniform1i(p.u("uLutMode"), 2) }
            else -> { bindTex(3, lut.texture); gl.uniform1i(p.u("uLutMode"), 1) }
        }
        gl.uniform1i(p.u("uHasMask"), if (mask != null) 1 else 0)
        gl.uniform1i(p.u("uMode"), mode.ordinal)
        gl.uniform1i(p.u("uKind"), if (atop) KIND_ADJUST_ATOP else KIND_ADJUST)
        gl.uniform1f(p.u("uAlpha"), alpha)
        gl.uniform2i(p.u("uOut0"), curRect.left, curRect.top)
        quad(p, 0, 0, out.width, out.height, out)
        if (out !== dst) release(dst)
        return out
    }

    /** Uniforms des Mischens an Ort und Stelle; Maske auf Einheit 2 (sonst ein leerer Platzhalter). */
    private fun setBlend(p: Program, mask: Buffer?, mode: BlendMode, alpha: Float, kind: Int, srcTile: Boolean) {
        gl.useProgram(p.id)
        if (mask != null) bindTex(2, mask.texture) else bindTex(2, 0)
        bindTex(6, 0)
        gl.uniform1i(p.u("uHasMask"), if (mask != null) 1 else 0)
        gl.uniform1i(p.u("uMode"), mode.ordinal)
        gl.uniform1i(p.u("uKind"), kind)
        gl.uniform1f(p.u("uAlpha"), alpha)
        gl.uniform1i(p.u("uSrcTile"), if (srcTile) 1 else 0)
        gl.uniform2i(p.u("uOut0"), curRect.left, curRect.top)
    }

    /**
     * Kanalübergreifende Einstellungen, die als Shader laufen (Typ, Parameter) – ein Reglerwechsel
     * kostet dann nichts. null: kanalweise Tabelle oder (LUT-Dateien) vollständige 3D-Tabelle.
     */
    private fun shaderParams(a: de.photographics.core.adjust.Adjustment): Pair<Int, FloatArray>? = when (a) {
        is de.photographics.core.adjust.VibranceAdj -> 1 to floatArrayOf(a.vibrance / 100f, a.saturation / 100f)
        is de.photographics.core.adjust.HueSaturationAdj -> 2 to floatArrayOf(
            if (a.colorize) ((a.hue + 360) % 360).toFloat() else a.hue.toFloat(), a.saturation / 100f, a.lightness / 100f, if (a.colorize) 1f else 0f)
        is de.photographics.core.adjust.ColorBalanceAdj -> 3 to floatArrayOf(
            a.shadows[0].toFloat(), a.shadows[1].toFloat(), a.shadows[2].toFloat(),
            a.midtones[0].toFloat(), a.midtones[1].toFloat(), a.midtones[2].toFloat(),
            a.highlights[0].toFloat(), a.highlights[1].toFloat(), a.highlights[2].toFloat(), if (a.preserveLuminosity) 1f else 0f)
        is de.photographics.core.adjust.BlackWhiteAdj -> {
            val t = FloatArray(3).also { de.photographics.core.image.Colors.hslToRgb(a.tintHue.toFloat(), a.tintSaturation / 100f, 0.5f, it) }
            4 to floatArrayOf(a.red / 100f, a.yellow / 100f, a.green / 100f, a.cyan / 100f, a.blue / 100f, a.magenta / 100f,
                if (a.tint) 1f else 0f, t[0], t[1], t[2])
        }
        is de.photographics.core.adjust.PhotoFilterAdj -> {
            val fc = a.color
            5 to floatArrayOf(((fc shr 16) and 0xFF) / 255f, ((fc shr 8) and 0xFF) / 255f, (fc and 0xFF) / 255f, a.density / 100f, if (a.preserveLuminosity) 1f else 0f)
        }
        is de.photographics.core.adjust.ThresholdAdj -> 6 to floatArrayOf(a.level.toFloat())
        else -> null
    }

    /**
     * Tabelle einer Einstellung auf der GPU: kanalweise ([ChannelLuts]) als 256×1-Textur, sonst als
     * vollständige 256³-Tabelle (exakt wie die CPU, die vor dem Nachschlagen auf 8 Bit rundet).
     */
    private inner class Lut(map: de.photographics.core.adjust.RgbMap) {
        val is3d = map !is de.photographics.core.adjust.ChannelLuts
        val texture = gl.genTexture()
        var used = 0L
        init {
            if (map is de.photographics.core.adjust.ChannelLuts) {
                val b = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
                for (i in 0 until 256) { b.put(map.r[i].toByte()); b.put(map.g[i].toByte()); b.put(map.b[i].toByte()); b.put(-1) }
                b.flip()
                gl.activeTexture(C.TEXTURE0); gl.bindTexture(C.TEXTURE_2D, texture); nearest()
                gl.texImage2D(C.TEXTURE_2D, 0, C.RGBA8, 256, 1, C.RGBA, C.UNSIGNED_BYTE, b)
            } else {
                // Rot als langsamste Achse (Ebenen), dann Grün (Zeilen), Blau (Spalten); je 16 Rot-Ebenen
                // auf allen Kernen gerechnet und als Scheibe hochgeladen (4 MB Puffer statt 64 MB)
                gl.activeTexture(C.TEXTURE0); gl.bindTexture(C.TEXTURE_3D, texture)
                for (pn in intArrayOf(C.TEXTURE_MIN_FILTER, C.TEXTURE_MAG_FILTER)) gl.texParameteri(C.TEXTURE_3D, pn, C.NEAREST)
                for (pn in intArrayOf(C.TEXTURE_WRAP_S, C.TEXTURE_WRAP_T, C.TEXTURE_WRAP_R)) gl.texParameteri(C.TEXTURE_3D, pn, C.CLAMP_TO_EDGE)
                gl.texImage3D(C.TEXTURE_3D, 0, C.RGBA8, 256, 256, 256, C.RGBA, C.UNSIGNED_BYTE, null)
                val b = slab()
                for (r0 in 0 until 256 step SLICE) {
                    de.photographics.core.image.Parallel.bands(SLICE, 256L * 256 * 8, compute = { k ->
                        val r = (r0 + k) shl 16
                        // Bytes R, G, B, A je Eintrag, als Ganzzahl in nativer Reihenfolge gepackt
                        val row = IntArray(256 * 256)
                        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                        for (g in 0 until 256) for (bl in 0 until 256) {
                            val v = map.map(r or (g shl 8) or bl)
                            val rr = (v shr 16) and 0xFF; val gg = (v shr 8) and 0xFF; val bb = v and 0xFF
                            row[g * 256 + bl] = if (little) (0xFF shl 24) or (bb shl 16) or (gg shl 8) or rr else (rr shl 24) or (gg shl 16) or (bb shl 8) or 0xFF
                        }
                        row
                    }, consume = { k, row ->
                        b.position(k * 256 * 256 * 4)
                        b.asIntBuffer().put(row)
                    })
                    b.position(0).limit(SLICE * 256 * 256 * 4)
                    gl.texSubImage3D(C.TEXTURE_3D, 0, 0, 0, r0, 256, 256, SLICE, C.RGBA, C.UNSIGNED_BYTE, b)
                }
            }
        }
    }

    private val luts = java.util.IdentityHashMap<de.photographics.core.adjust.RgbMap, Lut>()
    private var slab: ByteBuffer? = null
    /** Gemeinsamer Puffer für eine Scheibe einer 3D-Tabelle ([SLICE] Rot-Ebenen, 4 MB). */
    private fun slab(): ByteBuffer = slab ?: ByteBuffer.allocateDirect(SLICE * 256 * 256 * 4).order(ByteOrder.nativeOrder()).also { slab = it }

    /** Raster [g] (Versatz [dx], [dy] in Dokumentpixeln) für den Ausschnitt in einen Puffer lesen. */
    private fun sampleGrid(g: TileGrid, dx: Int, dy: Int, scalar: Boolean, c: Ctx): Buffer {
        val rect = c.rect
        val out = acquire(rect.width, rect.height, clear = false)
        target(out)
        val d = g.defaultValue
        if (scalar) gl.clearColor(d / 255f, d / 255f, d / 255f, d / 255f)
        else { val a = (d ushr 24) / 255f; gl.clearColor(((d shr 16) and 0xFF) / 255f * a, ((d shr 8) and 0xFF) / 255f * a, (d and 0xFF) / 255f * a, a) }
        gl.clear(C.COLOR_BUFFER_BIT)
        drawTiles(g, dx, dy, scalar, c, sampleProg, out)
        return out
    }

    /**
     * Zeichnet für jede Kachel von [g] (Versatz [dx]/[dy]), die den Ausschnitt berührt, ein Rechteck
     * mit Programm [p] nach [out] (schon als Ziel gesetzt, Programm-Uniforms außer den Kachelwerten
     * ebenfalls); die Kacheltextur liegt auf Einheit 0.
     */
    private fun drawTiles(g: TileGrid, dx: Int, dy: Int, scalar: Boolean, c: Ctx, p: Program, out: Buffer) {
        val rect = c.rect
        val lg = if (c.level > 0) g.mip(c.level, scalar) else g
        val f = c.f; val st = c.step; val half = st / 2
        // Benötigter Bereich in Pixeln der Stufe
        fun srcX(x: Int, d0: Int) = when {
            st == 1 -> x - d0
            st == f -> x - Math.floorDiv(d0, f)
            else -> Math.floorDiv(x * st + half - d0, f)
        }
        val need = IntRect(srcX(rect.left, dx), srcX(rect.top, dy), srcX(rect.right - 1, dx) + 1, srcX(rect.bottom - 1, dy) + 1).intersect(lg.extent)
        if (need.isEmpty) return
        gl.useProgram(p.id)
        gl.uniform1i(p.u("uTile"), 0)
        gl.uniform4i(p.u("uMap"), st, f, dx, dy)
        gl.uniform4i(p.u("uExt"), lg.extent.left, lg.extent.top, lg.extent.right, lg.extent.bottom)
        gl.uniform1i(p.u("uScalar"), if (scalar) 1 else 0)
        gl.uniform2i(p.u("uOut0"), rect.left, rect.top)
        val cache = textures.getOrPut(lg) { GridTextures(lg.tilesX * lg.tilesY) }
        cache.used = frame
        lg.forTilesIn(need) { tx, ty ->
            val tile = lg.tile(tx, ty) ?: return@forTilesIn
            val tex = cache.texture(tx + ty * lg.tilesX, tile, scalar)
            val tr = lg.tileRect(tx, ty)
            gl.uniform2i(p.u("uTile0"), lg.extent.left + tx * TileGrid.TILE, lg.extent.top + ty * TileGrid.TILE)
            bindTex(0, tex)
            // Ausgabepixel, deren Quelle in der Kachel liegt (großzügig; der Shader verwirft genau)
            fun outX(sx: Int, d0: Int) = when {
                st == 1 -> sx + d0
                st == f -> sx + Math.floorDiv(d0, f)
                else -> Math.floorDiv(sx * f - half + d0, st)
            }
            val m = if (st == 1 || st == f) 0 else 1
            val o = IntRect(outX(tr.left, dx) - m, outX(tr.top, dy) - m, outX(tr.right, dx) + m + 1, outX(tr.bottom, dy) + m + 1).intersect(rect)
            if (!o.isEmpty) quad(p, o.left - rect.left, o.top - rect.top, o.right - rect.left, o.bottom - rect.top, out)
        }
    }

    // ------------------------------------------------------------ Kacheltexturen

    private inner class GridTextures(n: Int) {
        val tex = IntArray(n)
        val tiles = arrayOfNulls<Tile>(n)
        val versions = IntArray(n)
        var used = 0L

        fun texture(i: Int, tile: Tile, scalar: Boolean): Int {
            if (tex[i] != 0 && tiles[i] === tile && versions[i] == tile.version) return tex[i]
            val fresh = tex[i] == 0
            uploads++
            if (fresh) tex[i] = gl.genTexture()
            versions[i] = tile.version
            tiles[i] = tile
            tile.read(0, uploadInts, 0, Tile.SIZE)
            uploadBuf.clear()
            if (scalar) { for (v in uploadInts) uploadBuf.put(v.toByte()) } else uploadBuf.asIntBuffer().put(uploadInts)
            uploadBuf.position(0).limit(if (scalar) Tile.SIZE else Tile.SIZE * 4)
            gl.activeTexture(C.TEXTURE0)
            gl.bindTexture(C.TEXTURE_2D, tex[i])
            val internal = if (scalar) C.R8 else C.RGBA8
            val format = if (scalar) C.RED else C.RGBA
            if (fresh) {
                nearest()
                gl.texImage2D(C.TEXTURE_2D, 0, internal, TileGrid.TILE, TileGrid.TILE, format, C.UNSIGNED_BYTE, uploadBuf)
            } else gl.texSubImage2D(C.TEXTURE_2D, 0, 0, 0, TileGrid.TILE, TileGrid.TILE, format, C.UNSIGNED_BYTE, uploadBuf)
            uploadBuf.clear()
            return tex[i]
        }

        fun delete() { for (t in tex) if (t != 0) gl.deleteTexture(t) }
    }

    private val textures = java.util.IdentityHashMap<TileGrid, GridTextures>()

    /** Texturen von Rastern freigeben, die länger nicht gebraucht wurden. */
    private fun trimTextures() {
        val it = textures.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (frame - e.value.used > KEEP_FRAMES) { e.value.delete(); it.remove() } }
        // Tabellen nicht mehr benutzter Einstellungen sofort freigeben (3D-Tabellen sind groß; beim
        // Ziehen eines Reglers entsteht für jeden Wert eine neue)
        val lt = luts.entries.iterator()
        while (lt.hasNext()) { val e = lt.next(); if (e.value.used != frame) { gl.deleteTexture(e.value.texture); lt.remove() } }
    }

    /** Wartet, bis die Grafikkarte fertig ist (für Messungen). */
    fun finish() = gl.finish()

    /** Alle GL-Objekte freigeben (vor dem Zerstören des Kontexts). */
    fun dispose() {
        for (g in textures.values) g.delete()
        textures.clear()
        for (l in luts.values) gl.deleteTexture(l.texture)
        luts.clear()
        for (b in free) { gl.deleteFramebuffer(b.fbo); gl.deleteTexture(b.texture) }
        free.clear()
        gl.deleteProgram(sampleProg.id); gl.deleteProgram(blendProg.id); gl.deleteProgram(outputProg.id)
        fetchProg?.let { gl.deleteProgram(it.id) }
        out8?.let { gl.deleteFramebuffer(it.fbo); gl.deleteTexture(it.texture) }; out8 = null
    }

    // ------------------------------------------------------------ Puffer und Zeichnen

    private var curRect = IntRect.EMPTY

    fun acquire(w: Int, h: Int, clear: Boolean): Buffer {
        // Kleinster freier Puffer, der passt
        var best = -1
        for ((k, f) in free.withIndex()) if (f.capW >= w && f.capH >= h && (best < 0 || f.capW * f.capH < free[best].capW * free[best].capH)) best = k
        val b = if (best >= 0) free.removeAt(best) else {
            val cw = roundUp(w); val ch = roundUp(h)
            val t = gl.genTexture()
            gl.activeTexture(C.TEXTURE0); gl.bindTexture(C.TEXTURE_2D, t)
            nearest()
            if (fullFloat) gl.texImage2D(C.TEXTURE_2D, 0, C.RGBA32F, cw, ch, C.RGBA, C.FLOAT, null)
            else if (floatBuffers) gl.texImage2D(C.TEXTURE_2D, 0, C.RGBA16F, cw, ch, C.RGBA, C.HALF_FLOAT, null)
            else gl.texImage2D(C.TEXTURE_2D, 0, C.RGBA8, cw, ch, C.RGBA, C.UNSIGNED_BYTE, null)
            val fb = gl.genFramebuffer()
            gl.bindFramebuffer(C.FRAMEBUFFER, fb)
            gl.framebufferTexture2D(C.FRAMEBUFFER, C.COLOR_ATTACHMENT0, C.TEXTURE_2D, t, 0)
            check(gl.checkFramebufferStatus(C.FRAMEBUFFER) == C.FRAMEBUFFER_COMPLETE) { "GPU: Framebuffer unvollständig" }
            Buffer(t, fb, cw, ch)
        }
        b.width = w; b.height = h
        if (clear) { target(b); gl.clearColor(0f, 0f, 0f, 0f); gl.clear(C.COLOR_BUFFER_BIT) }
        return b
    }

    fun release(b: Buffer) {
        free += b
        // Nicht unbegrenzt Puffer behalten (Größenwechsel beim Zoomen)
        while (free.size > MAX_FREE) free.removeAt(0).let { gl.deleteFramebuffer(it.fbo); gl.deleteTexture(it.texture) }
    }

    private fun target(b: Buffer) {
        gl.bindFramebuffer(C.FRAMEBUFFER, b.fbo)
        gl.viewport(0, 0, b.width, b.height)
        gl.disable(C.BLEND); gl.disable(C.SCISSOR_TEST); gl.disable(C.DEPTH_TEST)
    }

    private fun quad(p: Program, x0: Int, y0: Int, x1: Int, y1: Int, t: Buffer) {
        gl.uniform4f(p.u("uRect"), x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
        gl.uniform4f(p.u("uTarget"), t.width.toFloat(), t.height.toFloat(), 0f, 0f)
        gl.bindVertexArray(vao)
        gl.drawArrays(C.TRIANGLE_STRIP, 0, 4)
        draws++
    }

    private fun bindTex(unit: Int, tex: Int) { gl.activeTexture(C.TEXTURE0 + unit); gl.bindTexture(C.TEXTURE_2D, tex) }

    private fun nearest() {
        gl.texParameteri(C.TEXTURE_2D, C.TEXTURE_MIN_FILTER, C.NEAREST); gl.texParameteri(C.TEXTURE_2D, C.TEXTURE_MAG_FILTER, C.NEAREST)
        gl.texParameteri(C.TEXTURE_2D, C.TEXTURE_WRAP_S, C.CLAMP_TO_EDGE); gl.texParameteri(C.TEXTURE_2D, C.TEXTURE_WRAP_T, C.CLAMP_TO_EDGE)
    }

    private class Program(private val gl: Gles, vs: String, fs: String) {
        val id: Int
        private val locs = HashMap<String, Int>()
        init {
            fun shader(type: Int, src: String): Int {
                val s = gl.createShader(type)
                gl.shaderSource(s, src); gl.compileShader(s)
                check(gl.getShaderCompiled(s)) { "GPU: Shader nicht übersetzbar:\n" + gl.getShaderInfoLog(s) }
                return s
            }
            val v = shader(C.VERTEX_SHADER, vs); val f = shader(C.FRAGMENT_SHADER, fs)
            id = gl.createProgram()
            gl.attachShader(id, v); gl.attachShader(id, f); gl.linkProgram(id)
            check(gl.getProgramLinked(id)) { "GPU: Programm nicht verknüpfbar:\n" + gl.getProgramInfoLog(id) }
            gl.deleteShader(v); gl.deleteShader(f)
        }
        fun u(name: String) = locs.getOrPut(name) { gl.getUniformLocation(id, name) }
    }

    companion object {
        const val KIND_NORMAL = 0
        const val KIND_ATOP = 1
        const val KIND_LERP = 2
        const val KIND_COPY = 3
        const val KIND_ADJUST = 4
        const val KIND_ADJUST_ATOP = 5
        const val KEEP_FRAMES = 120
        const val MAX_FREE = 12
        /** Kapazität auf Vielfache von 256 runden (wenige verschiedene Puffergrößen). */
        fun roundUp(v: Int) = (v + 255) / 256 * 256
        const val SLICE = 16
        /**
         * Obergrenze für die Kantenlänge eines Stücks. Große Stücke sind viel schneller: Kachel-GPUs
         * (Adreno, Mali) zahlen je Satz Render-Durchgänge eine hohe feste Gebühr. Begrenzt zusätzlich
         * durch die größte Textur des Treibers ([chunk]).
         */
        @Volatile var MAX_CHUNK = 4096

        fun to8(v: Float): Int { val x = (v + 0.5f).toInt(); return if (x < 0) 0 else if (x > 255) 255 else x }
    }
}
