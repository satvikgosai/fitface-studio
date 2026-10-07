package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Widget colour: a same-size patch of each type's [WidgetSchema.ColorModel] words, and
 * nothing else — on synthetic records of all four colourable types, then on every
 * colourable widget of the 99-face catalogue.
 */
class WidgetColorTest {
    private val style = "style0.bin"
    private val red = 0xFFFF_2000.toInt()
    private val blue = 0xFF20_40FF.toInt()

    private fun pair(index: Int = 0, sequence: Int = 17, x: Int = 20, color: Long = 0xFFFFFFFF) =
        record(56, WIDGET_PAIR, index, sequence) {
            putU16(0x18, x); putU16(0x1A, 40); putU16(0x1C, 60); putU16(0x1E, 30); putU16(0x20, 0xFFFF)
            putU32(0x24, color); this[0x28] = 1
        }

    private fun comp(color: Long = 0xFF75D8D6) = record(100, WIDGET_COMP, 0, 0) {
        putU16(0x18, 20); putU16(0x1A, 40); putU16(0x1C, 80); putU16(0x1E, 30); putU16(0x20, 0xFFFF)
        putU32(0x58, color)
    }

    private fun rule(copy: Long = 0xFF05E4B5) = record(52, WIDGET_BADGE, 0, 37) {
        putU16(0x18, 10); putU16(0x1A, 200); putU16(0x1C, 200); putU16(0x1E, 200)
        putU32(0x20, 0xFF3B3B3C); putU32(0x24, 0xFF3B3B3C); putU32(0x28, 0xFF05E4B5); putU32(0x2C, copy)
        this[0x30] = 6
    }

    private fun arc() = record(76, WIDGET_VECTOR_ARC, 0, 29) {
        putU16(0x18, 35); putU16(0x1A, 36); putU16(0x1C, 188); putU16(0x1E, 188)
        putU16(0x28, 270); putU16(0x2A, 630)
        putU32(0x2C, 0xFF3B3B3C); putU32(0x30, 0xFF3B3B3C); putU32(0x34, 0xFF01E455); putU32(0x38, 0xFF01E455)
        putU16(0x3C, 0xFFFF); putU16(0x40, 16); putU16(0x44, 1); putU16(0x46, 16)
    }

    private fun record(size: Int, type: Int, index: Int, sequence: Int, fill: ByteArray.() -> Unit) =
        ByteArray(size).apply {
            putU32(0, type.toLong()); putU32(4, sequence.toLong()); putU32(0x0C, ((index shl 16) or size).toLong())
            fill()
        }

    private fun container(vararg records: ByteArray): Fit3Container {
        val size = records.sumOf { it.size }
        val payload = ByteArray(STYLE_HEADER_SIZE + size + IMAGE_HEADER_SIZE + 2).apply {
            putU32(0, STYLE_MAGIC); putU32(4, records.size.toLong()); putU32(8, size.toLong())
            putU32(12, (IMAGE_HEADER_SIZE + 2).toLong()); putU32(20, (STYLE_HEADER_SIZE + size).toLong())
            val image = STYLE_HEADER_SIZE + size
            putU16(image, 1); putU16(image + 2, 1); putU16(image + 4, IMAGE_RGB565); putU32(image + 8, 2)
            this[0x11] = 1
            var at = STYLE_HEADER_SIZE
            records.forEach { it.copyInto(this, at); at += it.size }
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

    private fun first(c: Fit3Container, name: String = style) =
        FaceRecordParser.scanWidgets(c.entryByBasename(name)).first()

    private fun recolor(c: Fit3Container, color: Int, names: List<String> = listOf(style)) = first(c).let {
        FaceEditor.recolorWidgetAcrossStyles(c, names, it.globalIndex, it.widgetType, it.sequenceId, it.x, it.y, color)
    }

    private fun changedFields(before: Fit3Container, after: Fit3Container, name: String = style): Set<Int> {
        val old = first(before, name); val new = first(after, name)
        return (WIDGET_FIXED_SIZE until old.recordSize step 4).filter { old.word(it) != new.word(it) }.toSet()
    }

    @Test fun eachTypeRewritesItsColourAndTheCopiesKeptEqualToItAndNothingElse() {
        for ((name, source, fields) in listOf(
            Triple("pair", container(pair()), setOf(0x24)),
            Triple("comp", container(comp()), setOf(0x58)),
            Triple("rule", container(rule()), setOf(0x28, 0x2C)),
            Triple("arc", container(arc()), setOf(0x34, 0x38)),
        )) {
            val edit = recolor(source, red)
            assertTrue(name, edit.container.validate().isValid)
            assertEquals(name, source.fileSize, edit.container.fileSize)
            assertEquals(name, listOf(style), edit.changedStyles)
            assertEquals(name, fields, changedFields(source, edit.container))
            fields.forEach { assertEquals(name, red.toLong() and 0xFFFF_FFFFL, first(edit.container).word(it)) }
            assertEquals(name, red, FaceRecordParser.widgetGuides(edit.container.entryByBasename(style)).single().colorArgb)
            // The other styles were not asked for, so they keep the colour they had.
            assertEquals(name, emptySet<Int>(), changedFields(source, edit.container, "style1.bin"))
        }
    }

    @Test fun aCopyThatAlreadyDifferedKeepsItsOwnBytes() {
        val source = container(rule(copy = 0xFF123456))
        assertEquals(setOf(0x28), changedFields(source, recolor(source, red).container))
    }

    @Test fun eachStyleCanTakeItsOwnColourInOneEdit() {
        val source = container(comp())
        val r = first(source)
        val edit = FaceEditor.recolorWidgetAcrossStyles(source, listOf(style, "style1.bin"), r.globalIndex,
            r.widgetType, r.sequenceId, r.x, r.y, mapOf(style to red, "style1.bin" to blue))
        assertEquals(red.toLong() and 0xFFFF_FFFFL, first(edit.container).colorWord)
        assertEquals(blue.toLong() and 0xFFFF_FFFFL, first(edit.container, "style1.bin").colorWord)
        assertEquals(0xFF75D8D6, first(edit.container, "aod.bin").colorWord)
    }

    @Test fun refusesWhatItCannotWriteFaithfully() {
        val source = container(comp())
        assertThrows(Fit3FormatException::class.java) { recolor(source, 0x80FF0000.toInt()) }
        assertThrows(Fit3FormatException::class.java) { recolor(source, 0xFF75D8D6.toInt()) }
        val translucent = container(comp(color = 0x0075D8D6))
        assertNull(FaceRecordParser.widgetGuides(translucent.entryByBasename(style)).single().colorArgb)
        assertThrows(Fit3FormatException::class.java) { recolor(translucent, red) }
        val static = container(record(40, 1, 0, 0) { putU16(0x1C, 0xFFFF) })
        assertNull(FaceRecordParser.widgetGuides(static.entryByBasename(style)).single().colorArgb)
        assertThrows(Fit3FormatException::class.java) { recolor(static, red) }
    }

    /**
     * Two Values following the same reading — 74 of the catalogue's 734 share a sequence
     * with another in their style — are each colourable, and one changes alone.
     */
    @Test fun valuesSharingASequenceAreRecolouredOneAtATime() {
        val source = container(pair(index = 0, x = 20), pair(index = 1, x = 120))
        val guides = FaceRecordParser.widgetGuides(source.entryByBasename(style))
        assertTrue(guides.all { it.colorArgb == 0xFFFF_FFFF.toInt() })
        val second = FaceRecordParser.scanWidgets(source.entryByBasename(style))[1]
        val edit = FaceEditor.recolorWidgetAcrossStyles(source, listOf(style), second.globalIndex,
            second.widgetType, second.sequenceId, second.x, second.y, red)
        val after = FaceRecordParser.widgetGuides(edit.container.entryByBasename(style))
        assertEquals(listOf(0xFFFF_FFFF.toInt(), red), after.map { it.colorArgb })
    }

    /** A colour is a property of a widget, not part of which widget it is. */
    @Test fun aRecolouredWidgetIsStillItsOriginal() {
        for (source in listOf(container(pair()), container(comp()), container(rule()), container(arc()))) {
            val edited = recolor(source, red).container
            val spec = WidgetSchema.spec(first(source).widgetType)
            val color = requireNotNull(spec.color)
            (listOf(color.offset) + color.copies).forEach { assertEquals(0L, spec.stableWord(it, 0xFF123456)) }
            assertEquals(mapOf(0 to 0), FaceRecordParser.originalWidgetSources(
                edited.entryByBasename(style), source.entryByBasename(style)))
            assertEquals(emptyMap<Int, Int>(), FaceRecordParser.duplicateSourceGlobalIndices(
                edited.entryByBasename(style), source.entryByBasename(style)))
        }
    }

    /**
     * Every colourable widget in the first style of every catalogue face takes a new colour
     * and comes back to its shipped bytes exactly.
     */
    @Test fun everyCatalogueColourTurnsAndComesBack() {
        val directory = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot"))).resolve("SM_R390")
        assumeTrue("no corpus at $directory", Files.isDirectory(directory))
        val counts = mutableMapOf<Int, Int>()
        Files.list(directory).use { it.toList() }.sorted().forEach { folder ->
            val path = folder.resolve("${folder.fileName}.bin")
            if (!Files.isRegularFile(path)) return@forEach
            val original = Fit3Container.parse(Files.readAllBytes(path))
            val name = FaceResources.selectableStyles(original).first().basename
            for (guide in FaceRecordParser.widgetGuides(original.entryByBasename(name))) {
                val shipped = guide.colorArgb ?: continue
                val label = "${folder.fileName}/$name #${guide.globalIndex}"
                val next = shipped xor 0x00FF_FFFF
                fun apply(c: Fit3Container, color: Int) = FaceEditor.recolorWidgetAcrossStyles(c, listOf(name),
                    guide.globalIndex, guide.type, guide.sequenceId, guide.x, guide.y, color).container
                val edited = apply(original, next)
                assertTrue(label, edited.validate().isValid)
                assertEquals(label, next, FaceRecordParser.widgetGuides(edited.entryByBasename(name))
                    .single { it.globalIndex == guide.globalIndex }.colorArgb)
                assertArrayEquals(label, original.toByteArray(), apply(edited, shipped).toByteArray())
                counts.merge(guide.type, 1, Int::plus)
            }
        }
        assumeTrue("corpus holds no colourable widget", counts.isNotEmpty())
        assertEquals(setOf(WIDGET_PAIR, WIDGET_COMP, WIDGET_BADGE, WIDGET_VECTOR_ARC), counts.keys)
    }
}
