package de.photographics.app

import android.app.ActivityManager
import android.app.Application
import android.os.SharedMemory
import android.util.Log
import de.photographics.core.image.SlabTileMemory
import de.photographics.core.image.TileMemory
import java.nio.ByteBuffer

/**
 * Richtet beim Start den nativen Kachelspeicher ein: Bildpixel liegen in SharedMemory-Blöcken
 * (ashmem/memfd) außerhalb des Java-Heaps. Begrenzt sind sie nur durch das Budget unten, nicht durch
 * die Heap-Grenze von Android (meist 512 MB). Das System kann selten genutzte Blöcke bei Bedarf in
 * den komprimierten zRAM auslagern.
 */
class PhotographicsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Sprache vor allem anderen setzen: Texte in Aufzählungen und Objekten werden beim ersten Zugriff übersetzt
        val lang = getSharedPreferences("settings", MODE_PRIVATE).getString("language", null)
            ?.let { n -> Language.entries.firstOrNull { it.name == n } } ?: Language.SYSTEM
        de.photographics.core.I18n.lang = lang.resolve()
        TileMemory.current = createTileMemory()
    }

    private fun createTileMemory(): TileMemory {
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val budget = tileBudget(info.totalMem, am.isLowRamDevice)
        Log.i(TAG, "Kachelspeicher: ${budget shr 20} MB (Geräte-RAM ${info.totalMem shr 20} MB)")
        return SlabTileMemory(
            limitBytes = budget,
            newSlab = { bytes ->
                val sm = SharedMemory.create("photographics-tiles", bytes)
                try { sm.mapReadWrite() } finally { sm.close() } // Abbildung bleibt nach close() gültig
            },
            releaseSlab = { buf: ByteBuffer -> SharedMemory.unmap(buf) },
        )
    }

    companion object {
        private const val TAG = "Photographics"

        /**
         * Budget für Bildpixel: 35 % des Geräte-RAM (8-GB-Tablet ≈ 2,8 GB), höchstens 6 GB,
         * auf Geräten mit wenig RAM 25 %.
         */
        fun tileBudget(totalRam: Long, lowRam: Boolean): Long {
            val share = if (lowRam) 0.25 else 0.35
            return (totalRam * share).toLong().coerceIn(256L shl 20, 6L shl 30)
        }
    }
}
