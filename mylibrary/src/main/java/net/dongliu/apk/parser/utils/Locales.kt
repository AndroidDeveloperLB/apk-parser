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

    /**
     * Check if any locale in 'appLocales' matches the given 'locale' at level >= 2.
     */
    @JvmStatic
    fun isLanguageSupported(locale: Locale, appLocales: Set<Locale>): Boolean {
        for (appLocale in appLocales) {
            if (match(locale, appLocale) >= 2) {
                return true
            }
        }
        return false
    }

    /**
     * Find the best matching score for a list of locales.
     * Higher is better.
     */
    @JvmStatic
    fun matchScore(locales: List<Locale>, targetLocale: Locale): Long {
        return matchScore(locales, targetLocale, emptySet())
    }

    @JvmStatic
    fun matchScore(locales: List<Locale>, targetLocale: Locale, appLocales: Set<Locale>): Long {
        if (locales.isEmpty()) return match(null, targetLocale).toLong()
        
        // 1. Determine which language in the preference list we should "commit" to.
        var bestSupportedLocaleIndex = -1
        if (!appLocales.isEmpty()) {
            for (i in 0 until locales.size) {
                if (isLanguageSupported(locales.get(i), appLocales)) {
                    bestSupportedLocaleIndex = i
                    break
                }
            }
        }

        // 2. Score the targetLocale against the locales list.
        for (i in 0 until locales.size) {
            val level = match(locales.get(i), targetLocale)
            
            // If we've committed to a specific language, ONLY allow matches for that language or earlier.
            // This prevents falling through to a later language if the current language has ANY support in the app.
            if (bestSupportedLocaleIndex != -1 && i > bestSupportedLocaleIndex) {
                break
            }

            if (level >= 2) {
                // Score depends on position primarily, then level.
                var score = (locales.size - i).toLong() * 10000 + level * 1000
                
                // Tie-breaker
                val country = targetLocale.country
                if (country.length == 2) {
                    score += (90 - country[0].code) * 30 + (90 - country[1].code)
                } else {
                    score += 800 
                }
                return score
            }
        }
        
        // Priority 2: Default configuration (no language specified).
        // It is a fallback for the "best" language we matched.
        if (targetLocale.language.isEmpty()) {
            val i = if (bestSupportedLocaleIndex != -1) bestSupportedLocaleIndex else 0
            return (locales.size - i).toLong() * 10000 + 1
        }
        
        return 0
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
