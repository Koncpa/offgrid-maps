package cz.offgrid.maps

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import cz.offgrid.maps.data.tracking.GpxExporter
import cz.offgrid.maps.data.tracking.TrackingManager
import cz.offgrid.maps.presentation.theme.OffGridTheme
import cz.offgrid.maps.presentation.ui.MapScreen

/**
 * Hlavní aktivita aplikace OffGrid Maps pro Wear OS.
 * Zpracovává také přímé otevření .gpx souborů přijatých z telefonu (Mapy.cz, Quick Share, Bluetooth).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleIncomingGpxIntent(intent)

        setContent {
            OffGridTheme {
                MapScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingGpxIntent(intent)
    }

    private fun handleIncomingGpxIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW) {
            val importedFile = GpxExporter.importGpxFromUri(this, uri)
            if (importedFile != null) {
                val points = GpxExporter.parseGpxFile(importedFile)
                if (points.isNotEmpty()) {
                    TrackingManager.setPlannedRoute(importedFile.nameWithoutExtension, points)
                    TrackingManager.setSavedSummary("Načteno: ${importedFile.nameWithoutExtension}")
                }
            }
        }
    }
}
