package dev.fitface.studio.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The one action a top bar carries, and the three things behind it.
 *
 * There used to be a bare "Report a problem" button here. The app is sideloaded, reports
 * to no console and updates through no store, so it needs two more things a person can
 * reach — what version they are running, and whether there is a newer one — and three
 * buttons do not fit in a bar that has already had one width bug.
 *
 * **The control stays exactly one [FitIconButton].** `FitTopBar` gives its title column
 * `weight(1f)`, so every dp an action takes comes out of the title; a text-labelled action
 * once measured 109dp of a 360dp bar and ellipsized the subtitle on every editor page. A
 * `DropdownMenu` costs nothing against that budget because it is a `Popup` — a separate
 * window, measured outside this composition — and the anchoring [Box] wraps its content,
 * so what the bar's `Row` measures is still one 38dp square.
 * `FitTopBarLayoutTest.theOpenMenuCostsTheBarNoWidth` pins that.
 *
 * The glyph is `≡` rather than the platform's `⋮`, because the editor's Canvas page
 * already carries a `⋯` that *navigates to a page*. Two ellipses side by side, one a menu
 * and one a destination, is the ambiguity worth avoiding. `⚙` and `ℹ` were the other
 * candidates and were rejected: U+2699 and U+2139 fall through to the emoji font on many
 * Android builds and would render in colour, the only such glyph in the inventory.
 *
 * @param tint overrides the glyph colour. The library header uses it to mark a crash from
 *   the previous run without changing the control's size or shape.
 * @param contentDescription overrides the label. The crash case passes its own, so the
 *   offer still reaches someone using a screen reader — a colour alone cannot say "this is
 *   about the crash you just had".
 * @param reportLabel the first entry's label, and [reportTint] its colour. Both move for a
 *   crash too: an amber `≡` says much less than an amber `!` did, so the reason has to
 *   survive the extra tap.
 */
@Composable
fun AppMenuAction(
    onReportProblem: () -> Unit,
    onAbout: () -> Unit,
    onCheckForUpdate: () -> Unit,
    tint: Color? = null,
    contentDescription: String = stringResource(R.string.ui_app_menu_a11y),
    reportLabel: String = stringResource(R.string.ui_diagnostics_action),
    reportTint: Color? = null,
) {
    // `remember` and not `rememberSaveable`: a menu should be closed after a rotation or a
    // process restart, not reopen itself over whatever is on screen.
    var expanded by remember { mutableStateOf(false) }

    Box {
        FitIconButton(
            glyph = "≡",
            contentDescription = contentDescription,
            onClick = { expanded = true },
            tint = tint,
        )
        FitDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FitMenuEntry(reportLabel, reportTint) { expanded = false; onReportProblem() }
            FitMenuEntry(stringResource(R.string.ui_app_menu_about)) {
                expanded = false
                onAbout()
            }
            FitMenuEntry(stringResource(R.string.ui_app_menu_update)) {
                expanded = false
                onCheckForUpdate()
            }
        }
    }
}

/**
 * Closes the menu **before** invoking the callback, in every entry.
 *
 * Each of these opens a dialog, and a popup left standing behind one is the first thing
 * that goes wrong with a construction like this.
 */
/**
 * A menu surface in this design: a lighter container and a 1dp border, never a shadow.
 *
 * Shared rather than copied because there are two menus now — this bar's, and the one on a
 * project row — and a second hand-assembled `DropdownMenu` had already drifted: it took
 * Material's default entry type, `labelLarge`, against this one's `bodyMedium`, so the same
 * gesture produced two different-looking menus in one app.
 */
@Composable
fun FitDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // Elevation in this design is a lighter surface and a 1dp border, never a shadow.
        // Material's own default would put one here.
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}

/**
 * One line of a [FitDropdownMenu].
 *
 * @param tint overrides the label colour — the crash offer uses it, and so does a
 *   destructive entry, which takes `colorScheme.error` like every other danger in the app.
 *
 * Every caller closes the menu before it runs its callback: these all open a dialog or
 * navigate, and a popup left standing behind one is the first thing to go wrong here.
 */
@Composable
fun FitMenuEntry(label: String, tint: Color? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = tint ?: MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
    )
}
