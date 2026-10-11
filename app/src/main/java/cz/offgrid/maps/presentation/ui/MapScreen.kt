package cz.offgrid.maps.presentation.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import cz.offgrid.maps.data.map.MapFileManager
import cz.offgrid.maps.presentation.theme.*
import cz.offgrid.maps.presentation.ui.map.MapLibreMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Hlavní obrazovka mapy pro kruhový displej Wear OS.
 *
 * Kombinuje:
 * 1. Nativní MapLibre rendering s True Black stylem.
 * 2. Rotary Input (zoom otáčením korunky bez zakrývání displeje prsty).
 * 3. Indikaci stavu GPS a záznamu trasy.
 * 4. Tlačítko pro vycentrování pozice.
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapFileManager = remember { MapFileManager(context) }
    val defaultMapFile = remember { mapFileManager.getDefaultMap() }

    var zoomLevel by remember { mutableFloatStateOf(13.0f) }
    var isTracking by remember { mutableStateOf(false) }
    var isGpsFixed by remember { mutableStateOf(true) }

    // Výchozí souřadnice (např. Krkonoše / Sněžka: 50.736, 15.740)
    var currentLocation by remember { mutableStateOf(LatLng(50.7360, 15.7396)) }
    var maplibreMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }

    // FocusRequester pro zachycení otočení korunky hodinek
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
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
        // Nativní vektorová mapa
        MapLibreMapView(
            zoomLevel = zoomLevel,
            targetLocation = currentLocation,
            mbtilesFile = defaultMapFile,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { map ->
                maplibreMapInstance = map
            }
        )

        // Upozornění, pokud ještě není nahrán žádný offline soubor .mbtiles
        if (defaultMapFile == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .background(DarkSurfaceVariant.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Vložte .mbtiles do /files/maps",
                    color = OutdoorAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Horní indikátor: Stav GPS a zoomu
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .background(AmoledBlack.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (isGpsFixed) OutdoorLime else TrailRed,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (isGpsFixed) "GPS" else "NO GPS",
                color = if (isGpsFixed) OutdoorLime else TrailRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Z %.1f".format(zoomLevel),
                color = OutdoorTextSecondary,
                fontSize = 10.sp
            )
        }

        // Spodní ergonomická lišta: Ovládací tlačítka
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .fillMaxWidth(0.82f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tlačítko záznamu trasy (REC / GO)
            Button(
                onClick = { isTracking = !isTracking },
                modifier = Modifier.size(46.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isTracking) TrailRed else DarkSurfaceVariant,
                    contentColor = OutdoorTextPrimary
                )
            ) {
                Text(
                    text = if (isTracking) "REC" else "GO",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Tlačítko pro návrat na střed (Re-center na GPS pozici)
            Button(
                onClick = {
                    maplibreMapInstance?.easeCamera(
                        CameraUpdateFactory.newLatLng(currentLocation),
                        200
                    )
                },
                modifier = Modifier.size(46.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = DarkSurfaceVariant,
                    contentColor = OutdoorAmber
                )
            ) {
                Text(
                    text = "GPS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
