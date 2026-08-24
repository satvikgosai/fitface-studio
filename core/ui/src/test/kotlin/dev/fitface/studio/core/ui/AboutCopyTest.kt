package dev.fitface.studio.core.ui

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The About dialog's copy, asserted as copy rather than as a rendered layout.
 *
 * Deliberately not a composition test. An `AlertDialog` never reaches idle in this
 * harness — `createComposeRule` throws `AppNotIdleException` before it can measure one,
 * which is why neither this dialog nor `DiagnosticsDialog` has a rendering test — and the
 * properties worth guarding here are not geometric anyway. They are the things a well-meant
 * edit to the wording would quietly remove:
 *
 *  * the non-affiliation claim, which is the one part of this dialog that is not optional;
 *  * the length that keeps it to a line, which is why it was shortened in the first place;
 *  * the link, which is what allows the short version — `NOTICE.md` is reachable rather
 *    than restated, and a wall of disclaimer stops being read at all.
 *
 * The dialog's actual appearance is checked by eye on a device; that part cannot be
 * automated here.
 */
@RunWith(RobolectricTestRunner::class)
class AboutCopyTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun string(id: Int) = resources.getString(id)

    /** Trim it further if it helps, but not past the claim itself. */
    @Test
    fun theNonAffiliationClaimSurvivesAnyTrim() {
        val line = string(R.string.ui_about_independent)

        assertTrue(
            "the independence line no longer makes the claim: \"$line\"",
            line.contains("independent", ignoreCase = true) &&
                line.contains("not affiliated", ignoreCase = true),
        )
    }

    /**
     * The reason it is one line: a dialog on a 360dp phone has room for a sentence, and a
     * paragraph of disclaimer is read by nobody. The bound is loose enough for a
     * rewording and tight enough to catch the old three-clause version growing back.
     */
    @Test
    fun theNonAffiliationLineStaysShort() {
        val line = string(R.string.ui_about_independent)

        assertTrue(
            "the independence line is ${line.length} characters and is growing back",
            line.length <= 130,
        )
    }

    /**
     * The scheme is what makes it followable — the dialog draws the address without it, but
     * `LinkAnnotation.Url` needs it or the platform has no protocol to open.
     */
    @Test
    fun theProjectLinkIsAnHttpsUrlToTheProject() {
        val url = string(R.string.ui_about_source_url)

        assertTrue("the project URL lost its scheme: $url", url.startsWith("https://"))
        assertTrue("the project URL is not the repository: $url", url.contains("github.com/"))
        assertTrue("the project URL has a trailing slash, which the label would show", !url.endsWith("/"))
    }

    /**
     * The prose is what gives way when the dialog has to fit landscape, so it has a bound
     * too — the version and the link are the two things this dialog exists for.
     */
    @Test
    fun theDescriptionStaysShortEnoughToLeaveRoomForTheVersion() {
        val line = string(R.string.ui_about_what_it_is)

        assertTrue(
            "the description is ${line.length} characters; the version falls off the bottom " +
                "in landscape well before 220",
            line.length <= 180,
        )
    }

    /** Without the placeholder the dialog would show the word rather than the version. */
    @Test
    fun theVersionLineTakesTheVersion() {
        val template = string(R.string.ui_about_version)

        assertTrue("ui_about_version has no placeholder: \"$template\"", template.contains("%1\$s"))
        assertTrue(
            "formatting it did not produce the version",
            resources.getString(R.string.ui_about_version, "0.1.1 (17)").contains("0.1.1 (17)"),
        )
    }
}
