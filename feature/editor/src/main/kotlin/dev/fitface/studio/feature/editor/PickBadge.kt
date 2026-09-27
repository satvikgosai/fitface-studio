package dev.fitface.studio.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fitface.studio.core.ui.FitFaceType

/**
 * A pick's number in the top-left corner of its rectangle — on the donor face, on the
 * review of a set, and on the editor's canvas when several widgets are selected, so a
 * numbered set looks the same wherever one is being built.
 */
internal fun DrawScope.drawPickBadge(order: Int, corner: Offset, color: Color, measurer: TextMeasurer) {
    val badge = measurer.measure(
        AnnotatedString("$order"),
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold),
    )
    val radius = PickBadgeRadius.toPx()
    val centre = pickBadgeCentre(corner, radius, size.width, size.height, FaceOutlineCorner.toPx())
    drawCircle(color = color, radius = radius, center = centre)
    drawText(
        textLayoutResult = badge,
        color = Color.Black,
        topLeft = Offset(centre.x - badge.size.width / 2f, centre.y - badge.size.height / 2f),
    )
}

internal val PickBadgeRadius = 9.dp

/** The face's rounded outline, which every canvas that draws a face clips to. */
internal val FaceOutlineCorner = 32.dp

/**
 * Where a pick's badge is drawn: tucked into its rectangle's top-left corner, and moved
 * inward only as far as it takes to lie wholly inside the face's rounded outline.
 *
 * Widgets are laid out to the panel's edges, and the face is clipped to a 32dp radius, so a
 * widget in the corner — face `00016`'s battery gauge is one — had its number cut in half by
 * the clip, on the one widget whose number was 1. Pure so a test can hold it.
 */
internal fun pickBadgeCentre(
    corner: Offset,
    radius: Float,
    width: Float,
    height: Float,
    outline: Float,
): Offset {
    var x = (corner.x + radius).coerceIn(radius, maxOf(radius, width - radius))
    var y = (corner.y + radius).coerceIn(radius, maxOf(radius, height - radius))
    // Inside one of the four corner squares, the circle has to fit inside that corner's arc.
    val arcX = when {
        x < outline -> outline
        x > width - outline -> width - outline
        else -> return Offset(x, y)
    }
    val arcY = when {
        y < outline -> outline
        y > height - outline -> height - outline
        else -> return Offset(x, y)
    }
    val reach = (outline - radius).coerceAtLeast(0f)
    val dx = x - arcX
    val dy = y - arcY
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    if (distance > reach) {
        x = arcX + dx * reach / distance
        y = arcY + dy * reach / distance
    }
    return Offset(x, y)
}

/** A pick's number over a row's artwork tile — the same badge the canvases draw. */
@Composable
internal fun PickBadge(order: Int) {
    Box(
        Modifier.size(PickBadgeRadius * 2)
            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(999.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$order", style = FitFaceType.micro, color = Color.Black)
    }
}
