package de.photographics.gnome

import de.photographics.core.I18n
import de.photographics.core.image.SlabTileMemory
import de.photographics.core.image.TileMemory
import de.photographics.core.tr
import org.gnome.adw.AboutDialog
import org.gnome.adw.Application
import org.gnome.gio.ApplicationFlags
import org.gnome.gio.SimpleAction
import org.gnome.gtk.License
import org.gnome.gtk.RecentManager
import java.io.File
import java.nio.ByteBuffer

const val APP_ID = "de.andreasbartelmess.Photographics"
const val VERSION = "0.4.0"

class PhotographicsApp {
    // Testläufe (Bildschirmfoto) als eigene Instanz, sonst übernimmt eine laufende App die Dateien
    val adw = Application(APP_ID, if (System.getenv("PG_SCREENSHOT") != null) setOf(ApplicationFlags.HANDLES_OPEN, ApplicationFlags.NON_UNIQUE) else setOf(ApplicationFlags.HANDLES_OPEN))
    private val windows = ArrayList<MainWindow>()

    init {
        adw.onStartup { setupStyle() }
        adw.onActivate { (windows.lastOrNull() ?: newWindow()).present() }
        adw.onOpen { files, _ ->
            for (f in files.orEmpty()) {
                val path = f?.local() ?: continue
                // Leeres Fenster wiederverwenden, sonst je Datei ein neues
                val w = windows.firstOrNull { it.session == null } ?: newWindow()
                w.present(); w.openFile(path)
            }
        }
        val quit = SimpleAction("quit", null)
        quit.onActivate { _ -> for (w in windows.toList()) w.window.close() }
        adw.addAction(quit)
        adw.setAccelsForAction("app.quit", arrayOf("<Control>q"))
        val about = SimpleAction("about", null)
        about.onActivate { _ -> showAbout() }
        adw.addAction(about)
        val new = SimpleAction("new-window", null)
        new.onActivate { _ -> newWindow().present() }
        adw.addAction(new)
        adw.setAccelsForAction("app.new-window", arrayOf("<Control>n"))
    }

    /** Eigene Stile und das App-Icon (auch beim Start aus dem Quellbaum ohne Installation). */
    private fun setupStyle() {
        val display = org.gnome.gdk.Display.getDefault() ?: return
        val css = org.gnome.gtk.CssProvider()
        css.loadFromString(
            """
            .layer-thumb, .mask-thumb { background-color: alpha(currentColor, 0.08); border-radius: 3px; }
            .thumb-frame { padding: 2px; border-radius: 5px; }
            .thumb-selected { box-shadow: inset 0 0 0 2px @accent_color; }
            button.eye, button.disclosure { padding: 2px; min-width: 24px; min-height: 24px; }
            .layer-list row { padding-top: 0; padding-bottom: 0; }
            .layer-list row.drop-above { box-shadow: inset 0 2px 0 @accent_color; }
            .layer-list row.drop-below { box-shadow: inset 0 -2px 0 @accent_color; }
            .layer-list row.drop-into { background-color: alpha(@accent_color, 0.25); }
            .layer-list row.hidden-layer .layer-thumb, .layer-list row.hidden-layer .mask-thumb { opacity: 0.45; }
            .tool-palette button { padding: 6px; }
            .toolbar-scroller { min-height: 46px; }
            .toolbar-scroller > scrollbar { opacity: 0; }
            .options-bar { padding-top: 0; padding-bottom: 0; }
            .options-bar spinbutton > button { min-width: 20px; padding-left: 3px; padding-right: 3px; }
            .options-bar spinbutton > text { padding-left: 6px; padding-right: 2px; }
            .options-bar dropdown > button, .options-bar menubutton > button { padding-left: 8px; padding-right: 6px; }
            .sidebar-tabs > button { padding-top: 4px; padding-bottom: 4px; }
            .gallery-preview { background-color: alpha(currentColor, 0.04); padding: 12px; }
            .gallery-item { padding: 4px; }
            .gallery-thumb { border-radius: 6px; }
            .wordmark { font-family: "Adwaita Sans", sans-serif; font-weight: 400; font-size: 32px; letter-spacing: -0.3px; }
            button.card.preset-card { padding: 12px; }
            button.card.recent-card { padding: 0; }
            .recent-card .recent-thumb { background-color: alpha(currentColor, 0.06); border-radius: 12px 12px 0 0; }
            .recent-card overlay { border-radius: 12px 12px 0 0; }
            """.trimIndent()
        )
        org.gnome.gtk.Gtk.styleContextAddProviderForDisplay(display, css, org.gnome.gtk.Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        runCatching {
            // Ohne index.theme sucht GTK Symbole direkt im Suchpfad (nicht in hicolor/…)
            val dir = File(FileIO.cacheDir, "icons").apply { deleteRecursively(); mkdirs() }
            val actionIcons = (Tool.entries.map { it.icon } + listOf("pg-mask-symbolic", "pg-fx-symbolic", "pg-adjust-symbolic", "pg-smart-symbolic", "pg-ai-remove-symbolic", "pg-lock-alpha-symbolic", "pg-clip-symbolic", "pg-hidden-symbolic")).distinct()
            for (name in listOf("scalable/apps/$APP_ID.svg", "symbolic/apps/$APP_ID-symbolic.svg") + actionIcons.map { "symbolic/actions/$it.svg" }) {
                val out = File(dir, name.substringAfterLast('/'))
                PhotographicsApp::class.java.getResourceAsStream("/icons/hicolor/$name")?.use { i -> out.outputStream().use { i.copyTo(it) } }
            }
            org.gnome.gtk.IconTheme.getForDisplay(display).addSearchPath(dir.path)
        }
        org.gnome.gtk.Window.setDefaultIconName(APP_ID)
    }

    fun newWindow(): MainWindow {
        val w = MainWindow(this)
        windows += w
        w.window.onDestroy { windows -= w }
        return w
    }

    /** Zuletzt verwendete Dateien (GNOME-weit, z. B. im Dateiauswahldialog). */
    fun recent(f: File) {
        runCatching { RecentManager.getDefault().addItem(f.toURI().toString()) }
    }

    private fun showAbout() {
        val d = AboutDialog()
        d.applicationName = "photographics"
        d.applicationIcon = APP_ID
        d.version = VERSION
        d.developerName = "Andreas Bartelmess"
        d.comments = tr("Ebenenbasierte Bildbearbeitung für GNOME")
        d.website = "https://photographics.andreasbartelmess.de"
        d.issueUrl = "https://github.com/andreasbartelmess/photographics/issues"
        d.licenseType = License.GPL_3_0
        d.copyright = "© 2026 Andreas Bartelmess"
        d.present(adw.activeWindow)
    }

    fun run(args: Array<String>): Int {
        System.getenv("PG_SCREENSHOT")?.let { path -> screenshotLater(File(path)) }
        return adw.run(arrayOf("photographics") + args)
    }

    /**
     * Für Tests und Bilder der Website: rendert das Fenster nach einer Wartezeit in eine PNG-Datei
     * und beendet die App (`PG_SCREENSHOT=datei.png`, Wartezeit `PG_SCREENSHOT_DELAY` in ms).
     */
    private fun screenshotLater(out: File) {
        val delay = System.getenv("PG_SCREENSHOT_DELAY")?.toIntOrNull() ?: 3000
        // Wie auf einem Bildschirm mit dieser Skalierung (Kachelkanten liegen dann auf Gerätepixeln)
        val scale = System.getenv("PG_SCREENSHOT_SCALE")?.toDoubleOrNull() ?: 1.0
        org.gnome.glib.GLib.timeoutAddOnce(100) { windows.lastOrNull()?.debugScale(scale) }
        // Fensteraktionen nacheinander auslösen (z. B. PG_ACTIONS=new-layer,duplicate-layer,undo)
        System.getenv("PG_ACTIONS")?.split(',')?.filter { it.isNotBlank() }?.forEachIndexed { i, name ->
            org.gnome.glib.GLib.timeoutAddOnce(delay / 3 + i * 300) {
                val n = name.trim()
                if (n.startsWith("shot:")) shoot(File(out.parentFile, n.substring(5) + ".png")) else windows.lastOrNull()?.debugAction(n)
            }
        }
        System.getenv("PG_SCREENSHOT_ZOOM")?.toDoubleOrNull()?.let { z ->
            org.gnome.glib.GLib.timeoutAddOnce(delay / 2) { windows.lastOrNull()?.debugZoom(z) }
        }
        org.gnome.glib.GLib.timeoutAddOnce(delay) {
            shoot(out, scale)
            adw.quit()
        }
    }

    /** Fenster jetzt als PNG speichern (auch als Testaktion „shot:name“ mitten im Ablauf). */
    fun shoot(out: File, scale: Double = System.getenv("PG_SCREENSHOT_SCALE")?.toDoubleOrNull() ?: 1.0) {
        run {
            val win = windows.lastOrNull()?.window
            if (win != null) {
                val paintable = org.gnome.gtk.WidgetPaintable(win)
                val snap = org.gnome.gtk.Snapshot()
                snap.scale(scale.toFloat(), scale.toFloat())
                paintable.snapshot(snap, win.width.toDouble(), win.height.toDouble())
                val node = snap.toNode()
                val vp = org.gnome.graphene.Rect().init(0f, 0f, (win.width * scale).toFloat(), (win.height * scale).toFloat())
                val tex = node?.let { win.renderer?.renderTexture(it, vp) }
                if (tex?.saveToPng(out.path) == true) println("Bildschirmfoto: $out") else System.err.println("Bildschirmfoto fehlgeschlagen (Knoten ${node != null}, Renderer ${win.renderer != null}, Textur ${tex != null}, ${win.width}x${win.height})")
            }
        }
    }
}

fun main(args: Array<String>) {
    org.gnome.glib.GLib.setApplicationName("photographics")
    I18n.lang = when (Settings.language) { Settings.Language.DE -> I18n.Lang.DE; Settings.Language.EN -> I18n.Lang.EN; else -> systemLanguage() }
    de.photographics.core.doc.TextRasterizer.current = PangoText
    TileMemory.current = SlabTileMemory(
        limitBytes = tileBudget(),
        newSlab = { bytes -> ByteBuffer.allocateDirect(bytes) },
    )
    System.exit(PhotographicsApp().run(args))
}

private fun systemLanguage(): I18n.Lang {
    val l = listOf("LANGUAGE", "LC_ALL", "LC_MESSAGES", "LANG").firstNotNullOfOrNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() && v != "C" && v != "POSIX" } }
    return if (l == null || l.startsWith("de")) I18n.Lang.DE else I18n.Lang.EN
}

/** Pixelspeicher: 40 % des Arbeitsspeichers, höchstens so viel, wie die JVM außerhalb des Heaps darf. */
private fun tileBudget(): Long {
    val total = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: (8L shl 30)
    return (total * 0.4).toLong().coerceIn(512L shl 20, 14L shl 30)
}
