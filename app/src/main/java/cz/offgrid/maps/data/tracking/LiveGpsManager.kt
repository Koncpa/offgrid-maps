package cz.offgrid.maps.data.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import cz.offgrid.maps.domain.model.TrackPoint

/**
 * Hybrid live GPS manager with a 10-meter open-sky threshold (`STRICT_ACCURACY_METERS = 10.0f`)
 * and instant hardware GNSS engagement for forest hikes.
 *
 * How battery efficiency and accuracy work together:
 * 1. Requests `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY` and `GRANULARITY_FINE`.
 *    When a companion phone is connected over Bluetooth with clear sky view (accuracy <= 10 m),
 *    the watch receives accurate GPS over low-power Bluetooth while its internal GNSS chip sleeps (0 mW).
 * 2. During an active trek (`TrackingManager.isTracking.value`), when pressing the `GPS` button, or
 *    whenever phone accuracy degrades (> 10 m in a pocket/backpack or under forest canopy), the watch's
 *    built-in hardware satellite receiver (`LocationManager.GPS_PROVIDER`) is engaged immediately.
 */
class LiveGpsManager(
    private val context: Context,
    private val altimeterManager: BarometricAltimeterManager
) {
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var isWatchHardwareGpsActive = false
    private var lastStrictFixElapsedRealtimeNs = 0L
    private var lastHardwareGpsElapsedRealtimeNs = 0L
    private var consecutiveAccurateFusedFixes = 0
    private var currentLocTokenSource: CancellationTokenSource? = null

    // Fallback timer: if no <= 10 m fix arrives within 1.5 s of screen wake, enable watch hardware GPS
    private val fallbackToWatchGpsRunnable = Runnable {
        if (!isRunning) return@Runnable
        val nsSinceStrictFix = SystemClock.elapsedRealtimeNanos() - lastStrictFixElapsedRealtimeNs
        val hasRecentStrictFix = lastStrictFixElapsedRealtimeNs > 0L && nsSinceStrictFix < 2_000_000_000L
        if (!hasRecentStrictFix) {
            enableWatchHardwareGps()
        }
    }

    private val hardwareGpsListener = LocationListener { location ->
        if (!isRunning) return@LocationListener
        lastHardwareGpsElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
        if (location.hasAccuracy() && location.accuracy <= FOREST_SAT_ACCURACY_METERS) {
            lastStrictFixElapsedRealtimeNs = lastHardwareGpsElapsedRealtimeNs
        }
        processLocation(location, isDirectWatchSatellite = true)
    }

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!isRunning) return
            for (location in result.locations) {
                val acc = if (location.hasAccuracy()) location.accuracy else 999.0f
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val nsSinceHwGps = nowNs - lastHardwareGpsElapsedRealtimeNs
                val isEchoFromWatchHwGps = isWatchHardwareGpsActive &&
                        lastHardwareGpsElapsedRealtimeNs > 0L &&
                        nsSinceHwGps < 5_000_000_000L

                if (acc <= STRICT_ACCURACY_METERS) {
                    lastStrictFixElapsedRealtimeNs = nowNs
                    // Note: FusedLocationProvider on Wear OS also echoes fixes from the watch's local GPS_PROVIDER.
                    // Never disable the watch hardware GPS during an active trek or when fixes originate from the watch chip,
                    // otherwise the watch would cycle its GNSS receiver on/off in the forest and lose satellite lock.
                    if (!isEchoFromWatchHwGps && !TrackingManager.isTracking.value) {
                        consecutiveAccurateFusedFixes++
                        if (isWatchHardwareGpsActive && consecutiveAccurateFusedFixes >= 6) {
                            disableWatchHardwareGps()
                        }
                    }
                    processLocation(location, isDirectWatchSatellite = isEchoFromWatchHwGps)
                } else {
                    consecutiveAccurateFusedFixes = 0
                    enableWatchHardwareGps()

                    // Discard coarser fused estimates when the watch hardware GPS recently delivered a satellite fix
                    val hasRecentHwGps = lastHardwareGpsElapsedRealtimeNs > 0L && nsSinceHwGps < 10_000_000_000L
                    if (!hasRecentHwGps) {
                        processLocation(location, isDirectWatchSatellite = false)
                    }
                }
            }
        }
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts live GPS updates when the screen wakes (`ON_RESUME`).
     * Immediately engages the watch's hardware satellite receiver if an active trek is recording.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning || !hasFineLocationPermission()) return
        isRunning = true
        consecutiveAccurateFusedFixes = 0

        // 1. Use last known location from both Fused and direct GPS_PROVIDER:
        //    - Even indoors when the last GPS fix is older than 30 s, always calibrate the barometer from it
        //      so the barometer never displays uncalibrated standard-atmosphere altitude!
        try {
            val lastHw = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastHw != null) {
                calibrateAltimeterFromLocation(lastHw)
                if (isLocationFreshAndAccurate(lastHw, maxAgeMs = 30_000L, maxAccMeters = FOREST_SAT_ACCURACY_METERS)) {
                    lastStrictFixElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
                    processLocation(lastHw, isDirectWatchSatellite = true)
                }
            }
        } catch (_: Exception) {
        }

        fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
            if (!isRunning || lastLoc == null) return@addOnSuccessListener
            calibrateAltimeterFromLocation(lastLoc)
            if (isLocationFreshAndAccurate(lastLoc, maxAgeMs = 30_000L, maxAccMeters = FOREST_SAT_ACCURACY_METERS)) {
                lastStrictFixElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
                processLocation(lastLoc, isDirectWatchSatellite = false)
            } else if (TrackingManager.currentLocation.value == null && lastLoc.hasAccuracy() && lastLoc.accuracy <= 25.0f) {
                // Seed initial indoor position from the last known fix if no current location is set yet
                val mslAlt = BarometricAltimeterManager.extractMslAltitudeMeters(lastLoc)
                TrackingManager.addPoint(
                    TrackPoint(
                        latitude = lastLoc.latitude,
                        longitude = lastLoc.longitude,
                        altitudeMeters = mslAlt,
                        timestampMillis = lastLoc.time,
                        accuracyMeters = lastLoc.accuracy,
                        speedMps = null
                    )
                )
            }
        }

        // 2. Request fast high-accuracy updates from FusedLocationProvider
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
            .setMinUpdateIntervalMillis(800L)
            .setMinUpdateDistanceMeters(0.5f)
            .setMaxUpdateDelayMillis(0L)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
            .build()

        fusedClient.requestLocationUpdates(
            request,
            fusedLocationCallback,
            Looper.getMainLooper()
        )

        // 3. Engage watch hardware GPS immediately (0 ms) during an active trek, otherwise after 1.5 s
        mainHandler.removeCallbacks(fallbackToWatchGpsRunnable)
        if (TrackingManager.isTracking.value) {
            enableWatchHardwareGps()
        } else {
            mainHandler.postDelayed(fallbackToWatchGpsRunnable, 1500L)
        }
    }

    /**
     * Stops live UI location updates when the screen turns off (`ON_PAUSE` -> 0 mW).
     */
    fun stop() {
        isRunning = false
        mainHandler.removeCallbacks(fallbackToWatchGpsRunnable)
        currentLocTokenSource?.cancel()
        currentLocTokenSource = null
        disableWatchHardwareGps()
        fusedClient.removeLocationUpdates(fusedLocationCallback)
    }

    /**
     * Requests an immediate fresh satellite fix (`maxUpdateAgeMillis = 0`) when the user taps the GPS button.
     * Immediately activates the watch's hardware GNSS chip without waiting for a phone Bluetooth timeout.
     */
    @SuppressLint("MissingPermission")
    fun requestImmediateHighAccuracyFix() {
        if (!isRunning || !hasFineLocationPermission()) return

        if (TrackingManager.isTracking.value) {
            LocationTrackingService.flushNow(context)
            return
        }

        // 1. Immediately engage the watch's hardware satellite receiver (0 ms delay)
        enableWatchHardwareGps()

        // 2. Immediately read the latest fix from GPS_PROVIDER if fresh (< 15 s)
        try {
            val lastHwGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastHwGps != null && isLocationFreshAndAccurate(lastHwGps, maxAgeMs = 15_000L, maxAccMeters = FOREST_SAT_ACCURACY_METERS)) {
                processLocation(lastHwGps, isDirectWatchSatellite = true)
            }
        } catch (_: Exception) {
        }

        // 3. Flush any batched locations
        fusedClient.flushLocations()

        currentLocTokenSource?.cancel()
        val tokenSource = CancellationTokenSource()
        currentLocTokenSource = tokenSource

        val currentRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(4_000L)
            .build()

        fusedClient.getCurrentLocation(currentRequest, tokenSource.token)
            .addOnSuccessListener { currentLoc ->
                if (!isRunning) return@addOnSuccessListener
                if (currentLoc != null) {
                    val acc = if (currentLoc.hasAccuracy()) locationAccuracy(currentLoc) else 999.0f
                    if (acc > STRICT_ACCURACY_METERS) {
                        enableWatchHardwareGps()
                    }
                    processLocation(currentLoc, isDirectWatchSatellite = false)
                } else {
                    enableWatchHardwareGps()
                }
            }
    }

    private fun locationAccuracy(loc: Location): Float = if (loc.hasAccuracy()) loc.accuracy else 999.0f

    @SuppressLint("MissingPermission")
    private fun enableWatchHardwareGps() {
        if (!isRunning || isWatchHardwareGpsActive || !hasFineLocationPermission()) return
        try {
            val lm = locationManager
            if (lm != null && lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0.5f,
                    hardwareGpsListener,
                    Looper.getMainLooper()
                )
                isWatchHardwareGpsActive = true
            }
        } catch (_: Exception) {
        }
    }

    private fun disableWatchHardwareGps() {
        try {
            locationManager?.removeUpdates(hardwareGpsListener)
        } catch (_: Exception) {
        }
        isWatchHardwareGpsActive = false
    }

    private fun isLocationFreshAndAccurate(
        location: Location,
        maxAgeMs: Long,
        maxAccMeters: Float
    ): Boolean {
        val ageMs = if (location.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
        val acc = if (location.hasAccuracy()) location.accuracy else 999.0f
        return ageMs in 0..maxAgeMs && acc <= maxAccMeters
    }

    private fun calibrateAltimeterFromLocation(location: Location) {
        if (TrackingManager.isTracking.value) return
        val mslAlt = BarometricAltimeterManager.extractMslAltitudeMeters(location) ?: return
        val gpsAcc = if (location.hasAccuracy()) location.accuracy else null
        val vertAcc = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null
        altimeterManager.onGpsAltitudeReceived(mslAlt, gpsAcc, vertAcc)
    }

    private fun processLocation(location: Location, isDirectWatchSatellite: Boolean) {
        // When LocationTrackingService is recording, it owns the active GPS & altimeter pipeline exclusively
        if (TrackingManager.isTracking.value) {
            return
        }

        val ageMs = if (location.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
        if (!isDirectWatchSatellite && ageMs > 20_000L) {
            return
        }

        val mslAlt = BarometricAltimeterManager.extractMslAltitudeMeters(location)
        val gpsAcc = if (location.hasAccuracy()) location.accuracy else null
        val vertAcc = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null
        altimeterManager.onGpsAltitudeReceived(mslAlt, gpsAcc, vertAcc)

        TrackingManager.addPoint(
            TrackPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeMeters = mslAlt,
                timestampMillis = location.time,
                accuracyMeters = location.accuracy,
                speedMps = if (location.hasSpeed()) location.speed else null
            )
        )
    }

    companion object {
        /** Strict open-sky accuracy threshold (10 m) for deciding between phone GPS and watch GNSS. */
        const val STRICT_ACCURACY_METERS = 10.0f
        /** Valid satellite lock accuracy threshold under dense forest canopy or steep valleys (up to 20 m). */
        const val FOREST_SAT_ACCURACY_METERS = 20.0f
    }
}
