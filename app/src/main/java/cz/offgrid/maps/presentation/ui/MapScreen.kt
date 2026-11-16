package cz.offgrid.maps.presentation.ui

import android.Manifest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import cz.offgrid.maps.data.map.MapFileManager
import cz.offgrid.maps.data.map.OfflinePresetRegion
import cz.offgrid.maps.data.map.OfflineRegionHelper
import cz.offgrid.maps.data.map.SavedOfflineRegion
import cz.offgrid.maps.data.tracking.BarometricAltimeterManager
import cz.offgrid.maps.data.tracking.CompassSensorManager
import cz.offgrid.maps.data.tracking.GpxExporter
import cz.offgrid.maps.data.tracking.LiveGpsManager
import cz.offgrid.maps.data.tracking.LocationTrackingService
import cz.offgrid.maps.data.tracking.TrackingManager
import cz.offgrid.maps.presentation.theme.*
import cz.offgrid.maps.presentation.ui.map.MapLibreMapView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val coroutineScope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapFileManager = remember { MapFileManager(context) }
    val defaultMapFile = remember { mapFileManager.getDefaultMap() }
    val compassManager = remember { CompassSensorManager(context) }
    val altimeterManager = remember { BarometricAltimeterManager(context) }
    val liveGpsManager = remember { LiveGpsManager(context, altimeterManager) }

    var showGpxPicker by remember { mutableStateOf(false) }
    var availableGpxFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isFollowingGps by remember { mutableStateOf(true) }

    // Zablokujeme systémové gesto "Zpět" při tažení od kraje displeje (pokud je otevřen GPX výběr, zavře ho)
    BackHandler(enabled = true) {
        if (showGpxPicker) {
            showGpxPicker = false
        }
    }

    var isScreenVisible by remember { mutableStateOf(true) }

    // Senzory a živý satelitní GPS přijímač běží při rozsvíceném displeji (0 mW při svěšené ruce)
    DisposableEffect(lifecycleOwner, compassManager, altimeterManager, liveGpsManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isScreenVisible = true
                    compassManager.start()
                    altimeterManager.start()
                    liveGpsManager.start()
                    LocationTrackingService.notifyScreenState(context, interactive = true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isScreenVisible = false
                    compassManager.stop()
                    altimeterManager.stop()
                    liveGpsManager.stop()
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
            liveGpsManager.stop()
        }
    }

    val isTracking by TrackingManager.isTracking.collectAsState()
    val currentAltitudeMeters by TrackingManager.currentAltitudeMeters.collectAsState()
    val latestGpsPoint by TrackingManager.currentLocation.collectAsState()
    val trackPoints by TrackingManager.trackPoints.collectAsState()
    val plannedRoutePoints by TrackingManager.plannedRoutePoints.collectAsState()
    val plannedRouteName by TrackingManager.plannedRouteName.collectAsState()
    val isOffRoute by TrackingManager.isOffRoute.collectAsState()
    val lastSavedSummary by TrackingManager.lastSavedSummary.collectAsState()
    val offlineStatus by OfflineRegionHelper.statusText.collectAsState()
    val activeDownloadTitle by OfflineRegionHelper.activeDownloadTitle.collectAsState()
    val savedOfflineRegions by OfflineRegionHelper.savedRegions.collectAsState()
    val azimuthDegrees by compassManager.azimuthDegrees.collectAsState()

    var zoomLevel by remember { mutableFloatStateOf(13.0f) }
    var maplibreMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }

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

    // Když si uživatel posouvá mapu prstem (isFollowingGps == false) a nenahrává trasu,
    // povolíme živý náhled dlaždic (např. při hledání oblasti v Čechách ke stažení).
    LaunchedEffect(isFollowingGps, isTracking) {
        OfflineRegionHelper.setBrowsingMode(!isFollowingGps && !isTracking)
    }

    // Výchozí poloha: Jihomoravský kraj (Oslavany: 49.1246, 16.3353)
    var activeLocation by remember { mutableStateOf(LatLng(49.1246, 16.3353)) }

    // Kamera sleduje GPS pouze tehdy, když je displej rozsvícený A uživatel neposunul mapu prstem (isFollowingGps)
    LaunchedEffect(latestGpsPoint, isScreenVisible) {
        if (isScreenVisible) {
            latestGpsPoint?.let { pt ->
                val newLatLng = LatLng(pt.latitude, pt.longitude)
                activeLocation = newLatLng
                if (isFollowingGps) {
                    maplibreMapInstance?.moveCamera(
                        CameraUpdateFactory.newLatLng(newLatLng)
                    )
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            liveGpsManager.start()
            liveGpsManager.requestImmediateHighAccuracyFix()
        }
    }

    val gpxImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val (importedFile, points) = withContext(Dispatchers.IO) {
                    val file = GpxExporter.importGpxFromUri(context, uri)
                    val pts = if (file != null) GpxExporter.parseGpxFile(file) else emptyList()
                    file to pts
                }
                if (importedFile != null) {
                    if (points.isNotEmpty()) {
                        TrackingManager.setPlannedRoute(importedFile.nameWithoutExtension, points)
                        isFollowingGps = false
                        val startLatLng = LatLng(points.first().latitude, points.first().longitude)
                        maplibreMapInstance?.moveCamera(CameraUpdateFactory.newLatLngZoom(startLatLng, 14.0))
                        zoomLevel = 14.0f
                        TrackingManager.setSavedSummary("Loaded: ${importedFile.nameWithoutExtension}")
                    }
                    availableGpxFiles = withContext(Dispatchers.IO) { GpxExporter.listSavedGpxFiles(context) }
                    showGpxPicker = false
                }
            }
        }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        val recoveredFile = GpxExporter.recoverUnfinishedTrackIfAny(context)
        if (recoveredFile != null) {
            TrackingManager.setSavedSummary("Track recovered")
        }

        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            liveGpsManager.start()
            liveGpsManager.requestImmediateHighAccuracyFix()
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
    val buttonSize = 38.dp
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
            onUserPanGesture = {
                isFollowingGps = false
            },
            onMapReady = { map ->
                maplibreMapInstance = map
            }
        )

        // Horní informační pilulka se symbolem české turistické značky KČT (vždy kompaktní, nikdy neoříznutá)
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
                    OfflineRegionHelper.refreshSavedRegions(context)
                    OfflineRegionHelper.syncCatalogFromGithub(context)
                    showGpxPicker = true
                }
                .padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Ikonická česká pásová značka KČT (Červená = aktivní záznam, Modrá = GPX navigace, Zelená = klidový stav)
            KctTrailBlazeBadge(
                stripeColor = when {
                    isTracking -> TrailRed
                    plannedRouteName != null -> TrailBlue
                    else -> TrailGreen
                }
            )
            Spacer(modifier = Modifier.width(6.dp))

            val displayAltitude = currentAltitudeMeters ?: latestGpsPoint?.altitudeMeters
            val altitudeText = displayAltitude?.let { "▲ ${it.roundToInt()} m" }
            val pillLabel = if (offlineStatus.startsWith("DL")) {
                offlineStatus
            } else {
                altitudeText ?: offlineStatus
            }
            Text(
                text = pillLabel,
                color = OutdoorTextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = formatCompassCardinal(azimuthDegrees),
                color = TrailYellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (isOffRoute) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 31.dp)
                    .background(AmoledBlack.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
                    .border(1.2.dp, TrailRed, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Off route (>60 m)",
                    color = TrailRed,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 30.dp)
            ) {
                MapScaleBar(
                    latitude = activeLocation.latitude,
                    zoomLevel = zoomLevel
                )
            }
        }

        // Potvrzení po zastavení záznamu a uložení GPX souboru
        lastSavedSummary?.let { summary ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 58.dp)
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

        // Vlevo uprostřed: Oddálit (−) v barvě MODRÉ turistické značky KČT (symetrických 38.dp)
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
                .padding(start = 8.dp)
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

        // Vpravo uprostřed: Přiblížit (+) v barvě ŽLUTÉ turistické značky KČT (symetrických 38.dp)
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
                .padding(end = 8.dp)
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

        // Spodní lišta: Záznam trasy (ČERVENÁ KČT) a Poloha (ZELENÁ KČT)
        // Kompaktní rozestup uprostřed zaručuje, že tlačítka nejsou nikdy oříznuta kulatou lunetou displeje
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
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

            // Indikátor satelitního zaměření (Zelená = satelitní fix včetně lesa <= 20 m, Žlutá = hledá satelity)
            val hasStrictGpsLock = latestGpsPoint?.let {
                it.accuracyMeters in 0.1f..LiveGpsManager.FOREST_SAT_ACCURACY_METERS
            } == true
            val gpsIndicatorColor = if (hasStrictGpsLock) TrailGreen else TrailYellow

            // Tlačítko pro okamžité vycentrování, vynucení čerstvého satelitního fixu a uzamčení sledování
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    isFollowingGps = true
                    LocationTrackingService.flushNow(context)
                    liveGpsManager.start()
                    liveGpsManager.requestImmediateHighAccuracyFix()
                    val latest = TrackingManager.currentLocation.value
                    val targetLatLng = if (latest != null) {
                        LatLng(latest.latitude, latest.longitude)
                    } else {
                        activeLocation
                    }
                    activeLocation = targetLatLng
                    maplibreMapInstance?.moveCamera(
                        CameraUpdateFactory.newLatLng(targetLatLng)
                    )
                },
                modifier = Modifier
                    .size(buttonSize)
                    .border(
                        width = if (isFollowingGps) 2.0.dp else buttonBorder,
                        color = gpsIndicatorColor,
                        shape = CircleShape
                    ),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AmoledBlack.copy(alpha = 0.88f),
                    contentColor = if (isFollowingGps) gpsIndicatorColor else OutdoorTextSecondary
                )
            ) {
                Text(
                    text = "GPS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // AMOLED modální menu rozdělené na dvě přehledné záložky: 1) Trasy GPX, 2) Offline mapy
        if (showGpxPicker) {
            GpxRoutePickerOverlay(
                files = availableGpxFiles,
                activeRouteName = plannedRouteName,
                savedRegions = savedOfflineRegions,
                offlineStatus = offlineStatus,
                onSelectFile = { file ->
                    showGpxPicker = false
                    coroutineScope.launch {
                        val currentLoc = activeLocation
                        val (points, closestPt) = withContext(Dispatchers.IO) {
                            val pts = GpxExporter.parseGpxFile(file)
                            val closest = pts.minByOrNull { pt ->
                                TrackingManager.calculateDistanceMeters(
                                    currentLoc.latitude, currentLoc.longitude,
                                    pt.latitude, pt.longitude
                                )
                            } ?: pts.firstOrNull()
                            pts to closest
                        }
                        if (points.isNotEmpty() && closestPt != null) {
                            // Vždy načteme a vykreslíme vybranou trasu (nová instance listu vynutí překreslení GeoJsonSource)
                            TrackingManager.setPlannedRoute(null, emptyList())
                            TrackingManager.setPlannedRoute(file.nameWithoutExtension, points.toList())
                            isFollowingGps = false

                            val focusLatLng = LatLng(closestPt.latitude, closestPt.longitude)
                            maplibreMapInstance?.moveCamera(CameraUpdateFactory.newLatLngZoom(focusLatLng, 13.5))
                            zoomLevel = 13.5f
                        }
                    }
                },
                onShareFile = { file ->
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val ok = GpxExporter.shareAndExportGpxFile(context, file)
                    if (ok) {
                        TrackingManager.setSavedSummary("Saved to Download/OffGridMaps")
                    }
                    showGpxPicker = false
                },
                onDeleteFile = { file ->
                    if (plannedRouteName == file.nameWithoutExtension) {
                        TrackingManager.setPlannedRoute(null, emptyList())
                    }
                    GpxExporter.deleteGpxFile(file)
                    availableGpxFiles = GpxExporter.listSavedGpxFiles(context)
                },
                onRemoveActiveRoute = {
                    TrackingManager.setPlannedRoute(null, emptyList())
                    isFollowingGps = true
                    maplibreMapInstance?.moveCamera(CameraUpdateFactory.newLatLng(activeLocation))
                    showGpxPicker = false
                },
                onImportGpx = {
                    availableGpxFiles = GpxExporter.listSavedGpxFiles(context)
                    try {
                        gpxImportLauncher.launch(arrayOf("*/*"))
                    } catch (_: Exception) {
                        TrackingManager.setSavedSummary("Place .gpx in Download folder")
                        showGpxPicker = false
                    }
                },
                onDownloadPresetRegion = { preset ->
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    isFollowingGps = false
                    val presetCenter = LatLng(preset.centerLat, preset.centerLon)
                    maplibreMapInstance?.moveCamera(CameraUpdateFactory.newLatLngZoom(presetCenter, 12.5))
                    zoomLevel = 12.5f
                    OfflineRegionHelper.downloadPresetRegion(context, preset)
                },
                activeDownloadTitle = activeDownloadTitle,
                onSelectOfflineRegion = { region ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    isFollowingGps = false
                    val regionCenter = LatLng(region.centerLat, region.centerLon)
                    maplibreMapInstance?.moveCamera(CameraUpdateFactory.newLatLngZoom(regionCenter, 12.5))
                    zoomLevel = 12.5f
                    showGpxPicker = false
                },
                onDeleteOfflineRegion = { region ->
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    OfflineRegionHelper.deleteOfflineRegion(context, region.id)
                },
                onDismiss = {
                    showGpxPicker = false
                }
            )
        }
    }
}

/**
 * Compact cartographic scale bar calculated from latitude and zoom level.
 */
@Composable
private fun MapScaleBar(
    latitude: Double,
    zoomLevel: Float
) {
    val metersPerDp = (156543.03392 * cos(Math.toRadians(latitude))) / 2.0.pow(zoomLevel.toDouble())
    val targetMeters = metersPerDp * 34.0

    val niceSteps = intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
    val chosenMeters = niceSteps.minByOrNull { kotlin.math.abs(it - targetMeters) } ?: 200
    val barWidthDp = (chosenMeters / metersPerDp).coerceIn(22.0, 44.0).dp
    val label = if (chosenMeters >= 1000) "${chosenMeters / 1000} km" else "$chosenMeters m"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(AmoledBlack.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .height(5.dp)
                    .background(OutdoorTextSecondary)
            )
            Box(
                modifier = Modifier
                    .width(barWidthDp)
                    .height(1.8.dp)
                    .background(OutdoorTextSecondary)
            )
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .height(5.dp)
                    .background(OutdoorTextSecondary)
            )
        }
        Text(
            text = label,
            color = OutdoorTextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Pure black AMOLED overlay menu with two tabs:
 * 1) Routes (GPX import, route list, export/share, delete, clear active route)
 * 2) Offline (saved regions + multi-country GitHub catalog for 1-click download)
 */
@Composable
private fun GpxRoutePickerOverlay(
    files: List<File>,
    activeRouteName: String?,
    savedRegions: List<SavedOfflineRegion>,
    offlineStatus: String,
    activeDownloadTitle: String?,
    onSelectFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onDeleteFile: (File) -> Unit,
    onRemoveActiveRoute: () -> Unit,
    onImportGpx: () -> Unit,
    onDownloadPresetRegion: (OfflinePresetRegion) -> Unit,
    onSelectOfflineRegion: (SavedOfflineRegion) -> Unit,
    onDeleteOfflineRegion: (SavedOfflineRegion) -> Unit,
    onDismiss: () -> Unit
) {
    // 0 = Routes, 1 = Offline Maps
    var selectedTab by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack.copy(alpha = 0.96f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(vertical = 8.dp)
        ) {
            // Top tab switcher: [Routes] | [Offline]
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isGpxTab = selectedTab == 0
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isGpxTab) DarkSurface else AmoledBlack)
                        .border(
                            width = if (isGpxTab) 1.8.dp else 1.2.dp,
                            color = if (isGpxTab) TrailGreen else DarkSurfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { selectedTab = 0 }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Routes (${files.size})",
                        color = if (isGpxTab) TrailGreen else OutdoorTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                val isOfflineTab = selectedTab == 1
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isOfflineTab) DarkSurface else AmoledBlack)
                        .border(
                            width = if (isOfflineTab) 1.8.dp else 1.2.dp,
                            color = if (isOfflineTab) TrailBlue else DarkSurfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { selectedTab = 1 }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Offline (${savedRegions.size})",
                        color = if (isOfflineTab) TrailBlue else OutdoorTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (selectedTab == 0) {
                // --- TAB 1: GPX ROUTES ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkSurface)
                        .border(1.5.dp, TrailGreen, RoundedCornerShape(14.dp))
                        .clickable { onImportGpx() }
                        .padding(horizontal = 12.dp, vertical = 8.5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+ Import GPX",
                        color = TrailGreen,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (files.isEmpty()) {
                    Text(
                        text = "No saved routes",
                        color = OutdoorTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    files.forEach { file ->
                        val isSelected = activeRouteName == file.nameWithoutExtension
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(DarkSurface)
                                .border(
                                    width = if (isSelected) 1.6.dp else 1.2.dp,
                                    color = if (isSelected) TrailGreen else DarkSurfaceVariant,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectFile(file) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = file.nameWithoutExtension.replace("_", " "),
                                color = if (isSelected) TrailGreen else OutdoorTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(AmoledBlack)
                                    .border(1.2.dp, TrailBlue, CircleShape)
                                    .clickable { onShareFile(file) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "↗",
                                    color = TrailBlue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(AmoledBlack)
                                    .border(1.2.dp, TrailRed, CircleShape)
                                    .clickable { onDeleteFile(file) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✕",
                                    color = TrailRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                // --- TAB 2: OFFLINE MAPS ---
                val isDownloading = offlineStatus.startsWith("DL")

                if (isDownloading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(DarkSurface)
                            .border(1.6.dp, TrailYellow, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val dlLabel = if (!activeDownloadTitle.isNullOrBlank()) {
                            "⏳ $activeDownloadTitle ($offlineStatus)"
                        } else {
                            "⏳ Downloading ($offlineStatus)"
                        }
                        Text(
                            text = dlLabel,
                            color = TrailYellow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 1) Downloaded offline regions stored on the watch
                Text(
                    text = "SAVED ON WATCH",
                    color = TrailGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )

                savedRegions.forEach { region ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(DarkSurface)
                            .border(
                                width = 1.3.dp,
                                color = if (region.isDefaultJmk) TrailYellow.copy(alpha = 0.85f) else TrailGreen.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectOfflineRegion(region) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (region.isDefaultJmk) "★ ${region.displayName}" else "✓ ${region.displayName}",
                            color = OutdoorTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!region.isDefaultJmk) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(AmoledBlack)
                                    .border(1.2.dp, TrailRed, CircleShape)
                                    .clickable { onDeleteOfflineRegion(region) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✕",
                                    color = TrailRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 2) Multi-country catalog loaded from GitHub (Koncpa/offgrid-maps-data)
                val catalogRegions by OfflineRegionHelper.catalogRegions.collectAsState()
                val savedTitles = remember(savedRegions) {
                    savedRegions.map { it.displayName }.toSet()
                }
                val availableByCountry = remember(savedTitles, catalogRegions) {
                    catalogRegions
                        .filter { it.title !in savedTitles }
                        .groupBy { it.countryName }
                }

                availableByCountry.forEach { (countryHeader, regions) ->
                    Text(
                        text = countryHeader.uppercase(),
                        color = TrailBlue,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    regions.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(DarkSurface)
                                .border(1.2.dp, TrailBlue.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                                .clickable(enabled = !isDownloading) { onDownloadPresetRegion(preset) }
                                .padding(horizontal = 10.dp, vertical = 6.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.title,
                                    color = OutdoorTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = preset.subtitle,
                                    color = OutdoorTextSecondary,
                                    fontSize = 8.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(AmoledBlack)
                                    .border(1.2.dp, TrailBlue, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "⬇",
                                    color = TrailBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                if (selectedTab == 0 && activeRouteName != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(DarkSurface)
                            .border(1.5.dp, TrailYellow, RoundedCornerShape(14.dp))
                            .clickable { onRemoveActiveRoute() }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Clear route",
                            color = TrailYellow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkSurface)
                        .border(1.5.dp, OutdoorTextSecondary, RoundedCornerShape(14.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Back",
                        color = OutdoorTextPrimary,
                        fontSize = 11.sp,
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
 * Převede azimut ve stupních (0..360) na mezinárodní anglickou zkratku světové strany (N, NE, E, SE, S, SW, W, NW).
 */
private fun formatCompassCardinal(azimuthDeg: Float): String {
    val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = ((azimuthDeg + 22.5f) / 45.0f).toInt() and 7
    return directions[index]
}


