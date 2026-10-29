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
 * Bateriově šetrný správce kompasu (směru pohledu / azimutu) pro hodinky.
 *
 * Jak šetří baterii:
 * 1. Běží výhradně v době, kdy je displej rozsvícený (start() v ON_RESUME, stop() v ON_PAUSE).
 *    Jakmile svěsíte ruku, senzor se okamžitě odpojí (0 mW spotřeba).
 * 2. Používá práh změny úhlu (min. 6 stupňů) a časový filtr (max 4x za sekundu),
 *    aby nezahlcoval UI a GPU zbytečným překreslováním při jemném chvění ruky.
 */
class CompassSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // Geomagnetic Rotation Vector využívá jen magnetometr + akcelerometr a nezapíná žravý MEMS gyroskop (~80 % úspora proudu)
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _azimuthDegrees = MutableStateFlow(0f)
    val azimuthDegrees: StateFlow<Float> = _azimuthDegrees.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastUpdateMillis = 0L

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return

        val now = System.currentTimeMillis()
        if (now - lastUpdateMillis < 250L) return // Max 4 aktualizace za sekundu

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        val azimuthRad = orientationAngles[0]
        val azimuthDeg = ((Math.toDegrees(azimuthRad.toDouble()).toFloat() + 360f) % 360f)

        // Aktualizujeme pouze při otočení o alespoň 6 stupňů (odfiltruje třes ruky)
        val diff = abs(azimuthDeg - _azimuthDegrees.value)
        val wrappedDiff = if (diff > 180f) 360f - diff else diff
        if (wrappedDiff >= 6f) {
            lastUpdateMillis = now
            _azimuthDegrees.value = azimuthDeg
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
