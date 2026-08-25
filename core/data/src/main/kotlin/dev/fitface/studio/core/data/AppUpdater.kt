package dev.fitface.studio.core.data

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.model.AppRelease
import dev.fitface.studio.core.model.AppUpdateState
import dev.fitface.studio.core.model.AppVersion
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.UpdateBlocker
import dev.fitface.studio.core.model.WatchFaceException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether a newer build has been published, and getting it installed.
 *
 * A concrete `@Singleton` with a `StateFlow` and no interface behind it, exactly as
 * `Fit3DirectInstaller` is: its only consumer is the app shell, nothing fakes it, and
 * every decision worth testing has been lifted out into pure code — `AppVersion`,
 * `GitHubReleaseFeed`, `isTrustedUpdateHost`, `signingVerdict`.
 *
 * **It owns its own scope, and that is the point.** The APK is 36 MiB, and the menu that
 * starts the download is in both top bars, so a download begun in the library has to
 * survive opening a face in the editor. In a `viewModelScope` it would be cancelled the
 * moment the nav entry it belonged to went away.
 *
 * Nothing here runs on its own. There is no launch-time poll and no background job: the
 * network is touched when, and only when, someone taps *Check for update*. That keeps
 * the app off the network at startup and well inside GitHub's unauthenticated rate limit.
 */
@Singleton
// The constructor is internal because the three collaborators are: they are this
// module's own plumbing, and only `AppUpdater` itself is reached from outside.
class AppUpdater @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val downloads: UpdateDownloads,
    private val installer: UpdateInstaller,
    private val diagnostics: DiagnosticsLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    /**
     * The system's install confirmation, for an Activity to launch.
     *
     * A separate channel rather than a field on [AppUpdateState] because an `Intent` is an
     * Android type and the state lives in `:core:model`, which is framework-free — and
     * because it is an event: replaying it after a rotation would reopen the system
     * installer over and over.
     */
    private val mutableConfirmations = MutableSharedFlow<Intent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val confirmations: Flow<Intent> = mutableConfirmations.asSharedFlow()

    private var work: Job? = null

    /**
     * `0.1.1 (17)` for the running build, which is what the About dialog shows.
     *
     * The same reader `DiagnosticsReporter` uses for the report's `app=` line, so the
     * dialog and the report cannot disagree about what is installed.
     */
    fun installedVersion(): String = context.installedVersionLabel()

    /** Whether "install unknown apps" is on. Read fresh; it can be revoked at any moment. */
    fun canInstallPackages(): Boolean = installer.canInstallPackages()

    fun installPermissionIntent(): Intent? = installer.installPermissionIntent()

    /**
     * Re-reads the install permission and moves the state on if it has been granted.
     *
     * Called when the reader comes back from the Settings screen this flow sent them to.
     * Without it the dialog kept saying "Android needs your permission" after they had
     * just given it, because the verdict was baked in when the check ran — so the only way
     * forward was to close the dialog and start again.
     *
     * Deliberately no network: this recomputes one boolean against the release already
     * found, rather than fetching the feed a second time for a question that has nothing
     * to do with it.
     */
    fun recheckInstallPermission() {
        val permitted = installer.canInstallPackages()
        mutableState.value = when (val current = mutableState.value) {
            is AppUpdateState.Available -> current.copy(installBlockedUpFront = !permitted)
            // Downloaded, then refused for the permission alone. Now that it is granted
            // the file on disk is installable as it stands.
            is AppUpdateState.Blocked ->
                if (permitted && current.blocker == UpdateBlocker.INSTALL_NOT_PERMITTED) {
                    AppUpdateState.ReadyToInstall(current.release)
                } else {
                    current
                }
            else -> current
        }
    }

    fun check() = start {
        mutableState.value = AppUpdateState.Checking
        val installed = requireNotNull(context.installedIdentity()) {
            "the package manager cannot describe this app"
        }
        val newest = withContext(Dispatchers.IO) {
            GitHubReleaseFeed.newest(downloads.fetchFeed())
        }
        val current = AppVersion.parse(installed.versionName)
        // Kept only if it is the release about to be offered. Keeping "the newest release"
        // outright would keep the 36 MiB file for ever once its update was installed:
        // newest would then equal installed, and the sweep would spare the very file it
        // exists to remove.
        val offered = newest?.takeIf { candidate -> current != null && candidate.version > current }
        downloads.forgetStale(keep = offered)
        diagnostics.info(
            TAG,
            "update check installed=${installed.versionName} newest=${newest?.version?.raw}",
        )
        mutableState.value = when {
            // Not "up to date". The feed answered and held nothing this app could use,
            // which is what a renamed asset or a re-shaped response looks like — and
            // reporting that as current would leave a reader told they were on the newest
            // build for as long as the mistake lasted.
            newest == null -> AppUpdateState.Failed(
                "No published build could be found to compare against.",
                "the release feed carried no usable release",
            )
            current == null -> AppUpdateState.Failed(
                "This app could not read its own version, so there is nothing to compare.",
                "unparseable installed versionName=${installed.versionName}",
            )
            newest.version > current -> AppUpdateState.Available(
                release = newest,
                installedVersion = installed.label,
                installBlockedUpFront = !installer.canInstallPackages(),
            )
            // Equal, or the installed build is ahead of anything published — a local
            // build. Never offer the older one: it cannot install over this and the only
            // way through deletes every saved project.
            else -> AppUpdateState.UpToDate(installed.label)
        }
    }

    fun download() {
        val offered = mutableState.value as? AppUpdateState.Available ?: return
        val release = offered.release
        start {
            if (!downloads.hasRoomFor(release)) {
                mutableState.value = AppUpdateState.Blocked(release, UpdateBlocker.NOT_ENOUGH_SPACE)
                return@start
            }
            mutableState.value = AppUpdateState.Downloading(release, DownloadProgress(0, release.assetBytes))
            // Whole percentages only. Reported per read, a 36 MiB download emits about
            // 4,600 times, and every one of those is a state change the whole dialog
            // recomposes on.
            var lastPercent = -1
            val job = currentCoroutineContext()[Job]
            val file = withContext(Dispatchers.IO) {
                downloads.download(release) { progress ->
                    // The loop this arrives from is blocking, so a cancel can land between
                    // two reads and one more callback can follow it. Unguarded, that write
                    // put `Downloading` straight back over the state `cancel()` had just
                    // restored — so the dialog went on counting up after you cancelled it.
                    if (job?.isActive != true) return@download
                    val percent = (progress.fraction * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        mutableState.value = AppUpdateState.Downloading(release, progress)
                    }
                }
            }
            val blocker = inspect(release, file.absolutePath)
            mutableState.value = if (blocker == null) {
                AppUpdateState.ReadyToInstall(release)
            } else {
                AppUpdateState.Blocked(release, blocker)
            }
        }
    }

    fun install() {
        val ready = mutableState.value as? AppUpdateState.ReadyToInstall ?: return
        val release = ready.release
        start {
            val file = downloads.fileFor(release)
            // Re-checked here and not only at the offer: the setting is a switch a reader
            // can turn back off while the download is running.
            if (!installer.canInstallPackages()) {
                mutableState.value =
                    AppUpdateState.Blocked(release, UpdateBlocker.INSTALL_NOT_PERMITTED)
                return@start
            }
            mutableState.value = AppUpdateState.Installing(release)
            when (val outcome = installer.install(file) { mutableConfirmations.tryEmit(it) }) {
                is InstallOutcome.Succeeded -> {
                    // Rarely reached: the package manager stops this process as it
                    // replaces it, so the usual end of a successful update is no state at
                    // all.
                    mutableState.value = AppUpdateState.UpToDate(release.version.raw)
                }
                is InstallOutcome.Cancelled ->
                    mutableState.value = AppUpdateState.ReadyToInstall(release)
                is InstallOutcome.Failed -> {
                    diagnostics.error(TAG, "the installer refused the update", outcome.detail)
                    mutableState.value = AppUpdateState.Failed(outcome.message, outcome.detail)
                }
            }
        }
    }

    /**
     * Stops whatever is running and goes back to the last resting point.
     *
     * A cancelled download leaves no `.tmp` behind — the streaming write deletes it on
     * the way out of any failure, cancellation included.
     */
    fun cancel() {
        work?.cancel()
        work = null
        mutableState.value = when (val current = mutableState.value) {
            is AppUpdateState.Downloading -> AppUpdateState.Available(
                release = current.release,
                installedVersion = context.installedVersionLabel(),
                // Re-read rather than defaulted: cancelling back to the offer should not
                // quietly tell someone installs are allowed when they are not.
                installBlockedUpFront = !installer.canInstallPackages(),
            )
            is AppUpdateState.Installing -> AppUpdateState.ReadyToInstall(current.release)
            else -> AppUpdateState.Idle
        }
    }

    /**
     * Closing the dialog. Deliberately does **not** cancel a download: the state is
     * process-wide, so reopening the menu drops the reader back into "Downloading 45%"
     * rather than starting again. Cancelling is its own button.
     */
    fun dismiss() {
        if (!mutableState.value.isBusy) mutableState.value = AppUpdateState.Idle
    }

    private fun inspect(release: AppRelease, path: String): UpdateBlocker? {
        val installed = context.installedIdentity() ?: return null
        val archive = context.archiveIdentity(File(path))
        if (archive == null) {
            diagnostics.warn(TAG, "the downloaded update could not be read as a package")
            return null
        }
        if (archive.packageName != installed.packageName) {
            diagnostics.warn(TAG, "downloaded package is ${archive.packageName}")
            return UpdateBlocker.SIGNATURE_DIFFERS
        }
        // The feed carries no version code, so this is the first and last place a re-cut
        // tag or a republished asset can be caught.
        if (archive.versionCode <= installed.versionCode) return UpdateBlocker.NOT_NEWER
        return when (signingVerdict(installed.signerDigests, archive.signerDigests)) {
            SigningVerdict.INCOMPATIBLE -> UpdateBlocker.SIGNATURE_DIFFERS
            // Not a refusal. The package manager is the real gate, and blocking on this
            // code's own failed read would make the feature unusable for no gain.
            SigningVerdict.UNKNOWN -> {
                diagnostics.warn(TAG, "could not compare signing keys for ${release.tag}")
                null
            }
            SigningVerdict.COMPATIBLE -> null
        }
    }

    /**
     * Runs one piece of work at a time, turning anything thrown into a shown sentence and
     * a recorded reason.
     *
     * The guard is the same one a transfer uses: without it, tapping the menu in the
     * library and again in the editor starts two downloads of the same 36 MiB.
     */
    private fun start(block: suspend () -> Unit) {
        if (mutableState.value.isBusy) return
        work = scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val detail = (error as? WatchFaceException)?.technicalDetail ?: error.message
                // The half that explains it. Recorded rather than dropped, because a
                // funnel that shows only the sentence is how a store result code once
                // existed in the process and reached nobody.
                diagnostics.error(TAG, "update failed: ${error.message}", detail, error)
                mutableState.value = AppUpdateState.Failed(
                    message = (error as? WatchFaceException)?.userMessage
                        ?: "The update could not be checked. Try again.",
                    detail = detail,
                )
            }
        }
    }

    private companion object {
        const val TAG = "AppUpdater"
    }
}
