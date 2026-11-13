package dev.fitface.studio.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.ClockFontName
import dev.fitface.studio.core.model.ClockPart
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.TextFont
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitChip
import dev.fitface.studio.core.ui.FitHeading
import dev.fitface.studio.core.ui.InfoText
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.fitText

/** What the Clock font page and Edit widget's Font section for a digit can do; see [EditorViewModel.startClockFont]. */
internal class ClockFontActions(
    val onOpen: () -> Unit,
    val onFont: (TextFont) -> Unit,
    val onChooseFontFile: () -> Unit,
    val onPart: (ClockPart, Boolean) -> Unit,
    val onTint: (Int?) -> Unit,
    val onAllStyles: (Boolean) -> Unit,
    val onDiscard: () -> Unit,
    val onApply: () -> Unit,
    val onReset: (ClockPart) -> Unit,
)

/**
 * The Clock font page, which takes the Widgets page while it is open, as placing a picture
 * does: the face pinned at the top as it will be, then the font, which parts, their colour and
 * which styles, each in the editor's [ControlCard], then Discard and Apply font.
 *
 * It opens with no font chosen and the face as it is; nothing is drawn until a font is picked.
 * From then the face is the repository's drawing of the last request that finished, so it is
 * exactly what Apply commits, and holding it shows the face as it is, as an import's review
 * does; there are no Before and After chips. A request that cannot be drawn says why on the Font card and leaves the face on the last one
 * that could. The page states what is chosen and what changes; how it works is behind the ⓘs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClockFontWorkspace(
    state: EditorUiState,
    snapshot: EditorSnapshot,
    pending: PendingClockFont,
    actions: ClockFontActions,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.isWorking
    val chosen = pending.font != null
    val drawn = pending.preview?.preview.takeIf { chosen }
    val compare = rememberFaceCompare(drawn)
    PinnedFacePage(
        snapshot,
        faceCap = 186.dp,
        modifier = modifier,
        face = { faceModifier ->
            val frame = drawn?.takeIf { !compare.comparing } ?: snapshot.composedPreview
            FacePreview(
                frame = frame,
                imagePath = null,
                contentDescription = stringResource(R.string.editor_clock_font_face),
                modifier = faceModifier.fillMaxWidth().aspectRatio(frame.width.toFloat() / frame.height.coerceAtLeast(1))
                    .then(if (drawn != null) compare.modifier else Modifier),
            )
        },
    ) {
        if (drawn != null) CompareHint(stringResource(R.string.editor_clock_font_compare))
        if (chosen && !pending.ready && pending.problem == null) {
            Text(
                stringResource(R.string.editor_clock_font_drawing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitText.secondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ControlCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FitHeading(stringResource(R.string.editor_clock_font_heading)) {
                InfoText(stringResource(R.string.editor_clock_font_file_note))
            }
            FontPicker(pending.font, enabled, actions.onFont, actions.onChooseFontFile,
                placeholder = stringResource(R.string.editor_clock_font_choose))
            if (!chosen) {
                Text(
                    pending.current?.let { stringResource(R.string.editor_clock_font_now, clockFontName(it)) }
                        ?: stringResource(R.string.editor_clock_font_own),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            pending.problem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        // Most faces draw only hours and minutes this way, and then there is nothing to choose.
        if (pending.available.size > 1) {
            ControlCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MicroLabel(stringResource(R.string.editor_clock_font_parts))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pending.available.forEach { part ->
                        val included = part in pending.parts
                        FitChip(clockPartName(part), included, { actions.onPart(part, !included) }, enabled = enabled)
                    }
                }
            }
        }
        // The editor's one colour control, previewing: Original colours keeps each part's own.
        ColourPicker(
            selected = pending.tint,
            original = pending.tint == null,
            enabled = enabled,
            commits = false,
            onColour = actions.onTint,
            stateKey = pending.parts,
            info = { InfoText(stringResource(R.string.editor_clock_font_colour_detail)) },
        )
        if (!snapshot.isAodSelected && snapshot.styleNames.size > 1) {
            ScopeChoice(thisStyleOnly = !pending.applyToAllStyles, enabled = enabled) { actions.onAllStyles(!it) }
        }
        // What Apply changes stays in view; how the digits are drawn is the button's ⓘ.
        Text(
            stringResource(R.string.editor_clock_font_detail,
                if (pending.applyToAllStyles && !snapshot.isAodSelected) stringResource(R.string.editor_clock_font_detail_all)
                else importVariantLabel(snapshot.selectedVariant)),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FitButton(stringResource(R.string.editor_bg_discard), actions.onDiscard, Modifier.weight(1f), enabled,
                style = FitButtonStyle.Secondary)
            FitButton(
                stringResource(R.string.editor_clock_font_apply), actions.onApply, Modifier.weight(2f),
                enabled && pending.ready, loading = state.isWorking,
                info = {
                    InfoText(stringResource(R.string.editor_clock_font_note))
                    InfoText(stringResource(R.string.editor_clock_font_undo))
                },
            )
        }
    }
}

/**
 * Edit widget's Font section for a digit of a clock drawn from pictures: the font they are in,
 * the page that draws them, and Reset font.
 */
@Composable
internal fun ClockFontInspectorControls(part: ClockPart, font: ClockFontName?, enabled: Boolean, actions: ClockFontActions) {
    ControlCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FitHeading(stringResource(R.string.editor_clock_font_section)) {
            InfoText(stringResource(R.string.editor_clock_font_body))
        }
        Text(
            font?.let { stringResource(R.string.editor_clock_font_drawn_in, clockFontName(it)) }
                ?: stringResource(R.string.editor_clock_font_own_short),
            style = MaterialTheme.typography.bodyMedium,
        )
        FitButton(stringResource(R.string.editor_clock_font_draw), actions.onOpen, Modifier.fillMaxWidth(),
            enabled = enabled, style = FitButtonStyle.Secondary)
        if (font != null) {
            FitButton(stringResource(R.string.editor_clock_font_reset), { actions.onReset(part) }, Modifier.fillMaxWidth(),
                enabled = enabled, style = FitButtonStyle.Secondary)
        }
    }
}

@Composable
private fun clockFontName(font: ClockFontName): String =
    font.phone?.let { phoneFontName(it) } ?: font.file.orEmpty()

@Composable
internal fun clockPartName(part: ClockPart): String = stringResource(
    when (part) {
        ClockPart.TIME -> R.string.editor_clock_part_time
        ClockPart.SECONDS -> R.string.editor_clock_part_seconds
        ClockPart.SECOND_ZONE -> R.string.editor_clock_part_second_zone
        ClockPart.WEEKDAY -> R.string.editor_clock_part_weekday
    },
)
