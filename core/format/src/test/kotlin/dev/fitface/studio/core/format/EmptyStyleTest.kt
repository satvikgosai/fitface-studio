package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A style with every widget removed, which the editor allows — and the edits someone makes
 * next.
 *
 * Removing the last widget was always permitted, and every edit that could follow it was
 * refused: the shared guard demanded a non-empty widget table, so a custom face emptied by
 * hand answered "style0.bin: style needs widgets and images" to every edit, and could only
 * be got back by resetting it.
 */
class EmptyStyleTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val style = "style0.bin"

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    private fun records(c: Fit3Container) = FaceRecordParser.scanWidgets(c.entryByBasename(style))

    /** Every widget deleted, highest first, as the editor deletes them. */
    private fun emptied(source: Fit3Container): Fit3Container {
        var current = source
        records(source).reversed().forEach { record ->
            current = StructuralEditor.deleteWidget(current, listOf(style), record.globalIndex,
                record.widgetType, record.sequenceId, record.x, record.y,
                targetIndices = mapOf(style to record.globalIndex)).container
        }
        assertTrue(records(current).isEmpty())
        assertEquals(emptyList<ValidationIssue>(), current.validate().errors)
        return current
    }

    @Test
    fun anEmptyStyleTakesABackgroundAndAnImport() {
        val empty = emptied(CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID)))
        val panel = FaceRecordParser.panelSize(empty.entryByBasename(style))
        val withBackground = StructuralEditor.addBackgrounds(empty, listOf(style), panel.width, panel.height,
            IntArray(panel.width * panel.height) { 0xFF223344.toInt() }).container
        assertEquals(1, records(withBackground).size)
        assertEquals(emptyList<ValidationIssue>(), withBackground.validate().errors)
        val imported = WidgetImporter.importWidget(empty, style, face("00008"), style, 1).edit.container
        assertEquals(1, records(imported).size)
        assertEquals(emptyList<ValidationIssue>(), imported.validate().errors)
    }
}
