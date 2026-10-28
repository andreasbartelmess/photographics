package de.photographics.core.image

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Rechnet Streifen eines Bildes auf allen Prozessorkernen.
 *
 * Regeln, damit das mit dem Kachelspeicher sicher ist:
 * - [bands] berechnet in Hilfs-Threads nur **lesend** (Kacheln lesen, Arrays rechnen); geschrieben
 *   wird ausschließlich in `consume` auf dem aufrufenden Thread, in der Reihenfolge der Streifen.
 * - Während [bands] läuft, darf niemand Kacheln anheften (`Tile.pin`) – der Kachel-Cache ist nicht
 *   threadsicher. `compute` darf deshalb nur `readRect`/`Tile.read` benutzen.
 * - Gleichzeitig in Arbeit sind höchstens so viele Streifen, wie in [maxInFlightBytes] passen
 *   (Speichergrenze für den Java-Heap), und nie mehr als Kerne vorhanden sind.
 */
object Parallel {
    /** Anzahl Rechen-Threads (alle Kerne). */
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

    /** Java-Speicher, den gleichzeitig laufende Streifen zusammen höchstens belegen sollen (⅕ des Heaps). */
    var maxInFlightBytes: Long = Runtime.getRuntime().maxMemory() / 5

    private val pool: ExecutorService by lazy {
        Executors.newFixedThreadPool(threads) { r ->
            Thread({ inWorker.set(true); r.run() }, "photographics-rechnen").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }

    /** true in Rechen-Threads: verschachtelte Aufrufe laufen dann direkt (der feste Pool würde sonst auf sich selbst warten). */
    private val inWorker = ThreadLocal.withInitial { false }

    /**
     * Berechnet [count] Streifen mit [compute] parallel und übergibt die Ergebnisse geordnet an
     * [consume] (auf dem aufrufenden Thread). [bytesPerBand] = geschätzter Java-Speicher je Streifen.
     */
    fun <T> bands(count: Int, bytesPerBand: Long, compute: (Int) -> T, consume: (Int, T) -> Unit) {
        val window = minOf(threads.toLong(), maxOf(1L, maxInFlightBytes / maxOf(1L, bytesPerBand))).toInt()
        if (count <= 1 || window <= 1 || inWorker.get()) {
            for (i in 0 until count) consume(i, compute(i))
            return
        }
        val inFlight = ArrayDeque<Future<T>>()
        var next = 0
        fun submit() { val i = next++; inFlight.addLast(pool.submit(Callable { compute(i) })) }
        try {
            while (next < count && inFlight.size < window) submit()
            for (done in 0 until count) {
                val result = try {
                    inFlight.removeFirst().get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                consume(done, result)
                if (next < count) submit()
            }
        } finally {
            // Bei Fehlern laufende Streifen zu Ende rechnen lassen – sie lesen noch Kacheln
            for (f in inFlight) runCatching { f.get() }
        }
    }
}
