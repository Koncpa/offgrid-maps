# OffGrid Maps (Wear OS)

Standalone offline outdoor map and GPX navigation app for **Wear OS** smartwatches, engineered for maximum battery life, reliable trail navigation, and effortless outdoor usability.

For detailed system architecture and the future product backlog, see **[PRODUCT_ROADMAP.md](./PRODUCT_ROADMAP.md)**. For future hosting migration see **[MIGRATION.md](./MIGRATION.md)**, and for Google Play preparation see **[RELEASE_CHECKLIST.md](./RELEASE_CHECKLIST.md)**.

## Key Features
- **100% Offline Navigation & Global Region Catalog (476 Regions / 192 Countries):**
  - Pre-installed **South Moravia** (`z8–z14`) + global catalog across **6 continents** served from Cloudflare R2 CDN (with GitHub Releases fallback) for one-tap offline downloads, sequential download queue, and Wi-Fi / wake-lock protection during downloads.
- **Offline Contour Lines (10 m / 50 m) & Marked Hiking Trails (KČT):**
  - Web Mercator contour tile cache (`10 m` minor / `50 m` index contours) and complete hiking trail layers (Red, Blue, Green, Yellow), forest tracks, and footpaths following topographic cartographic conventions.
- **Instant Forest GNSS Lock & Orthometric Altimeter (EGM2008):**
  - Hybrid location engine: uses low-power companion phone Bluetooth GPS in open terrain ($\le 10\text{ m}$) and immediately locks the watch's internal dual-frequency GNSS receiver (`LocationManager.GPS_PROVIDER`) under forest canopy or during active trek recording.
  - Barometric and satellite altimeter with **EGM2008 geoid undulation correction** (`▲ m`), eliminating the ~45 m WGS84 ellipsoidal height offset in Central Europe.
- **GPX Route Navigation & Track Recording:**
  - Non-blocking `Dispatchers.IO` GPX import/export (compatible with Mapy.com, Komoot, Strava, and Garmin `<rtept>` and `<trkpt>`), **Cross-Track (Point-to-Segment)** off-route distance calculation, haptic off-route alerts (>60 m), crash recovery checkpointing, and automatic emergency save at $\le 5\%$ battery.
- **Ultra-Pure AMOLED Power Efficiency:**
  - True black background (`#000000` = `0 mW`) and zero-blue amber-orange planned route rendering (`#E66800`, `B = 0`, >52% lower OLED subpixel power draw than white).

## How to Get Custom GPX Routes onto Your Watch

OffGrid Maps accepts standard **GPX 1.1** files containing `<trkpt>` (recorded tracks) or `<rtept>` (planned routes from Mapy.com, Komoot, Strava, or Garmin Connect):

1. **Send from your phone (Quick Share / Bluetooth / Wear file manager):**
   - Export a `.gpx` route on your phone (e.g. **Mapy.com → Export → GPX**).
   - Send it to the watch via **Quick Share** or **Bluetooth** (it lands in the watch's `Download/` folder).
   - Either tap the received file notification and open it with **OffGrid Maps**, **or** simply open **OffGrid Maps → Routes** (auto-scans `Download/` and `Download/OffGridMaps/`) or tap **`+ Import GPX`** to pick any `.gpx` file manually.
2. **Copy from a computer via USB / ADB:**
   ```bash
   adb push my_route.gpx /sdcard/Download/OffGridMaps/
   # or directly into the app's external tracks directory:
   adb push my_route.gpx /sdcard/Android/data/cz.offgrid.maps/files/tracks/
   ```
   Then open **OffGrid Maps → Routes** — newly copied `.gpx` files appear automatically and tapping one loads it onto the map.

> **Tip:** Recorded tracks are saved inside the app and backed up as `.gpx` copies to `Download/OffGridMaps/` on the watch.

## Tech Stack
- **Language:** Kotlin (Coroutines, StateFlow)
- **UI:** Jetpack Compose for Wear OS
- **Map Engine:** MapLibre Native Android + local loopback style/tile server
- **Location & Sensors:** `FusedLocationProviderClient` + direct `LocationManager.GPS_PROVIDER`, `Sensor.TYPE_PRESSURE` (barometer with EGM2008 geoid model), `Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR` (tilt-compensated compass)
- **Testing:** JUnit 4 unit test suite (`./gradlew test`)
