package de.photographics.core.color

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Eingebaute Arbeitsfarbräume. Die Profile werden aus den Normdaten (Primärfarben, Weißpunkt,
 * Tonwertkurve) erzeugt – keine fremden Profildateien, frei einbettbar.
 */
object IccProfiles {
    /** Kurzname, Anzeigename, Primärvalenzen (xy R, G, B), Weißpunkt (xy), Kurve. */
    enum class Builtin(val label: String, val primaries: DoubleArray, val white: DoubleArray, val curve: Tone, val note: String) {
        SRGB("sRGB IEC61966-2.1", doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06), D65, Tone.SRGB, "Standard für Web und die meisten Bildschirme"),
        DISPLAY_P3("Display P3", doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060), D65, Tone.SRGB, "Größerer Farbraum moderner Tablets und Handys"),
        ADOBE_RGB("Adobe RGB (1998)", doubleArrayOf(0.64, 0.33, 0.21, 0.71, 0.15, 0.06), D65, Tone.Gamma(563.0 / 256.0), "Druckvorstufe, deckt CMYK besser ab"),
        PROPHOTO("ProPhoto RGB", doubleArrayOf(0.7347, 0.2653, 0.1596, 0.8404, 0.0366, 0.0001), D50xy, Tone.Gamma(1.8), "Sehr groß – mit 8 Bit Gefahr von Abrissen"),
        ;

        val profile: IccProfile by lazy { IccProfile(rgbProfile(label, primaries, white, curve)) }
    }

    sealed class Tone {
        object SRGB : Tone()
        class Gamma(val g: Double) : Tone()
        /** Beliebige Kurve (Gerätewert → linear), als Tabelle mit 1024 Stützstellen gespeichert. */
        class Function(val f: (Double) -> Double) : Tone()
    }

    private val D65 get() = doubleArrayOf(0.3127, 0.3290)
    private val D50xy get() = doubleArrayOf(0.3457, 0.3585)

    val sRGB: IccProfile get() = Builtin.SRGB.profile

    /** Graustufen Gamma 2.2 (D50). */
    val grayGamma22: IccProfile by lazy { IccProfile(grayProfile("Graustufen Gamma 2.2", 2.2)) }

    /** Eingebautes Profil, das [p] entspricht (gleicher Name), sonst null. */
    fun builtinFor(p: IccProfile): Builtin? = Builtin.entries.firstOrNull { it.profile.bytes.contentEquals(p.bytes) || it.label == p.description }

    // ------------------------------------------------------------ Matrixberechnung

    private fun xyToXyz(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)

    /** RGB → XYZ (D50-adaptiert per Bradford), Spalten = rXYZ, gXYZ, bXYZ. */
    fun rgbToXyzD50(pr: DoubleArray, white: DoubleArray): DoubleArray {
        val r = xyToXyz(pr[0], pr[1]); val g = xyToXyz(pr[2], pr[3]); val b = xyToXyz(pr[4], pr[5])
        val m = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
        val w = xyToXyz(white[0], white[1])
        val inv = IccProfile.invert3(m)
        val s = DoubleArray(3) { i -> inv[i * 3] * w[0] + inv[i * 3 + 1] * w[1] + inv[i * 3 + 2] * w[2] }
        val native = DoubleArray(9) { i -> m[i] * s[i % 3] }
        return mul(bradford(w, Pcs.D50), native)
    }

    private val BRADFORD = doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)

    /** Chromatische Adaption von Weiß [src] nach [dst] (XYZ). */
    fun bradford(src: DoubleArray, dst: DoubleArray): DoubleArray {
        val inv = IccProfile.invert3(BRADFORD)
        fun cone(v: DoubleArray) = DoubleArray(3) { i -> BRADFORD[i * 3] * v[0] + BRADFORD[i * 3 + 1] * v[1] + BRADFORD[i * 3 + 2] * v[2] }
        val cs = cone(src); val cd = cone(dst)
        val d = doubleArrayOf(cd[0] / cs[0], 0.0, 0.0, 0.0, cd[1] / cs[1], 0.0, 0.0, 0.0, cd[2] / cs[2])
        return mul(inv, mul(d, BRADFORD))
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    // ------------------------------------------------------------ ICC-Schreiber (Version 2.4)

    /** Matrix/TRC-Profil für einen RGB-Arbeitsfarbraum. */
    fun rgbProfile(name: String, primaries: DoubleArray, white: DoubleArray, tone: Tone): ByteArray {
        val m = rgbToXyzD50(primaries, white)
        val trc = curveTag(tone)
        val w = xyToXyz(white[0], white[1])
        return build("mntr", "RGB ", listOf(
            "desc" to descTag(name), "cprt" to textTag("Erzeugt von Photographics aus Normdaten, frei verwendbar"),
            "wtpt" to xyzTag(w), "rXYZ" to xyzTag(doubleArrayOf(m[0], m[3], m[6])),
            "gXYZ" to xyzTag(doubleArrayOf(m[1], m[4], m[7])), "bXYZ" to xyzTag(doubleArrayOf(m[2], m[5], m[8])),
            "rTRC" to trc, "gTRC" to trc, "bTRC" to trc,
        ))
    }

    fun grayProfile(name: String, gamma: Double): ByteArray = build("mntr", "GRAY", listOf(
        "desc" to descTag(name), "cprt" to textTag("Erzeugt von Photographics"),
        "wtpt" to xyzTag(Pcs.D50), "kTRC" to curveTag(Tone.Gamma(gamma)),
    ))

    private fun curveTag(t: Tone): ByteArray {
        val o = ByteArrayOutputStream()
        o.writeAscii("curv"); o.writeInt(0)
        when (t) {
            is Tone.Gamma -> { o.writeInt(1); o.writeShort((t.g * 256).roundToInt()); o.writeShort(0) }
            Tone.SRGB -> {
                val n = 1024
                o.writeInt(n)
                for (i in 0 until n) {
                    val v = i / (n - 1.0)
                    val lin = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
                    o.writeShort((lin * 65535).roundToInt())
                }
            }
            is Tone.Function -> {
                val n = 1024
                o.writeInt(n)
                for (i in 0 until n) o.writeShort((t.f(i / (n - 1.0)).coerceIn(0.0, 1.0) * 65535).roundToInt())
            }
        }
        return o.toByteArray()
    }

    private fun xyzTag(v: DoubleArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.writeAscii("XYZ "); o.writeInt(0); for (c in v) o.writeInt((c * 65536).roundToInt())
        return o.toByteArray()
    }

    private fun textTag(s: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.writeAscii("text"); o.writeInt(0); o.write(s.toByteArray(Charsets.ISO_8859_1)); o.write(0)
        return o.toByteArray()
    }

    private fun descTag(s: String): ByteArray {
        val o = ByteArrayOutputStream()
        val a = s.toByteArray(Charsets.ISO_8859_1)
        o.writeAscii("desc"); o.writeInt(0); o.writeInt(a.size + 1); o.write(a); o.write(0)
        o.writeInt(0); o.writeInt(0)            // Unicode: keiner
        o.writeShort(0); o.write(0); o.write(ByteArray(67))  // ScriptCode: keiner
        return o.toByteArray()
    }

    private fun build(cls: String, space: String, tags: List<Pair<String, ByteArray>>): ByteArray {
        // Gleiche Inhalte (z. B. drei identische Kurven) nur einmal ablegen
        val headerAndTable = 128 + 4 + tags.size * 12
        val data = ByteArrayOutputStream()
        val offsets = HashMap<Int, Int>()
        val table = tags.map { (sig, bytes) ->
            val key = bytes.contentHashCode()
            val off = offsets.getOrPut(key) {
                while (data.size() % 4 != 0) data.write(0)
                val o = headerAndTable + data.size(); data.write(bytes); o
            }
            Triple(sig, off, bytes.size)
        }
        while (data.size() % 4 != 0) data.write(0)
        val total = headerAndTable + data.size()
        val b = ByteBuffer.allocate(total)
        b.putInt(total); b.putInt(0); b.putInt(0x02400000)
        b.put(cls.toByteArray(Charsets.US_ASCII)); b.put(space.toByteArray(Charsets.US_ASCII)); b.put("XYZ ".toByteArray(Charsets.US_ASCII))
        for (v in intArrayOf(2026, 1, 1, 0, 0, 0)) b.putShort(v.toShort())
        b.put("acsp".toByteArray(Charsets.US_ASCII)); b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0); b.putLong(0); b.putInt(0)
        for (c in Pcs.D50) b.putInt((c * 65536).roundToInt())
        b.put("PGRF".toByteArray(Charsets.US_ASCII))
        b.put(ByteArray(44))
        b.putInt(tags.size)
        for ((sig, off, size) in table) { b.put(sig.toByteArray(Charsets.US_ASCII)); b.putInt(off); b.putInt(size) }
        b.put(data.toByteArray())
        return b.array()
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.writeInt(v: Int) { write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v) }
    private fun ByteArrayOutputStream.writeShort(v: Int) { write(v ushr 8); write(v) }
}
