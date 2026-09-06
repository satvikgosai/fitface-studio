package dev.fitface.studio.core.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * That a project archive is a package this app can open, and that it holds everything the
 * parser reads and nothing else.
 *
 * The design rests on one claim: `Fit3Apk.parse` cannot tell an archive from the package it
 * came from. Everything downstream of an import is built on that — the project's `source.apk`
 * *is* the archive, so opening it, duplicating it, extracting its style previews, resolving
 * the pristine container a resize resamples from and building the install payload all run
 * unmodified. If the claim is false in any one of the four particulars `Fit3Apk` reports, the
 * failure is silent: a face name goes missing, or a sampler id, or the style previews the
 * picker shows, and the reader finds out by looking at a screen rather than by being told.
 *
 * So [anArchiveParsesAsThePackageItCameFrom] sweeps every package in the corpus and compares
 * all four, plus the container byte for byte. [theArchiveHoldsNothingTheParserDoesNotRead] is
 * the other direction — the part that makes the file small — and the two together are what
 * pins `Fit3Apk.readsMember` as the single list rather than one of two that have to agree.
 */
class ProjectArchiveTest {
    private val corpus: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private fun packages(): List<Path> = corpus.resolve("packages")
        .takeIf(Files::isDirectory)
        ?.let { directory ->
            Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".apk") }.sorted().toList()
            }
        }
        .orEmpty()

    private fun manifest(faceId: String) = ProjectManifest(
        projectName = "Aurora 2",
        faceId = faceId,
        displayName = "SM-R390_$faceId.apk",
        faceName = "Aurora",
        sourceUri = "fit3-catalog://test/40000/0",
        productId = "test",
        packageVersionCode = 40_000,
        styleId = 0,
        selectedStyle = "style0.bin",
        exportedAtEpochMillis = 1_700_000_000_000,
        exportedByVersion = "0.1.2 (18)",
    )

    private fun pack(
        packageBytes: ByteArray,
        faceId: String,
        edited: ByteArray? = null,
        session: ByteArray? = null,
        manifest: ProjectManifest = manifest(faceId),
    ): ByteArray = ByteArrayOutputStream().also { output ->
        ProjectArchive.pack(
            source = ByteArrayInputStream(packageBytes),
            destination = output,
            manifest = manifest,
            editedContainer = edited,
            sessionState = session,
        )
    }.toByteArray()

    private fun entryNames(archive: ByteArray): List<String> = buildList {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) add(entry.name)
                zip.closeEntry()
            }
        }
    }

    /**
     * The claim the whole feature rests on, over all 100 corpus packages.
     *
     * Every field `Fit3Apk` reports, not just the container: the face name comes out of
     * `bandface_info.json`, the sampler id out of the same file's `thumbnail`, and the style
     * previews out of the `assets/`-anchored PNGs. Dropping any one of the three members
     * would leave the container intact and the parse succeeding, which is exactly the kind
     * of loss a round-trip test that only compared bytes would miss.
     */
    @Test
    fun anArchiveParsesAsThePackageItCameFrom() {
        val packages = packages()
        assumeTrue("no packages under $corpus/packages", packages.isNotEmpty())
        var compared = 0
        packages.forEach { path ->
            val bytes = Files.readAllBytes(path)
            val original = runCatching { Fit3Apk.parse(bytes) }.getOrNull() ?: return@forEach
            val archive = pack(bytes, original.faceId)
            val reparsed = Fit3Apk.parse(archive)

            assertEquals("$path faceId", original.faceId, reparsed.faceId)
            assertEquals("$path samplerId", original.samplerId, reparsed.samplerId)
            assertEquals("$path faceName", original.faceName, reparsed.faceName)
            assertEquals("$path binaryMember", original.binaryMember, reparsed.binaryMember)
            assertTrue("$path container", original.binary.contentEquals(reparsed.binary))
            assertEquals(
                "$path style previews",
                original.stylePreviews.keys,
                reparsed.stylePreviews.keys,
            )
            original.stylePreviews.forEach { (index, png) ->
                assertTrue(
                    "$path style preview $index",
                    png.contentEquals(reparsed.stylePreviews[index]),
                )
            }
            compared++
        }
        assumeTrue("no package in the corpus carries a container", compared > 0)
    }

    /**
     * The other direction, and the reason the file is worth writing at all.
     *
     * Every member is either one the parser reads or one of the three sidecar names. A
     * package's other 560-odd members — the dex, the resources, the signature block, the
     * accessory JARs — carry nothing this app looks at, and the `assets/<locale>/` copies of
     * the previews are localised artwork the preview pattern is anchored to exclude.
     */
    @Test
    fun theArchiveHoldsNothingTheParserDoesNotRead() {
        val packages = packages()
        assumeTrue("no packages under $corpus/packages", packages.isNotEmpty())
        val sidecar = setOf(
            ProjectArchive.ManifestEntry,
            ProjectArchive.EditedEntry,
            ProjectArchive.SessionEntry,
        )
        packages.take(12).forEach { path ->
            val bytes = Files.readAllBytes(path)
            val original = runCatching { Fit3Apk.parse(bytes, retainMembers = false) }
                .getOrNull() ?: return@forEach
            val names = entryNames(pack(bytes, original.faceId))

            val unexpected = names.filterNot { it in sidecar || Fit3Apk.readsMember(it) }
            assertEquals("$path kept members the parser never reads", emptyList<String>(), unexpected)
            assertTrue(
                "$path kept a localised preview",
                names.none { it.startsWith("assets/") && it.count { char -> char == '/' } > 1 },
            )
            assertTrue("$path lost the container", names.any(Fit3Apk::isFaceBinary))
            assertTrue(
                "$path lost the face metadata",
                names.contains(Fit3Apk.FACE_METADATA_MEMBER),
            )
        }
    }

    /** The point of the exercise: a package's 571 members become a container and a few files. */
    @Test
    fun anArchiveIsSubstantiallySmallerThanThePackage() {
        val packages = packages()
        assumeTrue("no packages under $corpus/packages", packages.isNotEmpty())
        packages.take(12).forEach { path ->
            val bytes = Files.readAllBytes(path)
            val original = runCatching { Fit3Apk.parse(bytes, retainMembers = false) }
                .getOrNull() ?: return@forEach
            val archive = pack(bytes, original.faceId)
            assertTrue(
                "$path archive ${archive.size} is not smaller than package ${bytes.size}",
                archive.size < bytes.size,
            )
        }
    }

    /**
     * The edit and the removal records come back byte for byte.
     *
     * They have to: `edited.bin` is a container the watch will be asked to accept, and
     * `session.json` holds base64 of the raw records a restore writes back. A round trip
     * that changed either by a byte would produce a project that validates and draws wrong.
     */
    @Test
    fun theEditAndTheRemovalRecordsSurviveTheRoundTrip() {
        val path = anyPackage() ?: return
        val bytes = Files.readAllBytes(path)
        val original = Fit3Apk.parse(bytes, retainMembers = false)
        val edited = original.binary.copyOf()
        val session = """{"thumbnailRefreshed":true,"removed":[]}""".encodeToByteArray()

        val contents = ProjectArchive.read(
            pack(bytes, original.faceId, edited = edited, session = session),
        )

        assertTrue("edited container", edited.contentEquals(contents.editedContainer))
        assertTrue("session state", session.contentEquals(contents.sessionState))
        assertEquals("Aurora 2", contents.manifest.projectName)
        assertEquals(original.faceId, contents.manifest.faceId)
        assertEquals("style0.bin", contents.manifest.selectedStyle)
        assertEquals(40_000L, contents.manifest.packageVersionCode)
    }

    /** A project exported before it was edited carries no edit, and that is not a failure. */
    @Test
    fun anUneditedProjectExportsWithNoEdit() {
        val path = anyPackage() ?: return
        val bytes = Files.readAllBytes(path)
        val faceId = Fit3Apk.parse(bytes, retainMembers = false).faceId

        val contents = ProjectArchive.read(pack(bytes, faceId))

        assertNull(contents.editedContainer)
        assertNull(contents.sessionState)
        assertNotNull(contents.manifest)
    }

    /**
     * Removal records with no edit to apply them to are dropped rather than carried.
     *
     * `pack` will not write that pair — the records describe widgets cut out of the edited
     * container, so without one they describe removals from a face nothing removed anything
     * from, and `restoreSessionState` is not even reached. An archive assembled by hand can
     * still hold both, so `read` drops the half that cannot apply.
     */
    @Test
    fun removalRecordsWithNoEditAreNotCarried() {
        val path = anyPackage() ?: return
        val bytes = Files.readAllBytes(path)
        val faceId = Fit3Apk.parse(bytes, retainMembers = false).faceId

        val contents = ProjectArchive.read(
            pack(bytes, faceId, edited = null, session = "{}".encodeToByteArray()),
        )

        assertNull("a session survived with no edit beside it", contents.sessionState)
    }

    /**
     * Re-exporting an imported project writes one sidecar, not two.
     *
     * `Fit3Apk.readsMember` refuses the three `fitface/` names, so the old sidecar is skipped
     * on the way through and a fresh one is appended. Without that the archive would nest a
     * copy of every previous export, and `read` — which takes the first of a repeated name —
     * would hand back whichever one the zip happened to list first.
     */
    @Test
    fun packingAnArchiveAgainReplacesItsSidecarRatherThanNestingIt() {
        val path = anyPackage() ?: return
        val bytes = Files.readAllBytes(path)
        val original = Fit3Apk.parse(bytes, retainMembers = false)
        val first = pack(bytes, original.faceId, edited = original.binary.copyOf())

        val second = pack(
            first,
            original.faceId,
            manifest = manifest(original.faceId).copy(projectName = "Aurora 3"),
        )

        val names = entryNames(second)
        assertEquals(
            "the manifest was written more than once",
            1,
            names.count { it == ProjectArchive.ManifestEntry },
        )
        assertEquals(
            "the previous export's edit was carried over",
            0,
            names.count { it == ProjectArchive.EditedEntry },
        )
        assertEquals("Aurora 3", ProjectArchive.read(second).manifest.projectName)
        // Still a package, which is the property the whole design turns on.
        assertEquals(original.faceId, Fit3Apk.parse(second).faceId)
    }

    /** A zip that is not an export says so, rather than importing as an empty project. */
    @Test
    fun aZipWithNoManifestIsRefused() {
        val path = anyPackage() ?: return
        val failure = runCatching { ProjectArchive.read(Files.readAllBytes(path)) }

        val error = failure.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", error is ProjectArchiveException)
        assertTrue(
            "the message does not say what is wrong: ${error?.message}",
            error?.message?.contains("no exported project") == true,
        )
    }

    /** Not a zip at all. */
    @Test
    fun somethingThatIsNotAZipIsRefused() {
        val failure = runCatching { ProjectArchive.read(ByteArray(4_096) { 0x7 }) }
        assertTrue("expected a refusal", failure.exceptionOrNull() is ProjectArchiveException)
    }

    /**
     * An archive from a newer build is refused, not read hopefully.
     *
     * `ignoreUnknownKeys` is what makes this necessary: without the check a newer manifest
     * decodes cleanly while dropping whatever the newer field carried, and the reader finds
     * out by looking at the face.
     */
    @Test
    fun anArchiveFromANewerSchemaIsRefused() {
        val path = anyPackage() ?: return
        val bytes = Files.readAllBytes(path)
        val faceId = Fit3Apk.parse(bytes, retainMembers = false).faceId
        // Written by hand, because `pack` refuses to write a schema it is not.
        val archive = replaceManifest(
            pack(bytes, faceId),
            """{"schema":99,"projectName":"Aurora","faceId":"$faceId","displayName":"x.apk"}""",
        )

        val error = runCatching { ProjectArchive.read(archive) }.exceptionOrNull()

        assertTrue("expected a refusal", error is ProjectArchiveException)
        assertTrue(
            "the message does not name the cause: ${error?.message}",
            error?.message?.contains("newer version") == true,
        )
    }

    /** `pack` writes the schema it is, so a caller cannot stamp an archive with another. */
    @Test
    fun packingRefusesAManifestFromAnotherSchema() {
        val path = anyPackage() ?: return
        val bytes = Files.readAllBytes(path)
        val faceId = Fit3Apk.parse(bytes, retainMembers = false).faceId

        val failure = runCatching {
            pack(bytes, faceId, manifest = manifest(faceId).copy(schema = 2))
        }

        assertTrue("expected a refusal", failure.isFailure)
    }

    /** A zip with no container in it cannot become a project, and is refused as it is written. */
    @Test
    fun packingSomethingWithNoContainerIsRefused() {
        val empty = ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zip.write(ByteArray(16))
                zip.closeEntry()
            }
        }.toByteArray()

        val error = runCatching { pack(empty, "00001") }.exceptionOrNull()

        assertTrue("expected a refusal", error is ProjectArchiveException)
        assertTrue(
            "the message does not say what was missing: ${error?.message}",
            error?.message?.contains("containers, not one") == true,
        )
    }

    private fun anyPackage(): Path? {
        val packages = packages()
        assumeTrue("no packages under $corpus/packages", packages.isNotEmpty())
        return packages.firstOrNull { path ->
            runCatching { Fit3Apk.parse(Files.readAllBytes(path), retainMembers = false) }.isSuccess
        }
    }

    /** Rewrites just the manifest member, leaving every other entry as it was. */
    private fun replaceManifest(archive: ByteArray, json: String): ByteArray {
        val output = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { out ->
            ZipInputStream(ByteArrayInputStream(archive)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (!entry.isDirectory) {
                        out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        if (entry.name == ProjectArchive.ManifestEntry) {
                            out.write(json.encodeToByteArray())
                        } else {
                            input.copyTo(out)
                        }
                        out.closeEntry()
                    }
                    input.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
