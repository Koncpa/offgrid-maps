# OffGrid Maps (Wear OS) – Product Documentation & Roadmap

**OffGrid Maps** is a standalone offline outdoor map and trail navigation application for **Wear OS** smartwatches (optimized and tested on **Samsung Galaxy Watch Ultra `SM-L705F`**, 100% compatible with all Wear OS 3/4/5 watches).

The core mission of the product is to deliver **100% reliable offline topographic maps with marked hiking trails, contour lines, and GPX navigation directly on the wrist** while minimizing battery consumption.

---

## 1. Production Core Architecture (Completed)

### A) Cartography & Multi-Country Offline Maps
- **Regional Offline Maps Connected to Private GitHub Repository (`Koncpa/offgrid-maps-data`):**
  - Pre-installed **South Moravia** (`z8–z14`) + dynamic `catalog.json` manifest supporting all **14 Regions of Czechia**, **Slovakia**, **Austria**, and future countries via one-tap download.
- **Offline 10 m / 50 m Contour Lines (`ContourTileManager`):**
  - Web Mercator tile generator and persistent flash cache (`filesDir/contour_tiles/tile_12_{x}_{y}.features`) derived from DEM elevation tiles using the *Marching Squares* algorithm.
- **Topographic Zoom Hierarchy & Marked Hiking Trails (`StyleBuilder`):**
  - `zoom 9+`: Marked hiking trails (Red, Blue, Green, Yellow) and primary roads.
  - `zoom 12+`: Forest and unpaved tracks (`class = track`, long dashes) + minor (`10 m`) and major (`50 m`) contour lines.
  - `zoom 13+`: Narrow forest footpaths (`class = path/footway`, short dashes) and contour elevation labels.

### B) Forest GNSS Engine & Orthometric Altimeter
- **Hybrid 10 m / Forest GNSS Receiver (`LiveGpsManager` & `LocationTrackingService`):**
  - Uses low-power companion phone Bluetooth fixes in open sky ($\le 10\text{ m}$).
  - Immediately (`0 ms`) engages the watch's built-in hardware satellite receiver (`LocationManager.GPS_PROVIDER`) when recording a trek (`REC`), pressing `GPS`, or entering forest canopy where the phone is shielded inside a backpack.
- **Barometric & Satellite Altimeter with EGM2008 Geoid Correction (`BarometricAltimeterManager`):**
  - Converts Android's raw WGS84 ellipsoidal height ($h_{\text{WGS84}}$) into true orthometric elevation above mean sea level ($H_{\text{MSL}}$) by subtracting the **EGM2008 geoid undulation** ($+43\text{ to }+47\text{ m}$ in Central Europe).
  - Instant startup barometer calibration from the last known 3D fix (even indoors) with persistent calibration offset in `SharedPreferences`.

### C) GPX Navigation & Track Recording
- **Asynchronous GPX Import, Export & Management (`GpxExporter`):**
  - Supports both `<rtept>` (Mapy.com) and `<trkpt>` GPX files, non-blocking background parsing (`Dispatchers.IO`) for 3,000+ point routes, route clearing (*Clear route*) without deleting the file, sharing to `Download/OffGridMaps`, and permanent deletion.
- **Cross-Track (Point-to-Segment) Off-Route Distance (`TrackingManager`):**
  - Calculates perpendicular projection onto each route segment rather than distance to vertices, eliminating false off-route alarms ($> 60\text{ m}$) on long straight trail segments.
- **Crash Recovery & Low-Battery Protection:**
  - Periodic recovery checkpoints during active recording and automatic emergency GPX save when battery drops to $\le 5\%$.

### D) Ultra-Pure AMOLED Power Optimization
- **Zero-Blue Palette:** True black background (`#000000` = `0 mW`) and amber-orange planned route line (`#E66800`, `B = 0`), reducing OLED display power draw by >52% compared to white lines.

---

## 2. Product Backlog – Future Roadmap

### 1. GPX Route Statistics (`km`, `↑ m`) & Remaining Distance to Finish
- **Description:**
  - Display total distance and cumulative ascent (e.g. **`28.4 km • ↑640 m`**) below each route name in the `Routes` tab.
  - During active navigation along a planned GPX route, show **remaining distance and remaining ascent to the finish** (plus estimated time of arrival based on current walking pace).
- **Technical Design:**
  - Compute route length and hysteresis-filtered ascent on `Dispatchers.IO` and cache metadata locally.
  - Project current GPS position onto the active route segment $i$ and sum remaining segments $i \dots N$.

### 2. Quick Waypoint "Car / Cabin / Start" (Point-to-Point Return)
- **Description:**
  - When walking off-trail (mushroom picking, bushcraft, alpine terrain, or finding a parked car/camp), allow saving the current position as **📍 Car / Start** with a single long-press.
  - Render a waypoint marker and a direct bearing line from current GPS position with straight-line distance and azimuth.
- **Technical Design:**
  - Long-pressing the `GPS` button stores coordinates in `SharedPreferences` and adds a dedicated `GeoJsonSource` layer connecting the user's live position to the saved waypoint.

### 3. Hardware / Action Button Controls (Gloves & Rain Mode)
- **Description:**
  - Operating a wet touchscreen in rain or with thick winter gloves is difficult.
  - Map hardware side/action buttons (`KEYCODE_STEM_PRIMARY`, `KEYCODE_STEM_1`, Galaxy Watch Ultra Action Button) to core outdoor actions:
    - **Short press:** Immediately center map on live GPS position and lock tracking.
    - **Double press / Long press:** Cycle zoom level or drop a quick waypoint.

### 4. Ultra-Low-Power 1-Bit Ambient Mode (Always-On Display for Trekking Poles)
- **Description:**
  - When hiking with trekking poles in both hands or climbing steep terrain, keep the route visible even when the wrist is lowered.
  - In Ambient Mode (`AmbientLifecycleObserver`), hide UI buttons, dim brightness, and render only the 1-bit outline of the active GPX route and location arrow, refreshing once every 30 seconds.

### 5. On-Watch Elevation Profile Chart
- **Description:**
  - Tapping the altitude indicator (`▲ 368 m`) in the top status pill opens a compact elevation profile chart of the active GPX route, highlighting the hiker's current position along the climb.

### 6. Paid Companion Mobile App (GPX Manager & Route Library)
- **Description:**
  - Dedicated companion Android app for comfortable GPX import/organization, watch sync, and a lightweight curated + community route library (one-time purchase; watch app remains free).
  - See full concept and phased launch notes in **[COMPANION_APP_IDEA.md](./COMPANION_APP_IDEA.md)**.

---

## 3. Security & Performance Hardening Backlog (`v1.1.0` — Prioritized)

Prioritized engineering audit backlog prepared after the `v1.0.0` release (`versionCode = 2`, `targetSdk = 35`).

### P1 – Highest Priority (Stability, Privacy & Daily Battery Impact)
- [x] **1. Asynchronous `stopTracking()` & clean service teardown** (`LocationTrackingService.kt`):
  - Move `GpxExporter.saveTrackToGpx(...)` and `MediaStore` export off the main thread onto `ioExecutor` before `stopSelf()`, preventing ANRs on long multi-hour treks or emergency `<= 5%` battery saves.
  - Shut down `ioExecutor` and ensure sensors/listeners are cleanly released in `onDestroy()`.
- [x] **2. Dynamic `1–4` z12 contour tile window & post-index raw `.features` cleanup** (`ContourTileManager.kt`, `TrailTileManager.kt`, `PoiTileManager.kt`):
  - Load only the `1–4` nearest `z=12` contour tiles around the viewport instead of a fixed `3×3` (`9`-tile) grid, reducing native MapLibre `geojson-vt` RAM for contours by ~55–85%.
  - Automatically delete redundant raw `trails.features` and `pois.features` files once `.indexed_v1` spatial tile markers are flushed cleanly, reclaiming tens to hundreds of MB of watch flash storage.
- [x] **3. Disable Android Auto Backup (`android:allowBackup="false"`)** (`AndroidManifest.xml`):
  - Prevents Android from backing up local GPS history (`files/tracks/*.gpx`) or last coordinates (`SharedPreferences`) to Google Drive, aligning 100% with the offline-only Privacy Policy.
- [x] **4. Isolate compass `azimuthDegrees` recompositions & throttle `SharedPreferences` writes** (`MapScreen.kt`, `OfflineRegionHelper.kt`, `BarometricAltimeterManager.kt`):
  - Scope `azimuthDegrees` collection to small isolated composables (cardinal label + MapLibre chevron effect) instead of recomposing the entire root `MapScreen` up to 12.5×/s.
  - Throttle `saveLastKnownCenter` (save on `> 150 m` move or `ON_PAUSE`) and `saveCalibrationToPrefs` (at most every `60 s` or `> 2 m` offset shift) instead of flushing XML to flash on every 1.5 s GPS fix.

### P2 – Medium Priority (Security Hardening & All-Day Trek Efficiency)
- [ ] **5. Hardened GPX import (`XmlPullParser`, size cap & non-destructive naming)** (`GpxExporter.kt`):
  - Enforce a maximum import size limit (e.g. `10 MB`) for untrusted `ACTION_VIEW` / picker URIs.
  - Replace regex-based `pointTagRegex` (`DOT_MATCHES_ALL`) with streaming `XmlPullParser` (O(N), zero ReDoS, XXE-safe).
  - Avoid silently overwriting an existing saved track with the same sanitized filename by appending a numeric suffix on collision.
- [ ] **6. Defense-in-depth validation for downloaded packages & URLs** (`OfflineRegionHelper.kt`, `ContourTileManager.kt`):
  - Validate `preset.id` against `^[a-z0-9_-]{1,64}$` and verify canonical target paths stay within `github_regions/`.
  - Strictly parse `z/x/y` coordinates via `toIntOrNull()` in `ContourTileManager.importTilePackageToDir(...)`.
  - Enforce an `https://` + allowed-host check on catalog `releaseBaseUrl` (`*.workers.dev`, `github.com`, `raw.githubusercontent.com`) and add a max decompressed byte cap in `streamFeatureArrayFromGzip(...)` against GZIP bombs.
- [ ] **7. Adaptive hardware `GPS_PROVIDER` cadence for screen-off & stationary pauses** (`LocationTrackingService.kt`):
  - Mirror `interactive` / screen-off intervals (`1500 ms` → `4000 ms`) and stationary hysteresis (`10000 ms` after staying within `3 m` for `>= 45 s`) on `LocationManager.GPS_PROVIDER`, backed by unit tests.
- [ ] **8. Lazy overlay computations & precomputed GPX suffix stats** (`MapScreen.kt`, `TrackingManager.kt`):
  - Compute `nearbyRegions` (476 Haversine calls) only on the `Offline` tab (`selectedTab == 1`) and `enrichedPois` only on the `POIs` tab (`selectedTab == 2`), quantized to ~50 m movement steps.
  - Precompute cumulative `suffixDistanceMeters[]` and `suffixAscentMeters[]` once when loading a planned GPX route so `calculateRemainingRouteStats(...)` avoids repeated Haversine loops.

### P3 – Lower Priority (Long-Trek Polish, Local Socket Defense & Small Watch UX)
- [ ] **9. Live track polyline update throttling & zero-allocation formatting** (`MapLibreMapView.kt`, `ContourTileManager.kt`, `GpxExporter.kt`):
  - Throttle MapLibre `TRACK_LINE_SOURCE_ID` updates / simplify displayed trace for multi-hour 10k+ point hikes; replace `String.format` inside hot loops (`interpCoord`, `buildGpxXml`) with direct `StringBuilder` formatting.
- [ ] **10. Localhost style server hardening** (`OfflineRegionHelper.kt`):
  - Wrap `client` sockets in `.use {}` and cap header line count/length in `OffGridLocalStyleServer` (`127.0.0.1`).
- [ ] **11. Compact 40 mm watch responsiveness & native debug symbols** (`MapScreen.kt`, `app/build.gradle.kts`):
  - Switch overlay `LazyColumn` to Wear OS `ScalingLazyColumn` + compact spacing when `screenWidthDp <= 205`.
  - Add `ndk { debugSymbolLevel = "SYMBOL_TABLE" }` in `release` buildType to include native MapLibre symbol tables and clear the Google Play Console warning.
