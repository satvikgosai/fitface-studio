package dev.fitface.studio.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Optional reference, reachable by tap, keyboard or TalkBack. Warnings stay outside. */
@Composable
fun FitDetails(
    label: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    initiallyExpanded: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(initiallyExpanded) }
    val action = stringResource(
        if (expanded) R.string.ui_details_collapse else R.string.ui_details_expand,
        label,
    )
    val state = stringResource(
        if (expanded) R.string.ui_details_expanded else R.string.ui_details_collapsed,
    )
    Column(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
                .semantics { stateDescription = state }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MicroLabel(label, Modifier.weight(1f))
            summary?.let {
                Text(it, style = FitFaceType.numeric, color = summaryColor)
            }
            Text(
                if (expanded) "▴" else "▾",
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = body,
            )
        }
    }
}
