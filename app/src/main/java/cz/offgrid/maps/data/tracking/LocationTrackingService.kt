package cz.offgrid.maps.data.tracking

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import cz.offgrid.maps.MainActivity
import cz.offgrid.maps.domain.model.TrackPoint

/**
 * Popřední služba (Foreground Service) pro záznam GPS trasy, barometrického převýšení,
 * průběžné zálohování a automatické nouzové uložení GPX při poklesu baterie na <= 5 %.
 */
class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationManager: LocationManager? = null
    private lateinit var locationCallback: LocationCallback
    private lateinit var hardwareGpsListener: LocationListener
    private lateinit var altimeterManager: BarometricAltimeterManager
    private var lastNotificationMillis = 0L
    private var lastNotifiedDistanceMeters = 0.0
    private var lastCheckpointPointsCount = 0
    private var isScreenInteractive = true
    private var isBatteryReceiverRegistered = false
    private var isWatchHardwareGpsActive = false
    private var consecutiveAccurateFusedFixes = 0

    /**
     * Pasivní posluchač systémového vysílání stavu baterie (0 mW spotřeba, žádné dotazování ve smyčce).
     * Jakmile baterie klesne na <= 5 % (a hodinky nejsou na nabíječce), okamžitě uloží GPX trasu
     * a vypne GPS, aby zbylých 5 % udrželo hodinky v chodu.
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            if (!TrackingManager.isTracking.value) return

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            if (level >= 0 && scale > 0 && !isCharging) {
                val batteryPct = (level * 100) / scale
                if (batteryPct in 1..LOW_BATTERY_THRESHOLD_PCT) {
                    stopTracking(emergencyLowBattery = true)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        altimeterManager = BarometricAltimeterManager(this)
        createNotificationChannel()

        hardwareGpsListener = LocationListener { location ->
            handleIncomingLocation(location)
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    // Během aktivního treku v přírodě necháváme hardwarový GPS čip hodinek trvale zapnutý,
                    // aby v lese neztrácel satelitní zámek (FusedLocationProvider na Wear OS přeposílá
                    // i body z čipu hodinek, takže vypínání po 3 bodech by způsobilo cyklické výpadky).
                    if (!isWatchHardwareGpsActive) {
                        enableWatchHardwareGps()
                    }
                    handleIncomingLocation(location)
                }
            }
        }
    }

    private fun handleIncomingLocation(location: Location) {
        val mslAlt = BarometricAltimeterManager.extractMslAltitudeMeters(location)
        val gpsAcc = if (location.hasAccuracy()) location.accuracy else null
        val vertAcc = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null
        altimeterManager.onGpsAltitudeReceived(mslAlt, gpsAcc, vertAcc)

        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = mslAlt,
            timestampMillis = location.time,
            accuracyMeters = location.accuracy,
            speedMps = if (location.hasSpeed()) location.speed else null
        )
        TrackingManager.addPoint(point)

        // Průběžná záloha na disk každých 10 zaznamenaných bodů (ochrana proti vybití/restartu)
        val currentPoints = TrackingManager.trackPoints.value
        if (currentPoints.size - lastCheckpointPointsCount >= 10) {
            lastCheckpointPointsCount = currentPoints.size
            GpxExporter.saveRecoveryCheckpoint(this@LocationTrackingService, currentPoints)
        }

        updateNotificationThrottled()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking(emergencyLowBattery = false)
            ACTION_SCREEN_ON -> {
                if (TrackingManager.isTracking.value && !isScreenInteractive) {
                    isScreenInteractive = true
                    fusedLocationClient.flushLocations()
                    registerLocationRequest(interactive = true)
                }
            }
            ACTION_SCREEN_OFF -> {
                if (TrackingManager.isTracking.value && isScreenInteractive) {
                    isScreenInteractive = false
                    registerLocationRequest(interactive = false)
                }
            }
            ACTION_FLUSH_NOW -> {
                if (TrackingManager.isTracking.value) {
                    fusedLocationClient.flushLocations()
                    try {
                        val lastHw = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        if (lastHw != null && (System.currentTimeMillis() - lastHw.time) in 0..15_000L) {
                            handleIncomingLocation(lastHw)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        TrackingManager.setTrackingActive(true)
        altimeterManager.start()
        lastNotificationMillis = System.currentTimeMillis()
        lastNotifiedDistanceMeters = 0.0
        lastCheckpointPointsCount = 0
        isScreenInteractive = true
        consecutiveAccurateFusedFixes = 0

        if (!isBatteryReceiverRegistered) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            isBatteryReceiverRegistered = true
        }

        val notification = buildNotification("Recording track...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 1. Okamžitě zapneme přímý hardwarový GPS čip v hodinkách (0 ms), aby v lese nečekal na telefon v batohu
        enableWatchHardwareGps()

        // 2. Okamžitě si vyžádáme čerstvý satelitní bod při startu (maxUpdateAgeMillis = 0)
        val currentReq = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(4_000L)
            .build()

        fusedLocationClient.getCurrentLocation(currentReq, null)
            .addOnSuccessListener { loc ->
                if (loc != null && TrackingManager.isTracking.value) {
                    handleIncomingLocation(loc)
                    GpxExporter.saveRecoveryCheckpoint(this, TrackingManager.trackPoints.value)
                }
            }

        registerLocationRequest(interactive = true)
    }

    /**
     * Přepíná mezi rychlým 1.5s intervalem (když se uživatel dívá na displej)
     * a 4s intervalem (když je ruka svěšená), aniž by vypínal satelitní zámek v hodinkách.
     */
    @SuppressLint("MissingPermission")
    private fun registerLocationRequest(interactive: Boolean) {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        // Hardwarový GPS čip nevypínáme, pouze jej ujistíme, že běží
        enableWatchHardwareGps()

        val locationRequest = if (interactive) {
            // Displej svítí: Rychlý živý režim bez zpoždění pro okamžitou odezvu tlačítka GPS a šipky
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
                .setMinUpdateIntervalMillis(1000L)
                .setMinUpdateDistanceMeters(1.0f)
                .setMaxUpdateDelayMillis(0L)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setWaitForAccurateLocation(false)
                .build()
        } else {
            // Displej zhasnutý: Kontinuální záznam bez 30s prodlevy, aby po zvednutí ruky byla poloha hned aktuální
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
                .setMinUpdateIntervalMillis(2500L)
                .setMinUpdateDistanceMeters(2.0f)
                .setMaxUpdateDelayMillis(5000L)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setWaitForAccurateLocation(false)
                .build()
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    @SuppressLint("MissingPermission")
    private fun enableWatchHardwareGps() {
        if (!TrackingManager.isTracking.value || isWatchHardwareGpsActive) return
        try {
            val lm = locationManager
            if (lm != null && lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1500L,
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

    private fun stopTracking(emergencyLowBattery: Boolean) {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        disableWatchHardwareGps()
        altimeterManager.stop()
        unregisterBatteryReceiverSafely()

        val recordedPoints = TrackingManager.trackPoints.value
        val distKm = TrackingManager.totalDistanceMeters.value / 1000.0
        val ascentM = TrackingManager.totalAscentMeters.value.toInt()

        val prefix = if (emergencyLowBattery) "lowbat5pct" else "hike"
        val savedFile = GpxExporter.saveTrackToGpx(this, recordedPoints, prefix = prefix)
        TrackingManager.setTrackingActive(false)

        if (emergencyLowBattery) {
            TrackingManager.setSavedSummary("Battery ≤5%! GPX saved (%.2f km)".format(distKm))
        } else if (savedFile != null) {
            TrackingManager.setSavedSummary("GPX: %.2f km • ↑%d m".format(distKm, ascentM))
        } else {
            GpxExporter.clearRecoveryCheckpoint(this)
            TrackingManager.setSavedSummary("Recording stopped (0 pts)")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun unregisterBatteryReceiverSafely() {
        if (isBatteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: IllegalArgumentException) {
            }
            isBatteryReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        unregisterBatteryReceiverSafely()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OffGrid Track Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active offline GPS track recording status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OffGrid Recording")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Aktualizuje systémovou notifikaci maximálně jednou za 60 sekund nebo po každých 100 m,
     * aby nebudila proces `system_server` přes Binder IPC při každém GPS bodu.
     */
    private fun updateNotificationThrottled() {
        val now = System.currentTimeMillis()
        val totalDistMeters = TrackingManager.totalDistanceMeters.value
        if (now - lastNotificationMillis < 60_000L && (totalDistMeters - lastNotifiedDistanceMeters) < 100.0) {
            return
        }
        lastNotificationMillis = now
        lastNotifiedDistanceMeters = totalDistMeters

        val distanceKm = totalDistMeters / 1000.0
        val ascentM = TrackingManager.totalAscentMeters.value.toInt()
        val statusText = "%.2f km • ↑%d m".format(distanceKm, ascentM)

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "cz.offgrid.maps.ACTION_START"
        const val ACTION_STOP = "cz.offgrid.maps.ACTION_STOP"
        const val ACTION_SCREEN_ON = "cz.offgrid.maps.ACTION_SCREEN_ON"
        const val ACTION_SCREEN_OFF = "cz.offgrid.maps.ACTION_SCREEN_OFF"
        const val ACTION_FLUSH_NOW = "cz.offgrid.maps.ACTION_FLUSH_NOW"
        private const val CHANNEL_ID = "offgrid_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val LOW_BATTERY_THRESHOLD_PCT = 5

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun notifyScreenState(context: Context, interactive: Boolean) {
            if (!TrackingManager.isTracking.value) return
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = if (interactive) ACTION_SCREEN_ON else ACTION_SCREEN_OFF
            }
            context.startService(intent)
        }

        fun flushNow(context: Context) {
            if (!TrackingManager.isTracking.value) return
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_FLUSH_NOW
            }
            context.startService(intent)
        }
    }
}
