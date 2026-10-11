package cz.offgrid.maps.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val OffGridColorPalette = Colors(
    primary = OutdoorAmber,
    primaryVariant = OutdoorLime,
    secondary = OutdoorLime,
    background = AmoledBlack,
    surface = DarkSurface,
    onPrimary = AmoledBlack,
    onSecondary = AmoledBlack,
    onBackground = OutdoorTextPrimary,
    onSurface = OutdoorTextPrimary,
    onError = TrailRed
)

@Composable
fun OffGridTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = OffGridColorPalette,
        content = content
    )
}
