package cz.offgrid.maps.data.tracking

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.LocationResult
import cz.offgrid.maps.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationTrackingServiceTest {

    private lateinit var app: Application
    private lateinit var context: Context
    private lateinit var locationManager: LocationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        context = app
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        TrackingManager.clearTrack()
        File(context.filesDir, "tracks").deleteRecursively()
    }

    private fun makeLocation(lat: Double, lon: Double, altitudeMsl: Double = 350.0, accuracy: Float = 5.0f): Location =
        Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            this.accuracy = accuracy
            altitude = altitudeMsl
            verticalAccuracyMeters = 3.0f
            speed = 1.5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    @Test
    fun startScreenToggleAndAsyncStop_savesGpxFileAndUpdatesSummary() {
        val controller = Robolectric.buildService(LocationTrackingService::class.java).create()
        val service = controller.get()

        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START), 0, 1)
        assertTrue("ACTION_START must activate TrackingManager", TrackingManager.isTracking.value)

        // Record two track points
        TrackingManager.addPoint(TrackPoint(49.1200, 16.3300, altitudeMeters = 320.0, timestampMillis = 1_700_000_000_000L, accuracyMeters = 5f))
        TrackingManager.addPoint(TrackPoint(49.1250, 16.3350, altitudeMeters = 365.0, timestampMillis = 1_700_000_060_000L, accuracyMeters = 5f))

        // Screen off -> screen on transitions must not crash or deactivate tracking
        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_SCREEN_OFF), 0, 2)
        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_SCREEN_ON), 0, 3)
        assertTrue(TrackingManager.isTracking.value)

        // Stop asynchronously writes GPX on ioExecutor and posts final summary to main looper
        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_STOP), 0, 4)
        assertFalse("Tracking must deactivate immediately on STOP", TrackingManager.isTracking.value)

        // Wait briefly for ioExecutor disk write and flush main looper callback
        val tracksDir = File(context.filesDir, "tracks")
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(context.mainLooper).idle()
            if (TrackingManager.lastSavedSummary.value?.startsWith("GPX:") == true) break
            Thread.sleep(25L)
        }

        val summary = TrackingManager.lastSavedSummary.value
        assertNotNull(summary)
        assertTrue("Expected final GPX summary, got: $summary", summary!!.startsWith("GPX:"))
        val savedGpx = tracksDir.listFiles { f -> f.name.startsWith("hike_") && f.name.endsWith(".gpx") }
        assertTrue("Expected saved hike_*.gpx file on disk", (savedGpx?.size ?: 0) >= 1)

        controller.destroy()
    }

    @Test
    fun lowBatteryBroadcast_triggersEmergencySaveAtFivePercentDischargingOnly() {
        val controller = Robolectric.buildService(LocationTrackingService::class.java).create()
        val service = controller.get()

        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START), 0, 1)
        TrackingManager.addPoint(TrackPoint(49.1200, 16.3300, altitudeMeters = 320.0, timestampMillis = 1_700_000_000_000L, accuracyMeters = 5f))
        TrackingManager.addPoint(TrackPoint(49.1220, 16.3320, altitudeMeters = 340.0, timestampMillis = 1_700_000_030_000L, accuracyMeters = 5f))

        // 1. 4% while CHARGING must NOT stop tracking
        context.sendBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 4)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
        )
        Shadows.shadowOf(context.mainLooper).idle()
        assertTrue("Charging at 4% must not trigger emergency stop", TrackingManager.isTracking.value)

        // 2. 4% while DISCHARGING must immediately trigger emergency low-battery GPX save
        context.sendBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 4)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
        )
        Shadows.shadowOf(context.mainLooper).idle()
        assertFalse("Discharging at <= 5% must immediately stop tracking", TrackingManager.isTracking.value)

        val tracksDir = File(context.filesDir, "tracks")
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(context.mainLooper).idle()
            if (TrackingManager.lastSavedSummary.value?.contains("Battery ≤5%") == true) break
            Thread.sleep(25L)
        }

        assertTrue(
            "Expected emergency low-battery summary, got: ${TrackingManager.lastSavedSummary.value}",
            TrackingManager.lastSavedSummary.value?.contains("Battery ≤5%") == true
        )
        val lowBatFiles = tracksDir.listFiles { f -> f.name.startsWith("lowbat5pct_") && f.name.endsWith(".gpx") }
        assertTrue("Expected emergency lowbat5pct_*.gpx file on disk", (lowBatFiles?.size ?: 0) >= 1)

        controller.destroy()
    }

    @Test
    fun hardwareGpsPipeline_suppressesRecentFusedEchoAndSavesRecoveryCheckpointEvery10Points() {
        val controller = Robolectric.buildService(LocationTrackingService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START), 0, 1)

        // Feed 10 distinct direct watch hardware GPS fixes (~13 m & 3 s apart, exceeding minTimeMs=1500 ms)
        val baseTimeMs = System.currentTimeMillis()
        for (i in 0 until 10) {
            val loc = makeLocation(49.1200 + i * 0.0001, 16.3300 + i * 0.0001, altitudeMsl = 320.0 + i * 3.0).apply {
                time = baseTimeMs + i * 3_000L
            }
            Shadows.shadowOf(locationManager).simulateLocation(loc)
            Shadows.shadowOf(context.mainLooper).idle()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        }
        assertEquals("All 10 direct hardware GPS fixes must be recorded", 10, TrackingManager.trackPoints.value.size)

        // Immediately send a redundant Fused echo (< 5 s since last HW GPS fix): must be skipped to avoid zig-zag
        val echoLoc = makeLocation(49.1211, 16.3311, accuracy = 6.0f).apply {
            time = baseTimeMs + 31_000L
        }
        service.locationCallback.onLocationResult(LocationResult.create(listOf(echoLoc)))
        assertEquals("Fused echo within 5 s of HW GPS fix must be skipped", 10, TrackingManager.trackPoints.value.size)

        // Advance clock past 5 s silence window: Fused fallback fix must now be accepted
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        val fusedFallback = makeLocation(49.1212, 16.3312, accuracy = 6.0f).apply {
            time = baseTimeMs + 37_000L
        }
        service.locationCallback.onLocationResult(LocationResult.create(listOf(fusedFallback)))
        assertEquals("Fused fix after >5 s HW silence must be recorded", 11, TrackingManager.trackPoints.value.size)

        // Wait briefly for the 10-point async checkpoint file to flush on ioExecutor
        val checkpointFile = File(File(context.filesDir, "tracks"), ".active_recovery.gpx")
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline && !checkpointFile.exists()) {
            Thread.sleep(25L)
        }
        assertTrue("10th recorded point must asynchronously write .active_recovery.gpx", checkpointFile.exists())

        controller.destroy()
    }

    @Test
    fun actionFlushNow_andZeroPointStop_handleFreshLastKnownGpsAndEmptyTrackCleanup() {
        val controller = Robolectric.buildService(LocationTrackingService::class.java).create()
        val service = controller.get()

        // 1. Start and immediately stop with 0 recorded points -> must report 0 pts and clean recovery checkpoint
        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START), 0, 1)
        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_STOP), 0, 2)

        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(context.mainLooper).idle()
            if (TrackingManager.lastSavedSummary.value?.contains("0 pts") == true) break
            Thread.sleep(25L)
        }
        assertEquals("Recording stopped (0 pts)", TrackingManager.lastSavedSummary.value)

        // 2. Restart tracking and verify ACTION_FLUSH_NOW ingests fresh (< 15 s) lastKnown GPS_PROVIDER fix
        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START), 0, 3)
        val freshLast = makeLocation(47.8712, 13.8351, altitudeMsl = 610.0)
        @Suppress("DEPRECATION")
        Shadows.shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, freshLast)

        service.onStartCommand(Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_FLUSH_NOW), 0, 4)
        assertEquals(1, TrackingManager.trackPoints.value.size)
        assertEquals(47.8712, TrackingManager.trackPoints.value.first().latitude, 0.0001)

        controller.destroy()
    }
}

