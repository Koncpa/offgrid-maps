package cz.offgrid.maps.data.tracking

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import cz.offgrid.maps.MainActivity
import cz.offgrid.maps.domain.model.TrackPoint

/**
 * Popřední služba (Foreground Service) pro záznam GPS trasy a automatický GPX export.
 */
class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    val point = TrackPoint(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                        timestampMillis = location.time,
                        accuracyMeters = location.accuracy,
                        speedMps = if (location.hasSpeed()) location.speed else null
                    )
                    TrackingManager.addPoint(point)
                    updateNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        TrackingManager.setTrackingActive(true)

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

        // Okamžitě si vyžádáme 1. bod při startu
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null && TrackingManager.isTracking.value) {
                    TrackingManager.addPoint(
                        TrackPoint(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            altitudeMeters = if (loc.hasAltitude()) loc.altitude else null,
                            timestampMillis = loc.time,
                            accuracyMeters = loc.accuracy
                        )
                    )
                }
            }

        // Plynulé vzorkování po 5 sekundách při pohybu o alespoň 2 metry (bez zpoždění dávkování)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .setMinUpdateDistanceMeters(2.0f)
            .setWaitForAccurateLocation(false)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)

        val recordedPoints = TrackingManager.trackPoints.value
        val distKm = TrackingManager.totalDistanceMeters.value / 1000.0

        // Uložíme trasu do .gpx souboru v hodinkách
        val savedFile = GpxExporter.saveTrackToGpx(this, recordedPoints)
        TrackingManager.setTrackingActive(false)

        if (savedFile != null) {
            TrackingManager.setSavedSummary("GPX uloženo: %.2f km (%d b.)".format(distKm, recordedPoints.size))
        } else {
            TrackingManager.setSavedSummary("Záznam ukončen (0 bodů)")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val distanceKm = TrackingManager.totalDistanceMeters.value / 1000.0
        val pointsCount = TrackingManager.trackPoints.value.size
        val statusText = "Vzdálenost: %.2f km • Body: %d".format(distanceKm, pointsCount)

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "cz.offgrid.maps.ACTION_START"
        const val ACTION_STOP = "cz.offgrid.maps.ACTION_STOP"
        private const val CHANNEL_ID = "offgrid_tracking_channel"
        private const val NOTIFICATION_ID = 1001

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
    }
}
