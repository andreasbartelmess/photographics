package de.photographics.core

/**
 * Übersetzung der Oberfläche nach dem Muster von gettext: Im Code steht der deutsche Text in [tr],
 * die Übersetzungen liegen je Sprache in `i18n/<sprache>.tsv` (Ressource, eine Zeile je Text:
 * deutsch, Tabulator, Übersetzung; `\n` und `\t` als Escape). Fehlt eine Übersetzung, erscheint
 * der deutsche Text. Platzhalter {0}, {1}, … werden nach dem Übersetzen ersetzt.
 *
 * Die Sprache gilt für den ganzen Prozess; die App setzt sie beim Start (Einstellung oder Systemsprache).
 */
object I18n {
    enum class Lang(val code: String) { DE("de"), EN("en") }

    @Volatile var lang: Lang = Lang.DE
        set(v) { field = v; table = null }

    @Volatile private var table: Map<String, String>? = null

    private fun table(): Map<String, String> = table ?: load(lang).also { table = it }

    /** Übersetzungstabelle einer Sprache (leer für Deutsch). */
    fun load(l: Lang): Map<String, String> {
        if (l == Lang.DE) return emptyMap()
        val stream = I18n::class.java.getResourceAsStream("/i18n/${l.code}.tsv") ?: return emptyMap()
        val map = HashMap<String, String>(2048)
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                map[unescape(line.substring(0, tab))] = unescape(line.substring(tab + 1))
            }
        }
        return map
    }

    private fun unescape(s: String) = if ('\\' !in s) s else s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")

    fun tr(de: String, args: Array<out Any?>): String {
        val t = if (lang == Lang.DE) de else table()[de] ?: de
        if (args.isEmpty()) return t
        var out = t
        for (i in args.indices) out = out.replace("{$i}", args[i].toString())
        return out
    }
}

/** Übersetzt einen deutschen Oberflächentext in die eingestellte Sprache (siehe [I18n]). */
fun tr(de: String, vararg args: Any?): String = I18n.tr(de, args)
