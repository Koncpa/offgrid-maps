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
 * Popřední služba (Foreground Service) pro spolehlivý záznam GPS trasy na Wear OS.
 *
 * Klíčové technické principy pro úsporu energie:
 * 1. Adaptivní interval (7 sekund): Pro pěší chůzi (~1.2 m/s) je posun o 8 metrů perfektní přesnost,
 *    ale GPS čip může mezi pulzy usínat (duty-cycling), což šetří ~60 % energie.
 * 2. Filtr minimální vzdálenosti (5 metrů): Pokud uživatel stojí (svačina, rozcestník),
 *    systém neprobouzí hlavní procesor ani nepřidává zbytečné duplicitní body.
 * 3. Hardware Batching (maxUpdateDelay = 10s): Umožňuje hardwarovému koprocesoru
 *    akumulovat vzorky a předat je najednou, místo neustálého probouzení CPU z hlubokého spánku.
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

        val notification = buildNotification("Hledám GPS satelity...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Bateriově optimalizovaný požadavek na polohu
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 7000L)
            .setMinUpdateIntervalMillis(4000L)
            .setMinUpdateDistanceMeters(5.0f) // Klíčový filtr: nevzorkuje, pokud stojíte na místě
            .setMaxUpdateDelayMillis(10000L)  // Hardware batching
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        TrackingManager.setTrackingActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Záznam trasy OffGrid",
                NotificationManager.IMPORTANCE_LOW // Nízká priorita: nevibruje a neruší při každém bodu
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
