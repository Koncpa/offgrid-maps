package cz.offgrid.maps.data.map

import android.content.Context
import java.io.File

/**
 * Local offline MBTiles map file manager.
 *
 * How MBTiles works:
 * - MBTiles is an SQLite database containing individual vector tiles (MVT).
 * - Instead of millions of small files on disk, an entire country or region is stored in a single file.
 * - SQLite provides indexed lookups by (zoom, x, y) with minimal RAM and I/O overhead.
 */
class MapFileManager(private val context: Context) {

    /**
     * Internal storage directory for offline MBTiles files.
     */
    val mapsDirectory: File
        get() = File(context.filesDir, "maps").apply {
            if (!exists()) mkdirs()
        }

    /**
     * Returns all available offline .mbtiles files.
     */
    fun getAvailableMaps(): List<File> {
        return mapsDirectory.listFiles { file ->
            file.extension.equals("mbtiles", ignoreCase = true)
        }?.toList() ?: emptyList()
    }

    /**
     * Returns the default offline map file if present.
     */
    fun getDefaultMap(): File? {
        return getAvailableMaps().firstOrNull()
    }

    /**
     * Returns the total size of all downloaded MBTiles files in megabytes.
     */
    fun getTotalMapsSizeMb(): Long {
        val totalBytes = getAvailableMaps().sumOf { it.length() }
        return totalBytes / (1024 * 1024)
    }
}
