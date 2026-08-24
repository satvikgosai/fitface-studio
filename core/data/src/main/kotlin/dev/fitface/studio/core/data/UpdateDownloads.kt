package dev.fitface.studio.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.model.AppRelease
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.WatchFaceException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the release feed and the APK behind it, into app-private storage.
 *
 * The download follows the face-package path step for step — a size ceiling checked
 * before, during and after, and the **post-redirect** URL re-validated against a host
 * allowlist — with two differences that matter:
 *
 *  * **It streams to a file.** The package download buffers into a `ByteArrayOutputStream`,
 *    which is fine for a 32 MiB ceiling but not here: `toByteArray()` copies, so a 36 MiB
 *    APK would peak around 80 MiB of heap for no reason.
 *  * **No call timeout.** OkHttp's default is none, and it must stay none — a wall-clock
 *    timeout across the whole call kills a 36 MiB download on a slow connection at
 *    whatever fraction it had reached.
 */
@Singleton
internal class UpdateDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** The releases feed, as text. */
    fun fetchFeed(): String {
        val url = GitHubReleaseFeed.Endpoint.toHttpUrlOrNull()
            ?: throw WatchFaceException("The update service could not be reached.", "bad endpoint")
        require(isTrustedUpdateHost(url.host)) { "the endpoint is not a trusted host" }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            // GitHub refuses a request with no User-Agent. The app's own name and version
            // and nothing else: no device identifier goes anywhere near this.
            .header("User-Agent", "fitface-studio/${context.installedVersionLabel().substringBefore(' ')}")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    throw WatchFaceException(
                        "Update checks are rate-limited right now. Try again later.",
                        "HTTP ${response.code} remaining=${response.header("x-ratelimit-remaining")}",
                    )
                }
                if (!response.isSuccessful) {
                    throw WatchFaceException(
                        "The update service could not be reached.",
                        "HTTP ${response.code} for ${url.encodedPath}",
                    )
                }
                response.body.string()
            }
        } catch (error: WatchFaceException) {
            throw error
        } catch (error: Exception) {
            throw WatchFaceException(
                "The update service could not be reached. Check your connection.",
                error.message,
                error,
            )
        }
    }

    /**
     * Downloads [release] and returns the file.
     *
     * A complete earlier download of the same asset is reused rather than fetched twice —
     * the same rule the package cache follows, and the difference between retrying a
     * failed install and spending another 36 MiB.
     */
    suspend fun download(release: AppRelease, onProgress: (DownloadProgress) -> Unit): File {
        // Cancellation in Kotlin is cooperative and the read loop below never suspends, so
        // it has to ask. Without this, cancelling a download cancelled the coroutine and
        // left the loop running to the last byte — the dialog said it had stopped while
        // 36 MiB went on arriving.
        val job = currentCoroutineContext()[Job]
        val target = File(updatesDirectory(), release.assetName)
        if (target.isFile && target.length() == release.assetBytes) {
            onProgress(DownloadProgress(release.assetBytes, release.assetBytes))
            return target
        }
        if (release.assetBytes > MaxUpdateBytes) {
            throw WatchFaceException(
                "That update is larger than the ${MaxUpdateBytes / (1024 * 1024)} MiB safety limit.",
                "expected size=${release.assetBytes}",
            )
        }
        val url = release.assetUrl.toHttpUrlOrNull()
            ?: throw WatchFaceException("That update's address could not be read.", "unparseable url")
        if (!url.isHttps || !isTrustedUpdateHost(url.host)) {
            throw WatchFaceException(
                "That update is served from an address this app does not trust.",
                "host=${url.host}",
            )
        }
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WatchFaceException(
                        "The update download failed.",
                        "HTTP ${response.code}",
                    )
                }
                val finalUrl = response.request.url
                if (!finalUrl.isHttps || !isTrustedUpdateHost(finalUrl.host)) {
                    // The host only. A release-asset redirect carries a signed access
                    // token in its query string, and this string reaches the bug report.
                    throw WatchFaceException(
                        "The update download was redirected to an untrusted address.",
                        "redirected to ${finalUrl.host}",
                    )
                }
                val body = response.body
                val declared = body.contentLength()
                if (declared > MaxUpdateBytes) {
                    throw WatchFaceException(
                        "That update is larger than the ${MaxUpdateBytes / (1024 * 1024)} MiB safety limit.",
                        "content length=$declared",
                    )
                }
                val total = declared.takeIf { it > 0 } ?: release.assetBytes
                var received = 0L
                writeStreaming(target) { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    body.byteStream().use { input ->
                        while (true) {
                            if (job?.isActive == false) {
                                throw CancellationException("the update download was cancelled")
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MaxUpdateBytes) {
                                throw WatchFaceException(
                                    "That update is larger than the " +
                                        "${MaxUpdateBytes / (1024 * 1024)} MiB safety limit.",
                                    "received=$received",
                                )
                            }
                            output.write(buffer, 0, count)
                            onProgress(DownloadProgress(received, total))
                        }
                    }
                    if (received != release.assetBytes) {
                        throw WatchFaceException(
                            "The update download was incomplete.",
                            "expected=${release.assetBytes} actual=$received",
                        )
                    }
                }
            }
        } catch (error: WatchFaceException) {
            throw error
        } catch (error: CancellationException) {
            // Not a failure, and not something to dress up as one. The streaming write has
            // already removed the partial `.tmp` on its way out.
            throw error
        } catch (error: Exception) {
            throw WatchFaceException("The update download failed. Try again.", error.message, error)
        }
        return target
    }

    /**
     * Room for the download and for the copy the package installer stages from it.
     *
     * Without this the failure is a half-written file and an opaque installer error, at
     * the end of a 36 MiB download rather than before it.
     */
    fun hasRoomFor(release: AppRelease): Boolean {
        val existing = File(updatesDirectory(), release.assetName)
            .takeIf(File::isFile)?.length() ?: 0L
        val needed = ((release.assetBytes - existing).coerceAtLeast(0) + release.assetBytes) * 6 / 5
        return runCatching { context.filesDir.usableSpace > needed }.getOrDefault(true)
    }

    /** Deletes every downloaded update except [keep], and any `.tmp` left by a failure. */
    fun forgetStale(keep: AppRelease?) {
        runCatching {
            updatesDirectory().listFiles()?.forEach { file ->
                if (file.name != keep?.assetName) file.delete()
            }
        }
    }

    fun fileFor(release: AppRelease): File = File(updatesDirectory(), release.assetName)

    private fun updatesDirectory(): File =
        File(context.filesDir, UpdatesDirectory).apply { mkdirs() }

    /**
     * Writes through a `.tmp` and renames, so a target file either is the whole download
     * or does not exist. The same commit discipline as `PackageCache.writeAtomically`,
     * but handed an `OutputStream` rather than a `ByteArray` — see the class comment.
     */
    private fun writeStreaming(target: File, write: (OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                write(output)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Could not commit ${target.name}")
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    companion object {
        /**
         * A safety limit, not a measurement. The published asset is 37.9 MB — a debug
         * build carrying two accessory SDK JARs, unminified — so it is already **over**
         * the 32 MiB ceiling the face-package download uses and that constant cannot be
         * reused here. 64 MiB leaves room for the app to grow without letting an
         * unbounded response fill the device.
         */
        const val MaxUpdateBytes = 64 * 1024 * 1024L

        const val UpdatesDirectory = "updates"
    }
}
