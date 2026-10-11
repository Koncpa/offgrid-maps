package cz.offgrid.maps.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import cz.offgrid.maps.presentation.theme.*

/**
 * Hlavní obrazovka mapy pro kruhový displej Wear OS.
 *
 * Architektura a ergonomie:
 * - Kruhový layout: Ovládací prvky respektují zakřivení displeje a nezasahují do rohů.
 * - Rotary Zoom: Plná podpora otočné korunky pro zoomování (s haptickým cvaknutím).
 * - True Black: Pozadí mapy a UI je čistě černé (#000000) pro nulovou spotřebu OLED pixelů.
 * - Dotykové plochy: Tlačítka mají velikost minimálně 48 dp pro spolehlivé stisknutí i v rukavicích.
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier
) {
    var zoomLevel by remember { mutableStateOf(14.0f) }
    var isTracking by remember { mutableStateOf(false) }
    var isGpsFixed by remember { mutableStateOf(true) }

    // FocusRequester je nutný pro zachycení otočení korunky (Rotary Event)
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
                    if (zoomLevel < 19.0f) zoomLevel += 0.5f
                },
                onZoomOut = {
                    if (zoomLevel > 5.0f) zoomLevel -= 0.5f
                }
            )
    ) {
        // Map Container - zde se vykresluje offline vektorová mapa přes MapLibre
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledBlack),
            contentAlignment = Alignment.Center
        ) {
            // Prototypové zobrazení mapového středu a zoomu
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "OffGrid Map",
                    color = OutdoorTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Zoom: %.1f".format(zoomLevel),
                    color = OutdoorAmber,
                    fontSize = 12.sp
                )
                Text(
                    text = "Otáčejte korunkou",
                    color = OutdoorTextSecondary,
                    fontSize = 10.sp
                )
            }
        }

        // Horní indikátor: Stav GPS a úsporného režimu
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (isGpsFixed) OutdoorLime else TrailRed,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isGpsFixed) "GPS FIX" else "HLEDÁM GPS",
                color = if (isGpsFixed) OutdoorLime else TrailRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Spodní ovládací lišta: Záznam trasy a zaměření polohy
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .fillMaxWidth(0.85f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tlačítko pro spuštění / zastavení záznamu trasy
            Button(
                onClick = { isTracking = !isTracking },
                modifier = Modifier.size(48.dp),
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

            // Tlačítko pro vycentrování na aktuální GPS pozici
            Button(
                onClick = {
                    // Vycentrovat mapu
                },
                modifier = Modifier.size(48.dp),
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
