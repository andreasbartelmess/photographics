package de.photographics.core.net

import de.photographics.core.tr
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Minimale HTTP-Schnittstelle (GET, optional mit Range), damit die App einen eigenen Client einsetzen kann. */
fun interface Http {
    /** Folgt Weiterleitungen; wirft IOException bei einem Status außer 200/206. */
    fun get(url: String, range: String?): Response

    class Response(
        val code: Int,
        /** Adresse nach allen Weiterleitungen (z. B. die signierte CDN-Adresse). */
        val url: String,
        val contentLength: Long,
        private val headers: (String) -> String?,
        val body: InputStream,
        private val onClose: () -> Unit,
    ) : Closeable {
        fun header(name: String): String? = headers(name)
        override fun close() = onClose()
    }
}

/**
 * Standard-Client über HttpURLConnection. Achtung: Er probiert die Adressen eines Servers
 * nacheinander – in Netzen mit kaputtem IPv6 wartet er je IPv6-Adresse den Verbindungs-Timeout ab
 * (Hugging Face: 8 Adressen × 20 s). Die App nutzt deshalb einen Client mit parallelem IPv4/IPv6-Aufbau.
 */
class UrlConnectionHttp(private val userAgent: String = "photographics") : Http {
    override fun get(url: String, range: String?): Http.Response {
        var u = URL(url)
        repeat(9) {
            val conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000; conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", userAgent)
            // Keine transparente Komprimierung: sonst fehlen Länge und Range-Unterstützung
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) conn.setRequestProperty("Range", range)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException(tr("Server antwortet mit {0}", code))
                u = URL(u, loc); conn.disconnect(); return@repeat
            }
            if (code != 200 && code != 206) { conn.disconnect(); throw IOException(tr("Server antwortet mit {0}", code)) }
            return Http.Response(code, conn.url.toString(), conn.contentLengthLong, conn::getHeaderField, conn.inputStream) { conn.disconnect() }
        }
        throw IOException(tr("Zu viele Weiterleitungen"))
    }
}

/**
 * Herunterladen großer Dateien (KI-Modelle): bei Range-Unterstützung in [parts] parallelen
 * Teilstücken – einzelne Verbindungen zu manchen CDNs (Hugging Face) sind auf Mobilgeräten stark
 * gedrosselt. Abgebrochene Downloads werden fortgesetzt: [dest] bleibt als Teildatei liegen, der
 * Fortschritt je Stück steht in „<dest>.state“.
 */
class Download(
    private val url: String,
    private val dest: File,
    private val parts: Int = 4,
    private val http: Http = UrlConnectionHttp(),
) {
    /** Wird aus mehreren Threads aufgerufen: geladene Bytes und Gesamtgröße (≤ 0 = unbekannt). */
    var onProgress: (Long, Long) -> Unit = { _, _ -> }
    @Volatile var cancelled = false

    private val state = File(dest.path + ".state")

    /** Blockiert bis zum Ende; wirft IOException (Teildatei bleibt für die Fortsetzung erhalten). */
    fun run() {
        dest.parentFile?.mkdirs()
        // Weiterleitungen einmal auflösen (signierte CDN-Adresse) und Größe/Range-Fähigkeit erfragen
        val final: String; val total: Long; val ranges: Boolean
        http.get(url, "bytes=0-0").use { probe ->
            final = probe.url
            val t = probe.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()   // „bytes 0-0/208044816“
            ranges = probe.code == 206 && t != null
            total = if (ranges) t!! else probe.contentLength
        }

        val done = AtomicLong(0)
        if (!ranges || total < MIN_SPLIT) {
            dest.delete()
            http.get(final, null).use { resp ->
                resp.body.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (cancelled) throw IOException(tr("Abgebrochen"))
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); onProgress(done.addAndGet(n.toLong()), total)
                        }
                    }
                }
            }
            if (total > 0 && dest.length() != total) throw IOException(tr("Verbindung unterbrochen"))
            state.delete()
            return
        }

        // Stücke: Start, Ende (exklusiv), bereits geladen – ggf. aus einem früheren Versuch
        val resumable = dest.exists() && dest.length() == total
        val chunks = readState(total)?.takeIf { resumable }
            ?: List(parts) { k -> longArrayOf(total * k / parts, total * (k + 1) / parts, 0L) }
        if (!resumable) { dest.delete(); RandomAccessFile(dest, "rw").use { it.setLength(total) } }
        done.set(chunks.sumOf { it[2] })
        onProgress(done.get(), total)
        val pool = Executors.newFixedThreadPool(chunks.size)
        try {
            val jobs: List<Future<*>> = chunks.map { c ->
                pool.submit {
                    var attempt = 0
                    while (c[0] + c[2] < c[1]) {
                        try {
                            fetch(final, c, done, total) { synchronized(chunks) { writeState(total, chunks) } }
                        } catch (e: IOException) {
                            if (cancelled || ++attempt >= RETRIES) throw e
                            Thread.sleep(1000L * attempt)
                        }
                    }
                }
            }
            for (j in jobs) {
                try { j.get() } catch (e: java.util.concurrent.ExecutionException) {
                    cancelled = true
                    throw e.cause as? IOException ?: IOException(e.cause)
                }
            }
        } finally { pool.shutdownNow() }
        state.delete()
    }

    /** Lädt den Rest von Stück [c] an seine Stelle in [dest]; [checkpoint] sichert etwa alle 4 MB den Stand. */
    private fun fetch(url: String, c: LongArray, done: AtomicLong, total: Long, checkpoint: () -> Unit) {
        val from = c[0] + c[2]
        http.get(url, "bytes=$from-${c[1] - 1}").use { resp ->
            if (resp.code != 206) throw IOException(tr("Server unterstützt keine Teil-Downloads"))
            RandomAccessFile(dest, "rw").use { raf ->
                raf.seek(from)
                resp.body.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var sinceState = 0
                    try {
                        while (c[0] + c[2] < c[1]) {
                            if (cancelled) throw IOException(tr("Abgebrochen"))
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), c[1] - c[0] - c[2]).toInt())
                            if (n < 0) throw IOException(tr("Verbindung unterbrochen"))
                            raf.write(buf, 0, n); c[2] += n
                            onProgress(done.addAndGet(n.toLong()), total)
                            sinceState += n
                            if (sinceState >= 4 shl 20) { sinceState = 0; checkpoint() }
                        }
                    } finally { checkpoint() }
                }
            }
        }
    }

    private fun readState(total: Long): List<LongArray>? = try {
        val lines = state.readLines().filter { it.isNotBlank() }
        if (lines.firstOrNull()?.toLongOrNull() != total) null
        else lines.drop(1).map { l -> l.split(' ').map { it.toLong() }.toLongArray() }
            .takeIf { p -> p.isNotEmpty() && p.all { it.size == 3 && it[2] in 0..(it[1] - it[0]) } }
    } catch (_: Exception) { null }

    private fun writeState(total: Long, chunks: List<LongArray>) {
        state.writeText(buildString { append(total).append('\n'); for (c in chunks) append("${c[0]} ${c[1]} ${c[2]}\n") })
    }

    private companion object {
        const val MIN_SPLIT = 4L shl 20
        const val RETRIES = 4
    }
}
