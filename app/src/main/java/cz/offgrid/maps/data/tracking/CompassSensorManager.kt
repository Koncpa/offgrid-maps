package cz.offgrid.maps.data.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Battery-efficient compass (heading / azimuth) sensor manager for Wear OS.
 *
 * Battery optimization strategy:
 * 1. Runs exclusively while the screen is interactive (`start()` in `ON_RESUME`, `stop()` in `ON_PAUSE`).
 *    As soon as the wrist is lowered, the sensor listener is unregistered (0 mW power draw).
 * 2. Applies an angular threshold (1.5 degrees) and a rate limiter (~12.5 Hz) to prevent unnecessary
 *    UI and GPU repaints from micro-tremors.
 */
class CompassSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // Rotation Vector runs on the low-power hardware SensorHub and provides responsive heading updates.
    // Because we unregister in ON_PAUSE, it consumes 0 mW while the screen is off.
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val _azimuthDegrees = MutableStateFlow(0f)
    val azimuthDegrees: StateFlow<Float> = _azimuthDegrees.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastUpdateMillis = 0L

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val type = event?.sensor?.type ?: return
        if (type != Sensor.TYPE_ROTATION_VECTOR && type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) return

        val now = System.currentTimeMillis()
        if (now - lastUpdateMillis < 80L) return // ~12.5 Hz cap for smooth heading rotation

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // When the watch is tilted steeply toward the user's face, remap the coordinate axes
        // to avoid gimbal lock and keep the compass heading aligned with the line of sight.
        val inclinationZ = abs(rotationMatrix[8])
        val activeMatrix = if (inclinationZ < 0.5f) {
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
            )
            remappedMatrix
        } else {
            rotationMatrix
        }

        SensorManager.getOrientation(activeMatrix, orientationAngles)

        val azimuthRad = orientationAngles[0]
        val azimuthDeg = ((Math.toDegrees(azimuthRad.toDouble()).toFloat() + 360f) % 360f)

        // 1.5-degree threshold for smooth arrow rotation on the map
        val diff = abs(azimuthDeg - _azimuthDegrees.value)
        val wrappedDiff = if (diff > 180f) 360f - diff else diff
        if (wrappedDiff >= 1.5f) {
            lastUpdateMillis = now
            _azimuthDegrees.value = azimuthDeg
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
