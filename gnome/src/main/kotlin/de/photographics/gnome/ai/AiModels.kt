package de.photographics.gnome.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import de.photographics.core.net.Download
import de.photographics.core.tr
import de.photographics.gnome.onMain
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.zip.ZipInputStream

// Wie in der Android-App (app/…/ai/AiModels.kt); hier ohne Coroutines, Fortschritt im GTK-Hauptthread.

// Katalog gemeinsam mit Linux/Android im Kern (de.photographics.core.ai)
typealias AiPurpose = de.photographics.core.ai.AiPurpose
typealias AiModel = de.photographics.core.ai.AiModel

sealed class ModelStatus {
    data object Missing : ModelStatus()
    data class Downloading(val progress: Float) : ModelStatus()
    data object Ready : ModelStatus()
    data class Failed(val message: String) : ModelStatus()
}

/** Lädt, speichert und öffnet die KI-Modelle (ONNX Runtime, läuft komplett auf dem Rechner). */
object AiModelManager {
    private val root: File by lazy {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share")
        File(base, "photographics/models").apply { mkdirs() }
    }
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<AiModel, OrtSession>()
    private val running = HashMap<AiModel, Download>()

    /** Zustand je Modell; Änderungen melden [listeners] im Hauptthread. */
    val status = HashMap<AiModel, ModelStatus>().apply { for (m in AiModel.entries) put(m, if (modelFile(m) != null) ModelStatus.Ready else ModelStatus.Missing) }
    val listeners = ArrayList<() -> Unit>()

    val environment: OrtEnvironment get() = env

    fun modelFile(m: AiModel): File? =
        File(root, m.id).takeIf { it.isDirectory }?.walkTopDown()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") }

    fun isReady(m: AiModel) = modelFile(m) != null

    /** Gewähltes Modell eines Zwecks, sonst das erste installierte (Reihenfolge des Katalogs). */
    fun firstReady(p: AiPurpose, preferred: AiModel? = selected(p)): AiModel? =
        preferred?.takeIf { it.purpose == p && isReady(it) } ?: AiModel.entries.firstOrNull { it.purpose == p && isReady(it) }

    /** Vom Benutzer gewähltes Modell (gespeichert), auch wenn es noch nicht geladen ist. */
    fun selected(p: AiPurpose): AiModel? = AiModel.byId(prefs.getString("model_${p.name}"))?.takeIf { it.purpose == p }

    /** [m] für seinen Zweck wählen; Sitzungen anderer Modelle desselben Zwecks schließen (Speicher). */
    fun select(m: AiModel) {
        prefs.edit().putString("model_${m.purpose.name}", m.id).apply()
        synchronized(sessions) {
            for (o in sessions.keys.filter { it.purpose == m.purpose && it != m }) sessions.remove(o)?.close()
        }
        onMain { for (l in listeners.toList()) l() }
    }

    private fun setStatus(m: AiModel, s: ModelStatus) = onMain { status[m] = s; for (l in listeners.toList()) l() }

    /** Lädt [m] im Hintergrund herunter (parallele Teilstücke, Fortsetzung nach Abbruch). */
    fun download(m: AiModel) {
        if (running.containsKey(m)) return
        val dir = File(root, m.id)
        val tmp = File(root, "${m.id}.part")
        val job = Download(m.url, tmp)
        running[m] = job
        setStatus(m, ModelStatus.Downloading(0f))
        Thread({
            try {
                var shown = -1
                job.onProgress = { done, total ->
                    val t = if (total > 0) total else (m.sizeMb * 1_000_000).toLong()
                    val pct = (done * 200 / t).toInt()
                    if (pct != shown) { shown = pct; setStatus(m, ModelStatus.Downloading((done.toFloat() / t).coerceIn(0f, 1f))) }
                }
                job.run()
                dir.deleteRecursively(); dir.mkdirs()
                if (m.zipped) { unzip(tmp, dir); tmp.delete() }
                else if (!tmp.renameTo(File(dir, "model.onnx"))) { tmp.copyTo(File(dir, "model.onnx"), overwrite = true); tmp.delete() }
                if (modelFile(m) == null) throw IOException(tr("Archiv enthält kein ONNX-Modell"))
                setStatus(m, ModelStatus.Ready)
            } catch (e: Exception) {
                setStatus(m, if (job.cancelled) ModelStatus.Missing else ModelStatus.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                onMain { running.remove(m) }
            }
        }, "photographics-download").apply { isDaemon = true }.start()
    }

    fun cancel(m: AiModel) { running[m]?.cancelled = true }

    private fun unzip(zip: File, dir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(dir, e.name)
                // Schutz vor Pfaden außerhalb des Zielordners
                if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
            }
        }
    }

    fun delete(m: AiModel) {
        synchronized(sessions) { sessions.remove(m)?.close() }
        File(root, m.id).deleteRecursively()
        File(root, "${m.id}.part").delete(); File(root, "${m.id}.part.state").delete()
        setStatus(m, ModelStatus.Missing)
    }

    /** Öffnet (und hält) eine Inferenz-Sitzung; teuer beim ersten Aufruf. */
    fun session(m: AiModel): OrtSession = synchronized(sessions) {
        sessions.getOrPut(m) {
            val f = modelFile(m) ?: throw IOException(tr("Modell {0} ist nicht installiert", m.title))
            val opts = OrtSession.SessionOptions().apply {
                // Einen Kern für die Oberfläche frei lassen und nicht aktiv warten (Spinning) – sonst steht
                // die Oberfläche still, solange ein großes Modell rechnet
                setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8))
                addConfigEntry("session.intra_op.allow_spinning", "0")
                addConfigEntry("session.inter_op.allow_spinning", "0")
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(f.absolutePath, opts)
        }
    }

    /** Kleine Einstellungsdatei (wie SharedPreferences der App), z. B. kalibrierte Maskenkonvention. */
    val prefs = Prefs(File(root, "settings.properties"))

    class Prefs(private val file: File) {
        private val p = Properties().apply { runCatching { file.inputStream().use { load(it) } } }
        fun contains(k: String) = synchronized(p) { p.containsKey(k) }
        fun getBoolean(k: String, def: Boolean) = synchronized(p) { p.getProperty(k)?.toBoolean() ?: def }
        fun getString(k: String): String? = synchronized(p) { p.getProperty(k) }
        fun edit() = Editor()
        inner class Editor {
            private val changes = HashMap<String, String>()
            fun putBoolean(k: String, v: Boolean) = apply { changes[k] = v.toString() }
            fun putString(k: String, v: String) = apply { changes[k] = v }
            fun apply() = synchronized(p) {
                for ((k, v) in changes) p.setProperty(k, v)
                runCatching { file.outputStream().use { p.store(it, "photographics") } }
                Unit
            }
        }
    }
}
