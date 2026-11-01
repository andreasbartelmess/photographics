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
            step("Verflüssigen (Strudel, 500 px)") {
                val st = ed.beginStroke(de.photographics.core.tools.LiquifyTool(de.photographics.core.tools.LiquifyMode.TWIRL_CW, 0.8f),
                    BrushSettings(size = 500f, hardness = 0.4f, spacing = 0.1f), PaintTarget.PIXELS)!!
                for (i in 0..40) st.addPoint(w / 2f + i * 20f, h / 2f + i * 10f)
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
            step("Ebene automatisch auswählen") {
                check(de.photographics.core.doc.LayerPick.at(doc, nw / 2, nh / 2) != null) { "keine Ebene getroffen" }
            }
            step("Zauberstab (ganzes Bild)") {
                val l = doc.activeLayer as de.photographics.core.doc.RasterLayer
                ed.setSelection(de.photographics.core.selection.MagicWand.select(l.raster, nw / 2, nh / 2, 24, contiguous = false))
            }
            step("Objektauswahl (Rechteck)") {
                ed.setSelection(de.photographics.core.selection.ObjectSelect.select(doc.width, doc.height, IntRect(nw / 4, nh / 4, nw * 3 / 4, nh * 3 / 4),
                    { r, st -> de.photographics.core.render.Compositor.renderArgbSampled(doc, r, st) }))
            }
            step("Verlauf in der Auswahl") {
                ed.fillGradient(PaintTarget.PIXELS, de.photographics.core.editor.Gradient(de.photographics.core.editor.GradientType.RADIAL,
                    nw / 2f, nh / 2f, nw.toFloat(), nh.toFloat(), 0xFFFF8000.toInt(), 0x000000FF), 0.8f)
                ed.deselect()
            }
            step("Inhaltsbasiert füllen (großer Bereich)") {
                ed.setSelection(de.photographics.core.selection.Selection.ellipse(doc.width, doc.height, nw * 0.3f, nh * 0.3f, nw * 0.65f, nh * 0.6f))
                ed.contentAwareFill()
                ed.deselect()
            }
            step("Auswahl kopieren und einfügen") {
                ed.setSelection(de.photographics.core.selection.Selection.rect(doc.width, doc.height, nw * 0.1f, nh * 0.1f, nw * 0.6f, nh * 0.6f))
                val clip = checkNotNull(ed.copy()) { "nichts kopiert" }
                ed.paste(clip, inPlace = true)
            }
            step("Auswahl schwebend transformieren") {
                ed.setActive(doc.root.children.first { it is de.photographics.core.doc.PixelLayer })
                ed.setSelection(de.photographics.core.selection.Selection.ellipse(doc.width, doc.height, nw * 0.2f, nh * 0.2f, nw * 0.7f, nh * 0.7f))
                val f = checkNotNull(ed.floatSelection()) { "nichts angehoben" }
                f.layer.transform = f.layer.transform.then(de.photographics.core.image.Affine.around(nw / 2.0, nh / 2.0, de.photographics.core.image.Affine.rotate(12.0)))
                ed.commitFloating(f)
                ed.deselect()
            }
            step("Filtergalerie (Aquarell + Struktur)") {
                ed.setActive(doc.root.children.first { it is de.photographics.core.doc.PixelLayer })
                val g = de.photographics.core.filters.Gallery
                val chain = de.photographics.core.filters.GalleryChain(listOf("watercolor", "texturizer").map { id -> g.byId(id)!!.let { de.photographics.core.filters.GalleryEffect(it, it.defaults()) } })
                val (tx, _) = checkNotNull(ed.previewFilter(chain)) { "keine Pixelebene" }
                ed.commitFilter(tx, "Filtergalerie")
            }
            step("Formen, Pfade und Alphakanal") {
                val v = de.photographics.core.vector.VectorPath
                ed.addShapeLayer(v.ellipse(nw * 0.1, nh * 0.1, nw * 0.9, nh * 0.9), 0xFF3366CC.toInt(), de.photographics.core.vector.ShapeStroke(40f, 0xFFFFFFFF.toInt()))
                de.photographics.core.render.Compositor.renderBands(doc, doc.bounds) { _, _ -> }
                ed.setWorkPath(v.polygon(0.0, 0.0, nw.toDouble(), nh.toDouble(), 7, 0.5))
                ed.selectionFromPath(doc.workPath!!)
                ed.saveSelectionAsChannel()
                ed.setActive(doc.root.children.first { it is de.photographics.core.doc.PixelLayer })
                ed.strokePath(doc.workPath!!, 0xFFFF0000.toInt(), 30f)
                ed.deselect()
            }
            step("Farbtabelle (LUT) als Ebene") { ed.addAdjustmentLayer(de.photographics.core.adjust.ColorLookupAdj()) }
            step("Kanalkurven + Histogramm") {
                val l = ed.addAdjustmentLayer(de.photographics.core.adjust.CurvesAdj(red = listOf(0 to 0, 128 to 160, 255 to 255)))
                check(de.photographics.core.adjust.Histogram.below(doc, l).sum() > 0) { "Histogramm leer" }
            }
            step("Ebeneneffekte rendern (Schatten, Schein, Kontur)") {
                val l = doc.root.children.filterIsInstance<de.photographics.core.doc.RasterLayer>().last()
                val m = de.photographics.core.render.BlendMode.MULTIPLY
                l.preservedBlocks = listOf(de.photographics.core.psd.PsdEffects.toBlock(de.photographics.core.doc.LayerEffects(
                    dropShadows = listOf(de.photographics.core.doc.LayerEffects.Shadow(0, 0.6f, m, 120.0, true, 25.0, 0.1, 40.0)),
                    outerGlow = de.photographics.core.doc.LayerEffects.Glow(0xFFFFCC, 0.5f, de.photographics.core.render.BlendMode.SCREEN, 0.0, 30.0),
                    strokes = listOf(de.photographics.core.doc.LayerEffects.Stroke(0xFF0000, 1f, de.photographics.core.render.BlendMode.NORMAL, 8.0, de.photographics.core.doc.LayerEffects.StrokePosition.OUTSIDE)),
                )))
                check(l.effects?.visible == true) { "Effekte nicht gelesen" }
                de.photographics.core.render.Compositor.renderBands(doc, doc.bounds) { _, _ -> }
            }
            step("Ebenenstil: Kante, Glanz, Verlauf (mit Verlauf)") {
                val l = doc.root.children.filterIsInstance<de.photographics.core.doc.RasterLayer>().last()
                val fx = (l.effects ?: de.photographics.core.doc.LayerEffects()).copy(
                    bevel = de.photographics.core.doc.LayerEffects.Bevel(size = 30.0, soften = 4.0),
                    satin = de.photographics.core.doc.LayerEffects.Satin(distance = 40.0, size = 30.0),
                    gradientOverlay = de.photographics.core.doc.LayerEffects.GradientOverlay(opacity = 0.4f),
                )
                ed.setEffects(l, fx)
                check(l.effects?.bevel != null && l.effects?.gradientOverlay != null) { "Ebenenstil nicht gesetzt" }
                de.photographics.core.render.Compositor.renderBands(doc, doc.bounds) { _, _ -> }
            }
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
            step("Füllwerkzeug, Fläche füllen, Magischer Radiergummi") {
                val l = doc.root.children.filterIsInstance<de.photographics.core.doc.PixelLayer>().first()
                doc.activeLayer = l
                // Innerhalb einer Auswahl (ein Viertel des Bildes), Farben aus allen Ebenen gesucht
                ed.setSelection(Selection.rect(doc.width, doc.height, nw * 0.25f, nh * 0.25f, nw * 0.75f, nh * 0.75f))
                ed.bucketFill(nw / 2, nh / 2, 0xFF336699.toInt(), 20, contiguous = true, allLayers = true)
                ed.fillArea(0xFF808080.toInt(), de.photographics.core.render.BlendMode.MULTIPLY, 0.5f)
                ed.magicErase(nw / 2, nh / 2, 16, contiguous = true, allLayers = true)
                ed.deselect()
            }
            step("Schnellauswahl (großer Pinsel)") {
                val l = doc.root.children.filterIsInstance<de.photographics.core.doc.RasterLayer>().first()
                val sel = de.photographics.core.selection.QuickSelect.select(l.raster, floatArrayOf(nw * 0.2f, nh * 0.5f, nw * 0.6f, nh * 0.55f), 120f)
                check(!sel.isEmpty) { "Schnellauswahl leer" }
            }
            step("PSD öffnen (speicherabgebildet)") {
                val d = RandomAccessFile(psd, "r").use { f -> PsdReader.read(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())) }
                check(d.width == nw + 200) { "PSD: falsche Breite ${d.width}" }
                check(d.colorProfile.description == "Adobe RGB (1998)") { "PSD: Profil fehlt (${d.colorProfile.description})" }
            }
            step("16 Bit/Kanal: Umwandeln, Verlauf, Korrektur") {
                ed.setBitDepth(16)
                ed.setActive(doc.root.children.first { it is de.photographics.core.doc.PixelLayer })
                ed.fillGradient(PaintTarget.PIXELS, de.photographics.core.editor.Gradient(de.photographics.core.editor.GradientType.LINEAR,
                    0f, 0f, nw.toFloat(), 0f, 0xFF102030.toInt(), 0x80E0D0C0.toInt()), 0.7f)
                val l = doc.activeLayer as de.photographics.core.doc.PixelLayer
                val (tx, _) = de.photographics.core.filters.FilterRunner.apply(doc, l, de.photographics.core.filters.Levels(10, 240, 1.2f, 0, 255))
                ed.commitFilter(tx, "Tonwert 16 Bit")
                check(l.low != null) { "untere Bits fehlen" }
            }
            step("PSD mit 16 Bit speichern und öffnen") {
                psd.outputStream().buffered().use { PsdWriter.write(doc, it, File(scratch, "psd")) }
                val d = RandomAccessFile(psd, "r").use { f -> PsdReader.read(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())) }
                check(d.bitDepth == 16 && d.pixelLayers().any { it.low != null }) { "PSD: 16 Bit fehlen" }
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
