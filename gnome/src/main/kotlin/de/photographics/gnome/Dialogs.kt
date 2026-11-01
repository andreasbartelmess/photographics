package de.photographics.gnome

import de.photographics.core.image.ResampleMethod
import de.photographics.core.render.BlendMode
import de.photographics.core.tr
import org.gnome.adw.AlertDialog
import org.gnome.adw.ResponseAppearance
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.CheckButton
import org.gnome.gtk.DropDown
import org.gnome.gtk.Entry
import org.gnome.gtk.Grid
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.SpinButton
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.Widget
import kotlin.math.roundToInt

/** Kleine Formulardialoge (Bildgröße, Arbeitsfläche, Drehen …) als libadwaita-Hinweisdialoge. */
object Dialogs {
    private class Form {
        val grid = Grid().apply { rowSpacing = 8; columnSpacing = 12; marginTop = 6 }
        private var row = 0
        fun add(label: String?, w: Widget) {
            if (label != null) grid.attach(Label(label).apply { xalign = 0f; addCssClass("dim-label") }, 0, row, 1, 1)
            w.hexpand = true
            grid.attach(w, if (label != null) 1 else 0, row, if (label != null) 1 else 2, 1)
            row++
        }
    }

    private fun spin(min: Double, max: Double, value: Double, digits: Int = 0) = SpinButton.withRange(min, max, if (digits == 0) 1.0 else 0.1).apply {
        this.digits = digits; this.value = value; widthChars = 7
    }

    private fun show(parent: Widget, heading: String, body: String?, form: Form, ok: String, onOk: () -> Unit) {
        val d = AlertDialog(heading, body)
        d.extraChild = form.grid
        d.addResponse("cancel", tr("Abbrechen"))
        d.addResponse("ok", ok)
        d.setResponseAppearance("ok", ResponseAppearance.SUGGESTED)
        d.defaultResponse = "ok"; d.closeResponse = "cancel"
        d.onResponse(null) { id -> if (id == "ok") onOk() }
        d.present(parent)
    }

    fun newDocument(parent: Widget, onOk: (Int, Int, Boolean, Float) -> Unit) {
        val f = Form()
        val w = spin(1.0, 30000.0, 1920.0); val h = spin(1.0, 30000.0, 1080.0); val dpi = spin(1.0, 2400.0, 72.0)
        val transparent = CheckButton.withLabel(tr("Transparenter Hintergrund"))
        f.add(tr("Breite (px)"), w); f.add(tr("Höhe (px)"), h); f.add(tr("Auflösung (dpi)"), dpi); f.add(null, transparent)
        show(parent, tr("Neues Bild"), null, f, tr("Erstellen")) { onOk(w.value.toInt(), h.value.toInt(), transparent.active, dpi.value.toFloat()) }
    }

    /** [aiModel]: Name eines installierten Hochskalierungsmodells (dann gibt es die KI-Option). */
    fun imageSize(parent: Widget, w0: Int, h0: Int, dpi0: Float, aiModel: String?, onOk: (Int, Int, ResampleMethod, Float, Boolean) -> Unit) {
        val f = Form()
        val w = spin(1.0, 60000.0, w0.toDouble()); val h = spin(1.0, 60000.0, h0.toDouble()); val pct = spin(1.0, 2000.0, 100.0, 1)
        val keep = CheckButton.withLabel(tr("Seitenverhältnis beibehalten")).apply { active = true }
        val dpi = spin(1.0, 2400.0, dpi0.toDouble())
        val methods = ResampleMethod.entries
        val method = DropDown.fromStrings(methods.map { it.label }.toTypedArray()).apply { selected = methods.indexOf(ResampleMethod.BICUBIC) }
        var lock = false
        fun sync(from: SpinButton) {
            if (lock) return
            lock = true
            when (from) {
                w -> { if (keep.active) h.value = (w.value * h0 / w0).roundToInt().coerceAtLeast(1).toDouble(); pct.value = w.value * 100 / w0 }
                h -> { if (keep.active) w.value = (h.value * w0 / h0).roundToInt().coerceAtLeast(1).toDouble(); pct.value = h.value * 100 / h0 }
                pct -> { w.value = (w0 * pct.value / 100).roundToInt().coerceAtLeast(1).toDouble(); h.value = (h0 * pct.value / 100).roundToInt().coerceAtLeast(1).toDouble() }
            }
            lock = false
        }
        w.onValueChanged { sync(w) }; h.onValueChanged { sync(h) }; pct.onValueChanged { sync(pct) }
        f.add(tr("Breite (px)"), w); f.add(tr("Höhe (px)"), h); f.add(tr("Prozent"), pct); f.add(null, keep)
        f.add(tr("Auflösung (dpi)"), dpi); f.add(tr("Neuberechnung"), method)
        val ai = CheckButton.withLabel(if (aiModel != null) tr("KI-Hochskalierung ({0})", aiModel) else tr("KI-Hochskalierung (Modell unter „KI-Modelle“ laden)"))
        ai.sensitive = aiModel != null
        f.add(null, ai)
        show(parent, tr("Bildgröße"), tr("Aktuell: {0} × {1} px", w0, h0), f, tr("Ändern")) {
            onOk(w.value.toInt(), h.value.toInt(), methods[method.selected], dpi.value.toFloat(), ai.active)
        }
    }

    fun canvasSize(parent: Widget, w0: Int, h0: Int, onOk: (Int, Int, Float, Float) -> Unit) {
        val f = Form()
        val w = spin(1.0, 60000.0, w0.toDouble()); val h = spin(1.0, 60000.0, h0.toDouble())
        // Anker: 3 × 3 Knöpfe, die Mitte ist voreingestellt
        val anchor = Grid().apply { halign = Align.START; rowSpacing = 2; columnSpacing = 2 }
        var ax = 0.5f; var ay = 0.5f
        val btns = ArrayList<ToggleButton>()
        for (r in 0..2) for (c in 0..2) {
            val b = ToggleButton().apply { setSizeRequest(28, 28); active = r == 1 && c == 1 }
            b.onToggled {
                if (b.active) { ax = c / 2f; ay = r / 2f; for (o in btns) if (o !== b) o.active = false }
                else if (ax == c / 2f && ay == r / 2f) b.active = true
            }
            btns += b
            anchor.attach(b, c, r, 1, 1)
        }
        f.add(tr("Breite (px)"), w); f.add(tr("Höhe (px)"), h); f.add(tr("Anker"), anchor)
        show(parent, tr("Arbeitsfläche"), tr("Aktuell: {0} × {1} px", w0, h0), f, tr("Ändern")) { onOk(w.value.toInt(), h.value.toInt(), ax, ay) }
    }

    /** Exportieren als: Format, JPEG-Qualität, Größe (Seitenverhältnis bleibt), sRGB. */
    fun export(parent: Widget, w0: Int, h0: Int, onOk: (FileIO.Format, FileIO.ExportOptions) -> Unit) {
        val f = Form()
        val formats = listOf(FileIO.Format.JPEG, FileIO.Format.PNG)
        val format = DropDown.fromStrings(arrayOf("JPEG", "PNG")).apply { selected = if (Settings.exportFormat == "PNG") 1 else 0 }
        val quality = org.gnome.gtk.Scale.withRange(Orientation.HORIZONTAL, 1.0, 100.0, 1.0).apply {
            value = Settings.jpegQuality.toDouble(); drawValue = true; digits = 0; hexpand = true
        }
        val qualityRow = Box(Orientation.HORIZONTAL, 6).apply { append(quality) }
        val w = spin(1.0, 60000.0, w0.toDouble()); val h = spin(1.0, 60000.0, h0.toDouble()); val pct = spin(1.0, 1000.0, 100.0, 1)
        var lock = false
        fun sync(from: SpinButton) {
            if (lock) return
            lock = true
            when (from) {
                w -> { h.value = (w.value * h0 / w0).roundToInt().coerceAtLeast(1).toDouble(); pct.value = w.value * 100 / w0 }
                h -> { w.value = (h.value * w0 / h0).roundToInt().coerceAtLeast(1).toDouble(); pct.value = h.value * 100 / h0 }
                pct -> { w.value = (w0 * pct.value / 100).roundToInt().coerceAtLeast(1).toDouble(); h.value = (h0 * pct.value / 100).roundToInt().coerceAtLeast(1).toDouble() }
            }
            lock = false
        }
        w.onValueChanged { sync(w) }; h.onValueChanged { sync(h) }; pct.onValueChanged { sync(pct) }
        val srgb = CheckButton.withLabel(tr("In sRGB umwandeln (Web, Geräte ohne Farbmanagement)")).apply { active = Settings.exportToSrgb }
        val note = Label(tr("JPEG kennt keine Transparenz – sie wird auf Weiß reduziert.")).apply { addCssClass("dim-label"); addCssClass("caption"); xalign = 0f; wrap = true }
        fun upd() { val jpeg = format.selected == 0; qualityRow.sensitive = jpeg; note.visible = jpeg }
        format.onNotify("selected") { _ -> upd() }
        upd()
        f.add(tr("Format"), format); f.add(tr("Qualität"), qualityRow); f.add(null, note)
        f.add(tr("Breite (px)"), w); f.add(tr("Höhe (px)"), h); f.add(tr("Prozent"), pct); f.add(null, srgb)
        show(parent, tr("Exportieren als"), null, f, tr("Exportieren …")) {
            val fmt = formats[format.selected]
            Settings.exportFormat = fmt.name; Settings.jpegQuality = quality.value.roundToInt(); Settings.exportToSrgb = srgb.active
            onOk(fmt, FileIO.ExportOptions(w.value.toInt(), h.value.toInt(), quality.value.roundToInt(), srgb.active))
        }
    }

    fun rotate(parent: Widget, onOk: (Double) -> Unit) {
        val f = Form()
        val a = spin(-359.9, 359.9, 0.0, 1)
        f.add(tr("Winkel (°, im Uhrzeigersinn)"), a)
        show(parent, tr("Bild drehen"), null, f, tr("Drehen")) { if (a.value != 0.0) onOk(a.value) }
    }

    fun newGuide(parent: Widget, onOk: (Boolean, Double) -> Unit) {
        val f = Form()
        val dir = DropDown.fromStrings(arrayOf(tr("Senkrecht"), tr("Waagerecht")))
        val pos = spin(0.0, 60000.0, 0.0, 1)
        f.add(tr("Ausrichtung"), dir); f.add(tr("Position (px)"), pos)
        show(parent, tr("Neue Hilfslinie"), null, f, tr("OK")) { onOk(dir.selected == 0, pos.value) }
    }

    fun guideLayout(parent: Widget, onOk: (Int, Int, Double, Double) -> Unit) {
        val f = Form()
        val cols = spin(1.0, 50.0, 3.0); val rows = spin(1.0, 50.0, 1.0); val margin = spin(0.0, 5000.0, 0.0); val gutter = spin(0.0, 1000.0, 0.0)
        f.add(tr("Spalten"), cols); f.add(tr("Zeilen"), rows); f.add(tr("Rand (px)"), margin); f.add(tr("Abstand (px)"), gutter)
        show(parent, tr("Neues Hilfslinienlayout"), null, f, tr("OK")) { onOk(cols.value.toInt(), rows.value.toInt(), margin.value, gutter.value) }
    }

    fun feather(parent: Widget, onOk: (Float) -> Unit) {
        val f = Form()
        val r = spin(0.1, 250.0, 5.0, 1)
        f.add(tr("Radius (px)"), r)
        show(parent, tr("Weiche Auswahlkante"), null, f, tr("OK")) { onOk(r.value.toFloat()) }
    }

    /** Ebeneneigenschaften: Name, Deckkraft, Fläche, Mischmodus, Fixieren, Schnittmaske. */
    fun layerProps(parent: Widget, row: LayerRow, onOk: (String, Float, Float, BlendMode, Boolean) -> Unit) {
        val f = Form()
        val name = Entry().apply { text = row.name; activatesDefault = true }
        val op = spin(0.0, 100.0, (row.opacity * 100.0).roundToInt().toDouble())
        val fill = spin(0.0, 100.0, (row.fillOpacity * 100.0).roundToInt().toDouble())
        val modes = BlendMode.entries.filter { it != BlendMode.PASS_THROUGH || row.kind == LayerRow.Kind.GROUP }
        val blend = DropDown.fromStrings(modes.map { it.label }.toTypedArray()).apply { selected = modes.indexOf(row.blend).coerceAtLeast(0) }
        val lock = CheckButton.withLabel(tr("Transparente Pixel fixieren")).apply { active = row.lockTransparency }
        f.add(tr("Name"), name); f.add(tr("Deckkraft (%)"), op); f.add(tr("Fläche (%)"), fill); f.add(tr("Mischmodus"), blend); f.add(null, lock)
        show(parent, tr("Ebeneneigenschaften"), null, f, tr("OK")) {
            onOk(name.text.ifBlank { row.name }, (op.value / 100).toFloat(), (fill.value / 100).toFloat(), modes[blend.selected], lock.active)
        }
    }

    /** Fläche füllen (Umschalt+F5): Inhalt, eigene Farbe, Mischmodus, Deckkraft, Transparenz erhalten. */
    fun fill(parent: Widget, color0: Int, onOk: (de.photographics.core.editor.FillContent, Int, BlendMode, Float, Boolean) -> Unit) {
        val f = Form()
        val contents = de.photographics.core.editor.FillContent.entries
        val content = DropDown.fromStrings(contents.map { it.label }.toTypedArray()).apply { selected = lastFill }
        fun rgba(c: Int) = org.gnome.gdk.RGBA(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, 1f)
        val custom = org.gnome.gtk.ColorDialogButton(org.gnome.gtk.ColorDialog().apply { withAlpha = false }).apply { rgba = rgba(lastFillColor ?: color0) }
        val modes = de.photographics.core.doc.LayerStyles.blendModes
        val blend = DropDown.fromStrings(modes.map { it.label }.toTypedArray()).apply { selected = 0 }
        val op = spin(1.0, 100.0, 100.0)
        val keep = CheckButton.withLabel(tr("Transparenz erhalten"))
        fun sync() {
            val c = contents[content.selected]
            custom.sensitive = c == de.photographics.core.editor.FillContent.COLOR
            val blendable = c != de.photographics.core.editor.FillContent.CONTENT_AWARE
            blend.sensitive = blendable; op.sensitive = blendable; keep.sensitive = blendable
        }
        content.onNotify("selected") { _ -> sync() }
        sync()
        f.add(tr("Inhalt"), content); f.add(tr("Farbe"), custom)
        f.add(tr("Modus"), blend); f.add(tr("Deckkraft (%)"), op); f.add(null, keep)
        show(parent, tr("Fläche füllen"), null, f, tr("OK")) {
            lastFill = content.selected
            val col = argbOf(custom.rgba); lastFillColor = col
            onOk(contents[content.selected], col, modes[blend.selected], (op.value / 100).toFloat(), keep.active)
        }
    }
    private var lastFill = 0
    private var lastFillColor: Int? = null

    fun confirm(parent: Widget, heading: String, body: String, ok: String, destructive: Boolean = false, onOk: () -> Unit) {
        val d = AlertDialog(heading, body)
        d.addResponse("cancel", tr("Abbrechen"))
        d.addResponse("ok", ok)
        d.setResponseAppearance("ok", if (destructive) ResponseAppearance.DESTRUCTIVE else ResponseAppearance.SUGGESTED)
        d.defaultResponse = "ok"; d.closeResponse = "cancel"
        d.onResponse(null) { id -> if (id == "ok") onOk() }
        d.present(parent)
    }

    @Suppress("unused") private fun hbox(vararg w: Widget) = Box(Orientation.HORIZONTAL, 6).apply { for (x in w) append(x) }

    /** Formeigenschaften: Füllung und Kontur (Farbe, Breite, Lage) einer Formebene. */
    fun shapeStyle(parent: Widget, fill: Int?, stroke: de.photographics.core.vector.ShapeStroke?, onOk: (Int?, de.photographics.core.vector.ShapeStroke?) -> Unit) {
        val f = Form()
        fun rgba(c: Int) = org.gnome.gdk.RGBA(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, ((c ushr 24) and 0xFF) / 255f)
        fun argb(r: org.gnome.gdk.RGBA) = (argbOf(r) and 0xFFFFFF) or ((r.readAlpha() * 255).toInt().coerceIn(0, 255) shl 24)
        val fillOn = CheckButton.withLabel(tr("Füllung")).apply { active = fill != null }
        val fillColor = org.gnome.gtk.ColorDialogButton(org.gnome.gtk.ColorDialog()).apply { rgba = rgba(fill ?: 0xFF000000.toInt()) }
        val strokeOn = CheckButton.withLabel(tr("Kontur")).apply { active = stroke != null }
        val strokeColor = org.gnome.gtk.ColorDialogButton(org.gnome.gtk.ColorDialog()).apply { rgba = rgba(stroke?.color ?: 0xFF000000.toInt()) }
        val width = spin(0.5, 500.0, (stroke?.width ?: 3f).toDouble(), 1)
        val aligns = de.photographics.core.vector.ShapeStroke.Align.entries
        val align = DropDown.fromStrings(aligns.map { it.label }.toTypedArray()).apply { selected = aligns.indexOf(stroke?.align ?: de.photographics.core.vector.ShapeStroke.Align.INSIDE) }
        f.add(null, fillOn); f.add(tr("Farbe"), fillColor)
        f.add(null, strokeOn); f.add(tr("Farbe"), strokeColor); f.add(tr("Breite (px)"), width); f.add(tr("Lage"), align)
        show(parent, tr("Formeigenschaften"), null, f, tr("Übernehmen")) {
            onOk(if (fillOn.active) argb(fillColor.rgba) else null,
                if (strokeOn.active) de.photographics.core.vector.ShapeStroke(width.value.toFloat(), argb(strokeColor.rgba), aligns[align.selected]) else null)
        }
    }
}
