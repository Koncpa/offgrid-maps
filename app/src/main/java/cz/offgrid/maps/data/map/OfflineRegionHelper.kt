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
import kotlin.concurrent.thread

/**
 * Správce offline mapových regionů (MapLibre OfflineManager) s lokálním HTTP loopbackem pro OkHttp.
 *
 * Proč používáme lokální loopback `http://127.0.0.1:<port>/style.json`:
 * MapLibre Native v Androidu předává `styleURL` z `OfflineTilePyramidRegionDefinition` třídě
 * `Mbgl-HttpRequest` (`okhttp3.HttpUrl.parse`), která odmítá `file://` schémata s chybou
 * `Unable to parse resourceUrl file://...`. Lokální loopback na `127.0.0.1` poskytne `OfflineManageru`
 * platnou `http://` adresu bez jakéhokoliv externího provozu a stáhne přesně dlaždice `offline_source`.
 */
object OfflineRegionHelper {

    private const val REGION_NAME_JMK = "Jihomoravsky_kraj_z14_v3"

    private val _statusText = MutableStateFlow("JMK")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isOfflineReady = MutableStateFlow(false)
    val isOfflineReady: StateFlow<Boolean> = _isOfflineReady.asStateFlow()

    private var isInitialized = false
    private var isDownloading = false
    private var isUserBrowsingMap = false

    @Volatile
    private var localStyleServerPort: Int = 0

    /**
     * Minimální definice stylu pro `OfflineManager` obsahující pouze vzdálené HTTP zdroje dlaždic a fontů
     * (bez lokálního `asset://` zdroje, který už je trvale zabalený v APK).
     */
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

    /**
     * Nastartuje miniaturní lokální socket na `127.0.0.1`, ze kterého si MapLibre `OfflineManager`
     * přečte JSON definici stylu pro stahování offline dlaždic.
     */
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

    /**
     * Když si uživatel posouvá mapu prstem mimo svou GPS polohu (např. hledá oblast v Čechách ke stažení)
     * a neběží záznam trasy, povolíme načítání náhledu mapy. Jakmile se vrátí na GPS, síť se vypne.
     */
    fun setBrowsingMode(browsing: Boolean) {
        isUserBrowsingMap = browsing
        if (browsing) {
            MapLibre.setConnected(true)
        } else if (!isDownloading) {
            MapLibre.setConnected(false)
        }
    }

    /**
     * Zkontroluje, zda je Jihomoravský kraj (až do Zoom 14) uložen v offline databázi hodinek.
     */
    fun ensureSouthMoraviaOffline(context: Context, @Suppress("UNUSED_PARAMETER") styleJson: String) {
        if (isInitialized) return
        isInitialized = true

        val offlineManager = OfflineManager.getInstance(context)
        offlineManager.setOfflineMapboxTileCountLimit(80000L)

        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val existing = offlineRegions?.firstOrNull { region ->
                    val name = String(region.metadata, StandardCharsets.UTF_8)
                    name == REGION_NAME_JMK
                }

                if (existing != null) {
                    existing.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status != null && status.isComplete) {
                                finishDownloadAndUpdateConnectivity()
                            } else {
                                startRegionDownload(existing)
                            }
                        }

                        override fun onError(error: String?) {
                            finishDownloadAndUpdateConnectivity()
                        }
                    })
                    return
                }

                MapLibre.setConnected(true)
                val styleUrl = ensureLocalStyleServerUrl()

                val bounds = LatLngBounds.from(
                    49.42, // Sever (Moravský kras / Tišnovsko / Boskovicko)
                    16.82, // Východ (Slavkov / Bučovice)
                    48.78, // Jih (Pálava / Mikulov / Znojmo)
                    16.02  // Západ (Oslavany / Ivančice / Moravský Krumlov / Náměšť)
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
                            startRegionDownload(offlineRegion)
                        }

                        override fun onError(error: String) {
                            finishDownloadAndUpdateConnectivity()
                        }
                    }
                )
            }

            override fun onError(error: String) {
                _statusText.value = "JMK"
            }
        })
    }

    private fun startRegionDownload(offlineRegion: OfflineRegion) {
        isDownloading = true
        MapLibre.setConnected(true)
        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    finishDownloadAndUpdateConnectivity()
                } else if (status.requiredResourceCount > 0) {
                    val pct = (100.0 * status.completedResourceCount / status.requiredResourceCount)
                        .toInt()
                        .coerceIn(0, 99)
                    _statusText.value = "DL $pct%"
                }
            }

            override fun onError(error: OfflineRegionError) {
                // Při chvilkovém výpadku jednoho souboru necháme stahování pokračovat
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                finishDownloadAndUpdateConnectivity()
            }
        })
    }

    /**
     * Stáhne do offline paměti hodinek ~30×30 km oblast kolem aktuálního středu mapy (až do detailu Zoom 14).
     */
    fun downloadAreaAround(
        context: Context,
        centerLat: Double,
        centerLon: Double
    ) {
        val offlineManager = OfflineManager.getInstance(context)
        offlineManager.setOfflineMapboxTileCountLimit(80000L)

        val styleUrl = ensureLocalStyleServerUrl()

        // Obdélník cca 30 × 30 km kolem zvoleného středu mapy
        val bounds = LatLngBounds.from(
            (centerLat + 0.13).coerceAtMost(51.1),
            (centerLon + 0.18).coerceAtMost(18.9),
            (centerLat - 0.13).coerceAtLeast(48.5),
            (centerLon - 0.18).coerceAtLeast(12.0)
        )

        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            8.0,
            14.0,
            1.0f
        )

        val regionName = "Custom_${"%.2f".format(java.util.Locale.US, centerLat)}_${"%.2f".format(java.util.Locale.US, centerLon)}"
        val metadata = regionName.toByteArray(StandardCharsets.UTF_8)

        isDownloading = true
        _statusText.value = "DL 0%"
        MapLibre.setConnected(true)

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    startRegionDownload(offlineRegion)
                }

                override fun onError(error: String) {
                    finishDownloadAndUpdateConnectivity()
                }
            }
        )
    }

    private fun finishDownloadAndUpdateConnectivity() {
        isDownloading = false
        _isOfflineReady.value = true
        _statusText.value = "OFFLINE"
        if (!isUserBrowsingMap) {
            MapLibre.setConnected(false)
        }
    }
}
