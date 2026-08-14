package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.model.DirectInstallPayload
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Device-proven payloads, checked byte for byte against what this Kotlin encoder
 * produces. The recorded payloads are not redistributable, so these cases skip when
 * the fixture tree is absent — [IdentityTransferProtocolSyntheticTest] covers the same
 * encoder rules without them.
 */
class IdentityTransferProtocolTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.fixtureRoot")))

    /**
     * A fixture's bytes, looked up by name under the configured fixture root.
     *
     * Two layouts are accepted: `payloads/<name>.bin` for a standalone checkout, and
     * the original combined-workspace paths so an existing corpus keeps working.
     */
    private fun locate(name: String, vararg legacy: String): Path? =
        (listOf("payloads/$name.bin") + legacy)
            .map(root::resolve)
            .firstOrNull(Files::isRegularFile)

    private val fixtures = listOf(
        Fixture(
            name = "00046-stock",
            legacyPath = "SM-R390_00046/assets/SM-R390_00046_256x402.bin",
            alternateLegacyPath = "artifacts/SM-R390_00046/assets/SM-R390_00046_256x402.bin",
            faceId = 46,
            size = 1_611_265,
            windows = 41,
            lastWindow = 27_265,
            firstCrc = 0x70748A40,
            lastCrc = 0xB0511152.toInt(),
            sha256 = "b71f60a843048d78151799936d1f030fd5ff1691ef8d7db1a67fca01cd27f755",
        ),
        Fixture(
            name = "00046-edited",
            legacyPath = "fit3-builder/gate-sessions/00046-device-1/artifacts/custom-image.bin",
            faceId = 46,
            size = 1_611_265,
            windows = 41,
            lastWindow = 27_265,
            firstCrc = 0x46E86CA3,
            lastCrc = 0x58E57B14,
            sha256 = "27fb0745809b1cfc5df91de2f9949c56929a314161268302337d7f4ad57c5e76",
        ),
        Fixture(
            name = "00106-stock",
            legacyPath = "SM-R390_00106/assets/SM-R390_00106_256x402.bin",
            alternateLegacyPath = "artifacts/SM-R390_00106/assets/SM-R390_00106_256x402.bin",
            faceId = 106,
            size = 1_880_368,
            windows = 48,
            lastWindow = 19_168,
            firstCrc = 0x32AAE257,
            lastCrc = 0x76C5CB24,
            sha256 = "af34ca4ded7e49311fbe522f9e07112ffd8e8ee2bcb50743eb5fc67113dedffc",
        ),
        Fixture(
            name = "00106-edited",
            legacyPath = "fit3-builder/gate-sessions/00106-device-1/artifacts/custom-image.bin",
            faceId = 106,
            size = 1_880_368,
            windows = 48,
            lastWindow = 19_168,
            firstCrc = 0xE2249025.toInt(),
            lastCrc = 0x76C5CB24,
            sha256 = "235f60925608e8a5c3d5eff33bb0cacb14e553ffdeb941ade3a3efd7d9295cdf",
        ),
    )

    @Test
    fun allProvenPayloadsMatchKotlinDescriptorWindowsCrcAndInstallPacket() {
        val located = fixtures.associateWith { locate(it.name, *it.legacyPaths) }
        assumeTrue(
            "recorded payload fixtures are not available under $root",
            located.values.all { it != null },
        )
        fixtures.forEach { fixture ->
            val bytes = Files.readAllBytes(located.getValue(fixture)!!)
            val fileName = "SM-R390_${fixture.faceId.toString().padStart(5, '0')}_256x402.bin"
            val payload = DirectInstallPayload.create(
                faceId = fixture.faceId,
                samplerId = 2,
                fileName = fileName,
                bytes = bytes,
            )

            assertEquals(fixture.size, bytes.size)
            assertEquals(fixture.sha256, bytes.sha256())
            assertEquals(fixture.sha256, payload.sha256)
            assertEquals(fixture.windows, IdentityTransferProtocol.windowCount(bytes.size))
            assertArrayEquals(
                "33bin,/user/wf/$fileName,${fixture.size}".encodeToByteArray(),
                IdentityTransferProtocol.descriptor(fileName, fixture.size),
            )
            assertArrayEquals(
                byteArrayOf(0x04, 0x04, fixture.faceId.toByte(), 0x1d, 0x02),
                WatchfaceInstallProtocol.request(payload),
            )

            val first = ByteArrayOutputStream()
            assertEquals(
                IdentityTransferProtocol.WINDOW_BYTES,
                IdentityTransferProtocol.writeWindow(first, bytes, 0),
            )
            assertEquals(fixture.firstCrc, first.toByteArray().littleEndianCrc())

            val last = ByteArrayOutputStream()
            assertEquals(
                fixture.lastWindow,
                IdentityTransferProtocol.writeWindow(last, bytes, fixture.windows - 1),
            )
            assertEquals(fixture.lastWindow + 4, last.size())
            assertEquals(fixture.lastCrc, last.toByteArray().littleEndianCrc())

            assertThrows(Exception::class.java) {
                IdentityTransferProtocol.writeWindow(
                    ByteArrayOutputStream(),
                    bytes,
                    fixture.windows,
                )
            }
        }
    }

    private data class Fixture(
        val name: String,
        val legacyPath: String,
        val alternateLegacyPath: String? = null,
        val faceId: Int,
        val size: Int,
        val windows: Int,
        val lastWindow: Int,
        val firstCrc: Int,
        val lastCrc: Int,
        val sha256: String,
    ) {
        val legacyPaths: Array<String>
            get() = listOfNotNull(legacyPath, alternateLegacyPath).toTypedArray()
    }
}

private fun ByteArray.littleEndianCrc(): Int {
    val offset = size - 4
    return (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { value -> "%02x".format(value.toInt() and 0xff) }
