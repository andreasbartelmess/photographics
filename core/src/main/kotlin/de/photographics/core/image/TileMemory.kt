package de.photographics.core.image

import de.photographics.core.tr
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Inhalt einer 256×256-Kachel. Liegt entweder als `IntArray` im Java-Speicher ([HeapTileMemory])
 * oder in einem nativen Speicherblock ([SlabTileMemory]) – dann zählt er nicht zur Heap-Grenze
 * von Android (meist 512 MB), sondern nur zum echten Arbeitsspeicher des Geräts.
 *
 * Für schnelle Schleifen liefert [pin] ein `IntArray`; bei nativen Kacheln ist das eine Arbeitskopie
 * im Kachel-Cache, die beim Verdrängen zurückgeschrieben wird. Alle Zugriffe erfolgen auf einem
 * Thread (Engine-Thread der App).
 */
class Tile internal constructor(
    /** Heap-Kachel bzw. Arbeitskopie einer nativen Kachel ("heiß"). */
    internal var array: IntArray?,
    internal val buffer: IntBuffer?,
    internal val owner: SlabTileMemory?,
) {
    internal var dirty = false
    internal var pins = 0
    /** Änderungszähler (für verkleinerte Fassungen, siehe [MipMap]). */
    @Volatile var version = 0
        internal set

    operator fun get(i: Int): Int {
        val a = array
        return if (a != null) a[i] else buffer!!.get(i)
    }

    operator fun set(i: Int, v: Int) {
        version++
        val a = array
        if (a != null) { a[i] = v; dirty = true } else buffer!!.put(i, v)
    }

    /** Liest [n] Werte ab Index [from] nach [dst]. */
    fun read(from: Int, dst: IntArray, dstOff: Int, n: Int) {
        val a = array
        if (a != null) System.arraycopy(a, from, dst, dstOff, n)
        else { val b = buffer!!.duplicate(); b.position(from); b.get(dst, dstOff, n) }
    }

    /** Schreibt [n] Werte aus [src] ab Index [to]. */
    fun write(to: Int, src: IntArray, srcOff: Int, n: Int) {
        version++
        val a = array
        if (a != null) { System.arraycopy(src, srcOff, a, to, n); dirty = true }
        else { val b = buffer!!.duplicate(); b.position(to); b.put(src, srcOff, n) }
    }

    fun fill(v: Int, from: Int = 0, to: Int = SIZE) {
        version++
        val a = array
        if (a != null) { a.fill(v, from, to); dirty = true; return }
        val b = buffer!!
        val chunk = FILL_CHUNK.get()
        if (chunk[0] != v || chunk[chunk.size - 1] != v) chunk.fill(v)
        var i = from
        while (i < to) { val n = minOf(chunk.size, to - i); val d = b.duplicate(); d.position(i); d.put(chunk, 0, n); i += n }
    }

    /** Alle Werte gleich [v]? */
    fun isUniform(v: Int): Boolean {
        val a = array
        if (a != null) { for (x in a) if (x != v) return false; return true }
        val tmp = SCRATCH.get()
        read(0, tmp, 0, SIZE)
        for (x in tmp) if (x != v) return false
        return true
    }

    /** Ganzer Inhalt als neues Array (z. B. zum Auslagern). */
    fun toArray(): IntArray = IntArray(SIZE).also { read(0, it, 0, SIZE) }

    /** Unabhängige Kopie im gleichen Speicher. */
    fun copy(): Tile {
        val o = owner ?: return Tile(array!!.copyOf(), null, null)
        val t = o.allocateRaw()
        val a = array
        if (a != null) { val d = t.buffer!!.duplicate(); d.position(0); d.put(a, 0, SIZE) }
        else { val d = t.buffer!!.duplicate(); d.position(0); val s = buffer!!.duplicate(); s.position(0); d.put(s) }
        return t
    }

    /**
     * Schneller Array-Zugriff für Schleifen. Bis [unpin] darf das Array benutzt werden; Änderungen
     * daran gelten als Änderungen der Kachel.
     */
    fun pin(forWrite: Boolean = true): IntArray {
        if (forWrite) version++
        val o = owner ?: return array!!
        val a = o.makeHot(this)
        pins++
        if (forWrite) dirty = true
        return a
    }

    fun unpin() { if (owner != null && pins > 0) pins-- }

    companion object {
        const val SIZE = TileGrid.TILE * TileGrid.TILE
        const val BYTES = SIZE * 4L
        private val SCRATCH = ThreadLocal.withInitial { IntArray(SIZE) }
        private val FILL_CHUNK = ThreadLocal.withInitial { IntArray(4096) }
    }
}

/** Woher Kacheln ihren Speicher bekommen. */
abstract class TileMemory {
    /** Neue Kachel, gefüllt mit [fill]. */
    abstract fun allocate(fill: Int = 0): Tile

    /** Belegter Kachelspeicher in Bytes. */
    abstract val usedBytes: Long

    /** Obergrenze in Bytes. */
    abstract val limitBytes: Long

    /** Auslastung 0..1 (für Verlauf und Speicherreserven). */
    open val pressure: Double get() = usedBytes.toDouble() / limitBytes

    /** Noch verfügbare Bytes. */
    open val freeBytes: Long get() = (limitBytes - usedBytes).coerceAtLeast(0)

    /** Arbeitskopien zurückschreiben (z. B. bevor das System Speicher zurückfordert). */
    open fun flush() {}

    companion object {
        /** Speicher für neue Kacheln; die App setzt hier den nativen Speicher ein. */
        @JvmStatic
        @Volatile
        var current: TileMemory = HeapTileMemory()
    }
}

/** Kacheln als `IntArray` im Java-Speicher (Desktop-Tests, Fallback). */
class HeapTileMemory : TileMemory() {
    override fun allocate(fill: Int): Tile = Tile(IntArray(Tile.SIZE).also { if (fill != 0) it.fill(fill) }, null, null)

    override val usedBytes: Long get() { val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }
    override val limitBytes: Long get() = Runtime.getRuntime().maxMemory()
}

/**
 * Kacheln in großen nativen Speicherblöcken ("Slabs"), aufgeteilt in Plätze à 256 KB.
 *
 * - Freigabe automatisch: Wird eine Kachel nicht mehr referenziert, meldet eine PhantomReference
 *   ihren Platz zurück (geprüft bei jeder neuen Anforderung).
 * - Ist das Budget erreicht, wird die Speicherbereinigung angestoßen und erneut geprüft; erst dann
 *   gibt es einen [OutOfMemoryError], den die App wie gewohnt behandelt (Verlauf auslagern, erneut versuchen).
 * - Vollständig freie Blöcke werden an das System zurückgegeben.
 * - Ein kleiner Kachel-Cache hält Arbeitskopien als `IntArray` für schnelle Schleifen ([Tile.pin]).
 *
 * [newSlab] liefert einen Block mit der angeforderten Größe (Android: SharedMemory, Tests: Direct-Buffer),
 * [releaseSlab] gibt ihn frei.
 */
class SlabTileMemory(
    override val limitBytes: Long,
    private val newSlab: (bytes: Int) -> ByteBuffer,
    private val releaseSlab: (ByteBuffer) -> Unit = {},
    private val tilesPerSlab: Int = 256,
    private val hotCapacity: Int = 96,
) : TileMemory() {
    private class Slab(val buf: ByteBuffer, val tiles: Int) {
        val free = ArrayDeque<Int>().also { q -> for (i in 0 until tiles) q.addLast(i) }
        var used = 0
    }

    private class SlotRef(t: Tile, q: ReferenceQueue<Tile>, val slab: Slab, val slot: Int) : PhantomReference<Tile>(t, q)

    private val slabs = ArrayList<Slab>()
    private val queue = ReferenceQueue<Tile>()
    /** Hält die PhantomReferences am Leben, bis sie eingereiht wurden. */
    private val refs = HashSet<SlotRef>()
    private var usedTiles = 0L

    /** Kachel-Cache: zuletzt benutzte heiße Kacheln (Einfügereihenfolge = LRU). */
    private val hot = LinkedHashMap<Tile, Unit>(hotCapacity * 2, 0.75f, true)
    private val arrayPool = ArrayDeque<IntArray>()

    override val usedBytes: Long get() = usedTiles * Tile.BYTES
    val slabCount get() = slabs.size
    val reservedBytes: Long get() = slabs.size.toLong() * tilesPerSlab * Tile.BYTES

    override fun allocate(fill: Int): Tile = allocateRaw().also { it.fill(fill) }

    // Synchronisiert: Mipmap-Stufen können in Rechen-Threads angelegt werden
    @Synchronized
    internal fun allocateRaw(): Tile {
        reclaim()
        var place = findFree()
        if (place == null && reservedBytes + tilesPerSlab * Tile.BYTES <= limitBytes) place = grow()
        if (place == null) {
            // Unerreichbare Kacheln einsammeln lassen und noch einmal versuchen
            repeat(3) { attempt ->
                if (place != null) return@repeat
                System.gc()
                @Suppress("DEPRECATION") System.runFinalization()
                if (attempt > 0) Thread.sleep(20)
                reclaim()
                place = findFree()
            }
        }
        val (slab, slot) = place ?: throw OutOfMemoryError(tr("Kachelspeicher voll ({0} MB)", limitBytes shr 20))
        val bb = slab.buf.duplicate()
        val off = slot * Tile.BYTES.toInt()
        bb.position(off); bb.limit(off + Tile.BYTES.toInt())
        val ib = bb.slice().order(ByteOrder.nativeOrder()).asIntBuffer()
        val t = Tile(null, ib, this)
        refs += SlotRef(t, queue, slab, slot)
        slab.used++; usedTiles++
        return t
    }

    private fun findFree(): Pair<Slab, Int>? {
        for (s in slabs) s.free.removeFirstOrNull()?.let { return s to it }
        return null
    }

    private fun grow(): Pair<Slab, Int>? {
        val buf = try { newSlab(tilesPerSlab * Tile.BYTES.toInt()) } catch (_: Throwable) { return null }
        val s = Slab(buf.order(ByteOrder.nativeOrder()), tilesPerSlab)
        slabs += s
        return s to s.free.removeFirst()
    }

    /** Plätze nicht mehr referenzierter Kacheln zurückholen; leere Blöcke freigeben. */
    @Synchronized
    fun reclaim() {
        while (true) {
            val r = queue.poll() as SlotRef? ?: break
            refs -= r
            r.slab.free.addLast(r.slot)
            r.slab.used--; usedTiles--
        }
        // Höchstens einen leeren Block als Reserve behalten
        var emptyKept = false
        val it = slabs.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.used != 0) continue
            if (!emptyKept) { emptyKept = true; continue }
            it.remove(); releaseSlab(s.buf)
        }
    }

    internal fun makeHot(t: Tile): IntArray {
        t.array?.let { hot[t] = Unit; return it }
        val a = arrayPool.removeFirstOrNull() ?: IntArray(Tile.SIZE)
        val b = t.buffer!!.duplicate(); b.position(0); b.get(a, 0, Tile.SIZE)
        t.array = a; t.dirty = false
        hot[t] = Unit
        if (hot.size > hotCapacity) evict(hot.size - hotCapacity)
        return a
    }

    private fun evict(count: Int) {
        var n = count
        val it = hot.keys.iterator()
        while (n > 0 && it.hasNext()) {
            val t = it.next()
            if (t.pins > 0) continue
            it.remove(); cool(t); n--
        }
    }

    private fun cool(t: Tile) {
        val a = t.array ?: return
        if (t.dirty) { val b = t.buffer!!.duplicate(); b.position(0); b.put(a, 0, Tile.SIZE) }
        t.array = null; t.dirty = false
        if (arrayPool.size < 16) arrayPool.addLast(a)
    }

    /** Alle Arbeitskopien zurückschreiben und den Cache leeren. */
    override fun flush() {
        val it = hot.keys.iterator()
        while (it.hasNext()) { val t = it.next(); if (t.pins == 0) { it.remove(); cool(t) } }
        arrayPool.clear()
    }
}
