package de.photographics.core.psd

import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.SmartLayer
import de.photographics.core.doc.TextLayer
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.Layer
import de.photographics.core.doc.LayerKind
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.color.DeviceToRgb
import de.photographics.core.color.IccProfile
import de.photographics.core.color.IccProfiles
import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import java.util.zip.Inflater

/**
 * Liest Photoshop-Dateien (PSD und PSB).
 *
 * Unterstützt: 1/8/16/32 Bit, RGB, Graustufen, Duplex, Indiziert, Bitmap, CMYK, Lab;
 * Kompression Raw/RLE/ZIP/ZIP mit Prädiktion; Ebenen mit Mischmodus, Deckkraft, Fläche,
 * Sichtbarkeit, Schnittmasken, Pixelmasken, Gruppen und Unicode-Namen. Text-, Smartobjekt-,
 * Füll- und Einstellungsebenen werden erkannt; ihre Rohdaten werden für das Speichern erhalten.
 * Intern wird alles nach 8-Bit-sRGB konvertiert.
 */
object PsdReader {

    /** Ressourcen-IDs, die beim Speichern unverändert übernommen werden dürfen. */
    // 1039 (ICC-Profil) wird nicht durchgereicht, sondern als Dokumentprofil gelesen und neu geschrieben
    // 1032 (Raster und Hilfslinien) wird gelesen und beim Speichern neu erzeugt
    private val PRESERVED_RESOURCES = setOf(1028, 1058, 1059, 1060)

    /** Schlüssel, die in PSB eine 8-Byte-Länge haben. */
    private val PSB_LONG_KEYS = setOf(
        "LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD",
    )

    /** Blöcke, die wir selbst interpretieren und beim Speichern neu erzeugen. */
    private val CONSUMED_KEYS = setOf("luni", "lsct", "lsdk", "lyid", "iOpa", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", PsdWriter.MASK_LINK_KEY)

    private val TEXT_KEYS = setOf("TySh", "tySh")
    private val SMART_KEYS = setOf("SoLd", "PlLd", "SoLE")
    private val FILL_KEYS = setOf("SoCo", "GdFl", "PtFl")
    private val ADJUSTMENT_KEYS = setOf(
        "levl", "curv", "brit", "blnc", "hue ", "hue2", "selc", "thrs", "nvrt", "post", "grdm",
        "mixr", "phfl", "expA", "vibA", "clrL", "blwh",
    )

    internal class Header(val version: Int, val channels: Int, val width: Int, val height: Int, val depth: Int, val mode: Int) {
        val big get() = version == 2
        /** Farbumrechnung über ICC-Profile für CMYK, Graustufen und Lab (sonst einfache Formeln). */
        var conv: de.photographics.core.color.DeviceToRgb? = null
    }

    private class ChannelInfo(val id: Int, val length: Long)

    private class LayerRecord(
        val rect: IntRect,
        val channels: List<ChannelInfo>,
        val blendKey: String,
        val opacity: Int,
        val clipping: Int,
        val flags: Int,
        val maskRect: IntRect?,
        val maskDefault: Int,
        val maskFlags: Int,
        val realMaskRect: IntRect?,
        val realMaskDefault: Int,
        val realMaskFlags: Int,
        var name: String,
        val blocks: List<PsdTaggedBlock>,
    ) {
        /** Kanäle; Zeilen werden erst beim Aufbau der Ebene streifenweise gelesen. */
        val channelReaders = HashMap<Int, ChannelReader>()
    }

    /**
     * Liest Zeilen eines Kanals bei Bedarf. RLE und unkomprimierte 8-Bit-Kanäle (der Normalfall)
     * werden zeilenweise direkt aus der Datei dekodiert; andere (ZIP, 16/32 Bit) einmal komplett.
     */
    private class ChannelReader(
        private val src: BinaryReader, private val dataStart: Int, private val end: Int, private val compression: Int,
        val w: Int, val h: Int, private val depth: Int, private val big: Boolean, private val gamma: Boolean,
        private var rowStart: IntArray? = null,
        private var rowLen: IntArray? = null,
    ) {
        private var full: ByteArray? = null
        private val direct = depth == 8 && (compression == 0 || compression == 1)

        init {
            if (direct && compression == 1 && rowStart == null) {
                val r = BinaryReader(src.buf, dataStart, src.limit, src.le)
                val counts = IntArray(h) { if (big) r.i32() else r.u16() }
                var p = r.pos
                rowStart = IntArray(h) { y -> p.also { p += counts[y] } }
                rowLen = counts
            }
        }

        /** Zeilen [y0, y1) als 8-Bit-Werte nach [dst] (Länge w·(y1−y0)). */
        fun rows(y0: Int, y1: Int, dst: ByteArray) {
            if (!direct) {
                val f = full ?: decodeChannel(BinaryReader(src.buf, dataStart, src.limit, src.le), end, compression, w, h, depth, big, gamma).also { full = it }
                System.arraycopy(f, y0 * w, dst, 0, (y1 - y0) * w)
                return
            }
            if (compression == 0) {
                val from = dataStart + y0 * w
                val n = minOf((y1 - y0) * w, maxOf(0, end - from))
                if (n > 0) src.copy(from, dst, 0, n)
                if (n < (y1 - y0) * w) dst.fill(0, maxOf(0, n), (y1 - y0) * w)
                return
            }
            val starts = rowStart!!; val lens = rowLen!!
            for (y in y0 until y1) {
                val st = starts[y]
                PackBits.decode(src, st, minOf(lens[y], maxOf(0, end - st)), dst, (y - y0) * w, w)
            }
        }
    }

    fun read(bytes: ByteArray, workingRgb: IccProfile = IccProfiles.sRGB): Document = read(java.nio.ByteBuffer.wrap(bytes), workingRgb)

    /**
     * Liest aus einem Puffer – z. B. einer speicherabgebildeten Datei, die nicht im Heap liegt.
     * CMYK-, Graustufen- und Lab-Dateien werden in den RGB-Arbeitsfarbraum [workingRgb] umgewandelt,
     * mit dem eingebetteten Profil, falls vorhanden.
     */
    fun read(buffer: java.nio.ByteBuffer, workingRgb: IccProfile = IccProfiles.sRGB): Document = read(buffer, workingRgb, layers = true)

    /**
     * Nur das in der Datei gespeicherte zusammengeführte Bild (Photoshop: „Kompatibilität maximieren“)
     * als einzige Ebene – Referenz für Kompatibilitätstests. [Document.hasRealComposite] sagt, ob
     * Photoshop es wirklich berechnet hat.
     */
    fun readComposite(buffer: java.nio.ByteBuffer, workingRgb: IccProfile = IccProfiles.sRGB): Document = read(buffer, workingRgb, layers = false)

    private fun read(buffer: java.nio.ByteBuffer, workingRgb: IccProfile, layers: Boolean): Document {
        val r = BinaryReader(buffer)
        if (r.ascii(4) != "8BPS") throw PsdFormatException("Keine Photoshop-Datei (Signatur fehlt)")
        val version = r.u16()
        if (version != 1 && version != 2) throw PsdFormatException("Unbekannte PSD-Version $version")
        r.skip(6)
        val channels = r.u16(); val height = r.i32(); val width = r.i32()
        val depth = r.u16(); val mode = r.u16()
        val h = Header(version, channels, width, height, depth, mode)
        if (h.width <= 0 || h.height <= 0) throw PsdFormatException("Ungültige Bildgröße ${h.width}x${h.height}")
        if (h.depth !in setOf(1, 8, 16, 32)) throw PsdFormatException("Nicht unterstützte Farbtiefe ${h.depth} Bit")

        val doc = Document(h.width, h.height)
        if (h.big) doc.importWarnings += "PSB (Großformat) – Zusatzdaten werden beim Speichern als PSD nicht übernommen."
        if (h.depth != 8) doc.importWarnings += "${h.depth}-Bit-Dokument wurde in 8 Bit pro Kanal umgewandelt."

        // Farbmodusdaten (Palette bei indizierten Farben)
        val cmLen = r.u32()
        val colorData = r.bytes(cmLen.toInt())

        // Bildressourcen
        var targetLayerIndex = -1
        var iccData: ByteArray? = null
        val resources = mutableListOf<PsdImageResource>()
        val resLen = r.u32().toInt()
        val resEnd = r.pos + resLen
        while (r.pos + 12 <= resEnd) {
            val sig = r.ascii(4)
            if (sig != "8BIM" && sig != "MeSa" && sig != "AgHg" && sig != "PHUT" && sig != "DCSR") break
            val id = r.u16()
            val name = r.pascal(2)
            val size = r.u32().toInt()
            if (size < 0 || r.pos + size > resEnd) break
            val data = r.bytes(size)
            if (size % 2 == 1 && r.pos < resEnd) r.skip(1)
            when (id) {
                1005 -> if (data.size >= 4) doc.dpi = BinaryReader(data).i32() / 65536f
                1024 -> if (data.size >= 2) targetLayerIndex = BinaryReader(data).u16()
                1039 -> iccData = data
                1032 -> doc.guides = readGuides(data)
                // Versionsinfo: Byte 4 = „hasRealMergedData“
                1057 -> if (data.size >= 5) doc.hasRealComposite = data[4].toInt() != 0
            }
            if (id in PRESERVED_RESOURCES) resources += PsdImageResource(id, name, data)
        }
        r.seek(resEnd)
        doc.preservedResources = resources
        setUpColor(doc, h, iccData, workingRgb)

        // Ebenen- und Maskeninformationen
        val lmLen = r.len(h.big)
        val lmEnd = r.pos + lmLen.toInt()
        var records: List<LayerRecord> = emptyList()
        var mergedHasAlpha = false
        val globalBlocks = mutableListOf<PsdTaggedBlock>()
        if (lmLen > 0) {
            val liLen = r.len(h.big)
            if (liLen > 0) {
                val liEnd = r.pos + liLen.toInt()
                val res = readLayerInfo(r, h, h.depth)
                records = res.first; mergedHasAlpha = res.second
                r.seek(liEnd)
            }
            if (r.pos + 4 <= lmEnd) {
                val gmLen = r.u32()
                r.skip(gmLen.coerceAtMost((lmEnd - r.pos).toLong()))
            }
            for (blk in readTaggedBlocks(r, lmEnd, h.big)) {
                when (blk.key) {
                    "Lr16", "Lr32", "Layr" -> if (records.isEmpty()) {
                        val depth = when (blk.key) { "Lr16" -> 16; "Lr32" -> 32; else -> h.depth }
                        val res = readLayerInfo(BinaryReader(blk.data), h, depth)
                        records = res.first; mergedHasAlpha = res.second
                    }
                    !in CONSUMED_KEYS -> globalBlocks += blk
                }
            }
            r.seek(lmEnd.coerceAtMost(r.limit))
        }
        doc.preservedGlobalBlocks = if (h.big) emptyList() else globalBlocks

        if (records.isNotEmpty() && layers) {
            buildLayers(doc, records, h, colorData, targetLayerIndex)
        }
        if (doc.root.children.isEmpty()) {
            // Keine Ebenen: zusammengeführtes Bild als Hintergrund verwenden
            val bg = PixelLayer("Hintergrund", readMergedGrid(r, h, colorData, mergedHasAlpha))
            doc.root.add(bg)
            doc.activeLayer = bg
        }
        return doc
    }

    /** Ebenen dürfen über die Arbeitsfläche ragen, aber nicht beliebig weit (Schutz vor defekten Dateien). */
    private fun sanityBounds(h: Header) = IntRect(-h.width - 8192, -h.height - 8192, 2 * h.width + 8192, 2 * h.height + 8192)

    private fun baseChannels(mode: Int) = when (mode) {
        PsdColorMode.RGB, PsdColorMode.LAB -> 3
        PsdColorMode.CMYK -> 4
        else -> 1
    }

    // ---------------------------------------------------------------- Ebenen

    private fun readLayerInfo(r: BinaryReader, h: Header, depth: Int): Pair<List<LayerRecord>, Boolean> {
        val rawCount = r.i16()
        val count = kotlin.math.abs(rawCount)
        val list = ArrayList<LayerRecord>(count)
        repeat(count) { list += readLayerRecord(r, h) }
        for (rec in list) {
            for (ch in rec.channels) {
                val start = r.pos
                val end = start + ch.length.toInt()
                if (ch.length >= 2) {
                    val rect = when (ch.id) {
                        -2 -> rec.maskRect ?: IntRect.EMPTY
                        -3 -> rec.realMaskRect ?: IntRect.EMPTY
                        else -> rec.rect
                    }
                    val compression = r.u16()
                    if (!rect.isEmpty) {
                        // Beschädigte Kanäle lassen die Ebene leer statt den Import abzubrechen
                        runCatching {
                            rec.channelReaders[ch.id] = ChannelReader(r, r.pos, end, compression, rect.width, rect.height, depth, h.big,
                                gamma = depth == 32 && ch.id >= 0)
                        }
                    }
                }
                r.seek(end.coerceAtMost(r.limit))
            }
        }
        return list to (rawCount < 0)
    }

    private fun readLayerRecord(r: BinaryReader, h: Header): LayerRecord {
        val top = r.i32(); val left = r.i32(); val bottom = r.i32(); val right = r.i32()
        val nch = r.u16()
        val channels = List(nch) { ChannelInfo(r.i16(), r.len(h.big)) }
        val sig = r.sig()
        if (sig != "8BIM" && sig != "8B64") throw PsdFormatException("Ungültige Signatur im Ebeneneintrag: $sig")
        val blendKey = r.sig()
        val opacity = r.u8(); val clipping = r.u8(); val flags = r.u8(); r.u8()
        val extraLen = r.u32().toInt()
        val extraEnd = r.pos + extraLen

        // Maskendaten
        var maskRect: IntRect? = null; var maskDefault = 255; var maskFlags = 0
        var realRect: IntRect? = null; var realDefault = 255; var realFlags = 0
        val mLen = r.u32().toInt()
        val mEnd = r.pos + mLen
        if (mLen >= 18) {
            val mt = r.i32(); val ml = r.i32(); val mb = r.i32(); val mr = r.i32()
            maskRect = IntRect(ml, mt, mr, mb)
            maskDefault = r.u8(); maskFlags = r.u8()
            if (mLen > 20) {
                if (maskFlags and 0x10 != 0 && r.pos < mEnd) {
                    val params = r.u8()
                    if (params and 1 != 0) r.skip(1)
                    if (params and 2 != 0) r.skip(8)
                    if (params and 4 != 0) r.skip(1)
                    if (params and 8 != 0) r.skip(8)
                }
                if (mEnd - r.pos >= 18) {
                    realFlags = r.u8(); realDefault = r.u8()
                    val rt = r.i32(); val rl = r.i32(); val rb = r.i32(); val rr = r.i32()
                    realRect = IntRect(rl, rt, rr, rb)
                }
            }
        }
        r.seek(mEnd)
        // Fülloptionen-Bereiche überspringen
        val brLen = r.u32()
        r.skip(brLen)
        val name = r.pascal(4)
        val blocks = readTaggedBlocks(r, extraEnd, h.big)
        r.seek(extraEnd)
        return LayerRecord(
            IntRect(left, top, right, bottom), channels, blendKey, opacity, clipping, flags,
            maskRect, maskDefault, maskFlags, realRect, realDefault, realFlags, name, blocks,
        )
    }

    private fun readTaggedBlocks(r: BinaryReader, end: Int, big: Boolean): List<PsdTaggedBlock> {
        val out = mutableListOf<PsdTaggedBlock>()
        while (true) {
            // Auffüllbytes tolerieren (je nach Erzeuger auf 2 oder 4 Byte ausgerichtet)
            var skipped = 0
            while (r.pos + 4 <= end && skipped < 4 && r.peekSig().let { it != "8BIM" && it != "8B64" }) { r.pos++; skipped++ }
            if (r.pos + 12 > end) break
            val sig = r.sig()
            if (sig != "8BIM" && sig != "8B64") break
            val key = r.sig()
            val len = if (big && key in PSB_LONG_KEYS) r.i64() else r.u32()
            if (len < 0 || r.pos + len > end) break
            val data = r.bytes(len.toInt())
            if (!r.le) { out += PsdTaggedBlock(sig, key, data); continue }
            // Little-Endian (TIFF): einfache Blöcke umwandeln, alles andere verwerfen
            val be = if (key in LE_LAYER_INFO_KEYS) data else leBlockToBe(key, data)
            if (be != null) out += PsdTaggedBlock(sig, key, be) else droppedLeKeys += key
        }
        return out
    }

    /**
     * Ebenen aus Photoshops Ebenenblock in TIFF-Dateien (Tag 37724 „ImageSourceData“): Kennung
     * „Adobe Photoshop Document Data Block“, dann Zusatzblöcke wie in PSD („Layr“, „Lr16“, …). Bei
     * TIFFs mit Intel-Bytefolge sind Kennungen und Zahlen der Struktur byteweise vertauscht, die
     * Bildwerte der Kanäle bleiben Big-Endian (wie psdtags/ImageMagick). [data] ist ein Ausschnitt der (ggf.
     * speicherabgebildeten) Datei; Kanaldaten werden erst beim Aufbau der Ebenen gelesen.
     * Liefert false, wenn keine Ebenen gefunden wurden.
     */
    internal fun readEmbeddedLayers(doc: Document, h: Header, data: java.nio.ByteBuffer, le: Boolean, colorData: ByteArray): Boolean {
        droppedLeKeys.clear()
        val r = BinaryReader(data, 0, data.limit(), le)
        val magic = "Adobe Photoshop Document Data Block"
        if (r.peekAscii(magic.length) != magic) return false
        r.skip(magic.length + 1L)
        var records: List<LayerRecord> = emptyList()
        while (r.remaining >= 12) {
            var skipped = 0
            while (r.remaining >= 4 && skipped < 4 && r.peekSig().let { it != "8BIM" && it != "8B64" }) { r.pos++; skipped++ }
            if (r.remaining < 12) break
            val sig = r.sig(); if (sig != "8BIM" && sig != "8B64") break
            val key = r.sig()
            val len = r.u32()
            if (len < 0 || len > r.remaining) break
            val end = r.pos + len.toInt()
            if (records.isEmpty() && key in LE_LAYER_INFO_KEYS && len > 0) {
                val depth = when (key) { "Lr16" -> 16; "Lr32" -> 32; else -> h.depth }
                records = readLayerInfo(r.sub(len.toInt()), h, depth).first
            }
            r.seek(end)
        }
        if (records.isEmpty()) return false
        buildLayers(doc, records, h, colorData, -1)
        if (droppedLeKeys.isNotEmpty()) doc.importWarnings +=
            "TIFF mit Intel-Bytefolge: Ebeneneffekte, Text- und Einstellungsdaten (${droppedLeKeys.sorted().joinToString()}) konnten nicht übernommen werden – " +
                "Pixel, Masken, Gruppen und Mischmodi schon. In Photoshop mit Bytefolge „Macintosh“ gespeicherte TIFFs behalten alles."
        return doc.root.children.isNotEmpty()
    }

    /**
     * Ebenenname ohne Unicode-Block: Photoshop schreibt dann Latin-1/MacRoman, GIMP und ImageMagick
     * UTF-8. Gültiges UTF-8 mit Sonderzeichen wird als UTF-8 gelesen.
     */
    private fun utf8OrLatin1(name: String): String {
        val bytes = name.toByteArray(Charsets.ISO_8859_1)
        if (bytes.all { it >= 0 }) return name
        return runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrDefault(name)
    }

    /** Ressource 1032: Version, Rasterabstand (2×4 Byte), Anzahl, je Linie Lage (1/32 px) und Richtung (0 = senkrecht). */
    private fun readGuides(data: ByteArray): List<de.photographics.core.doc.Guide> = runCatching {
        val r = BinaryReader(data)
        r.u32(); r.u32(); r.u32()
        val n = r.u32().toInt().coerceIn(0, 10_000)
        List(n) { val pos = r.i32() / 32.0; val dir = r.u8(); de.photographics.core.doc.Guide(dir == 0, pos) }
    }.getOrDefault(emptyList())

    /** Schlüssel, die aus Little-Endian-Daten nicht übernommen werden konnten (pro Lesevorgang). */
    private val droppedLeKeys: MutableSet<String> get() = droppedLeKeysTl.get()
    private val droppedLeKeysTl = ThreadLocal.withInitial { mutableSetOf<String>() }
    /** Diese Blöcke enthalten selbst Ebenendaten und werden mit dem Little-Endian-Leser weiterverarbeitet. */
    private val LE_LAYER_INFO_KEYS = setOf("Layr", "Lr16", "Lr32")

    /** Wandelt einfache Little-Endian-Zusatzblöcke in die Big-Endian-Form von PSD um; null = nicht unterstützt. */
    private fun leBlockToBe(key: String, d: ByteArray): ByteArray? = runCatching {
        val r = BinaryReader(d, le = true)
        val w = BinaryWriter()
        when (key) {
            "luni" -> { val n = r.i32(); w.i32(n); repeat(n) { w.u16(r.u16()) } }
            "lsct", "lsdk" -> {
                w.i32(r.i32())
                if (r.remaining >= 8) { w.ascii(r.sig()); w.ascii(r.sig()) }
                if (r.remaining >= 4) w.i32(r.i32())
            }
            "lyid", "lspf" -> w.i32(r.i32())
            "iOpa", "clbl", "infx", "knko" -> return@runCatching d
            else -> return@runCatching null
        }
        w.toByteArray()
    }.getOrNull()

    private fun buildLayers(doc: Document, records: List<LayerRecord>, h: Header, colorData: ByteArray, targetIndex: Int) {
        // Datensätze liegen von unten nach oben vor; Gruppen werden durch Trenner (lsct=3) unten
        // und den Gruppen-Datensatz (lsct=1/2) oben geklammert.
        val stack = ArrayDeque<GroupLayer>()
        stack.addLast(doc.root)
        val recordLayers = arrayOfNulls<Layer>(records.size)
        var sawEffects = false; var sawVector = false
        val kinds = mutableSetOf<LayerKind>()

        for ((idx, rec) in records.withIndex()) {
            val blockMap = rec.blocks.associateBy { it.key }
            val unicodeName = blockMap["luni"]?.let { runCatching { BinaryReader(it.data).unicode() }.getOrNull() }
            if (!unicodeName.isNullOrEmpty()) rec.name = unicodeName else rec.name = utf8OrLatin1(rec.name)
            val section = (blockMap["lsct"] ?: blockMap["lsdk"])?.let { BinaryReader(it.data) }
            val sectionType = section?.let { if (it.remaining >= 4) it.i32() else 0 } ?: 0
            val sectionBlend = section?.let { if (it.remaining >= 8) { it.ascii(4); it.ascii(4) } else null }

            if (sectionType == 3) {
                stack.addLast(GroupLayer("Gruppe"))
                continue
            }

            val layer: Layer
            if (sectionType == 1 || sectionType == 2) {
                val g = if (stack.size > 1) stack.removeLast() else GroupLayer("Gruppe")
                g.name = rec.name
                g.expanded = sectionType == 1
                layer = g
                layer.blendMode = BlendMode.fromPsdKey(sectionBlend ?: rec.blendKey)
            } else {
                val n = rec.rect.width * rec.rect.height
                // Ebenen dürfen über die Arbeitsfläche hinausragen – nichts abschneiden (Grenze gegen defekte Dateien)
                val grid = TileGrid(h.width, h.height, area = rec.rect.intersect(sanityBounds(h)))
                val colorIds = rec.channelReaders.keys.filter { it >= -1 }
                if (n > 0 && colorIds.isNotEmpty()) {
                    // Streifenweise lesen und umwandeln: nie eine ganze Ebene im Speicher
                    val rw = rec.rect.width
                    val band = maxOf(1, (1 shl 20) / rw)
                    var y0 = 0
                    while (y0 < rec.rect.height) {
                        val y1 = minOf(rec.rect.height, y0 + band)
                        val planes = HashMap<Int, ByteArray>()
                        for (id in colorIds) runCatching {
                            planes[id] = ByteArray((y1 - y0) * rw).also { rec.channelReaders[id]!!.rows(y0, y1, it) }
                        }
                        val px = planesToArgb(planes, h, 0, (y1 - y0) * rw, colorData, alphaId = -1)
                        grid.writeRect(IntRect(rec.rect.left, rec.rect.top + y0, rec.rect.right, rec.rect.top + y1), px)
                        y0 = y1
                    }
                    grid.compact()
                }
                val ourText = blockMap[PsdTextSmart.TEXT_KEY]?.let { PsdTextSmart.readText(it.data) }
                val psText = if (ourText == null) blockMap["TySh"]?.let { PsdTextSmart.parseTySh(it.data) } else null
                val smart = blockMap[PsdTextSmart.SMART_KEY]?.let { PsdTextSmart.readSmart(it.data) }
                val adjustment = if (rec.blocks.any { it.key in PsdAdjustments.KEYS }) PsdAdjustments.read(blockMap) else null
                layer = when {
                    adjustment != null -> AdjustmentLayer(rec.name, adjustment)
                    ourText != null -> TextLayer(rec.name, ourText.first, ourText.second, h.width, h.height).also { it.originalRaster = grid }
                    psText != null -> TextLayer(rec.name, psText.first, psText.second, h.width, h.height).also {
                        it.originalRaster = grid; it.unchangedFromPsd = true
                    }
                    smart != null -> SmartLayer(rec.name, smart.first, smart.second, h.width, h.height).also { sl ->
                        blockMap[PsdTextSmart.SMART_KEY]?.let { PsdTextSmart.readSmartWarp(it.data) }?.let { sl.warp = it }
                        sl.setCachedRaster(grid)
                    }
                    else -> PixelLayer(rec.name, grid)
                }
                layer.blendMode = BlendMode.fromPsdKey(rec.blendKey)
            }

            layer.opacity = rec.opacity / 255f
            layer.clipped = rec.clipping != 0
            layer.visible = rec.flags and 0x02 == 0
            layer.lockTransparency = rec.flags and 0x01 != 0
            blockMap["iOpa"]?.data?.let { if (it.isNotEmpty()) layer.fillOpacity = (it[0].toInt() and 0xFF) / 255f }
            blockMap["lyid"]?.data?.let { if (it.size >= 4) layer.psdId = BinaryReader(it).i32() }

            layer.kind = when {
                rec.blocks.any { it.key in TEXT_KEYS } -> LayerKind.TEXT
                rec.blocks.any { it.key in SMART_KEYS } -> LayerKind.SMART_OBJECT
                rec.blocks.any { it.key in ADJUSTMENT_KEYS } -> LayerKind.ADJUSTMENT
                rec.blocks.any { it.key in FILL_KEYS } -> LayerKind.FILL
                else -> LayerKind.PIXEL
            }
            if (layer is AdjustmentLayer || layer is TextLayer) layer.kind = LayerKind.PIXEL
            if (layer.kind != LayerKind.PIXEL) kinds += layer.kind
            if (rec.blocks.any { it.key == "lfx2" || it.key == "lrFX" || it.key == "lmfx" }) sawEffects = true
            if (rec.blocks.any { it.key == "vmsk" || it.key == "vsms" }) sawVector = true
            if (!h.big) layer.preservedBlocks = rec.blocks.filter { b ->
                b.key !in CONSUMED_KEYS && b.key != PsdTextSmart.TEXT_KEY && b.key != PsdTextSmart.SMART_KEY &&
                    !(layer is AdjustmentLayer && b.key in PsdAdjustments.KEYS)
            }

            // Pixelmaske: bei Vektor+Pixelmaske steht die Pixelmaske in Kanal -3 ("real user mask")
            val useReal = rec.channelReaders.containsKey(-3) && rec.realMaskRect != null
            val maskReader = if (useReal) rec.channelReaders[-3] else rec.channelReaders[-2]
            val mRect = if (useReal) rec.realMaskRect else rec.maskRect
            if (maskReader != null && mRect != null && !mRect.isEmpty) runCatching {
                val def = if (useReal) rec.realMaskDefault else rec.maskDefault
                val flags = if (useReal) rec.realMaskFlags else rec.maskFlags
                val invert = flags and 0x04 != 0
                val mg = TileGrid(h.width, h.height, if (invert) 255 - def else def, area = mRect.intersect(sanityBounds(h)))
                val mw = mRect.width
                val band = maxOf(1, (1 shl 20) / mw)
                var y0 = 0
                while (y0 < mRect.height) {
                    val y1 = minOf(mRect.height, y0 + band)
                    val b = ByteArray((y1 - y0) * mw); maskReader.rows(y0, y1, b)
                    mg.writeRect(IntRect(mRect.left, mRect.top + y0, mRect.right, mRect.top + y1), IntArray(b.size) { i ->
                        val v = b[i].toInt() and 0xFF
                        if (invert) 255 - v else v
                    })
                    y0 = y1
                }
                mg.compact()
                layer.mask = LayerMask(mg, enabled = flags and 0x02 == 0, linked = blockMap[PsdWriter.MASK_LINK_KEY] == null)
            }
            rec.channelReaders.clear()

            stack.last().add(layer)
            recordLayers[idx] = layer
        }
        // Nicht geschlossene Gruppen (defekte Dateien) trotzdem einhängen
        while (stack.size > 1) {
            val g = stack.removeLast()
            for (c in g.children.toList()) stack.last().add(c)
        }

        if (sawEffects) doc.importWarnings += "Ebeneneffekte (Schatten, Konturen ...) werden nicht dargestellt, bleiben beim Speichern aber erhalten."
        if (sawVector) doc.importWarnings += "Vektormasken werden nicht dargestellt."
        for (k in kinds) doc.importWarnings += "Enthält ${k.label}."

        // Aktive Ebene aus der Datei, sonst die oberste sichtbare Pixelebene
        fun effectivelyVisible(l: Layer): Boolean {
            var cur: Layer? = l
            while (cur != null) { if (!cur.visible) return false; cur = cur.parent }
            return true
        }
        doc.activeLayer = recordLayers.getOrNull(targetIndex)?.takeIf { it !is GroupLayer && effectivelyVisible(it) }
            ?: doc.pixelLayers().lastOrNull { effectivelyVisible(it) && it.kind != LayerKind.ADJUSTMENT }
            ?: doc.pixelLayers().lastOrNull() ?: doc.allLayers().lastOrNull()
    }

    // ---------------------------------------------------------------- Kanaldaten

    /** Dekodiert einen Kanal und liefert 8-Bit-Werte (w*h). */
    private fun decodeChannel(r: BinaryReader, end: Int, compression: Int, w: Int, h: Int, depth: Int, big: Boolean, gamma: Boolean): ByteArray {
        val rowBytes = (w * depth + 7) / 8
        val raw = ByteArray(rowBytes * h)
        when (compression) {
            0 -> {
                val n = minOf(raw.size, end - r.pos)
                r.copy(r.pos, raw, 0, n)
                r.pos += n
            }
            1 -> {
                val counts = IntArray(h) { if (big) r.i32() else r.u16() }
                for (y in 0 until h) {
                    val c = counts[y]
                    PackBits.decode(r, r.pos, minOf(c, end - r.pos), raw, y * rowBytes, rowBytes)
                    r.pos += c
                }
            }
            2, 3 -> {
                inflate(r, r.pos, end - r.pos, raw)
                r.pos = end
                if (compression == 3) unpredict(raw, w, h, depth)
            }
            else -> throw PsdFormatException("Unbekannte Kompression $compression")
        }
        return to8Bit(raw, w, h, depth, gamma)
    }

    private fun inflate(src: BinaryReader, off: Int, len: Int, dst: ByteArray) {
        val inf = Inflater()
        try {
            // Eingabe stückweise kopieren (die Quelle kann ein speicherabgebildeter Puffer sein)
            val chunk = ByteArray(minOf(len, 1 shl 16).coerceAtLeast(1))
            var inPos = off; val inEnd = off + len
            var o = 0
            while (o < dst.size && !inf.finished()) {
                if (inf.needsInput()) {
                    if (inPos >= inEnd) break
                    val n = minOf(chunk.size, inEnd - inPos)
                    src.copy(inPos, chunk, 0, n); inPos += n
                    inf.setInput(chunk, 0, n)
                }
                val n = inf.inflate(dst, o, dst.size - o)
                if (n == 0 && inf.needsDictionary()) break
                o += n
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw PsdFormatException("ZIP-Daten beschädigt")
        } finally {
            inf.end()
        }
    }

    private fun unpredict(d: ByteArray, w: Int, h: Int, depth: Int) {
        when (depth) {
            8 -> for (y in 0 until h) {
                val o = y * w
                for (x in 1 until w) d[o + x] = (d[o + x] + d[o + x - 1]).toByte()
            }
            16 -> for (y in 0 until h) {
                val o = y * w * 2
                var prev = 0
                for (x in 0 until w) {
                    val i = o + x * 2
                    val v = (((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)) + prev and 0xFFFF
                    d[i] = (v ushr 8).toByte(); d[i + 1] = v.toByte()
                    prev = v
                }
            }
            32 -> {
                val rowLen = w * 4
                val tmp = ByteArray(rowLen)
                for (y in 0 until h) {
                    val o = y * rowLen
                    for (x in 1 until rowLen) d[o + x] = (d[o + x] + d[o + x - 1]).toByte()
                    // Bytes liegen ebenenweise (alle Byte0, alle Byte1, ...) – zurück verschachteln
                    for (x in 0 until w) for (b in 0 until 4) tmp[x * 4 + b] = d[o + b * w + x]
                    System.arraycopy(tmp, 0, d, o, rowLen)
                }
            }
        }
    }

    private fun to8Bit(raw: ByteArray, w: Int, h: Int, depth: Int, gamma: Boolean): ByteArray = when (depth) {
        8 -> raw
        1 -> {
            val rowBytes = (w + 7) / 8
            ByteArray(w * h) { i ->
                val x = i % w; val y = i / w
                val bit = (raw[y * rowBytes + x / 8].toInt() shr (7 - x % 8)) and 1
                if (bit == 1) 0.toByte() else 255.toByte()
            }
        }
        16 -> ByteArray(w * h) { i ->
            val v = ((raw[i * 2].toInt() and 0xFF) shl 8) or (raw[i * 2 + 1].toInt() and 0xFF)
            ((v * 255 + 32767) / 65535).toByte()
        }
        32 -> ByteArray(w * h) { i ->
            val bits = ((raw[i * 4].toInt() and 0xFF) shl 24) or ((raw[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((raw[i * 4 + 2].toInt() and 0xFF) shl 8) or (raw[i * 4 + 3].toInt() and 0xFF)
            val f = Float.fromBits(bits)
            (if (gamma) ColorConvert.linearToSrgb(f) else (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt()).toByte()
        }
        else -> throw PsdFormatException("Farbtiefe $depth")
    }

    /** Wandelt Kanalebenen des Dokumentfarbmodus in ARGB um. [alphaId]: Kanal-ID der Transparenz oder null. */
    /** Farbprofil des Dokuments festlegen und ggf. die Umrechnung des Farbmodus nach RGB vorbereiten. */
    internal fun setUpColor(doc: Document, h: Header, iccData: ByteArray?, workingRgb: IccProfile) {
        val embedded = iccData?.let { runCatching { IccProfile(it) }.getOrNull() }
        val modeName = PsdColorMode.name(h.mode)
        fun converted(p: IccProfile) {
            h.conv = DeviceToRgb.icc(p, workingRgb)
            doc.colorProfile = workingRgb
            doc.importWarnings += "$modeName-Dokument wurde mit dem eingebetteten Profil „${p.description}“ in „${workingRgb.description}“ umgewandelt."
        }
        when (h.mode) {
            PsdColorMode.RGB -> doc.colorProfile = embedded?.takeIf { it.isRgb } ?: IccProfiles.sRGB
            PsdColorMode.CMYK -> if (embedded?.isCmyk == true) runCatching { converted(embedded) }.onFailure { naive(doc, modeName) } else naive(doc, modeName)
            PsdColorMode.GRAYSCALE, PsdColorMode.DUOTONE ->
                if (embedded?.isGray == true) runCatching { converted(embedded) }.onFailure { naive(doc, modeName) } else naive(doc, modeName)
            PsdColorMode.LAB -> {
                h.conv = DeviceToRgb.lab(workingRgb); doc.colorProfile = workingRgb
                doc.importWarnings += "Lab-Dokument wurde in „${workingRgb.description}“ umgewandelt."
            }
            else -> naive(doc, modeName)
        }
    }

    private fun naive(doc: Document, modeName: String) {
        doc.colorProfile = IccProfiles.sRGB
        doc.importWarnings += "Farbmodus $modeName wurde ohne Farbprofil in RGB umgewandelt (Farben können abweichen)."
    }

    internal fun planesToArgb(planes: Map<Int, ByteArray>, h: Header, start: Int, n: Int, colorData: ByteArray, alphaId: Int?): IntArray {
        val mode = h.mode
        val conv = h.conv
        val dev = IntArray(4)
        fun p(id: Int) = planes[id]
        val alpha = alphaId?.let { p(it) }
        val out = IntArray(n)
        val c0 = p(0); val c1 = p(1); val c2 = p(2); val c3 = p(3)
        for (j in 0 until n) {
            val i = start + j
            fun v(a: ByteArray?, def: Int = 0) = if (a != null && i < a.size) a[i].toInt() and 0xFF else def
            val rgb = when (mode) {
                PsdColorMode.RGB -> (v(c0) shl 16) or (v(c1) shl 8) or v(c2)
                PsdColorMode.CMYK -> if (conv != null) {
                    // PSD speichert CMYK invertiert (255 = keine Farbe)
                    dev[0] = 255 - v(c0, 255); dev[1] = 255 - v(c1, 255); dev[2] = 255 - v(c2, 255); dev[3] = 255 - v(c3, 255)
                    conv.rgb(dev)
                } else ColorConvert.cmykToRgb(v(c0, 255), v(c1, 255), v(c2, 255), v(c3, 255))
                PsdColorMode.LAB -> if (conv != null) { dev[0] = v(c0); dev[1] = v(c1, 128); dev[2] = v(c2, 128); conv.rgb(dev) }
                    else ColorConvert.labToRgb(v(c0), v(c1, 128), v(c2, 128))
                PsdColorMode.GRAYSCALE, PsdColorMode.DUOTONE -> if (conv != null) { dev[0] = v(c0); conv.rgb(dev) } else v(c0) * 0x010101
                PsdColorMode.INDEXED -> {
                    val idx = v(c0)
                    if (colorData.size >= 768) ((colorData[idx].toInt() and 0xFF) shl 16) or
                        ((colorData[256 + idx].toInt() and 0xFF) shl 8) or (colorData[512 + idx].toInt() and 0xFF)
                    else idx * 0x010101
                }
                else -> v(c0) * 0x010101 // Graustufen, Duplex, Bitmap, Mehrkanal
            }
            val a = if (alpha != null) v(alpha) else 255
            out[j] = (a shl 24) or rgb
        }
        return out
    }

    /** Zusammengeführtes Bild als Raster; 8-Bit-RLE/unkomprimiert streifenweise, sonst komplett. */
    private fun readMergedGrid(r: BinaryReader, h: Header, colorData: ByteArray, hasAlpha: Boolean): TileGrid {
        val alphaId = if (hasAlpha) baseChannels(h.mode) else null
        val w = h.width; val hh = h.height
        val grid = TileGrid(w, hh)
        val start = r.pos
        val compression = if (r.remaining >= 2) r.u16() else -1
        val readers = HashMap<Int, ChannelReader>()
        if (h.depth == 8 && (compression == 0 || compression == 1)) {
            val nch = h.channels
            if (compression == 1) {
                val counts = IntArray(nch * hh) { if (h.big) r.i32() else r.u16() }
                var p = r.pos
                for (c in 0 until nch) {
                    val starts = IntArray(hh); val lens = IntArray(hh)
                    for (y in 0 until hh) { starts[y] = p; lens[y] = counts[c * hh + y]; p += lens[y] }
                    readers[c] = ChannelReader(r, starts[0], r.limit, 1, w, hh, 8, h.big, false, starts, lens)
                }
            } else {
                for (c in 0 until nch) readers[c] = ChannelReader(r, r.pos + c * w * hh, r.limit, 0, w, hh, 8, h.big, false)
            }
        } else {
            r.pos = start
            val planes = readMerged(r, h)
            val px = planesToArgb(planes, h, 0, w * hh, colorData, alphaId)
            grid.writeRect(IntRect.ofSize(w, hh), px); grid.compact()
            return grid
        }
        val band = maxOf(1, (1 shl 20) / w)
        var y0 = 0
        while (y0 < hh) {
            val y1 = minOf(hh, y0 + band)
            val planes = HashMap<Int, ByteArray>()
            for ((id, rd) in readers) runCatching { planes[id] = ByteArray((y1 - y0) * w).also { rd.rows(y0, y1, it) } }
            grid.writeRect(IntRect(0, y0, w, y1), planesToArgb(planes, h, 0, (y1 - y0) * w, colorData, alphaId))
            y0 = y1
        }
        grid.compact()
        return grid
    }

    private fun readMerged(r: BinaryReader, h: Header): Map<Int, ByteArray> {
        val planes = HashMap<Int, ByteArray>()
        if (r.remaining < 2) return planes
        val compression = r.u16()
        val w = h.width; val hh = h.height
        val rowBytes = (w * h.depth + 7) / 8
        val nch = h.channels
        when (compression) {
            0 -> for (c in 0 until nch) {
                if (r.remaining <= 0) break
                val raw = ByteArray(rowBytes * hh)
                val n = minOf(raw.size, r.remaining)
                r.copy(r.pos, raw, 0, n); r.pos += n
                planes[c] = to8Bit(raw, w, hh, h.depth, gamma = h.depth == 32 && c < 3)
            }
            1 -> {
                val counts = IntArray(nch * hh) { if (h.big) r.i32() else r.u16() }
                for (c in 0 until nch) {
                    val raw = ByteArray(rowBytes * hh)
                    for (y in 0 until hh) {
                        val cnt = counts[c * hh + y]
                        PackBits.decode(r, r.pos, minOf(cnt, r.remaining), raw, y * rowBytes, rowBytes)
                        r.pos = minOf(r.pos + cnt, r.limit)
                    }
                    planes[c] = to8Bit(raw, w, hh, h.depth, gamma = h.depth == 32 && c < 3)
                }
            }
            2, 3 -> {
                val all = ByteArray(rowBytes * hh * nch)
                inflate(r, r.pos, r.remaining, all)
                for (c in 0 until nch) {
                    val raw = all.copyOfRange(c * rowBytes * hh, (c + 1) * rowBytes * hh)
                    if (compression == 3) unpredict(raw, w, hh, h.depth)
                    planes[c] = to8Bit(raw, w, hh, h.depth, gamma = h.depth == 32 && c < 3)
                }
            }
        }
        return planes
    }
}
