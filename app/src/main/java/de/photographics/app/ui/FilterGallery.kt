package de.photographics.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.photographics.app.EditorViewModel
import de.photographics.core.filters.Gallery
import de.photographics.core.filters.GalleryCategory
import de.photographics.core.filters.GalleryChain
import de.photographics.core.filters.GalleryContext
import de.photographics.core.filters.GalleryEffect
import de.photographics.core.filters.GallerySpec
import de.photographics.core.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Zuletzt angewendeter Stapel und zuletzt benutzte Werte je Effekt (wie Photoshop sie behält). */
private var lastStack: List<GalleryEffect>? = null
private val remembered = HashMap<String, FloatArray>()

private fun toBitmap(px: IntArray, w: Int, h: Int): ImageBitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * Filtergalerie wie in Photoshop: Vorschau, Effekte nach Gruppen mit Miniaturen aus dem eigenen
 * Bild, Regler des gewählten Effekts und der Effektstapel. Auf dem Tablet quer dreispaltig.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterGalleryDialog(vm: EditorViewModel, fg: Int, bg: Int, onDismiss: () -> Unit) {
    val effects = remember { mutableStateListOf<GalleryEffect>().apply { addAll(lastStack ?: listOf(GalleryEffect(Gallery.specs.first(), Gallery.specs.first().defaults()))) } }
    var selected by remember { mutableIntStateOf(0) }
    var full by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<Pair<EditorViewModel.GallerySource, EditorViewModel.GallerySource>?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(true) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    var category by remember { mutableStateOf(effects.getOrNull(0)?.spec?.category ?: GalleryCategory.ARTISTIC) }

    LaunchedEffect(Unit) { sources = vm.gallerySources() ?: run { onDismiss(); return@LaunchedEffect } }
    // Miniaturen aller Effekte aus einem verkleinerten Ausschnitt des eigenen Bildes
    LaunchedEffect(sources) {
        val f = sources?.first ?: return@LaunchedEffect
        val tw = 200; val th = 136
        val step = maxOf(1, minOf(f.w / tw, f.h / th))
        val sw = minOf(f.w / step, tw); val sh = minOf(f.h / step, th)
        val x0 = (f.w - sw * step) / 2; val y0 = (f.h - sh * step) / 2
        val small = IntArray(sw * sh) { i -> f.px[(y0 + (i / sw) * step) * f.w + x0 + (i % sw) * step] }
        for (s in Gallery.specs) {
            val bmp = withContext(Dispatchers.Default) {
                runCatching { GalleryChain(listOf(GalleryEffect(s, s.defaults())), GalleryContext(fg, bg, f.scale / step)).process(small.copyOf(), sw, sh, 0, 0, sw, sh) }.getOrNull()
                    ?.let { toBitmap(it, sw, sh) }
            }
            if (bmp != null) thumbs[s.id] = bmp
        }
    }
    // Vorschau: bei jeder Änderung neu (kurz entprellt; neue Änderungen brechen die alte Rechnung ab)
    val key = effects.toList()
    LaunchedEffect(key, full, sources) {
        val src = (if (full) sources?.second else sources?.first) ?: return@LaunchedEffect
        busy = true
        delay(60)
        effects.getOrNull(selected)?.let { remembered[it.spec.id] = it.values }
        val out = withContext(Dispatchers.Default) {
            GalleryChain(key, GalleryContext(fg, bg, src.scale)).process(src.px.copyOf(), src.w, src.h, src.ox, src.oy, src.w, src.h)
        }
        preview = toBitmap(out, src.w, src.h)
        busy = false
    }

    fun pick(s: GallerySpec) {
        val cur = effects[selected]
        if (cur.spec !== s) effects[selected] = GalleryEffect(s, remembered[s.id] ?: s.defaults(), cur.visible)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding()) {
                TopAppBar(
                    title = { Text(tr("Filtergalerie")) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, tr("Abbrechen")) } },
                    actions = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(!full, { full = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(tr("Ganzes Bild")) }
                            SegmentedButton(full, { full = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(tr("100 %")) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { lastStack = effects.toList(); vm.applyGallery(effects.toList()); onDismiss() }, modifier = Modifier.padding(end = 12.dp)) { Text(tr("Anwenden")) }
                    },
                )
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 840.dp
                    val previewBox: @Composable (Modifier) -> Unit = { m ->
                        Box(m.background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(16.dp), contentAlignment = Alignment.Center) {
                            preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        }
                    }
                    val catalog: @Composable (Modifier) -> Unit = { m ->
                        Column(m) {
                            PrimaryScrollableTabRow(selectedTabIndex = category.ordinal, edgePadding = 12.dp) {
                                for (c in GalleryCategory.entries) Tab(category == c, { category = c }, text = { Text(c.label) })
                            }
                            val specs = Gallery.specs.filter { it.category == category }
                            if (wide) LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(specs, key = { it.id }) { s -> GalleryThumb(s, thumbs[s.id], effects.getOrNull(selected)?.spec === s) { pick(s) } }
                            } else LazyRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(specs, key = { it.id }) { s -> Box(Modifier.width(120.dp)) { GalleryThumb(s, thumbs[s.id], effects.getOrNull(selected)?.spec === s) { pick(s) } } }
                            }
                        }
                    }
                    val controls: @Composable (Modifier) -> Unit = { m ->
                        Column(m.padding(16.dp)) {
                            val e = effects.getOrNull(selected)
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (e != null) {
                                    Text(e.spec.label, style = MaterialTheme.typography.titleMedium)
                                    if (e.spec.usesColors) Text(tr("Verwendet Vorder- und Hintergrundfarbe"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    for ((i, p) in e.spec.params.withIndex()) {
                                        fun set(v: Float) { effects[selected] = e.copy(values = e.values.copyOf().also { it[i] = v }) }
                                        val opts = p.options
                                        Text(p.label + if (opts == null) ": ${e.values[i].roundToInt()}" else "", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                                        if (opts != null) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            itemsIndexed(opts) { k, o ->
                                                androidx.compose.material3.FilterChip(e.values[i].roundToInt() == k, { set(k.toFloat()) }, label = { Text(o) })
                                            }
                                        } else Slider(e.values[i], { set(it.roundToInt().toFloat()) }, valueRange = p.min..p.max)
                                    }
                                    TextButton(onClick = { effects[selected] = e.copy(values = e.spec.defaults()) }) { Text(tr("Standardwerte")) }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Effektebenen"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { effects.add(selected + 1, effects[selected].copy()); selected++ }) { Icon(Icons.Filled.Add, tr("Neue Effektebene")) }
                                IconButton(onClick = { if (effects.size > 1) { effects.removeAt(selected); selected = selected.coerceAtMost(effects.size - 1) } }, enabled = effects.size > 1) {
                                    Icon(Icons.Filled.Delete, tr("Effektebene löschen"))
                                }
                            }
                            LazyColumn(Modifier.height(if (wide) 200.dp else 120.dp)) {
                                itemsIndexed(effects) { i, fx ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .background(if (i == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                                            .clickable { selected = i; category = fx.spec.category }.padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        IconButton(onClick = { effects[i] = fx.copy(visible = !fx.visible) }) {
                                            Icon(if (fx.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, tr("Effekt ein-/ausblenden"))
                                        }
                                        Text(fx.spec.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    if (wide) Row(Modifier.fillMaxSize()) {
                        previewBox(Modifier.weight(1f).fillMaxHeight())
                        catalog(Modifier.width(380.dp).fillMaxHeight())
                        controls(Modifier.width(320.dp).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize()) {
                        previewBox(Modifier.fillMaxWidth().weight(1f))
                        catalog(Modifier.fillMaxWidth().height(200.dp))
                        controls(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryThumb(s: GallerySpec, image: ImageBitmap?, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .then(if (active) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier),
        ) { image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
        Text(s.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
