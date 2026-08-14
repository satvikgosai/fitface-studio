package dev.fitface.studio.feature.library

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.CatalogSort
import dev.fitface.studio.core.model.FaceStyleOption
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitChip
import dev.fitface.studio.core.ui.FitFaceType
import dev.fitface.studio.core.ui.FitStatus
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.StatusBanner
import java.io.File

@Composable
fun LibraryRoute(
    onOpenEditor: (Long) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is LibraryEvent.OpenEditor) onOpenEditor(event.projectId)
        }
    }
    // Cleared after the snackbar has been shown: clearing first changed this effect's
    // key mid-`showSnackbar` and cancelled it, so the message only flickered.
    val message = state.error
    LaunchedEffect(message?.id) {
        if (message == null) return@LaunchedEffect
        try {
            snackbar.showSnackbar(message.text)
        } finally {
            viewModel.clearError(message.id)
        }
    }

    LibraryScreen(
        state = state,
        projects = projects,
        snackbar = snackbar,
        onRefresh = viewModel::refreshCatalog,
        onQuery = viewModel::setQuery,
        onSort = viewModel::setSort,
        onFace = viewModel::selectFace,
        onDismissFace = viewModel::dismissFace,
        onStyle = viewModel::selectStyle,
        onDownload = viewModel::downloadSelectedFace,
        onProjectClick = viewModel::openProject,
        onDeleteProject = viewModel::deleteProject,
    )
}

private enum class LibraryPage { WatchFaces, Projects }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    projects: List<ProjectSummary>,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onQuery: (String) -> Unit,
    onSort: (CatalogSort) -> Unit,
    onFace: (CatalogFace) -> Unit,
    onDismissFace: () -> Unit,
    onStyle: (Int) -> Unit,
    onDownload: () -> Unit,
    onProjectClick: (ProjectSummary) -> Unit,
    onDeleteProject: (ProjectSummary) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(LibraryPage.WatchFaces) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LibraryHeader(
                page = page,
                projectCount = projects.size,
                loading = state.isLoadingCatalog,
                onPage = { page = it },
                onRefresh = onRefresh,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (page) {
                LibraryPage.WatchFaces -> WatchFaceGrid(
                    state = state,
                    onQuery = onQuery,
                    onSort = onSort,
                    onRefresh = onRefresh,
                    onFace = onFace,
                    modifier = Modifier.fillMaxSize(),
                )
                LibraryPage.Projects -> ProjectsList(
                    projects = projects,
                    enabled = !state.isWorking,
                    onBrowse = { page = LibraryPage.WatchFaces },
                    onOpen = onProjectClick,
                    onRemove = onDeleteProject,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    state.selectedFace?.let { face ->
        FaceDetailsSheet(
            face = face,
            selectedStyleId = state.selectedStyleId,
            downloading = state.downloadingProductId == face.productId,
            downloadFraction = state.downloadFraction,
            uneditable = face.appId in state.uneditableAppIds,
            error = state.sheetError,
            onDismiss = onDismissFace,
            onStyle = onStyle,
            onDownload = onDownload,
        )
    }
}

@Composable
private fun LibraryHeader(
    page: LibraryPage,
    projectCount: Int,
    loading: Boolean,
    onPage: (LibraryPage) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 17.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                MicroLabel(
                    stringResource(R.string.library_brand),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(
                        if (page == LibraryPage.WatchFaces) {
                            R.string.library_title_watch_faces
                        } else {
                            R.string.library_title_projects
                        },
                    ),
                    modifier = Modifier.padding(top = 7.dp),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            if (page == LibraryPage.WatchFaces) {
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text(
                        stringResource(
                            if (loading) {
                                R.string.library_action_syncing
                            } else {
                                R.string.library_action_refresh
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Text(
            stringResource(
                if (page == LibraryPage.WatchFaces) {
                    R.string.library_subtitle_watch_faces
                } else {
                    R.string.library_subtitle_projects
                },
            ),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FitChip(
                text = stringResource(R.string.library_tab_watch_faces),
                selected = page == LibraryPage.WatchFaces,
                onClick = { onPage(LibraryPage.WatchFaces) },
                modifier = Modifier.weight(1f),
            )
            FitChip(
                text = stringResource(R.string.library_tab_projects, projectCount),
                selected = page == LibraryPage.Projects,
                onClick = { onPage(LibraryPage.Projects) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WatchFaceGrid(
    state: LibraryUiState,
    onQuery: (String) -> Unit,
    onSort: (CatalogSort) -> Unit,
    onRefresh: () -> Unit,
    onFace: (CatalogFace) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolved out here: a `semantics` lambda is not a composable scope, so it cannot read
    // a resource itself.
    val catalogueDescription = stringResource(R.string.library_catalogue_a11y)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        modifier = modifier.semantics { contentDescription = catalogueDescription },
        contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.downloadingProductId == null,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                    leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = {}),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MicroLabel(stringResource(R.string.library_sort_label), Modifier.padding(end = 3.dp))
                    CatalogSort.entries.forEach { option ->
                        FitChip(
                            text = option.label,
                            selected = state.sort == option,
                            onClick = { onSort(option) },
                            enabled = !state.isWorking,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MicroLabel(
                        stringResource(
                            if (state.catalogFromCache) {
                                R.string.library_source_cached
                            } else {
                                R.string.library_source_live
                            },
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.library_face_and_style_count,
                            state.visibleFaces.size,
                            state.styleCount,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
                    )
                }
            }
        }

        if (state.isLoadingCatalog && state.faces.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { CatalogLoading() }
        } else if (state.faces.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { CatalogUnavailable(onRefresh) }
        } else if (state.visibleFaces.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.library_no_matches_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.library_no_matches_detail),
                        modifier = Modifier.padding(top = 7.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            gridItems(state.visibleFaces, key = CatalogFace::productId) { face ->
                WatchFaceCard(
                    face = face,
                    enabled = !state.isWorking,
                    uneditable = face.appId in state.uneditableAppIds,
                    onClick = { onFace(face) },
                )
            }
        }
    }
}

@Composable
private fun CatalogLoading() {
    val loadingDescription = stringResource(R.string.library_loading_a11y)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(16.dp)
            .semantics { contentDescription = loadingDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Column {
            Text(
                stringResource(R.string.library_loading_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.library_loading_detail),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .68f),
            )
        }
    }
}

@Composable
private fun CatalogUnavailable(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(horizontal = 24.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.library_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.library_unavailable_detail),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FitButton(
            stringResource(R.string.library_try_again),
            onRefresh,
            Modifier.fillMaxWidth().padding(top = 18.dp),
        )
    }
}

@Composable
private fun WatchFaceCard(
    face: CatalogFace,
    enabled: Boolean,
    uneditable: Boolean,
    onClick: () -> Unit,
) {
    val styleCount = styleCountLabel(face.styles.size)
    val cardDescription = stringResource(
        if (uneditable) {
            R.string.library_face_card_a11y_not_editable
        } else {
            R.string.library_face_card_a11y
        },
        face.name,
        face.faceId,
        styleCount,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = cardDescription }
            .padding(8.dp),
    ) {
        Box {
            AsyncImage(
                model = face.styles.first().previewUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(256f / 402f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentScale = ContentScale.Fit,
            )
            if (uneditable) {
                Text(
                    stringResource(R.string.library_not_editable_badge),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = FitFaceType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            face.name,
            modifier = Modifier.padding(start = 3.dp, top = 10.dp, end = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.library_face_number, face.faceId.takeLast(3)),
                style = FitFaceType.micro,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                styleCount,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .68f),
            )
        }
    }
}

/**
 * "1 style" / "4 styles", from a plural resource rather than an inline `if`.
 *
 * Composable because that is where a resource is readable; every caller is already in a
 * composition, and the accessibility description needs the same words the card shows.
 */
@Composable
private fun styleCountLabel(count: Int): String =
    pluralStringResource(R.plurals.library_style_count, count, count)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FaceDetailsSheet(
    face: CatalogFace,
    selectedStyleId: Int?,
    downloading: Boolean,
    downloadFraction: Float,
    uneditable: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onStyle: (Int) -> Unit,
    onDownload: () -> Unit,
) {
    val selected = face.styles.singleOrNull { it.id == selectedStyleId } ?: face.styles.first()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The sheet exists to reach one button. Opening half-height put "Download &
        // edit" below the screen edge, where nothing said it was there to scroll to.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // The sheet is taller than the screen on a long description or a many-style
        // face, and a bottom sheet clips rather than scrolls: the download button fell
        // off the bottom edge with no way to reach it. The details scroll; the action
        // is pinned outside the scroll region so it is always reachable.
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MicroLabel(
                    stringResource(R.string.library_sheet_kind),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    face.name,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(
                        R.string.library_sheet_face_version,
                        face.faceId,
                        face.versionName,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AsyncImage(
                    model = selected.previewUrl,
                    contentDescription = stringResource(
                        R.string.library_sheet_preview_a11y,
                        face.name,
                        selected.id + 1,
                    ),
                    modifier = Modifier
                        .width(176.dp)
                        .aspectRatio(256f / 402f)
                        .padding(top = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentScale = ContentScale.Fit,
                )
                MicroLabel(
                    stringResource(R.string.library_sheet_choose_style),
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 9.dp),
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items(face.styles, key = FaceStyleOption::id) { style ->
                        StyleThumbnail(
                            faceName = face.name,
                            style = style,
                            selected = style.id == selected.id,
                            enabled = !downloading,
                            onClick = { onStyle(style.id) },
                        )
                    }
                }
                if (face.description.isNotBlank()) {
                    Text(
                        face.description,
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MicroLabel(stringResource(R.string.library_sheet_package))
                    Text(
                        formatBytes(face.packageSize),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                error?.let {
                    StatusBanner(
                        if (uneditable) FitStatus.Warning else FitStatus.Fail,
                        it,
                        modifier = Modifier.padding(bottom = 16.dp),
                        label = stringResource(
                            if (uneditable) {
                                R.string.library_sheet_uneditable_label
                            } else {
                                R.string.library_sheet_error_label
                            },
                        ),
                    )
                }
                FitButton(
                    text = stringResource(
                        when {
                            downloading -> R.string.library_download_in_progress
                            uneditable -> R.string.library_download_not_editable
                            else -> R.string.library_download
                        },
                    ),
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !downloading && !uneditable,
                    loading = downloading,
                )
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { downloadFraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
                Text(
                    stringResource(R.string.library_download_cache_note),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
                )
            }
        }
    }
}

@Composable
private fun StyleThumbnail(
    faceName: String,
    style: FaceStyleOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val thumbnailDescription = stringResource(
        if (selected) {
            R.string.library_style_thumb_a11y_selected
        } else {
            R.string.library_style_thumb_a11y
        },
        faceName,
        style.id + 1,
    )
    Column(
        modifier = Modifier
            .width(74.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.small,
            )
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = thumbnailDescription }
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = style.previewUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(256f / 402f)
                .clip(RoundedCornerShape(5.dp)),
            contentScale = ContentScale.Fit,
        )
        Text(
            (style.id + 1).toString().padStart(2, '0'),
            modifier = Modifier.padding(top = 5.dp),
            style = FitFaceType.micro,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProjectsList(
    projects: List<ProjectSummary>,
    enabled: Boolean,
    onBrowse: () -> Unit,
    onOpen: (ProjectSummary) -> Unit,
    onRemove: (ProjectSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (projects.isEmpty()) {
            item { ProjectsEmptyState(onBrowse) }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MicroLabel(stringResource(R.string.library_projects_saved))
                    Text(
                        stringResource(R.string.library_projects_local_count, projects.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .66f),
                    )
                }
            }
            items(projects, key = ProjectSummary::id) { project ->
                ProjectRow(
                    project = project,
                    enabled = enabled,
                    onOpen = { onOpen(project) },
                    onRemove = { onRemove(project) },
                )
            }
        }
    }
}

@Composable
private fun ProjectsEmptyState(onBrowse: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(horizontal = 22.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                    MaterialTheme.shapes.medium,
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = .3f),
                    MaterialTheme.shapes.medium,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "◇",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Text(
            stringResource(R.string.library_projects_empty_title),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.library_projects_empty_detail),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FitButton(
            stringResource(R.string.library_browse),
            onBrowse,
            Modifier.fillMaxWidth().padding(top = 18.dp),
        )
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val age = remember(project.importedAtEpochMillis) {
        DateUtils.getRelativeTimeSpanString(
            project.importedAtEpochMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        ProjectThumbnail(project)
        Column(Modifier.weight(1f)) {
            Text(
                project.faceName ?: stringResource(R.string.library_unnamed_face),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    R.string.library_project_face_line,
                    project.faceId,
                    project.displayName,
                ),
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .68f),
            )
            Text(
                age,
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .48f),
            )
        }
        TextButton(onClick = onRemove, enabled = enabled) {
            Text(
                "×",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Text(
            "›",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/**
 * The face a project holds, as a picture.
 *
 * This is the package's own image for the style the project was left on — the same
 * artwork the catalogue grid shows, extracted to app storage when the project was
 * opened. It is deliberately *not* a render of the edit: drawing that would mean
 * parsing every project's container to lay out this list, which is a whole library's
 * worth of work to fill in one column. The face number stays as the fallback for a
 * package that shipped no previews.
 */
@Composable
private fun ProjectThumbnail(project: ProjectSummary) {
    val shape = RoundedCornerShape(8.dp)
    val plate = Modifier
        .width(38.dp)
        .height(60.dp)
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ),
        )
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    val path = project.previewImagePath
    if (path != null) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            modifier = plate,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(plate, contentAlignment = Alignment.Center) {
            Text(
                project.faceId.takeLast(3),
                style = FitFaceType.micro,
                color = MaterialTheme.colorScheme.primary.copy(alpha = .78f),
            )
        }
    }
}

/**
 * A package size in the unit that keeps it to one decimal place.
 *
 * Composable so the unit suffixes and the "unknown" wording come from resources; the
 * numbers themselves are formatted by the resource, which is also what gets the decimal
 * separator right for the reader's locale.
 */
@Composable
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> stringResource(R.string.library_size_unavailable)
    bytes < 1024 -> stringResource(R.string.library_size_bytes, bytes)
    bytes < 1024 * 1024 -> stringResource(R.string.library_size_kib, bytes / 1024.0)
    else -> stringResource(R.string.library_size_mib, bytes / (1024.0 * 1024.0))
}
