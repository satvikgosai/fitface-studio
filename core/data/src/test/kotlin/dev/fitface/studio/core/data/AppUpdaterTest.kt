package dev.fitface.studio.core.data

import android.content.Context
import dev.fitface.studio.core.model.AppRelease
import dev.fitface.studio.core.model.AppUpdateState
import dev.fitface.studio.core.model.AppVersion
import dev.fitface.studio.core.model.DiagnosticsLog
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun repeatedDownloadDoesNotStartAnotherJobAndCancelCannotBeUndoneByOldWork() = runBlocking {
        val release = AppRelease(
            AppVersion.parse("9.9.9")!!, "v9.9.9", "update.apk",
            "https://github.com/example/update.apk", 10,
        )
        val firstEntered = CountDownLatch(1)
        val bothEntered = CountDownLatch(2)
        val releaseCheck = CountDownLatch(1)
        val downloads = mockk<UpdateDownloads>()
        every { downloads.hasRoomFor(release) } answers {
            firstEntered.countDown()
            bothEntered.countDown()
            releaseCheck.await(5, TimeUnit.SECONDS)
            false
        }
        val updater = AppUpdater(
            mockk<Context>(relaxed = true), downloads,
            mockk<UpdateInstaller>(relaxed = true), mockk<DiagnosticsLog>(relaxed = true),
        )
        updater.mutableStateForTest().value = AppUpdateState.Available(release, "9.9.8")

        try {
            updater.download()
            assertTrue("Download must become busy before the worker runs", updater.state.value is AppUpdateState.Downloading)
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val firstJob = updater.workForTest()
            updater.download()
            assertFalse("second download reached the space check", bothEntered.await(200, TimeUnit.MILLISECONDS))
            updater.cancel()
            releaseCheck.countDown()
            firstJob.join()
            assertTrue(updater.state.value is AppUpdateState.Available)
        } finally {
            releaseCheck.countDown()
            updater.cancel()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun AppUpdater.mutableStateForTest(): MutableStateFlow<AppUpdateState> =
        AppUpdater::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(this) as MutableStateFlow<AppUpdateState>

    private fun AppUpdater.workForTest(): Job =
        AppUpdater::class.java.getDeclaredField("work").apply { isAccessible = true }
            .get(this) as Job
}
