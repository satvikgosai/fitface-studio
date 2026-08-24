package dev.fitface.studio.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.fitface.studio.core.ui.FitFaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.fitface.studio.core.ui.R as UiR

/**
 * The tabs must not move when you switch tabs.
 *
 * Both halves of the header are sized by the page it is showing, and the two pages do not
 * carry the same content: Watch faces has a REFRESH button the Projects page has none of, and
 * a longer explanation under the headline that wraps to a second line on a narrow phone. So
 * the row of tabs sat lower on Watch faces than on Projects — 26px lower on a 411dp phone from
 * the button alone, a whole line more than that on a 360dp one — and the tab you had just
 * tapped slid out from under your finger as the page changed.
 *
 * The fix reserves both: the actions row keeps the touch-target height whether or not REFRESH
 * is in it, and the subtitle lays out **both** pages' strings so the box is as tall as the
 * longer one wraps to. The assertions are on positions rather than on line counts, because
 * whether a given string wraps at a given width is exactly the thing Robolectric's font
 * metrics get wrong — see the note in `FitTopBarLayoutTest`. Equality of positions holds
 * either way, and it is the property the bug was about.
 *
 * Native graphics mode for the same reason it is on there: the default stub metrics collapse
 * every string to a few pixels, so nothing below the text would be where the device puts it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class LibraryHeaderLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private var page by mutableStateOf(LibraryPage.WatchFaces)
    private var crashed by mutableStateOf(false)

    /** Captured from the composition so the test cannot drift from the string resources. */
    private var projectsTab: String = ""
    private var menuLabel: String = ""

    private fun setHeader() {
        compose.setContent {
            projectsTab = stringResource(R.string.library_tab_projects, PROJECT_COUNT)
            menuLabel = stringResource(UiR.string.ui_app_menu_a11y)
            FitFaceTheme(darkTheme = true) {
                LibraryHeader(
                    page = page,
                    state = LibraryUiState(isLoadingCatalog = false, previousCrash = crashed),
                    projectCount = PROJECT_COUNT,
                    loading = false,
                    onPage = { page = it },
                    onRefresh = {},
                    onReportProblem = {},
                    onAbout = {},
                    onCheckForUpdate = {},
                )
            }
        }
    }

    /** Where a node sits down the header, in whole pixels. */
    private fun topOfTab(): Int =
        compose.onNodeWithText(projectsTab).fetchSemanticsNode().positionInRoot.y.toInt()

    private fun topOfMenuAction(): Int = menuNode(menuLabel).positionInRoot.y.toInt()

    private fun menuNode(label: String) =
        compose.onNodeWithContentDescription(label).fetchSemanticsNode()

    private fun onPage(target: LibraryPage) {
        page = target
        compose.waitForIdle()
    }

    /** The reported bug: the control you tapped is not where it was when you tapped it. */
    @Test
    fun theTabsStayPutWhenThePageChanges() {
        setHeader()
        val onWatchFaces = topOfTab()
        onPage(LibraryPage.Projects)
        assertEquals(
            "the tab row moved when the page changed, so switching tabs moves the tabs",
            onWatchFaces,
            topOfTab(),
        )
    }

    /**
     * The other half of the same shift. REFRESH is a `TextButton` and carries the 48dp
     * minimum touch target with it; on the page without one the row used to shrink to its
     * headline, taking the report action up with it.
     */
    @Test
    fun theMenuActionStaysPutWhenRefreshGoesAway() {
        setHeader()
        val withRefresh = topOfMenuAction()
        onPage(LibraryPage.Projects)
        assertEquals(
            "the menu action moved when REFRESH went away",
            withRefresh,
            topOfMenuAction(),
        )
    }

    /**
     * A crash in the previous run changes the menu's colour and its first entry's wording,
     * and nothing else. It used to swap in a differently-styled `TextButton`, so the control
     * changed typeface and width depending on whether the last run died — which moved
     * everything beside it. Held by a comment until now.
     */
    @Test
    fun theCrashAffordanceMovesNothing() {
        setHeader()
        val plain = menuNode(menuLabel)
        val plainSize = plain.size
        val plainPosition = plain.positionInRoot

        crashed = true
        compose.waitForIdle()
        val crashedNode = menuNode(CRASH_LABEL)

        assertEquals("the crash case resized the menu action", plainSize, crashedNode.size)
        assertEquals(
            "the crash case moved the menu action",
            plainPosition,
            crashedNode.positionInRoot,
        )
    }

    private companion object {
        const val PROJECT_COUNT = 3

        /** `library_previous_crash`, which becomes the menu's label after a crash. */
        const val CRASH_LABEL = "Report crash"
    }
}
