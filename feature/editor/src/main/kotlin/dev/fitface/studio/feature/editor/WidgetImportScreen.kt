package dev.fitface.studio.feature.editor

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.fitface.studio.core.model.*
import dev.fitface.studio.core.ui.*
import kotlin.math.roundToInt

@Composable
internal fun WidgetImportRoute(snapshot: EditorSnapshot, onDismiss: () -> Unit,
    onImported: (EditorSnapshot) -> Unit, viewModel: WidgetImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start(snapshot) }
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
                        WidgetImportStage.FACES -> stringResource(R.string.widget_import_faces)
                        WidgetImportStage.WIDGETS -> state.selectedFace?.name
                            ?: stringResource(R.string.widget_import_widgets)
                        WidgetImportStage.REVIEW -> state.preview?.widget?.let { importWidgetName(it) }
                            ?: stringResource(R.string.widget_import_review)
                    },
                    subtitle = when (state.stage) {
                        WidgetImportStage.FACES -> stringResource(R.string.widget_import_target,
                            importVariantLabel(snapshot.selectedVariant))
                        else -> stringResource(R.string.widget_import_donor_subtitle,
                            state.donor?.faceId ?: state.selectedFace?.faceId.orEmpty(),
                            importVariantLabel(snapshot.selectedVariant))
                    },
                    onBack = back,
                )
                // Pinned, not scrolled with the content. As a list item the progress bar
                // and its Cancel button sat at the top of a list someone had scrolled far
                // down to tap a face — a transfer with no visible way to stop it.
                ImportNotices(state, viewModel)
                when (state.stage) {
                    WidgetImportStage.FACES -> DonorFacesPage(state, viewModel, Modifier.weight(1f))
                    WidgetImportStage.WIDGETS ->
                        DonorWidgetsPage(state, snapshot, viewModel, Modifier.weight(1f))
                    WidgetImportStage.REVIEW ->
                        ImportReviewPage(state, snapshot, viewModel, Modifier.weight(1f))
                }
            }
        }
    }
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
                stringResource(R.string.widget_import_experimental_short),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
        state.error?.let { error ->
            StatusBanner(FitStatus.Fail, error, Modifier.padding(top = 6.dp, end = 16.dp))
        }
        if (state.busy) {
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
                    { viewModel.selectVariant(variant) }, enabled = !state.busy)
            }
        }
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
            DonorWidgetList(state, content, snapshot, viewModel, Modifier.weight(1f))
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
                        selected = state.selectedWidget,
                        enabled = !state.busy,
                        onSelect = { index ->
                            if (index == null) viewModel.clearSelectedWidget()
                            else viewModel.selectWidget(index)
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
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val face = content.composed.rememberImportBitmap()
    val importable = remember(content) {
        content.widgets.filter { it.globalIndex !in content.unavailable }
    }
    val latestWidgets by rememberUpdatedState(importable)
    val latestSelected by rememberUpdatedState(selected)
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
    Canvas(
        modifier = modifier.fillMaxWidth()
            .aspectRatio(content.composed.width.toFloat() / content.composed.height)
            .clip(RoundedCornerShape(32.dp))
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
                val isSelected = widget.globalIndex == selected
                drawRect(
                    color = if (isSelected) selectedColor else guideColor.copy(alpha = .55f),
                    topLeft = Offset(widget.drawLeft * scaleX, widget.drawTop * scaleY),
                    size = Size(widget.width * scaleX, widget.height * scaleY),
                    style = Stroke(if (isSelected) 2.dp.toPx() else 1.dp.toPx()),
                )
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
) {
    val widget = content.widgets.firstOrNull { it.globalIndex == state.selectedWidget }
    if (widget == null) {
        Text(
            stringResource(R.string.widget_import_pick_hint),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
        return
    }
    val layer = content.layers.firstOrNull { it.globalIndex == widget.globalIndex }
    val added = state.preview?.addedBytes ?: content.addedBytes[widget.globalIndex]
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
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
        ImportRoomMeter(snapshot, added)
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
 * How much of the watch's 4 MiB this face would be using afterwards.
 *
 * The ceiling is settled and `rebuild` refuses to cross it, so a widget that cannot fit is
 * worth knowing about while there is still another one to pick.
 */
@Composable
private fun ImportRoomMeter(snapshot: EditorSnapshot, added: Int?) {
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
        if (!fits) {
            Text(
                stringResource(R.string.widget_import_too_big),
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
                selected = state.selectedWidget == widget.globalIndex,
                enabled = !state.busy,
                onClick = { viewModel.selectWidget(widget.globalIndex); viewModel.setShowList(false) },
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
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fits = added == null || snapshot.containerBytes + added <= WATCH_CONTAINER_BYTE_CEILING
    Row(
        modifier = Modifier.fillMaxWidth()
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
        ImportArtworkTile(layer, Modifier.size(48.dp))
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
// 3 · the review
// ---------------------------------------------------------------------------

@Composable
private fun ImportReviewPage(
    state: WidgetImportUiState,
    snapshot: EditorSnapshot,
    viewModel: WidgetImportViewModel,
    modifier: Modifier,
) {
    val preview = state.preview
    if (preview == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.widget_import_preview_gone),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.fitText.secondary)
        }
        return
    }
    BoxWithConstraints(modifier) {
        val face: @Composable (Modifier) -> Unit = { faceModifier ->
            BoxWithConstraints(
                modifier = faceModifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                ImportReviewFace(
                    preview = preview,
                    before = snapshot.composedPreview,
                    modifier = Modifier.width(
                        minOf(
                            maxWidth,
                            maxHeight * (preview.preview.width.toFloat() / preview.preview.height),
                            ImportCanvasMaxWidth,
                        ),
                    ),
                )
            }
        }
        val facts: @Composable (Modifier) -> Unit = { factsModifier ->
            Column(
                factsModifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MicroLabel(
                    stringResource(R.string.widget_import_compare),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow,
                            MaterialTheme.shapes.medium)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ImportFactRow(
                        stringResource(R.string.widget_import_fact_from),
                        stringResource(R.string.widget_import_fact_from_value,
                            state.selectedFace?.name ?: state.donor?.faceId.orEmpty(),
                            state.variant?.let { importVariantLabel(it) }.orEmpty()),
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
        if (importPageSplits(maxWidth, maxHeight)) {
            Row(Modifier.fillMaxSize()) {
                face(Modifier.weight(1f).fillMaxHeight())
                facts(
                    Modifier.weight(1f).fillMaxHeight()
                        .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                )
            }
        } else {
            // The face takes the height the facts leave, the way the canvas page does it.
            // A `weight` inside a scrolling column is measured against an infinite height,
            // so the column that holds one cannot be the one that scrolls.
            Column(Modifier.fillMaxSize()) {
                face(Modifier.fillMaxWidth().weight(1f))
                facts(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp))
            }
        }
    }
}

/**
 * The face as it would be, with everything but the addition dimmed — and the face as it is
 * for as long as a finger is held on it.
 *
 * Both pictures are real renders of the same container before and after the edit, so the
 * comparison invents nothing. A 2px rectangle on a busy face was the only thing saying what
 * had changed.
 */
@Composable
private fun ImportReviewFace(
    preview: WidgetImportPreview,
    before: PreviewFrame,
    modifier: Modifier = Modifier,
) {
    var comparing by remember { mutableStateOf(false) }
    val after = preview.preview.rememberImportBitmap()
    val original = before.rememberImportBitmap()
    val scrim = MaterialTheme.colorScheme.background.copy(alpha = .55f)
    val ringColor = MaterialTheme.colorScheme.primary
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.widget_import_review_a11y)
    // No caption inside this box. The face is fitted to the height it was handed, so a
    // line under it either takes height off the face or falls outside the box — the hint
    // lives with the facts instead, and an overlay would sit on the artwork it offers to
    // show.
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxWidth()
                .aspectRatio(preview.preview.width.toFloat() / preview.preview.height)
                .clip(RoundedCornerShape(32.dp))
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
                image = if (comparing) original else after,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
            if (!comparing) {
                val scaleX = size.width / preview.preview.width
                val scaleY = size.height / preview.preview.height
                val left = preview.widget.drawLeft * scaleX
                val top = preview.widget.drawTop * scaleY
                val width = preview.widget.width * scaleX
                val height = preview.widget.height * scaleY
                // Four rectangles rather than one translucent sheet with a hole in it:
                // the addition keeps its own pixels untouched, which is the whole point of
                // showing it.
                drawRect(scrim, Offset.Zero, Size(size.width, top))
                drawRect(scrim, Offset(0f, top + height), Size(size.width, size.height - top - height))
                drawRect(scrim, Offset(0f, top), Size(left, height))
                drawRect(scrim, Offset(left + width, top), Size(size.width - left - width, height))
                drawRect(ringColor, Offset(left, top), Size(width, height), style = Stroke(2.dp.toPx()))
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
