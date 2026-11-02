package dev.fitface.studio.feature.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.WidgetArrangement
import dev.fitface.studio.core.model.WidgetCategory
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.WidgetRotationKind
import dev.fitface.studio.core.model.arrangementTarget
import dev.fitface.studio.core.model.canRotateTo
import dev.fitface.studio.core.model.normalizedRotation
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitIconButton
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.fitText

/** One tap of the rotate buttons, in tenths: a whole number of degrees for every kind. */
internal const val RotationStepTenths = 150

/**
 * The angle one rotate tap lands on, or null where it cannot be taken.
 *
 * Relative to the stored angle, so a vendor's 31.8° text steps to 46.8° rather than
 * snapping to the grid — the step is a nudge, and Reset is how to get the original back.
 */
internal fun nextWidgetRotation(widget: WidgetGuide?, clockwise: Boolean): Int? {
    val current = widget?.rotationTenths ?: return null
    val next = normalizedRotation(current + if (clockwise) RotationStepTenths else -RotationStepTenths)
    return next.takeIf { canRotateTo(widget, it) }
}

/**
 * Rotate left or right by one step — the same button in the canvas tray and in Edit widget.
 *
 * `↺` `↻`: open circles read as turning a widget round, where the half-arcs `↶` `↷`
 * read as undo and redo.
 */
@Composable
internal fun RotationStepButton(
    widget: WidgetGuide?,
    clockwise: Boolean,
    enabled: Boolean,
    onRotate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val next = nextWidgetRotation(widget, clockwise)
    FitIconButton(
        glyph = if (clockwise) "↻" else "↺",
        contentDescription = stringResource(
            if (clockwise) R.string.editor_rotation_right else R.string.editor_rotation_left,
        ),
        onClick = { next?.let(onRotate) },
        modifier = modifier,
        enabled = enabled && next != null,
    )
}

/**
 * Rotation in Edit widget: the step buttons, an exact angle and a reset, all on the page.
 *
 * It used to be a dialog behind a button. A rotation is judged by looking at the face, and
 * a dialog covered it — so this sits under the page's own preview, and every control
 * commits when tapped, exactly as the tray's buttons do.
 */
@Composable
internal fun RotationControls(
    widget: WidgetGuide,
    snapshot: EditorSnapshot,
    allStyles: Boolean,
    enabled: Boolean,
    onRotate: (Int) -> Unit,
) {
    val current = widget.rotationTenths
    val kind = widget.rotationKind
    var angle by rememberSaveable(widget.globalIndex, current) { mutableStateOf(rotationInput(current ?: 0)) }
    val parsed = parseRotationInput(angle)
    ControlCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MicroLabel(stringResource(R.string.editor_rotation))
        if (current == null || kind == null) {
            Text(
                stringResource(unsupportedRotationReason(widget)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
            return@ControlCard
        }
        Text(
            stringResource(
                R.string.editor_rotation_current,
                rotationInput(current),
                rotationInput(widget.originalRotationTenths ?: current),
            ),
        )
        Text(
            if (!snapshot.isAodSelected && allStyles && widget.addedKind == null) {
                stringResource(R.string.editor_rotation_scope_all)
            } else {
                stringResource(R.string.editor_rotation_scope, variantLabel(snapshot.selectedVariant.basename))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RotationStepButton(widget, clockwise = false, enabled, onRotate, Modifier.weight(1f))
            RotationStepButton(widget, clockwise = true, enabled, onRotate, Modifier.weight(1f))
        }
        OutlinedTextField(
            value = angle,
            onValueChange = { angle = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.editor_rotation_degrees)) },
            isError = parsed == null || !canRotateTo(widget, parsed),
        )
        rotationInputError(widget, parsed)?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FitButton(
                stringResource(R.string.editor_rotation_apply),
                { parsed?.let(onRotate) },
                enabled = enabled && parsed != null && canRotateTo(widget, parsed) &&
                    parsed != normalizedRotation(current),
                style = FitButtonStyle.Secondary,
            )
            val original = widget.originalRotationTenths ?: current
            FitButton(
                stringResource(R.string.editor_rotation_reset),
                { onRotate(original) },
                enabled = enabled && original != current && canRotateTo(widget, original),
                style = FitButtonStyle.Secondary,
            )
        }
        Text(
            stringResource(
                when (kind) {
                    WidgetRotationKind.TEXT -> R.string.editor_rotation_text
                    WidgetRotationKind.LINE -> R.string.editor_rotation_line
                    WidgetRotationKind.ARC -> R.string.editor_rotation_arc
                    WidgetRotationKind.ARTWORK -> R.string.editor_rotation_artwork
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
        // An opaque picture's black box turns with it, which matters on a coloured face.
        if (kind == WidgetRotationKind.ARTWORK && widget.opaqueArtwork) {
            Text(
                stringResource(R.string.editor_rotation_artwork_opaque),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
        // Said before the tap, as resize says it: a shared digit pool turns every digit.
        if (kind == WidgetRotationKind.ARTWORK && widget.sharedArtworkWidgets > 0) {
            Text(
                pluralStringResource(
                    R.plurals.editor_rotation_shared,
                    widget.sharedArtworkWidgets,
                    widget.sharedArtworkWidgets,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Why there is nothing to turn, in terms of what the widget is rather than its type number. */
@StringRes
private fun unsupportedRotationReason(widget: WidgetGuide): Int = when {
    widget.placement == WidgetPlacement.BACKGROUND -> R.string.editor_rotation_unsupported_background
    widget.category == WidgetCategory.HAND -> R.string.editor_rotation_unsupported_hand
    widget.category == WidgetCategory.ARC || widget.category == WidgetCategory.BAR ->
        R.string.editor_rotation_unsupported_gauge
    widget.category == WidgetCategory.IMAGE || widget.category == WidgetCategory.SPRITE ||
        widget.category == WidgetCategory.ANIMATION -> R.string.editor_rotation_unsupported_artwork
    else -> R.string.editor_rotation_unsupported
}

/** What is wrong with a typed angle, or null when it can be applied. */
@StringRes
internal fun rotationInputError(widget: WidgetGuide, parsed: Int?): Int? = when {
    parsed == null -> R.string.editor_rotation_invalid
    canRotateTo(widget, parsed) -> null
    widget.rotationKind == WidgetRotationKind.TEXT -> R.string.editor_rotation_too_large
    else -> R.string.editor_rotation_whole_degrees
}

internal fun rotationInput(tenths: Int): String {
    val angle = normalizedRotation(tenths)
    return if (angle % 10 == 0) (angle / 10).toString() else "${angle / 10}.${angle % 10}"
}

/** Accept decimal degrees to tenths, including negative/full turns; never truncate precision. */
internal fun parseRotationInput(input: String): Int? = runCatching {
    normalizedRotation(input.trim().replace(',', '.').toBigDecimal().movePointRight(1).intValueExact())
}.getOrNull()

/**
 * Layer order in Edit widget: four tiles, each an arrow over a short word, front-most first.
 *
 * Order only changes which of two overlapping widgets is on top and is rarely used, so it
 * takes one row. The words keep it readable where bare arrows read as a quick panel; each
 * tile still announces its full name. Disabled at a boundary rather than hidden, and two
 * to a row when the words do not fit four across (large text, a narrow screen).
 */
@Composable
internal fun ArrangementControls(
    widget: WidgetGuide,
    snapshot: EditorSnapshot,
    enabled: Boolean,
    onArrange: (WidgetArrangement) -> Unit,
) {
    ControlCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MicroLabel(stringResource(R.string.editor_arrange), Modifier.align(Alignment.CenterVertically))
            Text(
                stringResource(R.string.editor_arrange_scope, variantLabel(snapshot.selectedVariant.basename)),
                Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
            )
        }
        val actions = WidgetArrangement.entries.reversed()
        val words = actions.map { stringResource(it.shortLabel) }
        val wordStyle = MaterialTheme.typography.labelMedium
        val measurer = rememberTextMeasurer()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val perRow = with(density) {
                arrangeTilesPerRow(
                    availablePx = constraints.maxWidth,
                    widestWordPx = words.maxOf { measurer.measure(it, wordStyle).size.width },
                    tilePaddingPx = (ArrangeTilePadding * 2).roundToPx(),
                    gapPx = ArrangeTileGap.roundToPx(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(ArrangeTileGap)) {
                actions.indices.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ArrangeTileGap)) {
                        row.forEach { i ->
                            val action = actions[i]
                            val description = stringResource(action.label)
                            FitButton(
                                onClick = { onArrange(action) },
                                modifier = Modifier.weight(1f).semantics { contentDescription = description },
                                enabled = enabled &&
                                    arrangementTarget(snapshot.widgets, widget.globalIndex, action) != null,
                                style = FitButtonStyle.Secondary,
                                contentPadding = PaddingValues(horizontal = ArrangeTilePadding, vertical = 8.dp),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    NudgeArrow(
                                        degrees = if (action.towardFront) 270f else 90f,
                                        toEdge = action == WidgetArrangement.FRONT || action == WidgetArrangement.BACK,
                                    )
                                    // The tile's description already says it in full.
                                    Text(
                                        words[i],
                                        Modifier.clearAndSetSemantics {},
                                        style = wordStyle,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.editor_arrange_boundaries),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
}

/** Four tiles across when the widest word fits in each, otherwise two. */
internal fun arrangeTilesPerRow(availablePx: Int, widestWordPx: Int, tilePaddingPx: Int, gapPx: Int): Int {
    val fourAcross = (availablePx - gapPx * 3) / 4
    return if (widestWordPx + tilePaddingPx <= fourAcross) 4 else 2
}

private val WidgetArrangement.towardFront: Boolean
    get() = this == WidgetArrangement.FRONT || this == WidgetArrangement.FORWARD

@get:StringRes
private val WidgetArrangement.label: Int
    get() = when (this) {
        WidgetArrangement.FRONT -> R.string.editor_arrange_front
        WidgetArrangement.FORWARD -> R.string.editor_arrange_forward
        WidgetArrangement.BACKWARD -> R.string.editor_arrange_backward
        WidgetArrangement.BACK -> R.string.editor_arrange_back
    }

@get:StringRes
private val WidgetArrangement.shortLabel: Int
    get() = when (this) {
        WidgetArrangement.FRONT -> R.string.editor_arrange_front_short
        WidgetArrangement.FORWARD -> R.string.editor_arrange_forward_short
        WidgetArrangement.BACKWARD -> R.string.editor_arrange_backward_short
        WidgetArrangement.BACK -> R.string.editor_arrange_back_short
    }

private val ArrangeTilePadding = 4.dp
private val ArrangeTileGap = 8.dp
