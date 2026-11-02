package dev.fitface.studio.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.fitface.studio.core.model.Hsv
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.colorHex
import dev.fitface.studio.core.model.colorOf
import dev.fitface.studio.core.model.hsvOf
import dev.fitface.studio.core.model.parseColorHex
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.FitFaceType
import dev.fitface.studio.core.ui.FitIconButton
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.fitText
import kotlinx.coroutines.launch

/** The Canvas quick panel's pages, left to right; the panel opens on [Main]. */
internal enum class QuickPage { Colour, Main, Layers }

/**
 * The selection panel as pages: swipe right from the shipped panel for Colour, left for Layers.
 *
 * Every page is at least as tall as [QuickPage.Main], so the face above does not move on a
 * swipe. The dots in the top corner are the tap and screen-reader route to the same pages,
 * because a control here must not need a gesture.
 */
@Composable
internal fun QuickPanelPager(
    modifier: Modifier = Modifier,
    page: @Composable (QuickPage, active: Boolean) -> Unit,
) {
    val pages = QuickPage.entries
    val pagerState = rememberPagerState(initialPage = QuickPage.Main.ordinal) { pages.size }
    val scope = rememberCoroutineScope()
    var mainHeight by remember { mutableIntStateOf(0) }
    val minHeight = with(LocalDensity.current) { mainHeight.toDp() }
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
        ) { index ->
            val shown = pages[index]
            Box(
                if (shown == QuickPage.Main) {
                    Modifier.fillMaxWidth().onSizeChanged { mainHeight = it.height }
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
                page(shown, pagerState.currentPage == index)
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 8.dp),
        ) {
            pages.forEach { target ->
                val current = pagerState.currentPage == target.ordinal
                val description = stringResource(
                    R.string.editor_quick_page_a11y,
                    stringResource(target.title),
                    target.ordinal + 1,
                    pages.size,
                )
                Box(
                    Modifier.size(QuickDotTargetWidth, QuickDotTargetHeight)
                        .selectable(selected = current, role = Role.Tab) {
                            scope.launch { pagerState.animateScrollToPage(target.ordinal) }
                        }
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(7.dp).background(
                            if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                    )
                }
            }
        }
    }
}

private val QuickPage.title: Int
    get() = when (this) {
        QuickPage.Colour -> R.string.editor_quick_page_colour
        QuickPage.Main -> R.string.editor_quick_page_main
        QuickPage.Layers -> R.string.editor_quick_page_layers
    }

private val QuickDotTargetWidth = 28.dp
private val QuickDotTargetHeight = 24.dp

/** What a page's title row leaves free on its right for the page dots. */
internal val QuickPageDotsReserve = QuickDotTargetWidth * QuickPage.entries.size

/**
 * The Colour page, in page 1's frame row for row so `›` lands where page 1 has it.
 *
 * Row one is the colour as text: a hex field, Apply, and the button that opens Edit widget's
 * hue, saturation and brightness sliders in a pop-up. Row two is Original colours, the
 * presets and `›`. A slider saves when the finger lifts, not while it drags, because every
 * save rewrites and validates the container; the field shows the colour on the way. Everything
 * chosen while the page is [active] for one widget is one undo step, as a held nudge is.
 */
@Composable
internal fun QuickColourPage(
    widget: WidgetGuide,
    title: String,
    /** Which styles a colour reaches, or null on a face with no other style to reach. */
    scope: String?,
    isWorking: Boolean,
    active: Boolean,
    onColour: (Int?, Long) -> Unit,
    onInspect: () -> Unit,
) {
    val current = widget.colorArgb
    val original = widget.originalColorArgb ?: current
    // Not disabled while a save runs: a choice made then is queued, and typing never waits.
    val enabled = current != null
    val group = remember(widget.globalIndex, active) { EditGroups.next() }
    // The colour on the way: what the field and the sliders show before it is saved.
    var shown by remember(widget.globalIndex) { mutableStateOf(current) }
    var hex by remember(widget.globalIndex) { mutableStateOf(current?.let(::colorHex).orEmpty()) }
    var hsv by remember(widget.globalIndex) { mutableStateOf(hsvOf(current ?: White)) }
    // Set by typing and cleared by any choice: an undo or a reset then shows in the field
    // even if it still has focus, and only a half-typed code is left alone.
    var hexEdited by remember(widget.globalIndex) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    var dragging by remember { mutableStateOf(false) }
    var slidersOpen by remember(widget.globalIndex) { mutableStateOf(false) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    // Follows the saved colour whenever nothing is being chosen: after a save lands, an undo,
    // or Edit widget. Not while a save runs, or a queued drag would flick back and forth.
    LaunchedEffect(widget.globalIndex, current, isWorking) {
        if (!isWorking && !dragging && current != null) {
            shown = current
            if (!hexEdited) hex = colorHex(current)
            hsv = followHue(hsvOf(current), hsv)
        }
    }
    fun choose(colour: Int?) {
        // Closes the keyboard: a choice is made, and what it covered is the face.
        focus.clearFocus()
        hexEdited = false
        if (colour != null) {
            shown = colour
            hex = colorHex(colour)
            hsv = followHue(hsvOf(colour), hsv)
        }
        onColour(colour, group)
    }
    val typed = applicableHex(hex, current)
    val slidersDescription = stringResource(R.string.editor_quick_colour_sliders)
    Column(
        Modifier.fillMaxWidth()
            .onSizeChanged { pageSize = it }
            .padding(TrayPadding),
    ) {
        TrayTitle(title)
        Text(
            when {
                current == null -> stringResource(R.string.editor_quick_colour_unavailable)
                scope == null -> colorHex(current)
                else -> stringResource(R.string.editor_quick_colour_facts, colorHex(current), scope)
            },
            color = if (current == null) MaterialTheme.fitText.secondary else MaterialTheme.colorScheme.primary,
            style = FitFaceType.numeric,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Row one on page 1's four-column grid, so its edges fall where the nudge arrows' do:
        // the field takes two columns, Apply one, the sliders button the last.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = TrayFirstRowTop)) {
            val column = (maxWidth - TrayFirstRowGap * 3) / 4
            Row(
                horizontalArrangement = Arrangement.spacedBy(TrayFirstRowGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickHexField(
                    value = hex,
                    // What Apply would set, as it is typed.
                    swatch = parseColorHex(hex) ?: shown,
                    valid = hex.isBlank() || parseColorHex(hex) != null,
                    enabled = enabled,
                    onValue = { hex = it; hexEdited = true },
                    onDone = { typed?.let(::choose) },
                    modifier = Modifier.width(column * 2 + TrayFirstRowGap),
                )
                // A column is narrower than the button's own side padding allows for "Apply".
                FitButton(
                    onClick = { typed?.let(::choose) },
                    modifier = Modifier.width(column),
                    enabled = enabled && typed != null,
                    style = FitButtonStyle.Secondary,
                    compact = true,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.editor_quick_colour_apply),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FitButton(
                    onClick = { focus.clearFocus(); slidersOpen = true },
                    modifier = Modifier.width(column).semantics {
                        contentDescription = slidersDescription
                    },
                    enabled = enabled,
                    style = FitButtonStyle.Secondary,
                    compact = true,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SlidersMark()
                }
            }
        }
        // Row two is page 1's second row, slot for slot: seven equal buttons, `›` last.
        Row(
            Modifier.fillMaxWidth().padding(top = TraySecondRowTop),
            horizontalArrangement = Arrangement.spacedBy(TraySecondRowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val atOriginal = current != null && current == original
            FitIconButton(
                contentDescription = stringResource(R.string.editor_image_colour_original),
                onClick = { if (!atOriginal) choose(null) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                selected = atOriginal,
            ) {
                // A ring in the shipped colour: "the colour it came with", not a fill.
                Box(
                    Modifier.size(QuickSwatchSize)
                        .alpha(if (enabled) 1f else 0.38f)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .padding(1.dp)
                        .border(4.dp, Color(original ?: White), CircleShape),
                )
            }
            WidgetColorPresets.forEach { (label, colour) ->
                FitIconButton(
                    contentDescription = stringResource(label),
                    onClick = { if (current != colour) choose(colour) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    selected = current == colour,
                ) {
                    Box(
                        Modifier.size(QuickSwatchSize)
                            .alpha(if (enabled) 1f else 0.38f)
                            .background(Color(colour), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                }
            }
            // Last, in the slot page 1 gives it, so it does not move on a swipe.
            FitIconButton(
                glyph = "›",
                contentDescription = stringResource(R.string.editor_action_record_details),
                onClick = onInspect,
                modifier = Modifier.weight(1f),
                enabled = !isWorking,
            )
        }
        MicroLabel(
            stringResource(R.string.editor_quick_colour_hint),
            Modifier.align(Alignment.CenterHorizontally).padding(top = TrayHintTop),
        )
    }
    if (slidersOpen && current != null) {
        // Exactly over this page, at its size: the sliders replace the panel while they are
        // open and the face above stays in full view.
        Popup(
            popupPositionProvider = OverPanel,
            onDismissRequest = { slidersOpen = false },
            properties = PopupProperties(focusable = true),
        ) {
            val density = LocalDensity.current
            Column(
                Modifier.size(with(density) { pageSize.width.toDp() }, with(density) { pageSize.height.toDp() })
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(TrayPadding),
            ) {
                // The page's own title, its colour where the dots were, and ✕ to close.
                TrayTitle(title) {
                    Box(
                        Modifier.size(14.dp).background(Color(shown ?: current), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Text(colorHex(shown ?: current), color = MaterialTheme.colorScheme.primary, style = FitFaceType.numeric)
                    FitIconButton(
                        glyph = "✕",
                        contentDescription = stringResource(R.string.editor_quick_colour_sliders_close),
                        onClick = { slidersOpen = false },
                    )
                }
                // Three rows of 40dp rather than 48dp, so all three fit in the panel's height.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides QuickSliderRow) {
                    ColorSliders(
                        hsv = hsv,
                        enabled = enabled,
                        onChange = { next ->
                            dragging = true
                            hsv = next
                            shown = colorOf(next)
                            hex = colorHex(colorOf(next))
                        },
                        onRelease = {
                            dragging = false
                            shown?.let(::choose)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The colour a typed hex code would apply, or null when it is not a colour or is the one the
 * widget already has, which is when Apply is disabled.
 */
internal fun applicableHex(text: String, current: Int?): Int? =
    parseColorHex(text)?.takeIf { current != null && it != current }

/**
 * [next] with the hue the sliders already had when [next] has none to give: a grey, white or
 * black, whose hue is undefined. Without this, saving white snapped the hue slider to red.
 */
internal fun followHue(next: Hsv, previous: Hsv): Hsv =
    if (next.saturation == 0f || next.value == 0f) next.copy(hue = previous.hue) else next

/** Puts the pop-up on its anchor, the Colour page, so it covers the panel and nothing else. */
private object OverPanel : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.top)
}

/** A sliders mark, drawn for the same reason the nudge arrows are: no font draws one alike. */
@Composable
private fun SlidersMark() {
    val color = LocalContentColor.current
    Canvas(Modifier.requiredSize(16.dp)) {
        val stroke = 1.6.dp.toPx()
        val knob = 2.4.dp.toPx()
        listOf(.2f to .3f, .5f to .7f, .8f to .45f).forEach { (y, x) ->
            val cy = size.height * y
            drawLine(color, Offset(0f, cy), Offset(size.width, cy), stroke, StrokeCap.Round)
            drawCircle(color, knob, Offset(size.width * x, cy))
        }
    }
}

/** The hex field at a button's height: an outlined box with the colour's swatch inside. */
@Composable
private fun QuickHexField(
    value: String,
    swatch: Int?,
    valid: Boolean,
    enabled: Boolean,
    onValue: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.editor_color_hex)
    val invalid = stringResource(R.string.editor_color_hex_invalid)
    val border = when {
        !valid -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    BasicTextField(
        value = value,
        onValueChange = { onValue(it.take(7)) },
        modifier = modifier.height(QuickRowHeight)
            .semantics {
                contentDescription = label
                if (!valid) error(invalid)
            },
        enabled = enabled,
        singleLine = true,
        textStyle = FitFaceType.numeric.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize()
                    .border(1.dp, border, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp)
                    .alpha(if (enabled) 1f else 0.38f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(16.dp).background(Color(swatch ?: White), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text("#RRGGBB", style = FitFaceType.numeric, color = MaterialTheme.fitText.secondary)
                    }
                    field()
                }
            }
        },
    )
}

private const val White = 0xFFFF_FFFF.toInt()
/** A tray button's height, which the hex field matches. */
private val QuickRowHeight = TrayButtonHeight
private val QuickSwatchSize = 22.dp
private val QuickSliderRow = 40.dp
