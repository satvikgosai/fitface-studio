package dev.fitface.studio.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.FaceCatalog
import dev.fitface.studio.core.model.FaceStyleOption
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The app-private cache the library grid is painted from before the network is touched.
 *
 * Two things here fail silently rather than loudly, which is why they are pinned. The
 * catalogue's version is written by one expression and checked by another, so nothing
 * but a test notices when they stop agreeing — the cache simply goes cold and every
 * cold start pays for the network. And `cachedPackageBytes` sums files off disk for a
 * storage read-out the app does not have yet, so these tests are the only thing holding
 * it correct until one exists; a counted partial write would be a number the user reads
 * and cannot reconcile with anything.
 */
@RunWith(RobolectricTestRunner::class)
class PackageCacheTest {
    private lateinit var context: Context
    private lateinit var cache: PackageCache

    private val root: File get() = File(context.filesDir, "catalog-cache")
    private val catalogFile: File get() = File(root, "catalog.json")
    private val packagesDirectory: File get() = File(root, "packages")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cache = PackageCache(context, DiagnosticsLog())
        cache.clear()
    }

    @After
    fun tearDown() {
        cache.clear()
    }

    @Test
    fun aCatalogueWrittenToTheCacheReadsBackUnchanged() {
        cache.writeCatalog(catalog())

        val read = requireNotNull(cache.readCatalog()) { "the cache it just wrote was rejected" }

        assertEquals(catalog().faces, read.faces)
        assertEquals(3, read.styleCount)
        assertEquals(1_700_000_000_000L, read.fetchedAtEpochMillis)
        assertTrue("a catalogue served off disk has to say so", read.fromCache)
    }

    // The regression 2.7 leaves behind: `of()` stamped a literal 1 while `readCatalog`
    // compared against CacheVersion, so bumping the constant would have made the writer
    // produce caches its own reader throws away. Comparing the stamp to the constant is
    // the only check that survives a bump.
    @Test
    fun aWrittenCatalogueIsStampedWithTheVersionTheReaderAccepts() {
        cache.writeCatalog(catalog())

        val stamped = Json.parseToJsonElement(catalogFile.readText())
            .jsonObject
            .getValue("version")
            .jsonPrimitive
            .int

        assertEquals(CacheVersion, stamped)
    }

    @Test
    fun aCatalogueStampedWithAnotherVersionIsRejectedAndDeleted() {
        root.mkdirs()
        catalogFile.writeText(
            """{"version":${CacheVersion + 1},"fetchedAtEpochMillis":12,"faces":[]}""",
        )

        assertNull(cache.readCatalog())
        assertFalse("a cache no reader will ever accept must not survive", catalogFile.exists())
    }

    @Test
    fun cachedPackageBytesIgnoresAnUncommittedTemporary() {
        cache.writePackage(AppId, versionCode = 4, bytes = ByteArray(16) { 1 })
        File(packagesDirectory, "$AppId@5.apk.tmp").writeBytes(ByteArray(1024))

        assertEquals(16L, cache.cachedPackageBytes())
    }

    @Test
    fun aPackageIsReadableAfterWritingItAndANewerVersionEvictsIt() {
        val first = ByteArray(8) { 7 }
        val second = ByteArray(12) { 9 }

        cache.writePackage(AppId, versionCode = 1, bytes = first)
        assertArrayEquals(first, cache.readPackage(AppId, versionCode = 1))

        cache.writePackage(AppId, versionCode = 2, bytes = second)

        assertArrayEquals(second, cache.readPackage(AppId, versionCode = 2))
        assertNull("the superseded version is still cached", cache.readPackage(AppId, 1))
        assertEquals(second.size.toLong(), cache.cachedPackageBytes())
    }

    // Eviction's prefix match reaching a `.tmp` sibling is deliberate, not the same bug
    // as 2.11: the only one it can see belongs to an interrupted write of an older
    // version, and nothing else ever collects those.
    @Test
    fun writingAVersionSweepsAnInterruptedWriteOfAnother() {
        packagesDirectory.mkdirs()
        val abandoned = File(packagesDirectory, "$AppId@1.apk.tmp")
        abandoned.writeBytes(ByteArray(64))

        cache.writePackage(AppId, versionCode = 2, bytes = ByteArray(4))

        assertFalse("a partial write of an older version leaked", abandoned.exists())
        assertEquals(4L, cache.cachedPackageBytes())
    }

    private fun catalog() = FaceCatalog(
        faces = listOf(
            CatalogFace(
                productId = "product-46",
                faceId = "00046",
                name = "Test Face",
                description = "A face that only exists in this test.",
                appId = AppId,
                versionName = "1.0.2",
                versionCode = 12,
                packageSize = 2_048,
                styles = listOf(
                    FaceStyleOption(id = 0, previewUrl = "https://example.invalid/0.png"),
                    FaceStyleOption(id = 1, previewUrl = "https://example.invalid/1.png"),
                ),
            ),
            CatalogFace(
                productId = "product-22",
                faceId = "00022",
                name = "Other Face",
                description = "",
                appId = "dev.fitface.face00022",
                versionName = "3.1.0",
                versionCode = 31,
                packageSize = 4_096,
                styles = listOf(
                    FaceStyleOption(id = 0, previewUrl = "https://example.invalid/x.png"),
                ),
            ),
        ),
        styleCount = 3,
        fetchedAtEpochMillis = 1_700_000_000_000L,
    )

    /**
     * Two writers of one cache file both finish, and what lands is one of them whole.
     *
     * This is not hypothetical concurrency: nothing joined the catalogue load the library
     * starts on its first frame to the one the widget importer starts when it opens, so on
     * a cold start they could be in flight together. While every write shared a single
     * `<name>.tmp` they interleaved their bytes into that one file, and whichever renamed
     * second failed on a path the first had already consumed — "Could not cache the
     * catalogue", and a whole catalogue fetched again on the next launch.
     *
     * Honest about what this proves: it passes against the shared name too, because a 70 KB
     * write on a JVM closes the window most of the time. It holds the *invariant* — every
     * writer commits, what is read back is one whole catalogue, nothing is left behind —
     * rather than reproducing the failure. The unique scratch name is what makes the
     * invariant hold by construction, and [scratchFilesAreThisWritesOwnAndOldOnesAreSwept]
     * pins that half deterministically.
     */
    @Test
    fun concurrentWritersEachCommitAWholeCatalogue() {
        val first = catalog()
        val second = catalog().copy(styleCount = 99)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 8).map { index ->
            Thread {
                start.await()
                runCatching { cache.writeCatalog(if (index % 2 == 0) first else second) }
                    .onFailure(failures::add)
            }.apply { start() }
        }
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(emptyList<Throwable>(), failures.toList())
        // Whole, and one of the two — never a blend of both.
        val written = requireNotNull(cache.readCatalog()) { "no catalogue was committed" }
        assertTrue(written.styleCount == first.styleCount || written.styleCount == second.styleCount)
        assertEquals(first.faces.map { it.productId }, written.faces.map { it.productId })
        // And nothing is left behind for the next reader to trip over.
        assertEquals(
            emptyList<String>(),
            root.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") },
        )
    }

    /**
     * A write uses a scratch file of its own and cleans up after the ones that died.
     *
     * The shared `<name>.tmp` gave one thing for free: a write killed mid-flight left a
     * scratch file that the next write to the same target truncated and reused. A unique
     * name would leave it there for ever, so the sweep replaces that — and it has to leave
     * a *fresh* scratch file alone, because that one may belong to a writer still running.
     */
    @Test
    fun scratchFilesAreThisWritesOwnAndOldOnesAreSwept() {
        root.mkdirs()
        val abandoned = File(root, "catalog.json.dead-writer.tmp").apply {
            writeText("half a catalogue")
            setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000)
        }
        val live = File(root, "catalog.json.another-writer.tmp").apply { writeText("in flight") }

        cache.writeCatalog(catalog())

        assertFalse("an hour-old scratch file is swept", abandoned.exists())
        assertTrue("a scratch file that may still be in use is left alone", live.exists())
        assertEquals(catalog().faces.size, requireNotNull(cache.readCatalog()).faces.size)
    }

    private companion object {
        const val AppId = "dev.fitface.face00046"
    }
}
