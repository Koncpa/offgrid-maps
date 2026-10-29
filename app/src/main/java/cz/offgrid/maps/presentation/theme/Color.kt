package cz.offgrid.maps.presentation.theme

import androidx.compose.ui.graphics.Color

// AMOLED True Black - Klíčové pro minimální spotřebu displeje
val AmoledBlack = Color(0xFF000000)
val DarkSurface = Color(0xFF121212)
val DarkSurfaceVariant = Color(0xFF1E1E1E)

// Outdoorové akcentní barvy - vysoký kontrast i na přímém slunci, šetrné k OLED
val OutdoorAmber = Color(0xFFE07A00) // Hlavní akcent s B=0
val OutdoorLime = Color(0xFF00C814)  // Status / GPS fix
val OutdoorTextPrimary = Color(0xFFD8D8D0) // Teplá lomená bílá (~25 % úspora proudu oproti #FFFFFF)
val OutdoorTextSecondary = Color(0xFFA0A098)

// České pásové značení (KČT) optimalizované pro čisté OLED subpixely (minimální odběr proudu)
val TrailRed = Color(0xFFEA0000)    // Čistý R subpixel (G=0, B=0)
val TrailBlue = Color(0xFF0077E6)   // Bez parazitního R subpixelu, snížený špičkový proud B
val TrailGreen = Color(0xFF00C814)  // Lesní zelená tažená úsporným G subpixelem (R=0, min B)
val TrailYellow = Color(0xFFE6B800) // Teplá žlutá s B=0 a sníženým špičkovým jasem o 10 %
