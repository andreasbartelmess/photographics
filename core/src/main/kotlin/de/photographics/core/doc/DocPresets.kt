package de.photographics.core.doc

import de.photographics.core.tr

/** Vorgabe für ein neues Dokument; [print]: Größe in cm anzeigen. */
data class DocPreset(val label: String, val w: Int, val h: Int, val dpi: Float = 72f, val print: Boolean = false) {
    /** „6000 × 4000 px“ bzw. bei Druckformaten „21 × 29,7 cm“. */
    val sizeLabel: String get() = if (print) tr("{0} × {1} cm", cm(w, dpi), cm(h, dpi)) else tr("{0} × {1} px", w, h)

    private fun cm(px: Int, dpi: Float): String {
        val v = Math.round(px / dpi * 25.4f) / 10f
        return if (v % 1f == 0f) v.toInt().toString() else v.toString().replace('.', ',')
    }
}

/** Vorgaben nach Einsatzzweck, wie im „Neues Dokument“-Dialog von Photoshop (Startbildschirm beider Versionen). */
object DocPresets {
    val groups: List<Pair<String, List<DocPreset>>> by lazy {
        listOf(
            tr("Foto") to listOf(
                DocPreset(tr("Kleinbild 3:2 · 24 MP"), 6000, 4000, 300f),
                DocPreset(tr("Kleinbild 3:2 · 45 MP"), 8256, 5504, 300f),
                DocPreset(tr("Mittelformat 4:3"), 8256, 6192, 300f),
                DocPreset(tr("Micro Four Thirds 4:3"), 5184, 3888, 300f),
                DocPreset(tr("Smartphone 4:3"), 4032, 3024, 300f),
                DocPreset(tr("Großformat 5:4"), 5000, 4000, 300f),
                DocPreset(tr("Quadrat 1:1"), 4000, 4000, 300f),
                DocPreset(tr("Panorama 3:1"), 9000, 3000, 300f),
            ),
            tr("Druck · 300 dpi") to listOf(
                DocPreset("A5", 1748, 2480, 300f, print = true),
                DocPreset("A4", 2480, 3508, 300f, print = true),
                DocPreset("A3", 3508, 4961, 300f, print = true),
                DocPreset(tr("Postkarte 10 × 15"), 1181, 1772, 300f, print = true),
                DocPreset(tr("Abzug 13 × 18"), 1535, 2126, 300f, print = true),
                DocPreset(tr("Abzug 20 × 30"), 2362, 3543, 300f, print = true),
                DocPreset(tr("Poster 30 × 45"), 3543, 5315, 300f, print = true),
                DocPreset(tr("US Letter"), 2550, 3300, 300f, print = true),
            ),
            tr("Bildschirm & Video") to listOf(
                DocPreset(tr("Full HD"), 1920, 1080),
                DocPreset("4K UHD", 3840, 2160),
                DocPreset("DCI 4K", 4096, 2160),
                DocPreset("5K", 5120, 2880),
                DocPreset("QHD", 2560, 1440),
                DocPreset(tr("Tablet 16:10"), 2560, 1600),
                DocPreset(tr("Smartphone 20:9"), 1080, 2400),
            ),
            tr("Social Media") to listOf(
                DocPreset(tr("Hochformat 4:5"), 1080, 1350),
                DocPreset(tr("Quadrat"), 1080, 1080),
                DocPreset(tr("Story / Reel 9:16"), 1080, 1920),
                DocPreset(tr("Video-Vorschau 16:9"), 1280, 720),
                DocPreset(tr("Pin 2:3"), 1000, 1500),
                DocPreset(tr("Profil-Banner 4:1"), 1584, 396),
            ),
        )
    }
}
