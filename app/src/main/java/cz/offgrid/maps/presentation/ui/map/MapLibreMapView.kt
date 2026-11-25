package cz.offgrid.maps.presentation.ui.map

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.res.Configuration
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
    onCameraZoomChanged: (Float) -> Unit = {},
    onMapReady: (MapLibreMap) -> Unit = {}
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCameraZoomChanged by rememberUpdatedState(onCameraZoomChanged)

    RememberMapLibreInit(context)

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleLoaded by remember { mutableStateOf(false) }
    var isScreenVisible by remember { mutableStateOf(true) }
    val customContoursGeoJson by ContourTileManager.customContoursGeoJson.collectAsState()
    val customTrailsUri by OfflineRegionHelper.customTrailsUri.collectAsState()
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
        val memCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    mapView.onLowMemory()
                }
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() {
                mapView.onLowMemory()
            }
        }
        val appContext = context.applicationContext
        appContext.registerComponentCallbacks(memCallbacks)

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
                Lifecycle.Event.ON_STOP -> {
                    mapView.onStop()
                    mapView.onLowMemory()
                }
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            appContext.unregisterComponentCallbacks(memCallbacks)
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
                currentOnCameraZoomChanged(camPos.zoom.toFloat())
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

                // 2. Icons and SymbolLayers for outdoor POIs and the user location dot
                registerOutdoorPoiIcons(style)
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
                    OfflineRegionHelper.initializeOfflineRegions(
                        context = context,
                        centerLat = targetLocation.latitude,
                        centerLon = targetLocation.longitude
                    )
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

    // Update marked hiking trails from streamed file URI so multi-region GeoJSON stays off the Java heap
    LaunchedEffect(customTrailsUri, isStyleLoaded) {
        val trailsUri = customTrailsUri
        if (isStyleLoaded && !trailsUri.isNullOrBlank()) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(CUSTOM_KCT_TRAILS_SOURCE_ID)?.let { source ->
                source.setUri(trailsUri)
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

private data class PoiPaints(
    val haloFill: Paint,
    val fill: Paint,
    val haloStroke: Paint,
    val stroke: Paint
)

/**
 * Registers compact, high-contrast AMOLED vector-drawn POI icons into the active MapLibre style.
 */
private fun registerOutdoorPoiIcons(style: Style) {
    style.addImage("poi_water", createPoiIconBitmap("#00C8FF") { c, p, center ->
        val path = Path().apply {
            moveTo(center, center - 8.5f)
            cubicTo(center + 6.5f, center - 1.5f, center + 6.5f, center + 5.0f, center, center + 8.0f)
            cubicTo(center - 6.5f, center + 5.0f, center - 6.5f, center - 1.5f, center, center - 8.5f)
            close()
        }
        c.drawPath(path, p.haloFill)
        c.drawPath(path, p.fill)
    })

    style.addImage("poi_shelter", createPoiIconBitmap("#FFB300") { c, p, center ->
        val roof = Path().apply {
            moveTo(center - 8.5f, center + 1.0f)
            lineTo(center, center - 7.5f)
            lineTo(center + 8.5f, center + 1.0f)
        }
        c.drawPath(roof, p.haloStroke)
        c.drawLine(center - 5.2f, center + 0.5f, center - 5.2f, center + 7.5f, p.haloStroke)
        c.drawLine(center + 5.2f, center + 0.5f, center + 5.2f, center + 7.5f, p.haloStroke)
        c.drawPath(roof, p.stroke)
        c.drawLine(center - 5.2f, center + 0.5f, center - 5.2f, center + 7.5f, p.stroke)
        c.drawLine(center + 5.2f, center + 0.5f, center + 5.2f, center + 7.5f, p.stroke)
    })

    style.addImage("poi_viewpoint", createPoiIconBitmap("#FFD54F") { c, p, center ->
        c.drawLine(center, center + 4.5f, center - 7.5f, center - 5.5f, p.haloStroke)
        c.drawLine(center, center + 4.5f, center, center - 7.5f, p.haloStroke)
        c.drawLine(center, center + 4.5f, center + 7.5f, center - 5.5f, p.haloStroke)
        c.drawCircle(center, center + 5.0f, 4.4f, p.haloFill)
        c.drawLine(center, center + 4.5f, center - 7.5f, center - 5.5f, p.stroke)
        c.drawLine(center, center + 4.5f, center, center - 7.5f, p.stroke)
        c.drawLine(center, center + 4.5f, center + 7.5f, center - 5.5f, p.stroke)
        c.drawCircle(center, center + 5.0f, 2.7f, p.fill)
    })

    style.addImage("poi_picnic", createPoiIconBitmap("#4CD964") { c, p, center ->
        val lines = listOf(
            floatArrayOf(center - 6.5f, center - 4.0f, center + 6.5f, center - 4.0f),
            floatArrayOf(center - 8.5f, center + 2.0f, center + 8.5f, center + 2.0f),
            floatArrayOf(center - 3.5f, center - 4.0f, center - 6.5f, center + 6.5f),
            floatArrayOf(center + 3.5f, center - 4.0f, center + 6.5f, center + 6.5f)
        )
        lines.forEach { l -> c.drawLine(l[0], l[1], l[2], l[3], p.haloStroke) }
        lines.forEach { l -> c.drawLine(l[0], l[1], l[2], l[3], p.stroke) }
    })

    style.addImage("poi_saddle", createPoiIconBitmap("#C8B88A") { c, p, center ->
        val leftArc = Path().apply { moveTo(center - 7.0f, center - 5.5f); quadTo(center - 1.5f, center, center - 7.0f, center + 5.5f) }
        val rightArc = Path().apply { moveTo(center + 7.0f, center - 5.5f); quadTo(center + 1.5f, center, center + 7.0f, center + 5.5f) }
        c.drawPath(leftArc, p.haloStroke); c.drawPath(rightArc, p.haloStroke)
        c.drawPath(leftArc, p.stroke); c.drawPath(rightArc, p.stroke)
    })

    style.addImage("poi_rescue", createPoiIconBitmap("#FF4444") { c, p, center ->
        c.drawLine(center - 6.5f, center, center + 6.5f, center, p.haloStroke)
        c.drawLine(center, center - 6.5f, center, center + 6.5f, p.haloStroke)
        c.drawLine(center - 6.5f, center, center + 6.5f, center, p.stroke)
        c.drawLine(center, center - 6.5f, center, center + 6.5f, p.stroke)
    })

    style.addImage("poi_landmark", createPoiIconBitmap("#E0E0D8") { c, p, center ->
        val diamond = Path().apply {
            moveTo(center, center - 7.2f)
            lineTo(center + 7.2f, center)
            lineTo(center, center + 7.2f)
            lineTo(center - 7.2f, center)
            close()
        }
        c.drawPath(diamond, p.haloFill)
        c.drawPath(diamond, p.fill)
        c.drawCircle(center, center, 2.2f, p.haloFill)
    })
}

private fun createPoiIconBitmap(
    hexColor: String,
    draw: (Canvas, PoiPaints, Float) -> Unit
): Bitmap {
    val size = 28
    val center = size / 2f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val colorInt = android.graphics.Color.parseColor(hexColor)
    val paints = PoiPaints(
        haloFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.FILL_AND_STROKE
            strokeWidth = 2.6f
            strokeJoin = Paint.Join.ROUND
        },
        fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            style = android.graphics.Paint.Style.FILL
        },
        haloStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5.0f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        },
        stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    )
    draw(canvas, paints, center)
    return bitmap
}

@Composable
private fun RememberMapLibreInit(context: android.content.Context) {
    remember {
        MapLibre.getInstance(context)
    }
}
