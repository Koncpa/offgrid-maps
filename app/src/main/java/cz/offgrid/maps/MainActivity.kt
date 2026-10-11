package cz.offgrid.maps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import cz.offgrid.maps.presentation.theme.OffGridTheme
import cz.offgrid.maps.presentation.ui.MapScreen

/**
 * Hlavní aktivita aplikace OffGrid Maps pro Wear OS.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            OffGridTheme {
                MapScreen()
            }
        }
    }
}
