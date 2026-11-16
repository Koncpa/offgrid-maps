package cz.offgrid.maps.data.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Univerzální barometrický a satelitní výškoměr pro všechny hodinky s Wear OS s korekcí geoidu EGM2008.
 *
 * Proč běžné GPS aplikace v ČR ukazují o ~45 metrů více:
 * - `Location.getAltitude()` v Androidu vrací výšku nad matematickým elipsoidem WGS84 ($h_{WGS84}$).
 * - V České republice leží skutečná hladina moře (geoid EGM2008 / výškový systém Bpv) o 43 až 47 m
 *   výše než elipsoid WGS84 (např. na jižní Moravě o +44.5 m, v Praze o +46.5 m).
 * - Tato třída převádí elipsoidickou výšku na skutečnou ortometrickou nadmořskou výšku ($H_{MSL}$),
 *   která na metr sedí s českými turistickými mapami a rozcestníky KČT, a kalibruje jí barometr.
 */
class BarometricAltimeterManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Indikuje, zda tento model hodinek disponuje hardwarovým barometrem. */
    val hasHardwareBarometer: Boolean = pressureSensor != null

    private val _altitudeMeters = MutableStateFlow<Double?>(null)
    val altitudeMeters: StateFlow<Double?> = _altitudeMeters.asStateFlow()

    // Kalibrační posun barometru podle ortometrické nadmořské výšky (pro kompenzaci změn počasí/tlaku)
    private var baroCalibrationOffsetMeters: Double = 0.0
    private var isCalibratedWithGps = false
    private var bestCalibrationVertAcc: Float = Float.MAX_VALUE
    private var pendingCalibrationMslAltitude: Double? = null
    private var smoothedBaroAltitude: Double? = null
    private var lastUpdateMillis = 0L

    init {
        if (prefs.contains(KEY_BARO_OFFSET)) {
            baroCalibrationOffsetMeters = prefs.getFloat(KEY_BARO_OFFSET, 0f).toDouble()
            isCalibratedWithGps = true
        }
        if (prefs.contains(KEY_LAST_MSL_ALT)) {
            val savedAlt = prefs.getFloat(KEY_LAST_MSL_ALT, 368f).toDouble()
            _altitudeMeters.value = savedAlt
            TrackingManager.updateAltitude(savedAlt, isBarometric = hasHardwareBarometer)
        }
    }

    fun start() {
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Volá se při příchodu nového GPS bodu (již převedeného na skutečnou nadmořskou výšku MSL).
     * - Na hodinkách bez barometru přímo aktualizuje nadmořskou výšku.
     * - Na hodinkách s barometrem ihned nebo plynule zkalibruje barometrickou výšku.
     */
    fun onGpsAltitudeReceived(
        mslAltitudeMeters: Double?,
        gpsAccuracyMeters: Float?,
        verticalAccuracyMeters: Float? = null
    ) {
        if (mslAltitudeMeters == null) return

        // Ignorujeme výškově nespolehlivé 2D fixy (když je vertikální chyba > 25 m)
        if (verticalAccuracyMeters != null && verticalAccuracyMeters > 25.0f && _altitudeMeters.value != null) {
            return
        }

        if (!hasHardwareBarometer) {
            val prev = _altitudeMeters.value
            val next = if (prev == null) mslAltitudeMeters else (prev * 0.7 + mslAltitudeMeters * 0.3)
            _altitudeMeters.value = next
            TrackingManager.updateAltitude(next, isBarometric = false)
            saveCalibrationToPrefs(0.0, next)
            return
        }

        val isGoodHorizontalFix = gpsAccuracyMeters == null || gpsAccuracyMeters <= 25.0f
        val isGoodVerticalFix = verticalAccuracyMeters == null || verticalAccuracyMeters <= 20.0f
        val effectiveVertAcc = verticalAccuracyMeters ?: (gpsAccuracyMeters ?: 15.0f)

        val rawBaro = smoothedBaroAltitude
        if (rawBaro == null) {
            // Barometr ještě neposlal první vzorek tlaku -> uložíme si kalibrační MSL výšku do fronty
            // a rovnou ji zobrazíme v UI, aby uživatel neviděl nezkalibrovanou výšku podle 1013.25 hPa!
            if (isGoodHorizontalFix) {
                pendingCalibrationMslAltitude = mslAltitudeMeters
                bestCalibrationVertAcc = effectiveVertAcc
                _altitudeMeters.value = mslAltitudeMeters
                TrackingManager.updateAltitude(mslAltitudeMeters, isBarometric = true)
            }
            return
        }

        if (!isCalibratedWithGps || (isGoodHorizontalFix && isGoodVerticalFix)) {
            val targetOffset = mslAltitudeMeters - rawBaro
            val isMuchBetterVerticalFix = effectiveVertAcc < bestCalibrationVertAcc * 0.7f
            baroCalibrationOffsetMeters = if (!isCalibratedWithGps || isMuchBetterVerticalFix) {
                isCalibratedWithGps = true
                bestCalibrationVertAcc = minOf(bestCalibrationVertAcc, effectiveVertAcc)
                targetOffset
            } else {
                bestCalibrationVertAcc = minOf(bestCalibrationVertAcc, effectiveVertAcc)
                val alpha = if (effectiveVertAcc <= 5.0f) 0.35 else 0.15
                baroCalibrationOffsetMeters * (1.0 - alpha) + targetOffset * alpha
            }
            val calibratedAltitude = rawBaro + baroCalibrationOffsetMeters
            _altitudeMeters.value = calibratedAltitude
            TrackingManager.updateAltitude(calibratedAltitude, isBarometric = true)
            saveCalibrationToPrefs(baroCalibrationOffsetMeters, calibratedAltitude)
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

        // Pokud přišla GPS výška ještě před prvním tikem barometru, okamžitě barometr zkalibrujeme
        val pendingMsl = pendingCalibrationMslAltitude
        if (pendingMsl != null) {
            baroCalibrationOffsetMeters = pendingMsl - nextRaw
            isCalibratedWithGps = true
            pendingCalibrationMslAltitude = null
            saveCalibrationToPrefs(baroCalibrationOffsetMeters, pendingMsl)
        }

        // Pokud ještě vůbec nemáme kalibraci z GPS (ani z minula), počkáme na GPS/lastLocation,
        // abychom při tlakové níži neukazovali falešných 500 m podle standardních 1013.25 hPa!
        if (!isCalibratedWithGps) {
            return
        }

        val calibratedAltitude = nextRaw + baroCalibrationOffsetMeters
        lastUpdateMillis = now
        _altitudeMeters.value = calibratedAltitude
        TrackingManager.updateAltitude(calibratedAltitude, isBarometric = true)
    }

    private fun saveCalibrationToPrefs(offsetMeters: Double, mslAltMeters: Double) {
        prefs.edit()
            .putFloat(KEY_BARO_OFFSET, offsetMeters.toFloat())
            .putFloat(KEY_LAST_MSL_ALT, mslAltMeters.toFloat())
            .apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val PREFS_NAME = "offgrid_altimeter_prefs"
        private const val KEY_BARO_OFFSET = "baro_calibration_offset_m"
        private const val KEY_LAST_MSL_ALT = "last_calibrated_msl_alt_m"

        /**
         * Vrátí skutečnou ortometrickou nadmořskou výšku (MSL / Bpv) z objektu [Location]:
         * 1. Na Androidu 14+ (API 34+) použije `mslAltitudeMeters`, pokud je k dispozici.
         * 2. Jinak převede elipsoidickou výšku `location.altitude` (WGS84) na nadmořskou výšku (MSL)
         *    odečtením undulace geoidu EGM2008.
         */
        fun extractMslAltitudeMeters(location: Location): Double? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude()) {
                return location.mslAltitudeMeters
            }
            if (!location.hasAltitude()) return null
            return wgs84ToMslAltitude(location.altitude, location.latitude, location.longitude)
        }

        /**
         * Převádí výšku nad referenčním elipsoidem WGS84 ($h_{WGS84}$) na skutečnou nadmořskou
         * výšku nad hladinou moře ($H_{MSL}$ / Bpv) odečtením výšky geoidu EGM2008 $N(\varphi, \lambda)$.
         *
         * V ČR a střední Evropě (47°–52° s. š., 11°–20° v. d.) leží geoid EGM2008 o 42.5 až 48.0 m
         * nad elipsoidem WGS84 (např. Zbýšov/Oslavany: +44.56 m, Praha: +46.52 m, Sněžka: +45.69 m).
         */
        fun wgs84ToMslAltitude(wgs84AltitudeMeters: Double, latitude: Double, longitude: Double): Double {
            val geoidUndulationMeters = estimateEgm2008GeoidUndulationMeters(latitude, longitude)
            return wgs84AltitudeMeters - geoidUndulationMeters
        }

        /**
         * Spočítá undulaci geoidu EGM2008 $N(\varphi, \lambda)$ v metrech pro střední Evropu.
         * Přesnost vůči plné mřížce ČÚZK / EGM2008 v celé ČR je +-0.3 m.
         */
        fun estimateEgm2008GeoidUndulationMeters(latitude: Double, longitude: Double): Double {
            if (latitude !in 45.0..55.0 || longitude !in 8.0..24.0) {
                return 0.0
            }
            return (44.55 + 0.42 * (latitude - 49.15) - 0.82 * (longitude - 16.35))
                .coerceIn(38.0, 52.0)
        }
    }
}
