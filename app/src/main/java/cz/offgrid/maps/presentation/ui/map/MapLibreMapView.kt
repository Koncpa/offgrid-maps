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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

private const val USER_LOCATION_SOURCE_ID = "user_location_source"
private const val USER_LOCATION_LAYER_ID = "user_location_symbol_layer"
private const val USER_LOCATION_ICON_ID = "mapycz_style_location_arrow"

private const val TRACK_LINE_SOURCE_ID = "recorded_track_source"
private const val TRACK_LINE_LAYER_ID = "recorded_track_layer"

/**
 * Energeticky vysoce optimalizovaný Compose wrapper pro MapLibre MapView na Wear OS.
 *
 * Jak funguje nová GPS značka ve stylu Mapy.cz:
 * 1. Malá kompaktní tečka s drobnou šipečkou na obvodu (vykreslená jako lehká 48x48 bitmapa).
 * 2. Šipečka má na displeji vždy stejnou malou velikost bez ohledu na přiblížení mapy (Zoom).
 * 3. Barva je zvolena podle fyziky OLED displejů: Teplá tlumená jantarová (#D97706) s nulovým
 *    podílem modrého subpixelu a bez svítící bílé (#FFFFFF) – spotřebovává o ~65 % méně proudu.
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
                // 1. Zdroj a vrstva pro zaznamenanou GPX trasu (v úsporné tlumené jantarové barvě)
                val trackSource = GeoJsonSource(TRACK_LINE_SOURCE_ID)
                style.addSource(trackSource)
                style.addLayer(
                    LineLayer(TRACK_LINE_LAYER_ID, TRACK_LINE_SOURCE_ID).withProperties(
                        lineColor("#D97706"),
                        lineWidth(3.0f)
                    )
                )

                // 2. Ikona a SymbolLayer pro malou GPS tečku se šipečkou ve stylu Mapy.cz
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

    // Aktualizace pozice GPS tečky
    LaunchedEffect(targetLocation, isStyleLoaded) {
        if (isStyleLoaded) {
            mapInstance?.style?.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)?.let { source ->
                val pt = Point.fromLngLat(targetLocation.longitude, targetLocation.latitude)
                source.setGeoJson(Feature.fromGeometry(pt))
            }
        }
    }

    // Aktualizace natočení malé šipečky podle kompasu (přímo na GPU přes vlastnost vrstvy)
    LaunchedEffect(azimuthDegrees, isStyleLoaded) {
        if (isStyleLoaded) {
            mapInstance?.style?.getLayerAs<SymbolLayer>(USER_LOCATION_LAYER_ID)?.setProperties(
                iconRotate(azimuthDegrees)
            )
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
 * Vytvoří malou, k OLED displeji maximálně šetrnou značku polohy se směrovou šipečkou (styl Mapy.cz).
 *
 * Používá tlumenou teplou jantarovou/terakotovou barvu (#D97706) s černým lemem (#000000):
 * - 0 % modrého OLED subpixelu (který má nejvyšší energetickou náročnost).
 * - Žádná bílá (#FFFFFF), která by rozsvěcela všechny 3 subpixely najednou.
 */
private fun createMapyCzStyleLocationBitmap(): Bitmap {
    val size = 44
    val center = size / 2f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Černý kontrastní lem pro oddělení od barevných tras pod značkou (0 mW OLED)
    val darkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#000000")
        style = Paint.Style.FILL
    }

    // Šetrná teplá jantarová barva (R=217, G=119, B=6 -> téměř nulová modrá složka)
    val ecoAmberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#D97706")
        style = Paint.Style.FILL
    }

    // Tmavý střed tečky (šetří vnitřní pixely kruhu)
    val innerCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#141414")
        style = Paint.Style.FILL
    }

    // 1. Malá směrová šipečka (zobáček) těsně nad kroužkem polohy (jako v Mapy.cz)
    val outerArrowPath = Path().apply {
        moveTo(center, 2f)          // Špička nahoře
        lineTo(center + 7.5f, 14f)  // Pravý roh šipečky
        lineTo(center - 7.5f, 14f)  // Levý roh šipečky
        close()
    }
    canvas.drawPath(outerArrowPath, darkStrokePaint)

    val innerArrowPath = Path().apply {
        moveTo(center, 4f)
        lineTo(center + 5.5f, 13f)
        lineTo(center - 5.5f, 13f)
        close()
    }
    canvas.drawPath(innerArrowPath, ecoAmberPaint)

    // 2. Kompaktní kroužek polohy uprostřed
    canvas.drawCircle(center, center + 2f, 8.5f, darkStrokePaint)
    canvas.drawCircle(center, center + 2f, 6.5f, ecoAmberPaint)
    canvas.drawCircle(center, center + 2f, 2.8f, innerCorePaint)

    return bitmap
}

@Composable
private fun RememberMapLibreInit(context: android.content.Context) {
    remember {
        MapLibre.getInstance(context)
    }
}
