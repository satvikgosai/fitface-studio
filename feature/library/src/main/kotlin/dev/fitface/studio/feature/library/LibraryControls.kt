package dev.fitface.studio.feature.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.CatalogSort
import dev.fitface.studio.core.model.ProjectSort
import dev.fitface.studio.core.ui.FitChip
import dev.fitface.studio.core.ui.FitIconButton
import dev.fitface.studio.core.ui.MicroLabel

/**
 * The search field and sort chips both library pages use.
 *
 * One pair, used twice, because the two pages have to read as one screen: Watch faces had
 * these and Your projects had nothing, and a second hand-built copy would drift the moment
 * either was touched. They stay in this module rather than `:core:ui` — both callers are
 * here, and a component with one module of users does not need to be shared to be reused.
 *
 * Both are emitted as the first item *inside* each page's list, so they scroll away with it.
 * That is deliberate: the header above them already takes 41% of a landscape phone before a
 * single row, and pinning these as well would leave a face grid barely taller than one card.
 */
@Composable
internal fun LibrarySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
        trailingIcon = {
            // Only while there is something to clear. A permanently visible × on an empty
            // field is a control that does nothing, and it would sit in the same place the
            // reader looks to confirm the field is empty.
            if (value.isNotEmpty()) {
                FitIconButton(
                    glyph = "×",
                    contentDescription = stringResource(R.string.library_search_clear),
                    onClick = { onValueChange("") },
                    modifier = Modifier.padding(end = 8.dp),
                    enabled = enabled,
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
    )
}

/**
 * The sort chips, where the selected one reverses on a second tap.
 *
 * Generic over the sort enum because the catalogue and the projects list order different
 * things by different keys but present the choice identically.
 *
 * @param label the wording for an option in a direction. Not a property on the enum: those
 *   live in `:core:model`, which has no resources, and a reversible sort needs two labels per
 *   option in the reader's language.
 */
@Composable
internal fun <T> LibrarySortRow(
    options: List<T>,
    selected: T,
    reversed: Boolean,
    enabled: Boolean,
    label: @Composable (option: T, reversed: Boolean) -> String,
    onSelect: (T) -> Unit,
    onReverse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MicroLabel(stringResource(R.string.library_sort_label), Modifier.padding(end = 3.dp))
        options.forEach { option ->
            val isSelected = option == selected
            // An unselected chip always names its natural order, so the row reads as a set of
            // orders to choose from. Only the selected one shows the direction it is in,
            // because only it can be reversed.
            val text = label(option, isSelected && reversed)
            FitChip(
                text = text,
                selected = isSelected,
                onClick = { if (isSelected) onReverse() else onSelect(option) },
                enabled = enabled,
                // Not RadioButton on the selected chip: it is already selected, and
                // announcing "radio button" says the only thing it does is become so.
                role = if (isSelected) Role.Button else Role.RadioButton,
                contentDescription = if (isSelected) {
                    stringResource(R.string.library_sort_reverse_a11y, text)
                } else {
                    null
                },
            )
        }
    }
}

/**
 * Catalogue sort wording.
 *
 * Two entries say their direction in words and need no glyph; face number has no such pair,
 * so it carries an arrow. `↑` and `↓` are plain text glyphs — not `▲▼`, and not `⚙` or `ℹ`,
 * which fall through to the emoji font on many builds and would be the app's only colour
 * characters.
 */
@Composable
internal fun catalogSortLabel(sort: CatalogSort, reversed: Boolean): String = stringResource(
    when (sort) {
        CatalogSort.RECENT -> if (reversed) R.string.library_sort_oldest else R.string.library_sort_newest
        CatalogSort.NAME -> if (reversed) R.string.library_sort_name_za else R.string.library_sort_name_az
        CatalogSort.NUMBER ->
            if (reversed) R.string.library_sort_number_desc else R.string.library_sort_number_asc
    },
)

@Composable
internal fun projectSortLabel(sort: ProjectSort, reversed: Boolean): String = stringResource(
    when (sort) {
        // "Edited" and not "Newest": the catalogue's newest is when the store published a
        // face, and a project's is when its owner last changed it. Same chip, different fact.
        ProjectSort.RECENT ->
            if (reversed) R.string.library_sort_edited_oldest else R.string.library_sort_edited_newest
        ProjectSort.NAME -> if (reversed) R.string.library_sort_name_za else R.string.library_sort_name_az
        ProjectSort.NUMBER ->
            if (reversed) R.string.library_sort_number_desc else R.string.library_sort_number_asc
    },
)
