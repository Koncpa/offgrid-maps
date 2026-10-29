package cz.offgrid.maps.data.tracking

import android.content.Context
import cz.offgrid.maps.domain.model.TrackPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exportér a čtečka standardního formátu GPX 1.1 pro uložené túry.
 */
object GpxExporter {

    /**
     * Uloží seznam zaznamenaných bodů do souboru .gpx v interním úložišti hodinek (/files/tracks/).
     */
    fun saveTrackToGpx(context: Context, points: List<TrackPoint>): File? {
        if (points.isEmpty()) return null

        val tracksDir = File(context.filesDir, "tracks").apply {
            if (!exists()) mkdirs()
        }

        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val gpxFile = File(tracksDir, "offgrid_$fileTimestamp.gpx")

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="OffGrid Maps Wear OS" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("""  <trk>""")
        sb.appendLine("""    <name>Túra $fileTimestamp</name>""")
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

        gpxFile.writeText(sb.toString())
        return gpxFile
    }
}
