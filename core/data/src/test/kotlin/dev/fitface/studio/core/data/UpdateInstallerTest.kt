package dev.fitface.studio.core.data

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateInstallerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun failedApkReadAbandonsAlreadyCreatedSession() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val packages = mockk<PackageManager>()
        val packageInstaller = mockk<PackageInstaller>(relaxed = true)
        val session = mockk<PackageInstaller.Session>(relaxed = true)
        every { context.packageManager } returns packages
        every { packages.packageInstaller } returns packageInstaller
        every { packageInstaller.createSession(any()) } returns 42
        every { packageInstaller.openSession(42) } returns session
        every { session.openWrite(any(), any(), any()) } returns ByteArrayOutputStream()

        val missingApk = temporary.root.resolve("missing-update.apk")
        val outcome = UpdateInstaller(context).install(missingApk) {}

        assertTrue(outcome is InstallOutcome.Failed)
        verify(exactly = 1) { packageInstaller.abandonSession(42) }
    }
}
