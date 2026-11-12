package dev.fitface.studio.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.ALBUM_PHOTO_HEIGHT
import dev.fitface.studio.core.model.ALBUM_PHOTO_WIDTH
import dev.fitface.studio.core.model.PhotoClockSetting
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.colorHex
import dev.fitface.studio.core.ui.FitFaceType
import dev.fitface.studio.core.ui.FitIconButton
import dev.fitface.studio.core.ui.FitInfo
import dev.fitface.studio.core.ui.fitText

/**
 * The Photos face's Canvas: the editor's Canvas page for a face whose one widget is its clock.
 * The face takes the height the page leaves, drawn with the photo being placed (else the one
 * shown) and the clock outlined as the editor outlines a selected widget; under it, the
 * editor's selection tray for the clock, with the colours the watch offers in the tray's
 * button slots. The clock is always the selection, since there is nothing else to select.
 */
@Composable
internal fun PhotosCanvasTab(
    state: PhotoAlbumUiState,
    onColour: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = clockLook(state)
    Column(modifier) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val aspect = ALBUM_PHOTO_WIDTH.toFloat() / ALBUM_PHOTO_HEIGHT
            val width = minOf(maxWidth, maxHeight * aspect, PhotosCanvasMaxWidth)
            PlacedPhoto(
                photo = stylesBackdrop(state),
                description = stringResource(R.string.editor_photos_canvas_face_a11y),
                enabled = false,
                onTransform = { _, _, _ -> },
                clock = look,
                outline = look?.let { remember(it.picture) { opaqueBounds(it.picture) } },
                modifier = Modifier.width(width),
            )
        }
        ClockTray(state, onColour)
    }
}

/**
 * The selection tray for the clock, in the editor's tray frame: the title, the colour as the
 * Colour page states it, and two rows of the tray's square buttons, one per colour the watch
 * offers, scrolling sideways past the seven a row shows.
 */
@Composable
private fun ClockTray(state: PhotoAlbumUiState, onColour: (Int) -> Unit) {
    val options = state.album?.clockOptions
    val clock = state.clock
    val off = state.clockSetting == PhotoClockSetting.None
    val title = stringResource(R.string.editor_photos_clock_title)
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(TrayPadding),
    ) {
        TrayTitle(title) {
            FitInfo(
                topic = title,
                text = stringResource(
                    when {
                        options == null -> R.string.editor_photos_clock_unavailable
                        off -> R.string.editor_photos_clock_off_info
                        else -> R.string.editor_photos_clock_info
                    },
                ),
            )
        }
        val current = clock?.let { options?.colour(it.colour) }
        Text(
            when {
                off -> stringResource(R.string.editor_photos_no_clock)
                options == null || current == null -> stringResource(R.string.editor_photos_clock_unavailable_short)
                else -> stringResource(R.string.editor_photos_clock_facts, colorHex(current.argb),
                    options.colours.indexOf(current) + 1, options.colours.size)
            },
            color = if (current == null) MaterialTheme.fitText.secondary else MaterialTheme.colorScheme.primary,
            style = FitFaceType.numeric,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // A clock that is off has no colour to choose; Styles brings it back.
        if (options == null || off) return@Column
        val selectedIndex = options.colours.indexOf(current).coerceAtLeast(0)
        // Opens on the column holding the colour the face has, so it is in view.
        val grid = rememberLazyGridState(initialFirstVisibleItemIndex = selectedIndex - selectedIndex % ClockRows)
        val enabled = !state.busy
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = TrayFirstRowTop)) {
            // Seven to a row, as the editor's second row has them, the rest a swipe away.
            val slot = (maxWidth - TraySecondRowGap * (ClockColumnsShown - 1)) / ClockColumnsShown
            LazyHorizontalGrid(
                rows = GridCells.Fixed(ClockRows),
                state = grid,
                modifier = Modifier.fillMaxWidth().height(TrayButtonHeight * ClockRows + TraySecondRowTop),
                horizontalArrangement = Arrangement.spacedBy(TraySecondRowGap),
                verticalArrangement = Arrangement.spacedBy(TraySecondRowTop),
            ) {
                itemsIndexed(options.colours, key = { _, choice -> choice.id }) { index, choice ->
                    val selected = choice == current
                    FitIconButton(
                        contentDescription = stringResource(R.string.editor_photos_clock_colour_a11y, index + 1,
                            options.colours.size, colorHex(choice.argb)),
                        onClick = { if (!selected) onColour(choice.id) },
                        modifier = Modifier.width(slot),
                        enabled = enabled,
                        selected = selected,
                    ) {
                        Box(
                            Modifier.size(ClockSwatchSize)
                                .alpha(if (enabled) 1f else 0.38f)
                                .background(Color(choice.argb), CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Where a clock picture draws anything: the box round its pixels that are not fully clear, in
 * the picture's own pixels, or null for a picture that draws nothing.
 */
internal fun opaqueBounds(frame: PreviewFrame): IntRect? {
    var left = frame.width
    var top = frame.height
    var right = -1
    var bottom = -1
    for (y in 0 until frame.height) {
        for (x in 0 until frame.width) {
            if (frame.argb[y * frame.width + x] ushr 24 == 0) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    return if (right < 0) null else IntRect(left, top, right + 1, bottom + 1)
}

private const val ClockRows = 2
private const val ClockColumnsShown = 7
private val ClockSwatchSize = 22.dp
/** The editor's Canvas cap, so the two faces come out the same size. */
private val PhotosCanvasMaxWidth = 288.dp
