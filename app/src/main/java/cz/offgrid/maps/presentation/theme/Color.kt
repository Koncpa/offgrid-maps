package cz.offgrid.maps.presentation.theme

import androidx.compose.ui.graphics.Color

// AMOLED True Black - Essential for zero display power consumption on black pixels
val AmoledBlack = Color(0xFF000000)
val DarkSurface = Color(0xFF121212)
val DarkSurfaceVariant = Color(0xFF1E1E1E)

// Outdoor high-contrast accent colors optimized for direct sunlight and OLED subpixel efficiency
val OutdoorAmber = Color(0xFFE07A00) // Primary accent with B=0
val OutdoorLime = Color(0xFF00C814)  // Status / GPS fix
val OutdoorTextPrimary = Color(0xFFD8D8D0) // Warm off-white (~25% lower current draw than #FFFFFF)
val OutdoorTextSecondary = Color(0xFFA0A098)

// Marked hiking trail palette (KČT) tuned for pure OLED subpixels (minimum current draw)
val TrailRed = Color(0xFFEA0000)    // Pure R subpixel (G=0, B=0)
val TrailBlue = Color(0xFF0077E6)   // Zero parasitic R subpixel, reduced peak B current
val TrailGreen = Color(0xFF00C814)  // Forest green driven by efficient G subpixel (R=0, min B)
val TrailYellow = Color(0xFFE6B800) // Warm yellow with B=0 and 10% reduced peak brightness
