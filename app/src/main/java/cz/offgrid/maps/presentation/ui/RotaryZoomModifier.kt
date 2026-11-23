package cz.offgrid.maps.presentation.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView

/**
 * Modifier for handling the rotating crown or bezel on Wear OS watches.
 *
 * How it works:
 * 1. Accumulates vertical rotary scroll pixels (positive = clockwise, negative = counter-clockwise).
 * 2. Triggers tactile haptic feedback (`CLOCK_TICK`) on each zoom step.
 * 3. Enables smooth map zooming without obscuring the display with fingers.
 */
fun Modifier.rotaryZoom(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit
): Modifier = composed {
    val view = LocalView.current
    val currentOnZoomIn by rememberUpdatedState(onZoomIn)
    val currentOnZoomOut by rememberUpdatedState(onZoomOut)
    var accumulatedDelta by remember { mutableFloatStateOf(0f) }
    val threshold = 40f // Rotary scroll sensitivity threshold per zoom step

    this.onRotaryScrollEvent { event ->
        accumulatedDelta += event.verticalScrollPixels
        if (accumulatedDelta >= threshold) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            currentOnZoomIn()
            accumulatedDelta = 0f
            true
        } else if (accumulatedDelta <= -threshold) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            currentOnZoomOut()
            accumulatedDelta = 0f
            true
        } else {
            false
        }
    }
}
