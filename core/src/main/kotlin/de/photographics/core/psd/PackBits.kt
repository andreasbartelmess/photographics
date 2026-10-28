package de.photographics.core.psd

import java.io.ByteArrayOutputStream

/** PackBits-RLE, wie es Photoshop für Kanaldaten verwendet. */
object PackBits {
    /** Dekodiert genau [outLen] Bytes ab src[off] (max. [inLen] Eingabebytes) nach dst[dstOff]. */
    /** Wie [decode], liest aber aus einem [BinaryReader] ab absolutem Offset [off]. */
    fun decode(src: BinaryReader, off: Int, inLen: Int, dst: ByteArray, dstOff: Int, outLen: Int) {
        val n = inLen.coerceIn(0, src.limit - off)
        val tmp = scratch.get().let { if (it.size >= n) it else ByteArray(n).also(scratch::set) }
        src.copy(off, tmp, 0, n)
        decode(tmp, 0, n, dst, dstOff, outLen)
    }

    private val scratch = ThreadLocal.withInitial { ByteArray(1 shl 16) }

    fun decode(src: ByteArray, off: Int, inLen: Int, dst: ByteArray, dstOff: Int, outLen: Int) {
        var i = off
        val end = (off + inLen).coerceAtMost(src.size)
        var o = dstOff
        val oEnd = dstOff + outLen
        while (i < end && o < oEnd) {
            val n = src[i++].toInt()
            if (n >= 0) {
                val cnt = minOf(n + 1, oEnd - o, end - i)
                System.arraycopy(src, i, dst, o, cnt)
                i += n + 1; o += cnt
            } else if (n != -128) {
                if (i >= end) break
                val b = src[i++]
                val cnt = minOf(-n + 1, oEnd - o)
                dst.fill(b, o, o + cnt)
                o += cnt
            }
        }
    }

    fun encode(src: ByteArray, off: Int, len: Int, out: ByteArrayOutputStream) {
        var i = off
        val end = off + len
        while (i < end) {
            // Wiederholung suchen
            var run = 1
            while (i + run < end && run < 128 && src[i + run] == src[i]) run++
            if (run >= 2) {
                out.write(1 - run)
                out.write(src[i].toInt())
                i += run
                continue
            }
            // Literalfolge bis zur nächsten Wiederholung (>= 2 gleiche Bytes)
            val start = i
            var lit = 0
            while (i < end && lit < 128) {
                if (i + 1 < end && src[i] == src[i + 1]) break
                i++; lit++
            }
            out.write(lit - 1)
            out.write(src, start, lit)
        }
    }
}
