package dev.fitface.studio.feature.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
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
import dev.fitface.studio.core.ui.fitText

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
 *
 * They are laid out by one composable, [LibraryPageControls], rather than assembled twice.
 * Two copies had already drifted: the catalogue inset its grid by 16dp and the projects list
 * by 20dp, and the top padding differed by 2dp, so the search field and every sort chip
 * stepped sideways and up whenever you switched tabs. One layout and one [LibraryPageInsets]
 * is what makes that impossible rather than merely fixed.
 */

/**
 * The insets both library pages lay their list out with.
 *
 * Shared so the controls sit at the same place on each. The rows and the cards inside the
 * lists differ, but what is above them must not.
 */
internal val LibraryPageInsets = PaddingValues(
    start = 16.dp,
    top = 14.dp,
    end = 16.dp,
    bottom = 28.dp,
)
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
 * The row scrolls sideways, which is a fallback and not a licence to overflow it: a chip past
 * the right edge is a chip nothing on screen says is there. It has to fit unscrolled on the
 * narrowest phone this app runs on, and the budget is tight. At 320dp, [LibraryPageInsets]
 * leaves 288dp; the SORT label and its 3dp gap take 30dp, the three 7dp gaps take 21dp, and a
 * chip is its label plus 28dp of padding at roughly 6.8dp a character — `labelMedium` is
 * monospace, so a character really is a unit of width. Three eight-character labels come to
 * 284dp and fit; "Face number ↑" alone put the row at 318dp and hung the last chip off the
 * screen edge, arrow and all. `SortChipLayoutTest` holds the eight.
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
 *
 * **Both labels of a pair are the same number of characters, and that is load-bearing.**
 * `labelMedium` is `FontFamily.Monospace`, so equal length is equal width — which is what
 * stops the selected chip from resizing when it is reversed and shoving every chip after it
 * sideways under the finger that just tapped it. `SortChipLayoutTest` holds it. A translation
 * has to keep the pairs equal too, or it reintroduces the shove.
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

/**
 * Project sort wording, which is deliberately the **same** as [catalogSortLabel].
 *
 * The two pages order different things — the catalogue's newest is when the store published
 * a face, a project's is when its owner last edited it — but the chips sit in the same place
 * and mean the same kind of thing, and wording them differently ("Edited last") made every
 * chip change width on a tab switch. Identical labels are what let you switch pages without
 * the row moving under your finger.
 */
@Composable
internal fun projectSortLabel(sort: ProjectSort, reversed: Boolean): String = stringResource(
    when (sort) {
        ProjectSort.RECENT ->
            if (reversed) R.string.library_sort_oldest else R.string.library_sort_newest
        ProjectSort.NAME -> if (reversed) R.string.library_sort_name_za else R.string.library_sort_name_az
        ProjectSort.NUMBER ->
            if (reversed) R.string.library_sort_number_desc else R.string.library_sort_number_asc
    },
)


/**
 * The search field, the sort chips and the count line, in that order — the whole of what
 * sits above either library list.
 *
 * One composable for both pages on purpose; see the note on [LibrarySearchField].
 */
@Composable
internal fun <T> LibraryPageControls(
    query: String,
    onQuery: (String) -> Unit,
    searchPlaceholder: String,
    searchEnabled: Boolean,
    sortOptions: List<T>,
    sort: T,
    sortReversed: Boolean,
    sortEnabled: Boolean,
    sortLabel: @Composable (option: T, reversed: Boolean) -> String,
    onSort: (T) -> Unit,
    onReverseSort: () -> Unit,
    sourceLabel: String,
    countLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        LibrarySearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = searchPlaceholder,
            enabled = searchEnabled,
        )
        LibrarySortRow(
            options = sortOptions,
            selected = sort,
            reversed = sortReversed,
            enabled = sortEnabled,
            label = sortLabel,
            onSelect = onSort,
            onReverse = onReverseSort,
            modifier = Modifier.padding(top = 11.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicroLabel(sourceLabel)
            Text(
                countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitText.secondary,
            )
        }
    }
}
