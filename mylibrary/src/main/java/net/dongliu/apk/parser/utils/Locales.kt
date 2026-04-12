package net.dongliu.apk.parser.utils

import java.util.Locale

/**
 * @author dongliu
 */
object Locales {
    /**
     * when do localize, any locale will match this
     */
    @JvmField
    val any = Locale("", "")

    /**
     * How much the given locale match the expected locale.
     */
    @JvmStatic
    fun match(locale: Locale?, targetLocale: Locale): Int {
        if (locale == null) {
            return -1
        }
        val lang1 = normalizeLanguage(locale.language)
        val lang2 = normalizeLanguage(targetLocale.language)
        val languageMatch = lang1 == lang2
        if (languageMatch) {
            if (locale.country == targetLocale.country) {
                return 4
            }
            if (targetLocale.country.isEmpty()) {
                return 3
            }
            // Pseudolocale check: en-XA, ar-XB, etc.
            // These should have lower priority than the default locale (1) if they don't match exactly.
            if (targetLocale.country == "XA" || targetLocale.country == "XB") {
                return 0
            }
            return 2
        }
        return if (targetLocale.language.isEmpty()) 1 else 0
    }

    private fun normalizeLanguage(language: String): String {
        return when (language) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> language
        }
    }
}
