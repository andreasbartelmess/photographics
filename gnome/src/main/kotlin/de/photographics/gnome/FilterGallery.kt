package de.photographics.gnome

import de.photographics.core.doc.PixelLayer
import de.photographics.core.filters.Gallery
import de.photographics.core.filters.GalleryCategory
import de.photographics.core.filters.GalleryChain
import de.photographics.core.filters.GalleryContext
import de.photographics.core.filters.GalleryEffect
import de.photographics.core.filters.GallerySpec
import de.photographics.core.image.IntRect
import de.photographics.core.tr
import org.gnome.gdk.MemoryFormat
import org.gnome.gdk.MemoryTexture
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.Picture
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.Widget

/**
 * Filtergalerie wie in Photoshop: links die Vorschau, in der Mitte die Effekte nach Gruppen mit
 * Miniaturen aus dem eigenen Bild, rechts die Regler des gewählten Effekts und der Effektstapel
 * (mehrere Effekte nacheinander, einzeln ein- und ausblendbar).
 */
class FilterGallery(private val parent: Widget, private val session: Session, private val view: IntRect, private val fg: Int, private val bg: Int, private val onApply: (GalleryChain) -> Unit) {
    /** Ausgangsbild der Vorschau: ganze Ebene verkleinert oder ein Ausschnitt in 100 %. */
    private class Source(val px: IntArray, val w: Int, val h: Int, val ox: Int, val oy: Int, val scale: Float)

    private val dialog = org.gnome.adw.Dialog()
    private val picture = Picture().apply { contentFit = org.gnome.gtk.ContentFit.CONTAIN; canShrink = true; hexpand = true; vexpand = true }
    private val params = Box(Orientation.VERTICAL, 10)
    private val stackList = org.gnome.gtk.ListBox().apply { addCssClass("boxed-list"); selectionMode = org.gnome.gtk.SelectionMode.SINGLE }
    private val thumbButtons = HashMap<String, ToggleButton>()
    private val thumbPictures = HashMap<String, Picture>()
    private val spinner = org.gnome.gtk.Spinner()
    private var fit: Source? = null
    private var crop: Source? = null
    private var full = false
    private var selected = 0
    private val effects = ArrayList<GalleryEffect>()
    private var running = false
    @Volatile private var pending = false
    @Volatile private var closed = false
    /** Quelle, für die schon eine genaue Vorschau zu sehen ist. */
    private var exactShownFor: Source? = null
    private var updating = false

    init {
        // PG_GALLERY_EFFECT=<id>: mit diesem Effekt öffnen (Bildschirmfotos)
        val first = System.getenv("PG_GALLERY_EFFECT")?.let { Gallery.byId(it) } ?: Gallery.specs.first()
        effects += last ?: listOf(GalleryEffect(first, first.defaults()))
        dialog.title = tr("Filtergalerie")
        dialog.contentWidth = 1280; dialog.contentHeight = 820
        val header = org.gnome.adw.HeaderBar().apply { showEndTitleButtons = false; showStartTitleButtons = false }
        header.packStart(Button.withLabel(tr("Abbrechen")).apply { onClicked { closed = true; dialog.close() } })
        header.packEnd(Button.withLabel(tr("Anwenden")).apply {
            addCssClass("suggested-action")
            onClicked { closed = true; last = effects.toList(); onApply(chain(1f)); dialog.close() }
        })
        header.packEnd(spinner)
        val zoom = org.gnome.gtk.DropDown.fromStrings(arrayOf(tr("Ganzes Bild"), tr("100 %"))).apply {
            tooltipText = tr("Vorschau")
            onNotify("selected") { _ -> full = selected == 1; render() }
        }
        header.packStart(zoom)

        // Vorschau | Effekte | Regler und Stapel
        // Feste Mindestgröße: ohne Bild hätte die Vorschau keine natürliche Breite, und ihre Größe
        // bestimmt, in welcher Auflösung die Vorschau gerechnet wird
        val previewFrame = Box(Orientation.VERTICAL, 0).apply {
            addCssClass("gallery-preview"); hexpand = true; vexpand = true
            setSizeRequest(560, 480)
            append(picture)
        }
        val catalog = Box(Orientation.VERTICAL, 6).apply { marginStart = 12; marginEnd = 12; marginTop = 12; marginBottom = 12 }
        for (cat in GalleryCategory.entries) {
            val flow = org.gnome.gtk.FlowBox().apply {
                selectionMode = org.gnome.gtk.SelectionMode.NONE
                minChildrenPerLine = 3; maxChildrenPerLine = 3
                rowSpacing = 6; columnSpacing = 6; homogeneous = true
            }
            for (s in Gallery.specs.filter { it.category == cat }) flow.append(thumb(s))
            catalog.append(org.gnome.gtk.Expander(cat.label).apply { child = flow; expanded = cat == effects.getOrNull(0)?.spec?.category || System.getenv("PG_GALLERY_EXPAND") == cat.name; addCssClass("heading") })
        }
        val middle = ScrolledWindow().apply {
            setPolicy(org.gnome.gtk.PolicyType.NEVER, org.gnome.gtk.PolicyType.AUTOMATIC)
            child = catalog
            setSizeRequest(372, -1)
        }
        val right = Box(Orientation.VERTICAL, 12).apply { marginStart = 12; marginEnd = 12; marginTop = 12; marginBottom = 12; setSizeRequest(300, -1) }
        right.append(ScrolledWindow().apply { setPolicy(org.gnome.gtk.PolicyType.NEVER, org.gnome.gtk.PolicyType.AUTOMATIC); vexpand = true; child = params })
        right.append(Label(tr("Effektebenen")).apply { xalign = 0f; addCssClass("heading") })
        right.append(ScrolledWindow().apply { setPolicy(org.gnome.gtk.PolicyType.NEVER, org.gnome.gtk.PolicyType.AUTOMATIC); setSizeRequest(-1, 180); child = stackList })
        right.append(Box(Orientation.HORIZONTAL, 6).apply {
            halign = Align.END
            append(Button.fromIconName("list-add-symbolic").apply {
                tooltipText = tr("Neue Effektebene"); addCssClass("flat")
                onClicked { effects.add(selected + 1, effects[selected].copy()); selected++; refresh(); render() }
            })
            append(Button.fromIconName("user-trash-symbolic").apply {
                tooltipText = tr("Effektebene löschen"); addCssClass("flat")
                onClicked { if (effects.size > 1) { effects.removeAt(selected); selected = selected.coerceAtMost(effects.size - 1); refresh(); render() } }
            })
        })
        stackList.onRowSelected { row -> if (row != null && row.index != selected && !updating) { selected = row.index; refresh() } }

        val body = Box(Orientation.HORIZONTAL, 0)
        body.append(previewFrame)
        body.append(org.gnome.gtk.Separator(Orientation.VERTICAL))
        body.append(middle)
        body.append(org.gnome.gtk.Separator(Orientation.VERTICAL))
        body.append(right)
        dialog.child = org.gnome.adw.ToolbarView().apply { addTopBar(header); content = body }
        dialog.onClosed { closed = true }
        refresh()
    }

    fun present() {
        dialog.present(parent)
        waitForSize(0, 0, 0)
    }

    /** Die Quellen werden in der Pixelgröße der Vorschaufläche gerechnet: erst messen, wenn sie zugeteilt ist. */
    private fun waitForSize(tries: Int, lastW: Int, lastH: Int) {
        org.gnome.glib.GLib.timeoutAddOnce(50) {
            if (closed) return@timeoutAddOnce
            // Während der Dialog eingeblendet wird, stimmt die Zuteilung noch nicht: auf zwei gleiche,
            // plausible Messungen warten (höchstens etwa eine Sekunde)
            val k = maxOf(1, picture.scaleFactor)
            val w = picture.width; val h = picture.height
            val plausible = w >= 64 && h >= 64 && h <= dialog.height && w <= dialog.width
            if ((!plausible || w != lastW || h != lastH) && tries < 20) waitForSize(tries + 1, w, h)
            else if (plausible) loadSource(w * k, h * k).also { if (System.getenv("PG_SCREENSHOT") != null) println("Galerie-Vorschau: ${w * k} × ${h * k} px") }
            else loadSource(dialog.contentWidth * k / 2, dialog.contentHeight * k * 4 / 5)
        }
    }

    private fun chain(scale: Float, list: List<GalleryEffect> = effects) = GalleryChain(list, GalleryContext(fg, bg, scale))

    // ------------------------------------------------------------ Oberfläche

    /** Regler und Stapel für den aktuellen Stand neu aufbauen. */
    private fun refresh() {
        updating = true
        val e = effects[selected]
        for ((id, b) in thumbButtons) b.active = id == e.spec.id
        while (true) { val c = params.firstChild ?: break; params.remove(c) }
        params.append(Label(e.spec.label).apply { xalign = 0f; addCssClass("title-4") })
        if (e.spec.usesColors) params.append(Label(tr("Verwendet Vorder- und Hintergrundfarbe")).apply { xalign = 0f; wrap = true; addCssClass("caption"); addCssClass("dim-label") })
        for ((i, p) in e.spec.params.withIndex()) {
            val opts = p.options
            if (opts != null) {
                params.append(Label(p.label).apply { xalign = 0f; addCssClass("dim-label") })
                params.append(org.gnome.gtk.DropDown.fromStrings(opts.toTypedArray()).apply {
                    selected = e.values[i].toInt()
                    onNotify("selected") { _ -> setValue(i, selected.toFloat()) }
                })
            } else {
                val value = Label("").apply { addCssClass("numeric") }
                fun show(v: Float) { value.label = v.toInt().toString() }
                params.append(Box(Orientation.HORIZONTAL, 6).apply {
                    append(Label(p.label).apply { xalign = 0f; hexpand = true; addCssClass("dim-label") })
                    append(value)
                })
                val sc = org.gnome.gtk.Scale.withRange(Orientation.HORIZONTAL, p.min.toDouble(), p.max.toDouble(), 1.0)
                sc.drawValue = false
                sc.value = e.values[i].toDouble()
                show(e.values[i])
                sc.onValueChanged { val v = Math.round(sc.value).toFloat(); show(v); setValue(i, v) }
                params.append(sc)
            }
        }
        params.append(Button.withLabel(tr("Standardwerte")).apply {
            halign = Align.START; addCssClass("flat")
            onClicked { effects[selected] = effects[selected].copy(values = effects[selected].spec.defaults()); refresh(); render() }
        })
        stackList.removeAll()
        // Oberste Effektebene oben (wie Photoshop): zuletzt angewendet
        for ((i, fx) in effects.withIndex()) {
            val row = org.gnome.adw.ActionRow().apply {
                title = fx.spec.label
                addPrefix(ToggleButton().apply {
                    iconName = if (fx.visible) "view-reveal-symbolic" else "pg-hidden-symbolic"
                    active = fx.visible
                    addCssClass("flat"); addCssClass("eye"); valign = Align.CENTER
                    tooltipText = tr("Effekt ein-/ausblenden")
                    onToggled { effects[i] = effects[i].copy(visible = active); refresh(); render() }
                })
            }
            stackList.append(row)
        }
        stackList.selectRow(stackList.getRowAtIndex(selected))
        updating = false
    }

    private fun setValue(i: Int, v: Float) {
        if (updating) return
        val e = effects[selected]
        if (e.values[i] == v) return
        effects[selected] = e.copy(values = e.values.copyOf().also { it[i] = v })
        render()
    }

    /** Miniatur eines Effekts (aus dem eigenen Bild, nach dem Laden gerechnet). */
    private fun thumb(s: GallerySpec): Widget {
        val pic = Picture().apply { setSizeRequest(104, 70); contentFit = org.gnome.gtk.ContentFit.COVER; canShrink = true; addCssClass("gallery-thumb") }
        val b = ToggleButton().apply {
            addCssClass("flat"); addCssClass("gallery-item")
            tooltipText = s.label
            child = Box(Orientation.VERTICAL, 4).apply {
                append(pic)
                append(Label(s.label).apply { ellipsize = org.gnome.pango.EllipsizeMode.END; maxWidthChars = 1; hexpand = true; addCssClass("caption") })
            }
            onClicked {
                if (updating) return@onClicked
                // Wie Photoshop: der Klick ersetzt den Effekt der gewählten Effektebene
                val cur = effects[selected]
                if (cur.spec !== s) effects[selected] = GalleryEffect(s, remembered[s.id] ?: s.defaults(), cur.visible)
                refresh(); render()
            }
        }
        thumbButtons[s.id] = b
        thumbPictures[s.id] = pic
        return b
    }

    // ------------------------------------------------------------ Rechnen

    /**
     * Ebenenpixel holen: auf die Vorschaufläche [boxW]×[boxH] (Gerätepixel) verkleinert (ganzes Bild)
     * und ein Ausschnitt in 100 % in Größe der Fläche um die Ansichtsmitte – scharf statt hochskaliert.
     */
    private fun loadSource(boxW: Int, boxH: Int) {
        spinner.spinning = true
        Engine.call({
            val l = session.doc.activeLayer as? PixelLayer ?: return@call null
            val g = l.pixels
            val d = session.doc
            val bw = boxW.coerceAtMost(4096); val bh = boxH.coerceAtMost(4096)
            val sc = minOf(1f, bw.toFloat() / d.width, bh.toFloat() / d.height)
            val tw = maxOf(1, kotlin.math.round(d.width * sc).toInt()); val th = maxOf(1, kotlin.math.round(d.height * sc).toInt())
            // Größte Mip-Stufe, die nicht kleiner als das Ziel ist, dann gefiltert auf die Zielgröße
            var level = 0
            while ((d.width shr (level + 1)) >= tw && (d.height shr (level + 1)) >= th) level++
            val m = if (level > 0) g.mip(level) else g
            val fw = (d.width + (1 shl level) - 1) shr level; val fh = (d.height + (1 shl level) - 1) shr level
            val mpx = IntArray(fw * fh).also { m.readRect(IntRect.ofSize(fw, fh), it) }
            val fitSrc = if (fw == tw && fh == th) Source(mpx, fw, fh, 0, 0, 1f / (1 shl level))
                else Source(de.photographics.core.image.Resample.resizeArgb(mpx, fw, fh, tw, th, de.photographics.core.image.ResampleMethod.BICUBIC), tw, th, 0, 0, tw.toFloat() / d.width)
            val cw = minOf(d.width, bw); val ch = minOf(d.height, bh)
            val cx = ((view.left + view.right) / 2 - cw / 2).coerceIn(0, d.width - cw); val cy = ((view.top + view.bottom) / 2 - ch / 2).coerceIn(0, d.height - ch)
            val cpx = IntArray(cw * ch).also { g.readRect(IntRect(cx, cy, cx + cw, cy + ch), it) }
            fitSrc to Source(cpx, cw, ch, cx, cy, 1f)
        }) { r ->
            val (f, c) = r.getOrNull() ?: run { spinner.spinning = false; return@call }
            fit = f; crop = c
            render()
            renderThumbs(f)
        }
    }

    /** Vorschau neu rechnen; Änderungen während einer Rechnung werden zusammengefasst. */
    private fun render() {
        if (closed) return
        val src = (if (full) crop else fit) ?: return
        if (running) { pending = true; return }
        running = true; pending = false
        spinner.spinning = true
        val list = effects.toList()
        val c = chain(src.scale, list)
        remembered[effects[selected].spec.id] = effects[selected].values
        // Ganzes Bild: verkleinert gerechnet sehen Striche, Korn und feine Linien anders aus als nach
        // dem Anwenden – nach der schnellen Vorschau daher in voller Auflösung rechnen und verkleinern
        val exact = !full && src.scale < 1f
        val layer = session.doc.activeLayer as? PixelLayer
        fun show(out: IntArray?) {
            val bytes = out?.let { CanvasView.toBytes(it) } ?: return
            onMain {
                if (!closed) picture.paintable = MemoryTexture(src.w, src.h, MemoryFormat.B8G8R8A8, bytes, src.w * 4L).also { t ->
                    // Nur für Tests: das Fensterfoto erfasst den Dialoginhalt nicht
                    System.getenv("PG_GALLERY_DUMP")?.let { t.saveToPng(it) }
                }
            }
        }
        // Schnelle, ungenaue Vorschau nur beim ersten Bild einer Quelle; danach läuft jede genaue
        // Rechnung zu Ende (neue Änderungen warten und nehmen dann den neuesten Stand) – sonst käme
        // beim Ziehen eines Reglers nie ein genaues Bild
        val quick = !exact || exactShownFor !== src
        Thread {
            if (quick) show(runCatching { c.process(src.px.copyOf(), src.w, src.h, src.ox, src.oy, src.w, src.h) }.getOrNull())
            if (exact && layer != null) runCatching {
                de.photographics.core.filters.FilterRunner.previewScaled(session.doc, layer, chain(1f, list), src.w, src.h) { closed }
            }.getOrNull()?.let { show(it); onMain { exactShownFor = src } }
            onMain {
                running = false
                spinner.spinning = pending
                if (pending) render()
            }
        }.apply { isDaemon = true; name = "photographics-galerie" }.start()
    }

    /** Miniaturen aller Effekte aus einem verkleinerten Bildausschnitt (im Hintergrund). */
    private fun renderThumbs(f: Source) {
        // Ausschnitt im Seitenverhältnis der Kachel (Gerätepixel), gefiltert verkleinert
        val k = maxOf(1, picture.scaleFactor)
        val tw = 104 * k; val th = 70 * k
        val q = minOf(f.w.toFloat() / tw, f.h.toFloat() / th).coerceAtLeast(1f)
        val cw = minOf(f.w, kotlin.math.round(tw * q).toInt()); val ch = minOf(f.h, kotlin.math.round(th * q).toInt())
        val sw = maxOf(1, kotlin.math.round(cw / q).toInt()); val sh = maxOf(1, kotlin.math.round(ch / q).toInt())
        val x0 = (f.w - cw) / 2; val y0 = (f.h - ch) / 2
        val crop = IntArray(cw * ch) { i -> f.px[(y0 + i / cw) * f.w + x0 + i % cw] }
        val thumbScale = f.scale * sw / cw
        Thread {
            val small = if (sw == cw && sh == ch) crop else de.photographics.core.image.Resample.resizeArgb(crop, cw, ch, sw, sh, de.photographics.core.image.ResampleMethod.BICUBIC)
            for (s in Gallery.specs) {
                if (closed) return@Thread
                val out = runCatching { GalleryChain(listOf(GalleryEffect(s, s.defaults())), GalleryContext(fg, bg, thumbScale)).process(small.copyOf(), sw, sh, 0, 0, sw, sh) }.getOrNull() ?: continue
                val bytes = CanvasView.toBytes(out)
                onMain { if (!closed) thumbPictures[s.id]?.paintable = MemoryTexture(sw, sh, MemoryFormat.B8G8R8A8, bytes, sw * 4L) }
            }
        }.apply { isDaemon = true; name = "photographics-galerie-miniaturen" }.start()
    }

    companion object {
        /** Zuletzt angewendeter Stapel (beim nächsten Öffnen wieder da, wie in Photoshop). */
        private var last: List<GalleryEffect>? = null
        /** Zuletzt benutzte Werte je Effekt. */
        private val remembered = HashMap<String, FloatArray>()
    }
}
