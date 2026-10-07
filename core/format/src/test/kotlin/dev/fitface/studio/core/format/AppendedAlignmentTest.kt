package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An appended record — a duplicate, or a restored widget — is drawn where its source was.
 *
 * A reference resolves only against an earlier record, so a widget whose target is at or
 * after its own index sits on the face. Appended at the end, every index is earlier, and
 * the same target can name a real widget: on face `00016` the date names 20, and once
 * duplicating it grew the face past 20, every further copy jumped to copy #20 — off the
 * face. These pin the copy and the restore to the place their source was drawn.
 */
class AppendedAlignmentTest {
    private val style = "style0.bin"

    /** A Value at [x],[y], aligned by code 0 to [target]. */
    private fun value(index: Int, target: Int, x: Int, y: Int) = ByteArray(56).apply {
        putU32(0, WIDGET_PAIR.toLong()); putU32(4, 17); putU32(0x0C, ((index shl 16) or 56).toLong())
        putU16(0x18, x); putU16(0x1A, y); putU16(0x1C, 40); putU16(0x1E, 20)
        putU16(0x20, 0); putU16(0x22, target)
        putU32(0x24, 0xFFFFFFFF); this[0x28] = 1
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
        val names = listOf(style, "aod.bin")
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

    private fun guides(c: Fit3Container) = FaceRecordParser.widgetGuides(c.entryByBasename(style))
    private fun target(c: Fit3Container, index: Int) =
        FaceRecordParser.scanWidgets(c.entryByBasename(style)).single { it.globalIndex == index }.liveAlignment?.targetGlobalIndex

    private fun duplicate(c: Fit3Container, index: Int): Fit3Container {
        val r = FaceRecordParser.scanWidgets(c.entryByBasename(style)).single { it.globalIndex == index }
        return StructuralEditor.duplicateWidget(c, listOf(style), r.globalIndex, r.widgetType, r.sequenceId, r.x, r.y).container
    }

    @Test fun copiesOfAFaceRelativeWidgetStayWhereItIsAsTheFaceGrowsPastItsTarget() {
        // Index 0 names 5, which does not exist yet: it sits on the face at 20,40.
        var c = container(value(0, target = 5, x = 20, y = 40), value(1, target = 1, x = 100, y = 200))
        repeat(8) { c = duplicate(c, 0) }
        guides(c).filter { it.globalIndex != 1 }.forEach {
            assertEquals("#${it.globalIndex}", 20 to 40, it.drawLeft to it.drawTop)
        }
        // Copies at 2..5 still name nothing with 5 and keep their bytes; from 6 on, 5 is a
        // real record, so they name nothing by name instead.
        (2..5).forEach { assertEquals(5, target(c, it)) }
        (6..9).forEach { assertEquals(WidgetImporter.ROOT_TARGET, target(c, it)) }
        // A self-reference is face-relative too: its copy must not align to its source.
        c = duplicate(c, 1)
        assertEquals(100 to 200, guides(c).last().let { it.drawLeft to it.drawTop })
    }

    @Test fun aCopyOfAWidgetAlignedToARealWidgetKeepsThatReference() {
        var c = container(value(0, target = 0xFFFF, x = 30, y = 50), value(1, target = 0, x = 10, y = 10))
        assertEquals(40 to 60, guides(c)[1].let { it.drawLeft to it.drawTop })
        c = duplicate(c, 1)
        assertEquals(0, target(c, 2))
        assertEquals(40 to 60, guides(c)[2].let { it.drawLeft to it.drawTop })
    }

    @Test fun aRestoredFaceRelativeWidgetComesBackWhereItWas() {
        var c = container(value(0, target = 0, x = 0, y = 0), value(1, target = 3, x = 60, y = 90),
            value(2, target = 2, x = 5, y = 5))
        val r = FaceRecordParser.scanWidgets(c.entryByBasename(style))[1]
        val removed = StructuralEditor.removeWidget(c, listOf(style), r.globalIndex, r.widgetType, r.sequenceId,
            r.x, r.y, requireFinal = false)
        c = removed.container
        // Grow the face past index 3, so the saved target would name a real widget.
        repeat(3) { c = duplicate(c, 0) }
        c = StructuralEditor.appendWidget(c, listOf(style), removed.removedRecords).container
        assertEquals(60 to 90, guides(c).last().let { it.drawLeft to it.drawTop })
        assertEquals(WidgetImporter.ROOT_TARGET, target(c, guides(c).last().globalIndex))
    }
}
