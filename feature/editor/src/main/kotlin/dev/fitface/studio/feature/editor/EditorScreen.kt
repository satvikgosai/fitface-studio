package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.model.visualBounds
import dev.fitface.studio.core.model.visualOffsetX
import dev.fitface.studio.core.model.visualOffsetY

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.fitface.studio.core.delivery.DirectInstallPhase
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.delivery.EnvironmentAdvisory
import dev.fitface.studio.core.delivery.SetupStep
import dev.fitface.studio.core.model.AOD_ENTRY_NAME
import dev.fitface.studio.core.model.EditAuditSummary
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.EditorVariant
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.RemovedWidget
import dev.fitface.studio.core.model.ReplacementImage
import dev.fitface.studio.core.model.VariantKind
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.WidgetCategory
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetImageLayer
import dev.fitface.studio.core.model.WidgetLayerComposer
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.WidgetResizeStepPercent
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.mebibytes
import dev.fitface.studio.core.model.nextWidgetSize
import dev.fitface.studio.core.model.widgetSizePercent
import dev.fitface.studio.core.ui.AppMenuAction
import dev.fitface.studio.core.ui.DiagnosticsDialog
import dev.fitface.studio.core.ui.FitBadge
import dev.fitface.studio.core.ui.FitDetails
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitChip
import dev.fitface.studio.core.ui.FitFaceType
import dev.fitface.studio.core.ui.FitIconButton
import dev.fitface.studio.core.ui.FitStatus
import dev.fitface.studio.core.ui.FitTopBar
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.StatusBanner
import dev.fitface.studio.core.ui.fitColors
import dev.fitface.studio.core.ui.fitText
import dev.fitface.studio.core.ui.styleLabel
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    // Deleting the open project takes its row, its directory and its session with it, so
    // there is nothing left for this screen to draw.
    LaunchedEffect(state.projectDeleted) { if (state.projectDeleted) onBack() }
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

    // Show first, clear in a `finally` — the same shape as the error above, for the same
    // reason: clearing first changes this effect's key mid-`showSnackbar` and cancels it.
    val duplicated = state.duplicated
    val duplicatedText = duplicated?.let {
        stringResource(R.string.editor_project_duplicated, it.name)
    }
    LaunchedEffect(duplicated?.id) {
        if (duplicated == null || duplicatedText == null) return@LaunchedEffect
        try {
            snackbar.showSnackbar(duplicatedText)
        } finally {
            viewModel.clearDuplicated(duplicated.id)
        }
    }

    // A set edit that stopped partway: said in words, because the face has visibly changed
    // and a bare error over it would read as though nothing had.
    val stopped = state.selectionStopped
    val stoppedText = stopped?.let {
        stringResource(
            when (it.action) {
                SelectionAction.DUPLICATE -> R.string.editor_selection_stopped_duplicate
                SelectionAction.REMOVE -> R.string.editor_selection_stopped_remove
            },
            it.done, it.total, it.reason,
        )
    }
    LaunchedEffect(stopped?.id) {
        if (stopped == null || stoppedText == null) return@LaunchedEffect
        try {
            snackbar.showSnackbar(stoppedText, withDismissAction = true, duration = SnackbarDuration.Long)
        } finally {
            viewModel.clearSelectionStopped(stopped.id)
        }
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
        onVariant = viewModel::selectVariant,
        onWidget = viewModel::selectWidget,
        selection = SelectionActions(
            onBegin = viewModel::beginSelection,
            onClear = viewModel::clearSelection,
            onNudge = viewModel::nudgeSelection,
            onDuplicate = viewModel::duplicateSelection,
            onRemove = viewModel::removeSelection,
        ),
        onMoveWidget = viewModel::moveWidget,
        onNudgeWidget = viewModel::nudgeWidget,
        onRemoveWidget = viewModel::removeSelectedWidget,
        onDuplicateWidget = viewModel::duplicateSelectedWidget,
        onRestoreWidget = viewModel::restoreWidget,
        onWidgetImported = viewModel::acceptWidgetImport,
        onBackgroundImported = viewModel::acceptBackgroundImport,
        onStylesDeleted = viewModel::acceptStyleDeletion,
        onResizeWidget = viewModel::resizeSelectedWidget,
        onWidgetColor = viewModel::setSelectedWidgetColor,
        onRotateWidget = viewModel::rotateSelectedWidget,
        onArrangeWidget = viewModel::arrangeSelectedWidget,
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
        onRename = viewModel::renameProject,
        onDelete = viewModel::deleteProject,
        onDuplicate = viewModel::duplicateProject,
        onGrantNearby = {
            val permissions = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    /**
     * The checks and the transfer, on one page.
     *
     * They were two rail entries describing one journey. Tapping Install with an
     * unreviewed preview did not open Install: it silently landed on Validate and marked
     * the preview reviewed, so the rail said one thing and the screen said another. The
     * gate that redirect implemented is a property of the layout now — the validated
     * render is the first thing on this page and the send button is near the last — and
     * `previewReviewed` still arms the button, so the mechanism survives without a page
     * that exists only to be passed through.
     */
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
            else -> Canvas
        }

    /**
     * Whether the canvas the wide layout keeps beside this page is an editor or a picture.
     *
     * Outlined widgets are a promise you can drag them — the Canvas page's own hint says
     * so in words — and the send page was making that promise on the one page whose job
     * is to send what has already been decided. Nothing was ever at risk there: the pane
     * is frozen while a transfer is active, and a commit re-arms a finished one through
     * `payloadChanged()` rather than corrupting it. The affordance contradicting the page
     * is the bug, so Send gets a read-only render.
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
    onVariant: (EditorVariant) -> Unit,
    onWidget: (Int?) -> Unit,
    selection: SelectionActions,
    onMoveWidget: (Int, Int, Int) -> Unit,
    onNudgeWidget: (Int, Int, Int) -> Unit,
    onRemoveWidget: () -> Unit,
    onDuplicateWidget: () -> Unit,
    onRestoreWidget: (Long) -> Unit,
    onWidgetImported: (EditorSnapshot) -> Unit,
    onBackgroundImported: (EditorSnapshot) -> Unit,
    onStylesDeleted: (EditorSnapshot) -> Unit,
    onResizeWidget: (Boolean) -> Unit,
    onWidgetColor: (Int?) -> Unit,
    onRotateWidget: (Int) -> Unit,
    onArrangeWidget: (dev.fitface.studio.core.model.WidgetArrangement) -> Unit,
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
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
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
    var importing by rememberSaveable { mutableStateOf(false) }
    var importingBackground by rememberSaveable { mutableStateOf(false) }

    val snapshot = state.snapshot
    // Two ways to delete styles. Manage styles is for recovering space, where several may
    // need to go and the style being edited is kept for the pending edit; a style's own ✕ on
    // the Styles page confirms that one style, and protects it only while an unapplied
    // background still needs it.
    var managingStyles by rememberSaveable { mutableStateOf(false) }
    var deletingStyle by rememberSaveable { mutableStateOf<String?>(null) }
    if (managingStyles && snapshot != null) StyleManagementRoute(snapshot.projectId,
        capacity = state.capacity, protectedVariant = snapshot.selectedVariant.basename,
        onDismiss = { managingStyles = false },
        onDeleted = { managingStyles = false; onStylesDeleted(it) })
    deletingStyle?.let { style ->
        if (snapshot != null) DeleteStyleDialog(snapshot.projectId, style,
            protectedVariant = snapshot.selectedVariant.basename.takeIf { state.pendingImage != null },
            onDismiss = { deletingStyle = null },
            onDeleted = { deletingStyle = null; onStylesDeleted(it) })
    }
    if ((importing || importingBackground) && snapshot != null) WidgetImportRoute(snapshot,
        backgroundMode = importingBackground,
        onStylesDeleted = onStylesDeleted,
        onDismiss = { importing = false; importingBackground = false },
        onImported = { result ->
            if (importingBackground) onBackgroundImported(result) else onWidgetImported(result)
            importing = false
            importingBackground = false
            page = EditorPage.Canvas
        })
    val selected = snapshot?.widgets?.singleOrNull {
        it.globalIndex == state.selectedWidgetIndex
    }
    // No redirect any more: Install is one page, so there is nowhere to be quietly sent
    // instead. Arriving marks the preview reviewed, which is what arms the send button,
    // and `InstallWorkspace` re-marks it on every snapshot so an edit made on the page
    // cannot leave it inert.
    val navigate: (EditorPage) -> Unit = { target ->
        if (target != page) {
            page = target
            if (target == EditorPage.Install) onPreviewReviewed()
        }
    }
    val goBack: () -> Unit = {
        val parent = page.parent
        if (parent == null) onBack() else page = parent
    }
    // Removing a widget left the Inspector describing nothing — no name, no rectangle, no
    // control that did anything — and the back arrow was the only way out. It falls back to
    // the face, which is where the widget was selected and where the removal is visible.
    //
    // Driven by the removal *count* changing, not by "the Inspector has no widget". That
    // rule reads better and is wrong: `page` is local Compose state and moves in the same
    // frame as the tap, while the selection comes through `collectAsStateWithLifecycle` a
    // frame later, so opening a widget from the list would find an Inspector that had not
    // been told which widget yet and bounce straight back to it. The counter only advances
    // on a removal that committed, which also leaves a failed one on the page it happened
    // on, where its message is.
    var removalsSeen by rememberSaveable { mutableIntStateOf(state.widgetRemovals) }
    LaunchedEffect(state.widgetRemovals) {
        if (state.widgetRemovals != removalsSeen) {
            removalsSeen = state.widgetRemovals
            if (page == EditorPage.Inspector) page = EditorPage.Canvas
        }
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
                state.capacity?.let { capacity ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.editor_style_capacity_deficit, capacity.deficit),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { managingStyles = true }, enabled = !state.isWorking) {
                            Text(stringResource(R.string.editor_manage_styles))
                        }
                    }
                }
                // The wide layout keeps a canvas beside every page but Canvas itself, so a
                // page that also renders the face renders it twice, side by side, at two
                // sizes. Send is the one where that is pure duplication: both are the same
                // read-only picture. (Background's own preview is the thing being dragged,
                // so it stays.)
                val sideCanvasVisible = wide && page != EditorPage.Canvas
                val content: @Composable (Modifier) -> Unit = { contentModifier ->
                    EditorPageContent(
                        page = page,
                        state = state,
                        sideCanvasVisible = sideCanvasVisible,
                        snapshot = snapshot,
                        selected = selected,
                        onNavigate = navigate,
                        onVariant = onVariant,
                        onWidget = onWidget,
                        selection = selection,
                        onMoveWidget = onMoveWidget,
                        onNudgeWidget = onNudgeWidget,
                        onRemoveWidget = onRemoveWidget,
                        onDuplicateWidget = onDuplicateWidget,
                        onRestoreWidget = onRestoreWidget,
                        onImportWidget = { importing = true },
                        onImportBackground = { importingBackground = true },
                        onManageStyles = { managingStyles = true },
                        onDeleteStyle = { deletingStyle = it },
                        onResizeWidget = onResizeWidget,
                        onWidgetColor = onWidgetColor,
                        onRotateWidget = onRotateWidget,
                        onArrangeWidget = onArrangeWidget,
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
                        onRename = onRename,
                        onDelete = onDelete,
                        onDuplicate = onDuplicate,
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

/**
 * What to call a face entry in copy — never a container basename.
 *
 * `style0.bin` is "Style 1" and `aod.bin` is "Always-on display": the entry names are the
 * format's, the numbering is zero-based, and neither is something a reader should have to
 * translate. The number comes from [EditorVariant.displayNumberOf], which reads it out of
 * the name, so this screen and the library label the same colourway the same way.
 *
 * Every site that shows an entry name goes through here — the header subtitle, the Styles
 * rows, the background notices, the thumbnail copy and the install summary — because the
 * ones that formatted their own `removeSuffix(".bin")` are exactly how "style0" kept
 * reaching the screen.
 */
@Composable
internal fun variantLabel(basename: String): String =
    EditorVariant.styleNumberOf(basename)
        ?.let { styleLabel(it) }
        ?: if (basename == AOD_ENTRY_NAME) {
            stringResource(R.string.editor_aod_label)
        } else {
            // Not a shape this app has seen. Showing the raw entry beats showing nothing.
            basename.removeSuffix(".bin")
        }

/**
 * [variantLabel] for a list of entries, as "Style 1, Style 3".
 *
 * `map` is inline, so the label lookup stays a composable call; `joinToString`'s own
 * transform is not, which is why the two steps are separate.
 */
@Composable
private fun variantLabels(basenames: List<String>): String =
    basenames.map { variantLabel(it) }.joinToString()

/** [variantLabel] for [EditorSnapshot.selectedVariant]. */
@Composable
private fun EditorSnapshot.selectedVariantLabel(): String =
    if (isAodSelected) {
        stringResource(R.string.editor_aod_label)
    } else {
        variantLabel(selectedVariant.basename)
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
        // The project's name, not the face's. Two projects on one face carry the same
        // `faceName`, so titling the editor with it left no way to tell from this screen
        // which of them was open — and the subtitle below is the face and style, which are
        // identical too.
        EditorPage.Canvas -> snapshot.projectName
        EditorPage.Widgets -> stringResource(R.string.editor_page_widgets)
        EditorPage.Inspector -> stringResource(
            R.string.editor_page_widget_number,
            selected?.globalIndex?.toString()
                ?: stringResource(R.string.editor_widget_index_unknown),
        )
        EditorPage.Background -> stringResource(R.string.editor_page_background)
        EditorPage.Styles -> stringResource(R.string.editor_page_styles)
        EditorPage.Install -> stringResource(R.string.editor_page_install)
        EditorPage.Project -> stringResource(R.string.editor_page_project)
    }
    val subtitle = when (page) {
        EditorPage.Canvas -> stringResource(
            R.string.editor_subtitle_canvas,
            snapshot.faceId,
            snapshot.selectedVariantLabel(),
        )
        EditorPage.Widgets -> stringResource(
            R.string.editor_subtitle_widgets,
            snapshot.canvasWidgets.size,
            snapshot.widgets.size,
        )
        EditorPage.Inspector -> selected?.let {
            stringResource(
                R.string.editor_subtitle_inspector,
                it.category.label,
                it.readingLabel(),
            )
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
        EditorPage.Styles -> if (snapshot.hasAod) {
            pluralStringResource(
                R.plurals.editor_subtitle_styles_with_aod,
                snapshot.styleNames.size,
                snapshot.styleNames.size,
            )
        } else {
            pluralStringResource(
                R.plurals.editor_subtitle_styles,
                snapshot.styleNames.size,
                snapshot.styleNames.size,
            )
        }
        EditorPage.Install -> stringResource(R.string.editor_subtitle_install)
        EditorPage.Project -> snapshot.sourceName
    }
    // The app menu goes last so its right edge stays put across pages.
    //
    // Two glyphs sit side by side on Canvas and they are deliberately different shapes: `⋯`
    // navigates to this face's Project page, `≡` opens the app-wide menu. Two ellipses would
    // read as one control with two behaviours.
    FitTopBar(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        titleContentDescription = if (page == EditorPage.Canvas && snapshot.isDirty) {
            stringResource(R.string.editor_title_edited_a11y, title)
        } else null,
    ) {
        // Keep edit status visible without opening Project or scrolling to Apply.
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
    // The labels are the page titles, so the rail and the header cannot drift apart. The
    // canvas is the exception and has a label of its own: its header is titled with the
    // project's name, which is the only thing on that screen telling two projects on one
    // face apart.
    //
    // It is first because it is the subject of every other page, and because until it was
    // here the only way back to it was the `‹` arrow — which everywhere else in this app
    // means "leave". Now `‹` means one thing again.
    val destinations = listOf(
        Triple("▣", stringResource(R.string.editor_page_canvas), EditorPage.Canvas),
        Triple("▦", stringResource(R.string.editor_page_widgets), EditorPage.Widgets),
        Triple("▧", stringResource(R.string.editor_page_background), EditorPage.Background),
        Triple("◫", stringResource(R.string.editor_page_styles), EditorPage.Styles),
        Triple("⇧", stringResource(R.string.editor_page_install), EditorPage.Install),
    )
    check(destinations.size == RailDestinationCount) {
        // The budget below is a fifth of the bar. A sixth entry silently re-clips the
        // longest label, which is exactly how `BACKGROUND` came to render as `BACKGROUN`.
        "The rail's width budget is computed for $RailDestinationCount destinations"
    }
    val container = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)
    if (vertical) {
        // A landscape phone leaves this column about 265dp, and five items at the
        // horizontal rail's spacing want more than that — so `SEND` was simply not on the
        // screen, which is a destination that does not exist as far as anyone can tell.
        //
        // Two halves to the fix, and both are needed. The items are tighter here than in
        // the bar, which is what makes them fit; and the column scrolls, which is what
        // keeps the last one reachable at a font scale or a window height nobody measured.
        // Giving each item `weight(1f)` instead was the first attempt and is worse: it
        // hands out a share of the height whether or not the content fits in it, so every
        // label was cut through the middle rather than one being missing.
        Column(
            modifier = container.fillMaxHeight().width(96.dp)
                .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(VerticalRailSpacing),
        ) {
            destinations.forEach { (glyph, label, page) ->
                RailItem(
                    glyph,
                    label,
                    current == page,
                    { onNavigate(page) },
                    Modifier.fillMaxWidth(),
                    verticalPadding = VerticalRailItemPadding,
                )
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
    verticalPadding: Dp = 8.dp,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.fitText.secondary
    Column(
        modifier = modifier.clickable(onClick = onClick)
            .padding(vertical = verticalPadding, horizontal = RailItemHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailItemGlyphGap),
    ) {
        Text(glyph, color = color, style = FitFaceType.numeric)
        Text(
            label.uppercase(),
            color = color,
            style = RailLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * [FitFaceType.micro] without its letter spacing, because the rail is the one place in the
 * app where a label's width is a hard five-way split of the screen.
 *
 * `BACKGROUND` is ten characters, and at `micro`'s 1.3sp tracking that is about 67dp
 * against the 64dp a fifth of a 339dp phone leaves it — so it rendered as `BACKGROUN`,
 * clipped mid-word with no ellipsis and nothing in any test. Dropping the tracking costs
 * the longest label 13dp and leaves about 15% headroom; the glyph above it carries the
 * rhythm the tracking was there for. The ellipsis is the honest degradation for whatever
 * a large font scale or a longer translation does next.
 */
private val RailLabel = FitFaceType.micro.copy(letterSpacing = 0.sp)

/** Between two buttons of the selection tray, on both of its rows. */
private val ActionGap = 6.dp

private val RailItemHorizontalPadding = 2.dp

/** The gap between a rail item's glyph and its label. */
private val RailItemGlyphGap = 3.dp

/** Tighter than the bar's, because the vertical rail is the one that runs out of room. */
private val VerticalRailItemPadding = 6.dp
private val VerticalRailSpacing = 2.dp

/**
 * What the vertical rail wants, before it starts scrolling.
 *
 * `FitFaceType.numeric` and [RailLabel] both set a `lineHeight`, so a rail item's height is
 * arithmetic rather than a measurement — which is the only way this module can assert it.
 */
internal fun verticalRailNaturalHeight(destinations: Int = RailDestinationCount): Dp {
    val item = FitFaceType.numeric.lineHeight.value.dp + RailItemGlyphGap +
        RailLabel.lineHeight.value.dp + VerticalRailItemPadding * 2
    return item * destinations + VerticalRailSpacing * (destinations - 1) + RailColumnPadding * 2
}

private val RailColumnPadding = 8.dp

/**
 * What a landscape phone leaves below the editor's header.
 *
 * 1080px at this emulator's 510dpi is 339dp of window; the header and its divider take
 * about 74dp of it. Rounded down, because the number exists to be pessimistic.
 */
internal val LandscapeRailHeight = 260.dp

/** Five: face, widgets, background, styles, send. [railLabelCharacterBudget] assumes it. */
internal const val RailDestinationCount = 5

/**
 * The narrowest window this app lays out for. Below it everything is cramped; at it,
 * nothing may be clipped.
 */
internal val NarrowestSupportedWidth = 320.dp

/**
 * How many characters of a rail label fit before the bar clips it.
 *
 * Pinned by a test in characters rather than pixels, the way `SortChipLayoutTest` pins the
 * library's sort chips and for the same reason: `:feature:editor` cannot measure a
 * composable, and the label is monospace, so a character count is something arithmetic can
 * decide and a device can be checked against.
 *
 * This is not decoration. The rail splits its width five equal ways and the labels are the
 * page titles, so a title one character too long is clipped mid-word on every phone, with
 * no ellipsis, no warning and nothing in any test — which is what `BACKGROUND` did, as
 * `BACKGROUN`, for as long as the rail existed.
 */
internal fun railLabelCharacterBudget(
    railWidth: Dp = NarrowestSupportedWidth,
    destinations: Int = RailDestinationCount,
): Int {
    val usable = railWidth / destinations - RailItemHorizontalPadding * 2
    val perCharacter = RailLabel.fontSize.value * MonospaceAdvanceRatio +
        RailLabel.letterSpacing.value
    return (usable.value / perCharacter).toInt()
}

/**
 * The advance of one glyph in Android's monospace face, as a fraction of the font size.
 *
 * Measured rather than assumed: `STYLES` occupies 129 of face 00046's rail on a 510dpi
 * emulator, which is 6.74dp a character at `micro`'s 9.5sp and 1.3sp tracking — 0.573 of
 * the size once the tracking is taken out. Rounded up to be safe, because the cost of
 * being wrong in this direction is a clipped label and in the other is a shorter one.
 */
private const val MonospaceAdvanceRatio = 0.6f

@Composable
private fun EditorPageContent(
    page: EditorPage,
    state: EditorUiState,
    sideCanvasVisible: Boolean,
    snapshot: EditorSnapshot,
    selected: WidgetGuide?,
    onNavigate: (EditorPage) -> Unit,
    onVariant: (EditorVariant) -> Unit,
    onWidget: (Int?) -> Unit,
    selection: SelectionActions,
    onMoveWidget: (Int, Int, Int) -> Unit,
    onNudgeWidget: (Int, Int, Int) -> Unit,
    onRemoveWidget: () -> Unit,
    onDuplicateWidget: () -> Unit,
    onRestoreWidget: (Long) -> Unit,
    onImportWidget: () -> Unit,
    onImportBackground: () -> Unit,
    onManageStyles: () -> Unit,
    onDeleteStyle: (String) -> Unit,
    onResizeWidget: (Boolean) -> Unit,
    onWidgetColor: (Int?) -> Unit,
    onRotateWidget: (Int) -> Unit,
    onArrangeWidget: (dev.fitface.studio.core.model.WidgetArrangement) -> Unit,
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
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
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
            onResizeWidget, onRotateWidget, onDuplicateWidget, onRemoveWidget,
            { onNavigate(EditorPage.Widgets) }, { onNavigate(EditorPage.Inspector) },
            selection, modifier,
        )
        EditorPage.Widgets -> WidgetsWorkspace(
            snapshot = snapshot,
            selected = selected,
            multiSelection = state.multiSelection,
            enabled = !state.isWorking,
            // The list is a picker and the face is the editor. It used to open the
            // inspector, which is the page of *facts* — so picking a widget from a list
            // put you two taps from moving it and showed you its type and sequence id
            // instead. Every edit is on the face now, so that is where a pick lands —
            // unless a set is being built, when a tap changes the set and stays here, the
            // way the import picker's list does.
            onSelect = { widget ->
                val building = state.multiSelection.isNotEmpty()
                onWidget(widget.globalIndex)
                if (!building) onNavigate(EditorPage.Canvas)
            },
            onHold = { selection.onBegin(it.globalIndex) },
            onClearSet = selection.onClear,
            onEditSet = { onNavigate(EditorPage.Canvas) },
            onRestore = onRestoreWidget,
            onImport = onImportWidget,
            modifier = modifier,
        )
        EditorPage.Inspector -> InspectorWorkspace(
            state, snapshot, selected, onNudgeWidget, onApplyAll, onRemoveWidget,
            onDuplicateWidget, onResizeWidget, onWidgetColor, onRotateWidget, onArrangeWidget, modifier,
        )
        EditorPage.Background -> BackgroundWorkspace(
            state, snapshot, onWidget, onMoveWidget, onTransformImage, onStepImageZoom, onFit,
            onChooseImage, onImportBackground, onResetImagePlacement, onDiscardImage, onApplyImage, onTintCyan,
            onTintMagenta, onManageStyles, modifier,
        )
        EditorPage.Styles -> StylesWorkspace(
            snapshot, !state.isWorking, state.isWorking, onVariant, onSyncThumbnail, onDeleteStyle, modifier,
        )
        EditorPage.Install -> InstallWorkspace(
            snapshot = snapshot,
            state = state,
            showFace = !sideCanvasVisible,
            onReviewed = onPreviewReviewed,
            onReset = onReset,
            onGrantNearby = onGrantNearby,
            onOpenCompanion = onOpenCompanion,
            onInitializeAndDiscover = onInitializeAndDiscover,
            onOpenPluginSettings = onOpenPluginSettings,
            onConfirmPluginReleased = onConfirmPluginReleased,
            onRediscover = onRediscover,
            onSendBin = onSendBin,
            onResetDelivery = onResetDelivery,
            onCanvas = { onNavigate(EditorPage.Canvas) },
            modifier = modifier,
        )
        EditorPage.Project ->
            ProjectWorkspace(
                snapshot, !state.isWorking, onReset, onRename, onDelete, onDuplicate, modifier,
            )
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
    onResizeWidget: (Boolean) -> Unit,
    onRotateWidget: (Int) -> Unit,
    onDuplicateWidget: () -> Unit,
    onRemoveWidget: () -> Unit,
    onOpenWidgets: () -> Unit,
    onInspect: () -> Unit,
    selection: SelectionActions,
    modifier: Modifier = Modifier,
) {
    val picks = state.multiSelection.mapNotNull { index ->
        snapshot.widgets.firstOrNull { it.globalIndex == index }
    }
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
                selection.onBegin, faceModifier,
            )
        }
        val actions: @Composable (WidgetGuide) -> Unit = { widget ->
            SelectionActionBar(
                widget = widget,
                state = state,
                snapshot = snapshot,
                onNudgeWidget = onNudgeWidget,
                onResize = onResizeWidget,
                onRotate = onRotateWidget,
                onDuplicate = onDuplicateWidget,
                onRemove = onRemoveWidget,
                onInspect = onInspect,
            )
        }
        val tray: @Composable () -> Unit = {
            when {
                picks.isNotEmpty() -> SelectionSetBar(picks, state, snapshot, selection)
                selected != null -> actions(selected)
            }
        }
        if (canvasPageSplits(maxWidth, maxHeight)) {
            Row(Modifier.fillMaxSize()) {
                face(Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CanvasHint(state, selected, picks.size, onOpenWidgets)
                    tray()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                face(Modifier.weight(1f).fillMaxWidth())
                CanvasHint(state, selected, picks.size, onOpenWidgets)
                tray()
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
    onHoldWidget: (Int) -> Unit,
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
            multiSelection = state.multiSelection,
            pendingSetMove = state.pendingSetMove,
            onHoldWidget = onHoldWidget,
        )
    }
}

@Composable
private fun CanvasHint(
    state: EditorUiState,
    selected: WidgetGuide?,
    /** How many widgets are selected together, or zero. */
    setSize: Int,
    onOpenWidgets: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(
                when {
                    setSize > 0 -> R.string.editor_canvas_hint_set
                    selected != null && selected.placement != WidgetPlacement.CANVAS &&
                        state.snapshot?.isAodSelected == true -> R.string.editor_canvas_nudge_aod
                    selected != null && selected.placement != WidgetPlacement.CANVAS &&
                        state.editsReachOtherStyles -> R.string.editor_canvas_nudge_all_styles
                    selected != null && selected.placement != WidgetPlacement.CANVAS ->
                        R.string.editor_canvas_nudge_this_style
                    selected != null && state.snapshot?.isAodSelected == true ->
                        R.string.editor_canvas_hint_aod
                    selected != null && state.editsReachOtherStyles ->
                        R.string.editor_canvas_hint_all_styles
                    selected != null -> R.string.editor_canvas_hint_this_style
                    // What the render leaves out, said where the reader is looking at it
                    // rather than left to a flag nothing shows.
                    state.snapshot?.selectedVariantApproximate == true ->
                        R.string.editor_canvas_hint_none_approximate
                    else -> R.string.editor_canvas_hint_none
                },
            ),
            modifier = Modifier.widthIn(max = 300.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected == null && setSize == 0) {
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

/** The watch panel, 256×402, as a width-over-height ratio. */
private const val PanelAspectRatio = 256f / 402f

/**
 * The plate on the install card, sized to the four lines of text beside it.
 *
 * At 104dp it stood 163dp tall against about 90dp of text and the card carried 50dp of
 * nothing under the plugin line. Driving the height off the text instead (`IntrinsicSize`)
 * did not fix it — the plate's own bitmap answers the intrinsic query, so it went on
 * setting the height — and sizing the plate *down* to 90dp of text leaves it barely wider
 * than the 44dp stamp this replaced. 80dp is the compromise: 126dp tall against ~90dp of
 * text, with the column centred so what is left reads as the card's padding rather than as
 * a hole under the last line.
 */
private val InstallPlateWidth = 80.dp
private val InstallPlateHeight = InstallPlateWidth / PanelAspectRatio

/**
 * Everything a widget edit is, under the face that is being edited.
 *
 * This was a readout, four arrows and an `Inspect ›` button. Five of the six edits lived
 * two screens away, behind a tap, a page turn and a scroll past the record's *facts* — so
 * making a widget larger cost three taps and a scroll, and removing one cost four taps and
 * two scrolls. The complaint was that editing a widget is what this editor is for and it
 * was the hardest thing in it to reach.
 *
 * Now: move, resize, rotate, duplicate and remove are one tap each, and everything that is
 * set rather than stepped — colour, layer order, the all-styles switch, an exact angle —
 * is in Edit widget behind `›`, where it has the room to say what it does. The scope every
 * button here obeys stays visible in `CanvasHint`, directly above the tray.
 */
@Composable
private fun SelectionActionBar(
    widget: WidgetGuide,
    state: EditorUiState,
    snapshot: EditorSnapshot,
    onNudgeWidget: (Int, Int, Int) -> Unit,
    onResize: (Boolean) -> Unit,
    onRotate: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onInspect: () -> Unit,
) {
    var confirmRemoval by rememberSaveable(widget.globalIndex) { mutableStateOf(false) }
    if (confirmRemoval) {
        RemoveWidgetDialog(
            widget = widget,
            state = state,
            snapshot = snapshot,
            onConfirm = { confirmRemoval = false; onRemove() },
            onDismiss = { confirmRemoval = false },
        )
    }
    val enabled = !state.isWorking
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Nothing to the right of the title: the actions are all on the row below, and a
        // second row of icons up here was three more things to learn for one widget.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.primary))
            Text(
                widget.readingLabel(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            // The rectangle the canvas outlines, not the stored endpoint. Reading
            // the stored coordinate here reported a far-end Rule's far endpoint —
            // a whole width away from the edge being nudged.
            stringResource(
                R.string.editor_selection_facts,
                widget.globalIndex,
                widget.width,
                widget.height,
                widget.drawLeft,
                widget.drawTop,
            ),
            color = MaterialTheme.colorScheme.primary,
            style = FitFaceType.numeric,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            NudgeDirection.entries.forEach { direction ->
                RepeatingNudgeButton(
                    direction = direction,
                    enabled = widget.canEditPosition && enabled,
                    modifier = Modifier.weight(1f),
                    onStep = { pixels ->
                        onNudgeWidget(
                            widget.globalIndex,
                            direction.deltaX * pixels,
                            direction.deltaY * pixels,
                        )
                    },
                )
            }
        }
        // One row, every button taking an equal share of it — the same shape as the nudge
        // row above, so the tray reads as one control surface rather than a cluster with a
        // hole in it. The pairs that do one job sit together: smaller and larger, then
        // rotate left and right, then the two that make or remove a widget.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(ActionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `−` `+` `⧉` `✕` — picked off a rendered sampler rather than a character
            // table, because a glyph this app cannot draw is a blank button. Two that read
            // well on paper were rejected there: `❐` fell back to a plain square, losing
            // the "two sheets" that says duplicate, and `␡` came out as tiny DEL letters.
            FitIconButton(
                glyph = "−",
                contentDescription = stringResource(R.string.editor_action_smaller_a11y),
                onClick = { onResize(false) },
                modifier = Modifier.weight(1f),
                enabled = enabled && widget.canResize &&
                    nextWidgetSize(widget, grow = false) != null,
            )
            FitIconButton(
                glyph = "+",
                contentDescription = stringResource(R.string.editor_action_larger_a11y),
                onClick = { onResize(true) },
                modifier = Modifier.weight(1f),
                enabled = enabled && widget.canResize &&
                    nextWidgetSize(widget, grow = true) != null,
            )
            // Disabled rather than hidden on a widget with no angle, like resize: Edit
            // widget says why, and a button that vanishes leaves the reader guessing.
            RotationStepButton(widget, clockwise = false, enabled, onRotate, Modifier.weight(1f))
            RotationStepButton(widget, clockwise = true, enabled, onRotate, Modifier.weight(1f))
            FitIconButton(
                glyph = "⧉",
                contentDescription = stringResource(R.string.editor_duplicate),
                onClick = onDuplicate,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
            FitIconButton(
                glyph = "✕",
                contentDescription = stringResource(R.string.editor_remove_widget),
                onClick = { confirmRemoval = true },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                tint = MaterialTheme.colorScheme.error,
            )
            // Last, so its right edge is pinned to the card's padding: an action that is
            // always present must not slide sideways when a neighbour's state changes.
            // `›` rather than a second `⋯` — the canvas header already carries one, and
            // two ellipses on one screen read as one control with two behaviours.
            FitIconButton(
                glyph = "›",
                contentDescription = stringResource(R.string.editor_action_record_details),
                onClick = onInspect,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
        MicroLabel(
            stringResource(
                when {
                    !widget.canEditPosition -> R.string.editor_nudge_locked
                    widget.placement == WidgetPlacement.CANVAS -> R.string.editor_nudge_hint
                    else -> R.string.editor_nudge_hint_no_drag
                },
            ),
            Modifier.align(Alignment.CenterHorizontally).padding(top = 9.dp),
        )
    }
}

/**
 * Which styles the set tray's buttons reach — a label, not a control.
 *
 * The switch moved to Edit widget, but a set's nudges, duplicates and removals still follow
 * it, and the canvas hint above a set explains picking rather than scope — so the tray says
 * whether a tap changes one style or all of them. Nothing on a one-style face, where there
 * is no other style to reach. An imported widget stays in the style it was added to.
 */
@Composable
private fun TrayScopeLabel(state: EditorUiState, snapshot: EditorSnapshot, imported: Boolean) {
    if (!snapshot.isAodSelected && snapshot.styleNames.size <= 1) return
    val all = state.editsReachOtherStyles && !imported
    MicroLabel(
        if (all) {
            stringResource(R.string.editor_tray_scope_all)
        } else {
            stringResource(R.string.editor_tray_scope_one, snapshot.selectedVariantLabel())
        },
        Modifier.padding(start = 8.dp),
        color = if (all) MaterialTheme.colorScheme.primary else null,
    )
}

/**
 * Removal's confirmation, shared by the action bar and the inspector.
 *
 * Both offer the same edit now, and a second copy of this dialog is a second place for the
 * "all styles" wording to go stale — which is the sentence that tells someone whether they
 * are about to change one style or four.
 */
@Composable
private fun RemoveWidgetDialog(
    widget: WidgetGuide,
    state: EditorUiState,
    snapshot: EditorSnapshot,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val importedFrom = widget.importedFromFaceId
    // A copy of a stock widget is deleted outright as well; its original stays to copy again.
    val copy = importedFrom == null && widget.duplicateSourceGlobalIndex != null
    val deletes = importedFrom != null || copy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (deletes) R.string.editor_delete_title else R.string.editor_remove_title,
                    widget.globalIndex,
                ),
            )
        },
        text = {
            Text(
                if (importedFrom != null) {
                    // It is not coming back from a list, so the dialog says so, and says
                    // where it can be had again.
                    stringResource(R.string.editor_delete_body_imported, importedFrom)
                } else if (copy) {
                    stringResource(R.string.editor_delete_body_duplicate)
                } else if (state.editsReachOtherStyles) {
                    stringResource(R.string.editor_remove_body_all_styles)
                } else {
                    stringResource(
                        R.string.editor_remove_body_one_style,
                        snapshot.selectedVariantLabel(),
                    )
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (deletes) R.string.editor_delete_confirm else R.string.editor_remove_confirm,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

/**
 * Whether a widget edit reaches styles other than the one on the canvas: the switch is on,
 * a numbered style is showing, and there is another one to reach.
 *
 * The flag alone is not the answer. On a face with one style — a custom face — it can be on
 * and govern nothing, and a hint or a dialog that says "every style that has this widget"
 * there is describing styles that do not exist.
 */
private val EditorUiState.editsReachOtherStyles: Boolean
    get() = applyWidgetEditsToAllStyles &&
        snapshot?.let { !it.isAodSelected && it.styleNames.size > 1 } == true

/**
 * The actions a set of widgets takes, bundled so they travel through the page plumbing as
 * one argument rather than five more on every signature between the route and the tray.
 */
internal class SelectionActions(
    val onBegin: (Int) -> Unit,
    val onClear: () -> Unit,
    val onNudge: (Int, Int) -> Unit,
    val onDuplicate: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * The tray for several widgets at once.
 *
 * The single tray's shape, row for row, so the face above does not change size when a set
 * starts: the same header line, the same four arrows, the same seven buttons. The ones a set
 * cannot do — resize, rotate, Edit widget — are there and disabled, the rule every
 * control on this tray follows, so the reader sees that a set cannot do them rather than
 * wondering where they went.
 *
 * Every action is the single-widget one, run once per member: moves through the nudge
 * queue, duplicates and removals through the repository's own calls.
 */
@Composable
private fun SelectionSetBar(
    picks: List<WidgetGuide>,
    state: EditorUiState,
    snapshot: EditorSnapshot,
    selection: SelectionActions,
) {
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    if (confirmRemoval) {
        RemoveSetDialog(
            picks = picks,
            state = state,
            snapshot = snapshot,
            onConfirm = { confirmRemoval = false; selection.onRemove() },
            onDismiss = { confirmRemoval = false },
        )
    }
    val enabled = !state.isWorking
    val movable = picks.any { it.canEditPosition }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.tertiary))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.editor_selection_set_title, picks.size),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // In pick order, the numbers on the face: which widgets, not how big.
                        picks.joinToString(" · ") { "#${it.globalIndex}" },
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary,
                        style = FitFaceType.numeric,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Stock members follow the switch; an all-imported set cannot.
                    TrayScopeLabel(state, snapshot, imported = picks.all { it.importedFromFaceId != null })
                }
            }
            Text(
                stringResource(R.string.editor_selection_clear),
                modifier = Modifier.clip(MaterialTheme.shapes.small)
                    .clickable(enabled = enabled, onClick = selection.onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            NudgeDirection.entries.forEach { direction ->
                RepeatingNudgeButton(
                    direction = direction,
                    enabled = movable && enabled,
                    modifier = Modifier.weight(1f),
                    onStep = { pixels ->
                        selection.onNudge(direction.deltaX * pixels, direction.deltaY * pixels)
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(ActionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FitIconButton(
                glyph = "−",
                contentDescription = stringResource(R.string.editor_action_smaller_a11y),
                onClick = {},
                modifier = Modifier.weight(1f),
                enabled = false,
            )
            FitIconButton(
                glyph = "+",
                contentDescription = stringResource(R.string.editor_action_larger_a11y),
                onClick = {},
                modifier = Modifier.weight(1f),
                enabled = false,
            )
            // Each member turns about its own centre, which is not turning the set.
            RotationStepButton(null, clockwise = false, enabled = false, onRotate = {}, Modifier.weight(1f))
            RotationStepButton(null, clockwise = true, enabled = false, onRotate = {}, Modifier.weight(1f))
            FitIconButton(
                glyph = "⧉",
                contentDescription = if (picks.size == 1) stringResource(R.string.editor_duplicate)
                    else stringResource(R.string.editor_selection_duplicate_a11y, picks.size),
                onClick = selection.onDuplicate,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
            FitIconButton(
                glyph = "✕",
                contentDescription = if (picks.size == 1) stringResource(R.string.editor_remove_widget)
                    else stringResource(R.string.editor_selection_remove_a11y, picks.size),
                onClick = { confirmRemoval = true },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                tint = MaterialTheme.colorScheme.error,
            )
            FitIconButton(
                glyph = "›",
                contentDescription = stringResource(R.string.editor_action_record_details),
                onClick = {},
                modifier = Modifier.weight(1f),
                enabled = false,
            )
        }
        MicroLabel(
            stringResource(if (movable) R.string.editor_nudge_hint_set else R.string.editor_nudge_locked),
            Modifier.align(Alignment.CenterHorizontally).padding(top = 9.dp),
        )
    }
}

/**
 * Confirms removing a set, and says what becomes of each kind of member.
 *
 * The two kinds end differently now: a stock widget goes to the Removed list and can be
 * put back, and one imported from another face is deleted with its artwork. A set can hold
 * both, so the dialog counts each.
 */
@Composable
private fun RemoveSetDialog(
    picks: List<WidgetGuide>,
    state: EditorUiState,
    snapshot: EditorSnapshot,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val imported = picks.count { it.importedFromFaceId != null }
    val copies = picks.count { it.importedFromFaceId == null && it.duplicateSourceGlobalIndex != null }
    val stock = picks.size - imported - copies
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (picks.size == 1) stringResource(R.string.editor_remove_title, picks.single().globalIndex)
            else stringResource(R.string.editor_remove_set_title, picks.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stock > 0) {
                    Text(
                        if (state.editsReachOtherStyles) {
                            pluralStringResource(R.plurals.editor_remove_set_stock_all, stock, stock)
                        } else {
                            pluralStringResource(R.plurals.editor_remove_set_stock_one, stock, stock,
                                snapshot.selectedVariantLabel())
                        },
                    )
                }
                if (copies > 0) {
                    Text(pluralStringResource(R.plurals.editor_remove_set_duplicate, copies, copies))
                }
                if (imported > 0) {
                    Text(pluralStringResource(R.plurals.editor_remove_set_imported, imported, imported))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.editor_remove_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

/**
 * [degrees] turns [NudgeArrow]'s right-pointing arrow to face the way the step moves.
 */
private enum class NudgeDirection(
    val deltaX: Int,
    val deltaY: Int,
    val degrees: Float,
    @StringRes val description: Int,
) {
    LEFT(-1, 0, 180f, R.string.editor_nudge_left_a11y),
    UP(0, -1, 270f, R.string.editor_nudge_up_a11y),
    DOWN(0, 1, 90f, R.string.editor_nudge_down_a11y),
    RIGHT(1, 0, 0f, R.string.editor_nudge_right_a11y),
}

/**
 * One arrow, turned to face [direction].
 *
 * The four used to be text — `←` `↑` `↓` `→` — and a font draws those as two pairs, not one
 * shape four ways: the horizontal ones sit on the maths axis and the vertical ones span the
 * cap height. On the emulator `←` and `→` rode 4px lower than `↑` and `↓`, all four sat
 * below their buttons' centres, and a phone whose font takes them from a fallback can size
 * the pairs differently as well. One path rotated about the middle of its own box is the
 * same size in every direction and centred by construction.
 */
@Composable
private fun NudgeArrow(direction: NudgeDirection) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(NudgeArrowSize)) {
        val stroke = NudgeArrowStroke.toPx()
        val reach = size.minDimension / 2 - stroke / 2
        val head = reach * .6f
        val tip = Offset(center.x + reach, center.y)
        val arrow = Path().apply {
            moveTo(center.x - reach, center.y)
            lineTo(tip.x, tip.y)
            moveTo(tip.x - head, tip.y - head)
            lineTo(tip.x, tip.y)
            lineTo(tip.x - head, tip.y + head)
        }
        rotate(direction.degrees) {
            drawPath(arrow, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private val NudgeArrowSize = 15.dp
private val NudgeArrowStroke = 1.6.dp

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
    direction: NudgeDirection,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onStep: (Int) -> Unit,
) {
    val description = stringResource(direction.description)
    RepeatingButton(
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description },
        onStep = onStep,
    ) {
        NudgeArrow(direction)
    }
}

@Composable
private fun RepeatingNudgeButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onStep: (Int) -> Unit,
) {
    RepeatingButton(enabled, modifier, onStep) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RepeatingButton(
    enabled: Boolean,
    modifier: Modifier,
    onStep: (Int) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    val steppedWhilePressed = remember { mutableStateOf(false) }

    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        // The first step is always the one the label promises, however long the press
        // turns out to be: a hold that began as a tap must not overshoot.
        step(1)
        steppedWhilePressed.value = true
        delay(FirstRepeatDelayMillis)
        var interval = RepeatIntervalMillis
        var repeat = 0
        while (true) {
            step(nudgeStepPixels(repeat))
            repeat++
            delay(interval)
            // The repeat rate accelerates too, but never faster than the editor can
            // commit an edit. Rate alone was not enough: at one pixel a tick, the far
            // side of a 402px panel is still half a minute away.
            interval = (interval - 8).coerceAtLeast(MinRepeatIntervalMillis)
        }
    }

    FitButton(
        onClick = {
            if (!steppedWhilePressed.value) step(1)
            steppedWhilePressed.value = false
        },
        modifier = modifier,
        enabled = enabled,
        style = FitButtonStyle.Secondary,
        interactionSource = interactions,
        // The tray takes its height out of the face above it, and four arrows at the full
        // 46dp were the tallest thing in it. They are still ~70dp wide, so the target only
        // shrinks on the axis that had room to spare.
        compact = true,
        content = content,
    )
}

/**
 * How far one tick of a held nudge moves, on its [repeat]-th repetition.
 *
 * A held button that never coarsens is the complaint this answers: one pixel a tick across
 * a 256×402 panel is hundreds of ticks, and the rate alone cannot close that without
 * becoming unusable for the single pixel the control exists to give. So a hold starts at
 * the pixel the label promises and widens twice, about a second and about two and a half
 * seconds in — long enough that placing a widget precisely never reaches the coarse steps,
 * short enough that crossing the face does.
 *
 * Pure so the curve is pinned by a test: `:feature:editor` cannot measure a composable, and
 * "it feels about right" is not a regression test.
 */
internal fun nudgeStepPixels(repeat: Int): Int = when {
    repeat >= CoarseNudgeRepeat -> CoarseNudgePixels
    repeat >= MediumNudgeRepeat -> MediumNudgePixels
    else -> 1
}

private const val FirstRepeatDelayMillis = 420L

/** Repetitions before a held nudge widens its step, and how far it widens it. */
private const val MediumNudgeRepeat = 10
private const val CoarseNudgeRepeat = 24
private const val MediumNudgePixels = 2
private const val CoarseNudgePixels = 5
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
                multiSelection = state.multiSelection.takeIf { editable }.orEmpty(),
            )
        }
        Text(
            snapshot.selectedVariantLabel(),
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
    /** The set, in pick order — numbered here as the canvas numbers it. */
    multiSelection: List<Int>,
    enabled: Boolean,
    onSelect: (WidgetGuide) -> Unit,
    /** Holding a row adds it to the set, as holding a widget on the canvas does. */
    onHold: (WidgetGuide) -> Unit,
    onClearSet: () -> Unit,
    onEditSet: () -> Unit,
    onRestore: (Long) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onCanvas = snapshot.canvasWidgets
    val offCanvas = snapshot.offCanvasWidgets
    val picks = multiSelection.mapNotNull { index -> snapshot.widgets.firstOrNull { it.globalIndex == index } }
    fun order(widget: WidgetGuide) = multiSelection.indexOf(widget.globalIndex).takeIf { it >= 0 }
    // Removals used to be the third section of this list, below every widget on the face
    // and every widget off it — so putting one back meant scrolling past two dozen rows to
    // find out whether there was anything to put back at all. The count is at the top now
    // and opens in place.
    var showRemoved by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FitButton(stringResource(R.string.widget_import_open), onImport, Modifier.fillMaxWidth(),
                    enabled = enabled, style = FitButtonStyle.Secondary)
            }
            if (snapshot.removedWidgets.isNotEmpty()) {
                item {
                    RemovedWidgetsToggle(
                        count = snapshot.removedWidgets.size,
                        expanded = showRemoved,
                        onToggle = { showRemoved = !showRemoved },
                    )
                }
                if (showRemoved) {
                    item {
                        Text(
                            stringResource(R.string.editor_widgets_removed_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FitDetails(
                            label = stringResource(R.string.editor_restore_details),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(
                                stringResource(R.string.editor_restore_explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.fitText.secondary,
                            )
                        }
                    }
                    items(snapshot.removedWidgets, key = { "removed-${it.id}" }) { removed ->
                        RemovedWidgetRow(removed, enabled) { onRestore(removed.id) }
                    }
                }
            }
            item {
                SectionHeading(
                    stringResource(R.string.editor_widgets_on_canvas_title),
                )
            }
            item {
                Text(
                    stringResource(
                        if (picks.isEmpty()) R.string.editor_widgets_hint else R.string.editor_widgets_hint_set,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.fitText.secondary,
                )
            }
            items(onCanvas, key = { "canvas-${it.globalIndex}" }) { widget ->
                WidgetRow(
                    widget = widget,
                    snapshot = snapshot,
                    active = selected?.globalIndex == widget.globalIndex || order(widget) != null,
                    pickOrder = order(widget),
                    enabled = enabled,
                    onHold = { onHold(widget) },
                ) { onSelect(widget) }
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
                        active = selected?.globalIndex == widget.globalIndex || order(widget) != null,
                        dimmed = true,
                        pickOrder = order(widget),
                        enabled = enabled,
                        onHold = { onHold(widget) },
                    ) { onSelect(widget) }
                }
            }
            item { Box(Modifier.height(16.dp)) }
        }
        // Pinned under the list, so the set and the way to act on it stay in view however
        // far the list scrolls. The tray that moves, duplicates and removes a set is on the
        // face; this says what is chosen and goes there.
        if (picks.isNotEmpty()) {
            WidgetSetFooter(picks, enabled, onClearSet, onEditSet)
        }
    }
}

@Composable
private fun WidgetSetFooter(
    picks: List<WidgetGuide>,
    enabled: Boolean,
    onClear: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.tertiary))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.editor_selection_set_title, picks.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    picks.joinToString(" · ") { "#${it.globalIndex}" },
                    color = MaterialTheme.colorScheme.primary,
                    style = FitFaceType.numeric,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.editor_selection_clear),
                modifier = Modifier.clip(MaterialTheme.shapes.small)
                    .clickable(enabled = enabled, onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        FitButton(stringResource(R.string.editor_widgets_set_edit), onEdit, Modifier.fillMaxWidth(), enabled = enabled)
    }
}

/**
 * How many widgets are in the removed list, and the tap that opens it.
 *
 * A chip rather than a section heading, because it has to read as something that does
 * something: the list it opens is the only way a removal is undone.
 */
@Composable
private fun RemovedWidgetsToggle(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val action = stringResource(
        if (expanded) R.string.editor_widgets_removed_hide else R.string.editor_widgets_removed_show,
    )
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = action }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MicroLabel(stringResource(R.string.editor_widgets_removed_title), Modifier.weight(1f))
        Text(
            pluralStringResource(R.plurals.editor_widgets_removed_count, count, count),
            style = FitFaceType.numeric,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            if (expanded) "▴" else "▾",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeading(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(bottom = 2.dp)) {
        MicroLabel(title)
        detail?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
    }
}

@Composable
private fun WidgetRow(
    widget: WidgetGuide,
    snapshot: EditorSnapshot,
    active: Boolean,
    dimmed: Boolean = false,
    /** Where this widget sits in the set, when it is in one. */
    pickOrder: Int? = null,
    enabled: Boolean = true,
    onHold: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
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
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.editor_select_multiple),
                onLongClick = onHold?.let { hold ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        hold()
                    }
                },
            )
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.TopStart) {
            WidgetThumbnail(
                snapshot = snapshot,
                widget = widget,
                accent = if (dimmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            pickOrder?.let { PickBadge(it + 1) }
        }
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    stringResource(R.string.editor_selection_widget, widget.globalIndex),
                    style = MaterialTheme.typography.titleSmall,
                )
                MicroLabel(widget.category.label, color = MaterialTheme.colorScheme.tertiary)
            }
            FlowRow(
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
                    widget.readingLabel(),
                    widget.width,
                    widget.height,
                ),
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.secondary,
            )
            Text(
                stringResource(R.string.editor_row_position, widget.drawLeft, widget.drawTop),
                style = FitFaceType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onHold != null) {
            FitIconButton(
                glyph = if (pickOrder == null) "+" else "✓",
                contentDescription = stringResource(
                    if (pickOrder == null) R.string.editor_add_to_selection else R.string.editor_remove_from_selection,
                    widget.globalIndex,
                ),
                onClick = if (pickOrder == null) onHold else onClick,
                enabled = enabled,
            )
        } else {
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The isolated resource layer, never a crop that can include the background or a neighbour. */
@Composable
private fun WidgetThumbnail(
    snapshot: EditorSnapshot,
    widget: WidgetGuide,
    accent: Color,
    extent: Dp = 34.dp,
) {
    val artwork = remember(
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
                it.globalIndex == widget.globalIndex
            }
            ?.frame
    }
    if (artwork == null) {
        Box(Modifier.width(3.dp).height(28.dp).background(accent))
        return
    }
    val bitmap = artwork.rememberBitmap()
    Canvas(
        Modifier.size(extent)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp)),
    ) {
        // Letterboxed, never stretched: a 242x4 divider has to stay readable as one.
        val scale = minOf(size.width / artwork.width, size.height / artwork.height)
        val drawWidth = (artwork.width * scale).coerceAtLeast(1f)
        val drawHeight = (artwork.height * scale).coerceAtLeast(1f)
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

/**
 * What this widget follows, in words where the format names it.
 *
 * The number is not a label. It selects one of the watch's own readings, and where the
 * evidence does not name one — reading 70, and the hour offsets — it is shown as itself
 * rather than given an invented name.
 */
@Composable
private fun WidgetGuide.readingLabel(): String = when (val reading = sourceLabel) {
    is String -> reading
    else -> if (followsReading) {
        stringResource(R.string.editor_widget_reading_unnamed, sequenceId)
    } else {
        // A widget that follows no reading of its own is better identified by its place
        // in the table than by a number that means nothing on it.
        stringResource(R.string.editor_widget_index, globalIndex)
    }
}

/**
 * The reading a removed record followed, named the way a live row names it.
 *
 * The same three cases as [WidgetGuide.readingLabel] and deliberately worded identically:
 * the Removed list sits directly under the live one, and a record that read "Sprite ·
 * steps" before it was cut must not read "type 4 · seq 29" after.
 */
@Composable
private fun RemovedWidget.removedReadingLabel(): String = when (val reading = sourceLabel) {
    is String -> reading
    else -> if (followsReading) {
        stringResource(R.string.editor_widget_reading_unnamed, sequenceId)
    } else {
        stringResource(R.string.editor_widget_index, globalIndex)
    }
}

/**
 * What the removal reached: some number of numbered styles, or the always-on display.
 *
 * Not `recordsByVariant.size`, which counts *entries* — an always-on removal writes one
 * entry that is not a style, and it used to be reported as "1 styles". AOD is never joined
 * to a style edit, so the two cases do not overlap.
 */
@Composable
private fun RemovedWidget.removedScopeLabel(): String =
    if (touchedAod) {
        stringResource(R.string.editor_aod_label)
    } else {
        pluralStringResource(R.plurals.editor_row_removed_styles, styleCount, styleCount)
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
            Text(
                stringResource(
                    R.string.editor_selection_widget_or_unknown,
                    removed.globalIndex.takeIf { it >= 0 }?.toString()
                        ?: stringResource(R.string.editor_widget_index_unknown),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    R.string.editor_row_removed,
                    removed.category.label,
                    removed.removedReadingLabel(),
                    removed.width,
                    removed.height,
                    removed.removedScopeLabel(),
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
    onColor: (Int?) -> Unit,
    onRotate: (Int) -> Unit,
    onArrange: (dev.fitface.studio.core.model.WidgetArrangement) -> Unit,
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
    val displayX = widget.drawLeft
    val displayY = widget.drawTop
    var confirmRemoval by rememberSaveable(widget.globalIndex) { mutableStateOf(false) }
    if (confirmRemoval) {
        RemoveWidgetDialog(
            widget = widget,
            state = state,
            snapshot = snapshot,
            onConfirm = { confirmRemoval = false; onRemove() },
            onDismiss = { confirmRemoval = false },
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
        // Edits first, in the order they are reached for, and the record's facts after
        // them: this page is where a widget is edited, not only where it is described.
        Column {
            MicroLabel(stringResource(R.string.editor_position_heading))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CoordinateControl(
                    stringResource(R.string.editor_axis_x), displayX, widget.canEditPosition,
                    NudgeDirection.LEFT, NudgeDirection.RIGHT,
                    { pixels -> onNudgeWidget(widget.globalIndex, -pixels, 0) },
                    { pixels -> onNudgeWidget(widget.globalIndex, pixels, 0) },
                    Modifier.weight(1f),
                )
                CoordinateControl(
                    stringResource(R.string.editor_axis_y), displayY, widget.canEditPosition,
                    NudgeDirection.UP, NudgeDirection.DOWN,
                    { pixels -> onNudgeWidget(widget.globalIndex, 0, -pixels) },
                    { pixels -> onNudgeWidget(widget.globalIndex, 0, pixels) },
                    Modifier.weight(1f),
                )
            }
            Text(
                // What the file holds, and what it is measured from. The stored value is
                // only the same as the display position when the widget is placed against
                // the face itself; when it is placed against another widget, the numbers
                // differ and the reader needs to know why. This line used to read
                // "anchored from end" whenever a coordinate was negative, which was the
                // old guess about what a negative value meant.
                widget.alignedToGlobalIndex.let { anchor ->
                    when {
                        widget.originX == 0 && widget.originY == 0 -> stringResource(
                            R.string.editor_encoded,
                            widget.x,
                            widget.y,
                        )
                        anchor != null -> stringResource(
                            R.string.editor_encoded_offset,
                            widget.x,
                            widget.y,
                            anchor,
                        )
                        else -> stringResource(
                            R.string.editor_encoded_offset_panel,
                            widget.x,
                            widget.y,
                        )
                    }
                },
                modifier = Modifier.padding(top = 9.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.secondary,
            )
        }
        WidgetSizeControls(widget, !state.isWorking, onResize)
        RotationControls(widget, snapshot, state.applyWidgetEditsToAllStyles, !state.isWorking, onRotate)
        ColorControls(widget, snapshot, state.applyWidgetEditsToAllStyles, !state.isWorking, onColor)
        ArrangementControls(widget, snapshot, !state.isWorking, onArrange)
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
            // Only when the banner at the top of this page is not already showing it. A
            // widget with no outline — a clock hand, anything off-canvas — puts its
            // support message in that banner, and printing it again a few hundred dp
            // below was the same sentence twice on one screen.
            if (widget.placement == WidgetPlacement.CANVAS) {
                Text(
                    widget.supportMessage,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.fitText.secondary,
                )
            }
            widget.duplicateSourceGlobalIndex?.let { source ->
                MicroLabel(
                    stringResource(R.string.editor_duplicate_of, source),
                    Modifier.padding(top = 10.dp),
                    MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        // The scope sits directly above Duplicate and Remove, where it was before 0.2.0's
        // reordering: it governs those two as much as the edits above, and rotation and
        // colour each repeat it in their own "Applies to" line.
        if (widget.importedFromFaceId != null) {
            Text(stringResource(R.string.widget_import_scope, widget.importedFromFaceId.orEmpty()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.fitText.secondary)
        } else if (snapshot.isAodSelected) {
            Text(
                stringResource(R.string.editor_apply_all_aod),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        } else if (snapshot.styleNames.size == 1) {
            // A switch with nothing on its other side is a control that does nothing.
            Text(
                stringResource(R.string.editor_apply_all_single),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        } else {
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
                            pluralStringResource(
                                R.plurals.editor_apply_all_on,
                                snapshot.styleNames.size,
                                snapshot.styleNames.size,
                            )
                        } else {
                            stringResource(
                                R.string.editor_apply_all_off,
                                snapshot.selectedVariantLabel(),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.applyWidgetEditsToAllStyles, onCheckedChange = onApplyAll)
            }
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
            FitDetails(label = stringResource(R.string.editor_duplicate_restore_details)) {
                Text(
                    stringResource(R.string.editor_destructive_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.fitText.secondary,
                )
            }
        }
    }
}

/**
 * Resize is always visible so the capability is discoverable, and says why it is
 * unavailable — which is now three different answers, because resize covers three
 * different mechanisms. A widget whose artwork does not match the shape this app can
 * resample is refused; a panel-sized layer belongs to the Background page; and a Value or
 * a Composite has no size in its record at all, because the watch draws its glyphs from a
 * firmware font. Saying "does not match the proven resize schema" to someone looking at a
 * text widget would be describing a check it never reached.
 *
 * The sizes come from [widgetResizeLadder], so the readout can say which rung the widget
 * is on and each button knows whether there is another one in its direction — a "Larger"
 * that is still lit at the top of the ladder is a button that does nothing.
 */
@Composable
private fun WidgetSizeControls(
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
            val percent = widgetSizePercent(widget)
            val shipped = widget.width == widget.originalWidth &&
                widget.height == widget.originalHeight
            Text(
                when {
                    shipped && widget.importedFromFaceId != null -> stringResource(
                        R.string.widget_import_original_size, WidgetResizeStepPercent,
                    )
                    shipped -> stringResource(
                        R.string.editor_size_shipped,
                        WidgetResizeStepPercent,
                    )
                    percent != null -> stringResource(
                        R.string.editor_size_percent,
                        percent,
                        widget.originalWidth,
                        widget.originalHeight,
                        WidgetResizeStepPercent,
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
                enabled && widget.canResize && nextWidgetSize(widget, grow = false) != null,
                style = FitButtonStyle.Secondary,
            )
            FitButton(
                stringResource(R.string.editor_size_larger),
                { onResize(true) },
                Modifier.weight(1f),
                enabled && widget.canResize && nextWidgetSize(widget, grow = true) != null,
                style = FitButtonStyle.Secondary,
            )
        }
        if (!widget.canResize) {
            Text(
                stringResource(
                    when {
                        widget.placement == WidgetPlacement.BACKGROUND ->
                            R.string.editor_size_unavailable_background
                        widget.category == WidgetCategory.VALUE ||
                            widget.category == WidgetCategory.COMPOSITE ->
                            R.string.editor_size_unavailable_text
                        // A type that names no raster cannot be refused for its artwork,
                        // and neither can one this app will not resize at all. Telling
                        // someone looking at a Rule that its "shared raster set" is the
                        // wrong format describes a check it never reached.
                        widget.category in RecordsWithoutArtwork ->
                            R.string.editor_size_unavailable_geometry
                        else -> R.string.editor_size_unavailable_artwork
                    },
                ),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
    }
}

/** Categories whose records address no raster, so no artwork can be the reason. */
private val RecordsWithoutArtwork = setOf(
    WidgetCategory.RULE,
    WidgetCategory.VECTOR_ARC,
    WidgetCategory.ANIMATION,
    WidgetCategory.GROUP,
    WidgetCategory.RESERVED,
    WidgetCategory.UNKNOWN,
)

/**
 * One axis of the selected widget's position, with the two buttons that change it.
 *
 * The buttons are **arrows**, not a minus and a plus. This control sits under a picture of
 * the face and moves the widget on it: "+" on the Y axis is only obviously *down* if you
 * already know the panel's origin is its top-left corner, which is a thing about the file
 * format rather than about the face. The arrows say which way the widget goes, and they
 * are the same [NudgeArrow]s as the canvas page's own nudge row, so the two controls read
 * as the same control in two places.
 */
@Composable
private fun CoordinateControl(
    label: String,
    value: Int,
    enabled: Boolean,
    decrease: NudgeDirection,
    increase: NudgeDirection,
    onMinus: (Int) -> Unit,
    onPlus: (Int) -> Unit,
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
            RepeatingNudgeButton(decrease, enabled, Modifier.weight(1f), onMinus)
            RepeatingNudgeButton(increase, enabled, Modifier.weight(1f), onPlus)
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
    onImportBackground: () -> Unit,
    onResetPlacement: () -> Unit,
    onDiscard: () -> Unit,
    onApply: () -> Unit,
    onTintCyan: () -> Unit,
    onTintMagenta: () -> Unit,
    onManageStyles: () -> Unit,
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
            if (snapshot.backgroundWouldNotFit) TextButton(onClick = onManageStyles, enabled = !state.isWorking) {
                Text(stringResource(R.string.editor_manage_styles))
            }
            return@Column
        }
        if (adding) {
            AddBackgroundNotice(snapshot)
            if (snapshot.backgroundAddSkipped.isNotEmpty()) TextButton(onClick = onManageStyles, enabled = !state.isWorking) {
                Text(stringResource(R.string.editor_manage_styles))
            }
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
            }
            PartialBackgroundNotice(snapshot)
            FitButton(
                stringResource(R.string.editor_bg_choose),
                onChooseImage,
                Modifier.fillMaxWidth(),
                !state.isWorking,
            )
            FitButton(
                stringResource(R.string.editor_bg_import_open), onImportBackground,
                Modifier.fillMaxWidth(), !state.isWorking, style = FitButtonStyle.Secondary,
            )
            FitDetails(label = stringResource(R.string.editor_image_details)) {
                Text(
                    stringResource(
                        R.string.editor_bg_no_image_detail,
                        snapshot.preview.width,
                        snapshot.preview.height,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            if (snapshot.isAodSelected) {
                stringResource(R.string.editor_bg_adds_aod)
            } else if (skipped.isEmpty()) {
                pluralStringResource(
                    R.plurals.editor_bg_adds_all,
                    snapshot.styleNames.size,
                    snapshot.styleNames.size,
                )
            } else {
                stringResource(
                    R.string.editor_bg_adds_some,
                    variantLabels(snapshot.backgroundAddTargets),
                    mebibytes(WATCH_CONTAINER_BYTE_CEILING),
                    variantLabels(skipped),
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
    // "Partial" is about the style group: some carry a panel raster and some do not. An
    // AOD edit writes one entry, so which styles carry one says nothing about it.
    if (snapshot.isAodSelected) return
    val targets = variantLabels(snapshot.backgroundStyles)
    Text(
        if (snapshot.selectedVariantHasBackground) {
            stringResource(R.string.editor_bg_partial_applies, targets)
        } else {
            stringResource(
                R.string.editor_bg_partial_elsewhere,
                snapshot.selectedVariantLabel(),
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
                    direction = direction,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onStep = { pixels ->
                        onTransformImage(
                            1f,
                            direction.deltaX * horizontalStep * pixels,
                            direction.deltaY * verticalStep * pixels,
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
            // One rung a tick however long the hold runs: zoom steps a ladder of fixed
            // percentages, so a wider step would skip rungs rather than move faster.
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
 * The variants, shown as pictures rather than as a list of names.
 *
 * A name alone says nothing about which colourway you are about to load. The selected
 * variant is drawn from the composed preview, so it carries the edit; an unselected style
 * is the package's own render, which is the only per-style image available without
 * reparsing a container per row, and an unselected always-on display is this app's own
 * render, because the package ships none.
 *
 * One row composable for both kinds, and the order comes from
 * [EditorSnapshot.variants] — the same reason the library's two pages share one. Assembled
 * twice, the AOD row and the style row had already begun to drift apart in what "edited"
 * meant.
 */
@Composable
private fun StylesWorkspace(
    snapshot: EditorSnapshot,
    enabled: Boolean,
    working: Boolean,
    onVariant: (EditorVariant) -> Unit,
    onSyncThumbnail: () -> Unit,
    onDeleteStyle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(snapshot.variants, key = { it.basename }) { variant ->
            val isAod = variant.kind == VariantKind.AOD
            val selected = snapshot.selectedVariant.basename == variant.basename
            // Only a style has a packaged picture; AOD's comes from the composer.
            val imagePath = snapshot.stylePreviewPaths[variant.basename]
            val label = if (isAod) {
                stringResource(R.string.editor_aod_label)
            } else {
                variantLabel(variant.basename)
            }
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
                    .clickable(enabled = enabled) { onVariant(variant) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                FacePreview(
                    frame = if (selected) {
                        snapshot.composedPreview
                    } else if (isAod) {
                        snapshot.aodThumbnail
                    } else {
                        null
                    },
                    imagePath = imagePath.takeIf { !isAod },
                    contentDescription = stringResource(
                        R.string.editor_style_preview_a11y,
                        label,
                    ),
                    modifier = Modifier.width(54.dp).height(85.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            when {
                                // Per variant, not per project: with AOD in the list, "the
                                // project is dirty" says nothing about whether *this* row's
                                // picture carries an edit.
                                selected && snapshot.isSelectedVariantDirty -> if (isAod) {
                                    R.string.editor_aod_current_edited
                                } else {
                                    R.string.editor_style_current_edited
                                }
                                selected -> if (isAod) {
                                    R.string.editor_aod_current
                                } else {
                                    R.string.editor_style_current
                                }
                                isAod -> R.string.editor_aod_tap_to_load
                                imagePath != null -> R.string.editor_style_tap_to_load
                                else -> R.string.editor_style_tap_to_load_no_image
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.fitText.secondary,
                    )
                }
                if (selected) Text("●", color = MaterialTheme.colorScheme.primary)
                // The row's own ✕, as a widget's remove is in the tray: the card is the
                // thing being deleted, and the dialog behind it says what that costs.
                // Disabled on the last style rather than hidden, with the reason below.
                if (!isAod) {
                    FitIconButton(
                        glyph = "✕",
                        contentDescription = stringResource(R.string.editor_style_delete_action, label),
                        onClick = { onDeleteStyle(variant.basename) },
                        enabled = enabled && snapshot.styleNames.size > 1,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            if (snapshot.styleNames.size == 1) Text(stringResource(R.string.editor_style_keep_one),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.fitText.secondary)
            FitDetails(label = stringResource(R.string.editor_styles_details)) {
                Text(
                    stringResource(R.string.editor_styles_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.fitText.secondary,
                )
            }
        }
        // The face-picker thumbnail belongs here, not on the install page. It is a *style's*
        // picture — one frame of `preview.bin`, per numbered style, and the reason
        // `canRefreshThumbnail` refuses while the always-on display is on the canvas. It has
        // nothing to do with sending anything to a watch, and folding it away behind a
        // summary on a page about the transfer made it a thing nobody would ever open.
        item {
            ThumbnailCard(
                snapshot = snapshot,
                working = working,
                onRefresh = onSyncThumbnail,
                modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
            )
        }
    }
}

/**
 * One watch face at panel aspect: [frame] when the caller has real pixels for it,
 * otherwise the package's preview image at [imagePath], otherwise an empty plate.
 */
@Composable
internal fun FacePreview(
    frame: PreviewFrame?,
    imagePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(9.dp),
    borderColor: Color? = null,
) {
    val plate = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp, borderColor ?: MaterialTheme.colorScheme.outlineVariant, shape)
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
private fun InstallWorkspace(
    snapshot: EditorSnapshot,
    state: EditorUiState,
    /** False where the wide layout is already showing the same read-only face beside it. */
    showFace: Boolean,
    onReviewed: () -> Unit,
    onReset: () -> Unit,
    onGrantNearby: () -> Unit,
    onOpenCompanion: () -> Unit,
    onInitializeAndDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
    onRediscover: () -> Unit,
    onSendBin: () -> Unit,
    onResetDelivery: () -> Unit,
    onCanvas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val valid = snapshot.validationErrors.isEmpty()
    // Sending is gated on having seen the validated render, and every commit clears that
    // flag. The render is the first thing on this page, so arriving is reviewing — but an
    // edit made *while here* clears it again and would leave the send button inert.
    // Keyed on the snapshot so every commit re-marks.
    LaunchedEffect(snapshot) { onReviewed() }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // A pass has no banner. "Container reparsed successfully. Entry bounds, records and
        // CRCs are valid." is three lines saying what `CHECKS · 5 pass` says in two words,
        // at the top of the one page that already carries a render, a checklist and a
        // transfer. A failure keeps its banner, because a failure is the page.
        if (!valid) {
            StatusBanner(
                FitStatus.Fail,
                // Straight from :core:format, which has no resource table.
                snapshot.validationErrors.joinToString(),
            )
        }
        // The watch and the face going to it, at the top, the shape this page has always
        // had. The plate is large now because it is the only render on the page: the
        // merge brought a second, bigger one with it and two pictures of one face on one
        // screen is what that cost. Putting them side by side instead only made the page
        // look like two pages.
        DeviceStatusRow(state.directInstall, snapshot, showFace)
        val environment = state.directInstall.environment
        if (environment.pluginLabel != null || environment.pluginVersionName != null) {
            FitDetails(label = stringResource(R.string.editor_connection_details)) {
                val plugin = environment.pluginLabel
                    ?: stringResource(R.string.editor_device_plugin_installed)
                Text(
                    environment.pluginVersionName?.let {
                        stringResource(R.string.editor_device_plugin_version, plugin, it)
                    } ?: plugin,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.fitText.secondary,
                )
            }
        }
        // What the edit *is*, directly under the picture of it and above the steps for
        // sending it. Each header states its conclusion; opening one shows the working.
        FitDetails(
            label = stringResource(R.string.editor_checks_heading),
            summary = if (valid) {
                stringResource(R.string.editor_install_checks_summary, ValidationChecks.size)
            } else {
                stringResource(R.string.editor_install_checks_failed)
            },
            summaryColor = if (valid) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            // A failure is the one thing on this page nobody should have to open a section
            // to read.
            initiallyExpanded = !valid,
        ) {
            Column(
                Modifier.fillMaxWidth().border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    MaterialTheme.shapes.small,
                ),
            ) {
                ValidationChecks.forEach { ValidationCheck(stringResource(it), valid) }
            }
        }
        // Always here, even with nothing to report. It is one of the three things this page
        // says about the edit, and a section that comes and goes with whether a commit has
        // happened yet is one nobody learns is there.
        FitDetails(
            label = stringResource(R.string.editor_audit_heading),
            summary = snapshot.audit?.let { signedBytes(it.sizeDelta) }
                ?: stringResource(R.string.editor_audit_none_summary),
        ) {
            snapshot.audit?.let { AuditDetail(snapshot, it) } ?: Text(
                stringResource(R.string.editor_audit_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        // Warnings are never folded away. A collapsed section is something the reader
        // chose not to read; a warning they never saw is one this page failed to give.
        snapshot.validationWarnings.forEach { warning -> StatusBanner(FitStatus.Warning, warning) }
        // This page presents the plate above as what is about to be installed, so what
        // that picture cannot show has to be said here and not only on the canvas.
        if (snapshot.selectedVariantApproximate) {
            StatusBanner(FitStatus.Warning, stringResource(R.string.editor_aod_approximate))
            FitDetails(label = stringResource(R.string.editor_preview_details)) {
                Text(
                    stringResource(R.string.editor_preview_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.fitText.secondary,
                )
            }
        }

        if (valid) {
            WatchSection(
                state = state,
                snapshot = snapshot,
                canSend = state.previewReviewed,
                onGrantNearby = onGrantNearby,
                onOpenCompanion = onOpenCompanion,
                onInitializeAndDiscover = onInitializeAndDiscover,
                onOpenPluginSettings = onOpenPluginSettings,
                onConfirmPluginReleased = onConfirmPluginReleased,
                onRediscover = onRediscover,
                onSendBin = onSendBin,
                onReset = onResetDelivery,
                onCanvas = onCanvas,
            )
        } else {
            // No disabled transfer panel: an invalid container has nothing to send, and a
            // page full of inert controls says less than one sentence and the way out.
            Text(
                stringResource(R.string.editor_install_blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FitButton(
                stringResource(R.string.editor_reset_edits),
                onReset, Modifier.fillMaxWidth(), !state.isWorking,
                style = FitButtonStyle.Danger,
            )
        }

    }
}

/** The five joins `Session.validate()` reports, in the order it reports them. */
private val ValidationChecks = listOf(
    R.string.editor_check_entry_bounds,
    R.string.editor_check_record_sizes,
    R.string.editor_check_crc,
    R.string.editor_check_payload_length,
    R.string.editor_check_face_id,
)

/**
 * A size change, short enough for a folded section's summary slot.
 *
 * `+49,836 B` is nine characters, and the summary shares a 143dp line with the section's own
 * label on a 320dp phone. Kibibytes for anything that would not fit, bytes below that —
 * "+0.0 KB" for a twelve-byte edit reports a change as no change.
 */
private fun signedBytes(delta: Int): String {
    val sign = if (delta >= 0) "+" else "−"
    val size = abs(delta)
    return if (size < 1024) {
        "$sign$size B"
    } else {
        String.format(Locale.US, "%s%.1f KB", sign, size / 1024f)
    }
}

@Composable
private fun AuditDetail(snapshot: EditorSnapshot, audit: EditAuditSummary) {
    Text(
        if (snapshot.auditTouchedOnlyAod) {
            stringResource(
                R.string.editor_audit_detail_aod,
                audit.operation,
                audit.changedPayloadBytes,
                "${if (audit.sizeDelta >= 0) "+" else ""}${audit.sizeDelta}",
                // The watch ignores a container over the ceiling, so how close this
                // edit is to it belongs beside the rest of the audit.
                mebibytes(snapshot.containerBytes),
                mebibytes(WATCH_CONTAINER_BYTE_CEILING),
            )
        } else {
            stringResource(
                R.string.editor_audit_detail,
                audit.operation,
                audit.changedPayloadBytes,
                "${if (audit.sizeDelta >= 0) "+" else ""}${audit.sizeDelta}",
                audit.changedStyles.size,
                mebibytes(snapshot.containerBytes),
                mebibytes(WATCH_CONTAINER_BYTE_CEILING),
            )
        },
        style = FitFaceType.numeric,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The face-picker thumbnail, updated on request but only ever once per edit.
 *
 * The button disappears as soon as the thumbnail matches, because a repeat pass is
 * pure loss: the widget pixels come from the vendor's smaller `preview.bin` render,
 * and resampling them again softens the result every time.
 */
/**
 * The face-picker thumbnail: the picture the watch's carousel shows for this style now, and
 * — while **Update thumbnail** is on offer — the one it would show after.
 *
 * Laid out as the style rows above it are, a plate at panel aspect and then a name and one
 * line of state, because it is the same kind of thing: a picture of a style. Both plates are
 * stored pixels rather than the canvas scaled by the display. "Now" is the `preview.bin`
 * frame itself, and "After" is exactly what the update writes, box filter and RGB565
 * included, so the pair is a promise the button keeps (`ThumbnailPreviewTest` holds it to
 * the pixel).
 */
@Composable
private fun ThumbnailCard(
    snapshot: EditorSnapshot,
    working: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val after = snapshot.pickerThumbnailAfter?.takeIf { snapshot.canRefreshThumbnail }
    val styleName = variantLabel(snapshot.activeStyleName)
    Column(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            ThumbnailPlate(
                frame = snapshot.pickerThumbnail,
                label = stringResource(R.string.editor_thumbnail_now),
                description = stringResource(R.string.editor_thumbnail_now_a11y, styleName),
            )
            if (after != null) {
                Text(
                    "→",
                    modifier = Modifier.padding(bottom = 18.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                ThumbnailPlate(
                    frame = after,
                    label = stringResource(R.string.editor_thumbnail_after),
                    description = stringResource(R.string.editor_thumbnail_after_a11y),
                    highlighted = true,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.editor_thumbnail_heading),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when {
                        snapshot.isAodSelected ->
                            stringResource(R.string.editor_thumbnail_state_aod, styleName)
                        snapshot.thumbnailRefreshed -> stringResource(R.string.editor_thumbnail_state_in_sync)
                        !snapshot.isDirty -> stringResource(R.string.editor_thumbnail_state_stock)
                        snapshot.validationErrors.isNotEmpty() ->
                            stringResource(R.string.editor_thumbnail_state_blocked)
                        else -> stringResource(R.string.editor_thumbnail_state_stale)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (snapshot.thumbnailRefreshed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.fitText.secondary
                    },
                )
            }
        }
        if (!snapshot.isAodSelected && snapshot.thumbnailRefreshed) {
            FitDetails(label = stringResource(R.string.editor_thumbnail_details)) {
                Text(
                    stringResource(R.string.editor_thumbnail_in_sync, styleName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (snapshot.isAodSelected || snapshot.isDirty) {
            Text(
                when {
                    snapshot.isAodSelected -> stringResource(R.string.editor_thumbnail_aod, styleName)
                    snapshot.validationErrors.isNotEmpty() -> stringResource(R.string.editor_thumbnail_blocked)
                    else -> stringResource(R.string.editor_thumbnail_stale)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.canRefreshThumbnail) {
            FitButton(
                stringResource(R.string.editor_thumbnail_update),
                onRefresh,
                Modifier.fillMaxWidth(),
                !working,
                loading = working,
                style = FitButtonStyle.Secondary,
            )
        }
    }
}

/** One thumbnail at the style rows' plate size, with what it is under it. */
@Composable
private fun ThumbnailPlate(
    frame: PreviewFrame?,
    label: String,
    description: String,
    highlighted: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        FacePreview(
            frame = frame,
            imagePath = null,
            contentDescription = description,
            modifier = Modifier.width(54.dp).height(85.dp),
            // The selected style row's border, for the picture the button will produce.
            borderColor = if (highlighted) MaterialTheme.colorScheme.primary else null,
        )
        MicroLabel(
            label,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.fitText.secondary,
        )
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
    enabled: Boolean,
    onReset: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    if (renaming) {
        RenameProjectDialog(
            current = snapshot.projectName,
            onDismiss = { renaming = false },
            onConfirm = { renaming = false; onRename(it) },
        )
    }
    if (deleting) {
        DeleteProjectDialog(
            name = snapshot.projectName,
            onDismiss = { deleting = false },
            onConfirm = { deleting = false; onDelete() },
        )
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The name is the project's own, not the face's, and this is where it is changed.
        // Two projects on one face read identically everywhere else in the editor.
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(14.dp),
        ) {
            MicroLabel(stringResource(R.string.editor_project_name_heading))
            Text(
                snapshot.projectName,
                Modifier.fillMaxWidth().padding(top = 9.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            // A labelled button rather than a pencil glyph. This page has the width for a
            // word, and the glyphs that would fit — U+270E and its neighbours — are exactly
            // the class that falls through to the emoji font on some builds, which would
            // make it the only colour character in the app.
            FitButton(
                stringResource(R.string.editor_project_rename),
                { renaming = true },
                Modifier.fillMaxWidth().padding(top = 12.dp),
                style = FitButtonStyle.Secondary,
            )
        }
        FitDetails(label = stringResource(R.string.editor_container_heading)) {
            Text(
                stringResource(
                    R.string.editor_container_detail,
                    snapshot.faceId,
                    snapshot.styleNames.size,
                    snapshot.imageCount,
                    snapshot.widgets.size,
                    snapshot.sourceName,
                ),
                style = FitFaceType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Non-destructive, so it sits above the two that are. Duplicating leaves the editor
        // on the original: switching out from under an edit in progress would be worse than
        // having to go and find the copy.
        FitButton(
            stringResource(R.string.editor_duplicate_project),
            onDuplicate,
            Modifier.fillMaxWidth(),
            enabled,
            style = FitButtonStyle.Secondary,
        )
        Text(
            stringResource(R.string.editor_duplicate_project_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FitButton(
            stringResource(R.string.editor_reset_edits),
            onReset, Modifier.fillMaxWidth(), enabled && snapshot.isDirty,
            style = FitButtonStyle.Danger,
        )
        Text(
            stringResource(R.string.editor_reset_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Deleting from here as well as from the projects list, because this is where you
        // are when you decide a project is not worth keeping — and with more than one
        // project per face, going back to find the right row is where you delete the wrong
        // one.
        FitButton(
            stringResource(R.string.editor_delete_project),
            { deleting = true },
            Modifier.fillMaxWidth(),
            enabled,
            style = FitButtonStyle.Danger,
        )
        Text(
            stringResource(R.string.editor_delete_project_note),
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
    /** The set, in pick order; outlined and numbered the way the import picker numbers one. */
    multiSelection: List<Int> = emptyList(),
    /** Where each member of a set being nudged is headed; see `EditorUiState.pendingSetMove`. */
    pendingSetMove: List<WidgetMovePreview> = emptyList(),
    /**
     * Holding a widget starts a selection set or adds it. Null where this canvas does not
     * build sets — only the Canvas page does, because only its tray acts on one.
     */
    onHoldWidget: ((Int) -> Unit)? = null,
) {
    val composedBitmap = snapshot.composedPreview.rememberBitmap()
    val overlayBitmap = snapshot.widgetOverlay.rememberBitmap()
    val pendingBitmap = pendingImage?.preview?.rememberBitmap()
    var draggingIndex by remember(snapshot.selectedVariant.basename) { mutableIntStateOf(-1) }
    var draggingPosition by remember(snapshot.selectedVariant.basename) { mutableStateOf(Offset.Zero) }
    // Where the finger has actually taken the widget, before the panel clamp.
    // Accumulating the *clamped* position instead is what made a widget stick: push it
    // past an edge, come back, and it started moving again from the edge rather than
    // from under the finger — so it trailed the finger by however far it had overshot,
    // for the rest of the drag. The clamp belongs on the way out, not in the running total.
    var dragTrack by remember(snapshot.selectedVariant.basename) { mutableStateOf(Offset.Zero) }
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
    // The same reason as the two above: a set is picked and let go without any key changing.
    val latestMultiSelection by rememberUpdatedState(multiSelection)
    val latestOnHold by rememberUpdatedState(onHoldWidget)
    val haptics = LocalHapticFeedback.current
    val badgeMeasurer = rememberTextMeasurer()
    // Resolved here rather than inside `semantics`, which is not a composable scope.
    val canvasDescription = stringResource(
        R.string.editor_canvas_a11y,
        snapshot.selectedVariantLabel(),
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
    // A set being nudged is drawn from its members' targets, all at once, rather than from
    // the snapshot, which only catches up one commit at a time.
    val setTargets = pendingSetMove.associateBy { it.globalIndex }
    val setLayers = snapshot.rememberSetMoveLayers(setTargets.keys)
    DisposableEffect(setLayers) {
        onDispose { setLayers?.bitmaps?.forEach(Bitmap::recycle) }
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
            .clip(RoundedCornerShape(FaceOutlineCorner))
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
            .pointerInput(snapshot.selectedVariant.basename, pendingImage?.uri, editing) {
                if (pendingImage == null && editing) {
                    fun hit(position: Offset): Int? {
                        val current = latestSnapshot
                        return hitWidget(
                            widgets = current.widgets,
                            point = position,
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            faceWidth = current.preview.width,
                            faceHeight = current.preview.height,
                            preferredGlobalIndex = latestSelectedGlobalIndex
                                ?: latestMultiSelection.lastOrNull(),
                        )?.globalIndex
                    }
                    detectTapGestures(
                        // Holding a widget is how a set starts, and how one grows past a
                        // single selection: it adds to what is selected instead of
                        // replacing it. The buzz is the only sign the hold registered
                        // before the outline changes.
                        onLongPress = { position ->
                            val hold = latestOnHold ?: return@detectTapGestures
                            if (!latestEnabled) return@detectTapGestures
                            val index = hit(position) ?: return@detectTapGestures
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            hold(index)
                        },
                    ) { position ->
                        if (!latestEnabled) return@detectTapGestures
                        onWidget(hit(position))
                    }
                }
            }
            .pointerInput(snapshot.selectedVariant.basename, pendingImage?.uri, editing) {
                if (pendingImage == null && editing) {
                    detectDragGestures(
                        onDragStart = { position ->
                            // A set moves with the arrows, together. Dragging one member
                            // would pull it out of the arrangement the set is for.
                            if (!latestEnabled || latestMultiSelection.isNotEmpty()) {
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
                                // draws and the move expects the stored value back. The
                                // clamp is what knows about the offset.
                                draggingPosition = Offset(
                                    x = (widget.originX + widget.x).toFloat(),
                                    y = (widget.originY + widget.y).toFloat(),
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
                                val startX = (widget.originX + widget.x).toFloat()
                                val startY = (widget.originY + widget.y).toFloat()
                                val horizontal = stepDragAxis(
                                    track = dragTrack.x,
                                    delta = amount.x * current.preview.width / size.width,
                                    starting = startX,
                                    extent = widget.visualBounds.width,
                                    canvasExtent = current.preview.width,
                                    drawOffset = widget.visualOffsetX,
                                )
                                val vertical = stepDragAxis(
                                    track = dragTrack.y,
                                    delta = amount.y * current.preview.height / size.height,
                                    starting = startY,
                                    extent = widget.visualBounds.height,
                                    canvasExtent = current.preview.height,
                                    drawOffset = widget.visualOffsetY,
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
                                        draggingPosition.x.roundToInt() - widget.originX,
                                        draggingPosition.y.roundToInt() - widget.originY,
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
        } else if (setLayers != null) {
            val scaleX = size.width / snapshot.preview.width
            val scaleY = size.height / snapshot.preview.height
            setLayers.slices.forEach { slice ->
                when (slice) {
                    is SetMoveSlice.Still -> drawImage(
                        image = slice.image,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                    is SetMoveSlice.Moving -> {
                        val target = setTargets[slice.globalIndex] ?: return@forEach
                        drawImage(
                            image = slice.image,
                            dstOffset = IntOffset(
                                ((target.displayX + slice.offsetX) * scaleX).roundToInt(),
                                ((target.displayY + slice.offsetY) * scaleY).roundToInt(),
                            ),
                            dstSize = IntSize(
                                (slice.width * scaleX).roundToInt().coerceAtLeast(1),
                                (slice.height * scaleY).roundToInt().coerceAtLeast(1),
                            ),
                            filterQuality = FilterQuality.Low,
                        )
                    }
                }
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
                drawImage(
                    image = dragLayer.foreground,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
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
                    val order = multiSelection.indexOf(widget.globalIndex)
                    val selected = widget.globalIndex == activeIndex || order >= 0
                    // A drag tracks the stored coordinate, so the rectangle needs the
                    // same offset the resting position gets.
                    val setTarget = setTargets[widget.globalIndex]
                    val x = when {
                        setTarget != null -> setTarget.displayX + widget.visualOffsetX
                        visualMoveIndex == widget.globalIndex -> visualMovePosition.x + widget.visualOffsetX
                        else -> (widget.drawLeft + widget.visualBounds.left).toFloat()
                    }
                    val y = when {
                        setTarget != null -> setTarget.displayY + widget.visualOffsetY
                        visualMoveIndex == widget.globalIndex -> visualMovePosition.y + widget.visualOffsetY
                        else -> (widget.drawTop + widget.visualBounds.top).toFloat()
                    }
                    drawRect(
                        color = if (selected) selectedGuideColor else guideColor,
                        topLeft = Offset(x * scaleX, y * scaleY),
                        size = Size(widget.visualBounds.width * scaleX, widget.visualBounds.height * scaleY),
                        style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()),
                    )
                    if (order >= 0) {
                        drawPickBadge(order + 1, Offset(x * scaleX, y * scaleY),
                            selectedGuideColor, badgeMeasurer)
                    }
                }
            }
        }
        drawRect(color = borderColor, style = Stroke(2.dp.toPx()))
    }
}

// ---------------------------------------------------------------------------
// The watch half of the Install page
// ---------------------------------------------------------------------------

/**
 * Everything below the checks: the watch, the setup it needs, and the transfer.
 *
 * This was its own page and its own scroll. Merged, it is a section of one — so it owns
 * no scroll and no padding, and takes its spacing from the column it is placed in.
 *
 * [canSend] is `previewReviewed`. It is not the gate that matters — `Session.validatedBytes()`
 * is, and it is fail-closed — but it is the one that makes having seen the render a
 * precondition for sending it, which is the whole reason the flag exists.
 */
@Composable
private fun ColumnScope.WatchSection(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    canSend: Boolean,
    onGrantNearby: () -> Unit,
    onOpenCompanion: () -> Unit,
    onInitializeAndDiscover: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onConfirmPluginReleased: () -> Unit,
    onRediscover: () -> Unit,
    onSendBin: () -> Unit,
    onReset: () -> Unit,
    onCanvas: () -> Unit,
) {
    val delivery = state.directInstall
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
        val stopped = if (delivery.setupComplete) null else delivery.failure
        stopped?.let { failure ->
            StatusBanner(
                FitStatus.Fail,
                failure,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                label = stringResource(R.string.editor_install_stopped_label),
            )
        }
        // A failure is already in a banner, including after setup. A rewind's distinct
        // recovery instruction still belongs here. During transfer, the phase/progress
        // below are primary; the full protocol message remains in Transfer details.
        val bannerMessage = if (delivery.setupComplete && delivery.phase == DirectInstallPhase.FAILED) {
            delivery.failure ?: delivery.message
        } else stopped
        val transferHasStatus = delivery.setupComplete &&
            (delivery.isActive || delivery.phase == DirectInstallPhase.COMPLETE)
        if (!transferHasStatus && delivery.message != bannerMessage) {
            Text(
                delivery.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (delivery.setupComplete) {
        TransferPanel(delivery, state.isWorking, canSend, onSendBin, onRediscover, onCanvas)
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

/**
 * Which badge the delivery state wears, and in what colour.
 *
 * Resource and colour are chosen together. They used to be two `when`s, the second keyed on
 * the badge's own display text — which quietly ties the colour to the wording, so renaming a
 * badge or translating it would drop it back to the default.
 */
@Composable
private fun deliveryBadge(state: DirectInstallState): Pair<Int, Color> = when {
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

/**
 * The watch, and the face that is about to be sent to it.
 *
 * @param showFace false where the wide layout is already keeping a canvas beside the whole
 *   page, which would otherwise make this the second picture of one face on one screen.
 */
@Composable
private fun DeviceStatusRow(
    state: DirectInstallState,
    snapshot: EditorSnapshot,
    showFace: Boolean,
) {
    val (badge, badgeColor) = deliveryBadge(state)
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showFace) {
            // What installs is `activeStyleName`, never whatever variant the canvas happens
            // to be showing: looking at the always-on display must not make this page claim
            // the always-on display is what is about to go to the watch.
            val activeIsOnCanvas = snapshot.selectedVariant.basename == snapshot.activeStyleName
            FacePreview(
                frame = snapshot.composedPreview.takeIf { activeIsOnCanvas },
                imagePath = snapshot.stylePreviewPaths[snapshot.activeStyleName]
                    .takeIf { !activeIsOnCanvas },
                contentDescription = stringResource(R.string.editor_device_face_a11y),
                modifier = Modifier.width(InstallPlateWidth).height(InstallPlateHeight),
                shape = MaterialTheme.shapes.medium,
            )
        }
        Column(Modifier.weight(1f)) {
            // The badge is its own line above the name rather than sharing one with it.
            // Beside the plate a 320dp phone leaves this column about 150dp, and the name
            // and the badge together want more than that — so the badge wrapped, rendering
            // as "SETU / P" across the name it was meant to sit beside.
            Text(
                stringResource(badge),
                modifier = Modifier
                    .background(badgeColor.copy(alpha = .14f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                color = badgeColor,
                style = FitFaceType.micro,
                maxLines = 1,
            )
            Text(
                stringResource(R.string.editor_device_name),
                modifier = Modifier.padding(top = 7.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val activeStyleLabel = variantLabel(snapshot.activeStyleName)
            Text(
                snapshot.faceName?.let {
                    stringResource(R.string.editor_device_face_named, it, activeStyleLabel)
                } ?: stringResource(
                    R.string.editor_device_face_unnamed,
                    snapshot.faceId,
                    activeStyleLabel,
                ),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(
                    if (state.environment.pluginInstalled) R.string.editor_device_plugin_installed
                    else R.string.editor_device_plugin_missing,
                ),
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Whether this phone has a Nearby devices switch for the stock plugin at all.
 *
 * Android 12 is where `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` became runtime permissions, so
 * below it there is no per-app switch to revoke and `Fit3DirectInstaller.pluginNearbyGranted`
 * is always null — step 4 can only ever be the user's acknowledgement there. Every string
 * that names that switch is therefore wrong on an older phone, and every string that names
 * the older phones' way round it — a freezing tool, ADB — is noise on a newer one, which is
 * how a reader who does not know their own Android version ends up following instructions
 * meant for somebody else's. So the split is made once, here, and both halves are gated on
 * it. See `docs/direct-install.md`.
 */
private val hasPluginNearbySwitch: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

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
    val nearbySwitch = hasPluginNearbySwitch
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
            why = stringResource(
                if (nearbySwitch) {
                    R.string.editor_setup_step3_why
                } else {
                    R.string.editor_setup_step3_why_legacy
                },
            ),
            done = stringResource(R.string.editor_setup_step3_done),
            action = onDiscover,
        ),
        SetupRow(
            step = SetupStep.PLUGIN_RELEASED,
            title = stringResource(R.string.editor_setup_step4_title),
            why = stringResource(
                if (nearbySwitch) {
                    R.string.editor_setup_step4_why
                } else {
                    R.string.editor_setup_step4_why_legacy
                },
            ),
            done = stringResource(R.string.editor_setup_step4_done),
            action = onOpenPluginSettings,
            // Amber because this is the one step whose way through is not on the phone:
            // it needs a tool the reader has to go and set up first.
            warning = !nearbySwitch,
        ),
    )
    val completed = steps.count { state.isStepDone(it.step) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FitDetails(label = stringResource(R.string.editor_setup_details)) {
            Text(
                stringResource(
                    if (nearbySwitch) R.string.editor_setup_banner else R.string.editor_setup_banner_legacy,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
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
                    // Below Android 12 the same screen has no Nearby devices entry to
                    // reach, so calling it "Plugin access" sends the reader looking for
                    // a switch that is not there.
                    stringResource(
                        if (nearbySwitch) {
                            R.string.editor_setup_plugin_access
                        } else {
                            R.string.editor_setup_plugin_access_legacy
                        },
                    ),
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
                stringResource(
                    if (nearbySwitch) {
                        R.string.editor_setup_confirm_note
                    } else {
                        R.string.editor_setup_confirm_note_legacy
                    },
                ),
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
    val warning: Boolean = false,
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
                else if (row.warning) MaterialTheme.fitColors.warning.copy(alpha = .07f)
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
                else if (row.warning) MaterialTheme.fitColors.warning
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
    /** `previewReviewed`: every button here starts a transfer, so all of them take it. */
    canSend: Boolean,
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
                                R.string.editor_transfer_percent,
                                (state.progress.coerceIn(0f, 1f) * 100).toInt(),
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
                    stringResource(
                        if (hasPluginNearbySwitch) {
                            R.string.editor_transfer_done
                        } else {
                            R.string.editor_transfer_done_legacy
                        },
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    label = stringResource(R.string.editor_transfer_done_label),
                )
                // Sending the same bytes again is legitimate — a watch can reject a
                // face for reasons that have nothing to do with the payload.
                FitButton(
                    stringResource(R.string.editor_transfer_send_again),
                    onSendBin, Modifier.fillMaxWidth(), !isWorking && canSend,
                    style = FitButtonStyle.Secondary,
                )
                FitButton(
                    stringResource(R.string.editor_transfer_back_to_canvas),
                    onCanvas, Modifier.fillMaxWidth(),
                    style = FitButtonStyle.Secondary,
                )
            }
            state.phase == DirectInstallPhase.FAILED -> {
                StatusBanner(
                    FitStatus.Fail,
                    state.failure ?: state.message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                // A failure used to leave the page with no action at all, so the only
                // way forward was restarting the whole setup.
                FitButton(
                    stringResource(R.string.editor_transfer_try_again),
                    onSendBin, Modifier.fillMaxWidth(), !isWorking && canSend,
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
                        DirectInstallState.TransferPhases.firstOrNull { it.first == state.phase }?.second
                            ?: state.message,
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
                    enabled = !isWorking && canSend && state.phase == DirectInstallPhase.READY,
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
                modifier = Modifier.semantics {
                    if (active) liveRegion = LiveRegionMode.Polite
                },
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
    FitDetails(label = stringResource(R.string.editor_payload_heading)) {
        Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
        Text(
            stringResource(
                R.string.editor_transfer_progress,
                state.acknowledgedWindows,
                state.totalWindows,
                state.acknowledgedBytes,
                state.totalBytes,
            ),
            style = FitFaceType.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            val bounds = it.visualBounds
            val left = it.drawLeft + bounds.left
            val top = it.drawTop + bounds.top
            // Half-open on purpose. Testing `x <= left + width` made the rectangle
            // width+1 px wide, so two abutting widgets shared a one-pixel column and
            // the right and bottom edges belonged to both of them.
            x >= left && x < left + bounds.width && y >= top && y < top + bounds.height
        }
        .toList()
    val specific = candidates.minWithOrNull(
        compareBy<WidgetGuide> { it.visualBounds.width.toLong() * it.visualBounds.height }
            .thenByDescending(WidgetGuide::globalIndex),
    ) ?: return null
    val preferred = candidates.singleOrNull { it.globalIndex == preferredGlobalIndex }
    val specificArea = specific.visualBounds.width.toLong() * specific.visualBounds.height
    val preferredArea = preferred?.let { it.visualBounds.width.toLong() * it.visualBounds.height }
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
    val foreground: ImageBitmap,
    val widget: ImageBitmap,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
    val bitmaps: List<Bitmap>,
)

/** One slice of the layer stack while a set is moving: layers that stay, or one that moves. */
internal sealed interface SetMoveSegment {
    data class Still(val layers: List<WidgetImageLayer>) : SetMoveSegment
    data class Moving(val layer: WidgetImageLayer) : SetMoveSegment
}

/**
 * The layer stack split for moving a set: runs of layers that stay put, and each layer of a
 * moving widget on its own, in record order.
 *
 * The single-widget drag cuts the stack in two around the one widget — below it, above it —
 * and a set is the same cut made at every member. Keeping record order is what keeps the
 * z-order honest while they move: a member under a stationary widget stays under it, and
 * one above stays above. It always opens with a still run, empty or not, because that run
 * carries the black the face is drawn on. Pure so a test can hold it.
 */
internal fun setMoveSegments(layers: List<WidgetImageLayer>, moving: Set<Int>): List<SetMoveSegment> {
    val segments = mutableListOf<SetMoveSegment>()
    var still = mutableListOf<WidgetImageLayer>()
    layers.forEach { layer ->
        if (layer.globalIndex in moving) {
            segments += SetMoveSegment.Still(still)
            still = mutableListOf()
            segments += SetMoveSegment.Moving(layer)
        } else {
            still += layer
        }
    }
    if (still.isNotEmpty() || segments.isEmpty()) segments += SetMoveSegment.Still(still)
    // Two members adjacent in the stack leave an empty run between them; only the first,
    // which paints the black, is worth a bitmap.
    return segments.filterIndexed { index, segment ->
        index == 0 || segment !is SetMoveSegment.Still || segment.layers.isNotEmpty()
    }
}

/** A set's stack, drawn: bitmaps for each still run and each moving layer. */
private data class SetMoveLayers(
    val slices: List<SetMoveSlice>,
    val bitmaps: List<Bitmap>,
)

private sealed interface SetMoveSlice {
    data class Still(val image: ImageBitmap) : SetMoveSlice
    data class Moving(
        val globalIndex: Int,
        val image: ImageBitmap,
        val offsetX: Int,
        val offsetY: Int,
        val width: Int,
        val height: Int,
    ) : SetMoveSlice
}

@Composable
private fun EditorSnapshot.rememberSetMoveLayers(moving: Set<Int>): SetMoveLayers? =
    remember(widgetImageLayers, widgets, preview.width, preview.height, moving) {
        if (moving.isEmpty()) return@remember null
        val guides = widgets.associateBy { it.globalIndex }
        val bitmaps = mutableListOf<Bitmap>()
        fun bitmap(frame: PreviewFrame) = Bitmap.createBitmap(
            frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888).also(bitmaps::add)
        val slices = setMoveSegments(widgetImageLayers, moving).mapIndexedNotNull { index, segment ->
            when (segment) {
                is SetMoveSegment.Still -> SetMoveSlice.Still(
                    bitmap(
                        WidgetLayerComposer.compose(preview.width, preview.height, segment.layers,
                            widgets, transparent = index != 0),
                    ).asImageBitmap(),
                )
                is SetMoveSegment.Moving -> {
                    val guide = guides[segment.layer.globalIndex] ?: return@mapIndexedNotNull null
                    SetMoveSlice.Moving(
                        globalIndex = segment.layer.globalIndex,
                        image = bitmap(segment.layer.frame).asImageBitmap(),
                        offsetX = guide.drawOffsetX + segment.layer.offsetX,
                        offsetY = guide.drawOffsetY + segment.layer.offsetY,
                        width = segment.layer.frame.width,
                        height = segment.layer.frame.height,
                    )
                }
            }
        }
        SetMoveLayers(slices, bitmaps)
    }

@Composable
private fun EditorSnapshot.rememberWidgetDragLayer(globalIndex: Int): WidgetDragLayer? =
    remember(widgetImageLayers, widgets, preview.width, preview.height, globalIndex) {
        val selected = widgets.singleOrNull { it.globalIndex == globalIndex }
            ?: return@remember null
        val position = widgetImageLayers.indexOfFirst { it.globalIndex == globalIndex }
        if (position < 0) return@remember null
        val layer = widgetImageLayers[position]
        // Rebuild both sides of the selected record's z-order. No erasing from a flat
        // image: lower widgets are revealed intact, upper widgets remain above the drag.
        val base = WidgetLayerComposer.compose(preview.width, preview.height,
            widgetImageLayers.take(position), widgets)
        val foreground = WidgetLayerComposer.compose(preview.width, preview.height,
            widgetImageLayers.drop(position + 1), widgets, transparent = true)
        fun bitmap(frame: PreviewFrame) = Bitmap.createBitmap(
            frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val baseBitmap = bitmap(base)
        val foregroundBitmap = bitmap(foreground)
        val widgetBitmap = bitmap(layer.frame)
        WidgetDragLayer(
            baseBitmap.asImageBitmap(), foregroundBitmap.asImageBitmap(), widgetBitmap.asImageBitmap(),
            selected.drawOffsetX + layer.offsetX, selected.drawOffsetY + layer.offsetY,
            layer.frame.width, layer.frame.height,
            listOf(baseBitmap, foregroundBitmap, widgetBitmap),
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

/**
 * Renames the open project.
 *
 * A second copy of the library's dialog rather than a shared one: the two modules have no
 * component layer between them, and one small `AlertDialog` in each is less than a new
 * shared surface would cost. Both scroll their text slot for the same reason — an
 * `AlertDialog` caps its own height and clips rather than scrolls in landscape.
 */
@Composable
private fun RenameProjectDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_project_rename_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.editor_project_name_heading)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.editor_project_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

/**
 * Confirms deleting the open project, from inside it.
 *
 * The same guarantee the library's copy gives: the text slot scrolls, because an
 * `AlertDialog` caps its own height and clips rather than scrolls in landscape.
 */
@Composable
private fun DeleteProjectDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_delete_project_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.editor_delete_project_message, name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.editor_delete_project_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}
