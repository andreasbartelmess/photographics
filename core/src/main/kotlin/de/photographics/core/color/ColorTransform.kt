package de.photographics.core.color

import kotlin.math.sqrt

/** Thread-sichere Umrechnung einer 8-Bit-RGB-Farbe (0xRRGGBB). */
fun interface RgbConverter {
    fun map(rgb: Int): Int
}

/**
 * Umwandlung zwischen zwei Profilen über den Profilverbindungsraum – das Herz des
 * Farbmanagements („In Profil umwandeln“, Anzeige, Proof, CMYK-Import).
 *
 * - [intent]: Renderpriorität; absolut farbmetrisch rechnet zusätzlich mit den Medienweißpunkten.
 * - [blackPointCompensation]: Tiefenkompensierung (wie Photoshop: Schwarz wird auf Schwarz
 *   abgebildet statt abgeschnitten); bei absolut farbmetrisch wirkungslos.
 */
class ColorTransform(
    val source: IccProfile,
    val target: IccProfile,
    val intent: RenderingIntent = RenderingIntent.RELATIVE,
    val blackPointCompensation: Boolean = true,
) {
    private val toPcs = source.toPcs(intent)
    private val fromPcs = target.fromPcs(intent)
    private val inCh = source.channels
    private val outCh = target.channels

    private val absolute = intent == RenderingIntent.ABSOLUTE
    private val bpc = blackPointCompensation && !absolute
    /** Anpassung im PCS je Komponente: x' = scale·x + offset (absolut farbmetrisch bzw. Tiefenkompensierung). */
    private val pcsScale = DoubleArray(3) { 1.0 }
    private val pcsOffset = DoubleArray(3)

    init {
        if (absolute) {
            val sw = source.mediaWhite; val dw = target.mediaWhite
            for (c in 0..2) pcsScale[c] = sw[c] / dw[c]
        } else if (bpc) {
            val bs = source.blackPoint(intent, asInput = true); val bd = target.blackPoint(intent, asInput = false)
            for (c in 0..2) {
                val w = Pcs.D50[c]
                val s = (w - bd[c]) / (w - bs[c]).coerceAtLeast(1e-6)
                pcsScale[c] = s; pcsOffset[c] = bd[c] - bs[c] * s
            }
        }
    }

    private val pcs = DoubleArray(3)

    /** Gerätewerte (0..1) der Quelle → Gerätewerte (0..1) des Ziels. */
    @Synchronized
    fun apply(input: DoubleArray, out: DoubleArray) {
        toPcs.apply(input, pcs)
        for (c in 0..2) pcs[c] = pcs[c] * pcsScale[c] + pcsOffset[c]
        fromPcs.apply(pcs, out)
        for (c in 0 until outCh) out[c] = out[c].coerceIn(0.0, 1.0)
    }

    /** Quellfarbe im PCS (XYZ) nach Absolut-/Tiefenkompensierungs-Anpassung – Sollwert im Ziel. */
    @Synchronized
    fun pcsOf(input: DoubleArray, out: DoubleArray) {
        toPcs.apply(input, out)
        for (c in 0..2) out[c] = out[c] * pcsScale[c] + pcsOffset[c]
    }

    /** Als 3D-Tabelle (nur RGB → RGB) – etwa für Proof und Anzeige-Vorschau. */
    fun toLut3D(size: Int = 33): Lut3D {
        require(inCh == 3 && outCh == 3) { "3D-Tabelle nur für RGB → RGB" }
        val i = DoubleArray(3); val o = DoubleArray(3)
        return Lut3D.of(size, "${source.description} → ${target.description}") { r, g, b, out ->
            i[0] = r.toDouble(); i[1] = g.toDouble(); i[2] = b.toDouble()
            apply(i, o)
            out[0] = o[0].toFloat(); out[1] = o[1].toFloat(); out[2] = o[2].toFloat()
        }
    }

    /** Wandelt eine 8-Bit-Farbe um (für Einzelwerte, z. B. Pipette). */
    fun map8(vararg v: Int): IntArray {
        val i = DoubleArray(inCh) { v[it] / 255.0 }; val o = DoubleArray(outCh)
        apply(i, o)
        return IntArray(outCh) { Math.round(o[it] * 255).toInt() }
    }

    // ------------------------------------------------------------ schnelle, genaue Wege

    private val fastOut: RgbEncoder? by lazy { if (target.matrixShaper() != null) RgbEncoder(target) else null }

    /**
     * RGB → RGB (8 Bit), thread-sicher und genau: zwischen Matrix/TRC-Profilen exakt über
     * Kurventabellen und eine zusammengefasste Matrix (wie LittleCMS „Matrix-Shaper“), sonst über
     * eine 3D-Tabelle.
     */
    fun rgbConverter(): RgbConverter {
        require(inCh == 3 && outCh == 3) { "Nur RGB → RGB" }
        val srcMs = source.matrixShaper(); val out = fastOut
        if (srcMs != null && out != null) {
            val (m, trc) = srcMs
            val lin = Array(3) { c -> DoubleArray(256) { trc[c].eval(it / 255.0) } }
            return RgbConverter { c ->
                val r = lin[0][(c shr 16) and 0xFF]; val g = lin[1][(c shr 8) and 0xFF]; val b = lin[2][c and 0xFF]
                val x = (m[0] * r + m[1] * g + m[2] * b) * pcsScale[0] + pcsOffset[0]
                val y = (m[3] * r + m[4] * g + m[5] * b) * pcsScale[1] + pcsOffset[1]
                val z = (m[6] * r + m[7] * g + m[8] * b) * pcsScale[2] + pcsOffset[2]
                out.encode(x, y, z)
            }
        }
        val lut = toLut3D(49)
        val tmp = ThreadLocal.withInitial { FloatArray(3) }
        return RgbConverter { c -> lut.map(c and 0xFFFFFF, tmp.get()) }
    }

    /**
     * Beliebige Quelle (CMYK, Graustufen, Lab …) → RGB-Pixel, exakt pro Aufruf ausgewertet
     * (für den Import; nicht thread-sicher).
     */
    fun deviceToRgb(input: DoubleArray): Int {
        toPcs.apply(input, pcs)
        val x = pcs[0] * pcsScale[0] + pcsOffset[0]; val y = pcs[1] * pcsScale[1] + pcsOffset[1]; val z = pcs[2] * pcsScale[2] + pcsOffset[2]
        fastOut?.let { return it.encode(x, y, z) }
        pcs[0] = x; pcs[1] = y; pcs[2] = z
        val o = DoubleArray(outCh); fromPcs.apply(pcs, o)
        return (Math.round(o[0].coerceIn(0.0, 1.0) * 255).toInt() shl 16) or (Math.round(o[1].coerceIn(0.0, 1.0) * 255).toInt() shl 8) or
            Math.round(o[2].coerceIn(0.0, 1.0) * 255).toInt()
    }

    companion object {
        private val cache = object : LinkedHashMap<String, RgbConverter>(8, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, RgbConverter>?) = size > 8
        }

        /** RGB → RGB-Umrechner (zwischengespeichert); null wenn beide Profile gleich sind. */
        @Synchronized
        fun rgb(src: IccProfile, dst: IccProfile, intent: RenderingIntent, bpc: Boolean): RgbConverter? {
            if (src.bytes.contentEquals(dst.bytes)) return null
            val key = "${src.bytes.contentHashCode()}|${dst.bytes.contentHashCode()}|$intent|$bpc"
            return cache.getOrPut(key) { ColorTransform(src, dst, intent, bpc).rgbConverter() }
        }
    }
}

/**
 * PCS-XYZ (D50) → 8-Bit-RGB eines Profils. Bei Matrix/TRC-Profilen schnell und genau über die
 * invertierte Matrix und eine Tabelle der umgekehrten Tonwertkurve, die über √(linear) aufgeteilt
 * ist – so bleibt sie auch in den Tiefen genau, wo Gammakurven sehr steil sind. Thread-sicher.
 */
class RgbEncoder(private val profile: IccProfile) {
    private val ms = profile.matrixShaper()
    private val inv = ms?.let { IccProfile.invert3(it.first) }
    private val tables = ms?.second?.let { trc ->
        Array(3) { c -> DoubleArray(STEPS + 1) { i -> val t = i.toDouble() / STEPS; trc[c].inverse(t * t) * 255.0 } }
    }
    private val slow by lazy { profile.fromPcs(RenderingIntent.RELATIVE) }

    private fun channel(c: Int, lin: Double): Int {
        val tab = tables!![c]
        val t = sqrt(lin.coerceIn(0.0, 1.0)) * STEPS
        val i = minOf(t.toInt(), STEPS - 1); val f = t - i
        return (tab[i] + (tab[i + 1] - tab[i]) * f + 0.5).toInt().coerceIn(0, 255)
    }

    fun encode(x: Double, y: Double, z: Double): Int {
        val m = inv
        if (m == null) {
            val o = DoubleArray(3)
            synchronized(this) { slow.apply(doubleArrayOf(x, y, z), o) }
            return (Math.round(o[0].coerceIn(0.0, 1.0) * 255).toInt() shl 16) or (Math.round(o[1].coerceIn(0.0, 1.0) * 255).toInt() shl 8) or
                Math.round(o[2].coerceIn(0.0, 1.0) * 255).toInt()
        }
        val r = m[0] * x + m[1] * y + m[2] * z
        val g = m[3] * x + m[4] * y + m[5] * z
        val b = m[6] * x + m[7] * y + m[8] * z
        return (channel(0, r) shl 16) or (channel(1, g) shl 8) or channel(2, b)
    }

    private companion object { const val STEPS = 4096 }
}
