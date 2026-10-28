package de.photographics.app

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Bildzeiten der Oberfläche je Benchmark-Phase (nur für tools/device_bench.sh): sammelt FrameMetrics
 * des Fensters unter dem gerade gesetzten [phase]-Namen und fasst sie in [report] zusammen.
 */
object FrameStats {
    @Volatile var phase: String? = null
    /** Nur für Messungen: 1 = Bildkacheln nicht zeichnen, 2 = Überlagerungen nicht zeichnen. */
    @Volatile var skip: Int = 0
    private val frames = LinkedHashMap<String, MutableList<LongArray>>()
    private val handler by lazy { Handler(HandlerThread("pg-frames").apply { start() }.looper) }

    private val metrics = intArrayOf(
        FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION,
        FrameMetrics.SWAP_BUFFERS_DURATION, FrameMetrics.TOTAL_DURATION,
    )

    fun attach(window: Window) {
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            val p = phase ?: return@addOnFrameMetricsAvailableListener
            val row = LongArray(metrics.size + 1) { i ->
                if (i < metrics.size) m.getMetric(metrics[i])
                else if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else 0L
            }
            synchronized(frames) { frames.getOrPut(p) { ArrayList() }.add(row) }
        }, handler)
    }

    /** Zeilen „Phase: Anzahl, zu langsam, Mediane“; [budgetMs] = Bildbudget der Anzeige. */
    fun report(budgetMs: Double): List<String> = synchronized(frames) {
        frames.map { (name, rows) ->
            fun med(i: Int) = rows.map { it[i] }.sorted()[rows.size / 2] / 1e6
            fun p90(i: Int) = rows.map { it[i] }.sorted()[(rows.size * 9) / 10] / 1e6
            val slow = rows.count { it[4] / 1e6 > budgetMs }
            "%-12s %3d Bilder, %3d über %.1f ms | Median: Aufzeichnen %.1f  Sync %.1f  Befehle %.1f  Swap %.1f  GPU %.1f  gesamt %.1f (90 %%: %.1f)".format(
                name, rows.size, slow, budgetMs, med(0), med(1), med(2), med(3), med(5), med(4), p90(4))
        }.also { frames.clear() }
    }
}
