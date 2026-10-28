package de.photographics.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import de.photographics.core.editor.Upscaler
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.image.Resample
import de.photographics.core.image.ResampleMethod
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * KI-Hochskalierung (Real-ESRGAN). Rechnet kachelweise mit Überlappung und bringt jede Kachel
 * direkt auf die Zielauflösung – so bleibt der Speicherbedarf auch bei großen Bildern klein.
 */
class OnnxUpscaler(
    private val manager: AiModelManager,
    private val model: AiModel,
    private val onProgress: (Float) -> Unit,
) : Upscaler {

    override fun upscale(src: TileGrid, targetW: Int, targetH: Int): TileGrid {
        val w = src.width; val h = src.height
        val s = manager.session(model)
        val input = s.inputInfo.entries.first()
        val shape = (input.value.info as TensorInfo).shape
        val tile = shape.lastOrNull()?.takeIf { it > 0 }?.toInt() ?: 192
        val tileH = shape.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: tile
        val overlap = 12
        val stepX = tile - 2 * overlap; val stepY = tileH - 2 * overlap
        val sx = targetW.toDouble() / w; val sy = targetH.toDouble() / h

        // Klassisch skaliertes Ergebnis als Grundlage (liefert auch die Transparenz – ESRGAN kennt nur RGB)
        val out = Resample.resizeGrid(src, targetW, targetH, ResampleMethod.LANCZOS)

        val tilesX = max(1, ceil((w - 2.0 * overlap) / stepX).toInt()); val tilesY = max(1, ceil((h - 2.0 * overlap) / stepY).toInt())
        val total = tilesX * tilesY
        var done = 0
        val env = manager.environment
        val dw = max(1, (tile * sx).roundToInt()); val dh = max(1, (tileH * sy).roundToInt())
        fun tileY0(ty: Int) = min(max(0, h - tileH), ty * stepY)

        // Gleitendes Zeilenfenster statt Akkumulator in Zielgröße: nur ~2 Kachelzeilen im Speicher
        val winH = 2 * dh + 2
        val acc = FloatArray(targetW * winH * 3)
        val wsum = FloatArray(targetW * winH)
        var winStart = 0
        val inPx = IntArray(tile * tileH)
        val row = IntArray(targetW)

        fun flushRows(until: Int) {
            val end = until.coerceAtMost(targetH)
            for (y in winStart until end) {
                val r0 = y - winStart
                out.readRect(IntRect(0, y, targetW, y + 1), row)
                for (x in 0 until targetW) {
                    val o = r0 * targetW + x
                    val ws = wsum[o]
                    if (ws <= 0f) continue
                    val r = (acc[o * 3] / ws).roundToInt().coerceIn(0, 255); val g = (acc[o * 3 + 1] / ws).roundToInt().coerceIn(0, 255)
                    val b = (acc[o * 3 + 2] / ws).roundToInt().coerceIn(0, 255)
                    row[x] = (row[x] and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                }
                out.writeRect(IntRect(0, y, targetW, y + 1), row)
            }
            // Fenster verschieben
            val shift = end - winStart
            if (shift > 0) {
                val keep = winH - shift
                if (keep > 0) {
                    System.arraycopy(acc, shift * targetW * 3, acc, 0, keep * targetW * 3)
                    System.arraycopy(wsum, shift * targetW, wsum, 0, keep * targetW)
                }
                acc.fill(0f, maxOf(0, keep) * targetW * 3, acc.size)
                wsum.fill(0f, maxOf(0, keep) * targetW, wsum.size)
                winStart = end
            }
        }

        for (ty in 0 until tilesY) {
            val y0 = tileY0(ty)
            val oy = (y0 * sy).roundToInt()
            for (tx in 0 until tilesX) {
                val x0 = min(max(0, w - tile), tx * stepX)
                val rr = IntRect(x0, y0, min(w, x0 + tile), min(h, y0 + tileH))
                src.readRect(rr, inPx)
                val n = tile * tileH
                val fb = FloatBuffer.allocate(3 * n)
                for (c in 0..2) for (j in 0 until tileH) for (i in 0 until tile) {
                    // Randpixel wiederholen, falls das Bild kleiner als eine Kachel ist
                    val xi = i.coerceAtMost(rr.width - 1); val yj = j.coerceAtMost(rr.height - 1)
                    fb.put(((inPx[yj * rr.width + xi] shr (16 - 8 * c)) and 0xFF) / 255f)
                }
                fb.rewind()
                OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, tileH.toLong(), tile.toLong())).use { inT ->
                    s.run(mapOf(input.key to inT)).use { res ->
                        val t = res[0] as OnnxTensor
                        val os = (t.info as TensorInfo).shape
                        val ow = os[3].toInt(); val oh = os[2].toInt()
                        val f = t.floatBuffer
                        var mx = 0f
                        for (i in 0 until min(f.limit(), 4096)) mx = max(mx, f.get(i))
                        val k = if (mx > 2f) 1f else 255f
                        val on = ow * oh
                        val tilePx = IntArray(on) { i ->
                            fun c(v: Float) = (v * k).roundToInt().coerceIn(0, 255)
                            (0xFF shl 24) or (c(f.get(i)) shl 16) or (c(f.get(on + i)) shl 8) or c(f.get(2 * on + i))
                        }
                        // Kachel auf Zielmaßstab bringen und gewichtet ins Fenster addieren
                        val scaled = Resample.resizeArgb(tilePx, ow, oh, dw, dh, ResampleMethod.LANCZOS)
                        val ox = (x0 * sx).roundToInt()
                        val ovx = overlap * sx; val ovy = overlap * sy
                        for (j in 0 until dh) {
                            val ty2 = oy + j; if (ty2 >= targetH) break
                            val wr = ty2 - winStart
                            if (wr < 0 || wr >= winH) continue
                            val wy = ramp(j.toDouble(), dh.toDouble(), ovy, y0 == 0, y0 + tileH >= h)
                            for (i in 0 until dw) {
                                val tx2 = ox + i; if (tx2 >= targetW) break
                                val wgt = (wy * ramp(i.toDouble(), dw.toDouble(), ovx, x0 == 0, x0 + tile >= w)).toFloat()
                                if (wgt <= 0f) continue
                                val c = scaled[j * dw + i]; val o = wr * targetW + tx2
                                acc[o * 3] += ((c shr 16) and 0xFF) * wgt; acc[o * 3 + 1] += ((c shr 8) and 0xFF) * wgt; acc[o * 3 + 2] += (c and 0xFF) * wgt
                                wsum[o] += wgt
                            }
                        }
                    }
                }
                done++
                onProgress(done.toFloat() / total)
            }
            // Zeilen oberhalb der nächsten Kachelzeile sind fertig
            flushRows(if (ty + 1 < tilesY) (tileY0(ty + 1) * sy).roundToInt() else targetH)
        }
        flushRows(targetH)
        out.compact()
        return out
    }

    /** Gewicht 0..1, das an inneren Kachelrändern über die Überlappung ansteigt. */
    private fun ramp(p: Double, len: Double, ov: Double, firstEdge: Boolean, lastEdge: Boolean): Double {
        val a = if (firstEdge) 1.0 else ((p + 0.5) / max(1.0, ov)).coerceIn(0.0, 1.0)
        val b = if (lastEdge) 1.0 else ((len - p - 0.5) / max(1.0, ov)).coerceIn(0.0, 1.0)
        return min(a, b) + 1e-4
    }
}
