package cz.offgrid.maps.data.map

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import cz.offgrid.maps.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.max

/**
 * Data model for a saved offline map region displayed in the overview list.
 */
data class SavedOfflineRegion(
    val id: Long,
    val rawMetadata: String,
    val displayName: String,
    val centerLat: Double,
    val centerLon: Double,
    val isDefaultJmk: Boolean
)

/**
 * Predefined region in the global catalog (`catalog.json` in `Koncpa/offgrid-maps-data`)
 * for one-tap offline download.
 */
data class OfflinePresetRegion(
    val id: String,
    val title: String,
    val subtitle: String,
    val centerLat: Double,
    val centerLon: Double,
    val latSpanDeg: Double = 0.50,
    val lonSpanDeg: Double = 0.75,
    val countryCode: String = "CZ",
    val countryName: String = "Czechia – Regions",
    val packagePath: String = "maps/cz/$id",
    val continent: String = OfflineRegionHelper.resolveContinent(countryCode),
    val releaseTag: String? = null,
    val assetName: String? = null,
    val releaseBaseUrl: String? = null
)

/**
 * Offline outdoor Point of Interest (spring/water source, shelter/hut, mountain saddle, landmark, or rescue point).
 */
data class OutdoorPoi(
    val category: String,
    val label: String,
    val lat: Double,
    val lon: Double
)

/**
 * Offline map region manager connected to CDN / GitHub Releases (`Koncpa/offgrid-maps-data`),
 * dynamically loading `catalog.json` worldwide and pre-built map packages.
 */
object OfflineRegionHelper {

    private const val REGION_NAME_JMK = "Jihomoravsky_kraj_z14_v3"
    private const val MAP_PREFS_NAME = "offgrid_map_prefs"
    private const val KEY_LAST_LAT = "last_viewport_lat"
    private const val KEY_LAST_LON = "last_viewport_lon"
    private const val CACHED_CATALOG_FILE = "cached_github_catalog.json"
    private const val ASSET_DEFAULT_CATALOG_FILE = "default_catalog.json"
    private const val ASSET_JMK_POIS_FILE = "jmk_outdoor_pois.geojson"
    private const val PACKAGES_DIR = "github_regions"
    private const val TRAILS_FRAGMENT_FILE = "trails.features"
    private const val POIS_FRAGMENT_FILE = "pois.features"

    private val trailExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val releaseAssetUrlCache = ConcurrentHashMap<String, Map<String, String>>()
    private val downloadLock = Any()
    private val pendingDownloadQueue = mutableListOf<OfflinePresetRegion>()
    private var activeWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundWifiNetwork: Network? = null
    private var activeWifiLock: WifiManager.WifiLock? = null
    private var activeDownloadWakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var activeCatalogReleaseBaseUrl: String? = null
    @Volatile
    private var activeDownloadId: String? = null

    private val _statusText = MutableStateFlow("NO MAP")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _activeDownloadTitle = MutableStateFlow<String?>(null)
    val activeDownloadTitle: StateFlow<String?> = _activeDownloadTitle.asStateFlow()

    private val _queuedRegions = MutableStateFlow<List<OfflinePresetRegion>>(emptyList())
    val queuedRegions: StateFlow<List<OfflinePresetRegion>> = _queuedRegions.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    private val _isOfflineReady = MutableStateFlow(false)
    val isOfflineReady: StateFlow<Boolean> = _isOfflineReady.asStateFlow()

    private val _savedRegions = MutableStateFlow<List<SavedOfflineRegion>>(emptyList())
    val savedRegions: StateFlow<List<SavedOfflineRegion>> = _savedRegions.asStateFlow()

    fun saveLastKnownCenter(context: Context, lat: Double, lon: Double) {
        context.getSharedPreferences(MAP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LAST_LAT, lat.toFloat())
            .putFloat(KEY_LAST_LON, lon.toFloat())
            .apply()
    }

    fun loadLastKnownCenter(context: Context): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(MAP_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LAST_LAT) && prefs.contains(KEY_LAST_LON)) {
            return prefs.getFloat(KEY_LAST_LAT, 49.1246f).toDouble() to
                prefs.getFloat(KEY_LAST_LON, 16.3353f).toDouble()
        }
        val savedFirst = _savedRegions.value.firstOrNull()
        if (savedFirst != null) {
            return savedFirst.centerLat to savedFirst.centerLon
        }
        return 49.1246 to 16.3353
    }

    private val northAmericaCodes = setOf(
        "US", "CA", "MX", "GL", "BS", "BZ", "CR", "CU", "DO", "GP", "GT", "HN", "HT", "JM", "MQ", "NI", "PA", "SV"
    )
    private val southAmericaCodes = setOf(
        "AR", "BO", "BR", "CL", "CO", "EC", "FK", "GF", "GY", "PE", "PY", "SR", "UY", "VE"
    )
    private val asiaCodes = setOf(
        "AE", "AF", "AM", "AZ", "BD", "BH", "BN", "BT", "CN", "ID", "IL", "IN", "IQ", "IR",
        "JO", "JP", "KG", "KH", "KP", "KR", "KW", "KZ", "LA", "LB", "LK", "MM", "MN", "MV",
        "MY", "NP", "OM", "PH", "PK", "PS", "QA", "SA", "SG", "SY", "TH", "TJ", "TL", "TM",
        "TW", "UZ", "VN", "YE"
    )
    private val africaCodes = setOf(
        "AO", "BF", "BI", "BJ", "BW", "CD", "CF", "CG", "CI", "CM", "CV", "DJ", "DZ", "EG",
        "ER", "ET", "GA", "GH", "GM", "GN", "GQ", "GW", "KE", "KM", "LR", "LS", "LY", "MA",
        "MG", "ML", "MR", "MU", "MW", "MZ", "NA", "NE", "NG", "RE", "RW", "SC", "SD", "SL",
        "SN", "SO", "SS", "ST", "SZ", "TD", "TG", "TN", "TZ", "UG", "YT", "ZA", "ZM", "ZW"
    )
    private val oceaniaCodes = setOf(
        "AU", "NZ", "CK", "FJ", "FM", "KI", "MH", "NC", "NR", "NU", "PF", "PG", "PW", "SB", "TO", "TV", "VU", "WS"
    )

    fun resolveContinent(countryCode: String): String {
        val code = countryCode.uppercase(Locale.US)
        return when {
            code in northAmericaCodes -> "North America"
            code in southAmericaCodes -> "South America"
            code in asiaCodes -> "Asia"
            code in africaCodes -> "Africa"
            code in oceaniaCodes -> "Australia & Oceania"
            else -> "Europe"
        }
    }

    /**
     * Built-in default catalog of Czech regions (available even offline before first GitHub sync).
     */
    val presetRegions: List<OfflinePresetRegion> = listOf(
        OfflinePresetRegion("jihomoravsky_kraj", "South Moravia", "Brno, Pálava, Moravian Karst, Podyjí, Znojmo", 49.08, 16.58, 0.78, 1.30),
        OfflinePresetRegion("kraj_vysocina", "Vysočina Region", "Jihlava, Třebíč, Žďár n. S., Havl. Brod", 49.45, 15.68, 0.55, 0.75),
        OfflinePresetRegion("jihocesky_kraj", "South Bohemia", "Č. Budějovice, Tábor, Č. Krumlov, Písek", 49.05, 14.48, 0.65, 0.90),
        OfflinePresetRegion("zlinsky_kraj", "Zlín Region", "Zlín, Vsetín, Uh. Hradiště, Kroměříž", 49.22, 17.72, 0.50, 0.70),
        OfflinePresetRegion("olomoucky_kraj", "Olomouc Region", "Olomouc, Přerov, Prostějov, Šumperk", 49.72, 17.18, 0.65, 0.75),
        OfflinePresetRegion("moravskoslezsky_kraj", "Moravian-Silesian", "Ostrava, Opava, Frýdek-Místek, Bruntál", 49.78, 18.12, 0.60, 0.85),
        OfflinePresetRegion("pardubicky_kraj", "Pardubice Region", "Pardubice, Chrudim, Svitavy, Ústí n. O.", 49.92, 16.15, 0.50, 0.80),
        OfflinePresetRegion("kralovehradecky_kraj", "Hradec Králové Region", "Hradec Králové, Trutnov, Náchod, Jičín", 50.38, 15.82, 0.55, 0.75),
        OfflinePresetRegion("stredocesky_kraj", "Central Bohemia", "Beroun, Kladno, M. Boleslav, Příbram", 49.98, 14.55, 0.65, 0.95),
        OfflinePresetRegion("praha", "Prague & Surroundings", "Prague, Karlštejn, Bohemian Karst", 50.08, 14.43, 0.30, 0.42),
        OfflinePresetRegion("plzensky_kraj", "Plzeň Region", "Plzeň, Klatovy, Domažlice, Tachov", 49.58, 13.28, 0.65, 0.80),
        OfflinePresetRegion("liberecky_kraj", "Liberec Region", "Liberec, Jablonec n. N., Česká Lípa, Semily", 50.72, 15.05, 0.45, 0.65),
        OfflinePresetRegion("ustecky_kraj", "Ústí nad Labem Region", "Ústí n. L., Děčín, Most, Teplice, Chomutov", 50.55, 13.85, 0.55, 0.85),
        OfflinePresetRegion("karlovarsky_kraj", "Karlovy Vary Region", "Karlovy Vary, Cheb, Sokolov, Mar. Lázně", 50.18, 12.75, 0.45, 0.65)
    )

    private val _catalogRegions = MutableStateFlow<List<OfflinePresetRegion>>(presetRegions)
    val catalogRegions: StateFlow<List<OfflinePresetRegion>> = _catalogRegions.asStateFlow()

    private val _customTrailsGeoJson = MutableStateFlow<String?>(null)
    val customTrailsGeoJson: StateFlow<String?> = _customTrailsGeoJson.asStateFlow()

    private val _customPoisGeoJson = MutableStateFlow<String?>(null)
    val customPoisGeoJson: StateFlow<String?> = _customPoisGeoJson.asStateFlow()

    private val _activePois = MutableStateFlow<List<OutdoorPoi>>(emptyList())
    val activePois: StateFlow<List<OutdoorPoi>> = _activePois.asStateFlow()

    @Volatile
    private var isInitialized = false
    @Volatile
    private var isDownloading = false
    @Volatile
    private var isVectorDownloadInFlight = false
    @Volatile
    private var isPackageSyncInFlight = false
    @Volatile
    private var isUserBrowsingMap = false
    @Volatile
    private var lastViewportLat = 49.1246
    @Volatile
    private var lastViewportLon = 16.3353

    @Volatile
    private var localStyleServerPort: Int = 0

    private val OFFLINE_DOWNLOAD_STYLE_JSON = """
        {
          "version": 8,
          "name": "OffGrid Download Definition",
          "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
          "sources": {
            "offline_source": {
              "type": "vector",
              "tiles": ["https://tiles.openfreemap.org/planet/20261025_080012_pt/{z}/{x}/{y}.pbf"],
              "minzoom": 0,
              "maxzoom": 14
            }
          },
          "layers": [
            {
              "id": "place_labels",
              "type": "symbol",
              "source": "offline_source",
              "source-layer": "place",
              "layout": {
                "text-field": ["get", "name"],
                "text-font": ["Noto Sans Regular"],
                "text-size": 11
              }
            }
          ]
        }
    """.trimIndent()

    @Synchronized
    private fun ensureLocalStyleServerUrl(): String {
        if (localStyleServerPort > 0) {
            return "http://127.0.0.1:$localStyleServerPort/style.json"
        }
        val serverSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        localStyleServerPort = serverSocket.localPort
        val bodyBytes = OFFLINE_DOWNLOAD_STYLE_JSON.toByteArray(StandardCharsets.UTF_8)

        thread(name = "OffGridLocalStyleServer", isDaemon = true) {
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()
                    client.soTimeout = 3000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    while (true) {
                        val line = reader.readLine()
                        if (line == null || line.isEmpty()) break
                    }
                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ${bodyBytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(StandardCharsets.UTF_8)

                    val out = client.getOutputStream()
                    out.write(header)
                    out.write(bodyBytes)
                    out.flush()
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
        return "http://127.0.0.1:$localStyleServerPort/style.json"
    }

    fun setBrowsingMode(browsing: Boolean) {
        isUserBrowsingMap = browsing
        if (browsing) {
            MapLibre.setConnected(true)
        } else if (!isDownloading && _isOfflineReady.value) {
            MapLibre.setConnected(false)
        }
    }

    /**
     * Refreshes the list of all saved offline regions in watch storage and automatically
     * removes older duplicate entries.
     */
    fun refreshSavedRegions(context: Context) {
        val offlineManager = OfflineManager.getInstance(context)
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val sortedRegions = offlineRegions?.sortedByDescending { it.id } ?: emptyList()
                val uniqueMapped = mutableListOf<SavedOfflineRegion>()
                val seenTitles = mutableSetOf<String>()

                for (region in sortedRegions) {
                    val rawMeta = String(region.metadata, StandardCharsets.UTF_8)
                    val bounds = region.definition.bounds
                    val centerLat = bounds?.center?.latitude ?: 49.1246
                    val centerLon = bounds?.center?.longitude ?: 16.3353
                    val isJmk = rawMeta.startsWith("Jihomoravsky_kraj")
                    val title = if (isJmk) {
                        "South Moravia"
                    } else if (rawMeta.contains("|")) {
                        val rawTitle = rawMeta.substringAfter("|")
                        if (rawTitle == "Jihomoravský kraj") "South Moravia" else rawTitle
                    } else {
                        resolveCzechRegionTitle(centerLat, centerLon)
                    }

                    // Delete older duplicate versions of the same region from the database
                    if (!seenTitles.add(title)) {
                        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() {}
                            override fun onError(error: String) {}
                        })
                        continue
                    }

                    uniqueMapped.add(
                        SavedOfflineRegion(
                            id = region.id,
                            rawMetadata = rawMeta,
                            displayName = title,
                            centerLat = centerLat,
                            centerLon = centerLon,
                            isDefaultJmk = isJmk
                        )
                    )
                }

                // Keep default South Moravia at the top, followed by newest regions
                val sorted = uniqueMapped.sortedWith(
                    compareByDescending<SavedOfflineRegion> { it.isDefaultJmk }
                        .thenByDescending { it.id }
                )
                _savedRegions.value = sorted
                val hasRegions = sorted.isNotEmpty()
                _isOfflineReady.value = hasRegions
                if (!isDownloading) {
                    _statusText.value = if (hasRegions) "OFFLINE" else "NO MAP"
                    if (!hasRegions) {
                        MapLibre.setConnected(true)
                    }
                }
            }

            override fun onError(error: String) {
            }
        })
    }

    /**
     * Deletes the selected offline region from the watch database.
     */
    fun deleteOfflineRegion(context: Context, regionId: Long) {
        _savedRegions.value.firstOrNull { it.id == regionId }?.let { region ->
            deleteTrailPackage(context, region.displayName)
        }
        val offlineManager = OfflineManager.getInstance(context)
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val target = offlineRegions?.firstOrNull { it.id == regionId } ?: return
                target.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() {
                        refreshSavedRegions(context)
                    }

                    override fun onError(error: String) {
                        refreshSavedRegions(context)
                    }
                })
            }

            override fun onError(error: String) {
            }
        })
    }

    /**
     * Initializes offline region state around the user's current/last known coordinates without
     * forcing a hardcoded South Moravia vector download for users in other regions.
     */
    fun initializeOfflineRegions(context: Context, centerLat: Double, centerLon: Double) {
        ContourTileManager.loadCachedCustomContours(context, centerLat, centerLon)
        updateTrailsForViewport(context, centerLat, centerLon)
        if (isInitialized) {
            refreshSavedRegions(context)
            return
        }
        isInitialized = true

        val offlineManager = OfflineManager.getInstance(context)
        offlineManager.setOfflineMapboxTileCountLimit(80000L)

        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                refreshSavedRegions(context)
                val regions = offlineRegions ?: emptyArray()
                if (regions.isEmpty()) {
                    _isOfflineReady.value = false
                    _statusText.value = "NO MAP"
                    MapLibre.setConnected(true)
                    return
                }

                // Resume the latest region if its download was interrupted, otherwise lock offline mode
                val latest = regions.maxByOrNull { it.id } ?: return
                latest.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        if (status != null && !status.isComplete) {
                            startRegionDownload(context, latest)
                        } else {
                            finishDownloadPart(context, vectorDone = true)
                        }
                    }

                    override fun onError(error: String?) {
                        finishDownloadPart(context, vectorDone = true)
                    }
                })
            }

            override fun onError(error: String) {
                _statusText.value = "NO MAP"
            }
        })
    }

    private fun startRegionDownload(context: Context, offlineRegion: OfflineRegion) {
        synchronized(downloadLock) {
            isDownloading = true
            isVectorDownloadInFlight = true
        }
        acquireDownloadWifiAndWakeLocks(context)
        _downloadError.value = null
        MapLibre.setConnected(true)
        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    _downloadError.value = null
                    finishDownloadPart(context, vectorDone = true)
                } else if (status.requiredResourceCount > 0) {
                    val pct = (100.0 * status.completedResourceCount / status.requiredResourceCount)
                        .toInt()
                        .coerceIn(0, 99)
                    _statusText.value = "DL $pct%"
                }
            }

            override fun onError(error: OfflineRegionError) {
                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                _downloadError.value = "Connection lost – check Wi-Fi"
                finishDownloadPart(context, vectorDone = true)
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                _downloadError.value = "Storage limit reached – delete old region"
                finishDownloadPart(context, vectorDone = true)
            }
        })
    }

    /**
     * Loads the latest `catalog.json` from the private GitHub repository (`BuildConfig.MAPS_GITHUB_REPO`)
     * listing all countries and regions (or falls back to the local cache / bundled asset when offline).
     */
    fun syncCatalogFromGithub(context: Context) {
        val cacheFile = File(context.filesDir, CACHED_CATALOG_FILE)
        if (cacheFile.exists() && cacheFile.length() > 10L) {
            parseCatalogJson(cacheFile.readText())?.let { cachedList ->
                if (cachedList.isNotEmpty()) {
                    _catalogRegions.value = cachedList
                }
            }
        } else {
            try {
                val assetJson = context.assets.open(ASSET_DEFAULT_CATALOG_FILE).bufferedReader().use { it.readText() }
                parseCatalogJson(assetJson)?.let { bundledList ->
                    if (bundledList.isNotEmpty()) {
                        _catalogRegions.value = bundledList
                    }
                }
            } catch (_: Exception) {
            }
        }

        thread(name = "OffGridCatalogSync", isDaemon = true) {
            try {
                val repo = BuildConfig.MAPS_GITHUB_REPO.ifBlank { "Koncpa/offgrid-maps-data" }
                val cdnBase = (activeCatalogReleaseBaseUrl ?: BuildConfig.MAPS_CDN_BASE_URL)
                    .trim()
                    .trimEnd('/')
                val candidates = buildList {
                    if (cdnBase.isNotBlank()) add("$cdnBase/catalog.json" to false)
                    add("https://raw.githubusercontent.com/$repo/main/catalog.json" to true)
                }
                for ((catalogUrl, sendToken) in candidates) {
                    val conn = (URL(catalogUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        val token = BuildConfig.MAPS_GITHUB_TOKEN
                        if (sendToken && token.isNotBlank()) {
                            setRequestProperty("Authorization", "Bearer $token")
                        }
                    }
                    var updated = false
                    try {
                        if (conn.responseCode in 200..299) {
                            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                            val parsed = parseCatalogJson(jsonText)
                            if (!parsed.isNullOrEmpty()) {
                                cacheFile.writeText(jsonText)
                                _catalogRegions.value = parsed
                                updated = true
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    if (updated) break
                }
            } catch (_: Exception) {
            }
        }
    }

    internal data class ZipExtractResult(
        val trailsOk: Boolean,
        val poisOk: Boolean,
        val contoursOk: Boolean
    )

    internal fun extractPackageZip(
        zipStream: InputStream,
        pkgDir: File,
        importContours: (InputStream) -> Boolean
    ): ZipExtractResult {
        var trailsOk = false
        var poisOk = false
        var contoursOk = false
        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryBytes = zis.readBytes()
                when (entry.name) {
                    "region_meta.json" -> {
                        File(pkgDir, "region_meta.json").writeBytes(entryBytes)
                    }
                    "kct_trails.geojson.gz" -> {
                        val geoJson = GZIPInputStream(entryBytes.inputStream()).bufferedReader().use { it.readText() }
                        File(pkgDir, TRAILS_FRAGMENT_FILE).writeText(extractFeatureArray(geoJson))
                        trailsOk = true
                    }
                    "outdoor_pois.geojson.gz" -> {
                        val geoJson = GZIPInputStream(entryBytes.inputStream()).bufferedReader().use { it.readText() }
                        File(pkgDir, POIS_FRAGMENT_FILE).writeText(extractFeatureArray(geoJson))
                        poisOk = true
                    }
                    "contours_z12.tiles.gz" -> {
                        contoursOk = entryBytes.inputStream().use { importContours(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return ZipExtractResult(trailsOk, poisOk, contoursOk)
    }

    internal fun resolveCdnZipUrl(preset: OfflinePresetRegion, defaultCdnBaseUrl: String): String? {
        val base = (preset.releaseBaseUrl ?: defaultCdnBaseUrl).trim().trimEnd('/')
        val tag = preset.releaseTag?.takeIf { it.isNotBlank() } ?: return null
        if (base.isBlank()) return null
        val asset = preset.assetName?.takeIf { it.isNotBlank() } ?: "${preset.id}.zip"
        return "$base/releases/$tag/$asset"
    }

    internal fun parseCatalogJson(jsonText: String): List<OfflinePresetRegion>? {
        return try {
            val root = JSONObject(jsonText)
            val rootReleaseBase = root.optString("releaseBaseUrl", "")
                .trim()
                .trimEnd('/')
                .takeIf { it.isNotBlank() }
            if (rootReleaseBase != null) {
                activeCatalogReleaseBaseUrl = rootReleaseBase
            }
            val countries = root.optJSONArray("countries") ?: return null
            val result = mutableListOf<OfflinePresetRegion>()
            for (i in 0 until countries.length()) {
                val countryObj = countries.getJSONObject(i)
                val countryCode = countryObj.optString("countryCode", "CZ")
                val countryName = countryObj.optString("countryName", "Czechia – Regions")
                val continent = countryObj.optString("continent", "").takeIf { it.isNotBlank() }
                    ?: resolveContinent(countryCode)
                val regions = countryObj.optJSONArray("regions") ?: continue
                for (j in 0 until regions.length()) {
                    val reg = regions.getJSONObject(j)
                    val id = reg.getString("id")
                    val regionReleaseBase = reg.optString("releaseBaseUrl", "")
                        .trim()
                        .trimEnd('/')
                        .takeIf { it.isNotBlank() }
                        ?: rootReleaseBase
                    result.add(
                        OfflinePresetRegion(
                            id = id,
                            title = reg.getString("title"),
                            subtitle = reg.optString("subtitle", ""),
                            centerLat = reg.getDouble("centerLat"),
                            centerLon = reg.getDouble("centerLon"),
                            latSpanDeg = reg.optDouble("latSpanDeg", 0.50),
                            lonSpanDeg = reg.optDouble("lonSpanDeg", 0.75),
                            countryCode = countryCode,
                            countryName = countryName,
                            packagePath = reg.optString("packagePath", "maps/${countryCode.lowercase(Locale.US)}/$id"),
                            continent = continent,
                            releaseTag = reg.optString("releaseTag", "").takeIf { it.isNotBlank() },
                            assetName = reg.optString("assetName", "").takeIf { it.isNotBlank() },
                            releaseBaseUrl = regionReleaseBase
                        )
                    )
                }
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    internal fun enqueueUniquePreset(
        queue: MutableList<OfflinePresetRegion>,
        currentActiveId: String?,
        preset: OfflinePresetRegion
    ): Boolean {
        if (currentActiveId == preset.id || queue.any { it.id == preset.id }) {
            return false
        }
        queue.add(preset)
        return true
    }

    /**
     * Removes a waiting region from the sequential download queue (does not abort the active one).
     */
    fun removeFromDownloadQueue(presetId: String) {
        synchronized(downloadLock) {
            if (pendingDownloadQueue.removeAll { it.id == presetId }) {
                _queuedRegions.value = pendingDownloadQueue.toList()
            }
        }
    }

    /**
     * Clears all waiting regions from the download queue.
     */
    fun clearDownloadQueue() {
        synchronized(downloadLock) {
            pendingDownloadQueue.clear()
            _queuedRegions.value = emptyList()
        }
    }

    /**
     * Enqueues or starts downloading the selected catalog region ([preset]) with a single tap,
     * preferring single-ZIP bundles from Cloudflare R2 CDN / GitHub Releases with fallback to raw files.
     */
    fun downloadPresetRegion(
        context: Context,
        preset: OfflinePresetRegion
    ) {
        val appContext = context.applicationContext
        var startNow = false
        synchronized(downloadLock) {
            if (isDownloading) {
                if (enqueueUniquePreset(pendingDownloadQueue, activeDownloadId, preset)) {
                    _queuedRegions.value = pendingDownloadQueue.toList()
                }
            } else {
                isDownloading = true
                isPackageSyncInFlight = true
                isVectorDownloadInFlight = true
                activeDownloadId = preset.id
                _activeDownloadTitle.value = preset.title
                _downloadError.value = null
                _statusText.value = "DL 0%"
                startNow = true
            }
        }
        if (startNow) {
            startPresetDownloadInternal(appContext, preset)
        }
    }

    private fun startPresetDownloadInternal(
        appContext: Context,
        preset: OfflinePresetRegion
    ) {
        acquireDownloadWifiAndWakeLocks(appContext)
        thread(name = "OffGridGithubPkg_${preset.id}", isDaemon = true) {
            try {
                val pkgDir = File(appContext.filesDir, "$PACKAGES_DIR/${preset.id}").apply {
                    if (!exists()) mkdirs()
                }
                val zipResult = downloadReleaseZipPackage(appContext, pkgDir, preset)
                val trailsOk: Boolean
                val contoursImported: Boolean
                if (zipResult != null && (zipResult.trailsOk || zipResult.contoursOk)) {
                    trailsOk = zipResult.trailsOk
                    contoursImported = zipResult.contoursOk
                } else {
                    downloadPackageFile(preset, "region_meta.json") { input ->
                        File(pkgDir, "region_meta.json").outputStream().use { input.copyTo(it) }
                    }
                    trailsOk = downloadPackageFile(preset, "kct_trails.geojson.gz") { input ->
                        val geoJson = GZIPInputStream(input).bufferedReader().use { it.readText() }
                        File(pkgDir, TRAILS_FRAGMENT_FILE).writeText(extractFeatureArray(geoJson))
                    }
                    downloadPackageFile(preset, "outdoor_pois.geojson.gz") { input ->
                        val geoJson = GZIPInputStream(input).bufferedReader().use { it.readText() }
                        File(pkgDir, POIS_FRAGMENT_FILE).writeText(extractFeatureArray(geoJson))
                    }
                    contoursImported = downloadPackageFile(preset, "contours_z12.tiles.gz") { input ->
                        ContourTileManager.importTilePackage(appContext, input)
                    }
                }
                if (!contoursImported) {
                    ContourTileManager.downloadContoursForArea(appContext, preset.centerLat, preset.centerLon)
                }
                ContourTileManager.ensureViewportContours(
                    context = appContext,
                    centerLat = lastViewportLat,
                    centerLon = lastViewportLon,
                    zoom = 13.0,
                    allowNetworkFetch = false
                )
                if (!trailsOk && !contoursImported) {
                    _downloadError.value = "Package sync failed – check Wi-Fi"
                }
                trailExecutor.execute { invalidateTrailPackages() }
                updateTrailsForViewport(appContext, lastViewportLat, lastViewportLon)
            } finally {
                finishDownloadPart(appContext, packageDone = true)
            }
        }

        // 2. Download the vector tile pyramid on main looper for MapLibre thread safety
        val startVector = {
            downloadCustomBounds(
                context = appContext,
                centerLat = preset.centerLat,
                centerLon = preset.centerLon,
                halfLatSpan = preset.latSpanDeg / 2.0,
                halfLonSpan = preset.lonSpanDeg / 2.0,
                explicitTitle = preset.title
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startVector()
        } else {
            mainHandler.post { startVector() }
        }
    }

    private fun resolveReleaseAssetUrl(repo: String, releaseTag: String, assetName: String): String? {
        val cached = releaseAssetUrlCache[releaseTag]?.get(assetName)
        if (cached != null) return cached

        val token = BuildConfig.MAPS_GITHUB_TOKEN
        val url = URL("https://api.github.com/repos/$repo/releases/tags/$releaseTag")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val assets = JSONObject(body).optJSONArray("assets") ?: return null
            val map = HashMap<String, String>(assets.length())
            for (i in 0 until assets.length()) {
                val obj = assets.getJSONObject(i)
                val name = obj.optString("name", "")
                val apiUrl = obj.optString("url", "")
                if (name.isNotBlank() && apiUrl.isNotBlank()) {
                    map[name] = apiUrl
                }
            }
            releaseAssetUrlCache[releaseTag] = map
            map[assetName]
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun streamZipFromUrl(
        context: Context,
        pkgDir: File,
        downloadUrl: String
    ): ZipExtractResult? = streamZipFromUrlWithImporter(pkgDir, downloadUrl) { contourStream ->
        ContourTileManager.importTilePackage(context, contourStream) > 0
    }

    internal fun streamZipFromUrlWithImporter(
        pkgDir: File,
        downloadUrl: String,
        contourImporter: (InputStream) -> Boolean
    ): ZipExtractResult? {
        return try {
            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 60000
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { input ->
                    extractPackageZip(input, pkgDir, contourImporter)
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadReleaseZipPackage(
        context: Context,
        pkgDir: File,
        preset: OfflinePresetRegion
    ): ZipExtractResult? {
        val cdnDefault = activeCatalogReleaseBaseUrl ?: BuildConfig.MAPS_CDN_BASE_URL
        resolveCdnZipUrl(preset, cdnDefault)?.let { cdnUrl ->
            streamZipFromUrl(context, pkgDir, cdnUrl)?.let { return it }
        }

        val releaseTag = preset.releaseTag ?: return null
        val assetName = preset.assetName ?: "${preset.id}.zip"
        val repo = BuildConfig.MAPS_GITHUB_REPO.ifBlank { "Koncpa/offgrid-maps-data" }
        val token = BuildConfig.MAPS_GITHUB_TOKEN

        if (token.isBlank()) {
            val publicUrl = "https://github.com/$repo/releases/download/$releaseTag/$assetName"
            return streamZipFromUrl(context, pkgDir, publicUrl)
        }

        val apiUrl = resolveReleaseAssetUrl(repo, releaseTag, assetName) ?: return null
        return try {
            // Request with instanceFollowRedirects=false so Authorization header is NOT sent to S3 redirect
            val ghConn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = ghConn.responseCode
            val streamConn: HttpURLConnection = if (code in 300..399) {
                val redirectUrl = ghConn.getHeaderField("Location")
                ghConn.disconnect()
                if (redirectUrl.isNullOrBlank()) return null
                (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 60000
                }
            } else {
                ghConn
            }
            try {
                if (streamConn.responseCode !in 200..299) return null
                streamConn.inputStream.use { input ->
                    extractPackageZip(input, pkgDir) { contourStream ->
                        ContourTileManager.importTilePackage(context, contourStream) > 0
                    }
                }
            } finally {
                streamConn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Streams one package file from the map data repository into [consume].
     * @return true when the file was downloaded and processed successfully
     */
    private fun downloadPackageFile(
        preset: OfflinePresetRegion,
        fileName: String,
        consume: (InputStream) -> Unit
    ): Boolean {
        return try {
            val repo = BuildConfig.MAPS_GITHUB_REPO.ifBlank { "Koncpa/offgrid-maps-data" }
            val url = URL("https://raw.githubusercontent.com/$repo/main/${preset.packagePath}/$fileName")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
                val token = BuildConfig.MAPS_GITHUB_TOKEN
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            try {
                if (conn.responseCode !in 200..299) return false
                conn.inputStream.use(consume)
                true
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns the content of the `features` array of a FeatureCollection without parsing it.
     * The package builder always writes `features` as the last key, so the array spans from
     * `"features":[` to the final `]`. Avoiding a full JSON parse keeps memory low on the watch.
     */
    internal fun extractFeatureArray(featureCollection: String): String {
        val marker = "\"features\":["
        val start = featureCollection.indexOf(marker)
        val end = featureCollection.lastIndexOf(']')
        if (start < 0 || end < start + marker.length) return ""
        return featureCollection.substring(start + marker.length, end).trim()
    }

    /** Downloaded trail package: bounds are [north, east, south, west] as in `region_meta.json`. */
    private class TrailPackage(val dir: File, val title: String, val bounds: DoubleArray)

    @Volatile
    private var trailPackages: List<TrailPackage>? = null
    @Volatile
    private var activeTrailKey: String? = null
    @Volatile
    private var bundledJmkPoisFragment: String? = null

    private fun invalidateTrailPackages() {
        trailPackages = null
        activeTrailKey = null
    }

    private fun loadBundledJmkPoisFragment(context: Context): String {
        bundledJmkPoisFragment?.let { return it }
        return try {
            val raw = context.assets.open(ASSET_JMK_POIS_FILE).bufferedReader().use { it.readText() }
            extractFeatureArray(raw).also { bundledJmkPoisFragment = it }
        } catch (_: Exception) {
            ""
        }
    }

    private fun loadTrailPackages(context: Context): List<TrailPackage> {
        trailPackages?.let { return it }
        val dirs = File(context.filesDir, PACKAGES_DIR).listFiles() ?: emptyArray()
        val loaded = dirs.mapNotNull { dir ->
            try {
                val meta = JSONObject(File(dir, "region_meta.json").readText())
                val b = meta.getJSONArray("bounds")
                TrailPackage(dir, meta.optString("title"), DoubleArray(4) { b.getDouble(it) })
            } catch (_: Exception) {
                null
            }
        }
        trailPackages = loaded
        return loaded
    }

    /**
     * Shows the marked trails and outdoor POIs of every downloaded region that covers the given map position.
     * Only nearby regions are kept in memory, so having many regions on the watch does not
     * increase RAM use. Called on camera idle; does nothing when the region set is unchanged.
     */
    fun updateTrailsForViewport(context: Context, lat: Double, lon: Double) {
        lastViewportLat = lat
        lastViewportLon = lon
        trailExecutor.execute {
            refreshActiveViewportOnExecutor(context, lat, lon)
        }
    }

    private fun refreshActiveViewportOnExecutor(context: Context, lat: Double, lon: Double) {
        val margin = 0.05
        val visible = loadTrailPackages(context).filter { p ->
            lat <= p.bounds[0] + margin && lat >= p.bounds[2] - margin &&
                lon <= p.bounds[1] + margin && lon >= p.bounds[3] - margin
        }
        val inJmk = lat in 48.70..49.50 && lon in 15.90..17.00
        val key = visible.joinToString(",") { it.dir.name } + "|jmk=$inJmk"
        if (key == activeTrailKey) return
        activeTrailKey = key

        val trailFragments = visible.mapNotNull { p ->
            File(p.dir, TRAILS_FRAGMENT_FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        }
        _customTrailsGeoJson.value = """{"type":"FeatureCollection","features":[${trailFragments.joinToString(",")}]}"""

        val poiFragments = mutableListOf<String>()
        if (inJmk) {
            val jmkFrag = loadBundledJmkPoisFragment(context)
            if (jmkFrag.isNotBlank()) poiFragments.add(jmkFrag)
        }
        visible.forEach { p ->
            val frag = File(p.dir, POIS_FRAGMENT_FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
            if (frag != null) poiFragments.add(frag)
        }
        val combinedPoisGeoJson = """{"type":"FeatureCollection","features":[${poiFragments.joinToString(",")}]}"""
        _customPoisGeoJson.value = combinedPoisGeoJson
        _activePois.value = parseOutdoorPois(combinedPoisGeoJson)
    }

    private val POI_FEATURE_REGEX = Regex(
        """\"cat\"\s*:\s*\"([^\"]+)\"\s*,\s*\"label\"\s*:\s*\"((?:[^\"\\]|\\.)+)\"[^}]*\}\s*,\s*\"geometry\"\s*:\s*\{\s*\"type\"\s*:\s*\"Point\"\s*,\s*\"coordinates\"\s*:\s*\[\s*([-0-9.]+)\s*,\s*([-0-9.]+)\s*\]"""
    )

    internal fun parseOutdoorPois(featureCollectionJson: String): List<OutdoorPoi> {
        val list = mutableListOf<OutdoorPoi>()
        for (match in POI_FEATURE_REGEX.findAll(featureCollectionJson)) {
            val cat = match.groupValues[1]
            val label = match.groupValues[2].replace("\\\"", "\"")
            val lon = match.groupValues[3].toDoubleOrNull() ?: continue
            val lat = match.groupValues[4].toDoubleOrNull() ?: continue
            if (label.isNotBlank()) {
                list.add(OutdoorPoi(category = cat, label = label, lat = lat, lon = lon))
            }
        }
        return list
    }

    /** Removes the downloaded trail package that belongs to a deleted offline region. */
    private fun deleteTrailPackage(context: Context, regionTitle: String) {
        val appCtx = context.applicationContext
        trailExecutor.execute {
            loadTrailPackages(appCtx).filter { it.title == regionTitle }.forEach { it.dir.deleteRecursively() }
            invalidateTrailPackages()
            refreshActiveViewportOnExecutor(appCtx, lastViewportLat, lastViewportLon)
        }
    }

    internal fun computeAdaptiveZoomRange(halfLatSpan: Double, halfLonSpan: Double): Pair<Double, Double> {
        val maxSpan = max(halfLatSpan, halfLonSpan) * 2.0
        return when {
            maxSpan <= 1.8 -> 8.0 to 14.0
            maxSpan <= 4.0 -> 8.0 to 13.0
            maxSpan <= 9.0 -> 7.0 to 12.0
            else -> 6.0 to 11.0
        }
    }

    private fun downloadCustomBounds(
        context: Context,
        centerLat: Double,
        centerLon: Double,
        halfLatSpan: Double,
        halfLonSpan: Double,
        explicitTitle: String
    ) {
        val offlineManager = OfflineManager.getInstance(context)
        offlineManager.setOfflineMapboxTileCountLimit(80000L)

        val styleUrl = ensureLocalStyleServerUrl()

        val bounds = LatLngBounds.from(
            (centerLat + halfLatSpan).coerceIn(-85.0, 85.0),
            (centerLon + halfLonSpan).coerceIn(-179.9, 179.9),
            (centerLat - halfLatSpan).coerceIn(-85.0, 85.0),
            (centerLon - halfLonSpan).coerceIn(-179.9, 179.9)
        )

        val (minZoom, maxZoom) = computeAdaptiveZoomRange(halfLatSpan, halfLonSpan)
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            minZoom,
            maxZoom,
            1.0f
        )

        val regionMeta = "Custom_${"%.2f".format(Locale.US, centerLat)}_${"%.2f".format(Locale.US, centerLon)}|$explicitTitle"
        val metadata = regionMeta.toByteArray(StandardCharsets.UTF_8)

        synchronized(downloadLock) {
            isDownloading = true
            isVectorDownloadInFlight = true
            _activeDownloadTitle.value = explicitTitle
            _statusText.value = "DL 0%"
        }
        MapLibre.setConnected(true)

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    refreshSavedRegions(context)
                    startRegionDownload(context, offlineRegion)
                }

                override fun onError(error: String) {
                    finishDownloadPart(context, vectorDone = true)
                }
            }
        )
    }

    private fun finishDownloadPart(
        context: Context,
        vectorDone: Boolean = false,
        packageDone: Boolean = false
    ) {
        val appContext = context.applicationContext
        var nextPreset: OfflinePresetRegion? = null
        var hasSavedSnapshot = false
        synchronized(downloadLock) {
            if (vectorDone) isVectorDownloadInFlight = false
            if (packageDone) isPackageSyncInFlight = false
            if (isVectorDownloadInFlight || isPackageSyncInFlight) return

            if (pendingDownloadQueue.isNotEmpty()) {
                val dequeued = pendingDownloadQueue.removeAt(0)
                _queuedRegions.value = pendingDownloadQueue.toList()
                isDownloading = true
                isPackageSyncInFlight = true
                isVectorDownloadInFlight = true
                activeDownloadId = dequeued.id
                _activeDownloadTitle.value = dequeued.title
                _statusText.value = "DL 0%"
                nextPreset = dequeued
            } else {
                isDownloading = false
                activeDownloadId = null
                _activeDownloadTitle.value = null
                hasSavedSnapshot = _savedRegions.value.isNotEmpty()
                _isOfflineReady.value = hasSavedSnapshot
                _statusText.value = if (hasSavedSnapshot) "OFFLINE" else "NO MAP"
            }
        }
        refreshSavedRegions(appContext)
        val presetToStart = nextPreset
        if (presetToStart != null) {
            startPresetDownloadInternal(appContext, presetToStart)
        } else {
            releaseDownloadWifiAndWakeLocks(appContext)
            if (!isUserBrowsingMap && hasSavedSnapshot) {
                MapLibre.setConnected(false)
            }
        }
    }

    /**
     * Keeps the watch Wi-Fi radio awake and bound during active region downloads so Wear OS
     * does not drop Wi-Fi or route large tile transfers over Bluetooth proxy.
     */
    @SuppressLint("MissingPermission")
    private fun acquireDownloadWifiAndWakeLocks(context: Context) {
        val appCtx = context.applicationContext
        synchronized(downloadLock) {
            try {
                if (activeDownloadWakeLock == null) {
                    val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    activeDownloadWakeLock = pm?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "OffGridMaps:MapDownload"
                    )?.apply { setReferenceCounted(false) }
                }
                if (activeDownloadWakeLock?.isHeld == false) {
                    activeDownloadWakeLock?.acquire(20 * 60 * 1000L)
                }
            } catch (_: Exception) {
            }

            try {
                if (activeWifiLock == null) {
                    val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    activeWifiLock = wm?.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        "OffGridMaps:MapDownload"
                    )?.apply { setReferenceCounted(false) }
                }
                if (activeWifiLock?.isHeld == false) {
                    activeWifiLock?.acquire()
                }
            } catch (_: Exception) {
            }

            if (activeWifiNetworkCallback == null) {
                try {
                    val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                        ?: return
                    val req = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            synchronized(downloadLock) {
                                if (!isDownloading) return
                                boundWifiNetwork = network
                            }
                            try {
                                cm.bindProcessToNetwork(network)
                            } catch (_: Exception) {
                            }
                        }

                        override fun onLost(network: Network) {
                            val shouldUnbind = synchronized(downloadLock) {
                                if (boundWifiNetwork == network) {
                                    boundWifiNetwork = null
                                    true
                                } else {
                                    false
                                }
                            }
                            if (shouldUnbind) {
                                try {
                                    cm.bindProcessToNetwork(null)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    activeWifiNetworkCallback = cb
                    cm.requestNetwork(req, cb)
                } catch (_: Exception) {
                    activeWifiNetworkCallback = null
                }
            }
        }
    }

    private fun releaseDownloadWifiAndWakeLocks(context: Context) {
        val appCtx = context.applicationContext
        val cb: ConnectivityManager.NetworkCallback?
        val wifiLock: WifiManager.WifiLock?
        val wakeLock: PowerManager.WakeLock?
        synchronized(downloadLock) {
            cb = activeWifiNetworkCallback
            activeWifiNetworkCallback = null
            boundWifiNetwork = null
            wifiLock = activeWifiLock
            activeWifiLock = null
            wakeLock = activeDownloadWakeLock
            activeDownloadWakeLock = null
        }

        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.bindProcessToNetwork(null)
            if (cb != null) cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock.release()
        } catch (_: Exception) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (_: Exception) {
        }
    }

    /**
     * Automatically resolves a human-readable English region title for given coordinates.
     */
    fun resolveCzechRegionTitle(lat: Double, lon: Double): String {
        data class Landmark(val name: String, val lat: Double, val lon: Double, val radiusDeg: Double)
        val landmarks = listOf(
            Landmark("Krkonoše", 50.73, 15.65, 0.30),
            Landmark("Šumava", 49.02, 13.55, 0.40),
            Landmark("Beskydy", 49.50, 18.42, 0.35),
            Landmark("Jeseníky", 50.08, 17.22, 0.35),
            Landmark("Jizera Mountains", 50.83, 15.25, 0.25),
            Landmark("Orlické Mountains", 50.28, 16.40, 0.28),
            Landmark("Ore Mountains", 50.42, 13.05, 0.40),
            Landmark("Bohemian Paradise", 50.55, 15.20, 0.22),
            Landmark("Bohemian Switzerland", 50.88, 14.35, 0.22),
            Landmark("Brdy & Křivoklátsko", 49.75, 13.85, 0.32),
            Landmark("Prague & Surroundings", 50.08, 14.43, 0.32),
            Landmark("Vysočina Region", 49.58, 15.95, 0.40),
            Landmark("South Bohemia", 49.05, 14.48, 0.45),
            Landmark("Plzeň Region", 49.75, 13.35, 0.45),
            Landmark("Hradec Králové Region", 50.15, 15.82, 0.45),
            Landmark("Central Bohemia", 49.95, 14.85, 0.45),
            Landmark("Ústí nad Labem Region", 50.62, 14.05, 0.45),
            Landmark("Olomouc Region", 49.55, 17.25, 0.45),
            Landmark("South Moravia", 49.15, 16.45, 0.45)
        )
        val match = landmarks.minByOrNull { hypot(lat - it.lat, lon - it.lon) }
        if (match != null && hypot(lat - match.lat, lon - match.lon) <= match.radiusDeg) {
            return match.name
        }
        val regionPrefix = if (lon < 15.6) "Bohemia" else "Moravia"
        return "$regionPrefix (${"%.2f".format(Locale.US, lat)}, ${"%.2f".format(Locale.US, lon)})"
    }
}
