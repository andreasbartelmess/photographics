package de.photographics.core.color

/**
 * Schnelle Umrechnung von 8-Bit-Gerätewerten (Graustufen, Lab, CMYK) in RGB eines Zielprofils –
 * vorab als Tabelle berechnet, dann pro Pixel nur noch interpoliert. Für den Import von PSD-Dateien
 * in diesen Farbmodi.
 */
class DeviceToRgb private constructor(private val channels: Int, private val eval: (IntArray) -> Int) {
    /** Gerätewerte 0..255 (bei CMYK: Farbauftrag, 255 = volle Farbe; bei Lab: PSD-Kodierung). */
    fun rgb(v: IntArray): Int = eval(v)

    companion object {
        /** Über ein ICC-Profil des Quellmodus ([source]) ins Zielprofil [target]. */
        fun icc(source: IccProfile, target: IccProfile, intent: RenderingIntent = RenderingIntent.RELATIVE, bpc: Boolean = true): DeviceToRgb {
            val t = ColorTransform(source, target, intent, bpc)
            val inp = DoubleArray(source.channels); val out = DoubleArray(3)
            fun pack(o: DoubleArray) = (Math.round(o[0] * 255).toInt() shl 16) or (Math.round(o[1] * 255).toInt() shl 8) or Math.round(o[2] * 255).toInt()
            return when (source.channels) {
                1 -> {
                    val table = IntArray(256) { inp[0] = it / 255.0; t.apply(inp, out); pack(out) }
                    DeviceToRgb(1) { v -> table[v[0]] }
                }
                3 -> {
                    val lut = Lut3D.of(33, "") { a, b, c, o -> inp[0] = a.toDouble(); inp[1] = b.toDouble(); inp[2] = c.toDouble(); t.apply(inp, out); for (i in 0..2) o[i] = out[i].toFloat() }
                    val tmp = ThreadLocal.withInitial { FloatArray(3) }
                    DeviceToRgb(3) { v -> lut.map((v[0] shl 16) or (v[1] shl 8) or v[2], tmp.get()) }
                }
                4 -> {
                    // Quellprofil pro Pixel exakt auswerten; gleiche Farben (häufig in Grafiken) zwischenspeichern
                    val cache = object : LinkedHashMap<Int, Int>(4096, 0.75f, true) {
                        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Int>?) = size > 65536
                    }
                    DeviceToRgb(4) { v ->
                        val key = (v[0] shl 24) or (v[1] shl 16) or (v[2] shl 8) or v[3]
                        cache.getOrPut(key) { for (c in 0..3) inp[c] = v[c] / 255.0; t.deviceToRgb(inp) }
                    }
                }
                else -> throw IccException("${source.channels} Kanäle werden nicht unterstützt")
            }
        }

        /** PSD-Lab (L 0..255, a/b mit 128 = neutral) ohne eigenes Profil → Zielprofil über den PCS. */
        fun lab(target: IccProfile): DeviceToRgb {
            val enc = RgbEncoder(target)
            val xyz = DoubleArray(3)
            return DeviceToRgb(3) { v ->
                Pcs.labToXyz(v[0] / 255.0 * 100.0, v[1] - 128.0, v[2] - 128.0, xyz)
                enc.encode(xyz[0], xyz[1], xyz[2])
            }
        }
    }
}
