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
 * Universal barometric and satellite altimeter for Wear OS watches with EGM2008 geoid correction.
 *
 * Why standard GPS apps in Central Europe read ~45 meters too high:
 * - `Location.getAltitude()` in Android returns height above the mathematical WGS84 reference ellipsoid ($h_{WGS84}$).
 * - In Central Europe (Czechia, Slovakia, Austria), true mean sea level (the EGM2008 geoid) lies 43 to 47 m
 *   above the WGS84 ellipsoid (e.g. +44.56 m in South Moravia, +46.5 m in Prague).
 * - This class converts ellipsoidal height into true orthometric elevation above mean sea level ($H_{MSL}$),
 *   matching topographic contour lines and trailhead signs to the meter, and uses it to calibrate the barometer.
 */
class BarometricAltimeterManager(
    context: Context,
    private val isTrackingOwner: Boolean = false
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Indicates whether this watch model features a hardware barometer sensor. */
    val hasHardwareBarometer: Boolean = pressureSensor != null

    private val _altitudeMeters = MutableStateFlow<Double?>(null)
    val altitudeMeters: StateFlow<Double?> = _altitudeMeters.asStateFlow()

    // Barometer calibration offset relative to orthometric MSL altitude (compensates for weather pressure shifts)
    private var baroCalibrationOffsetMeters: Double = 0.0
    private var isCalibratedWithGps = false
    private var bestCalibrationVertAcc: Float = Float.MAX_VALUE
    private var pendingCalibrationMslAltitude: Double? = null
    private var smoothedBaroAltitude: Double? = null
    private var lastUpdateMillis = 0L
    private var lastCalibrationTimeMillis = 0L

    init {
        loadCalibrationFromPrefs()
        if (prefs.contains(KEY_LAST_MSL_ALT)) {
            val savedAlt = prefs.getFloat(KEY_LAST_MSL_ALT, 368f).toDouble()
            _altitudeMeters.value = savedAlt
            if (canPublishToTrackingManager() && TrackingManager.currentAltitudeMeters.value == null) {
                TrackingManager.applyAltitudeCalibration(savedAlt)
            }
        }
    }

    private fun canPublishToTrackingManager(): Boolean {
        return isTrackingOwner || !TrackingManager.isTracking.value
    }

    private fun loadCalibrationFromPrefs() {
        val savedTime = prefs.getLong(KEY_CALIBRATION_TIME_MS, 0L)
        if (prefs.contains(KEY_BARO_OFFSET) && savedTime >= lastCalibrationTimeMillis) {
            baroCalibrationOffsetMeters = prefs.getFloat(KEY_BARO_OFFSET, 0f).toDouble()
            isCalibratedWithGps = true
            lastCalibrationTimeMillis = savedTime
            // Allow the first good GPS fix in a new session to replace an older cached offset
            if (System.currentTimeMillis() - savedTime > 120_000L) {
                bestCalibrationVertAcc = Float.MAX_VALUE
            }
        }
    }

    fun start() {
        loadCalibrationFromPrefs()
        smoothedBaroAltitude = null
        lastUpdateMillis = 0L
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        smoothedBaroAltitude = null
        pendingCalibrationMslAltitude = null
        lastUpdateMillis = 0L
    }

    /**
     * Called when a new GPS location arrives (already converted to true MSL elevation).
     * - On watches without a barometer, directly updates smoothed altitude.
     * - On watches with a barometer, calibrates the barometric MSL offset cleanly without
     *   injecting GPS vertical jitter into accumulated ascent.
     */
    fun onGpsAltitudeReceived(
        mslAltitudeMeters: Double?,
        gpsAccuracyMeters: Float?,
        verticalAccuracyMeters: Float? = null
    ) {
        if (mslAltitudeMeters == null) return

        // Ignore vertically unreliable 2D fixes (when vertical accuracy error > 25 m)
        if (verticalAccuracyMeters != null && verticalAccuracyMeters > 25.0f && _altitudeMeters.value != null) {
            return
        }

        if (!hasHardwareBarometer) {
            val prev = _altitudeMeters.value
            val next = if (prev == null) mslAltitudeMeters else (prev * 0.7 + mslAltitudeMeters * 0.3)
            _altitudeMeters.value = next
            if (canPublishToTrackingManager()) {
                if (prev == null) {
                    TrackingManager.applyAltitudeCalibration(next)
                } else {
                    TrackingManager.updateAltitude(next, isBarometric = false)
                }
            }
            saveCalibrationToPrefs(0.0, next)
            return
        }

        val isGoodHorizontalFix = gpsAccuracyMeters == null || gpsAccuracyMeters <= 25.0f
        val isGoodVerticalFix = verticalAccuracyMeters == null || verticalAccuracyMeters <= 20.0f
        val effectiveVertAcc = verticalAccuracyMeters ?: (gpsAccuracyMeters ?: 15.0f)

        val rawBaro = smoothedBaroAltitude
        if (rawBaro == null) {
            // Barometer has not emitted its first pressure sample yet -> queue the MSL calibration altitude
            // and display it immediately so the user never sees an uncalibrated 1013.25 hPa reading!
            if (isGoodHorizontalFix) {
                pendingCalibrationMslAltitude = mslAltitudeMeters
                bestCalibrationVertAcc = effectiveVertAcc
                _altitudeMeters.value = mslAltitudeMeters
                if (canPublishToTrackingManager()) {
                    TrackingManager.applyAltitudeCalibration(mslAltitudeMeters)
                }
            }
            return
        }

        if (!isCalibratedWithGps || (isGoodHorizontalFix && isGoodVerticalFix)) {
            val prevOffset = baroCalibrationOffsetMeters
            val wasCalibrated = isCalibratedWithGps
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
            val shiftMeters = if (wasCalibrated) (baroCalibrationOffsetMeters - prevOffset) else null
            _altitudeMeters.value = calibratedAltitude
            if (canPublishToTrackingManager()) {
                TrackingManager.applyAltitudeCalibration(calibratedAltitude, shiftMeters)
            }
            saveCalibrationToPrefs(baroCalibrationOffsetMeters, calibratedAltitude)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_PRESSURE) return

        val now = System.currentTimeMillis()
        if (now - lastUpdateMillis < 1000L) return // 1 sample per second is plenty (minimal CPU wakeups)

        val pressureHpa = event.values[0]
        if (pressureHpa <= 0f) return

        val rawAltitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
            pressureHpa
        ).toDouble()

        val prev = smoothedBaroAltitude
        val nextRaw = if (prev == null) rawAltitude else (prev * 0.75 + rawAltitude * 0.25)
        smoothedBaroAltitude = nextRaw

        // If a GPS altitude arrived before the first barometer tick, calibrate immediately
        var justConsumedPendingCalibration = false
        val pendingMsl = pendingCalibrationMslAltitude
        if (pendingMsl != null) {
            baroCalibrationOffsetMeters = pendingMsl - nextRaw
            isCalibratedWithGps = true
            pendingCalibrationMslAltitude = null
            justConsumedPendingCalibration = true
            saveCalibrationToPrefs(baroCalibrationOffsetMeters, pendingMsl)
        }

        // Wait until calibrated before publishing raw barometric pressure altitude
        if (!isCalibratedWithGps) {
            return
        }

        val calibratedAltitude = nextRaw + baroCalibrationOffsetMeters
        lastUpdateMillis = now
        _altitudeMeters.value = calibratedAltitude
        if (canPublishToTrackingManager()) {
            if (justConsumedPendingCalibration || prev == null) {
                TrackingManager.applyAltitudeCalibration(calibratedAltitude)
            } else {
                TrackingManager.updateAltitude(calibratedAltitude, isBarometric = true)
            }
        }
    }

    private fun saveCalibrationToPrefs(offsetMeters: Double, mslAltMeters: Double) {
        val now = System.currentTimeMillis()
        lastCalibrationTimeMillis = now
        prefs.edit()
            .putFloat(KEY_BARO_OFFSET, offsetMeters.toFloat())
            .putFloat(KEY_LAST_MSL_ALT, mslAltMeters.toFloat())
            .putLong(KEY_CALIBRATION_TIME_MS, now)
            .apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val PREFS_NAME = "offgrid_altimeter_prefs"
        private const val KEY_BARO_OFFSET = "baro_calibration_offset_m"
        private const val KEY_LAST_MSL_ALT = "last_calibrated_msl_alt_m"
        private const val KEY_CALIBRATION_TIME_MS = "baro_calibration_time_ms"

        /**
         * Extracts true orthometric elevation above mean sea level (MSL) from a [Location]:
         * 1. On Android 14+ (API 34+), uses `mslAltitudeMeters` if available.
         * 2. Otherwise converts `location.altitude` (WGS84 ellipsoid) to MSL by subtracting
         *    the EGM2008 geoid undulation.
         */
        fun extractMslAltitudeMeters(location: Location): Double? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude()) {
                return location.mslAltitudeMeters
            }
            if (!location.hasAltitude()) return null
            return wgs84ToMslAltitude(location.altitude, location.latitude, location.longitude)
        }

        /**
         * Converts height above the WGS84 reference ellipsoid ($h_{WGS84}$) to true orthometric
         * elevation above mean sea level ($H_{MSL}$) by subtracting the EGM2008 geoid height $N(\varphi, \lambda)$.
         */
        fun wgs84ToMslAltitude(wgs84AltitudeMeters: Double, latitude: Double, longitude: Double): Double {
            val geoidUndulationMeters = estimateEgm2008GeoidUndulationMeters(latitude, longitude)
            return wgs84AltitudeMeters - geoidUndulationMeters
        }

        /**
         * Computes the EGM2008 geoid undulation $N(\varphi, \lambda)$ in meters for Central Europe.
         * Accuracy relative to the full EGM2008 grid across Czechia is +-0.3 m.
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
