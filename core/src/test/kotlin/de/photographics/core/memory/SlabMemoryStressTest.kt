package de.photographics.core.memory

import de.photographics.core.diagnostics.MemorySelfTest
import de.photographics.core.image.SlabTileMemory
import de.photographics.core.image.TileMemory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * Speicher-Sperre vor jeder Version (Task `memoryTest`, läuft mit nur 192 MB Java-Heap).
 *
 * Die Pixel liegen wie in der App im Kachelspeicher außerhalb des Heaps; das Dokument ist mit
 * bis zu ~1,8 GB Bilddaten ein Vielfaches des Heaps. Braucht eine Funktion einen Puffer in
 * Bildgröße im Java-Speicher, schlägt der Test mit OutOfMemoryError fehl.
 */
class SlabMemoryStressTest {
    private lateinit var previous: TileMemory
    private lateinit var scratch: File

    @Before
    fun setUp() {
        previous = TileMemory.current
        TileMemory.current = SlabTileMemory(limitBytes = 2560L shl 20, newSlab = { ByteBuffer.allocateDirect(it) })
        scratch = Files.createTempDirectory("pg-selbsttest").toFile()
    }

    @After
    fun tearDown() {
        TileMemory.current = previous
        scratch.deleteRecursively()
    }

    @Test
    fun largeDocumentSession() {
        val r = MemorySelfTest.run(scratch, full = true) { println(it) }
        println("Gesamt ${r.totalMs / 1000} s, Heap max. ${r.maxHeapMb} MB, Kacheln max. ${r.maxTileMb} MB")
        if (!r.ok) throw AssertionError("„${r.failedStep}“ fehlgeschlagen (Heap ${Runtime.getRuntime().maxMemory() shr 20} MB): ${r.error}", r.error)
        // Grenze für Java-Speicher: alles Übrige muss im Kachelspeicher liegen
        assertTrue("Java-Heap ${r.maxHeapMb} MB > 120 MB", r.maxHeapMb <= 120)
        assertTrue("Kachelspeicher wurde nicht genutzt", r.maxTileMb > 500)
    }
}
