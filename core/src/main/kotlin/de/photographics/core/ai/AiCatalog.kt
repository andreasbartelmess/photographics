package de.photographics.core.ai

import de.photographics.core.tr

enum class AiPurpose(val label: String) { INPAINT(tr("Objekte entfernen")), UPSCALE(tr("Hochskalieren")) }

/**
 * Frei lizenzierte Modelle, die bei Bedarf heruntergeladen werden (nicht im Programm enthalten).
 * Gemeinsamer Katalog für Android und Linux; Laden und Ausführen übernimmt die jeweilige Plattform.
 *
 * [maxSide]: längste Seite, in der ein Modell mit freier Eingangsgröße rechnet (größere Ausschnitte
 * werden verkleinert); [mobileMaxSide] dasselbe für Telefone und Tablets. Modelle mit fester
 * Eingangsgröße (LaMa 512²) ignorieren beides.
 */
enum class AiModel(
    val id: String,
    val title: String,
    val purpose: AiPurpose,
    val url: String,
    val sizeMb: Float,
    val license: String,
    val zipped: Boolean,
    val description: String,
    val maxSide: Int = 0,
    val mobileMaxSide: Int = maxSide,
) {
    MIGAN(
        "migan", "MI-GAN", AiPurpose.INPAINT,
        "https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx",
        28f, tr("MIT (Picsart AI Research)"), false, tr("Schnell, gut für kleine bis mittlere Objekte."),
        maxSide = 2048, mobileMaxSide = 2048,
    ),
    LAMA(
        "lama", "LaMa", AiPurpose.INPAINT,
        // 8-Bit-Gewichte (OpenCV Zoo): weniger als halb so groß wie fp32, Ergebnis praktisch gleich
        "https://huggingface.co/opencv/inpainting_lama/resolve/main/inpainting_lama_2025jan.onnx",
        93f, tr("Apache 2.0 (Samsung AI, OpenCV)"), false, tr("Gute Qualität für große Flächen, rechnet intern mit 512 × 512 Pixeln."),
    ),
    LAMA_HD(
        "lama-hd", "LaMa HD", AiPurpose.INPAINT,
        // Big-LaMa mit freier Eingangsgröße, exportiert mit tools/models/export_lama_hd.py
        "https://github.com/andreasbartelmess/photographics-models/releases/download/lama-hd-1/lama-hd.onnx",
        104f, tr("Apache 2.0 (Samsung AI)"), false,
        tr("Beste Qualität: rechnet in voller Auflösung bis 1024 Pixel und erhält Strukturen und Schärfe. Langsamer."),
        maxSide = 1024, mobileMaxSide = 768,
    ),
    ESRGAN_GENERAL(
        "esrgan-general", tr("Real-ESRGAN General"), AiPurpose.UPSCALE,
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.65.0/real_esrgan_general_x4v3-onnx-float.zip",
        4.5f, tr("BSD 3-Clause (Tencent ARC)"), true, tr("Schnelle 4×-Vergrößerung für Fotos."),
    ),
    ESRGAN_PLUS(
        "esrgan-x4plus", tr("Real-ESRGAN x4plus"), AiPurpose.UPSCALE,
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_x4plus/releases/v0.65.0/real_esrgan_x4plus-onnx-float.zip",
        62f, tr("BSD 3-Clause (Tencent ARC)"), true, tr("Beste Detailtreue, deutlich langsamer."),
    );

    companion object {
        fun byId(id: String?): AiModel? = entries.firstOrNull { it.id == id }
    }
}
