package dev.fitface.studio.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.model.AppRelease
import dev.fitface.studio.core.model.AppVersion
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.FaceStyleOption
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HttpCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cancellingUpdateDownloadClosesBlockedHttpCall() = runBlocking {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        val downloads = UpdateDownloads(context)
        val client = mockk<OkHttpClient>()
        val blocked = blockedCall()
        every { client.newCall(any()) } returns blocked.call
        downloads.replaceClient(client)
        val release = AppRelease(
            AppVersion.parse("9.9.9")!!, "v9.9.9", "update.apk",
            "https://github.com/example/update.apk", 10,
        )

        val work = launch(Dispatchers.IO) { runCatching { downloads.download(release) {} } }
        try {
            assertTrue(blocked.entered.await(2, TimeUnit.SECONDS))
            work.cancel()
            assertTrue("HTTP call stayed blocked", blocked.cancelled.await(1, TimeUnit.SECONDS))
        } finally {
            blocked.release.countDown()
            work.join()
        }
    }

    @Test
    fun cancellingFaceDownloadClosesBlockedHttpCallBeforeCaching() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = mockk<PackageCache>(relaxed = true)
        every { cache.readPackage(any(), any()) } returns null
        val repository = FaceCatalogRepositoryImpl(context, cache, DiagnosticsLog())
        val client = mockk<OkHttpClient>()
        val blocked = blockedCall()
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            when {
                request.url.encodedPath.endsWith("gearAppUpdateCheck.as") ->
                    textCall(request, "<result><resultCode>0</resultCode>" +
                        "<appInfo><resultCode>1</resultCode></appInfo></result>")
                request.url.encodedPath.endsWith("gearAppDownload.as") ->
                    textCall(request, "<result><appInfo><resultCode>1</resultCode>" +
                        "<downloadURI>https://samsungapps.com/example.apk</downloadURI>" +
                        "<contentSize>10</contentSize></appInfo></result>")
                else -> blocked.call
            }
        }
        repository.replaceClient(client)
        val face = CatalogFace(
            productId = "face", faceId = "00046", name = "Face", description = "",
            appId = "sm_r390_00046", versionName = "1", versionCode = 1,
            packageSize = 10, styles = listOf(FaceStyleOption(0, "https://samsungapps.com/preview.png")),
        )

        val work = launch(Dispatchers.IO) { runCatching { repository.downloadPackage(face, 0) {} } }
        try {
            assertTrue(blocked.entered.await(2, TimeUnit.SECONDS))
            work.cancel()
            assertTrue("face HTTP call stayed blocked", blocked.cancelled.await(1, TimeUnit.SECONDS))
        } finally {
            blocked.release.countDown()
            work.join()
        }
        verify(exactly = 0) { cache.writePackage(any(), any(), any()) }
    }

    private data class BlockedCall(
        val call: Call,
        val entered: CountDownLatch,
        val cancelled: CountDownLatch,
        val release: CountDownLatch,
    )

    private fun blockedCall(): BlockedCall {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = mockk<Call>(relaxed = true)
        every { call.execute() } answers {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("test call released")
        }
        every { call.cancel() } answers { cancelled.countDown(); release.countDown() }
        return BlockedCall(call, entered, cancelled, release)
    }

    private fun textCall(request: Request, xml: String): Call = mockk {
        every { execute() } returns Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(xml.toResponseBody()).build()
        every { cancel() } returns Unit
    }

    private fun Any.replaceClient(client: OkHttpClient) {
        javaClass.getDeclaredField("client").apply { isAccessible = true }.set(this, client)
    }
}
