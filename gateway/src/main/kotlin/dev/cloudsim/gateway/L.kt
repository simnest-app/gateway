package dev.cloudsim.gateway

import java.util.Locale

/**
 * The gateway's three languages (I18N_PLAN.md): the Chinese text is its own key; English and Traditional Chinese come
 * from [I18nTables]. Follows the language the system gives this app (Android 13 lets each app have its own), so
 * nothing needs a Context. A text not in the tables shows as written.
 */
object L {
    fun t(key: String, vararg args: Any?): String {
        val text = table()?.get(key) ?: key
        return if (args.isEmpty()) text.replace("%%", "%") else runCatching { String.format(Locale.getDefault(), text, *args) }.getOrDefault(text)
    }

    private fun table(): Map<String, String>? {
        val l = Locale.getDefault()
        return when {
            l.language != "zh" -> I18nTables.en
            l.script == "Hant" || l.country in setOf("TW", "HK", "MO") -> I18nTables.zhHant
            else -> null
        }
    }
}
