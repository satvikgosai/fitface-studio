package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version ordering, which is the whole basis of "is there an update".
 *
 * The case that matters most is `0.1.10` against `0.1.9`: compared as strings the newer
 * one sorts lower, so the release a reader most needs would be the one never offered.
 * The rest of these pin the fail-closed rule — anything unparseable is not a version,
 * so it cannot be offered.
 */
class AppVersionTest {

    private fun version(text: String) = requireNotNull(AppVersion.parse(text)) { "not parsed: $text" }

    @Test
    fun aTwoDigitSegmentIsNewerThanAOneDigitOne() {
        assertTrue("0.1.10 must be newer than 0.1.9", version("0.1.10") > version("0.1.9"))
        assertTrue(version("0.10.0") > version("0.9.9"))
    }

    @Test
    fun aMissingSegmentIsZero() {
        assertEquals(0, version("0.2").compareTo(version("0.2.0")))
        assertEquals(0, version("1").compareTo(version("1.0.0.0")))
    }

    @Test
    fun theOrderingIsWhatYouWouldExpectOtherwise() {
        assertTrue(version("0.1.2") > version("0.1.1"))
        assertTrue(version("1.0.0") > version("0.9.9"))
        assertTrue(version("0.1.1") < version("0.2"))
        assertEquals(0, version("0.1.1").compareTo(version("0.1.1")))
    }

    /** The leading `v` is on every release tag and on no version name. */
    @Test
    fun aTagPrefixIsAccepted() {
        assertEquals(version("0.1.1"), version("v0.1.1"))
        assertEquals("0.1.1", version("v0.1.1").raw)
    }

    /**
     * Fail closed. A pre-release suffix has no obvious place in this ordering, and
     * guessing one risks offering a version that cannot be installed over the current
     * build — whose only workaround deletes every saved project.
     */
    @Test
    fun anythingThatIsNotDottedDigitsIsNotAVersion() {
        assertNull(AppVersion.parse("0.2.0-rc1"))
        assertNull(AppVersion.parse("nightly"))
        assertNull(AppVersion.parse("0..1"))
        assertNull(AppVersion.parse("0.1."))
        assertNull(AppVersion.parse("-1.0"))
        assertNull(AppVersion.parse("0.1.1a"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("   "))
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse("v"))
        // Wider than an Int, so there is no silent wrap.
        assertNull(AppVersion.parse("99999999999"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(version("0.1.1"), version("  v0.1.1  "))
    }
}
