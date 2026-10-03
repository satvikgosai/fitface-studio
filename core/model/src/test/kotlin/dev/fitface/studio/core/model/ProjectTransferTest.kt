package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an exported archive is called.
 *
 * Tested here rather than through a screen because the name is handed to a system picker
 * this harness cannot see.
 */
class ProjectTransferTest {
    /** The face prefix keeps the package's convention; the slug is what tells two apart. */
    @Test
    fun anArchiveIsNamedForItsFaceAndItsProject() {
        assertEquals(
            "SM-R390_00046_Aurora-2.zip",
            ProjectArchiveNaming.fileName("00046", "Aurora 2"),
        )
        assertEquals(
            "SM-R390_00001_Midnight.zip",
            ProjectArchiveNaming.fileName("00001", "Midnight"),
        )
    }

    /**
     * A name of nothing but punctuation still produces a usable file name.
     *
     * The archive is going somewhere this app does not control — a share sheet, a cloud
     * folder, a Windows machine — so the slug is ASCII or it is absent. It is cosmetic
     * either way: nothing on import reads it, `fitface/project.json` carries the real name.
     */
    @Test
    fun aNameThatSlugifiesToNothingLeavesJustTheFace() {
        assertEquals("SM-R390_00046.zip", ProjectArchiveNaming.fileName("00046", "···"))
        assertEquals("SM-R390_00046.zip", ProjectArchiveNaming.fileName("00046", "   "))
        assertEquals("SM-R390_00046.zip", ProjectArchiveNaming.fileName("00046", ""))
    }

    /** Non-Latin names are common and must not produce a name ending in a stray dash. */
    @Test
    fun aNameIsNeverLeftWithATrailingOrDoubledSeparator() {
        listOf("표준 시계", "Aurora  ·  2", "-Aurora-", "a/b\\c:d", "時計").forEach { name ->
            val file = ProjectArchiveNaming.fileName("00046", name)
            assertFalse("$name -> $file", file.contains("--"))
            assertFalse("$name -> $file", file.contains("_.zip"))
            assertFalse("$name -> $file", file.contains("-.zip"))
            assertTrue("$name -> $file", file.endsWith(".zip"))
            assertTrue("$name -> $file", file.startsWith("SM-R390_00046"))
        }
    }

    /** A very long project name cannot produce a file name a filesystem refuses. */
    @Test
    fun aLongNameIsCut() {
        val file = ProjectArchiveNaming.fileName("00046", "A".repeat(400))
        assertTrue("$file is ${file.length} characters", file.length < 80)
    }

    /**
     * The import picker asks for more than `application/zip`.
     *
     * Some providers hand a zip over as `application/octet-stream` — a file that arrived by
     * chat or sits on a USB volume routinely does — and a picker that filters to the exact
     * type greys out the file the reader is looking straight at.
     */
    @Test
    fun theImportPickerAcceptsMoreThanTheExactType() {
        assertTrue(ProjectArchiveNaming.ImportMimeTypes.contains(ProjectArchiveNaming.MimeType))
        assertTrue(ProjectArchiveNaming.ImportMimeTypes.contains("application/octet-stream"))
    }
}
