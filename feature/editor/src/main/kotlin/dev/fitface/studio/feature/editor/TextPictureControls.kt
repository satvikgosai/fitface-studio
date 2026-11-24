package dev.fitface.studio.feature.editor

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.PhoneFont
import dev.fitface.studio.core.model.TextFont
import dev.fitface.studio.core.model.TextPicture
import dev.fitface.studio.core.model.TextProblem
import dev.fitface.studio.core.model.WidgetLabel
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitDropdownMenu
import dev.fitface.studio.core.ui.FitHeading
import dev.fitface.studio.core.ui.InfoText
import dev.fitface.studio.core.ui.FitMenuEntry
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.fitText

/**
 * The Text card on the placing page, above Placement: the words, then the font. Its size is
 * Placement's, in pixels.
 *
 * What stopped the words being drawn is said under the field, where they are typed; the
 * face keeps showing what was last drawn, and Add waits.
 */
@Composable
internal fun TextPictureControls(text: PendingText, enabled: Boolean, pictures: PictureActions, info: List<String>) {
    val wanted = text.wanted
    ControlCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FitHeading(stringResource(R.string.editor_text_heading)) {
            info.forEach { InfoText(it) }
        }
        OutlinedTextField(
            value = wanted.text,
            onValueChange = pictures.onTextWords,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_text_words)) },
            maxLines = TextPicture.MAX_LINES,
            isError = text.problem != null,
        )
        text.problem?.let { problem ->
            Text(
                textProblemLine(problem, wanted.sizePx),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        FitHeading(stringResource(R.string.editor_text_font)) {
            InfoText(stringResource(R.string.editor_text_font_note))
        }
        FontPicker(wanted.font, enabled, pictures.onTextFont, pictures.onChooseFontFile)
    }
}

/**
 * The font, as one field that opens a list rather than a chip per font: twelve chips took
 * most of a screen. The field and every entry name a phone font in its own face; a picked
 * file is listed under them while it is the font, and the last entry picks another file.
 * Text and the clock font page both choose with it; with no [font] yet, the field says
 * [placeholder] and nothing is ticked.
 */
@Composable
internal fun FontPicker(
    font: TextFont?,
    enabled: Boolean,
    onFont: (TextFont) -> Unit,
    onChooseFile: () -> Unit,
    placeholder: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    val families = remember {
        PhoneFont.entries.associateWith { FontFamily(Typeface.create(it.family, if (it.bold) Typeface.BOLD else Typeface.NORMAL)) }
    }
    val name = when (font) {
        is TextFont.Phone -> phoneFontName(font.font)
        is TextFont.File -> font.name
        null -> placeholder
    }
    val description = stringResource(R.string.editor_text_font_a11y, name)
    Box {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.let { style ->
                    (font as? TextFont.Phone)?.let { style.copy(fontFamily = families.getValue(it.font)) } ?: style
                },
                color = if (enabled && font != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        FitDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PhoneFont.entries.forEach { choice ->
                FitMenuEntry(phoneFontName(choice), fontFamily = families.getValue(choice),
                    selected = font == TextFont.Phone(choice)) {
                    expanded = false
                    onFont(TextFont.Phone(choice))
                }
            }
            (font as? TextFont.File)?.let { FitMenuEntry(it.name, selected = true) { expanded = false } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FitMenuEntry(stringResource(R.string.editor_text_font_file)) {
                expanded = false
                onChooseFile()
            }
        }
    }
}

@Composable
private fun textProblemLine(problem: TextProblem, sizePx: Int): String = when (problem) {
    TextProblem.EMPTY -> stringResource(R.string.editor_text_problem_empty)
    TextProblem.TOO_LONG -> stringResource(R.string.editor_text_problem_long, TextPicture.MAX_LENGTH)
    TextProblem.TOO_MANY_LINES -> stringResource(R.string.editor_text_problem_lines, TextPicture.MAX_LINES)
    TextProblem.TOO_BIG -> stringResource(R.string.editor_text_problem_big, sizePx)
}

@Composable
internal fun phoneFontName(font: PhoneFont): String = stringResource(
    when (font) {
        PhoneFont.SANS -> R.string.editor_font_sans
        PhoneFont.SANS_BOLD -> R.string.editor_font_sans_bold
        PhoneFont.SANS_LIGHT -> R.string.editor_font_sans_light
        PhoneFont.CONDENSED -> R.string.editor_font_condensed
        PhoneFont.CONDENSED_BOLD -> R.string.editor_font_condensed_bold
        PhoneFont.SERIF -> R.string.editor_font_serif
        PhoneFont.SERIF_BOLD -> R.string.editor_font_serif_bold
        PhoneFont.MONOSPACE -> R.string.editor_font_monospace
        PhoneFont.TYPEWRITER -> R.string.editor_font_typewriter
        PhoneFont.HANDWRITING -> R.string.editor_font_handwriting
        PhoneFont.CASUAL -> R.string.editor_font_casual
        PhoneFont.SMALL_CAPITALS -> R.string.editor_font_small_capitals
    },
)

/**
 * Edit widget's Font section for a label: the watch draws it in a font of its own, and this
 * offers to draw it as a picture in any font instead, saying what that gives up.
 */
@Composable
internal fun LabelFontControls(label: WidgetLabel, enabled: Boolean, onDraw: () -> Unit) {
    ControlCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FitHeading(stringResource(R.string.editor_label_font_heading)) {
            InfoText(stringResource(R.string.editor_label_font_info, label.text))
        }
        Text(stringResource(R.string.editor_label_font_now), style = MaterialTheme.typography.bodyMedium)
        FitButton(
            stringResource(R.string.editor_label_font_draw),
            onDraw,
            Modifier.fillMaxWidth(),
            enabled = enabled,
            style = FitButtonStyle.Secondary,
        )
    }
}
