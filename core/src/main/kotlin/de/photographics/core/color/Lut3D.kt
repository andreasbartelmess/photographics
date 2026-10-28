package de.photographics.core.color

import java.util.Locale

/**
 * 3D-Farbtabelle (RGB → RGB) mit [size]³ Stützstellen, Werte 0..1, Rot läuft am schnellsten
 * (Reihenfolge wie in .cube-Dateien). Ausgewertet wird tetraedrisch – genauer als trilinear und
 * dasselbe Verfahren wie in Photoshop, DaVinci Resolve und LittleCMS.
 *
 * Dient für Farbtabellen-Einstellungsebenen ebenso wie als schneller Zwischenspeicher für
 * ICC-Farbumwandlungen.
 */
class Lut3D(val size: Int, val data: FloatArray, val title: String = "") {
    init {
        require(size in 2..256) { "LUT-Größe $size nicht unterstützt" }
        require(data.size == size * size * size * 3) { "LUT-Daten passen nicht zur Größe $size" }
    }

    /** Wertet einen Punkt aus (Eingabe 0..1, Ausgabe in [out]). */
    fun eval(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        val fr = r.coerceIn(0f, 1f) * n1; val fg = g.coerceIn(0f, 1f) * n1; val fb = b.coerceIn(0f, 1f) * n1
        val r0 = minOf(fr.toInt(), n1 - 1); val g0 = minOf(fg.toInt(), n1 - 1); val b0 = minOf(fb.toInt(), n1 - 1)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        val sR = 3; val sG = 3 * size; val sB = 3 * size * size
        val c000 = (b0 * size + g0) * size * 3 + r0 * 3
        val c111 = c000 + sR + sG + sB
        // Tetraeder anhand der Reihenfolge der Nachkommaanteile wählen
        val i1: Int; val i2: Int; val w0: Float; val w1: Float; val w2: Float; val w3: Float
        if (dr >= dg) {
            if (dg >= db) { i1 = c000 + sR; i2 = c000 + sR + sG; w0 = 1 - dr; w1 = dr - dg; w2 = dg - db; w3 = db }
            else if (dr >= db) { i1 = c000 + sR; i2 = c000 + sR + sB; w0 = 1 - dr; w1 = dr - db; w2 = db - dg; w3 = dg }
            else { i1 = c000 + sB; i2 = c000 + sR + sB; w0 = 1 - db; w1 = db - dr; w2 = dr - dg; w3 = dg }
        } else {
            if (db >= dg) { i1 = c000 + sB; i2 = c000 + sG + sB; w0 = 1 - db; w1 = db - dg; w2 = dg - dr; w3 = dr }
            else if (db >= dr) { i1 = c000 + sG; i2 = c000 + sG + sB; w0 = 1 - dg; w1 = dg - db; w2 = db - dr; w3 = dr }
            else { i1 = c000 + sG; i2 = c000 + sR + sG; w0 = 1 - dg; w1 = dg - dr; w2 = dr - db; w3 = db }
        }
        val d = data
        for (c in 0..2) out[c] = w0 * d[c000 + c] + w1 * d[i1 + c] + w2 * d[i2 + c] + w3 * d[c111 + c]
    }

    /** Wendet die Tabelle auf eine RGB-Farbe (0xRRGGBB) an; [mix] 0..1 blendet mit dem Original. */
    fun map(rgb: Int, out: FloatArray, mix: Float = 1f): Int {
        val r = ((rgb shr 16) and 0xFF) / 255f; val g = ((rgb shr 8) and 0xFF) / 255f; val b = (rgb and 0xFF) / 255f
        eval(r, g, b, out)
        fun ch(v: Float, o: Float) = ((o + (v - o) * mix) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (ch(out[0], r) shl 16) or (ch(out[1], g) shl 8) or ch(out[2], b)
    }

    /** Als .cube-Datei (Adobe/Resolve-Format). */
    fun toCube(): ByteArray {
        val sb = StringBuilder()
        if (title.isNotEmpty()) sb.append("TITLE \"").append(title.replace("\"", "'")).append("\"\n")
        sb.append("# Erstellt mit Photographics\n")
        sb.append("LUT_3D_SIZE ").append(size).append('\n')
        sb.append("DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n")
        var i = 0
        while (i < data.size) {
            sb.append(String.format(Locale.ROOT, "%.6f %.6f %.6f\n", data[i], data[i + 1], data[i + 2]))
            i += 3
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** Tabelle aus einer Funktion (Eingabe r, g, b → Ausgabe in out). */
        fun of(size: Int, title: String = "", f: (Float, Float, Float, FloatArray) -> Unit): Lut3D {
            val d = FloatArray(size * size * size * 3)
            val o = FloatArray(3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                f(r / (size - 1f), g / (size - 1f), b / (size - 1f), o)
                d[i++] = o[0]; d[i++] = o[1]; d[i++] = o[2]
            }
            return Lut3D(size, d, title)
        }

        fun identity(size: Int = 17) = of(size, "Neutral") { r, g, b, o -> o[0] = r; o[1] = g; o[2] = b }

        /** Erkennt das Format am Inhalt (.cube oder .3dl). */
        fun parse(bytes: ByteArray, fileName: String = ""): Lut3D {
            val text = String(bytes, Charsets.UTF_8)
            val looks3dl = fileName.endsWith(".3dl", ignoreCase = true) ||
                (!text.contains("LUT_3D_SIZE") && !text.contains("LUT_1D_SIZE"))
            return if (looks3dl) parse3dl(text, fileName) else parseCube(text, fileName)
        }

        /** .cube (Adobe / IRIDAS / Resolve): 3D-Tabellen, 1D-Tabellen werden in 3D umgerechnet. */
        fun parseCube(text: String, fileName: String = ""): Lut3D {
            var size3 = 0; var size1 = 0
            var title = fileName.substringAfterLast('/').substringBeforeLast('.')
            val min = floatArrayOf(0f, 0f, 0f); val max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> title = line.substringAfter("TITLE").trim().trim('"')
                    "LUT_3D_SIZE" -> size3 = parts[1].toInt()
                    "LUT_1D_SIZE" -> size1 = parts[1].toInt()
                    "DOMAIN_MIN" -> for (i in 0..2) min[i] = parts[i + 1].toFloat()
                    "DOMAIN_MAX" -> for (i in 0..2) max[i] = parts[i + 1].toFloat()
                    "LUT_3D_INPUT_RANGE", "LUT_1D_INPUT_RANGE" -> { for (i in 0..2) { min[i] = parts[1].toFloat(); max[i] = parts[2].toFloat() } }
                    else -> if (parts.size >= 3 && parts[0].first().let { it.isDigit() || it == '-' || it == '.' }) {
                        values += parts[0].toFloat(); values += parts[1].toFloat(); values += parts[2].toFloat()
                    }
                }
            }
            if (size3 > 0) {
                require(values.size >= size3 * size3 * size3 * 3) { "Zu wenige Werte für LUT_3D_SIZE $size3" }
                val lut = Lut3D(size3, FloatArray(size3 * size3 * size3 * 3) { values[it] }, title)
                return if (min.all { it == 0f } && max.all { it == 1f }) lut else remapDomain(lut, min, max)
            }
            require(size1 > 1 && values.size >= size1 * 3) { "Keine gültige LUT (.cube)" }
            // 1D-Tabelle: je Kanal eine Kurve → als 3D-Tabelle (33³) abbilden
            fun curve(c: Int, v: Float): Float {
                val t = ((v - min[c]) / (max[c] - min[c])).coerceIn(0f, 1f) * (size1 - 1)
                val i = minOf(t.toInt(), size1 - 2); val f = t - i
                return values[i * 3 + c] * (1 - f) + values[(i + 1) * 3 + c] * f
            }
            return of(33, title) { r, g, b, o -> o[0] = curve(0, r); o[1] = curve(1, g); o[2] = curve(2, b) }
        }

        /** Eingabebereich ≠ 0..1: auf 0..1 umrechnen (Werte außerhalb werden beschnitten). */
        private fun remapDomain(lut: Lut3D, min: FloatArray, max: FloatArray): Lut3D {
            val o = FloatArray(3)
            return of(lut.size, lut.title) { r, g, b, out ->
                fun n(v: Float, c: Int) = ((v - min[c]) / (max[c] - min[c])).coerceIn(0f, 1f)
                lut.eval(n(r, 0), n(g, 1), n(b, 2), o); out[0] = o[0]; out[1] = o[1]; out[2] = o[2]
            }
        }

        /**
         * .3dl (Autodesk Lustre/Flame): erste Zeile mit den Eingabestufen, danach N³ Zeilen mit
         * ganzzahligen Ausgaben; Blau läuft am schnellsten. Die Bittiefe ergibt sich aus dem Maximum.
         */
        fun parse3dl(text: String, fileName: String = ""): Lut3D {
            val rows = text.lineSequence().map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() && it.first().isDigit() }
                .map { l -> l.split(Regex("\\s+")).map { it.toInt() } }.toList()
            require(rows.isNotEmpty()) { "Keine gültige LUT (.3dl)" }
            val header = rows.first().takeIf { it.size > 3 }
            val triples = (if (header != null) rows.drop(1) else rows).filter { it.size >= 3 }
            val n = header?.size ?: Math.cbrt(triples.size.toDouble()).toInt()
            require(n >= 2 && triples.size >= n * n * n) { "Unvollständige .3dl-Tabelle" }
            val maxOut = triples.maxOf { maxOf(it[0], it[1], it[2]) }
            val scale = when {
                maxOut > 4095 -> 65535f
                maxOut > 1023 -> 4095f
                maxOut > 255 -> 1023f
                else -> 255f
            }
            val d = FloatArray(n * n * n * 3)
            // Datei: Index = (r·n + g)·n + b; Tabelle: Rot am schnellsten
            for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) {
                val t = triples[(r * n + g) * n + b]
                val o = ((b * n + g) * n + r) * 3
                d[o] = t[0] / scale; d[o + 1] = t[1] / scale; d[o + 2] = t[2] / scale
            }
            return Lut3D(n, d, fileName.substringAfterLast('/').substringBeforeLast('.'))
        }
    }
}
