package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class CorpusParityTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    /** The corpus is never committed, so skip rather than fail without it. */
    @Before
    fun requireCorpus() {
        val required = fixtures.map { it.relativePath } +
            listOf("SM-R390_00046.apk", "SM-R390_00106.apk")
        assumeTrue(
            "corpus is not available at $root",
            required.all { Files.isRegularFile(root.resolve(it)) },
        )
    }

    private val fixtures = listOf(
        Fixture(
            "SM_R390/SM-R390_00022_256x402/SM-R390_00022_256x402.bin",
            4_117_664,
            13,
            "da3c6d3f88f4ea13e2c5ac1517aba8f55b44fa8fb3a9a16781212f01ecc120ae",
        ),
        Fixture(
            "SM_R390/SM-R390_00028_256x402/SM-R390_00028_256x402.bin",
            3_003_548,
            10,
            "7ed2598420a7a03e5b84a1e7a62fc7866cddd4b5ce2671640525fd1a01985877",
        ),
        Fixture(
            "SM_R390/SM-R390_00046_256x402/SM-R390_00046_256x402.bin",
            1_610_640,
            14,
            "ed9bdb386f02f2175dd35392b643f41734553ffeb0752d84d3f5f1bb99738ba4",
        ),
        Fixture(
            "SM_R390/SM-R390_00079_256x402/SM-R390_00079_256x402.bin",
            3_037_917,
            19,
            "2e55b59e11c72c99a16a15f5863fb1c56ea40630a8d425d56b01b054fdb986fd",
        ),
        Fixture(
            "SM_R390/SM-R390_00106_256x402/SM-R390_00106_256x402.bin",
            1_879_589,
            16,
            "73568618086c6054a82c11fc96717a471e73ff997b1dfc16f070cd520f400093",
        ),
        Fixture(
            "SM_R390/SM-R390_00108_256x402/SM-R390_00108_256x402.bin",
            2_581_912,
            16,
            "8c7363ce2c9f6e5960fb3ffa96e4eea93c4abbd986757a87fa8792264e1be002",
        ),
        Fixture(
            "SM-R390_00046/assets/SM-R390_00046_256x402.bin",
            1_611_265,
            17,
            "b71f60a843048d78151799936d1f030fd5ff1691ef8d7db1a67fca01cd27f755",
        ),
        Fixture(
            "SM-R390_00106/assets/SM-R390_00106_256x402.bin",
            1_880_368,
            19,
            "af34ca4ded7e49311fbe522f9e07112ffd8e8ee2bcb50743eb5fc67113dedffc",
        ),
    )

    @Test
    fun allReferenceContainersValidateAndRoundTripByteIdentically() {
        fixtures.forEach { fixture ->
            val original = Files.readAllBytes(root.resolve(fixture.relativePath))
            val container = Fit3Container.parse(original)

            assertEquals(fixture.size, original.size)
            assertEquals(fixture.entryCount, container.entries.size)
            assertEquals(fixture.sha256, original.sha256())
            assertTrue("${fixture.relativePath}: ${container.validate().errors}", container.validate().isValid)
            assertArrayEquals(original, container.toByteArray())
        }
    }

    @Test
    fun allObservedStyleImageAndWidgetStreamsScanCompletely() {
        var imageCount = 0
        fixtures.forEach { fixture ->
            val container = Fit3Container.parse(
                Files.readAllBytes(root.resolve(fixture.relativePath)),
            )
            container.entries.filter {
                it.basename == "aod.bin" ||
                    it.basename == "preview.bin" ||
                    it.basename.startsWith("style")
            }.forEach { entry ->
                imageCount += FaceRecordParser.scanImages(entry).size
                if (entry.basename != "preview.bin") {
                    FaceRecordParser.scanWidgets(entry)
                }
            }
        }
        assertEquals(831, imageCount)
    }

    @Test
    fun everyStyleHasOneRenderedReferencePreview() {
        fixtures.forEach { fixture ->
            val container = Fit3Container.parse(
                Files.readAllBytes(root.resolve(fixture.relativePath)),
            )
            val styleCount = container.entries.count {
                it.basename.matches(Regex("""style\d+\.bin"""))
            }
            val previews = FaceRecordParser.scanImages(
                container.entryByBasename("preview.bin"),
            )

            assertEquals(fixture.relativePath, styleCount, previews.size)
            assertTrue(previews.all { it.width > 0 && it.height > 0 })
        }
    }

    /**
     * A background edit reaches exactly the styles that have a background.
     *
     * Faces `00022` (every style) and `00108` (styles 0–3) carry no panel-sized
     * raster — they draw their widgets straight onto the watch's black panel. Their
     * first raster is a 37×28 icon and a 204×204 dial respectively, and tinting that
     * as though it were the background repainted artwork instead.
     *
     * `00108` is the reason this is per style rather than per container: it does carry
     * a background in styles 4 and 5, and refusing the whole face because styles 0–3
     * lack one meant those two could never be edited. Only a face with no background
     * anywhere — `00022` — is refused outright.
     */
    @Test
    fun aTintReachesEveryStyleWithABackgroundAndLeavesTheOthersUntouched() {
        var tinted = 0
        var refused = 0
        fixtures.forEach { fixture ->
            val original = Files.readAllBytes(root.resolve(fixture.relativePath))
            val container = Fit3Container.parse(original)
            val styles = container.entries.filter {
                it.basename.matches(Regex("""style\d+\.bin"""))
            }
            val withBackground = styles.filter { FaceRecordParser.backgroundImage(it) != null }

            if (withBackground.isEmpty()) {
                assertThrows(Fit3FormatException::class.java) {
                    FaceEditor.tintBackgrounds(container, red = 0, green = 255, blue = 255)
                }
                assertArrayEquals(original, container.toByteArray())
                refused++
                return@forEach
            }
            val edit = FaceEditor.tintBackgrounds(container, red = 0, green = 255, blue = 255)
            assertEquals(original.size, edit.container.fileSize)
            assertTrue(fixture.relativePath, edit.container.validate().isValid)
            assertTrue(fixture.relativePath, edit.changedPayloadBytes > 0)
            assertEquals(
                fixture.relativePath,
                withBackground.map { it.basename },
                edit.changedStyles,
            )
            styles.filterNot { it in withBackground }.forEach { bare ->
                assertArrayEquals(
                    "${fixture.relativePath}/${bare.basename}",
                    bare.data,
                    edit.container.entryByBasename(bare.basename).data,
                )
            }
            tinted++
        }
        assertEquals(7, tinted)
        assertEquals(1, refused)
    }

    @Test
    fun bothRealApksCanBeParsedAndRepackedWithIdenticalBinary() {
        mapOf(
            "SM-R390_00046.apk" to "Minimalist",
            "SM-R390_00106.apk" to "Fitness pro 3",
        ).forEach { (filename, expectedName) ->
            val original = Files.readAllBytes(root.resolve(filename))
            val apk = Fit3Apk.parse(original)
            val rebuilt = apk.rebuildWithBinary(apk.binary)
            val reparsed = Fit3Apk.parse(rebuilt)

            assertEquals(apk.faceId, reparsed.faceId)
            assertEquals(0, apk.samplerId)
            assertEquals(0, reparsed.samplerId)
            assertEquals(expectedName, apk.faceName)
            assertEquals(expectedName, reparsed.faceName)
            assertArrayEquals(apk.binary, reparsed.binary)
            assertTrue(Fit3Container.parse(reparsed.binary).validate().isValid)
        }
    }

    @Test
    fun fitnessProImageBackedWidgetsResolveWithoutMutatingContainer() {
        val bytes = Files.readAllBytes(
            root.resolve("SM-R390_00106/assets/SM-R390_00106_256x402.bin"),
        )
        val container = Fit3Container.parse(bytes)
        val style = container.entryByBasename("style0.bin")
        val preview = FaceRecordParser.scanImages(
            container.entryByBasename("preview.bin"),
        ).first().let { image ->
            FaceRecordParser.decodeImage(container.entryByBasename("preview.bin"), image)
        }

        val layers = FaceRecordParser.widgetImageLayers(
            entry = style,
            originalEntry = style,
            reference = preview,
        ).associateBy { it.globalIndex }

        assertEquals(setOf(6, 7, 8, 9, 10, 11), layers.keys)

        // The weather sprite is RGB565+A, so the watch honours its alpha and the
        // editor is allowed to cut its background away when the widget moves.
        assertEquals(26 to 26, layers.getValue(6).frame.run { width to height })
        assertFalse(layers.getValue(6).isOpaque)
        val firstWeatherFrame = FaceRecordParser.scanImages(style)[1]
            .let { FaceRecordParser.decodeImage(style, it) }
        val maskedWeather = layers.getValue(6).frame
        maskedWeather.argb.indices.forEach { index ->
            if (maskedWeather.argb[index] ushr 24 != 0) {
                assertEquals(firstWeatherFrame.argb[index], maskedWeather.argb[index])
            }
        }
        assertTrue(maskedWeather.argb.any { it ushr 24 != 0 })
        assertTrue(maskedWeather.argb.any { it ushr 24 == 0 })

        // The four clock digits are plain RGB565: no alpha channel at all. The watch
        // blits their whole 50x90 rectangle, black backdrop included, so the preview
        // must not pretend they are cut out.
        listOf(7, 8, 9, 10).forEach { index ->
            val layer = layers.getValue(index)
            assertEquals(50 to 90, layer.frame.run { width to height })
            assertTrue("widget $index is an opaque RGB565 sprite", layer.isOpaque)
            assertTrue(
                "widget $index must stay fully opaque",
                layer.frame.argb.all { it ushr 24 == 0xFF },
            )
        }
        assertEquals(30 to 90, layers.getValue(11).frame.run { width to height })
        assertTrue(layers.getValue(11).isOpaque)
        assertArrayEquals(bytes, container.toByteArray())
    }

    @Test
    fun widgetMoveChangesOnlyCoordinatesAndRequiredCrcFields() {
        listOf(
            "SM-R390_00046/assets/SM-R390_00046_256x402.bin",
            "SM-R390_00106/assets/SM-R390_00106_256x402.bin",
        ).forEach { relativePath ->
            val originalBytes = Files.readAllBytes(root.resolve(relativePath))
            val source = Fit3Container.parse(originalBytes)
            val entry = source.entryByBasename("style0.bin")
            val widget = FaceRecordParser.scanWidgets(entry)
                .first { it.width in 1..255 && it.height in 1..401 }
            val targetX = if (widget.x < Short.MAX_VALUE) widget.x + 1 else widget.x - 1
            val targetY = if (widget.y < Short.MAX_VALUE) widget.y + 1 else widget.y - 1

            val edit = FaceEditor.moveWidget(
                source = source,
                entryBasename = entry.basename,
                globalIndex = widget.globalIndex,
                widgetType = widget.widgetType,
                sequenceId = widget.sequenceId,
                x = targetX,
                y = targetY,
            )
            val editedBytes = edit.container.toByteArray()
            val coordinateStart = entry.offset + widget.recordOffset + 0x18
            val entryCrcStart =
                CONTAINER_HEADER_SIZE + entry.index * DIRECTORY_ENTRY_SIZE + 72
            val allowedOffsets =
                (coordinateStart until coordinateStart + 4).toSet() +
                    setOf(16, 17, entryCrcStart, entryCrcStart + 1)
            val changedOffsets = originalBytes.indices.filter {
                originalBytes[it] != editedBytes[it]
            }

            assertEquals(relativePath, originalBytes.size, editedBytes.size)
            assertTrue(relativePath, changedOffsets.isNotEmpty())
            assertTrue(
                "$relativePath changed unexpected offsets: " +
                    changedOffsets.filterNot(allowedOffsets::contains),
                changedOffsets.all(allowedOffsets::contains),
            )
            assertTrue(
                relativePath,
                changedOffsets.any { it in coordinateStart until coordinateStart + 4 },
            )
            assertArrayEquals(originalBytes, source.toByteArray())
            assertTrue(edit.container.validate().isValid)
            val moved = FaceRecordParser.scanWidgets(
                edit.container.entryByBasename(entry.basename),
            ).single { it.globalIndex == widget.globalIndex }
            assertEquals(targetX, moved.x)
            assertEquals(targetY, moved.y)
        }
    }

    @Test
    fun widgetMoveAcrossAllStylesIsAtomicAndChangesOnlyCoordinatesAndCrcs() {
        val originalBytes = Files.readAllBytes(
            root.resolve("SM-R390_00106/assets/SM-R390_00106_256x402.bin"),
        )
        val source = Fit3Container.parse(originalBytes)
        val styles = source.entries.filter {
            it.basename.matches(Regex("""style\d+\.bin"""))
        }
        val widgetsByStyle = styles.associateWith(FaceRecordParser::scanWidgets)
        val widget = requireNotNull(
            widgetsByStyle.getValue(styles.first()).firstOrNull { candidate ->
                styles.all { style ->
                    widgetsByStyle.getValue(style).count {
                        it.globalIndex == candidate.globalIndex &&
                            it.widgetType == candidate.widgetType &&
                            it.sequenceId == candidate.sequenceId
                    } == 1
                }
            },
        )
        val targetX = if (widget.x < Short.MAX_VALUE) widget.x + 1 else widget.x - 1
        val targetY = if (widget.y < Short.MAX_VALUE) widget.y + 1 else widget.y - 1

        val edit = FaceEditor.moveWidgetAcrossStyles(
            source = source,
            entryBasenames = styles.map { it.basename },
            globalIndex = widget.globalIndex,
            widgetType = widget.widgetType,
            sequenceId = widget.sequenceId,
            x = targetX,
            y = targetY,
        )
        val editedBytes = edit.container.toByteArray()
        val allowedOffsets = mutableSetOf(16, 17)
        styles.forEach { entry ->
            val record = widgetsByStyle.getValue(entry).single {
                it.globalIndex == widget.globalIndex &&
                    it.widgetType == widget.widgetType &&
                    it.sequenceId == widget.sequenceId
            }
            val coordinateStart = entry.offset + record.recordOffset + 0x18
            allowedOffsets += coordinateStart until coordinateStart + 4
            val entryCrcStart =
                CONTAINER_HEADER_SIZE + entry.index * DIRECTORY_ENTRY_SIZE + 72
            allowedOffsets += entryCrcStart
            allowedOffsets += entryCrcStart + 1

            val moved = FaceRecordParser.scanWidgets(
                edit.container.entryByBasename(entry.basename),
            ).single { it.globalIndex == widget.globalIndex }
            assertEquals(entry.basename, targetX, moved.x)
            assertEquals(entry.basename, targetY, moved.y)
        }
        val changedOffsets = originalBytes.indices.filter {
            originalBytes[it] != editedBytes[it]
        }

        assertEquals(styles.map { it.basename }, edit.changedStyles)
        assertEquals(originalBytes.size, editedBytes.size)
        assertTrue(changedOffsets.isNotEmpty())
        assertTrue(
            "all-style move changed unexpected offsets: " +
                changedOffsets.filterNot(allowedOffsets::contains),
            changedOffsets.all(allowedOffsets::contains),
        )
        assertArrayEquals(originalBytes, source.toByteArray())
        assertTrue(edit.container.validate().isValid)

        assertThrows(Fit3FormatException::class.java) {
            FaceEditor.moveWidgetAcrossStyles(
                source = source,
                entryBasenames = styles.map { it.basename } + "preview.bin",
                globalIndex = widget.globalIndex,
                widgetType = widget.widgetType,
                sequenceId = widget.sequenceId,
                x = targetX,
                y = targetY,
            )
        }
        assertArrayEquals(originalBytes, source.toByteArray())
    }

    @Test
    fun widgetMoveAcrossVariantsUpdatesMatchingAodRecord() {
        val source = Fit3Container.parse(
            Files.readAllBytes(
                root.resolve("SM-R390_00106/assets/SM-R390_00106_256x402.bin"),
            ),
        )
        val styles = source.entries.filter {
            it.basename.matches(Regex("""style\d+\.bin"""))
        }
        val aod = source.entryByBasename("aod.bin")
        val aodWidgets = FaceRecordParser.scanWidgets(aod)
        val widget = FaceRecordParser.scanWidgets(styles.first()).first { candidate ->
            aodWidgets.count {
                it.widgetType == candidate.widgetType &&
                    it.sequenceId == candidate.sequenceId &&
                    it.x == candidate.x &&
                    it.y == candidate.y
            } == 1
        }
        val moved = FaceEditor.moveWidgetAcrossStyles(
            source = source,
            entryBasenames = styles.map { it.basename } + aod.basename,
            globalIndex = widget.globalIndex,
            widgetType = widget.widgetType,
            sequenceId = widget.sequenceId,
            x = widget.x + 1,
            y = widget.y + 1,
        )

        val movedAod = FaceRecordParser.scanWidgets(
            moved.container.entryByBasename(aod.basename),
        ).single {
            it.widgetType == widget.widgetType &&
                it.sequenceId == widget.sequenceId &&
                it.x == widget.x + 1 &&
                it.y == widget.y + 1
        }
        assertTrue(movedAod.globalIndex != widget.globalIndex)
        assertTrue(aod.basename in moved.changedStyles)
        assertTrue(moved.container.validate().isValid)
    }

    @Test
    fun badgeMovePreservesEndpointsAndOnlyChangesGeometryAndCrcs() {
        val originalBytes = Files.readAllBytes(
            root.resolve("SM-R390_00106/assets/SM-R390_00106_256x402.bin"),
        )
        val source = Fit3Container.parse(originalBytes)
        val entry = source.entryByBasename("style0.bin")
        val badge = FaceRecordParser.scanWidgets(entry).single {
            it.widgetType == WIDGET_BADGE
        }
        val originalEndX = badge.width.toShort().toInt()
        val originalEndY = badge.height.toShort().toInt()
        val deltaX = 3
        val deltaY = 4

        val edit = FaceEditor.moveWidget(
            source = source,
            entryBasename = entry.basename,
            globalIndex = badge.globalIndex,
            widgetType = badge.widgetType,
            sequenceId = badge.sequenceId,
            x = badge.x + deltaX,
            y = badge.y + deltaY,
        )
        val moved = FaceRecordParser.scanWidgets(
            edit.container.entryByBasename(entry.basename),
        ).single { it.globalIndex == badge.globalIndex }
        val editedBytes = edit.container.toByteArray()
        val geometryStart = entry.offset + badge.recordOffset + 0x18
        val entryCrcStart =
            CONTAINER_HEADER_SIZE + entry.index * DIRECTORY_ENTRY_SIZE + 72
        val allowedOffsets =
            (geometryStart until geometryStart + 8).toSet() +
                setOf(16, 17, entryCrcStart, entryCrcStart + 1)
        val changedOffsets = originalBytes.indices.filter {
            originalBytes[it] != editedBytes[it]
        }

        assertEquals(badge.x + deltaX, moved.x)
        assertEquals(badge.y + deltaY, moved.y)
        assertEquals(originalEndX + deltaX, moved.width.toShort().toInt())
        assertEquals(originalEndY + deltaY, moved.height.toShort().toInt())
        assertTrue(changedOffsets.all(allowedOffsets::contains))
        assertArrayEquals(originalBytes, source.toByteArray())
        assertTrue(edit.container.validate().isValid)
    }

    private data class Fixture(
        val relativePath: String,
        val size: Int,
        val entryCount: Int,
        val sha256: String,
    )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") {
            "%02x".format(it)
        }
}
