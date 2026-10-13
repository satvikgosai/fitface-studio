package dev.fitface.studio.core.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone's language, as the key of the face dictionary a preview reads.
 *
 * Donor previews and the custom template used to pass the raw locale (`fr_FR`), which names
 * no dictionary and silently fell back to English while the canvas beside them was French.
 */
class PreviewDictionaryLocaleTest {
    @Test
    fun aRegionalLocaleNamesTheLanguageDictionaryNotANonexistentOne() {
        assertEquals("fr", previewDictionaryLocale(Locale.forLanguageTag("fr-FR")))
        assertEquals("ja", previewDictionaryLocale(Locale.forLanguageTag("ja-JP")))
        assertEquals("ko", previewDictionaryLocale(Locale.forLanguageTag("ko-KR")))
        assertEquals("it", previewDictionaryLocale(Locale.forLanguageTag("it-IT")))
    }

    @Test
    fun chineseAndPortugueseUseTheProducersOwnKeys() {
        assertEquals("cn0", previewDictionaryLocale(Locale.forLanguageTag("zh-CN")))
        assertEquals("cn2", previewDictionaryLocale(Locale.forLanguageTag("zh-TW")))
        assertEquals("cn2", previewDictionaryLocale(Locale.forLanguageTag("zh-Hant-SG")))
        assertEquals("pt_rPT", previewDictionaryLocale(Locale.forLanguageTag("pt-BR")))
    }

    @Test
    fun aLanguageNoFaceCarriesFallsBackToEnglish() {
        assertEquals("en", previewDictionaryLocale(Locale.forLanguageTag("de-DE")))
        assertEquals("en", previewDictionaryLocale(Locale.forLanguageTag("en-GB")))
    }
}
