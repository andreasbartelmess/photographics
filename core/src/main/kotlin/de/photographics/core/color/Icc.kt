package de.photographics.core.color

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow

/** Renderprioritäten wie in Photoshop („Priorität“ im Dialog „In Profil umwandeln“). */
enum class RenderingIntent(val label: String, val code: Int) {
    PERCEPTUAL("Perzeptiv", 0),
    RELATIVE("Relativ farbmetrisch", 1),
    SATURATION("Sättigung", 2),
    ABSOLUTE("Absolut farbmetrisch", 3),
}

/** Ungültiges oder nicht unterstütztes ICC-Profil. */
class IccException(msg: String) : Exception(msg)

/**
 * Farbmathematik im Profilverbindungsraum (PCS): CIE-XYZ mit D50-Weißpunkt, Weiß = (0.9642, 1, 0.8249).
 */
object Pcs {
    val D50 = doubleArrayOf(0.9642, 1.0, 0.8249)

    fun xyzToLab(x: Double, y: Double, z: Double, out: DoubleArray) {
        fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
        val fx = f(x / D50[0]); val fy = f(y / D50[1]); val fz = f(z / D50[2])
        out[0] = 116 * fy - 16; out[1] = 500 * (fx - fy); out[2] = 200 * (fy - fz)
    }

    fun labToXyz(l: Double, a: Double, b: Double, out: DoubleArray) {
        fun fi(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) * 27 / 24389
        val fy = (l + 16) / 116; val fx = fy + a / 500; val fz = fy - b / 200
        out[0] = D50[0] * fi(fx); out[1] = D50[1] * fi(fy); out[2] = D50[2] * fi(fz)
    }

    fun deltaE(l1: DoubleArray, l2: DoubleArray): Double {
        val dl = l1[0] - l2[0]; val da = l1[1] - l2[1]; val db = l1[2] - l2[2]
        return kotlin.math.sqrt(dl * dl + da * da + db * db)
    }
}

/** Abbildung zwischen normierten Werten (Gerät 0..1) bzw. PCS-XYZ. */
fun interface ColorFn {
    fun apply(input: DoubleArray, out: DoubleArray)
}

/**
 * ICC-Profil (Version 2 und 4). Unterstützt Matrix/TRC-Profile (RGB, Graustufen) und
 * tabellenbasierte Profile (mft1, mft2, mAB, mBA) für RGB, CMYK, Graustufen und Lab – damit lassen
 * sich Druckerprofile (z. B. von der Druckerei) für Proof und Umwandlung verwenden.
 */
class IccProfile(val bytes: ByteArray) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes)
    val versionMajor: Int
    val deviceClass: String
    val colorSpace: String
    val pcs: String
    private val tags = HashMap<String, Pair<Int, Int>>()

    init {
        if (bytes.size < 132) throw IccException("Datei zu kurz für ein ICC-Profil")
        if (sig(36) != "acsp") throw IccException("Keine ICC-Profil-Signatur")
        versionMajor = bytes[8].toInt() and 0xFF
        deviceClass = sig(12); colorSpace = sig(16); pcs = sig(20)
        val n = u32(128)
        for (i in 0 until n) {
            val p = 132 + i * 12
            if (p + 12 > bytes.size) break
            val off = u32(p + 4); val size = u32(p + 8)
            if (off + size <= bytes.size) tags[sig(p)] = off to size
        }
        if (pcs != "XYZ " && pcs != "Lab ") throw IccException("Profilverbindungsraum „$pcs“ wird nicht unterstützt")
    }

    val channels: Int = when (colorSpace) {
        "RGB ", "Lab ", "XYZ ", "YCbr", "HSV ", "HLS ", "CMY " -> 3
        "GRAY" -> 1
        "CMYK" -> 4
        else -> colorSpace.trim().removeSuffix("CLR").toIntOrNull(16) ?: throw IccException("Farbraum „$colorSpace“ wird nicht unterstützt")
    }

    val isRgb get() = colorSpace == "RGB "
    val isCmyk get() = colorSpace == "CMYK"
    val isGray get() = colorSpace == "GRAY"

    /** Name aus dem desc-Tag (v2 textDescription oder v4 mluc). */
    val description: String by lazy { readText("desc") ?: "Unbenanntes Profil" }

    /**
     * Medienweiß (XYZ) für absolut farbmetrisch. Wie LittleCMS und Photoshop gilt bei
     * v2-Monitorprofilen D50 – absolut wirkt dort wie relativ; bei Druckerprofilen ist es das Papierweiß.
     */
    val mediaWhite: DoubleArray by lazy {
        if (versionMajor < 4 && deviceClass == "mntr") Pcs.D50.copyOf() else readXyz("wtpt") ?: Pcs.D50.copyOf()
    }

    // ------------------------------------------------------------ Lesehilfen

    private fun u8(p: Int) = bytes[p].toInt() and 0xFF
    private fun u16(p: Int) = ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)
    private fun u32(p: Int) = buf.getInt(p)
    private fun s15(p: Int) = buf.getInt(p) / 65536.0
    private fun sig(p: Int) = String(bytes, p, 4, Charsets.ISO_8859_1)

    fun hasTag(t: String) = tags.containsKey(t)

    private fun readXyz(tag: String): DoubleArray? {
        val (o, _) = tags[tag] ?: return null
        if (sig(o) != "XYZ ") return null
        return doubleArrayOf(s15(o + 8), s15(o + 12), s15(o + 16))
    }

    private fun readText(tag: String): String? {
        val (o, size) = tags[tag] ?: return null
        return runCatching {
            when (sig(o)) {
                "desc" -> { val n = u32(o + 8); String(bytes, o + 12, maxOf(0, n - 1), Charsets.ISO_8859_1).trim() }
                "text" -> String(bytes, o + 8, size - 8, Charsets.ISO_8859_1).trimEnd('\u0000').trim()
                "mluc" -> {
                    val count = u32(o + 8)
                    // Bevorzugt Deutsch, sonst Englisch, sonst den ersten Eintrag
                    var best = -1
                    for (i in 0 until count) {
                        val lang = String(bytes, o + 16 + i * 12, 2, Charsets.ISO_8859_1)
                        if (best < 0 || lang == "de" || (lang == "en" && String(bytes, o + 16 + best * 12, 2, Charsets.ISO_8859_1) != "de")) best = i
                    }
                    val len = u32(o + 16 + best * 12 + 4); val off = u32(o + 16 + best * 12 + 8)
                    String(bytes, o + off, len, Charsets.UTF_16BE).trimEnd('\u0000').trim()
                }
                else -> null
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------ Kurven

    /** Kurve (curv oder para) ab Position [o]; liefert Funktion und Länge in Bytes. */
    private fun readCurve(o: Int): Pair<Curve, Int> = when (sig(o)) {
        "curv" -> {
            val n = u32(o + 8)
            val c = when (n) {
                0 -> Curve.Gamma(1.0)
                1 -> Curve.Gamma(u16(o + 12) / 256.0)
                else -> Curve.Table(DoubleArray(n) { u16(o + 12 + it * 2) / 65535.0 })
            }
            c to (12 + n * 2)
        }
        "para" -> {
            val type = u16(o + 8)
            val count = intArrayOf(1, 3, 4, 5, 7).getOrElse(type) { throw IccException("Unbekannte Kurvenart $type") }
            val p = DoubleArray(count) { s15(o + 12 + it * 4) }
            Curve.Parametric(type, p) to (12 + count * 4)
        }
        else -> throw IccException("Unbekannte Kurve „${sig(o)}“")
    }

    private fun curveTag(tag: String): Curve? = tags[tag]?.let { (o, _) -> readCurve(o).first }

    // ------------------------------------------------------------ Matrix/TRC

    private val matrixModel: Boolean get() = isRgb && hasTag("rXYZ") && hasTag("gXYZ") && hasTag("bXYZ") && hasTag("rTRC")

    /** Matrix (Spalten rXYZ, gXYZ, bXYZ) und Tonwertkurven, falls es ein Matrix/TRC-RGB-Profil ist. */
    fun matrixShaper(): Pair<DoubleArray, Array<Curve>>? {
        if (!matrixModel || hasTag("A2B0")) return null
        val r = curveTag("rTRC")!!
        return matrix() to arrayOf(r, curveTag("gTRC") ?: r, curveTag("bTRC") ?: r)
    }

    private fun matrix(): DoubleArray {
        val r = readXyz("rXYZ")!!; val g = readXyz("gXYZ")!!; val b = readXyz("bXYZ")!!
        return doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
    }

    // ------------------------------------------------------------ Pipelines

    /** Gerät (0..1) → PCS-XYZ (relativ farbmetrisch, D50) für die gewünschte Priorität. */
    fun toPcs(intent: RenderingIntent): ColorFn {
        val lutTag = listOf("A2B${intent.code.coerceAtMost(2)}", "A2B0").firstOrNull { hasTag(it) }
        if (lutTag != null) return readLut(lutTag, toPcs = true)
        if (matrixModel) {
            val m = matrix()
            val trc = arrayOf(curveTag("rTRC")!!, curveTag("gTRC") ?: curveTag("rTRC")!!, curveTag("bTRC") ?: curveTag("rTRC")!!)
            return ColorFn { inp, out ->
                val r = trc[0].eval(inp[0]); val g = trc[1].eval(inp[1]); val b = trc[2].eval(inp[2])
                out[0] = m[0] * r + m[1] * g + m[2] * b
                out[1] = m[3] * r + m[4] * g + m[5] * b
                out[2] = m[6] * r + m[7] * g + m[8] * b
            }
        }
        if (isGray && hasTag("kTRC")) {
            val k = curveTag("kTRC")!!
            return ColorFn { inp, out -> val y = k.eval(inp[0]); out[0] = Pcs.D50[0] * y; out[1] = y; out[2] = Pcs.D50[2] * y }
        }
        throw IccException("Profil „$description“ kann nicht als Quelle dienen")
    }

    /** PCS-XYZ (relativ, D50) → Gerät (0..1). */
    fun fromPcs(intent: RenderingIntent): ColorFn {
        val lutTag = listOf("B2A${intent.code.coerceAtMost(2)}", "B2A0").firstOrNull { hasTag(it) }
        if (lutTag != null) return readLut(lutTag, toPcs = false)
        if (matrixModel) {
            val inv = invert3(matrix())
            val trc = arrayOf(curveTag("rTRC")!!, curveTag("gTRC") ?: curveTag("rTRC")!!, curveTag("bTRC") ?: curveTag("rTRC")!!)
            return ColorFn { inp, out ->
                for (c in 0..2) {
                    val lin = inv[c * 3] * inp[0] + inv[c * 3 + 1] * inp[1] + inv[c * 3 + 2] * inp[2]
                    out[c] = trc[c].inverse(lin.coerceIn(0.0, 1.0))
                }
            }
        }
        if (isGray && hasTag("kTRC")) {
            val k = curveTag("kTRC")!!
            return ColorFn { inp, out -> out[0] = k.inverse(inp[1].coerceIn(0.0, 1.0)) }
        }
        throw IccException("Profil „$description“ kann nicht als Ziel dienen")
    }

    // ------------------------------------------------------------ Tabellen (mft1, mft2, mAB, mBA)

    private fun readLut(tag: String, toPcs: Boolean): ColorFn {
        val (o, _) = tags[tag]!!
        val inCh = u8(o + 8); val outCh = u8(o + 9)
        val pcsLab = pcs == "Lab "
        return when (val type = sig(o)) {
            "mft1", "mft2" -> {
                val bits16 = type == "mft2"
                val grid = u8(o + 10)
                val mat = DoubleArray(9) { s15(o + 12 + it * 4) }
                val nIn = if (bits16) u16(o + 48) else 256; val nOut = if (bits16) u16(o + 50) else 256
                var p = o + if (bits16) 52 else 48
                val rd: (Int) -> Double = if (bits16) { i -> u16(i) / 65535.0 } else { i -> u8(i) / 255.0 }
                val step = if (bits16) 2 else 1
                val inT = Array(inCh) { c -> Curve.Table(DoubleArray(nIn) { rd(p + (c * nIn + it) * step) }) }
                p += inCh * nIn * step
                val clutSize = pow(grid, inCh) * outCh
                val labInput = (!toPcs && pcsLab) || (toPcs && colorSpace == "Lab ")
                val clut = Clut(IntArray(inCh) { grid }, outCh, DoubleArray(clutSize) { rd(p + it * step) }, labInput)
                p += clutSize * step
                val outT = Array(outCh) { c -> Curve.Table(DoubleArray(nOut) { rd(p + (c * nOut + it) * step) }) }
                // Die Matrix gilt nur, wenn der Eingang PCS-XYZ ist
                val useMatrix = !toPcs && !pcsLab && !isIdentity(mat)
                val legacyLab = bits16 // mft2: 16-Bit-Lab im Altformat (0xFF00 = 100)
                val lab = DoubleArray(3); val tmpIn = DoubleArray(inCh); val tmpOut = DoubleArray(outCh)
                ColorFn { inp, out ->
                    synchronized(tmpIn) {
                        if (toPcs) for (c in 0 until inCh) tmpIn[c] = inp[c]
                        else pcsToNormalized(inp, tmpIn, pcsLab, legacyLab, lab)
                        if (useMatrix) applyMatrix(mat, tmpIn)
                        for (c in 0 until inCh) tmpIn[c] = inT[c].eval(tmpIn[c])
                        clut.eval(tmpIn, tmpOut)
                        for (c in 0 until outCh) tmpOut[c] = outT[c].eval(tmpOut[c])
                        if (toPcs) normalizedToPcs(tmpOut, out, pcsLab, legacyLab, lab)
                        else for (c in 0 until outCh) out[c] = tmpOut[c]
                    }
                }
            }
            "mAB ", "mBA " -> {
                val offB = u32(o + 12); val offMat = u32(o + 16); val offM = u32(o + 20); val offClut = u32(o + 24); val offA = u32(o + 28)
                fun curves(off: Int, n: Int): Array<Curve>? {
                    if (off == 0) return null
                    var p = o + off
                    return Array(n) { val (c, len) = readCurve(p); p += (len + 3) and 3.inv(); c }
                }
                val isAtoB = type == "mAB "
                // A-Kurven liegen auf der Geräteseite, B auf der PCS-Seite
                val aCurves = curves(offA, if (isAtoB) inCh else outCh)
                val bCurves = curves(offB, if (isAtoB) outCh else inCh)!!
                val mCurves = curves(offM, if (isAtoB) outCh else inCh)
                val mat = if (offMat != 0) DoubleArray(12) { s15(o + offMat + it * 4) } else null
                val clut = if (offClut != 0) {
                    val p = o + offClut
                    val grid = IntArray(inCh) { u8(p + it) }
                    val prec = u8(p + 16)
                    val n = grid.fold(1) { a, g -> a * g } * outCh
                    val labInput = (!isAtoB && pcsLab) || (isAtoB && colorSpace == "Lab ")
                    Clut(grid, outCh, DoubleArray(n) { if (prec == 1) u8(p + 20 + it) / 255.0 else u16(p + 20 + it * 2) / 65535.0 }, labInput)
                } else null
                val lab = DoubleArray(3)
                val t1 = DoubleArray(maxOf(inCh, outCh, 3)); val t2 = DoubleArray(maxOf(inCh, outCh, 3))
                ColorFn { inp, out ->
                    synchronized(t1) {
                        if (isAtoB) {
                            for (c in 0 until inCh) t1[c] = aCurves?.get(c)?.eval(inp[c]) ?: inp[c]
                            if (clut != null) clut.eval(t1, t2) else for (c in 0 until outCh) t2[c] = t1[c]
                            if (mCurves != null) for (c in 0 until outCh) t2[c] = mCurves[c].eval(t2[c])
                            if (mat != null) applyMatrixOffset(mat, t2)
                            for (c in 0 until outCh) t2[c] = bCurves[c].eval(t2[c].coerceIn(0.0, 1.0))
                            normalizedToPcs(t2, out, pcsLab, legacyLab = false, tmp = lab)
                        } else {
                            pcsToNormalized(inp, t1, pcsLab, legacyLab = false, tmp = lab)
                            for (c in 0 until inCh) t1[c] = bCurves[c].eval(t1[c])
                            if (mat != null) applyMatrixOffset(mat, t1)
                            if (mCurves != null) for (c in 0 until inCh) t1[c] = mCurves[c].eval(t1[c].coerceIn(0.0, 1.0))
                            if (clut != null) clut.eval(t1, t2) else for (c in 0 until outCh) t2[c] = t1[c]
                            for (c in 0 until outCh) out[c] = aCurves?.get(c)?.eval(t2[c].coerceIn(0.0, 1.0)) ?: t2[c]
                        }
                    }
                }
            }
            else -> throw IccException("Tabellenart „$type“ wird nicht unterstützt")
        }
    }

    private fun pow(b: Int, e: Int): Int { var r = 1; repeat(e) { r *= b }; return r }
    private fun isIdentity(m: DoubleArray) = m.indices.all { abs(m[it] - if (it % 4 == 0) 1.0 else 0.0) < 1e-6 }

    private fun applyMatrix(m: DoubleArray, v: DoubleArray) {
        val x = v[0]; val y = v[1]; val z = v[2]
        v[0] = m[0] * x + m[1] * y + m[2] * z; v[1] = m[3] * x + m[4] * y + m[5] * z; v[2] = m[6] * x + m[7] * y + m[8] * z
    }

    private fun applyMatrixOffset(m: DoubleArray, v: DoubleArray) {
        applyMatrix(m, v); v[0] += m[9]; v[1] += m[10]; v[2] += m[11]
    }

    /** PCS (XYZ real) → normierte Tabellenwerte. */
    private fun pcsToNormalized(xyz: DoubleArray, out: DoubleArray, lab: Boolean, legacyLab: Boolean, tmp: DoubleArray) {
        if (lab) {
            Pcs.xyzToLab(xyz[0], xyz[1], xyz[2], tmp)
            val k = if (legacyLab) 65280.0 / 65535.0 else 1.0
            out[0] = (tmp[0] / 100.0 * k).coerceIn(0.0, 1.0)
            out[1] = ((tmp[1] + 128.0) / 255.0 * k).coerceIn(0.0, 1.0)
            out[2] = ((tmp[2] + 128.0) / 255.0 * k).coerceIn(0.0, 1.0)
        } else {
            // u1Fixed15: 1.0 ↔ 0x8000
            for (c in 0..2) out[c] = (xyz[c] * 32768.0 / 65535.0).coerceIn(0.0, 1.0)
        }
    }

    /** Normierte Tabellenwerte → PCS (XYZ real). */
    private fun normalizedToPcs(v: DoubleArray, out: DoubleArray, lab: Boolean, legacyLab: Boolean, tmp: DoubleArray) {
        if (lab) {
            val k = if (legacyLab) 65535.0 / 65280.0 else 1.0
            Pcs.labToXyz(v[0] * k * 100.0, v[1] * k * 255.0 - 128.0, v[2] * k * 255.0 - 128.0, out)
        } else {
            for (c in 0..2) out[c] = v[c] * 65535.0 / 32768.0
        }
        if (tmp.isEmpty()) return
    }

    // ------------------------------------------------------------ Schwarzpunkt

    /**
     * Schwarzpunkt (PCS-XYZ) für die Tiefenkompensierung. Als Quelle ([asInput]) ist es die dunkelste
     * Farbmischung des Geräts (bei CMYK alle Farben 100 %), als Ziel die dunkelste erreichbare Farbe
     * über einen Rundlauf von Lab-Schwarz – wie bei LittleCMS.
     */
    fun blackPoint(intent: RenderingIntent, asInput: Boolean = false): DoubleArray {
        val out = DoubleArray(3)
        return runCatching {
            when {
                // v4, perzeptiv/Sättigung: fester Referenz-Schwarzpunkt (Matrixprofile: dunkelste Farbe)
                versionMajor >= 4 && (intent == RenderingIntent.PERCEPTUAL || intent == RenderingIntent.SATURATION) && matrixShaper() == null ->
                    doubleArrayOf(0.00336, 0.0034731, 0.00287)
                // CMYK-Druckerprofil, relativ farbmetrisch: perzeptiver Rundlauf von Lab-Schwarz (wie LittleCMS)
                asInput && intent == RenderingIntent.RELATIVE && deviceClass == "prtr" && isCmyk && hasTag("B2A0") -> {
                    val dev = DoubleArray(channels)
                    val black = DoubleArray(3).also { Pcs.labToXyz(0.0, 0.0, 0.0, it) }
                    fromPcs(RenderingIntent.PERCEPTUAL).apply(black, dev)
                    toPcs(RenderingIntent.RELATIVE).apply(dev, out)
                    val lab = DoubleArray(3); Pcs.xyzToLab(out[0], out[1], out[2], lab)
                    Pcs.labToXyz(lab[0].coerceIn(0.0, 50.0), 0.0, 0.0, out)
                    out
                }
                asInput -> {
                    val dark = DoubleArray(channels) { if (isCmyk || colorSpace == "CMY ") 1.0 else 0.0 }
                    toPcs(intent).apply(dark, out)
                    val lab = DoubleArray(3); Pcs.xyzToLab(out[0], out[1], out[2], lab)
                    Pcs.labToXyz(if (lab[0] > 50) 0.0 else lab[0], 0.0, 0.0, out)
                    out
                }
                isCmyk || (!matrixModel && hasTag("B2A0")) -> {
                    val dev = DoubleArray(channels)
                    val black = DoubleArray(3).also { Pcs.labToXyz(0.0, 0.0, 0.0, it) }
                    fromPcs(intent).apply(black, dev)
                    toPcs(intent).apply(dev, out)
                    // Farbanteil entfernen: nur die Helligkeit zählt
                    val lab = DoubleArray(3); Pcs.xyzToLab(out[0], out[1], out[2], lab)
                    Pcs.labToXyz(lab[0].coerceIn(0.0, 50.0), 0.0, 0.0, out)
                    out
                }
                else -> { toPcs(intent).apply(DoubleArray(channels), out); out }
            }
        }.getOrElse { DoubleArray(3) }
    }

    override fun toString() = "$description (${colorSpace.trim()}, v$versionMajor)"

    companion object {
        fun invert3(m: DoubleArray): DoubleArray {
            val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            if (abs(det) < 1e-12) throw IccException("Profilmatrix ist nicht invertierbar")
            return doubleArrayOf(
                (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
                (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
                (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
            )
        }
    }
}

/** Tonwertkurve eines Profils. */
sealed class Curve {
    abstract fun eval(x: Double): Double

    /** Umkehrung (Kurven sind monoton steigend); per Bisektion – nur beim Aufbau von Tabellen benutzt. */
    open fun inverse(y: Double): Double {
        var lo = 0.0; var hi = 1.0
        val rising = eval(1.0) >= eval(0.0)
        repeat(40) {
            val mid = (lo + hi) / 2
            if ((eval(mid) < y) == rising) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    class Gamma(val g: Double) : Curve() {
        override fun eval(x: Double) = x.coerceAtLeast(0.0).pow(g)
        override fun inverse(y: Double) = y.coerceAtLeast(0.0).pow(1 / g)
    }

    class Table(val t: DoubleArray) : Curve() {
        override fun eval(x: Double): Double {
            val n = t.size - 1
            if (n <= 0) return t.firstOrNull() ?: x
            val f = x.coerceIn(0.0, 1.0) * n
            val i = minOf(f.toInt(), n - 1); val r = f - i
            return t[i] + (t[i + 1] - t[i]) * r
        }
    }

    class Parametric(val type: Int, val p: DoubleArray) : Curve() {
        override fun eval(x: Double): Double {
            val g = p[0]
            return when (type) {
                0 -> x.coerceAtLeast(0.0).pow(g)
                1 -> if (x >= -p[2] / p[1]) (p[1] * x + p[2]).coerceAtLeast(0.0).pow(g) else 0.0
                2 -> if (x >= -p[2] / p[1]) (p[1] * x + p[2]).coerceAtLeast(0.0).pow(g) + p[3] else p[3]
                3 -> if (x >= p[4]) (p[1] * x + p[2]).coerceAtLeast(0.0).pow(g) else p[3] * x
                else -> if (x >= p[4]) (p[1] * x + p[2]).coerceAtLeast(0.0).pow(g) + p[5] else p[3] * x + p[6]
            }
        }
    }
}

/** Mehrdimensionale Farbtabelle (CLUT): tetraedrisch bei 3 Eingängen, sonst multilinear. */
internal class Clut(
    private val grid: IntArray,
    private val outCh: Int,
    private val data: DoubleArray,
    /** Trilinear statt tetraedrisch (bei Lab-Eingang, wie LittleCMS – Tetraeder verzerren dort Farbtöne). */
    private val trilinear: Boolean = false,
) {
    private val inCh = grid.size
    private val stride = IntArray(inCh).also { s ->
        // Erster Eingang ändert sich am langsamsten (ICC-Reihenfolge)
        var acc = outCh
        for (i in inCh - 1 downTo 0) { s[i] = acc; acc *= grid[i] }
    }

    private val tmpA = DoubleArray(outCh); private val tmpB = DoubleArray(outCh)

    fun eval(inp: DoubleArray, out: DoubleArray) {
        if (inCh == 3 && !trilinear) { tetra(inp, out, 0); return }
        if (inCh == 4) {
            // Wie LittleCMS/Adobe CMM: linear über den ersten Kanal, tetraedrisch über die übrigen drei
            val f = inp[0].coerceIn(0.0, 1.0) * (grid[0] - 1)
            val k0 = minOf(f.toInt(), grid[0] - 2); val r = f - k0
            val sub = doubleArrayOf(inp[1], inp[2], inp[3])
            tetra(sub, tmpA, k0 * stride[0], 1)
            tetra(sub, tmpB, (k0 + 1) * stride[0], 1)
            for (c in 0 until outCh) out[c] = tmpA[c] + (tmpB[c] - tmpA[c]) * r
            return
        }
        val base = IntArray(inCh); val frac = DoubleArray(inCh)
        for (i in 0 until inCh) {
            val f = inp[i].coerceIn(0.0, 1.0) * (grid[i] - 1)
            base[i] = minOf(f.toInt(), maxOf(0, grid[i] - 2)); frac[i] = if (grid[i] > 1) f - base[i] else 0.0
        }
        for (c in 0 until outCh) out[c] = 0.0
        for (corner in 0 until (1 shl inCh)) {
            var w = 1.0; var idx = 0
            for (i in 0 until inCh) {
                val bit = (corner shr i) and 1
                w *= if (bit == 1) frac[i] else 1 - frac[i]
                idx += (base[i] + if (grid[i] > 1) bit else 0) * stride[i]
            }
            if (w == 0.0) continue
            for (c in 0 until outCh) out[c] += w * data[idx + c]
        }
    }

    /** Tetraedrisch über die Eingänge [first]..[first]+2; [base] = Versatz für weitere (feste) Eingänge. */
    private fun tetra(inp: DoubleArray, out: DoubleArray, base: Int, first: Int = 0) {
        val gx = grid[first]; val gy = grid[first + 1]; val gz = grid[first + 2]
        val fx = inp[0].coerceIn(0.0, 1.0) * (gx - 1); val fy = inp[1].coerceIn(0.0, 1.0) * (gy - 1); val fz = inp[2].coerceIn(0.0, 1.0) * (gz - 1)
        val x0 = minOf(fx.toInt(), gx - 2); val y0 = minOf(fy.toInt(), gy - 2); val z0 = minOf(fz.toInt(), gz - 2)
        val rx = fx - x0; val ry = fy - y0; val rz = fz - z0
        val sx = stride[first]; val sy = stride[first + 1]; val sz = stride[first + 2]
        val c0 = base + x0 * sx + y0 * sy + z0 * sz
        val c1: Int; val c2: Int; val w0: Double; val w1: Double; val w2: Double; val w3: Double
        if (rx >= ry) {
            if (ry >= rz) { c1 = c0 + sx; c2 = c0 + sx + sy; w0 = 1 - rx; w1 = rx - ry; w2 = ry - rz; w3 = rz }
            else if (rx >= rz) { c1 = c0 + sx; c2 = c0 + sx + sz; w0 = 1 - rx; w1 = rx - rz; w2 = rz - ry; w3 = ry }
            else { c1 = c0 + sz; c2 = c0 + sx + sz; w0 = 1 - rz; w1 = rz - rx; w2 = rx - ry; w3 = ry }
        } else {
            if (rz >= ry) { c1 = c0 + sz; c2 = c0 + sy + sz; w0 = 1 - rz; w1 = rz - ry; w2 = ry - rx; w3 = rx }
            else if (rz >= rx) { c1 = c0 + sy; c2 = c0 + sy + sz; w0 = 1 - ry; w1 = ry - rz; w2 = rz - rx; w3 = rx }
            else { c1 = c0 + sy; c2 = c0 + sx + sy; w0 = 1 - ry; w1 = ry - rx; w2 = rx - rz; w3 = rz }
        }
        val c3 = c0 + sx + sy + sz
        for (c in 0 until outCh) out[c] = w0 * data[c0 + c] + w1 * data[c1 + c] + w2 * data[c2 + c] + w3 * data[c3 + c]
    }
}
