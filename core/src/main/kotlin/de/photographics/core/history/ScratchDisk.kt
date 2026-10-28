package de.photographics.core.history

import de.photographics.core.image.IntRect
import de.photographics.core.image.Tile
import de.photographics.core.image.TileGrid
import de.photographics.core.image.TileMemory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Arbeitsvolume wie in Photoshop: ältere Verlaufsschritte werden in Dateien ausgelagert und erst
 * beim Rückgängigmachen wieder geladen. Einfarbige Kacheln werden als einzelner Wert gespeichert.
 */
class ScratchDisk(private val dir: File, val maxBytes: Long = 4L shl 30) {
    private val counter = AtomicLong()
    private val buf = ByteBuffer.allocate(TileGrid.TILE * TileGrid.TILE * 4).order(ByteOrder.LITTLE_ENDIAN)

    init {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }   // Reste einer früheren Sitzung
    }

    val usedBytes: Long get() = dir.listFiles()?.sumOf { it.length() } ?: 0L
    fun hasRoom(bytes: Long) = usedBytes + bytes <= maxBytes && dir.usableSpace > bytes + (512L shl 20)

    /** Schreibt Kacheln (Schlüssel → Inhalt oder null) in eine neue Datei. */
    @Synchronized
    fun writeTiles(tiles: Map<Int, Tile?>): File {
        val f = File(dir, "v${counter.incrementAndGet()}.tiles")
        DataOutputStream(BufferedOutputStream(f.outputStream(), 1 shl 16)).use { out ->
            out.writeInt(tiles.size)
            for ((k, t) in tiles) writeTile(out, k, t)
        }
        return f
    }

    @Synchronized
    fun readTiles(f: File, memory: TileMemory = TileMemory.current): HashMap<Int, Tile?> {
        val m = HashMap<Int, Tile?>()
        DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 16)).use { inp ->
            repeat(inp.readInt()) { val (k, t) = readTile(inp, memory); m[k] = t }
        }
        return m
    }

    @Synchronized
    fun writeGrid(g: TileGrid): File {
        val f = File(dir, "v${counter.incrementAndGet()}.grid")
        DataOutputStream(BufferedOutputStream(f.outputStream(), 1 shl 16)).use { out ->
            out.writeInt(g.width); out.writeInt(g.height); out.writeInt(g.defaultValue)
            val e = g.extent
            out.writeInt(e.left); out.writeInt(e.top); out.writeInt(e.right); out.writeInt(e.bottom)
            var n = 0
            for (ty in 0 until g.tilesY) for (tx in 0 until g.tilesX) if (g.tile(tx, ty) != null) n++
            out.writeInt(n)
            for (ty in 0 until g.tilesY) for (tx in 0 until g.tilesX) {
                val t = g.tile(tx, ty) ?: continue
                writeTile(out, ty * g.tilesX + tx, t)
            }
        }
        return f
    }

    @Synchronized
    fun readGrid(f: File): TileGrid {
        DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 16)).use { inp ->
            val w = inp.readInt(); val h = inp.readInt(); val def = inp.readInt()
            val area = IntRect(inp.readInt(), inp.readInt(), inp.readInt(), inp.readInt())
            val g = TileGrid(w, h, def, area = area)
            repeat(inp.readInt()) {
                val (k, t) = readTile(inp, g.memory)
                g.setTile(k % g.tilesX, k / g.tilesX, t)
            }
            return g
        }
    }

    private val tmp = IntArray(Tile.SIZE)

    private fun writeTile(out: DataOutputStream, key: Int, t: Tile?) {
        out.writeInt(key)
        if (t == null) { out.writeByte(0); return }
        t.read(0, tmp, 0, Tile.SIZE)
        val v0 = tmp[0]
        if (tmp.all { it == v0 }) { out.writeByte(1); out.writeInt(v0); return }
        out.writeByte(2)
        buf.clear(); buf.asIntBuffer().put(tmp)
        out.write(buf.array(), 0, Tile.SIZE * 4)
    }

    private fun readTile(inp: DataInputStream, memory: TileMemory): Pair<Int, Tile?> {
        val key = inp.readInt()
        val t = when (inp.readByte().toInt()) {
            0 -> null
            1 -> memory.allocate(inp.readInt())
            else -> {
                inp.readFully(buf.array(), 0, Tile.SIZE * 4)
                buf.clear(); buf.asIntBuffer().get(tmp)
                memory.allocate().also { it.write(0, tmp, 0, Tile.SIZE) }
            }
        }
        return key to t
    }
}
