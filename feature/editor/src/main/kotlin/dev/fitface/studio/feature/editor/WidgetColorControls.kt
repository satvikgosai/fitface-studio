package dev.fitface.studio.feature.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.Hsv
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.colorHex
import dev.fitface.studio.core.model.colorOf
import dev.fitface.studio.core.model.hsvOf
import dev.fitface.studio.core.model.parseColorHex
import dev.fitface.studio.core.ui.FitButton
import dev.fitface.studio.core.ui.FitButtonStyle
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.fitText

/**
 * Colours one tap applies: black, white and the three primaries. Starting points, not the
 * range — any colour can be set below — and few enough to sit in one row on a narrow phone.
 */
internal val WidgetColorPresets: List<Pair<Int, Int>> = listOf(
    R.string.editor_color_black to 0xFF00_0000.toInt(),
    R.string.editor_color_white to 0xFFFF_FFFF.toInt(),
    R.string.editor_color_red to 0xFFFF_0000.toInt(),
    R.string.editor_color_green to 0xFF00_FF00.toInt(),
    R.string.editor_color_blue to 0xFF00_00FF.toInt(),
)

/**
 * Colour in Edit widget: presets, any colour by hue, saturation and brightness or `#RRGGBB`,
 * and a reset — on the page, under its preview, like rotation.
 *
 * A preset commits when tapped. The custom colour commits on Apply, because every commit
 * rewrites and validates the container: a slider that committed while dragging would save
 * dozens of edits for one choice.
 *
 * [onColor] with null resets: each style in scope returns to the colour *it* shipped with.
 */
@Composable
internal fun ColorControls(
    widget: WidgetGuide,
    snapshot: EditorSnapshot,
    allStyles: Boolean,
    enabled: Boolean,
    onColor: (Int?) -> Unit,
) {
    val current = widget.colorArgb ?: return
    val original = widget.originalColorArgb ?: current
    val start = hsvOf(current)
    var hue by rememberSaveable(widget.globalIndex, current) { mutableFloatStateOf(start.hue) }
    var saturation by rememberSaveable(widget.globalIndex, current) { mutableFloatStateOf(start.saturation) }
    var brightness by rememberSaveable(widget.globalIndex, current) { mutableFloatStateOf(start.value) }
    var hex by rememberSaveable(widget.globalIndex, current) { mutableStateOf(colorHex(current)) }
    val pending = parseColorHex(hex)
    fun fromSliders(next: Hsv) {
        hue = next.hue; saturation = next.saturation; brightness = next.value
        hex = colorHex(colorOf(next))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MicroLabel(stringResource(R.string.editor_color))
        Text(stringResource(R.string.editor_color_current, colorHex(current), colorHex(original)))
        Text(
            if (!snapshot.isAodSelected && allStyles && widget.importedFromFaceId == null) {
                stringResource(R.string.editor_rotation_scope_all)
            } else {
                stringResource(R.string.editor_rotation_scope, variantLabel(snapshot.selectedVariant.basename))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
        // Five 48dp targets need 240dp, inside even a 320dp phone's page.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WidgetColorPresets.forEach { (label, color) ->
                ColorSwatch(color, label, selected = color == current, enabled = enabled) { onColor(color) }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = pending ?: current
            Box(
                Modifier.size(48.dp)
                    .background(Color(shown), MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .semantics { contentDescription = colorHex(shown) },
            )
            OutlinedTextField(
                value = hex,
                onValueChange = { text ->
                    hex = text
                    parseColorHex(text)?.let(::hsvOf)?.let {
                        hue = it.hue; saturation = it.saturation; brightness = it.value
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.editor_color_hex)) },
                isError = pending == null,
            )
        }
        if (pending == null) {
            Text(stringResource(R.string.editor_color_hex_invalid), color = MaterialTheme.colorScheme.error)
        }
        ColorSliders(Hsv(hue, saturation, brightness), enabled, ::fromSliders)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FitButton(
                stringResource(R.string.editor_color_apply),
                { pending?.let(onColor) },
                enabled = enabled && pending != null && pending != current,
                style = FitButtonStyle.Secondary,
            )
            FitButton(
                stringResource(R.string.editor_color_reset),
                { onColor(null) },
                enabled = enabled && original != current,
                style = FitButtonStyle.Secondary,
            )
        }
        Text(
            stringResource(R.string.editor_color_watch_depth),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.fitText.secondary,
        )
    }
}

/**
 * Hue, saturation and brightness as three compact rows: each label beside its slider, and
 * each track drawn as the range it picks from, so a slider shows what dragging it does.
 *
 * The labels share the widest one's measured width, so the tracks line up at any text size
 * instead of truncating "Brightness" at a large font scale. Each row keeps the slider's
 * 48dp touch target; the label is also the slider's accessible name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorSliders(hsv: Hsv, enabled: Boolean, onChange: (Hsv) -> Unit) {
    val labels = listOf(
        R.string.editor_color_hue,
        R.string.editor_color_saturation,
        R.string.editor_color_brightness,
    ).map { stringResource(it) }
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val labelWidth = with(LocalDensity.current) {
        labels.maxOf { measurer.measure(it, style).size.width }.toDp()
    }
    val tracks = listOf(
        (0..6).map { Color(colorOf(Hsv(it * 60f, 1f, 1f))) },
        listOf(Color(colorOf(hsv.copy(saturation = 0f))), Color(colorOf(hsv.copy(saturation = 1f)))),
        listOf(Color.Black, Color(colorOf(hsv.copy(value = 1f)))),
    )
    val values = listOf(hsv.hue / 360f, hsv.saturation, hsv.value)
    Column {
        labels.forEachIndexed { index, name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    name,
                    Modifier.width(labelWidth),
                    style = style,
                    color = MaterialTheme.fitText.secondary,
                    maxLines = 1,
                )
                Slider(
                    value = values[index].coerceIn(0f, 1f),
                    onValueChange = {
                        onChange(
                            when (index) {
                                0 -> hsv.copy(hue = it * 360f)
                                1 -> hsv.copy(saturation = it)
                                else -> hsv.copy(value = it)
                            },
                        )
                    },
                    modifier = Modifier.weight(1f).semantics { contentDescription = name },
                    enabled = enabled,
                    thumb = {
                        // A ring, so the track's colour under it stays visible; the dark edge
                        // keeps the white ring readable on the white end of a track.
                        Box(
                            Modifier.size(20.dp)
                                .alpha(if (enabled) 1f else 0.38f)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .padding(1.dp)
                                .border(3.dp, Color.White, CircleShape),
                        )
                    },
                    track = {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(8.dp)
                                .alpha(if (enabled) 1f else 0.38f)
                                .clip(CircleShape)
                                .background(Brush.horizontalGradient(tracks[index]))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    },
                )
            }
        }
    }
}

/** One preset: a filled circle in a 48dp target, ringed when it is the current colour. */
@Composable
private fun ColorSwatch(color: Int, @StringRes label: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val name = stringResource(label)
    Box(
        Modifier.size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name }
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(44.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
        }
        Box(
            Modifier.size(34.dp)
                .background(Color(color), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}
