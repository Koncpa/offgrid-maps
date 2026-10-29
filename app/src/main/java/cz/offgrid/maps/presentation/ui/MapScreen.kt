package cz.offgrid.maps.presentation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import cz.offgrid.maps.data.map.MapFileManager
import cz.offgrid.maps.data.map.OfflineRegionHelper
import cz.offgrid.maps.data.tracking.CompassSensorManager
import cz.offgrid.maps.data.tracking.LocationTrackingService
import cz.offgrid.maps.data.tracking.TrackingManager
import cz.offgrid.maps.domain.model.TrackPoint
import cz.offgrid.maps.presentation.theme.*
import cz.offgrid.maps.presentation.ui.map.MapLibreMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

/**
 * Hlavní obrazovka mapy v designu Českého klubu turistů (KČT) a Ultra-Pure AMOLED.
 *
 * Vlastnosti:
 * 1. Blok 1: Kompasový směr pohledu (šipka na mapě + světová strana) a nadmořská výška (m n. m.).
 * 2. Barevná identita KČT:
 *    - Horní lišta nese ikonickou pásovou turistickou značku (Bílá - Barva - Bílá).
 *    - 4 ovládací tlačítka mají tenké obrysy v barvách 4 turistických tras KČT
 *      (Červená = REC, Modrá = Oddálit, Zelená = GPS, Žlutá = Přiblížit) na 100% černém AMOLED pozadí.
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapFileManager = remember { MapFileManager(context) }
    val defaultMapFile = remember { mapFileManager.getDefaultMap() }
    val compassManager = remember { CompassSensorManager(context) }

    // Zablokujeme systémové gesto "Zpět" při tažení od kraje displeje
    BackHandler(enabled = true) {
        // Pro opuštění aplikace slouží fyzické tlačítko Domů
    }

    // Kompas běží výhradně při rozsvíceném displeji (0 mW při svěšené ruce)
    DisposableEffect(lifecycleOwner, compassManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> compassManager.start()
                Lifecycle.Event.ON_PAUSE -> compassManager.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            compassManager.stop()
        }
    }

    val isTracking by TrackingManager.isTracking.collectAsState()
    val totalDistanceMeters by TrackingManager.totalDistanceMeters.collectAsState()
    val latestGpsPoint by TrackingManager.currentLocation.collectAsState()
    val trackPoints by TrackingManager.trackPoints.collectAsState()
    val offlineStatus by OfflineRegionHelper.statusText.collectAsState()
    val azimuthDegrees by compassManager.azimuthDegrees.collectAsState()

    var zoomLevel by remember { mutableFloatStateOf(13.0f) }
    var maplibreMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }

    // Výchozí poloha: Jihomoravský kraj (Oslavany: 49.1246, 16.3353)
    var activeLocation by remember { mutableStateOf(LatLng(49.1246, 16.3353)) }

    LaunchedEffect(latestGpsPoint) {
        latestGpsPoint?.let { pt ->
            val newLatLng = LatLng(pt.latitude, pt.longitude)
            activeLocation = newLatLng
            maplibreMapInstance?.moveCamera(
                CameraUpdateFactory.newLatLng(newLatLng)
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            fetchPassiveLastLocation(context)
        }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            fetchPassiveLastLocation(context)
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .focusRequester(focusRequester)
            .focusable()
            .rotaryZoom(
                onZoomIn = {
                    if (zoomLevel < 18.0f) zoomLevel += 0.5f
                },
                onZoomOut = {
                    if (zoomLevel > 6.0f) zoomLevel -= 0.5f
                }
            )
    ) {
        // Nativní vektorová mapa Jihomoravského kraje s kompasovou šipkou
        MapLibreMapView(
            zoomLevel = zoomLevel,
            targetLocation = activeLocation,
            azimuthDegrees = azimuthDegrees,
            trackPoints = trackPoints,
            mbtilesFile = defaultMapFile,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { map ->
                maplibreMapInstance = map
            }
        )

        // Horní informační pilulka se symbolem české turistické značky KČT
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .background(AmoledBlack.copy(alpha = 0.90f), RoundedCornerShape(12.dp))
                .border(1.dp, DarkSurfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Ikonická česká pásová značka KČT (Bílá - Barva - Bílá)
            KctTrailBlazeBadge(
                stripeColor = if (isTracking) TrailRed else TrailGreen
            )
            Spacer(modifier = Modifier.width(6.dp))

            // Text telemetrie: Při záznamu ušlé km, jinak nadmořská výška / stav offline
            val altitudeText = latestGpsPoint?.altitudeMeters?.let { "${it.roundToInt()} m" }
            if (isTracking) {
                val km = totalDistanceMeters / 1000.0
                Text(
                    text = "%.2f km".format(km),
                    color = OutdoorTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = altitudeText ?: offlineStatus,
                    color = OutdoorTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Směr kompasu (S, SV, V, JV, J, JZ, Z, SZ)
            Text(
                text = formatCompassCardinal(azimuthDegrees),
                color = TrailYellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Z%.0f".format(zoomLevel),
                color = OutdoorTextSecondary,
                fontSize = 9.sp
            )
        }

        // Vlevo uprostřed: Oddálit (−) v barvě MODRÉ turistické značky KČT
        Button(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val currentCamZoom = maplibreMapInstance?.cameraPosition?.zoom?.toFloat() ?: zoomLevel
                val nextZoom = (currentCamZoom - 1.0f).coerceAtLeast(6.0f)
                zoomLevel = nextZoom
                maplibreMapInstance?.moveCamera(CameraUpdateFactory.zoomTo(nextZoom.toDouble()))
            },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 5.dp)
                .size(36.dp)
                .border(1.2.dp, TrailBlue, CircleShape),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = AmoledBlack.copy(alpha = 0.88f),
                contentColor = TrailBlue
            )
        ) {
            Text(
                text = "−",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Vpravo uprostřed: Přiblížit (+) v barvě ŽLUTÉ turistické značky KČT
        Button(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val currentCamZoom = maplibreMapInstance?.cameraPosition?.zoom?.toFloat() ?: zoomLevel
                val nextZoom = (currentCamZoom + 1.0f).coerceAtMost(18.0f)
                zoomLevel = nextZoom
                maplibreMapInstance?.moveCamera(CameraUpdateFactory.zoomTo(nextZoom.toDouble()))
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 5.dp)
                .size(36.dp)
                .border(1.2.dp, TrailYellow, CircleShape),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = AmoledBlack.copy(alpha = 0.88f),
                contentColor = TrailYellow
            )
        ) {
            Text(
                text = "+",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Spodní lišta: Záznam trasy (ČERVENÁ KČT) a Poloha (ZELENÁ KČT)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .fillMaxWidth(0.68f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tlačítko záznamu trasy (REC / STOP) v barvě ČERVENÉ turistické značky KČT
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (isTracking) {
                        LocationTrackingService.stop(context)
                    } else {
                        val hasFine = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasFine) {
                            LocationTrackingService.start(context)
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .border(1.5.dp, TrailRed, CircleShape),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isTracking) TrailRed else AmoledBlack.copy(alpha = 0.88f),
                    contentColor = if (isTracking) OutdoorTextPrimary else TrailRed
                )
            ) {
                Text(
                    text = if (isTracking) "STOP" else "REC",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Tlačítko pro vycentrování na aktuální GPS pozici v barvě ZELENÉ turistické značky KČT
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    fetchActiveLocationOnDemand(context)
                    maplibreMapInstance?.moveCamera(
                        CameraUpdateFactory.newLatLng(activeLocation)
                    )
                },
                modifier = Modifier
                    .size(42.dp)
                    .border(1.5.dp, TrailGreen, CircleShape),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AmoledBlack.copy(alpha = 0.88f),
                    contentColor = TrailGreen
                )
            ) {
                Text(
                    text = "GPS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Vykreslí miniaturní českou pásovou značku KČT (Bílý pruh – Barevný pruh – Bílý pruh).
 */
@Composable
private fun KctTrailBlazeBadge(stripeColor: Color) {
    Column(
        modifier = Modifier
            .size(width = 11.dp, height = 9.dp)
            .clip(RoundedCornerShape(1.5.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background( Color.White )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.4f)
                .background( stripeColor )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background( Color.White )
        )
    }
}

/**
 * Převede azimut ve stupních (0..360) na českou zkratku světové strany.
 */
private fun formatCompassCardinal(azimuthDeg: Float): String {
    val directions = arrayOf("S", "SV", "V", "JV", "J", "JZ", "Z", "SZ")
    val index = ((azimuthDeg + 22.5f) / 45.0f).toInt() and 7
    return directions[index]
}

@SuppressLint("MissingPermission")
private fun fetchPassiveLastLocation(context: Context) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.lastLocation.addOnSuccessListener { location ->
        if (location != null) {
            TrackingManager.addPoint(
                TrackPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                    timestampMillis = location.time,
                    accuracyMeters = location.accuracy
                )
            )
        }
    }
}

@SuppressLint("MissingPermission")
private fun fetchActiveLocationOnDemand(context: Context) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
        .addOnSuccessListener { currentLoc ->
            if (currentLoc != null) {
                TrackingManager.addPoint(
                    TrackPoint(
                        latitude = currentLoc.latitude,
                        longitude = currentLoc.longitude,
                        altitudeMeters = if (currentLoc.hasAltitude()) currentLoc.altitude else null,
                        timestampMillis = currentLoc.time,
                        accuracyMeters = currentLoc.accuracy
                    )
                )
            }
        }
}
