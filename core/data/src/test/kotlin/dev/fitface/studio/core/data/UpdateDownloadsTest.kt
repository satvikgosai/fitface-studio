package dev.fitface.studio.core.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cancelledWriteCannotDeleteTheNextAttemptsScratchFile() {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        val downloads = UpdateDownloads(context)
        val target = temporary.root.resolve("updates/update.apk")
        val firstWriting = CountDownLatch(1)
        val secondWriting = CountDownLatch(1)
        val failFirst = CountDownLatch(1)
        val finishSecond = CountDownLatch(1)
        val firstError = AtomicReference<Throwable?>()
        val secondError = AtomicReference<Throwable?>()
        val writer = UpdateDownloads::class.java.getDeclaredMethod(
            "writeStreaming", java.io.File::class.java, Function1::class.java,
        ).apply { isAccessible = true }

        val first = thread {
            firstError.set(runCatching {
                writer.invoke(downloads, target, { output: OutputStream ->
                    output.write('a'.code)
                    firstWriting.countDown()
                    assertTrue(failFirst.await(5, TimeUnit.SECONDS))
                    throw IOException("cancelled first attempt")
                })
            }.exceptionOrNull())
        }
        assertTrue(firstWriting.await(2, TimeUnit.SECONDS))
        val second = thread {
            secondError.set(runCatching {
                writer.invoke(downloads, target, { output: OutputStream ->
                    output.write('b'.code)
                    secondWriting.countDown()
                    assertTrue(finishSecond.await(5, TimeUnit.SECONDS))
                })
            }.exceptionOrNull())
        }
        try {
            assertTrue(secondWriting.await(2, TimeUnit.SECONDS))
            failFirst.countDown()
            first.join(2000)
            assertNotNull(firstError.get())
            finishSecond.countDown()
            second.join(2000)
            assertNull("the new attempt lost its scratch file", secondError.get())
            assertEquals("b", target.readText())
        } finally {
            failFirst.countDown()
            finishSecond.countDown()
            first.join(2000)
            second.join(2000)
        }
    }
}
