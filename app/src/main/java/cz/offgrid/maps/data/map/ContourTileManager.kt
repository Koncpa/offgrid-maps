package cz.offgrid.maps.data.map

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * Dlaždicový generátor a offline úložiště vrstevnic (10 m vedlejší a 50 m hlavní vrstevnice)
 * fungující na stejném principu jako vektorové mapové dlaždice:
 * - Každá dlaždice `z=12` (`tile_12_{x}_{y}.features`) se ukládá samostatně do flash paměti hodinek
 *   (`filesDir/contour_tiles/`), takže stažené oblasti (Šumava, Praha, Krkonoše, JMK...) se nikdy nepřepisují.
 * - Při posunu mapy (`ensureViewportContours`) se okamžitě načtou dlaždice pro viditelnou oblast z offline cache
 *   (a při aktivním prohlížení se chybějící dlaždice automaticky dopočítají a uloží).
 * - Při stažení offline mapy (`downloadContoursForArea`) se předpočítá celá mřížka 3×3 dlaždic (~30×20 km).
 */
object ContourTileManager {

    private const val TILES_DIR_NAME = "contour_tiles"
    private const val TILE_ZOOM = 12

    // Dlaždice z=12, které jsou již předbalené přímo v APK (assets/jmk_contours.geojson pro Oslavany / Ivančice / Zbýšov)
    internal val BUNDLED_JMK_TILES = setOf(
        "2233_1403", "2234_1403",
        "2233_1404", "2234_1404"
    )

    private val scope = CoroutineScope(Dispatchers.IO)
    private val memoryTileCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var lastViewportKey: String = ""

    private val _customContoursGeoJson = MutableStateFlow<String?>(null)
    val customContoursGeoJson: StateFlow<String?> = _customContoursGeoJson.asStateFlow()

    /**
     * Načte vrstevnice pro aktuální výřez kamery z lokální dlaždicové cache (a při povolené síti
     * chybějící dlaždice rovnou stáhne, vygeneruje a trvale uloží do offline cache).
     */
    fun ensureViewportContours(
        context: Context,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        allowNetworkFetch: Boolean
    ) {
        if (zoom < 11.0) return

        val centerTx = lonToTileX(centerLon, TILE_ZOOM)
        val centerTy = latToTileY(centerLat, TILE_ZOOM)
        val viewportKey = "${centerTx}_${centerTy}_$allowNetworkFetch"
        if (viewportKey == lastViewportKey) return
        lastViewportKey = viewportKey

        scope.launch {
            updateActiveViewportTiles(
                context = context,
                minTx = centerTx - 1,
                maxTx = centerTx + 1,
                minTy = centerTy - 1,
                maxTy = centerTy + 1,
                allowNetworkFetch = allowNetworkFetch
            )
        }
    }

    /**
     * Stáhne a trvale uloží do offline cache vrstevnice (mřížku 3×3 dlaždic z=12, ~30×20 km)
     * pro nově stahovanou offline oblast kolem [centerLat], [centerLon].
     */
    fun downloadContoursForArea(context: Context, centerLat: Double, centerLon: Double) {
        scope.launch {
            val centerTx = lonToTileX(centerLon, TILE_ZOOM)
            val centerTy = latToTileY(centerLat, TILE_ZOOM)
            lastViewportKey = "" // Vynutíme obnovení po stažení
            updateActiveViewportTiles(
                context = context,
                minTx = centerTx - 1,
                maxTx = centerTx + 1,
                minTy = centerTy - 1,
                maxTy = centerTy + 1,
                allowNetworkFetch = true
            )
        }
    }

    /**
     * Při startu aplikace načte offline dlaždice v okolí výchozí polohy.
     */
    fun loadCachedCustomContours(context: Context) {
        ensureViewportContours(
            context = context,
            centerLat = 49.1246,
            centerLon = 16.3353,
            zoom = 13.0,
            allowNetworkFetch = false
        )
    }

    private fun updateActiveViewportTiles(
        context: Context,
        minTx: Int,
        maxTx: Int,
        minTy: Int,
        maxTy: Int,
        allowNetworkFetch: Boolean
    ) {
        val tilesDir = File(context.filesDir, TILES_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

        val combinedFeatures = StringBuilder()
        var first = true

        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                val tileKey = "${tx}_${ty}"
                // Přeskočíme dlaždice, které už jsou trvale v assets/jmk_contours.geojson
                if (BUNDLED_JMK_TILES.contains(tileKey)) continue

                val tileFeatures = getOrFetchTileFeatures(tilesDir, TILE_ZOOM, tx, ty, allowNetworkFetch)
                if (tileFeatures.isNotEmpty()) {
                    if (!first) combinedFeatures.append(",")
                    combinedFeatures.append(tileFeatures)
                    first = false
                }
            }
        }

        val geoJson = """{"type":"FeatureCollection","features":[$combinedFeatures]}"""
        _customContoursGeoJson.value = geoJson
    }

    private fun getOrFetchTileFeatures(
        tilesDir: File,
        z: Int,
        tx: Int,
        ty: Int,
        allowNetworkFetch: Boolean
    ): String {
        val key = "${z}_${tx}_${ty}"
        memoryTileCache[key]?.let { return it }

        val diskFile = File(tilesDir, "tile_$key.features")
        if (diskFile.exists() && diskFile.length() > 2L) {
            try {
                val cached = diskFile.readText()
                memoryTileCache[key] = cached
                return cached
            } catch (_: Exception) {
            }
        }

        if (!allowNetworkFetch) return ""

        return try {
            val generated = fetchAndGenerateTileContours(z, tx, ty)
            if (generated.isNotEmpty()) {
                diskFile.writeText(generated)
                memoryTileCache[key] = generated
            }
            generated
        } catch (_: Exception) {
            ""
        }
    }

    private fun fetchAndGenerateTileContours(z: Int, tx: Int, ty: Int): String {
        val url = URL("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$tx/$ty.png")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        val elev = FloatArray(w * h)
        var minEle = 9000f
        var maxEle = -500f
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            val e = (r * 256f + g + b / 256f) - 32768f
            elev[i] = e
            if (e < minEle) minEle = e
            if (e > maxEle) maxEle = e
        }

        val step = 4
        val startLevel = ((minEle.toInt() / 10) * 10).coerceAtLeast(100)
        val endLevel = ((maxEle.toInt() / 10) * 10).coerceAtMost(2600)

        val featuresList = mutableListOf<String>()
        for (level in startLevel..endLevel step 10) {
            val lvlF = level.toFloat()
            val segments = StringBuilder()
            var segCount = 0

            var y = 0
            while (y < h - step) {
                var x = 0
                while (x < w - step) {
                    val v0 = elev[y * w + x]
                    val v1 = elev[y * w + (x + step)]
                    val v2 = elev[(y + step) * w + (x + step)]
                    val v3 = elev[(y + step) * w + x]

                    var idx = 0
                    if (v0 >= lvlF) idx = idx or 1
                    if (v1 >= lvlF) idx = idx or 2
                    if (v2 >= lvlF) idx = idx or 4
                    if (v3 >= lvlF) idx = idx or 8

                    if (idx != 0 && idx != 15) {
                        val pTop = interpCoord(z, tx, ty, x, y, v0, x + step, y, v1, lvlF)
                        val pRight = interpCoord(z, tx, ty, x + step, y, v1, x + step, y + step, v2, lvlF)
                        val pBottom = interpCoord(z, tx, ty, x, y + step, v3, x + step, y + step, v2, lvlF)
                        val pLeft = interpCoord(z, tx, ty, x, y, v0, x, y + step, v3, lvlF)

                        val pair = when (idx) {
                            1, 14 -> "[$pLeft,$pTop]"
                            2, 13 -> "[$pTop,$pRight]"
                            4, 11 -> "[$pRight,$pBottom]"
                            8, 7 -> "[$pBottom,$pLeft]"
                            3, 12 -> "[$pLeft,$pRight]"
                            6, 9 -> "[$pTop,$pBottom]"
                            else -> null
                        }
                        if (pair != null) {
                            if (segCount > 0) segments.append(",")
                            segments.append(pair)
                            segCount++
                        }
                    }
                    x += step
                }
                y += step
            }

            if (segCount > 0) {
                val major = if (level % 50 == 0) 1 else 0
                featuresList.add(
                    """{"type":"Feature","properties":{"ele":$level,"major":$major},"geometry":{"type":"MultiLineString","coordinates":[$segments]}}"""
                )
            }
        }
        return featuresList.joinToString(",")
    }

    private fun interpCoord(
        z: Int, tx: Int, ty: Int,
        xa: Int, ya: Int, va: Float,
        xb: Int, yb: Int, vb: Float,
        level: Float
    ): String {
        val diff = vb - va
        val t = if (abs(diff) < 1e-4f) 0.5f else ((level - va) / diff).coerceIn(0f, 1f)
        val px = xa + t * (xb - xa)
        val py = ya + t * (yb - ya)
        val n = (1 shl z).toDouble()
        val lon = (tx + px / 256.0) / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1.0 - 2.0 * (ty + py / 256.0) / n)))
        val lat = Math.toDegrees(latRad)
        return "[%.5f,%.5f]".format(Locale.US, lon, lat)
    }

    internal fun lonToTileX(lon: Double, z: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl z)).toInt()
    }

    internal fun latToTileY(lat: Double, z: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * (1 shl z)).toInt()
    }
}
