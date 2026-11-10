package dev.fitface.studio.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fitface.studio.core.model.EditorSnapshot

/**
 * A page that adjusts what the face shows — Edit widget, and placing a background, a moving
 * background or a picture. The face stays put at the top while the controls under it scroll,
 * so what a control changes is seen as it changes: scrolled with everything else, the face
 * left the screen just as the colour or position controls came into view.
 *
 * Pinned only where the window is tall enough to keep the face and still show controls under
 * it ([pinnedFaceWidth]); a landscape phone or a short window scrolls the whole page as before.
 * [face] is given the width modifier its face takes; it may add a line under it, such as
 * Pause/Play, whose height it passes as [faceExtra].
 */
@Composable
internal fun PinnedFacePage(
    snapshot: EditorSnapshot,
    faceCap: Dp,
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    faceExtra: Dp = 0.dp,
    face: @Composable ColumnScope.(Modifier) -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val aspect = snapshot.preview.width.toFloat() / snapshot.preview.height.coerceAtLeast(1)
        val pinned = pinnedFaceWidth(maxWidth - PagePadding * 2, maxHeight, faceCap, aspect, faceExtra)
        if (pinned != null) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = PagePadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    face(Modifier.width(pinned))
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(PagePadding),
                    horizontalAlignment = horizontalAlignment,
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    content = controls,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding),
                horizontalAlignment = horizontalAlignment,
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    face(Modifier.widthIn(max = faceCap))
                }
                controls()
            }
        }
    }
}

/**
 * The width a pinned face takes in a page [maxWidth] × [maxHeight], or null when the page is
 * too short to pin one and should scroll whole. At most [cap], and never more than
 * [PinnedFaceShare] of the height (less [extra], a line under the face), so the controls keep
 * the rest. Pure, so a test can pin it.
 */
internal fun pinnedFaceWidth(maxWidth: Dp, maxHeight: Dp, cap: Dp, aspect: Float, extra: Dp = 0.dp): Dp? {
    if (maxHeight < PinnedFaceMinHeight) return null
    return minOf(cap, maxWidth, (maxHeight * PinnedFaceShare - extra).coerceAtLeast(0.dp) * aspect)
}

/** Below this the controls would get a sliver; the canvas page stacks from the same height. */
private val PinnedFaceMinHeight = 480.dp

/** The most of the page's height a pinned face may take. */
private const val PinnedFaceShare = 0.45f

private val PagePadding = 16.dp
