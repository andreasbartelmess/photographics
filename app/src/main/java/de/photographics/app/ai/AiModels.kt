package de.photographics.app.ai

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

enum class AiPurpose(val label: String) { INPAINT("Objekte entfernen"), UPSCALE("Hochskalieren") }

/** Frei lizenzierte Modelle, die bei Bedarf heruntergeladen werden (nicht in der APK enthalten). */
enum class AiModel(
    val id: String,
    val title: String,
    val purpose: AiPurpose,
    val url: String,
    val sizeMb: Float,
    val license: String,
    val zipped: Boolean,
    val description: String,
) {
    MIGAN(
        "migan", "MI-GAN", AiPurpose.INPAINT,
        "https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx",
        28f, "MIT (Picsart AI Research)", false, "Schnell, gut für kleine bis mittlere Objekte.",
    ),
    LAMA(
        "lama", "LaMa", AiPurpose.INPAINT,
        "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx",
        208f, "Apache 2.0 (Samsung AI)", false, "Hohe Qualität, auch für große Flächen und Strukturen.",
    ),
    ESRGAN_GENERAL(
        "esrgan-general", "Real-ESRGAN General", AiPurpose.UPSCALE,
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.65.0/real_esrgan_general_x4v3-onnx-float.zip",
        4.5f, "BSD 3-Clause (Tencent ARC)", true, "Schnelle 4×-Vergrößerung für Fotos.",
    ),
    ESRGAN_PLUS(
        "esrgan-x4plus", "Real-ESRGAN x4plus", AiPurpose.UPSCALE,
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_x4plus/releases/v0.65.0/real_esrgan_x4plus-onnx-float.zip",
        62f, "BSD 3-Clause (Tencent ARC)", true, "Beste Detailtreue, deutlich langsamer.",
    ),
}

sealed class ModelStatus {
    data object Missing : ModelStatus()
    data class Downloading(val progress: Float) : ModelStatus()
    data object Ready : ModelStatus()
    data class Failed(val message: String) : ModelStatus()
}

/** Lädt, speichert und öffnet die KI-Modelle (ONNX Runtime, läuft komplett auf dem Gerät). */
class AiModelManager(private val context: Context) {
    private val root = File(context.filesDir, "models")
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<AiModel, OrtSession>()

    private val _status = MutableStateFlow(AiModel.entries.associateWith { if (modelFile(it) != null) ModelStatus.Ready else ModelStatus.Missing })
    val status: StateFlow<Map<AiModel, ModelStatus>> = _status.asStateFlow()

    val environment: OrtEnvironment get() = env

    fun modelFile(m: AiModel): File? =
        File(root, m.id).walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".onnx") }

    fun isReady(m: AiModel) = modelFile(m) != null

    /** Erstes installiertes Modell eines Zwecks (bevorzugt in der Reihenfolge der Aufzählung). */
    fun firstReady(p: AiPurpose, preferred: AiModel? = null): AiModel? =
        preferred?.takeIf { it.purpose == p && isReady(it) } ?: AiModel.entries.firstOrNull { it.purpose == p && isReady(it) }

    private fun setStatus(m: AiModel, s: ModelStatus) = _status.update { it + (m to s) }

    suspend fun download(m: AiModel) = withContext(Dispatchers.IO) {
        val dir = File(root, m.id)
        val tmp = File(root, "${m.id}.part")
        try {
            root.mkdirs()
            setStatus(m, ModelStatus.Downloading(0f))
            var url = URL(m.url)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                val code = conn.responseCode
                if (code in 300..399 && redirects < 8) {
                    url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); redirects++; continue
                }
                if (code != 200) throw IOException("Server antwortet mit $code")
                break
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (m.sizeMb * 1_000_000).toLong()
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L; var last = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - last > 512 * 1024) { last = done; setStatus(m, ModelStatus.Downloading((done.toFloat() / total).coerceIn(0f, 1f))) }
                    }
                }
            }
            dir.deleteRecursively(); dir.mkdirs()
            if (m.zipped) unzip(tmp, dir) else tmp.copyTo(File(dir, "model.onnx"), overwrite = true)
            tmp.delete()
            if (modelFile(m) == null) throw IOException("Archiv enthält kein ONNX-Modell")
            setStatus(m, ModelStatus.Ready)
        } catch (e: Exception) {
            tmp.delete()
            setStatus(m, ModelStatus.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun unzip(zip: File, dir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(dir, e.name)
                // Schutz vor Pfaden außerhalb des Zielordners
                if (!f.canonicalPath.startsWith(dir.canonicalPath)) continue
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
            }
        }
    }

    fun delete(m: AiModel) {
        synchronized(sessions) { sessions.remove(m)?.close() }
        File(root, m.id).deleteRecursively()
        setStatus(m, ModelStatus.Missing)
    }

    /** Öffnet (und hält) eine Inferenz-Sitzung; teuer beim ersten Aufruf. */
    fun session(m: AiModel): OrtSession = synchronized(sessions) {
        sessions.getOrPut(m) {
            val f = modelFile(m) ?: throw IOException("Modell ${m.title} ist nicht installiert")
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(f.absolutePath, opts)
        }
    }

    val prefs get() = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
}
