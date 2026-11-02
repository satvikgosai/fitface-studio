package dev.fitface.studio.feature.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.AnimationTiming
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.MovingBackground
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.VideoClip
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.animationAddedBytes
import dev.fitface.studio.core.model.animationFrameBytes
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitStatus
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.StatusBanner
import dev.fitface.studio.core.ui.fitText
import java.util.Locale
import kotlinx.coroutines.delay

/** What the moving background's controls can do beyond a still background's; see [EditorViewModel.prepareMovingBackground]. */
internal class MovingBackgroundActions(
    val onStep: (Int) -> Unit,
    /** A video's clip moved or stretched; read again. Shared with a moving picture. */
    val onClip: (VideoClip) -> Unit,
    /** Remove, for a moving or a still background alike; see [EditorViewModel.removeBackground]. */
    val onRemoveBackground: () -> Unit,
)

/** The Pause/Play line under a playing face, kept with it when the face is pinned. */
internal val PlayToggleHeight = 48.dp

/** A loop's length in seconds, one decimal: "2.4". */
internal fun loopSeconds(milliseconds: Int): String = String.format(Locale.ROOT, "%.1f", milliseconds / 1000.0)

/**
 * The frame [pending] is showing now, stepping through its plan at the watch's own timing, so
 * dropping frames is seen before it is added. Null when nothing is being placed; frozen on the
 * current frame while [playing] is false.
 */
@Composable
internal fun rememberPlayingFrame(pending: PendingAnimation?, playing: Boolean): PreviewFrame? {
    if (pending == null) return null
    val plan = pending.plan
    val frames = pending.source.frames
    val frame by produceState(frames[plan.frames[plan.table.first()]], pending, playing) {
        var position = plan.table.indices.firstOrNull { frames[plan.frames[plan.table[it]]] === value } ?: 0
        while (playing) {
            value = frames[plan.frames[plan.table[position]]]
            delay(plan.periodMs.toLong())
            position = (position + 1) % plan.table.size
        }
    }
    return frame
}

/**
 * Placing a picked GIF as a moving background: the Background page's own controls, in its
 * order, then how many frames to keep and what that costs.
 *
 * The face plays the GIF as the watch will — the frames kept, at their timing — over the
 * style's widgets. Every frame takes the same placement and colour. The frame count is the
 * one control a still picture has no use for: each frame kept is about 100 KB, so a long GIF
 * keeps every 2nd or 3rd frame to fit, and fewer frames is the way to make room.
 */
@Composable
internal fun MovingBackgroundWorkspace(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    pending: PendingAnimation,
    actions: MovingBackgroundActions,
    onFit: (ImageFit) -> Unit,
    onTransformImage: (Float, Float, Float) -> Unit,
    onStepImageZoom: (Boolean) -> Unit,
    onResetPlacement: () -> Unit,
    onTint: (Int?) -> Unit,
    onDiscard: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.isWorking
    var playing by remember { mutableStateOf(true) }
    val frame = rememberPlayingFrame(pending, playing)
    val plan = pending.plan
    val panel = snapshot.preview
    val added = animationAddedBytes(plan.frames.size, plan.table.size, panel.width, panel.height)
    // A moving background already there makes way for this one, so the face grows by the difference.
    val growth = added - snapshot.movingBackgroundBytes()
    val fits = snapshot.containerBytes + growth <= WATCH_CONTAINER_BYTE_CEILING
    val total = pending.source.frames.size
    PinnedFacePage(
        snapshot,
        faceCap = 186.dp,
        modifier = modifier,
        faceExtra = PlayToggleHeight,
        face = { faceModifier ->
            DirectWatchCanvas(
                snapshot = snapshot,
                editing = false,
                selectedGlobalIndex = null,
                pendingWidgetMove = null,
                pendingImage = state.pendingImage?.let { image -> frame?.let { image.copy(preview = it) } ?: image },
                placement = state.placement,
                enabled = enabled,
                onWidget = {},
                onMoveWidget = { _, _, _ -> },
                onTransformImage = onTransformImage,
                modifier = faceModifier,
            )
            TextButton(onClick = { playing = !playing }) {
                Text(
                    stringResource(if (playing) R.string.editor_moving_pause else R.string.editor_moving_play),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    ) {
        ImportFactCard {
            ImportFactRow(
                stringResource(R.string.editor_moving_fact_frames),
                stringResource(R.string.editor_moving_frames_of, plan.frames.size, total),
            )
            ImportFactRow(stringResource(R.string.editor_moving_fact_loop),
                stringResource(R.string.editor_moving_seconds, loopSeconds(plan.loopMs)))
            ImportFactRow(
                stringResource(R.string.widget_import_fact_adds),
                importBytes(added.toLong()),
                emphasised = true,
            )
            Box(Modifier.padding(top = 4.dp)) { ImportRoomMeter(snapshot, growth) }
        }
        FrameStepControls(pending, total, enabled, animationFrameBytes(panel.width, panel.height), actions.onStep)
        pending.source.clip?.let { VideoClipControls(it, enabled, state.readingFrames, actions.onClip) }
        FitControls(state.placement.fit, enabled, onFit)
        PlacementControls(
            placement = state.placement,
            edges = imageEdges(pending.source.width, pending.source.height, panel.width, panel.height, state.placement),
            panelWidth = panel.width,
            panelHeight = panel.height,
            enabled = enabled,
            onTransformImage = onTransformImage,
            onStepZoom = onStepImageZoom,
            onResetPlacement = onResetPlacement,
            entryKey = pending.source.uri,
        )
        ImageColourControls(state.placement.tint, enabled = enabled, onTint = onTint, stateKey = pending.source.uri)
        MovingBackgroundNotes(snapshot, video = pending.source.clip != null)
        if (snapshot.offersBackgroundScope) {
            Text(
                stringResource(R.string.editor_moving_scope, snapshot.selectedVariantLabel()),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
        if (snapshot.selectedBackgroundShown) MovingBackgroundReplacedNote()
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FitButton(
                stringResource(R.string.editor_bg_discard),
                onDiscard, Modifier.weight(1f), enabled,
                style = FitButtonStyle.Secondary,
            )
            FitButton(
                stringResource(R.string.editor_moving_add),
                onAdd,
                Modifier.weight(2f),
                enabled && fits,
                loading = state.isWorking,
            )
        }
    }
}

/**
 * Fewer or more frames: every frame, every 2nd, every 3rd… The page opens at the smallest
 * step that fits; More goes back below it only as far as the table's 255 entries allow, and
 * Add stays off while the frames kept do not fit. [frameBytes] is what one frame costs here.
 */
@Composable
internal fun FrameStepControls(pending: PendingAnimation, total: Int, enabled: Boolean, frameBytes: Int, onStep: (Int) -> Unit) {
    val smallest = AnimationTiming.smallestStep(total)
    ControlCard {
        MicroLabel(stringResource(R.string.editor_moving_frames_heading))
        Text(
            if (pending.step == 1) stringResource(R.string.editor_moving_every_frame)
            else stringResource(R.string.editor_moving_every_nth, pending.step),
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            RepeatingNudgeButton(
                stringResource(R.string.editor_moving_fewer),
                enabled && pending.step < total,
                Modifier.weight(1f),
            ) { onStep(pending.step + 1) }
            RepeatingNudgeButton(
                stringResource(R.string.editor_moving_more),
                enabled && pending.step > smallest,
                Modifier.weight(1f),
            ) { onStep(pending.step - 1) }
        }
        Text(
            stringResource(R.string.editor_moving_frames_hint, importBytes(frameBytes.toLong())),
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
}

/**
 * Which part of a video is read: Earlier/Later move the start and Shorter/Longer the length,
 * half a second at a time, and each reads the frames again. Plain buttons, not repeating
 * ones: every press is a read of the video.
 */
@Composable
internal fun VideoClipControls(clip: VideoClip, enabled: Boolean, reading: Boolean, onClip: (VideoClip) -> Unit) {
    ControlCard {
        MicroLabel(stringResource(R.string.editor_video_heading))
        @Composable
        fun line(text: String, back: String, forward: String, earlier: VideoClip?, later: VideoClip?) {
            Text(text, modifier = Modifier.padding(top = 9.dp), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                FitButton(back, { earlier?.let(onClip) }, Modifier.weight(1f), enabled && earlier != null,
                    style = FitButtonStyle.Secondary)
                FitButton(forward, { later?.let(onClip) }, Modifier.weight(1f), enabled && later != null,
                    style = FitButtonStyle.Secondary)
            }
        }
        line(stringResource(R.string.editor_video_start, loopSeconds(clip.startMs)),
            stringResource(R.string.editor_video_earlier), stringResource(R.string.editor_video_later),
            clip.earlier(), clip.later())
        line(stringResource(R.string.editor_video_length, loopSeconds(clip.lengthMs)),
            stringResource(R.string.editor_video_shorter), stringResource(R.string.editor_video_longer),
            clip.shorter(), clip.longer())
        Text(
            stringResource(R.string.editor_video_hint, loopSeconds(clip.durationMs)),
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
        if (reading) ReadingFramesLine(Modifier.padding(top = 9.dp))
    }
}

/**
 * Said while a GIF or video is read: a long video takes a few seconds, and greyed-out buttons
 * alone do not say why.
 */
@Composable
internal fun ReadingFramesLine(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.editor_reading_frames),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
}

/**
 * What the watch does with it, said before it is added and while it is there. Once added
 * nothing says whether it came from a GIF or a video, so that note names neither ([video] null).
 */
@Composable
internal fun MovingBackgroundNotes(snapshot: EditorSnapshot, video: Boolean?) {
    Text(
        stringResource(when (video) {
            true -> R.string.editor_moving_loops_video
            false -> R.string.editor_moving_loops
            null -> R.string.editor_moving_loops_added
        }),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.fitText.secondary,
    )
    if (snapshot.isAodSelected) {
        StatusBanner(FitStatus.Warning, stringResource(R.string.editor_moving_aod))
    }
}

/** Said while a new background is placed over a style that shows one: Apply replaces it. */
@Composable
internal fun MovingBackgroundReplacedNote() {
    Text(
        stringResource(R.string.editor_moving_replaced),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.fitText.secondary,
    )
}

/** The selected style's moving background: what it is. Remove takes it away; a new background replaces it. */
@Composable
internal fun MovingBackgroundPresent(snapshot: EditorSnapshot, moving: MovingBackground) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        MicroLabel(stringResource(R.string.editor_moving_heading))
        Text(
            stringResource(R.string.editor_moving_summary, moving.frames, loopSeconds(moving.loopMs)),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.editor_moving_present_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
    MovingBackgroundNotes(snapshot, video = null)
}
