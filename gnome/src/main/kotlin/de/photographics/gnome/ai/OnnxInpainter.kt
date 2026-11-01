package de.photographics.gnome.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import de.photographics.core.image.Resample
import de.photographics.core.image.ResampleMethod
import de.photographics.core.retouch.Inpainter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Inpainting über ein ONNX-Modell (MI-GAN, LaMa). Erkennt Datentyp und Form der Ein-/Ausgaben
 * selbst; die Maskenkonvention (Lücke = hell oder dunkel) wird beim ersten Einsatz kalibriert.
 */
class OnnxInpainter(private val manager: AiModelManager, private val model: AiModel) : Inpainter {
    private val session: OrtSession get() = manager.session(model)

    override fun inpaint(px: IntArray, w: Int, h: Int, hole: BooleanArray): IntArray {
        val holeHigh = holeConvention()
        val result = run(px, w, h, hole, holeHigh)
        return IntArray(px.size) { if (hole[it]) result[it] else px[it] }
    }

    /** Modell auf Arbeitsgröße (meist 512²) rechnen und zurückskalieren. */
    private fun run(px: IntArray, w: Int, h: Int, hole: BooleanArray, holeHigh: Boolean): IntArray {
        val s = session
        val inputs = s.inputInfo.entries.toList()
        val imgIn = inputs.firstOrNull { !it.key.contains("mask", true) } ?: inputs[0]
        val maskIn = inputs.firstOrNull { it.key.contains("mask", true) } ?: inputs[1]
        val info = imgIn.value.info as TensorInfo
        val shape = info.shape
        val size = shape.lastOrNull()?.takeIf { it > 0 }?.toInt() ?: 512
        val sizeH = shape.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: size

        val img = Resample.resizeArgb(px, w, h, size, sizeH, ResampleMethod.BILINEAR)
        // Maske hochskalieren und etwas erweitern, damit keine Ränder der Lücke übrig bleiben
        val m = BooleanArray(size * sizeH) { i ->
            val x = (i % size) * w / size; val y = (i / size) * h / sizeH
            hole[y * w + x]
        }
        val mask = dilate(m, size, sizeH, 2)
        val env = manager.environment
        val n = size * sizeH
        val floatInput = info.type == OnnxJavaType.FLOAT
        val imgTensor: OnnxTensor
        val maskTensor: OnnxTensor
        if (floatInput) {
            val fb = FloatBuffer.allocate(3 * n)
            for (c in 0..2) for (i in 0 until n) fb.put(((img[i] shr (16 - 8 * c)) and 0xFF) / 255f)
            fb.rewind()
            imgTensor = OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, sizeH.toLong(), size.toLong()))
            val mb = FloatBuffer.allocate(n)
            for (i in 0 until n) mb.put(if (mask[i] == holeHigh) 1f else 0f)
            mb.rewind()
            maskTensor = OnnxTensor.createTensor(env, mb, longArrayOf(1, 1, sizeH.toLong(), size.toLong()))
        } else {
            val bb = ByteBuffer.allocateDirect(3 * n).order(ByteOrder.nativeOrder())
            for (c in 0..2) for (i in 0 until n) bb.put(((img[i] shr (16 - 8 * c)) and 0xFF).toByte())
            bb.rewind()
            imgTensor = OnnxTensor.createTensor(env, bb, longArrayOf(1, 3, sizeH.toLong(), size.toLong()), OnnxJavaType.UINT8)
            val mb = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
            for (i in 0 until n) mb.put(if (mask[i] == holeHigh) 255.toByte() else 0)
            mb.rewind()
            maskTensor = OnnxTensor.createTensor(env, mb, longArrayOf(1, 1, sizeH.toLong(), size.toLong()), OnnxJavaType.UINT8)
        }
        val out = IntArray(n)
        imgTensor.use { it2 ->
            maskTensor.use { mt ->
                s.run(mapOf(imgIn.key to it2, maskIn.key to mt)).use { res ->
                    val t = res[0] as OnnxTensor
                    val oshape = (t.info as TensorInfo).shape
                    val nchw = oshape.size == 4 && oshape[1] == 3L
                    if ((t.info as TensorInfo).type == OnnxJavaType.UINT8) {
                        val b = t.byteBuffer
                        for (i in 0 until n) {
                            val r: Int; val g: Int; val bl: Int
                            if (nchw) { r = b.get(i).toInt() and 0xFF; g = b.get(n + i).toInt() and 0xFF; bl = b.get(2 * n + i).toInt() and 0xFF }
                            else { r = b.get(i * 3).toInt() and 0xFF; g = b.get(i * 3 + 1).toInt() and 0xFF; bl = b.get(i * 3 + 2).toInt() and 0xFF }
                            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                        }
                    } else {
                        val f = t.floatBuffer
                        var mx = 0f
                        for (i in 0 until minOf(f.limit(), 4096)) mx = max(mx, f.get(i))
                        val k = if (mx > 2f) 1f else 255f // Ausgabe 0..1 oder 0..255
                        for (i in 0 until n) {
                            val r: Float; val g: Float; val bl: Float
                            if (nchw) { r = f.get(i); g = f.get(n + i); bl = f.get(2 * n + i) } else { r = f.get(i * 3); g = f.get(i * 3 + 1); bl = f.get(i * 3 + 2) }
                            fun c(v: Float) = (v * k).roundToInt().coerceIn(0, 255)
                            out[i] = (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(bl)
                        }
                    }
                }
            }
        }
        // Alpha vom Original übernehmen
        val back = Resample.resizeArgb(out, size, sizeH, w, h, ResampleMethod.BICUBIC)
        return IntArray(w * h) { (px[it] and 0xFF000000.toInt()) or (back[it] and 0xFFFFFF) }
    }

    /**
     * Ob das Modell Lücken als 1/255 erwartet. Test: rotes Quadrat als Lücke markieren – bleibt es
     * rot, hat das Modell es als bekannten Bereich behandelt und die Konvention ist umgekehrt.
     */
    private fun holeConvention(): Boolean {
        val key = "conv_${model.id}"
        val p = manager.prefs
        if (p.contains(key)) return p.getBoolean(key, true)
        val w = 128; val h = 128
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or ((60 + x) shl 16) or ((90 + y / 2) shl 8) or 140 }
        val hole = BooleanArray(w * h) { i -> (i % w) in 44..83 && (i / w) in 44..83 }
        for (i in px.indices) if (hole[i]) px[i] = 0xFFFF0000.toInt()
        val res = run(px, w, h, hole, holeHigh = true)
        var red = 0f; var cnt = 0
        for (i in res.indices) if (hole[i] && (i % w) in 54..73 && (i / w) in 54..73) {
            val c = res[i]; red += ((c shr 16) and 0xFF) - (((c shr 8) and 0xFF) + (c and 0xFF)) / 2f; cnt++
        }
        val conv = red / max(1, cnt) < 90f
        p.edit().putBoolean(key, conv).apply()
        return conv
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (!m[y * w + x]) continue
            for (yy in maxOf(0, y - r)..minOf(h - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(w - 1, x + r)) out[yy * w + xx] = true
        }
        return out
    }
}
