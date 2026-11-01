package de.photographics.gnome

import de.photographics.core.color.IccProfiles
import de.photographics.core.color.Palette
import de.photographics.core.color.PaletteIO
import de.photographics.core.color.ProofCondition
import de.photographics.core.tools.BrushPreset
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tiff.TiffWriter
import java.io.File
import java.util.Properties

/** Einstellungen der GNOME-Version (~/.config/photographics/settings.properties). */
object Settings {
    enum class Language(val code: String?) { SYSTEM(null), DE("de"), EN("en") }

    private val dir: File by lazy {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config")
        File(base, "photographics").apply { mkdirs() }
    }
    private val file get() = File(dir, "settings.properties")
    private val p = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

    private fun <E : Enum<E>> enum(key: String, def: E, all: Array<E>): E = all.firstOrNull { it.name == p.getProperty(key) } ?: def
    private fun bool(key: String, def: Boolean) = p.getProperty(key)?.toBooleanStrictOrNull() ?: def
    private fun set(key: String, v: Any) { p.setProperty(key, v.toString()); runCatching { file.outputStream().use { p.store(it, "photographics") } } }

    var language: Language
        get() = enum("language", Language.SYSTEM, Language.entries.toTypedArray())
        set(v) = set("language", v.name)
    var workingRgb: IccProfiles.Builtin
        get() = enum("workingRgb", IccProfiles.Builtin.SRGB, IccProfiles.Builtin.entries.toTypedArray())
        set(v) = set("workingRgb", v.name)
    var proofCondition: ProofCondition
        get() = enum("proofCondition", ProofCondition.COATED, ProofCondition.entries.toTypedArray())
        set(v) = set("proofCondition", v.name)
    var proofSimulatePaper: Boolean
        get() = bool("proofSimulatePaper", false)
        set(v) = set("proofSimulatePaper", v)
    var exportToSrgb: Boolean
        get() = bool("exportToSrgb", true)
        set(v) = set("exportToSrgb", v)
    var jpegQuality: Int
        get() = p.getProperty("jpegQuality")?.toIntOrNull()?.coerceIn(1, 100) ?: 90
        set(v) = set("jpegQuality", v)
    var exportFormat: String
        get() = p.getProperty("exportFormat") ?: "JPEG"
        set(v) = set("exportFormat", v)
    var tiffCompression: TiffWriter.Compression
        get() = enum("tiffCompression", TiffWriter.Compression.LZW, TiffWriter.Compression.entries.toTypedArray())
        set(v) = set("tiffCompression", v.name)
    var snap: Boolean
        get() = bool("snap", true)
        set(v) = set("snap", v)
    var showRulers: Boolean
        get() = bool("showRulers", true)
        set(v) = set("showRulers", v)
    var showGuides: Boolean
        get() = bool("showGuides", true)
        set(v) = set("showGuides", v)
    var activePalette: String
        get() = p.getProperty("palette") ?: "Standard"
        set(v) = set("palette", v)
    var recentColors: List<Int>
        get() = p.getProperty("recentColors")?.split(',')?.mapNotNull { it.toLongOrNull(16)?.toInt() } ?: emptyList()
        set(v) = set("recentColors", v.joinToString(",") { Integer.toHexString(it) })

    // ------------------------------------------------------------ Eigene Pinselvorgaben

    private val brushFile get() = File(dir, "brushes.txt")

    /** Eigene Vorgaben (eine Zeile je Vorgabe: Name, Tabulator, Einstellungen). */
    fun userBrushes(): List<BrushPreset> = runCatching {
        brushFile.readLines().mapNotNull { l ->
            val i = l.indexOf('\t'); if (i <= 0) null else BrushPreset(l.substring(0, i), BrushSettings.decode(l.substring(i + 1)))
        }
    }.getOrDefault(emptyList())

    fun saveBrush(name: String, b: BrushSettings) {
        val list = userBrushes().filter { it.name != name } + BrushPreset(name, b)
        brushFile.writeText(list.joinToString("\n") { "${it.name.replace('\t', ' ')}\t${it.settings.encode()}" })
    }

    fun deleteBrush(name: String) {
        brushFile.writeText(userBrushes().filter { it.name != name }.joinToString("\n") { "${it.name}\t${it.settings.encode()}" })
    }

    // ------------------------------------------------------------ Eigene Farbfelder

    val paletteDir: File get() = File(dir, "palettes").apply { mkdirs() }

    fun userPalettes(): List<Palette> = paletteDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
        ?.mapNotNull { f -> runCatching { PaletteIO.read(f.readBytes(), f.name) }.getOrNull() } ?: emptyList()

    fun savePalette(p: Palette) {
        File(paletteDir, p.name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_") + ".gpl").writeText(PaletteIO.writeGpl(p))
    }
}
