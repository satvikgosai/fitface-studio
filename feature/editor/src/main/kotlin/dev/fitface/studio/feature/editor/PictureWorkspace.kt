package dev.fitface.studio.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.VideoClip
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.animationFrameBytes
import dev.fitface.studio.core.model.WidgetResizeStepPercent
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.fitText

/** What placing a picture can do; see [EditorViewModel.preparePicture] and what follows it. */
internal class PictureActions(
    val onChoose: () -> Unit,
    /** Pick a GIF or video to place as a moving picture; see [EditorViewModel.prepareMovingPicture]. */
    val onChooseMoving: () -> Unit,
    /** A moving picture's frame step. */
    val onStep: (Int) -> Unit,
    val onTransform: (Float, Float, Float) -> Unit,
    val onStepSize: (Boolean) -> Unit,
    val onReset: () -> Unit,
    val onTint: (Int?) -> Unit,
    val onDiscard: () -> Unit,
    val onAdd: () -> Unit,
    val onAddedShown: (Long) -> Unit,
    /** A moving picture's video clip moved or stretched; read again. */
    val onClip: (VideoClip) -> Unit,
)

/**
 * Placing a picked picture before it is added: the Background page's controls, in the same
 * order, for a widget. A moving picture adds what a moving background does — it plays on the
 * face, with the frames kept, the loop and, for a video, the part read — and has its size
 * chosen here for good, since an Animation is not resized once added.
 *
 * The face draws the picture where and how it will land; a pinch sizes it and a drag moves
 * it, exactly as they place a background. Under it are the same placement controls — its
 * size steps its own resize ladder, the rungs Smaller and Larger step once it is added — and
 * the same colour control, then Discard and Add. There is no fit: a picture keeps its
 * proportions, where a background has to cover the face somehow.
 */
@Composable
internal fun PictureWorkspace(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    pending: PendingPicture,
    pictures: PictureActions,
    modifier: Modifier = Modifier,
) {
    val panel = snapshot.preview
    val placement = pending.placement(panel.width, panel.height)
    val size = pending.size
    val fits = snapshot.containerBytes + pending.addedBytes <= WATCH_CONTAINER_BYTE_CEILING
    val enabled = !state.isWorking
    val animation = pending.animation
    var playing by remember { mutableStateOf(true) }
    val frame = rememberPlayingFrame(animation, playing)
    // The face draws the frame showing now, through the same pending-picture path a still uses.
    val shown = frame?.let { pending.copy(prepared = pending.prepared.copy(preview = it)) } ?: pending
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DirectWatchCanvas(
            snapshot = snapshot,
            editing = false,
            selectedGlobalIndex = null,
            pendingWidgetMove = null,
            pendingImage = null,
            placement = placement,
            enabled = enabled,
            onWidget = {},
            onMoveWidget = { _, _, _ -> },
            onTransformImage = pictures.onTransform,
            modifier = Modifier.widthIn(max = 186.dp),
            pendingPicture = shown,
        )
        if (animation != null) {
            TextButton(onClick = { playing = !playing }) {
                Text(
                    stringResource(if (playing) R.string.editor_moving_pause else R.string.editor_moving_play),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        ImportFactCard {
            ImportFactRow(
                stringResource(if (animation != null) R.string.editor_moving_picture_fact else R.string.editor_picture_fact),
                stringResource(R.string.widget_import_widget_size, size.width, size.height),
            )
            animation?.let {
                ImportFactRow(
                    stringResource(R.string.editor_moving_fact_frames),
                    stringResource(R.string.editor_moving_frames_of, it.plan.frames.size, it.source.frames.size),
                )
                ImportFactRow(stringResource(R.string.editor_moving_fact_loop),
                    stringResource(R.string.editor_moving_seconds, loopSeconds(it.plan.loopMs)))
            }
            ImportFactRow(
                stringResource(R.string.widget_import_fact_adds),
                importBytes(pending.addedBytes.toLong()),
                emphasised = true,
            )
            Box(Modifier.padding(top = 4.dp)) { ImportRoomMeter(snapshot, pending.addedBytes) }
        }
        PlacementControls(
            placement = placement,
            panelWidth = panel.width,
            panelHeight = panel.height,
            enabled = enabled,
            onTransformImage = pictures.onTransform,
            onStepZoom = pictures.onStepSize,
            onResetPlacement = pictures.onReset,
            minZoomPercent = pending.prepared.ladder.first().percentOfOriginal,
            maxZoomPercent = pending.prepared.ladder.last().percentOfOriginal,
            stepPercent = WidgetResizeStepPercent,
        )
        if (animation != null) {
            FrameStepControls(animation, animation.source.frames.size, enabled,
                animationFrameBytes(size.width, size.height), pictures.onStep)
            animation.source.clip?.let { VideoClipControls(it, enabled, state.readingFrames, pictures.onClip) }
        }
        ImageColourControls(pending.tint, enabled = enabled, onTint = pictures.onTint)
        if (animation != null) {
            MovingBackgroundNotes(snapshot, video = animation.source.clip != null)
        } else if (snapshot.isAodSelected) {
            Text(
                stringResource(R.string.editor_picture_aod_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FitButton(
                stringResource(R.string.editor_bg_discard),
                pictures.onDiscard, Modifier.weight(1f), enabled,
                style = FitButtonStyle.Secondary,
            )
            FitButton(
                stringResource(R.string.widget_import_add_to, importVariantLabel(snapshot.selectedVariant)),
                pictures.onAdd,
                Modifier.weight(2f),
                enabled && fits,
                loading = state.isWorking,
            )
        }
        Text(
            stringResource(if (animation != null) R.string.editor_moving_picture_detail else R.string.editor_picture_detail,
                importVariantLabel(snapshot.selectedVariant)),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
}
