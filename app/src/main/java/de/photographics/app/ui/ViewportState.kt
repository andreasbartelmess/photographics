package de.photographics.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/**
 * Zoom und Position der Arbeitsfläche. Wird von Gesten, Mausrad und Tastaturkürzeln gemeinsam
 * benutzt. Bildschirm = Dokument * scale + offset.
 */
@Stable
class ViewportState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var _viewSize by mutableStateOf(IntSize.Zero)

    /** Größe der Fläche; bei Änderung (Drehen, Panels) neu einpassen bzw. Bildmitte halten. */
    var viewSize: IntSize
        get() = _viewSize
        set(v) {
            if (v == _viewSize) return
            val old = _viewSize
            _viewSize = v
            if (fitted || old == IntSize.Zero) fit()
            else offset += Offset((v.width - old.width) / 2f, (v.height - old.height) / 2f)
        }

    /** true, solange der Nutzer seit dem letzten Einpassen nicht gezoomt/verschoben hat. */
    private var fitted = true
    /** Von schwebenden Leisten verdeckter Bereich oben (px), wird beim Einpassen freigelassen. */
    var topInset = 0f
    var docWidth = 0
        private set
    var docHeight = 0
        private set

    fun toDoc(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)
    fun toScreen(p: Offset) = Offset(p.x * scale + offset.x, p.y * scale + offset.y)

    private val center get() = Offset(viewSize.width / 2f, viewSize.height / 2f)

    fun setDocument(w: Int, h: Int) {
        val changed = w != docWidth || h != docHeight
        docWidth = w; docHeight = h
        if (changed) fit()
    }

    fun fit() {
        if (viewSize.width == 0 || docWidth == 0) return
        if (viewSize.height <= 0) return
        // Bei sehr niedriger Fläche die verdeckte Zone ignorieren statt negativ zu rechnen
        val availH = (viewSize.height - topInset).coerceAtLeast(viewSize.height * 0.5f)
        val s = min(viewSize.width * 0.92f / docWidth, availH * 0.92f / docHeight)
        scale = s
        offset = Offset((viewSize.width - docWidth * s) / 2f, topInset + (availH - docHeight * s) / 2f)
        fitted = true
    }

    /** Zoomt um [factor] um den Bildschirmpunkt [pivot] und verschiebt zusätzlich um [pan]. */
    fun transform(factor: Float, pivot: Offset = center, pan: Offset = Offset.Zero) {
        fitted = false
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val k = newScale / scale
        offset = Offset(pivot.x - (pivot.x - offset.x) * k + pan.x, pivot.y - (pivot.y - offset.y) * k + pan.y)
        scale = newScale
    }

    fun pan(delta: Offset) { offset += delta; fitted = false }

    fun zoomTo(target: Float, pivot: Offset = center) = transform(target / scale, pivot)

    companion object {
        const val MIN_SCALE = 0.02f
        const val MAX_SCALE = 64f
    }
}
