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
     * Find the best matching score for a list of locales.
     * Higher is better.
     * The score combines the index in the locale list and the match level for that locale.
     */
    @JvmStatic
    fun matchScore(locales: List<Locale>, targetLocale: Locale): Long {
        if (locales.isEmpty()) return match(null, targetLocale).toLong()
        
        // Priority 1: Language match (Level 2, 3, 4) for any locale in the list.
        // We iterate through user preferences. The first language that has ANY match in the app wins.
        for (i in 0 until locales.size) {
            val level = match(locales.get(i), targetLocale)
            if (level >= 2) {
                // Score depends on position primarily, then level.
                // Multiplier 10000 ensures position i always beats i+1 regardless of level.
                var score = (locales.size - i).toLong() * 10000 + level * 1000
                
                // Tie-breaker within the same locale and level (e.g. en_AU vs en_GB for en_IL).
                val country = targetLocale.country
                if (country.length == 2) {
                    // Alphabetical tie-breaker: favor earlier country codes (AU > CA).
                    // Multiplier 30 ensures first letter dominates (max diff 25*30 vs 25).
                    score += (90 - country[0].code) * 30 + (90 - country[1].code)
                } else {
                    score += 800 // Within the level's 1000 range
                }
                return score
            }
        }
        
        // Priority 2: Default configuration (no language specified)
        if (targetLocale.language.isEmpty()) {
            return 1
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
