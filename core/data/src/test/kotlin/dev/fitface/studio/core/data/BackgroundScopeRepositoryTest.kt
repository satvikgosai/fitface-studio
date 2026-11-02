package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.WatchFaceException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "This style only": a background replaced, added or imported for the selected style alone
 * leaves every other style's bytes as they were, and the repository's default still writes them
 * all; removing one paints it black in the same scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundScopeRepositoryTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var repository: WatchFaceRepositoryImpl

    @Before fun setup() {
        assumeTrue(Files.isDirectory(root.resolve("SM_R390")))
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FitFaceDatabase::class.java).allowMainThreadQueries().build()
        repository = WatchFaceRepositoryImpl(context, database.projectDao(),
            AndroidImageSource(context.contentResolver), context.contentResolver, DiagnosticsLog())
    }

    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) {
            File(context.filesDir, "projects").deleteRecursively()
            File(context.cacheDir, "pictures").deleteRecursively()
        }
    }

    @Test fun thisStyleOnlyReplacesTheSelectedStylesBackgroundAlone() = runBlocking {
        val opened = repository.openPackage(face("00112"))
        assertTrue(opened.offersBackgroundScope)
        val before = entries()
        val one = repository.replaceBackground(png(), ImagePlacement(fit = ImageFit.STRETCH), thisStyleOnly = true)
        val after = entries()
        assertFalse(before.getValue("style0.bin").contentEquals(after.getValue("style0.bin")))
        (before.keys - "style0.bin").forEach { name ->
            assertArrayEquals("$name kept its background", before.getValue(name), after.getValue(name))
        }
        assertEquals(opened.containerBytes, one.containerBytes)

        // All styles, the default, still reaches every style that has one.
        repository.replaceBackground(png(0xFF00AA00.toInt()), ImagePlacement(fit = ImageFit.STRETCH))
        val all = entries()
        opened.styleNames.forEach { name ->
            assertFalse("$name changed", after.getValue(name).contentEquals(all.getValue(name)))
        }
        assertArrayEquals("the always-on display is never joined to a style edit",
            before.getValue("aod.bin"), all.getValue("aod.bin"))
    }

    /**
     * Face 00108's first styles paint onto black while their siblings carry backgrounds, so the
     * whole-face edit could only replace the siblings'. Alone, the bare style is given its own.
     */
    @Test fun aBareStyleIsGivenItsOwnBackgroundWhileItsSiblingsKeepTheirs() = runBlocking {
        val opened = repository.openPackage(face("00108"))
        val bare = opened.styleNames.first { it !in opened.backgroundStyles }
        val selected = repository.currentSnapshot(bare)
        assertTrue(selected.canReplaceBackground)
        assertFalse(selected.selectedVariantHasBackground)
        assertTrue(selected.selectedCanTakeBackground)
        assertTrue(selected.backgroundAdds(thisStyleOnly = true))
        assertFalse(selected.backgroundAdds(thisStyleOnly = false))
        val before = entries()
        val added = repository.addBackground(png(), ImagePlacement(fit = ImageFit.STRETCH), thisStyleOnly = true)
        assertTrue(bare in added.backgroundStyles)
        assertEquals(opened.backgroundStyles.size + 1, added.backgroundStyles.size)
        val after = entries()
        (before.keys - bare).forEach { name ->
            assertArrayEquals("$name is untouched", before.getValue(name), after.getValue(name))
        }
    }

    @Test fun anImportedBackgroundCanGoToTheSelectedStyleAlone() = runBlocking {
        val opened = repository.openPackage(face("00112"))
        val donor = repository.inspectWidgetDonor(face("00076"))
        val preview = repository.previewBackgroundImport(donor.handle, "style0.bin", opened.projectId,
            "style0.bin", thisStyleOnly = true)
        assertEquals(listOf("style0.bin"), preview.changedVariants)
        val before = entries()
        repository.importBackground(preview.ticket)
        val after = entries()
        (before.keys - "style0.bin").forEach { name ->
            assertArrayEquals("$name is untouched", before.getValue(name), after.getValue(name))
        }
    }

    /**
     * Issue on face 00043: four styles, each its own background. With a later style selected,
     * this style alone changes it there and every other style keeps its own picture.
     */
    @Test fun eachStyleOf00043KeepsItsOwnBackground() = runBlocking {
        repository.openPackage(face("00043"))
        val selected = repository.currentSnapshot("style2.bin")
        assertTrue(selected.offersBackgroundScope)
        val before = entries()
        repository.replaceBackground(png(), ImagePlacement(fit = ImageFit.STRETCH), thisStyleOnly = true)
        val after = entries()
        assertFalse(before.getValue("style2.bin").contentEquals(after.getValue("style2.bin")))
        (before.keys - "style2.bin").forEach { name ->
            assertArrayEquals("$name kept its background", before.getValue(name), after.getValue(name))
        }
    }

    /**
     * Remove background paints it black, in the scope chosen, keeping its raster: the size does
     * not change, a second Remove is refused, Undo brings it back and a picture still replaces it.
     */
    @Test fun removingABackgroundPaintsItBlackInTheScopeChosen() = runBlocking {
        val opened = repository.openPackage(face("00043"))
        repository.currentSnapshot("style1.bin")
        val before = entries()
        val removed = repository.removeBackground(thisStyleOnly = true)
        assertEquals(opened.containerBytes, removed.containerBytes)
        assertTrue("it still has a raster a picture can replace", "style1.bin" in removed.backgroundStyles)
        assertFalse("but shows none, so Remove is not offered again", removed.selectedBackgroundShown)
        val container = Fit3Container.parse(repository.prepareDirectInstall().copyBytes())
        val entry = container.entryByBasename("style1.bin")
        val raster = requireNotNull(FaceRecordParser.backgroundImage(entry))
        assertTrue(FaceRecordParser.decodeImage(entry, raster).argb.all { it and 0xFFFFFF == 0 })
        val after = entries()
        (before.keys - "style1.bin").forEach { name ->
            assertArrayEquals("$name kept its background", before.getValue(name), after.getValue(name))
        }

        val again = runCatching { repository.removeBackground(thisStyleOnly = true) }.exceptionOrNull()
        assertEquals("There is no background here to remove.", (again as WatchFaceException).userMessage)
        assertFalse(repository.currentSnapshot().selectedBackgroundShown)

        repository.undo()
        assertArrayEquals(before.getValue("style1.bin"), entries().getValue("style1.bin"))
        repository.removeBackground(thisStyleOnly = true)
        repository.replaceBackground(png(), ImagePlacement(fit = ImageFit.STRETCH), thisStyleOnly = true)
        assertFalse(after.getValue("style1.bin").contentEquals(entries().getValue("style1.bin")))

        // All styles paints every style's.
        repository.removeBackground()
        val all = Fit3Container.parse(repository.prepareDirectInstall().copyBytes())
        opened.backgroundStyles.forEach { name ->
            val styled = all.entryByBasename(name)
            val image = requireNotNull(FaceRecordParser.backgroundImage(styled))
            assertTrue("$name is black", FaceRecordParser.decodeImage(styled, image).argb.all { it and 0xFFFFFF == 0 })
        }
    }

    private suspend fun entries(): Map<String, ByteArray> =
        Fit3Container.parse(repository.prepareDirectInstall().copyBytes()).entries
            .associate { it.basename to it.data.copyOf() }

    private fun face(id: String): FacePackage {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("no corpus face $id", Files.isRegularFile(path))
        return FacePackage(FacePackage.sourceKey("test-$id", 1, 0), "SM-R390_$id.apk", id, 0, 1,
            ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("assets/$name.bin")); zip.write(Files.readAllBytes(path)); zip.closeEntry()
                }
            }.toByteArray())
    }

    private fun png(color: Int = 0xFFCC2255.toInt()): String {
        val bitmap = Bitmap.createBitmap(256, 402, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val file = File(context.cacheDir, "pictures/b-${System.nanoTime()}.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file).toString()
    }
}
