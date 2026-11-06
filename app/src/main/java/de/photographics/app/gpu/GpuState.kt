package de.photographics.app.gpu

import de.photographics.core.gpu.GpuDisplay
import de.photographics.core.tr

/**
 * Grafikkarte der Anzeige (siehe [GpuDisplay]). Der ViewModel legt sie an; ihr Kontext gehört
 * seinem Engine-Thread. Hier nur zum Anzeigen des Zustands in den Einstellungen.
 */
object GpuState {
    @Volatile var current: GpuDisplay? = null

    fun create(): GpuDisplay = GpuDisplay { HeadlessEgl14.open()?.let { AndroidGles() to it } }.also { current = it }

    /** Für die Einstellungen: welche Grafikkarte bzw. warum keine. */
    fun status(): String = current?.broken?.let { tr("Nicht verfügbar: {0}", it) }
        ?: current?.renderer ?: tr("Setzt das Bild auf der Grafikkarte zusammen; sonst rechnet der Prozessor")
}
