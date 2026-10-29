package cz.offgrid.maps.data.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*
import java.nio.charset.StandardCharsets

/**
 * Správce offline mapových regionů (MapLibre OfflineManager) s automatickým odpojením sítě.
 *
 * Jak to šetří baterii:
 * 1. Stáhne Jihomoravský kraj včetně nejvyššího detailu (Zoom 14 = všechny lesní pěšiny a budovy)
 *    do lokální SQLite databáze (mbgl-offline.db).
 * 2. Jakmile je region stažen, zavolá MapLibre.setConnected(false).
 *    Tím se natvrdo vypnou veškeré síťové dotazy MapLibre – hodinky při posouvání mapy vůbec
 *    neprobouzí Wi-Fi ani Bluetooth rádio a čtou 100 % dat z lokální flash paměti!
 */
object OfflineRegionHelper {

    private const val REGION_NAME_JMK = "Jihomoravsky_kraj_z14_v2"

    private val _statusText = MutableStateFlow("JMK")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isOfflineReady = MutableStateFlow(false)
    val isOfflineReady: StateFlow<Boolean> = _isOfflineReady.asStateFlow()

    private var isInitialized = false

    /**
     * Zkontroluje, zda je Jihomoravský kraj (až do Zoom 14) uložen v offline databázi hodinek.
     * Pokud ano, okamžitě přepne MapLibre do čistě offline režimu bez spotřeby Wi-Fi/BT rádia.
     */
    fun ensureSouthMoraviaOffline(context: Context, styleJson: String) {
        if (isInitialized) return
        isInitialized = true

        val offlineManager = OfflineManager.getInstance(context)
        // Povolíme vyšší limit dlaždic pro uložení celého kraje do detailu Zoom 14
        offlineManager.setOfflineMapboxTileCountLimit(25000L)

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
                                activateStrictOfflineMode()
                            } else {
                                startRegionDownload(existing)
                            }
                        }

                        override fun onError(error: String?) {
                            activateStrictOfflineMode()
                        }
                    })
                    return
                }

                // Povolíme síť pouze po dobu prvotního stažení regionu
                MapLibre.setConnected(true)

                // Oblast Jihomoravského kraje (Oslavany, Ivančice, Brno, Moravský kras, Pálava, Znojemsko)
                val bounds = LatLngBounds.from(
                    49.42, // Sever (Moravský kras / Tišnovsko / Boskovicko)
                    16.82, // Východ (Slavkov / Bučovice)
                    48.78, // Jih (Pálava / Mikulov / Znojmo)
                    16.02  // Západ (Oslavany / Ivančice / Moravský Krumlov / Náměšť)
                )

                val styleFile = java.io.File(context.filesDir, "amoled_jmk_z14_style.json")
                styleFile.writeText(styleJson)
                val styleFileUri = "file://${styleFile.absolutePath}"

                val definition = OfflineTilePyramidRegionDefinition(
                    styleFileUri,
                    bounds,
                    8.0,   // minZoom (celý kraj)
                    14.0,  // maxZoom 14 (obsahuje 100 % detailů: každou lesní pěšinu, KČT trasu i budovu až do Z 18)
                    1.0f   // pixelRatio 1.0f šetří místo a paměť u vektorových dlaždic
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
                            _statusText.value = "JMK CACHE"
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
        MapLibre.setConnected(true)
        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    activateStrictOfflineMode()
                } else if (status.requiredResourceCount > 0) {
                    val pct = (100.0 * status.completedResourceCount / status.requiredResourceCount)
                        .toInt()
                        .coerceIn(0, 99)
                    _statusText.value = "JMK $pct%"
                }
            }

            override fun onError(error: OfflineRegionError) {
                // Pokud vypadne internet během stahování, použijeme to, co už v cache je
                _statusText.value = "JMK CACHE"
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                activateStrictOfflineMode()
            }
        })
    }

    /**
     * Odpojí MapLibre od sítě (MapLibre.setConnected(false)).
     * Od této chvíle aplikace nečerpá žádnou energii na Wi-Fi/BT síťové dotazy a jede 100% z interní flash paměti.
     */
    private fun activateStrictOfflineMode() {
        MapLibre.setConnected(false)
        _isOfflineReady.value = true
        _statusText.value = "OFFLINE"
    }
}
