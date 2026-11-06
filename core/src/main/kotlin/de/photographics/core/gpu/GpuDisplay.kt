package de.photographics.core.gpu

import de.photographics.core.doc.Document
import de.photographics.core.image.IntRect

/**
 * Grafikkarte für die Anzeige, gemeinsam für Android und GNOME: startet den GL-Kontext beim ersten
 * Gebrauch über [open] (Plattform: Kontext ohne Fenster anlegen und aktuell machen, Ergebnis
 * [Gles] + Aufräumen), schaltet bei einem Fehler dauerhaft auf den Prozessor zurück und meldet,
 * ob die letzte Anzeige von der Grafikkarte kam.
 *
 * Alle Aufrufe außer [status]/[fast]/[enabled] auf dem Thread, dem die Dokumente gehören (Engine).
 */
class GpuDisplay(private val open: () -> Pair<Gles, AutoCloseable>?) {
    private var ctx: AutoCloseable? = null
    private var gpu: GpuCompositor? = null
    private var lastGl: Pair<Gles, AutoCloseable>? = null

    /** Eingeschaltet (Einstellung); aus → der Kontext wird beim nächsten Aufruf freigegeben. */
    @Volatile var enabled = true

    /** Warum keine Grafikkarte (null = verfügbar oder noch nicht versucht). */
    @Volatile var broken: String? = null
        private set

    /** Name der Grafikkarte, sobald sie läuft. */
    @Volatile var renderer: String? = null
        private set

    /** Die letzte Anzeige kam von der Grafikkarte (Live-Vorschauen brauchen dann keine gröbere Stufe). */
    @Volatile var fast = false
        private set

    /** Engine-Thread: Kontext und Shader vorbereiten (beim Start, damit das erste Bild nicht wartet). */
    fun warmUp() { compositor() }

    private fun compositor(): GpuCompositor? {
        if (!enabled) { close(); return null }
        if (broken != null) return null
        gpu?.let { return it }
        return try {
            val opened = open()
            lastGl = opened
            val (gl, c) = opened ?: run { broken = de.photographics.core.tr("kein OpenGL-ES-3-Treiber"); return null }
            ctx = c
            GpuCompositor(gl).also { gpu = it; renderer = it.renderer }
        } catch (t: Throwable) { fail(t); null }
    }

    /** Kann die Grafikkarte dieses Dokument bei Schritt [step] darstellen (und ist sie an)? */
    fun canRender(doc: Document, step: Int): Boolean {
        val g = compositor() ?: run { fast = false; return false }
        return g.supports(doc, step).also { if (!it) fast = false }
    }

    /**
     * [rect] (Ausgabepixel bei Schritt [step]) als ARGB, oder null: dann rechnet der Aufrufer auf
     * dem Prozessor (aus, nicht verfügbar, Dokument mit Ebenen, die die Grafikkarte noch nicht kann,
     * oder ein Fehler – danach bleibt sie aus).
     */
    fun render(doc: Document, rect: IntRect, step: Int, out: IntArray? = null): IntArray? {
        if (rect.isEmpty || !canRender(doc, step)) return null
        val g = gpu ?: return null
        return try {
            g.renderArgb(doc, rect, step, out ?: IntArray(rect.width * rect.height)).also { fast = true }
        } catch (t: Throwable) { fail(t); null }
    }

    /**
     * Messung (Benchmark): [rect] bei [step] je [n]-mal zusammensetzen und zurücklesen, getrennt
     * gemessen, für jede Variante (32/16 Bit, an Ort und Stelle oder Ping-Pong); dazu die
     * Selbstprüfung gegen die CPU je Variante. Nur auf dem Engine-Thread.
     */
    fun profile(doc: Document, rect: IntRect, step: Int, n: Int = 3, check: Boolean = false): List<String> {
        val g = compositor() ?: return listOf("keine Grafikkarte")
        if (!g.supports(doc, step)) return listOf("Dokument nicht unterstützt")
        val gl = lastGl?.first ?: return listOf("kein Kontext")
        val variants = buildList {
            add(g)
            if (g.fullFloat) add(GpuCompositor(gl, preferHalfFloat = true))
            if (g.inPlace) add(GpuCompositor(gl, allowFetch = false))
        }
        val lines = ArrayList<String>()
        for (c in variants) {
            val label = (if (c.fullFloat) "32 Bit" else if (c.floatBuffers) "16 Bit" else "8 Bit") + if (c.inPlace) ", an Ort und Stelle" else ", Ping-Pong"
            var comp = 0L; var read = 0L; var cpu = 0L
            val out = IntArray(rect.width * rect.height)
            for (i in 0..n) {
                if (i == 1) c.resetStats()
                var y = rect.top
                while (y < rect.bottom) {
                    val y1 = minOf(rect.bottom, y + c.chunk)
                    var x = rect.left
                    while (x < rect.right) {
                        val x1 = minOf(rect.right, x + c.chunk)
                        val t0 = System.nanoTime()
                        val b = c.render(doc, IntRect(x, y, x1, y1), step)
                        val tc = System.nanoTime()
                        c.finish()
                        val t1 = System.nanoTime()
                        if (i > 0) cpu += tc - t0
                        c.readArgb(b, out)
                        c.release(b)
                        if (i > 0) { comp += t1 - t0; read += System.nanoTime() - t1 }
                        x = x1
                    }
                    y = y1
                }
            }
            var line = "$label: Zusammensetzen ${comp / n / 1_000_000} ms (davon Aufrufe ${cpu / n / 1_000_000} ms, ${c.draws / n} Zeichenaufrufe, ${c.uploads / n} Uploads), Zurücklesen ${read / n / 1_000_000} ms"
            if (check) {
                val errors = GpuSelfCheck.run(c)
                line += if (errors.isEmpty()) "; Selbstprüfung gegen CPU: gleich" else "; Selbstprüfung: ${errors.size} Abweichungen, z. B. ${errors.take(3).joinToString(" | ")}"
            }
            lines += line
            if (c !== g) c.dispose()
        }
        return lines
    }

    private fun fail(t: Throwable) {
        System.err.println("Grafikkarte abgeschaltet, der Prozessor übernimmt:")
        t.printStackTrace()
        fast = false
        broken = t.message ?: t.javaClass.simpleName
        close()
    }

    /** GL-Objekte und Kontext freigeben (Engine-Thread). */
    fun close() {
        runCatching { gpu?.dispose() }; gpu = null
        runCatching { ctx?.close() }; ctx = null
        fast = false
    }
}
