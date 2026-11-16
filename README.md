# OffGrid Maps (Wear OS)

Standalone offline outdoor map and GPX navigation app for **Wear OS** smartwatches, engineered for maximum battery life, reliable trail navigation, and effortless outdoor usability.

For detailed system architecture and the future product backlog, see **[PRODUCT_ROADMAP.md](./PRODUCT_ROADMAP.md)**.

## Key Features
- **100% Offline Navigation & Multi-Country Region Catalog (1-Click Download):**
  - Pre-installed **South Moravia** (`z8–z14`) + built-in catalog of all **14 Regions of Czechia**, **Slovakia**, and **Austria** dynamically synced from a dedicated GitHub map repository (`Koncpa/offgrid-maps-data`) for instant one-tap offline downloads.
- **Offline Contour Lines (10 m / 50 m) & Marked Hiking Trails (KČT):**
  - Web Mercator contour tile cache (`10 m` minor / `50 m` index contours) and complete hiking trail layers (Red, Blue, Green, Yellow), forest tracks, and footpaths following topographic cartographic conventions.
- **Instant Forest GNSS Lock & Orthometric Altimeter (EGM2008):**
  - Hybrid location engine: uses low-power companion phone Bluetooth GPS in open terrain ($\le 10\text{ m}$) and immediately locks the watch's internal dual-frequency GNSS receiver (`LocationManager.GPS_PROVIDER`) under forest canopy or during active trek recording.
  - Barometric and satellite altimeter with **EGM2008 geoid undulation correction** (`▲ m`), eliminating the ~45 m WGS84 ellipsoidal height offset in Central Europe.
- **GPX Route Navigation & Track Recording:**
  - Non-blocking `Dispatchers.IO` GPX import/export (compatible with Mapy.com `<rtept>` and `<trkpt>`), **Cross-Track (Point-to-Segment)** off-route distance calculation, haptic off-route alerts (>60 m), crash recovery checkpointing, and automatic emergency save at $\le 5\%$ battery.
- **Ultra-Pure AMOLED Power Efficiency:**
  - True black background (`#000000` = `0 mW`) and zero-blue amber-orange planned route rendering (`#E66800`, `B = 0`, >52% lower OLED subpixel power draw than white).

## Tech Stack
- **Language:** Kotlin (Coroutines, StateFlow)
- **UI:** Jetpack Compose for Wear OS
- **Map Engine:** MapLibre Native Android + local loopback style/tile server
- **Location & Sensors:** `FusedLocationProviderClient` + direct `LocationManager.GPS_PROVIDER`, `Sensor.TYPE_PRESSURE` (barometer with EGM2008 geoid model), `Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR` (tilt-compensated compass)
- **Testing:** JUnit 4 unit test suite (`./gradlew test`)
