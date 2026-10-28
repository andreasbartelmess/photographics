package de.photographics.core.diagnostics

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.filters.*
import de.photographics.core.history.ScratchDisk
import de.photographics.core.image.IntRect
import de.photographics.core.image.ResampleMethod
import de.photographics.core.image.SlabTileMemory
import de.photographics.core.image.TileGrid
import de.photographics.core.image.TileMemory
import de.photographics.core.psd.PsdReader
import de.photographics.core.psd.PsdWriter
import de.photographics.core.selection.Selection
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.BrushTool
import de.photographics.core.tools.PaintTarget
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Speicher-Selbsttest: eine harte Bearbeitungssitzung mit großem Dokument. Wird von der
 * Speicher-Sperre am PC (192 MB Heap) und vom Rauchtest auf dem Gerät benutzt.
 *
 * Regel für neue Funktionen: Sie gehören hier hinein und dürfen nie ein ganzes Bild im
 * Java-Speicher halten – Bilddaten liegen in Kacheln ([TileMemory]).
 */
object MemorySelfTest {
    class Result(val ok: Boolean, val failedStep: String?, val error: Throwable?, val maxHeapMb: Long, val maxTileMb: Long, val totalMs: Long)

    /** [full] = alle Filter und größeres Dokument (PC); sonst eine schnellere Auswahl (Gerät). */
    fun run(scratch: File, full: Boolean, log: (String) -> Unit): Result {
        val w = if (full) 5000 else 4000
        val h = if (full) 4000 else 3000
        val memory = TileMemory.current
        var maxHeap = 0L; var maxTiles = 0L
        val start = System.currentTimeMillis()
        fun heapMb(): Long { val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) shr 20 }
        var current = "Vorbereitung"
        fun step(name: String, block: () -> Unit) {
            current = name
            val t0 = System.currentTimeMillis()
            block()
            (memory as? SlabTileMemory)?.reclaim()
            System.gc()
            val heap = heapMb(); val tiles = memory.usedBytes shr 20
            maxHeap = maxOf(maxHeap, heap); maxTiles = maxOf(maxTiles, tiles)
            log("%-36s %6d ms   Heap %4d MB   Kacheln %5d MB".format(name, System.currentTimeMillis() - t0, heap, tiles))
        }
        return try {
            scratch.mkdirs()
            val doc = Document(w, h)
            step("$w × $h, 3 Ebenen anlegen") {
                repeat(3) { doc.root.add(photoLayer("Ebene $it", w, h, it * 31)) }
                doc.activeLayer = doc.root.children.last()
            }
            val ed = Editor(doc)
            ed.history.scratch = ScratchDisk(File(scratch, "verlauf"))
            val filters = if (full) listOf(
                GaussianBlurFilter(12f), UnsharpMask(80f, 1.5f, 0), ReduceNoise(30f), BrightnessContrast(10f, 10f),
                HueSaturation(10f, 10f, 0f), MotionBlur(30f, 15), RadialBlur(10f, false), SurfaceBlur(4, 20),
                TiltShift(8f), Median(2), DustAndScratches(2, 10), AddNoise(5f, true, true), HighPass(4f),
                Emboss(45f, 2, 100f), FindEdges(), Mosaic(16), OilPaint(2), Twirl(40f), Vignette(30f, 50f),
                LensDistortion(10f), Clarity(30f), Dehaze(20f), SkinSmooth(40f, 50f), SmartSharpen(80f, 1.5f, 10f),
            ) else listOf(GaussianBlurFilter(12f), UnsharpMask(80f, 1.5f, 0), Twirl(40f), LensDistortion(10f), Clarity(30f), Dehaze(20f))
            for (f in filters) step("Filter ${f.label}") {
                val (tx, _) = ed.previewFilter(f)!!
                ed.commitFilter(tx, f.label)
            }
            step("Pinselstrich 400 px") {
                val st = ed.beginStroke(BrushTool(0xFF2060C0.toInt()), BrushSettings(size = 400f, hardness = 0.5f), PaintTarget.PIXELS)!!
                for (i in 0..80) st.addPoint(500f + i * 40f, 800f + i * 25f)
                ed.endStroke(st)
            }
            step("Pinsel mit Dynamik (Streuung, Farbe)") {
                val dyn = BrushSettings(size = 300f, hardness = 0.8f, spacing = 0.4f, sizeJitter = 0.6f, angleJitter = 1f, roundness = 0.5f,
                    scatter = 2f, scatterBothAxes = true, count = 3, fgBgJitter = 1f, hueJitter = 0.2f, opacityJitter = 0.5f,
                    mode = de.photographics.core.tools.BrushMode.MULTIPLY)
                val st = ed.beginStroke(BrushTool(0xFFC02060.toInt(), 0xFF20C060.toInt()), dyn, PaintTarget.PIXELS)!!
                for (i in 0..80) st.addPoint(w - 500f - i * 40f, 800f + i * 25f, 0.5f + 0.5f * (i % 10) / 10f)
                ed.endStroke(st)
            }
            step("Auswahl + Ebene durch Kopie") {
                doc.selection = Selection.rect(w, h, 200f, 200f, w * 0.6f, h * 0.6f)
                ed.layerViaCopy()
                doc.selection = null
            }
            step("Ebenenmaske + anwenden") { ed.addMask(false); ed.applyMask() }
            step("Auf eine Ebene reduzieren") { ed.mergeDown() }
            step("90° drehen") { ed.rotate(true) }
            step("Horizontal spiegeln") { ed.flip(true) }
            val nw = h * 14 / 10; val nh = w * 14 / 10
            step("Bildgröße 140 % (Lanczos)") { ed.resizeImage(nw, nh, ResampleMethod.LANCZOS) }
            step("Arbeitsfläche +400 px") { ed.resizeCanvas(nw + 400, nh + 400, 0.5f, 0.5f) }
            step("Freistellen") { ed.crop(IntRect(100, 100, nw + 300, nh + 300)) }
            step("Farbtabelle (LUT) als Ebene") { ed.addAdjustmentLayer(de.photographics.core.adjust.ColorLookupAdj()) }
            step("In Adobe RGB umwandeln") {
                ed.convertToProfile(de.photographics.core.color.IccProfiles.Builtin.ADOBE_RGB.profile, de.photographics.core.color.RenderingIntent.RELATIVE, true)
            }
            val psd = File(scratch, "selbsttest.psd")
            step("PSD speichern (gestreamt)") { psd.outputStream().buffered().use { PsdWriter.write(doc, it, File(scratch, "psd")) } }
            step("Alles rückgängig") { while (ed.history.canUndo) ed.undo() }
            check(doc.width == w && doc.root.children.size == 3) { "Rückgängig: falscher Zustand ${doc.width} px, ${doc.root.children.size} Ebenen" }
            step("Alles wiederholen") { while (ed.history.canRedo) ed.redo() }
            check(doc.width == nw + 200) { "Wiederholen: falsche Breite ${doc.width}" }
            step("Auf Hintergrund reduzieren") { ed.flatten() }
            step("PSD öffnen (speicherabgebildet)") {
                val d = RandomAccessFile(psd, "r").use { f -> PsdReader.read(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())) }
                check(d.width == nw + 200) { "PSD: falsche Breite ${d.width}" }
                check(d.colorProfile.description == "Adobe RGB (1998)") { "PSD: Profil fehlt (${d.colorProfile.description})" }
            }
            psd.delete()
            val tif = File(scratch, "selbsttest.tif")
            step("TIFF mit Ebenen speichern (LZW)") { tif.outputStream().buffered().use { de.photographics.core.tiff.TiffWriter.write(doc, it, File(scratch, "tiff")) } }
            step("TIFF öffnen (speicherabgebildet)") {
                val d = RandomAccessFile(tif, "r").use { f -> de.photographics.core.tiff.TiffReader.read(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())) }
                check(d.width == doc.width && d.allLayers().size == doc.allLayers().size) { "TIFF: ${d.width} px, ${d.allLayers().size} Ebenen" }
            }
            tif.delete()
            val xcf = File(scratch, "selbsttest.xcf")
            step("GIMP-Datei (XCF) speichern") { xcf.outputStream().buffered().use { de.photographics.core.xcf.XcfWriter.write(doc, it, File(scratch, "xcf")) } }
            step("GIMP-Datei (XCF) öffnen") {
                val d = RandomAccessFile(xcf, "r").use { f -> de.photographics.core.xcf.XcfReader.read(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())) }
                check(d.width == doc.width) { "XCF: ${d.width} px" }
            }
            xcf.delete()
            ed.history.clear()
            Result(true, null, null, maxHeap, maxTiles, System.currentTimeMillis() - start)
        } catch (t: Throwable) {
            Result(false, current, t, maxHeap, maxTiles, System.currentTimeMillis() - start)
        } finally {
            File(scratch, "verlauf").deleteRecursively()
            File(scratch, "psd").deleteRecursively()
        }
    }

    /** Fotoähnliche Ebene, streifenweise erzeugt (auch der Test hält nie ein ganzes Bild im Heap). */
    private fun photoLayer(name: String, w: Int, h: Int, seed: Int): PixelLayer {
        val g = TileGrid(w, h)
        val row = IntArray(w * 64)
        for (y0 in 0 until h step 64) {
            val n = minOf(64, h - y0)
            for (i in 0 until w * n) {
                val x = i % w; val y = y0 + i / w
                row[i] = (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x * 7 + y * 13 + seed) and 0xFF)
            }
            g.writeRect(IntRect(0, y0, w, y0 + n), if (n == 64) row else row.copyOf(w * n))
        }
        return PixelLayer(name, g)
    }
}
