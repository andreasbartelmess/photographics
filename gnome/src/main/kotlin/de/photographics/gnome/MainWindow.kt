package de.photographics.gnome

import de.photographics.core.adjust.AdjustmentType
import de.photographics.core.color.IccProfiles
import de.photographics.core.color.RenderingIntent
import de.photographics.core.doc.Document
import de.photographics.core.doc.Layer
import de.photographics.core.doc.RasterLayer
import de.photographics.core.doc.TextStyle
import de.photographics.core.editor.Editor
import de.photographics.core.history.ScratchDisk
import de.photographics.core.image.IntRect
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import de.photographics.core.tools.PaintTarget
import de.photographics.core.tr
import org.gnome.adw.AlertDialog
import org.gnome.adw.ApplicationWindow
import org.gnome.adw.HeaderBar
import org.gnome.adw.OverlaySplitView
import org.gnome.adw.ResponseAppearance
import org.gnome.adw.Toast
import org.gnome.adw.ToastOverlay
import org.gnome.adw.ToolbarView
import org.gnome.adw.WindowTitle
import org.gnome.gdk.DragAction
import org.gnome.gdk.MemoryFormat
import org.gnome.gdk.MemoryTexture
import org.gnome.gdk.ModifierType
import org.gnome.gdk.Texture
import org.gnome.gio.ListStore
import org.gnome.gio.Menu
import org.gnome.gio.SimpleAction
import org.gnome.glib.GLib
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.DropTarget
import org.gnome.gtk.EventControllerKey
import org.gnome.gtk.FileDialog
import org.gnome.gtk.FileFilter
import org.gnome.gtk.MenuButton
import org.gnome.gtk.Orientation
import org.gnome.gtk.PackType
import org.gnome.gtk.PropagationPhase
import org.gnome.gtk.Spinner
import org.gnome.gtk.Stack
import org.gnome.gtk.ToggleButton
import java.io.File

/** Ein Fenster je Dokument. */
class MainWindow(private val app: PhotographicsApp) : Controller.Ui {
    val window = ApplicationWindow(app.adw)
    private val title = WindowTitle("photographics", "")
    val canvas = CanvasView()
    val c = Controller(canvas, this)
    private val layers = LayersPanel(LayerActions())
    private val sideStack = Stack()
    private val tabs = Stack()
    private val tabs2 = Stack()
    private val swatches = SwatchesPanel({ window }, { col, bg -> c.setOptions { if (bg) it.copy(bgColor = col or 0xFF000000.toInt()) else it.copy(color = col or 0xFF000000.toInt()) } }, { c.opts.color })
    private val history = HistoryPanel { steps -> if (c.transforming) c.cancelTransform(); c.edit(null) { e -> var r: IntRect? = IntRect.EMPTY; repeat(steps) { r = null; e.undo() }; r } }
    private val props = PropertiesPanel { sideStack.visibleChildName = "layers" }
    private val paths = PathsPanel(c) { window }
    private val channels = ChannelsPanel(c, canvas) { window }
    private val palette = ToolPalette({ t -> c.setOptions { it.copy(tool = t) } }, ::pickColor, ::swapColors, ::resetColors)
    private val options = OptionsBar({ f -> c.setOptions(f) }, { c.confirm() }, { c.cancel() })
    private val optionsBar = org.gnome.gtk.ScrolledWindow()
    private val split = OverlaySplitView()
    private val stack = Stack()
    private val toasts = ToastOverlay()
    private val zoomLabel = Button.withLabel("100 %")
    private val spinner = Spinner()
    private val actions = HashMap<String, SimpleAction>()
    private val rulerTop = Ruler(false, canvas)
    private val rulerLeft = Ruler(true, canvas)
    private val rulerCorner = Box(Orientation.HORIZONTAL, 0)

    private fun showRulers(on: Boolean) { rulerTop.visible = on; rulerLeft.visible = on; rulerCorner.visible = on }

    val session: Session? get() = c.session
    private val state: DocState? get() = c.state
    private var thumbs = HashMap<Int, Texture>()
    private var closing = false
    private var busyCount = 0

    /** Nur mit offenem Dokument sichtbar (Kopfleiste). */
    private val docWidgets = ArrayList<org.gnome.gtk.Widget>()
    private fun <W : org.gnome.gtk.Widget> docOnly(w: W): W { docWidgets += w; w.visible = false; return w }

    private val start = StartPage(
        onOpen = { chooseOpen() },
        onCustomNew = { actions["new"]?.activate(null) },
        onNew = { p -> newDocument(p.w, p.h, false, p.dpi) },
        onOpenFile = { f -> openFile(f) },
    )

    init {
        window.setDefaultSize(1400, 880)
        window.title = "photographics"

        val header = HeaderBar()
        header.titleWidget = title
        header.packStart(Button.fromIconName("document-open-symbolic").apply { actionName = "win.open"; tooltipText = tr("Öffnen …") + " (Strg+O)" })
        header.packStart(docOnly(Button.fromIconName("document-save-symbolic").apply { actionName = "win.save"; tooltipText = tr("Speichern") + " (Strg+S)" }))
        header.packStart(docOnly(Box(Orientation.HORIZONTAL, 0).apply {
            addCssClass("linked")
            append(Button.fromIconName("edit-undo-symbolic").apply { actionName = "win.undo"; tooltipText = tr("Rückgängig") + " (Strg+Z)" })
            append(Button.fromIconName("edit-redo-symbolic").apply { actionName = "win.redo"; tooltipText = tr("Wiederholen") + " (Strg+Umschalt+Z)" })
        }))
        header.packStart(docOnly(MenuButton().apply { label = tr("Bearbeiten"); menuModel = editMenu() }))
        header.packStart(docOnly(MenuButton().apply { label = tr("Bild"); menuModel = imageMenu() }))
        header.packStart(docOnly(MenuButton().apply { label = tr("Ebene"); menuModel = layerMenu() }))
        header.packStart(docOnly(MenuButton().apply { label = tr("Auswahl"); menuModel = selectMenu() }))
        header.packStart(docOnly(MenuButton().apply { label = tr("Korrekturen"); menuModel = adjustMenu() }))
        header.packStart(docOnly(MenuButton().apply { label = tr("Filter"); menuModel = filterMenu() }))
        spinner.visible = false
        header.packStart(spinner)

        header.packEnd(MenuButton().apply { iconName = "open-menu-symbolic"; menuModel = mainMenu(); primary = true; tooltipText = tr("Hauptmenü") })
        header.packEnd(docOnly(ToggleButton().apply {
            iconName = "sidebar-show-right-symbolic"; tooltipText = tr("Ebenen") + " (F7)"
            active = true
            bindProperty("active", split, "show-sidebar", org.gnome.gobject.BindingFlags.BIDIRECTIONAL)
        }))
        zoomLabel.addCssClass("flat"); zoomLabel.addCssClass("numeric")
        zoomLabel.actionName = "win.zoom-fit"; zoomLabel.tooltipText = tr("Einpassen") + " (Strg+0)"
        header.packEnd(docOnly(Box(Orientation.HORIZONTAL, 0).apply {
            append(Button.fromIconName("zoom-out-symbolic").apply { actionName = "win.zoom-out"; tooltipText = tr("Verkleinern") + " (Strg+−)"; addCssClass("flat") })
            append(zoomLabel)
            append(Button.fromIconName("zoom-in-symbolic").apply { actionName = "win.zoom-in"; tooltipText = tr("Vergrößern") + " (Strg++)"; addCssClass("flat") })
        }))

        val empty = start.widget
        tabs.addTitled(layers.widget, "layers", tr("Ebenen"))
        tabs.addTitled(channels.widget, "channels", tr("Kanäle"))
        tabs.addTitled(paths.widget, "paths", tr("Pfade"))
        tabs.onNotify("visible-child-name") { _ -> c.pathsVisible = tabs.visibleChildName == "paths" }
        tabs.vexpand = true
        // Wie Photoshops Arbeitsbereich „Grundelemente“: Farbfelder oben, Ebenen darunter (Höhe verschiebbar)
        tabs2.addTitled(swatches.widget, "swatches", tr("Farbfelder"))
        tabs2.addTitled(history.widget, "history", tr("Protokoll"))
        tabs2.vexpand = true
        fun group(st: Stack) = Box(Orientation.VERTICAL, 0).apply {
            append(org.gnome.gtk.StackSwitcher().apply { stack = st; hexpand = true; marginStart = 12; marginEnd = 12; marginTop = 10; marginBottom = 4; addCssClass("sidebar-tabs") })
            append(st)
        }
        val side = org.gnome.gtk.Paned(Orientation.VERTICAL).apply {
            startChild = group(tabs2); endChild = group(tabs)
            resizeStartChild = false; resizeEndChild = true; shrinkStartChild = false; shrinkEndChild = false
            position = 250
        }
        sideStack.addNamed(side, "layers")
        sideStack.addNamed(props.widget, "props")
        sideStack.transitionType = org.gnome.gtk.StackTransitionType.CROSSFADE
        split.sidebarPosition = PackType.END
        val work = Box(Orientation.HORIZONTAL, 0)
        work.append(palette.widget)
        work.append(org.gnome.gtk.Separator(Orientation.VERTICAL))
        // Lineale oben und links (Strg+R), daraus Hilfslinien ziehen
        tabs.addCssClass("side-tabs")
        val grid = org.gnome.gtk.Grid()
        grid.attach(rulerCorner, 0, 0, 1, 1)
        grid.attach(rulerTop, 1, 0, 1, 1)
        grid.attach(rulerLeft, 0, 1, 1, 1)
        grid.attach(canvas, 1, 1, 1, 1)
        rulerTop.hexpand = true; rulerLeft.vexpand = true
        rulerCorner.setSizeRequest(Ruler.SIZE, Ruler.SIZE)
        rulerTop.onGuideDrag = { ph, pos -> c.rulerDrag(false, ph, pos) }
        rulerLeft.onGuideDrag = { ph, pos -> c.rulerDrag(true, ph, pos) }
        canvas.onDrawn = { rulerTop.queueDraw(); rulerLeft.queueDraw() }
        showRulers(Settings.showRulers)
        work.append(grid)
        split.content = work
        split.sidebar = sideStack
        split.minSidebarWidth = 280.0
        split.maxSidebarWidth = 360.0
        stack.addNamed(empty, "empty")
        stack.addNamed(split, "doc")
        stack.visibleChildName = "empty"
        toasts.child = stack

        val tv = ToolbarView()
        tv.addTopBar(header)
        // Optionsleiste waagerecht rollbar, damit sie keine Mindestbreite des Fensters erzwingt
        optionsBar.setPolicy(org.gnome.gtk.PolicyType.EXTERNAL, org.gnome.gtk.PolicyType.NEVER)
        optionsBar.child = options.widget
        optionsBar.propagateNaturalHeight = true
        optionsBar.addCssClass("toolbar-scroller")
        tv.addTopBar(optionsBar)
        tv.content = toasts
        window.content = tv
        optionsBar.visible = false

        canvas.onZoomChanged = { z -> zoomLabel.label = "${Math.round(z * 100)} %" }
        canvas.input = c
        c.onContext = { x, y -> brushPicker(x, y) }
        canvas.overlay = { sn -> c.drawOverlay(sn) }
        // Laufende Ameisen
        GLib.timeoutAdd(0, 160) {
            if (state?.hasSelection == true && stack.visibleChildName == "doc") { c.antsPhase = (c.antsPhase + 1f) % 8f; canvas.queueDraw() }
            !closing
        }

        val drop = DropTarget(org.gnome.gio.File.getType(), DragAction.COPY)
        drop.onDrop { value, _, _ ->
            val f = value?.getObject() as? org.gnome.gio.File
            f?.local()?.let { openFile(it) }
            f != null
        }
        tv.addController(drop)

        setupKeys()
        setupActions()
        window.onCloseRequest { if (closing || session?.dirty != true) { closing = true; false } else { askSaveThen { closing = true; window.close() }; true } }
        optionsChanged(c.opts)
        updateActions()
    }

    /** Vorschaubild für den Startbildschirm (nach Öffnen und Speichern). */
    private fun storeThumb(s: Session, f: File) {
        Engine.call({
            val doc = s.doc
            var step = 1
            while (maxOf(doc.width, doc.height) / step > 480) step *= 2
            Compositor.prepare(doc, step)
            val r = IntRect(0, 0, (doc.width + step - 1) / step, (doc.height + step - 1) / step)
            r to Compositor.renderArgbSampled(doc, r, step)
        }) { res -> res.getOrNull()?.let { (r, px) -> Recent.storeThumb(f, r.width, r.height, px) } }
    }

    // ------------------------------------------------------------ Menüs

    private fun Menu.item(label: String, action: String) = append(label, "win.$action")
    private fun Menu.submenu(label: String, build: Menu.() -> Unit) = appendSubmenu(label, Menu().apply(build))
    private fun Menu.section(build: Menu.() -> Unit) = appendSection(null, Menu().apply(build))

    private fun mainMenu() = Menu().apply {
        section { item(tr("Neues Bild …"), "new"); item(tr("Öffnen …"), "open"); item(tr("Als Ebene platzieren …"), "place") }
        section { item(tr("Speichern"), "save"); item(tr("Speichern unter …"), "save-as"); item(tr("Exportieren als …"), "export"); item(tr("Drucken …"), "print") }
        submenu(tr("Ansicht")) {
            section { item(tr("Einpassen"), "zoom-fit"); item(tr("Originalgröße"), "zoom-100") }
            section {
                item(tr("Lineale"), "rulers"); item(tr("Hilfslinien"), "guides"); item(tr("Ausrichten"), "snap")
                item(tr("Neue Hilfslinie …"), "new-guide"); item(tr("Neues Hilfslinienlayout …"), "guide-layout"); item(tr("Hilfslinien löschen"), "clear-guides")
            }
            section {
                item(tr("Farbproof"), "proof"); item(tr("Farbumfang-Warnung"), "gamut")
                submenu(tr("Proof einrichten")) {
                    for (pc in de.photographics.core.color.ProofCondition.entries) append(pc.label, "win.proof-condition::${pc.name}")
                    appendSection(null, Menu().apply { append(tr("Papierfarbe simulieren"), "win.proof-paper") })
                }
            }
        }
        section { item(tr("KI-Modelle …"), "ai-models"); item(tr("Einstellungen"), "settings") }
        section { append(tr("Über photographics"), "app.about") }
    }

    private fun editMenu() = Menu().apply {
        section { item(tr("Rückgängig"), "undo"); item(tr("Wiederholen"), "redo") }
        section {
            item(tr("Ausschneiden"), "cut"); item(tr("Kopieren"), "copy"); item(tr("Auf eine Ebene reduziert kopieren"), "copy-merged")
            item(tr("Einfügen"), "paste"); item(tr("An Originalposition einfügen"), "paste-in-place")
        }
        section { item(tr("Frei transformieren"), "transform"); item(tr("Fläche füllen …"), "fill"); item(tr("Inhaltsbasiert füllen"), "content-fill") }
    }

    private fun imageMenu() = Menu().apply {
        section { item(tr("Bildgröße …"), "image-size"); item(tr("Arbeitsfläche …"), "canvas-size") }
        section {
            item(tr("90° im Uhrzeigersinn drehen"), "rotate-cw"); item(tr("90° gegen den Uhrzeigersinn drehen"), "rotate-ccw")
            item(tr("Drehen …"), "rotate-any")
            item(tr("Horizontal spiegeln"), "flip-h"); item(tr("Vertikal spiegeln"), "flip-v")
        }
        section {
            submenu(tr("Profil zuweisen")) { for (b in IccProfiles.Builtin.entries) append(b.label, "win.assign-profile::${b.name}") }
            submenu(tr("In Profil umwandeln")) { for (b in IccProfiles.Builtin.entries) append(b.label, "win.convert-profile::${b.name}") }
        }
        section {
            submenu(tr("Modus")) { append(tr("8 Bit/Kanal"), "win.bit-depth::8"); append(tr("16 Bit/Kanal"), "win.bit-depth::16") }
            item(tr("Auf Hintergrundebene reduzieren"), "flatten")
        }
    }

    private fun layerMenu() = Menu().apply {
        section { item(tr("Neue Ebene"), "new-layer"); item(tr("Neue Gruppe"), "new-group"); item(tr("Ebenen gruppieren"), "group-layers") }
        section { item(tr("Ebene duplizieren"), "duplicate-layer"); item(tr("Ebene löschen"), "delete-layer"); item(tr("Auf darunterliegende Ebene reduzieren"), "merge-down") }
        section { item(tr("Ebene durch Kopie"), "layer-via-copy"); item(tr("Ebene durch Ausschneiden"), "layer-via-cut") }
        section {
            item(tr("Ebenenmaske hinzufügen"), "add-mask"); item(tr("Maske aus Auswahl"), "add-mask-selection")
            item(tr("Maske aktivieren/deaktivieren"), "toggle-mask"); item(tr("Maske anwenden"), "apply-mask"); item(tr("Maske löschen"), "delete-mask")
        }
        section { item(tr("Schnittmaske erstellen/lösen"), "toggle-clip"); item(tr("In Smartobjekt umwandeln"), "smart"); item(tr("Rastern"), "rasterize") }
        section { item(tr("Nach oben"), "layer-up"); item(tr("Nach unten"), "layer-down"); item(tr("Ebeneneigenschaften …"), "layer-props"); item(tr("Ebenenstil") + " …", "layer-style") }
    }

    private fun selectMenu() = Menu().apply {
        section { item(tr("Alles auswählen"), "select-all"); item(tr("Auswahl aufheben"), "deselect"); item(tr("Auswahl umkehren"), "invert-selection") }
        section { item(tr("Weiche Auswahlkante …"), "feather") }
        section {
            item(tr("Mit Vordergrundfarbe füllen"), "fill-fg")
            item(tr("Mit Hintergrundfarbe füllen"), "fill-bg"); item(tr("Löschen"), "clear-selection")
            item(tr("Inhaltsbasiert füllen"), "content-fill"); item(tr("Mit KI füllen"), "ai-fill")
        }
    }

    private fun adjustMenu() = Menu().apply {
        section {
            for (t in AdjustmentType.entries) append(t.label, "win.adjust-layer::${t.name}")
        }
        submenu(tr("Direkt auf die Ebene anwenden")) {
            for (t in AdjustmentType.entries) if (t != AdjustmentType.COLOR_LOOKUP) append(t.label, "win.adjust-direct::${t.name}")
        }
    }

    private fun filterMenu() = Menu().apply {
        section { item(tr("Filtergalerie …"), "filter-gallery") }
        for ((group, specs) in FilterSpecs.withIndex().groupBy { it.value.group }) {
            submenu(group) { for ((i, s) in specs) append(s.label, "win.filter::$i") }
        }
    }

    // ------------------------------------------------------------ Aktionen

    private fun action(name: String, accels: Array<String> = emptyArray(), run: () -> Unit) {
        val a = SimpleAction(name, null)
        a.onActivate { _ -> run() }
        window.addAction(a)
        actions[name] = a
        if (accels.isNotEmpty()) app.adw.setAccelsForAction("win.$name", accels)
    }

    /** Ein/Aus-Aktion (Häkchen im Menü). */
    private fun toggleAction(name: String, accels: Array<String> = emptyArray(), initial: Boolean = false, run: (Boolean) -> Unit) {
        val a = SimpleAction.stateful(name, null, org.gnome.glib.Variant.boolean_(initial))
        a.onActivate { _ ->
            val on = !(a.state?.getBoolean() ?: false)
            a.state = org.gnome.glib.Variant.boolean_(on)
            run(on)
        }
        window.addAction(a)
        actions[name] = a
        if (accels.isNotEmpty()) app.adw.setAccelsForAction("win.$name", accels)
    }

    // Farbproof: Druck simulieren; sonst nur Dokumentprofil → Bildschirm (sRGB), falls nötig
    private var proofOn = false
    private var proofProfileName: String? = null

    private fun updateProof() {
        val s = session ?: return
        val on = proofOn; val cond = Settings.proofCondition; val paper = Settings.proofSimulatePaper
        Engine.call({
            val prof = s.doc.colorProfile
            val isSrgb = prof.bytes.contentEquals(de.photographics.core.color.IccProfiles.sRGB.bytes)
            when {
                on -> de.photographics.core.color.SoftProof(cond, paper, if (isSrgb) null else prof)
                isSrgb -> null
                else -> de.photographics.core.color.SoftProof.display(prof)
            }
        }) { r -> if (session === s) { canvas.proofIsDisplay = !on; canvas.proof = r.getOrNull() } }
        title.subtitle = title.subtitle
    }

    /** Aktion mit Textparameter (für Menüeinträge wie „win.filter::3“). */
    private fun paramAction(name: String, run: (String) -> Unit) {
        val a = SimpleAction(name, org.gnome.glib.VariantType("s"))
        a.onActivate { v -> v?.getString(null)?.let(run) }
        window.addAction(a)
        actions[name] = a
    }

    private fun setupActions() {
        action("new", arrayOf("<Control>n")) { Dialogs.newDocument(window) { w, h, t, dpi -> newDocument(w, h, t, dpi) } }
        action("open", arrayOf("<Control>o")) { chooseOpen() }
        action("place") { choosePlace() }
        action("save", arrayOf("<Control>s")) { save(false) }
        action("save-as", arrayOf("<Control><Shift>s")) { save(true) }
        action("export", arrayOf("<Control><Shift>e")) { chooseExport() }
        // Während des Transformierens nimmt Strg+Z die Transformation zurück
        action("undo", arrayOf("<Control>z")) { if (c.transforming) c.cancel() else if (!props.open) c.edit(null) { it.undo() } }
        action("redo", arrayOf("<Control><Shift>z", "<Control>y")) { if (c.transforming) c.cancelTransform() else if (!props.open) c.edit(null) { it.redo() } }
        action("zoom-in", arrayOf("<Control>plus", "<Control>equal", "<Control>KP_Add")) { canvas.zoomIn() }
        action("zoom-out", arrayOf("<Control>minus", "<Control>KP_Subtract")) { canvas.zoomOut() }
        action("zoom-fit", arrayOf("<Control>0")) { canvas.fit() }
        action("zoom-100", arrayOf("<Control>1")) { canvas.setZoom(1.0) }
        action("toggle-sidebar", arrayOf("F7")) { split.showSidebar = !split.showSidebar }
        action("print", arrayOf("<Control>p")) {
            val s = session ?: return@action
            busy(true)
            Engine.call({ Printing.render(s.doc) to s.doc.dpi }) { r ->
                busy(false)
                r.onSuccess { (img, dpi) -> Printing.print(window, s.name, img, dpi) }.onFailure { e -> error(tr("Drucken fehlgeschlagen"), e) }
            }
        }
        action("ai-models") { de.photographics.gnome.ai.AiDialog.show(window) }
        action("settings", arrayOf("<Control>comma")) { SettingsDialog.show(window) { updateProof() } }
        toggleAction("proof", arrayOf("<Control>y")) { on -> proofOn = on; updateProof() }
        toggleAction("rulers", arrayOf("<Control>r"), Settings.showRulers) { on -> Settings.showRulers = on; showRulers(on) }
        toggleAction("guides", arrayOf("<Control>semicolon"), Settings.showGuides) { on -> Settings.showGuides = on; canvas.queueDraw() }
        toggleAction("snap", arrayOf("<Control><Shift>semicolon"), Settings.snap) { on -> Settings.snap = on }
        action("new-guide") { Dialogs.newGuide(window) { v, pos -> c.addGuide(v, pos) } }
        action("clear-guides") { c.edit(tr("Hilfslinien löschen")) { it.setGuides(tr("Hilfslinien löschen"), emptyList()); IntRect.EMPTY } }
        action("guide-layout") {
            state?.let { st ->
                Dialogs.guideLayout(window) { cols, rows, margin, gutter ->
                    c.edit(tr("Neues Hilfslinienlayout")) { e -> e.setGuides(tr("Neues Hilfslinienlayout"), guideLayout(st.width.toDouble(), st.height.toDouble(), cols, rows, margin, gutter)); IntRect.EMPTY }
                }
            }
        }
        toggleAction("gamut", arrayOf("<Control><Shift>y")) { on -> canvas.gamutWarning = on; if (on && !proofOn) actions["proof"]?.activate(null) }
        toggleAction("proof-paper", initial = Settings.proofSimulatePaper) { on -> Settings.proofSimulatePaper = on; updateProof() }
        val cond = SimpleAction.stateful("proof-condition", org.gnome.glib.VariantType("s"), org.gnome.glib.Variant.string(Settings.proofCondition.name))
        cond.onActivate { v ->
            val n = v?.getString(null) ?: return@onActivate
            cond.state = org.gnome.glib.Variant.string(n)
            de.photographics.core.color.ProofCondition.entries.firstOrNull { it.name == n }?.let { Settings.proofCondition = it }
            if (!proofOn) actions["proof"]?.activate(null) else updateProof()
        }
        window.addAction(cond); actions["proof-condition"] = cond
        action("ai-fill") {
            val m = de.photographics.gnome.ai.AiModelManager.firstReady(de.photographics.gnome.ai.AiPurpose.INPAINT)
            when {
                state?.hasSelection != true -> toast(tr("Zuerst einen Bereich auswählen."))
                m == null -> requestAiModels()
                else -> c.edit(tr("Mit KI füllen"), busy = true) { it.contentAwareFill(de.photographics.gnome.ai.OnnxInpainter(de.photographics.gnome.ai.AiModelManager, m)) ?: IntRect.EMPTY }
            }
        }
        action("close", arrayOf("<Control>w")) { window.close() }

        // Bild
        action("image-size", arrayOf("<Control><Alt>i")) {
            state?.let { st ->
                val ai = de.photographics.gnome.ai.AiModelManager.firstReady(de.photographics.gnome.ai.AiPurpose.UPSCALE)
                Dialogs.imageSize(window, st.width, st.height, st.dpi, ai?.title) { w, h, m, dpi, useAi ->
                    val up = if (useAi && ai != null && (w > st.width || h > st.height))
                        de.photographics.gnome.ai.OnnxUpscaler(de.photographics.gnome.ai.AiModelManager, ai) { p -> onMain { title.subtitle = tr("KI-Hochskalierung ({0})", ai.title) + " ${(p * 100).toInt()} %" } }
                    else null
                    c.edit(tr("Bildgröße"), busy = true) { ed -> ed.resizeImage(w, h, m, up); ed.doc.dpi = dpi; null }
                }
            }
        }
        action("canvas-size", arrayOf("<Control><Alt>c")) { state?.let { st -> Dialogs.canvasSize(window, st.width, st.height) { w, h, ax, ay -> c.edit(tr("Arbeitsfläche"), busy = true) { ed -> ed.resizeCanvas(w, h, ax, ay); null } } } }
        action("rotate-cw") { c.edit(null, busy = true) { it.rotate(true); null } }
        action("rotate-ccw") { c.edit(null, busy = true) { it.rotate(false); null } }
        action("rotate-any") { Dialogs.rotate(window) { deg -> val bg = c.opts.bgColor; c.edit(null, busy = true) { it.rotateArbitrary(deg, bg); null } } }
        action("flip-h") { c.edit(null, busy = true) { it.flip(true); null } }
        action("flip-v") { c.edit(null, busy = true) { it.flip(false); null } }
        paramAction("assign-profile") { n -> IccProfiles.Builtin.entries.firstOrNull { it.name == n }?.let { b -> c.edit(tr("Profil zuweisen")) { it.assignProfile(b.profile); null } } }
        paramAction("convert-profile") { n ->
            IccProfiles.Builtin.entries.firstOrNull { it.name == n }?.let { b -> c.edit(tr("In Profil umwandeln"), busy = true) { it.convertToProfile(b.profile, RenderingIntent.RELATIVE, true); null } }
        }
        val depth = SimpleAction.stateful("bit-depth", org.gnome.glib.VariantType("s"), org.gnome.glib.Variant.string("8"))
        depth.onActivate { v ->
            val d = v?.getString(null)?.toIntOrNull() ?: return@onActivate
            c.edit(null, busy = true) { it.setBitDepth(d); null }
        }
        window.addAction(depth); actions["bit-depth"] = depth
        action("flatten") { c.edit(null, busy = true) { it.flatten(); null } }

        // Ebenen
        action("new-layer", arrayOf("<Control><Shift>n")) { c.edit(null) { it.addLayer(); IntRect.EMPTY } }
        action("new-group") { c.edit(null) { it.addGroup(); IntRect.EMPTY } }
        // Mit Mehrfachauswahl wirken die Befehle auf alle ausgewählten Ebenen (wie Photoshop)
        action("group-layers", arrayOf("<Control>g")) { val ids = selectedIds(); c.edit(null) { e -> e.groupLayers(e.selectedLayers(ids)); null } }
        action("duplicate-layer") { val ids = selectedIds(); c.edit(null) { e -> e.duplicateLayers(e.selectedLayers(ids)); null } }
        action("delete-layer") { val ids = selectedIds(); c.edit(null) { e -> e.deleteLayers(e.selectedLayers(ids)); null } }
        action("merge-down", arrayOf("<Control>e")) {
            val ids = selectedIds()
            c.edit(null, busy = true) { ed -> if (!(if (ids.size > 1) ed.mergeLayers(ed.selectedLayers(ids)) else ed.mergeDown())) toastLater(tr("Nichts zum Zusammenfügen.")); null }
        }
        action("layer-up", arrayOf("<Control>bracketright")) { val ids = selectedIds(); c.edit(null) { e -> for (l in e.selectedLayers(ids).sortedByDescending { e.doc.allLayers().indexOf(it) }) e.moveLayer(l, true); null } }
        action("layer-down", arrayOf("<Control>bracketleft")) { val ids = selectedIds(); c.edit(null) { e -> for (l in e.selectedLayers(ids).sortedBy { e.doc.allLayers().indexOf(it) }) e.moveLayer(l, false); null } }
        action("layer-front", arrayOf("<Control><Shift>bracketright")) { val ids = selectedIds(); c.edit(null) { e -> val ls = e.selectedLayers(ids); val p = e.doc.parentOf(ls.first()); e.moveLayersTo(ls, p, p.children.size); null } }
        action("layer-back", arrayOf("<Control><Shift>bracketleft")) { val ids = selectedIds(); c.edit(null) { e -> val ls = e.selectedLayers(ids); e.moveLayersTo(ls, e.doc.parentOf(ls.first()), 0); null } }
        // Alt+[ / Alt+]: nächste Ebene darunter/darüber auswählen
        action("select-layer-above", arrayOf("<Alt>bracketright")) { stepLayer(-1) }
        action("select-layer-below", arrayOf("<Alt>bracketleft")) { stepLayer(1) }
        // Wie Photoshop: nach dem Anlegen wird auf der Maske gemalt
        action("add-mask") { c.edit(null) { it.addMask(false); null }; c.setOptions { it.copy(paintTarget = PaintTarget.MASK) } }
        action("add-mask-selection") { c.edit(null) { it.addMask(true); null }; c.setOptions { it.copy(paintTarget = PaintTarget.MASK) } }
        action("delete-mask") { c.setOptions { it.copy(paintTarget = PaintTarget.PIXELS) }; c.edit(null) { it.deleteMask(); null } }
        action("apply-mask") { c.setOptions { it.copy(paintTarget = PaintTarget.PIXELS) }; c.edit(null) { it.applyMask(); null } }
        action("toggle-mask") { c.edit(tr("Maske")) { e -> e.doc.activeLayer?.let { l -> l.mask?.let { m -> val on = !m.enabled; e.setProps(l, tr("Maske")) { m.enabled = on } } }; null } }
        action("toggle-clip", arrayOf("<Control><Alt>g")) { c.edit(null) { e -> e.doc.activeLayer?.let { l -> if (!e.setClipping(l, !l.clipped)) toastLater(tr("Diese Ebene kann keine Schnittmaske bilden.")) }; null } }
        action("smart") { c.edit(null) { e -> (e.doc.activeLayer as? de.photographics.core.doc.PixelLayer)?.let { e.convertToSmartObject(it) ?: toastLater(tr("Die Ebene ist leer.")) }; IntRect.EMPTY } }
        action("rasterize") { c.edit(null) { e -> e.doc.activeLayer?.let { e.rasterize(it) }; null } }
        action("layer-style") {
            state?.active?.let { row ->
                c.startEffects(row.id) { fx ->
                    styleDialog = LayerStyleDialog(window, fx, onPreview = { c.previewEffects(row.id, it) }, onFinish = { c.finishEffects(row.id, it) }).also { it.present() }
                }
            }
        }
        action("layer-props") {
            state?.active?.let { row ->
                Dialogs.layerProps(window, row) { n, op, fill, bl, lock ->
                    c.edit(tr("Ebeneneigenschaften")) { e ->
                        e.doc.findLayer(row.id)?.let { l -> e.setProps(l, tr("Ebeneneigenschaften")) { name = n; opacity = op; fillOpacity = fill; blendMode = bl; lockTransparency = lock } }
                        null
                    }
                }
            }
        }

        // Auswahl
        action("select-all", arrayOf("<Control>a")) { c.edit(null) { it.selectAll(); IntRect.EMPTY } }
        action("deselect", arrayOf("<Control>d")) { c.edit(null) { it.deselect(); IntRect.EMPTY } }
        action("invert-selection", arrayOf("<Control><Shift>i")) { c.edit(null) { it.invertSelection(); IntRect.EMPTY } }
        action("feather", arrayOf("<Shift>F6")) { Dialogs.feather(window) { r -> c.edit(null) { it.featherSelection(r); IntRect.EMPTY } } }
        // Strg+J wie Photoshop: Auswahl in neue Ebene, ohne Auswahl die Ebene(n) duplizieren
        action("layer-via-copy", arrayOf("<Control>j")) {
            val ids = selectedIds()
            c.edit(null) { e -> if (ids.size > 1) e.duplicateLayers(e.selectedLayers(ids)) else e.layerViaCopy(); null }
        }
        action("layer-via-cut", arrayOf("<Control><Shift>j")) { c.edit(null) { it.layerViaCut(); null } }
        // Strg+Eingabe wie Photoshop: gewählten Pfad (sonst den Arbeitspfad) als Auswahl laden
        action("path-to-selection", arrayOf("<Control>Return", "<Control>KP_Enter")) {
            val st = state ?: return@action
            val p = (if (c.selectedPath >= 0) st.paths.getOrNull(c.selectedPath)?.path else st.workPath) ?: st.shapePath ?: return@action
            val op = c.opts.selectionOp; val f = c.opts.feather
            c.edit(tr("Pfad als Auswahl laden")) { it.selectionFromPath(p, op, f); IntRect.EMPTY }
        }
        action("copy", arrayOf("<Control>c")) { copy(merged = false, cut = false) }
        action("copy-merged", arrayOf("<Control><Shift>c")) { copy(merged = true, cut = false) }
        action("cut", arrayOf("<Control>x")) { copy(merged = false, cut = true) }
        action("paste", arrayOf("<Control>v")) { paste(false) }
        action("paste-in-place", arrayOf("<Control><Shift>v")) { paste(true) }
        action("fill", arrayOf("<Shift>F5")) {
            Dialogs.fill(window, c.opts.color) { content, custom, mode, opacity, keep ->
                if (content == de.photographics.core.editor.FillContent.CONTENT_AWARE) actions["content-fill"]?.activate(null)
                else {
                    val col = content.color(c.opts.color, c.opts.bgColor, custom) ?: return@fill
                    c.edit(tr("Fläche füllen"), busy = true) { it.fillArea(col, mode, opacity, keep) ?: run { toastLater(tr("Nur auf Pixelebenen möglich.")); IntRect.EMPTY } }
                }
            }
        }
        action("fill-fg", arrayOf("<Alt>BackSpace")) { val col = c.opts.color; c.edit(null) { it.fillSelection(col) ?: IntRect.EMPTY } }
        action("fill-bg", arrayOf("<Control>BackSpace")) { val col = c.opts.bgColor; c.edit(null) { it.fillSelection(col) ?: IntRect.EMPTY } }
        action("clear-selection") { c.edit(null) { it.clearSelection() ?: IntRect.EMPTY } }
        action("content-fill", arrayOf("<Shift>BackSpace")) {
            if (state?.hasSelection != true) toast(tr("Zuerst einen Bereich auswählen."))
            else c.edit(tr("Inhaltsbasiert füllen"), busy = true) { it.contentAwareFill() ?: run { toastLater(tr("Nur auf Pixelebenen möglich.")); IntRect.EMPTY } }
        }

        // Korrekturen und Filter
        paramAction("adjust-layer") { n -> AdjustmentType.entries.firstOrNull { it.name == n }?.let { t -> c.addAdjustment(t, ::openAdjustment) } }
        paramAction("adjust-direct") { n -> AdjustmentType.entries.firstOrNull { it.name == n }?.let { t -> c.filterStart { showProps(); props.showDirectAdjustment(t, c) } } }
        paramAction("filter") { n -> n.toIntOrNull()?.let { FilterSpecs.getOrNull(it) }?.let { spec -> c.filterStart { showProps(); props.showFilter(spec, c) } } }
        action("filter-gallery") {
            val s = session ?: return@action
            c.filterStart {
                FilterGallery(window, s, canvas.liveView().region, c.opts.color, c.opts.bgColor) { chain ->
                    if (!chain.isEmpty) c.edit(tr("Filtergalerie"), busy = true) { ed ->
                        ed.previewFilter(chain)?.let { (tx, r) -> ed.commitFilter(tx, tr("Filtergalerie")); r } ?: IntRect.EMPTY
                    }
                }.present()
            }
        }
        action("levels", arrayOf("<Control>l")) { c.addAdjustment(AdjustmentType.LEVELS, ::openAdjustment) }
        action("curves", arrayOf("<Control>m")) { c.addAdjustment(AdjustmentType.CURVES, ::openAdjustment) }
        action("hue-sat", arrayOf("<Control>u")) { c.addAdjustment(AdjustmentType.HUE_SATURATION, ::openAdjustment) }
        action("color-balance", arrayOf("<Control>b")) { c.addAdjustment(AdjustmentType.COLOR_BALANCE, ::openAdjustment) }
        action("invert", arrayOf("<Control>i")) { c.filterStart { c.filterPreview(AdjustmentFilter(AdjustmentType.INVERT.createDefault())); c.filterFinish(true, AdjustmentType.INVERT.label) } }
        action("transform", arrayOf("<Control>t")) { c.setOptions { it.copy(tool = Tool.TRANSFORM) } }
    }

    private fun toastLater(msg: String) = onMain { toast(msg) }

    /** Füllung und Kontur einer Formebene (Doppelklick im Ebenenbedienfeld). */
    private fun editShape(id: Int) {
        val s = session ?: return
        Engine.call({ (s.doc.findLayer(id) as? de.photographics.core.doc.ShapeLayer)?.let { it.fill to it.stroke } }) { r ->
            val (fill, stroke) = r.getOrNull() ?: return@call
            Dialogs.shapeStyle(window, fill, stroke) { f, st ->
                c.edit(tr("Formeigenschaften")) { e -> (e.doc.findLayer(id) as? de.photographics.core.doc.ShapeLayer)?.let { e.setShapeStyle(it, f, st) }; null }
            }
        }
    }

    // ------------------------------------------------------------ Zwischenablage

    /** Kopieren/Ausschneiden: in die eigene Zwischenablage und als Bild in die von GNOME. */
    private fun copy(merged: Boolean, cut: Boolean) {
        val block: (Editor) -> IntRect? = { ed ->
            val clip = if (cut) ed.cut() else ed.copy(merged)
            if (clip == null) { toastLater(tr("Kein Pixel ausgewählt.")); IntRect.EMPTY }
            else {
                de.photographics.core.editor.Clipboard.clip = clip
                // Für andere Programme (sehr große Ausschnitte nur intern; Testläufe lassen die Zwischenablage in Ruhe)
                if (clip.rect.width.toLong() * clip.rect.height <= 50_000_000L && System.getenv("PG_SCREENSHOT") == null) {
                    val bytes = CanvasView.toBytes(clip.pixels())
                    onMain { runCatching { window.clipboard.setTexture(MemoryTexture(clip.rect.width, clip.rect.height, MemoryFormat.B8G8R8A8, bytes, clip.rect.width * 4L)) } }
                }
                if (cut) null else IntRect.EMPTY
            }
        }
        if (cut) c.edit(tr("Ausschneiden"), block = block) else c.query(block)
    }

    /** Einfügen: eigene Pixel (mit Lage) oder ein Bild aus einem anderen Programm. */
    private fun paste(inPlace: Boolean) {
        val cb = window.clipboard
        val foreign = !cb.isLocal && cb.formats?.containGtype(Texture.getType()) == true
        if (!foreign) { pasteClip(null, inPlace); return }
        cb.readTextureAsync(null) { res ->
            val tex = runCatching { cb.readTextureFinish(res) }.getOrNull()
            if (tex == null) { pasteClip(null, inPlace); return@readTextureAsync }
            val d = org.gnome.gdk.TextureDownloader(tex)
            d.format = MemoryFormat.B8G8R8A8
            val stride = org.javagi.base.Out(0L)
            val bytes = d.downloadBytes(stride) ?: return@readTextureAsync
            val w = tex.width; val h = tex.height; val st = (stride.get() ?: (w * 4L)).toInt()
            val px = IntArray(w * h)
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until h) for (x in 0 until w) px[y * w + x] = bb.getInt(y * st + x * 4)
            pasteClip(w to h to px, inPlace)
        }
    }

    private fun pasteClip(foreign: Pair<Pair<Int, Int>, IntArray>?, inPlace: Boolean) {
        val view = canvas.liveView().region
        c.edit(tr("Einfügen")) { ed ->
            val clip = foreign?.let { (size, px) ->
                val (w, h) = size
                val g = de.photographics.core.image.TileGrid(w, h)
                g.writeRect(IntRect.ofSize(w, h), px)
                de.photographics.core.editor.PixelClip(g, IntRect.ofSize(w, h))
            } ?: de.photographics.core.editor.Clipboard.clip ?: run { toastLater(tr("Die Zwischenablage ist leer.")); return@edit IntRect.EMPTY }
            ed.paste(clip, inPlace && foreign == null, view)
        }
    }

    /** Spalten/Zeilen mit Rand und Abstand (wie Photoshops „Neues Hilfslinienlayout“). */
    private fun guideLayout(w: Double, h: Double, cols: Int, rows: Int, margin: Double, gutter: Double): List<de.photographics.core.doc.Guide> {
        val out = ArrayList<de.photographics.core.doc.Guide>()
        fun axis(len: Double, n: Int, vertical: Boolean) {
            val inner = len - 2 * margin
            if (inner <= 0 || n < 1) return
            val cell = (inner - gutter * (n - 1)) / n
            if (margin > 0) { out += de.photographics.core.doc.Guide(vertical, margin); out += de.photographics.core.doc.Guide(vertical, len - margin) }
            for (i in 1 until n) {
                val p = margin + i * cell + (i - 1) * gutter
                out += de.photographics.core.doc.Guide(vertical, p)
                if (gutter > 0) out += de.photographics.core.doc.Guide(vertical, p + gutter)
            }
        }
        axis(w, cols, true); axis(h, rows, false)
        return out.distinct()
    }

    private fun showProps() { sideStack.visibleChildName = "props"; split.showSidebar = true }

    private fun openAdjustment(id: Int, a: de.photographics.core.adjust.Adjustment, h: IntArray?) {
        showProps()
        props.showAdjustment(id, a, h, c)
    }

    /** Pinselauswahl wie Photoshops Rechtsklick-Menü: Größe, Härte, Vorgaben – direkt am Zeiger. */
    private fun brushPicker(x: Double, y: Double) {
        val o = c.opts.brush
        val pop = org.gnome.gtk.Popover()
        val box = Box(Orientation.VERTICAL, 6).apply { marginStart = 8; marginEnd = 8; marginTop = 8; marginBottom = 8; setSizeRequest(280, -1) }
        fun slider(label: String, min: Double, max: Double, v: Double, log: Boolean, set: (Double) -> Unit) {
            val row = Box(Orientation.HORIZONTAL, 6)
            row.append(org.gnome.gtk.Label(label).apply { xalign = 0f; hexpand = true; addCssClass("dim-label") })
            val value = org.gnome.gtk.Label("").apply { addCssClass("numeric") }
            row.append(value)
            box.append(row)
            // Größe logarithmisch: feine Schritte bei kleinen Pinseln, schnell bei großen
            val sc = org.gnome.gtk.Scale.withRange(Orientation.HORIZONTAL, if (log) kotlin.math.ln(min) else min, if (log) kotlin.math.ln(max) else max, if (log) 0.01 else 1.0)
            sc.drawValue = false
            sc.value = if (log) kotlin.math.ln(v) else v
            fun show() { val real = if (log) kotlin.math.exp(sc.value) else sc.value; value.label = if (log) "${real.toInt()} px" else "${real.toInt()} %"; real.let(set) }
            value.label = if (log) "${v.toInt()} px" else "${v.toInt()} %"
            sc.onValueChanged { show() }
            box.append(sc)
        }
        slider(tr("Größe"), 1.0, 5000.0, o.size.toDouble(), true) { v -> c.setOptions { it.copy(brush = it.brush.copy(size = v.toFloat())) } }
        slider(tr("Härte"), 0.0, 100.0, o.hardness * 100.0, false) { v -> c.setOptions { it.copy(brush = it.brush.copy(hardness = (v / 100).toFloat())) } }
        box.append(org.gnome.gtk.Separator(Orientation.HORIZONTAL))
        val flow = org.gnome.gtk.FlowBox().apply { selectionMode = org.gnome.gtk.SelectionMode.NONE; maxChildrenPerLine = 2; minChildrenPerLine = 2; homogeneous = true }
        val own = Settings.userBrushes()
        for (p in own + de.photographics.core.tools.BrushPresets.builtin) flow.append(Button.withLabel(p.name).apply {
            addCssClass("flat")
            if (p in own) { addCssClass("accent"); tooltipText = tr("Eigene Vorgabe – Rechtsklick: löschen") }
            onClicked { c.setOptions { it.copy(brush = if (p.keepSize) p.settings.copy(size = it.brush.size) else p.settings) }; pop.popdown() }
            if (p in own) addController(org.gnome.gtk.GestureClick().apply {
                button = 3
                onPressed { _, _, _ -> Settings.deleteBrush(p.name); options.rebuild(); options.update(c.opts, state?.active?.hasMask == true); pop.popdown() }
            })
        })
        box.append(flow)
        box.append(Button.withLabel(tr("Als Vorgabe speichern …")).apply {
            onClicked {
                pop.popdown()
                val entry = org.gnome.gtk.Entry().apply { placeholderText = tr("Name"); activatesDefault = true }
                val d = AlertDialog(tr("Pinselvorgabe speichern"), null)
                d.extraChild = entry
                d.addResponse("cancel", tr("Abbrechen")); d.addResponse("ok", tr("Speichern"))
                d.setResponseAppearance("ok", ResponseAppearance.SUGGESTED); d.defaultResponse = "ok"; d.closeResponse = "cancel"
                d.onResponse(null) { r ->
                    val n = entry.text.trim()
                    if (r == "ok" && n.isNotEmpty()) { Settings.saveBrush(n, c.opts.brush); options.rebuild(); options.update(c.opts, state?.active?.hasMask == true); toast(tr("Vorgabe „{0}“ gespeichert", n)) }
                }
                d.present(window)
            }
        })
        pop.child = box
        pop.setParent(canvas)
        pop.setPointingTo(org.gnome.gdk.Rectangle(x.toInt(), y.toInt(), 1, 1))
        pop.onClosed { onMain { pop.unparent() } }
        pop.popup()
    }

    /** Aktive Ebene in der Liste um [d] Zeilen wechseln (−1 = darüber). */
    private fun stepLayer(d: Int) {
        val rows = state?.layers ?: return
        val i = rows.indexOfFirst { it.active }
        val n = rows.getOrNull((i + d).coerceIn(0, rows.size - 1)) ?: return
        c.query { e -> e.doc.findLayer(n.id)?.let { e.setActive(it) }; IntRect.EMPTY }
    }

    override fun requestAiModels() {
        toast(tr("Zuerst ein KI-Modell laden."))
        de.photographics.gnome.ai.AiDialog.show(window)
    }

    override fun editText(layerId: Int, style: TextStyle) {
        showProps()
        props.showText(style, c, window)
    }

    private fun updateActions() {
        val s = state
        val has = s != null
        for (a in actions.values) a.enabled = has
        for (n in listOf("new", "open", "close", "ai-models", "settings")) actions[n]?.enabled = true
        actions["undo"]?.enabled = s?.canUndo == true
        actions["redo"]?.enabled = s?.canRedo == true
        val a = s?.active
        actions["cut"]?.enabled = a?.kind == LayerRow.Kind.PIXEL
        actions["layer-via-cut"]?.enabled = a?.kind == LayerRow.Kind.PIXEL && s.hasSelection
        actions["delete-layer"]?.enabled = a != null && s.layers.size > 1
        actions["merge-down"]?.enabled = a != null
        for (n in listOf("duplicate-layer", "layer-up", "layer-down", "layer-props", "toggle-clip")) actions[n]?.enabled = a != null
        for (n in listOf("delete-mask", "apply-mask", "toggle-mask")) actions[n]?.enabled = a?.hasMask == true
        actions["add-mask"]?.enabled = a != null && !a.hasMask
        actions["add-mask-selection"]?.enabled = a != null && !a.hasMask && s.hasSelection
        actions["smart"]?.enabled = a?.kind == LayerRow.Kind.PIXEL
        // Effekte rechnet der Compositor für Pixel-, Text-, Form- und Smartobjektebenen
        actions["layer-style"]?.enabled = a != null && a.kind != LayerRow.Kind.GROUP && a.kind != LayerRow.Kind.ADJUSTMENT
        actions["rasterize"]?.enabled = a != null && (a.kind == LayerRow.Kind.TEXT || a.kind == LayerRow.Kind.SMART)
        for (n in listOf("deselect", "invert-selection", "feather", "fill-fg", "fill-bg", "clear-selection", "content-fill", "ai-fill")) actions[n]?.enabled = s?.hasSelection == true
        zoomLabel.sensitive = has
        for (w in docWidgets) w.visible = has
    }

    // ------------------------------------------------------------ Tastatur

    private fun setupKeys() {
        val keys = EventControllerKey()
        // Nach den Eingabefeldern: Buchstaben in Textfeldern kommen hier nicht an
        keys.propagationPhase = PropagationPhase.BUBBLE
        keys.onKeyPressed { keyval, _, mods -> keyPressed(keyval, mods) }
        keys.onKeyReleased { keyval, _, mods ->
            c.canvasMods = mods
            if (keyval == org.gnome.gdk.Gdk.KEY_space) canvas.panMode = false
        }
        window.addController(keys)
    }

    private fun keyPressed(keyval: Int, mods: Set<ModifierType>): Boolean {
        c.canvasMods = mods
        if (session == null) return false
        val ctrl = ModifierType.CONTROL_MASK in mods; val alt = ModifierType.ALT_MASK in mods
        val shift = ModifierType.SHIFT_MASK in mods
        when (keyval) {
            org.gnome.gdk.Gdk.KEY_space -> { canvas.panMode = true; return true }
            org.gnome.gdk.Gdk.KEY_Escape -> { if (props.open) props.close(false) else c.cancel(); return true }
            org.gnome.gdk.Gdk.KEY_Return, org.gnome.gdk.Gdk.KEY_KP_Enter -> { if (c.confirmable) { c.confirm(); return true }; return false }
            // Polygon-Lasso: letzte Ecke entfernen (wie Photoshop)
            org.gnome.gdk.Gdk.KEY_BackSpace -> { if (c.polyActive && !ctrl && !alt) { c.polyBack(); return true }; return false }
            org.gnome.gdk.Gdk.KEY_Delete -> {
                if (c.polyActive) { c.polyBack(); return true }
                if (c.deleteAnchor()) return true
                if (state?.hasSelection == true) actions["clear-selection"]?.activate(null) else actions["delete-layer"]?.activate(null); return true }
            org.gnome.gdk.Gdk.KEY_Left, org.gnome.gdk.Gdk.KEY_Right, org.gnome.gdk.Gdk.KEY_Up, org.gnome.gdk.Gdk.KEY_Down -> {
                if (c.opts.tool != Tool.MOVE) return false
                val d = if (shift) 10 else 1
                when (keyval) {
                    org.gnome.gdk.Gdk.KEY_Left -> c.nudge(-d, 0); org.gnome.gdk.Gdk.KEY_Right -> c.nudge(d, 0)
                    org.gnome.gdk.Gdk.KEY_Up -> c.nudge(0, -d); else -> c.nudge(0, d)
                }
                return true
            }
            org.gnome.gdk.Gdk.KEY_bracketleft, org.gnome.gdk.Gdk.KEY_bracketright -> if (!ctrl && !alt) {
                val up = keyval == org.gnome.gdk.Gdk.KEY_bracketright
                c.setOptions { o -> val s = o.brush.size; o.copy(brush = o.brush.copy(size = (if (up) s * 1.15f + 1 else s / 1.15f - 1).coerceIn(1f, 5000f))) }
                return true
            }
        }
        if (ctrl || alt) return false
        val ch = org.gnome.gdk.Gdk.keyvalToUnicode(keyval).toChar().lowercaseChar()
        when (ch) {
            'x' -> { swapColors(); return true }
            'd' -> { resetColors(); return true }
        }
        // Werkzeug per Buchstabe; mit Umschalt wechselt es innerhalb der Gruppe
        val tools = Tool.entries.filter { it.key == ch }
        if (tools.isNotEmpty()) {
            val cur = c.opts.tool
            val t = if (shift && cur.group == tools[0].group) cur.group.tools[(cur.group.tools.indexOf(cur) + 1) % cur.group.tools.size] else tools[0]
            c.setOptions { it.copy(tool = t) }
            return true
        }
        return false
    }

    // ------------------------------------------------------------ Farben

    private fun pickColor(background: Boolean) {
        val o = c.opts
        val cur = if (background) o.bgColor else o.color
        val d = org.gnome.gtk.ColorDialog()
        d.title = if (background) tr("Hintergrundfarbe") else tr("Vordergrundfarbe")
        d.withAlpha = false
        d.chooseRgba(window, org.gnome.gdk.RGBA(((cur shr 16) and 0xFF) / 255f, ((cur shr 8) and 0xFF) / 255f, (cur and 0xFF) / 255f, 1f), null) { res ->
            val rgba = runCatching { d.chooseRgbaFinish(res) }.getOrNull() ?: return@chooseRgba
            val v = argbOf(rgba)
            c.setOptions { if (background) it.copy(bgColor = v) else it.copy(color = v) }
        }
    }

    private fun swapColors() = c.setOptions { it.copy(color = it.bgColor, bgColor = it.color) }
    private fun resetColors() = c.setOptions { it.copy(color = 0xFF000000.toInt(), bgColor = 0xFFFFFFFF.toInt()) }

    // ------------------------------------------------------------ Controller.Ui

    override fun stateChanged(st: DocState, dirty: IntRect?) {
        if (dirty == null || !dirty.isEmpty) canvas.invalidate(dirty, st)
        layers.update(st, thumbs)
        history.update(st)
        paths.update(st)
        channels.update(st)
        if (st.profileName != proofProfileName) { proofProfileName = st.profileName; updateProof() }
        if (st.active?.hasMask != true && c.opts.paintTarget == PaintTarget.MASK) c.setOptions { it.copy(paintTarget = PaintTarget.PIXELS) }
        options.update(c.opts, st.active?.hasMask == true)
        updateTitle(); updateActions()
        requestThumbs()
        canvas.queueDraw()
    }

    private var lastColor = 0xFF000000.toInt()

    override fun optionsChanged(o: ToolOptions) {
        if (o.color != lastColor) { lastColor = o.color; swatches.used(o.color) }
        palette.update(o)
        options.update(o, state?.active?.hasMask == true)
        layers.setTarget(o.paintTarget)
        optionsBar.visible = session != null
    }

    override fun toast(msg: String) = toasts.addToast(Toast(msg).apply { timeout = 3 })

    override fun error(heading: String, e: Throwable) {
        val d = AlertDialog(heading, e.message ?: e.javaClass.simpleName)
        d.addResponse("ok", tr("OK"))
        d.present(window)
    }

    override fun busy(on: Boolean) {
        busyCount = maxOf(0, busyCount + if (on) 1 else -1)
        spinner.spinning = busyCount > 0
        spinner.visible = busyCount > 0
    }

    // ------------------------------------------------------------ Ebenen

    /** Ausgewählte Ebenen im Bedienfeld (mindestens die aktive). */
    private fun selectedIds(): Set<Int> = layers.selected.ifEmpty { setOfNotNull(state?.active?.id) }
    private fun Editor.selectedLayers(ids: Set<Int>): List<Layer> = ids.mapNotNull { doc.findLayer(it) }

    private inner class LayerActions : LayersPanel.Actions {
        override fun select(active: Int, selected: Set<Int>) { c.query { e -> e.doc.findLayer(active)?.let { e.setActive(it) }; IntRect.EMPTY } }

        override fun setVisible(id: Int, visible: Boolean) {
            // Mehrfachauswahl: das Auge einer ausgewählten Ebene gilt für alle ausgewählten
            val ids = if (id in layers.selected) layers.selected else setOf(id)
            changeLayers(if (visible) tr("Ebene einblenden") else tr("Ebene ausblenden"), ids) { it.visible = visible }
        }

        override fun solo(id: Int) = c.edit(tr("Sichtbarkeit")) { e ->
            val all = e.doc.allLayers()
            val l = e.doc.findLayer(id) ?: return@edit IntRect.EMPTY
            // Ebenen auf dem Weg zur Wurzel bleiben sichtbar (sonst wäre die Ebene unsichtbar)
            val keep = HashSet<Layer>().apply { var p: Layer? = l; while (p != null) { add(p); p = p.parent }; if (l is de.photographics.core.doc.GroupLayer) addAll(all.filter { e.doc.isDescendant(it, l) }) }
            val before = all.associateWith { it.visible }
            val othersHidden = all.none { it !in keep && it.visible }
            // Zweites Alt+Klick: alle wieder einblenden
            val after = all.associateWith { if (othersHidden) true else it in keep }
            for ((x, v) in after) x.visible = v
            e.push(de.photographics.core.history.LambdaEntry(tr("Sichtbarkeit"), { for ((x, v) in before) x.visible = v }, { for ((x, v) in after) x.visible = v }))
            null
        }

        override fun opacity(v: Float) = liveChange(tr("Deckkraft")) { it.opacity = v }
        override fun fill(v: Float) = liveChange(tr("Fläche")) { it.fillOpacity = v }
        override fun blend(mode: BlendMode) = changeLayers(tr("Mischmodus"), selectedIds()) { it.blendMode = mode }
        override fun lockTransparency(on: Boolean) = changeLayers(tr("Transparente Pixel fixieren"), selectedIds()) { it.lockTransparency = on }

        override fun open(row: LayerRow) {
            when (row.kind) {
                LayerRow.Kind.ADJUSTMENT -> c.openAdjustment(row.id, ::openAdjustment)
                LayerRow.Kind.TEXT -> c.editTextLayer(row.id)
                LayerRow.Kind.SHAPE -> editShape(row.id)
                else -> actions["layer-props"]?.activate(null)
            }
        }

        override fun rename(id: Int, name: String) = c.edit(tr("Ebene umbenennen")) { e ->
            e.doc.findLayer(id)?.let { l -> e.setProps(l, tr("Ebene umbenennen")) { this.name = name } }
            IntRect.EMPTY
        }

        override fun target(id: Int, target: PaintTarget) {
            c.query { e -> e.doc.findLayer(id)?.let { e.setActive(it) }; IntRect.EMPTY }
            c.setOptions { it.copy(paintTarget = target) }
        }

        override fun toggleMaskEnabled(id: Int) = c.edit(tr("Maske")) { e ->
            e.doc.findLayer(id)?.let { l -> l.mask?.let { m -> val on = !m.enabled; e.setProps(l, if (on) tr("Maske aktivieren") else tr("Maske deaktivieren")) { m.enabled = on } } }
            null
        }

        override fun loadSelection(id: Int, mask: Boolean, op: de.photographics.core.selection.SelectionOp) = c.edit(tr("Auswahl laden"), busy = true) { e ->
            e.doc.findLayer(id)?.let { e.selectionFromLayer(it, mask, op) }
            IntRect.EMPTY
        }

        override fun toggleExpanded(id: Int) = c.query { e ->
            (e.doc.findLayer(id) as? de.photographics.core.doc.GroupLayer)?.let { it.expanded = !it.expanded }
            IntRect.EMPTY
        }

        override fun deleteLayers(ids: List<Int>) = c.edit(null) { e -> e.deleteLayers(e.selectedLayers(ids.toSet())); null }
        override fun duplicateLayers(ids: List<Int>) = c.edit(null) { e -> e.duplicateLayers(e.selectedLayers(ids.toSet())); null }

        override fun drop(ids: List<Int>, parentId: Int, index: Int) = c.edit(tr("Ebene verschieben")) { e ->
            val parent = if (parentId == 0) e.doc.root else e.doc.findLayer(parentId) as? de.photographics.core.doc.GroupLayer ?: return@edit IntRect.EMPTY
            if (e.moveLayersTo(e.selectedLayers(ids.toSet()), parent, index)) null else IntRect.EMPTY
        }
    }

    /** Eigenschaft mehrerer Ebenen in einem Verlaufsschritt ändern. */
    private fun changeLayers(label: String, ids: Set<Int>, f: (Layer) -> Unit) = c.edit(label) { e ->
        val ls = e.selectedLayers(ids)
        val before = ls.associateWith { it.props() }
        for (l in ls) f(l)
        val after = ls.associateWith { it.props() }
        if (before != after) e.push(de.photographics.core.history.LambdaEntry(label, { for ((l, p) in before) l.applyProps(p) }, { for ((l, p) in after) l.applyProps(p) }))
        null
    }

    // Deckkraft/Fläche: live ändern, nach einer kurzen Pause als ein Verlaufsschritt festhalten
    private var liveBefore: Map<Int, Layer.Props>? = null
    private var liveCommit = 0

    private fun liveChange(label: String, f: (Layer) -> Unit) {
        val s = session ?: return
        val ids = selectedIds()
        val v = canvas.liveView()
        Engine.call({
            val ls = s.editor.selectedLayers(ids)
            if (liveBefore?.keys != ids) liveBefore = ls.associate { it.id to it.props() }
            for (l in ls) f(l)
            // Eine Ebene: über die GPU, wenn möglich
            val one = ls.singleOrNull()
            if (one != null) c.liveLayer(one, (one as? RasterLayer)?.raster?.contentBounds() ?: s.doc.bounds, v)
            one == null
        }) { r -> if (r.getOrNull() == true) state?.let { canvas.invalidate(null, it, live = true) } }
        if (liveCommit != 0) org.gnome.glib.Source.remove(liveCommit)
        liveCommit = GLib.timeoutAddOnce(400) {
            liveCommit = 0
            c.edit(null) { e ->
                val before = liveBefore ?: return@edit IntRect.EMPTY
                liveBefore = null
                val ls = before.keys.mapNotNull { e.doc.findLayer(it) }
                val after = ls.associateWith { it.props() }
                e.push(de.photographics.core.history.LambdaEntry(label, { for (l in ls) before[l.id]?.let { l.applyProps(it) } }, { for ((l, p) in after) l.applyProps(p) }))
                IntRect.EMPTY
            }
        }
    }

    /** Vorschaubilder der Rasterebenen und Masken (verkleinert, auf dem Engine-Thread). */
    private var thumbsPending = false
    private fun requestThumbs() {
        val s = session ?: return
        if (thumbsPending) return
        thumbsPending = true
        GLib.timeoutAddOnce(250) {
            Engine.call({
                val doc = s.doc
                var step = 1
                while (maxOf(doc.width, doc.height) / step > LayersPanel.THUMB * 3) step *= 2
                Compositor.prepare(doc, step)
                val r = IntRect(0, 0, (doc.width + step - 1) / step, (doc.height + step - 1) / step)
                val out = HashMap<Int, ByteArray>()
                for (l in doc.allLayers()) {
                    if (l is RasterLayer) out[l.id] = CanvasView.toBytes(maskless(l, r, step))
                    l.mask?.let { m ->
                        val mv = IntArray(r.width * r.height)
                        for (y in 0 until r.height) for (x in 0 until r.width) {
                            val v = m.grid[minOf(doc.width - 1, x * step), minOf(doc.height - 1, y * step)] and 0xFF
                            mv[y * r.width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                        }
                        out[-l.id] = CanvasView.toBytes(mv)
                    }
                }
                out to r
            }) { res ->
                thumbsPending = false
                val (map, r) = res.getOrNull() ?: return@call
                if (session !== s) return@call
                thumbs = HashMap(map.mapValues { (_, b) -> MemoryTexture(r.width, r.height, MemoryFormat.B8G8R8A8, b, r.width * 4L) as Texture })
                layers.updateThumbs(thumbs)
            }
        }
    }

    /** Ebenenpixel ohne Maske (die Maske hat ein eigenes Vorschaubild). */
    private fun maskless(l: RasterLayer, r: IntRect, step: Int): IntArray {
        val m = l.mask ?: return Compositor.renderLayerSampled(l, r, step)
        val was = m.enabled
        m.enabled = false
        try { return Compositor.renderLayerSampled(l, r, step) } finally { m.enabled = was }
    }

    // ------------------------------------------------------------ Dokument

    private fun attach(s: Session, st: DocState) {
        c.attach(s, st)
        canvas.setSession(s, st)
        stack.visibleChildName = "doc"
        thumbs.clear()
        optionsBar.visible = true
        stateChanged(st, IntRect.EMPTY)
        optionsChanged(c.opts)
    }

    private fun newEditor(doc: Document): Editor = Editor(doc).also {
        it.history.scratch = ScratchDisk(File(FileIO.cacheDir, "scratch-${ProcessHandle.current().pid()}-${System.nanoTime()}"))
    }

    fun newDocument(w: Int, h: Int, transparent: Boolean, dpi: Float) {
        if (session != null) { app.newWindow().also { it.present() }.newDocument(w, h, transparent, dpi); return }
        Engine.call({
            val d = Document.blank(w, h, if (transparent) null else 0xFFFFFFFF.toInt())
            d.dpi = dpi
            val s = Session(newEditor(d), null, tr("Unbenannt"), true)
            s to s.state()
        }) { r -> r.onSuccess { (s, st) -> attach(s, st) }.onFailure { e -> error(tr("Bild konnte nicht angelegt werden"), e) } }
    }

    fun openFile(f: File) {
        if (session != null) { app.newWindow().also { it.present() }.openFile(f); return }
        title.subtitle = tr("Wird geöffnet …")
        busy(true)
        Engine.call({
            val loaded = FileIO.open(f, Settings.workingRgb.profile)
            val s = Session(newEditor(loaded.doc), if (loaded.layered) f else null, f.name, loaded.layered)
            s to s.state()
        }) { r ->
            busy(false)
            r.onSuccess { (s, st) ->
                attach(s, st)
                app.recent(f)
                storeThumb(s, f)
                if (s.doc.importWarnings.isNotEmpty()) toast(s.doc.importWarnings.first())
            }.onFailure { e ->
                updateTitle()
                error(tr("„{0}“ konnte nicht geöffnet werden", f.name), e)
            }
        }
    }

    // ------------------------------------------------------------ Dateien

    private fun filters(vararg spec: Pair<String, List<String>>): ListStore<FileFilter> {
        val store = ListStore<FileFilter>(FileFilter.getType())
        for ((name, patterns) in spec) store.append(FileFilter().apply {
            this.name = name
            for (p in patterns) if ('/' in p) addMimeType(p) else addSuffix(p)
        })
        return store
    }

    private fun imageFilters() = filters(
        tr("Alle Bilder") to listOf("image/*", "psd", "psb", "xcf", "tif", "tiff"),
        "Photoshop (PSD)" to listOf("psd", "psb"), "TIFF" to listOf("tif", "tiff"), "GIMP (XCF)" to listOf("xcf"),
    )

    private fun chooseOpen() {
        val d = FileDialog()
        d.title = tr("Bild öffnen")
        d.filters = imageFilters()
        d.open(window, null) { res -> runCatching { d.openFinish(res) }.getOrNull()?.local()?.let { openFile(it) } }
    }

    /** Datei als neue Ebene (PSD: zusammengeführt) mittig über der aktiven Ebene einfügen. */
    private fun choosePlace() {
        val d = FileDialog()
        d.title = tr("Als Ebene platzieren")
        d.filters = imageFilters()
        d.open(window, null) { res ->
            val f = runCatching { d.openFinish(res) }.getOrNull()?.local() ?: return@open
            c.edit(tr("Platzieren"), busy = true) { ed ->
                val src = FileIO.open(f).doc
                val g = ed.doc.newGrid()
                val ox = (ed.doc.width - src.width) / 2; val oy = (ed.doc.height - src.height) / 2
                Compositor.renderBands(src, src.bounds) { r, px -> g.writeRect(r.offset(ox, oy), px) }
                g.compact()
                ed.addLayer(f.nameWithoutExtension, g, tr("Platzieren"))
                null
            }
        }
    }

    private fun save(chooseName: Boolean, then: () -> Unit = {}) {
        val s = session ?: return
        // Offene Transformation erst übernehmen – sonst landet die schwebende Auswahl als eigene Ebene in der Datei
        if (c.transforming) c.applyTransform()
        val f = s.file
        if (!chooseName && f != null) { writeTo(s, f, FileIO.Format.of(f) ?: FileIO.Format.PSD, then); return }
        val d = FileDialog()
        d.title = tr("Speichern unter")
        d.initialName = s.name.substringBeforeLast('.') + "." + (s.file?.let { FileIO.Format.of(it)?.ext } ?: "psd")
        d.filters = filters("Photoshop (PSD)" to listOf("psd"), "TIFF" to listOf("tif", "tiff"), "GIMP (XCF)" to listOf("xcf"))
        d.save(window, null) { res ->
            val g = runCatching { d.saveFinish(res) }.getOrNull()?.local() ?: return@save
            val fmt = FileIO.Format.of(g)?.takeIf { it.layered }
            val target = if (fmt == null) File(g.path + ".psd") else g
            writeTo(s, target, fmt ?: FileIO.Format.PSD) { s.file = target; s.name = target.name; updateTitle(); then() }
        }
    }

    /** Exportieren als: erst Optionen (Format, Qualität, Größe), dann der Speicherort mit passender Endung. */
    private fun chooseExport() {
        val s = session ?: return
        if (c.transforming) c.applyTransform()
        val st = state ?: return
        Dialogs.export(window, st.width, st.height) { fmt, o ->
            val d = FileDialog()
            d.title = tr("Exportieren")
            d.initialName = s.name.substringBeforeLast('.') + "." + fmt.ext
            d.filters = if (fmt == FileIO.Format.JPEG) filters("JPEG" to listOf("jpg", "jpeg")) else filters("PNG" to listOf("png"))
            d.save(window, null) { res ->
                val g = runCatching { d.saveFinish(res) }.getOrNull()?.local() ?: return@save
                // Endung passend zum gewählten Format ergänzen
                val target = if (FileIO.Format.of(g) == fmt) g else File(g.path + "." + fmt.ext)
                busy(true)
                Engine.call({ FileIO.export(s.doc, target, fmt, o) }) { r ->
                    busy(false)
                    r.onSuccess { toast(tr("Exportiert: {0}", target.name)) }.onFailure { e -> error(tr("Export fehlgeschlagen"), e) }
                }
            }
        }
    }

    private fun writeTo(s: Session, f: File, format: FileIO.Format, then: () -> Unit) {
        title.subtitle = tr("Wird gespeichert …")
        busy(true)
        Engine.call({ FileIO.save(s.doc, f, format); s.dirty = false; s.state() }) { r ->
            busy(false)
            r.onSuccess { st -> stateChanged(st, IntRect.EMPTY); toast(tr("Gespeichert: {0}", f.name)); app.recent(f); storeThumb(s, f); then() }
                .onFailure { e -> updateTitle(); error(tr("Speichern fehlgeschlagen"), e) }
        }
    }

    /** Vor dem Schließen: Änderungen speichern, verwerfen oder abbrechen. */
    private fun askSaveThen(proceed: () -> Unit) {
        val d = AlertDialog(tr("Änderungen speichern?"), tr("Ohne Speichern gehen die Änderungen an „{0}“ verloren.", session?.name ?: ""))
        d.addResponse("cancel", tr("Abbrechen"))
        d.addResponse("discard", tr("Verwerfen"))
        d.addResponse("save", tr("Speichern"))
        d.setResponseAppearance("discard", ResponseAppearance.DESTRUCTIVE)
        d.setResponseAppearance("save", ResponseAppearance.SUGGESTED)
        d.defaultResponse = "save"; d.closeResponse = "cancel"
        d.onResponse(null) { id ->
            when (id) {
                "discard" -> proceed()
                "save" -> save(false, proceed)
            }
        }
        d.present(window)
    }

    private fun updateTitle() {
        val s = session
        val st = state
        if (s == null || st == null) { title.title = "photographics"; title.subtitle = ""; window.title = "photographics"; return }
        val mark = if (st.dirty) "• " else ""
        title.title = mark + s.name
        title.subtitle = "${st.width} × ${st.height} px · ${st.profileName}" + if (st.bitDepth == 16) tr(" · 16 Bit") else ""
        (actions["bit-depth"] as? SimpleAction)?.state = org.gnome.glib.Variant.string(st.bitDepth.toString())
        window.title = "$mark${s.name} – photographics"
    }

    fun present() = window.present()

    private var heldAt: Pair<Double, Double>? = null

    /** Nur für Bildschirmfotos (siehe PhotographicsApp.screenshotLater). */
    fun debugZoom(z: Double) = canvas.setZoom(z)
    fun debugScale(s: Double) { canvas.screenshotScale = s }

    /**
     * Testaktionen: Fensteraktion („new-layer“, „filter::3“), „tool:BRUSH“ wählt ein Werkzeug,
     * „drag:x0;y0;x1;y1“ zieht mit dem aktiven Werkzeug (Dokumentkoordinaten), „color:FFRRGGBB“.
     */
    /** Zuletzt geöffneter Ebenenstil (für Testaktionen „fx:…“). */
    private var styleDialog: LayerStyleDialog? = null

    fun debugAction(name: String) {
        when {
            // „fx:bevel“ schaltet einen Effekt im offenen Ebenenstil ein, „fx:ok“ übernimmt
            name.startsWith("fx:") -> styleDialog?.debug(name.substring(3))
            name.startsWith("tool:") -> Tool.entries.firstOrNull { it.name == name.substring(5) }?.let { t -> c.setOptions { it.copy(tool = t) } }
            name.startsWith("drag:") -> {
                val v = name.substring(5).split(';').map { it.toDouble() }
                c.press(v[0], v[1], 1f, emptySet())
                val n = 24
                for (i in 1..n) c.drag(v[0] + (v[2] - v[0]) * i / n, v[1] + (v[3] - v[1]) * i / n, 1f, emptySet())
                c.release(v[2], v[3], emptySet())
            }
            // „hold:x0;y0;x1;y1“ zieht ohne loszulassen (Vorschau prüfen), „release“ lässt los
            name.startsWith("hold:") || name.startsWith("chold:") -> {
                val v = name.substringAfter(':').split(';').map { it.toDouble() }
                val m = if (name[0] == 'c') setOf(ModifierType.CONTROL_MASK) else emptySet()
                c.press(v[0], v[1], 1f, m)
                for (i in 1..12) c.drag(v[0] + (v[2] - v[0]) * i / 12, v[1] + (v[3] - v[1]) * i / 12, 1f, m)
                heldAt = v[2] to v[3]
            }
            name == "pathmode" -> c.setOptions { it.copy(shapeLayerMode = false) }
            name.startsWith("chview:") -> canvas.channelView = name.substring(7).toInt()
            name == "savesel" -> c.edit(null) { it.saveSelectionAsChannel(); IntRect.EMPTY }
            name.startsWith("click:") -> name.substring(6).split(';').map { it.toDouble() }.let { (x, y) -> c.press(x, y, 1f, emptySet()); c.release(x, y, emptySet()) }
            name == "release" -> heldAt?.let { (x, y) -> c.release(x, y, emptySet()); heldAt = null }
            name.startsWith("color:") -> name.substring(6).toLongOrNull(16)?.let { col -> c.setOptions { it.copy(color = col.toInt()) } }
            name == "apply" -> if (props.open) props.close(true) else c.confirm()
            // „drop:Name;Ziel;into|above|below“ – wie Ziehen im Ebenenbedienfeld
            name.startsWith("drop:") -> {
                val (n, t, where) = name.substring(5).split(';')
                val rows = state?.layers ?: return
                val src = rows.firstOrNull { it.name == n } ?: return
                val dst = rows.firstOrNull { it.name == t } ?: return
                val a = LayerActions()
                when (where) {
                    "into" -> a.drop(listOf(src.id), dst.id, dst.childCount)
                    "above" -> a.drop(listOf(src.id), dst.parentId, dst.index + 1)
                    else -> a.drop(listOf(src.id), dst.parentId, dst.index)
                }
            }
            name.startsWith("layer:") -> state?.layers?.firstOrNull { it.name == name.substring(6) }?.let { LayerActions().select(it.id, setOf(it.id)) }
            name.startsWith("solo:") -> state?.layers?.firstOrNull { it.name == name.substring(5) }?.let { LayerActions().solo(it.id) }
            name.startsWith("adjust:") -> {
                val (dx, dy) = name.substring(7).split(';').map { it.toDouble() }
                c.brushAdjust(0, 0.0, 0.0); c.brushAdjust(1, dx, dy)
            }
            name.startsWith("picker") -> brushPicker(400.0, 300.0)
            name.startsWith("opacity:") -> LayerActions().opacity(name.substring(8).toFloat())
            name.startsWith("tab:") -> name.substring(4).let { n -> if (n == "history" || n == "swatches") tabs2.visibleChildName = n else tabs.visibleChildName = n }
            name.startsWith("cropangle:") -> c.setOptions { it.copy(cropAngle = name.substring(10).toFloat()) }
            name == "cropstraighten" -> c.setOptions { it.copy(cropStraighten = true) }
            name == "cropperspective" -> c.setOptions { it.copy(cropPerspective = true) }
            name.startsWith("printpdf:") -> session?.let { s -> Engine.call({ Printing.render(s.doc) to s.doc.dpi }) { r -> r.getOrNull()?.let { (img, dpi) -> Printing.print(window, s.name, img, dpi, File(name.substring(9))) } } }
            name.startsWith("guide:") -> name.substring(6).split(';').let { (v, p) -> c.addGuide(v == "v", p.toDouble()) }
            name.startsWith("layout:") -> state?.let { st -> name.substring(7).split(';').map { it.toDouble() }.let { v -> c.edit(null) { e -> e.setGuides("Layout", guideLayout(st.width.toDouble(), st.height.toDouble(), v[0].toInt(), v[1].toInt(), v[2], v[3])); IntRect.EMPTY } } }
            name.startsWith("cdrag:") -> {
                val v = name.substring(6).split(';').map { it.toDouble() }
                val m = setOf(ModifierType.CONTROL_MASK)
                c.press(v[0], v[1], 1f, m)
                for (i in 1..12) c.drag(v[0] + (v[2] - v[0]) * i / 12, v[1] + (v[3] - v[1]) * i / 12, 1f, m)
                c.release(v[2], v[3], m)
            }
            name.startsWith("pan:") -> name.substring(4).split(';').map { it.toDouble() }.let { (dx, dy) -> canvas.debugPan(dx, dy) }
            else -> {
                val a = actions[name.substringBefore("::")]
                if (a == null || !a.enabled) System.err.println("Aktion $name nicht verfügbar")
                else if ("::" in name) a.activate(org.gnome.glib.Variant.string(name.substringAfter("::"))) else a.activate(null)
            }
        }
    }
}
