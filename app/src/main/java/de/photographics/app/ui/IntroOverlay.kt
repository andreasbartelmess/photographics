package de.photographics.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private class IntroPage(val icon: ImageVector?, val title: String, val text: String, val points: List<Pair<ImageVector, String>> = emptyList())

private val PAGES = listOf(
    IntroPage(
        null, "Willkommen bei photographics",
        "Ebenenbasierte Bildbearbeitung für professionelle Workflows unterwegs. Ein kurzer Überblick über das Wichtigste.",
    ),
    IntroPage(
        Icons.Filled.Draw, "Stift und Finger",
        "Gemacht fürs Tablet mit Stift – Tastatur und Maus funktionieren genauso.",
        listOf(
            Icons.Filled.Brush to "Der Stift malt, Finger zoomen und verschieben. Der Stift-Modus schaltet sich beim ersten Stiftkontakt ein.",
            Icons.Filled.Undo to "Mit zwei Fingern kurz tippen macht rückgängig, mit drei Fingern wiederholt.",
            Icons.Filled.Gesture to "Radier-Ende radiert, die Seitentaste nimmt Farbe auf. Größe und Deckkraft regelst du am Bildrand mit dem Daumen.",
        ),
    ),
    IntroPage(
        Icons.Filled.Layers, "Ebenen wie gewohnt",
        "Alles bleibt veränderbar, bis du es anders willst.",
        listOf(
            Icons.Filled.Layers to "Pixel-, Text- und Smartobjekt-Ebenen, Gruppen, Masken, Schnittmasken und alle 27 Mischmodi.",
            Icons.Filled.Tune to "Korrekturen als Einstellungsebenen, Farbtabellen und Film-Looks – nicht-destruktiv.",
            Icons.Filled.AutoFixHigh to "Retusche mit Bereichsreparatur, Reparatur-Pinsel, Kopierstempel und Ausbessern.",
        ),
    ),
    IntroPage(
        Icons.Filled.SwapHoriz, "Zwischen Schreibtisch und unterwegs",
        "Deine Dateien kommen so zurück, wie sie gegangen sind.",
        listOf(
            Icons.Filled.Folder to "PSD, PSB, TIFF und GIMP-Dateien öffnen und mit allen Ebenen wieder speichern.",
            Icons.Filled.ColorLens to "Farbprofile bleiben erhalten; Umwandeln, Zuweisen und eigene ICC-Profile.",
            Icons.Filled.Print to "Farbproof für Druckerei und Fotodrucker, mit Farbumfang-Warnung.",
        ),
    ),
    IntroPage(
        Icons.Filled.AutoAwesome, "KI auf dem Gerät – wenn du willst",
        "Optional und komplett lokal: Deine Bilder verlassen das Tablet nicht.",
        listOf(
            Icons.Filled.AutoAwesome to "Objekte entfernen und Auswahlen füllen.",
            Icons.Filled.PhotoSizeSelectLarge to "Bilder hochskalieren.",
            Icons.Filled.Lock to "Die Modelle werden erst geladen, wenn du sie zum ersten Mal brauchst.",
        ),
    ),
)

/** Einführung beim ersten Start (und über das Menü): wischbare Seiten, jederzeit überspringbar. */
@Composable
fun IntroOverlay(onDone: () -> Unit) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onDone)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            // Berührungen nicht an die Oberfläche darunter durchlassen
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        val compact = maxWidth < 600.dp
        Surface(
            modifier = if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = 560.dp).height(minOf(maxHeight - 48.dp, 620.dp)),
            shape = if (compact) RoundedCornerShape(0.dp) else RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i -> Page(PAGES[i]) }
                val last = pager.currentPage == PAGES.lastIndex
                // Punkte genau in der Mitte, Knöpfe an den Rändern
                Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    if (!last) TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterStart)) { Text("Überspringen") }
                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.Center) {
                        repeat(PAGES.size) { k ->
                            val on = k == pager.currentPage
                            Box(
                                Modifier.padding(horizontal = 4.dp).size(if (on) 10.dp else 8.dp)
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            )
                        }
                    }
                    Button(onClick = {
                        if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }, modifier = Modifier.align(Alignment.CenterEnd)) { Text(if (last) "Los geht’s" else "Weiter") }
                }
            }
        }
    }
}

@Composable
private fun Page(p: IntroPage) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (p.icon == null) AppLogo(96.dp)
        else Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(p.icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(24.dp))
        Text(p.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(p.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (p.points.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((icon, text) in p.points) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
