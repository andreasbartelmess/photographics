package de.photographics.core.image

import kotlin.math.roundToInt

/**
 * Gaußscher Weichzeichner wie Photoshop: Photoshop stellt den Radius in 0,1-px-Schritten ein und nimmt
 * bis 2 px einen gerundeten Ganzzahl-Kern (Summe 256), darüber gestufte Kerne. Bis 4 px verwenden wir
 * Photoshops Kerne direkt (in Photoshop 2026 gemessen: Antwort auf einen einzelnen Punkt), darüber die
 * Box-Näherung aus [Blur], die dort mit Photoshop übereinstimmt.
 */
object PsGauss {
    /** Halbe Kerne (Mitte zuerst) für Radius 0,1 … 4,0 px. */
    private val KERNELS: Array<FloatArray> = arrayOf(
        floatArrayOf(1.00000f),
        floatArrayOf(0.92969f, 0.03516f),
        floatArrayOf(0.71875f, 0.14063f),
        floatArrayOf(0.61718f, 0.19141f),
        floatArrayOf(0.57031f, 0.21485f),
        floatArrayOf(0.52343f, 0.22657f, 0.01172f),
        floatArrayOf(0.47656f, 0.23438f, 0.02734f),
        floatArrayOf(0.44531f, 0.23438f, 0.04297f),
        floatArrayOf(0.41406f, 0.23438f, 0.05469f, 0.00391f),
        floatArrayOf(0.37500f, 0.23438f, 0.07031f, 0.00781f),
        floatArrayOf(0.35156f, 0.23047f, 0.08203f, 0.01172f),
        floatArrayOf(0.32812f, 0.22266f, 0.09375f, 0.01953f),
        floatArrayOf(0.29688f, 0.21875f, 0.10156f, 0.02734f, 0.00391f),
        floatArrayOf(0.29688f, 0.21094f, 0.10547f, 0.03125f, 0.00391f),
        floatArrayOf(0.28125f, 0.20312f, 0.10938f, 0.03906f, 0.00781f),
        floatArrayOf(0.26562f, 0.19531f, 0.11328f, 0.04688f, 0.01172f),
        floatArrayOf(0.25000f, 0.18750f, 0.11719f, 0.05078f, 0.01562f, 0.00391f),
        floatArrayOf(0.23438f, 0.18359f, 0.11719f, 0.05859f, 0.01953f, 0.00391f),
        floatArrayOf(0.22656f, 0.17578f, 0.11719f, 0.06250f, 0.02344f, 0.00781f),
        floatArrayOf(0.22656f, 0.16797f, 0.11719f, 0.06641f, 0.02734f, 0.00781f),
        floatArrayOf(0.19342f, 0.17283f, 0.12346f, 0.06859f, 0.02880f, 0.00824f, 0.00137f),
        floatArrayOf(0.19342f, 0.17283f, 0.12346f, 0.06859f, 0.02880f, 0.00824f, 0.00137f),
        floatArrayOf(0.19342f, 0.17283f, 0.12346f, 0.06859f, 0.02880f, 0.00824f, 0.00137f),
        floatArrayOf(0.16544f, 0.15308f, 0.12016f, 0.07900f, 0.04198f, 0.01730f, 0.00494f, 0.00082f),
        floatArrayOf(0.16544f, 0.15308f, 0.12016f, 0.07900f, 0.04198f, 0.01730f, 0.00494f, 0.00082f),
        floatArrayOf(0.15555f, 0.14518f, 0.11701f, 0.08147f, 0.04742f, 0.02224f, 0.00741f, 0.00150f),
        floatArrayOf(0.14864f, 0.13927f, 0.11455f, 0.08196f, 0.05038f, 0.02569f, 0.01038f, 0.00296f, 0.00049f),
        floatArrayOf(0.14131f, 0.13332f, 0.11199f, 0.08266f, 0.05334f, 0.02936f, 0.01333f, 0.00446f, 0.00088f),
        floatArrayOf(0.14131f, 0.13332f, 0.11199f, 0.08266f, 0.05334f, 0.02936f, 0.01333f, 0.00446f, 0.00088f),
        floatArrayOf(0.13068f, 0.12427f, 0.10669f, 0.08267f, 0.05707f, 0.03467f, 0.01813f, 0.00800f, 0.00266f, 0.00052f),
        floatArrayOf(0.13068f, 0.12427f, 0.10669f, 0.08267f, 0.05707f, 0.03467f, 0.01813f, 0.00800f, 0.00266f, 0.00052f),
        floatArrayOf(0.12640f, 0.12054f, 0.10455f, 0.08215f, 0.05813f, 0.03662f, 0.02026f, 0.00958f, 0.00372f, 0.00107f, 0.00018f),
        floatArrayOf(0.12640f, 0.12054f, 0.10455f, 0.08215f, 0.05813f, 0.03662f, 0.02026f, 0.00958f, 0.00372f, 0.00107f, 0.00018f),
        floatArrayOf(0.11853f, 0.11371f, 0.10028f, 0.08109f, 0.05984f, 0.04010f, 0.02411f, 0.01279f, 0.00586f, 0.00223f, 0.00064f, 0.00009f),
        floatArrayOf(0.11533f, 0.11084f, 0.09836f, 0.08042f, 0.06034f, 0.04135f, 0.02567f, 0.01425f, 0.00696f, 0.00290f, 0.00098f, 0.00024f),
        floatArrayOf(0.11533f, 0.11084f, 0.09836f, 0.08042f, 0.06034f, 0.04135f, 0.02567f, 0.01425f, 0.00696f, 0.00290f, 0.00098f, 0.00024f),
        floatArrayOf(0.10583f, 0.10238f, 0.09271f, 0.07836f, 0.06170f, 0.04501f, 0.03024f, 0.01852f, 0.01019f, 0.00497f, 0.00208f, 0.00070f, 0.00018f),
        floatArrayOf(0.10583f, 0.10238f, 0.09271f, 0.07836f, 0.06170f, 0.04501f, 0.03024f, 0.01852f, 0.01019f, 0.00497f, 0.00208f, 0.00070f, 0.00018f),
        floatArrayOf(0.10269f, 0.09961f, 0.09079f, 0.07755f, 0.06204f, 0.04621f, 0.03183f, 0.02008f, 0.01141f, 0.00571f, 0.00244f, 0.00082f, 0.00015f),
        floatArrayOf(0.09620f, 0.09364f, 0.08628f, 0.07520f, 0.06181f, 0.04783f, 0.03464f, 0.02332f, 0.01447f, 0.00818f, 0.00409f, 0.00174f, 0.00058f, 0.00012f),
    )

    /** Photoshops Kern für [radius] (auf 0,1 px gerundet), null = Box-Näherung verwenden. */
    fun kernel(radius: Float): FloatArray? {
        val i = (radius * 10f).roundToInt()
        return if (i in 1..KERNELS.size) KERNELS[i - 1] else null
    }

    /** Wie [Blur.gaussArgb], aber mit Photoshops Kernen bei kleinen Radien. */
    fun blurArgb(px: IntArray, w: Int, h: Int, radius: Float): IntArray {
        if (radius < 0.05f) return px.copyOf()
        val k = kernel(radius) ?: return Blur.gaussArgb(px, w, h, radius)
        val n = w * h
        val a = FloatArray(n) { (px[it] ushr 24) / 255f }
        val alphaSrc = a.copyOf()
        val tmp = FloatArray(n)
        convolve(a, tmp, w, h, k)
        val out = IntArray(n)
        for (i in 0 until n) out[i] = (a[i] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24
        val c = FloatArray(n)
        for (shift in intArrayOf(16, 8, 0)) {
            for (i in 0 until n) c[i] = ((px[i] shr shift) and 0xFF) * alphaSrc[i]
            convolve(c, tmp, w, h, k)
            for (i in 0 until n) {
                val af = a[i]
                if (af > 1e-5f) out[i] = out[i] or ((c[i] / af + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
        }
        for (i in 0 until n) if ((out[i] ushr 24) == 0) out[i] = 0
        return out
    }

    /** Trennbare Faltung in-place (Rand: letzter Pixel wiederholt). */
    private fun convolve(data: FloatArray, tmp: FloatArray, w: Int, h: Int, k: FloatArray) {
        val r = k.size - 1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = data[row + x] * k[0]
                for (j in 1..r) s += (data[row + (x - j).coerceAtLeast(0)] + data[row + (x + j).coerceAtMost(w - 1)]) * k[j]
                tmp[row + x] = s
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var s = tmp[y * w + x] * k[0]
                for (j in 1..r) s += (tmp[(y - j).coerceAtLeast(0) * w + x] + tmp[(y + j).coerceAtMost(h - 1) * w + x]) * k[j]
                data[y * w + x] = s
            }
        }
    }
}
