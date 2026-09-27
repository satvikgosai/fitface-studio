package dev.fitface.studio.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.FaceStyleOption
import dev.fitface.studio.core.model.WatchFaceException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FaceCatalogDiagnosticsTest {
    @Test
    fun invalidPackageAddressDoesNotExposeSignedPathInTechnicalDetail() = runBlocking {
        val secret = "private-token-in-path"
        val url = "https://untrusted.example/$secret?signature=also-private"
        val error = requestWithMetadata(url)

        assertTrue(error.technicalDetail.orEmpty().contains("untrusted.example"))
        assertFalse(error.technicalDetail.orEmpty().contains(secret))
    }

    @Test
    fun rejectedRedirectDoesNotExposeSignedPathInTechnicalDetail() = runBlocking {
        val secret = "private-token-in-path"
        val url = "https://samsungapps.com/example.apk"
        val redirect = "https://untrusted.example/$secret?signature=also-private"
        val error = requestWithMetadata(url, redirect)

        assertTrue(error.technicalDetail.orEmpty().contains("untrusted.example"))
        assertFalse(error.technicalDetail.orEmpty().contains(secret))
    }

    private suspend fun requestWithMetadata(metadataUrl: String, redirectedUrl: String? = null): WatchFaceException {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = mockk<PackageCache>(relaxed = true)
        every { cache.readPackage(any(), any()) } returns null
        val repository = FaceCatalogRepositoryImpl(context, cache, DiagnosticsLog())
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            val xml = when {
                request.url.encodedPath.endsWith("gearAppUpdateCheck.as") ->
                    "<result><resultCode>0</resultCode><appInfo><resultCode>1</resultCode></appInfo></result>"
                request.url.encodedPath.endsWith("gearAppDownload.as") ->
                    "<result><appInfo><resultCode>1</resultCode><downloadURI>$metadataUrl</downloadURI><contentSize>10</contentSize></appInfo></result>"
                else -> "content"
            }
            val responseRequest = redirectedUrl?.toHttpUrl()?.let { request.newBuilder().url(it).build() } ?: request
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(responseRequest).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(xml.toResponseBody()).build()
            }
        }
        FaceCatalogRepositoryImpl::class.java.getDeclaredField("client")
            .apply { isAccessible = true }.set(repository, client)
        val face = CatalogFace(
            productId = "face", faceId = "00046", name = "Face", description = "",
            appId = "sm_r390_00046", versionName = "1", versionCode = 1,
            packageSize = 10, styles = listOf(FaceStyleOption(0, "https://samsungapps.com/preview.png")),
        )
        return try {
            repository.downloadPackage(face, 0) {}
            error("expected the package address to be rejected")
        } catch (error: WatchFaceException) {
            error
        }
    }
}
