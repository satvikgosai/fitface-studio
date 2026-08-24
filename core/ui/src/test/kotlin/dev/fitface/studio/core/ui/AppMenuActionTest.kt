package dev.fitface.studio.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The menu behind the one action either top bar carries.
 *
 * Two properties are worth holding. The entries must live in a popup rather than in the
 * bar — `FitTopBarLayoutTest` measures the consequence, this measures the cause. And each
 * entry must close the menu *before* it runs, because every one of them opens a dialog and
 * a popup left standing behind a dialog is the obvious first bug in a construction like
 * this.
 *
 * Native graphics mode for the reason `FitTopBarLayoutTest` explains: the default stub
 * font metrics collapse every string to a few pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class AppMenuActionTest {

    @get:Rule
    val compose = createComposeRule()

    private var menuLabel = ""
    private var reportLabel = ""
    private var aboutLabel = ""
    private var updateLabel = ""
    private val tapped = mutableListOf<String>()
    private var crashed by mutableStateOf(false)

    private fun setMenu(content: @Composable () -> Unit) {
        compose.setContent {
            menuLabel = stringResource(R.string.ui_app_menu_a11y)
            reportLabel = stringResource(R.string.ui_diagnostics_action)
            aboutLabel = stringResource(R.string.ui_app_menu_about)
            updateLabel = stringResource(R.string.ui_app_menu_update)
            FitFaceTheme(darkTheme = true) { content() }
        }
    }

    /** One composition that can show either variant, because `setContent` runs once. */
    private fun setSwitchableMenu() = setMenu {
        val warning = MaterialTheme.fitColors.warning
        if (crashed) {
            AppMenuAction(
                onReportProblem = { tapped += "report" },
                onAbout = {},
                onCheckForUpdate = {},
                tint = warning,
                contentDescription = CRASH_LABEL,
                reportLabel = CRASH_LABEL,
                reportTint = warning,
            )
        } else {
            AppMenuAction(
                onReportProblem = { tapped += "report" },
                onAbout = {},
                onCheckForUpdate = {},
            )
        }
    }

    private fun setDefaultMenu() = setMenu {
        AppMenuAction(
            onReportProblem = { tapped += "report" },
            onAbout = { tapped += "about" },
            onCheckForUpdate = { tapped += "update" },
        )
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(menuLabel).performClick()
        compose.waitForIdle()
    }

    private fun entryExists(label: String) =
        compose.onAllNodesWithTextOrEmpty(label).isNotEmpty()

    @Test
    fun theEntriesDoNotExistUntilTheMenuIsOpened() {
        setDefaultMenu()

        assertTrue("the entries are in the bar, not in a popup", !entryExists(reportLabel))

        openMenu()

        compose.onNodeWithText(reportLabel).assertIsDisplayed()
        compose.onNodeWithText(aboutLabel).assertIsDisplayed()
        compose.onNodeWithText(updateLabel).assertIsDisplayed()
    }

    @Test
    fun eachEntryFiresItsOwnCallbackAndClosesTheMenu() {
        setDefaultMenu()

        listOf(reportLabel to "report", aboutLabel to "about", updateLabel to "update")
            .forEach { (label, expected) ->
                tapped.clear()
                openMenu()
                compose.onNodeWithText(label).performClick()
                compose.waitForIdle()

                assertEquals("tapping $label", listOf(expected), tapped)
                assertTrue(
                    "the menu is still open behind the dialog $label would have opened",
                    !entryExists(label),
                )
            }
    }

    /**
     * A crash in the previous run changes the colour and the wording, never the size or the
     * shape. The old crash branch swapped in a differently-styled `TextButton`, so the
     * control changed typeface and width depending on whether the last run died.
     */
    @Test
    fun theCrashAffordanceChangesTheLabelAndNotTheGeometry() {
        setSwitchableMenu()
        val plain = compose.onNodeWithContentDescription(menuLabel).fetchSemanticsNode()
        val plainSize = plain.size
        val plainPosition = plain.positionInRoot

        crashed = true
        compose.waitForIdle()
        val crashedNode = compose.onNodeWithContentDescription(CRASH_LABEL).fetchSemanticsNode()

        assertEquals("the crash case resized the control", plainSize, crashedNode.size)
        assertEquals("the crash case moved the control", plainPosition, crashedNode.positionInRoot)

        // The wording has to survive the extra tap: an amber glyph on its own cannot say
        // what it is amber about.
        compose.onNodeWithContentDescription(CRASH_LABEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(CRASH_LABEL).assertIsDisplayed()
    }

    /** The bar's own glyph label must not leak into the menu as an entry. */
    @Test
    fun theBarLabelIsNotOneOfTheEntries() {
        setDefaultMenu()
        openMenu()

        assertTrue(entryExists(reportLabel))
        assertTrue("\"$menuLabel\" is showing as a menu entry", !entryExists(menuLabel))
    }

    private companion object {
        const val CRASH_LABEL = "Report crash"
    }
}

/** `onNodeWithText` throws when nothing matches; this answers the question instead. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextOrEmpty(
    text: String,
) = onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes()
