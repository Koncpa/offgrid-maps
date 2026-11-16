package cz.offgrid.maps.data.tracking

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import cz.offgrid.maps.domain.model.TrackPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exporter, importer, and parser for standard GPX 1.1 files (compatible with Mapy.com, Garmin, Strava, Komoot).
 */
object GpxExporter {

    private const val RECOVERY_FILE_NAME = ".active_recovery.gpx"

    // Supports both <trkpt> (recorded track) and <rtept> (planned route from Mapy.com) including multiline tags
    private val pointTagRegex = Regex(
        """<(?:trkpt|rtept)\b([^>]*?)(?:/>|>(.*?)</(?:trkpt|rtept)>)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val latAttrRegex = Regex("""\blat\s*=\s*["']([0-9.+-]+)["']""", RegexOption.IGNORE_CASE)
    private val lonAttrRegex = Regex("""\blon\s*=\s*["']([0-9.+-]+)["']""", RegexOption.IGNORE_CASE)
    private val eleTagRegex = Regex("""<ele>\s*([0-9.+-]+)\s*</ele>""", RegexOption.IGNORE_CASE)

    /**
     * Saves recorded track points to a .gpx file in internal storage (`/files/tracks/`)
     * and automatically exports a backup copy to the public `Download/OffGridMaps/` directory.
     */
    fun saveTrackToGpx(context: Context, points: List<TrackPoint>, prefix: String = "hike"): File? {
        if (points.isEmpty()) return null

        val tracksDir = getTracksDir(context)
        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${prefix}_$fileTimestamp.gpx"
        val gpxFile = File(tracksDir, fileName)

        val xmlContent = buildGpxXml("Hike $fileTimestamp", points)
        gpxFile.writeText(xmlContent)
        clearRecoveryCheckpoint(context)

        // Export a copy to public Download/OffGridMaps/ for easy transfer to phone/PC
        exportToPublicDownloads(context, fileName, xmlContent)

        return gpxFile
    }

    /**
     * Periodically checkpoints the active track to disk in case of sudden battery depletion or reboot.
     */
    fun saveRecoveryCheckpoint(context: Context, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        val recoveryFile = File(getTracksDir(context), RECOVERY_FILE_NAME)
        recoveryFile.writeText(buildGpxXml("Recovered hike", points))
    }

    /**
     * Removes the temporary recovery checkpoint file after a clean track save.
     */
    fun clearRecoveryCheckpoint(context: Context) {
        val recoveryFile = File(getTracksDir(context), RECOVERY_FILE_NAME)
        if (recoveryFile.exists()) {
            recoveryFile.delete()
        }
    }

    /**
     * Checks at app startup whether an unfinished track checkpoint exists on disk (e.g. after battery depletion)
     * and automatically promotes it to a permanent .gpx file.
     */
    fun recoverUnfinishedTrackIfAny(context: Context): File? {
        if (TrackingManager.isTracking.value) return null
        val tracksDir = getTracksDir(context)
        val recoveryFile = File(tracksDir, RECOVERY_FILE_NAME)
        if (!recoveryFile.exists()) return null

        val points = parseGpxFile(recoveryFile)
        if (points.isEmpty()) {
            recoveryFile.delete()
            return null
        }

        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(recoveryFile.lastModified()))
        val fileName = "recovered_$fileTimestamp.gpx"
        val recoveredFile = File(tracksDir, fileName)
        val xmlContent = recoveryFile.readText()
        recoveryFile.renameTo(recoveredFile)
        exportToPublicDownloads(context, fileName, xmlContent)
        return recoveredFile
    }

    /**
     * Imports a .gpx file from a Uri (e.g. system file picker or Bluetooth / Quick Share).
     */
    fun importGpxFromUri(context: Context, uri: Uri): File? {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return null
            val points = parseGpxContent(content)
            if (points.isEmpty()) return null

            val rawName = resolveDisplayName(context, uri)
                ?.removeSuffix(".gpx")
                ?.removeSuffix(".GPX")
                ?.replace(Regex("""[^a-zA-Z0-9_\-ěščřžýáíéúůťďňĚŠČŘŽÝÁÍÉÚŮŤĎŇ ]"""), "_")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "import_${SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())}"

            val targetFile = File(getTracksDir(context), "$rawName.gpx")
            targetFile.writeText(content)
            targetFile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Exports a .gpx file to `Download/OffGridMaps/` and opens the system share sheet
     * (Quick Share / Bluetooth / phone transfer).
     */
    fun shareAndExportGpxFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val exportedToDownloads = exportToPublicDownloads(context, file.name, file.readText())

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Share GPX").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            return true
        } catch (_: Exception) {
            return exportedToDownloads
        }
    }

    /**
     * Writes a copy of the .gpx file to the public `Download/OffGridMaps/` folder (permissionless on Android 10+).
     */
    private fun exportToPublicDownloads(context: Context, fileName: String, xmlContent: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/OffGridMaps")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(xmlContent.toByteArray(Charsets.UTF_8))
                    }
                    true
                } else {
                    false
                }
            } else {
                @Suppress("DEPRECATION")
                val dlDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OffGridMaps")
                if (!dlDir.exists()) dlDir.mkdirs()
                File(dlDir, fileName).writeText(xmlContent)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildGpxXml(trackName: String, points: List<TrackPoint>): String {
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="OffGrid Maps Wear OS" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("""  <trk>""")
        sb.appendLine("""    <name>$trackName</name>""")
        sb.appendLine("""    <trkseg>""")

        for (pt in points) {
            sb.append("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}">""")
            if (pt.altitudeMeters != null) {
                sb.append("""<ele>${"%.1f".format(Locale.US, pt.altitudeMeters)}</ele>""")
            }
            val timeStr = isoFormatter.format(Date(pt.timestampMillis))
            sb.append("""<time>$timeStr</time>""")
            sb.appendLine("""</trkpt>""")
        }

        sb.appendLine("""    </trkseg>""")
        sb.appendLine("""  </trk>""")
        sb.appendLine("""</gpx>""")
        return sb.toString()
    }

    /**
     * Returns all saved .gpx routes (automatically syncing any .gpx files placed in Download or external files).
     */
    fun listSavedGpxFiles(context: Context): List<File> {
        val dir = getTracksDir(context)
        ensureSampleTrackExists(context, dir)
        syncExternalGpxDirectories(context, dir)
        return dir.listFiles { f ->
            f.isFile && !f.name.startsWith(".") && f.extension.equals("gpx", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Scans the public Download folder and external app storage (`/sdcard/Android/data/cz.offgrid.maps/files/tracks`)
     * and automatically imports newly added .gpx routes unless previously deleted by the user.
     */
    private fun syncExternalGpxDirectories(context: Context, internalTracksDir: File) {
        val deletedNames = getDeletedTrackNames(internalTracksDir)
        val candidateDirs = mutableListOf<File>()
        context.getExternalFilesDir("tracks")?.let { candidateDirs.add(it) }
        context.getExternalFilesDir(null)?.let { candidateDirs.add(it) }
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { dl ->
            candidateDirs.add(dl)
            candidateDirs.add(File(dl, "OffGridMaps"))
        }

        for (extDir in candidateDirs) {
            val files = try {
                extDir.listFiles { f -> f.isFile && f.extension.equals("gpx", ignoreCase = true) }
            } catch (_: Exception) {
                null
            } ?: continue

            for (extFile in files) {
                if (deletedNames.contains(extFile.name)) continue
                val target = File(internalTracksDir, extFile.name)
                if (!target.exists() && extFile.length() > 0L) {
                    try {
                        extFile.copyTo(target, overwrite = false)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /**
     * Parses route points from a GPX 1.1 file (supports both `<trkpt>` and `<rtept>` from Mapy.com).
     */
    fun parseGpxFile(file: File): List<TrackPoint> {
        if (!file.exists()) return emptyList()
        return try {
            parseGpxContent(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parses route points from GPX 1.1 XML content (independent of lat/lon attribute order or line breaks).
     */
    fun parseGpxContent(xmlContent: String): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        for (match in pointTagRegex.findAll(xmlContent)) {
            val attrs = match.groupValues[1]
            val innerBody = match.groupValues[2]

            val lat = latAttrRegex.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
            val lon = lonAttrRegex.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
            if (lat != null && lon != null) {
                val ele = eleTagRegex.find(innerBody)?.groupValues?.get(1)?.toDoubleOrNull()
                points.add(
                    TrackPoint(
                        latitude = lat,
                        longitude = lon,
                        altitudeMeters = ele,
                        timestampMillis = 0L
                    )
                )
            }
        }
        return points
    }

    /**
     * Deletes a saved .gpx file from internal storage and records its deletion in `.deleted_tracks`
     * so it is not re-imported from `Download/OffGridMaps` during folder sync.
     */
    fun deleteGpxFile(file: File): Boolean {
        file.parentFile?.let { dir ->
            recordDeletedTrackName(dir, file.name)
        }
        // Also remove any backup copy in public Download/OffGridMaps
        try {
            @Suppress("DEPRECATION")
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dlDir, file.name).takeIf { it.exists() }?.delete()
            File(File(dlDir, "OffGridMaps"), file.name).takeIf { it.exists() }?.delete()
        } catch (_: Exception) {
        }
        return if (file.exists()) file.delete() else false
    }

    private fun getDeletedTrackNames(tracksDir: File): Set<String> {
        val registry = File(tracksDir, ".deleted_tracks")
        if (!registry.exists()) return emptySet()
        return try {
            registry.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun recordDeletedTrackName(tracksDir: File, fileName: String) {
        try {
            val current = getDeletedTrackNames(tracksDir).toMutableSet()
            if (current.add(fileName)) {
                File(tracksDir, ".deleted_tracks").writeText(current.joinToString("\n"))
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun getTracksDir(context: Context): File {
        return File(context.filesDir, "tracks").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun ensureSampleTrackExists(context: Context, tracksDir: File) {
        val initMarker = File(tracksDir, ".sample_seeded_v5")
        if (initMarker.exists()) return
        initMarker.writeText("1")

        // Remove obsolete sample tracks from earlier development builds
        listOf(
            "oslavany_kct_okruh.gpx",
            "Oslavany_zamecky_okruh.gpx",
            "Zbysovska_halda_okruh.gpx",
            "Oslavany_Zbysov_KCT.gpx",
            "Oslavany_Ivancice_KCT.gpx"
        ).forEach { oldName ->
            File(tracksDir, oldName).takeIf { it.exists() }?.delete()
            recordDeletedTrackName(tracksDir, oldName)
        }

        // Seed the real Oslava River Valley route (3,131 points) from assets
        val assetName = "Udoli_Oslava.gpx"
        val target = File(tracksDir, assetName)
        if (!target.exists()) {
            try {
                context.assets.open(assetName).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
