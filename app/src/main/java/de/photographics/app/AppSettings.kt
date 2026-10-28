package de.photographics.app

import android.content.Context
import de.photographics.core.color.IccProfiles
import de.photographics.core.color.ProofCondition
import de.photographics.core.color.RenderingIntent
import de.photographics.core.tiff.TiffWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Hell"), DARK("Dunkel") }

enum class ColorSource(val label: String) { DYNAMIC("Hintergrundbild (Material You)"), SEED("Eigene Akzentfarbe") }

/** Hintergrund rund um das Bild – wie *Voreinstellungen → Benutzeroberfläche* in Photoshop. */
enum class CanvasBg(val label: String, val color: Int?) {
    AUTO("Standard", null),
    BLACK("Schwarz", 0xFF000000.toInt()),
    DARK("Dunkelgrau", 0xFF282828.toInt()),
    MEDIUM("Mittelgrau", 0xFF535353.toInt()),
    LIGHT("Hellgrau", 0xFFB8B8B8.toInt()),
    CUSTOM("Eigene Farbe", null),
}

enum class Side(val label: String) { LEFT("Links"), RIGHT("Rechts") }
enum class BarPosition(val label: String) { TOP("Oben"), BOTTOM("Unten") }
enum class SliderPlacement(val label: String) { LEFT("Links"), RIGHT("Rechts"), HIDDEN("Aus") }

/** Umgang mit Dateien, deren Profil vom Arbeitsfarbraum abweicht (Photoshop: Farbmanagement-Richtlinien). */
enum class ProfilePolicy(val label: String, val description: String) {
    KEEP("Eingebettetes Profil beibehalten", "Farben bleiben exakt, das Dokument behält sein Profil"),
    CONVERT("In Arbeitsfarbraum umwandeln", "Beim Öffnen in den Arbeitsfarbraum umrechnen"),
}

/** Vordefinierte Akzentfarben (Ausgangsfarbe für das Material-3-Farbschema). */
val SEED_PRESETS = listOf(
    "Indigo" to 0xFF4A5C92.toInt(),
    "Blau" to 0xFF1E88E5.toInt(),
    "Petrol" to 0xFF00796B.toInt(),
    "Grün" to 0xFF43A047.toInt(),
    "Oliv" to 0xFF827717.toInt(),
    "Bernstein" to 0xFFFFA000.toInt(),
    "Orange" to 0xFFF4511E.toInt(),
    "Rot" to 0xFFD32F2F.toInt(),
    "Magenta" to 0xFFC2185B.toInt(),
    "Violett" to 0xFF7B1FA2.toInt(),
    "Schiefer" to 0xFF607D8B.toInt(),
    "Graphit" to 0xFF5F5F5F.toInt(),
)

data class AppSettings(
    // Darstellung
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.DYNAMIC,
    val seedColor: Int = SEED_PRESETS[0].second,
    /** Tiefschwarze Flächen im dunklen Design (OLED). */
    val pureBlack: Boolean = false,
    val canvasBg: CanvasBg = CanvasBg.AUTO,
    val canvasCustom: Int = 0xFF3A3A3A.toInt(),
    // Arbeitsbereich
    val toolbarSide: Side = Side.LEFT,
    val toolLabels: Boolean = true,
    val layersSide: Side = Side.RIGHT,
    val layersWidth: Int = 340,
    val optionsBar: BarPosition = BarPosition.TOP,
    val quickSliders: SliderPlacement = SliderPlacement.LEFT,
    // Farbproof
    val proofCondition: ProofCondition = ProofCondition.COATED,
    val proofSimulatePaper: Boolean = false,
    val gamutWarningColor: Int = 0xFF808080.toInt(),
    /** Eigenes ICC-Profil für den Proof (Dateiname in der Profilbibliothek); null = modellierte Bedingung. */
    val proofIcc: String? = null,
    val proofIntent: RenderingIntent = RenderingIntent.RELATIVE,
    val proofBpc: Boolean = true,
    // Farbmanagement
    val workingRgb: IccProfiles.Builtin = IccProfiles.Builtin.SRGB,
    val profilePolicy: ProfilePolicy = ProfilePolicy.KEEP,
    val conversionIntent: RenderingIntent = RenderingIntent.RELATIVE,
    val conversionBpc: Boolean = true,
    /** Beim Export (PNG/JPEG) in sRGB umwandeln statt das Dokumentprofil einzubetten. */
    val exportToSrgb: Boolean = false,
    // Ansicht: Lineale, Hilfslinien, Ausrichten
    val showRulers: Boolean = true,
    val showGuides: Boolean = true,
    val snap: Boolean = true,
    val smartGuides: Boolean = true,
    // TIFF speichern
    val tiffCompression: TiffWriter.Compression = TiffWriter.Compression.LZW,
    val tiffLayers: Boolean = true,
    /** Einführung beim ersten Start gesehen. */
    val introSeen: Boolean = false,
) {
    /** Vordefinierte Arbeitsbereiche. */
    enum class Preset(val label: String, val description: String) {
        STANDARD("Standard", "Werkzeuge links, Ebenen rechts"),
        LEFT_HANDED("Linkshänder", "Werkzeuge und Schnellregler rechts, Ebenen links"),
        MINIMAL("Minimal", "Schmale Werkzeugleiste, Optionen unten, keine Schnellregler"),
    }

    fun withPreset(p: Preset): AppSettings = when (p) {
        Preset.STANDARD -> copy(toolbarSide = Side.LEFT, toolLabels = true, layersSide = Side.RIGHT, layersWidth = 340,
            optionsBar = BarPosition.TOP, quickSliders = SliderPlacement.LEFT)
        Preset.LEFT_HANDED -> copy(toolbarSide = Side.RIGHT, toolLabels = true, layersSide = Side.LEFT, layersWidth = 340,
            optionsBar = BarPosition.TOP, quickSliders = SliderPlacement.RIGHT)
        Preset.MINIMAL -> copy(toolbarSide = Side.LEFT, toolLabels = false, layersSide = Side.RIGHT, layersWidth = 300,
            optionsBar = BarPosition.BOTTOM, quickSliders = SliderPlacement.HIDDEN)
    }
}

/** Dauerhafte Ablage der Einstellungen (SharedPreferences). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_settings.value)
        _settings.value = s
        save(s)
    }

    private inline fun <reified E : Enum<E>> enumOf(key: String, def: E): E =
        prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOf("themeMode", d.themeMode),
            colorSource = enumOf("colorSource", d.colorSource),
            seedColor = prefs.getInt("seedColor", d.seedColor),
            pureBlack = prefs.getBoolean("pureBlack", d.pureBlack),
            canvasBg = enumOf("canvasBg", d.canvasBg),
            canvasCustom = prefs.getInt("canvasCustom", d.canvasCustom),
            toolbarSide = enumOf("toolbarSide", d.toolbarSide),
            toolLabels = prefs.getBoolean("toolLabels", d.toolLabels),
            layersSide = enumOf("layersSide", d.layersSide),
            layersWidth = prefs.getInt("layersWidth", d.layersWidth),
            optionsBar = enumOf("optionsBar", d.optionsBar),
            quickSliders = enumOf("quickSliders", d.quickSliders),
            proofCondition = enumOf("proofCondition", d.proofCondition),
            proofSimulatePaper = prefs.getBoolean("proofSimulatePaper", d.proofSimulatePaper),
            gamutWarningColor = prefs.getInt("gamutWarningColor", d.gamutWarningColor),
            proofIcc = prefs.getString("proofIcc", null),
            proofIntent = enumOf("proofIntent", d.proofIntent),
            proofBpc = prefs.getBoolean("proofBpc", d.proofBpc),
            workingRgb = enumOf("workingRgb", d.workingRgb),
            profilePolicy = enumOf("profilePolicy", d.profilePolicy),
            conversionIntent = enumOf("conversionIntent", d.conversionIntent),
            conversionBpc = prefs.getBoolean("conversionBpc", d.conversionBpc),
            exportToSrgb = prefs.getBoolean("exportToSrgb", d.exportToSrgb),
            showRulers = prefs.getBoolean("showRulers", d.showRulers),
            showGuides = prefs.getBoolean("showGuides", d.showGuides),
            snap = prefs.getBoolean("snap", d.snap),
            smartGuides = prefs.getBoolean("smartGuides", d.smartGuides),
            tiffCompression = enumOf("tiffCompression", d.tiffCompression),
            tiffLayers = prefs.getBoolean("tiffLayers", d.tiffLayers),
            introSeen = prefs.getBoolean("introSeen", d.introSeen),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("themeMode", s.themeMode.name)
            .putString("colorSource", s.colorSource.name)
            .putInt("seedColor", s.seedColor)
            .putBoolean("pureBlack", s.pureBlack)
            .putString("canvasBg", s.canvasBg.name)
            .putInt("canvasCustom", s.canvasCustom)
            .putString("toolbarSide", s.toolbarSide.name)
            .putBoolean("toolLabels", s.toolLabels)
            .putString("layersSide", s.layersSide.name)
            .putInt("layersWidth", s.layersWidth)
            .putString("optionsBar", s.optionsBar.name)
            .putString("quickSliders", s.quickSliders.name)
            .putString("proofCondition", s.proofCondition.name)
            .putBoolean("proofSimulatePaper", s.proofSimulatePaper)
            .putInt("gamutWarningColor", s.gamutWarningColor)
            .putString("proofIcc", s.proofIcc)
            .putString("proofIntent", s.proofIntent.name)
            .putBoolean("proofBpc", s.proofBpc)
            .putString("workingRgb", s.workingRgb.name)
            .putString("profilePolicy", s.profilePolicy.name)
            .putString("conversionIntent", s.conversionIntent.name)
            .putBoolean("conversionBpc", s.conversionBpc)
            .putBoolean("exportToSrgb", s.exportToSrgb)
            .putBoolean("showRulers", s.showRulers)
            .putBoolean("showGuides", s.showGuides)
            .putBoolean("snap", s.snap)
            .putBoolean("smartGuides", s.smartGuides)
            .putString("tiffCompression", s.tiffCompression.name)
            .putBoolean("tiffLayers", s.tiffLayers)
            .putBoolean("introSeen", s.introSeen)
            .apply()
    }
}
