package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Where the app's per-style pictures come from.
 *
 * The container holds no per-style image the app could draw cheaply: `preview.bin` is
 * one raster per style at a reduced size, and reaching a style's own artwork means
 * decoding its whole raster section. The **package** ships the vendor's render of every
 * style as a plain PNG, which is what the Styles page and the projects list show.
 *
 * This sweep is what says that source can be trusted: the indices are the style
 * indices, every image is a real PNG at the panel's own 256 × 402, and a package that
 * ships none is a case the UI has to survive rather than an impossibility — face
 * `00031` is exactly that, six styles and no previews at all.
 */
class StylePreviewSweepTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private lateinit var packages: List<Path>

    @Before
    fun locatePackages() {
        val directory = root.resolve("packages")
        assumeTrue("no packages at $directory", Files.isDirectory(directory))
        packages = Files.list(directory).use { entries ->
            entries.asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".apk") }
                .sortedBy { it.fileName.toString() }
                .toList()
        }
        assumeTrue("corpus holds no packages", packages.isNotEmpty())
    }

    @Test
    fun everyStylePreviewIsAPanelSizedPngKeyedByItsStyleIndex() {
        var faces = 0
        var previews = 0
        var withoutPreviews = 0
        packages.forEach { path ->
            val apk = parseOrSkip(path) ?: return@forEach
            faces++
            if (apk.stylePreviews.isEmpty()) {
                withoutPreviews++
                return@forEach
            }
            val styleIndices = Fit3Container.parse(apk.binary).entries
                .mapNotNull { entry ->
                    Regex("""style(\d+)\.bin""").matchEntire(entry.basename)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                }
                .sorted()
            assertEquals(
                "${path.fileName} does not ship one preview per style entry",
                styleIndices,
                apk.stylePreviews.keys.sorted(),
            )
            apk.stylePreviews.forEach { (index, png) ->
                previews++
                assertTrue(
                    "${path.fileName} style $index preview is not a PNG",
                    png.size > PngHeaderSize && png.copyOf(PngSignature.size).contentEquals(
                        PngSignature,
                    ),
                )
                assertEquals(
                    "${path.fileName} style $index preview is not panel width",
                    PanelWidth,
                    png.readBigEndianInt(IhdrWidthOffset),
                )
                assertEquals(
                    "${path.fileName} style $index preview is not panel height",
                    PanelHeight,
                    png.readBigEndianInt(IhdrWidthOffset + 4),
                )
            }
        }
        assumeTrue("no package in the corpus carries a container", faces > 0)
        assertTrue("no style previews found across $faces faces", previews > 0)
        // Not an assertion about how many: the point is that "none" is a real case, and
        // that it is rare enough not to be the assumption the UI is built on.
        assertTrue(
            "$withoutPreviews of $faces faces ship no style previews",
            withoutPreviews < faces / 2,
        )
    }

    @Test
    fun localisedArtworkIsNotMistakenForAStylePreview() {
        // Every package repeats the same file names under `assets/<locale>/`. Reading
        // one of those as style N would show the wrong language's face, and on a face
        // with more locales than styles it would invent styles that do not exist.
        val checked = packages.firstNotNullOfOrNull { path ->
            val apk = parseOrSkip(path) ?: return@firstNotNullOfOrNull null
            apk.takeIf { it.stylePreviews.isNotEmpty() }?.let { path to it }
        }
        assumeTrue("no package with previews in the corpus", checked != null)
        val (path, apk) = checked!!
        val localised = Files.newInputStream(path).use { stream ->
            java.util.zip.ZipInputStream(stream).use { zip ->
                generateSequence { zip.nextEntry }
                    .map { it.name }
                    .count { it.matches(Regex("""assets/[a-z]{2}_[A-Z]{2}/SM-R390_.*\.png""")) }
            }
        }
        assumeTrue("${path.fileName} ships no localised artwork", localised > 0)
        assertTrue(
            "${path.fileName} has $localised localised images and only " +
                "${apk.stylePreviews.size} previews were kept",
            apk.stylePreviews.size < localised,
        )
    }

    private fun parseOrSkip(path: Path): Fit3Apk? = try {
        Fit3Apk.parse(Files.readAllBytes(path), retainMembers = false)
    } catch (_: Fit3NoContainerException) {
        null
    }

    private fun ByteArray.readBigEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff shl 24) or
            (this[offset + 1].toInt() and 0xff shl 16) or
            (this[offset + 2].toInt() and 0xff shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private companion object {
        val PngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        const val PngHeaderSize = 24
        const val IhdrWidthOffset = 16
        const val PanelWidth = 256
        const val PanelHeight = 402
    }
}
