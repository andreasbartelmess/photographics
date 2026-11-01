package de.photographics.gnome

import org.gnome.glib.GLib
import java.util.concurrent.Executors

/**
 * Der Engine-Thread besitzt alle Dokumente: Editor und Kachelspeicher sind nicht threadsicher.
 * Die Oberfläche läuft im GTK-Hauptthread und schickt Arbeit mit [post]/[call] hierher; Ergebnisse
 * kommen über [onMain] zurück.
 */
object Engine {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "photographics-engine").apply { isDaemon = true } }

    fun post(block: () -> Unit) = exec.execute {
        try { block() } catch (t: Throwable) { t.printStackTrace() }
    }

    /** Führt [work] auf dem Engine-Thread aus und meldet das Ergebnis im Hauptthread. */
    fun <T> call(work: () -> T, done: (Result<T>) -> Unit) = exec.execute {
        val r = runCatching(work)
        r.exceptionOrNull()?.printStackTrace()
        onMain { done(r) }
    }
}

/** Führt [block] im GTK-Hauptthread aus (aus jedem Thread aufrufbar). */
fun onMain(block: () -> Unit) {
    // Eine Ausnahme im GTK-Rückruf ließe die App einfrieren (siehe [guarded])
    GLib.idleAddOnce { guarded(block) }
}

/**
 * Für Rückrufe aus GTK (Zeichnen, Signale): eine Ausnahme darf nicht in den nativen Code laufen –
 * dort friert die App ein, statt einen Fehler zu melden.
 */
inline fun guarded(block: () -> Unit) {
    try { block() } catch (t: Throwable) { t.printStackTrace() }
}
