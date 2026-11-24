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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import cz.offgrid.maps.data.map.MapFileManager
import cz.offgrid.maps.data.map.OfflinePresetRegion
import cz.offgrid.maps.data.map.OfflineRegionHelper
import cz.offgrid.maps.data.map.OutdoorPoi
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
import java.util.Locale
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Primary map screen designed around Czech Tourist Club (KCT) trail blazes and Ultra-Pure AMOLED
 * black palette, compatible with all Wear OS smartwatches (with automatic sensor fallback).
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

    // Intercept system edge-swipe Back gesture (closes the GPX/Offline modal if open)
    BackHandler(enabled = true) {
        if (showGpxPicker) {
            showGpxPicker = false
        }
    }

    var isScreenVisible by remember { mutableStateOf(true) }

    // Sensors and live satellite GPS receiver run while the screen is awake (0 mW when wrist is lowered)
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
    val totalDistanceMeters by TrackingManager.totalDistanceMeters.collectAsState()
    val totalAscentMeters by TrackingManager.totalAscentMeters.collectAsState()
    val currentAltitudeMeters by TrackingManager.currentAltitudeMeters.collectAsState()
    val latestGpsPoint by TrackingManager.currentLocation.collectAsState()
    val trackPoints by TrackingManager.trackPoints.collectAsState()
    val plannedRoutePoints by TrackingManager.plannedRoutePoints.collectAsState()
    val plannedRouteName by TrackingManager.plannedRouteName.collectAsState()
    val isOffRoute by TrackingManager.isOffRoute.collectAsState()
    val lastSavedSummary by TrackingManager.lastSavedSummary.collectAsState()
    val offlineStatus by OfflineRegionHelper.statusText.collectAsState()
    val isOfflineReady by OfflineRegionHelper.isOfflineReady.collectAsState()
    val catalogRegions by OfflineRegionHelper.catalogRegions.collectAsState()
    val activeDownloadTitle by OfflineRegionHelper.activeDownloadTitle.collectAsState()
    val downloadError by OfflineRegionHelper.downloadError.collectAsState()
    val savedOfflineRegions by OfflineRegionHelper.savedRegions.collectAsState()
    val azimuthDegrees by compassManager.azimuthDegrees.collectAsState()

    var zoomLevel by remember { mutableFloatStateOf(13.0f) }
    var maplibreMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var dismissOfflinePrompt by remember { mutableStateOf(false) }

    // Haptic alert on the wrist when deviating from the planned GPX route (> 60 m)
    LaunchedEffect(isOffRoute) {
        if (isOffRoute) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // Auto-hide the saved GPX summary banner after 4.5 seconds
    LaunchedEffect(lastSavedSummary) {
        if (lastSavedSummary != null) {
            kotlinx.coroutines.delay(4500L)
            TrackingManager.setSavedSummary(null)
        }
    }

    // When the user pans the map manually (isFollowingGps == false) and is not recording a trek,
    // allow live tile preview (e.g. when browsing another region before downloading it).
    LaunchedEffect(isFollowingGps, isTracking) {
        OfflineRegionHelper.setBrowsingMode(!isFollowingGps && !isTracking)
    }

    // Start on user's last known position (or nearest saved region / fallback)
    var activeLocation by remember {
        val (lat, lon) = OfflineRegionHelper.loadLastKnownCenter(context)
        mutableStateOf(LatLng(lat, lon))
    }

    // Camera follows GPS only while the screen is awake AND the user has not panned away (isFollowingGps)
    LaunchedEffect(latestGpsPoint, isScreenVisible) {
        if (isScreenVisible) {
            latestGpsPoint?.let { pt ->
                val newLatLng = LatLng(pt.latitude, pt.longitude)
                activeLocation = newLatLng
                OfflineRegionHelper.saveLastKnownCenter(context, pt.latitude, pt.longitude)
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
        OfflineRegionHelper.syncCatalogFromGithub(context)
        val recoveredFile = withContext(Dispatchers.IO) {
            GpxExporter.recoverUnfinishedTrackIfAny(context)
        }
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

    // Shared diameter and border thickness for symmetry across all 4 circular buttons
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
        // Native vector map with compass heading arrow, offline overlays, and GPX tracks
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
            onCameraZoomChanged = { camZoom ->
                if (kotlin.math.abs(zoomLevel - camZoom) > 0.05f) {
                    zoomLevel = camZoom.coerceIn(6.0f, 18.0f)
                }
            },
            onMapReady = { map ->
                maplibreMapInstance = map
            }
        )

        // Top status pill with KCT trail blaze badge (always compact, never clipped by the round bezel)
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
                    showGpxPicker = true
                    coroutineScope.launch {
                        availableGpxFiles = withContext(Dispatchers.IO) {
                            GpxExporter.listSavedGpxFiles(context)
                        }
                    }
                    OfflineRegionHelper.refreshSavedRegions(context)
                    OfflineRegionHelper.syncCatalogFromGithub(context)
                }
                .padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Iconic KCT trail blaze badge (Red = active recording, Blue = GPX navigation, Green = idle)
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
        } else if (isTracking) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 31.dp)
                    .background(AmoledBlack.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
                    .border(1.1.dp, TrailRed, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "%.2f km • ↑%d m".format(
                        Locale.US,
                        totalDistanceMeters / 1000.0,
                        totalAscentMeters.roundToInt()
                    ),
                    color = OutdoorTextPrimary,
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

        // Confirmation banner after stopping trek recording and saving the GPX file
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

        // For users without any saved offline region yet, suggest the nearest catalog region by GPS
        if (lastSavedSummary == null &&
            !isOfflineReady &&
            savedOfflineRegions.isEmpty() &&
            activeDownloadTitle == null &&
            offlineStatus == "NO MAP" &&
            !dismissOfflinePrompt &&
            latestGpsPoint != null
        ) {
            val nearestPreset = remember(catalogRegions, activeLocation) {
                catalogRegions.minByOrNull { preset ->
                    TrackingManager.calculateDistanceMeters(
                        activeLocation.latitude, activeLocation.longitude,
                        preset.centerLat, preset.centerLon
                    )
                }
            }
            if (nearestPreset != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 58.dp, start = 22.dp, end = 22.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AmoledBlack.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
                        .border(1.2.dp, TrailBlue, RoundedCornerShape(12.dp))
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            OfflineRegionHelper.downloadPresetRegion(context, nearestPreset)
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "⬇ Get: ${nearestPreset.title}",
                        color = TrailBlue,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "✕",
                        color = OutdoorTextSecondary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { dismissOfflinePrompt = true }
                            .padding(horizontal = 2.dp)
                    )
                }
            }
        }

        // Center-left: Zoom Out (−) styled in KCT BLUE trail color (symmetrical 38.dp)
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

        // Center-right: Zoom In (+) styled in KCT YELLOW trail color (symmetrical 38.dp)
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

        // Bottom bar: Trek recording (KCT RED) and GPS Lock/Center (KCT GREEN)
        // Compact center spacing ensures buttons are never clipped by the round watch bezel
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Trek recording toggle button (REC / STOP) styled in KCT RED trail color
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

            // Satellite lock indicator (Green = satellite fix including forest canopy <= 20 m, Yellow = acquiring satellites)
            val hasStrictGpsLock = latestGpsPoint?.let {
                it.accuracyMeters in 0.1f..LiveGpsManager.FOREST_SAT_ACCURACY_METERS
            } == true
            val gpsIndicatorColor = if (hasStrictGpsLock) TrailGreen else TrailYellow

            // Button for immediate re-centering, forcing a fresh satellite fix, and locking camera follow
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

        // AMOLED modal overlay split into two tabs: 1) GPX Routes, 2) Offline Maps
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
                            // Always load and render the selected route (new list instance forces GeoJsonSource refresh)
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
                    showGpxPicker = false
                    coroutineScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            GpxExporter.shareAndExportGpxFile(context, file)
                        }
                        if (ok) {
                            TrackingManager.setSavedSummary("Saved to Download/OffGridMaps")
                        }
                    }
                },
                onDeleteFile = { file ->
                    if (plannedRouteName == file.nameWithoutExtension) {
                        TrackingManager.setPlannedRoute(null, emptyList())
                    }
                    coroutineScope.launch {
                        availableGpxFiles = withContext(Dispatchers.IO) {
                            GpxExporter.deleteGpxFile(file)
                            GpxExporter.listSavedGpxFiles(context)
                        }
                    }
                },
                onRemoveActiveRoute = {
                    TrackingManager.setPlannedRoute(null, emptyList())
                    isFollowingGps = true
                    maplibreMapInstance?.moveCamera(CameraUpdateFactory.newLatLng(activeLocation))
                    showGpxPicker = false
                },
                onImportGpx = {
                    coroutineScope.launch {
                        availableGpxFiles = withContext(Dispatchers.IO) {
                            GpxExporter.listSavedGpxFiles(context)
                        }
                    }
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
                downloadError = downloadError,
                currentLat = activeLocation.latitude,
                currentLon = activeLocation.longitude,
                onSelectOfflineRegion = { region ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    isFollowingGps = false
                    val regionCenter = LatLng(region.centerLat, region.centerLon)
                    maplibreMapInstance?.moveCamera(CameraUpdateFactory.newLatLngZoom(regionCenter, 12.5))
                    zoomLevel = 12.5f
                    showGpxPicker = false
                },
                onSelectPoi = { poi ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    isFollowingGps = false
                    val poiLatLng = LatLng(poi.lat, poi.lon)
                    maplibreMapInstance?.moveCamera(CameraUpdateFactory.newLatLngZoom(poiLatLng, 15.0))
                    zoomLevel = 15.0f
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
 * Pure black AMOLED overlay menu with three tabs:
 * 1) Routes (remaining route distance/ascent, GPX import, route list, share, delete)
 * 2) POIs (Survival Radar: nearest springs/water, shelters/huts, saddles & landmarks)
 * 3) Offline (Nearby GPS shortcut, saved regions, and 3-level Continent -> Country -> Region catalog)
 */
@Composable
private fun GpxRoutePickerOverlay(
    files: List<File>,
    activeRouteName: String?,
    savedRegions: List<SavedOfflineRegion>,
    offlineStatus: String,
    activeDownloadTitle: String?,
    downloadError: String?,
    currentLat: Double,
    currentLon: Double,
    onSelectFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onDeleteFile: (File) -> Unit,
    onRemoveActiveRoute: () -> Unit,
    onImportGpx: () -> Unit,
    onDownloadPresetRegion: (OfflinePresetRegion) -> Unit,
    onSelectOfflineRegion: (SavedOfflineRegion) -> Unit,
    onSelectPoi: (OutdoorPoi) -> Unit,
    onDeleteOfflineRegion: (SavedOfflineRegion) -> Unit,
    onDismiss: () -> Unit
) {
    // 0 = Routes, 1 = Offline Maps, 2 = Survival POIs
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedContinent by remember { mutableStateOf<String?>(null) }
    var selectedCountry by remember { mutableStateOf<String?>(null) }

    val stepBackOrDismiss = {
        when {
            selectedTab == 1 && selectedCountry != null -> selectedCountry = null
            selectedTab == 1 && selectedContinent != null -> selectedContinent = null
            else -> onDismiss()
        }
    }

    BackHandler(enabled = true) {
        stepBackOrDismiss()
    }

    val catalogRegions by OfflineRegionHelper.catalogRegions.collectAsState()
    val activePois by OfflineRegionHelper.activePois.collectAsState()
    val savedByTitle = remember(savedRegions) {
        savedRegions.associateBy { it.displayName }
    }
    val nearbyRegions = remember(catalogRegions, savedByTitle, currentLat, currentLon) {
        catalogRegions
            .filter { it.title !in savedByTitle }
            .map { preset ->
                val distKm = (TrackingManager.calculateDistanceMeters(
                    currentLat, currentLon, preset.centerLat, preset.centerLon
                ) / 1000.0).roundToInt()
                preset to distKm
            }
            .sortedBy { it.second }
            .take(2)
    }
    val continentOrder = listOf("Europe", "North America", "Australia & NZ")
    val regionsByContinent = remember(catalogRegions) {
        val grouped = catalogRegions.groupBy { it.continent }
        continentOrder.mapNotNull { cont -> grouped[cont]?.let { cont to it } }
    }

    val remainingRouteStats = remember(activeRouteName, currentLat, currentLon) {
        if (activeRouteName != null) {
            TrackingManager.calculateRemainingRouteStats(currentLat, currentLon)
        } else {
            null
        }
    }

    val enrichedPois = remember(activePois, currentLat, currentLon) {
        activePois.map { poi ->
            val distM = TrackingManager.calculateDistanceMeters(
                currentLat, currentLon, poi.lat, poi.lon
            )
            val bearing = TrackingManager.calculateBearingDegrees(
                currentLat, currentLon, poi.lat, poi.lon
            )
            Triple(poi, distM, formatCompassCardinal(bearing))
        }.sortedBy { it.second }
    }
    val nearestWater = remember(enrichedPois) {
        enrichedPois.filter { it.first.category == "water" }.take(4)
    }
    val nearestShelters = remember(enrichedPois) {
        enrichedPois.filter { it.first.category in setOf("shelter", "picnic") }.take(4)
    }
    val nearestLandmarks = remember(enrichedPois) {
        enrichedPois.filter { it.first.category in setOf("viewpoint", "saddle", "landmark", "rescue") }.take(4)
    }

    val isDownloading = offlineStatus.startsWith("DL")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack.copy(alpha = 0.96f))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Top tab switcher: [Routes] | [POIs] | [Offline]
            item(key = "tab_switcher") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                            .padding(horizontal = 9.dp, vertical = 6.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Routes (${files.size})",
                            color = if (isGpxTab) TrailGreen else OutdoorTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val isPoiTab = selectedTab == 2
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isPoiTab) DarkSurface else AmoledBlack)
                            .border(
                                width = if (isPoiTab) 1.8.dp else 1.2.dp,
                                color = if (isPoiTab) TrailYellow else DarkSurfaceVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { selectedTab = 2 }
                            .padding(horizontal = 9.dp, vertical = 6.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "POIs",
                            color = if (isPoiTab) TrailYellow else OutdoorTextSecondary,
                            fontSize = 10.sp,
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
                            .padding(horizontal = 9.dp, vertical = 6.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Offline (${savedRegions.size})",
                            color = if (isOfflineTab) TrailBlue else OutdoorTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (selectedTab == 0) {
                // --- TAB 1: GPX ROUTES ---
                if (activeRouteName != null && remainingRouteStats != null) {
                    item(key = "active_route_stats") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(DarkSurface)
                                .border(1.4.dp, TrailYellow, RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 6.5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val kmStr = "%.1f".format(Locale.US, remainingRouteStats.first)
                            Text(
                                text = "▲ +${remainingRouteStats.second} m · $kmStr km left",
                                color = TrailYellow,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                item(key = "import_gpx_btn") {
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
                }

                if (files.isEmpty()) {
                    item(key = "no_routes") {
                        Text(
                            text = "No saved routes",
                            color = OutdoorTextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(files, key = { it.name }) { file ->
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
            } else if (selectedTab == 2) {
                // --- TAB 3: SURVIVAL RADAR (Nearest Water, Shelters, Saddles & Landmarks) ---
                if (enrichedPois.isEmpty()) {
                    item(key = "no_pois") {
                        Text(
                            text = "No offline POIs in this area yet",
                            color = OutdoorTextSecondary,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    if (nearestWater.isNotEmpty()) {
                        item(key = "poi_water_header") {
                            Text(
                                text = "NEAREST WATER & SPRINGS",
                                color = Color(0xFF00C8FF),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        items(nearestWater, key = { "w_${it.first.label}_${it.first.lat}_${it.first.lon}" }) { (poi, distM, dir) ->
                            SurvivalPoiRow(
                                title = poi.label,
                                distanceMeters = distM,
                                cardinal = dir,
                                accentColor = Color(0xFF00C8FF),
                                onClick = { onSelectPoi(poi) }
                            )
                        }
                    }

                    if (nearestShelters.isNotEmpty()) {
                        item(key = "poi_shelter_header") {
                            Text(
                                text = "NEAREST SHELTERS & PICNIC",
                                color = TrailYellow,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        items(nearestShelters, key = { "s_${it.first.category}_${it.first.label}_${it.first.lat}_${it.first.lon}" }) { (poi, distM, dir) ->
                            SurvivalPoiRow(
                                title = poi.label,
                                distanceMeters = distM,
                                cardinal = dir,
                                accentColor = TrailYellow,
                                onClick = { onSelectPoi(poi) }
                            )
                        }
                    }

                    if (nearestLandmarks.isNotEmpty()) {
                        item(key = "poi_landmark_header") {
                            Text(
                                text = "VIEWPOINTS & LANDMARKS",
                                color = TrailGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        items(nearestLandmarks, key = { "l_${it.first.category}_${it.first.label}_${it.first.lat}_${it.first.lon}" }) { (poi, distM, dir) ->
                            SurvivalPoiRow(
                                title = poi.label,
                                distanceMeters = distM,
                                cardinal = dir,
                                accentColor = TrailGreen,
                                onClick = { onSelectPoi(poi) }
                            )
                        }
                    }
                }
            } else {
                // --- TAB 2: OFFLINE MAPS (3-Level Drill-Down) ---
                if (isDownloading) {
                    item(key = "dl_banner") {
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
                }

                if (!downloadError.isNullOrBlank()) {
                    item(key = "dl_error") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(DarkSurface)
                                .border(1.4.dp, TrailRed, RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⚠ $downloadError",
                                color = TrailRed,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val activeContinent = selectedContinent
                val activeCountry = selectedCountry

                if (activeContinent == null) {
                    // --- LEVEL 0: OFFLINE ROOT (Nearby + Saved + Continents) ---
                    if (nearbyRegions.isNotEmpty()) {
                        item(key = "nearby_header") {
                            Text(
                                text = "NEARBY BY GPS",
                                color = TrailYellow,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        items(nearbyRegions, key = { "nearby_${it.first.id}" }) { (preset, distKm) ->
                            OfflinePresetRow(
                                preset = preset,
                                subtitleOverride = "~$distKm km · ${preset.subtitle}",
                                isSaved = false,
                                isDownloading = isDownloading,
                                accentColor = TrailYellow,
                                onClick = { onDownloadPresetRegion(preset) }
                            )
                        }
                    }

                    item(key = "saved_header") {
                        Text(
                            text = "SAVED ON WATCH (${savedRegions.size})",
                            color = TrailGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    items(savedRegions, key = { "saved_${it.id}" }) { region ->
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

                    item(key = "continents_header") {
                        Text(
                            text = "BROWSE CATALOG (${catalogRegions.size})",
                            color = TrailBlue,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    items(regionsByContinent, key = { "cont_${it.first}" }) { (continentName, contRegions) ->
                        val countryCount = contRegions.map { it.countryName }.distinct().size
                        val savedInCont = contRegions.count { it.title in savedByTitle }
                        val savedInfo = if (savedInCont > 0) " · $savedInCont saved" else ""
                        DrillDownFolderRow(
                            title = continentName,
                            subtitle = "$countryCount countries · ${contRegions.size} regions$savedInfo",
                            onClick = {
                                selectedContinent = continentName
                                selectedCountry = null
                            }
                        )
                    }
                } else if (activeCountry == null) {
                    // --- LEVEL 1: CONTINENT -> COUNTRIES ---
                    item(key = "back_to_continents") {
                        BreadcrumbBackPill(
                            label = "← All Continents",
                            onClick = { selectedContinent = null }
                        )
                    }
                    item(key = "continent_title") {
                        Text(
                            text = activeContinent.uppercase(),
                            color = TrailBlue,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    val countriesInContinent = catalogRegions
                        .filter { it.continent == activeContinent }
                        .groupBy { it.countryName }
                        .entries
                        .toList()

                    items(countriesInContinent, key = { "country_${it.key}" }) { (countryName, cRegions) ->
                        val savedInCountry = cRegions.count { it.title in savedByTitle }
                        val sub = if (savedInCountry > 0) {
                            "${cRegions.size} regions · $savedInCountry saved"
                        } else {
                            "${cRegions.size} regions"
                        }
                        DrillDownFolderRow(
                            title = countryName,
                            subtitle = sub,
                            onClick = { selectedCountry = countryName }
                        )
                    }
                } else {
                    // --- LEVEL 2: COUNTRY -> REGIONS ---
                    item(key = "back_to_countries") {
                        BreadcrumbBackPill(
                            label = "← $activeContinent",
                            onClick = { selectedCountry = null }
                        )
                    }
                    item(key = "country_title") {
                        Text(
                            text = activeCountry.uppercase(),
                            color = TrailBlue,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    val regionsInCountry = catalogRegions.filter { it.countryName == activeCountry }

                    items(regionsInCountry, key = { "reg_${it.id}" }) { preset ->
                        val existingSaved = savedByTitle[preset.title]
                        OfflinePresetRow(
                            preset = preset,
                            subtitleOverride = null,
                            isSaved = existingSaved != null,
                            isDownloading = isDownloading,
                            accentColor = if (existingSaved != null) TrailGreen else TrailBlue,
                            onClick = {
                                if (existingSaved != null) {
                                    onSelectOfflineRegion(existingSaved)
                                } else {
                                    onDownloadPresetRegion(preset)
                                }
                            }
                        )
                    }
                }
            }

            item(key = "bottom_actions") {
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
                            .clickable { stepBackOrDismiss() }
                            .padding(horizontal = 18.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val backLabel = if (selectedTab == 1 && (selectedCountry != null || selectedContinent != null)) {
                            "← Up"
                        } else {
                            "Close"
                        }
                        Text(
                            text = backLabel,
                            color = OutdoorTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbBackPill(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AmoledBlack)
            .border(1.2.dp, OutdoorTextSecondary, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.5.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            color = OutdoorTextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DrillDownFolderRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.2.dp, TrailBlue.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = OutdoorTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = OutdoorTextSecondary,
                fontSize = 8.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(AmoledBlack)
                .border(1.2.dp, TrailBlue, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "›",
                color = TrailBlue,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun OfflinePresetRow(
    preset: OfflinePresetRegion,
    subtitleOverride: String?,
    isSaved: Boolean,
    isDownloading: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.2.dp, accentColor.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .clickable(enabled = isSaved || !isDownloading) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isSaved) "✓ ${preset.title}" else preset.title,
                color = OutdoorTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitleOverride ?: preset.subtitle,
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
                .border(1.2.dp, accentColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isSaved) "✓" else "⬇",
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SurvivalPoiRow(
    title: String,
    distanceMeters: Double,
    cardinal: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    val distStr = if (distanceMeters < 1000.0) {
        "${distanceMeters.roundToInt()} m"
    } else {
        "${"%.1f".format(Locale.US, distanceMeters / 1000.0)} km"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.2.dp, accentColor.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = OutdoorTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$distStr · $cardinal",
                color = accentColor,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(AmoledBlack)
                .border(1.2.dp, accentColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "◎",
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Renders a miniature Czech KCT trail blaze badge (White stripe – Colored stripe – White stripe).
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
 * Converts an azimuth in degrees (0..360) to an 8-wind cardinal direction abbreviation (N, NE, E, SE, S, SW, W, NW).
 */
private fun formatCompassCardinal(azimuthDeg: Float): String {
    val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = ((azimuthDeg + 22.5f) / 45.0f).toInt() and 7
    return directions[index]
}


