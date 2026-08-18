package dev.fitface.studio.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Shows the pasteable report and puts it on the clipboard.
 *
 * There is a durable entry point for this on both top bars, not only an action on the
 * snackbar, because the snackbar is gone by the time most people decide to report
 * anything — and because the failures worth reporting here often raise no snackbar at
 * all: a container can edit, validate, transfer and be accepted while still drawing
 * wrong, and the only account of that is the edit history in this text.
 */
@Composable
fun DiagnosticsDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_diagnostics_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.ui_diagnostics_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    report,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            MaterialTheme.shapes.small,
                        )
                        .padding(10.dp)
                        // Wrapped rather than scrolled sideways: this text exists to be
                        // copied, and a line clipped at the edge reads as a report that
                        // is missing the part which mattered.
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { copyReport(context, report) }) {
                Text(stringResource(R.string.ui_diagnostics_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_diagnostics_close)) }
        },
    )
}

private fun copyReport(context: Context, report: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("FitFace Studio diagnostics", report))
    // Android 13 and up shows its own copy confirmation, and a second one on top of it
    // reads as the copy having happened twice.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.ui_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }
}

/** The overflow entry that opens [DiagnosticsDialog], for either top bar. */
@Composable
fun ReportProblemAction(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            stringResource(R.string.ui_diagnostics_action),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
