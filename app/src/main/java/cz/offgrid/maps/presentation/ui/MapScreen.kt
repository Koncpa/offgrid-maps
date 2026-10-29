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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
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
import cz.offgrid.maps.data.tracking.BarometricAltimeterManager
import cz.offgrid.maps.data.tracking.CompassSensorManager
import cz.offgrid.maps.data.tracking.GpxExporter
import cz.offgrid.maps.data.tracking.LocationTrackingService
import cz.offgrid.maps.data.tracking.TrackingManager
import cz.offgrid.maps.domain.model.TrackPoint
import cz.offgrid.maps.presentation.theme.*
import cz.offgrid.maps.presentation.ui.map.MapLibreMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.io.File
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Hlavní obrazovka mapy v designu Českého klubu turistů (KČT) a Ultra-Pure AMOLED,
 * plně kompatibilní se všemi hodinkami s Wear OS (automatický fallback senzorů).
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
    val altimeterManager = remember { BarometricAltimeterManager(context) }

    var showGpxPicker by remember { mutableStateOf(false) }
    var availableGpxFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    // Zablokujeme systémové gesto "Zpět" při tažení od kraje displeje (pokud je otevřen GPX výběr, zavře ho)
    BackHandler(enabled = true) {
        if (showGpxPicker) {
            showGpxPicker = false
        }
    }

    var isScreenVisible by remember { mutableStateOf(true) }

    // Senzory a živý režim GPS běží výhradně při rozsvíceném displeji (0 mW při svěšené ruce)
    DisposableEffect(lifecycleOwner, compassManager, altimeterManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isScreenVisible = true
                    compassManager.start()
                    altimeterManager.start()
                    LocationTrackingService.notifyScreenState(context, interactive = true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isScreenVisible = false
                    compassManager.stop()
                    altimeterManager.stop()
                    LocationTrackingService.notifyScreenState(context, interactive = false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            compassManager.stop()
            altimeterManager.stop()
        }
    }

    val isTracking by TrackingManager.isTracking.collectAsState()
    val totalDistanceMeters by TrackingManager.totalDistanceMeters.collectAsState()
    val totalAscentMeters by TrackingManager.totalAscentMeters.collectAsState()
    val currentAltitudeMeters by TrackingManager.currentAltitudeMeters.collectAsState()
    val latestGpsPoint by TrackingManager.currentLocation.collectAsState()
    val trackPoints by TrackingManager.trackPoints.collectAsState()
    val plannedRoutePoints by TrackingManager.plannedRoutePoints.collectAsState()
    val plannedRouteName by TrackingManager.plannedRouteName.collectAsState()
    val isOffRoute by TrackingManager.isOffRoute.collectAsState()
    val recordingStartMillis by TrackingManager.recordingStartMillis.collectAsState()
    val lastSavedSummary by TrackingManager.lastSavedSummary.collectAsState()
    val offlineStatus by OfflineRegionHelper.statusText.collectAsState()
    val azimuthDegrees by compassManager.azimuthDegrees.collectAsState()

    var zoomLevel by remember { mutableFloatStateOf(13.0f) }
    var maplibreMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    // Živý vteřinový odpočet běží VÝHRADNĚ při rozsvíceném displeji
    LaunchedEffect(isTracking, recordingStartMillis, isScreenVisible) {
        if (isTracking && recordingStartMillis > 0L) {
            while (isTracking && isScreenVisible) {
                elapsedSeconds = ((System.currentTimeMillis() - recordingStartMillis) / 1000L).coerceAtLeast(0L)
                kotlinx.coroutines.delay(1000L)
            }
        } else if (!isTracking) {
            elapsedSeconds = 0L
        }
    }

    // Haptické upozornění na zápěstí při sejmutí z naplánované GPX trasy (> 60 m)
    LaunchedEffect(isOffRoute) {
        if (isOffRoute) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // Automatické skrytí notifikace o uloženém GPX po 4.5 sekundách
    LaunchedEffect(lastSavedSummary) {
        if (lastSavedSummary != null) {
            kotlinx.coroutines.delay(4500L)
            TrackingManager.setSavedSummary(null)
        }
    }

    // Výchozí poloha: Jihomoravský kraj (Oslavany: 49.1246, 16.3353)
    var activeLocation by remember { mutableStateOf(LatLng(49.1246, 16.3353)) }

    // Kamera se posouvá pouze tehdy, když je displej rozsvícený (šetří GPU na pozadí)
    LaunchedEffect(latestGpsPoint, isScreenVisible) {
        if (isScreenVisible) {
            latestGpsPoint?.let { pt ->
                val newLatLng = LatLng(pt.latitude, pt.longitude)
                activeLocation = newLatLng
                maplibreMapInstance?.moveCamera(
                    CameraUpdateFactory.newLatLng(newLatLng)
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            fetchPassiveLastLocation(context, altimeterManager)
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
            fetchPassiveLastLocation(context, altimeterManager)
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Společný rozměr a tloušťka rámečku pro dokonalou symetrii všech 4 tlačítek
    val buttonSize = 40.dp
    val buttonBorder = 1.5.dp

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
        // Nativní vektorová mapa Jihomoravského kraje s kompasovou šipkou a GPX trasou
        MapLibreMapView(
            zoomLevel = zoomLevel,
            targetLocation = activeLocation,
            azimuthDegrees = azimuthDegrees,
            trackPoints = trackPoints,
            plannedRoutePoints = plannedRoutePoints,
            mbtilesFile = defaultMapFile,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { map ->
                maplibreMapInstance = map
            }
        )

        // Horní informační pilulka se symbolem české turistické značky KČT (klepnutím otevře výběr GPX tras)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AmoledBlack.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .border(
                    width = if (isTracking || isOffRoute) 1.5.dp else 1.dp,
                    color = when {
                        isOffRoute -> TrailRed
                        isTracking -> TrailRed
                        plannedRouteName != null -> TrailBlue
                        else -> DarkSurfaceVariant
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    availableGpxFiles = GpxExporter.listSavedGpxFiles(context)
                    showGpxPicker = true
                }
                .padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Ikonická česká pásová značka KČT (Bílá - Barva - Bílá)
            KctTrailBlazeBadge(
                stripeColor = when {
                    isTracking -> TrailRed
                    plannedRouteName != null -> TrailBlue
                    else -> TrailGreen
                }
            )
            Spacer(modifier = Modifier.width(6.dp))

            // Text telemetrie: Při záznamu živý čas + km + nastoupané m, jinak nadmořská výška
            val displayAltitude = currentAltitudeMeters ?: latestGpsPoint?.altitudeMeters
            val altitudeText = displayAltitude?.let { "${it.roundToInt()} m" }
            if (isTracking) {
                val mins = elapsedSeconds / 60
                val secs = elapsedSeconds % 60
                val km = totalDistanceMeters / 1000.0
                val asc = totalAscentMeters.roundToInt()
                Text(
                    text = "%02d:%02d • %.2f km • ↑%d m".format(mins, secs, km, asc),
                    color = OutdoorTextPrimary,
                    fontSize = 9.5.sp,
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
        }

        // Varování při odchýlení od načtené GPX trasy (> 60 m)
        if (isOffRoute) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .background(AmoledBlack.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
                    .border(1.2.dp, TrailRed, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Mimo GPX trasu (>60 m)",
                    color = TrailRed,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Potvrzení po zastavení záznamu a uložení GPX souboru
        lastSavedSummary?.let { summary ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 54.dp)
                    .background(AmoledBlack.copy(alpha = 0.94f), RoundedCornerShape(10.dp))
                    .border(1.2.dp, TrailGreen, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = summary,
                    color = TrailGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Vlevo uprostřed: Oddálit (−) v barvě MODRÉ turistické značky KČT (symetrických 40.dp)
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
                .size(buttonSize)
                .border(buttonBorder, TrailBlue, CircleShape),
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

        // Vpravo uprostřed: Přiblížit (+) v barvě ŽLUTÉ turistické značky KČT (symetrických 40.dp)
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
                .size(buttonSize)
                .border(buttonBorder, TrailYellow, CircleShape),
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

        // Spodní lišta: Záznam trasy (ČERVENÁ KČT), Měřítko mapy uprostřed a Poloha (ZELENÁ KČT)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .fillMaxWidth(0.70f),
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
                    .size(buttonSize)
                    .border(buttonBorder, TrailRed, CircleShape),
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

            // Kompaktní dynamické měřítko mapy mezi spodními tlačítky
            MapScaleBar(
                latitude = activeLocation.latitude,
                zoomLevel = zoomLevel
            )

            // Tlačítko pro vycentrování na aktuální GPS pozici v barvě ZELENÉ turistické značky KČT
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    fetchActiveLocationOnDemand(context, altimeterManager)
                    maplibreMapInstance?.moveCamera(
                        CameraUpdateFactory.newLatLng(activeLocation)
                    )
                },
                modifier = Modifier
                    .size(buttonSize)
                    .border(buttonBorder, TrailGreen, CircleShape),
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

        // AMOLED modální výběr uložených GPX tras (otevírá se klepnutím na horní lištu)
        if (showGpxPicker) {
            GpxRoutePickerOverlay(
                files = availableGpxFiles,
                activeRouteName = plannedRouteName,
                onSelectFile = { file ->
                    val points = GpxExporter.parseGpxFile(file)
                    if (points.isNotEmpty()) {
                        TrackingManager.setPlannedRoute(file.nameWithoutExtension, points)
                        val startLatLng = LatLng(points.first().latitude, points.first().longitude)
                        activeLocation = startLatLng
                        maplibreMapInstance?.moveCamera(CameraUpdateFactory.newLatLngZoom(startLatLng, 14.0))
                        zoomLevel = 14.0f
                    }
                    showGpxPicker = false
                },
                onClearRoute = {
                    TrackingManager.setPlannedRoute(null, emptyList())
                    showGpxPicker = false
                },
                onDismiss = {
                    showGpxPicker = false
                }
            )
        }
    }
}

/**
 * Kompaktní grafické měřítko mapy (Scale Bar) spočítané ze zeměpisné šířky a úrovně přiblížení.
 */
@Composable
private fun MapScaleBar(
    latitude: Double,
    zoomLevel: Float
) {
    // Metry na 1 dp při dané zeměpisné šířce a zoomu (Web Mercator projekce)
    val metersPerDp = (156543.03392 * cos(Math.toRadians(latitude))) / 2.0.pow(zoomLevel.toDouble())
    val targetMeters = metersPerDp * 34.0 // Cílová šířka měřítka kolem 34 dp

    val niceSteps = intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
    val chosenMeters = niceSteps.minByOrNull { kotlin.math.abs(it - targetMeters) } ?: 200
    val barWidthDp = (chosenMeters / metersPerDp).coerceIn(22.0, 44.0).dp
    val label = if (chosenMeters >= 1000) "${chosenMeters / 1000} km" else "$chosenMeters m"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(AmoledBlack.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = OutdoorTextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(1.dp))
        Box(
            modifier = Modifier
                .width(barWidthDp)
                .height(2.dp)
                .background(OutdoorTextSecondary)
        )
    }
}

/**
 * Čistě černý AMOLED překryvný panel pro výběr GPX trasy k navigaci.
 */
@Composable
private fun GpxRoutePickerOverlay(
    files: List<File>,
    activeRouteName: String?,
    onSelectFile: (File) -> Unit,
    onClearRoute: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack.copy(alpha = 0.96f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "GPX Navigace",
                color = TrailYellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            files.take(3).forEach { file ->
                val isSelected = activeRouteName == file.nameWithoutExtension
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurface)
                        .border(1.dp, if (isSelected) TrailGreen else DarkSurfaceVariant, RoundedCornerShape(10.dp))
                        .clickable { onSelectFile(file) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = file.nameWithoutExtension.replace("_", " "),
                        color = if (isSelected) TrailGreen else OutdoorTextPrimary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (activeRouteName != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, TrailRed, RoundedCornerShape(10.dp))
                            .clickable { onClearRoute() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Skrýt trasu",
                            color = TrailRed,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, OutdoorTextSecondary, RoundedCornerShape(10.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Zpět",
                        color = OutdoorTextPrimary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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
                .background(OutdoorTextPrimary)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.4f)
                .background(stripeColor)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(OutdoorTextPrimary)
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
private fun fetchPassiveLastLocation(
    context: Context,
    altimeterManager: BarometricAltimeterManager
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.lastLocation.addOnSuccessListener { location ->
        if (location != null) {
            val gpsAlt = if (location.hasAltitude()) location.altitude else null
            val gpsAcc = if (location.hasAccuracy()) location.accuracy else null
            altimeterManager.onGpsAltitudeReceived(gpsAlt, gpsAcc)

            TrackingManager.addPoint(
                TrackPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitudeMeters = gpsAlt,
                    timestampMillis = location.time,
                    accuracyMeters = location.accuracy
                )
            )
        }
    }
}

@SuppressLint("MissingPermission")
private fun fetchActiveLocationOnDemand(
    context: Context,
    altimeterManager: BarometricAltimeterManager
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
        .addOnSuccessListener { currentLoc ->
            if (currentLoc != null) {
                val gpsAlt = if (currentLoc.hasAltitude()) currentLoc.altitude else null
                val gpsAcc = if (currentLoc.hasAccuracy()) currentLoc.accuracy else null
                altimeterManager.onGpsAltitudeReceived(gpsAlt, gpsAcc)

                TrackingManager.addPoint(
                    TrackPoint(
                        latitude = currentLoc.latitude,
                        longitude = currentLoc.longitude,
                        altitudeMeters = gpsAlt,
                        timestampMillis = currentLoc.time,
                        accuracyMeters = currentLoc.accuracy
                    )
                )
            }
        }
}
