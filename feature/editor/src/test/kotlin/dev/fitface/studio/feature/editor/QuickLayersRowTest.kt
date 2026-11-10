package dev.fitface.studio.feature.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Layers page's second row is ALL, two add buttons and `›`, but it must keep page 1's seven
 * columns so `›` does not move on a swipe. Checked at a narrow and a wide phone's tray width.
 */
class QuickLayersRowTest {
    @Test
    fun theSecondRowFillsTheTrayAndEndsWherePageOnesDoes() {
        listOf(288.dp, 328.dp, 380.dp).forEach { width ->
            val gap = TraySecondRowGap
            val column = trayColumn(width, gap, 7)
            val add = addButtonWidth(column, gap)
            // ALL, add, add, › and the three gaps between them.
            assertEquals(width.value, (column * 2 + add * 2 + gap * 3).value, .01f)
            // `›` starts where page 1's seventh button does.
            assertEquals(((column + gap) * 6).value, (column + add * 2 + gap * 3).value, .01f)
        }
    }
}
