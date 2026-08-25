package dev.fitface.studio.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/**
 * What this app is, where it comes from, and what version of it is running.
 *
 * The version is here because a sideloaded app has nowhere else to show one: there is no
 * store listing and no settings entry, and until now the only place it appeared was inside
 * the pasteable bug report.
 *
 * The non-affiliation line is the one part of this that is not optional, and it is
 * deliberately **one line**. `NOTICE.md` is the long form, and the link below reaches it —
 * restating it here would only produce a wall of disclaimer that stops being read, which is
 * the opposite of what it is for. It names no vendor and no store, per the naming rule in
 * `CONTRIBUTING.md`: brand strings are literal technical identifiers only, never UI copy.
 *
 * The link is a real `LinkAnnotation`, so the platform's own URI handler opens it and this
 * module needs no `Intent` and no `Context` — which is what keeps `:core:ui` free of
 * everything but Compose.
 */
@Composable
fun AboutDialog(version: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_about_title)) },
        text = {
            // Scrolls for the reason the diagnostics dialog does: in landscape an
            // AlertDialog leaves the text slot a few dp, and without this the link and the
            // version — the two things this dialog exists for — were below the fold and
            // simply not drawn.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.ui_about_what_it_is),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.ui_about_independent),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.fitText.secondary,
                )
                ProjectLink()
                Text(
                    stringResource(R.string.ui_about_version, version),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.fitText.secondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_diagnostics_close)) }
        },
    )
}

/**
 * The project's address, as a link the platform opens.
 *
 * The scheme is dropped from what is drawn and kept in what is followed: `https://` is
 * nine characters of a line that has to fit a 360dp dialog, and it tells a reader nothing
 * they did not assume. Underlined as well as coloured, because colour alone does not say
 * "link" to someone who cannot distinguish it from the surrounding text.
 *
 * It carries no label line — a GitHub address in an About dialog needs no introducing, and
 * that line is what the version needs in order to stay on screen in landscape. What the
 * label said survives as the link's `contentDescription`, so a screen reader still hears
 * what is on the other end of it rather than just a URL.
 */
@Composable
private fun ProjectLink() {
    val url = stringResource(R.string.ui_about_source_url)
    val description = stringResource(R.string.ui_about_source_a11y)
    val styles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )
    Text(
        buildAnnotatedString {
            withLink(LinkAnnotation.Url(url, styles)) {
                append(url.removePrefix("https://"))
            }
        },
        modifier = Modifier
            .padding(top = 14.dp)
            .semantics { contentDescription = description },
        style = MaterialTheme.typography.labelMedium,
    )
}

/** Which of the update dialog's states is showing. */
enum class UpdatePhase {
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY,
    INSTALLING,

    /** Downloaded, then refused before the package manager was asked. */
    BLOCKED,

    FAILED,
}

/**
 * Everything the update dialog draws, as plain values.
 *
 * `:core:ui` depends on nothing but Compose — deliberately, and it is why the layout tests
 * in this module can run under Robolectric at all — so it cannot see `AppUpdateState` in
 * `:core:model`. The mapping between the two happens once, in `:app`, rather than being
 * written out in each screen that hosts a bar.
 */
data class UpdateDialogState(
    val phase: UpdatePhase,
    val installedVersion: String,
    val availableVersion: String = "",
    val downloadBytes: Long = 0,
    val fraction: Float = 0f,
    /** The sentence for [UpdatePhase.FAILED] and [UpdatePhase.BLOCKED]. */
    val message: String? = null,
    /** Whether the blocker is one the reader can clear from Settings. */
    val offerPermission: Boolean = false,
)

/**
 * Checking for a newer build, fetching it, and handing it to the system installer.
 *
 * The dialog is closable at every point and closing it **never** cancels a download: the
 * state lives in a process-wide singleton, so reopening the menu drops the reader back
 * into "Downloading 45%" instead of starting the 36 MiB again. Cancelling is its own
 * button, and only appears while there is something to cancel.
 */
@Composable
fun UpdateDialog(
    state: UpdateDialogState,
    onCheckAgain: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onGrantPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_update_title)) },
        text = { UpdateBody(state) },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.ui_update_download))
                }
                UpdatePhase.READY -> TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.ui_update_install))
                }
                UpdatePhase.FAILED -> TextButton(onClick = onCheckAgain) {
                    Text(stringResource(R.string.ui_update_try_again))
                }
                UpdatePhase.BLOCKED -> if (state.offerPermission) {
                    TextButton(onClick = onGrantPermission) {
                        Text(stringResource(R.string.ui_update_allow_installs))
                    }
                } else {
                    CloseButton(onDismiss)
                }
                // Nothing affirmative to offer while something is in flight, so Close is
                // the confirm slot and Cancel sits beside it.
                UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING,
                UpdatePhase.UP_TO_DATE,
                -> CloseButton(onDismiss)
            }
        },
        dismissButton = {
            when (state.phase) {
                UpdatePhase.DOWNLOADING -> TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.ui_update_cancel))
                }
                UpdatePhase.AVAILABLE, UpdatePhase.READY, UpdatePhase.FAILED -> CloseButton(onDismiss)
                // Close is already the confirm slot in these, so a second one would read
                // as two ways to do the same thing.
                UpdatePhase.BLOCKED -> if (state.offerPermission) CloseButton(onDismiss)
                UpdatePhase.CHECKING, UpdatePhase.INSTALLING, UpdatePhase.UP_TO_DATE -> Unit
            }
        },
    )
}

@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_diagnostics_close)) }
}

@Composable
private fun UpdateBody(state: UpdateDialogState) {
    // Same reason as the other two, and this one has the longest string in the app in it:
    // the signing-key refusal runs to three lines in portrait and more in landscape.
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            when (state.phase) {
                UpdatePhase.CHECKING -> stringResource(R.string.ui_update_checking)
                UpdatePhase.UP_TO_DATE -> stringResource(R.string.ui_update_up_to_date)
                UpdatePhase.AVAILABLE -> stringResource(
                    R.string.ui_update_available,
                    state.availableVersion,
                    megabytes(state.downloadBytes),
                )
                UpdatePhase.DOWNLOADING -> stringResource(
                    R.string.ui_update_downloading,
                    state.availableVersion,
                )
                UpdatePhase.READY -> stringResource(R.string.ui_update_ready, state.availableVersion)
                UpdatePhase.INSTALLING -> stringResource(R.string.ui_update_installing)
                UpdatePhase.BLOCKED, UpdatePhase.FAILED ->
                    state.message ?: stringResource(R.string.ui_update_failed_fallback)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.phase == UpdatePhase.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(
                stringResource(
                    R.string.ui_update_progress,
                    (state.fraction * 100).toInt(),
                    megabytes(state.downloadBytes),
                ),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.secondary,
            )
        }
        Text(
            stringResource(R.string.ui_update_installed, state.installedVersion),
            modifier = Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.fitText.secondary,
        )
    }
}

/** One decimal place, so 37,922,450 reads as 36.2 MB rather than as itself. */
private fun megabytes(bytes: Long): String = "%.1f".format(bytes / (1024.0 * 1024.0))
