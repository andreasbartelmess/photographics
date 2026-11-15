package de.photographics.gnome

import de.photographics.core.image.TileGrid
import de.photographics.gnome.ai.AiModel
import de.photographics.gnome.ai.AiModelManager
import de.photographics.gnome.ai.OnnxUpscaler
import org.junit.Assume.assumeTrue
import org.junit.Test

/** PG_UPSCALE_BENCH=1 (XDG_DATA_HOME mit installierten Modellen): Laufzeit der KI-Hochskalierung messen. */
class UpscaleBench {
    @Test fun bench() {
        assumeTrue(System.getenv("PG_UPSCALE_BENCH") == "1")
        val w = (System.getenv("PG_UPSCALE_W") ?: "800").toInt(); val h = w * 3 / 4
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w
            val v = ((x * 7 + y * 13) xor (x * y)) and 255
            (0xFF shl 24) or ((x * 255 / w) shl 16) or (v shl 8) or (y * 255 / h) }
        val src = TileGrid.fromPixels(w, h, px)
        for (m in listOf(AiModel.ESRGAN_GENERAL, AiModel.ESRGAN_PLUS)) {
            if (!AiModelManager.isReady(m)) { println("BENCH ${m.id} fehlt"); continue }
            var t = System.nanoTime(); val s = AiModelManager.session(m)
            println("BENCH ${m.id}: Sitzung ${(System.nanoTime() - t) / 1_000_000} ms, Eingabe ${(s.inputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape.toList()}")
            var last = 0f
            t = System.nanoTime()
            OnnxUpscaler(AiModelManager, m) { p -> if (p - last >= 0.25f) { last = p; println("BENCH   ${(p * 100).toInt()} % nach ${(System.nanoTime() - t) / 1_000_000} ms") } }.upscale(src, w * 4, h * 4)
            println("BENCH ${m.id}: ${w}x$h → 4x in ${(System.nanoTime() - t) / 1_000_000} ms")
        }
    }
}
