package dev.fitface.studio.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.WidgetArrangement
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.arrangementTarget
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitFaceType
import dev.fitface.studio.core.ui.FitIconButton

/**
 * The Layers page, swiped to from page 1's left: the four ways to add to the face on the
 * first row; the all-styles switch, layer order and `›` on the second.
 *
 * Built on page 1's frame row for row. The first row is the nudge arrows' row: four equal
 * compact buttons in the arrows' places, laid out exactly as the arrows are. The second row
 * keeps page 1's seven columns, every button one column, so `ALL` and `›` are where page 1
 * has its first and last buttons. The middle column is left empty, between the two moves
 * towards the front and the two towards the back.
 */
@Composable
internal fun QuickLayersPage(
    widget: WidgetGuide,
    title: String,
    snapshot: EditorSnapshot,
    allStyles: Boolean,
    isWorking: Boolean,
    onArrange: (WidgetArrangement) -> Unit,
    onApplyAll: (Boolean) -> Unit,
    onImportWidget: () -> Unit,
    onAddPicture: () -> Unit,
    onAddText: () -> Unit,
    onClockFont: () -> Unit,
    onInspect: () -> Unit,
) {
    val enabled = !isWorking
    val ordered = snapshot.widgets.sortedBy { it.ordinal }
    val layer = ordered.indexOfFirst { it.globalIndex == widget.globalIndex } + 1
    // The switch only means something where a widget has another style to reach: not on the
    // always-on display, not for a widget added to one style, not on a one-style face.
    val scopeFixed = snapshot.isAodSelected || widget.addedKind != null || snapshot.styleNames.size <= 1
    // Disabled rather than hidden where the style draws no clock from pictures, as page 1's
    // buttons are, so the row keeps its four places.
    val clockFontOffered = snapshot.widgets.any { it.clockPart != null }
    Column(Modifier.fillMaxWidth().padding(TrayPadding)) {
        TrayTitle(title)
        Text(
            stringResource(
                R.string.editor_quick_layers_facts,
                layer,
                ordered.size,
                snapshot.selectedVariantLabel(),
            ),
            color = MaterialTheme.colorScheme.primary,
            style = FitFaceType.numeric,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = TrayFirstRowTop),
            horizontalArrangement = Arrangement.spacedBy(TrayFirstRowGap),
        ) {
            // The Widgets page's order. Each label is short to fit a quarter of the row; the
            // description is the full name the Widgets page uses, for a screen reader.
            QuickAddButton(
                stringResource(R.string.editor_quick_import_widget),
                stringResource(R.string.widget_import_open),
                enabled, onImportWidget,
            )
            QuickAddButton(
                stringResource(R.string.editor_quick_add_picture),
                stringResource(R.string.editor_media_choose),
                enabled, onAddPicture,
            )
            QuickAddButton(
                stringResource(R.string.editor_quick_add_text),
                stringResource(R.string.editor_text_open),
                enabled, onAddText,
            )
            QuickAddButton(
                stringResource(R.string.editor_quick_clock_font),
                stringResource(R.string.editor_clock_font_open),
                enabled && clockFontOffered, onClockFont,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = TraySecondRowTop),
            horizontalArrangement = Arrangement.spacedBy(TraySecondRowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = stringResource(R.string.editor_apply_all_title)
            val state = stringResource(if (allStyles) R.string.editor_quick_on else R.string.editor_quick_off)
            FitIconButton(
                contentDescription = name,
                onClick = { onApplyAll(!allStyles) },
                modifier = Modifier.weight(1f).semantics { stateDescription = state },
                enabled = enabled && !scopeFixed,
                selected = allStyles && !scopeFixed,
            ) {
                Text(stringResource(R.string.editor_quick_all), style = MaterialTheme.typography.labelMedium)
            }
            // Front-most first, as Edit widget orders them: up is towards the viewer. The gap
            // is the middle column, between the moves forward and the moves back.
            WidgetArrangement.entries.reversed().forEach { action ->
                if (action == WidgetArrangement.BACKWARD) Spacer(Modifier.weight(1f))
                FitIconButton(
                    contentDescription = stringResource(action.quickLabel),
                    onClick = { onArrange(action) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled && arrangementTarget(snapshot.widgets, widget.globalIndex, action) != null,
                ) {
                    NudgeArrow(
                        degrees = if (action == WidgetArrangement.FRONT || action == WidgetArrangement.FORWARD) 270f else 90f,
                        toEdge = action == WidgetArrangement.FRONT || action == WidgetArrangement.BACK,
                    )
                }
            }
            FitIconButton(
                glyph = "›",
                contentDescription = stringResource(R.string.editor_action_record_details),
                onClick = onInspect,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
    }
}

/**
 * One of the first row's four buttons, a quarter of the row: page 1's nudge arrow button with
 * a label in place of the arrow. No height of its own: held to 38dp exactly, it lost the
 * touch-target room the arrows keep around theirs and sat 5dp above them, so both rows moved
 * on a swipe.
 *
 * The label is the action's full name in small mono type, on two short lines ("Clock /
 * font"), so it fits a quarter of the narrowest row whole with room around it: one line of a
 * bigger face fitted only a single word, which did not say what the button adds, and
 * `labelMedium` at its own size on two lines filled the button edge to edge.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickAddButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FitButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).semantics { contentDescription = description },
        enabled = enabled,
        style = FitButtonStyle.Secondary,
        compact = true,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = QuickAddSize, lineHeight = QuickAddLineHeight),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `labelMedium`'s weight and spacing a step smaller, with two lines inside the 38dp button and
 * room around them. `labelSmall` is no help here: its wide tracking and heavier weight made the
 * words as wide and heavier.
 */
private val QuickAddSize = 9.5.sp
private val QuickAddLineHeight = 12.sp

private val WidgetArrangement.quickLabel: Int
    get() = when (this) {
        WidgetArrangement.FRONT -> R.string.editor_arrange_front
        WidgetArrangement.FORWARD -> R.string.editor_arrange_forward
        WidgetArrangement.BACKWARD -> R.string.editor_arrange_backward
        WidgetArrangement.BACK -> R.string.editor_arrange_back
    }
