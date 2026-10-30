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
import kotlin.math.*

/**
 * Generátor a offline úložiště vrstevnic (10 m vedlejší a 50 m hlavní vrstevnice)
 * pro libovolnou staženou oblast v ČR nebo v Evropě pomocí algoritmu Marching Squares
 * nad výškovými daty Terrarium DEM (`elevation = R * 256 + G + B / 256 - 32768`).
 */
object ContourTileManager {

    private const val CUSTOM_CONTOURS_FILE = "custom_contours.geojson"
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _customContoursGeoJson = MutableStateFlow<String?>(null)
    val customContoursGeoJson: StateFlow<String?> = _customContoursGeoJson.asStateFlow()

    /**
     * Načte dříve uložené offline vrstevnice stažených oblastí z flash paměti hodinek.
     */
    fun loadCachedCustomContours(context: Context) {
        scope.launch {
            val file = File(context.filesDir, CUSTOM_CONTOURS_FILE)
            if (file.exists() && file.length() > 10L) {
                try {
                    _customContoursGeoJson.value = file.readText()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Stáhne a vygeneruje offline vrstevnice (po 10 m / 50 m) pro oblast kolem [centerLat], [centerLon].
     */
    fun downloadContoursForArea(context: Context, centerLat: Double, centerLon: Double) {
        scope.launch {
            try {
                val z = 12
                val centerTx = lonToTileX(centerLon, z)
                val centerTy = latToTileY(centerLat, z)

                val sbFeatures = StringBuilder()
                var firstFeature = true

                // Stáhneme 2×2 dlaždice kolem středu oblasti (~20 × 15 km detailních vrstevnic)
                for (tx in centerTx..centerTx + 1) {
                    for (ty in centerTy..centerTy + 1) {
                        val tileFeatures = fetchAndGenerateTileContours(z, tx, ty)
                        if (tileFeatures.isNotEmpty()) {
                            if (!firstFeature) sbFeatures.append(",")
                            sbFeatures.append(tileFeatures)
                            firstFeature = false
                        }
                    }
                }

                if (sbFeatures.isNotEmpty()) {
                    val geoJson = """{"type":"FeatureCollection","features":[$sbFeatures]}"""
                    File(context.filesDir, CUSTOM_CONTOURS_FILE).writeText(geoJson)
                    _customContoursGeoJson.value = geoJson
                }
            } catch (_: Exception) {
            }
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

    private fun lonToTileX(lon: Double, z: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl z)).toInt()
    }

    private fun latToTileY(lat: Double, z: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * (1 shl z)).toInt()
    }
}
