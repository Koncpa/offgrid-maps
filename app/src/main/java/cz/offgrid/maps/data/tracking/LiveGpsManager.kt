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

    // Pojistný časovač: pokud do 5 s od rozsvícení displeje nedorazí z telefonu fix <= 10 m, zapneme GPS v hodinkách
    private val fallbackToWatchGpsRunnable = Runnable {
        if (!isRunning) return@Runnable
        val nsSinceStrictFix = SystemClock.elapsedRealtimeNanos() - lastStrictFixElapsedRealtimeNs
        val hasRecentStrictFix = lastStrictFixElapsedRealtimeNs > 0L && nsSinceStrictFix < 5_000_000_000L
        if (!hasRecentStrictFix) {
            enableWatchHardwareGps()
        }
    }

    private val hardwareGpsListener = LocationListener { location ->
        if (!isRunning) return@LocationListener
        lastHardwareGpsElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
        if (location.hasAccuracy() && location.accuracy <= STRICT_ACCURACY_METERS) {
            lastStrictFixElapsedRealtimeNs = lastHardwareGpsElapsedRealtimeNs
        }
        processLocation(location, isDirectWatchSatellite = true)
    }

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!isRunning) return
            for (location in result.locations) {
                val acc = if (location.hasAccuracy()) location.accuracy else 999.0f

                if (acc <= STRICT_ACCURACY_METERS) {
                    // Telefon posílá špičkovou satelitní polohu (<= 10 m)
                    lastStrictFixElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
                    consecutiveAccurateFusedFixes++

                    // Pokud už 3× po sobě dorazila z telefonu přesnost <= 10 m, můžeme uspat čip v hodinkách
                    if (isWatchHardwareGpsActive && consecutiveAccurateFusedFixes >= 3) {
                        disableWatchHardwareGps()
                    }
                    processLocation(location, isDirectWatchSatellite = false)
                } else {
                    // Přesnost z telefonu je horší než 10 m (kapsa, batoh, nebo BTS v Sičce) -> okamžitě zapínáme čip v hodinkách!
                    consecutiveAccurateFusedFixes = 0
                    enableWatchHardwareGps()

                    // Pokud už čip v hodinkách nedávno dodal satelitní fix, horší fúzovaný bod zahodíme
                    val nsSinceHwGps = SystemClock.elapsedRealtimeNanos() - lastHardwareGpsElapsedRealtimeNs
                    val hasRecentHwGps = lastHardwareGpsElapsedRealtimeNs > 0L && nsSinceHwGps < 12_000_000_000L
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
     * Začne úsporně přes FusedLocationProvider (Bluetooth z telefonu) a pokud přesnost není <= 10 m,
     * automaticky zapne vestavěný GPS čip v hodinkách.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning || !hasFineLocationPermission()) return
        isRunning = true
        consecutiveAccurateFusedFixes = 0

        // 1. Použijeme poslední známou polohu pouze tehdy, pokud je čerstvá (< 60 s) a má přesnost <= 10 m
        fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
            if (!isRunning) return@addOnSuccessListener
            if (lastLoc != null && isLocationFreshAndAccurate(lastLoc, maxAgeMs = 60_000L, maxAccMeters = STRICT_ACCURACY_METERS)) {
                lastStrictFixElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
                processLocation(lastLoc, isDirectWatchSatellite = false)
            }
        }

        // 2. Primárně požádáme FusedLocationProvider o satelitní přesnost (umožní brát GPS z telefonu, pokud <= 10 m)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2500L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(1.0f)
            .setMaxUpdateDelayMillis(0L)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
            .build()

        fusedClient.requestLocationUpdates(
            request,
            fusedLocationCallback,
            Looper.getMainLooper()
        )

        // 3. Pokud do 5 sekund nedorazí z telefonu fix s přesností <= 10 m, zapneme přímo GPS čip v hodinkách
        mainHandler.removeCallbacks(fallbackToWatchGpsRunnable)
        mainHandler.postDelayed(fallbackToWatchGpsRunnable, 5000L)
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
     */
    @SuppressLint("MissingPermission")
    fun requestImmediateHighAccuracyFix() {
        if (!isRunning || !hasFineLocationPermission()) return

        currentLocTokenSource?.cancel()
        val tokenSource = CancellationTokenSource()
        currentLocTokenSource = tokenSource

        val currentRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(8_000L)
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
                    2000L,
                    1.0f,
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

    private fun processLocation(location: Location, isDirectWatchSatellite: Boolean) {
        val ageMs = if (location.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
        if (!isDirectWatchSatellite && ageMs > 30_000L) {
            return
        }

        val gpsAlt = if (location.hasAltitude()) location.altitude else null
        val gpsAcc = if (location.hasAccuracy()) location.accuracy else null
        altimeterManager.onGpsAltitudeReceived(gpsAlt, gpsAcc)

        TrackingManager.addPoint(
            TrackPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeMeters = gpsAlt,
                timestampMillis = location.time,
                accuracyMeters = location.accuracy,
                speedMps = if (location.hasSpeed()) location.speed else null
            )
        )
    }

    companion object {
        /** Striktní práh přesnosti (10 metrů) pro rozhodnutí, zda stačí GPS z telefonu, nebo se zapne čip v hodinkách. */
        const val STRICT_ACCURACY_METERS = 10.0f
    }
}
