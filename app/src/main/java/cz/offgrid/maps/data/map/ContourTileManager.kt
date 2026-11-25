package cz.offgrid.maps.data.map

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.*

/**
 * Tile-based contour generator and offline cache (`10 m` minor and `50 m` major index contours)
 * operating on the same Web Mercator tile pyramid principle as vector map tiles:
 * - Each `z=12` tile (`tile_12_{x}_{y}.features`) is stored independently in watch flash storage
 *   (`filesDir/contour_tiles/`), so downloaded regions never overwrite one another.
 * - On camera movement (`ensureViewportContours`), tiles for the visible viewport are loaded from cache
 *   (and missing tiles are generated and cached when network access is permitted).
 * - When downloading an offline map region (`downloadContoursForArea`), the entire 3x3 tile grid (~30x20 km) is pre-cached.
 * - Catalog regions ship pre-built tiles for the whole region (`importTilePackage`), so the watch
 *   does not need to decode DEM images at all for them.
 */
object ContourTileManager {

    private const val TILES_DIR_NAME = "contour_tiles"
    private const val TILE_ZOOM = 12
    private const val MAX_MEMORY_TILES = 12

    // z=12 tiles already pre-bundled in the APK (assets/jmk_contours.geojson for Oslavany / Ivančice / Zbýšov)
    internal val BUNDLED_JMK_TILES = setOf(
        "2233_1403", "2234_1403",
        "2233_1404", "2234_1404"
    )

    private val scope = CoroutineScope(Dispatchers.IO)
    private val memoryTileCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_MEMORY_TILES
            }
        }
    )
    @Volatile
    private var lastViewportKey: String = ""
    @Volatile
    private var activeViewportJob: Job? = null

    private val _customContoursGeoJson = MutableStateFlow<String?>(null)
    val customContoursGeoJson: StateFlow<String?> = _customContoursGeoJson.asStateFlow()

    /**
     * Loads contour lines for the current camera viewport from the local tile cache (and when network
     * is enabled, fetches, generates, and permanently caches any missing tiles).
     */
    fun ensureViewportContours(
        context: Context,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        allowNetworkFetch: Boolean
    ) {
        // Style contour layers start at minzoom 12; avoid decoding heavy GeoJSON at overview zooms
        if (zoom < 11.8) return

        val centerTx = lonToTileX(centerLon, TILE_ZOOM)
        val centerTy = latToTileY(centerLat, TILE_ZOOM)
        val viewportKey = "${centerTx}_${centerTy}_$allowNetworkFetch"
        if (viewportKey == lastViewportKey) return
        lastViewportKey = viewportKey

        activeViewportJob?.cancel()
        activeViewportJob = scope.launch {
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
     * Downloads and permanently caches contour lines (3x3 grid of z=12 tiles, ~30x20 km)
     * for a newly downloaded offline region around [centerLat], [centerLon].
     */
    fun downloadContoursForArea(context: Context, centerLat: Double, centerLon: Double) {
        scope.launch {
            val centerTx = lonToTileX(centerLon, TILE_ZOOM)
            val centerTy = latToTileY(centerLat, TILE_ZOOM)
            lastViewportKey = "" // Force refresh after download
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
     * Loads offline contour tiles around the specified startup location.
     */
    fun loadCachedCustomContours(
        context: Context,
        centerLat: Double = 49.1246,
        centerLon: Double = 16.3353
    ) {
        ensureViewportContours(
            context = context,
            centerLat = centerLat,
            centerLon = centerLon,
            zoom = 13.0,
            allowNetworkFetch = false
        )
    }

    /**
     * Imports a pre-built regional contour package (`contours_z12.tiles.gz` from the map data repository).
     *
     * Format: one tile per line, `12/{x}/{y}<TAB>{comma-joined GeoJSON features}`.
     * The stream is processed line by line, so peak memory stays at a single tile (~100 KB)
     * even for packages with hundreds of tiles. Tiles land in the same cache that
     * [ensureViewportContours] reads, so they are rendered offline without further work.
     *
     * @return number of imported tiles
     */
    fun importTilePackage(context: Context, gzipStream: InputStream): Int {
        val tilesDir = File(context.filesDir, TILES_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
        return importTilePackageToDir(tilesDir, gzipStream)
    }

    internal fun importTilePackageToDir(tilesDir: File, gzipStream: InputStream): Int {
        if (!tilesDir.exists()) tilesDir.mkdirs()
        var imported = 0
        GZIPInputStream(gzipStream).bufferedReader().useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val parts = line.substring(0, tab).split('/')
                if (parts.size != 3) continue
                val key = "${parts[0]}_${parts[1]}_${parts[2]}"
                File(tilesDir, "tile_$key.features").writeText(line.substring(tab + 1))
                memoryTileCache.remove(key)
                imported++
            }
        }
        lastViewportKey = "" // Force the next camera idle to reload tiles from disk
        return imported
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
                // Skip tiles that are already bundled in assets/jmk_contours.geojson
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
        val bytes = try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
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
