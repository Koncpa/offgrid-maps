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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import cz.offgrid.maps.data.map.MapFileManager
import cz.offgrid.maps.data.map.OfflineRegionHelper
import cz.offgrid.maps.data.tracking.LocationTrackingService
import cz.offgrid.maps.data.tracking.TrackingManager
import cz.offgrid.maps.domain.model.TrackPoint
import cz.offgrid.maps.presentation.theme.*
import cz.offgrid.maps.presentation.ui.map.MapLibreMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Hlavní obrazovka mapy přizpůsobená pro Samsung Galaxy Watch Ultra a celodenní výdrž.
 *
 * Bateriové optimalizace:
 * 1. Žádné animované přelety (moveCamera místo easeCamera -> 1 vykreslený snímek místo 25).
 * 2. Při startu čte pouze pasivní lastLocation (0 mAh spotřeba GPS rádia); aktivní GPS budí až tlačítko GPS nebo REC.
 * 3. Jakmile se stáhne Jihomoravský kraj (OFFLINE), natvrdo odpojí síťovou vrstvu MapLibre (MapLibre.setConnected(false)).
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val mapFileManager = remember { MapFileManager(context) }
    val defaultMapFile = remember { mapFileManager.getDefaultMap() }

    // Zablokujeme systémové gesto "Zpět" při tažení od kraje displeje
    BackHandler(enabled = true) {
        // Ignorujeme nechtěné gesto Zpět při posunu mapy (pro odchod slouží fyzické tlačítko Domů)
    }

    val isTracking by TrackingManager.isTracking.collectAsState()
    val totalDistanceMeters by TrackingManager.totalDistanceMeters.collectAsState()
    val latestGpsPoint by TrackingManager.currentLocation.collectAsState()
    val trackPoints by TrackingManager.trackPoints.collectAsState()
    val offlineStatus by OfflineRegionHelper.statusText.collectAsState()
    val isOfflineReady by OfflineRegionHelper.isOfflineReady.collectAsState()

    var zoomLevel by remember { mutableFloatStateOf(13.0f) }
    var maplibreMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }

    // Výchozí poloha: Jihomoravský kraj (Oslavany: 49.1246, 16.3353)
    var activeLocation by remember { mutableStateOf(LatLng(49.1246, 16.3353)) }

    // Když přijde nový GPS bod, aktualizujeme souřadnice bez náročné animace
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
        // Nativní vektorová mapa Jihomoravského kraje
        MapLibreMapView(
            zoomLevel = zoomLevel,
            targetLocation = activeLocation,
            trackPoints = trackPoints,
            mbtilesFile = defaultMapFile,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { map ->
                maplibreMapInstance = map
            }
        )

        // Horní indikátor: Stav Offline / GPS, ujetá vzdálenost a zoom
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .background(AmoledBlack.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                .border(1.dp, DarkSurfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (isOfflineReady || isTracking) OutdoorLime else OutdoorAmber,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(5.dp))

            if (isTracking) {
                val km = totalDistanceMeters / 1000.0
                Text(
                    text = "%.2f km".format(km),
                    color = OutdoorLime,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = offlineStatus,
                    color = if (isOfflineReady) OutdoorLime else OutdoorAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Z %.0f".format(zoomLevel),
                color = OutdoorTextSecondary,
                fontSize = 10.sp
            )
        }

        // Vlevo uprostřed: Oddálit (−) - jednosnímkový moveCamera (šetří GPU)
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
                .padding(start = 6.dp)
                .size(38.dp)
                .border(1.dp, OutdoorTextSecondary.copy(alpha = 0.4f), CircleShape),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = AmoledBlack.copy(alpha = 0.82f),
                contentColor = OutdoorTextPrimary
            )
        ) {
            Text(
                text = "−",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Vpravo uprostřed: Přiblížit (+) - jednosnímkový moveCamera (šetří GPU)
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
                .padding(end = 6.dp)
                .size(38.dp)
                .border(1.dp, OutdoorAmber.copy(alpha = 0.6f), CircleShape),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = AmoledBlack.copy(alpha = 0.82f),
                contentColor = OutdoorAmber
            )
        ) {
            Text(
                text = "+",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Spodní ergonomická lišta: Záznam trasy (REC) a Vycentrování (GPS)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .fillMaxWidth(0.70f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tlačítko záznamu trasy (REC / STOP)
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
                    .size(44.dp)
                    .border(1.dp, if (isTracking) TrailRed else OutdoorTextSecondary.copy(alpha = 0.4f), CircleShape),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isTracking) TrailRed else AmoledBlack.copy(alpha = 0.85f),
                    contentColor = OutdoorTextPrimary
                )
            ) {
                Text(
                    text = if (isTracking) "STOP" else "REC",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Tlačítko pro vycentrování na aktuální GPS pozici
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    fetchActiveLocationOnDemand(context)
                    maplibreMapInstance?.moveCamera(
                        CameraUpdateFactory.newLatLng(activeLocation)
                    )
                },
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, OutdoorLime.copy(alpha = 0.6f), CircleShape),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AmoledBlack.copy(alpha = 0.85f),
                    contentColor = OutdoorLime
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
 * Pasivně přečte poslední známou polohu ze systému (0 mAh spotřeba, nezapíná GPS čip).
 */
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

/**
 * Vyžádá čerstvou polohu pouze tehdy, když uživatel ručně stiskne tlačítko GPS.
 */
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
