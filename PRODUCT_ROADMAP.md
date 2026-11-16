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
