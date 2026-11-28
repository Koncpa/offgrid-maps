package cz.offgrid.maps.data.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarometricAltimeterManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("offgrid_altimeter_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TrackingManager.clearTrack()
    }

    @Test
    fun estimateEgm2008GeoidUndulation_matchesCentralEuropeAndReturnsZeroOutsideBounds() {
        val brnoUndulation = BarometricAltimeterManager.estimateEgm2008GeoidUndulationMeters(49.1951, 16.6068)
        assertTrue("Brno geoid undulation should lie around 43-46 m, was $brnoUndulation", brnoUndulation in 43.0..46.0)

        val alpsUndulation = BarometricAltimeterManager.estimateEgm2008GeoidUndulationMeters(47.87, 13.83)
        assertTrue("Austrian Alps geoid undulation should lie around 44-48 m, was $alpsUndulation", alpsUndulation in 44.0..48.5)

        // Outside Central Europe bounding box -> must cleanly return 0.0 (no wrong European offset applied in US/NZ)
        assertEquals(0.0, BarometricAltimeterManager.estimateEgm2008GeoidUndulationMeters(37.7749, -122.4194), 0.001)
        assertEquals(0.0, BarometricAltimeterManager.estimateEgm2008GeoidUndulationMeters(-41.2865, 174.7762), 0.001)
    }

    @Test
    fun extractMslAltitudeMeters_convertsWgs84LocationToOrthometricMsl() {
        val noAlt = Location("gps").apply {
            latitude = 49.1246
            longitude = 16.3353
        }
        assertNull(BarometricAltimeterManager.extractMslAltitudeMeters(noAlt))

        val withWgs84 = Location("gps").apply {
            latitude = 49.1246
            longitude = 16.3353
            altitude = 412.55
        }
        val msl = BarometricAltimeterManager.extractMslAltitudeMeters(withWgs84)
        assertNotNull(msl)
        assertEquals(368.0, msl!!, 0.5)
    }

    @Test
    fun onGpsAltitudeReceived_withoutBarometer_smoothsRejectsCoarseVerticalAndPersistsAcrossInstances() {
        val manager = BarometricAltimeterManager(context, isTrackingOwner = true)
        assertEquals(false, manager.hasHardwareBarometer)

        // 1. First valid GPS altitude initializes immediately without smoothing lag
        manager.onGpsAltitudeReceived(mslAltitudeMeters = 500.0, gpsAccuracyMeters = 8.0f, verticalAccuracyMeters = 6.0f)
        assertEquals(500.0, manager.altitudeMeters.value!!, 0.01)
        assertEquals(500.0, TrackingManager.currentAltitudeMeters.value!!, 0.01)

        // 2. Vertically unreliable 2D fix (> 25 m error) must be ignored once altitude is already initialized
        manager.onGpsAltitudeReceived(mslAltitudeMeters = 800.0, gpsAccuracyMeters = 10.0f, verticalAccuracyMeters = 40.0f)
        assertEquals(500.0, manager.altitudeMeters.value!!, 0.01)

        // 3. Subsequent valid fix uses 0.7/0.3 exponential smoothing (0.7*500 + 0.3*520 = 506.0)
        manager.onGpsAltitudeReceived(mslAltitudeMeters = 520.0, gpsAccuracyMeters = 6.0f, verticalAccuracyMeters = 5.0f)
        assertEquals(506.0, manager.altitudeMeters.value!!, 0.05)

        // 4. Fresh manager instance restores cached MSL altitude from SharedPreferences
        manager.stop()
        val restored = BarometricAltimeterManager(context, isTrackingOwner = true)
        assertNotNull(restored.altitudeMeters.value)
        assertEquals(506.0, restored.altitudeMeters.value!!, 0.5)
    }

    @Test
    fun hardwareBarometer_calibratesFromPendingGpsTracksClimbAndPersistsOffset() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val shadowSm = Shadows.shadowOf(sensorManager)
        val pressureSensor = ShadowSensor.newInstance(Sensor.TYPE_PRESSURE)
        shadowSm.addSensor(pressureSensor)

        val manager = BarometricAltimeterManager(context, isTrackingOwner = true)
        assertTrue(manager.hasHardwareBarometer)

        manager.start()
        assertTrue(shadowSm.hasListener(manager))

        // 1. GPS fix arrives before first pressure sample -> queues pending calibration and displays MSL immediately
        manager.onGpsAltitudeReceived(mslAltitudeMeters = 620.0, gpsAccuracyMeters = 8.0f, verticalAccuracyMeters = 10.0f)
        assertEquals(620.0, manager.altitudeMeters.value!!, 0.01)

        // 2. Invalid pressure <= 0 hPa must be ignored safely
        val invalidEvt = SensorEventBuilder.newBuilder()
            .setSensor(pressureSensor)
            .setValues(floatArrayOf(0f))
            .build()
        manager.onSensorChanged(invalidEvt)
        assertEquals(620.0, manager.altitudeMeters.value!!, 0.01)

        // 3. First valid pressure reading (~950 hPa) consumes pending GPS calibration -> calibrated altitude stays 620 m
        val firstEvt = SensorEventBuilder.newBuilder()
            .setSensor(pressureSensor)
            .setValues(floatArrayOf(950.0f))
            .build()
        manager.onSensorChanged(firstEvt)
        assertEquals(620.0, manager.altitudeMeters.value!!, 0.5)

        // 4. Immediate follow-up sample (< 1000 ms) must be rate-limited
        val rapidEvt = SensorEventBuilder.newBuilder()
            .setSensor(pressureSensor)
            .setValues(floatArrayOf(900.0f))
            .build()
        manager.onSensorChanged(rapidEvt)
        assertEquals(620.0, manager.altitudeMeters.value!!, 0.5)

        // 5. Higher-accuracy GPS fix recalibrates offset without inflating ascent during active tracking
        TrackingManager.setTrackingActive(true)
        manager.onGpsAltitudeReceived(mslAltitudeMeters = 635.0, gpsAccuracyMeters = 4.0f, verticalAccuracyMeters = 3.0f)
        assertEquals(635.0, manager.altitudeMeters.value!!, 0.5)
        assertEquals("GPS recalibration must NOT inflate ascent", 0.0, TrackingManager.totalAscentMeters.value, 0.01)

        // 6. After 1 s rate-limit window, pressure drop (~5 hPa = ~44 m climb; 0.25 smoothed = ~11 m > 3 m hysteresis) accumulates barometric ascent
        Thread.sleep(1020L)
        val climbEvt = SensorEventBuilder.newBuilder()
            .setSensor(pressureSensor)
            .setValues(floatArrayOf(945.0f))
            .build()
        manager.onSensorChanged(climbEvt)
        assertTrue("Expected barometric climb > 5 m, was ${TrackingManager.totalAscentMeters.value}", TrackingManager.totalAscentMeters.value > 5.0)

        manager.stop()
        assertFalse(shadowSm.hasListener(manager))

        // 7. New instance restores cached barometric offset cleanly from SharedPreferences
        val restored = BarometricAltimeterManager(context, isTrackingOwner = true)
        assertNotNull(restored.altitudeMeters.value)
        assertTrue(restored.altitudeMeters.value!! > 630.0)
    }
}
