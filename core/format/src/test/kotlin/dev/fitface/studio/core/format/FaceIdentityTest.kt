package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** A container renumbered to another face ID names only that ID, and changes only what names it. */
class FaceIdentityTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    private fun setting(container: Fit3Container) =
        requireNotNull(FaceResources.settingOrNull(container))

    /**
     * The custom template under `00201`: the header CRC, each directory record's path and CRC,
     * and `setting.bin`'s ID. Every other byte is the same, at the same offset, name slots
     * included: those are the bytes T3 installed.
     */
    @Test
    fun theTemplateRenumberedChangesOnlyWhatNamesTheFace() {
        val original = CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID))
        val moved = FaceIdentity.renumber(original, "00201")

        assertEquals(original.fileSize, moved.fileSize)
        assertTrue(moved.entries.all { it.path.startsWith("./SM-R390_00201_256x402/") })
        assertEquals("00201", SettingRecord.parse(setting(moved)).faceId)
        assertTrue(DeliveryChecks.check(moved, "00201").canSend)
        assertFalse(DeliveryChecks.check(moved, "00006").canSend)

        // The header's body CRC; each directory record's path and CRC; setting.bin's ID.
        val setting = setting(original)
        val allowed = buildSet {
            addAll(0x10..0x11)
            original.entries.indices.forEach { index ->
                val record = CONTAINER_HEADER_SIZE + index * DIRECTORY_ENTRY_SIZE
                addAll(record until record + 0x40)
                addAll(record + 0x48..record + 0x49)
            }
            addAll(setting.offset + 0x10 until setting.offset + 0x15)
        }
        val before = original.toByteArray()
        val after = moved.toByteArray()
        val changed = before.indices.filter { before[it] != after[it] }
        assertTrue("changed outside the face's names: ${changed.filterNot(allowed::contains)}", changed.all(allowed::contains))
        assertTrue(changed.any { it in setting.offset until setting.end })
        // A lead byte, then the old name, in both slots.
        assertEquals("SM-R390_00006_256x402", String(setting(moved).data, 0x39, 21, Charsets.US_ASCII))
        assertEquals("SM-R390_00006_256x402", String(setting(moved).data, 0x79, 21, Charsets.US_ASCII))
    }

    /** Every catalogue face goes to `00201` and passes the delivery checks there, and comes back byte for byte. */
    @Test
    fun everyCatalogueFaceRenumbersAndBackExactly() {
        val faces = root.resolve("SM_R390")
        assumeTrue("corpus is not available at $root", Files.isDirectory(faces))
        val checked = faces.listDirectoryEntries("SM-R390_*_256x402").mapNotNull { directory ->
            val bin = directory.resolve("${directory.name}.bin")
            if (!Files.isRegularFile(bin)) return@mapNotNull null
            val id = directory.name.removePrefix("SM-R390_").substring(0, 5)
            val bytes = Files.readAllBytes(bin)
            val moved = FaceIdentity.renumber(Fit3Container.parse(bytes), "00201")
            assertEquals(directory.name, emptyList<DeliveryChecks.Refusal>(), DeliveryChecks.check(moved, "00201").refusals)
            assertArrayEquals(directory.name, bytes, FaceIdentity.renumber(moved, id).toByteArray())
            id
        }
        assertTrue("expected the catalogue, checked ${checked.size}", checked.size >= 99)
    }

    @Test
    fun anIdThatIsNotFiveDigitsIsRefused() {
        val template = face(CustomFaceTemplate.FACE_ID)
        listOf("201", "002010", "0020a", "").forEach { id ->
            assertThrows(id, IllegalArgumentException::class.java) { FaceIdentity.renumber(template, id) }
        }
    }
}
