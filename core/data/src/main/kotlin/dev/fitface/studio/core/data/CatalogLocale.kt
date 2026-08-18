package dev.fitface.studio.core.data

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Builds the `locale` query parameter the catalogue endpoint will accept.
 *
 * The store matches the whole `language_REGION` pair against a fixed whitelist and
 * answers anything outside it with `resultCode=1005 "locale not supported"` — which the
 * app reported as an empty catalogue behind "Check your connection", so an affected
 * phone could never get past the first screen however good its connection was. Three
 * device-derived shapes are refused, and Android emits all three:
 *
 *  * **UN M.49 numeric regions.** `es_419` is the default Spanish across Latin America,
 *    and `en_001` / `en_150` are ordinary picks elsewhere. Every specific country works
 *    — `es_MX`, `es_AR`, `es_CO`, `es_CL`, `es_PE`, `es_VE` all return the full
 *    catalogue — so only the region needs replacing, never the language.
 *  * **Java's obsolete ISO 639 codes.** [Locale] still converts `id`→`in`, `he`→`iw`
 *    and `yi`→`ji` on construction, so Indonesian and Hebrew phones send a code the
 *    store does not list while the modern spelling works.
 *  * **Languages with no two-letter code**: `fil`, `tl`, `qu`, `gn`.
 *
 * The pair is case-sensitive too — `en_us` and `EN_US` are both refused — and a bare
 * language is refused, so `en` alone is not a safe fallback.
 *
 * Normalising cannot be complete: `qu_PE` is perfectly well-formed and still rejected,
 * and the whitelist is not enumerable from outside. So this only narrows how often the
 * retry in [FaceCatalogRepositoryImpl] is needed, and — by preferring the SIM's country
 * over [Fallback] — keeps the reader's own language while doing it.
 */
internal object CatalogLocale {
    /**
     * Verified to return the full catalogue. Deliberately not the empty string, which is
     * also accepted but makes the store serve Korean face names, because `cc` is `KOR`.
     */
    const val Fallback = "en_US"

    private val ModernLanguages = mapOf("in" to "id", "iw" to "he", "ji" to "yi")

    /**
     * A country to stand in for each numeric region. Every target is verified against the
     * store, so a phone set to "Español (Latinoamérica)" keeps Spanish face names instead
     * of dropping to English.
     *
     * Checked **before** the SIM on purpose. The region barely changes the response —
     * the language half is what picks the name — while a SIM can pair a language with a
     * country the store does not list: a Spanish speaker on a US SIM would otherwise
     * produce `es_US`, which is refused exactly like the `es_419` this is repairing.
     */
    private val RegionForM49 = mapOf("419" to "MX", "001" to "US", "150" to "GB")

    fun of(locale: Locale, simRegion: String? = null): String {
        val language = locale.language.lowercase(Locale.ROOT).let { ModernLanguages[it] ?: it }
        if (language.length != 2 || !language.all(Char::isLetter)) return Fallback
        val region = alpha2(locale.country)
            ?: RegionForM49[locale.country.trim()]
            ?: alpha2(simRegion)
            ?: return Fallback
        return "${language}_$region"
    }

    private fun alpha2(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.length == 2 && it.all(Char::isLetter) }
        ?.uppercase(Locale.ROOT)
}

/**
 * The country the SIM or the registered network claims, used only to replace a numeric
 * region [CatalogLocale] cannot otherwise repair. Absent on a Wi-Fi-only phone.
 *
 * Shared rather than resolved per call site: the request and the report have to agree
 * about what was sent, and they disagreed while the reporter left this out — a phone
 * whose locale names no country sent `es_MX` and was told in its own bug report that it
 * had sent `en_US`, which is a lie in the one field that failure is diagnosed from.
 */
internal fun Context.simCatalogRegion(): String? = runCatching {
    val telephony = getSystemService(TelephonyManager::class.java)
    telephony?.simCountryIso?.takeIf(String::isNotBlank) ?: telephony?.networkCountryIso
}.getOrNull()?.takeIf(String::isNotBlank)

/**
 * Whether a refused catalogue page is worth asking for again, and in what.
 *
 * Pure so it can be tested without a network: the retry is the half of the locale fix
 * that has to keep working, since [CatalogLocale] cannot recognise every pair the store
 * refuses, and a decision buried in a `catch` is a decision nothing asserts on.
 */
internal object CatalogRetry {
    /** The locale to retry with, or null to give up and surface the rejection. */
    fun localeAfter(rejection: CatalogRejected, current: String): String? = when {
        rejection.resultCode != CatalogRejected.LocaleNotSupported -> null
        // Retrying the same string is a wasted round trip and an infinite loop waiting
        // for someone to move the retry inside the paging loop.
        current == CatalogLocale.Fallback -> null
        else -> CatalogLocale.Fallback
    }
}
