package de.photographics.core.psd

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Big-Endian-Leser über einem [ByteBuffer]. Für große Dateien kann das ein speicherabgebildeter
 * Puffer sein (`FileChannel.map`) – dann liegt die Datei nicht im Java-Heap.
 * Mit [le] Little-Endian: so speichert Photoshop seine Ebenendaten in TIFFs mit Intel-Bytefolge
 * (Zahlen und 4-Zeichen-Kennungen byteweise vertauscht, z. B. „MIB8“ statt „8BIM“).
 */
class BinaryReader(val buf: ByteBuffer, var pos: Int = 0, val limit: Int = buf.limit(), val le: Boolean = false) {
    constructor(bytes: ByteArray, le: Boolean = false) : this(ByteBuffer.wrap(bytes), 0, bytes.size, le)

    val remaining get() = limit - pos

    private fun need(n: Long) {
        if (n < 0 || pos + n > limit) throw PsdFormatException("Unerwartetes Dateiende bei Offset $pos (benötigt $n Bytes)")
    }

    private fun b(i: Int) = buf.get(i).toInt()

    fun u8(): Int { need(1); return b(pos++) and 0xFF }
    fun i8(): Int { need(1); return b(pos++) }
    fun u16(): Int {
        need(2)
        val v = if (le) ((b(pos + 1) and 0xFF) shl 8) or (b(pos) and 0xFF) else ((b(pos) and 0xFF) shl 8) or (b(pos + 1) and 0xFF)
        pos += 2; return v
    }
    fun i16(): Int = u16().toShort().toInt()
    fun i32(): Int {
        need(4)
        val v = if (le) ((b(pos + 3) and 0xFF) shl 24) or ((b(pos + 2) and 0xFF) shl 16) or ((b(pos + 1) and 0xFF) shl 8) or (b(pos) and 0xFF)
            else ((b(pos) and 0xFF) shl 24) or ((b(pos + 1) and 0xFF) shl 16) or ((b(pos + 2) and 0xFF) shl 8) or (b(pos + 3) and 0xFF)
        pos += 4
        return v
    }
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun i64(): Long = if (le) u32() or (u32() shl 32) else (u32() shl 32) or u32()
    fun f32(): Float = Float.fromBits(i32())
    fun f64(): Double = Double.fromBits(i64())

    /** Länge mit 4 oder 8 Byte (PSB). */
    fun len(big: Boolean): Long = if (big) i64() else u32()

    /** Kopiert [n] Bytes ab absolutem Offset [off] nach [dst] (ohne die Leseposition zu ändern). */
    fun copy(off: Int, dst: ByteArray, dstOff: Int, n: Int) {
        val d = buf.duplicate(); d.position(off); d.get(dst, dstOff, n)
    }

    fun bytes(n: Int): ByteArray { need(n.toLong()); val r = ByteArray(n); copy(pos, r, 0, n); pos += n; return r }
    fun ascii(n: Int): String = String(bytes(n), Charsets.ISO_8859_1)

    /** 4-Zeichen-Kennung (Signatur, Schlüssel); bei Little-Endian rückwärts gespeichert. */
    fun sig(): String = ascii(4).let { if (le) it.reversed() else it }
    fun peekSig(): String? = peekAscii(4)?.let { if (le) it.reversed() else it }
    fun peekAscii(n: Int): String? = if (pos + n <= limit) String(ByteArray(n).also { copy(pos, it, 0, n) }, Charsets.ISO_8859_1) else null

    fun skip(n: Long) { need(n); pos += n.toInt() }
    fun seek(p: Int) { if (p < 0 || p > limit) throw PsdFormatException("Ungültiger Offset $p"); pos = p }

    /** Pascal-String, aufgefüllt auf ein Vielfaches von [pad] (inkl. Längenbyte). */
    fun pascal(pad: Int): String {
        val n = u8()
        val m = n.coerceAtMost(remaining)
        val s = String(ByteArray(m).also { copy(pos, it, 0, m) }, Charsets.ISO_8859_1)
        pos += n
        var total = n + 1
        while (total % pad != 0) { pos++; total++ }
        return s
    }

    fun unicode(): String {
        val n = i32()
        if (n < 0 || n.toLong() * 2 > remaining) throw PsdFormatException("Ungültige Unicode-Länge $n")
        val sb = StringBuilder(n)
        repeat(n) { sb.append(u16().toChar()) }
        return sb.toString().trimEnd('\u0000')
    }

    fun sub(length: Int): BinaryReader {
        need(length.toLong())
        return BinaryReader(buf, pos, pos + length, le)
    }
}

/** Big-Endian-Schreiber mit Platzhaltern für nachträglich einzutragende Längen. */
class BinaryWriter {
    private val out = ByteArrayOutputStream(1 shl 20)
    private val patches = mutableListOf<Pair<Int, Int>>()

    val size get() = out.size()

    fun u8(v: Int) = out.write(v and 0xFF)
    fun u16(v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
    fun i32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size) = out.write(b, off, len)
    fun ascii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
    fun zeros(n: Int) = repeat(n) { out.write(0) }

    fun pascal(s: String, pad: Int) {
        val b = s.map { if (it.code in 32..126) it.code.toByte() else '?'.code.toByte() }.take(255).toByteArray()
        u8(b.size); bytes(b)
        var total = b.size + 1
        while (total % pad != 0) { u8(0); total++ }
    }

    fun unicode(s: String) {
        i32(s.length + 1)
        for (ch in s) u16(ch.code)
        u16(0)
    }

    /** Unicode-String ohne abschließende Null (Descriptor-Namen). */
    fun unicodeNoTerm(s: String) {
        i32(s.length)
        for (ch in s) u16(ch.code)
    }

    fun f64(v: Double) { val bits = v.toRawBits(); i32((bits ushr 32).toInt()); i32(bits.toInt()) }
    fun f32(v: Float) = i32(v.toRawBits())

    /** Reserviert 4 Bytes für eine Länge; [endLength] trägt sie später ein. */
    fun beginLength(): Int { val p = size; i32(0); return p }

    /** Trägt die Länge seit [mark] ein, nachdem auf ein Vielfaches von [pad] aufgefüllt wurde. */
    fun endLength(mark: Int, pad: Int = 1) {
        while ((size - mark - 4) % pad != 0) u8(0)
        patches += mark to (size - mark - 4)
    }

    fun toByteArray(): ByteArray {
        val b = out.toByteArray()
        for ((p, v) in patches) {
            b[p] = (v ushr 24).toByte(); b[p + 1] = (v ushr 16).toByte()
            b[p + 2] = (v ushr 8).toByte(); b[p + 3] = v.toByte()
        }
        return b
    }
}
