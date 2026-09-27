package dev.fitface.studio.core.data

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response

/** Closes blocking OkHttp I/O as soon as the owning coroutine is cancelled. */
internal suspend fun <T> Call.useCancellable(block: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        try {
            val result = execute().use(block)
            continuation.resume(result)
        } catch (error: Throwable) {
            continuation.resumeWithException(error)
        }
    }
