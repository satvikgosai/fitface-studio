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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import dev.fitface.studio.core.model.PHOTO_ALBUM_LIMIT
import dev.fitface.studio.core.ui.*
import dev.fitface.studio.core.ui.FitInfoLink
import dev.fitface.studio.core.ui.InfoText
import kotlin.math.roundToInt

/** The Photos face's page: pick, place, send, or remove what this phone sent. */
@Composable
fun PhotoAlbumRoute(
    onBack: () -> Unit,
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
    var page by rememberSaveable { mutableStateOf(PhotosPage.Background) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        FitTopBar(
            title = stringResource(R.string.editor_photos_title),
            subtitle = when {
                page == PhotosPage.Install -> stringResource(R.string.editor_photos_subtitle_install)
                state.photos.isEmpty() -> stringResource(R.string.editor_photos_subtitle_choose)
                state.selected?.replaces != null -> stringResource(R.string.editor_photos_subtitle_changing)
                else -> stringResource(R.string.editor_photos_subtitle_placing,
                    state.photos.indexOf(state.selected) + 1, state.photos.size)
            },
            onBack = onBack,
        )
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
            onSend = viewModel::send,
            sent = SentActions(
                onChoose = viewModel::chooseSent,
                onShow = viewModel::showSent,
                onChange = { name ->
                    viewModel.changeSent(name)
                    page = PhotosPage.Background
                },
                onRemove = viewModel::removeSentPhoto,
                onResend = viewModel::resendAll,
            ),
            onRemoveSent = viewModel::removeSent,
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

/** The pages of the Photos face: the editor's Background and Install, and nothing else. */
internal enum class PhotosPage { Background, Install }

/** The editor's bottom bar, with the two destinations this page has. */
@Composable
private fun PhotosRail(current: PhotosPage, onNavigate: (PhotosPage) -> Unit) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).fillMaxWidth().heightIn(min = 58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RailItem("▧", stringResource(R.string.editor_page_background), current == PhotosPage.Background,
            { onNavigate(PhotosPage.Background) }, Modifier.weight(1f))
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
    onSelect: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onFit: (ImageFit) -> Unit,
    onResetPlacement: () -> Unit,
    onTint: (Int?) -> Unit,
    onSend: () -> Unit,
    sent: SentActions,
    onRemoveSent: () -> Unit,
    onGrantNearby: () -> Unit,
    onOpenCompanion: () -> Unit,
    onDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
    onRediscover: () -> Unit,
    onResetDelivery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingRemoval by remember { mutableStateOf(false) }
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
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scrolls.getValue(page)).padding(16.dp),
            // Centred under the face on Background and left-aligned on Install, as the editor's are.
            horizontalAlignment = if (page == PhotosPage.Background) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (page) {
                PhotosPage.Background -> PhotosBackgroundTab(
                    state, onChoose, onSelect, onRemove, onTransform, onStepZoom, onFit, onResetPlacement, onTint,
                    onNext = { onPage(PhotosPage.Install) },
                )
                PhotosPage.Install -> PhotosInstallTab(
                    state = state,
                    onPlace = { id ->
                        onSelect(id)
                        onPage(PhotosPage.Background)
                    },
                    onSend = onSend,
                    onRemoveSent = { confirmingRemoval = true },
                    sent = sent,
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
    if (confirmingRemoval && album != null) {
        AlertDialog(
            onDismissRequest = { confirmingRemoval = false },
            title = { Text(stringResource(R.string.editor_photos_remove_title)) },
            text = {
                Text(
                    pluralStringResource(R.plurals.editor_photos_remove_body, album.sent.size, album.sent.size),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemoval = false
                    onRemoveSent()
                }) { Text(stringResource(R.string.editor_photos_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoval = false }) { Text(stringResource(R.string.editor_photos_cancel)) }
            },
        )
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
private fun ColumnScope.PhotosBackgroundTab(
    state: PhotoAlbumUiState,
    onChoose: () -> Unit,
    onSelect: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onFit: (ImageFit) -> Unit,
    onResetPlacement: () -> Unit,
    onTint: (Int?) -> Unit,
    onNext: () -> Unit,
) {
    val photo = state.selected
    val enabled = !state.busy
    PlacedPhoto(
        photo = photo,
        description = photo?.let {
            stringResource(R.string.editor_photos_preview_a11y, state.photos.indexOf(it) + 1)
        } ?: stringResource(R.string.editor_photos_none_picked),
        enabled = enabled,
        onTransform = onTransform,
        modifier = Modifier.widthIn(max = 186.dp),
    )
    if (photo == null) {
        Column(
            Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                .padding(horizontal = 18.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.editor_photos_none_picked), style = MaterialTheme.typography.titleMedium)
        }
        FitButton(stringResource(R.string.editor_photos_choose), onChoose, Modifier.fillMaxWidth(),
            enabled = enabled && state.room > 0)
        RoomLine(state)
        FitInfoLink(stringResource(R.string.editor_photos_details_label)) {
            InfoText(stringResource(R.string.editor_photos_intro, PHOTO_ALBUM_LIMIT))
        }
        return
    }
    PhotoStrip(state, onSelect)
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
    RoomLine(state)
    FitButton(stringResource(R.string.editor_photos_add_more), onChoose, Modifier.fillMaxWidth(),
        enabled = enabled && state.room > 0, style = FitButtonStyle.Secondary)
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        FitButton(stringResource(R.string.editor_photos_remove_one), { onRemove(photo.id) }, Modifier.weight(1f),
            enabled, style = FitButtonStyle.Secondary)
        FitButton(stringResource(R.string.editor_photos_next), onNext, Modifier.weight(2f), enabled)
    }
}

/** How many more photos the face has room for, or that it has none. */
@Composable
private fun RoomLine(state: PhotoAlbumUiState) {
    Text(
        if (state.room > 0) {
            pluralStringResource(R.plurals.editor_photos_room, state.room, state.room)
        } else {
            stringResource(R.string.editor_photos_full)
        },
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What goes to the watch, what this phone already sent there, and the send itself. */
@Composable
private fun ColumnScope.PhotosInstallTab(
    state: PhotoAlbumUiState,
    onPlace: (Long) -> Unit,
    onSend: () -> Unit,
    onRemoveSent: () -> Unit,
    sent: SentActions,
    onGrantNearby: () -> Unit,
    onOpenCompanion: () -> Unit,
    onDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
    onRediscover: () -> Unit,
    onResetDelivery: () -> Unit,
) {
    val album = state.album ?: return
    if (state.photos.isEmpty()) {
        Text(
            stringResource(R.string.editor_photos_install_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            pluralStringResource(R.plurals.editor_photos_install_ready, state.photos.size, state.photos.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        PhotoStrip(state, onPlace)
    }
    if (album.sent.isNotEmpty()) OnTheWatch(state, onRemoveSent, sent)
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    MicroLabel(stringResource(R.string.editor_photos_send_heading))
    val delivery = state.directInstall
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
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** What can be done with a photo already on the watch; see [PhotoAlbumViewModel]. */
internal class SentActions(
    val onChoose: (String) -> Unit,
    val onShow: (String) -> Unit,
    val onChange: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onResend: () -> Unit,
)

/**
 * The photos this phone has sent, as it keeps them: the one shown marked, each one choosable,
 * and what can be done with the chosen one. Showing, removing and sending again go to the watch,
 * so they wait for the setup below; changing one only brings it back to Background.
 */
@Composable
private fun OnTheWatch(state: PhotoAlbumUiState, onRemoveAll: () -> Unit, actions: SentActions) {
    val album = state.album ?: return
    val ready = !state.busy && state.directInstall.setupComplete
    var removingOne by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MicroLabel(stringResource(R.string.editor_photos_on_watch))
        Text(
            pluralStringResource(R.plurals.editor_photos_sent_count, album.sent.size, album.sent.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            album.sent.forEachIndexed { index, photo ->
                val chosen = photo.name == state.chosenSent
                val shown = photo.name == album.shown
                val description = stringResource(
                    if (shown) R.string.editor_photos_sent_shown_a11y else R.string.editor_photos_sent_a11y,
                    index + 1, album.sent.size,
                )
                val frame = photo.preview
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(width = 54.dp, height = 85.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .border(
                                if (chosen) 2.dp else 1.dp,
                                if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .semantics(mergeDescendants = true) {
                                contentDescription = description
                                selected = chosen
                            }
                            .clickable(role = Role.Button) { actions.onChoose(photo.name) },
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
                        if (shown) stringResource(R.string.editor_photos_shown_label) else "${index + 1}",
                        modifier = Modifier.padding(top = 4.dp),
                        style = FitFaceType.micro,
                        color = if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.fitText.secondary,
                    )
                }
            }
        }
        val chosen = album.sent.firstOrNull { it.name == state.chosenSent }
        if (chosen == null) {
            Text(stringResource(R.string.editor_photos_choose_sent), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // Two to a row: three wrapped their labels mid-word on a phone.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FitButton(stringResource(R.string.editor_photos_show_this), { actions.onShow(chosen.name) },
                    Modifier.weight(1f), ready && chosen.name != album.shown, style = FitButtonStyle.Secondary)
                FitButton(stringResource(R.string.editor_photos_change), { actions.onChange(chosen.name) },
                    Modifier.weight(1f), !state.busy && chosen.changeable, style = FitButtonStyle.Secondary)
            }
            FitButton(stringResource(R.string.editor_photos_remove_this), { removingOne = chosen.name },
                Modifier.fillMaxWidth(), ready, style = FitButtonStyle.Secondary)
            if (!chosen.changeable) {
                Text(stringResource(R.string.editor_photos_no_copy_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FitButton(stringResource(R.string.editor_photos_resend), actions.onResend, Modifier.fillMaxWidth(),
            ready && album.sent.any { it.kept }, style = FitButtonStyle.Secondary)
        FitButton(stringResource(R.string.editor_photos_remove_sent), onRemoveAll, Modifier.fillMaxWidth(),
            ready, style = FitButtonStyle.Secondary)
        if (!state.directInstall.setupComplete) {
            Text(stringResource(R.string.editor_photos_sent_needs_setup), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    removingOne?.let { name ->
        AlertDialog(
            onDismissRequest = { removingOne = null },
            title = { Text(stringResource(R.string.editor_photos_remove_one_title)) },
            text = { Text(stringResource(R.string.editor_photos_remove_one_body), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    removingOne = null
                    actions.onRemove(name)
                }) { Text(stringResource(R.string.editor_photos_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { removingOne = null }) { Text(stringResource(R.string.editor_photos_cancel)) }
            },
        )
    }
}

/** The picked photos in send order, the first new one marked as the one the face shows. */
@Composable
private fun PhotoStrip(state: PhotoAlbumUiState, onSelect: (Long) -> Unit) {
    val selected = state.selected?.id
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.photos.forEachIndexed { index, photo ->
            val isSelected = photo.id == selected
            val description = stringResource(
                if (isSelected) R.string.editor_photos_thumb_selected_a11y else R.string.editor_photos_thumb_a11y,
                index + 1, state.photos.size,
            )
            // In its colour, as the face above draws it.
            val tint = photo.placement.tint
            val frame = remember(photo.preview, tint) { tint?.let { photo.preview.tintedTo(it) } ?: photo.preview }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = frame.rememberBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 54.dp, height = 85.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .clickable(role = Role.Button) { onSelect(photo.id) },
                )
                val shown = photo.id == state.shownPick
                Text(
                    when {
                        photo.replaces != null -> stringResource(R.string.editor_photos_changing_label)
                        shown -> stringResource(R.string.editor_photos_shown_label)
                        else -> "${index + 1}"
                    },
                    modifier = Modifier.padding(top = 4.dp),
                    style = FitFaceType.micro,
                    color = if (shown || photo.replaces != null) MaterialTheme.colorScheme.primary
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
private fun PlacedPhoto(
    photo: PendingPhoto?,
    description: String,
    enabled: Boolean,
    onTransform: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = photo?.placement?.tint
    val frame = remember(photo?.preview, tint) {
        photo?.preview?.let { preview -> tint?.let { preview.tintedTo(it) } ?: preview }
    }
    val bitmap = frame?.rememberBitmap()
    val latestPlacement by rememberUpdatedState(photo?.placement)
    Canvas(
        modifier.fillMaxWidth()
            .aspectRatio(ALBUM_PHOTO_WIDTH.toFloat() / ALBUM_PHOTO_HEIGHT)
            .clip(RoundedCornerShape(FaceOutlineCorner))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(FaceOutlineCorner))
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
        val sendLabel = pluralStringResource(R.plurals.editor_photos_send, state.photos.size, state.photos.size)
        val canSend = !state.busy && state.photos.isNotEmpty()
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
                if (state.photos.isNotEmpty()) FitButton(sendLabel, onSend, Modifier.fillMaxWidth(), canSend)
            }
            delivery.phase == DirectInstallPhase.FAILED -> {
                StatusBanner(
                    FitStatus.Fail,
                    delivery.failure ?: delivery.message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.photos.isNotEmpty()) FitButton(stringResource(R.string.editor_transfer_try_again), onSend, Modifier.fillMaxWidth(), canSend)
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
