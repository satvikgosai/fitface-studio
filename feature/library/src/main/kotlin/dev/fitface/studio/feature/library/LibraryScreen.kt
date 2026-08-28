package dev.fitface.studio.feature.library

import android.text.format.DateUtils
import androidx.annotation.StringRes
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import dev.fitface.studio.core.model.ProjectSort
import dev.fitface.studio.core.model.isOutdated
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.ui.DiagnosticsDialog
import dev.fitface.studio.core.ui.FitBadge
import dev.fitface.studio.core.ui.FitIconButton
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.AppMenuAction
import dev.fitface.studio.core.ui.FitChip
import dev.fitface.studio.core.ui.FitDropdownMenu
import dev.fitface.studio.core.ui.FitMenuEntry
import dev.fitface.studio.core.ui.FitFaceType
import dev.fitface.studio.core.ui.fitColors
import dev.fitface.studio.core.ui.fitText
import dev.fitface.studio.core.ui.FitStatus
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.StatusBanner
import java.io.File

@Composable
fun LibraryRoute(
    onOpenEditor: (Long) -> Unit,
    onAbout: () -> Unit,
    onCheckForUpdate: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    // Same show-then-clear shape as the error above, and for the same reason: clearing
    // first changes this effect's key while `showSnackbar` is still suspended and cancels
    // it, so the message appears for one frame and vanishes.
    val duplicated = state.duplicated
    val duplicatedText = duplicated?.let {
        stringResource(R.string.library_project_duplicated, it.name)
    }
    LaunchedEffect(duplicated?.id) {
        if (duplicated == null || duplicatedText == null) return@LaunchedEffect
        try {
            snackbar.showSnackbar(duplicatedText)
        } finally {
            viewModel.clearDuplicated(duplicated.id)
        }
    }

    state.diagnosticsReport?.let { report ->
        DiagnosticsDialog(report = report, onDismiss = viewModel::dismissDiagnostics)
    }
    state.renaming?.let { project ->
        RenameProjectDialog(
            project = project,
            onDismiss = viewModel::dismissRename,
            onConfirm = viewModel::confirmRename,
        )
    }
    state.deleting?.let { project ->
        DeleteProjectDialog(
            project = project,
            onDismiss = viewModel::dismissDelete,
            onConfirm = viewModel::confirmDelete,
        )
    }

    LibraryScreen(
        state = state,
        snackbar = snackbar,
        onRefresh = viewModel::refreshCatalog,
        onReportProblem = viewModel::showDiagnostics,
        onAbout = onAbout,
        onCheckForUpdate = onCheckForUpdate,
        onQuery = viewModel::setQuery,
        onSort = viewModel::setSort,
        onReverseSort = viewModel::reverseSort,
        onProjectQuery = viewModel::setProjectQuery,
        onProjectSort = viewModel::setProjectSort,
        onReverseProjectSort = viewModel::reverseProjectSort,
        onFace = viewModel::selectFace,
        onDismissFace = viewModel::dismissFace,
        onStyle = viewModel::selectStyle,
        onDownload = viewModel::downloadSelectedFace,
        onProjectClick = viewModel::openProject,
        onRenameProject = viewModel::startRename,
        onDuplicateProject = viewModel::duplicateProject,
        onDeleteProject = viewModel::startDelete,
    )
}

/**
 * The two halves of the library, each with the headline and one-line explanation that go
 * with it. The strings hang off the entries so the header can measure the page it is not
 * showing — see [LibraryHeader].
 */
internal enum class LibraryPage(@StringRes val title: Int, @StringRes val subtitle: Int) {
    WatchFaces(R.string.library_title_watch_faces, R.string.library_subtitle_watch_faces),
    Projects(R.string.library_title_projects, R.string.library_subtitle_projects),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onReportProblem: () -> Unit,
    onAbout: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onQuery: (String) -> Unit,
    onSort: (CatalogSort) -> Unit,
    onReverseSort: () -> Unit,
    onProjectQuery: (String) -> Unit,
    onProjectSort: (ProjectSort) -> Unit,
    onReverseProjectSort: () -> Unit,
    onFace: (CatalogFace) -> Unit,
    onDismissFace: () -> Unit,
    onStyle: (Int) -> Unit,
    onDownload: () -> Unit,
    onProjectClick: (ProjectSummary) -> Unit,
    onRenameProject: (ProjectSummary) -> Unit,
    onDuplicateProject: (ProjectSummary) -> Unit,
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
                state = state,
                projectCount = state.projects.size,
                loading = state.isLoadingCatalog,
                onPage = { page = it },
                onRefresh = onRefresh,
                onReportProblem = onReportProblem,
                onAbout = onAbout,
                onCheckForUpdate = onCheckForUpdate,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (page) {
                LibraryPage.WatchFaces -> WatchFaceGrid(
                    state = state,
                    onQuery = onQuery,
                    onSort = onSort,
                    onReverseSort = onReverseSort,
                    onRefresh = onRefresh,
                    onFace = onFace,
                    modifier = Modifier.fillMaxSize(),
                )
                LibraryPage.Projects -> ProjectsList(
                    state = state,
                    enabled = !state.isWorking,
                    onQuery = onProjectQuery,
                    onSort = onProjectSort,
                    onReverseSort = onReverseProjectSort,
                    onBrowse = { page = LibraryPage.WatchFaces },
                    onOpen = onProjectClick,
                    onRename = onRenameProject,
                    onDuplicate = onDuplicateProject,
                    onRemove = onDeleteProject,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    state.selectedFace?.let { face ->
        FaceDetailsSheet(
            face = face,
            faceProjects = state.projectsFor(face.faceId),
            selectedStyleId = state.selectedStyleId,
            downloading = state.downloadingProductId == face.productId,
            downloadFraction = state.downloadFraction,
            uneditable = face.appId in state.uneditableAppIds,
            packageOnDevice = state.selectedFaceCached,
            projectsEnabled = !state.isWorking,
            error = state.sheetError,
            onDismiss = onDismissFace,
            onStyle = onStyle,
            onDownload = onDownload,
            onOpenProject = onProjectClick,
        )
    }
}

/**
 * The minimum touch target, and the height the header's action row keeps whether or not
 * REFRESH is in it. Material gives every clickable this floor already; naming it here is what
 * stops the row from shrinking to its content on the page that has no text button.
 */
private val ACTION_TOUCH_TARGET = 48.dp

@Composable
internal fun LibraryHeader(
    page: LibraryPage,
    state: LibraryUiState,
    projectCount: Int,
    loading: Boolean,
    onPage: (LibraryPage) -> Unit,
    onRefresh: () -> Unit,
    onReportProblem: () -> Unit,
    onAbout: () -> Unit,
    onCheckForUpdate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 17.dp),
    ) {
        // The brand label sits above this Row rather than inside it. It used to share a Column
        // with the headline, and the actions — centred against that pair — landed between the
        // two, aligned with neither: the report button's touch target straddled the label above
        // it and most of the headline below. With only the headline in the Row, centring means
        // what it says.
        MicroLabel(
            stringResource(R.string.library_brand),
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp)
                // REFRESH is a TextButton, so it carries the 48dp minimum touch target and the
                // row is that tall on Watch faces; on Projects, where it is absent, the row
                // measured its 40dp headline instead and everything below moved up 26px. The
                // floor is the touch target, so both pages measure the same at every font
                // scale the headline stays under it.
                .heightIn(min = ACTION_TOUCH_TARGET),
            // No arrangement: the headline takes the weight, so there is no free space left
            // for one to distribute. `SpaceBetween` here would read as if it were doing
            // something.
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(page.title),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineLarge,
            )
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
            // Last, so its right edge is pinned to the header padding. Emitted before REFRESH
            // it moved 189px sideways when you switched to Projects and REFRESH went away.
            //
            // A crash in the previous run changes the colour and the label, never the size or
            // the shape: the old crash branch swapped in a differently-styled TextButton, so
            // the control changed typeface and width depending on whether the last run died.
            // With the report behind a menu, the wording has to move too — an amber glyph on
            // its own cannot say what it is amber about — so the first entry is relabelled
            // and tinted with it.
            if (state.previousCrash) {
                val warning = MaterialTheme.fitColors.warning
                val crashLabel = stringResource(R.string.library_previous_crash)
                AppMenuAction(
                    onReportProblem = onReportProblem,
                    onAbout = onAbout,
                    onCheckForUpdate = onCheckForUpdate,
                    tint = warning,
                    contentDescription = crashLabel,
                    reportLabel = crashLabel,
                    reportTint = warning,
                )
            } else {
                AppMenuAction(
                    onReportProblem = onReportProblem,
                    onAbout = onAbout,
                    onCheckForUpdate = onCheckForUpdate,
                )
            }
        }
        // Both subtitles are laid out and only the current page's is drawn, so the box is as
        // tall as the longer of the two wraps at this width. The strings are different lengths
        // — on a narrow phone "Pick a Fit3 face, download its package, then edit and install
        // it." takes two lines and "Continue an edit saved privately on this device." takes one
        // — so sizing the box to whichever page was showing dropped the tabs below it by a line
        // and switching tabs moved the tab you had just tapped out from under your finger.
        // Measuring both is exact at any width, font scale and translation; reserving a fixed
        // two lines would only be exact at the ones checked by hand.
        Box(modifier = Modifier.padding(top = 6.dp)) {
            LibraryPage.entries.forEach { candidate ->
                Text(
                    stringResource(candidate.subtitle),
                    // The pages not showing are there for their height alone: invisible, and
                    // out of the semantics tree so a screen reader does not read all of them.
                    modifier = if (candidate == page) {
                        Modifier
                    } else {
                        Modifier.alpha(0f).clearAndSetSemantics { }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    onReverseSort: () -> Unit,
    onRefresh: () -> Unit,
    onFace: (CatalogFace) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolved out here: a `semantics` lambda is not a composable scope, so it cannot read
    // a resource itself.
    val catalogueDescription = stringResource(R.string.library_catalogue_a11y)
    // Counted once for the whole grid, not per card. `projectsFor` filters the project
    // list, and asking it per card is that filter run once for every one of the hundred
    // faces on screen, on every frame the grid scrolls.
    val projectCounts = remember(state.projects) {
        state.projects.groupingBy(ProjectSummary::faceId).eachCount()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        modifier = modifier.semantics { contentDescription = catalogueDescription },
        contentPadding = LibraryPageInsets,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryPageControls(
                query = state.query,
                onQuery = onQuery,
                searchPlaceholder = stringResource(R.string.library_search_placeholder),
                searchEnabled = state.downloadingProductId == null,
                sortOptions = CatalogSort.entries,
                sort = state.sort,
                sortReversed = state.sortReversed,
                // `canSelectFace`, not `isWorking`. The grid is painted from the on-disk
                // cache before the network is touched, so `isLoadingCatalog` is true for the
                // whole opening window of every launch while the screen already looks ready
                // — and re-sorting a list conflicts with a refresh no more than opening the
                // sheet does.
                sortEnabled = state.canSelectFace,
                sortLabel = { option, reversed -> catalogSortLabel(option, reversed) },
                onSort = onSort,
                onReverseSort = onReverseSort,
                sourceLabel = stringResource(
                    if (state.catalogFromCache) {
                        R.string.library_source_cached
                    } else {
                        R.string.library_source_live
                    },
                ),
                countLabel = stringResource(
                    R.string.library_face_and_style_count,
                    state.visibleFaces.size,
                    state.styleCount,
                ),
            )
        }

        if (state.isLoadingCatalog && state.faces.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { CatalogLoading() }
        } else if (state.faces.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                CatalogUnavailable(state.catalogFailure, onRefresh)
            }
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
                    projectCount = projectCounts[face.faceId] ?: 0,
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
                color = MaterialTheme.fitText.secondary,
            )
        }
    }
}

@Composable
private fun CatalogUnavailable(reason: String?, onRefresh: () -> Unit) {
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
            // The panel used to assert a connection fault whatever had happened, which is
            // how a store rejecting this phone's locale read as a network problem on a
            // phone with five bars and Wi-Fi.
            reason ?: stringResource(R.string.library_unavailable_detail),
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
    projectCount: Int,
    onClick: () -> Unit,
) {
    val styleCount = styleCountLabel(face.styles.size)
    // The project count replaces the style count rather than joining it: this row is one
    // line of micro type between two edges, and what someone scanning the grid for a face
    // they have already started wants is the count that is about them.
    val trailing = if (projectCount > 0) {
        pluralStringResource(R.plurals.library_face_card_projects, projectCount, projectCount)
    } else {
        styleCount
    }
    val cardDescription = stringResource(
        if (uneditable) {
            R.string.library_face_card_a11y_not_editable
        } else {
            R.string.library_face_card_a11y
        },
        face.name,
        face.faceId,
        // Both facts reach a screen reader; only one of them fits on the card.
        if (projectCount > 0) "$styleCount, $trailing" else styleCount,
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
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = if (projectCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.fitText.secondary
                },
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
    faceProjects: List<ProjectSummary>,
    selectedStyleId: Int?,
    downloading: Boolean,
    downloadFraction: Float,
    uneditable: Boolean,
    packageOnDevice: Boolean,
    projectsEnabled: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onStyle: (Int) -> Unit,
    onDownload: () -> Unit,
    onOpenProject: (ProjectSummary) -> Unit,
) {
    val selected = face.styles.singleOrNull { it.id == selectedStyleId } ?: face.styles.first()
    val action =
        faceAction(downloading, uneditable, packageOnDevice, faceProjects, face.versionCode)
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
                if (faceProjects.isNotEmpty()) {
                    // A plain Column, never a LazyColumn. This region is already inside a
                    // `verticalScroll`, and nesting a vertical lazy list in one throws at
                    // measure time. The LazyRow of style thumbnails below is fine because it
                    // scrolls the other way.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MicroLabel(stringResource(R.string.library_sheet_your_projects))
                        Text(
                            faceProjects.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.fitText.secondary,
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        faceProjects.forEach { project ->
                            SheetProjectRow(
                                project = project,
                                outdated = project.isOutdated(face.versionCode),
                                enabled = projectsEnabled,
                                onOpen = { onOpenProject(project) },
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    MicroLabel(
                        stringResource(R.string.library_sheet_start_new),
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
                    // UPDATE and NEW_PROJECT run the same code — `onDownload` always fetches
                    // the version the catalogue is serving now, which *is* the newest. They
                    // differ only in what the button says, and that was the part that was
                    // wrong: it promised a download that would not happen, or offered to
                    // start something new without mentioning the newer version it would use.
                    text = when (action) {
                        FaceAction.OPENING -> stringResource(R.string.library_open_in_progress)
                        FaceAction.DOWNLOADING -> stringResource(R.string.library_download_in_progress)
                        FaceAction.NOT_EDITABLE -> stringResource(R.string.library_download_not_editable)
                        FaceAction.UPDATE ->
                            stringResource(R.string.library_update_to, face.versionName)
                        FaceAction.NEW_PROJECT -> stringResource(R.string.library_new_project)
                        FaceAction.OPEN -> stringResource(R.string.library_open_edit)
                        FaceAction.DOWNLOAD -> stringResource(R.string.library_download)
                    },
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !downloading && !uneditable,
                    loading = downloading,
                )
                // Only while bytes are actually arriving. A determinate bar over a cached
                // package would jump straight to full and say nothing true on the way.
                if (action == FaceAction.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { downloadFraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
                Text(
                    stringResource(
                        when {
                            action == FaceAction.UPDATE -> R.string.library_update_note
                            packageOnDevice && action != FaceAction.NOT_EDITABLE ->
                                R.string.library_new_project_note
                            else -> R.string.library_download_cache_note
                        },
                    ),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.fitText.secondary,
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
    state: LibraryUiState,
    enabled: Boolean,
    onQuery: (String) -> Unit,
    onSort: (ProjectSort) -> Unit,
    onReverseSort: () -> Unit,
    onBrowse: () -> Unit,
    onOpen: (ProjectSummary) -> Unit,
    onRename: (ProjectSummary) -> Unit,
    onDuplicate: (ProjectSummary) -> Unit,
    onRemove: (ProjectSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val projects = state.visibleProjects
    // Only one menu is ever composed, because only one id can be held here. A DropdownMenu
    // is a Popup — its own window — and one per row would be one window per project.
    var openMenuFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // Said once, above the list, rather than on each row that shares a face: it is a fact
    // about the watch, not about any one project.
    val sharesAFace = remember(state.projects) {
        state.projects.groupingBy(ProjectSummary::faceId).eachCount().any { it.value > 1 }
    }
    LazyColumn(
        modifier = modifier,
        // The catalogue's insets, not its own. They used to differ by 4dp horizontally and
        // 2dp vertically, which is why the search field and every sort chip stepped sideways
        // and up when you switched tabs.
        contentPadding = LibraryPageInsets,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (state.projects.isEmpty()) {
            item { ProjectsEmptyState(onBrowse) }
            return@LazyColumn
        }
        // The same controls the catalogue has, in the same place, scrolling with the list.
        item {
            Column {
                LibraryPageControls(
                    query = state.projectQuery,
                    onQuery = onQuery,
                    searchPlaceholder = stringResource(R.string.library_projects_search_placeholder),
                    searchEnabled = true,
                    sortOptions = ProjectSort.entries,
                    sort = state.projectSort,
                    sortReversed = state.projectSortReversed,
                    sortEnabled = true,
                    sortLabel = { option, reversed -> projectSortLabel(option, reversed) },
                    onSort = onSort,
                    onReverseSort = onReverseSort,
                    sourceLabel = stringResource(R.string.library_projects_saved),
                    countLabel = stringResource(
                        R.string.library_projects_local_count,
                        projects.size,
                    ),
                )
                if (sharesAFace) {
                    Text(
                        stringResource(R.string.library_projects_shared_face_note),
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.fitText.secondary,
                    )
                }
            }
        }
        if (projects.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.library_projects_no_matches_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.library_projects_no_matches_detail),
                        modifier = Modifier.padding(top = 7.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            items(projects, key = ProjectSummary::id) { project ->
                ProjectRow(
                    project = project,
                    enabled = enabled,
                    menuOpen = openMenuFor == project.id,
                    onOpen = { onOpen(project) },
                    onOpenMenu = { openMenuFor = project.id },
                    onDismissMenu = { openMenuFor = null },
                    onRename = { onRename(project) },
                    onDuplicate = { onDuplicate(project) },
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
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .padding(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        ProjectThumbnail(project)
        Column(Modifier.weight(1f)) {
            Text(
                // The project's own name, not the face's. `faceName` is the same string for
                // every project started on a face, which is what made two of them on one
                // face impossible to tell apart.
                project.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                projectFaceLine(project),
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.secondary,
            )
            Text(
                relativeAge(project.updatedAtEpochMillis),
                modifier = Modifier.padding(top = 3.dp),
                // Mono, like the face line directly above it: this is a quantity, and it was
                // the one numeral in the app still set in a proportional face.
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.tertiary,
            )
        }
        ProjectMenu(
            project = project,
            enabled = enabled,
            expanded = menuOpen,
            onOpen = onOpenMenu,
            onDismiss = onDismissMenu,
            onRename = onRename,
            onDuplicate = onDuplicate,
            onRemove = onRemove,
        )
        Text(
            "›",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/**
 * Rename and delete, behind one action on the row.
 *
 * It replaces a bare `×` TextButton that had no `contentDescription` at all — a screen
 * reader announced it as "multiplication sign" — and that deleted every edit in a project
 * with no confirmation. Two near-identical rows on one face make that much worse.
 *
 * The glyph is `⋯` and not the app menu's `≡`. `AGENTS.md` records why the header menu is
 * `≡`: the editor's Canvas page already carries a `⋯` that *navigates*, and two ellipses
 * side by side read as one control with two behaviours. There is no such `⋯` on this
 * screen, so here `⋯` means what it usually means.
 *
 * Both entries close the menu before invoking their callback, because both open a dialog
 * and a popup left standing behind one is the first thing to go wrong.
 */
@Composable
private fun ProjectMenu(
    project: ProjectSummary,
    enabled: Boolean,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
) {
    Box {
        FitIconButton(
            glyph = "⋯",
            contentDescription = stringResource(R.string.library_project_more_a11y, project.name),
            onClick = onOpen,
            enabled = enabled,
        )
        // The app menu's surface and entries, not a second set assembled by hand: built
        // separately these took Material's default entry type against the bar menu's
        // `bodyMedium`, so the same gesture opened two different-looking menus.
        FitDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            FitMenuEntry(stringResource(R.string.library_project_rename)) {
                onDismiss()
                onRename()
            }
            FitMenuEntry(stringResource(R.string.library_project_duplicate)) {
                onDismiss()
                onDuplicate()
            }
            // Last, and the only one that is destructive. Nothing above it can lose work,
            // so the entry that can is the one furthest from where the menu opens.
            FitMenuEntry(
                stringResource(R.string.library_project_delete),
                MaterialTheme.colorScheme.error,
            ) {
                onDismiss()
                onRemove()
            }
        }
    }
}

/**
 * One of this face's projects, inside the face sheet.
 *
 * Internal so `SheetProjectRowA11yTest` can render it: this row builds its own
 * `contentDescription`, which is the only thing a screen reader hears, so what it does and
 * does not include is worth an assertion.
 */
@Composable
internal fun SheetProjectRow(
    project: ProjectSummary,
    outdated: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val age = relativeAge(project.updatedAtEpochMillis)
    // The badge has to be in the description, not merely beside it. This row sets its own
    // `contentDescription`, which replaces whatever a screen reader would have assembled
    // from the text inside it — so OUTDATED was drawn and never announced, and it is the
    // only thing telling this row apart from the siblings above and below it. The grid's
    // cards already carry a second string for exactly this reason.
    val description = stringResource(
        if (outdated) {
            R.string.library_sheet_project_a11y_outdated
        } else {
            R.string.library_sheet_project_a11y
        },
        project.name,
        projectFaceLine(project),
        age,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = description }
            .padding(start = 10.dp, top = 9.dp, end = 12.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        ProjectThumbnail(project)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.name,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (outdated) {
                    FitBadge(
                        stringResource(R.string.library_project_outdated),
                        MaterialTheme.fitColors.warning,
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                "${projectFaceLine(project)} · $age",
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.secondary,
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
 * "face 00112 · style 01", or just the face when the style could not be recovered.
 *
 * The style is numbered the way the sheet's thumbnails are — `styleN.bin` is zero-based and
 * the labels beside the previews are not — so the two agree about which colourway is which.
 */
@Composable
private fun projectFaceLine(project: ProjectSummary): String {
    val styleId = project.styleId
    return if (styleId == null) {
        stringResource(R.string.library_project_face_line_plain, project.faceId)
    } else {
        stringResource(
            R.string.library_project_face_line,
            project.faceId,
            stringResource(
                R.string.library_project_style,
                (styleId + 1).toString().padStart(2, '0'),
            ),
        )
    }
}

/**
 * "2 hr. ago", for a moment in the past.
 *
 * Keyed on the timestamp alone: the composition it is read in re-runs on an edit, which is
 * the only thing that moves this value, and a clock that had to tick would recompose every
 * row in the list for a string most readers never watch change.
 */
@Composable
private fun relativeAge(epochMillis: Long): String = remember(epochMillis) {
    DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
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

/**
 * Renames a project.
 *
 * The text slot scrolls, like every dialog's in this app: an `AlertDialog` caps its own
 * height and hands the slot whatever is left, which on a landscape phone is a few lines,
 * and it clips rather than scrolls. About lost its link and its version to exactly that.
 */
@Composable
private fun RenameProjectDialog(
    project: ProjectSummary,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(project.id) { mutableStateOf(project.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_rename_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                // Blank is refused here rather than in the repository, which ignores it: a
                // dimmed button says why nothing happens, where a silent no-op does not.
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.library_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

/**
 * Confirms deleting a project.
 *
 * Deleting discards every edit saved in it and cannot be undone. It used to happen on one
 * tap of an unlabelled `×`; with more than one project on a face, the row beside the one
 * being deleted can look almost identical to it.
 */
@Composable
private fun DeleteProjectDialog(
    project: ProjectSummary,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_delete_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.library_delete_message, project.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.library_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}
