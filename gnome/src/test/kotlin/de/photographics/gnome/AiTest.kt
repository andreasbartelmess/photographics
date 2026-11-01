package de.photographics.gnome

import de.photographics.gnome.ai.AiModel
import de.photographics.gnome.ai.AiModelManager
import de.photographics.gnome.ai.ModelStatus
import de.photographics.gnome.ai.OnnxInpainter
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Lädt MI-GAN und füllt ein Loch in einem Verlauf (nur mit PG_AI_TEST=1, braucht Netz;
 * XDG_DATA_HOME auf einen Testordner setzen, damit nichts im Benutzerprofil landet).
 */
class AiTest {
    @Test
    fun miganFillsHole() {
        assumeTrue(System.getenv("PG_AI_TEST") == "1")
        val m = AiModel.MIGAN
        if (!AiModelManager.isReady(m)) {
            // Download läuft im eigenen Thread; Status kommt über den GLib-Hauptkontext – hier direkt abwarten
            AiModelManager.download(m)
            val ctx = org.gnome.glib.MainContext.default_()
            val t0 = System.currentTimeMillis()
            while (!AiModelManager.isReady(m) && System.currentTimeMillis() - t0 < 300_000) ctx.iteration(false).also { Thread.sleep(20) }
            assertTrue("Download: ${AiModelManager.status[m]}", AiModelManager.isReady(m))
        }
        val w = 256; val h = 256
        val px = IntArray(w * h) { i -> val x = i % w; (0xFF shl 24) or (x shl 16) or (x shl 8) or 255 - x }
        val hole = BooleanArray(w * h) { i -> (i % w) in 100..155 && (i / w) in 100..155 }
        val t = System.nanoTime()
        val out = OnnxInpainter(AiModelManager, m).inpaint(px, w, h, hole)
        println("MI-GAN: ${(System.nanoTime() - t) / 1_000_000} ms")
        // Im Loch soll der Verlauf weitergeführt werden: Rot ≈ x
        var err = 0.0; var n = 0
        for (i in px.indices) if (hole[i]) { err += Math.abs(((out[i] shr 16) and 0xFF) - (i % w)); n++ }
        println("mittlere Abweichung ${err / n}")
        assertTrue("Abweichung ${err / n}", err / n < 30)
        assertTrue(AiModelManager.status[m] is ModelStatus.Ready || AiModelManager.isReady(m))
    }
}
