package cz.offgrid.maps.data.tracking

import android.content.Context
import cz.offgrid.maps.domain.model.TrackPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exportér a čtečka standardního formátu GPX 1.1 pro uložené, naplánované a nouzově obnovené túry.
 */
object GpxExporter {

    private const val RECOVERY_FILE_NAME = ".active_recovery.gpx"
    private val trkptRegex = Regex("""<trkpt\s+lat="([0-9.-]+)"\s+lon="([0-9.-]+)"""")
    private val eleRegex = Regex("""<ele>([0-9.-]+)</ele>""")

    /**
     * Uloží seznam zaznamenaných bodů do souboru .gpx v interním úložišti hodinek (/files/tracks/).
     */
    fun saveTrackToGpx(context: Context, points: List<TrackPoint>, prefix: String = "tura"): File? {
        if (points.isEmpty()) return null

        val tracksDir = getTracksDir(context)
        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val gpxFile = File(tracksDir, "${prefix}_$fileTimestamp.gpx")

        gpxFile.writeText(buildGpxXml("Túra $fileTimestamp", points))
        clearRecoveryCheckpoint(context)
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
        val recoveredFile = File(tracksDir, "obnoveno_$fileTimestamp.gpx")
        recoveryFile.renameTo(recoveredFile)
        return recoveredFile
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
     * Vrátí seznam všech uložených .gpx tras (seřazených od nejnovějších).
     */
    fun listSavedGpxFiles(context: Context): List<File> {
        val dir = getTracksDir(context)
        ensureSampleTrackExists(dir)
        return dir.listFiles { f ->
            f.isFile && !f.name.startsWith(".") && f.extension.equals("gpx", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Načte body trasy ze souboru GPX 1.1.
     */
    fun parseGpxFile(file: File): List<TrackPoint> {
        if (!file.exists()) return emptyList()
        val points = mutableListOf<TrackPoint>()
        file.useLines { lines ->
            lines.forEach { line ->
                val match = trkptRegex.find(line)
                if (match != null) {
                    val lat = match.groupValues[1].toDoubleOrNull()
                    val lon = match.groupValues[2].toDoubleOrNull()
                    val ele = eleRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                    if (lat != null && lon != null) {
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
            }
        }
        return points
    }

    /**
     * Smaže uložený .gpx soubor z interního úložiště hodinek.
     */
    fun deleteGpxFile(file: File): Boolean {
        return if (file.exists()) file.delete() else false
    }

    private fun getTracksDir(context: Context): File {
        return File(context.filesDir, "tracks").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun ensureSampleTrackExists(tracksDir: File) {
        val initMarker = File(tracksDir, ".sample_seeded")
        if (initMarker.exists()) return
        initMarker.writeText("1")

        val sampleFile = File(tracksDir, "oslavany_kct_okruh.gpx")
        if (sampleFile.exists()) return

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
