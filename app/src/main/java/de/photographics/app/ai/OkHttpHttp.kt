package de.photographics.app.ai

import de.photographics.core.net.Http
import de.photographics.core.tr
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP für Modell-Downloads über OkHttp. Anders als HttpURLConnection baut OkHttp Verbindungen zu
 * IPv4- und IPv6-Adressen parallel auf („Happy Eyeballs“): In Netzen, die IPv6-Adressen vergeben,
 * es aber nicht weiterleiten, wartete der Download bei Hugging Face sonst 8 × 20 s vor dem ersten Byte.
 */
object OkHttpHttp : Http {
    private val client = OkHttpClient.Builder()
        .fastFallback(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    override fun get(url: String, range: String?): Http.Response {
        val req = Request.Builder().url(url)
            .header("User-Agent", "photographics (Android)")
            // Keine transparente Komprimierung: sonst fehlen Länge und Range-Unterstützung
            .header("Accept-Encoding", "identity")
            .apply { if (range != null) header("Range", range) }
            .build()
        val resp = client.newCall(req).execute()
        if (resp.code != 200 && resp.code != 206) { resp.close(); throw IOException(tr("Server antwortet mit {0}", resp.code)) }
        val body = resp.body
        return Http.Response(resp.code, resp.request.url.toString(), body.contentLength(), { resp.header(it) }, body.byteStream()) { resp.close() }
    }
}
