package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.TileGrid
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import org.junit.Assert.assertEquals
import org.junit.Test

class BlendTest {
    private fun composite(bottom: Int, top: Int, mode: BlendMode, opacity: Float = 1f): Int {
        val doc = Document.blank(1, 1, background = bottom)
        doc.root.add(PixelLayer("t", TileGrid(1, 1).also { it[0, 0] = top }).apply { blendMode = mode; this.opacity = opacity })
        return Compositor.sample(doc, 0, 0)
    }

    @Test fun normal() = assertEquals(0xFFFF0000.toInt(), composite(0xFF00FF00.toInt(), 0xFFFF0000.toInt(), BlendMode.NORMAL))
    @Test fun multiplyWithWhiteIsIdentity() = assertEquals(0xFF336699.toInt(), composite(0xFF336699.toInt(), -1, BlendMode.MULTIPLY))
    @Test fun screenWithBlackIsIdentity() = assertEquals(0xFF336699.toInt(), composite(0xFF336699.toInt(), 0xFF000000.toInt(), BlendMode.SCREEN))
    @Test fun differenceWithSelfIsBlack() = assertEquals(0xFF000000.toInt(), composite(0xFF336699.toInt(), 0xFF336699.toInt(), BlendMode.DIFFERENCE))
    @Test fun halfOpacity() = assertEquals(0xFF808080.toInt(), composite(0xFF000000.toInt(), -1, BlendMode.NORMAL, 0.5f))
}
