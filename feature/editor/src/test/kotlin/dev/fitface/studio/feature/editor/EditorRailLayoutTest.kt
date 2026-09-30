package dev.fitface.studio.feature.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail splits the bottom of the editor five equal ways, so each label's width is a
 * fifth of the phone and nothing negotiates for more.
 *
 * `BACKGROUND` overflowed that budget by about 3dp on a 339dp window and rendered as
 * `BACKGROUN` — clipped mid-word, with no ellipsis, on every phone, for as long as the rail
 * existed. Nothing caught it because `:feature:editor` cannot run Robolectric (its merged
 * manifest declares a receiver from the accessory SDK jar, whose pre-stackmap bytecode
 * fails the JVM verifier), so no test in this module can measure a composable.
 *
 * The answer is the one `SortChipLayoutTest` already uses in the library: the label is
 * monospace, so its width is a character count, and a character count is something
 * arithmetic can decide here and a person can check against a device.
 */
class EditorRailLayoutTest {

    /** The English labels, in rail order. They are the page titles. */
    private val labels = listOf("CANVAS", "WIDGETS", "BACKGROUND", "STYLES", "INSTALL")

    @Test
    fun everyRailLabelFitsTheNarrowestSupportedWindow() {
        val budget = railLabelCharacterBudget()
        labels.forEach { label ->
            assertTrue(
                "\"$label\" is ${label.length} characters and the rail allows $budget at " +
                    "$NarrowestSupportedWidth — it will be clipped",
                label.length <= budget,
            )
        }
    }

    @Test
    fun theRailIsSizedForTheDestinationsItActuallyHas() {
        assertEquals(RailDestinationCount, labels.size)
    }

    /**
     * The regression itself. `micro`'s 1.3sp tracking is what took `BACKGROUND` over the
     * line, so the rail label drops it — and this asserts the tracking would still break
     * it, which is the only reason to believe the fix is the thing that fixed it.
     */
    @Test
    fun theTrackingMicroLabelsCarryIsWhatOverflowedTheLongestLabel() {
        val withTracking = railLabelCharacterBudget(
            // 1.3sp of tracking on a 9.5sp monospace face: 5.7 + 1.3 = 7.0dp a character
            // against the 5.7 the rail label costs now. Expressed as the width that would
            // buy the same budget, so the sum stays in one place.
            railWidth = NarrowestSupportedWidth * (5.7f / 7.0f),
        )
        assertTrue(
            "BACKGROUND fitting with micro's tracking would mean this test proves nothing",
            "BACKGROUND".length > withTracking,
        )
    }

    /**
     * A sixth destination is not free. The budget is a fifth of the bar, and adding one
     * without re-checking is how the longest label gets clipped again — which is why
     * `EditorRail` asserts the count rather than trusting whoever adds the next page.
     */
    @Test
    fun aSixthDestinationWouldNotFitTheLongestLabel() {
        assertTrue(
            "BACKGROUND".length > railLabelCharacterBudget(
                railWidth = NarrowestSupportedWidth,
                destinations = RailDestinationCount + 1,
            ),
        )
    }

    /**
     * The other axis. A landscape phone leaves the vertical rail about 260dp, and five
     * items at the bar's own spacing want more than that — so `SEND` was off the bottom of
     * the screen with nothing to scroll and nothing to say it was there.
     *
     * The column scrolls now, so nothing can be unreachable; this asserts it does not
     * normally have to, because a navigation rail that scrolls on every landscape phone is
     * a rail whose last destination people will not find.
     */
    @Test
    fun theVerticalRailFitsALandscapePhoneWithoutScrolling() {
        assertTrue(
            "the vertical rail wants ${verticalRailNaturalHeight()} of $LandscapeRailHeight",
            verticalRailNaturalHeight() <= LandscapeRailHeight,
        )
    }

    /** A sixth destination does not fit this axis either, with no room left to take. */
    @Test
    fun aSixthDestinationWouldNotFitTheVerticalRail() {
        assertTrue(
            verticalRailNaturalHeight(destinations = RailDestinationCount + 1) >
                LandscapeRailHeight,
        )
    }

    /** A wider phone is only ever more room, never less. */
    @Test
    fun aWiderWindowNeverShrinksTheBudget() {
        assertTrue(
            railLabelCharacterBudget(railWidth = 411.dp) >= railLabelCharacterBudget(),
        )
    }
}
