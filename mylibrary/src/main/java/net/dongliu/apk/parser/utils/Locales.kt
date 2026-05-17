package net.dongliu.apk.parser.utils

import net.dongliu.apk.parser.bean.DeviceConfig
import net.dongliu.apk.parser.struct.resource.ResourceTable
import java.util.Locale

/**
 * Interface for resolving the script of a locale.
 * Should be implemented using Android's ICUCompat if running on Android.
 */
interface LocaleScriptResolver {
    fun getScript(locale: Locale): String
}

/**
 * Mimics Android's Resource resolution logic for Locales and Device configurations.
 */
object Locales {
    const val PERFECT_SCORE = Integer.MAX_VALUE

    /**
     * Resolver for locale scripts. If null, script matching will be skipped or use simple fallback.
     */
    @JvmStatic
    var scriptResolver: LocaleScriptResolver? = null

    private fun isPseudoLocale(locale: Locale): Boolean {
        return ((locale.language == "en" && locale.country == "XA") ||
                (locale.language == "ar" && locale.country == "XB"))
    }

    /**
     * Scores a candidate resource against the desired device configuration.
     * Higher is better.
     * 0 means completely incompatible.
     */
    @JvmStatic
    fun match(requestedConfig: DeviceConfig?, candidate: ResourceTable.Resource): Int {
        val config = candidate.type.config

        // 1. MCC/MNC matching (High priority)
        if (requestedConfig != null) {
            val reqMcc = requestedConfig.mcc
            val reqMnc = requestedConfig.mnc
            if (reqMcc != 0 && config.mcc != 0 && config.mcc != reqMcc) return 0
            if (reqMnc != 0 && config.mnc != 0 && config.mnc != reqMnc) return 0
        }

        // 2. UI Mode matching (Night mode)
        if (requestedConfig != null && requestedConfig.uiMode != 0) {
            val reqNight = requestedConfig.uiMode and 0x30 // UI_MODE_NIGHT_MASK
            val confNight = candidate.type.config.uiMode and 0x30
            if (reqNight != 0 && confNight != 0 && reqNight != confNight) return 0
        }

        // 3. Locale matching
        return match(requestedConfig?.locale, candidate.type.locale)
    }

    /**
     * Scores a candidate locale against the desired device locale.
     */
    @JvmStatic
    fun match(deviceLocale: Locale?, candidate: Locale): Int {
        // 1. Perfect Match (including pseudo-locales if they match exactly)
        if (deviceLocale != null && deviceLocale == candidate) {
            return PERFECT_SCORE
        }

        // Default/Empty configuration is the lowest possible positive match
        // BUT it must be better than a non-representative sibling.
        // Sibling = 5, Default = 10, Language Match = 30, Secondary Rep = 40, Primary Rep = 45, Perfect = MAX
        if (candidate.language.isEmpty()) {
            return 10
        }

        // If languages are different, they definitely don't match
        val candidateLanguage = normalizeLanguage(candidate.language)
        val deviceLanguage = if (deviceLocale != null) normalizeLanguage(deviceLocale.language) else ""

        if (deviceLanguage != candidateLanguage) {
            return 0
        }

        // Languages match!

        // 2. Pseudo-locale handling: If they are not equal (checked above), and one is pseudo, it's a mismatch
        if (isPseudoLocale(candidate) || (deviceLocale != null && isPseudoLocale(deviceLocale))) {
            return 0
        }

        // 3. Script matching
        val deviceScript = deviceLocale?.let { getScript(it) } ?: ""
        val candidateScript = getScript(candidate)
        if (deviceScript != candidateScript && deviceScript.isNotEmpty() && candidateScript.isNotEmpty()) {
            return 0
        }

        // Script matches (or at least one is missing).

        val candidateCountry = candidate.country
        val deviceCountry = deviceLocale?.country ?: ""

        // 4. Candidate has no country (general language match)
        if (candidateCountry.isEmpty()) {
            return 30
        }

        // 5. Representative Fallback Match (e.g., en-GB for en-AU, or en-US for en-IL)
        if (deviceLocale != null) {
            val repScore = getRepresentativeScore(deviceLanguage, deviceCountry, candidateCountry)
            if (repScore > 0) {
                return repScore
            }
        }

        // 6. Sibling (same language, different country, neither is a representative)
        // A sibling match is still better than nothing (score 0),
        // but worse than the default/empty configuration (score 10).
        return 5
    }

    private fun getScript(locale: Locale): String {
        return scriptResolver?.getScript(locale) ?: ""
    }

    /**
     * Simplified version of Android's representative table.
     */
    private fun getRepresentativeScore(lang: String, deviceCountry: String, candidateCountry: String): Int {
        if (lang == "en") {
            // en-GB is the primary representative for most of the world except Americas
            val britishRegions = setOf("IL", "GB", "AU", "NZ", "IE", "ZA", "IN", "HK", "MT", "SG")
            if (candidateCountry == "GB" && britishRegions.contains(deviceCountry)) return 45

            // en-US is the primary representative for Americas
            val americanRegions = setOf("US", "CA", "PH", "LR")
            if (candidateCountry == "US") {
                if (americanRegions.contains(deviceCountry)) return 45
                // en-US acts as a secondary/global representative for other en regions (like IL)
                return 40
            }
        }

        if (lang == "zh") {
            // zh-HK and zh-MO often fallback to zh-TW (Traditional)
            if (candidateCountry == "TW" && (deviceCountry == "HK" || deviceCountry == "MO")) return 45
        }

        return 0
    }

    @JvmStatic
    fun normalizeLanguage(language: String): String {
        return when (language) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> language.lowercase()
        }
    }
}
