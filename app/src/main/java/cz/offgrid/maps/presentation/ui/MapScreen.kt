package cz.offgrid.maps.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import cz.offgrid.maps.data.map.MapFileManager
import cz.offgrid.maps.data.tracking.LocationTrackingService
import cz.offgrid.maps.data.tracking.TrackingManager
import cz.offgrid.maps.presentation.theme.*
import cz.offgrid.maps.presentation.ui.map.MapLibreMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Hlavní obrazovka mapy pro kruhový displej Wear OS.
 *
 * Plná integrace:
 * 1. Nativní MapLibre rendering s True Black stylem.
 * 2. Rotary Input (zoom otáčením korunky bez zakrývání displeje prsty).
 * 3. Propojení s LocationTrackingService (Foreground Service) a TrackingManager.
 * 4. Živé zobrazení ujeté vzdálenosti (km) a stavu GPS fixu.
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapFileManager = remember { MapFileManager(context) }
    val defaultMapFile = remember { mapFileManager.getDefaultMap() }

    // Reaktivní stav z TrackingManager
    val isTracking by TrackingManager.isTracking.collectAsState()
    val totalDistanceMeters by TrackingManager.totalDistanceMeters.collectAsState()
    val latestGpsPoint by TrackingManager.currentLocation.collectAsState()

    var zoomLevel by remember { mutableFloatStateOf(13.0f) }
    var maplibreMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }

    // Výchozí poloha (např. Krkonoše / Sněžka, pokud ještě není GPS fix)
    var activeLocation by remember { mutableStateOf(LatLng(50.7360, 15.7396)) }

    // Když přijde nový GPS bod, aktualizujeme souřadnice
    LaunchedEffect(latestGpsPoint) {
        latestGpsPoint?.let { pt ->
            activeLocation = LatLng(pt.latitude, pt.longitude)
        }
    }

    // Permission launcher pro GPS
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineLocationGranted && !isTracking) {
            LocationTrackingService.start(context)
        }
    }

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
            targetLocation = activeLocation,
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

        // Horní indikátor: Stav GPS, ujetá vzdálenost a zoom
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .background(AmoledBlack.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val hasGpsFix = latestGpsPoint != null
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (hasGpsFix) OutdoorLime else TrailRed,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))

            // Pokud nahráváme, zobrazíme ujeté km, jinak stav GPS
            if (isTracking) {
                val km = totalDistanceMeters / 1000.0
                Text(
                    text = "%.2f km".format(km),
                    color = OutdoorLime,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = if (hasGpsFix) "GPS OK" else "HLEDÁM",
                    color = if (hasGpsFix) OutdoorLime else TrailRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Z %.1f".format(zoomLevel),
                color = OutdoorTextSecondary,
                fontSize = 10.sp
            )
        }

        // Spodní ergonomická lišta: Ovládací tlačítka (velikost 46 dp)
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
                onClick = {
                    if (isTracking) {
                        LocationTrackingService.stop(context)
                    } else {
                        // Zkontrolujeme oprávnění
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
                modifier = Modifier.size(46.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isTracking) TrailRed else DarkSurfaceVariant,
                    contentColor = OutdoorTextPrimary
                )
            ) {
                Text(
                    text = if (isTracking) "STOP" else "REC",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Tlačítko pro vycentrování na aktuální GPS pozici
            Button(
                onClick = {
                    maplibreMapInstance?.easeCamera(
                        CameraUpdateFactory.newLatLng(activeLocation),
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
