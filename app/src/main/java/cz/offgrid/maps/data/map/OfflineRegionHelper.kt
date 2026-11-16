package cz.offgrid.maps.data.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * Datový model pro uloženou offline mapovou oblast zobrazenou v přehledu.
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
 * Předdefinovaná turistická oblast / pohoří v katalogu pro rychlé stažení jedním klepnutím.
 */
data class OfflinePresetRegion(
    val id: String,
    val title: String,
    val subtitle: String,
    val centerLat: Double,
    val centerLon: Double,
    val latSpanDeg: Double = 0.26,
    val lonSpanDeg: Double = 0.36
)

/**
 * Správce offline mapových regionů (MapLibre OfflineManager) s lokálním HTTP loopbackem,
 * seznamem stažených oblastí a katalogem českých pohoří a krajů.
 */
object OfflineRegionHelper {

    private const val REGION_NAME_JMK = "Jihomoravsky_kraj_z14_v3"

    private val _statusText = MutableStateFlow("JMK")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _activeDownloadTitle = MutableStateFlow<String?>(null)
    val activeDownloadTitle: StateFlow<String?> = _activeDownloadTitle.asStateFlow()

    private val _isOfflineReady = MutableStateFlow(false)
    val isOfflineReady: StateFlow<Boolean> = _isOfflineReady.asStateFlow()

    private val _savedRegions = MutableStateFlow<List<SavedOfflineRegion>>(emptyList())
    val savedRegions: StateFlow<List<SavedOfflineRegion>> = _savedRegions.asStateFlow()

    /**
     * Katalog krajů České republiky podle struktury Mapy.com pro rychlé stažení jedním klepnutím
     * bez nutnosti ručního posouvání mapy na malém displeji hodinek.
     */
    val presetRegions: List<OfflinePresetRegion> = listOf(
        OfflinePresetRegion("kraj_vysocina", "Kraj Vysočina", "Jihlava, Třebíč, Žďár n. S., Havl. Brod", 49.45, 15.68, 0.55, 0.75),
        OfflinePresetRegion("jihocesky_kraj", "Jihočeský kraj", "Č. Budějovice, Tábor, Č. Krumlov, Písek", 49.05, 14.48, 0.65, 0.90),
        OfflinePresetRegion("zlinsky_kraj", "Zlínský kraj", "Zlín, Vsetín, Uh. Hradiště, Kroměříž", 49.22, 17.72, 0.50, 0.70),
        OfflinePresetRegion("olomoucky_kraj", "Olomoucký kraj", "Olomouc, Přerov, Prostějov, Šumperk", 49.72, 17.18, 0.65, 0.75),
        OfflinePresetRegion("moravskoslezsky_kraj", "Moravskoslezský kraj", "Ostrava, Opava, Frýdek-Místek, Bruntál", 49.78, 18.12, 0.60, 0.85),
        OfflinePresetRegion("pardubicky_kraj", "Pardubický kraj", "Pardubice, Chrudim, Svitavy, Ústí n. O.", 49.92, 16.15, 0.50, 0.80),
        OfflinePresetRegion("kralovehradecky_kraj", "Královéhradecký kraj", "Hradec Králové, Trutnov, Náchod, Jičín", 50.38, 15.82, 0.55, 0.75),
        OfflinePresetRegion("stredocesky_kraj", "Středočeský kraj", "Beroun, Kladno, M. Boleslav, Příbram", 49.98, 14.55, 0.65, 0.95),
        OfflinePresetRegion("praha", "Praha a okolí", "Hlavní město Praha, Český kras, Posázaví", 50.08, 14.43, 0.30, 0.42),
        OfflinePresetRegion("plzensky_kraj", "Plzeňský kraj", "Plzeň, Klatovy, Domažlice, Tachov", 49.58, 13.28, 0.65, 0.80),
        OfflinePresetRegion("liberecky_kraj", "Liberecký kraj", "Liberec, Jablonec n. N., Česká Lípa, Semily", 50.72, 15.05, 0.45, 0.65),
        OfflinePresetRegion("ustecky_kraj", "Ústecký kraj", "Ústí n. L., Děčín, Most, Teplice, Chomutov", 50.55, 13.85, 0.55, 0.85),
        OfflinePresetRegion("karlovarsky_kraj", "Karlovarský kraj", "Karlovy Vary, Cheb, Sokolov, Mar. Lázně", 50.18, 12.75, 0.45, 0.65)
    )

    private var isInitialized = false
    private var isDownloading = false
    private var isUserBrowsingMap = false

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
        } else if (!isDownloading) {
            MapLibre.setConnected(false)
        }
    }

    /**
     * Aktualizuje seznam všech uložených offline oblastí v paměti hodinek a automaticky
     * odstraní starší duplicitní verze (např. dřívější verze Jihomoravského kraje z vývoje).
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
                        "Jihomoravský kraj"
                    } else if (rawMeta.contains("|")) {
                        rawMeta.substringAfter("|")
                    } else {
                        resolveCzechRegionTitle(centerLat, centerLon)
                    }

                    // Pokud už máme novější verzi téže oblasti (např. JMK v1, v2, v3), starší duplikát smažeme z DB
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

                // Výchozí Jihomoravský kraj vždy nahoře, ostatní od nejnovějších
                _savedRegions.value = uniqueMapped.sortedWith(
                    compareByDescending<SavedOfflineRegion> { it.isDefaultJmk }
                        .thenByDescending { it.id }
                )
            }

            override fun onError(error: String) {
            }
        })
    }

    /**
     * Smaže vybranou offline oblast z interní databáze hodinek.
     */
    fun deleteOfflineRegion(context: Context, regionId: Long) {
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

    fun ensureSouthMoraviaOffline(context: Context, @Suppress("UNUSED_PARAMETER") styleJson: String) {
        ContourTileManager.loadCachedCustomContours(context)
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
                val existing = offlineRegions?.firstOrNull { region ->
                    val name = String(region.metadata, StandardCharsets.UTF_8)
                    name == REGION_NAME_JMK
                }

                if (existing != null) {
                    existing.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status != null && status.isComplete) {
                                finishDownloadAndUpdateConnectivity(context)
                            } else {
                                startRegionDownload(context, existing)
                            }
                        }

                        override fun onError(error: String?) {
                            finishDownloadAndUpdateConnectivity(context)
                        }
                    })
                    return
                }

                MapLibre.setConnected(true)
                val styleUrl = ensureLocalStyleServerUrl()

                val bounds = LatLngBounds.from(
                    49.42,
                    16.82,
                    48.78,
                    16.02
                )

                val definition = OfflineTilePyramidRegionDefinition(
                    styleUrl,
                    bounds,
                    8.0,
                    14.0,
                    1.0f
                )

                val metadata = REGION_NAME_JMK.toByteArray(StandardCharsets.UTF_8)

                offlineManager.createOfflineRegion(
                    definition,
                    metadata,
                    object : OfflineManager.CreateOfflineRegionCallback {
                        override fun onCreate(offlineRegion: OfflineRegion) {
                            refreshSavedRegions(context)
                            startRegionDownload(context, offlineRegion)
                        }

                        override fun onError(error: String) {
                            finishDownloadAndUpdateConnectivity(context)
                        }
                    }
                )
            }

            override fun onError(error: String) {
                _statusText.value = "JMK"
            }
        })
    }

    private fun startRegionDownload(context: Context, offlineRegion: OfflineRegion) {
        isDownloading = true
        MapLibre.setConnected(true)
        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    finishDownloadAndUpdateConnectivity(context)
                } else if (status.requiredResourceCount > 0) {
                    val pct = (100.0 * status.completedResourceCount / status.requiredResourceCount)
                        .toInt()
                        .coerceIn(0, 99)
                    _statusText.value = "DL $pct%"
                }
            }

            override fun onError(error: OfflineRegionError) {
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                finishDownloadAndUpdateConnectivity(context)
            }
        })
    }

    /**
     * Stáhne vybranou oblast z katalogu ČR ([preset]) jedním klepnutím včetně 10m/50m vrstevnic.
     */
    fun downloadPresetRegion(
        context: Context,
        preset: OfflinePresetRegion
    ) {
        downloadCustomBounds(
            context = context,
            centerLat = preset.centerLat,
            centerLon = preset.centerLon,
            halfLatSpan = preset.latSpanDeg / 2.0,
            halfLonSpan = preset.lonSpanDeg / 2.0,
            explicitTitle = preset.title
        )
    }

    /**
     * Stáhne do offline paměti hodinek ~30×30 km oblast kolem aktuálního středu mapy (až do detailu Zoom 14)
     * a zároveň vygeneruje pro danou oblast offline vrstevnice po 10 m / 50 m.
     */
    fun downloadAreaAround(
        context: Context,
        centerLat: Double,
        centerLon: Double
    ) {
        val humanTitle = resolveCzechRegionTitle(centerLat, centerLon)
        downloadCustomBounds(
            context = context,
            centerLat = centerLat,
            centerLon = centerLon,
            halfLatSpan = 0.13,
            halfLonSpan = 0.18,
            explicitTitle = humanTitle
        )
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

        // Souběžně vygenerujeme a uložíme offline vrstevnice (10 m / 50 m) pro tuto oblast
        ContourTileManager.downloadContoursForArea(context, centerLat, centerLon)

        val styleUrl = ensureLocalStyleServerUrl()

        val bounds = LatLngBounds.from(
            (centerLat + halfLatSpan).coerceIn(-85.0, 85.0),
            (centerLon + halfLonSpan).coerceIn(-179.9, 179.9),
            (centerLat - halfLatSpan).coerceIn(-85.0, 85.0),
            (centerLon - halfLonSpan).coerceIn(-179.9, 179.9)
        )

        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            8.0,
            14.0,
            1.0f
        )

        val regionMeta = "Custom_${"%.2f".format(Locale.US, centerLat)}_${"%.2f".format(Locale.US, centerLon)}|$explicitTitle"
        val metadata = regionMeta.toByteArray(StandardCharsets.UTF_8)

        isDownloading = true
        _activeDownloadTitle.value = explicitTitle
        _statusText.value = "DL 0%"
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
                    finishDownloadAndUpdateConnectivity(context)
                }
            }
        )
    }

    private fun finishDownloadAndUpdateConnectivity(context: Context) {
        isDownloading = false
        _activeDownloadTitle.value = null
        _isOfflineReady.value = true
        _statusText.value = "OFFLINE"
        refreshSavedRegions(context)
        if (!isUserBrowsingMap) {
            MapLibre.setConnected(false)
        }
    }

    /**
     * Automaticky přiřadí stažené oblasti srozumitelný český název podle nejbližšího pohoří nebo kraje.
     */
    fun resolveCzechRegionTitle(lat: Double, lon: Double): String {
        data class Landmark(val name: String, val lat: Double, val lon: Double, val radiusDeg: Double)
        val landmarks = listOf(
            Landmark("Krkonoše", 50.73, 15.65, 0.30),
            Landmark("Šumava", 49.02, 13.55, 0.40),
            Landmark("Beskydy", 49.50, 18.42, 0.35),
            Landmark("Jeseníky", 50.08, 17.22, 0.35),
            Landmark("Jizerské hory", 50.83, 15.25, 0.25),
            Landmark("Orlické hory", 50.28, 16.40, 0.28),
            Landmark("Krušné hory", 50.42, 13.05, 0.40),
            Landmark("Český ráj", 50.55, 15.20, 0.22),
            Landmark("České Švýcarsko", 50.88, 14.35, 0.22),
            Landmark("Brdy & Křivoklátsko", 49.75, 13.85, 0.32),
            Landmark("Praha a okolí", 50.08, 14.43, 0.32),
            Landmark("Vysočina & Žďárské vrchy", 49.58, 15.95, 0.40),
            Landmark("Jižní Čechy", 49.05, 14.48, 0.45),
            Landmark("Západní Čechy", 49.75, 13.35, 0.45),
            Landmark("Východní Čechy", 50.15, 15.82, 0.45),
            Landmark("Střední Čechy", 49.95, 14.85, 0.45),
            Landmark("Severní Čechy", 50.62, 14.05, 0.45),
            Landmark("Střední Morava", 49.55, 17.25, 0.45),
            Landmark("Jihomoravský kraj", 49.15, 16.45, 0.45)
        )
        val match = landmarks.minByOrNull { hypot(lat - it.lat, lon - it.lon) }
        if (match != null && hypot(lat - match.lat, lon - match.lon) <= match.radiusDeg) {
            return match.name
        }
        val regionPrefix = if (lon < 15.6) "Čechy" else "Morava"
        return "$regionPrefix (${"%.2f".format(Locale.US, lat)}, ${"%.2f".format(Locale.US, lon)})"
    }
}
