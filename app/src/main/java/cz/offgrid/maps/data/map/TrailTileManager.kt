package cz.offgrid.maps.data.map

import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.floor

/**
 * Bounded LRU pool of open [BufferedWriter] instances for writing spatial tile files (`t_*.tsv`, `p_*.features`)
 * without hitting Android per-process file descriptor limits when indexing huge regions.
 */
internal class BoundedTileWriterPool(
    private val tilesDir: File,
    private val prefix: String,
    private val suffix: String,
    private val maxOpenWriters: Int = 120
) : AutoCloseable {
    private val writers = HashMap<String, BufferedWriter>()
    private val createdFiles = HashSet<String>()

    fun appendRow(bx: Int, by: Int, writeRow: (BufferedWriter) -> Unit) {
        val key = "${bx}_${by}"
        var writer = writers[key]
        if (writer == null) {
            val file = File(tilesDir, "$prefix$key$suffix")
            val append = !createdFiles.add(key)
            writer = FileOutputStream(file, append).bufferedWriter(StandardCharsets.UTF_8)
            writers[key] = writer
            if (writers.size > maxOpenWriters) {
                val targetSize = maxOpenWriters / 2
                val iter = writers.entries.iterator()
                while (writers.size > targetSize && iter.hasNext()) {
                    val entry = iter.next()
                    if (entry.key != key) {
                        entry.value.close()
                        iter.remove()
                    }
                }
            }
        } else {
            createdFiles.add(key)
        }
        writeRow(writer)
    }

    override fun close() {
        writers.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        writers.clear()
    }
}

/**
 * Spatial tile indexer and viewport loader for downloaded `trails.features` files.
 *
 * Splits huge regional trail files (e.g. 13-28 MB / 100k+ lines across entire alpine states)
 * into ~17 x 15 km spatial grid cells (`0.15 deg lat x 0.20 deg lon`). On camera movement, only the
 * 3x3 grid around the watch screen (~50 x 45 km) is loaded into MapLibre, keeping native geojson-vt
 * memory small (~15-30 MB instead of 800+ MB) and preventing Wear OS LOW_MEMORY kills.
 */
object TrailTileManager {

    private const val TILES_SUBDIR = "trail_tiles"
    private const val INDEX_MARKER = ".indexed_v1"
    internal const val TILE_LAT_DEG = 0.15
    internal const val TILE_LON_DEG = 0.20

    internal fun cellX(lon: Double): Int = floor(lon / TILE_LON_DEG).toInt()
    internal fun cellY(lat: Double): Int = floor(lat / TILE_LAT_DEG).toInt()

    internal fun viewportTileKey(lat: Double, lon: Double): String = "${cellX(lon)}_${cellY(lat)}"

    /**
     * Ensures [pkgDir]/trail_tiles is populated from [pkgDir]/trails.features.
     * Uses streaming O(1) memory so even 30 MB GeoJSON fragments index cleanly on Wear OS.
     */
    fun ensurePackageIndexed(pkgDir: File) {
        val tilesDir = File(pkgDir, TILES_SUBDIR)
        val marker = File(tilesDir, INDEX_MARKER)
        if (marker.exists()) return

        val trailsFile = File(pkgDir, "trails.features")
        if (!trailsFile.exists() || trailsFile.length() == 0L) {
            tilesDir.mkdirs()
            marker.writeText("empty")
            return
        }

        indexTrailsFragmentToTiles(trailsFile, tilesDir)
        marker.writeText("ok")
    }

    /**
     * Streams a `trails.features` comma-separated Feature array (`{"type":"Feature",...},...`)
     * into spatial `t_{bx}_{by}.tsv` files formatted as `<colour>\t<lineCoordsJson>\n`.
     */
    internal fun indexTrailsFragmentToTiles(trailsFile: File, tilesDir: File) {
        if (tilesDir.exists()) {
            tilesDir.listFiles()?.forEach { if (it.name.startsWith("t_") && it.name.endsWith(".tsv")) it.delete() }
        } else {
            tilesDir.mkdirs()
        }

        val writerPool = BoundedTileWriterPool(tilesDir, prefix = "t_", suffix = ".tsv")
        fun appendLineToCell(bx: Int, by: Int, colour: String, lineJson: String) {
            writerPool.appendRow(bx, by) { w ->
                w.write(colour)
                w.write('\t'.code)
                w.write(lineJson)
                w.write('\n'.code)
            }
        }

        try {
            BufferedInputStream(trailsFile.inputStream(), 32 * 1024).use { input ->
                var braceDepth = 0
                var inString = false
                var escaped = false
                var inCoordsArray = false
                var bracketDepth = 0
                var currentColour = "other"

                val headerBuf = StringBuilder(256)
                val lineBuf = StringBuilder(512)
                val colourMarker = "\"colour\":\""
                val coordsMarker = "\"coordinates\":["

                while (true) {
                    val raw = input.read()
                    if (raw < 0) break
                    val ch = raw.toChar()

                    if (!inCoordsArray) {
                        headerBuf.append(ch)
                        if (headerBuf.length > 512) {
                            headerBuf.delete(0, headerBuf.length - 256)
                        }
                        if (!escaped) {
                            if (ch == '\\' && inString) {
                                escaped = true
                            } else if (ch == '"') {
                                inString = !inString
                            } else if (!inString) {
                                if (ch == '{') braceDepth++
                                else if (ch == '}') braceDepth = maxOf(0, braceDepth - 1)
                            }
                        } else {
                            escaped = false
                        }

                        if (headerBuf.endsWith(coordsMarker) && !inString) {
                            val hdr = headerBuf.toString()
                            val cIdx = hdr.lastIndexOf(colourMarker)
                            if (cIdx >= 0) {
                                val start = cIdx + colourMarker.length
                                val end = hdr.indexOf('"', start)
                                if (end > start) {
                                    currentColour = hdr.substring(start, end)
                                }
                            }
                            inCoordsArray = true
                            bracketDepth = 1
                            headerBuf.setLength(0)
                        }
                        continue
                    }

                    // Inside "coordinates":[ ... ] where MultiLineString has depth=2 per line [[lon,lat],...]
                    if (ch == '[') {
                        bracketDepth++
                        if (bracketDepth == 2) {
                            lineBuf.setLength(0)
                            lineBuf.append('[')
                        } else if (bracketDepth >= 3) {
                            lineBuf.append('[')
                        }
                    } else if (ch == ']') {
                        if (bracketDepth >= 3) {
                            lineBuf.append(']')
                        } else if (bracketDepth == 2) {
                            lineBuf.append(']')
                            indexSingleLine(lineBuf.toString(), currentColour, ::appendLineToCell)
                            lineBuf.setLength(0)
                        } else if (bracketDepth == 1) {
                            inCoordsArray = false
                            currentColour = "other"
                        }
                        bracketDepth = maxOf(0, bracketDepth - 1)
                    } else if (bracketDepth >= 2) {
                        lineBuf.append(ch)
                    }
                }
            }
        } finally {
            writerPool.close()
        }
    }

    private inline fun indexSingleLine(
        lineJson: String,
        colour: String,
        emit: (bx: Int, by: Int, colour: String, lineJson: String) -> Unit
    ) {
        // Parse min/max bounds directly from coordinate pairs inside `[[lon,lat],[lon,lat],...]`
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY

        var i = 0
        val n = lineJson.length
        var numBuf = StringBuilder(16)
        var expectLon = true
        var pendingLon = Double.NaN

        while (i < n) {
            val ch = lineJson[i]
            if ((ch in '0'..'9') || ch == '-' || ch == '.' || ch == '+') {
                numBuf.append(ch)
            } else if (numBuf.isNotEmpty()) {
                val value = numBuf.toString().toDoubleOrNull()
                numBuf.setLength(0)
                if (value != null) {
                    if (expectLon) {
                        pendingLon = value
                        expectLon = false
                    } else {
                        val lon = pendingLon
                        val lat = value
                        expectLon = true
                        if (lon in -180.0..180.0 && lat in -90.0..90.0) {
                            if (lon < minLon) minLon = lon
                            if (lon > maxLon) maxLon = lon
                            if (lat < minLat) minLat = lat
                            if (lat > maxLat) maxLat = lat
                        }
                    }
                }
            }
            i++
        }

        if (minLon.isInfinite() || minLat.isInfinite()) return

        val bx0 = cellX(minLon)
        val bx1 = cellX(maxLon)
        val by0 = cellY(minLat)
        val by1 = cellY(maxLat)

        // Cap degenerate dateline wrap spans just in case
        if (bx1 - bx0 > 16 || by1 - by0 > 16) {
            emit(cellX((minLon + maxLon) / 2.0), cellY((minLat + maxLat) / 2.0), colour, lineJson)
            return
        }

        for (bx in bx0..bx1) {
            for (by in by0..by1) {
                emit(bx, by, colour, lineJson)
            }
        }
    }

    /**
     * Loads trails from the 3x3 spatial tile grid surrounding ([lat], [lon]) across [packageDirs]
     * and returns a lightweight GeoJSON FeatureCollection string grouped into one MultiLineString per colour.
     */
    fun loadViewportTrailsGeoJson(packageDirs: List<File>, lat: Double, lon: Double): String =
        loadViewportTrailsGeoJsonOrNull(packageDirs, lat, lon) { false }
            ?: """{"type":"FeatureCollection","features":[]}"""

    /** Same as [loadViewportTrailsGeoJson], returning `null` if [shouldCancel] triggers mid-scan. */
    fun loadViewportTrailsGeoJsonOrNull(
        packageDirs: List<File>,
        lat: Double,
        lon: Double,
        shouldCancel: () -> Boolean
    ): String? {
        if (packageDirs.isEmpty()) {
            return """{"type":"FeatureCollection","features":[]}"""
        }

        val cx = cellX(lon)
        val cy = cellY(lat)
        val byColour = LinkedHashMap<String, LinkedHashSet<String>>()

        for (pkgDir in packageDirs) {
            if (shouldCancel()) return null
            ensurePackageIndexed(pkgDir)
            val tilesDir = File(pkgDir, TILES_SUBDIR)
            if (!tilesDir.exists()) continue

            for (bx in (cx - 1)..(cx + 1)) {
                for (by in (cy - 1)..(cy + 1)) {
                    if (shouldCancel()) return null
                    val cellFile = File(tilesDir, "t_${bx}_${by}.tsv")
                    if (!cellFile.exists() || cellFile.length() == 0L) continue
                    try {
                        cellFile.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            for (row in lines) {
                                val tab = row.indexOf('\t')
                                if (tab <= 0 || tab + 1 >= row.length) continue
                                val colour = row.substring(0, tab)
                                val lineJson = row.substring(tab + 1)
                                byColour.getOrPut(colour) { LinkedHashSet() }.add(lineJson)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        if (byColour.isEmpty()) {
            return """{"type":"FeatureCollection","features":[]}"""
        }

        val sb = StringBuilder(64 * 1024)
        sb.append("""{"type":"FeatureCollection","features":[""")
        var firstFeature = true
        for ((colour, lines) in byColour) {
            if (lines.isEmpty()) continue
            if (!firstFeature) sb.append(',')
            firstFeature = false
            sb.append("""{"type":"Feature","properties":{"colour":"""")
            sb.append(colour)
            sb.append(""""},"geometry":{"type":"MultiLineString","coordinates":[""")
            var firstLine = true
            for (line in lines) {
                if (!firstLine) sb.append(',')
                firstLine = false
                sb.append(line)
            }
            sb.append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }
}
