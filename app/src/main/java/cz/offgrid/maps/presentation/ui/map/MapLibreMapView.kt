package cz.offgrid.maps.presentation.ui.map

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cz.offgrid.maps.data.map.ContourTileManager
import cz.offgrid.maps.data.map.OfflineRegionHelper
import cz.offgrid.maps.data.map.StyleBuilder
import cz.offgrid.maps.domain.model.TrackPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import kotlinx.coroutines.delay
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

private const val USER_LOCATION_SOURCE_ID = "user_location_source"
private const val USER_LOCATION_LAYER_ID = "user_location_symbol_layer"
private const val USER_LOCATION_ICON_ID = "mapycz_style_location_arrow"

private const val TRACK_LINE_SOURCE_ID = "recorded_track_source"
private const val TRACK_LINE_LAYER_ID = "recorded_track_layer"

private const val PLANNED_ROUTE_SOURCE_ID = "planned_gpx_route_source"
private const val PLANNED_ROUTE_CASING_LAYER_ID = "planned_gpx_route_casing_layer"
private const val PLANNED_ROUTE_LAYER_ID = "planned_gpx_route_layer"

private const val CUSTOM_CONTOURS_SOURCE_ID = "custom_contours_source"
private const val CUSTOM_KCT_TRAILS_SOURCE_ID = "custom_kct_trails_source"
private const val CUSTOM_OUTDOOR_POIS_SOURCE_ID = "custom_outdoor_pois_source"

/**
 * Power-optimized Compose wrapper for MapLibre MapView on Wear OS.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapLibreMapView(
    zoomLevel: Float,
    targetLocation: LatLng,
    azimuthDegrees: Float,
    trackPoints: List<TrackPoint>,
    plannedRoutePoints: List<TrackPoint> = emptyList(),
    mbtilesFile: File?,
    modifier: Modifier = Modifier,
    onUserPanGesture: () -> Unit = {},
    onMapReady: (MapLibreMap) -> Unit = {}
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current

    RememberMapLibreInit(context)

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleLoaded by remember { mutableStateOf(false) }
    var isScreenVisible by remember { mutableStateOf(true) }
    val customContoursGeoJson by ContourTileManager.customContoursGeoJson.collectAsState()
    val customTrailsGeoJson by OfflineRegionHelper.customTrailsGeoJson.collectAsState()
    val customPoisGeoJson by OfflineRegionHelper.customPoisGeoJson.collectAsState()

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            // Key GPU power optimization: cap maximum frame rate at 15 FPS
            setMaximumFps(15)

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                false
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> {
                    isScreenVisible = true
                    mapView.onResume()
                    mapView.setMaximumFps(15)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isScreenVisible = false
                    mapView.onPause()
                }
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            mapInstance = map

            map.uiSettings.apply {
                isAttributionEnabled = false
                isLogoEnabled = false
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
                isFlingVelocityAnimationEnabled = false
                isScaleVelocityAnimationEnabled = false
            }

            // Unlock automatic GPS camera follow when the user pans the map manually
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    onUserPanGesture()
                }
            }

            // Load contour tiles and downloaded trail packages for the active camera viewport once camera movement settles
            map.addOnCameraIdleListener {
                val camPos = map.cameraPosition
                val target = camPos.target ?: return@addOnCameraIdleListener
                ContourTileManager.ensureViewportContours(
                    context = context,
                    centerLat = target.latitude,
                    centerLon = target.longitude,
                    zoom = camPos.zoom,
                    allowNetworkFetch = true
                )
                OfflineRegionHelper.updateTrailsForViewport(context, target.latitude, target.longitude)
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(targetLocation)
                .zoom(zoomLevel.toDouble())
                .build()

            val styleJson = StyleBuilder.createAmoledOutdoorStyle(mbtilesFile)
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->

                // 0. Source and layers for the active planned GPX route
                // Pure black casing (#000000 = 0 mW) + dashed zero-blue amber-orange (#E66800, B = 0,
                // >52% lower OLED power draw than white while remaining distinct from Red/Yellow/Green/Blue trails)
                val plannedSource = GeoJsonSource(PLANNED_ROUTE_SOURCE_ID)
                style.addSource(plannedSource)
                style.addLayer(
                    LineLayer(PLANNED_ROUTE_CASING_LAYER_ID, PLANNED_ROUTE_SOURCE_ID).withProperties(
                        lineColor("#000000"),
                        lineWidth(5.6f),
                        lineCap(Property.LINE_CAP_ROUND),
                        lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )
                style.addLayer(
                    LineLayer(PLANNED_ROUTE_LAYER_ID, PLANNED_ROUTE_SOURCE_ID).withProperties(
                        lineColor("#E66800"),
                        lineWidth(3.0f),
                        lineDasharray(arrayOf(2.2f, 1.8f)),
                        lineCap(Property.LINE_CAP_ROUND),
                        lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )

                // 1. Source and layer for the actively recorded GPX track
                val trackSource = GeoJsonSource(TRACK_LINE_SOURCE_ID)
                style.addSource(trackSource)
                style.addLayer(
                    LineLayer(TRACK_LINE_LAYER_ID, TRACK_LINE_SOURCE_ID).withProperties(
                        lineColor("#E07A00"),
                        lineWidth(3.4f),
                        lineCap(Property.LINE_CAP_ROUND),
                        lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )

                // 2. Icon and SymbolLayer for the location dot and heading chevron
                style.addImage(USER_LOCATION_ICON_ID, createMapyCzStyleLocationBitmap())

                val locationPoint = Point.fromLngLat(targetLocation.longitude, targetLocation.latitude)
                val userSource = GeoJsonSource(USER_LOCATION_SOURCE_ID, Feature.fromGeometry(locationPoint))
                style.addSource(userSource)

                style.addLayer(
                    SymbolLayer(USER_LOCATION_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                        iconImage(USER_LOCATION_ICON_ID),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        iconRotate(azimuthDegrees)
                    )
                )

                isStyleLoaded = true

                if (mbtilesFile == null) {
                    OfflineRegionHelper.ensureSouthMoraviaOffline(context, styleJson)
                }
            }

            onMapReady(map)
        }
    }

    // Update offline contour lines downloaded for regions outside South Moravia
    LaunchedEffect(customContoursGeoJson, isStyleLoaded) {
        val geoJson = customContoursGeoJson
        if (isStyleLoaded && !geoJson.isNullOrEmpty()) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(CUSTOM_CONTOURS_SOURCE_ID)?.let { source ->
                source.setGeoJson(geoJson)
                mapInstance?.triggerRepaint()
                delay(80L)
                mapInstance?.triggerRepaint()
            }
        }
    }

    // Update marked hiking trails downloaded from the GitHub repository (Koncpa/offgrid-maps-data)
    LaunchedEffect(customTrailsGeoJson, isStyleLoaded) {
        val trailsJson = customTrailsGeoJson
        if (isStyleLoaded && !trailsJson.isNullOrEmpty()) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(CUSTOM_KCT_TRAILS_SOURCE_ID)?.let { source ->
                source.setGeoJson(trailsJson)
                mapInstance?.triggerRepaint()
                delay(80L)
                mapInstance?.triggerRepaint()
            }
        }
    }

    // Update outdoor POIs (springs, shelters, huts, saddles, landmarks, rescue)
    LaunchedEffect(customPoisGeoJson, isStyleLoaded) {
        val poisJson = customPoisGeoJson
        if (isStyleLoaded && !poisJson.isNullOrEmpty()) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(CUSTOM_OUTDOOR_POIS_SOURCE_ID)?.let { source ->
                source.setGeoJson(poisJson)
                mapInstance?.triggerRepaint()
                delay(80L)
                mapInstance?.triggerRepaint()
            }
        }
    }

    // Update GPS location dot position (only when screen is interactive -> 0% background GPU work)
    LaunchedEffect(targetLocation, isStyleLoaded, isScreenVisible) {
        if (isStyleLoaded && isScreenVisible) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)?.let { source ->
                val pt = Point.fromLngLat(targetLocation.longitude, targetLocation.latitude)
                source.setGeoJson(Feature.fromGeometry(pt))
            }
        }
    }

    // Update heading chevron rotation on GPU via layer property
    LaunchedEffect(azimuthDegrees, isStyleLoaded, isScreenVisible) {
        if (isStyleLoaded && isScreenVisible) {
            mapInstance?.let { map ->
                map.style?.getLayerAs<SymbolLayer>(USER_LOCATION_LAYER_ID)?.setProperties(
                    iconRotate(azimuthDegrees)
                )
                map.triggerRepaint()
            }
        }
    }

    // Update recorded track polyline (repaints only when screen is visible)
    LaunchedEffect(trackPoints, isStyleLoaded, isScreenVisible) {
        if (isStyleLoaded && isScreenVisible) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(TRACK_LINE_SOURCE_ID)?.let { source ->
                if (trackPoints.size >= 2) {
                    val coords = trackPoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                    val feature = Feature.fromGeometry(LineString.fromLngLats(coords))
                    source.setGeoJson(FeatureCollection.fromFeature(feature))
                } else {
                    source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                }
                mapInstance?.triggerRepaint()
                delay(60L)
                mapInstance?.triggerRepaint()
            }
        }
    }

    // Update planned GPX route polyline
    // Note: MapLibre's C++ worker tessellates 3,000+ points asynchronously (~40-120 ms), so we trigger post-tessellation repaints
    LaunchedEffect(plannedRoutePoints, isStyleLoaded, isScreenVisible) {
        if (isStyleLoaded && isScreenVisible) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(PLANNED_ROUTE_SOURCE_ID)?.let { source ->
                if (plannedRoutePoints.size >= 2) {
                    val coords = plannedRoutePoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                    val feature = Feature.fromGeometry(LineString.fromLngLats(coords))
                    source.setGeoJson(FeatureCollection.fromFeature(feature))
                } else {
                    source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                }
                mapInstance?.triggerRepaint()
                delay(60L)
                mapInstance?.triggerRepaint()
                delay(140L)
                mapInstance?.triggerRepaint()
            }
        }
    }

    // Respond to zoom level changes
    LaunchedEffect(zoomLevel) {
        mapInstance?.let { map ->
            if (kotlin.math.abs(map.cameraPosition.zoom.toFloat() - zoomLevel) > 0.05f) {
                map.moveCamera(CameraUpdateFactory.zoomTo(zoomLevel.toDouble()))
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize()
    )
}

/**
 * Creates an OLED-efficient location dot and directional heading chevron bitmap.
 *
 * The location circle is centered at the geometric midpoint of the bitmap (42, 42) so the dot
 * remains anchored on the exact GPS coordinate while the heading chevron orbits around its rim.
 */
private fun createMapyCzStyleLocationBitmap(): Bitmap {
    val size = 84
    val center = size / 2f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Black high-contrast halo to separate the marker from underlying colored trails (0 mW OLED)
    val darkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#000000")
        style = Paint.Style.FILL
    }

    // Zero-blue warm amber fill (R=224, G=122, B=0)
    val ecoAmberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#E07A00")
        style = Paint.Style.FILL
    }

    // Dark center core to save inner circle subpixels
    val innerCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#121212")
        style = Paint.Style.FILL
    }

    // 1. Directional heading chevron orbiting above the location ring
    val outerArrowPath = Path().apply {
        moveTo(center, 3f)           // Top tip
        lineTo(center + 14.0f, 26f)  // Right wing
        lineTo(center - 14.0f, 26f)  // Left wing
        close()
    }
    canvas.drawPath(outerArrowPath, darkStrokePaint)

    val innerArrowPath = Path().apply {
        moveTo(center, 6.5f)
        lineTo(center + 10.5f, 24f)
        lineTo(center - 10.5f, 24f)
        close()
    }
    canvas.drawPath(innerArrowPath, ecoAmberPaint)

    // 2. Circular location dot anchored at the rotation pivot (center, center)
    canvas.drawCircle(center, center, 14.0f, darkStrokePaint)
    canvas.drawCircle(center, center, 11.0f, ecoAmberPaint)
    canvas.drawCircle(center, center, 4.8f, innerCorePaint)

    return bitmap
}

@Composable
private fun RememberMapLibreInit(context: android.content.Context) {
    remember {
        MapLibre.getInstance(context)
    }
}
