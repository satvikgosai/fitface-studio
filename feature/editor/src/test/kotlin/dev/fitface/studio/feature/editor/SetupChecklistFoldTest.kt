package dev.fitface.studio.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which setup steps the Install checklist draws as their own rows. */
class SetupChecklistFoldTest {
    private fun shown(phoneReady: Boolean, opened: Boolean) =
        (0..3).filter { setupStepShown(it, phoneReady, opened) }

    @Test
    fun stepsOneAndTwoFoldOnlyWhileThePhoneIsReadyAndTheRowIsClosed() {
        assertEquals("first visit: every step", listOf(0, 1, 2, 3), shown(phoneReady = false, opened = false))
        assertEquals("ready: only the steps that repeat", listOf(2, 3), shown(phoneReady = true, opened = false))
        assertEquals("ready and opened: all four again", listOf(0, 1, 2, 3), shown(phoneReady = true, opened = true))
    }
}
