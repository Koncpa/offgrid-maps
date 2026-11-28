package cz.offgrid.maps.data.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompassSensorManagerTest {

    @Test
    fun startStopAndRotationEvents_computeAzimuthApplyDeadbandAndRemapSteepTilt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val shadowSm = Shadows.shadowOf(sensorManager)
        val rotationSensor = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR)
        shadowSm.addSensor(rotationSensor)

        val compass = CompassSensorManager(context)
        compass.start()
        assertTrue("Compass listener must register on start()", shadowSm.hasListener(compass))

        // 1. Flat watch rotated ~90 degrees around Z axis (sin(45 deg) = 0.7071)
        val half90 = Math.toRadians(45.0)
        val flatEastEvt = SensorEventBuilder.newBuilder()
            .setSensor(rotationSensor)
            .setValues(floatArrayOf(0f, 0f, (-sin(half90)).toFloat(), cos(half90).toFloat()))
            .build()
        compass.onSensorChanged(flatEastEvt)
        val az1 = compass.azimuthDegrees.value
        assertTrue("Expected non-zero azimuth after 90 deg rotation, was $az1", az1 in 80f..100f)

        // 2. Immediate follow-up (< 80 ms) must be rate-limited
        val immediateEvt = SensorEventBuilder.newBuilder()
            .setSensor(rotationSensor)
            .setValues(floatArrayOf(0f, 0f, 1f, 0f))
            .build()
        compass.onSensorChanged(immediateEvt)
        assertEquals("Rate limiter must ignore updates faster than 80 ms", az1, compass.azimuthDegrees.value, 0.01f)

        // 3. After > 80 ms, micro-tremor (< 1.5 deg) must be ignored by deadband
        Thread.sleep(90L)
        val half90Point5 = Math.toRadians(45.25)
        val tremorEvt = SensorEventBuilder.newBuilder()
            .setSensor(rotationSensor)
            .setValues(floatArrayOf(0f, 0f, (-sin(half90Point5)).toFloat(), cos(half90Point5).toFloat()))
            .build()
        compass.onSensorChanged(tremorEvt)
        assertEquals("1.5 deg deadband must filter micro-tremor", az1, compass.azimuthDegrees.value, 0.01f)

        // 4. Steep tilt toward user's face (90 deg around X -> inclinationZ < 0.5f triggers axis remap)
        Thread.sleep(90L)
        val tiltEvt = SensorEventBuilder.newBuilder()
            .setSensor(rotationSensor)
            .setValues(floatArrayOf(sin(half90).toFloat(), 0f, 0f, cos(half90).toFloat()))
            .build()
        compass.onSensorChanged(tiltEvt)
        assertTrue("Remapped azimuth must stay normalized in [0, 360)", compass.azimuthDegrees.value in 0f..360f)

        // 5. Unrelated sensor type must be ignored safely
        val pressureSensor = ShadowSensor.newInstance(Sensor.TYPE_PRESSURE)
        compass.onSensorChanged(
            SensorEventBuilder.newBuilder().setSensor(pressureSensor).setValues(floatArrayOf(1013f)).build()
        )

        compass.stop()
        assertFalse("Compass listener must unregister on stop()", shadowSm.hasListener(compass))
    }
}
