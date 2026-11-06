package de.photographics.core

import de.photographics.core.adjust.AdjParam
import de.photographics.core.adjust.AdjustmentType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Die Abbildungen der Einstellungen werden parallel aufgerufen (Anzeige in Streifen, GPU-Tabellen). */
class AdjustThreadTest {
    @Test fun mapsAreThreadSafe() {
        for (t in AdjustmentType.entries) {
            val def = t.createDefault(); val ps = def.params()
            val adj = def.withValues(FloatArray(ps.size) { i -> val p = ps[i]; if (p.kind == AdjParam.Kind.SWITCH) 1f else p.min + (p.max - p.min) * 0.7f })
            val map = adj.compile()
            val colors = IntArray(200_000) { (it * 2654435761L).toInt() and 0xFFFFFF }
            val want = IntArray(colors.size) { map.map(colors[it]) }
            val threads = (0 until 8).map { k -> Thread { for (i in colors.indices) if (map.map(colors[(i + k * 7919) % colors.size]) != want[(i + k * 7919) % colors.size]) throw AssertionError("$t nicht threadsicher") } }
            val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            threads.forEach { it.setUncaughtExceptionHandler { _, e -> errors += e }; it.start() }
            threads.forEach { it.join() }
            assertEquals(t.name, emptyList<Throwable>(), errors.map { it.message })
        }
    }
}
