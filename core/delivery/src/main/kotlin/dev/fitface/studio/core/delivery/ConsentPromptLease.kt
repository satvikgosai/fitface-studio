package dev.fitface.studio.core.delivery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder

/**
 * Lets the accessory framework show its consent prompt over this app while discovery runs.
 *
 * The framework Galaxy Store serves phones from other makers (3.1.96.61123) answers a
 * find-peer request only for an app the user has allowed in the framework's own prompt.
 * Until then it raises that prompt from the background, waits 9.5 s and drops the request
 * without answering, so discovery could only time out. From Android 14 a background app may
 * start an activity on the strength of a visible app's binding only when that binding opts
 * in, and the SDK's own binding does not, so the prompt was blocked. While a discovery is
 * waiting, this holds a second binding to the same service that does opt in. Older Android
 * versions lend that right to every binding, so nothing is bound there.
 *
 * Each [open] returns a token, and [close] ends the lease only for the latest one: a
 * discovery that has finished must not take the binding from one that started after it.
 * See `docs/direct-install.md`, "Consent".
 */
internal class ConsentPromptLease(private val binding: Binding) {
    /** The one binding call this needs, apart so the token rules can be tested without Android. */
    internal interface Binding {
        /** Binds and reports what happened; [Opened.BOUND] is the only state [unbind] undoes. */
        fun bind(): Opened
        fun unbind()
    }

    /** What opening the lease did, for the diagnostics log. */
    enum class Opened { BOUND, NOT_NEEDED, UNAVAILABLE }

    data class Lease(val token: Int, val opened: Opened)

    private var latest = 0
    private var bound = false

    @Synchronized
    fun open(): Lease {
        latest += 1
        val opened = if (bound) {
            Opened.BOUND
        } else {
            binding.bind().also { bound = it == Opened.BOUND }
        }
        return Lease(latest, opened)
    }

    /** Ends [lease] if no later discovery has opened one since. */
    @Synchronized
    fun close(lease: Lease) {
        if (lease.token == latest) release()
    }

    /** Ends the lease whoever opened it, as a reset of the whole setup does. */
    @Synchronized
    fun release() {
        if (!bound) return
        bound = false
        binding.unbind()
    }

    /** The Android side: binds `com.samsung.accessory`'s framework service with the opt-in flag. */
    internal class FrameworkBinding(private val context: Context) : Binding {
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        override fun bind(): Opened {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return Opened.NOT_NEEDED
            val intent = Intent(FRAMEWORK_SERVICE_ACTION).setPackage(FRAMEWORK_PACKAGE)
            val bound = runCatching {
                context.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_ALLOW_ACTIVITY_STARTS,
                )
            }.getOrDefault(false)
            if (bound) return Opened.BOUND
            // A refused bind still registers the connection, and Android asks for it back.
            unbind()
            return Opened.UNAVAILABLE
        }

        override fun unbind() {
            runCatching { context.unbindService(connection) }
        }
    }

    internal companion object {
        const val FRAMEWORK_PACKAGE = "com.samsung.accessory"

        /** The interface the accessory SDK itself binds by, so the same service answers. */
        const val FRAMEWORK_SERVICE_ACTION = "com.samsung.accessory.api.ISAFrameworkManagerV2"
    }
}
