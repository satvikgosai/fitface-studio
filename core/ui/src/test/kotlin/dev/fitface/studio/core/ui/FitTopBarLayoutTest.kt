package dev.fitface.studio.core.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The top bar's actions slot is a width budget, and the title column pays for anything put
 * in it.
 *
 * `FitTopBar` gives its title/subtitle `Column` a `weight(1f)`, so it is the flexible child:
 * every dp an action takes comes out of the title. "Report a problem" went in as a full-label
 * `TextButton` — 109dp of a 360dp bar, wider than the title beside it — and the subtitle was
 * silently ellipsized on every editor page. `face 00112 · style0` rendered as
 * `face 00112 · styl…` and `reparse of the edited container` as `reparse of the edited
 * conta…`, with no exception, no lint warning and nothing in any test.
 *
 * **These assertions are deliberately about geometry, not about text.** The obvious test —
 * "assert the subtitle is not ellipsized" — cannot be trusted here: Robolectric's font
 * metrics are not the device's, and measurably so. In this harness the very subtitle that
 * clipped on a real phone measures as fitting, and the offending `TextButton` measures 125dp
 * where the device gave it 109dp. A truncation assertion would therefore pass while the
 * device still clipped. What *is* exactly reproducible is the layout arithmetic: an action's
 * declared size, and the position that gives the title column. So the rule enforced here is
 * the one that actually prevents the bug — **an action in this bar is icon-sized** — and the
 * title's share is derived from positions rather than from glyph widths.
 *
 * Native graphics mode is on because the default stub metrics collapse every string to a few
 * pixels, which would make the title column look enormous no matter what was beside it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class FitTopBarLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** Captured from the composition so the test cannot drift from the string resource. */
    private var menuLabel: String = ""

    private fun setBar(actions: @Composable RowScope.() -> Unit) {
        compose.setContent {
            menuLabel = stringResource(R.string.ui_app_menu_a11y)
            FitFaceTheme(darkTheme = true) {
                FitTopBar(title = TITLE, subtitle = SUBTITLE, onBack = {}, actions = actions)
            }
        }
    }

    private fun Int.px() = with(compose.density) { toDp() }

    /** Width of the action, found the way a screen reader would find it. */
    private fun menuActionWidth(): Dp =
        compose.onNodeWithContentDescription(menuLabel).fetchSemanticsNode().size.width.px()

    /**
     * How much room the title column was given: from the title's left edge to the left edge
     * of whichever action sits furthest left. Derived from positions, so no glyph width
     * enters the number.
     */
    private fun titleColumnWidth(actionLabels: List<String>): Dp {
        val titleLeft = compose.onNodeWithText(TITLE)
            .fetchSemanticsNode().positionInRoot.x
        val firstActionLeft = actionLabels.minOf { label ->
            compose.onNodeWithContentDescription(label).fetchSemanticsNode().positionInRoot.x
        }
        return (firstActionLeft - titleLeft).toInt().px()
    }

    /** An action in this bar is icon-sized. 44dp allows the 38dp square plus its border. */
    @Test
    fun theMenuActionStaysInsideItsBudget() {
        setBar { appMenu() }
        val action = menuActionWidth()
        assertTrue(
            "the app menu action is $action wide, over the $ACTION_BUDGET budget",
            action <= ACTION_BUDGET,
        )
    }

    /**
     * The reported bug, stated as a number. On a 360dp bar, 16dp of padding either side, a
     * 38dp back button and one 38dp action with 12dp gaps leave the title 228dp; the floor is
     * set below that so the test fails on a regression rather than on a rounding change.
     */
    @Test
    fun theTitleKeepsTheMajorityOfTheBar() {
        setBar { appMenu() }
        val column = titleColumnWidth(listOf(menuLabel))
        assertTrue(
            "the title column got $column of $PHONE_WIDTH; the actions are eating the title",
            column >= MINIMUM_TITLE_WIDTH,
        )
    }

    /**
     * The Canvas page is the worst case: the app menu, the EDITED badge and the `⋯`
     * overflow all at once. This combination is what left roughly 100dp for the face name, and
     * it is the tightest bar in the app — the floor here is correspondingly lower, and it is
     * the configuration to check on a device before believing this fix.
     */
    @Test
    fun theWorstCaseCanvasBarStillLeavesRoomForTheTitle() {
        setBar {
            Text("EDITED", style = FitFaceType.micro)
            FitIconButton(glyph = "⋯", contentDescription = OVERFLOW_LABEL, onClick = {})
            appMenu()
        }
        val column = titleColumnWidth(listOf(menuLabel, OVERFLOW_LABEL))
        assertTrue(
            "with a badge and an overflow present the title column got only $column",
            column >= MINIMUM_TITLE_WIDTH_CROWDED,
        )
    }

    /** Both bar actions are the same square, so neither may drift from the other. */
    @Test
    fun everyActionInTheBarIsTheSameSize() {
        setBar {
            FitIconButton(glyph = "⋯", contentDescription = OVERFLOW_LABEL, onClick = {})
            appMenu()
        }
        val overflow = compose.onNodeWithContentDescription(OVERFLOW_LABEL)
            .fetchSemanticsNode().size.width.px()
        val back = compose.onNodeWithContentDescription(BACK_LABEL)
            .fetchSemanticsNode().size.width.px()
        assertTrue(
            "actions disagree on width: back=$back overflow=$overflow " +
                "menu=${menuActionWidth()}",
            back == overflow && overflow == menuActionWidth(),
        )
    }

    /** The real control, with the callbacks a bar would give it. */
    @Composable
    private fun appMenu() {
        AppMenuAction(onReportProblem = {}, onAbout = {}, onCheckForUpdate = {})
    }

    /**
     * The whole reason a menu was allowed into this bar at all.
     *
     * A `DropdownMenu` is a `Popup` — its own window, measured outside this composition —
     * so opening it must not move anything in the `Row`. If someone ever replaces it with
     * an inline column, this is the test that notices: the bar would grow by the width of
     * the widest entry and the title would go back to being ellipsized.
     */
    @Test
    fun theOpenMenuCostsTheBarNoWidth() {
        setBar { appMenu() }
        val closedAction = menuActionWidth()
        val closedTitle = titleColumnWidth(listOf(menuLabel))

        compose.onNodeWithContentDescription(menuLabel).performClick()
        compose.waitForIdle()

        assertTrue(
            "the menu is open and the entries are not in the popup: " +
                "action $closedAction -> ${menuActionWidth()}",
            closedAction == menuActionWidth(),
        )
        assertTrue(
            "opening the menu took $closedTitle down to ${titleColumnWidth(listOf(menuLabel))}",
            closedTitle == titleColumnWidth(listOf(menuLabel)),
        )
    }

    private companion object {
        /** The narrowest width the app targets, and what the audit measured on. */
        val PHONE_WIDTH = 360.dp
        val ACTION_BUDGET = 44.dp
        val MINIMUM_TITLE_WIDTH = 200.dp

        /** Canvas carries two actions and a badge; 130dp still clears its 19-char subtitle. */
        val MINIMUM_TITLE_WIDTH_CROWDED = 130.dp

        const val TITLE = "Black or white"

        /** The longest subtitle the editor actually produces, from the Validate page. */
        const val SUBTITLE = "reparse of the edited container"

        const val OVERFLOW_LABEL = "Project"
        const val BACK_LABEL = "Back"
    }
}
