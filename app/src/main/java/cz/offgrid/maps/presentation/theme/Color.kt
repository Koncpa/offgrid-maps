package cz.offgrid.maps.presentation.theme

import androidx.compose.ui.graphics.Color

// AMOLED True Black - Klíčové pro minimální spotřebu displeje
val AmoledBlack = Color(0xFF000000)
val DarkSurface = Color(0xFF121212)
val DarkSurfaceVariant = Color(0xFF1E1E1E)

// Outdoorové akcentní barvy - vysoký kontrast i na přímém slunci
val OutdoorAmber = Color(0xFFFF9E0B) // Hlavní akcent
val OutdoorLime = Color(0xFF76FF03)  // Status / GPS fix
val OutdoorTextPrimary = Color(0xFFFFFFFF)
val OutdoorTextSecondary = Color(0xFFB0B0B0)

// České pásové značení (KČT) optimalizované pro čisté OLED subpixely (minimální odběr proudu)
val TrailRed = Color(0xFFEA0000)    // Čistý R subpixel (G=0, B=0)
val TrailBlue = Color(0xFF0077E6)   // Bez parazitního R subpixelu, snížený špičkový proud B
val TrailGreen = Color(0xFF00C814)  // Lesní zelená tažená úsporným G subpixelem (R=0, min B)
val TrailYellow = Color(0xFFE6B800) // Teplá žlutá s B=0 a sníženým špičkovým jasem o 10 %
