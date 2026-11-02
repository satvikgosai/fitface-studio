package dev.fitface.studio.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.WidgetArrangement
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.arrangementTarget
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitFaceType
import dev.fitface.studio.core.ui.FitIconButton
import dev.fitface.studio.core.ui.MicroLabel

/**
 * The Layers page, swiped to from page 1's left: layer order on the first row; the all-styles
 * switch, the two ways to add to the face and `›` on the second.
 *
 * Built on page 1's frame row for row. The first row's arrows are the nudge arrows' buttons in
 * the nudge arrows' places; the second row keeps page 1's seven columns, the two add buttons
 * sharing the middle five, so `ALL` and `›` are each exactly one column and `›` is where page 1
 * has it.
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
    onInspect: () -> Unit,
) {
    val enabled = !isWorking
    val ordered = snapshot.widgets.sortedBy { it.ordinal }
    val layer = ordered.indexOfFirst { it.globalIndex == widget.globalIndex } + 1
    // The switch only means something where a widget has another style to reach.
    val scopeFixed = when {
        snapshot.isAodSelected -> R.string.editor_quick_layers_scope_aod
        widget.addedKind != null -> R.string.editor_quick_layers_scope_added
        snapshot.styleNames.size <= 1 -> R.string.editor_quick_layers_scope_single
        else -> null
    }
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
            // Front-most first, as Edit widget orders them: up is towards the viewer.
            WidgetArrangement.entries.reversed().forEach { action ->
                val description = stringResource(action.quickLabel)
                FitButton(
                    onClick = { onArrange(action) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = description },
                    enabled = enabled && arrangementTarget(snapshot.widgets, widget.globalIndex, action) != null,
                    style = FitButtonStyle.Secondary,
                    compact = true,
                ) {
                    NudgeArrow(
                        degrees = if (action == WidgetArrangement.FRONT || action == WidgetArrangement.FORWARD) 270f else 90f,
                        toEdge = action == WidgetArrangement.FRONT || action == WidgetArrangement.BACK,
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = TraySecondRowTop)) {
            val column = trayColumn(maxWidth, TraySecondRowGap, 7)
            val half = addButtonWidth(column, TraySecondRowGap)
            Row(
                horizontalArrangement = Arrangement.spacedBy(TraySecondRowGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OneColumn(column) {
                    val name = stringResource(R.string.editor_apply_all_title)
                    val state = stringResource(if (allStyles) R.string.editor_quick_on else R.string.editor_quick_off)
                    FitIconButton(
                        contentDescription = name,
                        onClick = { onApplyAll(!allStyles) },
                        modifier = Modifier.weight(1f).semantics { stateDescription = state },
                        enabled = enabled && scopeFixed == null,
                        selected = allStyles && scopeFixed == null,
                    ) {
                        Text(stringResource(R.string.editor_quick_all), style = MaterialTheme.typography.labelMedium)
                    }
                }
                QuickWideButton(
                    stringResource(R.string.editor_quick_import_widget),
                    stringResource(R.string.widget_import_open),
                    half, enabled, onImportWidget,
                )
                QuickWideButton(
                    stringResource(R.string.editor_quick_add_picture),
                    stringResource(R.string.editor_media_choose),
                    half, enabled, onAddPicture,
                )
                OneColumn(column) {
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
        MicroLabel(
            scopeFixed?.let { stringResource(it) } ?: if (allStyles) {
                stringResource(R.string.editor_quick_layers_hint_all)
            } else {
                stringResource(R.string.editor_quick_layers_hint_one, snapshot.selectedVariantLabel())
            },
            Modifier.align(Alignment.CenterHorizontally).padding(top = TrayHintTop),
            color = if (allStyles && scopeFixed == null) MaterialTheme.colorScheme.primary else null,
        )
    }
}

/** One of [count] equal columns across [width] with [gap] between them, as weights share a row. */
internal fun trayColumn(width: Dp, gap: Dp, count: Int): Dp = (width - gap * (count - 1)) / count

/** Each add button: half of five columns and the four gaps inside them, less the gap between the two. */
internal fun addButtonWidth(column: Dp, gap: Dp): Dp = (column * 5 + gap * 3) / 2

/**
 * Exactly one of page 1's seven columns. A tray icon button takes its width only from a weight,
 * which a row of unequal neighbours cannot give it, so it gets a row of its own that wide.
 */
@Composable
private fun OneColumn(width: Dp, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.width(width), content = content)
}

/**
 * One of the second row's two add buttons, each half of the middle five columns. The label is
 * short to fit; [description] is the full name the Widgets page uses, for a screen reader.
 */
@Composable
private fun QuickWideButton(label: String, description: String, width: Dp, enabled: Boolean, onClick: () -> Unit) {
    FitButton(
        onClick = onClick,
        // The square buttons' height, not a text button's 48dp: taller, it made this row and
        // so the whole panel taller than page 1's, and the face jumped on every swipe.
        modifier = Modifier.width(width).height(TrayButtonHeight).semantics { contentDescription = description },
        enabled = enabled,
        style = FitButtonStyle.Secondary,
        compact = true,
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val WidgetArrangement.quickLabel: Int
    get() = when (this) {
        WidgetArrangement.FRONT -> R.string.editor_arrange_front
        WidgetArrangement.FORWARD -> R.string.editor_arrange_forward
        WidgetArrangement.BACKWARD -> R.string.editor_arrange_backward
        WidgetArrangement.BACK -> R.string.editor_arrange_back
    }
