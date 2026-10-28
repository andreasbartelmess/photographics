package de.photographics.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.photographics.app.IccEntry
import de.photographics.core.color.IccProfiles
import de.photographics.core.color.RenderingIntent

/** Zielprofil: eingebautes Profil oder RGB-Profil aus der Bibliothek (Dateiname). */
data class ProfileChoice(val builtin: IccProfiles.Builtin?, val library: String?)

/**
 * „Profil zuweisen …“ ([convert] = false: Pixelwerte bleiben, die Farben ändern ihre Bedeutung) bzw.
 * „In Profil umwandeln …“ ([convert] = true: Farben bleiben, Pixelwerte werden umgerechnet).
 */
@Composable
fun ProfileDialog(
    convert: Boolean,
    currentProfile: String,
    library: List<IccEntry>,
    defaultIntent: RenderingIntent,
    defaultBpc: Boolean,
    onConfirm: (ProfileChoice, RenderingIntent, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val rgbLibrary = library.filter { it.isRgb }
    var choice by remember { mutableStateOf(ProfileChoice(IccProfiles.Builtin.entries.firstOrNull { it.label != currentProfile } ?: IccProfiles.Builtin.SRGB, null)) }
    var intent by remember { mutableStateOf(defaultIntent) }
    var bpc by remember { mutableStateOf(defaultBpc) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (convert) "In Profil umwandeln" else "Profil zuweisen") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("Aktuell: $currentProfile", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (convert) "Die Farben bleiben erhalten, die Pixelwerte werden in das Zielprofil umgerechnet."
                    else "Die Pixelwerte bleiben unverändert – die Farben werden im neuen Profil anders dargestellt.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                Text("Zielprofil", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                for (b in IccProfiles.Builtin.entries) {
                    ChoiceRow(b.label, b.note, choice.builtin == b) { choice = ProfileChoice(b, null) }
                }
                for (e in rgbLibrary) {
                    ChoiceRow(e.description, e.fileName, choice.library == e.fileName) { choice = ProfileChoice(null, e.fileName) }
                }
                if (convert) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Priorität", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    for (i in RenderingIntent.entries) ChoiceRow(i.label, null, intent == i) { intent = i }
                    Row(
                        Modifier.fillMaxWidth().selectable(bpc, role = Role.Checkbox) { bpc = !bpc },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(bpc, null)
                        Text("Tiefenkompensierung verwenden", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice, intent, bpc) }) { Text(if (convert) "Umwandeln" else "Zuweisen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
