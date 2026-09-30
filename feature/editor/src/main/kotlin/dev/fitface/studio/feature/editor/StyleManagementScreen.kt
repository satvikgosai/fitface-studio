package dev.fitface.studio.feature.editor

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fitface.studio.core.model.*
import dev.fitface.studio.core.ui.*

@Composable
internal fun StyleManagementRoute(projectId: Long, protectedVariant: String? = null, initial: String? = null,
    capacity: ContainerCapacity? = null, onDismiss: () -> Unit, onDeleted: (EditorSnapshot) -> Unit,
    viewModel: StyleManagementViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    LaunchedEffect(state.confirming) { scroll.scrollTo(0) }
    LaunchedEffect(Unit) { viewModel.open(projectId, protectedVariant, initial) }
    LaunchedEffect(state.deleted) { state.deleted?.let { viewModel.close(); onDeleted(it) } }
    val dismiss = { if (!state.busy) { viewModel.close(); onDismiss() } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f), shape = MaterialTheme.shapes.large) {
            Column {
                FitTopBar(title = stringResource(if (state.confirming) R.string.editor_styles_delete_title else R.string.editor_manage_styles),
                    onBack = { if (state.confirming) viewModel.back() else dismiss() })
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.error?.let { StatusBanner(FitStatus.Fail, it) }
                    state.review?.let { review ->
                        val snapshot = review.snapshot
                        Text(stringResource(R.string.editor_style_capacity_usage, mebibytes(snapshot.containerBytes)),
                            style = MaterialTheme.typography.bodyMedium)
                        capacity?.let { Text(stringResource(R.string.editor_style_capacity_deficit, it.deficit)) }
                        Text(stringResource(R.string.editor_style_delete_explanation), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.fitText.secondary)
                        Text(stringResource(R.string.editor_aod_approximate), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.fitText.secondary)
                        val shown = if (state.confirming) snapshot.styleNames.filter { it in state.chosen } else snapshot.styleNames
                        shown.forEach { name ->
                            val chosen = name in state.chosen
                            val protected = name == state.protectedVariant
                            val selectable = !state.busy && !state.confirming && !protected &&
                                (chosen || state.chosen.size < snapshot.styleNames.size - 1)
                            val selection = if (state.confirming) Modifier else Modifier.toggleable(
                                value = chosen, enabled = selectable, role = Role.Checkbox,
                                onValueChange = { viewModel.toggle(name) })
                            Row(Modifier.fillMaxWidth().then(selection).padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FacePreview(review.previews[name], null, stringResource(R.string.editor_style_preview_a11y,
                                    variantLabel(name)), Modifier.width(48.dp).height(75.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(variantLabel(name), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.editor_style_reclaims, review.reclaimableBytes.getValue(name)),
                                        style = MaterialTheme.typography.bodySmall)
                                    if (protected || snapshot.styleNames.size == 1) Text(stringResource(if (protected)
                                        R.string.editor_style_protected else R.string.editor_style_keep_one),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.fitText.secondary)
                                }
                                if (!state.confirming) Checkbox(chosen, onCheckedChange = null, enabled = selectable)
                            }
                        }
                        if (state.chosen.isNotEmpty()) {
                            val mapping = survivingStyleNames(snapshot.styleNames, state.chosen)
                            Text(stringResource(R.string.editor_style_delete_savings,
                                state.chosen.sumOf { review.reclaimableBytes.getValue(it) }, mapping.size))
                            if (state.confirming) {
                                Text(stringResource(R.string.editor_style_renumbering))
                                mapping.forEach { (old, next) -> Text("${variantLabel(old)} → ${variantLabel(next)}") }
                                Text(stringResource(R.string.editor_style_active_after, variantLabel(survivingActiveStyle(
                                    snapshot.styleNames, mapping, snapshot.activeStyleName))))
                                Text(stringResource(R.string.editor_style_delete_warning), color = MaterialTheme.colorScheme.error)
                                if (state.protectedVariant != null) Text(stringResource(R.string.editor_style_delete_saved))
                            }
                        }
                        if (snapshot.hasAod) Text(stringResource(R.string.editor_style_aod_kept),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.fitText.secondary)
                    }
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FitButton(stringResource(if (state.confirming) R.string.editor_styles_delete_confirm else R.string.editor_styles_review_deletion),
                        onClick = { if (state.confirming) viewModel.delete() else viewModel.review() },
                        enabled = !state.busy && state.chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                        style = if (state.confirming) FitButtonStyle.Danger else FitButtonStyle.Primary)
                    TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.editor_cancel))
                    }
                }
            }
        }
    }
}
