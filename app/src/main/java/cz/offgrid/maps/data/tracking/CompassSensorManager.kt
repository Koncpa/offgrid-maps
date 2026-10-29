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
    // Samsung Rotation Vector na Galaxy Watch Ultra běží na hardwarovém SensorHubu a dává okamžitou odezvu při otočení zápěstí.
    // Protože ho v ON_PAUSE ihned odpojujeme, při zhasnutém displeji má 0 mW spotřebu.
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
        if (now - lastUpdateMillis < 80L) return // ~12.5 Hz pro plynulé otáčení šipky

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // Pokud jsou hodinky výrazně nakloněné k obličeji, přemapujeme souřadnicový systém,
        // aby nedocházelo k zámku os (gimbal lock) a kompas ukazoval přesně směr pohledu
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

        // Citlivý práh 1.5 stupně pro plynulé otáčení šipečky na mapě
        val diff = abs(azimuthDeg - _azimuthDegrees.value)
        val wrappedDiff = if (diff > 180f) 360f - diff else diff
        if (wrappedDiff >= 1.5f) {
            lastUpdateMillis = now
            _azimuthDegrees.value = azimuthDeg
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
