package dev.fitface.studio.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * A gesture nothing on screen can show — hold to select several, hold to compare, pinch —
 * spelled out until it has been done once on this device, and then not again.
 *
 * The rest of how-to copy lives behind [FitInfo]; these few stay visible at first because a
 * gesture behind an ⓘ nobody taps is a feature nobody finds. One private preferences file,
 * one boolean per gesture; losing it only shows a hint again.
 */
enum class LearnedGesture(internal val key: String) {
    SelectSeveral("select_several"),
    CompareHold("compare_hold"),
    PinchResize("pinch_resize"),
}

/**
 * One gesture's hint. Every screen asking about the same gesture shares one state, so a set
 * made from the list clears the canvas's line too.
 */
@Stable
class LearnedHint internal constructor(
    private val gesture: LearnedGesture,
    private val context: Context,
    private val unlearned: MutableState<Boolean>,
) {
    val shown: Boolean get() = unlearned.value

    /** Records that the gesture has been done. Cheap to call on every use. */
    fun learn() {
        if (!unlearned.value) return
        unlearned.value = false
        prefs(context).edit().putBoolean(gesture.key, true).apply()
    }
}

@Composable
fun rememberLearnedHint(gesture: LearnedGesture): LearnedHint {
    val context = LocalContext.current.applicationContext
    return remember(gesture) {
        val state = synchronized(states) {
            states.getOrPut(gesture) { mutableStateOf(!prefs(context).getBoolean(gesture.key, false)) }
        }
        LearnedHint(gesture, context, state)
    }
}

private val states = mutableMapOf<LearnedGesture, MutableState<Boolean>>()

private fun prefs(context: Context) = context.getSharedPreferences("fit_learned_hints", Context.MODE_PRIVATE)
