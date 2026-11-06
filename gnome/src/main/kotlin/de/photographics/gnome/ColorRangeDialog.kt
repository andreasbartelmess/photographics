package de.photographics.gnome

import de.photographics.core.image.IntRect
import de.photographics.core.image.TileGrid
import de.photographics.core.render.Compositor
import de.photographics.core.doc.RasterLayer
import de.photographics.core.selection.ColorRange
import de.photographics.core.tr
import org.gnome.gdk.MemoryFormat
import org.gnome.gdk.MemoryTexture
import org.gnome.gdk.ModifierType
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.CheckButton
import org.gnome.gtk.DropDown
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.Picture
import org.gnome.gtk.Scale
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.Widget

/**
 * Auswahl → Farbbereich: Vorschau des Bildes (oder der entstehenden Auswahl als Graustufen),
 * Farben per Klick aufnehmen – Umschalt fügt hinzu, Alt zieht ab (oder die Pipetten-Knöpfe).
 */
class ColorRangeDialog(
    private val parent: Widget, private val session: Session, sampleAll0: Boolean,
    private val onApply: (TileGrid, ColorRange.Spec) -> Unit,
) {
    private enum class Pick { SET, ADD, SUBTRACT }

    private val dialog = org.gnome.adw.Dialog()
    private val picture = Picture().apply { contentFit = org.gnome.gtk.ContentFit.CONTAIN; canShrink = true; hexpand = true; vexpand = true }
    private var source: TileGrid? = null
    private var small: IntArray? = null
    private var sw = 0; private var sh = 0
    // PG_COLORRANGE_PRESET=<Name>: mit dieser Vorgabe öffnen (Bildschirmfotos)
    private var spec = System.getenv("PG_COLORRANGE_PRESET")?.let { ColorRange.Spec(ColorRange.Preset.valueOf(it)) } ?: last ?: ColorRange.Spec()
    private var showImage = false
    private var pick = Pick.SET
    private var sampleAll = sampleAll0
    @Volatile private var closed = false
    private val presetBox = DropDown.fromStrings(ColorRange.Preset.entries.map { it.label }.toTypedArray())
    private val fuzz = Scale.withRange(Orientation.HORIZONTAL, 0.0, 200.0, 1.0).apply { digits = 0; drawValue = true; hexpand = true }
    private val pickButtons = ArrayList<ToggleButton>()
    private val okButton = Button.withLabel(tr("OK"))

    init {
        dialog.title = tr("Farbbereich")
        dialog.contentWidth = 900; dialog.contentHeight = 620
        val header = org.gnome.adw.HeaderBar().apply { showEndTitleButtons = false; showStartTitleButtons = false }
        header.packStart(Button.withLabel(tr("Abbrechen")).apply { onClicked { closed = true; dialog.close() } })
        header.packEnd(okButton.apply {
            addCssClass("suggested-action")
            onClicked {
                val src = source ?: return@onClicked
                closed = true; last = spec.copy(samples = if (spec.preset == ColorRange.Preset.SAMPLED) spec.samples else emptyList())
                onApply(src, spec); dialog.close()
            }
        })

        val previewFrame = Box(Orientation.VERTICAL, 0).apply { addCssClass("gallery-preview"); hexpand = true; vexpand = true; setSizeRequest(520, 420); append(picture) }
        val click = org.gnome.gtk.GestureClick()
        click.onPressed { _, x, y ->
            val st = click.currentEventState
            val mode = when {
                st.contains(ModifierType.ALT_MASK) -> Pick.SUBTRACT
                st.contains(ModifierType.SHIFT_MASK) -> Pick.ADD
                else -> pick
            }
            sampleAt(x, y, mode)
        }
        picture.addController(click)

        val side = Box(Orientation.VERTICAL, 12).apply { marginStart = 12; marginEnd = 12; marginTop = 12; marginBottom = 12; setSizeRequest(300, -1) }
        side.append(Label(tr("Auswahl")).apply { xalign = 0f; addCssClass("heading") })
        presetBox.selected = spec.preset.ordinal
        presetBox.onNotify("selected") { _ -> spec = spec.copy(preset = ColorRange.Preset.entries[presetBox.selected]); updatePick(); render() }
        side.append(presetBox)
        val picks = Box(Orientation.HORIZONTAL, 0).apply { addCssClass("linked"); halign = Align.START }
        for ((mode, label) in listOf(Pick.SET to tr("Pipette"), Pick.ADD to tr("Hinzufügen"), Pick.SUBTRACT to tr("Abziehen"))) {
            val b = ToggleButton.withLabel(label).apply {
                active = mode == pick
                if (pickButtons.isNotEmpty()) setGroup(pickButtons.first())
                onToggled { if (active) pick = mode }
            }
            pickButtons += b; picks.append(b)
        }
        side.append(picks)
        side.append(Label(tr("Ins Bild klicken: Farbe aufnehmen. Umschalt fügt hinzu, Alt zieht ab.")).apply { xalign = 0f; wrap = true; addCssClass("dim-label"); addCssClass("caption") })
        side.append(Label(tr("Toleranz")).apply { xalign = 0f })
        fuzz.value = spec.fuzziness.toDouble()
        fuzz.onValueChanged { val v = fuzz.value.toInt(); if (v != spec.fuzziness) { spec = spec.copy(fuzziness = v); render() } }
        side.append(fuzz)
        side.append(CheckButton.withLabel(tr("Umkehren")).apply { active = spec.invert; onToggled { spec = spec.copy(invert = active); render() } })
        side.append(CheckButton.withLabel(tr("Alle Ebenen")).apply {
            active = sampleAll
            tooltipText = tr("Farben im sichtbaren Bild statt nur in der aktiven Ebene suchen")
            onToggled { sampleAll = active; load() }
        })
        val view = DropDown.fromStrings(arrayOf(tr("Auswahl"), tr("Bild"))).apply {
            onNotify("selected") { _ -> showImage = selected == 1; render() }
        }
        side.append(Box(Orientation.HORIZONTAL, 6).apply { append(Label(tr("Vorschau")).apply { addCssClass("dim-label") }); append(view) })
        updatePick()

        val body = Box(Orientation.HORIZONTAL, 0)
        body.append(previewFrame)
        body.append(org.gnome.gtk.Separator(Orientation.VERTICAL))
        body.append(side)
        dialog.child = org.gnome.adw.ToolbarView().apply { addTopBar(header); content = body }
        dialog.onClosed { closed = true }
    }

    fun present() {
        dialog.present(parent)
        load()
    }

    /** Testaktionen (PG_ACTIONS): „pick;fx;fy[;add|sub]“, „ok“, „image“. */
    fun debug(cmd: String) {
        val p = cmd.split(';')
        when (p[0]) {
            "pick" -> {
                val pw = picture.width.toDouble(); val ph = picture.height.toDouble()
                val k = minOf(pw / sw, ph / sh)
                val x = (pw - sw * k) / 2 + p[1].toDouble() * sw * k; val y = (ph - sh * k) / 2 + p[2].toDouble() * sh * k
                sampleAt(x, y, when (p.getOrNull(3)) { "add" -> Pick.ADD; "sub" -> Pick.SUBTRACT; else -> Pick.SET })
            }
            "ok" -> okButton.emitClicked()
            "image" -> { showImage = !showImage; render() }
        }
    }

    private fun updatePick() {
        val sampled = spec.preset == ColorRange.Preset.SAMPLED
        for (b in pickButtons) b.sensitive = sampled
    }

    /** Quelle (aktive Ebene oder sichtbares Bild) und eine verkleinerte Fassung für die Vorschau holen. */
    private fun load() {
        val all = sampleAll
        Engine.call({
            val d = session.doc
            val g = if (all) d.newGrid().also { g -> Compositor.renderBands(d, d.bounds) { r, buf -> g.writeRect(r, buf) } }
                else (d.activeLayer as? RasterLayer)?.raster ?: d.newGrid().also { g -> Compositor.renderBands(d, d.bounds) { r, buf -> g.writeRect(r, buf) } }
            var level = 0
            while ((d.width shr level) > 900 || (d.height shr level) > 700) level++
            val m = if (level > 0) g.mip(level) else g
            val w = maxOf(1, (d.width + (1 shl level) - 1) shr level); val h = maxOf(1, (d.height + (1 shl level) - 1) shr level)
            val px = IntArray(w * h).also { m.readRect(IntRect.ofSize(w, h), it) }
            Triple(g, px, w to h)
        }) { r ->
            val (g, px, size) = r.getOrNull() ?: return@call
            source = g; small = px; sw = size.first; sh = size.second
            render()
        }
    }

    private fun sampleAt(x: Double, y: Double, mode: Pick) {
        val px = small ?: return
        if (spec.preset != ColorRange.Preset.SAMPLED) { spec = spec.copy(preset = ColorRange.Preset.SAMPLED); presetBox.selected = 0 }
        val pw = picture.width.toDouble(); val ph = picture.height.toDouble()
        val k = minOf(pw / sw, ph / sh)
        val ix = ((x - (pw - sw * k) / 2) / k).toInt(); val iy = ((y - (ph - sh * k) / 2) / k).toInt()
        if (ix !in 0 until sw || iy !in 0 until sh) return
        // 3×3-Mittel wie die Photoshop-Pipette im Farbbereich
        var r = 0; var g = 0; var b = 0; var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = ix + dx; val yy = iy + dy
            if (xx !in 0 until sw || yy !in 0 until sh) continue
            val c = px[yy * sw + xx]; if (c ushr 24 == 0) continue
            r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++
        }
        if (n == 0) return
        val c = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        val s = ColorRange.Sample(c, subtract = mode == Pick.SUBTRACT)
        spec = spec.copy(samples = if (mode == Pick.SET) listOf(s) else spec.samples + s)
        render()
    }

    private fun render() {
        if (closed) return
        val px = small ?: return
        // Noch keine Farbe aufgenommen: Bild zeigen, damit man sieht, wohin man klickt
        val img = showImage || (spec.preset == ColorRange.Preset.SAMPLED && spec.samples.isEmpty())
        val out = if (img) px.copyOf() else ColorRange.preview(px, spec)
        if (img) for (i in out.indices) {
            // Bild: transparente Bereiche auf mittlerem Grau
            val c = out[i]; val a = c ushr 24
            if (a < 255) {
                fun ch(s: Int) = ((c shr s and 255) * a + 128 * (255 - a)) / 255
                out[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        val bytes = CanvasView.toBytes(out)
        picture.paintable = MemoryTexture(sw, sh, MemoryFormat.B8G8R8A8, bytes, sw * 4L).also { t ->
            System.getenv("PG_COLORRANGE_DUMP")?.let { t.saveToPng(it) }
        }
    }

    companion object {
        /** Letzte Einstellungen (wie Photoshop beim nächsten Öffnen). */
        private var last: ColorRange.Spec? = null
    }
}
