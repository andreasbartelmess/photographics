package de.photographics.core.psd

import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.RasterLayer
import de.photographics.core.doc.SmartLayer
import de.photographics.core.doc.TextLayer
import de.photographics.core.image.IntRect
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Schreibt ein Dokument als ebenenbasierte 8-Bit-RGB-PSD (Version 1, RLE-komprimiert).
 *
 * Enthalten sind Ebenen inkl. Gruppen, Masken, Mischmodi, Deckkraft/Fläche, Schnittmasken,
 * Unicode-Namen und ein zusammengeführtes Bild ("Kompatibilität maximieren"), sodass auch
 * Programme ohne Ebenen-Unterstützung die Datei anzeigen können. Beim Import erhaltene
 * Zusatzdaten (Effekte, Textdaten, ICC-Profil, ...) werden mitgeschrieben.
 */
object PsdWriter {
    private const val GROUP_END_NAME = "</Layer group>"
    private val GENERATED_KEYS = setOf("luni", "lsct", "lsdk", "lyid", "iOpa", MASK_LINK_KEY)
    /** Eigener Block: Ebenenmaske nicht mit der Ebene verbunden (Inhalt: ein Byte 0). */
    internal const val MASK_LINK_KEY = "pgML"

    private sealed class Entry {
        class Pixel(val layer: RasterLayer) : Entry()
        class Adjust(val layer: AdjustmentLayer) : Entry()
        class GroupStart(val group: GroupLayer) : Entry()
        class GroupEnd(val group: GroupLayer) : Entry()
    }

    /**
     * Zwischenspeicher für RLE-Daten eines Kanals: im Speicher oder, wenn ein Verzeichnis für
     * temporäre Dateien angegeben ist, auf dem Datenträger (große Dokumente ohne Speicherspitzen).
     */
    internal class Spool(tmpDir: File?) : Closeable {
        private val file = tmpDir?.let { File.createTempFile("psd", ".rle", it) }
        private val mem = if (file == null) ByteArrayOutputStream() else null
        private val out: OutputStream = file?.let { BufferedOutputStream(FileOutputStream(it), 1 shl 16) } ?: mem!!
        var size = 0L; private set
        fun write(b: ByteArrayOutputStream) { b.writeTo(out); size += b.size() }
        fun copyTo(dst: OutputStream) {
            if (file != null) { out.close(); file.inputStream().use { it.copyTo(dst, 1 shl 16) } } else mem!!.writeTo(dst)
        }
        override fun close() { if (file != null) { runCatching { out.close() }; file.delete() } }
    }

    /** RLE-kodierter Kanal: Zeilenlängen im Speicher, Zeilendaten im [Spool]; ohne [counts] leer. */
    internal class Channel(val id: Int, val counts: IntArray?, val spool: Spool?) {
        val length: Long get() = if (counts == null) 2 else 2 + 2L * counts.size + spool!!.size
        fun writeTo(out: OutputStream) {
            if (counts == null) { out.write(0); out.write(0); return }
            val head = BinaryWriter(); head.u16(1); for (c in counts) head.u16(c)
            out.write(head.toByteArray()); spool!!.copyTo(out)
        }
    }

    /** Pixel pro Streifen beim Kodieren. */
    private const val BAND_PIXELS = 1 shl 20

    fun write(doc: Document): ByteArray = ByteArrayOutputStream().also { write(doc, it, null) }.toByteArray()

    /**
     * Schreibt direkt in [output]. Mit [tmpDir] werden die Kanaldaten in temporären Dateien
     * gesammelt; dann braucht auch ein sehr großes Dokument nur wenige MB zusätzlichen Speicher.
     */
    fun write(doc: Document, output: OutputStream, tmpDir: File?) {
        tmpDir?.mkdirs()
        val entries = mutableListOf<Entry>()
        flatten(doc.root, entries)
        val ids = assignIds(entries)
        val spools = ArrayList<Spool>()
        try {
            writeStreaming(doc, entries, ids, output, tmpDir, spools)
        } finally {
            spools.forEach { it.close() }
        }
    }

    /** Ebenendaten für Photoshops TIFF-Ebenenblock: [size] Bytes, die [writeTo] schreibt; danach [close]. */
    internal class LayerInfo(val size: Long, private val records: ByteArray, private val channels: List<List<Channel>>, private val spools: List<Spool>) : Closeable {
        fun writeTo(out: OutputStream) {
            out.write(records)
            for (chs in channels) for (c in chs) c.writeTo(out)
        }
        override fun close() = spools.forEach { it.close() }
    }

    /**
     * Ebeneninformation wie im PSD-Abschnitt „Layer info“ (Anzahl, Datensätze, Kanaldaten), ohne
     * Längenfeld – Inhalt des Blocks „Layr“ in TIFF-Dateien. [compositeHasAlpha] wie bei PSD: der
     * erste Zusatzkanal des Gesamtbilds ist die Transparenz. Null, wenn das Dokument keine Ebenen hat.
     */
    internal fun layerInfo(doc: Document, tmpDir: File?, compositeHasAlpha: Boolean): LayerInfo? {
        tmpDir?.mkdirs()
        val entries = mutableListOf<Entry>()
        flatten(doc.root, entries)
        if (entries.isEmpty()) return null
        val ids = assignIds(entries)
        val spools = ArrayList<Spool>()
        try {
            val channelData = entries.map { encodeEntry(it) { Spool(tmpDir).also { s -> spools += s } } }
            val rec = BinaryWriter()
            rec.u16(if (compositeHasAlpha) (-entries.size) and 0xFFFF else entries.size)
            for ((i, e) in entries.withIndex()) writeRecord(rec, e, channelData[i], ids[i], doc)
            val recBytes = rec.toByteArray()
            return LayerInfo(recBytes.size + channelData.sumOf { chs -> chs.sumOf { it.length } }, recBytes, channelData, spools)
        } catch (t: Throwable) {
            spools.forEach { it.close() }
            throw t
        }
    }

    private fun writeStreaming(doc: Document, entries: List<Entry>, ids: IntArray, output: OutputStream, tmpDir: File?, spools: MutableList<Spool>) {
        val newSpool = { Spool(tmpDir).also { spools += it } }
        val merged = encodeMerged(doc, newSpool)
        val hasAlpha = merged.hasAlpha
        val w = BinaryWriter()

        // Header
        w.ascii("8BPS"); w.u16(1); w.zeros(6)
        w.u16(if (hasAlpha) 4 else 3)
        w.i32(doc.height); w.i32(doc.width)
        w.u16(8); w.u16(PsdColorMode.RGB)

        // Farbmodusdaten
        w.i32(0)

        // Bildressourcen
        val resMark = w.beginLength()
        writeResource(w, 1005, resolutionInfo(doc.dpi))
        val activeIndex = entries.indexOfFirst { e ->
            (e is Entry.Pixel && e.layer === doc.activeLayer) || (e is Entry.Adjust && e.layer === doc.activeLayer) ||
                (e is Entry.GroupStart && e.group === doc.activeLayer)
        }
        if (activeIndex >= 0) writeResource(w, 1024, byteArrayOf((activeIndex ushr 8).toByte(), activeIndex.toByte()))
        // Farbprofil des Dokuments einbetten (Photoshop: „ICC-Profil einbetten“)
        writeResource(w, 1039, doc.colorProfile.bytes)
        if (doc.guides.isNotEmpty()) writeResource(w, 1032, BinaryWriter().also { b ->
            b.i32(1); b.i32(576); b.i32(576); b.i32(doc.guides.size)
            for (g in doc.guides) { b.i32(kotlin.math.round(g.position * 32).toInt()); b.u8(if (g.vertical) 0 else 1) }
        }.toByteArray())
        for (r in doc.preservedResources) if (r.id != 1039) writeResource(w, r.id, r.data, r.name)
        w.endLength(resMark)

        // Ebenen- und Maskeninformationen: Datensätze im Speicher, Kanaldaten gestreamt
        val channelData = entries.map { encodeEntry(it, newSpool) }
        val rec = BinaryWriter()
        if (entries.isNotEmpty()) {
            rec.u16(if (hasAlpha) (-entries.size) and 0xFFFF else entries.size)
            for ((i, e) in entries.withIndex()) writeRecord(rec, e, channelData[i], ids[i], doc)
        }
        val recBytes = rec.toByteArray()
        val rawLi = if (entries.isEmpty()) 0L else recBytes.size + channelData.sumOf { chs -> chs.sumOf { it.length } }
        val liPad = ((4 - rawLi % 4) % 4).toInt()
        val tail = BinaryWriter()
        tail.i32(0) // globale Maskeninfo
        for (b in doc.preservedGlobalBlocks) {
            tail.ascii(b.signature); tail.ascii(b.key); tail.i32(b.data.size); tail.bytes(b.data)
            var n = b.data.size; while (n % 4 != 0) { tail.u8(0); n++ }
        }
        val tailBytes = tail.toByteArray()
        val lmLen = 4 + rawLi + liPad + tailBytes.size
        if (lmLen > 0xFFFFFFFFL) throw PsdFormatException("Dokument zu groß für PSD (über 4 GB Ebenendaten)")
        w.i32(lmLen.toInt()); w.i32((rawLi + liPad).toInt())
        output.write(w.toByteArray())
        if (entries.isNotEmpty()) {
            output.write(recBytes)
            for (chs in channelData) for (c in chs) c.writeTo(output)
        }
        repeat(liPad) { output.write(0) }
        output.write(tailBytes)

        // Zusammengeführtes Bild
        val head = BinaryWriter()
        head.u16(1)
        val nch = if (hasAlpha) 4 else 3
        for (c in 0 until nch) for (v in merged.counts[c]) head.u16(v)
        output.write(head.toByteArray())
        for (c in 0 until nch) merged.spools[c].copyTo(output)
        output.flush()
    }

    private fun flatten(group: GroupLayer, out: MutableList<Entry>) {
        for (c in group.children) {
            when (c) {
                is RasterLayer -> out += Entry.Pixel(c)
                is AdjustmentLayer -> out += Entry.Adjust(c)
                is GroupLayer -> {
                    out += Entry.GroupEnd(c)
                    flatten(c, out)
                    out += Entry.GroupStart(c)
                }
            }
        }
    }

    private fun layerOf(e: Entry): Layer? = when (e) {
        is Entry.Pixel -> e.layer
        is Entry.Adjust -> e.layer
        is Entry.GroupStart -> e.group
        is Entry.GroupEnd -> null
    }

    private fun assignIds(entries: List<Entry>): IntArray {
        val used = HashSet<Int>()
        val ids = IntArray(entries.size)
        var next = entries.mapNotNull { layerOf(it)?.psdId }.maxOrNull()?.plus(1)?.coerceAtLeast(1) ?: 1
        for ((i, e) in entries.withIndex()) {
            val l = layerOf(e)
            val want = l?.psdId ?: 0
            ids[i] = if (want > 0 && used.add(want)) want else next++.also { used += it }
            if (l != null) l.psdId = ids[i]
        }
        return ids
    }

    // ---------------------------------------------------------------- Ressourcen

    private fun resolutionInfo(dpi: Float): ByteArray {
        val b = BinaryWriter()
        val fixed = (dpi * 65536f).roundToInt()
        b.i32(fixed); b.u16(1); b.u16(1)
        b.i32(fixed); b.u16(1); b.u16(1)
        return b.toByteArray()
    }

    private fun writeResource(w: BinaryWriter, id: Int, data: ByteArray, name: String = "") {
        w.ascii("8BIM"); w.u16(id); w.pascal(name, 2)
        w.i32(data.size); w.bytes(data)
        if (data.size % 2 == 1) w.u8(0)
    }

    // ---------------------------------------------------------------- Ebenen

    private fun entryRect(e: Entry): IntRect =
        if (e is Entry.Pixel) e.layer.raster.contentBounds() else IntRect.EMPTY

    private fun maskRect(l: Layer?): IntRect = l?.mask?.grid?.contentBounds(alphaOnly = false) ?: IntRect.EMPTY

    private fun encodeEntry(e: Entry, newSpool: () -> Spool): List<Channel> {
        val rect = entryRect(e)
        val out = mutableListOf<Channel>()
        if (e is Entry.Pixel && !rect.isEmpty) {
            out += encodeGrid(e.layer.raster, rect, intArrayOf(-1, 0, 1, 2), intArrayOf(24, 16, 8, 0), newSpool)
        } else {
            for (id in intArrayOf(-1, 0, 1, 2)) out += Channel(id, null, null)
        }
        val layer = layerOf(e)
        if (layer?.mask != null) {
            val mr = maskRect(layer)
            out += if (mr.isEmpty) Channel(-2, null, null)
                else encodeGrid(layer.mask!!.grid, mr, intArrayOf(-2), intArrayOf(0), newSpool).single()
        }
        return out
    }

    /** Kodiert die Kanäle (Bitverschiebung je Kanal) eines Rasterbereichs streifenweise. */
    private fun encodeGrid(grid: de.photographics.core.image.TileGrid, rect: IntRect, ids: IntArray, shifts: IntArray, newSpool: () -> Spool): List<Channel> {
        val w = rect.width; val h = rect.height
        val counts = Array(ids.size) { IntArray(h) }
        val sp = Array(ids.size) { newSpool() }
        val bandH = maxOf(1, minOf(h, BAND_PIXELS / w))
        val px = IntArray(w * bandH)
        val row = ByteArray(w)
        val enc = ByteArrayOutputStream(w + w / 64 + 4)
        var y0 = 0
        while (y0 < h) {
            val y1 = minOf(h, y0 + bandH)
            grid.readRect(IntRect(rect.left, rect.top + y0, rect.right, rect.top + y1), px)
            for (c in ids.indices) {
                val s = shifts[c]
                for (y in y0 until y1) {
                    val o = (y - y0) * w
                    for (x in 0 until w) row[x] = (px[o + x] ushr s).toByte()
                    enc.reset(); PackBits.encode(row, 0, w, enc)
                    counts[c][y] = enc.size(); sp[c].write(enc)
                }
            }
            y0 = y1
        }
        return ids.indices.map { Channel(ids[it], counts[it], sp[it]) }
    }

    private fun writeRecord(w: BinaryWriter, e: Entry, channels: List<Channel>, id: Int, doc: Document) {
        val rect = entryRect(e)
        val layer = layerOf(e)
        w.i32(rect.top); w.i32(rect.left); w.i32(rect.bottom); w.i32(rect.right)
        w.u16(channels.size)
        for (c in channels) { w.u16(c.id and 0xFFFF); w.i32(c.length.toInt()) }

        val blendKey = when (e) {
            is Entry.GroupEnd -> BlendMode.NORMAL.psdKey
            else -> layer!!.blendMode.let { if (it == BlendMode.PASS_THROUGH && e is Entry.Pixel) BlendMode.NORMAL else it }.psdKey
        }
        w.ascii("8BIM"); w.ascii(blendKey)
        w.u8(layer?.let { (it.opacity * 255f).roundToInt().coerceIn(0, 255) } ?: 255)
        w.u8(if (layer?.clipped == true) 1 else 0)
        var flags = 0x08
        if (layer?.lockTransparency == true) flags = flags or 0x01
        if (layer != null && !layer.visible) flags = flags or 0x02
        if (e !is Entry.Pixel) flags = flags or 0x10
        w.u8(flags)
        w.u8(0)

        val extraMark = w.beginLength()
        // Maskendaten
        val mask = layer?.mask
        if (mask != null) {
            val mr = maskRect(layer)
            w.i32(20)
            w.i32(mr.top); w.i32(mr.left); w.i32(mr.bottom); w.i32(mr.right)
            w.u8(mask.defaultColor)
            w.u8(if (mask.enabled) 0 else 0x02)
            w.zeros(2)
        } else {
            w.i32(0)
        }
        w.i32(0) // Fülloptionen-Bereiche
        val name = if (e is Entry.GroupEnd) GROUP_END_NAME else layer!!.name
        w.pascal(name, 4)

        // Zusatzinformationen
        block(w, "luni") { it.unicode(name) }
        block(w, "lyid") { it.i32(id) }
        if (layer != null) block(w, "iOpa") { it.u8((layer.fillOpacity * 255f).roundToInt().coerceIn(0, 255)); it.zeros(3) }
        if (layer?.mask?.linked == false) block(w, MASK_LINK_KEY) { it.u8(0); it.zeros(3) }
        when (e) {
            is Entry.GroupStart -> block(w, "lsct") {
                it.i32(if (e.group.expanded) 1 else 2); it.ascii("8BIM"); it.ascii(e.group.blendMode.psdKey)
            }
            is Entry.GroupEnd -> block(w, "lsct") { it.i32(3); it.ascii("8BIM"); it.ascii(BlendMode.NORMAL.psdKey) }
            else -> {}
        }
        // Typ-spezifische Daten
        val skip = HashSet(GENERATED_KEYS)
        when (layer) {
            is AdjustmentLayer -> {
                skip += PsdAdjustments.KEYS
                for ((key, data) in PsdAdjustments.write(layer.adjustment)) block(w, key) { it.bytes(data) }
            }
            is TextLayer -> if (!layer.unchangedFromPsd) {
                // Geänderter/neuer Text: Photoshop-Textdaten verwerfen, eigene Daten schreiben
                skip += "TySh"; skip += "tySh"
                block(w, PsdTextSmart.TEXT_KEY) { it.bytes(PsdTextSmart.writeText(layer.style, layer.transform)) }
            }
            is SmartLayer -> {
                skip += setOf("SoLd", "PlLd", "SoLE")
                block(w, PsdTextSmart.SMART_KEY) { it.bytes(PsdTextSmart.writeSmart(layer.source, layer.transform, layer.warp)) }
            }
            else -> {}
        }
        if (layer != null) for (b in layer.preservedBlocks) {
            if (b.key in skip) continue
            w.ascii(b.signature); w.ascii(b.key); w.i32(b.data.size); w.bytes(b.data)
            if (b.data.size % 2 == 1) w.u8(0)
        }
        w.endLength(extraMark, pad = 2)
    }

    private inline fun block(w: BinaryWriter, key: String, body: (BinaryWriter) -> Unit) {
        val b = BinaryWriter()
        body(b)
        val data = b.toByteArray()
        w.ascii("8BIM"); w.ascii(key)
        val padded = (data.size + 1) and 1.inv()
        w.i32(padded); w.bytes(data); w.zeros(padded - data.size)
    }

    // ---------------------------------------------------------------- zusammengeführtes Bild

    private class Merged(val hasAlpha: Boolean, val counts: Array<IntArray>, val spools: Array<Spool>)

    /** Zusammengeführtes Bild streifenweise rendern und kodieren (R, G, B, A). */
    private fun encodeMerged(doc: Document, newSpool: () -> Spool): Merged {
        val width = doc.width; val height = doc.height
        val shifts = intArrayOf(16, 8, 0, 24)
        val counts = Array(4) { IntArray(height) }
        val sp = Array(4) { newSpool() }
        val row = ByteArray(width)
        val enc = ByteArrayOutputStream(width + width / 64 + 4)
        var hasAlpha = false
        val bandH = maxOf(1, minOf(height, BAND_PIXELS / width))
        var y0 = 0
        while (y0 < height) {
            val y1 = minOf(height, y0 + bandH)
            val px = Compositor.renderArgb(doc, IntRect(0, y0, width, y1))
            if (!hasAlpha) hasAlpha = px.any { (it ushr 24) != 255 }
            for (c in 0 until 4) {
                val s = shifts[c]
                for (y in y0 until y1) {
                    val o = (y - y0) * width
                    for (x in 0 until width) row[x] = (px[o + x] ushr s).toByte()
                    enc.reset(); PackBits.encode(row, 0, width, enc)
                    counts[c][y] = enc.size(); sp[c].write(enc)
                }
            }
            y0 = y1
        }
        return Merged(hasAlpha, counts, sp)
    }
}
