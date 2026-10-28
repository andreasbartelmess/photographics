package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.editor.Editor
import de.photographics.core.filters.Clarity
import de.photographics.core.filters.GaussianBlurFilter
import de.photographics.core.filters.SurfaceBlur
import de.photographics.core.filters.UnsharpMask
import de.photographics.core.image.IntRect
import de.photographics.core.image.ResampleMethod
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdWriter
import org.junit.Test

/** Realistische Sitzung mit 12-MP-Foto bei 512 MB Heap (wie ein Android-Tablet mit largeHeap). */
class MemoryStressTest {
    private fun usedMb(): Long {
        val r = Runtime.getRuntime(); System.gc()
        return (r.totalMemory() - r.freeMemory()) / 1_000_000
    }

    private var hook: (() -> Unit)? = null

    private fun step(name: String, block: () -> Unit) {
        val t0 = System.currentTimeMillis()
        try {
            block()
            println("%-34s ok   %6d ms   belegt %4d MB".format(name, System.currentTimeMillis() - t0, usedMb()))
            hook?.invoke()
        } catch (e: OutOfMemoryError) {
            println("%-34s SPEICHER VOLL nach %d ms".format(name, System.currentTimeMillis() - t0))
            throw e
        }
    }

    @Test
    fun photoSession() {
        val w = 4000; val h = 3000
        val doc = Document(w, h)
        // Fotoähnliche Ebenen (Verlauf + Rauschen)
        repeat(3) { k ->
            val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((i * 31 + k * 17) and 0xFF) }
            doc.root.add(PixelLayer("Ebene $k", TileGrid.fromPixels(w, h, px)))
        }
        doc.activeLayer = doc.root.children.last()
        val ed = Editor(doc)
        val scratch = java.nio.file.Files.createTempDirectory("pg-scratch").toFile()
        ed.history.scratch = de.photographics.core.history.ScratchDisk(scratch)
        hook = { println("    Verlauf im Speicher %d MB, ausgelagert %d MB".format(ed.history.bytes / 1_000_000, scratch.listFiles()!!.sumOf { it.length() } / 1_000_000)) }
        println("Start: belegt ${usedMb()} MB von ${Runtime.getRuntime().maxMemory() / 1_000_000} MB")
        for ((name, f) in listOf(
            "Gaußscher Weichzeichner 20 px" to GaussianBlurFilter(20f),
            "Unscharf maskieren" to UnsharpMask(100f, 2f, 0),
            "Klarheit" to Clarity(40f),
            "Matter machen" to SurfaceBlur(6, 20),
        )) step(name) { val (tx, _) = ed.previewFilter(f)!!; ed.commitFilter(tx, name) }
        step("90° drehen") { ed.rotate(true) }
        step("Bildgröße 125 % (Lanczos)") { ed.resizeImage(3750, 5000, ResampleMethod.LANCZOS) }
        step("Ebene löschen") { ed.deleteLayer() }
        val psd = java.io.File(scratch.parentFile, "pg-stress.psd")
        step("PSD speichern (gestreamt)") { psd.outputStream().buffered().use { PsdWriter.write(doc, it, scratch) } }
        step("Alles rückgängig") { while (ed.history.canUndo) ed.undo() }
        check(doc.width == w && doc.height == h && doc.root.children.size == 3)
        step("Alles wiederholen") { while (ed.history.canRedo) ed.redo() }
        check(doc.width == 3750 && doc.root.children.size == 2)
        step("Auf Hintergrund reduzieren") { ed.flatten() }
        step("PSD öffnen (speicherabgebildet)") {
            ed.history.clear()   // wie die App: Verlauf des alten Dokuments vor dem Laden freigeben
            val d = java.io.RandomAccessFile(psd, "r").use { f ->
                de.photographics.core.psd.PsdReader.read(f.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, f.length()))
            }
            check(d.width == 3750 && d.root.children.size == 2)
        }
        psd.delete()
        @Suppress("unused") val r = IntRect.EMPTY
    }
}
