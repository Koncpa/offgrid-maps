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
                    handleIncomingLocation(location)
                }
            }
        }
    }

    private fun handleIncomingLocation(location: Location) {
        val gpsAlt = if (location.hasAltitude()) location.altitude else null
        val gpsAcc = if (location.hasAccuracy()) location.accuracy else null
        altimeterManager.onGpsAltitudeReceived(gpsAlt, gpsAcc)

        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = gpsAlt,
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

        if (!isBatteryReceiverRegistered) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            isBatteryReceiverRegistered = true
        }

        val notification = buildNotification("Zaznamenávám trasu...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Okamžitě si vyžádáme čerstvý satelitní bod při startu (maxUpdateAgeMillis = 0)
        val currentReq = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMaxUpdateAgeMillis(0L)
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
     * Přepíná mezi živým GPS režimem (když se uživatel dívá na displej)
     * a úsporným hardwarovým FIFO dávkováním (když je ruka svěšená a displej zhasnutý).
     */
    @SuppressLint("MissingPermission")
    private fun registerLocationRequest(interactive: Boolean) {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        try {
            locationManager?.removeUpdates(hardwareGpsListener)
        } catch (_: Exception) {
        }

        // Přímé buzení hardwarového GNSS čipu hodinek (zabraňuje přepnutí na hrubé BTS/Wi-Fi z telefonu)
        try {
            val lm = locationManager
            if (lm != null && lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                val minTimeMs = if (interactive) 3000L else 6000L
                val minDistM = if (interactive) 2.0f else 3.5f
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistM,
                    hardwareGpsListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: Exception) {
        }

        val locationRequest = if (interactive) {
            // Displej svítí: Živý režim bez zpoždění pro okamžité vykreslování stopy
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
                .setMinUpdateIntervalMillis(2000L)
                .setMinUpdateDistanceMeters(2.0f)
                .setMaxUpdateDelayMillis(0L)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setWaitForAccurateLocation(false)
                .build()
        } else {
            // Displej zhasnutý: Hardwarový FIFO batching v GNSS čipu (hlavní CPU spí 30 s v kuse)
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 8000L)
                .setMinUpdateIntervalMillis(5000L)
                .setMinUpdateDistanceMeters(3.5f)
                .setMaxUpdateDelayMillis(30000L)
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

    private fun stopTracking(emergencyLowBattery: Boolean) {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        try {
            locationManager?.removeUpdates(hardwareGpsListener)
        } catch (_: Exception) {
        }
        altimeterManager.stop()
        unregisterBatteryReceiverSafely()

        val recordedPoints = TrackingManager.trackPoints.value
        val distKm = TrackingManager.totalDistanceMeters.value / 1000.0
        val ascentM = TrackingManager.totalAscentMeters.value.toInt()

        val prefix = if (emergencyLowBattery) "baterie5pct" else "tura"
        val savedFile = GpxExporter.saveTrackToGpx(this, recordedPoints, prefix = prefix)
        TrackingManager.setTrackingActive(false)

        if (emergencyLowBattery) {
            TrackingManager.setSavedSummary("Baterie ≤5 %! GPX uloženo (%.2f km)".format(distKm))
        } else if (savedFile != null) {
            TrackingManager.setSavedSummary("GPX: %.2f km • ↑%d m".format(distKm, ascentM))
        } else {
            GpxExporter.clearRecoveryCheckpoint(this)
            TrackingManager.setSavedSummary("Záznam ukončen (0 bodů)")
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
                "Záznam trasy OffGrid",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zobrazuje aktivní stav záznamu offline trasy"
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
            .setContentTitle("OffGrid Záznam")
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
    }
}
