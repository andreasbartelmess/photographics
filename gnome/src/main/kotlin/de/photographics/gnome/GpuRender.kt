package de.photographics.gnome

import de.photographics.core.doc.Document
import de.photographics.core.gpu.GpuDisplay
import de.photographics.core.image.IntRect
import de.photographics.core.tr
import de.photographics.gpu.HeadlessEgl
import de.photographics.gpu.LwjglGles

/**
 * Zusammensetzen auf der Grafikkarte für die Anzeige (siehe [GpuDisplay]). Der GL-Kontext ohne
 * Fenster gehört dem Engine-Thread, wie die Dokumente: [render] nur dort aufrufen.
 * `PG_GPU=0` schaltet die Grafikkarte für einen Start ab.
 */
object GpuRender {
    private val display = GpuDisplay { HeadlessEgl.open()?.let { LwjglGles() to it } }.also {
        it.enabled = Settings.gpuCompose && System.getenv("PG_GPU") != "0"
    }
    private val STATS = System.getenv("PG_RENDER_STATS") != null

    /** Die letzte Anzeige kam von der Grafikkarte. */
    val fast get() = display.fast

    /** Für die Einstellungen: welche Grafikkarte bzw. warum keine. */
    fun status(): String = display.broken?.let { tr("Nicht verfügbar: {0}", it) }
        ?: display.renderer ?: tr("Setzt das Bild auf der Grafikkarte zusammen; sonst rechnet der Prozessor")

    fun settingChanged() {
        display.enabled = Settings.gpuCompose && System.getenv("PG_GPU") != "0"
        if (!display.enabled) Engine.post { display.close() }
    }

    /** Beim Start: GL-Kontext und Shader im Hintergrund vorbereiten. */
    fun warmUp() = Engine.post {
        display.warmUp()
        display.renderer?.let { println("GPU-Zusammensetzen: $it") }
    }

    /** Engine-Thread: [rect] (Ausgabepixel bei Schritt [step]) als ARGB, oder null → Prozessor. */
    fun render(doc: Document, rect: IntRect, step: Int): IntArray? {
        val t0 = System.nanoTime()
        return display.render(doc, rect, step).also {
            if (STATS && it != null) println("  GPU ${rect.width}×${rect.height}: ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }
}
