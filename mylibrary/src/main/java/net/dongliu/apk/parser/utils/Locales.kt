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
        
        // Priority 1: Locale List Index (Position). 
        // A match for the 1st locale is always better than a match for the 2nd locale.
        for (i in 0 until locales.size) {
            val level = match(locales.get(i), targetLocale)
            if (level > 1) {
                val score = (locales.size - i).toLong() * 10 + level
                // android.util.Log.d("AppLog", "label fetching: matchScore for [" + targetLocale + "] against [" + locales.get(i) + "] index " + i + " is " + score + " (level " + level + ")");
                return score
            }
        }
        
        // Priority 2: Default configuration match for the FIRST preferred locale.
        if (targetLocale.language.isEmpty()) {
            val score = locales.size.toLong() * 10 + 1
            // android.util.Log.d("AppLog", "label fetching: matchScore for [default] against index 0 is " + score);
            return score
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
