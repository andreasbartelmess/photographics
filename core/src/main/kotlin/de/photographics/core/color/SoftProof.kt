package de.photographics.core.color

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Proof-Bedingungen wie unter *Ansicht → Proof einrichten* in Photoshop.
 *
 * Die Druckbedingungen sind ohne ICC-Profile modelliert: Aus den Normfarbwerten der Prozessfarben
 * und ihrer Übereinanderdrucke (ISO 12647-2/-3) wird mit einem Yule-Nielsen-Neugebauer-Modell der
 * druckbare Farbumfang berechnet. Das ergibt eine gute Näherung für Farbumfang-Warnung und
 * Vorschau, ersetzt aber keinen Proof mit dem Profil der Druckerei.
 */
enum class ProofCondition(val label: String, val description: String) {
    COATED("Offsetdruck gestrichen", "ISO Coated v2 / FOGRA39 (Bilderdruckpapier)"),
    UNCOATED("Offsetdruck ungestrichen", "PSO Uncoated / FOGRA52 (Naturpapier)"),
    NEWSPAPER("Zeitungsdruck", "ISO Newspaper 26 (IFRA26)"),
    GRAYSCALE("Graustufen", "Dot Gain 15 %"),
    PROTANOPIA("Farbenblindheit – Protanopie", "Rotschwäche"),
    DEUTERANOPIA("Farbenblindheit – Deuteranopie", "Grünschwäche");

    val isPrint get() = this == COATED || this == UNCOATED || this == NEWSPAPER
}

/**
 * Umrechnung für die Bildschirmanzeige: ARGB → simulierte Ausgabe. Intern eine 3D-Tabelle
 * (49³ Stützstellen, trilinear), daher schnell genug für jedes Neuzeichnen.
 */
class SoftProof private constructor(
    /** Kennung (zum Wiederverwenden bereits berechneter Tabellen). */
    val key: String,
    /** Gibt es einen Farbumfang (Druck), für den gewarnt werden kann? */
    val supportsGamutWarning: Boolean,
    /** Stützstelle (Dokument-RGB 0..1) → Anzeige-sRGB in out; true = außerhalb des Farbumfangs. */
    node: (Double, Double, Double, DoubleArray) -> Boolean,
) {
    private val lut = IntArray(N * N * N)
    /** 0..255: Anteil außerhalb des Farbumfangs (für die Farbumfang-Warnung, interpoliert). */
    private val oog = ByteArray(N * N * N)

    /** Modellierte Bedingung; [document] = Dokumentprofil (null = sRGB). */
    constructor(condition: ProofCondition, simulatePaper: Boolean, document: IccProfile? = null) : this(
        "model:${condition.name}:$simulatePaper:${document?.bytes?.contentHashCode()}", condition.isPrint, modeled(condition, simulatePaper, document),
    )

    init {
        val rgb = DoubleArray(3)
        for (bi in 0 until N) for (gi in 0 until N) for (ri in 0 until N) {
            val i = (bi * N + gi) * N + ri
            val out = node(ri / (N - 1.0), gi / (N - 1.0), bi / (N - 1.0), rgb)
            lut[i] = (to8(rgb[0]) shl 16) or (to8(rgb[1]) shl 8) or to8(rgb[2])
            oog[i] = if (out) 255.toByte() else 0
        }
    }

    /**
     * Wendet den Proof auf [px] an (Alpha bleibt). Mit [gamutWarning] werden Farben außerhalb des
     * Farbumfangs mit [warnColor] markiert; mit [transform] = false nur das (Farbumfang-Warnung allein).
     */
    fun apply(px: IntArray, gamutWarning: Boolean = false, warnColor: Int = 0x808080, transform: Boolean = true) {
        val warn = gamutWarning && supportsGamutWarning
        if (!warn && !transform) return
        val scale = (N - 1) / 255f
        for (k in px.indices) {
            val c = px[k]
            val a = c ushr 24
            if (a == 0) continue
            val fr = ((c shr 16) and 0xFF) * scale; val fg = ((c shr 8) and 0xFF) * scale; val fb = (c and 0xFF) * scale
            val r0 = fr.toInt().coerceAtMost(N - 2); val g0 = fg.toInt().coerceAtMost(N - 2); val b0 = fb.toInt().coerceAtMost(N - 2)
            val dr = fr - r0; val dg = fg - g0; val db = fb - b0
            var rr = 0f; var gg = 0f; var bb = 0f; var oo = 0f
            for (corner in 0 until 8) {
                val cr = corner and 1; val cg = (corner shr 1) and 1; val cb = (corner shr 2) and 1
                val w = (if (cr == 1) dr else 1 - dr) * (if (cg == 1) dg else 1 - dg) * (if (cb == 1) db else 1 - db)
                if (w == 0f) continue
                val idx = ((b0 + cb) * N + g0 + cg) * N + r0 + cr
                if (warn) oo += (oog[idx].toInt() and 0xFF) * w
                if (!transform) continue
                val v = lut[idx]
                rr += ((v shr 16) and 0xFF) * w; gg += ((v shr 8) and 0xFF) * w; bb += (v and 0xFF) * w
            }
            // Interpolierter Anteil: glatte Grenze der Warnfläche
            if (warn && oo >= 127.5f) { px[k] = (a shl 24) or (warnColor and 0xFFFFFF); continue }
            if (!transform) continue
            px[k] = (a shl 24) or ((rr + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((gg + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (bb + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    /** Liegt die Farbe (ARGB) außerhalb des druckbaren Farbumfangs? */
    fun outOfGamut(argb: Int): Boolean {
        if (!supportsGamutWarning) return false
        val i = (((argb and 0xFF) * (N - 1) + 127) / 255 * N + (((argb shr 8) and 0xFF) * (N - 1) + 127) / 255) * N +
            (((argb shr 16) and 0xFF) * (N - 1) + 127) / 255
        return oog[i].toInt() != 0
    }

    companion object {
        private const val N = 49

        private fun to8(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()

        /** Dokument-RGB → sRGB-Werte 0..1 (null = Dokument ist bereits sRGB). */
        private fun toSrgbFn(document: IccProfile?): ((Double, Double, Double, DoubleArray) -> Unit)? {
            if (document == null || document.bytes.contentEquals(IccProfiles.sRGB.bytes)) return null
            val t = ColorTransform(document, IccProfiles.sRGB, RenderingIntent.RELATIVE, true)
            val i = DoubleArray(3)
            return { r, g, b, o -> i[0] = r; i[1] = g; i[2] = b; t.apply(i, o) }
        }

        private fun modeled(condition: ProofCondition, simulatePaper: Boolean, document: IccProfile?): (Double, Double, Double, DoubleArray) -> Boolean {
            val press = if (condition.isPrint) PressModel.of(condition) else null
            val toSrgb = toSrgbFn(document)
            val s = DoubleArray(3)
            return { r0, g0, b0, rgb ->
                var r = r0; var g = g0; var b = b0
                if (toSrgb != null) { toSrgb(r0, g0, b0, s); r = s[0]; g = s[1]; b = s[2] }
                var out = false
                when (condition) {
                    ProofCondition.GRAYSCALE -> {
                        val l = Lab.fromSrgb(r, g, b)[0]
                        // Tonwertzuwachs 15 % (Graustufen-Arbeitsfarbraum): Mitteltöne werden dunkler
                        val tone = 1 - l / 100.0
                        val gained = (tone + 0.6 * tone * (1 - tone) * 0.5).coerceIn(0.0, 1.0)
                        Lab.toSrgb(doubleArrayOf((1 - gained) * 100, 0.0, 0.0), rgb)
                    }
                    ProofCondition.PROTANOPIA -> daltonize(r, g, b, PROTAN, rgb)
                    ProofCondition.DEUTERANOPIA -> daltonize(r, g, b, DEUTAN, rgb)
                    else -> {
                        val lab = Lab.fromSrgb(r, g, b)
                        out = press!!.clipToGamut(lab)
                        if (simulatePaper) press.toAbsolute(lab)
                        Lab.toSrgb(lab, rgb)
                    }
                }
                out
            }
        }

        /** Nur Anzeige: Dokumentprofil → Bildschirm (sRGB), ohne Simulation. */
        fun display(document: IccProfile): SoftProof {
            val toSrgb = toSrgbFn(document)
            return SoftProof("display:${document.bytes.contentHashCode()}", false) { r, g, b, o ->
                if (toSrgb != null) toSrgb(r, g, b, o) else { o[0] = r; o[1] = g; o[2] = b }
                false
            }
        }

        /**
         * Proof mit einem echten ICC-Ausgabeprofil (z. B. vom Drucker oder der Druckerei):
         * Dokument → Ausgabegerät ([intent], [bpc]) → zurück in den PCS → Bildschirm. Mit
         * [simulatePaper] absolut farbmetrisch (Papierweiß und Schwärze sichtbar). Außerhalb des
         * Farbumfangs: Rundlauf über das Ausgabeprofil (relativ farbmetrisch) weicht um ΔE > 5 ab.
         */
        fun icc(document: IccProfile, output: IccProfile, intent: RenderingIntent, bpc: Boolean, simulatePaper: Boolean): SoftProof {
            val toDevice = ColorTransform(document, output, intent, bpc)
            // Mit Tiefenkompensierung: Tiefen unter dem Druckschwarz gelten nicht als „außerhalb“
            val gamut = ColorTransform(document, output, RenderingIntent.RELATIVE, true)
            val devToPcs = output.toPcs(RenderingIntent.RELATIVE)
            val display = RgbEncoder(IccProfiles.sRGB)
            val paper = output.mediaWhite
            val inp = DoubleArray(3); val dev = DoubleArray(output.channels); val xyz = DoubleArray(3)
            val lab1 = DoubleArray(3); val lab2 = DoubleArray(3)
            return SoftProof("icc:${document.bytes.contentHashCode()}:${output.bytes.contentHashCode()}:$intent:$bpc:$simulatePaper", true) { r, g, b, o ->
                inp[0] = r; inp[1] = g; inp[2] = b
                toDevice.apply(inp, dev)
                devToPcs.apply(dev, xyz)
                if (simulatePaper) for (c in 0..2) xyz[c] = xyz[c] * paper[c] / Pcs.D50[c]
                val px = display.encode(xyz[0], xyz[1], xyz[2])
                o[0] = ((px shr 16) and 0xFF) / 255.0; o[1] = ((px shr 8) and 0xFF) / 255.0; o[2] = (px and 0xFF) / 255.0
                // Farbumfang: relativer Rundlauf (mit Tiefenkompensierung) gegen den Sollwert
                gamut.pcsOf(inp, xyz); Pcs.xyzToLab(xyz[0], xyz[1], xyz[2], lab1)
                gamut.apply(inp, dev); devToPcs.apply(dev, xyz); Pcs.xyzToLab(xyz[0], xyz[1], xyz[2], lab2)
                Pcs.deltaE(lab1, lab2) > 5.0
            }
        }

        // Viénot, Brettel & Mollon (1999), in linearem sRGB
        val PROTAN = doubleArrayOf(0.11238, 0.88762, 0.0, 0.11238, 0.88762, 0.0, 0.00401, -0.00401, 1.0)
        val DEUTAN = doubleArrayOf(0.29275, 0.70725, 0.0, 0.29275, 0.70725, 0.0, -0.02234, 0.02234, 1.0)

        fun daltonize(r: Double, g: Double, b: Double, m: DoubleArray, out: DoubleArray) {
            val lr = Lab.lin(r); val lg = Lab.lin(g); val lb = Lab.lin(b)
            for (i in 0..2) out[i] = Lab.gam((m[i * 3] * lr + m[i * 3 + 1] * lg + m[i * 3 + 2] * lb).coerceIn(0.0, 1.0))
        }
    }
}

/** sRGB ↔ CIELAB (D65), in Double für den Tabellenaufbau. */
internal object Lab {
    private const val XN = 0.95047; private const val YN = 1.0; private const val ZN = 1.08883

    fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    fun gam(v: Double) = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

    fun srgbToXyz(r: Double, g: Double, b: Double): DoubleArray {
        val lr = lin(r); val lg = lin(g); val lb = lin(b)
        return doubleArrayOf(
            0.4124 * lr + 0.3576 * lg + 0.1805 * lb,
            0.2126 * lr + 0.7152 * lg + 0.0722 * lb,
            0.0193 * lr + 0.1192 * lg + 0.9505 * lb,
        )
    }

    private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    private fun fi(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) * 27 / 24389

    fun xyzToLab(x: Double, y: Double, z: Double): DoubleArray {
        val fx = f(x / XN); val fy = f(y / YN); val fz = f(z / ZN)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun labToXyz(lab: DoubleArray): DoubleArray {
        val fy = (lab[0] + 16) / 116; val fx = fy + lab[1] / 500; val fz = fy - lab[2] / 200
        return doubleArrayOf(XN * fi(fx), YN * fi(fy), ZN * fi(fz))
    }

    fun fromSrgb(r: Double, g: Double, b: Double): DoubleArray = srgbToXyz(r, g, b).let { xyzToLab(it[0], it[1], it[2]) }

    fun toSrgb(lab: DoubleArray, out: DoubleArray) {
        val (x, y, z) = labToXyz(lab).let { Triple(it[0], it[1], it[2]) }
        out[0] = gam((3.2406 * x - 1.5372 * y - 0.4986 * z).coerceIn(0.0, 1.0))
        out[1] = gam((-0.9689 * x + 1.8758 * y + 0.0415 * z).coerceIn(0.0, 1.0))
        out[2] = gam((0.0557 * x - 0.2040 * y + 1.0570 * z).coerceIn(0.0, 1.0))
    }
}

/**
 * Druckmodell: Yule-Nielsen-Neugebauer (n = 2) über die 16 Kombinationen von C, M, Y, K.
 * Daraus wird eine Farbumfang-Grenze (maximale Buntheit je Helligkeit und Farbton) bestimmt –
 * relativ farbmetrisch mit Tiefenkompensierung, wie in Photoshops Standard-Proof.
 */
internal class PressModel(paper: DoubleArray, primaries: Map<String, DoubleArray>, totalAreaCoverage: Double) {
    private val paperXyz = Lab.labToXyz(paper)
    private var blackY = 1.0
    /** Maximale Buntheit je (L-Klasse, Farbtonklasse). */
    private val cmax = Array(L_BINS) { DoubleArray(H_BINS) { -1.0 } }

    init {
        // XYZ^(1/n) der 16 Neugebauer-Primärfarben; K-Kombinationen multiplikativ abgeleitet
        val k = Lab.labToXyz(primaries.getValue("K"))
        val base = listOf("W", "C", "M", "CM", "Y", "CY", "MY", "CMY").map { key ->
            if (key == "W") paperXyz else Lab.labToXyz(primaries.getValue(key))
        }
        val prim = Array(16) { i ->
            val p = base[i and 7]
            // Übereinanderdruck mit Schwarz: nur teilweise multiplikativ (Farbannahme/Trapping)
            val xyz = if (i < 8) p else DoubleArray(3) { c -> k[c] * (p[c] / paperXyz[c]).pow(TRAPPING) }
            DoubleArray(3) { c -> sqrt(xyz[c].coerceAtLeast(0.0)) }
        }
        val samples = ArrayList<DoubleArray>()
        val steps = 24
        for (ki in 0..8) for (yi in 0..steps) for (mi in 0..steps) for (ci in 0..steps) {
            val c = ci / steps.toDouble(); val m = mi / steps.toDouble(); val y = yi / steps.toDouble(); val kk = ki / 8.0
            // Maximaler Gesamtfarbauftrag der Druckbedingung
            if (c + m + y + kk > totalAreaCoverage + 1e-9) continue
            val xyz = DoubleArray(3)
            for (i in 0 until 16) {
                val w = (if (i and 1 != 0) c else 1 - c) * (if (i and 2 != 0) m else 1 - m) *
                    (if (i and 4 != 0) y else 1 - y) * (if (i and 8 != 0) kk else 1 - kk)
                if (w == 0.0) continue
                for (ch in 0..2) xyz[ch] += w * prim[i][ch]
            }
            for (ch in 0..2) xyz[ch] = xyz[ch] * xyz[ch]
            samples += xyz
            blackY = minOf(blackY, xyz[1] / paperXyz[1])
        }
        for (xyz in samples) {
            val lab = toRelative(xyz)
            val l = lab[0]; val cc = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            val li = (l / L_STEP).roundToInt().coerceIn(0, L_BINS - 1)
            val hi = hueBin(lab)
            if (cc > cmax[li][hi]) cmax[li][hi] = cc
        }
        // Lücken je Farbton entlang L interpolieren; an Weiß- und Schwarzpunkt ist die Buntheit 0
        for (h in 0 until H_BINS) {
            cmax[0][h] = maxOf(cmax[0][h], 0.0); cmax[L_BINS - 1][h] = maxOf(cmax[L_BINS - 1][h], 0.0)
            var last = 0
            for (l in 1 until L_BINS) {
                if (cmax[l][h] < 0) continue
                for (j in last + 1 until l) cmax[j][h] = cmax[last][h] + (cmax[l][h] - cmax[last][h]) * (j - last) / (l - last)
                last = l
            }
        }
    }

    init {
        // Grenze glätten: Stichproben-Rauschen der Maxima würde sonst als Streifen sichtbar
        repeat(3) {
            val src = Array(L_BINS) { cmax[it].copyOf() }
            for (l in 1 until L_BINS - 1) for (h in 0 until H_BINS) {
                var sum = 0.0
                for (dl in -1..1) for (dh in -1..1) sum += src[l + dl][(h + dh + H_BINS) % H_BINS] * (if (dl == 0 && dh == 0) 4 else if (dl == 0 || dh == 0) 2 else 1)
                cmax[l][h] = sum / 16
            }
        }
    }

    private fun hueBin(lab: DoubleArray): Int {
        var h = Math.toDegrees(atan2(lab[2], lab[1])); if (h < 0) h += 360
        return (h / (360.0 / H_BINS)).toInt().coerceIn(0, H_BINS - 1)
    }

    /** Absolute Druck-XYZ → relatives Lab (Papier = Weiß, Schwarzpunkt kompensiert). */
    private fun toRelative(xyz: DoubleArray): DoubleArray {
        val w = Lab.labToXyz(doubleArrayOf(100.0, 0.0, 0.0))
        val rel = DoubleArray(3) { c -> xyz[c] / paperXyz[c] * w[c] }
        val bk = blackY.coerceIn(0.0, 0.5)
        val bpc = DoubleArray(3) { c -> ((rel[c] - bk * w[c]) / (1 - bk)).coerceAtLeast(0.0) }
        return Lab.xyzToLab(bpc[0], bpc[1], bpc[2])
    }

    /** Maximal druckbare Buntheit bei Helligkeit [l] und Farbton [hDeg] (bilinear interpoliert). */
    private fun maxChroma(l: Double, hDeg: Double): Double {
        val fl = (l / L_STEP).coerceIn(0.0, L_BINS - 1.0)
        val fh = (hDeg / (360.0 / H_BINS)) - 0.5
        val l0 = floor(fl).toInt().coerceAtMost(L_BINS - 2); val tl = fl - l0
        val h0 = floor(fh).toInt(); val th = fh - h0
        fun v(li: Int, hi: Int) = cmax[li][((hi % H_BINS) + H_BINS) % H_BINS]
        val a = v(l0, h0) * (1 - th) + v(l0, h0 + 1) * th
        val b = v(l0 + 1, h0) * (1 - th) + v(l0 + 1, h0 + 1) * th
        return a * (1 - tl) + b * tl
    }

    /**
     * Bildet [lab] (relativ) auf die nächstgelegene druckbare Farbe ab (kleinster Farbabstand bei
     * gleichem Farbton, wie relativ farbmetrisch); true, wenn die Farbe außerhalb lag.
     */
    fun clipToGamut(lab: DoubleArray): Boolean {
        val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        if (c < 2.0) return false
        var h = Math.toDegrees(atan2(lab[2], lab[1])); if (h < 0) h += 360
        val l = lab[0]
        if (c <= maxChroma(l, h) + TOLERANCE) return false
        // Nächster Punkt auf der Grenze in der L-C-Ebene dieses Farbtons
        var bestL = l; var bestC = 0.0; var best = Double.MAX_VALUE
        var lt = 0.0
        while (lt <= 100.0) {
            val cm = maxChroma(lt, h).coerceAtLeast(0.0)
            val cc = minOf(c, cm)
            val d = (l - lt) * (l - lt) + (c - cc) * (c - cc)
            if (d < best) { best = d; bestL = lt; bestC = cc }
            lt += 0.5
        }
        val f = bestC / c
        lab[0] = bestL; lab[1] *= f; lab[2] *= f
        return true
    }

    /** Relativ → absolut: Papierweiß und Schwärze des Drucks sichtbar machen. */
    fun toAbsolute(lab: DoubleArray) {
        val w = Lab.labToXyz(doubleArrayOf(100.0, 0.0, 0.0))
        val bpc = Lab.labToXyz(lab)
        val bk = blackY.coerceIn(0.0, 0.5)
        val abs = DoubleArray(3) { c -> (bpc[c] * (1 - bk) + bk * w[c]) / w[c] * paperXyz[c] }
        val r = Lab.xyzToLab(abs[0], abs[1], abs[2])
        lab[0] = r[0]; lab[1] = r[1]; lab[2] = r[2]
    }

    companion object {
        const val L_BINS = 41
        const val L_STEP = 2.5
        const val H_BINS = 48
        /** Kleine Überschreitungen gelten noch als druckbar (wie die Toleranz in Photoshop). */
        const val TOLERANCE = 3.0
        /** Exponent für Schwarz-Übereinanderdrucke (1 = rein multiplikativ). */
        const val TRAPPING = 0.5

        private val cache = HashMap<ProofCondition, PressModel>()

        @Synchronized
        fun of(c: ProofCondition): PressModel = cache.getOrPut(c) {
            fun lab(l: Double, a: Double, b: Double) = doubleArrayOf(l, a, b)
            when (c) {
                // ISO 12647-2:2013, PC1 (gestrichen) – Volltöne und Übereinanderdrucke
                ProofCondition.COATED -> PressModel(lab(95.0, 0.0, -2.0), mapOf(
                    "C" to lab(55.0, -37.0, -50.0), "M" to lab(48.0, 74.0, -3.0), "Y" to lab(89.0, -5.0, 93.0),
                    "K" to lab(16.0, 0.0, 0.0), "CM" to lab(24.0, 22.0, -46.0), "CY" to lab(50.0, -65.0, 27.0),
                    "MY" to lab(47.0, 68.0, 48.0), "CMY" to lab(23.0, 0.0, 0.0),
                ), totalAreaCoverage = 3.3)
                // ISO 12647-2:2013, PC5 (ungestrichen, FOGRA52)
                ProofCondition.UNCOATED -> PressModel(lab(95.0, 1.0, -4.0), mapOf(
                    "C" to lab(60.0, -26.0, -44.0), "M" to lab(56.0, 61.0, -1.0), "Y" to lab(89.0, -4.0, 78.0),
                    "K" to lab(31.0, 1.0, 1.0), "CM" to lab(39.0, 9.0, -31.0), "CY" to lab(56.0, -42.0, 13.0),
                    "MY" to lab(54.0, 55.0, 26.0), "CMY" to lab(40.0, 0.0, 0.0),
                ), totalAreaCoverage = 3.0)
                // ISO 12647-3 (Zeitungsdruck, IFRA26)
                else -> PressModel(lab(82.0, 0.0, 3.0), mapOf(
                    "C" to lab(57.0, -23.0, -27.0), "M" to lab(54.0, 44.0, 0.0), "Y" to lab(78.0, -3.0, 58.0),
                    "K" to lab(36.0, 1.0, 4.0), "CM" to lab(41.0, 7.0, -22.0), "CY" to lab(53.0, -34.0, 17.0),
                    "MY" to lab(52.0, 41.0, 25.0), "CMY" to lab(40.0, 1.0, 3.0),
                ), totalAreaCoverage = 2.4)
            }
        }
    }
}
