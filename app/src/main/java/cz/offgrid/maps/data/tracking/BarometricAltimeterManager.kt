package cz.offgrid.maps.data.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Univerzální výškoměr pro všechny hodinky s Wear OS.
 *
 * Architektura (Hardware Capability Fallback):
 * 1. Pokud mají hodinky hardwarový barometr (`Sensor.TYPE_PRESSURE`, např. Galaxy Watch, Pixel Watch, TicWatch),
 *    měří nadmořskou výšku z atmosférického tlaku s přesností na ~0.5 metru a automaticky se kalibruje
 *    podle kvalitního GPS fixu.
 * 2. Pokud hodinky barometr nemají, automaticky a bezchybně přepne na filtrovanou nadmořskou výšku z GPS,
 *    takže aplikace funguje spolehlivě na libovolném modelu s Wear OS.
 */
class BarometricAltimeterManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    /** Indikuje, zda tento model hodinek disponuje hardwarovým barometrem. */
    val hasHardwareBarometer: Boolean = pressureSensor != null

    private val _altitudeMeters = MutableStateFlow<Double?>(null)
    val altitudeMeters: StateFlow<Double?> = _altitudeMeters.asStateFlow()

    // Kalibrační posun barometru podle GPS nadmořské výšky (pro kompenzaci změn počasí/tlaku)
    private var baroCalibrationOffsetMeters: Double = 0.0
    private var isCalibratedWithGps = false
    private var smoothedBaroAltitude: Double? = null
    private var lastUpdateMillis = 0L

    fun start() {
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Volá se při příchodu nového GPS bodu.
     * - Na hodinkách bez barometru přímo aktualizuje nadmořskou výšku.
     * - Na hodinkách s barometrem jemně zkalibruje barometrickou výšku při dobrém GPS signálu.
     */
    fun onGpsAltitudeReceived(gpsAltitudeMeters: Double?, gpsAccuracyMeters: Float?) {
        if (gpsAltitudeMeters == null) return

        if (!hasHardwareBarometer) {
            // Fallback pro Wear OS hodinky bez barometru: exponenciální vyhlazení GPS výšky
            val prev = _altitudeMeters.value
            val next = if (prev == null) gpsAltitudeMeters else (prev * 0.7 + gpsAltitudeMeters * 0.3)
            _altitudeMeters.value = next
            TrackingManager.updateAltitude(next, isBarometric = false)
            return
        }

        // Pokud máme barometr a GPS má slušnou přesnost, zkalibrujeme posun vůči reálné výšce
        val rawBaro = smoothedBaroAltitude ?: return
        if (!isCalibratedWithGps || (gpsAccuracyMeters != null && gpsAccuracyMeters <= 15f)) {
            val targetOffset = gpsAltitudeMeters - rawBaro
            baroCalibrationOffsetMeters = if (!isCalibratedWithGps) {
                isCalibratedWithGps = true
                targetOffset
            } else {
                baroCalibrationOffsetMeters * 0.9 + targetOffset * 0.1
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_PRESSURE) return

        val now = System.currentTimeMillis()
        if (now - lastUpdateMillis < 1000L) return // Stačí 1 vzorek za sekundu (extrémní úspora CPU)

        val pressureHpa = event.values[0]
        if (pressureHpa <= 0f) return

        val rawAltitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
            pressureHpa
        ).toDouble()

        val prev = smoothedBaroAltitude
        val nextRaw = if (prev == null) rawAltitude else (prev * 0.75 + rawAltitude * 0.25)
        smoothedBaroAltitude = nextRaw

        val calibratedAltitude = nextRaw + baroCalibrationOffsetMeters
        lastUpdateMillis = now
        _altitudeMeters.value = calibratedAltitude
        TrackingManager.updateAltitude(calibratedAltitude, isBarometric = true)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
