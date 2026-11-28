package cz.offgrid.maps.data.map

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Spatial tile indexer and viewport loader for downloaded `pois.features` files and
 * the bundled `jmk_outdoor_pois.geojson` asset.
 *
 * Splits huge regional POI files (up to ~5-6 MB / ~20k points per alpine region) into the same
 * `0.15 deg lat x 0.20 deg lon` spatial grid used by [TrailTileManager]. On camera movement, only
 * the 3x3 grid around the viewport (~50 x 45 km) is passed to MapLibre and Survival Radar, cutting
 * native GeoJSON source & symbol memory by >95%.
 */
object PoiTileManager {

    private const val TILES_SUBDIR = "poi_tiles"
    private const val JMK_TILES_DIR = "jmk_poi_tiles"
    private const val INDEX_MARKER = ".indexed_v1"

    /** Clears the index marker before re-downloading a package so updated POIs are re-indexed cleanly. */
    fun invalidateIndex(pkgDir: File) {
        val marker = File(File(pkgDir, TILES_SUBDIR), INDEX_MARKER)
        if (marker.exists()) marker.delete()
    }

    /**
     * Ensures [pkgDir]/poi_tiles is populated from [pkgDir]/pois.features in O(1) streaming RAM,
     * then deletes the redundant raw `pois.features` file once the index marker is flushed.
     */
    fun ensurePackageIndexed(pkgDir: File) {
        val tilesDir = File(pkgDir, TILES_SUBDIR)
        val marker = File(tilesDir, INDEX_MARKER)
        val poisFile = File(pkgDir, "pois.features")
        if (marker.exists()) {
            cleanupRawFeatureFileIfIndexed(poisFile, marker)
            return
        }

        if (!poisFile.exists() || poisFile.length() == 0L) {
            tilesDir.mkdirs()
            marker.writeText("empty")
            cleanupRawFeatureFileIfIndexed(poisFile, marker)
            return
        }

        poisFile.inputStream().use { input ->
            indexPoiStreamToTiles(input, tilesDir, isFeatureCollection = false)
        }
        marker.writeText("ok")
        cleanupRawFeatureFileIfIndexed(poisFile, marker)
    }

    /** Lazily indexes the bundled JMK POI asset on disk so it is not pinned permanently in heap RAM. */
    fun ensureJmkAssetIndexed(context: Context, assetName: String): File {
        val tilesDir = File(context.filesDir, JMK_TILES_DIR)
        val marker = File(tilesDir, INDEX_MARKER)
        if (!marker.exists()) {
            try {
                context.assets.open(assetName).use { input ->
                    indexPoiStreamToTiles(input, tilesDir, isFeatureCollection = true)
                }
                marker.writeText("ok")
            } catch (_: Exception) {
                tilesDir.mkdirs()
                marker.writeText("empty")
            }
        }
        return tilesDir
    }

    /**
     * Streams GeoJSON Point features from [input] into spatial `p_{bx}_{by}.features` files.
     * Supports both comma-separated feature fragments (`pois.features`) and full FeatureCollections.
     */
    internal fun indexPoiStreamToTiles(
        input: InputStream,
        tilesDir: File,
        isFeatureCollection: Boolean
    ) {
        if (tilesDir.exists()) {
            tilesDir.listFiles()?.forEach {
                if (it.name.startsWith("p_") && it.name.endsWith(".features")) it.delete()
            }
        } else {
            tilesDir.mkdirs()
        }

        val writerPool = BoundedTileWriterPool(tilesDir, prefix = "p_", suffix = ".features")
        try {
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
                if (isFeatureCollection && !skipToArrayStart(reader, "\"features\":[")) {
                    return
                }

                var braceDepth = 0
                var inString = false
                var escaped = false
                val featureBuf = StringBuilder(256)

                while (true) {
                    val raw = reader.read()
                    if (raw < 0) break
                    val ch = raw.toChar()

                    if (braceDepth == 0) {
                        if (ch == '{') {
                            braceDepth = 1
                            inString = false
                            escaped = false
                            featureBuf.setLength(0)
                            featureBuf.append('{')
                        } else if (isFeatureCollection && ch == ']') {
                            break
                        }
                        continue
                    }

                    if (featureBuf.length < 16_384) {
                        featureBuf.append(ch)
                    }

                    if (escaped) {
                        escaped = false
                        continue
                    }
                    if (inString) {
                        if (ch == '\\') escaped = true
                        else if (ch == '"') inString = false
                        continue
                    }

                    when (ch) {
                        '"' -> inString = true
                        '{' -> braceDepth++
                        '}' -> {
                            braceDepth--
                            if (braceDepth == 0) {
                                indexSinglePoiFeature(featureBuf.toString(), writerPool)
                                featureBuf.setLength(0)
                            }
                        }
                    }
                }
            }
        } finally {
            writerPool.close()
        }
    }

    private fun skipToArrayStart(reader: BufferedReader, marker: String): Boolean {
        val window = StringBuilder(marker.length + 16)
        while (true) {
            val raw = reader.read()
            if (raw < 0) return false
            window.append(raw.toChar())
            if (window.length > marker.length) {
                window.deleteCharAt(0)
            }
            if (window.length == marker.length && window.contentEquals(marker)) {
                return true
            }
        }
    }

    private fun indexSinglePoiFeature(featureJson: String, writerPool: BoundedTileWriterPool) {
        val coordsIdx = featureJson.lastIndexOf("\"coordinates\":[")
        if (coordsIdx < 0) return
        val start = coordsIdx + "\"coordinates\":[".length
        val end = featureJson.indexOf(']', start)
        if (end <= start) return
        val comma = featureJson.indexOf(',', start)
        if (comma <= start || comma >= end) return

        val lon = featureJson.substring(start, comma).trim().toDoubleOrNull() ?: return
        val lat = featureJson.substring(comma + 1, end).trim().toDoubleOrNull() ?: return
        if (lon !in -180.0..180.0 || lat !in -90.0..90.0) return

        val bx = TrailTileManager.cellX(lon)
        val by = TrailTileManager.cellY(lat)
        writerPool.appendRow(bx, by) { w ->
            w.write(featureJson)
            w.write('\n'.code)
        }
    }

    /**
     * Loads POI features from the 3x3 spatial tile grid around ([lat], [lon]) across [packageDirs]
     * (plus [extraTileDirs] such as indexed bundled JMK POIs). Returns `null` if cancelled early.
     */
    fun loadViewportPoisGeoJson(
        packageDirs: List<File>,
        lat: Double,
        lon: Double,
        extraTileDirs: List<File> = emptyList(),
        shouldCancel: () -> Boolean = { false }
    ): String? {
        if (packageDirs.isEmpty() && extraTileDirs.isEmpty()) {
            return """{"type":"FeatureCollection","features":[]}"""
        }

        val tileDirs = ArrayList<File>(packageDirs.size + extraTileDirs.size)
        for (dir in extraTileDirs) {
            if (shouldCancel()) return null
            if (dir.exists()) tileDirs.add(dir)
        }
        for (pkgDir in packageDirs) {
            if (shouldCancel()) return null
            ensurePackageIndexed(pkgDir)
            val tilesDir = File(pkgDir, TILES_SUBDIR)
            if (tilesDir.exists()) tileDirs.add(tilesDir)
        }

        val cx = TrailTileManager.cellX(lon)
        val cy = TrailTileManager.cellY(lat)
        val features = LinkedHashSet<String>()

        for (tilesDir in tileDirs) {
            for (bx in (cx - 1)..(cx + 1)) {
                for (by in (cy - 1)..(cy + 1)) {
                    if (shouldCancel()) return null
                    val cellFile = File(tilesDir, "p_${bx}_${by}.features")
                    if (!cellFile.exists() || cellFile.length() == 0L) continue
                    try {
                        cellFile.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            for (row in lines) {
                                if (row.isNotBlank()) features.add(row)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        if (features.isEmpty()) {
            return """{"type":"FeatureCollection","features":[]}"""
        }

        val sb = StringBuilder(32 * 1024)
        sb.append("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (feature in features) {
            if (!first) sb.append(',')
            first = false
            sb.append(feature)
        }
        sb.append("]}")
        return sb.toString()
    }
}
