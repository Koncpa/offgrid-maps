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
 * Exportér, importér a čtečka standardního formátu GPX 1.1 (kompatibilní s Mapy.cz, Garmin, Strava, Komoot).
 */
object GpxExporter {

    private const val RECOVERY_FILE_NAME = ".active_recovery.gpx"

    // Podpora pro <trkpt> (zaznamenaná stopa) i <rtept> (naplánovaná trasa z Mapy.cz) včetně víceřádkového zápisu
    private val pointTagRegex = Regex(
        """<(?:trkpt|rtept)\b([^>]*?)(?:/>|>(.*?)</(?:trkpt|rtept)>)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val latAttrRegex = Regex("""\blat\s*=\s*["']([0-9.+-]+)["']""", RegexOption.IGNORE_CASE)
    private val lonAttrRegex = Regex("""\blon\s*=\s*["']([0-9.+-]+)["']""", RegexOption.IGNORE_CASE)
    private val eleTagRegex = Regex("""<ele>\s*([0-9.+-]+)\s*</ele>""", RegexOption.IGNORE_CASE)

    /**
     * Uloží seznam zaznamenaných bodů do souboru .gpx v interním úložišti hodinek (/files/tracks/)
     * a zároveň automaticky vyexportuje kopii do veřejné složky `Download/OffGridMaps/`.
     */
    fun saveTrackToGpx(context: Context, points: List<TrackPoint>, prefix: String = "tura"): File? {
        if (points.isEmpty()) return null

        val tracksDir = getTracksDir(context)
        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${prefix}_$fileTimestamp.gpx"
        val gpxFile = File(tracksDir, fileName)

        val xmlContent = buildGpxXml("Túra $fileTimestamp", points)
        gpxFile.writeText(xmlContent)
        clearRecoveryCheckpoint(context)

        // Automaticky uložíme kopii také do veřejné složky Download/OffGridMaps/ pro snadné stažení do telefonu/PC
        exportToPublicDownloads(context, fileName, xmlContent)

        return gpxFile
    }

    /**
     * Průběžně ukládá rozpracovanou trasu na disk pro případ náhlého vybití nebo restartu hodinek.
     */
    fun saveRecoveryCheckpoint(context: Context, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        val recoveryFile = File(getTracksDir(context), RECOVERY_FILE_NAME)
        recoveryFile.writeText(buildGpxXml("Obnovená túra", points))
    }

    /**
     * Odstraní dočasný soubor obnovy po řádném uložení trasy.
     */
    fun clearRecoveryCheckpoint(context: Context) {
        val recoveryFile = File(getTracksDir(context), RECOVERY_FILE_NAME)
        if (recoveryFile.exists()) {
            recoveryFile.delete()
        }
    }

    /**
     * Při startu aplikace zkontroluje, zda na disku nezůstala neukončená trasa (např. po vybití baterie),
     * a automaticky ji převede na plnohodnotný .gpx záznam.
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
        val fileName = "obnoveno_$fileTimestamp.gpx"
        val recoveredFile = File(tracksDir, fileName)
        val xmlContent = recoveryFile.readText()
        recoveryFile.renameTo(recoveredFile)
        exportToPublicDownloads(context, fileName, xmlContent)
        return recoveredFile
    }

    /**
     * Importuje .gpx soubor z Uri (např. ze systémového výběru souborů nebo po přijetí přes Bluetooth / Quick Share).
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
     * Vyexportuje .gpx soubor do veřejné složky `Download/OffGridMaps/` a otevře systémové sdílení
     * (Quick Share / Bluetooth / odeslání do telefonu).
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
            val chooser = Intent.createChooser(shareIntent, "Odeslat GPX").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            return true
        } catch (_: Exception) {
            return exportedToDownloads
        }
    }

    /**
     * Zapíše kopii .gpx souboru do veřejné složky `Download/OffGridMaps/` (funguje bez oprávnění na Androidu 10+).
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
     * Vrátí seznam všech uložených .gpx tras (automaticky synchronizuje i .gpx soubory nahrané do složky Download nebo external files).
     */
    fun listSavedGpxFiles(context: Context): List<File> {
        val dir = getTracksDir(context)
        ensureSampleTrackExists(dir)
        syncExternalGpxDirectories(context, dir)
        return dir.listFiles { f ->
            f.isFile && !f.name.startsWith(".") && f.extension.equals("gpx", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Zkontroluje veřejnou složku Download a externí složku aplikace (`/sdcard/Android/data/cz.offgrid.maps/files/tracks`)
     * a automaticky naimportuje nově vložené .gpx trasy (např. z Mapy.cz), pokud nebyly uživatelem smazány.
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
     * Načte body trasy ze souboru GPX 1.1 (podporuje `<trkpt>` i `<rtept>` z Mapy.cz).
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
     * Načte body trasy z textového obsahu GPX 1.1 (nezávisle na pořadí atributů lat/lon a řádkování).
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
     * Smaže uložený .gpx soubor z interního úložiště hodinek a zaznamená jeho smazání,
     * aby se znovu automaticky nenačetl ze záložní složky Download/OffGridMaps.
     */
    fun deleteGpxFile(file: File): Boolean {
        file.parentFile?.let { dir ->
            recordDeletedTrackName(dir, file.name)
        }
        // Pokusíme se rovnou smazat i případnou kopii ve veřejné složce Download/OffGridMaps
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

    private fun ensureSampleTrackExists(tracksDir: File) {
        val initMarker = File(tracksDir, ".sample_seeded_v2")
        if (initMarker.exists()) return
        initMarker.writeText("1")

        // Ukázková trasa 1: Okruh přes Zbýšovskou haldu a Zakřany (pro otestování navigace a vrstevnic)
        val zbysovFile = File(tracksDir, "Zbysovska_halda_okruh.gpx")
        if (!zbysovFile.exists()) {
            val zbysovGpx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="OffGrid Maps Wear OS">
                  <trk>
                    <name>Zbýšovská halda a Zakřany (Okruh)</name>
                    <trkseg>
                      <trkpt lat="49.1558" lon="16.3492"><ele>345.0</ele></trkpt>
                      <trkpt lat="49.1535" lon="16.3461"><ele>362.0</ele></trkpt>
                      <trkpt lat="49.1506" lon="16.3435"><ele>388.0</ele></trkpt>
                      <trkpt lat="49.1489" lon="16.3398"><ele>374.0</ele></trkpt>
                      <trkpt lat="49.1524" lon="16.3342"><ele>392.0</ele></trkpt>
                      <trkpt lat="49.1578" lon="16.3315"><ele>412.0</ele></trkpt>
                      <trkpt lat="49.1625" lon="16.3368"><ele>405.0</ele></trkpt>
                      <trkpt lat="49.1607" lon="16.3443"><ele>368.0</ele></trkpt>
                      <trkpt lat="49.1558" lon="16.3492"><ele>345.0</ele></trkpt>
                    </trkseg>
                  </trk>
                </gpx>
            """.trimIndent()
            zbysovFile.writeText(zbysovGpx)
        }

        // Ukázková trasa 2: Oslavany - Zámecký okruh KČT
        val sampleFile = File(tracksDir, "Oslavany_zamecky_okruh.gpx")
        if (!sampleFile.exists()) {
            val sampleGpx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="OffGrid Maps Wear OS">
                  <trk>
                    <name>Oslavany - Zámecký okruh KČT</name>
                    <trkseg>
                      <trkpt lat="49.1246" lon="16.3353"><ele>228.0</ele></trkpt>
                      <trkpt lat="49.1268" lon="16.3321"><ele>242.0</ele></trkpt>
                      <trkpt lat="49.1295" lon="16.3274"><ele>268.0</ele></trkpt>
                      <trkpt lat="49.1322" lon="16.3228"><ele>295.0</ele></trkpt>
                      <trkpt lat="49.1301" lon="16.3180"><ele>310.0</ele></trkpt>
                      <trkpt lat="49.1264" lon="16.3215"><ele>276.0</ele></trkpt>
                      <trkpt lat="49.1246" lon="16.3353"><ele>228.0</ele></trkpt>
                    </trkseg>
                  </trk>
                </gpx>
            """.trimIndent()
            sampleFile.writeText(sampleGpx)
        }
    }
}
