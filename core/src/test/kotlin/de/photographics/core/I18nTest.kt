package de.photographics.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Jeder Text in tr("…") (Kern und App) braucht eine englische Übersetzung mit denselben Platzhaltern. */
class I18nTest {
    @After fun reset() { I18n.lang = I18n.Lang.DE }

    private val lit = "\"((?:[^\"\\\\]|\\\\.)*)\""

    private fun unescape(s: String) = Regex("\\\\(u[0-9a-fA-F]{4}|.)").replace(s) { m ->
        val e = m.groupValues[1]
        if (e.startsWith("u") && e.length == 5) e.substring(1).toInt(16).toChar().toString()
        else when (e) { "n" -> "\n"; "t" -> "\t"; else -> e }
    }

    /** Schlüssel wie tools: tr("…") überall, dazu Namen, die erst bei der Anzeige übersetzt werden. */
    private fun keys(): Set<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val files = listOf("core/src/main/kotlin", "app/src/main/java", "gnome/src/main/kotlin").flatMap { File(root, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        val out = LinkedHashSet<String>()
        val trCall = Regex("\\btr\\($lit")
        val named = listOf(Regex("\\bLook\\($lit"), Regex("\\($lit to tr\\("), Regex("\\bBrushPreset\\($lit"), Regex("\\badd\\($lit\\)"),
            Regex("\\bp\\($lit"), Regex("0x[0-9A-Fa-f]+ to $lit"))
        val namedFiles = setOf("Looks.kt", "Films.kt", "BrushSettings.kt", "Palettes.kt", "PsdEffects.kt")
        for (f in files) {
            val src = f.readText()
            val pats = if (f.name in namedFiles) listOf(trCall) + named else listOf(trCall)
            for (p in pats) for (m in p.findAll(src)) {
                val k = unescape(m.groupValues[1])
                if (k.any { it.isLetter() } && !k.contains("\${")) out += k
            }
        }
        return out
    }

    @Test
    fun everyTextHasAnEnglishTranslation() {
        val en = I18n.load(I18n.Lang.EN)
        val missing = keys().filter { it !in en }
        assertTrue("Ohne englische Übersetzung (core/src/main/resources/i18n/en.tsv):\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun placeholdersMatch() {
        val ph = Regex("\\{\\d+\\}")
        val bad = I18n.load(I18n.Lang.EN).filter { (k, v) -> ph.findAll(k).map { it.value }.sorted().toList() != ph.findAll(v).map { it.value }.sorted().toList() }
        assertTrue("Platzhalter passen nicht: ${bad.keys}", bad.isEmpty())
    }

    @Test
    fun translatesWithArguments() {
        I18n.lang = I18n.Lang.EN
        assertEquals("Profile “sRGB” assigned.", tr("Profil „{0}“ zugewiesen.", "sRGB"))
        assertEquals("Multiply", tr("Multiplizieren"))
        assertEquals("Kein Schlüssel", tr("Kein Schlüssel"))
        I18n.lang = I18n.Lang.DE
        assertEquals("Profil „sRGB“ zugewiesen.", tr("Profil „{0}“ zugewiesen.", "sRGB"))
    }
}
