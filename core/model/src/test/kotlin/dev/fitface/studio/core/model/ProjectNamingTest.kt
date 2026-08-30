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
    fun copyingAProjectThatAlreadyEndsInACounterContinuesTheSameSeries() {
        // Duplication is what feeds this an existing *project* name rather than a face's.
        // Numbering "Aurora 2" as a stem of its own produced "Aurora 2 2", and a second copy
        // "Aurora 2 3" — a parallel series sitting next to the real one.
        assertEquals(
            "Aurora 3",
            ProjectNaming.defaultName("Aurora 2", listOf("Aurora", "Aurora 2")),
        )
        assertEquals(
            "Aurora 4",
            ProjectNaming.defaultName("Aurora 2", listOf("Aurora", "Aurora 2", "Aurora 3")),
        )
    }

    @Test
    fun aFreeNameEndingInACounterIsKeptRatherThanPromoted() {
        // Only a *taken* base is re-stemmed. Otherwise duplicating "Aurora 2" onto a face
        // with no "Aurora 2" would hand the copy the name "Aurora", which nobody asked for.
        assertEquals("Aurora 2", ProjectNaming.defaultName("Aurora 2", listOf("Aurora")))
    }

    @Test
    fun aNameThatIsOnlyDigitsIsNotStemmedToNothing() {
        assertEquals("2 2", ProjectNaming.defaultName("2", listOf("2")))
    }

    @Test
    fun namingTwentyProjectsOnOneFaceNeverRepeats() {
        val taken = mutableListOf<String>()
        repeat(20) { taken += ProjectNaming.defaultName("Aurora", taken) }
        assertEquals(20, taken.toSet().size)
        assertTrue(taken.none(String::isBlank))
    }

    @Test
    fun duplicatingTheNewestCopyTwentyTimesNeverRepeatsOrCompounds() {
        // What someone actually does: copy a project, then copy the copy. Every name has to
        // stay on the one series, so none of them may pick up a second counter.
        val taken = mutableListOf("Aurora")
        var latest = "Aurora"
        repeat(20) {
            latest = ProjectNaming.defaultName(latest, taken)
            taken += latest
        }
        assertEquals(21, taken.toSet().size)
        assertTrue("compounded: $taken", taken.none { Regex(""".*\s\d+\s\d+$""").matches(it) })
        assertEquals("Aurora 21", latest)
    }
}
