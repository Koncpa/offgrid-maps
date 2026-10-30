package cz.offgrid.maps.data.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import cz.offgrid.maps.domain.model.TrackPoint

/**
 * Správce živé satelitní GPS polohy pro vykreslování na mapě, když se uživatel dívá na displej.
 *
 * Proč kombinujeme přímý hardwarový `LocationManager.GPS_PROVIDER` a `FusedLocationProviderClient`:
 * - Na Wear OS se `FusedLocationProviderClient` při připojeném telefonu přes Bluetooth často snaží
 *   šetřit baterii hodinek tím, že vrací hrubou polohu z Wi-Fi / BTS buněk v okolí (např. ze zástavby
 *   v Zbýšově / Sičce místo skutečné polohy na Zbýšovské haldě).
 * - Přímá registrace `LocationManager.GPS_PROVIDER` okamžitě probudí vestavěný GNSS čip hodinek
 *   (GPS + Galileo + GLONASS) a vynutí skutečný satelitní fix s přesností na metry.
 * - `FusedLocationProviderClient` s `PRIORITY_HIGH_ACCURACY` a `GRANULARITY_FINE` běží souběžně jako
 *   univerzální pojistka pro hodinky s Wear OS, které vlastní GPS čip nemají a berou GPS z telefonu.
 */
class LiveGpsManager(
    private val context: Context,
    private val altimeterManager: BarometricAltimeterManager
) {
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var isRunning = false
    private var lastHardwareGpsElapsedRealtimeNs = 0L

    private val hardwareGpsListener = LocationListener { location ->
        lastHardwareGpsElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
        processAccurateLocation(location, isDirectSatelliteGps = true)
    }

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                // Pokud nám v posledních 10 sekundách chodí přímá satelitní data z hardwarového GPS čipu,
                // ignorujeme méně přesné fúzované odhady (s nepřesností > 15 m), aby šipka neodskakovala.
                val nsSinceHwGps = SystemClock.elapsedRealtimeNanos() - lastHardwareGpsElapsedRealtimeNs
                val hasRecentHwGps = lastHardwareGpsElapsedRealtimeNs > 0L && nsSinceHwGps < 10_000_000_000L
                if (hasRecentHwGps && location.hasAccuracy() && location.accuracy > 15.0f) {
                    continue
                }
                processAccurateLocation(location, isDirectSatelliteGps = false)
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
     * Spustí živý satelitní příjem po dobu, kdy je displej hodinek rozsvícený (`ON_RESUME`).
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning || !hasFineLocationPermission()) return
        isRunning = true

        // 1. Nejprve zkontrolujeme poslední známou polohu, ale pouze pokud je čerstvá (< 2 minuty) a přesná (<= 30 m)
        fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
            if (lastLoc != null && isLocationFreshAndAccurate(lastLoc, maxAgeMs = 120_000L, maxAccMeters = 30.0f)) {
                processAccurateLocation(lastLoc, isDirectSatelliteGps = false)
            }
        }

        // 2. Aktivujeme přímý hardwarový satelitní přijímač (GPS_PROVIDER) v hodinkách
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
            }
        } catch (_: Exception) {
            // Pojistka pro zařízení bez přímého GPS provideru
        }

        // 3. Souběžně aktivujeme FusedLocationProvider s vynucenou satelitní přesností (GRANULARITY_FINE)
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
    }

    /**
     * Okamžitě vypne živý GPS přijímač pro vykreslování při zhasnutí displeje (`ON_PAUSE` -> 0 mW).
     * (Pokud běží nahrávání trasy REC, o záznam na pozadí se stará LocationTrackingService).
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            locationManager?.removeUpdates(hardwareGpsListener)
        } catch (_: Exception) {
        }
        fusedClient.removeLocationUpdates(fusedLocationCallback)
    }

    /**
     * Vyžádá si okamžitý čerstvý satelitní fix (`maxUpdateAgeMillis = 0`) po stisku tlačítka GPS.
     * Nikdy nepoužívá `PRIORITY_BALANCED_POWER_ACCURACY`, která vrací pouze hrubé BTS/Wi-Fi vysílače.
     */
    @SuppressLint("MissingPermission")
    fun requestImmediateHighAccuracyFix() {
        if (!hasFineLocationPermission()) return

        val currentRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(10_000L)
            .build()

        fusedClient.getCurrentLocation(currentRequest, null)
            .addOnSuccessListener { currentLoc ->
                if (currentLoc != null) {
                    processAccurateLocation(currentLoc, isDirectSatelliteGps = false)
                }
            }
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

    private fun processAccurateLocation(location: Location, isDirectSatelliteGps: Boolean) {
        // Odmítneme staré kešované polohy (starší než 45 sekund), které FusedProvider občas podstrčí z telefonu
        val ageMs = if (location.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
        if (!isDirectSatelliteGps && ageMs > 45_000L) {
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
}
