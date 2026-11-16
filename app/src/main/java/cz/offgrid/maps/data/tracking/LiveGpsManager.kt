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
 * Hybridní správce živé GPS polohy se striktním prahem přesnosti 10 metrů (`STRICT_ACCURACY_METERS = 10.0f`).
 *
 * Jak funguje úspora baterie vs. maximální přesnost:
 * 1. Primárně žádá `FusedLocationProviderClient` s `PRIORITY_HIGH_ACCURACY` a `GRANULARITY_FINE`.
 *    Pokud je připojený telefon přes Bluetooth a má dobrý výhled na satelity (přesnost <= 10 m),
 *    hodinky odebírají přesnou GPS z telefonu přes úsporný Bluetooth a vlastní GPS čip v hodinkách spí (0 mW).
 * 2. Jakmile však z telefonu přijde bod s nepřesností > 10 m (telefon je stíněný v kapse/batohu nebo posílá
 *    jen Wi-Fi/BTS ze zástavby) NEBO do 5 sekund nedorazí žádný fix s přesností <= 10 m, okamžitě se
 *    zapne přímý hardwarový satelitní čip v hodinkách (`LocationManager.GPS_PROVIDER`).
 * 3. Pokud se telefon později znovu chytne s přesností <= 10 m (3× po sobě), vlastní GPS čip v hodinkách
 *    se opět uspí, aby šetřil baterii hodinek.
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

    // Pojistný časovač: pokud do 1.5 s od rozsvícení displeje nedorazí fix <= 10 m, zapneme GPS v hodinkách
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
                    // POZOR: FusedLocationProvider na Wear OS přeposílá i body z lokálního GPS_PROVIDERu hodinek!
                    // Pokud běží aktivní trek nebo body pochází z čipu hodinek, NIKDY čip v hodinkách nevypínáme,
                    // jinak by se v lese cyklicky vypínal a ztrácel satelitní zámek.
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

                    // Pokud už čip v hodinkách nedávno dodal satelitní fix, horší fúzovaný bod zahodíme
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
     * Spustí příjem GPS při rozsvícení displeje (`ON_RESUME`).
     * Při aktivním treku okamžitě zapíná i přímý satelitní čip v hodinkách.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning || !hasFineLocationPermission()) return
        isRunning = true
        consecutiveAccurateFusedFixes = 0

        // 1. Použijeme poslední známou polohu z Fused i z přímého GPS čipu:
        //    - I když jsme v budově a poslední GPS fix je starší než 30 s, vždy z něj zkalibrujeme barometr,
        //      aby barometr neukazoval nezkalibrovaných ~500 m podle standardního tlaku 1013.25 hPa!
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
                // Pokud jsme po startu uvnitř budovy a ještě nemáme polohu, použijeme poslední známý fix
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

        // 2. Požádáme FusedLocationProvider o rychlou satelitní polohu (1 s interval)
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

        // 3. Při aktivním treku zapneme čip v hodinkách okamžitě (0 ms), jinak do 1.5 s
        mainHandler.removeCallbacks(fallbackToWatchGpsRunnable)
        if (TrackingManager.isTracking.value) {
            enableWatchHardwareGps()
        } else {
            mainHandler.postDelayed(fallbackToWatchGpsRunnable, 1500L)
        }
    }

    /**
     * Vypne odběr při zhasnutí displeje (`ON_PAUSE` -> 0 mW).
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
     * Vyžádá si okamžitý čerstvý satelitní fix (`maxUpdateAgeMillis = 0`) po stisku tlačítka GPS.
     * V lese a na horách okamžitě zapne hardwarový GPS čip v hodinkách bez čekání na telefon v batohu.
     */
    @SuppressLint("MissingPermission")
    fun requestImmediateHighAccuracyFix() {
        if (!isRunning || !hasFineLocationPermission()) return

        // 1. Okamžitě zapneme hardwarový satelitní čip v hodinkách (0 ms prodleva)
        enableWatchHardwareGps()

        // 2. Okamžitě vyčteme poslední satelitní fix z GPS_PROVIDERu, pokud je čerstvý (< 15 s)
        try {
            val lastHwGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastHwGps != null && isLocationFreshAndAccurate(lastHwGps, maxAgeMs = 15_000L, maxAccMeters = FOREST_SAT_ACCURACY_METERS)) {
                processLocation(lastHwGps, isDirectWatchSatellite = true)
            }
        } catch (_: Exception) {
        }

        // 3. Vyprázdníme případnou dávkovací frontu
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
                    val acc = if (currentLoc.hasAccuracy()) currentLoc.accuracy else 999.0f
                    if (acc > STRICT_ACCURACY_METERS) {
                        enableWatchHardwareGps()
                    }
                    processLocation(currentLoc, isDirectWatchSatellite = false)
                } else {
                    enableWatchHardwareGps()
                }
            }
    }

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
        val mslAlt = BarometricAltimeterManager.extractMslAltitudeMeters(location) ?: return
        val gpsAcc = if (location.hasAccuracy()) location.accuracy else null
        val vertAcc = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null
        altimeterManager.onGpsAltitudeReceived(mslAlt, gpsAcc, vertAcc)
    }

    private fun processLocation(location: Location, isDirectWatchSatellite: Boolean) {
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
        /** Striktní práh přesnosti (10 metrů) pro rozhodnutí, zda stačí GPS z telefonu, nebo se zapne čip v hodinkách. */
        const val STRICT_ACCURACY_METERS = 10.0f
        /** Přesnost plnohodnotného satelitního zámku v lese / hlubokém údolí (do 20 m). */
        const val FOREST_SAT_ACCURACY_METERS = 20.0f
    }
}
