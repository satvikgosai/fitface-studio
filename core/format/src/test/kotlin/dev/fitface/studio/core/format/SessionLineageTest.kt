package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SessionLineageTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue(Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    @Test fun pristineIdentityIsExactAcrossEveryCorpusVariant() {
        val dir = root.resolve("SM_R390")
        assumeTrue(Files.isDirectory(dir))
        Files.walk(dir).use { paths -> paths.filter { it.toString().endsWith(".bin") }.forEach { path ->
            val original = Fit3Container.parse(Files.readAllBytes(path))
            val state = SessionLineage.capture(original, original, null)
            state.validate(original, original, null)
            state.widgets.values.forEach { origins -> origins.forEach { (index, origin) ->
                assertEquals(index, origin.originalIndex)
                assertFalse(origin.duplicate)
            } }
        } }
    }

    @Test fun legacyAddedBackgroundDoesNotClaimTheMovedFirstStaticsOriginal() {
        val original = StructuralEditor.keepFirstStyles(face("00022"), 1).container
        val native = FaceRecordParser.scanWidgets(original.entryByBasename("style0.bin")).first()
        assertEquals(1, native.widgetType)
        val moved = FaceEditor.moveWidget(original, "style0.bin", native.globalIndex, native.widgetType,
            native.sequenceId, native.x + 7, native.y + 8).container
        val added = StructuralEditor.addBackgrounds(moved, listOf("style0.bin"), 256, 402,
            IntArray(256 * 402) { 0xFF123456.toInt() }).container
        val state = SessionLineage.capture(original, added, null)
        state.validate(original, added, null)
        assertNull(state.widgets.getValue("style0.bin").getValue(0).originalIndex)
        assertEquals(0, state.widgets.getValue("style0.bin").getValue(1).originalIndex)
    }

    @Test fun explicitPermutationDoesNotLoseIdentitiesOfSameTypeAndSequence() {
        val original = face("00003")
        val state = SessionLineage.capture(original, original, null)
        val twins = FaceRecordParser.scanWidgets(original.entryByBasename("style0.bin"))
            .filter { it.widgetType == 1 && it.sequenceId == 0 }.take(2)
        assertEquals(2, twins.size)
        val a = twins[0].globalIndex; val b = twins[1].globalIndex
        val swapped = state.remap("style0.bin") { when (it) { a -> b; b -> a; else -> it } }
        assertEquals(a, swapped.widgets.getValue("style0.bin").getValue(b).originalIndex)
        assertEquals(b, swapped.widgets.getValue("style0.bin").getValue(a).originalIndex)
        assertEquals(state, swapped.remap("style0.bin") { when (it) { a -> b; b -> a; else -> it } })
    }

    @Test fun foreignMissingDuplicatedAndInconsistentIdentitiesAreRefused() {
        val original = face("00105")
        val state = SessionLineage.capture(original, original, null)
        val records = state.widgets.getValue("style0.bin")
        val bad = listOf(
            state.copy(originalSha256 = "bad"),
            state.copy(variants = state.variants - "style0.bin"),
            state.copy(widgets = state.widgets - "style0.bin"),
            state.copy(widgets = state.widgets + ("style0.bin" to records.filterKeys { it != 0 })),
            state.append("style0.bin", 0, NativeWidgetOrigin(65535)),
            state.append("style0.bin", records.size, NativeWidgetOrigin(0)),
        )
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { it.validate(original, original, null) } }
    }
}
