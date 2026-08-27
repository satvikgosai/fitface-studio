package dev.fitface.studio.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * A pill that is one of a set.
 *
 * @param role defaults to [Role.RadioButton], which is what a pick-one chip is. A sort chip
 *   that also reverses its own order on a second tap is **not** one — announcing it as a
 *   radio button tells a screen reader the only thing it can do is become selected, and it
 *   is already selected. Those pass [Role.Button].
 * @param contentDescription replaces the label for a screen reader. A sort chip uses it to
 *   say what a second tap does, which the label alone cannot.
 */
@Composable
fun FitChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    contentDescription: String? = null,
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
                role = role,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .then(
                if (contentDescription == null) {
                    Modifier
                } else {
                    Modifier.semantics { this.contentDescription = contentDescription }
                },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = foreground, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * A square, bordered action for a top bar: the one shape a bar action is allowed to be.
 *
 * This is the back button's geometry, lifted out so everything in an actions slot shares it.
 * The rule it exists to enforce is in `FitTopBarLayoutTest`: `FitTopBar` gives the title
 * `weight(1f)`, so a text-labelled action takes its width out of the title and ellipsizes the
 * subtitle. 38dp is what the design specifies and what the back button has always been.
 *
 * `contentDescription` is required rather than optional because the child is a glyph: without
 * it TalkBack reads the literal character, which is how the `⋯` overflow announced itself as
 * "midline ellipsis".
 *
 * 38dp is under Material's 48dp touch-target guideline, and deliberately so: it is what the
 * design specifies, what the back button has always been, and the same order as `FitChip`'s
 * 42dp minimum. Raising this one control alone would make it the widest thing in the actions
 * slot and reopen the crowding on the narrow bars; raising all of them is a separate change.
 */
@Composable
fun FitIconButton(
    glyph: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(ICON_BUTTON_SIZE).semantics {
            this.contentDescription = contentDescription
        },
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else .45f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = tint ?: MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f),
        ),
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge)
    }
}

/** The design's square-action size, and what the back button has always measured. */
internal val ICON_BUTTON_SIZE = 38.dp

/**
 * A state marker for a top bar: EDITED, UNAPPLIED.
 *
 * It says that something is outstanding, and it lives in the bar because the bar is the one
 * part of a page that does not scroll. The Background page is why: its commit buttons are the
 * last children of a long scrolling column, so someone positioned an image, never saw
 * "Use positioned image", and lost the edit. Moving the buttons up fixed that and looked
 * wrong; a badge says the same thing from somewhere always visible.
 *
 * The label is passed already cased, unlike [MicroLabel] — these are single words that live in
 * `strings.xml` in the form they are read, and `uppercase()` on a translated string is a trap
 * in Turkish. Keep them to one short word: this sits in the actions slot and so spends the
 * width budget `FitTopBarLayoutTest` guards.
 */
@Composable
fun FitBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .background(color.copy(alpha = .13f), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        color = color,
        style = FitFaceType.micro,
    )
}

@Composable
fun MicroLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = FitFaceType.micro,
        color = color ?: MaterialTheme.fitText.secondary,
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
            FitIconButton(
                glyph = "‹",
                contentDescription = stringResource(R.string.ui_back),
                onClick = onBack,
            )
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
                    color = MaterialTheme.fitText.secondary,
                )
            }
        }
        actions()
    }
}
