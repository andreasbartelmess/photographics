package de.photographics.core.tools

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.Colors
import de.photographics.core.image.IntRect
import de.photographics.core.image.SlabTileMemory
import de.photographics.core.image.TileMemory
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.Compositor
import de.photographics.core.tiff.TiffReader
import de.photographics.core.tiff.TiffWriter
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/**
 * Kompatibilitätsprüfung mit einer eigenen PSD/TIFF/XCF-Sammlung (z. B. einem Archiv):
 *
 *   ./gradlew :core:psdCheck -PpsdDir=/pfad/zum/archiv [-PpsdOut=ordner] [-PpsdRoundtrip=false]
 *
 * Für jede PSD/PSB/TIFF (rekursiv) wird das Ergebnis von Photographics mit dem Bild verglichen, das
 * Photoshop selbst in der Datei gespeichert hat („Kompatibilität maximieren“). Optional wird die
 * Datei außerdem als PSD neu geschrieben, wieder gelesen und verglichen (Rundlauf).
 *
 * Die Quelldateien werden nur gelesen. Bericht (report.html, report.csv) und Vergleichsbilder
 * auffälliger Dateien landen im Ausgabeordner (Standard: core/build/psd-check).
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: run {
        System.err.println("Ordner fehlt: ./gradlew :core:psdCheck -PpsdDir=/pfad/zu/psds"); return
    })
    require(dir.isDirectory) { "Kein Ordner: $dir" }
    val out = File(args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "build/psd-check").absoluteFile
    require(!out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Der Ausgabeordner darf nicht im Archiv liegen: $out" }
    val roundtrip = args.getOrNull(2)?.lowercase() != "false"
    PsdArchiveCheck(dir, out, roundtrip).run()
}

class PsdArchiveCheck(private val dir: File, private val out: File, private val roundtrip: Boolean) {

    enum class Status(val label: String, val rank: Int) {
        ERROR("Fehler", 0), DIFF("Abweichung", 1), MINOR("Kleine Abweichung", 2), ROUNDTRIP("Rundlauf weicht ab", 3),
        UNVERIFIED("Ohne Referenzbild", 4), OK("OK", 5), SKIPPED("Übersprungen", 6),
    }

    class Result(
        val file: File, val status: Status, val detail: String = "",
        val size: String = "", val layers: Int = 0, val meanDiff: Double = 0.0, val badShare: Double = 0.0,
        val roundtripBad: Double = 0.0, val warnings: List<String> = emptyList(), val millis: Long = 0, val thumbs: Pair<String, String>? = null,
    )

    private val thumbDir = File(out, "bilder")
    private val tmp = File(out, "tmp")

    fun run() {
        TileMemory.current = SlabTileMemory(limitBytes = 6L shl 30, newSlab = { ByteBuffer.allocateDirect(it) })
        out.mkdirs(); thumbDir.mkdirs(); tmp.mkdirs()
        val files = dir.walkTopDown()
            .onEnter { !it.name.startsWith(".") || it == dir }
            .filter { it.isFile && it.extension.lowercase() in setOf("psd", "psb", "tif", "tiff", "xcf") && !it.name.startsWith("._") }
            .sortedBy { it.path.lowercase() }.toList()
        println("${files.size} PSD/PSB/TIFF-Dateien in $dir")
        val results = ArrayList<Result>()
        for ((i, f) in files.withIndex()) {
            val r = check(f)
            results += r
            println("[%d/%d] %-18s %s %s".format(i + 1, files.size, r.status.label, f.relativeTo(dir).path, r.detail))
            (TileMemory.current as? SlabTileMemory)?.reclaim()
            if (i % 8 == 7) System.gc()
            writeReports(results, done = i + 1 == files.size)
        }
        tmp.deleteRecursively()
        println()
        for (s in Status.entries) results.count { it.status == s }.takeIf { it > 0 }?.let { println("%-20s %d".format(s.label, it)) }
        println("Bericht: ${File(out, "report.html")}")
    }

    private fun check(f: File): Result {
        val t0 = System.currentTimeMillis()
        if (f.length() > Int.MAX_VALUE) return Result(f, Status.SKIPPED, "größer als 2 GB (noch nicht unterstützt)")
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                val tiff = TiffReader.isTiff(buf)
                val xcf = de.photographics.core.xcf.XcfReader.isXcf(buf)
                val doc = when { tiff -> TiffReader.read(buf.duplicate()); xcf -> de.photographics.core.xcf.XcfReader.read(buf.duplicate()); else -> PsdReader.read(buf.duplicate()) }
                // XCF enthält kein zusammengefügtes Bild: nur der Rundlauf wird geprüft
                val ref = when { tiff -> TiffReader.read(buf.duplicate(), layers = false); xcf -> null; else -> PsdReader.readComposite(buf.duplicate()) }
                val refGrid = (ref?.root?.children?.firstOrNull() as? PixelLayer)?.pixels
                val cmp = Comparison(doc.width, doc.height)
                val band = max(1, (1 shl 20) / doc.width)
                var y = 0
                while (y < doc.height) {
                    val r = IntRect(0, y, doc.width, minOf(doc.height, y + band))
                    val ours = Compositor.renderArgb(doc, r)
                    val theirs = IntArray(ours.size).also { a -> refGrid?.readRect(r, a) }
                    cmp.add(r, ours, theirs)
                    y = r.bottom
                }
                var rtBad = 0.0
                if (roundtrip) rtBad = roundtrip(doc, if (tiff) "tif" else if (xcf) "xcf" else "psd")
                val size = "${doc.width}×${doc.height}"
                val hasRef = doc.hasRealComposite != false && refGrid != null
                val status = when {
                    !hasRef -> Status.UNVERIFIED
                    cmp.badShare > 0.02 -> Status.DIFF
                    cmp.badShare > 0.001 -> Status.MINOR
                    rtBad > 0.001 -> Status.ROUNDTRIP
                    else -> Status.OK
                }.let { if (it == Status.UNVERIFIED && rtBad > 0.001) Status.ROUNDTRIP else it }
                val thumbs = if (status != Status.OK && hasRef) cmp.writeThumbs(f) else null
                val detail = buildString {
                    append(size)
                    if (hasRef) append(", Ø %.2f, %.2f %% Pixel abweichend".format(cmp.meanDiff, cmp.badShare * 100))
                    else append(", kein Referenzbild gespeichert")
                    if (roundtrip) append(", Rundlauf %.2f %%".format(rtBad * 100))
                }
                Result(f, status, detail, size, doc.allLayers().size, cmp.meanDiff, cmp.badShare, rtBad, doc.importWarnings.toList(),
                    System.currentTimeMillis() - t0, thumbs)
            }
        } catch (t: Throwable) {
            Result(f, Status.ERROR, "${t.javaClass.simpleName}: ${t.message}", millis = System.currentTimeMillis() - t0)
        }
    }

    /** Als PSD schreiben, wieder lesen, gerendert vergleichen: Anteil abweichender Pixel. */
    private fun roundtrip(doc: Document, format: String): Double {
        val file = File(tmp, "rundlauf.$format")
        try {
            file.outputStream().buffered(1 shl 16).use {
                when (format) {
                    "tif" -> TiffWriter.write(doc, it, File(tmp, "kanaele"))
                    "xcf" -> de.photographics.core.xcf.XcfWriter.write(doc, it, File(tmp, "kanaele"))
                    else -> PsdWriter.write(doc, it, File(tmp, "kanaele"))
                }
            }
            RandomAccessFile(file, "r").use { raf ->
                val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                val back = when (format) { "tif" -> TiffReader.read(mapped); "xcf" -> de.photographics.core.xcf.XcfReader.read(mapped); else -> PsdReader.read(mapped) }
                val cmp = Comparison(doc.width, doc.height, thumbs = false)
                val band = max(1, (1 shl 20) / doc.width)
                var y = 0
                while (y < doc.height) {
                    val r = IntRect(0, y, doc.width, minOf(doc.height, y + band))
                    cmp.add(r, Compositor.renderArgb(doc, r), Compositor.renderArgb(back, r))
                    y = r.bottom
                }
                return cmp.badShare
            }
        } finally {
            file.delete()
        }
    }

    /** Vergleich auf Weiß (wie Photoshops Referenzbild ohne Transparenz), mit Vorschaubildern. */
    private inner class Comparison(val w: Int, val h: Int, thumbs: Boolean = true) {
        private var sum = 0L; private var bad = 0L
        private val scale = max(1, (max(w, h) + 479) / 480)
        private val tw = (w + scale - 1) / scale; private val th = (h + scale - 1) / scale
        private val ours = if (thumbs) IntArray(tw * th) else null
        private val theirs = if (thumbs) IntArray(tw * th) else null
        val meanDiff get() = sum.toDouble() / (w.toLong() * h * 3)
        val badShare get() = bad.toDouble() / (w.toLong() * h)

        fun add(r: IntRect, a: IntArray, b: IntArray) {
            for (i in a.indices) {
                val ca = Colors.srcOver(-1, a[i]); val cb = Colors.srcOver(-1, b[i])
                val dr = abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF))
                val dg = abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF))
                val db = abs((ca and 0xFF) - (cb and 0xFF))
                sum += dr + dg + db
                if (max(dr, max(dg, db)) > 24) bad++
                if (ours != null) {
                    val x = i % r.width; val y = r.top + i / r.width
                    if (x % scale == 0 && y % scale == 0) { val k = (y / scale) * tw + x / scale; ours[k] = ca; theirs!![k] = cb }
                }
            }
        }

        fun writeThumbs(f: File): Pair<String, String> {
            val base = f.relativeTo(dir).path.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
            val names = "$base.photographics.png" to "$base.photoshop.png"
            for ((px, name) in listOf(ours!! to names.first, theirs!! to names.second)) {
                val img = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
                img.setRGB(0, 0, tw, th, px, 0, tw)
                ImageIO.write(img, "png", File(thumbDir, name))
            }
            return names
        }
    }

    private fun writeReports(results: List<Result>, done: Boolean) {
        val sorted = results.sortedWith(compareBy<Result> { it.status.rank }.thenByDescending { it.badShare })
        File(out, "report.csv").printWriter().use { w ->
            w.println("status;datei;groesse;ebenen;mittlere_abweichung;anteil_abweichend;rundlauf_abweichend;sekunden;hinweise;details")
            for (r in sorted) w.println(listOf(r.status.name, r.file.relativeTo(dir).path, r.size, r.layers, "%.3f".format(r.meanDiff),
                "%.5f".format(r.badShare), "%.5f".format(r.roundtripBad), "%.1f".format(r.millis / 1000.0),
                r.warnings.joinToString(" | "), r.detail).joinToString(";") { it.toString().replace(';', ',') })
        }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        File(out, "report.html").printWriter().use { w ->
            w.println("""<!doctype html><meta charset="utf-8"><title>PSD-Prüfung</title>
<style>body{font:14px system-ui,sans-serif;margin:24px;max-width:1400px}table{border-collapse:collapse;width:100%}
td,th{border-bottom:1px solid #ddd;padding:6px;text-align:left;vertical-align:top}img{max-width:240px;border:1px solid #ccc}
.s0,.s1{color:#b00020}.s2,.s3{color:#a15c00}.s5{color:#1b7f3b}small{color:#666}</style>""")
            w.println("<h1>PSD-Prüfung</h1><p>${esc(dir.path)} – ${results.size} Dateien${if (done) "" else " (läuft noch …)"}<br>")
            w.println(Status.entries.mapNotNull { s -> results.count { it.status == s }.takeIf { it > 0 }?.let { "${s.label}: $it" } }.joinToString(" · "))
            w.println("</p><table><tr><th>Status</th><th>Datei</th><th>Details und Hinweise</th><th>Photographics</th><th>Photoshop</th></tr>")
            for (r in sorted) {
                w.println("<tr><td class=s${r.status.rank}>${r.status.label}</td><td>${esc(r.file.relativeTo(dir).path)}<br><small>${r.layers} Ebenen, ${"%.1f".format(r.millis / 1000.0)} s</small></td>")
                w.println("<td>${esc(r.detail)}${r.warnings.joinToString("") { "<br><small>${esc(it)}</small>" }}</td>")
                w.println(r.thumbs?.let { "<td><img src=\"bilder/${it.first}\"></td><td><img src=\"bilder/${it.second}\"></td>" } ?: "<td></td><td></td>")
                w.println("</tr>")
            }
            w.println("</table>")
        }
    }
}
