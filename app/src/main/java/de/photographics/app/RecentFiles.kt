package de.photographics.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** Zuletzt geöffnete Dateien (mit Vorschaubild) für den Startbildschirm. */
class RecentFiles(context: Context) {
    data class Entry(val uri: Uri, val name: String, val time: Long, val thumb: File, val info: String)

    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "recent").apply { mkdirs() }

    fun list(): List<Entry> = (prefs.getString("list", "") ?: "").lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 4) null else Entry(Uri.parse(p[0]), p[1], p[2].toLongOrNull() ?: 0, File(dir, thumbName(p[0])), p[3])
    }

    fun add(uri: Uri, name: String, info: String, thumbnail: Bitmap?) {
        val key = uri.toString()
        thumbnail?.let { b -> runCatching { File(dir, thumbName(key)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } } }
        val rest = list().filter { it.uri.toString() != key }
        val all = listOf(Entry(uri, name, System.currentTimeMillis(), File(dir, thumbName(key)), info)) + rest
        prefs.edit().putString("list", all.take(12).joinToString("\n") { "${it.uri}\t${it.name.replace('\t', ' ')}\t${it.time}\t${it.info}" }).apply()
    }

    fun remove(uri: Uri) {
        val key = uri.toString()
        File(dir, thumbName(key)).delete()
        prefs.edit().putString("list", list().filter { it.uri.toString() != key }.joinToString("\n") { "${it.uri}\t${it.name}\t${it.time}\t${it.info}" }).apply()
    }

    fun loadThumb(e: Entry): Bitmap? = if (e.thumb.exists()) BitmapFactory.decodeFile(e.thumb.absolutePath) else null

    private fun thumbName(key: String) = "t" + Integer.toHexString(key.hashCode()) + ".png"
}
