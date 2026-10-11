package cz.offgrid.maps.presentation.ui.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import cz.offgrid.maps.data.map.StyleBuilder
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File

/**
 * Compose wrapper pro nativní MapLibre MapView na Wear OS.
 *
 * Zvláštní ohled na baterii:
 * 1. Propojení s Lifecycle: Pokud aplikace přejde do pozadí (nebo displej zhasne),
 *    okamžitě se volá mapView.onPause() a onStop(), což zastaví OpenGL renderovací smyčku.
 * 2. Vypnuté zbytečné UI widgety: Na hodinkách vypínáme logo, kompas a atribuci
 *    přímo v MapLibre (šetří vykreslovací buffery a nepřekáží v kruhovém rozhraní).
 */
@Composable
fun MapLibreMapView(
    zoomLevel: Float,
    targetLocation: LatLng,
    mbtilesFile: File?,
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Inicializace MapLibre singletonu
    RememberMapLibreInit(context)

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }

    // Životní cyklus - kritické pro úsporu energie
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                LifecycleEvent.ON_START -> mapView.onStart()
                LifecycleEvent.ON_RESUME -> mapView.onResume()
                LifecycleEvent.ON_PAUSE -> mapView.onPause() // Zastaví OpenGL thread
                LifecycleEvent.ON_STOP -> mapView.onStop()
                LifecycleEvent.ON_DESTROY -> mapView.onDestroy()
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

            // Optimalizace pro kruhové hodinky - vypneme defaultní ovládací prvky
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isRotateGesturesEnabled = false // Řídíme kompasem nebo tlačítky

            // Nastavení počáteční pozice (výchozí střed např. Sněžka / Krkonoše: 50.736, 15.740)
            map.cameraPosition = CameraPosition.Builder()
                .target(targetLocation)
                .zoom(zoomLevel.toDouble())
                .build()

            // Načtení energeticky úsporného AMOLED stylu
            val styleJson = StyleBuilder.createAmoledOutdoorStyle(mbtilesFile)
            map.setStyle(Style.Builder().fromJson(styleJson))

            onMapReady(map)
        }
    }

    // Reakce na změnu zoomu z otočné korunky
    LaunchedEffect(zoomLevel) {
        mapInstance?.let { map ->
            if (map.cameraPosition.zoom.toFloat() != zoomLevel) {
                map.easeCamera(CameraUpdateFactory.zoomTo(zoomLevel.toDouble()), 120)
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
