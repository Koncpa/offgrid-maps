package cz.offgrid.maps.data.tracking

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.LocationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveGpsManagerTest {

    private lateinit var app: Application
    private lateinit var locationManager: LocationManager
    private lateinit var altimeterManager: BarometricAltimeterManager
    private lateinit var gpsManager: LiveGpsManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Advance monotonic clock past 60 s so negative age offsets (e.g. 25 s stale fix) do not clamp to 0
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        app.getSharedPreferences("offgrid_altimeter_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TrackingManager.clearTrack()
        TrackingManager.setTrackingActive(false)
        locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        altimeterManager = BarometricAltimeterManager(app, isTrackingOwner = false)
        gpsManager = LiveGpsManager(app, altimeterManager)
    }

    @After
    fun tearDown() {
        gpsManager.stop()
        TrackingManager.clearTrack()
        TrackingManager.setTrackingActive(false)
    }

    private fun makeLocation(
        lat: Double,
        lon: Double,
        accuracy: Float = 5.0f,
        altitudeMsl: Double = 350.0,
        ageMs: Long = 0L,
        provider: String = LocationManager.GPS_PROVIDER,
        speedMps: Float? = null
    ): Location = Location(provider).apply {
        latitude = lat
        longitude = lon
        this.accuracy = accuracy
        altitude = altitudeMsl
        verticalAccuracyMeters = 4.0f
        time = System.currentTimeMillis() - ageMs
        elapsedRealtimeNanos = (SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L).coerceAtLeast(1L)
        if (speedMps != null) speed = speedMps
    }

    @Test
    fun startAndImmediateFix_withoutLocationPermission_areSafeNoOps() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gpsManager.start()
        assertFalse(gpsManager.isWatchHardwareGpsActive)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
        assertFalse("Fallback timer must not engage HW GPS without permission", gpsManager.isWatchHardwareGpsActive)

        gpsManager.requestImmediateHighAccuracyFix()
        assertFalse(gpsManager.isWatchHardwareGpsActive)
        assertNull(TrackingManager.currentLocation.value)
    }

    @Test
    fun start_withFreshLastKnownHardwareGps_calibratesAltimeterAndUpdatesLocation() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val freshLast = makeLocation(49.1246, 16.3353, accuracy = 8.0f, altitudeMsl = 385.0, ageMs = 2_000L, speedMps = 1.4f)
        @Suppress("DEPRECATION")
        Shadows.shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, freshLast)

        gpsManager.start()

        val cur = TrackingManager.currentLocation.value
        assertNotNull("Fresh GPS_PROVIDER lastKnownLocation must populate currentLocation", cur)
        assertEquals(49.1246, cur!!.latitude, 0.0001)
        assertEquals(16.3353, cur.longitude, 0.0001)
        assertEquals(1.4f, cur.speedMps!!, 0.01f)
        val expectedMsl = BarometricAltimeterManager.extractMslAltitudeMeters(freshLast)!!
        assertEquals("Altimeter must receive EGM96 geoid-corrected MSL altitude", expectedMsl, altimeterManager.altitudeMeters.value!!, 1.0)
    }

    @Test
    fun fallbackTimer_engagesWatchHardwareGpsAfter1500msWithoutStrictFix() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gpsManager.start()
        assertFalse("Idle start should wait 1.5s before engaging watch HW GPS", gpsManager.isWatchHardwareGpsActive)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
        assertTrue("Watch HW GPS must engage after 1.5s fallback without strict fix", gpsManager.isWatchHardwareGpsActive)

        // Direct hardware satellite callback dispatches onto main looper
        val hwFix = makeLocation(49.2000, 16.6000, accuracy = 6.0f, altitudeMsl = 420.0)
        Shadows.shadowOf(locationManager).simulateLocation(hwFix)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(49.2000, TrackingManager.currentLocation.value!!.latitude, 0.0001)
    }

    @Test
    fun coarseFusedFix_engagesHardwareGpsAndRecentSatelliteLockDiscardsCoarseFusedNoise() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gpsManager.start()

        // Coarse fused estimate (> 10 m) must immediately engage hardware GPS
        val coarseInitial = makeLocation(49.1000, 16.3000, accuracy = 24.0f, provider = "fused")
        gpsManager.fusedLocationCallback.onLocationResult(LocationResult.create(listOf(coarseInitial)))
        assertTrue("Coarse >10m fused fix must immediately engage watch HW GPS", gpsManager.isWatchHardwareGpsActive)
        assertEquals(49.1000, TrackingManager.currentLocation.value!!.latitude, 0.0001)

        // Watch hardware satellite lock arrives on main looper
        val satelliteFix = makeLocation(49.1500, 16.3500, accuracy = 5.0f, provider = LocationManager.GPS_PROVIDER)
        Shadows.shadowOf(locationManager).simulateLocation(satelliteFix)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(49.1500, TrackingManager.currentLocation.value!!.latitude, 0.0001)

        // Subsequent coarse fused estimate within 10 s must be discarded so it does not corrupt satellite lock
        val noisyFused = makeLocation(49.9999, 16.9999, accuracy = 35.0f, provider = "fused")
        gpsManager.fusedLocationCallback.onLocationResult(LocationResult.create(listOf(noisyFused)))
        assertEquals(
            "Coarse fused noise must be ignored while watch HW GPS has recent satellite lock",
            49.1500,
            TrackingManager.currentLocation.value!!.latitude,
            0.0001
        )
    }

    @Test
    fun sixConsecutiveAccurateFusedFixes_disablesHardwareGpsWhenNotTracking() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gpsManager.start()

        // Engage hardware GPS via 1.5s fallback timer
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
        assertTrue(gpsManager.isWatchHardwareGpsActive)

        // Deliver 6 consecutive open-sky accurate fused fixes (<= 10 m) with no recent HW GPS echo
        repeat(5) { idx ->
            val fix = makeLocation(49.1200 + idx * 0.0001, 16.3300, accuracy = 6.0f, provider = "fused")
            gpsManager.fusedLocationCallback.onLocationResult(LocationResult.create(listOf(fix)))
            assertTrue("Should stay active until 6th consecutive strict fused fix", gpsManager.isWatchHardwareGpsActive)
        }
        val sixthFix = makeLocation(49.1206, 16.3300, accuracy = 6.0f, provider = "fused")
        gpsManager.fusedLocationCallback.onLocationResult(LocationResult.create(listOf(sixthFix)))
        assertFalse("6 consecutive <=10m phone fused fixes should turn off watch HW GPS", gpsManager.isWatchHardwareGpsActive)
    }

    @Test
    fun staleNonSatelliteFix_isIgnoredByProcessLocation() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gpsManager.start()

        val staleFused = makeLocation(49.5555, 16.5555, accuracy = 5.0f, ageMs = 25_000L, provider = "fused")
        gpsManager.fusedLocationCallback.onLocationResult(LocationResult.create(listOf(staleFused)))
        assertNull("Non-satellite fix older than 20 s must be discarded", TrackingManager.currentLocation.value)
    }

    @Test
    fun activeTracking_engagesHardwareGpsAtZeroMsProtectsLockAndDelegatesFlushToService() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        TrackingManager.setTrackingActive(true)

        gpsManager.start()
        assertTrue("Active trek must engage watch HW GPS immediately at 0 ms", gpsManager.isWatchHardwareGpsActive)

        // 6 accurate fused fixes during an active trek must NEVER turn off HW GPS or duplicate points
        repeat(6) { idx ->
            val fix = makeLocation(49.1200 + idx * 0.0001, 16.3300, accuracy = 4.0f, provider = "fused")
            gpsManager.fusedLocationCallback.onLocationResult(LocationResult.create(listOf(fix)))
        }
        assertTrue("Active trek must keep watch HW GPS active", gpsManager.isWatchHardwareGpsActive)
        assertNull("LiveGpsManager must yield point ingestion to LocationTrackingService during active trek", TrackingManager.currentLocation.value)

        // Tapping GPS button during active trek delegates to LocationTrackingService.flushNow()
        gpsManager.requestImmediateHighAccuracyFix()
        val startedIntent = Shadows.shadowOf(app).nextStartedService
        assertNotNull("Expected ACTION_FLUSH_NOW service intent during active trek", startedIntent)
        assertEquals(LocationTrackingService.ACTION_FLUSH_NOW, startedIntent.action)
    }

    @Test
    fun requestImmediateHighAccuracyFix_whenIdle_engagesHardwareGpsAndReadsFreshLastKnownGps() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gpsManager.start()
        assertFalse(gpsManager.isWatchHardwareGpsActive)

        val freshHw = makeLocation(47.3920, 13.6890, accuracy = 7.0f, ageMs = 3_000L)
        @Suppress("DEPRECATION")
        Shadows.shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, freshHw)

        gpsManager.requestImmediateHighAccuracyFix()

        assertTrue("GPS button must immediately engage watch HW GPS", gpsManager.isWatchHardwareGpsActive)
        assertEquals(47.3920, TrackingManager.currentLocation.value!!.latitude, 0.0001)
    }

    @Test
    fun stop_disablesHardwareGpsAndCancelsFallbackTimer() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gpsManager.start()
        gpsManager.stop()

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
        assertFalse("Stopped LiveGpsManager must never engage HW GPS when delayed runnable time passes", gpsManager.isWatchHardwareGpsActive)
    }
}
