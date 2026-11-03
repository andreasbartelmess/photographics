package de.photographics.core.editor

import de.photographics.core.doc.Document
import de.photographics.core.psd.PsdWriter
import java.io.File
import java.util.Properties

/**
 * Automatische Sicherung eines geöffneten Dokuments (eine Sicherung je Verzeichnis): Wird die App
 * beendet, ohne zu speichern (Android im Hintergrund, Absturz, Abmelden), bietet sie beim nächsten
 * Start an, die ungespeicherte Arbeit wiederherzustellen.
 *
 * Die Sicherung ist eine PSD; geschrieben wird erst in eine temporäre Datei, die nach dem
 * vollständigen Schreiben umbenannt wird – ein Abbruch hinterlässt nie eine halbe Sicherung.
 */
class Recovery(private val dir: File) {
    /** [name]: Anzeigename des Dokuments; [target]/[format]: Datei (URI bzw. Pfad), in die Strg+S speicherte. */
    data class Info(val name: String, val time: Long, val width: Int, val height: Int, val target: String?, val format: String?)

    private val psd = File(dir, "recovery.psd")
    private val meta = File(dir, "recovery.properties")

    val file: File get() = psd

    /** Vorhandene Sicherung oder null. */
    fun info(): Info? {
        if (!psd.isFile || !meta.isFile) return null
        return runCatching {
            val p = Properties().apply { meta.inputStream().use { load(it) } }
            Info(
                p.getProperty("name") ?: "", p.getProperty("time")?.toLong() ?: psd.lastModified(),
                p.getProperty("width")?.toInt() ?: 0, p.getProperty("height")?.toInt() ?: 0,
                p.getProperty("target"), p.getProperty("format"),
            )
        }.getOrNull()
    }

    fun write(doc: Document, name: String, target: String?, format: String?, tmpDir: File) {
        dir.mkdirs()
        val tmp = File(dir, "recovery.psd.tmp")
        tmp.outputStream().buffered(1 shl 16).use { PsdWriter.write(doc, it, tmpDir) }
        val p = Properties().apply {
            setProperty("name", name); setProperty("time", System.currentTimeMillis().toString())
            setProperty("width", doc.width.toString()); setProperty("height", doc.height.toString())
            target?.let { setProperty("target", it) }; format?.let { setProperty("format", it) }
        }
        val metaTmp = File(dir, "recovery.properties.tmp")
        metaTmp.outputStream().use { p.store(it, null) }
        // Erst die PSD, dann die Beschreibung: ohne beide gilt die Sicherung als nicht vorhanden
        meta.delete()
        if (!tmp.renameTo(psd)) { tmp.delete(); throw java.io.IOException("Sicherung konnte nicht abgelegt werden") }
        if (!metaTmp.renameTo(meta)) { metaTmp.delete(); throw java.io.IOException("Sicherung konnte nicht abgelegt werden") }
    }

    fun clear() {
        meta.delete(); psd.delete()
        File(dir, "recovery.psd.tmp").delete(); File(dir, "recovery.properties.tmp").delete()
    }

    /** Verzeichnis samt Inhalt entfernen (eigene Sicherung je Fenster, siehe GNOME). */
    fun delete() { clear(); dir.delete() }
}
