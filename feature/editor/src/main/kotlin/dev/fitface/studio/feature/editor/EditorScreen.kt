package dev.fitface.studio.feature.editor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.fitface.studio.core.delivery.DirectInstallPhase
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.delivery.EnvironmentAdvisory
import dev.fitface.studio.core.delivery.SetupStep
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.RemovedWidget
import dev.fitface.studio.core.model.ReplacementImage
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.displayCoordinate
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.mebibytes
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.encodeCoordinate
import dev.fitface.studio.core.ui.DiagnosticsDialog
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitBadge
import dev.fitface.studio.core.ui.FitChip
import dev.fitface.studio.core.ui.AppMenuAction
import dev.fitface.studio.core.ui.FitFaceType
import dev.fitface.studio.core.ui.FitIconButton
import dev.fitface.studio.core.ui.FitStatus
import dev.fitface.studio.core.ui.FitTopBar
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.StatusBanner
import dev.fitface.studio.core.ui.fitColors
import dev.fitface.studio.core.ui.fitText
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun EditorRoute(
    projectId: Long,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onCheckForUpdate: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.prepareBackground(uri.toString())
    }
    val nearbyPermissionRequester = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.refreshDirectInstallEnvironment()
        if (result.values.any { granted -> !granted }) {
            val permanentlyDenied = result.keys.any { permission ->
                val activity = context as? Activity
                activity != null && !activity.shouldShowRequestPermissionRationale(permission)
            }
            if (permanentlyDenied) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
    }

    LaunchedEffect(projectId) { viewModel.loadProject(projectId) }
    // The message is cleared *after* it has been shown, not before. Clearing first
    // changed this effect's key while `showSnackbar` was still suspended, which
    // cancelled it — so every editor failure appeared for one frame and vanished, and
    // a refused edit looked like a button that flickered and did nothing.
    val message = state.error
    LaunchedEffect(message?.id) {
        if (message == null) return@LaunchedEffect
        try {
            snackbar.showSnackbar(message.text)
        } finally {
            viewModel.clearError(message.id)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.refreshDirectInstallEnvironment()
    }

    state.diagnosticsReport?.let { report ->
        DiagnosticsDialog(report = report, onDismiss = viewModel::dismissDiagnostics)
    }

    EditorScreen(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onReportProblem = viewModel::showDiagnostics,
        onAbout = onAbout,
        onCheckForUpdate = onCheckForUpdate,
        onStyle = viewModel::selectStyle,
        onWidget = viewModel::selectWidget,
        onMoveWidget = viewModel::moveWidget,
        onNudgeWidget = viewModel::nudgeWidget,
        onRemoveWidget = viewModel::removeSelectedWidget,
        onDuplicateWidget = viewModel::duplicateSelectedWidget,
        onRestoreWidget = viewModel::restoreWidget,
        onResizeWidget = viewModel::resizeSelectedWidget,
        onWidgetColor = viewModel::setSelectedWidgetColor,
        onSyncThumbnail = viewModel::refreshThumbnail,
        onTintCyan = viewModel::tintCyan,
        onTintMagenta = viewModel::tintMagenta,
        onApplyWidgetEditsToAllStyles = viewModel::setApplyWidgetEditsToAllStyles,
        onPreviewReviewed = viewModel::markPreviewReviewed,
        onFit = viewModel::selectFit,
        onChooseImage = { imagePicker.launch(arrayOf("image/*")) },
        onTransformImage = viewModel::transformImage,
        onStepImageZoom = viewModel::stepImageZoom,
        onResetImagePlacement = viewModel::resetImagePlacement,
        onDiscardImage = viewModel::cancelBackground,
        onApplyImage = viewModel::applyBackground,
        onReset = viewModel::reset,
        onGrantNearby = {
            val permissions = buildList {
                if (Build.VERSION.SDK_INT >= 31) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
            nearbyPermissionRequester.launch(permissions.toTypedArray())
        },
        onOpenCompanion = viewModel::openCompanionApp,
        onInitializeAndDiscover = viewModel::initializeDirectInstall,
        onOpenPluginSettings = viewModel::openPluginSettings,
        onConfirmPluginReleased = viewModel::confirmPluginChannelReleased,
        onRediscover = viewModel::restartDirectInstallDiscovery,
        onSendBin = viewModel::installCurrentBin,
        onResetDelivery = viewModel::resetDirectInstall,
    )
}

private enum class EditorPage {
    Canvas,
    Widgets,
    Inspector,
    Background,
    Styles,
    Validate,
    Install,
    Project,
    ;

    /**
     * Where "back" goes. A plain history stack made back retrace every hop the user
     * had taken to get here (Widgets → Inspector → Styles → Inspector → …), which is
     * confusing in a tool with a fixed rail. Each page has exactly one parent.
     */
    val parent: EditorPage?
        get() = when (this) {
            Canvas -> null
            Inspector -> Widgets
            Install -> Validate
            else -> Canvas
        }

    /**
     * Whether the canvas the wide layout keeps beside this page is an editor or a picture.
     *
     * Outlined widgets are a promise you can drag them — the Canvas page's own hint says
     * so in words — and the Install page was making that promise on the one page whose job
     * is to send what has already been decided. Nothing was ever at risk there: the pane
     * is frozen while a transfer is active, and a commit re-arms a finished one through
     * `payloadChanged()` rather than corrupting it. The affordance contradicting the page
     * is the bug. Install therefore gets the same read-only render the Validate page uses.
     */
    val canvasIsEditable: Boolean
        get() = this != Install
}

@Composable
private fun EditorScreen(
    state: EditorUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onReportProblem: () -> Unit,
    onAbout: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onStyle: (String) -> Unit,
    onWidget: (Int?) -> Unit,
    onMoveWidget: (Int, Int, Int) -> Unit,
    onNudgeWidget: (Int, Int, Int) -> Unit,
    onRemoveWidget: () -> Unit,
    onDuplicateWidget: () -> Unit,
    onRestoreWidget: (Long) -> Unit,
    onResizeWidget: (Boolean) -> Unit,
    onWidgetColor: (Int) -> Unit,
    onSyncThumbnail: () -> Unit,
    onTintCyan: () -> Unit,
    onTintMagenta: () -> Unit,
    onApplyWidgetEditsToAllStyles: (Boolean) -> Unit,
    onPreviewReviewed: () -> Unit,
    onFit: (ImageFit) -> Unit,
    onChooseImage: () -> Unit,
    onTransformImage: (Float, Float, Float) -> Unit,
    onStepImageZoom: (Boolean) -> Unit,
    onResetImagePlacement: () -> Unit,
    onDiscardImage: () -> Unit,
    onApplyImage: () -> Unit,
    onReset: () -> Unit,
    onGrantNearby: () -> Unit,
    onOpenCompanion: () -> Unit,
    onInitializeAndDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
    onRediscover: () -> Unit,
    onSendBin: () -> Unit,
    onResetDelivery: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(EditorPage.Canvas) }

    val snapshot = state.snapshot
    val selected = snapshot?.widgets?.singleOrNull {
        it.globalIndex == state.selectedWidgetIndex
    }
    val navigate: (EditorPage) -> Unit = { target ->
        val gatedTarget = if (
            target == EditorPage.Install &&
            (snapshot == null || snapshot.validationErrors.isNotEmpty() || !state.previewReviewed)
        ) {
            EditorPage.Validate
        } else {
            target
        }
        if (gatedTarget != page) {
            page = gatedTarget
            if (gatedTarget == EditorPage.Validate) onPreviewReviewed()
        }
    }
    val goBack: () -> Unit = {
        val parent = page.parent
        if (parent == null) onBack() else page = parent
    }

    BackHandler(enabled = page != EditorPage.Canvas) { goBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (snapshot == null) {
            EditorUnavailable(
                loading = state.isWorking,
                onBack = onBack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 700.dp
            Column(Modifier.fillMaxSize()) {
                EditorHeader(
                    page = page,
                    snapshot = snapshot,
                    pendingImage = state.pendingImage != null,
                    selected = selected,
                    onBack = goBack,
                    onProject = { navigate(EditorPage.Project) },
                    onReportProblem = onReportProblem,
                    onAbout = onAbout,
                    onCheckForUpdate = onCheckForUpdate,
                )
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                val content: @Composable (Modifier) -> Unit = { contentModifier ->
                    EditorPageContent(
                        page = page,
                        state = state,
                        snapshot = snapshot,
                        selected = selected,
                        onNavigate = navigate,
                        onStyle = onStyle,
                        onWidget = onWidget,
                        onMoveWidget = onMoveWidget,
                        onNudgeWidget = onNudgeWidget,
                        onRemoveWidget = onRemoveWidget,
                        onDuplicateWidget = onDuplicateWidget,
                        onRestoreWidget = onRestoreWidget,
                        onResizeWidget = onResizeWidget,
                        onWidgetColor = onWidgetColor,
                        onSyncThumbnail = onSyncThumbnail,
                        onTintCyan = onTintCyan,
                        onTintMagenta = onTintMagenta,
                        onApplyAll = onApplyWidgetEditsToAllStyles,
                        onPreviewReviewed = onPreviewReviewed,
                        onFit = onFit,
                        onChooseImage = onChooseImage,
                        onTransformImage = onTransformImage,
                        onStepImageZoom = onStepImageZoom,
                        onResetImagePlacement = onResetImagePlacement,
                        onDiscardImage = onDiscardImage,
                        onApplyImage = onApplyImage,
                        onReset = onReset,
                        onGrantNearby = onGrantNearby,
                        onOpenCompanion = onOpenCompanion,
                        onInitializeAndDiscover = onInitializeAndDiscover,
                        onOpenPluginSettings = onOpenPluginSettings,
                        onConfirmPluginReleased = onConfirmPluginReleased,
                        onRediscover = onRediscover,
                        onSendBin = onSendBin,
                        onResetDelivery = onResetDelivery,
                        modifier = contentModifier,
                    )
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        EditorRail(current = page, vertical = true, onNavigate = navigate)
                        if (page != EditorPage.Canvas) {
                            CanvasSidePane(
                                state = state,
                                snapshot = snapshot,
                                selected = selected,
                                editable = page.canvasIsEditable,
                                onWidget = onWidget,
                                onMoveWidget = onMoveWidget,
                                onTransformImage = onTransformImage,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier.fillMaxHeight().width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                        content(Modifier.weight(1f))
                    }
                } else {
                    content(Modifier.weight(1f))
                    if (page != EditorPage.Project && page != EditorPage.Inspector) {
                        EditorRail(current = page, vertical = false, onNavigate = navigate)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorUnavailable(
    loading: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FitTopBar(
            title = stringResource(
                if (loading) R.string.editor_loading_title else R.string.editor_title,
            ),
            onBack = onBack,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                // The placeholder is a fixed 176x276dp, which is taller than a landscape
                // phone leaves under the top bar — it pushed the spinner and "Opening…"
                // off the bottom edge, so the screen read as a blank grey slab. Taking
                // the height that is left and deriving the width from it keeps all three
                // on screen; portrait still gets the full 176dp.
                Box(
                    Modifier.weight(1f, fill = false).widthIn(max = 176.dp)
                        .aspectRatio(256f / 402f, matchHeightConstraintsFirst = true)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(32.dp),
                        ),
                )
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 20.dp).size(22.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    stringResource(R.string.editor_loading_detail),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.editor_session_ended_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.editor_session_ended_detail),
                    modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FitButton(
                    stringResource(R.string.editor_reopen),
                    onBack,
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EditorHeader(
    page: EditorPage,
    snapshot: EditorSnapshot,
    pendingImage: Boolean,
    selected: WidgetGuide?,
    onBack: () -> Unit,
    onProject: () -> Unit,
    onReportProblem: () -> Unit,
    onAbout: () -> Unit,
    onCheckForUpdate: () -> Unit,
) {
    val title = when (page) {
        EditorPage.Canvas -> snapshot.faceName
            ?: stringResource(R.string.editor_page_face_fallback, snapshot.faceId)
        EditorPage.Widgets -> stringResource(R.string.editor_page_widgets)
        EditorPage.Inspector -> stringResource(
            R.string.editor_page_widget_number,
            selected?.globalIndex?.toString()
                ?: stringResource(R.string.editor_widget_index_unknown),
        )
        EditorPage.Background -> stringResource(R.string.editor_page_background)
        EditorPage.Styles -> stringResource(R.string.editor_page_styles)
        EditorPage.Validate -> stringResource(R.string.editor_page_validate)
        EditorPage.Install -> stringResource(R.string.editor_page_install)
        EditorPage.Project -> stringResource(R.string.editor_page_project)
    }
    val subtitle = when (page) {
        EditorPage.Canvas -> stringResource(
            R.string.editor_subtitle_canvas,
            snapshot.faceId,
            snapshot.selectedStyle.removeSuffix(".bin"),
        )
        EditorPage.Widgets -> stringResource(
            R.string.editor_subtitle_widgets,
            snapshot.canvasWidgets.size,
            snapshot.widgets.size,
        )
        EditorPage.Inspector -> selected?.let {
            stringResource(R.string.editor_subtitle_inspector, it.type, it.sequenceId)
        }
        EditorPage.Background -> when {
            pendingImage -> stringResource(R.string.editor_subtitle_background_positioning)
            snapshot.canAddBackground ->
                stringResource(R.string.editor_subtitle_background_can_add)
            snapshot.backgroundWouldNotFit -> stringResource(
                R.string.editor_subtitle_background_no_room,
                mebibytes(snapshot.containerBytes),
            )
            !snapshot.canReplaceBackground ->
                stringResource(R.string.editor_subtitle_background_none)
            else -> stringResource(R.string.editor_subtitle_background_idle)
        }
        EditorPage.Styles ->
            stringResource(R.string.editor_subtitle_styles, snapshot.styleNames.size)
        EditorPage.Validate -> stringResource(R.string.editor_subtitle_validate)
        EditorPage.Install -> stringResource(R.string.editor_subtitle_install)
        EditorPage.Project -> snapshot.sourceName
    }
    // Order matters. The app menu is on every page while the badge and the overflow are
    // Canvas-only, so it goes last: as the final child of the Row its right edge is pinned to
    // the bar's padding and it stays put as you move between pages. Emitted first, it slid
    // sideways whenever a neighbour appeared or vanished.
    //
    // Two glyphs sit side by side on Canvas and they are deliberately different shapes: `⋯`
    // navigates to this face's Project page, `≡` opens the app-wide menu. Two ellipses would
    // read as one control with two behaviours.
    FitTopBar(title = title, subtitle = subtitle, onBack = onBack) {
        // One badge slot, whichever page has something outstanding to report. The bar is the
        // only part of a page that does not scroll, which is why an unapplied background says
        // so here: its commit buttons are the last children of a long scrolling column.
        if (page == EditorPage.Canvas && snapshot.isDirty) {
            FitBadge(
                stringResource(R.string.editor_badge_edited),
                MaterialTheme.fitColors.warning,
            )
        } else if (page == EditorPage.Background && pendingImage) {
            FitBadge(
                stringResource(R.string.editor_badge_unapplied),
                MaterialTheme.fitColors.warning,
            )
        }
        if (page == EditorPage.Canvas) {
            FitIconButton(
                glyph = "⋯",
                contentDescription = stringResource(R.string.editor_project_a11y),
                onClick = onProject,
            )
        }
        AppMenuAction(
            onReportProblem = onReportProblem,
            onAbout = onAbout,
            onCheckForUpdate = onCheckForUpdate,
        )
    }
}

@Composable
private fun EditorRail(
    current: EditorPage,
    vertical: Boolean,
    onNavigate: (EditorPage) -> Unit,
) {
    // The labels are the page titles, so the rail and the header cannot drift apart.
    val destinations = listOf(
        Triple("▦", stringResource(R.string.editor_page_widgets), EditorPage.Widgets),
        Triple("▧", stringResource(R.string.editor_page_background), EditorPage.Background),
        Triple("◫", stringResource(R.string.editor_page_styles), EditorPage.Styles),
        Triple("✓", stringResource(R.string.editor_page_validate), EditorPage.Validate),
        Triple("⇧", stringResource(R.string.editor_page_install), EditorPage.Install),
    )
    val container = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)
    if (vertical) {
        Column(
            modifier = container.fillMaxHeight().width(96.dp).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            destinations.forEach { (glyph, label, page) ->
                RailItem(glyph, label, current == page, { onNavigate(page) }, Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(
            modifier = container.fillMaxWidth().heightIn(min = 58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { (glyph, label, page) ->
                RailItem(glyph, label, current == page, { onNavigate(page) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RailItem(
    glyph: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.fitText.secondary
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(glyph, color = color, style = FitFaceType.numeric)
        Text(label.uppercase(), color = color, style = FitFaceType.micro, maxLines = 1)
    }
}

@Composable
private fun EditorPageContent(
    page: EditorPage,
    state: EditorUiState,
    snapshot: EditorSnapshot,
    selected: WidgetGuide?,
    onNavigate: (EditorPage) -> Unit,
    onStyle: (String) -> Unit,
    onWidget: (Int?) -> Unit,
    onMoveWidget: (Int, Int, Int) -> Unit,
    onNudgeWidget: (Int, Int, Int) -> Unit,
    onRemoveWidget: () -> Unit,
    onDuplicateWidget: () -> Unit,
    onRestoreWidget: (Long) -> Unit,
    onResizeWidget: (Boolean) -> Unit,
    onWidgetColor: (Int) -> Unit,
    onSyncThumbnail: () -> Unit,
    onTintCyan: () -> Unit,
    onTintMagenta: () -> Unit,
    onApplyAll: (Boolean) -> Unit,
    onPreviewReviewed: () -> Unit,
    onFit: (ImageFit) -> Unit,
    onChooseImage: () -> Unit,
    onTransformImage: (Float, Float, Float) -> Unit,
    onStepImageZoom: (Boolean) -> Unit,
    onResetImagePlacement: () -> Unit,
    onDiscardImage: () -> Unit,
    onApplyImage: () -> Unit,
    onReset: () -> Unit,
    onGrantNearby: () -> Unit,
    onOpenCompanion: () -> Unit,
    onInitializeAndDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
    onRediscover: () -> Unit,
    onSendBin: () -> Unit,
    onResetDelivery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (page) {
        EditorPage.Canvas -> CanvasWorkspace(
            state, snapshot, selected, onWidget, onMoveWidget, onNudgeWidget, onTransformImage,
            { onNavigate(EditorPage.Widgets) }, { onNavigate(EditorPage.Inspector) },
            modifier,
        )
        EditorPage.Widgets -> WidgetsWorkspace(
            snapshot = snapshot,
            selected = selected,
            enabled = !state.isWorking,
            onSelect = { onWidget(it.globalIndex); onNavigate(EditorPage.Inspector) },
            onRestore = onRestoreWidget,
            modifier = modifier,
        )
        EditorPage.Inspector -> InspectorWorkspace(
            state, snapshot, selected, onNudgeWidget, onApplyAll, onRemoveWidget,
            onDuplicateWidget, onResizeWidget, onWidgetColor, modifier,
        )
        EditorPage.Background -> BackgroundWorkspace(
            state, snapshot, onWidget, onMoveWidget, onTransformImage, onStepImageZoom, onFit,
            onChooseImage, onResetImagePlacement, onDiscardImage, onApplyImage, onTintCyan,
            onTintMagenta, modifier,
        )
        EditorPage.Styles -> StylesWorkspace(snapshot, !state.isWorking, onStyle, modifier)
        EditorPage.Validate -> ValidateWorkspace(
            snapshot = snapshot,
            state = state,
            onReviewed = onPreviewReviewed,
            onInstall = { onNavigate(EditorPage.Install) },
            onSyncThumbnail = onSyncThumbnail,
            onReset = onReset,
            modifier = modifier,
        )
        EditorPage.Install -> InstallWorkspace(
            state, snapshot, onGrantNearby, onOpenCompanion, onInitializeAndDiscover,
            onOpenPluginSettings, onConfirmPluginReleased, onRediscover, onSendBin,
            onResetDelivery, { onNavigate(EditorPage.Canvas) }, modifier,
        )
        EditorPage.Project -> ProjectWorkspace(snapshot, onReset, modifier)
    }
}

/**
 * The canvas is the editable layout and nothing else.
 *
 * It used to carry a second, read-only "validated preview" mode, which showed the
 * same thing the Validate page already shows — so the toggle only ever traded away
 * the ability to select a widget. With the mode gone, the "edit canvas" chip has
 * nothing left to switch to either.
 */
@Composable
private fun CanvasWorkspace(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    selected: WidgetGuide?,
    onWidget: (Int?) -> Unit,
    onMoveWidget: (Int, Int, Int) -> Unit,
    onNudgeWidget: (Int, Int, Int) -> Unit,
    onTransformImage: (Float, Float, Float) -> Unit,
    onOpenWidgets: () -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Stacked, the face is only as tall as the hint and the selection panel leave it, and
    // a landscape phone leaves all three about 340dp: the face came out a third of its
    // portrait size, and tapping a widget — which is what adds the panel — shrank it to a
    // dot with the hint clipped behind the panel's top edge. Under the stacked floor the
    // page splits into two columns instead, where the face keeps the full height and the
    // controls take width a short window has to spare. Measured here rather than read off
    // `LocalConfiguration`, because the header and the rail have already taken their cut.
    BoxWithConstraints(modifier) {
        val face: @Composable (Modifier) -> Unit = { faceModifier ->
            CanvasFace(
                state, snapshot, selected, onWidget, onMoveWidget, onTransformImage,
                faceModifier,
            )
        }
        if (canvasPageSplits(maxWidth, maxHeight)) {
            Row(Modifier.fillMaxSize()) {
                face(Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CanvasHint(state, selected, onOpenWidgets)
                    selected?.let { SelectionPeek(it, snapshot, onNudgeWidget, onInspect) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                face(Modifier.weight(1f).fillMaxWidth())
                CanvasHint(state, selected, onOpenWidgets)
                selected?.let { SelectionPeek(it, snapshot, onNudgeWidget, onInspect) }
            }
        }
    }
}

/**
 * The face, fitted to whatever box it is handed.
 *
 * Sized against the height that is actually left, not just the width: the selection
 * panel appears the moment a widget is tapped, and a canvas that only honoured
 * `fillMaxWidth().aspectRatio(…)` was clipped by exactly the height that panel took.
 */
@Composable
private fun CanvasFace(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    selected: WidgetGuide?,
    onWidget: (Int?) -> Unit,
    onMoveWidget: (Int, Int, Int) -> Unit,
    onTransformImage: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        DirectWatchCanvas(
            snapshot = snapshot,
            editing = true,
            selectedGlobalIndex = selected?.globalIndex,
            pendingWidgetMove = state.pendingWidgetMove,
            pendingImage = state.pendingImage,
            placement = state.placement,
            enabled = !state.isWorking && !state.directInstall.isActive,
            onWidget = onWidget,
            onMoveWidget = onMoveWidget,
            onTransformImage = onTransformImage,
            modifier = Modifier.width(fittedCanvasWidth(snapshot, CanvasMaxWidth)),
        )
    }
}

@Composable
private fun CanvasHint(
    state: EditorUiState,
    selected: WidgetGuide?,
    onOpenWidgets: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(
                when {
                    selected == null -> R.string.editor_canvas_hint_none
                    state.applyWidgetEditsToAllStyles -> R.string.editor_canvas_hint_all_styles
                    else -> R.string.editor_canvas_hint_this_style
                },
            ),
            modifier = Modifier.widthIn(max = 300.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected == null) {
            TextButton(onClick = onOpenWidgets) {
                Text(
                    stringResource(R.string.editor_canvas_pick_from_list),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Whether the canvas page puts its face and its controls side by side.
 *
 * A pure function of the box the page was handed so it can be pinned by a test:
 * `:feature:editor` cannot run Robolectric, so nothing here can measure a composable.
 */
internal fun canvasPageSplits(maxWidth: Dp, maxHeight: Dp): Boolean =
    maxWidth >= CanvasSideBySideMinWidth && maxHeight < CanvasStackedMinHeight

/**
 * The height the canvas page needs before it will stack its two halves.
 *
 * The stacked column carries the face, the hint and — once a widget is selected — the
 * nudge panel, which together want more than a landscape phone's ~340dp of content.
 */
private val CanvasStackedMinHeight = 480.dp

/** And it only splits when there is width to split, which a portrait phone never has. */
private val CanvasSideBySideMinWidth = 560.dp

/**
 * The largest panel-aspect width that fits inside both constraints, never above [cap].
 *
 * `fillMaxWidth().aspectRatio(…)` derives the height from the width and then overflows
 * whatever it was given, which clips rather than shrinks — so anything sharing a column
 * with the canvas eats into the face instead of the margin.
 */
private fun BoxWithConstraintsScope.fittedCanvasWidth(snapshot: EditorSnapshot, cap: Dp): Dp =
    minOf(
        maxWidth,
        maxHeight * (snapshot.preview.width.toFloat() / snapshot.preview.height),
        cap,
    )

/**
 * The face was capped well below the width of a modern phone, which left it floating in
 * empty margins. It can be given the room now that the height is honoured too: the cap
 * only decides how large it may get, and the fit decides how large it does get.
 */
private val CanvasMaxWidth = 288.dp

/** The same, for the persistent canvas beside every page in the wide layout. */
private val SidePaneCanvasMaxWidth = 260.dp

@Composable
private fun SelectionPeek(
    widget: WidgetGuide,
    snapshot: EditorSnapshot,
    onNudgeWidget: (Int, Int, Int) -> Unit,
    onInspect: () -> Unit,
) {
    // The rectangle the canvas outlines, not the stored endpoint. Reading
    // `displayCoordinate(widget.x, …)` here reported a far-end Badge's far endpoint — a
    // whole width away from the left edge the user is looking at while they nudge.
    val displayX = widget.drawLeft(snapshot.preview.width)
    val displayY = widget.drawTop(snapshot.preview.height)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.editor_selection_widget, widget.globalIndex),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        R.string.editor_selection_geometry,
                        displayX,
                        displayY,
                        widget.width,
                        widget.height,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = FitFaceType.numeric,
                )
            }
            FitButton(
                stringResource(R.string.editor_inspect),
                onInspect,
                style = FitButtonStyle.Secondary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            NudgeDirection.entries.forEach { direction ->
                RepeatingNudgeButton(
                    label = direction.glyph,
                    enabled = widget.canEditPosition,
                    modifier = Modifier.weight(1f),
                    onStep = {
                        onNudgeWidget(widget.globalIndex, direction.deltaX, direction.deltaY)
                    },
                )
            }
        }
        MicroLabel(
            stringResource(
                if (widget.canEditPosition) {
                    R.string.editor_nudge_hint
                } else {
                    R.string.editor_nudge_locked
                },
            ),
            Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
    }
}

private enum class NudgeDirection(val glyph: String, val deltaX: Int, val deltaY: Int) {
    LEFT("←", -1, 0),
    UP("↑", 0, -1),
    DOWN("↓", 0, 1),
    RIGHT("→", 1, 0),
}

/**
 * Fires once on press and then keeps firing while the finger is down, so nudging a
 * widget across the face is one long press instead of forty taps.
 *
 * The repeat is driven from the press interaction rather than a click, because a
 * click only arrives on release. That alone dropped the shortest taps: the effect is
 * launched by the recomposition the press causes and runs a frame later, so a tap that
 * is over within that frame was cancelled before its first step ever ran, and a control
 * whose own label promises "tap for 1 px" moved nothing at all. The click is the
 * fallback for exactly that case, and [steppedWhilePressed] keeps the release of a
 * longer press from adding a step the repeat already made.
 */
@Composable
private fun RepeatingNudgeButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onStep: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    val steppedWhilePressed = remember { mutableStateOf(false) }

    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        step()
        steppedWhilePressed.value = true
        delay(FirstRepeatDelayMillis)
        var interval = RepeatIntervalMillis
        while (true) {
            step()
            delay(interval)
            // Accelerate a little so long journeys do not take forever, but never
            // faster than the editor can commit an edit.
            interval = (interval - 8).coerceAtLeast(MinRepeatIntervalMillis)
        }
    }

    FitButton(
        text = label,
        onClick = {
            if (!steppedWhilePressed.value) step()
            steppedWhilePressed.value = false
        },
        modifier = modifier,
        enabled = enabled,
        style = FitButtonStyle.Secondary,
        interactionSource = interactions,
    )
}

private const val FirstRepeatDelayMillis = 420L
private const val RepeatIntervalMillis = 110L
private const val MinRepeatIntervalMillis = 45L

@Composable
private fun CanvasSidePane(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    selected: WidgetGuide?,
    editable: Boolean,
    onWidget: (Int?) -> Unit,
    onMoveWidget: (Int, Int, Int) -> Unit,
    onTransformImage: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            DirectWatchCanvas(
                snapshot = snapshot,
                // A page that does not take edits gets no outlines and no selection
                // either: the highlight is what says a widget is being worked on.
                editing = editable,
                selectedGlobalIndex = selected?.globalIndex?.takeIf { editable },
                pendingWidgetMove = state.pendingWidgetMove,
                pendingImage = state.pendingImage,
                placement = state.placement,
                // Otherwise the same gate `CanvasWorkspace` uses. This pane only tested
                // `isWorking`, so the wide layout let a widget be dragged while an
                // install was in flight — and that commit invalidates the transfer
                // through `payloadChanged()` the moment it lands.
                enabled = editable && !state.isWorking && !state.directInstall.isActive,
                onWidget = onWidget,
                onMoveWidget = onMoveWidget,
                onTransformImage = onTransformImage,
                modifier = Modifier.width(fittedCanvasWidth(snapshot, SidePaneCanvasMaxWidth)),
            )
        }
        Text(
            snapshot.selectedStyle.removeSuffix(".bin"),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WidgetsWorkspace(
    snapshot: EditorSnapshot,
    selected: WidgetGuide?,
    enabled: Boolean,
    onSelect: (WidgetGuide) -> Unit,
    onRestore: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onCanvas = snapshot.canvasWidgets
    val offCanvas = snapshot.offCanvasWidgets
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeading(
                stringResource(R.string.editor_widgets_on_canvas_title),
                stringResource(
                    R.string.editor_widgets_on_canvas_detail,
                    onCanvas.size,
                    snapshot.widgets.size,
                ),
            )
        }
        items(onCanvas, key = { "canvas-${it.globalIndex}" }) { widget ->
            WidgetRow(widget, snapshot, selected?.globalIndex == widget.globalIndex) {
                onSelect(widget)
            }
        }
        if (offCanvas.isNotEmpty()) {
            item {
                SectionHeading(
                    stringResource(R.string.editor_widgets_off_canvas_title),
                    stringResource(R.string.editor_widgets_off_canvas_detail),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            items(offCanvas, key = { "off-${it.globalIndex}" }) { widget ->
                WidgetRow(
                    widget = widget,
                    snapshot = snapshot,
                    active = selected?.globalIndex == widget.globalIndex,
                    dimmed = true,
                ) { onSelect(widget) }
            }
        }
        if (snapshot.removedWidgets.isNotEmpty()) {
            item {
                SectionHeading(
                    stringResource(R.string.editor_widgets_removed_title),
                    stringResource(R.string.editor_widgets_removed_detail),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            items(snapshot.removedWidgets, key = { "removed-${it.id}" }) { removed ->
                RemovedWidgetRow(removed, enabled) { onRestore(removed.id) }
            }
        }
        item { Box(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(bottom = 2.dp)) {
        MicroLabel(title)
        Text(
            detail,
            modifier = Modifier.padding(top = 5.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
}

@Composable
private fun WidgetRow(
    widget: WidgetGuide,
    snapshot: EditorSnapshot,
    active: Boolean,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = .08f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.shapes.small,
            )
            .border(
                1.dp,
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WidgetThumbnail(
            snapshot = snapshot,
            widget = widget,
            accent = if (dimmed) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f)
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    stringResource(R.string.editor_selection_widget, widget.globalIndex),
                    style = MaterialTheme.typography.titleSmall,
                )
                MicroLabel(widget.category.label, color = MaterialTheme.colorScheme.tertiary)
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when (widget.placement) {
                    WidgetPlacement.BACKGROUND ->
                        MicroLabel(stringResource(R.string.editor_row_placement_background))
                    WidgetPlacement.HIDDEN ->
                        MicroLabel(stringResource(R.string.editor_row_placement_hidden))
                    WidgetPlacement.CANVAS -> if (widget.isFinal) {
                        MicroLabel(stringResource(R.string.editor_row_placement_last))
                    }
                }
                if (widget.hasOpaqueBackdrop) {
                    MicroLabel(
                        stringResource(R.string.editor_row_opaque),
                        color = MaterialTheme.fitColors.warning,
                    )
                }
                // A property of the record, so it reads as one — and keeping it off the line
                // below is what stops that line wrapping.
                widget.frameCount?.let { frames ->
                    MicroLabel(stringResource(R.string.editor_row_frames, frames))
                }
            }
            Text(
                stringResource(
                    R.string.editor_row_record,
                    widget.type,
                    widget.sequenceId,
                    widget.width,
                    widget.height,
                ),
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.secondary,
            )
        }
        Text(
            stringResource(
                R.string.editor_row_position,
                widget.drawLeft(snapshot.preview.width),
                widget.drawTop(snapshot.preview.height),
            ),
            style = FitFaceType.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The widget itself, so the list shows what is being edited instead of only its
 * coordinates.
 *
 * Two sources, in order of truthfulness. A Static or Sprite has a raster in the
 * container, and `widgetImageLayers` has already decoded exactly the frame the
 * watch would blit — that is drawn as-is. Everything else (Value, Composite,
 * Badge, Arc, Bar) is drawn by the watch from live data and has no artwork to
 * decode, so the fallback crops the composed preview: the style's own background
 * with those widgets' pixels lifted out of the vendor's `preview.bin` render.
 *
 * Records with no drawable rectangle — clock hands, whose record stores a rotation
 * pivot rather than an extent — keep the plain accent bar, because there is nothing
 * truthful to draw for them.
 */
@Composable
private fun WidgetThumbnail(
    snapshot: EditorSnapshot,
    widget: WidgetGuide,
    accent: Color,
    extent: Dp = 34.dp,
) {
    val crop = remember(
        snapshot.composedPreview.argb,
        snapshot.widgetImageLayers,
        widget.globalIndex,
        widget.x,
        widget.y,
        widget.width,
        widget.height,
    ) {
        snapshot.widgetImageLayers
            .firstOrNull {
                it.globalIndex == widget.globalIndex &&
                    widget.placement == WidgetPlacement.CANVAS
            }
            ?.frame
            ?: cropWidgetPreview(snapshot.composedPreview, widget)
    }
    if (crop == null) {
        Box(Modifier.width(3.dp).height(28.dp).background(accent))
        return
    }
    val bitmap = crop.rememberBitmap()
    Canvas(
        Modifier.size(extent)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp)),
    ) {
        // Letterboxed, never stretched: a 242x4 divider has to stay readable as one.
        val scale = minOf(size.width / crop.width, size.height / crop.height)
        val drawWidth = (crop.width * scale).coerceAtLeast(1f)
        val drawHeight = (crop.height * scale).coerceAtLeast(1f)
        drawImage(
            image = bitmap,
            dstOffset = IntOffset(
                ((size.width - drawWidth) / 2f).roundToInt(),
                ((size.height - drawHeight) / 2f).roundToInt(),
            ),
            dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

/** The composed-preview pixels under [widget], or null when it covers nothing. */
private fun cropWidgetPreview(frame: PreviewFrame, widget: WidgetGuide): PreviewFrame? {
    if (widget.width <= 0 || widget.height <= 0) return null
    val left = widget.drawLeft(frame.width)
    val top = widget.drawTop(frame.height)
    val startX = left.coerceIn(0, frame.width)
    val startY = top.coerceIn(0, frame.height)
    val endX = (left + widget.width).coerceIn(0, frame.width)
    val endY = (top + widget.height).coerceIn(0, frame.height)
    val cropWidth = endX - startX
    val cropHeight = endY - startY
    if (cropWidth <= 0 || cropHeight <= 0) return null
    val pixels = IntArray(cropWidth * cropHeight)
    for (row in 0 until cropHeight) {
        frame.argb.copyInto(
            destination = pixels,
            destinationOffset = row * cropWidth,
            startIndex = (startY + row) * frame.width + startX,
            endIndex = (startY + row) * frame.width + endX,
        )
    }
    return PreviewFrame(cropWidth, cropHeight, pixels)
}

@Composable
private fun RemovedWidgetRow(
    removed: RemovedWidget,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(3.dp).height(28.dp)
                .background(MaterialTheme.fitColors.warning.copy(alpha = .6f)),
        )
        Column(Modifier.weight(1f)) {
            Text(removed.label, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.editor_row_removed,
                    removed.widgetType,
                    removed.sequenceId,
                    removed.width,
                    removed.height,
                    removed.recordsByStyle.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.secondary,
            )
        }
        FitButton(
            stringResource(R.string.editor_restore),
            onRestore,
            enabled = enabled,
            style = FitButtonStyle.Secondary,
        )
    }
}

@Composable
private fun InspectorWorkspace(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    widget: WidgetGuide?,
    onNudgeWidget: (Int, Int, Int) -> Unit,
    onApplyAll: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDuplicate: () -> Unit,
    onResize: (Boolean) -> Unit,
    onColor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (widget == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.editor_inspector_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Same rule as `SelectionPeek`: the number beside the canvas is the rectangle's own
    // left/top edge. The stored pair is reported separately as "encoded x, y" below, and
    // the nudge buttons move the stored coordinate a pixel either way regardless.
    val displayX = widget.drawLeft(snapshot.preview.width)
    val displayY = widget.drawTop(snapshot.preview.height)
    var confirmRemoval by rememberSaveable(widget.globalIndex) { mutableStateOf(false) }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = {
                Text(stringResource(R.string.editor_remove_title, widget.globalIndex))
            },
            text = {
                Text(
                    if (state.applyWidgetEditsToAllStyles) {
                        stringResource(R.string.editor_remove_body_all_styles)
                    } else {
                        stringResource(
                            R.string.editor_remove_body_one_style,
                            snapshot.selectedStyle.removeSuffix(".bin"),
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRemoval = false; onRemove() }) {
                    Text(stringResource(R.string.editor_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            },
        )
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DirectWatchCanvas(
                snapshot = snapshot,
                editing = true,
                selectedGlobalIndex = widget.globalIndex,
                pendingWidgetMove = state.pendingWidgetMove,
                pendingImage = null,
                placement = state.placement,
                enabled = false,
                onWidget = {},
                onMoveWidget = { _, _, _ -> },
                onTransformImage = { _, _, _ -> },
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
        if (widget.placement != WidgetPlacement.CANVAS) {
            StatusBanner(
                FitStatus.Warning,
                widget.supportMessage,
                label = stringResource(
                    if (widget.placement == WidgetPlacement.BACKGROUND) {
                        R.string.editor_label_background
                    } else {
                        R.string.editor_label_hidden
                    },
                ),
            )
        }
        if (widget.hasOpaqueBackdrop) {
            StatusBanner(
                FitStatus.Warning,
                stringResource(R.string.editor_opaque_body, widget.width, widget.height),
                label = stringResource(R.string.editor_label_opaque),
            )
        }
        Column {
            MicroLabel(stringResource(R.string.editor_position_heading))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CoordinateControl(
                    stringResource(R.string.editor_axis_x), displayX, widget.canEditPosition,
                    { onNudgeWidget(widget.globalIndex, -1, 0) },
                    { onNudgeWidget(widget.globalIndex, 1, 0) },
                    Modifier.weight(1f),
                )
                CoordinateControl(
                    stringResource(R.string.editor_axis_y), displayY, widget.canEditPosition,
                    { onNudgeWidget(widget.globalIndex, 0, -1) },
                    { onNudgeWidget(widget.globalIndex, 0, 1) },
                    Modifier.weight(1f),
                )
            }
            Text(
                stringResource(
                    if (widget.x < 0 || widget.y < 0) {
                        R.string.editor_encoded_anchored
                    } else {
                        R.string.editor_encoded
                    },
                    widget.x,
                    widget.y,
                ),
                modifier = Modifier.padding(top = 9.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.secondary,
            )
        }
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MicroLabel(stringResource(R.string.editor_record_heading))
                MicroLabel(widget.category.label, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                widget.frameCount?.let { frames ->
                    stringResource(
                        R.string.editor_record_detail_frames,
                        widget.width,
                        widget.height,
                        widget.globalIndex,
                        widget.type,
                        widget.sequenceId,
                        frames,
                    )
                } ?: stringResource(
                    R.string.editor_record_detail,
                    widget.width,
                    widget.height,
                    widget.globalIndex,
                    widget.type,
                    widget.sequenceId,
                ),
                modifier = Modifier.padding(top = 9.dp),
                style = FitFaceType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                widget.category.detail,
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                widget.supportMessage,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
            widget.duplicateSourceGlobalIndex?.let { source ->
                MicroLabel(
                    stringResource(R.string.editor_duplicate_of, source),
                    Modifier.padding(top = 10.dp),
                    MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        SpriteSizeControls(widget, !state.isWorking, onResize)
        widget.colorArgb?.let { currentColor ->
            val colors = listOf(
                stringResource(R.string.editor_color_white) to 0xFFFF_FFFF.toInt(),
                stringResource(R.string.editor_color_cyan) to 0xFF00_FFFF.toInt(),
                stringResource(R.string.editor_color_pink) to 0xFFFF_3DDC.toInt(),
                stringResource(R.string.editor_color_green) to 0xFF00_FF00.toInt(),
            )
            Column {
                MicroLabel(
                    stringResource(
                        R.string.editor_pair_color,
                        currentColor.toUInt().toString(16).padStart(8, '0').uppercase(),
                    ),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    colors.forEach { (label, color) ->
                        FitChip(
                            text = label,
                            selected = currentColor == color,
                            onClick = { onColor(color) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isWorking,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .clickable { onApplyAll(!state.applyWidgetEditsToAllStyles) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.editor_apply_all_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (state.applyWidgetEditsToAllStyles) {
                        stringResource(
                            R.string.editor_apply_all_on,
                            snapshot.styleNames.size,
                        )
                    } else {
                        stringResource(
                            R.string.editor_apply_all_off,
                            snapshot.selectedStyle.removeSuffix(".bin"),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.applyWidgetEditsToAllStyles, onCheckedChange = onApplyAll)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MicroLabel(stringResource(R.string.editor_destructive_heading))
            FitButton(
                stringResource(R.string.editor_duplicate),
                onDuplicate,
                Modifier.fillMaxWidth(),
                !state.isWorking,
                style = FitButtonStyle.Secondary,
            )
            FitButton(
                stringResource(R.string.editor_remove_widget),
                { confirmRemoval = true },
                Modifier.fillMaxWidth(),
                !state.isWorking,
                style = FitButtonStyle.Danger,
            )
            Text(
                stringResource(R.string.editor_destructive_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
    }
}

/**
 * Resize is always visible so the capability is discoverable, and says why it is
 * unavailable when the widget's frames do not match the schema that is proven safe
 * to rewrite (unique Sprite sequence, RGB565+A frames, uniform format, not the
 * background image).
 *
 * The sizes come from [spriteResizeLadder], so the readout can say which rung the widget
 * is on and each button knows whether there is another one in its direction — a "Larger"
 * that is still lit at the top of the ladder is a button that does nothing.
 */
@Composable
private fun SpriteSizeControls(
    widget: WidgetGuide,
    enabled: Boolean,
    onResize: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(14.dp),
    ) {
        MicroLabel(stringResource(R.string.editor_size_heading))
        Text(
            stringResource(R.string.editor_size_value, widget.width, widget.height),
            modifier = Modifier.padding(top = 9.dp),
            style = FitFaceType.readout,
            color = if (widget.canResize) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
            },
        )
        if (widget.canResize) {
            val percent = spriteSizePercent(widget)
            val shipped = widget.width == widget.originalWidth &&
                widget.height == widget.originalHeight
            Text(
                when {
                    shipped -> stringResource(
                        R.string.editor_size_shipped,
                        SpriteResizeStepPercent,
                    )
                    percent != null -> stringResource(
                        R.string.editor_size_percent,
                        percent,
                        widget.originalWidth,
                        widget.originalHeight,
                        SpriteResizeStepPercent,
                    )
                    else -> stringResource(
                        R.string.editor_size_off_ladder,
                        widget.originalWidth,
                        widget.originalHeight,
                    )
                },
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            FitButton(
                stringResource(R.string.editor_size_smaller),
                { onResize(false) },
                Modifier.weight(1f),
                enabled && widget.canResize && nextSpriteSize(widget, grow = false) != null,
                style = FitButtonStyle.Secondary,
            )
            FitButton(
                stringResource(R.string.editor_size_larger),
                { onResize(true) },
                Modifier.weight(1f),
                enabled && widget.canResize && nextSpriteSize(widget, grow = true) != null,
                style = FitButtonStyle.Secondary,
            )
        }
        if (!widget.canResize) {
            Text(
                stringResource(R.string.editor_size_unavailable),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
    }
}

@Composable
private fun CoordinateControl(
    label: String,
    value: Int,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MicroLabel(label)
            // Mono: the nudge buttons directly below change this number, and a proportional
            // digit changes width with its value, so the figure jittered as you held one.
            Text(value.toString(), style = FitFaceType.readout)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            RepeatingNudgeButton("−", enabled, Modifier.weight(1f), onMinus)
            RepeatingNudgeButton("+", enabled, Modifier.weight(1f), onPlus)
        }
    }
}

@Composable
private fun BackgroundWorkspace(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    onWidget: (Int?) -> Unit,
    onMoveWidget: (Int, Int, Int) -> Unit,
    onTransformImage: (Float, Float, Float) -> Unit,
    onStepImageZoom: (Boolean) -> Unit,
    onFit: (ImageFit) -> Unit,
    onChooseImage: () -> Unit,
    onResetPlacement: () -> Unit,
    onDiscard: () -> Unit,
    onApply: () -> Unit,
    onTintCyan: () -> Unit,
    onTintMagenta: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Adding one is the only thing a face with no panel raster anywhere can be
        // offered; everything below is otherwise the same-size replacement.
        val adding = snapshot.canAddBackground
        DirectWatchCanvas(
            snapshot = snapshot,
            editing = false,
            selectedGlobalIndex = null,
            pendingWidgetMove = null,
            pendingImage = state.pendingImage.takeIf { snapshot.canReplaceBackground || adding },
            placement = state.placement,
            // `editing = false`, so this only gates the pinch that positions the pending
            // image — which commits nothing on its own and stays live during a transfer.
            enabled = !state.isWorking,
            onWidget = onWidget,
            onMoveWidget = onMoveWidget,
            onTransformImage = onTransformImage,
            modifier = Modifier.widthIn(max = 186.dp),
        )
        if (!snapshot.canReplaceBackground && !adding) {
            NoBackgroundRasterNotice(snapshot)
            return@Column
        }
        if (adding) {
            AddBackgroundNotice(snapshot)
        }
        if (state.pendingImage == null) {
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                    .padding(horizontal = 18.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.editor_bg_no_image_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        R.string.editor_bg_no_image_detail,
                        snapshot.preview.width,
                        snapshot.preview.height,
                    ),
                    modifier = Modifier.padding(top = 7.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PartialBackgroundNotice(snapshot)
            FitButton(
                stringResource(R.string.editor_bg_choose),
                onChooseImage,
                Modifier.fillMaxWidth(),
                !state.isWorking,
            )
            // A tint rewrites a background's palette or samples, so a face without one
            // has nothing for it to touch either.
            if (!adding) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FitButton(
                        stringResource(R.string.editor_bg_tint_cyan),
                        onTintCyan, Modifier.weight(1f), !state.isWorking,
                        style = FitButtonStyle.Secondary,
                    )
                    FitButton(
                        stringResource(R.string.editor_bg_tint_magenta),
                        onTintMagenta, Modifier.weight(1f), !state.isWorking,
                        style = FitButtonStyle.Secondary,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                MicroLabel(stringResource(R.string.editor_bg_fit_heading))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageFit.entries.forEach { fit ->
                        FitChip(
                            fit.name,
                            state.placement.fit == fit,
                            { onFit(fit) },
                            Modifier.weight(1f),
                            !state.isWorking,
                        )
                    }
                }
            }
            PlacementControls(
                placement = state.placement,
                panelWidth = snapshot.preview.width,
                panelHeight = snapshot.preview.height,
                enabled = !state.isWorking,
                onTransformImage = onTransformImage,
                onStepZoom = onStepImageZoom,
                onResetPlacement = onResetPlacement,
            )
            PartialBackgroundNotice(snapshot)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FitButton(
                    stringResource(R.string.editor_bg_discard),
                    onDiscard, Modifier.weight(1f), !state.isWorking,
                    style = FitButtonStyle.Secondary,
                )
                FitButton(
                    stringResource(
                        if (adding) R.string.editor_bg_add else R.string.editor_bg_use,
                    ),
                    onApply,
                    Modifier.weight(2f),
                    !state.isWorking,
                )
            }
        }
    }
}

/**
 * Why this face cannot take a background image, in one line.
 *
 * Two different reasons land here. Either no style carries a panel raster and there is no
 * room to add one — the container is already close enough to the watch's size ceiling that
 * a 205,880-byte raster would push it over, which is true of face `00022` — or the face
 * has no styles at all. Both used to be a paragraph explaining image records; the page now
 * says which of the two it is and moves on.
 */
@Composable
private fun NoBackgroundRasterNotice(snapshot: EditorSnapshot) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(horizontal = 18.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MicroLabel(stringResource(R.string.editor_bg_unavailable_label))
        Text(
            stringResource(
                if (snapshot.backgroundWouldNotFit) {
                    R.string.editor_bg_no_room_title
                } else {
                    R.string.editor_bg_none_title
                },
            ),
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (snapshot.backgroundWouldNotFit) {
                stringResource(
                    R.string.editor_bg_no_room_detail,
                    mebibytes(snapshot.containerBytes),
                    mebibytes(WATCH_CONTAINER_BYTE_CEILING),
                )
            } else {
                stringResource(R.string.editor_bg_none_detail)
            },
            modifier = Modifier.padding(top = 7.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What "add" means on a face that never had a background, in two short lines.
 *
 * It is a bigger edit than a replacement — it grows the container by a raster and the
 * record that draws it — so the page still says so, and says which styles get one when
 * the size ceiling means it cannot be all of them. It used to say it in three paragraphs.
 */
@Composable
private fun AddBackgroundNotice(snapshot: EditorSnapshot) {
    val skipped = snapshot.backgroundAddSkipped
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(16.dp),
    ) {
        MicroLabel(stringResource(R.string.editor_bg_adds_label))
        Text(
            stringResource(R.string.editor_bg_adds_title),
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (skipped.isEmpty()) {
                stringResource(R.string.editor_bg_adds_all, snapshot.styleNames.size)
            } else {
                stringResource(
                    R.string.editor_bg_adds_some,
                    snapshot.backgroundAddTargets.joinToString { it.removeSuffix(".bin") },
                    mebibytes(WATCH_CONTAINER_BYTE_CEILING),
                    skipped.joinToString { it.removeSuffix(".bin") },
                )
            },
            modifier = Modifier.padding(top = 7.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Which styles a replacement will actually land on, when it is not all of them.
 *
 * Face `00011` style0 and `00108` styles 0–3 paint onto black while their siblings do
 * carry a background, so an edit made from one of those styles changes the container
 * without changing the canvas in front of you. Saying so beats looking inert.
 */
@Composable
private fun PartialBackgroundNotice(snapshot: EditorSnapshot) {
    // "Partial" needs something to be partial about. With no background anywhere the
    // notice above has already said so, and this rendered as "the image lands on ."
    // with an empty list.
    if (snapshot.backgroundStyles.isEmpty()) return
    if (snapshot.backgroundStyles.size == snapshot.styleNames.size) return
    val targets = snapshot.backgroundStyles.joinToString { it.removeSuffix(".bin") }
    Text(
        if (snapshot.selectedStyleHasBackground) {
            stringResource(R.string.editor_bg_partial_applies, targets)
        } else {
            stringResource(
                R.string.editor_bg_partial_elsewhere,
                snapshot.selectedStyle.removeSuffix(".bin"),
                targets,
            )
        },
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Precise placement for the pending image, in panel pixels.
 *
 * Drag-and-pinch on a 186 dp canvas is fine for roughing an image in and hopeless for
 * the last few pixels — two fingers on a preview a third of the panel's size cannot
 * express "down three, and one percent smaller". These buttons are the same
 * press-and-hold nudges the widget editor uses, so both surfaces behave the same way.
 *
 * The readout is in panel pixels rather than in the placement's own normalised units:
 * `offsetX 0.05` says nothing, `x +13 px` says where the image is.
 */
@Composable
private fun PlacementControls(
    placement: ImagePlacement,
    panelWidth: Int,
    panelHeight: Int,
    enabled: Boolean,
    onTransformImage: (Float, Float, Float) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onResetPlacement: () -> Unit,
) {
    val horizontalStep = 1f / panelWidth.coerceAtLeast(1)
    val verticalStep = 1f / panelHeight.coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(14.dp),
    ) {
        MicroLabel(stringResource(R.string.editor_placement_heading))
        Text(
            stringResource(
                R.string.editor_placement_readout,
                (placement.zoom * 100).roundToInt(),
                signedPixels(placement.offsetX * panelWidth),
                signedPixels(placement.offsetY * panelHeight),
            ),
            modifier = Modifier.padding(top = 9.dp),
            style = FitFaceType.numeric,
        )
        MicroLabel(stringResource(R.string.editor_placement_move), Modifier.padding(top = 13.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            NudgeDirection.entries.forEach { direction ->
                RepeatingNudgeButton(
                    label = direction.glyph,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onStep = {
                        onTransformImage(
                            1f,
                            direction.deltaX * horizontalStep,
                            direction.deltaY * verticalStep,
                        )
                    },
                )
            }
        }
        MicroLabel(stringResource(R.string.editor_placement_size), Modifier.padding(top = 13.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            RepeatingNudgeButton(
                "−",
                enabled && (placement.zoom * 100).roundToInt() > MinZoomPercent,
                Modifier.weight(1f),
            ) { onStepZoom(false) }
            RepeatingNudgeButton(
                "+",
                enabled && (placement.zoom * 100).roundToInt() < MaxZoomPercent,
                Modifier.weight(1f),
            ) { onStepZoom(true) }
        }
        MicroLabel(
            stringResource(R.string.editor_placement_hint, ZoomStepPercent),
            Modifier.padding(top = 10.dp),
        )
        TextButton(onClick = onResetPlacement, enabled = enabled) {
            Text(
                stringResource(R.string.editor_placement_reset),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Composable because the unit and the sign now come from a resource. */
@Composable
private fun signedPixels(value: Float): String {
    val rounded = value.roundToInt()
    return stringResource(
        if (rounded > 0) R.string.editor_signed_pixels_positive else R.string.editor_signed_pixels,
        rounded,
    )
}

/**
 * Styles, shown as pictures rather than as a list of names.
 *
 * A name alone says nothing about which colourway you are about to load. The selected
 * style is drawn from the composed preview, so it carries the edit; every other one is
 * the package's own render, which is the only per-style image available without
 * reparsing a container per row.
 */
@Composable
private fun StylesWorkspace(
    snapshot: EditorSnapshot,
    enabled: Boolean,
    onStyle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(snapshot.styleNames, key = { it }) { style ->
            val selected = snapshot.selectedStyle == style
            val imagePath = snapshot.stylePreviewPaths[style]
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .07f)
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.shapes.medium,
                    )
                    .border(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.shapes.medium,
                    )
                    .clickable(enabled = enabled) { onStyle(style) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                FacePreview(
                    frame = snapshot.composedPreview.takeIf { selected },
                    imagePath = imagePath,
                    contentDescription = stringResource(
                        R.string.editor_style_preview_a11y,
                        style.removeSuffix(".bin"),
                    ),
                    modifier = Modifier.width(54.dp).height(85.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(style.removeSuffix(".bin"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            when {
                                selected && snapshot.isDirty ->
                                    R.string.editor_style_current_edited
                                selected -> R.string.editor_style_current
                                imagePath != null -> R.string.editor_style_tap_to_load
                                else -> R.string.editor_style_tap_to_load_no_image
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.fitText.secondary,
                    )
                }
                if (selected) Text("●", color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Text(
                stringResource(R.string.editor_styles_footnote),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
    }
}

/**
 * One watch face at panel aspect: [frame] when the caller has real pixels for it,
 * otherwise the package's preview image at [imagePath], otherwise an empty plate.
 */
@Composable
private fun FacePreview(
    frame: PreviewFrame?,
    imagePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(9.dp),
) {
    val plate = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    when {
        frame != null -> {
            val bitmap = frame.rememberBitmap()
            Canvas(
                plate.semantics {
                    if (contentDescription != null) this.contentDescription = contentDescription
                },
            ) {
                drawImage(
                    image = bitmap,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        }
        imagePath != null -> AsyncImage(
            model = File(imagePath),
            contentDescription = contentDescription,
            modifier = plate,
            contentScale = ContentScale.Crop,
        )
        else -> Box(plate)
    }
}

@Composable
private fun ValidateWorkspace(
    snapshot: EditorSnapshot,
    state: EditorUiState,
    onReviewed: () -> Unit,
    onInstall: () -> Unit,
    onSyncThumbnail: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val valid = snapshot.validationErrors.isEmpty()
    // Install is gated on having reviewed the validated preview, and every commit
    // clears that flag. Without re-marking it here, editing anything while on this
    // page (re-rendering the thumbnail, say) leaves "Continue to install" inert.
    LaunchedEffect(snapshot) { onReviewed() }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusBanner(
            if (valid) FitStatus.Pass else FitStatus.Fail,
            if (valid) {
                stringResource(R.string.editor_validate_ok)
            } else {
                // Straight from :core:format, which has no resource table.
                snapshot.validationErrors.joinToString()
            },
        )
        DirectWatchCanvas(
            snapshot = snapshot,
            editing = false,
            selectedGlobalIndex = null,
            pendingWidgetMove = null,
            pendingImage = null,
            placement = state.placement,
            enabled = false,
            onWidget = {},
            onMoveWidget = { _, _, _ -> },
            onTransformImage = { _, _, _ -> },
            modifier = Modifier.widthIn(max = 168.dp),
        )
        Column(Modifier.fillMaxWidth()) {
            MicroLabel(stringResource(R.string.editor_checks_heading))
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
            ) {
                listOf(
                    R.string.editor_check_entry_bounds,
                    R.string.editor_check_record_sizes,
                    R.string.editor_check_crc,
                    R.string.editor_check_payload_length,
                    R.string.editor_check_face_id,
                ).forEach { label -> ValidationCheck(stringResource(label), valid) }
            }
        }
        snapshot.validationWarnings.forEach { warning -> StatusBanner(FitStatus.Warning, warning) }
        ThumbnailCard(
            snapshot = snapshot,
            working = state.isWorking,
            onRefresh = onSyncThumbnail,
        )
        snapshot.audit?.let { audit ->
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(14.dp),
            ) {
                MicroLabel(stringResource(R.string.editor_audit_heading))
                Text(
                    stringResource(
                        R.string.editor_audit_detail,
                        audit.operation,
                        audit.changedPayloadBytes,
                        "${if (audit.sizeDelta >= 0) "+" else ""}${audit.sizeDelta}",
                        audit.changedStyles.size,
                        snapshot.styleNames.size,
                        // The watch ignores a container over the ceiling, so how close this
                        // edit is to it belongs beside the rest of the audit.
                        mebibytes(snapshot.containerBytes),
                        mebibytes(WATCH_CONTAINER_BYTE_CEILING),
                    ),
                    modifier = Modifier.padding(top = 9.dp),
                    style = FitFaceType.numeric,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FitButton(
            stringResource(R.string.editor_continue_to_install),
            onInstall,
            Modifier.fillMaxWidth(),
            valid && !state.isWorking,
        )
        if (!valid) {
            FitButton(
                stringResource(R.string.editor_reset_edits),
                onReset, Modifier.fillMaxWidth(), !state.isWorking,
                style = FitButtonStyle.Danger,
            )
        }
    }
}

/**
 * The face-picker thumbnail, updated on request but only ever once per edit.
 *
 * The button disappears as soon as the thumbnail matches, because a repeat pass is
 * pure loss: the widget pixels come from the vendor's smaller `preview.bin` render,
 * and resampling them again softens the result every time.
 */
@Composable
private fun ThumbnailCard(
    snapshot: EditorSnapshot,
    working: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicroLabel(stringResource(R.string.editor_thumbnail_heading))
            if (snapshot.thumbnailRefreshed) {
                MicroLabel(
                    stringResource(R.string.editor_thumbnail_in_sync_label),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            when {
                snapshot.thumbnailRefreshed -> stringResource(
                    R.string.editor_thumbnail_in_sync,
                    snapshot.selectedStyle.removeSuffix(".bin"),
                )
                !snapshot.isDirty -> stringResource(R.string.editor_thumbnail_unedited)
                snapshot.validationErrors.isNotEmpty() ->
                    stringResource(R.string.editor_thumbnail_blocked)
                else -> stringResource(R.string.editor_thumbnail_stale)
            },
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snapshot.canRefreshThumbnail) {
            FitButton(
                stringResource(R.string.editor_thumbnail_update),
                onRefresh,
                Modifier.fillMaxWidth().padding(top = 12.dp),
                !working,
                loading = working,
                style = FitButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun ValidationCheck(label: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (ok) "✓" else "×",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = FitFaceType.numeric,
        )
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (ok) R.string.editor_check_pass else R.string.editor_check_fail),
            style = FitFaceType.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProjectWorkspace(
    snapshot: EditorSnapshot,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(14.dp),
        ) {
            MicroLabel(stringResource(R.string.editor_container_heading))
            Text(
                stringResource(
                    R.string.editor_container_detail,
                    snapshot.faceId,
                    snapshot.styleNames.size,
                    snapshot.imageCount,
                    snapshot.widgets.size,
                    snapshot.sourceName,
                ),
                modifier = Modifier.padding(top = 9.dp),
                style = FitFaceType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FitButton(
            stringResource(R.string.editor_reset_edits),
            onReset, Modifier.fillMaxWidth(), snapshot.isDirty,
            style = FitButtonStyle.Danger,
        )
        Text(
            stringResource(R.string.editor_reset_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DirectWatchCanvas(
    snapshot: EditorSnapshot,
    editing: Boolean,
    selectedGlobalIndex: Int?,
    pendingWidgetMove: WidgetMovePreview?,
    pendingImage: ReplacementImage?,
    placement: ImagePlacement,
    enabled: Boolean,
    onWidget: (Int?) -> Unit,
    onMoveWidget: (Int, Int, Int) -> Unit,
    onTransformImage: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val composedBitmap = snapshot.composedPreview.rememberBitmap()
    val overlayBitmap = snapshot.widgetOverlay.rememberBitmap()
    val pendingBitmap = pendingImage?.preview?.rememberBitmap()
    var draggingIndex by remember(snapshot.selectedStyle) { mutableIntStateOf(-1) }
    var draggingPosition by remember(snapshot.selectedStyle) { mutableStateOf(Offset.Zero) }
    // Where the finger has actually taken the widget, before the panel clamp.
    // Accumulating the *clamped* position instead is what made a widget stick: push it
    // past an edge, come back, and it started moving again from the edge rather than
    // from under the finger — so it trailed the finger by however far it had overshot,
    // for the rest of the drag. The clamp belongs on the way out, not in the running total.
    var dragTrack by remember(snapshot.selectedStyle) { mutableStateOf(Offset.Zero) }
    val latestEnabled by rememberUpdatedState(enabled)
    // Everything the gesture handlers read has to come through `rememberUpdatedState`.
    // `pointerInput` only restarts its block when a *key* changes, and the keys below are
    // the style, the pending image and the editing flag — none of which move when an edit
    // commits. So the running coroutine goes on executing the lambda it was started with,
    // holding the snapshot from that composition: after a drag, a nudge or a resize the
    // canvas drew the widget in its new place while the hit test still used the old
    // rectangle, so tapping the widget selected nothing and tapping where it used to be
    // selected it. Adding `snapshot` to the keys is not the fix — that restarts the
    // detector mid-gesture and cancels the drag in progress.
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestSelectedGlobalIndex by rememberUpdatedState(selectedGlobalIndex)
    // Resolved here rather than inside `semantics`, which is not a composable scope.
    val canvasDescription = stringResource(
        R.string.editor_canvas_a11y,
        snapshot.selectedStyle,
        snapshot.canvasWidgets.size,
    )
    val visualMoveIndex = draggingIndex.takeIf { it >= 0 }
        ?: pendingWidgetMove?.globalIndex
        ?: -1
    val visualMovePosition = if (draggingIndex >= 0) {
        draggingPosition
    } else {
        pendingWidgetMove?.let { Offset(it.displayX, it.displayY) } ?: Offset.Zero
    }
    val dragLayer = snapshot.rememberWidgetDragLayer(visualMoveIndex)
    DisposableEffect(dragLayer) {
        onDispose { dragLayer?.bitmaps?.forEach(Bitmap::recycle) }
    }
    val selectedGuideColor = MaterialTheme.colorScheme.tertiary
    val guideColor = MaterialTheme.colorScheme.primary
    val borderColor = if (editing) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Canvas(
        modifier = modifier.widthIn(max = 420.dp)
            .fillMaxWidth()
            .aspectRatio(snapshot.preview.width.toFloat() / snapshot.preview.height)
            .clip(RoundedCornerShape(32.dp))
            .semantics {
                contentDescription = canvasDescription
            }
            .pointerInput(pendingImage?.uri, enabled) {
                if (pendingImage != null && enabled) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onTransformImage(
                            zoom,
                            pan.x / size.width.coerceAtLeast(1),
                            pan.y / size.height.coerceAtLeast(1),
                        )
                    }
                }
            }
            .pointerInput(snapshot.selectedStyle, pendingImage?.uri, editing) {
                if (pendingImage == null && editing) {
                    detectTapGestures { position ->
                        if (!latestEnabled) return@detectTapGestures
                        val current = latestSnapshot
                        onWidget(
                            hitWidget(
                                widgets = current.widgets,
                                point = position,
                                canvasWidth = size.width,
                                canvasHeight = size.height,
                                faceWidth = current.preview.width,
                                faceHeight = current.preview.height,
                                preferredGlobalIndex = latestSelectedGlobalIndex,
                            )?.globalIndex,
                        )
                    }
                }
            }
            .pointerInput(snapshot.selectedStyle, pendingImage?.uri, editing) {
                if (pendingImage == null && editing) {
                    detectDragGestures(
                        onDragStart = { position ->
                            if (!latestEnabled) {
                                draggingIndex = -1
                                return@detectDragGestures
                            }
                            val current = latestSnapshot
                            val widget = hitWidget(
                                widgets = current.widgets,
                                point = position,
                                canvasWidth = size.width,
                                canvasHeight = size.height,
                                faceWidth = current.preview.width,
                                faceHeight = current.preview.height,
                                preferredGlobalIndex = latestSelectedGlobalIndex,
                            )
                            if (widget != null) {
                                // The selection is published when the gesture ends, never
                                // here. Publishing it at drag start was the reported "snap":
                                // it made `CanvasWorkspace` render `SelectionPeek` for the
                                // first time *during* the gesture, which took height off the
                                // `BoxWithConstraints` above it, so `fittedCanvasWidth`
                                // shrank the Canvas mid-drag — the face and the widget under
                                // the finger both jumped and `amount.x * preview.width /
                                // size.width` changed scale underneath the drag as well. The
                                // outline still highlights immediately because `activeIndex`
                                // prefers this internal index over the published selection.
                                draggingIndex = widget.globalIndex
                                // A drag carries the *stored* coordinate in display space,
                                // not `drawLeft`: the outline adds `drawOffset` when it
                                // draws and `encodeCoordinate` expects the stored value
                                // back. The clamp is what knows about the offset.
                                draggingPosition = Offset(
                                    x = displayCoordinate(
                                        widget.x,
                                        widget.width,
                                        current.preview.width,
                                    ).toFloat(),
                                    y = displayCoordinate(
                                        widget.y,
                                        widget.height,
                                        current.preview.height,
                                    ).toFloat(),
                                )
                                dragTrack = draggingPosition
                            } else {
                                draggingIndex = -1
                            }
                        },
                        onDrag = { change, amount ->
                            if (draggingIndex >= 0) {
                                change.consume()
                                val current = latestSnapshot
                                val widget = current.widgets.firstOrNull {
                                    it.globalIndex == draggingIndex
                                } ?: return@detectDragGestures
                                val startX = displayCoordinate(
                                    widget.x,
                                    widget.width,
                                    current.preview.width,
                                ).toFloat()
                                val startY = displayCoordinate(
                                    widget.y,
                                    widget.height,
                                    current.preview.height,
                                ).toFloat()
                                val horizontal = stepDragAxis(
                                    track = dragTrack.x,
                                    delta = amount.x * current.preview.width / size.width,
                                    starting = startX,
                                    extent = widget.width,
                                    canvasExtent = current.preview.width,
                                    drawOffset = widget.drawOffsetX,
                                )
                                val vertical = stepDragAxis(
                                    track = dragTrack.y,
                                    delta = amount.y * current.preview.height / size.height,
                                    starting = startY,
                                    extent = widget.height,
                                    canvasExtent = current.preview.height,
                                    drawOffset = widget.drawOffsetY,
                                )
                                dragTrack = Offset(horizontal.track, vertical.track)
                                draggingPosition = Offset(horizontal.position, vertical.position)
                            }
                        },
                        // A cancelled gesture still named a widget, so it still selects one.
                        onDragCancel = {
                            if (draggingIndex >= 0) onWidget(draggingIndex)
                            draggingIndex = -1
                        },
                        onDragEnd = {
                            if (draggingIndex >= 0) {
                                val current = latestSnapshot
                                val widget = current.widgets.firstOrNull {
                                    it.globalIndex == draggingIndex
                                }
                                // Published first and unconditionally: a drag that ends where
                                // it began, is refused by the container, or has lost its
                                // widget to a commit still has to leave the widget the finger
                                // grabbed selected.
                                onWidget(draggingIndex)
                                if (widget != null) {
                                    onMoveWidget(
                                        draggingIndex,
                                        encodeCoordinate(
                                            display = draggingPosition.x.roundToInt(),
                                            extent = widget.width,
                                            canvasExtent = current.preview.width,
                                            anchoredFromEnd = widget.x < 0,
                                        ),
                                        encodeCoordinate(
                                            display = draggingPosition.y.roundToInt(),
                                            extent = widget.height,
                                            canvasExtent = current.preview.height,
                                            anchoredFromEnd = widget.y < 0,
                                        ),
                                    )
                                }
                            }
                            draggingIndex = -1
                        },
                    )
                }
            },
    ) {
        drawRect(Color.Black)
        if (pendingImage != null && pendingBitmap != null) {
            val fitted = fittedRect(
                sourceWidth = pendingImage.preview.width,
                sourceHeight = pendingImage.preview.height,
                targetWidth = size.width,
                targetHeight = size.height,
                placement = placement,
            )
            clipRect {
                drawImage(
                    image = pendingBitmap,
                    dstOffset = IntOffset(
                        fitted.first.x.roundToInt(),
                        fitted.first.y.roundToInt(),
                    ),
                    dstSize = IntSize(
                        fitted.second.width.roundToInt().coerceAtLeast(1),
                        fitted.second.height.roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.High,
                )
                drawImage(
                    image = overlayBitmap,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        } else {
            drawImage(
                image = dragLayer?.base ?: composedBitmap,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
            if (dragLayer != null) {
                val scaleX = size.width / snapshot.preview.width
                val scaleY = size.height / snapshot.preview.height
                drawImage(
                    image = dragLayer.widget,
                    dstOffset = IntOffset(
                        ((visualMovePosition.x + dragLayer.offsetX) * scaleX).roundToInt(),
                        ((visualMovePosition.y + dragLayer.offsetY) * scaleY).roundToInt(),
                    ),
                    dstSize = IntSize(
                        (dragLayer.width * scaleX).roundToInt().coerceAtLeast(1),
                        (dragLayer.height * scaleY).roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Low,
                )
            }
        }

        if (editing && pendingImage == null) {
            val activeIndex = visualMoveIndex.takeIf { it >= 0 } ?: selectedGlobalIndex
            val scaleX = size.width / snapshot.preview.width
            val scaleY = size.height / snapshot.preview.height
            clipRect {
                snapshot.canvasWidgets.forEach { widget ->
                    val selected = widget.globalIndex == activeIndex
                    // A drag tracks the stored coordinate, so the rectangle needs the
                    // same offset the resting position gets.
                    val x = if (visualMoveIndex == widget.globalIndex) {
                        visualMovePosition.x + widget.drawOffsetX
                    } else {
                        widget.drawLeft(snapshot.preview.width).toFloat()
                    }
                    val y = if (visualMoveIndex == widget.globalIndex) {
                        visualMovePosition.y + widget.drawOffsetY
                    } else {
                        widget.drawTop(snapshot.preview.height).toFloat()
                    }
                    drawRect(
                        color = if (selected) selectedGuideColor else guideColor,
                        topLeft = Offset(x * scaleX, y * scaleY),
                        size = Size(widget.width * scaleX, widget.height * scaleY),
                        style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()),
                    )
                }
            }
        }
        drawRect(color = borderColor, style = Stroke(2.dp.toPx()))
    }
}

// ---------------------------------------------------------------------------
// Install
// ---------------------------------------------------------------------------

@Composable
private fun InstallWorkspace(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    onGrantNearby: () -> Unit,
    onOpenCompanion: () -> Unit,
    onInitializeAndDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
    onRediscover: () -> Unit,
    onSendBin: () -> Unit,
    onReset: () -> Unit,
    onCanvas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val delivery = state.directInstall
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceStatusRow(delivery, snapshot)

        // A note, not a wall. This used to replace the whole checklist whenever a package
        // probe came up short, which is how a phone with the watch paired, connected and
        // holding a live accessory session was told direct install was unavailable — with
        // no way forward on the screen. What is installed cannot answer that question, so
        // it is said once, in passing, and the checklist below stays usable.
        delivery.environment.advisory?.let { advisory ->
            StatusBanner(
                FitStatus.Warning,
                stringResource(
                    when (advisory) {
                        EnvironmentAdvisory.FRAMEWORK_MISSING ->
                            R.string.editor_install_advisory_framework
                        EnvironmentAdvisory.NO_ACCESSORY_APP ->
                            R.string.editor_install_advisory_no_agent
                        EnvironmentAdvisory.NO_COMPANION_APP ->
                            R.string.editor_install_advisory_no_companion
                    },
                ),
                label = stringResource(R.string.editor_install_advisory_label),
            )
        }

        run {
            // A rewound setup shows the checklist, which says what to do next but not
            // what went wrong; the transfer panel renders its own failure banner.
            if (!delivery.setupComplete) {
                delivery.failure?.let { failure ->
                    StatusBanner(
                        FitStatus.Fail,
                        failure,
                        label = stringResource(R.string.editor_install_stopped_label),
                    )
                }
            }
            // The banner above already says this in full; showing both is noise.
            Text(
                delivery.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (delivery.setupComplete) {
            TransferPanel(delivery, state.isWorking, onSendBin, onRediscover, onCanvas)
        } else {
            SetupChecklist(
                state = delivery,
                enabled = !state.isWorking,
                onOpenCompanion = onOpenCompanion,
                onGrantNearby = onGrantNearby,
                onDiscover = onInitializeAndDiscover,
                onOpenPluginSettings = onOpenPluginSettings,
                onConfirmPluginReleased = onConfirmPluginReleased,
            )
        }

        PayloadReadout(delivery)

        TextButton(
            onClick = onReset,
            enabled = !delivery.isActive,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.editor_install_restart),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * The watch, and the face that is about to be sent to it.
 *
 * The plate used to be empty. It cannot show what the watch is wearing right now —
 * nothing in the transport reports that — so it shows the payload instead, which is
 * the thing the page is actually about and is exactly what the canvas is holding.
 */
@Composable
private fun DeviceStatusRow(state: DirectInstallState, snapshot: EditorSnapshot) {
    // Resource and colour are chosen together from the state. They used to be two `when`s,
    // the second keyed on the badge's own display text — which quietly ties the colour to
    // the wording, so renaming a badge or translating it would drop it back to the default.
    val (badge, badgeColor) = when {
        !state.environment.probed ->
            R.string.editor_device_badge_checking to MaterialTheme.fitColors.warning
        // Warning, not error. Nothing here stops the reader trying, and colouring an
        // advisory as a failure is what made a working phone look broken.
        state.environment.advisory != null ->
            R.string.editor_device_badge_setup to MaterialTheme.fitColors.warning
        state.peersCached ->
            R.string.editor_device_badge_linked to MaterialTheme.colorScheme.primary
        else -> R.string.editor_device_badge_setup to MaterialTheme.fitColors.warning
    }
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        FacePreview(
            frame = snapshot.composedPreview,
            imagePath = null,
            contentDescription = stringResource(R.string.editor_device_face_a11y),
            modifier = Modifier.width(44.dp).height(69.dp),
            shape = RoundedCornerShape(7.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.editor_device_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                snapshot.faceName?.let {
                    stringResource(
                        R.string.editor_device_face_named,
                        it,
                        snapshot.selectedStyle.removeSuffix(".bin"),
                    )
                } ?: stringResource(
                    R.string.editor_device_face_unnamed,
                    snapshot.faceId,
                    snapshot.selectedStyle.removeSuffix(".bin"),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val plugin = if (state.environment.pluginInstalled) {
                state.environment.pluginLabel
                    ?: stringResource(R.string.editor_device_plugin_installed)
            } else {
                stringResource(R.string.editor_device_plugin_missing)
            }
            Text(
                state.environment.pluginVersionName?.let { version ->
                    stringResource(R.string.editor_device_plugin_version, plugin, version)
                } ?: plugin,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // A long plugin label plus its version does not fit beside the badge,
                // and clipping it silently left the line ending in a bare separator.
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(badge),
            modifier = Modifier
                .background(badgeColor.copy(alpha = .14f), RoundedCornerShape(4.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            color = badgeColor,
            style = FitFaceType.micro,
        )
    }
}

@Composable
private fun SetupChecklist(
    state: DirectInstallState,
    enabled: Boolean,
    onOpenCompanion: () -> Unit,
    onGrantNearby: () -> Unit,
    onDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
) {
    val steps = listOf(
        SetupRow(
            step = SetupStep.COMPANION_PRESENT,
            title = stringResource(R.string.editor_setup_step1_title),
            why = stringResource(R.string.editor_setup_step1_why),
            // Named rather than blanket, because this step is now satisfied by either
            // half and the plugin is the half that owns the channel: saying "companion
            // app and plugin found" when only one of them is there is the same kind of
            // claim-beyond-the-evidence that made the old hard gate wrong.
            done = stringResource(
                when {
                    state.environment.pluginInstalled &&
                        state.environment.companionAppInstalled ->
                        R.string.editor_setup_step1_done
                    state.environment.pluginInstalled ->
                        R.string.editor_setup_step1_done_plugin_only
                    state.environment.companionAppInstalled ->
                        R.string.editor_setup_step1_done_companion_only
                    else -> R.string.editor_setup_step1_done_agent_only
                },
            ),
            action = onOpenCompanion,
        ),
        SetupRow(
            step = SetupStep.HELPER_PERMISSION,
            title = stringResource(R.string.editor_setup_step2_title),
            why = stringResource(R.string.editor_setup_step2_why),
            done = stringResource(R.string.editor_setup_step2_done),
            action = onGrantNearby,
        ),
        SetupRow(
            step = SetupStep.PEERS_DISCOVERED,
            title = stringResource(R.string.editor_setup_step3_title),
            why = stringResource(R.string.editor_setup_step3_why),
            done = stringResource(R.string.editor_setup_step3_done),
            action = onDiscover,
        ),
        SetupRow(
            step = SetupStep.PLUGIN_RELEASED,
            title = stringResource(R.string.editor_setup_step4_title),
            why = stringResource(R.string.editor_setup_step4_why),
            done = stringResource(R.string.editor_setup_step4_done),
            action = onOpenPluginSettings,
        ),
    )
    val completed = steps.count { state.isStepDone(it.step) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusBanner(
            FitStatus.Warning,
            stringResource(R.string.editor_setup_banner),
            label = stringResource(R.string.editor_setup_label),
        )
        Column(
            Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .clip(MaterialTheme.shapes.medium),
        ) {
            steps.forEachIndexed { index, row ->
                val done = state.isStepDone(row.step)
                val busy = state.isStepBusy(row.step)
                // Step 1 is advisory — connecting the watch is something the reader may
                // have done months ago, and this app cannot reliably tell. Only steps 2
                // onward are genuinely sequential, so the chain starts at index 2 and a
                // not-detected companion app never disables the rest of the checklist.
                val blocked = !done && index > 1 && !state.isStepDone(steps[index - 1].step)
                SetupStepRow(
                    number = index + 1,
                    row = row,
                    done = done,
                    busy = busy,
                    blocked = blocked,
                    enabled = enabled && !blocked && !busy,
                )
                if (index != steps.lastIndex) {
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.editor_setup_progress, completed, steps.size),
            modifier = Modifier.fillMaxWidth(),
            style = FitFaceType.micro,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.awaitingDiscovery) {
            // Step 4 is blocked while step 3 is undone, so its shortcut into the
            // plugin's settings is out of reach exactly when the way forward is to
            // turn that access back on — which is where a rewound setup lands.
            //
            // These are shortcuts into the phone's own settings, not a second way to
            // run discovery: step 3's own row is the only button that starts it. A
            // duplicate "Discover the peers" button used to sit here, so the page
            // offered the same action twice with no way to tell them apart.
            val busy = state.isStepBusy(SetupStep.PEERS_DISCOVERED)
            MicroLabel(stringResource(R.string.editor_setup_shortcuts))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FitButton(
                    stringResource(R.string.editor_setup_companion_app),
                    onOpenCompanion,
                    Modifier.weight(1f),
                    enabled && !busy,
                    style = FitButtonStyle.Secondary,
                )
                FitButton(
                    stringResource(R.string.editor_setup_plugin_access),
                    onOpenPluginSettings,
                    Modifier.weight(1f),
                    enabled && !busy,
                    style = FitButtonStyle.Secondary,
                )
            }
        }
        if (state.peersCached && !state.pluginChannelReleased) {
            FitButton(
                stringResource(R.string.editor_setup_confirm_released),
                onConfirmPluginReleased,
                Modifier.fillMaxWidth(),
                enabled,
                style = FitButtonStyle.Secondary,
            )
            Text(
                stringResource(R.string.editor_setup_confirm_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
    }
}

private data class SetupRow(
    val step: SetupStep,
    val title: String,
    val why: String,
    val done: String,
    val action: () -> Unit,
)

@Composable
private fun SetupStepRow(
    number: Int,
    row: SetupRow,
    done: Boolean,
    busy: Boolean,
    blocked: Boolean,
    enabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (done) MaterialTheme.colorScheme.primary.copy(alpha = .05f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .clickable(enabled = enabled && !done, onClick = row.action)
            .padding(13.dp)
            .heightIn(min = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(22.dp)
                .background(
                    if (done) MaterialTheme.colorScheme.primary.copy(alpha = .16f)
                    else Color.Transparent,
                    RoundedCornerShape(11.dp),
                )
                .border(
                    1.dp,
                    if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    if (done) "✓" else number.toString(),
                    style = FitFaceType.micro,
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (blocked) {
                    MaterialTheme.fitText.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                when {
                    busy -> stringResource(R.string.editor_step_working)
                    done -> row.done
                    else -> row.why
                },
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.fitText.secondary,
            )
        }
        if (!done && !busy) {
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransferPanel(
    state: DirectInstallState,
    isWorking: Boolean,
    onSendBin: () -> Unit,
    onRediscover: () -> Unit,
    onCanvas: () -> Unit,
) {
    val order = DirectInstallState.TransferPhases.map { it.first }
    val currentIndex = order.indexOf(state.phase)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            MicroLabel(stringResource(R.string.editor_transfer_phases))
            Column(Modifier.padding(top = 11.dp)) {
                DirectInstallState.TransferPhases.forEachIndexed { index, (phase, label) ->
                    val done = currentIndex > index ||
                        state.phase == DirectInstallPhase.COMPLETE
                    val active = state.phase == phase
                    PhaseRow(
                        label = label,
                        detail = when {
                            phase == DirectInstallPhase.TRANSFERRING && active -> stringResource(
                                R.string.editor_transfer_progress,
                                state.acknowledgedWindows,
                                state.totalWindows,
                                state.acknowledgedBytes,
                                state.totalBytes,
                            )
                            else -> null
                        },
                        done = done,
                        active = active,
                        last = index == DirectInstallState.TransferPhases.lastIndex,
                    )
                }
            }
        }
        if (state.isActive) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.editor_transfer_keep_open),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            state.phase == DirectInstallPhase.COMPLETE -> {
                StatusBanner(
                    FitStatus.Pass,
                    stringResource(R.string.editor_transfer_done),
                    label = stringResource(R.string.editor_transfer_done_label),
                )
                // Sending the same bytes again is legitimate — a watch can reject a
                // face for reasons that have nothing to do with the payload.
                FitButton(
                    stringResource(R.string.editor_transfer_send_again),
                    onSendBin, Modifier.fillMaxWidth(), !isWorking,
                    style = FitButtonStyle.Secondary,
                )
                FitButton(
                    stringResource(R.string.editor_transfer_back_to_canvas),
                    onCanvas, Modifier.fillMaxWidth(),
                    style = FitButtonStyle.Secondary,
                )
            }
            state.phase == DirectInstallPhase.FAILED -> {
                StatusBanner(FitStatus.Fail, state.failure ?: state.message)
                // A failure used to leave the page with no action at all, so the only
                // way forward was restarting the whole setup.
                FitButton(
                    stringResource(R.string.editor_transfer_try_again),
                    onSendBin, Modifier.fillMaxWidth(), !isWorking,
                )
                // Re-sending only helps while the peers are still live. Anything that
                // outlived the channel handover needs the watch connected again, and
                // reaching that means walking discovery and the handover once more —
                // which the cached peers otherwise hid behind this panel forever.
                FitButton(
                    stringResource(R.string.editor_transfer_rediscover),
                    onRediscover,
                    Modifier.fillMaxWidth(),
                    !isWorking,
                    style = FitButtonStyle.Secondary,
                )
                FitButton(
                    stringResource(R.string.editor_transfer_back_to_canvas),
                    onCanvas, Modifier.fillMaxWidth(),
                    style = FitButtonStyle.Secondary,
                )
            }
            state.isActive -> {
                FitButton(
                    stringResource(
                        R.string.editor_transfer_in_progress,
                        state.phase.name.lowercase().replaceFirstChar(Char::uppercase),
                    ),
                    {},
                    Modifier.fillMaxWidth(),
                    enabled = false,
                )
            }
            else -> {
                FitButton(
                    stringResource(R.string.editor_transfer_install),
                    onSendBin,
                    Modifier.fillMaxWidth(),
                    enabled = !isWorking && state.phase == DirectInstallPhase.READY,
                )
            }
        }
    }
}

@Composable
private fun PhaseRow(
    label: String,
    detail: String?,
    done: Boolean,
    active: Boolean,
    last: Boolean,
) {
    val dotColor = when {
        done -> MaterialTheme.colorScheme.primary
        active -> MaterialTheme.fitColors.warning
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.width(14.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.padding(top = 5.dp).size(9.dp)
                    .background(dotColor, RoundedCornerShape(5.dp)),
            )
            if (!last) {
                Box(
                    Modifier.width(1.dp).heightIn(min = 26.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (done || active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.fitText.tertiary
                },
            )
            detail?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 3.dp),
                    style = FitFaceType.numeric,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PayloadReadout(state: DirectInstallState) {
    if (state.sha256 == null && state.faceId == null) return
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(14.dp),
    ) {
        MicroLabel(stringResource(R.string.editor_payload_heading))
        val none = stringResource(R.string.editor_payload_none)
        Text(
            stringResource(
                R.string.editor_payload_detail,
                state.faceId?.toString() ?: none,
                state.samplerId?.toString() ?: none,
                state.sha256?.take(24) ?: none,
                state.totalBytes,
            ),
            modifier = Modifier.padding(top = 9.dp),
            style = FitFaceType.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Canvas helpers
// ---------------------------------------------------------------------------

internal fun hitWidget(
    widgets: List<WidgetGuide>,
    point: Offset,
    canvasWidth: Int,
    canvasHeight: Int,
    faceWidth: Int,
    faceHeight: Int,
    preferredGlobalIndex: Int? = null,
): WidgetGuide? {
    val x = point.x * faceWidth / canvasWidth.coerceAtLeast(1)
    val y = point.y * faceHeight / canvasHeight.coerceAtLeast(1)
    val candidates = widgets.asSequence()
        .filter { it.placement.isVisibleOnCanvas && it.canEditPosition }
        .filter {
            val left = it.drawLeft(faceWidth)
            val top = it.drawTop(faceHeight)
            // Half-open on purpose. Testing `x <= left + width` made the rectangle
            // width+1 px wide, so two abutting widgets shared a one-pixel column and
            // the right and bottom edges belonged to both of them.
            x >= left && x < left + it.width && y >= top && y < top + it.height
        }
        .toList()
    val specific = candidates.minWithOrNull(
        compareBy<WidgetGuide> { it.width.toLong() * it.height }
            .thenByDescending(WidgetGuide::globalIndex),
    ) ?: return null
    val preferred = candidates.singleOrNull { it.globalIndex == preferredGlobalIndex }
    val specificArea = specific.width.toLong() * specific.height
    val preferredArea = preferred?.let { it.width.toLong() * it.height }
    return preferred?.takeIf {
        preferredArea != null && preferredArea <= specificArea * 5 / 4
    } ?: specific
}

/**
 * Keeps a dragged coordinate on the panel, in the stored coordinate's own display space.
 *
 * [drawOffset] is what makes that the *drawn* rectangle rather than the stored endpoint:
 * a widget draws at `display + drawOffset`, so the window shifts by the same amount. It is
 * zero for almost everything, but a Badge whose stored endpoint is the far one has
 * `drawOffsetX = -width` — 52 of the catalogue's 84 Badges — and clamping that to
 * `[0, canvasExtent - width]` was a whole width out: its real window is
 * `[width, canvasExtent]`, so such a Badge could be dragged until its rectangle sat
 * entirely off the left edge and could never reach the right one.
 *
 * Both bounds widen to admit [starting], so a widget that begins off-canvas — from an
 * earlier build, or from a face that ships one — can still be walked gradually back in
 * instead of snapping to the edge on the first movement.
 */
internal fun constrainDragCoordinate(
    proposed: Float,
    starting: Float,
    extent: Int,
    canvasExtent: Int,
    drawOffset: Int = 0,
): Float {
    // Negated as an Int, not as a Float: `-drawOffset.toFloat()` is -0.0f at the common
    // drawOffset of 0, and coerceIn hands that back as the clamped value, so a widget held
    // at the left edge reported -0.0 where every caller and test expects 0.0.
    val minimum = minOf((-drawOffset).toFloat(), starting)
    val maximum = maxOf(
        ((canvasExtent - extent).coerceAtLeast(0) - drawOffset).toFloat(),
        starting,
    )
    return proposed.coerceIn(minimum, maximum)
}

/**
 * One axis of a drag: the finger's own running total, and the position to draw and commit.
 *
 * [DragAxis.track] is unclamped on purpose, and the clamp is applied to it on the way out
 * rather than folded into it. Accumulating the *clamped* value made a widget stick: push
 * it past an edge, bring the finger back, and it resumed from the edge instead of from
 * under the finger — so it trailed by however far the finger had overshot, for the rest of
 * the drag, and the further you pushed the worse the offset got.
 */
internal data class DragAxis(val track: Float, val position: Float)

internal fun stepDragAxis(
    track: Float,
    delta: Float,
    starting: Float,
    extent: Int,
    canvasExtent: Int,
    drawOffset: Int = 0,
): DragAxis {
    val moved = track + delta
    return DragAxis(
        track = moved,
        position = constrainDragCoordinate(moved, starting, extent, canvasExtent, drawOffset),
    )
}

@Composable
private fun PreviewFrame.rememberBitmap(): ImageBitmap {
    val bitmap = remember(argb) {
        Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }
    DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
    return remember(bitmap) { bitmap.asImageBitmap() }
}

private data class WidgetDragLayer(
    val base: ImageBitmap,
    val widget: ImageBitmap,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
    val bitmaps: List<Bitmap>,
)

@Composable
private fun EditorSnapshot.rememberWidgetDragLayer(
    globalIndex: Int,
): WidgetDragLayer? = remember(
    composedPreview.argb,
    preview.argb,
    widgetOverlay.argb,
    widgetImageLayers,
    widgets,
    globalIndex,
) {
    val selected = widgets.singleOrNull { it.globalIndex == globalIndex }
        ?: return@remember null
    val sourceX = selected.drawLeft(preview.width)
    val sourceY = selected.drawTop(preview.height)
    val embeddedFrame = widgetImageLayers
        .singleOrNull { it.globalIndex == globalIndex }
        ?.frame
    if (embeddedFrame != null) {
        val basePixels = composedPreview.argb.copyOf()
        for (localY in 0 until embeddedFrame.height) {
            for (localX in 0 until embeddedFrame.width) {
                val pixel = embeddedFrame.argb[localY * embeddedFrame.width + localX]
                if (pixel ushr 24 == 0) continue
                val x = sourceX + localX
                val y = sourceY + localY
                if (x !in 0 until preview.width || y !in 0 until preview.height) continue
                val index = y * preview.width + x
                basePixels[index] = preview.argb[index]
            }
        }
        val baseBitmap = Bitmap.createBitmap(
            basePixels,
            preview.width,
            preview.height,
            Bitmap.Config.ARGB_8888,
        )
        val widgetBitmap = Bitmap.createBitmap(
            embeddedFrame.argb,
            embeddedFrame.width,
            embeddedFrame.height,
            Bitmap.Config.ARGB_8888,
        )
        return@remember WidgetDragLayer(
            base = baseBitmap.asImageBitmap(),
            widget = widgetBitmap.asImageBitmap(),
            offsetX = 0,
            offsetY = 0,
            width = embeddedFrame.width,
            height = embeddedFrame.height,
            bitmaps = listOf(baseBitmap, widgetBitmap),
        )
    }
    val cropLeft = sourceX.coerceIn(0, preview.width)
    val cropTop = sourceY.coerceIn(0, preview.height)
    val cropRight = (sourceX + selected.width).coerceIn(0, preview.width)
    val cropBottom = (sourceY + selected.height).coerceIn(0, preview.height)
    val cropWidth = cropRight - cropLeft
    val cropHeight = cropBottom - cropTop
    if (cropWidth <= 0 || cropHeight <= 0) return@remember null

    val selectedArea = selected.width.toLong() * selected.height
    val smallerOverlapMask = BooleanArray(preview.width * preview.height)
    widgets.filter { other ->
        other.globalIndex != selected.globalIndex &&
            other.width > 0 &&
            other.height > 0 &&
            other.width.toLong() * other.height < selectedArea
    }.forEach { other ->
        val left = other.drawLeft(preview.width)
        val top = other.drawTop(preview.height)
        val right = (left + other.width).coerceAtMost(preview.width)
        val bottom = (top + other.height).coerceAtMost(preview.height)
        for (y in top.coerceAtLeast(0) until bottom) {
            for (x in left.coerceAtLeast(0) until right) {
                smallerOverlapMask[y * preview.width + x] = true
            }
        }
    }
    val layerPixels = IntArray(cropWidth * cropHeight)
    var hasPixels = false
    for (localY in 0 until cropHeight) {
        for (localX in 0 until cropWidth) {
            val x = cropLeft + localX
            val y = cropTop + localY
            if (smallerOverlapMask[y * preview.width + x]) continue
            val pixel = widgetOverlay.argb[y * preview.width + x]
            layerPixels[localY * cropWidth + localX] = pixel
            hasPixels = hasPixels || pixel ushr 24 != 0
        }
    }
    if (!hasPixels) return@remember null

    val basePixels = composedPreview.argb.copyOf()
    for (localY in 0 until cropHeight) {
        for (localX in 0 until cropWidth) {
            if (layerPixels[localY * cropWidth + localX] ushr 24 == 0) continue
            val x = cropLeft + localX
            val y = cropTop + localY
            val index = y * preview.width + x
            basePixels[index] = preview.argb[index]
        }
    }
    val baseBitmap = Bitmap.createBitmap(
        basePixels,
        preview.width,
        preview.height,
        Bitmap.Config.ARGB_8888,
    )
    val widgetBitmap = Bitmap.createBitmap(
        layerPixels,
        cropWidth,
        cropHeight,
        Bitmap.Config.ARGB_8888,
    )
    WidgetDragLayer(
        base = baseBitmap.asImageBitmap(),
        widget = widgetBitmap.asImageBitmap(),
        offsetX = cropLeft - sourceX,
        offsetY = cropTop - sourceY,
        width = cropWidth,
        height = cropHeight,
        bitmaps = listOf(baseBitmap, widgetBitmap),
    )
}

private fun fittedRect(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Float,
    targetHeight: Float,
    placement: ImagePlacement,
): Pair<Offset, Size> {
    val widthScale = targetWidth / sourceWidth
    val heightScale = targetHeight / sourceHeight
    val baseScale = when (placement.fit) {
        ImageFit.CONTAIN -> minOf(widthScale, heightScale)
        ImageFit.COVER -> maxOf(widthScale, heightScale)
        ImageFit.STRETCH -> 1f
    }
    val baseWidth = if (placement.fit == ImageFit.STRETCH) targetWidth else sourceWidth * baseScale
    val baseHeight = if (placement.fit == ImageFit.STRETCH) {
        targetHeight
    } else {
        sourceHeight * baseScale
    }
    val width = baseWidth * placement.zoom
    val height = baseHeight * placement.zoom
    val centerX = targetWidth / 2f + placement.offsetX * targetWidth
    val centerY = targetHeight / 2f + placement.offsetY * targetHeight
    return Offset(centerX - width / 2f, centerY - height / 2f) to Size(width, height)
}
