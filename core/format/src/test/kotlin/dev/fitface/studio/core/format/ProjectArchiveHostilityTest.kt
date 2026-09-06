package dev.fitface.studio.core.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an archive from somewhere else may not do.
 *
 * An import reads a zip a stranger could have written, which is a different threat from
 * anything else this app opens: a face package comes from the store over a pinned host list,
 * and a picked image goes through the platform decoder. So the classic zip attacks are worth
 * naming and pinning rather than reasoning about.
 *
 * The strongest defence is structural and is asserted in [noEntryNameIsEverUsedAsAPath]:
 * **no entry name from an archive ever becomes a filesystem path.** An import writes three
 * fixed names into a directory named by a freshly-inserted row id, and the style previews are
 * named from an integer a `\d{1,3}` capture produced. There is nothing for `../` to traverse,
 * no name to collide with another project's file, and no symlink to follow, because nothing
 * is ever extracted *by name at all*. Every other check here is depth behind that.
 */
class ProjectArchiveHostilityTest {

    private fun zip(build: ZipOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { out -> ZipOutputStream(out).use(build) }.toByteArray()

    private fun ZipOutputStream.member(name: String, payload: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(payload)
        closeEntry()
    }

    /** Exactly as long as [ProjectArchive.ManifestEntry], so [renamed] moves no offset. */
    private val DecoyName = "fitface/project.jsoX"

    private fun manifest(faceId: String = "00046") =
        """{"schema":1,"projectName":"A","faceId":"$faceId","displayName":"a.apk"}"""

    private fun refusal(archive: ByteArray): ProjectArchiveException {
        val error = runCatching { ProjectArchive.read(archive) }.exceptionOrNull()
        assertTrue("expected a refusal, got $error", error is ProjectArchiveException)
        return error as ProjectArchiveException
    }

    /**
     * A zip bomb is refused while it inflates, not after.
     *
     * 64 MiB of zeroes deflates to about 64 KiB, so nothing about the file's size says what
     * it will become. `readBytes()` would allocate the lot — and `OutOfMemoryError` is an
     * `Error`, which the `catch (Exception)` around the loop would not have caught, so the
     * process would go down rather than the import failing.
     */
    @Test
    fun aSidecarMemberThatInflatesPastTheCeilingIsRefused() {
        val bomb = zip {
            member(ProjectArchive.ManifestEntry, manifest().encodeToByteArray())
            member(ProjectArchive.EditedEntry, ByteArray(64 * 1024 * 1024))
        }
        assertTrue(
            "a 64 MiB payload compressed to ${bomb.size} bytes and was not refused",
            bomb.size < 1024 * 1024,
        )

        assertTrue(refusal(bomb).message!!.contains("inflates past"))
    }

    /** The budget is shared across the sidecar, so two members cannot each spend it. */
    @Test
    fun theInflationCeilingIsSharedAcrossTheSidecar() {
        val nine = 9 * 1024 * 1024
        val bomb = zip {
            member(ProjectArchive.ManifestEntry, manifest().encodeToByteArray())
            member(ProjectArchive.EditedEntry, ByteArray(nine))
            member(ProjectArchive.SessionEntry, ByteArray(nine))
        }

        assertTrue(refusal(bomb).message!!.contains("inflates past"))
    }

    /**
     * A repeated sidecar name is refused rather than resolved.
     *
     * A zip may carry the same name twice, and then "which manifest is the real one" is
     * decided by whichever the reader happens to take. Both first and last are defensible and
     * neither is knowable from the file, so an archive that asks the question is not one to
     * answer — the same reason `Fit3Apk.parse` refuses two containers.
     */
    @Test
    fun aRepeatedSidecarMemberIsRefused() {
        // Built by patching bytes, because `ZipOutputStream` refuses to write a duplicate
        // name at all — which is itself worth knowing: this app cannot produce one, so an
        // archive that carries one was assembled by something else.
        val doubled = renamed(
            zip {
                member(ProjectArchive.ManifestEntry, manifest().encodeToByteArray())
                member(DecoyName, manifest(faceId = "00001").encodeToByteArray())
            },
            from = DecoyName,
            to = ProjectArchive.ManifestEntry,
        )

        assertTrue(refusal(doubled).message!!.contains("more than once"))
    }

    /**
     * Renames every occurrence of one entry name to another of the **same length**, in both
     * the local headers and the central directory.
     *
     * Equal length is what makes this safe to do with a byte replacement: nothing in a zip's
     * offsets moves, so the result is a structurally valid archive that simply names one
     * member twice.
     */
    private fun renamed(archive: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length) { "a rename must not move any offset" }
        val text = archive.toString(Charsets.ISO_8859_1).replace(from, to)
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * A zip declaring tens of thousands of members is refused before it is walked.
     *
     * An empty entry costs about 76 bytes, so a file under the read ceiling can declare
     * roughly 220,000 of them. That is a slow loop rather than a hang, and it is one this has
     * no reason to run: a real archive holds five to eight members.
     */
    @Test
    fun anArchiveOfThousandsOfEmptyMembersIsRefused() {
        val many = zip {
            repeat(5_000) { member("assets/filler-$it", ByteArray(0)) }
            member(ProjectArchive.ManifestEntry, manifest().encodeToByteArray())
        }

        assertTrue(refusal(many).message!!.contains("more than"))
    }

    /**
     * The structural defence, stated as an assertion: nothing an archive names is a path.
     *
     * Traversal, absolute paths, Windows separators, a name that would land on another
     * project's `edited.bin` — all of them are simply not sidecar names, so [ProjectArchive]
     * never reads them and the import never writes them. The archive is still read
     * successfully here, which is the point: these members are inert, not fatal.
     *
     * **On a device the same file is refused outright, and by the platform rather than by
     * this code.** Android's `ZipInputStream` validates entry names and throws
     * `ZipException: Invalid zip entry path` on a `..` segment; the desktop JDK this test
     * runs on does not, which is why the assertion here is "inert" and not "refused".
     * Confirmed on an emulator — importing an archive built with the first name below is
     * reported as *that file is not a readable zip*, with the platform's message carried
     * through into the diagnostics buffer.
     *
     * Both outcomes are safe and neither is relied on. What is relied on is that no name
     * reaches a filesystem call, which is what this asserts on the one of the two platforms
     * where a traversal name can get far enough to be observed at all.
     */
    @Test
    fun noEntryNameIsEverUsedAsAPath() {
        val nasty = listOf(
            "../../../../data/data/dev.fitface.studio/files/projects/1/edited.bin",
            "/etc/passwd",
            "..\\..\\windows\\system32\\config",
            "fitface/../fitface/project.json",
            "./fitface/edited.bin",
            "projects/1/session.json",
        )
        val archive = zip {
            nasty.forEach { member(it, "owned".encodeToByteArray()) }
            member(ProjectArchive.ManifestEntry, manifest().encodeToByteArray())
            member(ProjectArchive.EditedEntry, "real".encodeToByteArray())
        }

        val contents = ProjectArchive.read(archive)

        assertEquals("A", contents.manifest.projectName)
        assertEquals("00046", contents.manifest.faceId)
        // The traversal names did not become the edit. `fitface/../fitface/project.json` is
        // the one worth looking at twice: it *normalises* to the manifest's name, and is
        // ignored anyway, because the comparison is on the literal string.
        assertEquals("real", contents.editedContainer?.decodeToString())
        assertNull(contents.sessionState)
    }

    /** A directory entry named like a sidecar member carries no payload and is skipped. */
    @Test
    fun aDirectoryEntryCannotStandInForASidecarMember() {
        val archive = zip {
            putNextEntry(ZipEntry(ProjectArchive.EditedEntry + "/"))
            closeEntry()
            member(ProjectArchive.ManifestEntry, manifest().encodeToByteArray())
        }

        assertNull(ProjectArchive.read(archive).editedContainer)
    }

    /** Truncated, and truncated mid-member: a torn download is not a project. */
    @Test
    fun aTruncatedArchiveIsRefused() {
        val whole = zip {
            member(ProjectArchive.ManifestEntry, manifest().encodeToByteArray())
            member(ProjectArchive.EditedEntry, ByteArray(4_096) { it.toByte() })
        }

        refusal(whole.copyOf(whole.size / 2))
        refusal(whole.copyOf(24))
        refusal(ByteArray(0))
    }

    /** Not JSON, or JSON of the wrong shape, is a message rather than a crash. */
    @Test
    fun aManifestThatIsNotTheRightShapeIsRefused() {
        listOf(
            "not json at all",
            "[]",
            "{}",
            """{"schema":1}""",
            """{"schema":"one","projectName":"A","faceId":"00046","displayName":"a"}""",
            " ",
        ).forEach { body ->
            val archive = zip { member(ProjectArchive.ManifestEntry, body.encodeToByteArray()) }
            refusal(archive)
        }
    }

    /**
     * A manifest carrying fields this build has never heard of still imports.
     *
     * The opposite risk to the schema check, and both have to hold: an older build must be
     * able to read a file a newer one wrote *at the same schema*, or adding an optional field
     * would strand every archive already in a folder somewhere.
     */
    @Test
    fun unknownManifestFieldsAreIgnored() {
        val archive = zip {
            member(
                ProjectArchive.ManifestEntry,
                """{"schema":1,"projectName":"A","faceId":"00046","displayName":"a.apk",
                   "somethingNew":{"nested":[1,2,3]},"another":"value"}""".encodeToByteArray(),
            )
        }

        assertEquals("A", ProjectArchive.read(archive).manifest.projectName)
    }

    /**
     * An absurd manifest string is carried, not refused, and is the repository's to clamp.
     *
     * Worth pinning where the boundary is: this layer decides whether the *file* is readable,
     * and a long name does not make it unreadable. `ProjectManifest.sanitised` in `:core:data`
     * is what stops it reaching a database row, because that is where it would do harm.
     */
    @Test
    fun aVeryLongManifestNameIsTheRepositorysProblemNotThisLayers() {
        val archive = zip {
            member(
                ProjectArchive.ManifestEntry,
                """{"schema":1,"projectName":"${"A".repeat(100_000)}",
                   "faceId":"00046","displayName":"a.apk"}""".encodeToByteArray(),
            )
        }

        assertEquals(100_000, ProjectArchive.read(archive).manifest.projectName.length)
    }

    /**
     * Packing refuses a source with two containers, so an archive cannot be built ambiguous.
     *
     * `Fit3Apk.parse` would refuse it on the way back in, and finding that out at import —
     * with the project it came from long gone — is finding it out too late.
     */
    @Test
    fun packingASourceWithTwoContainersIsRefused() {
        val twoFaces = zip {
            member("assets/SM-R390_00046_256x402.bin", ByteArray(64))
            member("assets/SM-R390_00001_256x402.bin", ByteArray(64))
        }

        val error = runCatching {
            ProjectArchive.pack(
                source = ByteArrayInputStream(twoFaces),
                destination = ByteArrayOutputStream(),
                manifest = ProjectManifest(
                    projectName = "A",
                    faceId = "00046",
                    displayName = "a.apk",
                ),
                editedContainer = null,
                sessionState = null,
            )
        }.exceptionOrNull()

        assertTrue("expected a refusal, got $error", error is ProjectArchiveException)
        assertTrue(error!!.message!!.contains("containers, not one"))
    }

    /** Packing copies no entry the parser does not read, whatever the source names them. */
    @Test
    fun packingDropsEveryHostileNameInTheSource() {
        val hostile = zip {
            member("assets/SM-R390_00046_256x402.bin", ByteArray(64))
            member("../../../evil.bin", ByteArray(8))
            member("/absolute/evil.bin", ByteArray(8))
            member("assets/ko_KR/SM-R390_00046_2_0.png", ByteArray(8))
            member(ProjectArchive.ManifestEntry, "stale".encodeToByteArray())
            member("classes.dex", ByteArray(8))
        }
        val out = ByteArrayOutputStream()

        ProjectArchive.pack(
            source = ByteArrayInputStream(hostile),
            destination = out,
            manifest = ProjectManifest(projectName = "A", faceId = "00046", displayName = "a.apk"),
            editedContainer = null,
            sessionState = null,
        )

        val names = buildList {
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name)
                    zip.closeEntry()
                }
            }
        }
        assertEquals(
            listOf("assets/SM-R390_00046_256x402.bin", ProjectArchive.ManifestEntry),
            names,
        )
        // The stale sidecar was dropped on the way through and the fresh one written, so the
        // manifest that comes back is this call's and not the source's.
        assertEquals("A", ProjectArchive.read(out.toByteArray()).manifest.projectName)
    }
}
