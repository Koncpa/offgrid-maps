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
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

private const val USER_LOCATION_SOURCE_ID = "user_location_source"
private const val USER_LOCATION_HALO_LAYER_ID = "user_location_halo_layer"
private const val USER_LOCATION_DOT_LAYER_ID = "user_location_dot_layer"

private const val TRACK_LINE_SOURCE_ID = "recorded_track_source"
private const val TRACK_LINE_LAYER_ID = "recorded_track_layer"

/**
 * Energeticky vysoce optimalizovaný Compose wrapper pro MapLibre MapView na Wear OS.
 *
 * Úspora baterie pro celodenní túru:
 * 1. Limit snímkové frekvence: setMaximumFps(15) snižuje odběr GPU o ~75 % oproti 60 FPS.
 * 2. Jednosnímkové překreslení: Používá moveCamera() místo animovaného easeCamera() (1 snímek místo 25).
 * 3. Vypnutá setrvačnost (isFlingVelocityAnimationEnabled = false): Jakmile zvednete prst z displeje,
 *    GPU se okamžitě uspí a nedobíhá animace posunu.
 * 4. Okamžité uspání při zhasnutí displeje (Lifecycle ON_PAUSE -> mapView.onPause()).
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapLibreMapView(
    zoomLevel: Float,
    targetLocation: LatLng,
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

            // Zabráníme systému Wear OS / One UI Watch vyhodnotit tažení prstem po mapě
            // jako gesto "Zpět / Zavřít aplikaci" (Swipe-to-Dismiss).
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

    // Životní cyklus - kritické pro úsporu energie (zastaví OpenGL při zhasnutí displeje)
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

    // Inicializace mapy a stylu
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
                // Vypneme dobíhající setrvačnost po zvednutí prstu -> GPU usne okamžitě po dotyku
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
                        lineColor("#FF9E0B"),
                        lineWidth(4.0f)
                    )
                )

                // 2. Zdroj a vrstva pro aktuální polohu (GPS tečka)
                val locationPoint = Point.fromLngLat(targetLocation.longitude, targetLocation.latitude)
                val userSource = GeoJsonSource(USER_LOCATION_SOURCE_ID, Feature.fromGeometry(locationPoint))
                style.addSource(userSource)

                style.addLayer(
                    CircleLayer(USER_LOCATION_HALO_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                        circleRadius(10f),
                        circleColor("#3376FF03")
                    )
                )
                style.addLayer(
                    CircleLayer(USER_LOCATION_DOT_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                        circleRadius(5.0f),
                        circleColor("#76FF03"),
                        circleStrokeColor("#000000"),
                        circleStrokeWidth(2.0f)
                    )
                )

                isStyleLoaded = true

                // Automaticky uložíme Jihomoravský kraj do offline databáze hodinek
                if (mbtilesFile == null) {
                    OfflineRegionHelper.ensureSouthMoraviaOffline(context, styleJson)
                }
            }

            onMapReady(map)
        }
    }

    // Aktualizace pozice GPS tečky na mapě
    LaunchedEffect(targetLocation, isStyleLoaded) {
        if (isStyleLoaded) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)?.let { source ->
                val pt = Point.fromLngLat(targetLocation.longitude, targetLocation.latitude)
                source.setGeoJson(Feature.fromGeometry(pt))
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

    // Reakce na změnu zoomu - použijeme okamžitý moveCamera (1 snímek) místo náročné animace
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

@Composable
private fun RememberMapLibreInit(context: android.content.Context) {
    remember {
        MapLibre.getInstance(context)
    }
}
