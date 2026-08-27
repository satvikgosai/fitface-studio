package dev.fitface.studio.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.CatalogSort
import dev.fitface.studio.core.model.ProjectSort
import dev.fitface.studio.core.ui.FitFaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sort chips must not move when the row is used.
 *
 * Two ways they did. Reversing the selected sort reworded its chip — "Edited last" became
 * "Edited first" — and the chip resized, shoving every chip after it sideways under the
 * finger that had just tapped it. And the two pages worded the same three chips differently,
 * so switching tabs moved the whole row as well.
 *
 * Both are fixed by the wording rather than by the layout: the pages share one set of labels,
 * and both labels of a direction pair are the same number of characters. `labelMedium` is
 * `FontFamily.Monospace`, so equal length is equal width. These assertions are what stop a
 * later edit — or a translation — from quietly reintroducing the shove.
 *
 * Positions and sizes, not text, for the reason `FitTopBarLayoutTest` gives: Robolectric's
 * font metrics are not the device's. Comparing two strings measured the same way is sound
 * even when the absolute numbers are not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class SortChipLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private var reversed by mutableStateOf(false)
    private var selected by mutableStateOf(CatalogSort.RECENT)

    @Test
    fun reversingASortDoesNotShoveTheChipsAfterIt() {
        // The complaint exactly: tapping the selected chip reworded it, the chip resized,
        // and every chip to its right jumped sideways under the finger that had just
        // tapped. Measuring the *neighbours* is what makes this test real — the selected
        // chip is first in the row, so its own left edge cannot move whatever it does.
        val labels = mutableMapOf<Pair<CatalogSort, Boolean>, String>()
        compose.setContent {
            for (option in CatalogSort.entries) {
                labels[option to false] = catalogSortLabel(option, false)
                labels[option to true] = catalogSortLabel(option, true)
            }
            FitFaceTheme(darkTheme = true) {
                Box(Modifier.width(360.dp)) {
                    LibrarySortRow(
                        options = CatalogSort.entries,
                        selected = selected,
                        reversed = reversed,
                        enabled = true,
                        label = { entry, flip -> catalogSortLabel(entry, flip) },
                        onSelect = {},
                        onReverse = {},
                    )
                }
            }
        }

        for (option in CatalogSort.entries) {
            // Every chip that is not the one being reversed keeps its wording, so each is
            // findable by the same string before and after.
            val neighbours = CatalogSort.entries.filter { it != option }
                .map { labels.getValue(it to false) }

            selected = option
            reversed = false
            compose.waitForIdle()
            val before = neighbours.map {
                compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.x
            }

            reversed = true
            compose.waitForIdle()
            val after = neighbours.map {
                compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.x
            }

            assertEquals("chips beside a reversed $option", before, after)
        }
    }

    @Test
    fun theTwoPagesWordTheSameChipsIdentically() {
        // Switching tabs must not reword a chip that means the same kind of thing, or the
        // whole row steps sideways as the page changes.
        var catalogue = emptyList<String>()
        var projects = emptyList<String>()
        compose.setContent {
            catalogue = CatalogSort.entries.flatMap { option ->
                listOf(catalogSortLabel(option, false), catalogSortLabel(option, true))
            }
            projects = ProjectSort.entries.flatMap { option ->
                listOf(projectSortLabel(option, false), projectSortLabel(option, true))
            }
        }
        assertEquals(catalogue, projects)
    }

    @Test
    fun everyDirectionPairIsTheSameLengthInEveryLabel() {
        // The property the monospace font turns into equal width. Stated separately from the
        // measurement above so a translation that breaks it fails with the reason, not with
        // a pixel count.
        var pairs = emptyList<Pair<String, String>>()
        compose.setContent {
            pairs = CatalogSort.entries.map { option ->
                catalogSortLabel(option, false) to catalogSortLabel(option, true)
            }
        }
        for ((natural, flipped) in pairs) {
            assertEquals("\"$natural\" and \"$flipped\"", natural.length, flipped.length)
        }
    }
}
