package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetResizeKind
import dev.fitface.studio.core.model.WidgetRotationKind
import dev.fitface.studio.core.model.nextWidgetSize
import dev.fitface.studio.core.model.normalizedRotation
import dev.fitface.studio.core.model.widgetSizePercent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WidgetRotationTest {
    private val style = "style0.bin"
    private fun synthetic(width: Int = 80, height: Int = 30, angle: Int = 0): Fit3Container =
        container(ByteArray(100).apply {
            putU32(0, 13); putU32(0x0C, 100)
            putU16(0x18, 20); putU16(0x1A, 40)
            putU16(0x1C, width); putU16(0x1E, height)
            putU16(0x20, 0xFFFF); putU16(0x5C, angle)
        })

    /** A 52-byte Rule from ([x1],[y1]) to ([x2],[y2]), as the catalogue's are laid out. */
    private fun rule(x1: Int, y1: Int, x2: Int, y2: Int, thickness: Int = 6): Fit3Container =
        container(ByteArray(52).apply {
            putU32(0, WIDGET_BADGE.toLong()); putU32(4, 37); putU32(0x0C, 52)
            putU16(0x18, x1 and 0xFFFF); putU16(0x1A, y1 and 0xFFFF)
            putU16(0x1C, x2 and 0xFFFF); putU16(0x1E, y2 and 0xFFFF)
            for (colour in listOf(0x20, 0x24, 0x28, 0x2C)) putU32(colour, 0xFF05E4B5)
            this[0x30] = thickness.toByte(); this[0x31] = 1; putU16(0x32, 1)
        })

    /** A 76-byte vector arc with the given signed start and end angles. */
    private fun arc(start: Int, end: Int): Fit3Container =
        container(ByteArray(76).apply {
            putU32(0, WIDGET_VECTOR_ARC.toLong()); putU32(4, 29); putU32(0x0C, 76)
            putU16(0x18, 35); putU16(0x1A, 36); putU16(0x1C, 188); putU16(0x1E, 188)
            putU16(0x28, start and 0xFFFF); putU16(0x2A, end and 0xFFFF)
            putU32(0x2C, 0xFF3B3B3C); putU32(0x30, 0xFF3B3B3C); putU32(0x34, 0xFF01E455); putU32(0x38, 0xFF01E455)
            putU16(0x3C, 0xFFFF); putU16(0x40, 16); putU16(0x44, 1); putU16(0x46, 16)
        })

    private fun container(record: ByteArray): Fit3Container {
        val size = record.size
        val payload = ByteArray(STYLE_HEADER_SIZE + size + IMAGE_HEADER_SIZE + 2).apply {
            putU32(0, STYLE_MAGIC); putU32(4, 1); putU32(8, size.toLong()); putU32(12, (IMAGE_HEADER_SIZE + 2).toLong()); putU32(20, (STYLE_HEADER_SIZE + size).toLong())
            val image = STYLE_HEADER_SIZE + size
            putU16(image, 1); putU16(image + 2, 1); putU16(image + 4, IMAGE_RGB565); putU32(image + 8, 2)
            this[0x11] = 1
            record.copyInto(this, STYLE_HEADER_SIZE)
        }
        val names = listOf(style, "style1.bin", "aod.bin")
        val offset = CONTAINER_HEADER_SIZE + names.size * DIRECTORY_ENTRY_SIZE
        val bytes = ByteArray(offset + names.size * payload.size)
        "oppo".encodeToByteArray().copyInto(bytes)
        bytes.putU32(4, 1); bytes.putU32(8, (bytes.size - CONTAINER_HEADER_SIZE).toLong())
        bytes.putU32(12, names.size.toLong())
        names.forEachIndexed { i, name ->
            val dir = CONTAINER_HEADER_SIZE + i * DIRECTORY_ENTRY_SIZE
            "./SM-R390_00000_256x402/$name".encodeToByteArray().copyInto(bytes, dir)
            bytes.putU32(dir + 64, (offset + i * payload.size).toLong())
            bytes.putU32(dir + 68, payload.size.toLong())
            bytes.putU16(dir + 72, Crc16.ccittFalse(payload))
            payload.copyInto(bytes, offset + i * payload.size)
        }
        bytes.putU16(16, Crc16.ccittFalse(bytes, CONTAINER_HEADER_SIZE, bytes.size))
        return Fit3Container.parse(bytes)
    }
    private fun rotate(c: Fit3Container, angle: Int, names: List<String> = listOf(style)) =
        FaceEditor.rotateWidget(c, names, 0, 0, 20, 40, angle)
    private fun angle(c: Fit3Container, name: String = style) =
        FaceRecordParser.widgetGuides(c.entryByBasename(name)).single().rotationTenths

    @Test fun nativeMutationChangesOnlyTheAngleAndChecksumsAndResetsWithoutDrift() {
        val original = synthetic()
        for (requested in listOf(900, 1800, 2700, 3180, 3200, -421, 3601)) {
            val edit = rotate(original, requested)
            assertEquals(normalizedRotation(requested), angle(edit.container))
            assertEquals(original.fileSize, edit.container.fileSize)
            assertTrue(edit.container.validate().isValid)
            val entry = original.entryByBasename(style)
            val allowed = setOf(16, 17, CONTAINER_HEADER_SIZE + 72, CONTAINER_HEADER_SIZE + 73,
                entry.offset + STYLE_HEADER_SIZE + 0x5C, entry.offset + STYLE_HEADER_SIZE + 0x5D)
            val before = original.toByteArray(); val after = edit.container.toByteArray()
            assertTrue(before.indices.filter { before[it] != after[it] }.all { it in allowed })
            assertArrayEquals(before, rotate(edit.container, 0).container.toByteArray())
        }
        assertThrows(Fit3FormatException::class.java) { rotate(original, 3600) }
    }

    @Test fun scopeIsExplicitAndSelectedIdentityIsStrict() {
        val original = synthetic()
        val all = rotate(original, 900, listOf(style, "style1.bin")).container
        assertEquals(900, angle(all)); assertEquals(900, angle(all, "style1.bin")); assertEquals(0, angle(all, "aod.bin"))
        val aod = rotate(original, 3200, listOf("aod.bin")).container
        assertEquals(0, angle(aod)); assertEquals(3200, angle(aod, "aod.bin"))
        assertThrows(Fit3FormatException::class.java) { FaceEditor.rotateWidget(original, listOf(style), 1, 0, 20, 40, 900) }
        assertThrows(Fit3FormatException::class.java) { FaceEditor.rotateWidget(original, listOf(style), 0, 0, 21, 40, 900) }
    }

    @Test fun excessiveRuntimeCanvasIsRefusedButZeroRemainsAvailable() {
        assertThrows(Fit3FormatException::class.java) { rotate(synthetic(512, 512), 900) }
        val oversized = synthetic(512, 512, 3200)
        assertEquals(0, angle(rotate(oversized, 0).container))
        assertEquals(65535, angle(synthetic(angle = 65535))) // Preserve vendor values on read.
    }

    @Test fun rotatingAnEditedDuplicateRetainsItsOriginalSource() {
        val original = synthetic()
        val record = FaceRecordParser.scanWidgets(original.entryByBasename(style)).single()
        var current = StructuralEditor.duplicateWidget(original, listOf(style), 0, 13, 0, 20, 40).container
        current = FaceEditor.rotateWidget(current, listOf(style), 1, 0, 20, 40, 3180).container
        assertEquals(0, FaceRecordParser.duplicateSourceGlobalIndices(current.entryByBasename(style),
            original.entryByBasename(style))[1])
        assertEquals(record.globalIndex, FaceRecordParser.originalWidgetSources(current.entryByBasename(style),
            original.entryByBasename(style))[0])
    }

    @Test fun vendorRotatedCompositesRoundTripEveryAngleWithoutChangingOtherResources() {
        val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
        for (id in listOf("00105", "00124", "00112", "00106")) {
            val name = "SM-R390_${id}_256x402"
            val path = root.resolve("SM_R390/$name/$name.bin")
            assumeTrue("no corpus face $id", Files.isRegularFile(path))
            val original = Fit3Container.parse(Files.readAllBytes(path))
            for (entry in FaceResources.variantEntries(original)) {
                val guide = FaceRecordParser.widgetGuides(entry)
                    .firstOrNull { it.rotationKind == WidgetRotationKind.TEXT } ?: continue
                var current = original
                for (angle in listOf(900, 1800, 2700, 3181, guide.rotationTenths!!)) {
                    current = FaceEditor.rotateWidget(current, listOf(entry.basename), guide.globalIndex,
                        guide.sequenceId, guide.x, guide.y, angle).container
                    assertTrue(current.validate().isValid)
                }
                assertArrayEquals("$id/${entry.basename}", original.toByteArray(), current.toByteArray())
            }
        }
    }

    private fun line(c: Fit3Container, name: String = style) = FaceRecordParser.scanWidgets(c.entryByBasename(name)).single()
    private fun turn(c: Fit3Container, angle: Int, pristine: Fit3Container = c, names: List<String> = listOf(style)) =
        line(c).let { FaceEditor.rotateWidget(c, names, 0, it.sequenceId, it.x, it.y, angle, pristine = pristine).container }

    @Test fun aRuleTurnsAboutItsMidpointAndResetsToTheShippedBytes() {
        val original = rule(100, 200, 200, 200)
        assertEquals(0, angle(original))
        val vertical = turn(original, 900, original)
        line(vertical).let {
            assertEquals(listOf(150, 150, 150, 250), listOf(it.x, it.y, it.raw1C, it.raw1E))
            assertEquals(6, it.ruleThickness)
        }
        assertEquals(900, angle(vertical))
        assertEquals(original.fileSize, vertical.fileSize)
        // Only the endpoints and the two checksums change; colour, flag and thickness stay.
        val entry = original.entryByBasename(style)
        val endpoints = (entry.offset + STYLE_HEADER_SIZE + 0x18 until entry.offset + STYLE_HEADER_SIZE + 0x20).toSet()
        val before = original.toByteArray(); val after = vertical.toByteArray()
        assertTrue(before.indices.filter { before[it] != after[it] }.all {
            it in endpoints || it in setOf(16, 17, CONTAINER_HEADER_SIZE + 72, CONTAINER_HEADER_SIZE + 73)
        })
        assertArrayEquals(before, turn(vertical, 0, original).toByteArray())
        // A diagonal keeps its length and its midpoint to within half a pixel.
        line(turn(original, 450, original)).let {
            assertEquals(71, it.raw1C - it.x); assertEquals(71, it.raw1E - it.y)
            assertEquals(150.0, (it.x + it.raw1C) / 2.0, 0.5); assertEquals(200.0, (it.y + it.raw1E) / 2.0, 0.5)
        }
        assertThrows(Fit3FormatException::class.java) { turn(original, 455, original) }
        assertThrows(Fit3FormatException::class.java) { turn(original, 0, original) }
    }

    @Test fun aTurnedRuleIsResizedOnItsLadderAndKeepsItsTurn() {
        val original = rule(100, 200, 200, 200)
        var current = turn(original, 450, original)
        fun guide(c: Fit3Container): dev.fitface.studio.core.model.WidgetGuide {
            val anchor = FaceRecordParser.lineAnchor(line(c), line(original))
            return FaceRecordParser.widgetGuides(c.entryByBasename(style)).single().copy(
                originalWidth = anchor.width, originalHeight = anchor.height,
                rotationTenths = anchor.directionTenths, originalRotationTenths = anchor.originalDirectionTenths)
        }
        assertEquals(100, widgetSizePercent(guide(current)))
        repeat(3) {
            val next = requireNotNull(nextWidgetSize(guide(current), grow = false))
            val g = guide(current)
            current = StructuralEditor.resizeWidget(current, listOf(style), 0, WIDGET_BADGE, g.sequenceId,
                g.x, g.y, next.width, next.height, pristine = original).container
            assertEquals(next.percentOfOriginal, widgetSizePercent(guide(current)))
            assertEquals(450, guide(current).rotationTenths)
        }
        // Turning at 85% stays at 85% of the shipped line, now pointing straight down.
        current = turn(current, 900, original)
        assertEquals(85, widgetSizePercent(guide(current)))
        assertEquals(listOf(0, 85), line(current).let { listOf(it.raw1C - it.x, it.raw1E - it.y) })
        assertEquals(WidgetResizeKind.FIELDS, guide(current).resizeKind)
    }

    @Test fun anArcTurnsItsRangeAndResetsToTheVendorsExactPair() {
        val ring = arc(270, 630)
        assertEquals(2700, angle(ring))
        assertEquals(WidgetRotationKind.ARC, FaceRecordParser.widgetGuides(ring.entryByBasename(style)).single().rotationKind)
        val turned = FaceEditor.rotateWidget(ring, listOf(style), 0, 29, 35, 36, 2850).container
        assertEquals(listOf(285, 645), listOf(0x28, 0x2A).map { turned.entryByBasename(style).data.u16(STYLE_HEADER_SIZE + it) })
        assertEquals(2850, angle(turned))
        val back = FaceEditor.rotateWidget(turned, listOf(style), 0, 29, 35, 36, 2700).container
        assertArrayEquals(ring.toByteArray(), back.toByteArray())
        val quarter = FaceEditor.rotateWidget(arc(180, 270), listOf(style), 0, 29, 35, 36, 0).container
        val entry = quarter.entryByBasename(style)
        assertEquals(listOf(0, 90), listOf(0x28, 0x2A).map { entry.data.u16(STYLE_HEADER_SIZE + it) })
        assertThrows(Fit3FormatException::class.java) { FaceEditor.rotateWidget(ring, listOf(style), 0, 29, 35, 36, 2855) }
        // A decreasing pair has no proven reading, so it is not offered.
        val wrapped = arc(350, 110)
        assertNull(angle(wrapped))
        assertThrows(Fit3FormatException::class.java) { FaceEditor.rotateWidget(wrapped, listOf(style), 0, 29, 35, 36, 0) }
    }

    /**
     * Every catalogue Rule and vector arc, turned and turned back. A Rule is also walked a
     * rung down while turned and back up after, which is the order that would expose a
     * resize quietly undoing a turn, or a turn landing off the ladder.
     *
     * Recorded on the 99-face corpus: 84 Rules and the 71 arcs with a proven range.
     */
    @Test fun everyCatalogueLineAndArcTurnsAndComesBack() {
        val directory = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot"))).resolve("SM_R390")
        assumeTrue("no corpus at $directory", Files.isDirectory(directory))
        val failures = mutableListOf<String>()
        var lines = 0; var arcs = 0
        Files.list(directory).use { it.toList() }.sorted().forEach { folder ->
            val path = folder.resolve("${folder.fileName}.bin")
            if (!Files.isRegularFile(path)) return@forEach
            val original = Fit3Container.parse(Files.readAllBytes(path))
            for (entry in FaceResources.variantEntries(original)) {
                val name = entry.basename
                for (guide in FaceRecordParser.widgetGuides(entry)) {
                    val kind = guide.rotationKind ?: continue
                    // Text has its own sweep above, and artwork its own in `ArtworkTurnTest`.
                    if (kind == WidgetRotationKind.TEXT || kind == WidgetRotationKind.ARTWORK) continue
                    val label = "${folder.fileName}/$name #${guide.globalIndex}"
                    fun record(c: Fit3Container) = FaceRecordParser.scanWidgets(c.entryByBasename(name))
                        .single { it.globalIndex == guide.globalIndex }
                    fun rotate(c: Fit3Container, to: Int) = record(c).let {
                        FaceEditor.rotateWidget(c, listOf(name), it.globalIndex, it.sequenceId, it.x, it.y, to,
                            pristine = original).container.also { edited -> assertTrue(label, edited.validate().isValid) }
                    }
                    val start = guide.rotationTenths!!
                    try {
                        if (kind == WidgetRotationKind.ARC) {
                            arcs++
                            val back = rotate(rotate(rotate(original, start + 150), start + 3450), start)
                            if (!back.toByteArray().contentEquals(original.toByteArray())) failures += "$label: arc did not reset"
                            continue
                        }
                        lines++
                        val target = normalizedRotation(start + 150)
                        // Turned and straight back: the shipped line, its midpoint kept to a pixel.
                        val shipped = record(original)
                        val returned = record(rotate(rotate(original, target), start))
                        if (RuleGeometry.span(returned) != RuleGeometry.span(shipped) ||
                            abs(returned.x - shipped.x) > 1 || abs(returned.y - shipped.y) > 1) {
                            failures += "$label: a turn and back moved the line"
                        }
                        var current = rotate(original, target)
                        fun decorated(c: Fit3Container): dev.fitface.studio.core.model.WidgetGuide {
                            val anchor = FaceRecordParser.lineAnchor(record(c), record(original))
                            return FaceRecordParser.widgetGuides(c.entryByBasename(name)).single { it.globalIndex == guide.globalIndex }
                                .copy(originalWidth = anchor.width, originalHeight = anchor.height, rotationTenths = anchor.directionTenths)
                        }
                        if (widgetSizePercent(decorated(current)) != 100) failures += "$label: turned line is off its ladder"
                        val smaller = nextWidgetSize(decorated(current), grow = false)
                        if (smaller != null) {
                            val g = decorated(current)
                            current = StructuralEditor.resizeWidget(current, listOf(name), g.globalIndex, g.type, g.sequenceId,
                                g.x, g.y, smaller.width, smaller.height, pristine = original).container
                            val after = decorated(current)
                            if (after.width != smaller.width || after.height != smaller.height) {
                                failures += "$label: asked for ${smaller.width}x${smaller.height}, got ${after.width}x${after.height}"
                            }
                        }
                        current = rotate(current, start)
                        nextWidgetSize(decorated(current), grow = true)?.takeIf { smaller != null }?.let { larger ->
                            val g = decorated(current)
                            current = StructuralEditor.resizeWidget(current, listOf(name), g.globalIndex, g.type, g.sequenceId,
                                g.x, g.y, larger.width, larger.height, pristine = original).container
                        }
                        // A resize keeps the start point and a turn keeps the midpoint, so this
                        // path may shift the line; its shape has to be the shipped one exactly.
                        val now = record(current)
                        if (RuleGeometry.span(now) != RuleGeometry.span(shipped) || now.ruleThickness != shipped.ruleThickness) {
                            failures += "$label: came back ${RuleGeometry.span(now)}, shipped ${RuleGeometry.span(shipped)}"
                        }
                    } catch (error: Fit3FormatException) {
                        failures += "$label: ${error.message}"
                    }
                }
            }
        }
        assumeTrue("corpus holds no line or arc", lines + arcs > 0)
        assertEquals("$lines lines, $arcs arcs", emptyList<String>(), failures.take(10))
    }
}
