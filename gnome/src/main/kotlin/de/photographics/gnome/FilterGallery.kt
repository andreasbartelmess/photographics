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
    private var pending = false
    private var closed = false
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
        val previewFrame = Box(Orientation.VERTICAL, 0).apply {
            addCssClass("gallery-preview"); hexpand = true
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
        loadSource()
    }

    fun present() = dialog.present(parent)

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

    /** Ebenenpixel holen: verkleinert (ganzes Bild) und einen Ausschnitt in 100 % um die Ansichtsmitte. */
    private fun loadSource() {
        spinner.spinning = true
        Engine.call({
            val l = session.doc.activeLayer as? PixelLayer ?: return@call null
            val g = l.pixels
            val d = session.doc
            var level = 0
            while (maxOf(d.width, d.height) shr level > 1100) level++
            val m = if (level > 0) g.mip(level) else g
            val fw = (d.width + (1 shl level) - 1) shr level; val fh = (d.height + (1 shl level) - 1) shr level
            val fpx = IntArray(fw * fh).also { m.readRect(IntRect.ofSize(fw, fh), it) }
            val fitSrc = Source(fpx, fw, fh, 0, 0, 1f / (1 shl level))
            val cw = minOf(d.width, 1000); val ch = minOf(d.height, 760)
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
        val c = chain(src.scale, effects.toList())
        remembered[effects[selected].spec.id] = effects[selected].values
        Thread {
            val out = runCatching { c.process(src.px.copyOf(), src.w, src.h, src.ox, src.oy, src.w, src.h) }.getOrNull()
            val bytes = out?.let { CanvasView.toBytes(it) }
            onMain {
                running = false
                spinner.spinning = pending
                if (bytes != null && !closed) picture.paintable = MemoryTexture(src.w, src.h, MemoryFormat.B8G8R8A8, bytes, src.w * 4L)
                if (pending) render()
            }
        }.apply { isDaemon = true; name = "photographics-galerie" }.start()
    }

    /** Miniaturen aller Effekte aus einem verkleinerten Bildausschnitt (im Hintergrund). */
    private fun renderThumbs(f: Source) {
        val tw = 208; val th = 140
        val step = maxOf(1, minOf(f.w / tw, f.h / th))
        val sw = minOf(f.w / step, tw); val sh = minOf(f.h / step, th)
        val x0 = (f.w - sw * step) / 2; val y0 = (f.h - sh * step) / 2
        val small = IntArray(sw * sh) { i -> f.px[(y0 + (i / sw) * step) * f.w + x0 + (i % sw) * step] }
        Thread {
            for (s in Gallery.specs) {
                if (closed) return@Thread
                val out = runCatching { GalleryChain(listOf(GalleryEffect(s, s.defaults())), GalleryContext(fg, bg, f.scale / step)).process(small.copyOf(), sw, sh, 0, 0, sw, sh) }.getOrNull() ?: continue
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
