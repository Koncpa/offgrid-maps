package cz.offgrid.maps.presentation.ui

import android.view.HapticFeedbackConstants
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
    var accumulatedDelta = 0f
    val threshold = 40f // Rotary scroll sensitivity threshold per zoom step

    this.onRotaryScrollEvent { event ->
        accumulatedDelta += event.verticalScrollPixels
        if (accumulatedDelta >= threshold) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onZoomIn()
            accumulatedDelta = 0f
            true
        } else if (accumulatedDelta <= -threshold) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onZoomOut()
            accumulatedDelta = 0f
            true
        } else {
            false
        }
    }
}
