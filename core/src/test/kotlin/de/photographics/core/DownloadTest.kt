package de.photographics.core

import com.sun.net.httpserver.HttpServer
import de.photographics.core.net.Download
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class DownloadTest {
    private val data = Random(7).nextBytes(9_000_000)
    private val rangeRequests = AtomicInteger()
    /** Antworten, die nach 1 MB abreißen (simuliert wackeliges Netz). */
    private val dropNext = AtomicInteger()
    private var ranges = true
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/model.onnx") { ex ->
            ex.responseHeaders.add("Location", "/cdn/model.onnx")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        createContext("/cdn/model.onnx") { ex ->
            val range = ex.requestHeaders.getFirst("Range")?.takeIf { ranges }
            try {
                if (range == null) {
                    ex.sendResponseHeaders(200, data.size.toLong()); ex.responseBody.use { it.write(data) }
                } else {
                    rangeRequests.incrementAndGet()
                    val (a, b) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                    ex.responseHeaders.add("Content-Range", "bytes $a-$b/${data.size}")
                    ex.sendResponseHeaders(206, (b - a + 1).toLong())
                    ex.responseBody.use { out ->
                        if (b - a > 2_000_000 && dropNext.getAndDecrement() > 0) { out.write(data, a, 1_000_000); out.flush(); throw IOException("abgerissen") }
                        out.write(data, a, b - a + 1)
                    }
                }
            } catch (_: IOException) { } finally { ex.close() }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/model.onnx"
    private val dir = File(System.getProperty("java.io.tmpdir"), "pg-download-${System.nanoTime()}").apply { mkdirs() }

    @After fun stop() { server.stop(0); dir.deleteRecursively() }

    @Test
    fun parallelRangesFollowRedirect() {
        val f = File(dir, "m.part")
        var last = 0L
        Download(url, f, parts = 4).apply { onProgress = { d, _ -> last = maxOf(last, d) } }.run()
        assertArrayEquals(data, f.readBytes())
        assertEquals(data.size.toLong(), last)
        assertEquals("Probe + 4 Stücke", 5, rangeRequests.get())
        assertTrue("Stand-Datei aufgeräumt", !File(f.path + ".state").exists())
    }

    @Test
    fun retriesBrokenPieces() {
        dropNext.set(3)
        val f = File(dir, "m.part")
        Download(url, f, parts = 4).run()
        assertArrayEquals(data, f.readBytes())
    }

    @Test
    fun resumesFromState() {
        val f = File(dir, "m.part")
        // Früherer Versuch: zweites Stück zur Hälfte, Rest leer
        f.writeBytes(ByteArray(data.size).also { System.arraycopy(data, 2_250_000, it, 2_250_000, 1_000_000) })
        File(f.path + ".state").writeText("${data.size}\n0 2250000 0\n2250000 4500000 1000000\n4500000 6750000 0\n6750000 9000000 0\n")
        var first = -1L
        Download(url, f, parts = 4).apply { onProgress = { d, _ -> if (first < 0) first = d } }.run()
        assertArrayEquals(data, f.readBytes())
        assertEquals(1_000_000L, first)
    }

    @Test
    fun fallsBackWithoutRanges() {
        ranges = false
        val f = File(dir, "m.part")
        Download(url, f, parts = 4).run()
        assertArrayEquals(data, f.readBytes())
    }
}
