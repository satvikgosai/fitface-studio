package dev.fitface.studio

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.fitface.studio.core.data.AppUpdater
import dev.fitface.studio.core.model.AppUpdateState
import dev.fitface.studio.core.model.UpdateBlocker
import dev.fitface.studio.core.ui.AboutDialog
import dev.fitface.studio.core.ui.UpdateDialog
import dev.fitface.studio.core.ui.UpdateDialogState
import dev.fitface.studio.core.ui.UpdatePhase
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Which of the app menu's two dialogs is open, if either. */
enum class AppMenuRequest { About, Update }

/**
 * The app menu's own state, above the navigation.
 *
 * Both top bars carry the menu, so hosting its dialogs inside either screen would mean
 * two copies of the mapping below and two chances for them to drift. Hosting them here
 * instead has a second benefit that matters more: a dialog composed above `NavDisplay`
 * is not owned by a nav entry, so opening a face in the editor while a 36 MiB update
 * downloads does not disturb it.
 *
 * Neither `LibraryViewModel` nor `EditorViewModel` learns about any of this. They pass two
 * lambdas through, which is the same way `onOpenEditor` and `onBack` already travel.
 */
@HiltViewModel
class AppMenuViewModel @Inject constructor(
    private val updater: AppUpdater,
) : ViewModel() {
    val state = updater.state

    /** The system installer's own confirmation screen, which only an Activity may launch. */
    val confirmations: Flow<Intent> = updater.confirmations

    val installedVersion: String get() = updater.installedVersion()

    fun check() = updater.check()
    fun download() = updater.download()
    fun install() = updater.install()
    fun cancel() = updater.cancel()
    fun dismiss() = updater.dismiss()

    fun installPermissionIntent(): Intent? = updater.installPermissionIntent()

    /**
     * Whether this device has a screen for allowing app installs.
     *
     * Asked before the button is offered rather than when it is tapped: a button that
     * resolves to nothing and does nothing when pressed is worse than a sentence saying
     * the update cannot be installed from here.
     */
    fun hasInstallPermissionScreen(): Boolean = updater.installPermissionIntent() != null

    fun recheckInstallPermission() = updater.recheckInstallPermission()
}

/**
 * Draws whichever of the two dialogs is open, and owns the two hand-offs to the system.
 *
 * The update check fires when the dialog opens rather than from the menu tap, so the
 * spinner and the request start together and reopening the dialog after a failure retries
 * on its own.
 */
@Composable
fun AppMenuDialogs(
    request: AppMenuRequest?,
    onDismiss: () -> Unit,
    viewModel: AppMenuViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Both launched from a composable rather than from the updater: starting an Activity
    // from a background context is what recent Android versions drop on the floor, and the
    // install confirmation is the one screen this flow cannot do without.
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    // The result is always RESULT_CANCELED — the Settings screen reports nothing — so the
    // answer is re-read from the package manager instead of taken from the result code.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.recheckInstallPermission()
    }

    LaunchedEffect(viewModel) {
        viewModel.confirmations.collect { intent -> confirm.launch(intent) }
    }

    when (request) {
        null -> Unit
        AppMenuRequest.About -> AboutDialog(
            version = viewModel.installedVersion,
            onDismiss = onDismiss,
        )
        AppMenuRequest.Update -> {
            LaunchedEffect(Unit) { viewModel.check() }
            UpdateDialog(
                state = state.toDialogState(
                    installedFallback = viewModel.installedVersion,
                    canOfferPermission = viewModel.hasInstallPermissionScreen(),
                ),
                onCheckAgain = viewModel::check,
                onDownload = viewModel::download,
                onInstall = viewModel::install,
                onCancel = viewModel::cancel,
                onGrantPermission = {
                    viewModel.installPermissionIntent()?.let(permission::launch)
                },
                onDismiss = {
                    viewModel.dismiss()
                    onDismiss()
                },
            )
        }
    }
}

/**
 * The one place `AppUpdateState` becomes something `:core:ui` can draw.
 *
 * `:core:ui` depends on nothing but Compose, which is why its layout tests can run at
 * all, so it cannot see the sealed state directly. Exhaustive on purpose: a new phase
 * should stop this compiling rather than fall through to a blank dialog.
 */
@Composable
private fun AppUpdateState.toDialogState(
    installedFallback: String,
    canOfferPermission: Boolean,
): UpdateDialogState = when (this) {
    is AppUpdateState.Idle,
    is AppUpdateState.Checking,
    -> UpdateDialogState(UpdatePhase.CHECKING, installedFallback)

    is AppUpdateState.UpToDate -> UpdateDialogState(UpdatePhase.UP_TO_DATE, installedVersion)

    is AppUpdateState.Available -> UpdateDialogState(
        phase = if (installBlockedUpFront) UpdatePhase.BLOCKED else UpdatePhase.AVAILABLE,
        installedVersion = installedVersion,
        availableVersion = release.version.raw,
        downloadBytes = release.assetBytes,
        message = permissionMessage(canOfferPermission).takeIf { installBlockedUpFront },
        offerPermission = installBlockedUpFront && canOfferPermission,
    )

    is AppUpdateState.Downloading -> UpdateDialogState(
        phase = UpdatePhase.DOWNLOADING,
        installedVersion = installedFallback,
        availableVersion = release.version.raw,
        downloadBytes = release.assetBytes,
        fraction = progress.fraction,
    )

    is AppUpdateState.ReadyToInstall -> UpdateDialogState(
        phase = UpdatePhase.READY,
        installedVersion = installedFallback,
        availableVersion = release.version.raw,
        downloadBytes = release.assetBytes,
    )

    is AppUpdateState.Installing -> UpdateDialogState(
        phase = UpdatePhase.INSTALLING,
        installedVersion = installedFallback,
        availableVersion = release.version.raw,
    )

    is AppUpdateState.Blocked -> UpdateDialogState(
        phase = UpdatePhase.BLOCKED,
        installedVersion = installedFallback,
        availableVersion = release.version.raw,
        message = when (blocker) {
            UpdateBlocker.SIGNATURE_DIFFERS -> stringResource(R.string.app_update_blocked_signature)
            UpdateBlocker.INSTALL_NOT_PERMITTED -> permissionMessage(canOfferPermission)
            UpdateBlocker.NOT_ENOUGH_SPACE -> stringResource(R.string.app_update_blocked_space)
            UpdateBlocker.NOT_NEWER -> stringResource(R.string.app_update_blocked_not_newer)
        },
        // Only one of the four is something a reader can clear themselves, and only when
        // this device has the screen for it.
        offerPermission = blocker == UpdateBlocker.INSTALL_NOT_PERMITTED && canOfferPermission,
    )

    is AppUpdateState.Failed -> UpdateDialogState(
        phase = UpdatePhase.FAILED,
        installedVersion = installedFallback,
        message = message,
    )
}

/**
 * "Turn this on" when there is somewhere to turn it on, and otherwise the plain fact.
 *
 * A managed device can forbid installs outright, and on one of those the settings screen
 * does not resolve — so the offer would be a button that goes nowhere.
 */
@Composable
private fun permissionMessage(canOfferPermission: Boolean): String = stringResource(
    if (canOfferPermission) {
        R.string.app_update_blocked_permission
    } else {
        R.string.app_update_no_settings
    },
)
