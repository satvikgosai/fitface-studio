package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The identity and style-count checks a container has to pass before it is sent.
 *
 * The corpus sweep is the half that matters for regressions: these checks were made blocking
 * on the strength of every vendor container already passing them, so a face that stops
 * passing is a reason to revisit the check, not to ship it.
 */
class DeliveryChecksTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    private fun codes(report: DeliveryChecks.Report) = report.refusals.map { it.code }

    @Test
    fun everyCatalogueContainerPassesWithNothingEmpty() {
        val faces = root.resolve("SM_R390")
        assumeTrue("corpus is not available at $root", Files.isDirectory(faces))
        val checked = faces.listDirectoryEntries("SM-R390_*_256x402").mapNotNull { directory ->
            val bin = directory.resolve("${directory.name}.bin")
            if (!Files.isRegularFile(bin)) return@mapNotNull null
            val id = directory.name.removePrefix("SM-R390_").substring(0, 5)
            val report = DeliveryChecks.check(Fit3Container.parse(Files.readAllBytes(bin)), id)
            assertEquals(directory.name, emptyList<DeliveryChecks.Refusal>(), report.refusals)
            assertEquals(directory.name, emptyList<String>(), report.emptyVariants)
            id
        }
        assertTrue("expected the catalogue, checked ${checked.size}", checked.size >= 99)
    }

    @Test
    fun theCustomTemplatePasses() {
        val template = CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID))
        assertEquals(emptyList<DeliveryChecks.Refusal>(),
            DeliveryChecks.check(template, CustomFaceTemplate.FACE_ID).refusals)
    }

    @Test
    fun aStyleCountOfZeroIsRefused() {
        // The R390 Studio defect Fit3-App repairs: count zero, everything else in order.
        val source = face("00046")
        val report = DeliveryChecks.check(withSetting(source) { it[0x34] = 0 }, "00046")
        assertEquals(listOf("style_count_mismatch"), codes(report))
    }

    @Test
    fun aDefaultStyleBeyondTheLastIsRefused() {
        val source = face("00046")
        val report = DeliveryChecks.check(withSetting(source) { it[0x35] = 9 }, "00046")
        assertEquals(listOf("default_style_out_of_range"), codes(report))
    }

    @Test
    fun aSettingThatNamesAnotherFaceIsRefused() {
        val source = face("00046")
        val report = DeliveryChecks.check(withSetting(source) { "00047".toByteArray().copyInto(it, 0x10) }, "00046")
        assertEquals(listOf("face_id_setting"), codes(report))
    }

    @Test
    fun nameSlotsAreNotChecked() {
        // The watch never reads them, and 00018 ships a descriptive name in them.
        val source = face("00046")
        val report = DeliveryChecks.check(withSetting(source) { it[0x79 + 12] = '7'.code.toByte() }, "00046")
        assertTrue(report.canSend)
    }

    @Test
    fun aPackageNamedForAnotherFaceIsRefused() {
        assertEquals(listOf("face_id_package"), codes(DeliveryChecks.check(face("00046"), "00047")))
    }

    @Test
    fun anotherContainerVersionIsRefused() {
        val bytes = face("00046").toByteArray()
        bytes.putU32(4, 3)
        assertEquals(listOf("container_version"), codes(DeliveryChecks.check(Fit3Container.parse(bytes), "00046")))
    }

    @Test
    fun aMissingAlwaysOnEntryIsRefused() {
        val source = face("00046")
        val aod = source.entryByBasename("aod.bin").index
        val edited = StructuralEditor.rebuild(source, emptyMap(), removedEntries = setOf(aod)).container
        assertEquals(listOf("required_entry"), codes(DeliveryChecks.check(edited, "00046")))
    }

    @Test
    fun anEmptiedStyleIsReportedButNotRefused() {
        var current = CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID))
        FaceRecordParser.scanWidgets(current.entryByBasename("style0.bin")).reversed().forEach { record ->
            current = StructuralEditor.removeWidget(current, listOf("style0.bin"), record.globalIndex,
                record.widgetType, record.sequenceId, record.x, record.y, requireFinal = false).container
        }
        val report = DeliveryChecks.check(current, CustomFaceTemplate.FACE_ID)
        assertTrue(report.canSend)
        assertEquals(listOf("style0.bin"), report.emptyVariants)
    }

    @Test
    fun elevenStylesAreRefused() {
        val report = DeliveryChecks.check(synthetic(styles = 11), "00001")
        assertEquals(listOf("too_many_styles"), codes(report))
    }

    @Test
    fun aGapInStyleNumbersIsRefused() {
        val report = DeliveryChecks.check(synthetic(styles = 3, skipStyle = 1), "00001")
        assertEquals(listOf("style_numbering"), codes(report))
    }

    @Test
    fun aPathOutsideTheFaceDirectoryIsRefused() {
        val report = DeliveryChecks.check(synthetic(styles = 1, extraPath = "./elsewhere/font_0.bin"), "00001")
        assertEquals(listOf("entry_path"), codes(report))
    }

    @Test
    fun aSyntheticContainerShapedLikeTheCatalogueIsAccepted() {
        assertTrue(DeliveryChecks.check(synthetic(styles = 10), "00001").canSend)
    }

    private fun withSetting(source: Fit3Container, patch: (ByteArray) -> Unit): Fit3Container {
        val entry = source.entryByBasename("setting.bin")
        val data = entry.data.copyOf().also(patch)
        return StructuralEditor.rebuild(source, mapOf(entry.index to data)).container
    }

    /** A minimal container with the catalogue's metadata shape and [styles] one-widget styles. */
    private fun synthetic(styles: Int, skipStyle: Int? = null, extraPath: String? = null): Fit3Container {
        val id = "00001"
        val directory = "./SM-R390_${id}_256x402/"
        val setting = ByteArray(SettingRecord.SIZE).also {
            "LQ_WF".toByteArray().copyInto(it)
            it.putU32(0x0C, 0x12345678)
            id.toByteArray().copyInto(it, 0x10)
            it[0x34] = styles.toByte()
            it[0x36] = 0xFF.toByte()
            it[0x37] = 0xFF.toByte()
            "SM-R390_${id}_256x402".toByteArray().copyInto(it, 0x39)
            "SM-R390_${id}_256x402".toByteArray().copyInto(it, 0x79)
        }
        val style = ByteArray(STYLE_HEADER_SIZE).also {
            it.putU32(0, 0x12345678)
            it.putU32(4, 1)
        }
        val numbers = (0 until styles + (if (skipStyle != null) 1 else 0)).filter { it != skipStyle }
        val entries = buildList {
            add("${directory}aod.bin" to style)
            add("${directory}preview.bin" to ByteArray(4))
            add("${directory}setting.bin" to setting)
            numbers.forEach { add("${directory}style$it.bin" to style) }
            extraPath?.let { add(it to ByteArray(4)) }
        }
        val bodyStart = CONTAINER_HEADER_SIZE + entries.size * DIRECTORY_ENTRY_SIZE
        val bytes = ByteArray(bodyStart + entries.sumOf { it.second.size })
        "oppo".toByteArray().copyInto(bytes)
        bytes.putU32(4, 4)
        bytes.putU32(8, bytes.size - CONTAINER_HEADER_SIZE)
        bytes.putU32(12, entries.size)
        var cursor = bodyStart
        entries.forEachIndexed { index, (path, payload) ->
            val record = CONTAINER_HEADER_SIZE + index * DIRECTORY_ENTRY_SIZE
            path.toByteArray().copyInto(bytes, record)
            bytes.putU32(record + 0x40, cursor)
            bytes.putU32(record + 0x44, payload.size)
            bytes.putU16(record + 0x48, Crc16.ccittFalse(payload))
            payload.copyInto(bytes, cursor)
            cursor += payload.size
        }
        bytes.putU16(0x10, Crc16.ccittFalse(bytes, CONTAINER_HEADER_SIZE, bytes.size))
        return Fit3Container.parse(bytes)
    }
}
