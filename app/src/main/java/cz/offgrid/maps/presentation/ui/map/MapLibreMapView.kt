package cz.offgrid.maps.presentation.ui.map

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val USER_LOCATION_SOURCE_ID = "user_location_source"
private const val USER_LOCATION_HALO_LAYER_ID = "user_location_halo_layer"
private const val USER_LOCATION_DOT_LAYER_ID = "user_location_dot_layer"

private const val USER_HEADING_SOURCE_ID = "user_heading_source"
private const val USER_HEADING_LAYER_ID = "user_heading_layer"

private const val TRACK_LINE_SOURCE_ID = "recorded_track_source"
private const val TRACK_LINE_LAYER_ID = "recorded_track_layer"

/**
 * Energeticky vysoce optimalizovaný Compose wrapper pro MapLibre MapView na Wear OS.
 *
 * Zobrazuje:
 * 1. Ultra-Pure AMOLED vektorovou mapu + KČT trasy.
 * 2. Zelený GPS bod polohy + Směrový kompasový kužel (kam míří ruka na rozcestí).
 * 3. Zaznamenanou stopu túry.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapLibreMapView(
    zoomLevel: Float,
    targetLocation: LatLng,
    azimuthDegrees: Float,
    trackPoints: List<TrackPoint>,
    mbtilesFile: File?,
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    RememberMapLibreInit(context)

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleLoaded by remember { mutableStateOf(false) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            // Klíčová úspora GPU: Omezíme maximální FPS na 15 snímků za sekundu
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
                    mapView.onResume()
                    mapView.setMaximumFps(15)
                }
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
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

            map.cameraPosition = CameraPosition.Builder()
                .target(targetLocation)
                .zoom(zoomLevel.toDouble())
                .build()

            val styleJson = StyleBuilder.createAmoledOutdoorStyle(mbtilesFile)
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                // 1. Zdroj a vrstva pro zaznamenanou GPX trasu
                val trackSource = GeoJsonSource(TRACK_LINE_SOURCE_ID)
                style.addSource(trackSource)
                style.addLayer(
                    LineLayer(TRACK_LINE_LAYER_ID, TRACK_LINE_SOURCE_ID).withProperties(
                        lineColor("#FFFFFF"),
                        lineWidth(3.5f)
                    )
                )

                // 2. Zdroj a vrstva pro kompasový kužel (směr pohledu)
                val headingSource = GeoJsonSource(
                    USER_HEADING_SOURCE_ID,
                    createHeadingArrowFeature(targetLocation, azimuthDegrees, zoomLevel)
                )
                style.addSource(headingSource)
                style.addLayer(
                    LineLayer(USER_HEADING_LAYER_ID, USER_HEADING_SOURCE_ID).withProperties(
                        lineColor("#00D166"),
                        lineWidth(2.5f),
                        lineCap(Property.LINE_CAP_ROUND),
                        lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )

                // 3. Zdroj a vrstva pro aktuální polohu (GPS tečka v barvě KČT zelené s bílým středem)
                val locationPoint = Point.fromLngLat(targetLocation.longitude, targetLocation.latitude)
                val userSource = GeoJsonSource(USER_LOCATION_SOURCE_ID, Feature.fromGeometry(locationPoint))
                style.addSource(userSource)

                style.addLayer(
                    CircleLayer(USER_LOCATION_HALO_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                        circleRadius(9f),
                        circleColor("#3300D166")
                    )
                )
                style.addLayer(
                    CircleLayer(USER_LOCATION_DOT_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                        circleRadius(5.0f),
                        circleColor("#00D166"),
                        circleStrokeColor("#FFFFFF"),
                        circleStrokeWidth(2.0f)
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

    // Aktualizace pozice GPS tečky a směrové šipky kompasu
    LaunchedEffect(targetLocation, azimuthDegrees, zoomLevel, isStyleLoaded) {
        if (isStyleLoaded) {
            val map = mapInstance ?: return@LaunchedEffect
            val currentZoom = map.cameraPosition.zoom.toFloat()

            map.style?.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)?.let { source ->
                val pt = Point.fromLngLat(targetLocation.longitude, targetLocation.latitude)
                source.setGeoJson(Feature.fromGeometry(pt))
            }

            map.style?.getSourceAs<GeoJsonSource>(USER_HEADING_SOURCE_ID)?.let { source ->
                source.setGeoJson(createHeadingArrowFeature(targetLocation, azimuthDegrees, currentZoom))
            }
        }
    }

    // Aktualizace vykreslené stopy trasy
    LaunchedEffect(trackPoints, isStyleLoaded) {
        if (isStyleLoaded && trackPoints.size >= 2) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(TRACK_LINE_SOURCE_ID)?.let { source ->
                val coords = trackPoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(coords)))
            }
        }
    }

    // Reakce na změnu zoomu
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
 * Vypočítá směrovou šipku (kužel pohledu) vycházející z pozice uživatele ve směru kompasu.
 * Délka šipky se automaticky přizpůsobuje aktuálnímu přiblížení mapy (zoomu).
 */
private fun createHeadingArrowFeature(location: LatLng, azimuthDeg: Float, zoom: Float): Feature {
    // Měřítko délky šipky podle zoomu (aby na obrazovce měla vždy cca 24 pixelů)
    val baseSpanDeg = 0.0022 * 2.0.pow((13.0 - zoom.toDouble()).coerceIn(-5.0, 5.0))
    val latRad = Math.toRadians(location.latitude)
    val lonScale = 1.0 / cos(latRad).coerceAtLeast(0.2)

    val radCenter = Math.toRadians(azimuthDeg.toDouble())
    val tipLat = location.latitude + baseSpanDeg * cos(radCenter)
    val tipLon = location.longitude + baseSpanDeg * sin(radCenter) * lonScale

    // Křídla šipky (zpět od špičky pod úhlem +-145 stupňů)
    val wingSpan = baseSpanDeg * 0.42
    val radLeft = Math.toRadians((azimuthDeg + 145f).toDouble())
    val radRight = Math.toRadians((azimuthDeg - 145f).toDouble())

    val leftLat = tipLat + wingSpan * cos(radLeft)
    val leftLon = tipLon + wingSpan * sin(radLeft) * lonScale

    val rightLat = tipLat + wingSpan * cos(radRight)
    val rightLon = tipLon + wingSpan * sin(radRight) * lonScale

    val points = listOf(
        Point.fromLngLat(location.longitude, location.latitude),
        Point.fromLngLat(tipLon, tipLat),
        Point.fromLngLat(leftLon, leftLat),
        Point.fromLngLat(tipLon, tipLat),
        Point.fromLngLat(rightLon, rightLat)
    )
    return Feature.fromGeometry(LineString.fromLngLats(points))
}

@Composable
private fun RememberMapLibreInit(context: android.content.Context) {
    remember {
        MapLibre.getInstance(context)
    }
}
