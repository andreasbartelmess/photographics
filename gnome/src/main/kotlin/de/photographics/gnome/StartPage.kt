package de.photographics.gnome

import de.photographics.core.doc.DocPreset
import de.photographics.core.doc.DocPresets
import de.photographics.core.tr
import org.gnome.gdk.Texture
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.Overlay
import org.gnome.gtk.Picture
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.Widget
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

/** Zuletzt verwendete Dateien mit Vorschaubild (Liste von GNOME, Vorschaubilder von photographics). */
object Recent {
    class Entry(val file: File, val time: Long)

    private val dir get() = File(FileIO.cacheDir, "recent").apply { mkdirs() }

    /** Für Bildschirmfotos: statt der echten Liste die Bilder dieses Ordners (die echten Namen sind privat). */
    private val demoDir = System.getenv("PG_SCREENSHOT_RECENT")?.let(::File)

    fun list(max: Int = 12): List<Entry> {
        demoDir?.let { d -> return d.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.take(max).map { Entry(it, it.lastModified()) } }
        if (System.getenv("PG_SCREENSHOT") != null) return emptyList()
        return runCatching {
            org.gnome.gtk.RecentManager.getDefault().items.filterNotNull()
                .filter { it.hasApplication("photographics") && it.isLocal && it.exists() }
                .sortedByDescending { it.modified?.toUnix() ?: 0L }.take(max)
                .mapNotNull { info -> runCatching { Entry(File(java.net.URI(info.uri)), (info.modified?.toUnix() ?: 0L) * 1000) }.getOrNull() }
        }.getOrNull().orEmpty()
    }

    fun remove(f: File) {
        runCatching { org.gnome.gtk.RecentManager.getDefault().removeItem(f.toURI().toString()) }
        thumbFile(f).delete()
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun thumbFile(f: File) = File(dir, md5(f.absoluteFile.toURI().toString()) + ".png")

    /** Eigenes Vorschaubild, sonst das von GNOME (Dateien), sonst keins. */
    fun thumbnail(f: File): Texture? {
        val uri = "file://" + f.absolutePath.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val home = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.cache")
        val candidates = listOf(thumbFile(f)) + listOf("x-large", "large", "normal").map { File("$home/thumbnails/$it/${md5(uri)}.png") }
        for (c in candidates) if (c.isFile) runCatching { return Texture.fromFilename(c.path) }
        return null
    }

    /** Vorschaubild speichern (im Hauptthread; [px] ARGB, höchstens etwa 512 px). */
    fun storeThumb(f: File, w: Int, h: Int, px: IntArray) {
        val t = org.gnome.gdk.MemoryTexture(w, h, org.gnome.gdk.MemoryFormat.B8G8R8A8, CanvasView.toBytes(px), w * 4L)
        runCatching { t.saveToPng(thumbFile(f).path) }
    }
}

/**
 * Startbildschirm eines leeren Fensters: Öffnen, neues Dokument aus Vorlagen (wie die Android-App)
 * und die zuletzt verwendeten Dateien als Karten mit Vorschaubild.
 */
class StartPage(
    private val onOpen: () -> Unit,
    private val onCustomNew: () -> Unit,
    private val onNew: (DocPreset) -> Unit,
    private val onOpenFile: (File) -> Unit,
) {
    val widget = ScrolledWindow()
    private val recentBox = Box(Orientation.VERTICAL, 12)

    init {
        widget.setPolicy(org.gnome.gtk.PolicyType.NEVER, org.gnome.gtk.PolicyType.AUTOMATIC)
        val col = Box(Orientation.VERTICAL, 28).apply { marginTop = 36; marginBottom = 36; marginStart = 24; marginEnd = 24 }

        // Kopf: Logo, Wortmarke, Untertitel – darunter die Hauptaktionen
        col.append(Box(Orientation.HORIZONTAL, 16).apply {
            append(org.gnome.gtk.Image.fromIconName(APP_ID).apply { pixelSize = 64 })
            append(Box(Orientation.VERTICAL, 2).apply {
                valign = Align.CENTER
                append(Label("photographics").apply { xalign = 0f; addCssClass("wordmark") })
                append(Label(tr("Ebenenbasierte Bildbearbeitung für GNOME")).apply { xalign = 0f; addCssClass("dim-label") })
            })
        })
        col.append(Box(Orientation.HORIZONTAL, 12).apply {
            append(Button().apply {
                child = org.gnome.adw.ButtonContent().apply { iconName = "document-open-symbolic"; label = tr("Öffnen …") }
                addCssClass("pill"); addCssClass("suggested-action"); onClicked { onOpen() }
            })
            append(Button().apply {
                child = org.gnome.adw.ButtonContent().apply { iconName = "list-add-symbolic"; label = tr("Eigene Größe …") }
                addCssClass("pill"); onClicked { onCustomNew() }
            })
        })

        col.append(recentBox)

        col.append(Label(tr("Neues Dokument")).apply { xalign = 0f; addCssClass("title-3") })
        for ((group, presets) in DocPresets.groups) {
            col.append(Box(Orientation.VERTICAL, 8).apply {
                append(Label(group).apply { xalign = 0f; addCssClass("heading"); addCssClass("dim-label") })
                // Je Gruppe eine Reihe gleich großer Karten, seitlich rollbar
                val row = Box(Orientation.HORIZONTAL, 12).apply { marginBottom = 6 }
                for (p in presets) row.append(presetCard(p))
                append(ScrolledWindow().apply {
                    setPolicy(org.gnome.gtk.PolicyType.AUTOMATIC, org.gnome.gtk.PolicyType.NEVER)
                    child = row
                    propagateNaturalHeight = true
                })
            })
        }
        widget.child = org.gnome.adw.Clamp().apply { maximumSize = 1180; tighteningThreshold = 900; child = col }
        refresh()
    }

    /** Liste der zuletzt verwendeten Dateien neu aufbauen (beim Anzeigen des Startbildschirms). */
    fun refresh() {
        while (true) { val c = recentBox.firstChild ?: break; recentBox.remove(c) }
        val items = Recent.list()
        recentBox.visible = items.isNotEmpty()
        if (items.isEmpty()) return
        recentBox.append(Label(tr("Zuletzt verwendet")).apply { xalign = 0f; addCssClass("title-3") })
        val row = Box(Orientation.HORIZONTAL, 12).apply { marginBottom = 6 }
        for (e in items) row.append(recentCard(e))
        recentBox.append(ScrolledWindow().apply {
            setPolicy(org.gnome.gtk.PolicyType.AUTOMATIC, org.gnome.gtk.PolicyType.NEVER)
            child = row
            propagateNaturalHeight = true
        })
    }

    private fun presetCard(p: DocPreset): Widget {
        val b = Button().apply { addCssClass("card"); addCssClass("preset-card"); tooltipText = "${p.label} · ${p.w} × ${p.h} px · ${p.dpi.toInt()} dpi" }
        val box = Box(Orientation.VERTICAL, 4).apply { setSizeRequest(148, -1) }
        // Seitenverhältnis in einen festen Rahmen eingepasst: alle Karten gleich hoch
        box.append(org.gnome.gtk.DrawingArea().apply {
            contentHeight = 60; marginBottom = 6
            setDrawFunc { area, cr, w, h ->
                val ratio = p.w.toDouble() / p.h
                val maxW = 88.0; val maxH = 52.0
                val (bw, bh) = if (ratio >= maxW / maxH) maxW to maxW / ratio else maxH * ratio to maxH
                val x = (w - bw) / 2; val y = (h - bh) / 2
                val c = org.gnome.gdk.RGBA().also { area.getColor(it) }
                cr.setSourceRGBA(c.readRed().toDouble(), c.readGreen().toDouble(), c.readBlue().toDouble(), 0.06)
                cr.rectangle(x, y, bw, bh); cr.fill()
                cr.setSourceRGBA(c.readRed().toDouble(), c.readGreen().toDouble(), c.readBlue().toDouble(), 0.7)
                cr.setLineWidth(1.5)
                cr.rectangle(x + 0.75, y + 0.75, bw - 1.5, bh - 1.5); cr.stroke()
            }
        })
        box.append(Label(p.label).apply { xalign = 0f; ellipsize = org.gnome.pango.EllipsizeMode.END; addCssClass("heading") })
        box.append(Label(p.sizeLabel).apply { xalign = 0f; addCssClass("caption"); addCssClass("dim-label") })
        b.child = box
        b.onClicked { onNew(p) }
        return b
    }

    private fun recentCard(e: Recent.Entry): Widget {
        val b = Button().apply { addCssClass("card"); addCssClass("recent-card"); tooltipText = e.file.path }
        val box = Box(Orientation.VERTICAL, 0).apply { setSizeRequest(232, -1) }
        val pic = Picture().apply {
            contentFit = org.gnome.gtk.ContentFit.COVER
            canShrink = true
            setSizeRequest(232, 154)
            addCssClass("recent-thumb")
            Recent.thumbnail(e.file)?.let { paintable = it }
        }
        val overlay = Overlay().apply { child = pic }
        if (pic.paintable == null) overlay.addOverlay(org.gnome.gtk.Image.fromIconName("image-x-generic-symbolic").apply { pixelSize = 48; addCssClass("dim-label") })
        overlay.addOverlay(Button.fromIconName("window-close-symbolic").apply {
            halign = Align.END; valign = Align.START; marginTop = 6; marginEnd = 6
            addCssClass("circular"); addCssClass("osd")
            tooltipText = tr("Aus der Liste entfernen")
            onClicked { Recent.remove(e.file); refresh() }
        })
        box.append(overlay)
        box.append(Box(Orientation.VERTICAL, 2).apply {
            marginTop = 10; marginBottom = 12; marginStart = 12; marginEnd = 12
            append(Label(e.file.name).apply { xalign = 0f; ellipsize = org.gnome.pango.EllipsizeMode.MIDDLE; maxWidthChars = 1; addCssClass("heading") })
            append(Label(e.file.parentFile?.path?.replaceFirst(System.getProperty("user.home"), "~") ?: "").apply {
                xalign = 0f; ellipsize = org.gnome.pango.EllipsizeMode.START; maxWidthChars = 1; addCssClass("caption"); addCssClass("dim-label")
            })
            if (e.time > 0) append(Label(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.time))).apply {
                xalign = 0f; addCssClass("caption"); addCssClass("dim-label")
            })
        })
        // Das Vorschaubild gibt seine volle Größe als Wunschbreite an: auf Kartenbreite begrenzen
        b.child = org.gnome.adw.Clamp().apply { maximumSize = 232; tighteningThreshold = 232; child = box }
        b.hexpand = false
        b.onClicked { onOpenFile(e.file) }
        return b
    }
}
