package dev.fitface.studio.core.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * How something works, reachable from a drawn ⓘ beside the thing it explains.
 *
 * The screen states what is so — values, scope, state, errors, refusals — and this carries
 * why and how. Tap, keyboard and TalkBack open the same bubble; it stays until it is
 * dismissed (outside tap, back, the glyph again), because a timer would close a long reason
 * before it was read. The bubble is a focusable popup, so one is open at a time without any
 * shared host: the tap that would open a second one dismisses the first.
 *
 * The glyph is drawn, not the ℹ character, which some Android fonts turn into an emoji. It is
 * sized to the text it follows — [glyphSize] — because one a size bigger than its label reads
 * as the loudest thing in the row. Its layout is a few dp more so a row does not grow;
 * Compose widens a small clickable's touch area to the 48dp minimum on its own.
 *
 * [key] closes an open bubble when what it describes changes, such as the selected widget.
 */
@Composable
fun FitInfo(
    topic: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.fitText.secondary,
    key: Any? = null,
    glyphSize: Dp = InfoGlyphBody,
    content: @Composable ColumnScope.() -> Unit,
) {
    InfoTrigger(topic, key, modifier, content) { open, onToggle ->
        val description = stringResource(R.string.ui_info_about, topic)
        Box(
            Modifier.size(glyphSize + InfoGlyphMargin)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            InfoGlyph(if (open) MaterialTheme.colorScheme.primary else tint, glyphSize)
        }
    }
}

/** [FitInfo] with one paragraph, the common case. */
@Composable
fun FitInfo(
    topic: String,
    text: String,
    modifier: Modifier = Modifier,
    key: Any? = null,
    tint: Color = MaterialTheme.fitText.secondary,
    glyphSize: Dp = InfoGlyphBody,
) {
    FitInfo(topic, modifier, tint = tint, key = key, glyphSize = glyphSize) { InfoText(text) }
}

/**
 * A [MicroLabel] heading with its ⓘ, the top line of most control cards. [topic] defaults to
 * the label, which is what TalkBack reads as "About ‹label›".
 */
@Composable
fun FitHeading(
    label: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    topic: String = label,
    key: Any? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    info: (@Composable ColumnScope.() -> Unit)?,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MicroLabel(label, color = color)
        trailing()
        if (info != null) FitInfo(topic, key = key, glyphSize = InfoGlyphMicro, content = info)
    }
}

/**
 * A [FitBadge] that is itself the trigger: a property worth naming on screen (NO OUTLINE,
 * OPAQUE, EXPERIMENTAL) whose explanation is not worth a banner.
 */
@Composable
fun FitTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    key: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    InfoTrigger(text, key, modifier, content) { _, onToggle ->
        val description = stringResource(R.string.ui_info_about, text)
        Row(
            Modifier
                .background(color.copy(alpha = .13f), MaterialTheme.shapes.extraSmall)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(start = 7.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text, color = color, style = FitFaceType.micro)
            InfoGlyph(color, InfoGlyphTag)
        }
    }
}

/** A text trigger, for the one kind of reference whose question is its own label. */
@Composable
fun FitInfoLink(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    InfoTrigger(text, null, modifier, content) { _, onToggle ->
        Row(
            Modifier.heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            InfoGlyph(MaterialTheme.colorScheme.primary, InfoGlyphBody, Modifier.clearAndSetSemantics {})
        }
    }
}

/** A bubble paragraph, in the colour and size every bubble uses. */
@Composable
fun InfoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Technical facts inside a bubble: record numbers, checks, the audit, transfer IDs. */
@Composable
fun InfoFacts(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = FitFaceType.numeric,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoTrigger(
    topic: String,
    key: Any?,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
    trigger: @Composable (open: Boolean, onToggle: () -> Unit) -> Unit,
) {
    var open by rememberSaveable(topic, key) { mutableStateOf(false) }
    // The outside tap that dismisses the bubble lands first when that tap is on the glyph
    // itself, so without this the same tap would open it again straight away.
    var closedAt by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    // A popup anchors to the layout it is composed in, so the trigger gets one of its own:
    // composed beside the trigger instead, the bubble pointed at whatever row or list item
    // held it.
    Box(modifier) {
        trigger(open) {
            if (!open && SystemClock.uptimeMillis() - closedAt < ReopenGuardMillis) return@trigger
            open = !open
        }
        if (open) {
            InfoBubble(
                topic = topic,
                onDismiss = {
                    open = false
                    closedAt = SystemClock.uptimeMillis()
                },
                content = content,
            )
        }
    }
}

@Composable
private fun InfoBubble(topic: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val provider = remember(density) {
        BubblePositionProvider(with(density) { BubbleGap.roundToPx() }, with(density) { BubbleMargin.roundToPx() })
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .widthIn(max = minOf(BubbleMaxWidth, configuration.screenWidthDp.dp - BubbleMargin * 2))
                .heightIn(max = configuration.screenHeightDp.dp / 2)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                // The full outline, not the cards' variant: the bubble sits over those cards and
                // has to read as on top of them.
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .semantics { paneTitle = topic }
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * Above the trigger when it fits, otherwise below, centred on it and kept [margin] inside
 * the window. Below is the fallback rather than the default because a finger on the glyph
 * covers what is under it.
 */
internal class BubblePositionProvider(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centred = anchorBounds.center.x - popupContentSize.width / 2
        val x = centred.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val y = when {
            above >= margin -> above
            below + popupContentSize.height <= windowSize.height - margin -> below
            else -> (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        }
        return IntOffset(x, y)
    }
}

@Composable
private fun InfoGlyph(color: Color, glyphSize: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(glyphSize)) {
        // Thinner as it gets smaller, so a 9dp glyph is not a filled blot.
        val stroke = (glyphSize * .09f).toPx()
        val radius = size.minDimension / 2 - stroke / 2
        drawCircle(color, radius, style = Stroke(stroke))
        drawCircle(color, stroke * .85f, Offset(center.x, center.y - radius * .45f))
        drawLine(
            color,
            Offset(center.x, center.y - radius * .08f),
            Offset(center.x, center.y + radius * .5f),
            strokeWidth = stroke * 1.1f,
            cap = StrokeCap.Round,
        )
    }
}

/** Beside body text (bodySmall, bodyMedium), and in buttons. */
val InfoGlyphBody: Dp = 12.dp
/** After a [MicroLabel] heading. */
private val InfoGlyphMicro: Dp = 10.dp
/** Inside a [FitTag], beside its micro text. */
private val InfoGlyphTag: Dp = 9.dp
private val InfoGlyphMargin: Dp = 4.dp
private val BubbleGap: Dp = 6.dp
private val BubbleMargin: Dp = 16.dp
private val BubbleMaxWidth: Dp = 300.dp
private const val ReopenGuardMillis = 300L
