package dev.fitface.studio.core.format

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `parse` refuses to inflate.
 *
 * Every member of a package is decompressed and held until parsing finishes — including
 * with `retainMembers = false`, which only drops the list afterwards — and the download
 * ceiling bounds the *compressed* bytes. So a package well inside 32 MiB could inflate
 * until the process died, before its container or its face id had been looked at, and
 * `OutOfMemoryError` is an Error rather than an Exception so nothing on the way out
 * would have caught it either.
 */
class Fit3ApkBoundsTest {
    @Test
    fun aMemberThatInflatesPastTheLimitIsRefusedRatherThanAllocated() {
        val bomb = zip {
            // Zeros, so 80 MiB of member compresses to a couple of hundred kilobytes —
            // which is the whole point: the archive stays small, the member does not.
            putNextEntry(ZipEntry("assets/big.bin"))
            val chunk = ByteArray(1 shl 20)
            repeat(80) { write(chunk) }
            closeEntry()
        }
        assertTrue("the bomb should be small on disk", bomb.size < 1 shl 20)

        val failure = assertThrows(Fit3FormatException::class.java) { Fit3Apk.parse(bomb) }

        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("inflates past"))
    }

    @Test
    fun aPackageWithAbsurdlyManyMembersIsRefused() {
        val many = zip {
            repeat(5_000) { index ->
                putNextEntry(ZipEntry("assets/$index.txt"))
                write(byteArrayOf(index.toByte()))
                closeEntry()
            }
        }

        val failure = assertThrows(Fit3FormatException::class.java) { Fit3Apk.parse(many) }

        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("members"))
    }

    /**
     * The limits must not be in the way of an ordinary package. A real face is 571
     * members and 5.9 MiB inflated; this is the shape, not the size, and it has to reach
     * the container check rather than a bound.
     */
    @Test
    fun anOrdinarySizedPackageStillReachesTheContainerCheck() {
        val ordinary = zip {
            repeat(600) { index ->
                putNextEntry(ZipEntry("assets/SM-R390_00046_1_$index.png"))
                write(ByteArray(8 * 1024) { it.toByte() })
                closeEntry()
            }
        }

        // No container in it, so this is the answer a package of this size should get:
        // the parse ran to the end and found no watch-face binary.
        assertThrows(Fit3NoContainerException::class.java) { Fit3Apk.parse(ordinary) }
    }

    private fun zip(build: ZipOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use(build)
        return output.toByteArray()
    }
}
