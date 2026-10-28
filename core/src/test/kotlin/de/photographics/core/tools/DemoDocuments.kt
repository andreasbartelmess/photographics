package de.photographics.core.tools

import de.photographics.core.adjust.BlackWhiteAdj
import de.photographics.core.adjust.ColorLookupAdj
import de.photographics.core.adjust.CurvesAdj
import de.photographics.core.adjust.HueSaturationAdj
import de.photographics.core.color.Films
import de.photographics.core.doc.AdjustmentLayer
import de.photographics.core.doc.Document
import de.photographics.core.doc.GroupLayer
import de.photographics.core.doc.LayerMask
import de.photographics.core.doc.PixelLayer
import de.photographics.core.image.TileGrid
import de.photographics.core.psd.PsdWriter
import de.photographics.core.render.BlendMode
import de.photographics.core.render.Compositor
import org.junit.Assume
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Beispieldokumente mit mehreren Ebenen für Screenshots (Website, README):
 * `PG_DEMO_IN=<Ordner mit JPEGs> PG_DEMO_OUT=<Ziel> ./gradlew :core:test --tests "*DemoDocuments*"`.
 * Legt zu jedem Dokument das zusammengesetzte Bild als `<Name>-fertig.jpg` ab.
 * Erwartet dolomiten.jpg, studio.jpg und neon.jpg (Fotos von Unsplash, siehe website/README.md).
 */
class DemoDocuments {
    private fun load(f: File): Pair<Pair<Int, Int>, IntArray> {
        val img = ImageIO.read(f)
        val w = img.width; val h = img.height
        return (w to h) to img.getRGB(0, 0, w, h, null, 0, w)
    }

    private fun grid(w: Int, h: Int, f: (Int, Int) -> Int) = TileGrid.fromPixels(w, h, IntArray(w * h) { i -> f(i % w, i / w) })

    /** Graustufen-Maske (0..255) als Kachelraster. */
    private fun mask(w: Int, h: Int, f: (Int, Int) -> Int) = LayerMask(TileGrid.fromPixels(w, h, IntArray(w * h) { i -> f(i % w, i / w).coerceIn(0, 255) }), enabled = true)

    /** Zusammengesetztes Bild, wie photographics es rendert (für die Website: „das fertige Bild“). */
    private fun finished(doc: Document, f: File) {
        val px = Compositor.renderArgb(doc, doc.bounds)
        val img = java.awt.image.BufferedImage(doc.width, doc.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, doc.width, doc.height, px, 0, doc.width)
        ImageIO.write(img, "jpg", f)
    }

    private fun film(prefix: String) = Films.all.first { it.first.first.startsWith(prefix) }.first.first

    @Test
    fun write() {
        val inDir = System.getenv("PG_DEMO_IN"); val outDir = System.getenv("PG_DEMO_OUT")
        Assume.assumeTrue(inDir != null && outDir != null)
        val out = File(outDir!!).apply { mkdirs() }

        // Dolomiten: Himmel über eine Verlaufsmaske abdunkeln, Film-Look, Retusche in einer Gruppe
        run {
            val (size, px) = load(File(inDir, "dolomiten.jpg")); val (w, h) = size
            val doc = Document(w, h)
            doc.root.add(PixelLayer("Hintergrund", TileGrid.fromPixels(w, h, px)))
            val retusche = GroupLayer("Retusche")
            retusche.add(PixelLayer("Leitung", grid(w, h) { _, _ -> 0 }))
            retusche.add(PixelLayer("Wege", grid(w, h) { _, _ -> 0 }).also { it.opacity = 0.8f })
            doc.root.add(retusche)
            doc.root.add(AdjustmentLayer("Himmel", CurvesAdj(master = listOf(0 to 0, 120 to 92, 200 to 178, 255 to 245))).also {
                it.mask = mask(w, h) { _, y -> 255 - y * 255 * 2 / h }
            })
            doc.root.add(AdjustmentLayer("Grüntöne", HueSaturationAdj(hue = -4, saturation = -18, lightness = 0)).also { it.opacity = 0.7f })
            val look = AdjustmentLayer("Film-Look", ColorLookupAdj(film("Farbnegativ 100"))).also { it.opacity = 0.65f }
            doc.root.add(look)
            doc.activeLayer = doc.root.children[2]
            File(out, "Dolomiten.psd").outputStream().buffered().use { PsdWriter.write(doc, it, File(out, "tmp")) }
            finished(doc, File(out, "Dolomiten-fertig.jpg"))
        }

        // Studio-Porträt: Dodge & Burn (50 % Grau, Weiches Licht), Schwarzweiß als Variante ausgeblendet
        run {
            val (size, px) = load(File(inDir, "studio.jpg")); val (w, h) = size
            val doc = Document(w, h)
            doc.root.add(PixelLayer("Hintergrund", TileGrid.fromPixels(w, h, px)))
            doc.root.add(PixelLayer("Retusche", grid(w, h) { _, _ -> 0 }))
            val db = PixelLayer("Dodge & Burn", grid(w, h) { _, _ -> 0xFF808080.toInt() }).also { it.blendMode = BlendMode.SOFT_LIGHT }
            doc.root.add(db)
            doc.root.add(AdjustmentLayer("Kontrast", CurvesAdj(master = listOf(0 to 6, 64 to 56, 192 to 204, 255 to 255))))
            doc.root.add(AdjustmentLayer("Schwarzweiß", BlackWhiteAdj()).also { it.visible = false })
            doc.activeLayer = db
            File(out, "Studio.psd").outputStream().buffered().use { PsdWriter.write(doc, it, File(out, "tmp")) }
            finished(doc, File(out, "Studio-fertig.jpg"))
        }

        // Osaka bei Nacht: Farbtabelle aus den eingebauten Filmen
        run {
            val (size, px) = load(File(inDir, "neon.jpg")); val (w, h) = size
            val doc = Document(w, h)
            doc.root.add(PixelLayer("Hintergrund", TileGrid.fromPixels(w, h, px)))
            doc.root.add(AdjustmentLayer("Tiefen", CurvesAdj(master = listOf(0 to 10, 60 to 64, 255 to 255))))
            val look = AdjustmentLayer("Kinokopie", ColorLookupAdj(film("Kinokopie")))
            doc.root.add(look)
            doc.activeLayer = look
            File(out, "Osaka.psd").outputStream().buffered().use { PsdWriter.write(doc, it, File(out, "tmp")) }
            finished(doc, File(out, "Osaka-fertig.jpg"))
        }
        File(out, "tmp").deleteRecursively()
        println("Beispieldokumente: ${out.listFiles()!!.joinToString { it.name + " " + it.length() / 1024 + " KB" }}")
    }
}
