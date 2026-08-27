package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectNamingTest {
    @Test
    fun theFirstProjectOnAFaceIsJustTheFaceName() {
        assertEquals("Aurora", ProjectNaming.defaultName("Aurora", emptyList()))
    }

    @Test
    fun theFileExtensionIsNotPartOfAName() {
        // `displayName` is "${face.name}.apk" — a filename, and the fallback when the
        // package carries no label of its own.
        assertEquals("Aurora", ProjectNaming.defaultName("Aurora.apk", emptyList()))
    }

    @Test
    fun aSecondProjectOnTheSameFaceIsNumbered() {
        assertEquals("Aurora 2", ProjectNaming.defaultName("Aurora", listOf("Aurora")))
    }

    @Test
    fun theCounterStepsOverNamesAlreadyInUseRatherThanFillingGaps() {
        // "Aurora 2" was renamed away, but handing its number to different work would put a
        // name someone has already seen on something else.
        assertEquals(
            "Aurora 4",
            ProjectNaming.defaultName("Aurora", listOf("Aurora", "Aurora 2", "Aurora 3")),
        )
    }

    @Test
    fun aGapBelowTheHighestNumberIsStillTaken() {
        assertEquals(
            "Aurora 3",
            ProjectNaming.defaultName("Aurora", listOf("Aurora", "Aurora 2", "Aurora 9")),
        )
    }

    @Test
    fun namesThatDifferOnlyByCaseAreTwoNames() {
        // Folding them together would mean silently renaming what someone typed.
        assertEquals("Aurora", ProjectNaming.defaultName("Aurora", listOf("aurora")))
    }

    @Test
    fun anUnrelatedNameOnTheSameFaceDoesNotForceANumber() {
        assertEquals("Aurora", ProjectNaming.defaultName("Aurora", listOf("Night shift")))
    }

    @Test
    fun surroundingSpaceIsNotPartOfAName() {
        assertEquals("Aurora", ProjectNaming.defaultName("  Aurora.apk  ", emptyList()))
    }

    @Test
    fun aRenamedProjectStillBlocksItsOwnNumber() {
        val taken = listOf("Night shift", "Aurora 2")
        val chosen = ProjectNaming.defaultName("Aurora", taken)
        assertEquals("Aurora", chosen)
        assertFalse(chosen in taken)
    }

    @Test
    fun namingTwentyProjectsOnOneFaceNeverRepeats() {
        val taken = mutableListOf<String>()
        repeat(20) { taken += ProjectNaming.defaultName("Aurora", taken) }
        assertEquals(20, taken.toSet().size)
        assertTrue(taken.none(String::isBlank))
    }
}
