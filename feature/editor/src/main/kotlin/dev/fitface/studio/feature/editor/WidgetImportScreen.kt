package dev.fitface.studio.feature.editor

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.fitface.studio.core.model.*
import dev.fitface.studio.core.ui.*
import kotlin.math.roundToInt

@Composable
internal fun WidgetImportRoute(initialSnapshot: EditorSnapshot, onDismiss: () -> Unit,
    onImported: (EditorSnapshot) -> Unit, onStylesDeleted: (EditorSnapshot) -> Unit = {},
    backgroundMode: Boolean = false, viewModel: WidgetImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.targetSnapshot ?: initialSnapshot
    var managingStyles by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(backgroundMode) { viewModel.start(snapshot, backgroundMode) }
    LaunchedEffect(state.imported) { state.imported?.let { viewModel.close(); onImported(it) } }
    val back = { if (viewModel.back()) { viewModel.close(); onDismiss() } }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        BackHandler(onBack = back)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                FitTopBar(
                    title = when (state.stage) {
                        WidgetImportStage.FACES -> stringResource(if (state.backgroundMode)
                            R.string.editor_bg_import_faces else R.string.widget_import_faces)
                        WidgetImportStage.WIDGETS -> state.selectedFace?.name
                            ?: stringResource(R.string.widget_import_widgets)
                        WidgetImportStage.REVIEW -> if (state.backgroundMode) {
                            stringResource(R.string.editor_bg_import_review)
                        } else if (state.picks.size > 1) {
                            stringResource(R.string.widget_import_review_set_title, state.picks.size)
                        } else {
                            state.preview?.widget?.let { importWidgetName(it) }
                                ?: stringResource(R.string.widget_import_review)
                        }
                        WidgetImportStage.BATCH -> if (state.batchRunning) {
                            stringResource(R.string.widget_import_batch_running, state.batch.size)
                        } else {
                            stringResource(R.string.widget_import_batch_done,
                                state.batchAdded, state.batch.size)
                        }
                    },
                    subtitle = if (state.backgroundMode) {
                        stringResource(if (snapshot.isAodSelected) R.string.editor_bg_import_scope_aod
                            else R.string.editor_bg_import_scope_styles)
                    } else when (state.stage) {
                        WidgetImportStage.FACES -> stringResource(R.string.widget_import_target,
                            importVariantLabel(snapshot.selectedVariant))
                        else -> stringResource(R.string.widget_import_donor_subtitle,
                            state.donor?.faceId ?: state.selectedFace?.faceId.orEmpty(),
                            importVariantLabel(snapshot.selectedVariant))
                    },
                    onBack = back,
                    actions = {
                        if (state.backgroundMode && state.stage == WidgetImportStage.REVIEW) {
                            FitBadge(stringResource(R.string.editor_badge_unapplied), MaterialTheme.fitColors.warning)
                        }
                    },
                )
                // Pinned, not scrolled with the content. As a list item the progress bar
                // and its Cancel button sat at the top of a list someone had scrolled far
                // down to tap a face — a transfer with no visible way to stop it.
                ImportNotices(state, viewModel)
                if (state.stylesDeleted) Text(stringResource(R.string.editor_style_delete_saved),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                if (state.capacity != null || state.backgroundPreview?.skippedVariants?.isNotEmpty() == true) {
                    TextButton(onClick = { managingStyles = true }, enabled = !state.busy, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.editor_manage_styles))
                    }
                }
                when (state.stage) {
                    WidgetImportStage.FACES -> DonorFacesPage(state, viewModel, Modifier.weight(1f))
                    WidgetImportStage.WIDGETS -> if (state.backgroundMode) {
                        BackgroundDonorPage(state, viewModel, Modifier.weight(1f))
                    } else DonorWidgetsPage(state, snapshot, viewModel, Modifier.weight(1f))
                    WidgetImportStage.REVIEW -> if (state.backgroundMode) {
                        BackgroundReviewPage(state, snapshot, viewModel, Modifier.weight(1f))
                    } else ImportReviewPage(state, snapshot, viewModel, Modifier.weight(1f))
                    WidgetImportStage.BATCH ->
                        ImportBatchPage(state, viewModel, Modifier.weight(1f))
                }
            }
        }
    }
    if (managingStyles) StyleManagementRoute(snapshot.projectId, protectedVariant = snapshot.selectedVariant.basename,
        capacity = state.capacity, onDismiss = { managingStyles = false },
        onDeleted = { managingStyles = false; viewModel.acceptStyleDeletion(it); onStylesDeleted(it) })
}

/**
 * The strip under the top bar: what is experimental, what is running, what failed.
 *
 * One line each, and none of them scroll away — a reason someone has to go looking for is
 * a reason most of them will not see.
 */
@Composable
private fun ImportNotices(
    state: WidgetImportUiState,
    viewModel: WidgetImportViewModel,
) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 16.dp)
                .background(MaterialTheme.fitColors.warning.copy(alpha = .08f),
                    RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp))
                .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MicroLabel(stringResource(R.string.widget_import_experimental_label),
                color = MaterialTheme.fitColors.warning)
            Text(
                stringResource(if (state.backgroundMode) R.string.editor_bg_import_experimental
                    else R.string.widget_import_experimental_short),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
        state.error?.let { error ->
            StatusBanner(FitStatus.Fail, error, Modifier.padding(top = 6.dp, end = 16.dp))
        }
        // Pricing a pick and switching variant are deliberately silent here. Both are fast,
        // both are superseded by the next tap anyway, and this strip is pinned above the
        // page — so flashing it in and out shifted everything under it down by its own
        // height and back: a second tap aimed at a row landed on the one above it, and every
        // variant chip made the screen flicker. The panel's button shows pricing working, and
        // a variant switch keeps the face on screen with a bar in reserved space if it is
        // slow; the strip is for the downloads and loads that have something to cancel.
        val quietWork = state.stage == WidgetImportStage.WIDGETS &&
            (state.content != null || state.backgroundContent != null) &&
            state.progress == null && !state.saving
        if (state.busy && !quietWork) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    state.progress
                        ?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) }
                        ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            when {
                                state.saving && state.backgroundMode -> R.string.editor_bg_import_saving
                                state.saving -> R.string.widget_import_saving
                                state.progress != null -> R.string.widget_import_downloading
                                else -> R.string.widget_import_loading
                            },
                        ),
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.fitText.secondary,
                    )
                }
                if (!state.saving) {
                    FitButton(stringResource(R.string.editor_cancel), { viewModel.back() },
                        style = FitButtonStyle.Secondary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 1 · the donor face
// ---------------------------------------------------------------------------

@Composable
private fun DonorFacesPage(
    state: WidgetImportUiState,
    viewModel: WidgetImportViewModel,
    modifier: Modifier,
) {
    val faces = state.visibleFaces
    // The same card the library's catalogue uses, for the same reason: what someone is
    // choosing between is the picture, and a 54dp thumbnail beside two lines of text put
    // two and a half faces on a screen.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // One list, in catalogue order. A chip that split it in two made the reader
            // choose a haystack before they had seen a needle, and the badge on each card
            // already says which ones cost a download.
            OutlinedTextField(state.query, viewModel::setQuery, Modifier.fillMaxWidth(),
                singleLine = true, enabled = !state.busy,
                label = { Text(stringResource(R.string.widget_import_search)) })
        }
        if (faces.isEmpty() && !state.busy) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.widget_import_no_faces),
                        color = MaterialTheme.fitText.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                    if (state.faces.isEmpty()) {
                        FitButton(stringResource(R.string.widget_import_retry), viewModel::loadCatalog,
                            style = FitButtonStyle.Secondary)
                    }
                }
            }
        }
        items(faces, key = { it.productId }) { face ->
            DonorFaceCard(
                face = face,
                cached = face.productId in state.cachedFaces,
                uneditable = face.appId in state.uneditable,
                enabled = !state.busy && face.appId !in state.uneditable,
                onClick = { viewModel.openFace(face) },
            )
        }
    }
}

@Composable
private fun DonorFaceCard(
    face: CatalogFace,
    cached: Boolean,
    uneditable: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Short on purpose: this line is one row of 9.5sp mono between two card edges, and a
    // label long enough to wrap lands on top of the face number beside it.
    val badge = when {
        uneditable -> stringResource(R.string.widget_import_badge_not_editable)
        cached -> stringResource(R.string.widget_import_badge_cached)
        else -> stringResource(R.string.widget_import_badge_download, importBytes(face.packageSize))
    }
    val description = stringResource(R.string.widget_import_face_a11y, face.name, face.faceId,
        stringResource(
            when {
                uneditable -> R.string.widget_import_not_editable
                cached -> R.string.widget_import_a11y_cached
                else -> R.string.widget_import_a11y_download
            },
            importBytes(face.packageSize),
        ))
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            // A face this app cannot open looks inert rather than merely refusing a tap.
            .alpha(if (uneditable) DisabledCardAlpha else 1f)
            .padding(8.dp),
    ) {
        AsyncImage(
            model = face.styles.firstOrNull()?.previewUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(256f / 402f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentScale = ContentScale.Fit,
        )
        Text(
            face.name,
            modifier = Modifier.padding(start = 3.dp, top = 10.dp, end = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Three digits, as the library's cards number the same faces.
            MicroLabel(stringResource(R.string.widget_import_face_number, face.faceId.takeLast(3)),
                color = MaterialTheme.colorScheme.primary)
            Text(
                badge.uppercase(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = FitFaceType.micro,
                color = if (cached && !uneditable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.fitText.secondary
                },
            )
        }
    }
}

@Composable
private fun BackgroundDonorPage(state: WidgetImportUiState, viewModel: WidgetImportViewModel,
    modifier: Modifier) {
    Column(modifier) {
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.donor?.variants.orEmpty(), key = { it.basename }) { variant ->
                FitChip(importVariantLabel(variant), state.variant == variant,
                    { viewModel.selectVariant(variant) }, enabled = !state.saving && state.progress == null)
            }
        }
        QuietProgress(state.variantLoading, Modifier.padding(horizontal = 16.dp))
        val content = state.backgroundContent
        BackgroundPageBody(content?.background, Modifier.weight(1f),
            stringResource(R.string.editor_bg_import_asset)) {
            if (content == null) {
                if (!state.busy) {
                    Text(stringResource(R.string.widget_import_variant_failed))
                    FitButton(stringResource(R.string.widget_import_retry),
                        { state.variant?.let(viewModel::selectVariant) })
                }
            } else if (content.background == null) {
                Text(stringResource(R.string.editor_bg_import_missing),
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.editor_bg_import_primary),
                    style = MaterialTheme.typography.bodyMedium)
                if (content.fullPanelImageCount > 1) {
                    Text(stringResource(R.string.editor_bg_import_multiple),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.fitText.secondary)
                }
                FitButton(stringResource(R.string.widget_import_review), viewModel::reviewBackground,
                    Modifier.fillMaxWidth(), enabled = !state.busy, loading = state.busy)
            }
        }
    }
}

@Composable
private fun BackgroundReviewPage(state: WidgetImportUiState, snapshot: EditorSnapshot,
    viewModel: WidgetImportViewModel, modifier: Modifier) {
    val preview = state.backgroundPreview ?: return
    var before by remember(preview.ticket) { mutableStateOf(false) }
    BackgroundPageBody(if (before) snapshot.composedPreview else preview.preview, modifier,
        stringResource(R.string.editor_bg_import_result)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FitChip(stringResource(R.string.editor_bg_import_before), before, { before = true }, Modifier.weight(1f))
            FitChip(stringResource(R.string.editor_bg_import_after), !before, { before = false }, Modifier.weight(1f))
        }
        Text(stringResource(R.string.editor_bg_import_scope, preview.changedVariants.map { backgroundVariantLabel(it) }.joinToString()),
            style = MaterialTheme.typography.bodyMedium)
        if (preview.skippedVariants.isNotEmpty()) {
            Text(stringResource(R.string.editor_bg_import_skipped,
                preview.skippedVariants.map { backgroundVariantLabel(it) }.joinToString()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.fitText.secondary)
        }
        FitButton(stringResource(if (preview.addedBackground) R.string.editor_bg_import_add else R.string.editor_bg_import_replace),
            viewModel::applyBackground, Modifier.fillMaxWidth(), enabled = !state.busy, loading = state.saving)
        Text(stringResource(R.string.editor_bg_import_result_detail),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.fitText.secondary)
    }
}

@Composable
private fun backgroundVariantLabel(name: String): String = importVariantLabel(EditorVariant(name,
    if (name == AOD_ENTRY_NAME) VariantKind.AOD else VariantKind.STYLE, EditorVariant.styleNumberOf(name)))

/** Scrollable even when long target lists or large text leave little room for the preview. */
@Composable
private fun BackgroundPageBody(frame: PreviewFrame?, modifier: Modifier, description: String,
    content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val width = frame?.let { minOf(maxWidth - 32.dp, maxHeight * .55f * it.width / it.height, 186.dp) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (frame != null && width != null) {
                androidx.compose.foundation.Image(frame.rememberImportBitmap(), description,
                    Modifier.width(width).aspectRatio(frame.width.toFloat() / frame.height)
                        .background(Color.Black, MaterialTheme.shapes.medium).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Fit)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

// ---------------------------------------------------------------------------
// 2 · the widget, picked off the donor face
// ---------------------------------------------------------------------------

@Composable
private fun DonorWidgetsPage(
    state: WidgetImportUiState,
    snapshot: EditorSnapshot,
    viewModel: WidgetImportViewModel,
    modifier: Modifier,
) {
    val content = state.content
    Column(modifier) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.donor?.variants.orEmpty(), key = { it.basename }) { variant ->
                FitChip(importVariantLabel(variant), state.variant == variant,
                    { viewModel.selectVariant(variant) },
                    enabled = !state.saving && state.progress == null)
            }
        }
        // Space always held for it, so a load that shows a bar moves nothing; and the bar
        // only comes up once a load has taken long enough to be worth saying so.
        QuietProgress(state.variantLoading, Modifier.padding(horizontal = 16.dp))
        if (content == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!state.busy) {
                    Text(stringResource(R.string.widget_import_variant_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.fitText.secondary)
                    FitButton(stringResource(R.string.widget_import_retry),
                        { state.variant?.let(viewModel::selectVariant) },
                        style = FitButtonStyle.Secondary)
                }
            }
            return@Column
        }
        if (state.showList) {
            // The panel stays below the list, so picking from either view has the same
            // summary and the same way on. The list used to replace the whole page, which
            // with a set to build meant the action button was only on the other view.
            DonorWidgetList(state, content, snapshot, viewModel, Modifier.weight(1f))
            DonorSelectionPanel(state, content, snapshot, viewModel,
                Modifier.padding(top = 8.dp, bottom = 12.dp), compact = true)
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val face: @Composable (Modifier) -> Unit = { faceModifier ->
                BoxWithConstraints(
                    modifier = faceModifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    DonorFaceCanvas(
                        content = content,
                        picks = state.picks,
                        // Same reason as the rows: pricing one pick must not block the next.
                        // A variant still loading is the other variant's face, so no pick.
                        enabled = !state.saving && !state.variantLoading,
                        onSelect = { index ->
                            if (index == null) viewModel.clearSelectedWidget()
                            else viewModel.togglePick(index)
                        },
                        modifier = Modifier.width(
                            minOf(
                                maxWidth,
                                maxHeight * (content.composed.width.toFloat() / content.composed.height),
                                ImportCanvasMaxWidth,
                            ),
                        ),
                    )
                }
            }
            val controls: @Composable (Modifier) -> Unit = { controlModifier ->
                Column(controlModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MicroLabel(
                            stringResource(
                                R.string.widget_import_pick_count,
                                content.widgets.count { it.globalIndex !in content.unavailable },
                                content.widgets.size,
                            ),
                        )
                        TextButton({ viewModel.setShowList(true) }) {
                            Text(stringResource(R.string.widget_import_list),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    DonorSelectionPanel(state, content, snapshot, viewModel)
                }
            }
            if (importPageSplits(maxWidth, maxHeight)) {
                Row(Modifier.fillMaxSize()) {
                    face(Modifier.weight(1f).fillMaxHeight())
                    controls(
                        Modifier.weight(1f).fillMaxHeight()
                            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    face(Modifier.weight(1f).fillMaxWidth())
                    controls(Modifier.fillMaxWidth().padding(bottom = 12.dp))
                }
            }
        }
    }
}

/**
 * The donor face, drawn from its own resources, with a rectangle round everything that can
 * be added and the picked one highlighted.
 *
 * The same picture and the same hit test as the editor's own canvas — [hitWidget] decides
 * overlaps here exactly as it does there. Everything the gesture reads comes through
 * [rememberUpdatedState]: a `pointerInput` block keeps the values it was started with, and
 * the variant chips change the widget list underneath it without changing its keys.
 */
@Composable
private fun DonorFaceCanvas(
    content: WidgetDonorVariant,
    picks: List<Int>,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val face = content.composed.rememberImportBitmap()
    val importable = remember(content) {
        content.widgets.filter { it.globalIndex !in content.unavailable }
    }
    val latestWidgets by rememberUpdatedState(importable)
    // The last pick is what an overlap should resolve to, the way the editor's canvas
    // prefers the widget already selected.
    val latestSelected by rememberUpdatedState(picks.lastOrNull())
    val latestEnabled by rememberUpdatedState(enabled)
    // The panel extents the hit test scales by, and the callback it reports to, for the
    // same reason as the three above: the block below runs on `Unit`, so switching variant
    // would otherwise leave it measuring against the face it was started with.
    val latestFace by rememberUpdatedState(content.composed)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val guideColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.widget_import_canvas_a11y, importable.size)
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth()
            .aspectRatio(content.composed.width.toFloat() / content.composed.height)
            .clip(RoundedCornerShape(FaceOutlineCorner))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    if (!latestEnabled) return@detectTapGestures
                    latestOnSelect(
                        hitWidget(
                            widgets = latestWidgets,
                            point = position,
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            faceWidth = latestFace.width,
                            faceHeight = latestFace.height,
                            preferredGlobalIndex = latestSelected,
                        )?.globalIndex,
                    )
                }
            },
    ) {
        drawRect(Color.Black)
        drawImage(
            image = face,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
        val scaleX = size.width / content.composed.width
        val scaleY = size.height / content.composed.height
        clipRect {
            importable.forEach { widget ->
                val order = picks.indexOf(widget.globalIndex)
                drawRect(
                    color = if (order >= 0) selectedColor else guideColor.copy(alpha = .55f),
                    topLeft = Offset((widget.drawLeft + widget.visualBounds.left) * scaleX, (widget.drawTop + widget.visualBounds.top) * scaleY),
                    size = Size(widget.visualBounds.width * scaleX, widget.visualBounds.height * scaleY),
                    style = Stroke(if (order >= 0) 2.dp.toPx() else 1.dp.toPx()),
                )
                if (order < 0) return@forEach
                // The pick's number, because pick order is the order they are added and so
                // the z-order they end up in. A ring that only says "chosen" cannot say
                // which of three was chosen first.
                drawPickBadge(order + 1, Offset((widget.drawLeft + widget.visualBounds.left) * scaleX, (widget.drawTop + widget.visualBounds.top) * scaleY),
                    selectedColor, textMeasurer)
            }
        }
        drawRect(color = borderColor, style = Stroke(2.dp.toPx()))
    }
}

/**
 * What the picked widget is, what it costs, and the way on.
 *
 * The cost is the *exact* one: selecting a widget builds the edit, so the figure here is
 * the same one the review page shows rather than the row estimate.
 */
@Composable
private fun DonorSelectionPanel(
    state: WidgetImportUiState,
    content: WidgetDonorVariant,
    snapshot: EditorSnapshot,
    viewModel: WidgetImportViewModel,
    modifier: Modifier = Modifier,
    /** Under the list, where the rows already carry the pick numbers and the height is theirs. */
    compact: Boolean = false,
) {
    if (state.picks.isEmpty()) {
        Text(
            stringResource(R.string.widget_import_pick_hint),
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
        return
    }
    if (state.picks.size > 1) {
        ImportSetPanel(state, content, snapshot, viewModel, modifier, compact)
        return
    }
    val widget = content.widgets.firstOrNull { it.globalIndex == state.picks.single() } ?: return
    val layer = content.layers.firstOrNull { it.globalIndex == widget.globalIndex }
    val added = state.preview?.addedBytes ?: content.addedBytes[widget.globalIndex]
    val fits = added == null || snapshot.containerBytes + added <= WATCH_CONTAINER_BYTE_CEILING
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ImportArtworkTile(layer, Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(importWidgetName(widget), style = MaterialTheme.typography.titleSmall)
                    MicroLabel(widget.category.label, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    stringResource(R.string.widget_import_widget_size, widget.width, widget.height),
                    style = FitFaceType.numeric,
                    color = MaterialTheme.fitText.secondary,
                )
                added?.let {
                    Text(
                        stringResource(R.string.widget_import_adds_value, importBytes(it.toLong())),
                        style = FitFaceType.numeric,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        ImportRoomMeter(snapshot, added, verdict = false)
        // The pricing failure first, because it is the exact answer and the meter's is an
        // estimate; then the estimate's own verdict; then the way on.
        ImportPanelMessage(
            text = state.pickError ?: stringResource(
                if (fits) R.string.widget_import_pick_hint_more else R.string.widget_import_too_big,
            ),
            warning = state.pickError != null || !fits,
        )
        FitButton(
            stringResource(R.string.widget_import_use),
            viewModel::useSelectedWidget,
            Modifier.fillMaxWidth(),
            enabled = !state.busy && state.preview != null,
            loading = state.busy && state.preview == null,
        )
    }
}

/**
 * The set, once more than one widget is picked.
 *
 * There is no exact price here and there cannot be: the repository holds one pending import
 * at a time, so quoting the second widget would destroy the first one's ticket. The figures
 * are `addedBytesEstimate`, which is a documented **floor**, so the panel says "at least"
 * and "may not all fit" rather than promising a fit it cannot know. The 4 MiB ceiling is
 * still enforced once, in `rebuild`, on every one of the commits this starts.
 *
 * **Its height does not depend on how many are picked.** The face above it is fitted to
 * whatever height the panel leaves, so every line the panel grows is a line off the face:
 * the chips used to wrap, and five picks took the face down to a dot. They scroll sideways
 * on one line now, and the message under the meter is one slot of one height whichever
 * of its two things it is saying.
 */
@Composable
private fun ImportSetPanel(
    state: WidgetImportUiState,
    content: WidgetDonorVariant,
    snapshot: EditorSnapshot,
    viewModel: WidgetImportViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean,
) {
    val floor = state.picks.sumOf { content.addedBytes[it] ?: 0 }
    val fits = snapshot.containerBytes + floor <= WATCH_CONTAINER_BYTE_CEILING
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MicroLabel(stringResource(R.string.widget_import_selected, state.picks.size))
                Text(
                    stringResource(R.string.widget_import_set_estimate, importBytes(floor.toLong())),
                    style = FitFaceType.numeric,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(viewModel::clearSelectedWidget, enabled = !state.saving) {
                Text(stringResource(R.string.widget_import_clear),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        // In pick order, because that is the order they are added and so the z-order they
        // land in — the set is a list, not a bag. Left out under the widget list, where
        // every row already wears its own number and the height belongs to the rows.
        if (!compact) {
            val chips = rememberLazyListState()
            // The newest pick is the one just tapped, so it is the one to keep in view.
            LaunchedEffect(state.picks.size) {
                if (state.picks.isNotEmpty()) chips.animateScrollToItem(state.picks.lastIndex)
            }
            LazyRow(state = chips, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.picks, key = { _, index -> index }) { order, index ->
                    val widget = content.widgets.firstOrNull { it.globalIndex == index }
                        ?: return@itemsIndexed
                    ImportPickChip(order + 1, importWidgetName(widget), !state.saving) {
                        viewModel.togglePick(index)
                    }
                }
            }
        }
        ImportRoomMeter(snapshot, floor, verdict = false)
        // Under the list only the refusal earns the height; the caveat is on the review.
        if (!compact || !fits) {
            ImportPanelMessage(
                text = stringResource(
                    if (fits) R.string.widget_import_set_floor else R.string.widget_import_too_big_set,
                ),
                warning = !fits,
            )
        }
        FitButton(
            stringResource(R.string.widget_import_use_many, state.picks.size),
            viewModel::useSelectedWidget,
            Modifier.fillMaxWidth(),
            enabled = !state.saving && !state.busy,
        )
    }
}

/**
 * The line under a panel's meter: the way on, or why there is none.
 *
 * Two lines tall whatever it says, so that swapping the hint for a refusal — which is what
 * crossing the ceiling does — does not move the face above it.
 */
@Composable
private fun ImportPanelMessage(text: String, warning: Boolean) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.fitText.secondary,
    )
}

/**
 * An indeterminate bar in a fixed 2dp slot, shown only once [active] has lasted past a short
 * grace period. A load quicker than that shows nothing at all, which is the point: a bar that
 * appears and vanishes inside a fifth of a second is the flicker it was meant to explain.
 */
@Composable
private fun QuietProgress(active: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        visible = false
        if (active) {
            kotlinx.coroutines.delay(QuietProgressGraceMillis)
            visible = true
        }
    }
    Box(modifier.fillMaxWidth().height(2.dp)) {
        if (visible) LinearProgressIndicator(Modifier.fillMaxSize())
    }
}

private const val QuietProgressGraceMillis = 300L

/** One pick, numbered, tappable to take it back out of the set. */
@Composable
private fun ImportPickChip(order: Int, name: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .13f),
                RoundedCornerShape(999.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("$order", style = FitFaceType.micro, color = MaterialTheme.colorScheme.primary)
        Text(name, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text("✕", style = FitFaceType.micro, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * How much of the watch's 4 MiB this face would be using afterwards.
 *
 * The ceiling is settled and `rebuild` refuses to cross it, so a widget that cannot fit is
 * worth knowing about while there is still another one to pick.
 */
@Composable
private fun ImportRoomMeter(
    snapshot: EditorSnapshot,
    added: Int?,
    set: Boolean = false,
    /** Off in the pickers' panels, which say it in their own fixed-height slot. */
    verdict: Boolean = true,
) {
    val after = snapshot.containerBytes + (added ?: 0)
    val fits = after <= WATCH_CONTAINER_BYTE_CEILING
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MicroLabel(stringResource(R.string.widget_import_room))
            Text(
                stringResource(R.string.widget_import_room_value,
                    importBytes(after.toLong()), importBytes(WATCH_CONTAINER_BYTE_CEILING.toLong())),
                style = FitFaceType.numeric,
                color = if (fits) MaterialTheme.fitText.secondary else MaterialTheme.colorScheme.error,
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(
                Modifier.fillMaxWidth(
                    (after.toFloat() / WATCH_CONTAINER_BYTE_CEILING).coerceIn(0f, 1f),
                ).fillMaxHeight().background(
                    if (fits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                ),
            )
        }
        if (!fits && verdict) {
            Text(
                stringResource(
                    if (set) R.string.widget_import_too_big_set else R.string.widget_import_too_big,
                ),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The list the face cannot replace.
 *
 * A rotating hand has no axis-aligned rectangle to tap and a widget drawn off the panel
 * has none on screen, so the rows stay — split into what can be added and what cannot,
 * because a refused row that looks like an offered one is a tap that reads as broken.
 */
@Composable
private fun DonorWidgetList(
    state: WidgetImportUiState,
    content: WidgetDonorVariant,
    snapshot: EditorSnapshot,
    viewModel: WidgetImportViewModel,
    modifier: Modifier,
) {
    val available = content.widgets.filter { it.globalIndex !in content.unavailable }
    val refused = content.widgets.filter { it.globalIndex in content.unavailable }
    // Kept across a trip to the review page: coming back to the top of the list meant
    // scrolling to the widget you had just looked at, every time.
    val listState = rememberLazyListState()
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                MicroLabel(stringResource(R.string.widget_import_section_can, available.size))
                TextButton({ viewModel.setShowList(false) }) {
                    Text(stringResource(R.string.widget_import_face),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        items(available, key = { it.globalIndex }) { widget ->
            DonorWidgetRow(
                widget = widget,
                layer = content.layers.firstOrNull { it.globalIndex == widget.globalIndex },
                added = content.addedBytes[widget.globalIndex],
                snapshot = snapshot,
                // The list stays open on a tap now: it is a picker for a set, and closing
                // it after every pick would make choosing three widgets three round trips.
                pickOrder = state.picks.indexOf(widget.globalIndex).takeIf { it >= 0 },
                pickCount = state.picks.size,
                // Not `!busy`. Picking the first widget starts pricing it, and a row
                // disabled for that swallowed the very next tap — so a set of three was a
                // tap, a wait, a tap, a wait, with nothing on screen saying why. Only a
                // commit is uninterruptible, and `togglePick` refuses during one.
                enabled = !state.saving && !state.variantLoading,
                onClick = { viewModel.togglePick(widget.globalIndex) },
            )
        }
        if (refused.isNotEmpty()) {
            item {
                MicroLabel(stringResource(R.string.widget_import_section_cannot, refused.size),
                    modifier = Modifier.padding(top = 10.dp))
            }
            items(refused, key = { "refused-${it.globalIndex}" }) { widget ->
                RefusedWidgetRow(
                    widget = widget,
                    layer = content.layers.firstOrNull { it.globalIndex == widget.globalIndex },
                    reason = content.unavailable[widget.globalIndex].orEmpty(),
                )
            }
        }
    }
}

@Composable
private fun DonorWidgetRow(
    widget: WidgetGuide,
    layer: WidgetImageLayer?,
    added: Int?,
    snapshot: EditorSnapshot,
    pickOrder: Int?,
    pickCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selected = pickOrder != null
    val fits = added == null || snapshot.containerBytes + added <= WATCH_CONTAINER_BYTE_CEILING
    // A row that toggles has to say which way it will go, and a picked one has to say
    // where in the order it sits — the order is the order they are added.
    val description = if (pickOrder == null) {
        stringResource(R.string.widget_import_pick_add_a11y, importWidgetName(widget))
    } else {
        stringResource(R.string.widget_import_pick_order_a11y,
            importWidgetName(widget), pickOrder + 1, pickCount)
    }
    Row(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .08f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.shapes.small,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.small,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.TopStart) {
            ImportArtworkTile(layer, Modifier.size(48.dp))
            pickOrder?.let { PickBadge(it + 1) }
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(importWidgetName(widget), style = MaterialTheme.typography.titleSmall)
                MicroLabel(widget.category.label, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                stringResource(R.string.widget_import_widget_size, widget.width, widget.height),
                modifier = Modifier.padding(top = 2.dp),
                style = FitFaceType.numeric,
                color = MaterialTheme.fitText.secondary,
            )
        }
        added?.let {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(R.string.widget_import_adds_about, importBytes(it.toLong())),
                    style = FitFaceType.numeric,
                    color = if (fits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (!fits) {
                    MicroLabel(stringResource(R.string.widget_import_no_room),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RefusedWidgetRow(widget: WidgetGuide, layer: WidgetImageLayer?, reason: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(12.dp)
            .alpha(DisabledCardAlpha),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ImportArtworkTile(layer, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(importWidgetName(widget), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.fitText.secondary)
            Text(reason, modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.fitText.secondary)
        }
    }
}

// ---------------------------------------------------------------------------
// 3 · the batch report
// ---------------------------------------------------------------------------

/**
 * What became of each widget of a set, while it runs and after it stops.
 *
 * This page exists because **there is no rollback across commits**. Each widget is its own
 * commit, so a set that stops at the third leaves the first two on the face and saved, and
 * the reader has to be told that in those words rather than shown a banner over a closing
 * dialog. Leaving the page — by Done or by back — is what hands the editor the snapshot the
 * last successful commit produced.
 */
@Composable
private fun ImportBatchPage(
    state: WidgetImportUiState,
    viewModel: WidgetImportViewModel,
    modifier: Modifier,
) {
    val names = state.content?.widgets.orEmpty().associateBy { it.globalIndex }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.batch.forEach { step ->
            ImportBatchRow(step, names[step.globalIndex]?.let { importWidgetName(it) }
                ?: stringResource(R.string.widget_import_widget_number, step.globalIndex))
        }
        if (state.batchRunning) {
            Text(
                stringResource(R.string.widget_import_batch_saving),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
            return@Column
        }
        Text(
            when {
                // Nothing committed: no warning to give, because there is nothing to undo.
                state.batchAdded == 0 -> stringResource(R.string.widget_import_batch_none)
                state.batchAdded == state.batch.size ->
                    stringResource(R.string.widget_import_batch_all_done, state.batchAdded)
                else -> stringResource(R.string.widget_import_batch_partial, state.batchAdded)
            },
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.fitText.secondary,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FitButton(
                // "Done" leaves the importer with what committed. With nothing committed it
                // goes back to the picker instead, so it says that rather than promising an
                // exit it will not make.
                stringResource(
                    if (state.batchAdded == 0) R.string.widget_import_back_to_widgets
                    else R.string.widget_import_done,
                ),
                viewModel::finishBatch,
                Modifier.weight(1f),
            )
            // Offered rather than automatic: every limit a batch can hit only gets tighter
            // as it goes, so carrying on past a refusal is a decision, not a retry.
            if (state.batchRemaining.isNotEmpty()) {
                FitButton(
                    stringResource(R.string.widget_import_batch_continue),
                    viewModel::continueBatch,
                    Modifier.weight(1f),
                    style = FitButtonStyle.Secondary,
                )
            }
        }
    }
}

@Composable
private fun ImportBatchRow(step: WidgetImportStep, name: String) {
    val (glyph, label, tint) = when (step.outcome) {
        WidgetImportOutcome.QUEUED -> Triple("·",
            stringResource(R.string.widget_import_batch_queued), MaterialTheme.fitText.secondary)
        WidgetImportOutcome.ADDING -> Triple("⟳",
            stringResource(R.string.widget_import_batch_adding), MaterialTheme.fitColors.warning)
        WidgetImportOutcome.ADDED -> Triple("✓",
            stringResource(R.string.widget_import_batch_added), MaterialTheme.colorScheme.primary)
        WidgetImportOutcome.FAILED -> Triple("✕", "", MaterialTheme.colorScheme.error)
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(glyph, style = FitFaceType.numeric, color = tint)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            step.reason?.let {
                Text(it, modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
        if (label.isNotEmpty()) {
            Text(label, style = FitFaceType.numeric, color = tint)
        }
    }
}

// ---------------------------------------------------------------------------
// 4 · the review
// ---------------------------------------------------------------------------

@Composable
private fun ImportReviewPage(
    state: WidgetImportUiState,
    snapshot: EditorSnapshot,
    viewModel: WidgetImportViewModel,
    modifier: Modifier,
) {
    val content = state.content
    if (state.picks.size > 1 && content != null) {
        ImportSetReviewPage(state, content, snapshot, viewModel, modifier)
        return
    }
    val preview = state.preview
    if (preview == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.widget_import_preview_gone),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.fitText.secondary)
        }
        return
    }
    ImportReviewLayout(
        frame = preview.preview,
        modifier = modifier,
        face = { faceModifier ->
            ImportReviewFace(
                after = preview.preview,
                before = snapshot.composedPreview,
                additions = listOf(preview.widget),
                description = stringResource(R.string.widget_import_review_a11y),
                modifier = faceModifier,
            )
        },
    ) {
        MicroLabel(stringResource(R.string.widget_import_compare), Modifier.fillMaxWidth())
        ImportFactCard {
            ImportFactRow(
                stringResource(R.string.widget_import_fact_from),
                importDonorLabel(state),
            )
            ImportFactRow(
                stringResource(R.string.widget_import_fact_widget),
                stringResource(R.string.widget_import_widget_size,
                    preview.widget.width, preview.widget.height),
            )
            ImportFactRow(
                stringResource(R.string.widget_import_fact_adds),
                importBytes(preview.addedBytes.toLong()),
                emphasised = true,
            )
            Box(Modifier.padding(top = 4.dp)) {
                ImportRoomMeter(snapshot, preview.addedBytes)
            }
        }
        FitButton(
            stringResource(R.string.widget_import_add_to,
                importVariantLabel(snapshot.selectedVariant)),
            viewModel::add,
            Modifier.fillMaxWidth(),
            enabled = !state.busy &&
                snapshot.containerBytes + preview.addedBytes <= WATCH_CONTAINER_BYTE_CEILING,
            loading = state.saving,
        )
        Text(
            stringResource(R.string.widget_import_review_detail,
                importVariantLabel(snapshot.selectedVariant)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
}

/**
 * The same review for a set: your face with every pick painted in, numbered in the order
 * they will be added, and the face as it is for as long as a finger is held on it.
 *
 * The one difference is where the "after" picture comes from. A single widget's is the
 * repository's render of the edited container, because that edit exists — it is the
 * ticket. A set's edit cannot exist before it is committed (one pending import, validated
 * by reference against a container every commit replaces), so its picture is
 * [composeImportSet]: each pick's own donor layer over the face as it is now. That is the
 * same picture and not an approximation of it, because `WidgetImporter` copies a widget's
 * rasters byte for byte and **checks** that it lands exactly where it sat on its own face.
 */
@Composable
private fun ImportSetReviewPage(
    state: WidgetImportUiState,
    content: WidgetDonorVariant,
    snapshot: EditorSnapshot,
    viewModel: WidgetImportViewModel,
    modifier: Modifier,
) {
    val before = snapshot.composedPreview
    val after = remember(before, content, state.picks) {
        composeImportSet(before, content, state.picks)
    }
    val additions = remember(content, state.picks) {
        state.picks.mapNotNull { index -> content.widgets.firstOrNull { it.globalIndex == index } }
    }
    val floor = state.picks.sumOf { content.addedBytes[it] ?: 0 }
    val target = importVariantLabel(snapshot.selectedVariant)
    ImportReviewLayout(
        frame = after,
        modifier = modifier,
        face = { faceModifier ->
            ImportReviewFace(
                after = after,
                before = before,
                additions = additions,
                numbered = true,
                description = stringResource(R.string.widget_import_review_set_a11y, additions.size),
                modifier = faceModifier,
            )
        },
    ) {
        MicroLabel(stringResource(R.string.widget_import_compare_set), Modifier.fillMaxWidth())
        ImportFactCard {
            ImportFactRow(stringResource(R.string.widget_import_fact_from), importDonorLabel(state))
            ImportFactRow(stringResource(R.string.widget_import_fact_widgets), "${state.picks.size}")
            ImportFactRow(
                stringResource(R.string.widget_import_fact_adds),
                stringResource(R.string.widget_import_at_least, importBytes(floor.toLong())),
                emphasised = true,
            )
            Box(Modifier.padding(top = 4.dp)) {
                ImportRoomMeter(snapshot, floor, set = true)
            }
        }
        // Not disabled over the ceiling, unlike the single review: the figure is a floor
        // for the set as a whole, and the widgets that do fit still go in, in order.
        FitButton(
            stringResource(R.string.widget_import_add_many_to, state.picks.size, target),
            viewModel::addPicks,
            Modifier.fillMaxWidth(),
            enabled = !state.busy && !state.saving,
            loading = state.saving,
        )
        Text(
            stringResource(R.string.widget_import_review_set_detail, target),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
}

/**
 * The review's face beside its facts, or above them.
 *
 * The face takes the height the facts leave, the way the canvas page does it. A `weight`
 * inside a scrolling column is measured against an infinite height, so the column that
 * holds one cannot be the one that scrolls.
 */
@Composable
private fun ImportReviewLayout(
    frame: PreviewFrame,
    modifier: Modifier,
    face: @Composable (Modifier) -> Unit,
    facts: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val faceSlot: @Composable (Modifier) -> Unit = { faceModifier ->
            BoxWithConstraints(
                modifier = faceModifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                face(
                    Modifier.width(
                        minOf(maxWidth, maxHeight * (frame.width.toFloat() / frame.height),
                            ImportCanvasMaxWidth),
                    ),
                )
            }
        }
        val factsSlot: @Composable (Modifier) -> Unit = { factsModifier ->
            Column(
                factsModifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = facts,
            )
        }
        if (importPageSplits(maxWidth, maxHeight)) {
            Row(Modifier.fillMaxSize()) {
                faceSlot(Modifier.weight(1f).fillMaxHeight())
                factsSlot(
                    Modifier.weight(1f).fillMaxHeight()
                        .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                faceSlot(Modifier.fillMaxWidth().weight(1f))
                factsSlot(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun ImportFactCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun importDonorLabel(state: WidgetImportUiState) =
    stringResource(R.string.widget_import_fact_from_value,
        state.selectedFace?.name ?: state.donor?.faceId.orEmpty(),
        state.variant?.let { importVariantLabel(it) }.orEmpty())

/**
 * The face as it would be, with everything but the additions dimmed — and the face as it
 * is for as long as a finger is held on it.
 *
 * Both pictures are renders of the same face before and after, so the comparison invents
 * nothing. A 2px rectangle on a busy face was the only thing saying what had changed.
 */
@Composable
private fun ImportReviewFace(
    after: PreviewFrame,
    before: PreviewFrame,
    additions: List<WidgetGuide>,
    description: String,
    modifier: Modifier = Modifier,
    /** A set's additions carry their add order, the way they did on the donor face. */
    numbered: Boolean = false,
) {
    var comparing by remember { mutableStateOf(false) }
    val afterBitmap = after.rememberImportBitmap()
    val original = before.rememberImportBitmap()
    val scrim = MaterialTheme.colorScheme.background.copy(alpha = .55f)
    val ringColor = MaterialTheme.colorScheme.primary
    val badgeColor = MaterialTheme.colorScheme.tertiary
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val textMeasurer = rememberTextMeasurer()
    // No caption inside this box. The face is fitted to the height it was handed, so a
    // line under it either takes height off the face or falls outside the box — the hint
    // lives with the facts instead, and an overlay would sit on the artwork it offers to
    // show.
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxWidth()
                .aspectRatio(after.width.toFloat() / after.height)
                .clip(RoundedCornerShape(FaceOutlineCorner))
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            comparing = true
                            tryAwaitRelease()
                            comparing = false
                        },
                    )
                },
        ) {
            drawRect(Color.Black)
            drawImage(
                image = if (comparing) original else afterBitmap,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
            if (!comparing) {
                val scaleX = size.width / after.width
                val scaleY = size.height / after.height
                val rects = additions.map {
                    Rect(Offset((it.drawLeft + it.visualBounds.left) * scaleX, (it.drawTop + it.visualBounds.top) * scaleY),
                        Size(it.visualBounds.width * scaleX, it.visualBounds.height * scaleY))
                }
                // One sheet with a hole cut for each addition, rather than a translucent
                // sheet over everything: the additions keep their own pixels untouched,
                // which is the whole point of showing them. Overlapping holes are one hole,
                // because every rectangle winds the same way.
                val holes = Path().apply { rects.forEach(::addRect) }
                clipPath(holes, ClipOp.Difference) { drawRect(scrim) }
                rects.forEachIndexed { order, rect ->
                    drawRect(ringColor, rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
                    if (numbered) drawPickBadge(order + 1, rect.topLeft, badgeColor, textMeasurer)
                }
            }
            drawRect(color = borderColor, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun ImportFactRow(label: String, value: String, emphasised: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        MicroLabel(label)
        Text(
            value,
            style = FitFaceType.numeric,
            color = if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.fitText.secondary,
        )
    }
}

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

/**
 * Whether the importer puts its face and its controls side by side.
 *
 * The same rule and the same reason as `canvasPageSplits`: stacked, a landscape phone
 * leaves the face, the panel and the button about 340dp between them, which clipped the
 * face and put the button below the fold. Pure so it can be pinned by a test —
 * `:feature:editor` cannot measure a composable.
 */
internal fun importPageSplits(maxWidth: Dp, maxHeight: Dp): Boolean =
    maxWidth >= ImportSideBySideMinWidth && maxHeight < ImportStackedMinHeight

/**
 * The face as a set would leave it: [before] with each pick's own donor layers painted over
 * it, **in pick order**, which is add order and so the z-order the imports produce.
 *
 * Exact rather than approximate, for two reasons the importer enforces rather than hopes
 * for: it copies a widget's rasters byte for byte, and it checks the widget's drawn position
 * on the target is the one it had on its donor. So a donor layer at its donor position is
 * the pixels the imported widget will draw. Pure so it can be pinned by a test.
 */
internal fun composeImportSet(
    before: PreviewFrame,
    content: WidgetDonorVariant,
    picks: List<Int>,
): PreviewFrame {
    val layers = picks.flatMap { index -> content.layers.filter { it.globalIndex == index } }
    val additions = WidgetLayerComposer.compose(
        before.width, before.height, layers, content.widgets, transparent = true,
    )
    return PreviewFrame(before.width, before.height, IntArray(before.argb.size) { i ->
        WidgetLayerComposer.over(before.argb[i], additions.argb[i])
    })
}

private val ImportStackedMinHeight = 480.dp
private val ImportSideBySideMinWidth = 560.dp
private val ImportCanvasMaxWidth = 300.dp
private const val DisabledCardAlpha = .45f

@Composable
internal fun importVariantLabel(variant: EditorVariant) = if (variant.kind == VariantKind.AOD)
    stringResource(R.string.widget_import_aod) else styleLabel(variant.styleNumber ?: 0)

/**
 * What a widget is called on screen: its reading where the format names one, else its index.
 *
 * Capitalised here and nowhere else. `sourceLabel` is the format layer's own label — `hour
 * units`, `steps` — written for a line of record detail, and this is the one place it is a
 * page title.
 */
@Composable
private fun importWidgetName(widget: WidgetGuide): String = widget.sourceLabel
    ?.replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
    ?: stringResource(R.string.widget_import_widget_number, widget.globalIndex)

@Composable
private fun ImportArtworkTile(layer: WidgetImageLayer?, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (layer == null) {
            MicroLabel(stringResource(R.string.widget_import_no_artwork))
        } else {
            ImportArtwork(layer.frame, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ImportArtwork(frame: PreviewFrame, modifier: Modifier) {
    val bitmap = frame.rememberImportBitmap()
    androidx.compose.foundation.Image(bitmap, stringResource(R.string.widget_import_artwork),
        modifier, contentScale = ContentScale.Fit)
}

@Composable
private fun PreviewFrame.rememberImportBitmap(): ImageBitmap {
    val bitmap = remember(argb) { Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888) }
    DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
    return remember(bitmap) { bitmap.asImageBitmap() }
}

/** `29.2 KB`, `2.31 MB` — the unit a reader thinks in, not a nine-digit byte count. */
@Composable
private fun importBytes(bytes: Long): String = if (bytes >= 1L shl 20) {
    stringResource(R.string.widget_import_megabytes, bytes / 1_048_576f)
} else {
    stringResource(R.string.widget_import_kilobytes, bytes / 1024f)
}
