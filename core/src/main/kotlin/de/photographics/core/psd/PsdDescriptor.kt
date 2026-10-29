package de.photographics.core.psd

import de.photographics.core.tr

/**
 * Photoshop „Action Descriptor“ – das schlüssel-/wertbasierte Format, in dem Photoshop
 * u. a. Text-, Dynamik-, Schwarzweiß- und Helligkeit/Kontrast-Daten speichert.
 */
class PsdDescriptor(
    val classId: String = "null",
    val name: String = "",
    val items: LinkedHashMap<String, Any?> = LinkedHashMap(),
) {
    operator fun get(key: String): Any? = items[key]
    operator fun set(key: String, value: Any?) { items[key] = value }

    fun int(key: String): Int? = when (val v = items[key]) { is Int -> v; is Long -> v.toInt(); is Double -> v.toInt(); is UnitFloat -> v.value.toInt(); else -> null }
    fun double(key: String): Double? = when (val v = items[key]) { is Double -> v; is Int -> v.toDouble(); is UnitFloat -> v.value; else -> null }
    fun bool(key: String): Boolean? = items[key] as? Boolean
    fun string(key: String): String? = items[key] as? String
    fun descriptor(key: String): PsdDescriptor? = items[key] as? PsdDescriptor

    data class UnitFloat(val unit: String, val value: Double)
    data class EnumValue(val type: String, val value: String)
    data class ClassRef(val name: String, val classId: String)
    class RawData(val bytes: ByteArray)

    companion object {
        /** Liest einen Descriptor (ohne vorangestellte Versionsnummer). */
        fun read(r: BinaryReader): PsdDescriptor {
            val name = r.unicode()
            val classId = readKey(r)
            val count = r.i32()
            val d = PsdDescriptor(classId, name)
            repeat(count) {
                val key = readKey(r)
                val type = r.ascii(4)
                d.items[key] = readValue(r, type)
            }
            return d
        }

        private fun readKey(r: BinaryReader): String {
            val len = r.i32()
            return if (len == 0) r.ascii(4) else r.ascii(len)
        }

        private fun readValue(r: BinaryReader, type: String): Any? = when (type) {
            "Objc", "GlbO" -> read(r)
            "VlLs" -> { val n = r.i32(); List(n) { readValue(r, r.ascii(4)) } }
            "doub" -> r.f64()
            "UntF" -> UnitFloat(r.ascii(4), r.f64())
            "UnFl" -> { val unit = r.ascii(4); val n = r.i32(); UnitFloat(unit, if (n > 0) r.f64().also { r.skip((n - 1) * 8L) } else 0.0) }
            "TEXT" -> r.unicode()
            "enum" -> EnumValue(readKey(r), readKey(r))
            "long" -> r.i32()
            "comp" -> r.i64()
            "bool" -> r.u8() != 0
            "type", "GlbC" -> ClassRef(r.unicode(), readKey(r))
            "alis" -> RawData(r.bytes(r.i32()))
            "tdta" -> RawData(r.bytes(r.i32()))
            "obj " -> { readReference(r); null }
            "ObAr" -> throw PsdFormatException(tr("Objekt-Arrays werden nicht unterstützt"))
            else -> throw PsdFormatException(tr("Unbekannter Descriptor-Typ {0}", type))
        }

        private fun readReference(r: BinaryReader) {
            val n = r.i32()
            repeat(n) {
                when (r.ascii(4)) {
                    "prop" -> { r.unicode(); readKey(r); readKey(r) }
                    "Clss" -> { r.unicode(); readKey(r) }
                    "Enmr" -> { r.unicode(); readKey(r); readKey(r); readKey(r) }
                    "rele" -> { r.unicode(); readKey(r); r.i32() }
                    "Idnt", "indx" -> r.i32()
                    "name" -> { r.unicode(); readKey(r); r.unicode() }
                }
            }
        }

        fun write(w: BinaryWriter, d: PsdDescriptor) {
            w.unicodeNoTerm(d.name)
            writeKey(w, d.classId)
            w.i32(d.items.size)
            for ((k, v) in d.items) {
                writeKey(w, k)
                writeValue(w, v)
            }
        }

        private fun writeKey(w: BinaryWriter, k: String) {
            if (k.length == 4) { w.i32(0); w.ascii(k) } else { w.i32(k.length); w.ascii(k) }
        }

        private fun writeValue(w: BinaryWriter, v: Any?) {
            when (v) {
                is PsdDescriptor -> { w.ascii("Objc"); write(w, v) }
                is List<*> -> { w.ascii("VlLs"); w.i32(v.size); v.forEach { writeValue(w, it) } }
                is Double -> { w.ascii("doub"); w.f64(v) }
                is Float -> { w.ascii("doub"); w.f64(v.toDouble()) }
                is UnitFloat -> { w.ascii("UntF"); w.ascii(v.unit); w.f64(v.value) }
                is String -> { w.ascii("TEXT"); w.unicode(v) }
                is EnumValue -> { w.ascii("enum"); writeKey(w, v.type); writeKey(w, v.value) }
                is Int -> { w.ascii("long"); w.i32(v) }
                is Boolean -> { w.ascii("bool"); w.u8(if (v) 1 else 0) }
                is ClassRef -> { w.ascii("type"); w.unicodeNoTerm(v.name); writeKey(w, v.classId) }
                is RawData -> { w.ascii("tdta"); w.i32(v.bytes.size); w.bytes(v.bytes) }
                else -> throw IllegalArgumentException(tr("Wert kann nicht geschrieben werden: {0}", v))
            }
        }

        /** Block-Inhalt: Versionsnummer (16) + Descriptor. */
        fun readVersioned(data: ByteArray): PsdDescriptor? = runCatching {
            val r = BinaryReader(data)
            r.i32() // Version, üblicherweise 16
            read(r)
        }.getOrNull()

        fun writeVersioned(d: PsdDescriptor): ByteArray {
            val w = BinaryWriter(); w.i32(16); write(w, d); return w.toByteArray()
        }
    }
}
