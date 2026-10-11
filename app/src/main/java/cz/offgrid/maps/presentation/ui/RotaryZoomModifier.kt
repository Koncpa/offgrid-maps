package cz.offgrid.maps.presentation.ui

import android.view.HapticFeedbackConstants
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView

/**
 * Modifier pro obsluhu otočné korunky (Crown) nebo lunety (Bezel) na Wear OS hodinkách.
 *
 * Jak to funguje:
 * 1. Zpracovává rotaci v pixelech (kladná hodnota = po směru hodinových ručiček, záporná = proti).
 * 2. Poskytuje jemnou haptickou odezvu (Haptic Feedback) pro každý krok otočení,
 *    takže uživatel cítí fyzické cvaknutí na zápěstí.
 * 3. Umožňuje plynulý zoom mapy bez toho, aby si uživatel zakrýval displej prsty.
 */
fun Modifier.rotaryZoom(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit
): Modifier = composed {
    val view = LocalView.current
    var accumulatedDelta = 0f
    val threshold = 40f // Práh citlivosti otočení pro jeden zoom krok

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
