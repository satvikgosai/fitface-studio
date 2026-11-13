package dev.fitface.studio.feature.editor

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.fitface.studio.core.ui.LearnedGesture
import dev.fitface.studio.core.ui.LearnedHint
import dev.fitface.studio.core.ui.MicroLabel
import dev.fitface.studio.core.ui.rememberLearnedHint

/**
 * Hold the face to see it as it is now; let go to see the change again. A review page's face
 * shows what the change will leave, and this is how it compares, in place of Before and After.
 *
 * The face draws the face as it is while [comparing]. [modifier] goes on a face that takes no
 * other touch: a press compares, and a press long enough to be deliberate counts as having
 * learned the gesture. [stillModifier] goes on a face a drag places something on: only a finger
 * held still for a long press compares, so a drag or a pinch stays a drag or a pinch. Assistive
 * technology gets the same as an action that switches between the two.
 */
@Stable
internal class FaceCompare internal constructor(private val hint: LearnedHint, private val toggleLabel: String) {
    var comparing by mutableStateOf(false)
        private set

    val modifier: Modifier = Modifier
        .pointerInput(this) {
            detectTapGestures(
                onPress = {
                    comparing = true
                    val pressed = SystemClock.uptimeMillis()
                    try {
                        tryAwaitRelease()
                    } finally {
                        comparing = false
                    }
                    if (SystemClock.uptimeMillis() - pressed >= CompareLearnedMillis) hint.learn()
                },
            )
        }
        .then(accessible)

    val stillModifier: Modifier = Modifier
        .pointerInput(this) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Null when the finger moved, lifted or was taken by the pinch-and-drag first.
                awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                comparing = true
                hint.learn()
                try {
                    // Held, the face stays as it is and the finger moves nothing: what it
                    // would have dragged is taken here, before the placing gesture sees it.
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                } finally {
                    comparing = false
                }
            }
        }
        .then(accessible)

    private val accessible: Modifier
        get() = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(toggleLabel) {
                comparing = !comparing
                hint.learn()
                true
            })
        }
}

/** One per page: [key] is what is being compared, so a new change starts showing the change. */
@Composable
internal fun rememberFaceCompare(key: Any?): FaceCompare {
    val hint = rememberLearnedHint(LearnedGesture.CompareHold)
    val label = stringResource(R.string.editor_compare_action)
    return remember(key, hint, label) { FaceCompare(hint, label) }
}

/** How to compare, until holding the face to compare has been done once on this device. */
@Composable
internal fun CompareHint(text: String, modifier: Modifier = Modifier) {
    val hint = rememberLearnedHint(LearnedGesture.CompareHold)
    if (hint.shown) MicroLabel(text, modifier.fillMaxWidth())
}

/** How long a press on a face must last to count as comparing, not a tap. */
private const val CompareLearnedMillis = 400L
