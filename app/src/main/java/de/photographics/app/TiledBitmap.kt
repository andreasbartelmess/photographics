package de.photographics.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.Message
import java.util.concurrent.CountDownLatch

/**
 * Anzeige-Bitmap in Kacheln: Android lädt eine geänderte Bitmap vollständig neu in die GPU –
 * bei einer einzigen großen Bitmap also 24 MB für jeden Pinselabdruck. In Kacheln werden nur
 * die geänderten neu geladen. Schnittstelle wie [Bitmap] (setPixels, eraseColor).
 *
 * Schreiben aus dem Rechen-Thread landet zunächst in [DisplayWrites] und wird erst mit
 * [DisplayWrites.flush] auf dem Hauptthread übernommen (siehe dort).
 */
class TiledBitmap(val width: Int, val height: Int) {
    private val cols = (width + TILE - 1) / TILE
    private val rows = (height + TILE - 1) / TILE
    private val tiles = Array(cols * rows) { i ->
        val tx = i % cols; val ty = i / cols
        Bitmap.createBitmap(minOf(TILE, width - tx * TILE), minOf(TILE, height - ty * TILE), Bitmap.Config.ARGB_8888)
    }

    /** Kachel sicher deckend (dann braucht sie kein Schachbrett darunter). */
    private val opaque = BooleanArray(tiles.size)

    fun setPixels(px: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        if (!DisplayWrites.onMain()) {
            // Ausschnitt kopieren: der Aufrufer darf seinen Puffer gleich wiederverwenden
            val cw = maxOf(0, w); val ch = maxOf(0, h)
            val copy = IntArray(cw * ch)
            for (row in 0 until ch) System.arraycopy(px, offset + row * stride, copy, row * cw, cw)
            DisplayWrites.add(copy.size) { applyPixels(copy, 0, cw, x, y, cw, ch) }
            return
        }
        applyPixels(px, offset, stride, x, y, w, h)
    }

    private fun applyPixels(px: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        val x0 = maxOf(0, x); val y0 = maxOf(0, y)
        val x1 = minOf(width, x + w); val y1 = minOf(height, y + h)
        if (x0 >= x1 || y0 >= y1) return
        for (ty in y0 / TILE..(y1 - 1) / TILE) for (tx in x0 / TILE..(x1 - 1) / TILE) {
            val l = maxOf(x0, tx * TILE); val t = maxOf(y0, ty * TILE)
            val r = minOf(x1, (tx + 1) * TILE); val b = minOf(y1, (ty + 1) * TILE)
            val i = ty * cols + tx
            val o = offset + (t - y) * stride + (l - x)
            tiles[i].setPixels(px, o, stride, l - tx * TILE, t - ty * TILE, r - l, b - t)
            // Deckend bleibt deckend, solange nur deckende Pixel kommen; wieder deckend erst, wenn die
            // ganze Kachel deckend überschrieben wird (vorsichtig: im Zweifel Schachbrett zeichnen)
            val all = allOpaque(px, o, stride, r - l, b - t)
            val whole = r - l == tiles[i].width && b - t == tiles[i].height
            opaque[i] = all && (opaque[i] || whole)
        }
    }

    private fun allOpaque(px: IntArray, offset: Int, stride: Int, w: Int, h: Int): Boolean {
        for (yy in 0 until h) {
            var o = offset + yy * stride
            val end = o + w
            while (o < end) { if (px[o] ushr 24 != 0xFF) return false; o++ }
        }
        return true
    }

    fun eraseColor(c: Int) {
        if (!DisplayWrites.onMain()) { DisplayWrites.add(width * height / 16) { eraseColor(c) }; return }
        for (t in tiles) t.eraseColor(c); opaque.fill(c ushr 24 == 0xFF)
    }

    /** Zeichnet [paint] (Schachbrett) unter allen sichtbaren, nicht sicher deckenden Kacheln; Lage wie bei [draw]. */
    fun drawBackdrop(canvas: Canvas, left: Float, top: Float, scale: Float, clip: RectF, paint: Paint) {
        val dst = RectF()
        for (ty in 0 until rows) for (tx in 0 until cols) {
            val i = ty * cols + tx
            if (opaque[i]) continue
            dst.set(left + tx * TILE * scale, top + ty * TILE * scale, left + (tx * TILE + tiles[i].width) * scale, top + (ty * TILE + tiles[i].height) * scale)
            if (RectF.intersects(dst, clip)) canvas.drawRect(dst, paint)
        }
    }

    /**
     * Zeichnet die Kacheln so, dass ein Pixel [scale] Einheiten von [canvas] belegt, ab ([left], [top]);
     * nur Kacheln, die [clip] (in Einheiten von [canvas]) schneiden.
     */
    fun draw(canvas: Canvas, left: Float, top: Float, scale: Float, clip: RectF, paint: Paint) {
        val dst = RectF()
        for (ty in 0 until rows) for (tx in 0 until cols) {
            val tile = tiles[ty * cols + tx]
            dst.set(left + tx * TILE * scale, top + ty * TILE * scale, left + (tx * TILE + tile.width) * scale, top + (ty * TILE + tile.height) * scale)
            if (!RectF.intersects(dst, clip)) continue
            canvas.drawBitmap(tile, null as Rect?, dst, paint)
        }
    }

    companion object {
        const val TILE = 512
    }
}

/**
 * Übergabe an die Anzeige ohne Tearing: Android lädt geänderte Bitmaps beim Synchronisieren des
 * nächsten Bildes auf die GPU. Schriebe der Rechen-Thread gleichzeitig hinein, zeigte ein Bild oben
 * schon den neuen, unten noch den alten Stand (z. B. eine halb verschobene Ebene). Deshalb sammelt
 * der Rechen-Thread einen ganzen Render-Durchgang und [flush] spielt ihn auf dem Hauptthread
 * zwischen zwei Bildern auf einmal ein. Sehr große Durchgänge werden in Teilen übergeben, damit
 * der Zwischenspeicher klein bleibt.
 */
object DisplayWrites {
    private const val FLUSH_PIXELS = 2_500_000L
    private val main = Handler(Looper.getMainLooper())
    private val ops = ArrayList<() -> Unit>()
    private var pixels = 0L

    fun onMain() = Looper.myLooper() === Looper.getMainLooper()

    /** Nur vom Rechen-Thread (ein einziger Thread schreibt). */
    fun add(n: Int, op: () -> Unit) {
        synchronized(ops) { ops += op; pixels += n }
        if (pixels >= FLUSH_PIXELS) flush()
    }

    /** Ausstehendes auf dem Hauptthread anwenden; wartet, bis es übernommen ist. */
    fun flush() {
        val batch = synchronized(ops) { if (ops.isEmpty()) return; ops.toList().also { ops.clear(); pixels = 0 } }
        if (onMain()) { batch.forEach { it() }; return }
        val done = CountDownLatch(1)
        // Asynchron: läuft auch vor einer wartenden Layout-Sperre (Sync-Barrier) der Oberfläche
        main.sendMessage(Message.obtain(main) { try { batch.forEach { it() } } finally { done.countDown() } }.apply { isAsynchronous = true })
        done.await()
    }
}
