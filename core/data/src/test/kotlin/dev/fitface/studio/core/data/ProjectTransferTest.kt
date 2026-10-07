package dev.fitface.studio.core.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.data.db.ProjectDao
import dev.fitface.studio.core.data.db.ProjectEntity
import dev.fitface.studio.core.format.Fit3Apk
import dev.fitface.studio.core.format.ProjectArchive
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That a project survives a trip through a file, and comes back as a project of its own.
 *
 * The contract is the same one duplication has — independence — reached by a longer route,
 * and it has one more thing to prove on top: that the file is enough. A duplicate copies
 * four things out of a directory it can see; an import has only the archive, so anything the
 * exporter left out is gone with nothing to say so. The edit, the removed-widget records and
 * the project's own name are each carried by a different member of the file, and each one is
 * asserted here separately because losing any one of them produces a project that opens.
 *
 * The other half is what an import must refuse. A container the watch would reject, or an
 * edit belonging to another face, has to be turned away *before* a row is written: an
 * archive is a file from somewhere this app does not control, and the alternative is a
 * project someone spends an evening on before the Install page tells them it was never
 * sendable.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectTransferTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val packagePath: Path get() = root.resolve("SM-R390_00046.apk")
    private val otherPackagePath: Path get() = root.resolve("SM-R390_00106.apk")

    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var dao: ProjectDao
    private lateinit var watchingDao: PreviewWatchingDao
    private lateinit var repository: WatchFaceRepositoryImpl
    private lateinit var transfers: File

    @Before
    fun setUp() {
        assumeTrue("no package at $packagePath", Files.isRegularFile(packagePath))
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FitFaceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.projectDao()
        watchingDao = PreviewWatchingDao(dao) { id ->
            File(context.filesDir, "projects/$id/previews")
                .listFiles()
                .orEmpty()
                .count { it.isFile && it.length() > 0 }
        }
        repository = WatchFaceRepositoryImpl(
            context = context,
            projectDao = watchingDao,
            imageSource = AndroidImageSource(context.contentResolver),
            contentResolver = context.contentResolver,
            diagnostics = DiagnosticsLog(),
        )
        transfers = File(context.cacheDir, "transfers").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) {
            File(context.filesDir, "projects").deleteRecursively()
            transfers.deleteRecursively()
        }
    }

    /**
     * The whole feature in one assertion: what came out is what went in.
     *
     * The widget is moved *before* the export, so the archive has to carry `edited.bin` and
     * the import has to write it back and point the row at it. Any break in that chain gives
     * a project that opens on the unedited face, which is a loss nothing on screen reports.
     */
    @Test
    fun anExportedProjectComesBackWithTheEditItCarried() = runBlocking {
        val original = repository.openPackage(facePackage())
        val moved = position(nudge(original, by = 7))

        val imported = repository.importProject(export(original.projectId))

        assertEquals(moved, position(repository.openProject(imported.id)))
    }

    /** A project exported before it was edited imports as an unedited project, not a failure. */
    @Test
    fun anUneditedProjectMakesTheRoundTripToo() = runBlocking {
        val original = repository.openPackage(facePackage())
        val untouched = position(original)

        val imported = repository.importProject(export(original.projectId))

        val opened = repository.openProject(imported.id)
        assertEquals(untouched, position(opened))
        assertNull(
            "an unedited project imported with an edit",
            requireNotNull(dao.findById(imported.id)).editedBinPath,
        )
    }

    /**
     * The removed-widget records are their own member of the archive, and their own way to
     * lose an evening's work.
     *
     * Without `session.json` the import produces a project whose widget is missing from the
     * face with nothing offering to put it back — the container remembers the removal and
     * only this file remembers what was removed. It is the same reason `duplicateProject`
     * copies the file by name rather than by a stored path.
     */
    @Test
    fun aRemovedWidgetComesBackRestorable() = runBlocking {
        val original = repository.openPackage(facePackage())
        // Whichever the face lets go of. Removal is refused for a widget others are
        // positioned against, so the candidates are tried rather than assumed — this test
        // is about the archive, not about which of face 00046's records is removable.
        val anchors = original.widgets.mapNotNull { it.alignedToGlobalIndex }.toSet()
        val afterRemoval = original.widgets
            .filter { it.globalIndex !in anchors }
            .asReversed()
            .firstNotNullOfOrNull { widget ->
                runCatching {
                    repository.removeWidget(
                        styleName = original.selectedVariant.basename,
                        globalIndex = widget.globalIndex,
                        widgetType = widget.type,
                        sequenceId = widget.sequenceId,
                        x = widget.x,
                        y = widget.y,
                        requireFinal = false,
                        applyToAllStyles = false,
                    )
                }.getOrNull()
            }
        assumeTrue("face 00046 has nothing removable", afterRemoval != null)
        assertEquals("nothing was removed", 1, afterRemoval!!.removedWidgets.size)

        val imported = repository.importProject(export(original.projectId))

        val opened = repository.openProject(imported.id)
        assertEquals(
            "the removal records did not survive the archive",
            1,
            opened.removedWidgets.size,
        )
        val restored = repository.restoreWidget(opened.removedWidgets.single().id)
        assertTrue("the restored widget could not be put back", restored.removedWidgets.isEmpty())
    }

    /**
     * Editing the source after an export does not reach into the copy, and neither does
     * deleting it.
     *
     * The second half is what a row holding a path into another project's directory fails,
     * and it only fails after the original is gone — which is the point of asserting it
     * rather than the paths, though [anImportedProjectHoldsNoPathIntoAnother] does that too.
     */
    @Test
    fun anImportedProjectIsIndependentOfTheOneItCameFrom() = runBlocking {
        val original = repository.openPackage(facePackage())
        val exported = position(nudge(original, by = 7))
        val imported = repository.importProject(export(original.projectId))

        val reopened = repository.openProject(original.projectId)
        assertNotEquals("the original did not actually move", exported, position(nudge(reopened, by = 5)))
        assertEquals(
            "editing the original wrote into the import",
            exported,
            position(repository.openProject(imported.id)),
        )

        repository.deleteProject(original.projectId)
        assertEquals(
            "deleting the original took the import's package with it",
            exported,
            position(repository.openProject(imported.id)),
        )
    }

    /** Nothing in the imported row may point at the directory the archive was written from. */
    @Test
    fun anImportedProjectHoldsNoPathIntoAnother() = runBlocking {
        val original = repository.openPackage(facePackage())
        nudge(original, by = 7)
        val imported = repository.importProject(export(original.projectId))

        val row = requireNotNull(dao.findById(imported.id))
        val own = File(context.filesDir, "projects/${imported.id}").absolutePath
        assertTrue("localApkPath: ${row.localApkPath}", row.localApkPath!!.startsWith(own))
        assertTrue("editedBinPath: ${row.editedBinPath}", row.editedBinPath!!.startsWith(own))
    }

    /**
     * The archive is the project's package, so its style previews have to come out of it.
     *
     * Written at import rather than left to the first open, which is where a downloaded
     * project gets them: without it the row sits in the list with no thumbnail, and a
     * project that looks broken is one nobody taps.
     *
     * This one only proves the files exist. Whether the *list* ever sees them is
     * [theRowIsAnnouncedOnlyOnceItsPreviewsAreOnDisk], and the two are not the same thing.
     */
    @Test
    fun anImportedProjectHasItsStylePreviewsOnDiskBeforeItIsOpened() = runBlocking {
        val original = repository.openPackage(facePackage())
        val imported = repository.importProject(export(original.projectId))

        val previews = File(context.filesDir, "projects/${imported.id}/previews")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.length() > 0 }
        assertTrue("no style previews were extracted", previews.isNotEmpty())
        assertEquals(
            "the projects list would show no thumbnail",
            previews.size,
            repository.observeProjects().first()
                .count { it.id == imported.id && it.previewImagePath != null } * previews.size,
        )
    }

    /**
     * The previews are on disk **before** the row write that announces the project.
     *
     * The bug this pins: `observeProjects` maps every DAO emission through
     * `projectPreviewImage`, which lists the previews directory at that moment. Written
     * after the last write to the table, the previews are files no emission has seen — so
     * the imported row sat in the list with no thumbnail until something else touched the
     * table, which in practice meant opening the project. Reported from a device, and the
     * order in `importProject` is exactly what caused it.
     *
     * Asserted on write **order** rather than on what the flow emits, because Room's
     * invalidation is asynchronous: with the writes the wrong way round the emission
     * sometimes still lands after the files, which is what made this show up on one import
     * and not the next. A flaky bug needs a test that cannot itself be flaky, so this
     * records what the previews directory held at each `insert` and asserts on the last.
     */
    @Test
    fun theRowIsAnnouncedOnlyOnceItsPreviewsAreOnDisk() = runBlocking {
        val original = repository.openPackage(facePackage())
        val archive = export(original.projectId)

        watchingDao.recording = true
        val imported = repository.importProject(archive)
        watchingDao.recording = false

        val previewsAtEachWrite = watchingDao.previewCountsAtInsert.toList()
        assertTrue("no row was written", previewsAtEachWrite.isNotEmpty())
        assertTrue(
            "the row was announced before its previews existed: $previewsAtEachWrite",
            previewsAtEachWrite.last() > 0,
        )
        // And the end state agrees, so this is not merely an ordering trick.
        assertNotNull(
            repository.observeProjects().first().single { it.id == imported.id }.previewImagePath,
        )
    }

    /**
     * Importing the same archive twice gives two projects, named apart.
     *
     * Always a new project, never a merge into one already on the face — `openPackage`'s
     * rule, and what makes an archive usable as a checkpoint you can come back to more than
     * once. The naming is against *this* library, not the exporting one: the archive's own
     * name may already be taken here.
     */
    @Test
    fun importingTheSameArchiveTwiceGivesTwoProjectsWithDifferentNames() = runBlocking {
        val original = repository.openPackage(facePackage())
        nudge(original, by = 7)
        val archive = export(original.projectId)

        val first = repository.importProject(archive)
        val second = repository.importProject(archive)

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.name, second.name)
        assertEquals(3, dao.findByFaceId("00046").size)
        val names = dao.findByFaceId("00046").mapNotNull(ProjectEntity::projectName)
        assertEquals("two projects share a name", names.size, names.toSet().size)
    }

    /** The measurable claim: the file is a small fraction of the package it came from. */
    @Test
    fun theArchiveIsAFractionOfThePackage() = runBlocking {
        val original = repository.openPackage(facePackage())
        val archive = File(Uri.parse(export(original.projectId)).path!!)

        val packageSize = Files.size(packagePath)
        assertTrue(
            "archive ${archive.length()} against package $packageSize",
            archive.length() * 2 < packageSize,
        )
    }

    /** An export with no pristine container to carry is refused rather than half-written. */
    @Test
    fun exportingAProjectWhosePackageIsGoneIsRefused() = runBlocking {
        val original = repository.openPackage(facePackage())
        assertTrue(File(context.filesDir, "projects/${original.projectId}/source.apk").delete())

        val destination = destination("gone.zip")
        val failure = runCatching { repository.exportProject(original.projectId, destination) }

        assertTrue("the export was expected to be refused", failure.isFailure)
    }

    /** A file that is not an archive is refused, and leaves nothing in the library. */
    @Test
    fun importingSomethingThatIsNotAnArchiveLeavesNoRow() = runBlocking {
        val junk = File(transfers, "notes.zip").apply { writeBytes(ByteArray(2_048) { 0x41 }) }

        val failure = runCatching { repository.importProject(Uri.fromFile(junk).toString()) }

        assertTrue("the import was expected to be refused", failure.isFailure)
        assertTrue("a row was left behind", dao.findByFaceId("00046").isEmpty())
    }

    /**
     * A watch-face package is not a project archive, and the difference has to be said.
     *
     * It parses perfectly as a package — it is one — so nothing but the missing manifest
     * distinguishes it, and importing it as an empty project would be a plausible-looking
     * wrong answer.
     */
    @Test
    fun importingAPlainPackageIsRefused() = runBlocking {
        val copied = File(transfers, "package.zip")
            .apply { writeBytes(Files.readAllBytes(packagePath)) }

        val failure = runCatching { repository.importProject(Uri.fromFile(copied).toString()) }

        assertTrue("the import was expected to be refused", failure.isFailure)
        assertTrue("a row was left behind", dao.findByFaceId("00046").isEmpty())
    }

    /**
     * An archive whose `edited.bin` belongs to another face is refused before a row exists.
     *
     * The two halves of an archive are written separately and can be swapped by hand, and
     * this is the pairing that produces a container the watch accepts and draws wrong — the
     * edit validates on its own, so only comparing it against the pristine container beside
     * it catches the mismatch. Refused at import, because the alternative is finding out on
     * the Install page.
     */
    @Test
    fun anArchiveWhoseEditBelongsToAnotherFaceIsRefused() = runBlocking {
        assumeTrue("no package at $otherPackagePath", Files.isRegularFile(otherPackagePath))
        val original = repository.openPackage(facePackage())
        val archive = File(Uri.parse(export(original.projectId)).path!!)
        val foreign = Fit3Apk.parse(Files.readAllBytes(otherPackagePath), retainMembers = false)
        val tampered = File(transfers, "tampered.zip").apply {
            writeBytes(replacing(archive.readBytes(), ProjectArchive.EditedEntry, foreign.binary))
        }

        val failure = runCatching { repository.importProject(Uri.fromFile(tampered).toString()) }

        assertTrue("the swapped edit was expected to be refused", failure.isFailure)
        assertEquals("a row was left behind", 1, dao.findByFaceId("00046").size)
        assertTrue("a row was left behind", dao.findByFaceId(foreign.faceId).isEmpty())
    }

    /** An edit that is not a container at all is refused in its own words. */
    @Test
    fun anArchiveWhoseEditIsNotAContainerIsRefused() = runBlocking {
        val original = repository.openPackage(facePackage())
        nudge(original, by = 7)
        val archive = File(Uri.parse(export(original.projectId)).path!!)
        val tampered = File(transfers, "corrupt.zip").apply {
            writeBytes(
                replacing(archive.readBytes(), ProjectArchive.EditedEntry, ByteArray(512) { 0x5A }),
            )
        }

        val failure = runCatching { repository.importProject(Uri.fromFile(tampered).toString()) }

        assertTrue("a corrupt edit was expected to be refused", failure.isFailure)
        assertEquals("a row was left behind", 1, dao.findByFaceId("00046").size)
    }

    /**
     * An edited container past the watch's ceiling is refused at import, not at install.
     *
     * A container over 4 MiB transfers, is accepted and leaves the old face up — the failure
     * looks exactly like success — and this app's own `rebuild` refuses to grow one past the
     * line, so a container over it is one no export of ours produced. `validatedBytes()` would
     * stop it eventually; stopping it here is the difference between a message now and a dead
     * end after the edits have been reviewed.
     */
    @Test
    fun anEditedContainerOverTheWatchsCeilingIsRefused() = runBlocking {
        val original = repository.openPackage(facePackage())
        nudge(original, by = 7)
        val archive = File(Uri.parse(export(original.projectId)).path!!)
        val oversized = ByteArray(WATCH_CONTAINER_BYTE_CEILING + 1)
        val tampered = File(transfers, "oversized.zip").apply {
            writeBytes(replacing(archive.readBytes(), ProjectArchive.EditedEntry, oversized))
        }

        val failure = runCatching { repository.importProject(Uri.fromFile(tampered).toString()) }

        assertTrue("an oversized edit was expected to be refused", failure.isFailure)
        assertEquals("a row was left behind", 1, dao.findByFaceId("00046").size)
    }

    /**
     * A file far larger than any archive is refused while it is read, not after.
     *
     * The ceiling is checked as the bytes arrive rather than taken from the provider's
     * reported length, which a `content://` provider is under no obligation to get right.
     */
    @Test
    fun aFileTooLargeToBeAnArchiveIsRefusedWhileItIsRead() = runBlocking {
        val huge = File(transfers, "huge.zip")
        huge.outputStream().use { out ->
            val block = ByteArray(1024 * 1024)
            repeat((ProjectArchive.MaxArchiveBytes / block.size).toInt() + 2) { out.write(block) }
        }

        val failure = runCatching { repository.importProject(Uri.fromFile(huge).toString()) }

        assertTrue("an oversized file was expected to be refused", failure.isFailure)
        assertTrue(dao.findByFaceId("00046").isEmpty())
    }

    /**
     * A manifest's strings are clamped before they reach a row.
     *
     * Nothing here makes an archive unreadable, which is why the format layer carries it
     * through — but `projectName` becomes a database row and a list title, and a megabyte of
     * it is a row every query pays for and a title no `maxLines` saves. `selectedStyle` is
     * worse than cosmetic: it names a container entry, so a value that is not a style name is
     * a variant the session cannot resolve, and the honest reading of one is "no style was
     * recorded".
     */
    @Test
    fun anAbsurdManifestIsClampedRatherThanTrusted() = runBlocking {
        val original = repository.openPackage(facePackage())
        val archive = File(Uri.parse(export(original.projectId)).path!!)
        val hostile = """
            {"schema":1,"projectName":"${"N".repeat(50_000)}","faceId":"00046",
             "displayName":"${"D".repeat(50_000)}","selectedStyle":"../../etc/passwd",
             "styleId":99999,"productId":"${"P".repeat(50_000)}"}
        """.trimIndent().encodeToByteArray()
        val tampered = File(transfers, "absurd.zip").apply {
            writeBytes(replacing(archive.readBytes(), ProjectArchive.ManifestEntry, hostile))
        }

        val imported = repository.importProject(Uri.fromFile(tampered).toString())

        val row = requireNotNull(dao.findById(imported.id))
        assertTrue("projectName was not clamped: ${row.projectName?.length}", row.projectName!!.length <= 256)
        assertTrue("displayName was not clamped", row.displayName.length <= 256)
        assertTrue("productId was not clamped", row.productId!!.length <= 256)
        assertNull("a style name that names no entry was kept", row.selectedStyle)
        assertNull("an out-of-range style id was kept", row.styleId)
        // And it is still a working project, which is the point of clamping rather than
        // refusing: none of the above makes the archive unusable.
        assertEquals(position(original), position(repository.openProject(imported.id)))
    }

    // --- helpers ---

    /** Exports [projectId] to a file in the cache, and returns the URI it was written to. */
    private suspend fun export(projectId: Long): String {
        val destination = destination("project-$projectId-${System.nanoTime()}.zip")
        repository.exportProject(projectId, destination)
        return destination
    }

    private fun destination(name: String): String =
        Uri.fromFile(File(transfers, name)).toString()

    /** The archive with one member's bytes swapped, leaving every other entry as it was. */
    private fun replacing(archive: ByteArray, name: String, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { out ->
            ZipInputStream(ByteArrayInputStream(archive)).use { input ->
                var replaced = false
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (!entry.isDirectory) {
                        out.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == name) {
                            out.write(payload)
                            replaced = true
                        } else {
                            input.copyTo(out)
                        }
                        out.closeEntry()
                    }
                    input.closeEntry()
                }
                if (!replaced) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(payload)
                    out.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    /**
     * Records how many style previews were on disk at the moment of each row write.
     *
     * The only way to assert the ordering deterministically — see
     * [theRowIsAnnouncedOnlyOnceItsPreviewsAreOnDisk] for why the flow itself cannot be.
     */
    private class PreviewWatchingDao(
        private val delegate: ProjectDao,
        private val previewCount: (Long) -> Int,
    ) : ProjectDao by delegate {
        var recording = false
        val previewCountsAtInsert = mutableListOf<Int>()

        override suspend fun insert(project: ProjectEntity): Long {
            val id = delegate.insert(project)
            // Only writes that name a project, not the one that claims an id: the first
            // insert has no directory yet by construction, and asserting on it would say
            // nothing about the order this test is about.
            if (recording && project.id > 0) previewCountsAtInsert += previewCount(project.id)
            return id
        }
    }

    private suspend fun nudge(snapshot: EditorSnapshot, by: Int): EditorSnapshot {
        val widget = snapshot.widgets.first { it.width > 0 && it.height > 0 }
        return repository.moveWidget(
            styleName = snapshot.selectedVariant.basename,
            globalIndex = widget.globalIndex,
            widgetType = widget.type,
            sequenceId = widget.sequenceId,
            x = widget.x + by,
            y = widget.y,
            applyToAllStyles = false,
        )
    }

    private fun position(snapshot: EditorSnapshot): Pair<Int, Int> =
        snapshot.widgets.first { it.width > 0 && it.height > 0 }.let { it.x to it.y }

    private fun facePackage() = FacePackage(
        sourceKey = FacePackage.sourceKey(productId = "test-00046", versionCode = 1, styleId = 0),
        displayName = "SM-R390_00046.apk",
        expectedFaceId = "00046",
        selectedStyleId = 0,
        versionCode = 1,
        bytes = Files.readAllBytes(packagePath),
    )
}
