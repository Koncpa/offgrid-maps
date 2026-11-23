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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import cz.offgrid.maps.MainActivity
import cz.offgrid.maps.domain.model.TrackPoint
import java.util.concurrent.Executors

/**
 * Foreground service for continuous GPS track recording, barometric elevation gain,
 * periodic crash recovery checkpointing, and automatic emergency GPX save at <= 5% battery.
 */
class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationManager: LocationManager? = null
    private lateinit var locationCallback: LocationCallback
    private lateinit var hardwareGpsListener: LocationListener
    private lateinit var altimeterManager: BarometricAltimeterManager
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var lastNotificationMillis = 0L
    private var lastNotifiedDistanceMeters = 0.0
    private var lastCheckpointPointsCount = 0
    private var isScreenInteractive = true
    private var isBatteryReceiverRegistered = false
    private var isWatchHardwareGpsActive = false
    private var lastHardwareGpsElapsedRealtimeNs = 0L

    /**
     * Passive system battery broadcast listener (0 mW overhead, no polling loop).
     * As soon as battery drops to <= 5% (and the watch is not charging), immediately saves the GPX track
     * and shuts down GPS so the remaining 5% keeps the watch alive.
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
        altimeterManager = BarometricAltimeterManager(this, isTrackingOwner = true)
        createNotificationChannel()

        hardwareGpsListener = LocationListener { location ->
            if (location.hasAccuracy() && location.accuracy <= LiveGpsManager.FOREST_SAT_ACCURACY_METERS) {
                lastHardwareGpsElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
            }
            handleIncomingLocation(location)
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    // Keep the watch's hardware GNSS chip continuously active during an active trek
                    // so it maintains carrier-phase satellite lock under forest canopy.
                    if (!isWatchHardwareGpsActive) {
                        enableWatchHardwareGps()
                    }
                    // When the watch's hardware GPS recently delivered a satellite fix (< 5 s),
                    // skip redundant or coarser Fused echoes to prevent sawtooth zig-zagging.
                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    val hasRecentHwFix = isWatchHardwareGpsActive &&
                        lastHardwareGpsElapsedRealtimeNs > 0L &&
                        (nowNs - lastHardwareGpsElapsedRealtimeNs) < 5_000_000_000L
                    if (!hasRecentHwFix) {
                        handleIncomingLocation(location)
                    }
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

        // Save recovery checkpoint to disk asynchronously every 10 recorded points
        val currentPoints = TrackingManager.trackPoints.value
        if (currentPoints.size - lastCheckpointPointsCount >= 10) {
            lastCheckpointPointsCount = currentPoints.size
            val snapshot = currentPoints.toList()
            val appCtx = applicationContext
            ioExecutor.execute {
                GpxExporter.saveRecoveryCheckpoint(appCtx, snapshot)
            }
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
        lastHardwareGpsElapsedRealtimeNs = 0L

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

        // 1. Immediately engage the watch's hardware GPS receiver (0 ms) so it never waits for a phone in a backpack
        enableWatchHardwareGps()

        // 2. Request an immediate fresh satellite fix at start (maxUpdateAgeMillis = 0)
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
                    val snapshot = TrackingManager.trackPoints.value.toList()
                    val appCtx = applicationContext
                    ioExecutor.execute {
                        GpxExporter.saveRecoveryCheckpoint(appCtx, snapshot)
                    }
                }
            }

        registerLocationRequest(interactive = true)
    }

    /**
     * Switches between fast 1.5 s updates (when the screen is interactive) and 4 s updates
     * (when the wrist is lowered) while keeping the watch's satellite lock warm.
     */
    @SuppressLint("MissingPermission")
    private fun registerLocationRequest(interactive: Boolean) {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        enableWatchHardwareGps()

        val locationRequest = if (interactive) {
            // Screen on: fast live mode without delay for instant GPS button and cursor response
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
                .setMinUpdateIntervalMillis(1000L)
                .setMinUpdateDistanceMeters(1.0f)
                .setMaxUpdateDelayMillis(0L)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setWaitForAccurateLocation(false)
                .build()
        } else {
            // Screen off: continuous recording without long batching delay so lifting the wrist shows current position
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
     * Updates the system notification at most once every 60 seconds or every 100 m
     * to avoid waking `system_server` over Binder IPC on every single GPS fix.
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
