package dev.fitface.studio.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class FitButtonStyle { Primary, Secondary, Danger, Experimental }

enum class FitStatus { Pass, Warning, Fail }

@Composable
fun FitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    style: FitButtonStyle = FitButtonStyle.Primary,
    /** Pass a hoisted source to observe presses, e.g. for press-and-hold repeat. */
    interactionSource: MutableInteractionSource? = null,
) {
    val isEnabled = enabled && !loading
    if (style == FitButtonStyle.Primary) {
        Button(
            onClick = onClick,
            enabled = isEnabled,
            modifier = modifier.heightIn(min = 46.dp),
            shape = MaterialTheme.shapes.small,
            interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .05f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f),
            ),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        val color = when (style) {
            FitButtonStyle.Danger -> MaterialTheme.colorScheme.error
            FitButtonStyle.Experimental -> MaterialTheme.fitColors.experimental
            else -> MaterialTheme.colorScheme.onSurface
        }
        OutlinedButton(
            onClick = onClick,
            enabled = isEnabled,
            modifier = modifier.heightIn(min = 46.dp),
            shape = MaterialTheme.shapes.small,
            interactionSource = interactionSource,
            border = BorderStroke(1.dp, color.copy(alpha = if (enabled) .38f else .12f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = color,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f),
            ),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(9.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun FitChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val border = if (selected) selectedColor else MaterialTheme.colorScheme.outlineVariant
    val foreground = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .28f)
        selected -> selectedColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .heightIn(min = 42.dp)
            .alpha(if (enabled) 1f else .7f)
            .background(
                if (selected) selectedColor.copy(alpha = .13f) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .border(1.dp, border.copy(alpha = if (enabled) 1f else .45f), RoundedCornerShape(999.dp))
            .clickable(
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = foreground, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun MicroLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = FitFaceType.micro,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .68f),
    )
}

@Composable
fun StatusBanner(
    status: FitStatus,
    message: String,
    modifier: Modifier = Modifier,
    // A @Composable function's default argument is evaluated in composable context, so it
    // can read a resource the same way the body would.
    label: String = stringResource(
        when (status) {
            FitStatus.Pass -> R.string.ui_status_pass
            FitStatus.Warning -> R.string.ui_status_warning
            FitStatus.Fail -> R.string.ui_status_fail
        },
    ),
) {
    val color = when (status) {
        FitStatus.Pass -> MaterialTheme.colorScheme.primary
        FitStatus.Warning -> MaterialTheme.fitColors.warning
        FitStatus.Fail -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = .08f), RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp))
            .border(width = 0.dp, color = Color.Transparent)
            .padding(start = 14.dp, top = 13.dp, end = 14.dp, bottom = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(2.dp).heightIn(min = 34.dp).background(color))
        Text(label, color = color, style = FitFaceType.numeric)
        Text(
            message,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun FitTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.size(38.dp),
                shape = MaterialTheme.shapes.small,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) { Text("‹", style = MaterialTheme.typography.titleLarge) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            subtitle?.let {
                Text(
                    it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
                )
            }
        }
        actions()
    }
}
