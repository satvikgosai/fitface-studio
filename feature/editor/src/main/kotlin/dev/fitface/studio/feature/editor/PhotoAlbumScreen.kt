package dev.fitface.studio.feature.editor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.fitface.studio.core.ui.FitInfo
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fitface.studio.core.delivery.DirectInstallPhase
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.model.ALBUM_PHOTO_HEIGHT
import dev.fitface.studio.core.model.ALBUM_PHOTO_WIDTH
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.PHOTO_ALBUM_FACE_ID
import dev.fitface.studio.core.model.PHOTO_ALBUM_LIMIT
import dev.fitface.studio.core.model.PhotoClockSetting
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.ui.*
import dev.fitface.studio.core.ui.FitInfoLink
import dev.fitface.studio.core.ui.InfoText
import kotlin.math.roundToInt

/** The Photos face's page: pick, place, send, or remove what this phone sent. */
@Composable
fun PhotoAlbumRoute(
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onCheckForUpdate: () -> Unit,
    viewModel: PhotoAlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    KeepScreenOn(state.directInstall.phase in SendingPhases)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.pick(uris.map(Uri::toString))
    }
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.refreshEnvironment()
        // As on the Install page: only a permission denied for good goes to Settings.
        val denied = result.filterValues { granted -> !granted }.keys
        val forGood = denied.any { permission ->
            val activity = context as? Activity
            activity != null && !activity.shouldShowRequestPermissionRationale(permission)
        }
        if (forGood) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refreshEnvironment() }
    val message = state.error
    LaunchedEffect(message?.id) {
        if (message == null) return@LaunchedEffect
        try {
            snackbar.showSnackbar(message.text)
        } finally {
            viewModel.clearError(message.id)
        }
    }
    // Taking a photo out says so, with the way back, until the next one or the message goes.
    val takenOut = state.takenOutLast
    val takenOutText = takenOut?.let {
        stringResource(if (it.sent != null) R.string.editor_photos_taken_off else R.string.editor_photos_taken_out)
    }
    val undo = stringResource(R.string.editor_undo)
    LaunchedEffect(takenOut?.id) {
        if (takenOut == null || takenOutText == null) return@LaunchedEffect
        try {
            val result = snackbar.showSnackbar(takenOutText, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.putBack(takenOut.id)
        } finally {
            viewModel.clearTakenOut(takenOut.id)
        }
    }
    val droppedText = if (state.dropped > 0) {
        pluralStringResource(R.plurals.editor_photos_dropped, state.dropped, state.dropped)
    } else null
    LaunchedEffect(droppedText) {
        if (droppedText == null) return@LaunchedEffect
        try {
            snackbar.showSnackbar(droppedText)
        } finally {
            viewModel.clearDropped()
        }
    }
    state.diagnosticsReport?.let { report ->
        DiagnosticsDialog(report = report, onDismiss = viewModel::dismissDiagnostics)
    }
    var page by rememberSaveable { mutableStateOf(PhotosPage.Canvas) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Above the rail, which is part of the page rather than the scaffold's bottom bar, so a
        // message's Undo never sits over a destination.
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = PhotosRailHeight)) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        // The editor's header: Canvas is titled with the face, as the editor's is with the project,
        // and every other page with its own name; the app menu last.
        val styles = state.album?.clockOptions?.types
        FitTopBar(
            title = stringResource(
                when (page) {
                    PhotosPage.Canvas -> R.string.editor_photos_title
                    PhotosPage.Background -> R.string.editor_page_background
                    PhotosPage.Styles -> R.string.editor_page_styles
                    PhotosPage.Install -> R.string.editor_page_install
                },
            ),
            subtitle = when {
                // Until the face is read its clock is unknown, which is not the same as none.
                page == PhotosPage.Canvas && state.clockSetting == null -> stringResource(
                    R.string.editor_photos_subtitle_reading, PHOTO_ALBUM_FACE_ID.toString().padStart(5, '0'))
                page == PhotosPage.Canvas -> stringResource(R.string.editor_subtitle_canvas,
                    PHOTO_ALBUM_FACE_ID.toString().padStart(5, '0'),
                    state.clock?.let { clock -> styleLabel(styles?.indexOfFirst { it.id == clock.type }?.coerceAtLeast(0) ?: 0) }
                        ?: stringResource(R.string.editor_photos_no_clock))
                page == PhotosPage.Install -> stringResource(R.string.editor_subtitle_install)
                page == PhotosPage.Styles -> if (styles == null) {
                    stringResource(R.string.editor_photos_subtitle_styles)
                } else {
                    pluralStringResource(R.plurals.editor_subtitle_styles, styles.size, styles.size)
                }
                state.entries.isEmpty() -> stringResource(R.string.editor_photos_subtitle_choose)
                else -> stringResource(R.string.editor_photos_subtitle_placing,
                    state.entries.indexOf(state.shownEntry) + 1, state.entries.size)
            },
            onBack = onBack,
        ) {
            AppMenuAction(
                onReportProblem = viewModel::showDiagnostics,
                onAbout = onAbout,
                onCheckForUpdate = onCheckForUpdate,
            )
        }
        PhotoAlbumPage(
            state = state,
            page = page,
            onPage = { page = it },
            onRetry = viewModel::load,
            onChoose = { picker.launch(arrayOf("image/*")) },
            onSelect = viewModel::select,
            onRemove = viewModel::remove,
            onTransform = viewModel::transform,
            onStepZoom = viewModel::stepZoom,
            onFit = viewModel::setFit,
            onResetPlacement = viewModel::resetPlacement,
            onTint = viewModel::setTint,
            clock = ClockActions(viewModel::chooseClockType, viewModel::chooseClockColour, viewModel::chooseNoClock),
            onSend = viewModel::send,
            onGrantNearby = {
                val permissions = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                        add(Manifest.permission.BLUETOOTH_SCAN)
                    }
                }
                nearby.launch(permissions.toTypedArray())
            },
            onOpenCompanion = viewModel::openCompanionApp,
            onDiscover = viewModel::discover,
            onOpenPluginSettings = viewModel::openPluginSettings,
            onConfirmPluginReleased = viewModel::confirmPluginChannelReleased,
            onRediscover = viewModel::rediscover,
            onResetDelivery = viewModel::resetDelivery,
            modifier = Modifier.weight(1f),
        )
        PhotosRail(page) { page = it }
        }
    }
}

/**
 * The pages of the Photos face: the editor's Canvas, Background, Styles and Install, and nothing
 * else. Canvas is the face with its one widget, the clock, and the clock's colour; Background
 * places the photos; Styles chooses where the clock sits.
 */
internal enum class PhotosPage { Canvas, Background, Styles, Install }

/** The editor's bottom bar, with the four destinations this page has, in the editor's order. */
@Composable
private fun PhotosRail(current: PhotosPage, onNavigate: (PhotosPage) -> Unit) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).fillMaxWidth().heightIn(min = PhotosRailHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RailItem("▣", stringResource(R.string.editor_page_canvas), current == PhotosPage.Canvas,
            { onNavigate(PhotosPage.Canvas) }, Modifier.weight(1f))
        RailItem("▧", stringResource(R.string.editor_page_background), current == PhotosPage.Background,
            { onNavigate(PhotosPage.Background) }, Modifier.weight(1f))
        RailItem("◫", stringResource(R.string.editor_page_styles), current == PhotosPage.Styles,
            { onNavigate(PhotosPage.Styles) }, Modifier.weight(1f))
        RailItem("⇧", stringResource(R.string.editor_page_install), current == PhotosPage.Install,
            { onNavigate(PhotosPage.Install) }, Modifier.weight(1f))
    }
}

@Composable
internal fun PhotoAlbumPage(
    state: PhotoAlbumUiState,
    page: PhotosPage,
    onPage: (PhotosPage) -> Unit,
    onRetry: () -> Unit,
    onChoose: () -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onFit: (ImageFit) -> Unit,
    onResetPlacement: () -> Unit,
    onTint: (Int?) -> Unit,
    clock: ClockActions,
    onSend: () -> Unit,
    onGrantNearby: () -> Unit,
    onOpenCompanion: () -> Unit,
    onDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
    onRediscover: () -> Unit,
    onResetDelivery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val album = state.album
    Column(modifier.fillMaxWidth()) {
        if (state.loading || album == null) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            if (state.downloadFraction in 0.01f..0.99f) {
                                stringResource(R.string.editor_photos_loading_percent, (state.downloadFraction * 100).roundToInt())
                            } else {
                                stringResource(R.string.editor_photos_loading)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                } else {
                    StatusBanner(FitStatus.Fail, state.loadFailure ?: "")
                    FitButton(stringResource(R.string.editor_photos_retry), onRetry, Modifier.fillMaxWidth())
                }
            }
            return@Column
        }
        // Placing photos and sending them are two pages, as the editor's Background and Install
        // are, and each keeps its own place: sharing one scroll state opened Install at the
        // bottom whenever Background had been scrolled to its Next button.
        val scrolls = PhotosPage.entries.associateWith { rememberScrollState() }
        when (page) {
            PhotosPage.Canvas -> PhotosCanvasTab(
                state = state,
                onColour = clock.onColour,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            // The face stays in view while its controls scroll, as on the editor's Background.
            PhotosPage.Background -> PhotosBackgroundTab(
                state, onChoose, onSelect, onRemove, onTransform, onStepZoom, onFit, onResetPlacement, onTint,
                scroll = scrolls.getValue(PhotosPage.Background),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            PhotosPage.Styles -> PhotosStylesTab(
                state = state,
                actions = clock,
                scroll = scrolls.getValue(PhotosPage.Styles),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            // Left-aligned, as the editor's Install is.
            PhotosPage.Install -> Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(scrolls.getValue(PhotosPage.Install)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PhotosInstallTab(
                    state = state,
                    onPlace = { key ->
                        onSelect(key)
                        onPage(PhotosPage.Background)
                    },
                    onSend = onSend,
                    onGrantNearby = onGrantNearby,
                    onOpenCompanion = onOpenCompanion,
                    onDiscover = onDiscover,
                    onOpenPluginSettings = onOpenPluginSettings,
                    onConfirmPluginReleased = onConfirmPluginReleased,
                    onRediscover = onRediscover,
                    onResetDelivery = onResetDelivery,
                )
            }
        }
    }
}

/**
 * Picking the photos and placing each one: the editor's Background page, in its order. The face
 * first, drawn as it will be sent; then Fit, the placement controls and the colour; then the
 * page's two actions, taking the photo out where the editor discards and moving on to Install
 * where it applies. The strip between the face and Fit is the one thing the editor has no need
 * of: several photos go at once, and it says which is being placed.
 */
@Composable
private fun PhotosBackgroundTab(
    state: PhotoAlbumUiState,
    onChoose: () -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onFit: (ImageFit) -> Unit,
    onResetPlacement: () -> Unit,
    onTint: (Int?) -> Unit,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val entry = state.shownEntry
    val photo = entry?.drawn(-1)
    val enabled = !state.busy && entry?.pending != null
    PinnedFacePage(
        aspect = ALBUM_PHOTO_WIDTH.toFloat() / ALBUM_PHOTO_HEIGHT,
        faceCap = 186.dp,
        modifier = modifier,
        scroll = scroll,
        face = { faceModifier ->
            PlacedPhoto(
                photo = photo,
                description = entry?.let {
                    stringResource(
                        if (it.pending != null) R.string.editor_photos_preview_a11y else R.string.editor_photos_preview_fixed_a11y,
                        state.entries.indexOf(it) + 1,
                    )
                } ?: stringResource(R.string.editor_photos_none_picked),
                enabled = enabled,
                onTransform = onTransform,
                clock = clockLook(state),
                modifier = faceModifier,
            )
        },
    ) {
        PhotosBackgroundControls(
            state, onChoose, onSelect, onRemove, onTransform, onStepZoom, onFit, onResetPlacement, onTint,
        )
    }
}

/**
 * Everything on the Background page under the face, which scrolls while the face stays: every
 * photo the face will have, the one being placed (which the face will show) marked; its
 * placement; then more photos, and this one out.
 */
@Composable
private fun ColumnScope.PhotosBackgroundControls(
    state: PhotoAlbumUiState,
    onChoose: () -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onFit: (ImageFit) -> Unit,
    onResetPlacement: () -> Unit,
    onTint: (Int?) -> Unit,
) {
    val entry = state.shownEntry
    val enabled = !state.busy
    if (entry == null) {
        ChooseButton(state, primary = true, onChoose)
        FitInfoLink(stringResource(R.string.editor_photos_details_label)) {
            InfoText(stringResource(R.string.editor_photos_intro, PHOTO_ALBUM_LIMIT))
        }
        return
    }
    PhotoStrip(state, onSelect)
    val photo = entry.pending
    if (photo != null) {
        FitControls(photo.placement.fit, enabled, onFit)
        PlacementControls(
            placement = photo.placement,
            edges = imageEdges(photo.preview.width, photo.preview.height, ALBUM_PHOTO_WIDTH, ALBUM_PHOTO_HEIGHT,
                photo.placement),
            entryKey = photo.id,
            panelWidth = ALBUM_PHOTO_WIDTH,
            panelHeight = ALBUM_PHOTO_HEIGHT,
            enabled = enabled,
            onTransformImage = onTransform,
            onStepZoom = onStepZoom,
            onResetPlacement = onResetPlacement,
        )
        ImageColourControls(photo.placement.tint, enabled = enabled, onTint = onTint, stateKey = photo.id)
    } else if (entry.sent?.changeable == false) {
        val fixed = stringResource(R.string.editor_photos_fixed)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(fixed, style = MaterialTheme.typography.bodyMedium)
            FitInfo(fixed, stringResource(R.string.editor_photos_fixed_info))
        }
    }
    // The editor's Background ends on its actions for the picture: here, more photos and this
    // one out. Sending is Install's, on the rail, as it is for any edit.
    ChooseButton(state, primary = false, onChoose)
    FitButton(
        stringResource(if (entry.isNew) R.string.editor_photos_remove_one else R.string.editor_photos_take_off),
        { onRemove(entry.key) }, Modifier.fillMaxWidth(), enabled, style = FitButtonStyle.Secondary,
    )
}

/**
 * Choose photos, or more of them, with the room the face has left on the button; with none
 * left it is disabled and its ⓘ says why.
 */
@Composable
private fun ChooseButton(state: PhotoAlbumUiState, primary: Boolean, onChoose: () -> Unit) {
    val room = state.room
    FitButton(
        text = when {
            room == 0 -> stringResource(if (primary) R.string.editor_photos_choose_full else R.string.editor_photos_add_full)
            else -> pluralStringResource(
                if (primary) R.plurals.editor_photos_choose_room else R.plurals.editor_photos_add_room, room, room,
            )
        },
        onClick = onChoose,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy && room > 0,
        style = if (primary) FitButtonStyle.Primary else FitButtonStyle.Secondary,
        info = if (room > 0) null else { { InfoText(stringResource(R.string.editor_photos_full)) } },
    )
}

/** What goes to the watch, what this phone already sent there, and the send itself. */
@Composable
private fun ColumnScope.PhotosInstallTab(
    state: PhotoAlbumUiState,
    onPlace: (String) -> Unit,
    onSend: () -> Unit,
    onGrantNearby: () -> Unit,
    onOpenCompanion: () -> Unit,
    onDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
    onRediscover: () -> Unit,
    onResetDelivery: () -> Unit,
) {
    if (state.album == null) return
    FitHeading(
        stringResource(R.string.editor_photos_ready_heading),
        trailing = {
            if (!state.canSend) {
                Text(stringResource(R.string.editor_photos_ready_none), style = FitFaceType.micro,
                    color = MaterialTheme.fitText.secondary)
            }
        },
    ) { InfoText(stringResource(R.string.editor_photos_ready_info)) }
    if (state.entries.isNotEmpty()) PhotoStrip(state, onPlace)
    ChangeSummary(state)
    ClockSummary(state)
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    val delivery = state.directInstall
    EnvironmentAdvisoryBanner(delivery)
    val stopped = if (delivery.setupComplete) null else delivery.failure
    stopped?.let {
        StatusBanner(FitStatus.Fail, it, label = stringResource(R.string.editor_install_stopped_label),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (delivery.setupComplete) {
        AlbumTransferPanel(state, onSend, onRediscover)
    } else {
        if (delivery.message != stopped) {
            Text(delivery.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        SetupChecklist(
            state = delivery,
            enabled = !state.busy,
            onOpenCompanion = onOpenCompanion,
            onGrantNearby = onGrantNearby,
            onDiscover = onDiscover,
            onOpenPluginSettings = onOpenPluginSettings,
            onConfirmPluginReleased = onConfirmPluginReleased,
        )
    }
    FitInfoLink(stringResource(R.string.editor_photos_about_label)) {
        InfoText(stringResource(R.string.editor_photos_about))
    }
    TextButton(onClick = onResetDelivery, enabled = !delivery.isActive, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.editor_install_restart), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * What the send changes on the watch, line by line: photos added, placed again and taken off,
 * and which one the face shows. Nothing when the album is as sent.
 */
@Composable
private fun ChangeSummary(state: PhotoAlbumUiState) {
    val lines = buildList {
        if (state.added > 0) add(pluralStringResource(R.plurals.editor_photos_change_added, state.added, state.added))
        if (state.changed > 0) add(pluralStringResource(R.plurals.editor_photos_change_changed, state.changed, state.changed))
        if (state.removed > 0) add(pluralStringResource(R.plurals.editor_photos_change_removed, state.removed, state.removed))
        if (state.showsOther) {
            val entry = state.shownEntry
            add(
                if (entry == null) stringResource(R.string.editor_photos_change_shown_own)
                else stringResource(R.string.editor_photos_change_shown, state.entries.indexOf(entry) + 1),
            )
        }
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/**
 * Every photo the face will have, in its order: the one being placed, which the face will show,
 * outlined and marked SHOWN; new ones marked NEW and ones placed again CHANGED. One sent before
 * copies were kept has no picture here.
 */
@Composable
private fun PhotoStrip(state: PhotoAlbumUiState, onSelect: (String) -> Unit) {
    val entries = state.entries
    val shownKey = state.shownEntry?.key
    val scroll = rememberScrollState()
    // Seven thumbnails are wider than a phone: keep the one being placed in view, moving the
    // strip only when it is not, so a tap never slides the thumbnail from under the finger.
    val shownIndex = entries.indexOfFirst { it.key == shownKey }
    val density = LocalDensity.current
    LaunchedEffect(shownIndex, scroll.viewportSize) {
        if (shownIndex < 0 || scroll.viewportSize == 0) return@LaunchedEffect
        val start = with(density) { ((StripThumbWidth + StripGap) * shownIndex).roundToPx() }
        val end = start + with(density) { StripThumbWidth.roundToPx() }
        when {
            start < scroll.value -> scroll.animateScrollTo(start)
            end > scroll.value + scroll.viewportSize -> scroll.animateScrollTo(end - scroll.viewportSize)
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(StripGap)) {
        entries.forEachIndexed { index, entry ->
            val isShown = entry.key == shownKey
            val status = stringResource(
                when {
                    entry.isNew -> R.string.editor_photos_status_new
                    entry.isChanged -> R.string.editor_photos_status_changed
                    entry.sent?.kept == false -> R.string.editor_photos_status_no_copy
                    else -> R.string.editor_photos_status_sent
                },
            )
            val description = stringResource(
                if (isShown) R.string.editor_photos_thumb_selected_a11y else R.string.editor_photos_thumb_a11y,
                index + 1, entries.size, status,
            )
            // In its colour, as the face above draws it.
            val photo = entry.drawn(-1L - index)
            val tint = photo?.placement?.tint
            val frame = remember(photo?.preview, tint) {
                photo?.preview?.let { preview -> tint?.let { preview.tintedTo(it) } ?: preview }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(width = StripThumbWidth, height = 85.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(
                            if (isShown) 2.dp else 1.dp,
                            if (isShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = description
                            selected = isShown
                        }
                        .clickable(enabled = !state.busy, role = Role.Button) { onSelect(entry.key) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (frame != null) {
                        Image(frame.rememberBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(stringResource(R.string.editor_photos_no_copy), style = FitFaceType.micro,
                            color = MaterialTheme.fitText.secondary)
                    }
                }
                Text(
                    when {
                        isShown -> stringResource(R.string.editor_photos_shown_label)
                        entry.isNew -> stringResource(R.string.editor_photos_new_label)
                        entry.isChanged -> stringResource(R.string.editor_photos_changing_label)
                        else -> "${index + 1}"
                    },
                    modifier = Modifier.padding(top = 4.dp),
                    style = FitFaceType.micro,
                    color = if (isShown || entry.isNew || entry.isChanged) MaterialTheme.colorScheme.primary
                    else MaterialTheme.fitText.secondary,
                )
            }
        }
    }
}

/**
 * The photo on the 256 × 402 face, placed and coloured as it will be sent; a pinch sizes it and
 * a drag moves it. With no photo it is the empty face, so the page keeps its shape.
 */
@Composable
internal fun PlacedPhoto(
    photo: PendingPhoto?,
    description: String,
    enabled: Boolean,
    onTransform: (Float, Float, Float) -> Unit,
    clock: ClockLook? = null,
    /** The clock's box in its picture's pixels, outlined as the editor outlines a selected widget. */
    outline: IntRect? = null,
    shape: Shape = RoundedCornerShape(FaceOutlineCorner),
    modifier: Modifier = Modifier,
) {
    val outlineColor = MaterialTheme.colorScheme.tertiary
    val clockBitmap = clock?.picture?.rememberBitmap()
    val tint = photo?.placement?.tint
    val frame = remember(photo?.preview, tint) {
        photo?.preview?.let { preview -> tint?.let { preview.tintedTo(it) } ?: preview }
    }
    val bitmap = frame?.rememberBitmap()
    val latestPlacement by rememberUpdatedState(photo?.placement)
    Canvas(
        modifier.fillMaxWidth()
            .aspectRatio(ALBUM_PHOTO_WIDTH.toFloat() / ALBUM_PHOTO_HEIGHT)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics { contentDescription = description }
            .pointerInput(photo?.id, enabled) {
                if (photo != null && enabled) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onTransform(zoom, pan.x / size.width.coerceAtLeast(1), pan.y / size.height.coerceAtLeast(1))
                    }
                }
            },
    ) {
        drawRect(Color.Black)
        val placement = latestPlacement
        if (bitmap != null && frame != null && placement != null) {
            val (offset, fitted) = fittedRect(frame.width, frame.height, size.width, size.height, placement)
            clipRect {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                    dstSize = IntSize(fitted.width.roundToInt().coerceAtLeast(1), fitted.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.High,
                )
            }
        }
        // The clock over the photo, as the watch draws it: the plugin's picture of the type, white
        // on clear at the face's proportions, in the chosen colour.
        if (clockBitmap != null && clock != null) {
            drawImage(
                image = clockBitmap,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                colorFilter = ColorFilter.tint(Color(clock.argb), BlendMode.SrcIn),
                filterQuality = FilterQuality.High,
            )
            if (outline != null) {
                val scaleX = size.width / clock.picture.width
                val scaleY = size.height / clock.picture.height
                drawRect(
                    color = outlineColor,
                    topLeft = Offset(outline.left * scaleX, outline.top * scaleY),
                    size = Size(outline.width * scaleX, outline.height * scaleY),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
    }
}

/** The send, phase by phase, with what can be done at each end of it. */
@Composable
private fun AlbumTransferPanel(state: PhotoAlbumUiState, onSend: () -> Unit, onRediscover: () -> Unit) {
    val delivery = state.directInstall
    val progress = delivery.album
    val removing = progress?.removing == true
    val selecting = progress?.showing == true
    val phases = buildList {
        add(DirectInstallPhase.INITIALIZING to stringResource(R.string.editor_photos_phase_prepare))
        add(DirectInstallPhase.DISCOVERING to stringResource(R.string.editor_photos_phase_find))
        add(DirectInstallPhase.VERIFYING to stringResource(R.string.editor_photos_phase_open))
        if (progress == null || progress.photos > 0) {
            add(DirectInstallPhase.TRANSFERRING to stringResource(R.string.editor_photos_phase_send))
        }
        add(DirectInstallPhase.INSTALLING to stringResource(
            when {
                removing -> R.string.editor_photos_phase_remove
                selecting && state.clockOnly -> R.string.editor_photos_phase_clock
                selecting -> R.string.editor_photos_phase_show
                else -> R.string.editor_photos_phase_add
            }))
        add(DirectInstallPhase.COMPLETE to stringResource(R.string.editor_photos_phase_done))
    }
    // A face install's timeline is not this page's; it shows its own once a request starts.
    val showing = progress != null
    val currentIndex = phases.indexOfFirst { it.first == delivery.phase }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showing) {
            Column {
                MicroLabel(stringResource(R.string.editor_transfer_phases))
                Column(Modifier.padding(top = 11.dp)) {
                    phases.forEachIndexed { index, (phase, label) ->
                        val active = delivery.phase == phase
                        PhaseRow(
                            label = label,
                            detail = if (phase == DirectInstallPhase.TRANSFERRING && active && progress != null) {
                                stringResource(
                                    R.string.editor_photos_phase_photo,
                                    (progress.photosSent + 1).coerceAtMost(progress.photos),
                                    progress.photos,
                                    (delivery.progress.coerceIn(0f, 1f) * 100).toInt(),
                                )
                            } else null,
                            done = currentIndex > index || delivery.phase == DirectInstallPhase.COMPLETE,
                            active = active,
                            last = index == phases.lastIndex,
                        )
                    }
                }
            }
        }
        if (delivery.isActive) {
            LinearProgressIndicator(progress = { delivery.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.editor_transfer_keep_open),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val sendLabel = stringResource(R.string.editor_photos_send)
        val canSend = !state.busy && state.canSend
        when {
            showing && delivery.phase == DirectInstallPhase.COMPLETE -> {
                StatusBanner(
                    FitStatus.Pass,
                    stringResource(
                        when {
                            removing -> R.string.editor_photos_removed
                            selecting -> R.string.editor_photos_shown_done
                            hasPluginNearbySwitch -> R.string.editor_photos_done
                            else -> R.string.editor_photos_done_legacy
                        },
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    label = stringResource(R.string.editor_transfer_done_label),
                )
                if (state.canSend) FitButton(sendLabel, onSend, Modifier.fillMaxWidth(), canSend)
            }
            delivery.phase == DirectInstallPhase.FAILED -> {
                StatusBanner(
                    FitStatus.Fail,
                    delivery.failure ?: delivery.message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.canSend) FitButton(stringResource(R.string.editor_transfer_try_again), onSend, Modifier.fillMaxWidth(), canSend)
                FitButton(
                    stringResource(R.string.editor_transfer_rediscover), onRediscover, Modifier.fillMaxWidth(),
                    !state.busy, style = FitButtonStyle.Secondary,
                )
            }
            delivery.isActive -> {
                Text(delivery.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.preparing -> FitButton(stringResource(R.string.editor_photos_preparing), {}, Modifier.fillMaxWidth(), enabled = false)
            else -> FitButton(sendLabel, onSend, Modifier.fillMaxWidth(),
                enabled = canSend && delivery.phase == DirectInstallPhase.READY || canSend && delivery.isTerminal)
        }
    }
}

private val PhotosRailHeight = 58.dp
private val StripThumbWidth = 54.dp
private val StripGap = 8.dp

/** What the face's clock can do, from [PhotoAlbumViewModel]. */
internal class ClockActions(
    val onType: (Int) -> Unit,
    val onColour: (Int) -> Unit,
    val onNone: () -> Unit,
)

/** The clock as the face above draws it: the type's picture, in the colour. */
internal class ClockLook(val picture: PreviewFrame, val argb: Int)

/** The clock the face will have, to draw over the photo, or null while it is unknown. */
internal fun clockLook(state: PhotoAlbumUiState): ClockLook? {
    val options = state.album?.clockOptions ?: return null
    val clock = state.clock ?: return null
    val picture = options.type(clock.type)?.picture ?: return null
    val colour = options.colour(clock.colour) ?: return null
    return ClockLook(picture, colour.argb)
}

/**
 * Where the Photos face's clock sits, laid out as the editor's Styles page is: a row per style,
 * the face drawn with it beside its name, the chosen one marked. The colour is the clock's own,
 * on Canvas, as a widget's is in the editor; each style is drawn in it. Both come from the stock
 * plugin on this phone, belong to the whole face whichever photo is shown, and go with every send.
 */
@Composable
private fun PhotosStylesTab(
    state: PhotoAlbumUiState,
    actions: ClockActions,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.busy
    Column(
        modifier.verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        val options = state.album?.clockOptions
        if (options == null) {
            val unavailable = stringResource(R.string.editor_photos_styles_unavailable)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(unavailable, style = MaterialTheme.typography.bodyMedium)
                FitInfo(unavailable, stringResource(R.string.editor_photos_clock_unavailable))
            }
        } else {
            val backdrop = stylesBackdrop(state)
            val clock = state.clock
            val changed = state.clockChoice != null
            val colour = clock?.let { options.colour(it.colour)?.argb } ?: android.graphics.Color.WHITE
            options.types.forEachIndexed { index, type ->
                ClockStyleRow(
                    backdrop = backdrop,
                    look = type.picture?.let { ClockLook(it, colour) },
                    label = styleLabel(index),
                    selected = type.id == clock?.type,
                    changed = changed,
                    enabled = enabled,
                    onClick = { actions.onType(type.id) },
                )
            }
            // Last, after the styles: the face with its clock left out, which the watch draws bare.
            ClockStyleRow(
                backdrop = backdrop,
                look = null,
                label = stringResource(R.string.editor_photos_no_clock),
                selected = state.clockSetting == PhotoClockSetting.None,
                changed = changed,
                enabled = enabled,
                onClick = actions.onNone,
            )
            FitInfoLink(stringResource(R.string.editor_photos_styles_details)) {
                InfoText(stringResource(R.string.editor_photos_style_info))
            }
        }
    }
}

/**
 * What the Styles page draws its faces over: the photo being placed, else the one the watch
 * was last asked to show, as sent, else nothing (the face's black).
 */
internal fun stylesBackdrop(state: PhotoAlbumUiState): PendingPhoto? = state.shownEntry?.drawn(-1)

/** One clock style, as a row of the editor's Styles page: the face with it, its name, ● when chosen. */
@Composable
private fun ClockStyleRow(
    backdrop: PendingPhoto?,
    look: ClockLook?,
    label: String,
    selected: Boolean,
    changed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .07f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.shapes.medium,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.medium,
            )
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        PlacedPhoto(
            photo = backdrop,
            description = stringResource(R.string.editor_style_preview_a11y, label),
            enabled = false,
            onTransform = { _, _, _ -> },
            clock = look,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.width(54.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (selected) {
                Text(
                    stringResource(if (changed) R.string.editor_photos_style_current_changed else R.string.editor_photos_style_current),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.fitText.secondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (selected) Text("●", color = MaterialTheme.colorScheme.primary)
    }
}

/** On Install, the clock that goes with the send, or that none does. */
@Composable
private fun ClockSummary(state: PhotoAlbumUiState) {
    val changed = state.clockChoice != null
    if (state.clockSetting == PhotoClockSetting.None) {
        Text(
            stringResource(if (changed) R.string.editor_photos_install_no_clock_changed else R.string.editor_photos_install_no_clock),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    // Every other send carries a clock, so there is one to name; its swatch needs the plugin's list.
    val clock = state.clock ?: return
    val argb = state.album?.clockOptions?.colour(clock.colour)?.argb
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (argb != null) {
            Box(Modifier.size(14.dp).background(Color(argb), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
        Text(
            stringResource(
                if (changed) R.string.editor_photos_install_clock_changed else R.string.editor_photos_install_clock,
                clock.type, clock.colour,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
