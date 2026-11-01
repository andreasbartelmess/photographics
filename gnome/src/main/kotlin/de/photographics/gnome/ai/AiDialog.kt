package de.photographics.gnome.ai

import de.photographics.core.tr
import org.gnome.adw.ActionRow
import org.gnome.adw.PreferencesDialog
import org.gnome.adw.PreferencesGroup
import org.gnome.adw.PreferencesPage
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.Orientation
import org.gnome.gtk.ProgressBar
import org.gnome.gtk.Widget

/** Modellverwaltung: KI-Modelle laden und löschen (laufen lokal, nichts verlässt den Rechner). */
object AiDialog {
    fun show(parent: Widget) {
        val d = PreferencesDialog()
        d.title = tr("KI-Modelle")
        val page = PreferencesPage()
        val updaters = ArrayList<() -> Unit>()
        for (p in AiPurpose.entries) {
            val g = PreferencesGroup()
            g.title = p.label
            if (p == AiPurpose.INPAINT) g.description = tr("Die Modelle laufen auf diesem Rechner; Bilder werden nicht hochgeladen.")
            for (m in AiModel.entries.filter { it.purpose == p }) {
                val row = ActionRow()
                row.title = m.title
                row.subtitle = "${m.description} · ${m.license} · ${"%.0f".format(m.sizeMb.coerceAtLeast(1f))} MB"
                row.subtitleLines = 2
                val suffix = Box(Orientation.HORIZONTAL, 6).apply { valign = Align.CENTER }
                row.addSuffix(suffix)
                fun refresh() {
                    var c = suffix.firstChild
                    while (c != null) { val n = c.nextSibling; suffix.remove(c); c = n }
                    when (val st = AiModelManager.status[m]) {
                        is ModelStatus.Downloading -> {
                            suffix.append(ProgressBar().apply { fraction = st.progress.toDouble(); valign = Align.CENTER; setSizeRequest(120, -1) })
                            suffix.append(Button.fromIconName("process-stop-symbolic").apply { tooltipText = tr("Abbrechen"); addCssClass("flat"); onClicked { AiModelManager.cancel(m) } })
                        }
                        ModelStatus.Ready -> suffix.append(Button.fromIconName("user-trash-symbolic").apply {
                            tooltipText = tr("Löschen"); addCssClass("flat"); onClicked { AiModelManager.delete(m) }
                        })
                        is ModelStatus.Failed -> {
                            row.subtitle = tr("Fehler: {0}", st.message)
                            suffix.append(Button.withLabel(tr("Erneut versuchen")).apply { onClicked { AiModelManager.download(m) } })
                        }
                        else -> suffix.append(Button.withLabel(tr("Laden")).apply { addCssClass("suggested-action"); onClicked { AiModelManager.download(m) } })
                    }
                }
                refresh()
                updaters += ::refresh
                g.add(row)
            }
            page.add(g)
        }
        d.add(page)
        val listener = { for (u in updaters) u() }
        AiModelManager.listeners += listener
        d.onClosed { AiModelManager.listeners -= listener }
        d.present(parent)
    }
}
