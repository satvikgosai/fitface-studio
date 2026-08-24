package dev.fitface.studio.core.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/** How an install attempt ended. */
internal sealed interface InstallOutcome {
    data object Succeeded : InstallOutcome
    data object Cancelled : InstallOutcome
    data class Failed(val message: String, val detail: String?) : InstallOutcome
}

/**
 * Hands a downloaded APK to the package manager.
 *
 * `PackageInstaller` rather than `Intent.ACTION_INSTALL_PACKAGE`, for three reasons: the
 * latter is deprecated at API 29 and this app targets 36; it needs a `content://` URI and
 * therefore a `FileProvider`, which this project has none of and does not want, since the
 * whole point of "nothing is written outside app-private storage" is that nothing outside
 * can read it; and the session API takes the bytes through `openWrite`, so the APK never
 * leaves `filesDir`.
 */
@Singleton
internal class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Whether this app may install packages. Re-read every time rather than cached: it is
     * a per-app setting a reader can revoke between the check and the install.
     */
    fun canInstallPackages(): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * Where a reader turns "install unknown apps" on for this app.
     *
     * Three intents, narrowing: the per-app screen, then the list of all apps, then this
     * app's details page — the same fallback ladder, and the same
     * `ActivityNotFoundException` guard, as the plugin-settings hand-off in
     * `:core:delivery`. Null when none of them resolve, so the caller can say so instead
     * of throwing.
     */
    fun installPermissionIntent(): Intent? = sequenceOf(
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.fromParts("package", context.packageName, null),
        ),
        // The action string rather than a constant: `ACTION_MANAGE_UNKNOWN_APP_SOURCES`
        // is public API and this list-of-all-apps variant is not, so naming it in the
        // SDK would not compile. It resolves on stock Android and is skipped when it
        // does not, which is what the `resolveActivity` filter below is for.
        Intent("android.settings.MANAGE_ALL_UNKNOWN_APP_SOURCES"),
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    ).firstOrNull { intent ->
        runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
    }

    /**
     * Streams [file] into a session, commits it, and waits for the outcome.
     *
     * [onConfirmation] receives the system's own confirmation screen when it asks for one,
     * which is the normal path — it is handed up to an Activity to launch rather than
     * started from here, because a background activity start is exactly what recent
     * Android versions drop on the floor.
     *
     * Note that a successful self-install usually never returns: the package manager stops
     * this process as it replaces it. That is why nothing is cleaned up on the success
     * path and why the next check sweeps the file instead.
     */
    suspend fun install(file: File, onConfirmation: (Intent) -> Unit): InstallOutcome =
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val statuses = Channel<Intent>(Channel.UNLIMITED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent?.let(statuses::trySend)
                }
            }
            registerStatusReceiver(receiver)
            var sessionId = -1
            try {
                sessionId = openAndWrite(installer, file)
                installer.openSession(sessionId).use { session ->
                    session.commit(statusSender(sessionId).intentSender)
                }
                awaitOutcome(statuses, onConfirmation)
            } catch (error: CancellationException) {
                // Abandon the session, then let the cancellation through. Swallowed into a
                // Failed like the branch below, it would report the install as broken when
                // the reader had simply backed out of it.
                if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
                throw error
            } catch (error: Exception) {
                if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
                InstallOutcome.Failed(
                    "The update could not be handed to the installer.",
                    error.message,
                )
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
                statuses.close()
            }
        }

    private fun openAndWrite(installer: PackageInstaller, file: File): Int {
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(SessionFileName, 0, file.length()).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
        }
        return sessionId
    }

    /**
     * Reads statuses until one is terminal.
     *
     * `STATUS_PENDING_USER_ACTION` is deliberately **not** terminal: it means the system
     * wants to ask, and the real answer arrives in a later broadcast. Returning here would
     * report success or failure before the reader had been shown anything.
     */
    private suspend fun awaitOutcome(
        statuses: Channel<Intent>,
        onConfirmation: (Intent) -> Unit,
    ): InstallOutcome {
        for (intent in statuses) {
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            )
            val detail = "status=$status " +
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmation = confirmationIntent(intent)
                        ?: return InstallOutcome.Failed(
                            "Android did not offer its install confirmation.",
                            detail,
                        )
                    onConfirmation(confirmation)
                }
                PackageInstaller.STATUS_SUCCESS -> return InstallOutcome.Succeeded
                PackageInstaller.STATUS_FAILURE_ABORTED -> return InstallOutcome.Cancelled
                PackageInstaller.STATUS_FAILURE_CONFLICT -> return InstallOutcome.Failed(
                    "Android refused the update because it conflicts with the installed copy.",
                    detail,
                )
                PackageInstaller.STATUS_FAILURE_STORAGE -> return InstallOutcome.Failed(
                    "There was not enough room to install the update.",
                    detail,
                )
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> return InstallOutcome.Failed(
                    "Android refused the update as incompatible with this device.",
                    detail,
                )
                else -> return InstallOutcome.Failed("The update did not install.", detail)
            }
        }
        return InstallOutcome.Failed("The installer stopped without saying why.", null)
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(status: Intent): Intent? = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
        status.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        status.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
    }

    /**
     * `FLAG_MUTABLE` is required from API 31: the system fills `EXTRA_STATUS` and
     * `EXTRA_INTENT` into this intent, and an immutable `PendingIntent` throws instead.
     */
    private fun statusSender(sessionId: Int): PendingIntent {
        val intent = Intent(StatusAction)
            .setPackage(context.packageName)
            .putExtra(SessionExtra, sessionId)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    /**
     * Registered at runtime rather than declared in a manifest, on purpose.
     *
     * A manifest receiver would put a component into the merged manifest of every module
     * that depends on `:core:data` — which is the hazard that already stops
     * `:feature:editor` running under Robolectric — and it would buy nothing: the only
     * status worth acting on arrives while the app is in the foreground, because the
     * reader has just tapped Install, and a successful self-install replaces this process
     * before any receiver could report it.
     */
    private fun registerStatusReceiver(receiver: BroadcastReceiver) {
        val filter = IntentFilter(StatusAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private companion object {
        const val StatusAction = "dev.fitface.studio.UPDATE_INSTALL_STATUS"
        const val SessionExtra = "sessionId"
        const val SessionFileName = "update.apk"
    }
}
