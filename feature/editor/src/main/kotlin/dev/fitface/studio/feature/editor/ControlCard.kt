package dev.fitface.studio.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.ui.FitChip
import dev.fitface.studio.core.ui.MicroLabel

/**
 * The box every major control sits in — position, size, rotation, colour, arrangement, fit,
 * scope, frames and the part of a video — so each reads as one control and the page as a
 * stack of them, the same on every page.
 */
@Composable
internal fun ControlCard(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(14.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** How a background or photo fills the face: the same chips on every page that places one. */
@Composable
internal fun FitControls(fit: ImageFit, enabled: Boolean, onFit: (ImageFit) -> Unit) {
    ControlCard {
        MicroLabel(stringResource(R.string.editor_bg_fit_heading))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageFit.entries.forEach { option ->
                FitChip(option.name, fit == option, { onFit(option) }, Modifier.weight(1f), enabled)
            }
        }
    }
}
