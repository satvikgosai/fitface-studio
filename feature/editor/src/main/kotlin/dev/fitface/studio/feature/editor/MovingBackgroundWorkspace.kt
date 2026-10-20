package dev.fitface.studio.feature.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.movingBackgroundAddedBytes
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitChip
import dev.fitface.studio.core.ui.FitStatus
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.StatusBanner
import dev.fitface.studio.core.ui.fitText
import java.util.Locale
import kotlinx.coroutines.delay

/** What the moving background's controls can do beyond a still background's; see [EditorViewModel.prepareMovingBackground]. */
internal class MovingBackgroundActions(
    val onChoose: () -> Unit,
    val onStep: (Int) -> Unit,
    val onRemove: () -> Unit,
)

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
    val added = movingBackgroundAddedBytes(plan.frames.size, plan.table.size, panel.width, panel.height)
    val fits = snapshot.containerBytes + added <= WATCH_CONTAINER_BYTE_CEILING
    val total = pending.source.frames.size
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
            pendingImage = state.pendingImage?.let { image -> frame?.let { image.copy(preview = it) } ?: image },
            placement = state.placement,
            enabled = enabled,
            onWidget = {},
            onMoveWidget = { _, _, _ -> },
            onTransformImage = onTransformImage,
            modifier = Modifier.widthIn(max = 186.dp),
        )
        TextButton(onClick = { playing = !playing }) {
            Text(
                stringResource(if (playing) R.string.editor_moving_pause else R.string.editor_moving_play),
                style = MaterialTheme.typography.labelMedium,
            )
        }
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
            Box(Modifier.padding(top = 4.dp)) { ImportRoomMeter(snapshot, added) }
        }
        FrameStepControls(pending, total, enabled, actions.onStep)
        Column(Modifier.fillMaxWidth()) {
            MicroLabel(stringResource(R.string.editor_bg_fit_heading))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageFit.entries.forEach { fit ->
                    FitChip(fit.name, state.placement.fit == fit, { onFit(fit) }, Modifier.weight(1f), enabled)
                }
            }
        }
        PlacementControls(
            placement = state.placement,
            panelWidth = panel.width,
            panelHeight = panel.height,
            enabled = enabled,
            onTransformImage = onTransformImage,
            onStepZoom = onStepImageZoom,
            onResetPlacement = onResetPlacement,
        )
        ImageColourControls(state.placement.tint, enabled = enabled, onTint = onTint)
        MovingBackgroundNotes(snapshot)
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
 * Fewer or more frames: every frame, every 2nd, every 3rd… The step the page opened at is the
 * smallest that fits, so More stops there unless the face has since gained room.
 */
@Composable
private fun FrameStepControls(pending: PendingAnimation, total: Int, enabled: Boolean, onStep: (Int) -> Unit) {
    val smallest = AnimationTiming.smallestStep(total)
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(14.dp),
    ) {
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
            stringResource(R.string.editor_moving_frames_hint),
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
}

/** What the watch does with it, said before it is added and while it is there. */
@Composable
private fun MovingBackgroundNotes(snapshot: EditorSnapshot) {
    Text(
        stringResource(R.string.editor_moving_loops),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.fitText.secondary,
    )
    if (snapshot.isAodSelected) {
        StatusBanner(FitStatus.Warning, stringResource(R.string.editor_moving_aod))
    }
}

/** The selected style's moving background: what it is, and Remove. */
@Composable
internal fun MovingBackgroundPresent(snapshot: EditorSnapshot, moving: MovingBackground, enabled: Boolean, onRemove: () -> Unit) {
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
    MovingBackgroundNotes(snapshot)
    var confirming by remember { mutableStateOf(false) }
    FitButton(
        stringResource(R.string.editor_moving_remove),
        { confirming = true },
        Modifier.fillMaxWidth(),
        enabled,
        style = FitButtonStyle.Secondary,
    )
    // Deleted, frames and all, not parked under Removed; the same words the widget list uses.
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.editor_moving_remove_title)) },
            text = { Text(stringResource(R.string.editor_delete_body_moving)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onRemove() }) {
                    Text(stringResource(R.string.editor_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.editor_cancel)) }
            },
        )
    }
}
