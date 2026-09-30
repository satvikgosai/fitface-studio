package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure decisions behind export and import: what the file is called, and what opens
 * the door to writing one at all.
 *
 * Both are here rather than tested through a screen because both are exactly the kind of
 * thing a screen makes untestable. The gate has no visible state to assert on by design —
 * the only feedback is a control appearing — and the file name is handed to a system picker
 * this harness cannot see.
 */
class ProjectTransferTest {
    /**
     * The phrase under test, assembled rather than written out — the point of comparing a
     * digest is that the token is not greppable, and a literal here would be.
     */
    private val Phrase = "quartz" + "ite"

    /**
     * The phrase, in the shapes a keyboard produces.
     *
     * Built from [Phrase] rather than written out, so this file does not put back the
     * plaintext the digest exists to keep out of the binary. A test source is not compiled
     * into the APK, but it is in the same repository, and a grep that finds it here has
     * found it.
     */
    @Test
    fun theExactPhraseOpensTheTools() {
        assertTrue(DeveloperGate.isUnlockPhrase(Phrase))
        // Trimmed, because a soft keyboard's space suggestion lands after the last word.
        assertTrue(DeveloperGate.isUnlockPhrase("  $Phrase "))
        // Case-insensitive, because a keyboard that autocapitalises must not be the
        // difference between opening the tools and filtering the list to nothing.
        assertTrue(DeveloperGate.isUnlockPhrase(Phrase.uppercase()))
        assertTrue(DeveloperGate.isUnlockPhrase(Phrase.replaceFirstChar(Char::uppercase)))
    }

    /**
     * Every prefix on the way to typing it, and every plausible near miss.
     *
     * A `contains` or a `startsWith` here would fire mid-word — the field reports every
     * keystroke — so the tools would open and then close again as the phrase was finished,
     * and a project someone had named after this app would toggle them on every search.
     */
    @Test
    fun nothingElseOpensThem() {
        val misses = Phrase.indices.map(Phrase::take) +
            listOf(
                " ",
                Phrase + "s",
                "x" + Phrase,
                Phrase.dropLast(1) + "z",
                Phrase.reversed(),
                "fit3dev",
                "fitface",
                "debug",
                "aurora",
                "00046",
                "FitFace Studio",
            )
        misses.forEach { candidate ->
            assertFalse("\"$candidate\" opened the tools", DeveloperGate.isUnlockPhrase(candidate))
        }
    }

    /**
     * Blank input never matches, whatever the constant happens to be.
     *
     * The digest path has one failure mode a string comparison does not: hash the query
     * unconditionally and an empty field is a candidate like any other, checked on every
     * keystroke that clears the box.
     */
    @Test
    fun blankInputNeverMatches() {
        assertFalse(DeveloperGate.isUnlockPhrase(""))
        assertFalse(DeveloperGate.isUnlockPhrase("   "))
        assertFalse(DeveloperGate.isUnlockPhrase("\n\t"))
    }

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
