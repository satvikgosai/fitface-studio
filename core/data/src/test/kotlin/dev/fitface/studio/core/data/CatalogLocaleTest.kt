package dev.fitface.studio.core.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every expectation here was checked against the live endpoint before it was written: the
 * "accepted" values returned `resultCode=0` with a full page of faces, and the ones being
 * repaired returned `resultCode=1005 "locale not supported"` with none.
 */
class CatalogLocaleTest {
    @Test
    fun latinAmericanSpanishKeepsSpanishInsteadOfFallingBackToEnglish() {
        // es_419 is the default Spanish across Latin America and the reported failure.
        // Every specific country is accepted, so only the region is replaced.
        assertEquals("es_MX", CatalogLocale.of(Locale("es", "419")))
        assertEquals("es_MX", CatalogLocale.of(Locale("es", "419"), simRegion = "mx"))
    }

    @Test
    fun aSimCannotPairSpanishWithACountryTheStoreRefuses() {
        // es_US is rejected exactly like es_419, so the numeric region resolves from the
        // verified table first and the SIM only fills a region nothing else can name.
        assertEquals("es_MX", CatalogLocale.of(Locale("es", "419"), simRegion = "us"))
    }

    @Test
    fun otherNumericRegionsResolveToAnAcceptedCountry() {
        assertEquals("en_US", CatalogLocale.of(Locale("en", "001")))
        assertEquals("en_GB", CatalogLocale.of(Locale("en", "150")))
    }

    @Test
    fun javasObsoleteLanguageCodesAreModernisedBeforeTheyAreSent() {
        // The obsolete codes are fed in directly rather than via Locale("id"), because the
        // two platforms disagree and only one of them is the one that ships: Android's
        // libcore still converts id -> in, he -> iw and yi -> ji on construction, while
        // the desktop JDK stopped doing so in 17 unless java.locale.useOldISOCodes is set.
        // A test that leaned on the conversion would therefore pass here and prove nothing
        // about the phone that actually reported this.
        assertEquals("id_ID", CatalogLocale.of(Locale("in", "ID")))
        assertEquals("he_IL", CatalogLocale.of(Locale("iw", "IL")))
        assertEquals("yi_DE", CatalogLocale.of(Locale("ji", "DE")))

        // And the modern spellings, which is what this JVM produces, are already accepted.
        assertEquals("id_ID", CatalogLocale.of(Locale("id", "ID")))
        assertEquals("he_IL", CatalogLocale.of(Locale("he", "IL")))
    }

    @Test
    fun languagesWithNoTwoLetterCodeFallBackWholesale() {
        assertEquals("en_US", CatalogLocale.of(Locale("fil", "PH")))
        assertEquals("en_US", CatalogLocale.of(Locale("ceb", "PH")))
        // Even with a SIM naming a perfectly good country: the language is the problem.
        assertEquals("en_US", CatalogLocale.of(Locale("fil", ""), simRegion = "ph"))
    }

    @Test
    fun aWellFormedPairTheStoreStillRefusesIsLeftToTheRetry() {
        // qu_PE and tl_PH are both two-letter language plus real country, and both come
        // back 1005. Nothing here can know that — the whitelist is not enumerable — so
        // normalisation passes them through and FaceCatalogRepositoryImpl retries.
        assertEquals("qu_PE", CatalogLocale.of(Locale("qu", "PE")))
        assertEquals("tl_PH", CatalogLocale.of(Locale("tl", "PH")))
    }

    @Test
    fun aRegionThatCannotBeRecoveredAtAllFallsBack() {
        assertEquals("en_US", CatalogLocale.of(Locale("es", "")))
        assertEquals("es_MX", CatalogLocale.of(Locale("es", ""), simRegion = "MX"))
    }

    @Test
    fun theStoreIsCaseSensitiveSoTheCaseIsForced() {
        // en_us and EN_US are both refused; Locale normalises most of this itself, and
        // this pins the rest so a hand-built string cannot slip through.
        assertEquals("en_US", CatalogLocale.of(Locale("EN", "us")))
    }

    @Test
    fun anAlreadyAcceptedLocaleIsLeftAlone() {
        assertEquals("en_US", CatalogLocale.of(Locale("en", "US")))
        assertEquals("pt_BR", CatalogLocale.of(Locale("pt", "BR")))
        assertEquals("ko_KR", CatalogLocale.of(Locale("ko", "KR")))
    }
}

/**
 * The retry is the half that has to hold when normalisation cannot help — `qu_PE` is
 * well-formed, is refused, and only a second request in [CatalogLocale.Fallback] gets
 * that phone a catalogue at all.
 */
class CatalogRetryTest {
    @Test
    fun aRefusedLocaleIsRetriedInTheFallback() {
        val retry = CatalogRetry.localeAfter(
            CatalogRejected(CatalogRejected.LocaleNotSupported, "locale not supported"),
            current = "es_419",
        )

        assertEquals("en_US", retry)
    }

    @Test
    fun theFallbackIsNotRetriedAgainstItself() {
        // Otherwise a store that refuses en_US too costs a second round trip on every
        // page, and moving the retry inside the paging loop would spin forever.
        assertNull(
            CatalogRetry.localeAfter(
                CatalogRejected(CatalogRejected.LocaleNotSupported, "locale not supported"),
                current = "en_US",
            ),
        )
    }

    @Test
    fun anyOtherRejectionIsSurfacedRatherThanRetried() {
        // 1001 is a malformed request and 1007 is an empty list. Neither is fixed by
        // asking again in English, and retrying would hide the real code from the report.
        assertNull(CatalogRetry.localeAfter(CatalogRejected(1001, "Request Parsing Fail"), "es_MX"))
        assertNull(CatalogRetry.localeAfter(CatalogRejected(1007, "No Items"), "es_MX"))
        assertNull(CatalogRetry.localeAfter(CatalogRejected(null, ""), "es_MX"))
    }

    @Test
    fun theRejectionCarriesTheStoreCodeIntoTheReport() {
        // The code is the whole diagnosis, and it used to be flattened into prose the
        // moment it reached the UI — where technicalDetail was then dropped.
        val surfaced = CatalogRejected(1005, "locale not supported").asWatchFaceException()

        assertEquals("resultCode=1005 message=locale not supported", surfaced.technicalDetail)
    }
}
