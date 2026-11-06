package de.photographics.core.psd

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Eigene Ebenendaten (Text, Smartobjekt, Smartobjekt-Inhalt, Smartfilter, Maskenverbindung) im
 * Ebenen-XMP statt in eigenen Zusatzblöcken.
 *
 * Photoshop meldet bei unbekannten Zusatzblöcken („pgSO“ …), dass es Daten verwerfen wird. Die
 * Ebenen-Metadaten „shmd“ → „cust“ → Descriptor „metadata“ → TEXT „layerXMP“ schreibt Photoshop
 * selbst (Aufbau wie in Photoshop-2026-Dateien) und öffnet sie ohne Rückfrage. Jeder eigene Block
 * steht als Base64 in einem Element `pg:<name>`; große Blöcke deflate-komprimiert mit Präfix „z:“
 * (kommt in Base64 nicht vor).
 */
object PsdLayerXmp {
    const val NS = "http://photographics.andreasbartelmess.de/ns/1.0/"
    const val SHMD = "shmd"

    /** Eigener Blockschlüssel → XMP-Elementname. */
    val OWN_KEYS: Map<String, String> = linkedMapOf(
        PsdTextSmart.TEXT_KEY to "text",
        PsdTextSmart.SMART_KEY to "smartObject",
        PsdTextSmart.CONTENTS_KEY to "contents",
        PsdSmartFilters.KEY to "smartFilters",
        PsdWriter.MASK_LINK_KEY to "maskUnlinked",
    )
    private val BY_NAME = OWN_KEYS.entries.associate { (k, v) -> v to k }
    private const val COMPRESS_FROM = 512
    private val ELEMENT = Regex("<pg:(\\w+)>([^<]*)</pg:\\1>")

    private class Item(val signature: String, val key: String, val copy: Int, val data: ByteArray)

    /**
     * Neuer Inhalt des „shmd“-Blocks: vorhandene Einträge ([existing], z. B. aus Photoshop) bleiben,
     * unser bisheriges layerXMP wird durch [own] ersetzt (fremdes layerXMP bleibt, wenn [own] leer ist).
     * Null, wenn kein Block nötig ist.
     */
    fun shmd(existing: ByteArray?, own: Map<String, ByteArray>): ByteArray? {
        val items = existing?.let { runCatching { parse(it) }.getOrNull() }?.toMutableList() ?: mutableListOf()
        if (existing != null && items.isEmpty() && own.isEmpty()) return existing
        val xmp = if (own.isEmpty()) null else encode(own)
        val ci = items.indexOfFirst { it.key == "cust" }
        val desc = if (ci >= 0) PsdDescriptor.readVersioned(items[ci].data) else null
        if (desc == null && xmp == null) return existing
        val d = desc ?: PsdDescriptor("metadata").also { it["layerTime"] = System.currentTimeMillis() / 1000.0 }
        val old = d.string("layerXMP")
        if (xmp != null) d["layerXMP"] = xmp
        else if (old != null && old.contains(NS)) d.items.remove("layerXMP")
        else if (desc != null) return existing
        val cust = Item("8BIM", "cust", 0, writeCust(d))
        if (ci >= 0) items[ci] = cust else items.add(cust)
        val w = BinaryWriter()
        w.i32(items.size)
        for (it in items) {
            w.ascii(it.signature); w.ascii(it.key); w.u8(it.copy); w.zeros(3)
            val padded = (it.data.size + 1) and 1.inv()
            w.i32(padded); w.bytes(it.data); w.zeros(padded - it.data.size)
        }
        return w.toByteArray()
    }

    /** Eigene Blöcke aus einem „shmd“-Block (leer, wenn keine darin stehen). */
    fun read(shmd: ByteArray): Map<String, ByteArray> {
        val cust = runCatching { parse(shmd) }.getOrNull()?.firstOrNull { it.key == "cust" } ?: return emptyMap()
        val xmp = PsdDescriptor.readVersioned(cust.data)?.string("layerXMP") ?: return emptyMap()
        return decode(xmp)
    }

    fun encode(own: Map<String, ByteArray>): String = buildString {
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"photographics\">")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\" xmlns:pg=\"").append(NS).append("\">")
        for ((key, data) in own) {
            val name = OWN_KEYS[key] ?: continue
            append("<pg:").append(name).append('>')
            if (data.size >= COMPRESS_FROM) append("z:").append(Base64.getEncoder().encodeToString(deflate(data)))
            else append(Base64.getEncoder().encodeToString(data))
            append("</pg:").append(name).append('>')
        }
        append("</rdf:Description></rdf:RDF></x:xmpmeta>")
    }

    fun decode(xmp: String): Map<String, ByteArray> {
        if (!xmp.contains(NS)) return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        for (m in ELEMENT.findAll(xmp)) {
            val key = BY_NAME[m.groupValues[1]] ?: continue
            val v = m.groupValues[2].trim()
            runCatching {
                out[key] = if (v.startsWith("z:")) inflate(Base64.getMimeDecoder().decode(v.substring(2)))
                else Base64.getMimeDecoder().decode(v)
            }
        }
        return out
    }

    private fun parse(data: ByteArray): List<Item> {
        val r = BinaryReader(data)
        val n = r.i32()
        if (n < 0 || n > 10_000) throw PsdFormatException("shmd")
        return List(n) {
            val sig = r.ascii(4); val key = r.ascii(4)
            val copy = r.u8(); r.skip(3)
            val len = r.i32()
            Item(sig, key, copy, r.bytes(len))
        }
    }

    /** Wie Photoshop: Version 16, Name als Unicode mit Null, Klasse „metadata“. */
    private fun writeCust(d: PsdDescriptor): ByteArray {
        val w = BinaryWriter(); w.i32(16); PsdDescriptor.write(w, d, terminatedName = true); return w.toByteArray()
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(b.size / 2 + 64); val buf = ByteArray(1 shl 16)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    private fun inflate(b: ByteArray): ByteArray {
        val i = Inflater(); i.setInput(b)
        val out = ByteArrayOutputStream(b.size * 3); val buf = ByteArray(1 shl 16)
        try {
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) throw PsdFormatException("layerXMP")
                out.write(buf, 0, n)
            }
        } finally { i.end() }
        return out.toByteArray()
    }
}
