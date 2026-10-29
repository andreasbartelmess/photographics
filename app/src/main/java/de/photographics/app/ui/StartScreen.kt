package de.photographics.app.ui

import de.photographics.core.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.photographics.app.RecentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Dokumentvorgabe; [print]: Größe in cm anzeigen. */
private data class Preset(val label: String, val w: Int, val h: Int, val dpi: Float = 72f, val print: Boolean = false)

/** Vorgaben nach Einsatzzweck, wie im „Neues Dokument“-Dialog von Photoshop. */
private val PRESET_GROUPS = listOf(
    tr("Foto") to listOf(
        Preset(tr("Kleinbild 3:2 · 24 MP"), 6000, 4000, 300f),
        Preset(tr("Kleinbild 3:2 · 45 MP"), 8256, 5504, 300f),
        Preset(tr("Mittelformat 4:3"), 8256, 6192, 300f),
        Preset(tr("Micro Four Thirds 4:3"), 5184, 3888, 300f),
        Preset(tr("Smartphone 4:3"), 4032, 3024, 300f),
        Preset(tr("Großformat 5:4"), 5000, 4000, 300f),
        Preset(tr("Quadrat 1:1"), 4000, 4000, 300f),
        Preset(tr("Panorama 3:1"), 9000, 3000, 300f),
    ),
    tr("Druck · 300 dpi") to listOf(
        Preset("A5", 1748, 2480, 300f, print = true),
        Preset("A4", 2480, 3508, 300f, print = true),
        Preset("A3", 3508, 4961, 300f, print = true),
        Preset(tr("Postkarte 10 × 15"), 1181, 1772, 300f, print = true),
        Preset(tr("Abzug 13 × 18"), 1535, 2126, 300f, print = true),
        Preset(tr("Abzug 20 × 30"), 2362, 3543, 300f, print = true),
        Preset(tr("Poster 30 × 45"), 3543, 5315, 300f, print = true),
        Preset(tr("US Letter"), 2550, 3300, 300f, print = true),
    ),
    tr("Bildschirm & Video") to listOf(
        Preset(tr("Full HD"), 1920, 1080),
        Preset("4K UHD", 3840, 2160),
        Preset("DCI 4K", 4096, 2160),
        Preset("5K", 5120, 2880),
        Preset("QHD", 2560, 1440),
        Preset(tr("Tablet 16:10"), 2560, 1600),
        Preset(tr("Smartphone 20:9"), 1080, 2400),
    ),
    tr("Social Media") to listOf(
        Preset(tr("Hochformat 4:5"), 1080, 1350),
        Preset(tr("Quadrat"), 1080, 1080),
        Preset(tr("Story / Reel 9:16"), 1080, 1920),
        Preset(tr("Video-Vorschau 16:9"), 1280, 720),
        Preset(tr("Pin 2:3"), 1000, 1500),
        Preset(tr("Profil-Banner 4:1"), 1584, 396),
    ),
)

/** Startbildschirm: Neu aus Vorlage, Öffnen, Galerie, zuletzt verwendete Dateien. */
@Composable
fun StartScreen(
    recent: List<RecentFiles.Entry>,
    hasDocument: Boolean,
    loadThumb: (RecentFiles.Entry) -> android.graphics.Bitmap?,
    onNew: (Int, Int, Float) -> Unit,
    onCustomNew: () -> Unit,
    onOpen: () -> Unit,
    onGallery: () -> Unit,
    onOpenRecent: (RecentFiles.Entry) -> Unit,
    onRemoveRecent: (RecentFiles.Entry) -> Unit,
    onContinue: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(200.dp),
            modifier = Modifier.safeDrawingPadding().fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    AppLogo(56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Wordmark(MaterialTheme.typography.headlineMedium)
                        Text(tr("Ebenenbasierte Bildbearbeitung für professionelle Workflows unterwegs"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onOpen) { Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(tr("Öffnen")) }
                    FilledTonalButton(onClick = onGallery) { Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text(tr("Aus Galerie")) }
                    if (hasDocument) OutlinedButton(onClick = onContinue) {
                        Text(tr("Weiter bearbeiten")); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(tr("Neues Dokument"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onCustomNew) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("Eigene Größe …")) }
                }
            }
            // Je Gruppe eine Reihe gleich großer Karten (seitlich scrollbar): keine halb gefüllten Zeilen
            for ((group, presets) in PRESET_GROUPS) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "gruppe-$group") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems(presets) { p -> PresetCard(p) { onNew(p.w, p.h, p.dpi) } }
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(tr("Zuletzt verwendet")) }
                items(recent, key = { it.uri.toString() }) { e -> RecentCard(e, loadThumb, { onOpenRecent(e) }, { onRemoveRecent(e) }) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun PresetCard(p: Preset, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.width(176.dp)) {
        Column(Modifier.padding(14.dp)) {
            // Seitenverhältnis in einen festen Rahmen eingepasst: alle Karten gleich hoch, Vorschau mittig
            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                val ratio = p.w.toFloat() / p.h
                val maxW = 96f; val maxH = 56f
                val (bw, bh) = if (ratio >= maxW / maxH) maxW to maxW / ratio else maxH * ratio to maxH
                Box(
                    Modifier.size(bw.dp, bh.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(3.dp))
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(p.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (p.print) tr("{0} × {1} cm", cm(p.w, p.dpi), cm(p.h, p.dpi)) else tr("{0} × {1} px", p.w, p.h),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}

/** Pixel → cm bei [dpi], auf eine Nachkommastelle („29,7“, „21“). */
private fun cm(px: Int, dpi: Float): String {
    val v = Math.round(px / dpi * 25.4f) / 10f
    return if (v % 1f == 0f) v.toInt().toString() else v.toString().replace('.', ',')
}

@Composable
private fun RecentCard(e: RecentFiles.Entry, loadThumb: (RecentFiles.Entry) -> android.graphics.Bitmap?, onClick: () -> Unit, onRemove: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, e.uri) { value = withContext(Dispatchers.IO) { loadThumb(e)?.asImageBitmap() } }
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Filled.Close, tr("Aus der Liste entfernen"), tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)).padding(4.dp))
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
