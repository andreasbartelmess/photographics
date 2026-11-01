package de.photographics.gnome

import de.photographics.core.color.IccProfiles
import de.photographics.core.color.ProofCondition
import de.photographics.core.tiff.TiffWriter
import de.photographics.core.tr
import org.gnome.adw.ComboRow
import org.gnome.adw.PreferencesDialog
import org.gnome.adw.PreferencesGroup
import org.gnome.adw.PreferencesPage
import org.gnome.adw.SwitchRow
import org.gnome.gtk.StringList
import org.gnome.gtk.Widget

/** Einstellungen (Sprache, Farbmanagement, Dateien, Farbproof). */
object SettingsDialog {
    fun show(parent: Widget, changed: () -> Unit) {
        val d = PreferencesDialog()
        d.title = tr("Einstellungen")
        val page = PreferencesPage()

        fun <E> combo(title: String, subtitle: String?, items: List<E>, label: (E) -> String, current: E, set: (E) -> Unit) = ComboRow().apply {
            this.title = title
            if (subtitle != null) this.subtitle = subtitle
            model = StringList(items.map(label).toTypedArray())
            selected = items.indexOf(current).coerceAtLeast(0)
            onNotify("selected") { _ -> items.getOrNull(selected)?.let { set(it); changed() } }
        }
        fun switch(title: String, subtitle: String?, current: Boolean, set: (Boolean) -> Unit) = SwitchRow().apply {
            this.title = title
            if (subtitle != null) this.subtitle = subtitle
            active = current
            onNotify("active") { _ -> set(active); changed() }
        }

        page.add(PreferencesGroup().apply {
            title = tr("Allgemein")
            add(combo(tr("Sprache"), tr("Gilt ab dem nächsten Start"), Settings.Language.entries,
                { when (it) { Settings.Language.SYSTEM -> tr("Systemsprache"); Settings.Language.DE -> "Deutsch"; Settings.Language.EN -> "English" } },
                Settings.language) { Settings.language = it })
        })
        page.add(PreferencesGroup().apply {
            title = tr("Farbmanagement")
            add(combo(tr("Arbeitsfarbraum"), tr("Für CMYK-, Graustufen- und Lab-Dateien beim Öffnen"), IccProfiles.Builtin.entries, { it.label }, Settings.workingRgb) { Settings.workingRgb = it })
            add(switch(tr("Beim Export in sRGB umwandeln"), tr("Sonst wird das Dokumentprofil in PNG/JPEG eingebettet"), Settings.exportToSrgb) { Settings.exportToSrgb = it })
        })
        page.add(PreferencesGroup().apply {
            title = tr("Farbproof")
            add(combo(tr("Bedingung"), null, ProofCondition.entries, { it.label }, Settings.proofCondition) { Settings.proofCondition = it })
            add(switch(tr("Papierfarbe simulieren"), null, Settings.proofSimulatePaper) { Settings.proofSimulatePaper = it })
        })
        page.add(PreferencesGroup().apply {
            title = tr("Dateien")
            add(combo(tr("TIFF-Kompression"), null, TiffWriter.Compression.entries, { it.label }, Settings.tiffCompression) { Settings.tiffCompression = it })
        })
        d.add(page)
        d.present(parent)
    }
}
