package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.*
import dev.fitface.studio.core.format.*
import dev.fitface.studio.core.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetImportRepositoryTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var dao: FailableDao
    private lateinit var repository: WatchFaceRepositoryImpl
    private val samples = listOf("00008" to 1, "00002" to 2, "00025" to 1, "00001" to 1,
        "00023" to 2, "00004" to 1, "00003" to 3, "00028" to 1, "00028" to 13)

    @Before fun setup() {
        assumeTrue(Files.isDirectory(root.resolve("SM_R390")))
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FitFaceDatabase::class.java).allowMainThreadQueries().build()
        dao = FailableDao(database.projectDao())
        repository = repository()
    }
    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) {
            File(context.filesDir, "projects").deleteRecursively()
            File(context.cacheDir, "widget-import-tests").deleteRecursively()
        }
    }
    private fun repository() = WatchFaceRepositoryImpl(context, dao, AndroidImageSource(context.contentResolver),
        context.contentResolver, DiagnosticsLog())
    private fun bin(id: String): ByteArray {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("no corpus face $id", Files.isRegularFile(path))
        return Files.readAllBytes(path)
    }
    private fun face(id: String) = FacePackage(FacePackage.sourceKey("test-$id", 1, 0),
        "SM-R390_$id.apk", id, 0, 1, ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("assets/SM-R390_${id}_256x402.bin")); zip.write(bin(id)); zip.closeEntry()
            }
        }.toByteArray())
    private suspend fun prepare(snapshot: EditorSnapshot, id: String, index: Int): WidgetImportPreview {
        val donor = repository.inspectWidgetDonor(face(id))
        assertEquals(snapshot.projectId, repository.currentSnapshot(null).projectId)
        return repository.previewWidgetImport(donor.handle, "style0.bin", index,
            snapshot.projectId, snapshot.selectedVariant.basename)
    }
    private suspend fun add(snapshot: EditorSnapshot, id: String, index: Int) =
        repository.importWidget(prepare(snapshot, id, index).ticket)
    private suspend fun move(s: EditorSnapshot, w: WidgetGuide, dx: Int = 3) = repository.moveWidget(
        s.selectedVariant.basename, w.globalIndex, w.type, w.sequenceId, w.x + dx, w.y, true)
    private suspend fun remove(s: EditorSnapshot, w: WidgetGuide) = repository.removeWidget(
        s.selectedVariant.basename, w.globalIndex, w.type, w.sequenceId, w.x, w.y, false, true)
    private fun pixels(s: EditorSnapshot, index: Int) = s.widgetImageLayers.single { it.globalIndex == index }.frame.argb
    private fun exportFile() = File(context.cacheDir, "widget-import-tests/out.zip").also { it.parentFile!!.mkdirs() }
    private suspend fun export(id: Long): File = exportFile().also { repository.exportProject(id, Uri.fromFile(it).toString()) }

    @Test fun allNineTypesImportRenderEditAndReopenWithoutADonorProject() = runBlocking {
        val native = repository.openPackage(face("00106"))
        var s = native
        samples.forEach { (id, index) ->
            s = add(s, id, index)
            val widget = s.widgets.last()
            assertEquals(id, widget.importedFromFaceId)
            assertTrue(pixels(s, widget.globalIndex).any { it ushr 24 != 0 })
            if (widget.type == 5) {
                s = repository.recolorPairWidget(s.selectedVariant.basename, widget.globalIndex,
                    widget.sequenceId, widget.x, widget.y, 0xFF00FF00.toInt(), true)
                assertEquals(0xFF00FF00.toInt(), s.widgets.last().colorArgb)
                assertEquals(widget.originalColorArgb, s.widgets.last().originalColorArgb)
            }
            s = move(s, widget)
            val moved = s.widgets.last()
            val beforeCopy = s.containerBytes
            s = repository.duplicateWidget(s.selectedVariant.basename, moved.globalIndex, moved.type,
                moved.sequenceId, moved.x, moved.y, true)
            assertEquals(id, s.widgets.last().importedFromFaceId)
            // An imported widget is deleted, not parked under Removed. The copy shared the
            // original's artwork, so deleting it gives back exactly the record it added.
            s = remove(s, s.widgets.last())
            assertTrue(s.removedWidgets.none { it.importOriginId != null })
            assertEquals(beforeCopy, s.containerBytes)
            repository = repository()
            s = repository.openProject(native.projectId)
            assertEquals(id, s.widgets.last().importedFromFaceId)
            native.widgetImageLayers.forEach { layer -> assertArrayEquals(layer.frame.argb, pixels(s, layer.globalIndex)) }
        }
        assertEquals(1, dao.findByFaceId("00106").size)
        assertTrue(dao.findByFaceId("00008").isEmpty())
        assertEquals(9, s.widgets.count { it.importedFromFaceId != null })
        assertTrue(s.removedWidgets.isEmpty())
        val sibling = repository.currentSnapshot("style1.bin")
        assertTrue(sibling.widgets.none { it.importedFromFaceId != null })
    }

    @Test fun backgroundInsertionAndThumbnailRefreshPreserveImportedOrigins() = runBlocking {
        var s = add(repository.openPackage(face("00008")), "00008", 1)
        val imported = s.widgets.last()
        val artwork = pixels(s, imported.globalIndex)
        val image = File(context.cacheDir, "widget-import-tests/background.png").also { it.parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF182430.toInt()) }
        image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        s = repository.addBackground(Uri.fromFile(image).toString(), ImagePlacement())
        assertEquals(imported.globalIndex + 1, s.widgets.last().globalIndex)
        assertEquals("00008", s.widgets.last().importedFromFaceId)
        assertArrayEquals(artwork, pixels(s, s.widgets.last().globalIndex))
        s = repository.refreshThumbnail()
        assertTrue(s.thumbnailRefreshed)
        repository = repository()
        s = repository.openProject(s.projectId)
        assertTrue(s.thumbnailRefreshed)
        assertEquals(imported.originalWidth, s.widgets.last().originalWidth)
        assertArrayEquals(artwork, pixels(s, s.widgets.last().globalIndex))
        s = move(s, s.widgets.last())
        assertEquals(imported.x + 3, s.widgets.last().x)
        assertTrue(repository.prepareDirectInstall().copyBytes().isNotEmpty())
    }

    @Test fun importedHandAndItsDuplicateResizeFromTheDonorAfterReopen() = runBlocking {
        var s = add(repository.openPackage(face("00106")), "00025", 1)
        val original = s.widgets.last()
        val originalPixels = pixels(s, original.globalIndex)
        s = repository.duplicateWidget(s.selectedVariant.basename, original.globalIndex, original.type,
            original.sequenceId, original.x, original.y, true)
        suspend fun resize(width: Int, height: Int) {
            val w = s.widgets.last()
            s = repository.resizeWidget(s.selectedVariant.basename, w.globalIndex, w.type, w.sequenceId,
                w.x, w.y, width, height, true)
        }
        resize(original.width / 2, original.height / 2)
        val small = pixels(s, s.widgets.last().globalIndex)
        repository = repository()
        s = repository.openProject(s.projectId)
        assertEquals(original.width, s.widgets.last().originalWidth)
        resize(original.width, original.height)
        assertArrayEquals(originalPixels, pixels(s, s.widgets.last().globalIndex))
        resize(original.width / 2, original.height / 2)
        assertArrayEquals(small, pixels(s, s.widgets.last().globalIndex))
    }

    @Test fun archiveCopyAndResetKeepImportsIndependentAndResetToVendorBytes() = runBlocking {
        val native = repository.openPackage(face("00106"))
        val added = add(native, "00003", 3) // Dictionary and new font resources.
        val file = export(added.projectId)
        val archived = ProjectArchive.read(file.readBytes())
        assertEquals(2, archived.manifest.schema)
        assertArrayEquals(bin("00106"), Fit3Apk.parse(file.readBytes()).binary)
        val imported = repository.importProject(Uri.fromFile(file).toString())
        val copy = repository.duplicateProject(added.projectId)
        repository.deleteProject(added.projectId)
        for (id in listOf(imported.id, copy.id)) {
            val reopened = repository.openProject(id)
            assertEquals("00003", reopened.widgets.last().importedFromFaceId)
            assertArrayEquals(pixels(added, added.widgets.last().globalIndex), pixels(reopened, reopened.widgets.last().globalIndex))
            val reset = repository.resetEdits()
            assertEquals(native.widgets.size, reset.widgets.size)
            assertTrue(reset.widgets.none { it.importedFromFaceId != null })
            assertArrayEquals(bin("00106"), repository.prepareDirectInstall().copyBytes())
        }
    }

    @Test fun databaseFailureCannotCommitOnlyTheBinOrOnlyItsOrigins() = runBlocking {
        var s = add(repository.openPackage(face("00106")), "00008", 1)
        val before = dao.findById(s.projectId)!!
        val bytes = File(before.editedBinPath!!).readBytes()
        val preview = prepare(s, "00003", 3)
        dao.fail = true
        assertTrue(runCatching { repository.importWidget(preview.ticket) }.isFailure)
        assertEquals(before.editedBinPath, dao.findById(s.projectId)!!.editedBinPath)
        assertArrayEquals(bytes, File(before.editedBinPath!!).readBytes())
        assertEquals(s.widgets.size, repository.currentSnapshot(null).widgets.size)
        repository = repository()
        s = repository.openProject(s.projectId)
        assertEquals(1, s.widgets.count { it.importedFromFaceId != null })
        assertEquals(1, File(context.filesDir, "projects/${s.projectId}").listFiles()!!.count { it.extension == "checkpoint" })
    }

    @Test fun nativeRemovalRenumbersImportsAndNativeApplyAllNeverSelectsAnImport() = runBlocking {
        var s = add(repository.openPackage(face("00106")), "00008", 1)
        val original = s.widgets.last()
        val anchors = s.widgets.mapNotNull { it.alignedToGlobalIndex }.toSet()
        val native = s.widgets.first { it.globalIndex !in anchors && it.importedFromFaceId == null && it.placement == WidgetPlacement.CANVAS }
        s = remove(s, native)
        assertEquals(original.globalIndex - 1, s.widgets.last().globalIndex)
        assertEquals(original.originalWidth, s.widgets.last().originalWidth)
        repository = repository()
        s = repository.openProject(s.projectId)
        assertEquals("00008", s.widgets.last().importedFromFaceId)
        val importedX = s.widgets.last().x
        val sibling = repository.currentSnapshot("style1.bin")
        sibling.widgets.firstOrNull { it.type == original.type && it.sequenceId == original.sequenceId }?.let {
            move(sibling, it)
        }
        assertEquals(importedX, repository.currentSnapshot("style0.bin").widgets.last().x)
    }

    @Test fun stalePreviewIsRefusedAndAodIsIsolated() = runBlocking {
        val native = repository.openPackage(face("00106"))
        val preview = prepare(native, "00008", 1)
        repository.currentSnapshot("aod.bin")
        assertTrue(runCatching { repository.importWidget(preview.ticket) }.isFailure)
        val aod = repository.currentSnapshot("aod.bin")
        val added = add(aod, "00008", 1)
        assertEquals("00008", added.widgets.last().importedFromFaceId)
        assertEquals(native.activeStyleName, added.activeStyleName)
        assertEquals(native.widgets.size, repository.currentSnapshot("style0.bin").widgets.size)
    }

    @Test fun aNativeStyleMatchAtAnImportedIndexIsSkipped() = runBlocking {
        val native = repository.openPackage(face("00106"))
        val source = native.widgets.last()
        var s = add(native, "00106", source.globalIndex)
        // Remove only the selected native record: the import now occupies its old index,
        // while style1 still carries the native widget there, with the same type/source.
        s = repository.removeWidget("style0.bin", source.globalIndex, source.type, source.sequenceId,
            source.x, source.y, false, false)
        val imported = s.widgets.last()
        assertEquals(source.globalIndex, imported.globalIndex)
        val sibling = repository.currentSnapshot("style1.bin")
        val matched = sibling.widgets.single { it.globalIndex == source.globalIndex }
        assertEquals(imported.type, matched.type)
        assertEquals(imported.sequenceId, matched.sequenceId)
        move(sibling, matched, 9)
        val after = repository.currentSnapshot("style0.bin").widgets.last()
        assertEquals(imported.x, after.x)
        assertEquals("00106", after.importedFromFaceId)
    }

    /**
     * Removing an imported widget deletes it, artwork and all, and that survives a reopen.
     *
     * It used to go under Removed with every raster it brought still in the container,
     * drawn by nothing — the bytes never came back. Now the face is the size it was before
     * the import, and the project still opens: the saved import table outlives the widget,
     * because what the import added beside it (fonts, dictionary entries) is still there.
     */
    @Test fun anImportedWidgetIsDeletedWithItsArtwork() = runBlocking {
        val native = repository.openPackage(face("00106"))
        var s = add(native, "00008", 1)
        val imported = s.widgets.last()
        assertTrue(s.containerBytes > native.containerBytes)
        s = remove(s, imported)
        assertTrue(s.removedWidgets.isEmpty())
        assertEquals(native.widgets.size, s.widgets.size)
        assertEquals(native.containerBytes, s.containerBytes)
        assertEquals(native.imageCount, s.imageCount)
        repository = repository()
        s = repository.openProject(s.projectId)
        assertEquals(native.containerBytes, s.containerBytes)
        assertTrue(s.widgets.none { it.importedFromFaceId != null })
        // And importing again after that still works — the table it kept is still valid.
        s = add(s, "00008", 1)
        assertEquals("00008", s.widgets.last().importedFromFaceId)
    }

    /** A duplicate shares its original's artwork, so that artwork stays while either does. */
    @Test fun deletingAnImportKeepsTheArtworkItsDuplicateShares() = runBlocking {
        val native = repository.openPackage(face("00106"))
        var s = add(native, "00008", 1)
        val imported = s.widgets.last()
        val expected = pixels(s, imported.globalIndex)
        s = repository.duplicateWidget("style0.bin", imported.globalIndex, imported.type,
            imported.sequenceId, imported.x, imported.y, false)
        val withCopy = s.imageCount
        s = remove(s, s.widgets.single { it.globalIndex == imported.globalIndex })
        assertEquals(withCopy, s.imageCount)
        val copy = s.widgets.last()
        assertEquals("00008", copy.importedFromFaceId)
        assertArrayEquals(expected, pixels(s, copy.globalIndex))
        s = remove(s, copy)
        assertEquals(native.containerBytes, s.containerBytes)
    }

    /**
     * A stock widget under Removed stays restorable across an import's artwork being
     * deleted — its saved record is carried past the images that went.
     */
    @Test fun aStockRemovalStaysRestorableAfterAnImportIsDeleted() = runBlocking {
        val native = repository.openPackage(face("00106"))
        val anchors = native.widgets.mapNotNull { it.alignedToGlobalIndex }.toSet()
        val stock = native.widgets.first {
            it.globalIndex !in anchors && it.placement == WidgetPlacement.CANVAS
        }
        val expected = pixels(native, stock.globalIndex)
        var s = repository.removeWidget("style0.bin", stock.globalIndex, stock.type, stock.sequenceId,
            stock.x, stock.y, false, false)
        s = add(s, "00008", 1)
        s = add(s, "00008", 1)
        s = remove(s, s.widgets[s.widgets.size - 2])
        s = remove(s, s.widgets.last())
        assertEquals(1, s.removedWidgets.size)
        repository = repository()
        s = repository.openProject(s.projectId)
        s = repository.restoreWidget(s.removedWidgets.single().id)
        assertArrayEquals(expected, pixels(s, s.widgets.last().globalIndex))
    }

    @Test fun missingOrForeignProvenanceIsRefusedBeforeWritingAnArchiveRow() = runBlocking {
        val s = add(repository.openPackage(face("00106")), "00003", 3)
        val archive = export(s.projectId).readBytes()
        val originalCount = dao.findByFaceId("00106").size
        val originalInserts = dao.insertCalls
        for ((caseIndex, transform) in listOf<(String, ByteArray) -> ByteArray?>(
            { name, bytes -> bytes.takeUnless { name == ProjectArchive.SessionEntry } },
            { name, bytes -> if (name == ProjectArchive.SessionEntry) bytes.decodeToString()
                .replace(Regex("\"originalSha256\"\\s*:\\s*\"[a-f0-9]+\""), "\"originalSha256\":\"bad\"").encodeToByteArray() else bytes },
            { name, bytes -> if (name != ProjectArchive.SessionEntry) bytes else {
                val state = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.toMutableMap()
                // Fits the archive's inflation budget but not the private checkpoint,
                // which also contains the edited BIN encoded as base64.
                state["removed"] = buildJsonArray { add(buildJsonObject {
                    put("widgetType", 1); put("sequenceId", 0); put("x", 0); put("y", 0)
                    put("width", 1); put("height", 1); put("recordsByVariant", JsonObject(emptyMap()))
                    put("sourceLabel", "x".repeat(12 * 1024 * 1024))
                }) }
                JsonObject(state).toString().encodeToByteArray()
            } },
        ).withIndex()) {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip -> ZipInputStream(archive.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = transform(entry.name, input.readBytes()) ?: continue
                    zip.putNextEntry(ZipEntry(entry.name)); zip.write(bytes); zip.closeEntry()
                }
            } }
            val file = exportFile().apply { writeBytes(output.toByteArray()) }
            if (caseIndex == 2) assertEquals(2, ProjectArchive.read(file.readBytes()).manifest.schema)
            assertTrue(runCatching { repository.importProject(Uri.fromFile(file).toString()) }.isFailure)
            assertEquals(originalCount, dao.findByFaceId("00106").size)
            assertEquals(originalInserts, dao.insertCalls)
        }
    }

    @Test fun backgroundReviewDoesNotCommitAndItsTicketCannotOutliveTheDonorOrTarget() = runBlocking {
        val initial = repository.openPackage(face("00112"))
        val originalBytes = repository.prepareDirectInstall().copyBytes()
        val donor = repository.inspectWidgetDonor(face("00076"))
        val preview = repository.previewBackgroundImport(donor.handle, "style0.bin", initial.projectId, "style0.bin")
        assertArrayEquals(originalBytes, repository.prepareDirectInstall().copyBytes())
        assertFalse(repository.currentSnapshot().isDirty)
        repository.currentSnapshot("aod.bin")
        assertTrue(runCatching { repository.importBackground(preview.ticket) }.isFailure)
        repository.currentSnapshot("style0.bin")
        repository.releaseWidgetDonor(donor.handle)
        assertTrue(runCatching { repository.importBackground(preview.ticket) }.isFailure)
        assertArrayEquals(originalBytes, repository.prepareDirectInstall().copyBytes())
    }

    @Test fun backgroundImportSurvivesDonorReleaseAndReopenWithoutChangingWidgetArtwork() = runBlocking {
        val initial = repository.openPackage(face("00112"))
        val original = Fit3Container.parse(repository.prepareDirectInstall().copyBytes())
        val donor = repository.inspectWidgetDonor(face("00076"))
        val source = repository.backgroundDonorVariant(donor.handle, "style0.bin")
        assertNotNull(source.background)
        assertEquals(2, source.fullPanelImageCount)
        val preview = repository.previewBackgroundImport(donor.handle, "style0.bin", initial.projectId, "style0.bin")
        val result = repository.importBackground(preview.ticket)
        assertEquals(initial.containerBytes, result.containerBytes)
        assertTrue(result.isDirty)
        assertEquals(initial.widgets, result.widgets)
        assertArrayEquals(preview.preview.argb, result.composedPreview.argb)
        initial.widgetImageLayers.filter { layer -> initial.widgets.single { it.globalIndex == layer.globalIndex }
            .placement != WidgetPlacement.BACKGROUND }.forEach { layer ->
            assertArrayEquals(layer.frame.argb, pixels(result, layer.globalIndex))
        }
        val binary = repository.prepareDirectInstall().copyBytes()
        assertArrayEquals(original.entryByBasename("aod.bin").data,
            Fit3Container.parse(binary).entryByBasename("aod.bin").data)
        repository.releaseWidgetDonor(donor.handle)
        repository = repository()
        val reopened = repository.openProject(result.projectId)
        assertArrayEquals(result.composedPreview.argb, reopened.composedPreview.argb)
        assertArrayEquals(binary, repository.prepareDirectInstall().copyBytes())
        assertTrue(dao.findByFaceId("00076").isEmpty())
    }

    @Test fun backgroundTicketRejectsLaterEditsAndDatabaseFailureRollsBack() = runBlocking {
        var current = repository.openPackage(face("00112"))
        val donor = repository.inspectWidgetDonor(face("00076"))
        suspend fun preview() = repository.previewBackgroundImport(donor.handle, "style0.bin", current.projectId, "style0.bin")
        val stale = preview()
        current = move(current, current.canvasWidgets.first())
        assertTrue(runCatching { repository.importBackground(stale.ticket) }.isFailure)
        val before = repository.prepareDirectInstall().copyBytes()
        val prepared = preview()
        dao.fail = true
        assertTrue(runCatching { repository.importBackground(prepared.ticket) }.isFailure)
        assertArrayEquals(before, repository.prepareDirectInstall().copyBytes())
        repository = repository()
        val reopened = repository.openProject(current.projectId)
        assertArrayEquals(current.composedPreview.argb, reopened.composedPreview.argb)
        assertArrayEquals(before, repository.prepareDirectInstall().copyBytes())
    }

    @Test fun donorBackgroundAdditionPreservesImportedWidgetOrigins() = runBlocking {
        val initial = add(repository.openPackage(face("00008")), "00008", 1)
        val imported = initial.widgets.last()
        val art = pixels(initial, imported.globalIndex)
        val donor = repository.inspectWidgetDonor(face("00112"))
        val review = repository.previewBackgroundImport(donor.handle, "style0.bin", initial.projectId, "style0.bin")
        assertTrue(review.addedBackground)
        var result = repository.importBackground(review.ticket)
        assertEquals(imported.globalIndex + 1, result.widgets.last().globalIndex)
        assertEquals(imported.importedFromFaceId, result.widgets.last().importedFromFaceId)
        assertArrayEquals(art, pixels(result, result.widgets.last().globalIndex))
        repository = repository()
        result = repository.openProject(result.projectId)
        assertEquals(imported.importedFromFaceId, result.widgets.last().importedFromFaceId)
        assertArrayEquals(art, pixels(result, result.widgets.last().globalIndex))
        assertTrue(repository.prepareDirectInstall().copyBytes().isNotEmpty())
    }

    private class FailableDao(private val delegate: ProjectDao) : ProjectDao by delegate {
        var fail = false
        var insertCalls = 0
        override suspend fun insert(project: ProjectEntity): Long {
            insertCalls++
            if (fail) { fail = false; throw IOException("test database failure") }
            return delegate.insert(project)
        }
    }
}
