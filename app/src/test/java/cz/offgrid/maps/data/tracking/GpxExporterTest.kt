package cz.offgrid.maps.data.tracking

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cz.offgrid.maps.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpxExporterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TrackingManager.clearTrack()
        File(context.filesDir, "tracks").deleteRecursively()
    }

    @Test
    fun buildGpxXml_andParseGpxContent_roundTripPreservesNegativeHemisphereAndElevation() {
        val original = listOf(
            TrackPoint(-41.1335, -71.3103, altitudeMeters = 1420.5, timestampMillis = 1_700_000_000_000L),
            TrackPoint(-41.1350, -71.3125, altitudeMeters = null, timestampMillis = 1_700_000_010_000L),
            TrackPoint(47.8712, 13.8351, altitudeMeters = 1691.0, timestampMillis = 1_700_000_020_000L)
        )

        val xml = GpxExporter.buildGpxXml("Patagonia & <Traunstein> \"Loop\"", original)
        assertTrue(xml.contains("<name>Patagonia &amp; &lt;Traunstein&gt; &quot;Loop&quot;</name>"))

        val parsed = GpxExporter.parseGpxContent(xml)
        assertEquals(3, parsed.size)
        assertEquals(-41.1335, parsed[0].latitude, 0.0001)
        assertEquals(-71.3103, parsed[0].longitude, 0.0001)
        assertEquals(1420.5, parsed[0].altitudeMeters!!, 0.1)
        assertNull(parsed[1].altitudeMeters)
        assertEquals(1691.0, parsed[2].altitudeMeters!!, 0.1)
    }

    @Test
    fun parseGpxContent_handlesSelfClosingReversedAttributesAndMalformedXml() {
        val mixedXml = """
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lon="-122.4194" lat="37.7749"/>
                <rtept
                    lon='-105.6438'
                    lat='40.2549'>
                  <ele>4345.2</ele>
                </rtept>
                <trkpt lat="invalid" lon="16.0"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val parsed = GpxExporter.parseGpxContent(mixedXml)
        assertEquals(2, parsed.size)
        assertEquals(37.7749, parsed[0].latitude, 0.0001)
        assertEquals(-122.4194, parsed[0].longitude, 0.0001)
        assertNull(parsed[0].altitudeMeters)
        assertEquals(40.2549, parsed[1].latitude, 0.0001)
        assertEquals(-105.6438, parsed[1].longitude, 0.0001)
        assertEquals(4345.2, parsed[1].altitudeMeters!!, 0.1)

        assertTrue(GpxExporter.parseGpxContent("").isEmpty())
        assertTrue(GpxExporter.parseGpxContent("<broken><not-gpx").isEmpty())
    }

    @Test
    fun saveRecoveryCheckpoint_andRecoverUnfinishedTrack_recoversAfterCrashAndRespectsActiveTracking() {
        val points = listOf(
            TrackPoint(49.1200, 16.3300, altitudeMeters = 310.0, timestampMillis = 1_700_000_000_000L),
            TrackPoint(49.1240, 16.3350, altitudeMeters = 365.0, timestampMillis = 1_700_000_060_000L)
        )
        GpxExporter.saveRecoveryCheckpoint(context, points)

        val tracksDir = File(context.filesDir, "tracks")
        val recoveryFile = File(tracksDir, ".active_recovery.gpx")
        assertTrue("Recovery checkpoint must exist on disk", recoveryFile.exists())
        assertFalse("Temp checkpoint file must be renamed/cleaned", File(tracksDir, ".active_recovery.gpx.tmp").exists())

        // Must NOT recover while tracking is currently active
        TrackingManager.setTrackingActive(true)
        assertNull(GpxExporter.recoverUnfinishedTrackIfAny(context))
        assertTrue(recoveryFile.exists())

        // Once tracking is inactive (simulating restart after battery loss), promote checkpoint to permanent GPX
        TrackingManager.setTrackingActive(false)
        val recovered = GpxExporter.recoverUnfinishedTrackIfAny(context)
        assertNotNull(recovered)
        assertTrue(recovered!!.name.startsWith("recovered_"))
        assertFalse("Original hidden recovery file must be removed after promotion", recoveryFile.exists())
        assertEquals(2, GpxExporter.parseGpxFile(recovered).size)
    }

    @Test
    fun saveTrackToGpx_listSavedGpxFiles_andDeleteGpxFile_fullLifecycle() {
        val pts = listOf(
            TrackPoint(48.8700, 16.6500, altitudeMeters = 410.0, timestampMillis = 1_700_000_000_000L),
            TrackPoint(48.8750, 16.6550, altitudeMeters = 550.0, timestampMillis = 1_700_000_120_000L)
        )
        val saved = GpxExporter.saveTrackToGpx(context, pts, prefix = "palava")
        assertNotNull(saved)
        assertTrue(saved!!.exists())

        val listed = GpxExporter.listSavedGpxFiles(context)
        assertTrue("Saved track must appear in listed GPX files", listed.any { it.name == saved.name })
        assertTrue("Hidden checkpoint files must never be listed", listed.none { it.name.startsWith(".") })

        assertTrue(GpxExporter.deleteGpxFile(saved))
        assertFalse(saved.exists())
        assertFalse(GpxExporter.listSavedGpxFiles(context).any { it.name == saved.name })
    }

    @Test
    fun importGpxFromUri_importsValidFileSanitizesNameAndRejectsInvalidXml() {
        val cacheDir = context.cacheDir
        val validSource = File(cacheDir, "Sněžka (2026) #1!.gpx").apply {
            writeText(
                """<gpx version="1.1"><trk><trkseg><trkpt lat="50.7360" lon="15.7397"><ele>1603</ele></trkpt></trkseg></trk></gpx>"""
            )
        }
        val imported = GpxExporter.importGpxFromUri(context, Uri.fromFile(validSource))
        assertNotNull(imported)
        assertTrue(imported!!.exists())
        assertFalse("Unsafe characters must be sanitized from filename", imported.name.contains("#"))
        assertFalse("Unsafe characters must be sanitized from filename", imported.name.contains("!"))
        assertTrue("Czech diacritics should be preserved", imported.name.contains("Sněžka"))
        assertEquals(1, GpxExporter.parseGpxFile(imported).size)

        val invalidSource = File(cacheDir, "not_a_track.gpx").apply {
            writeText("hello world without any track points")
        }
        assertNull("Invalid GPX without coordinates must be rejected", GpxExporter.importGpxFromUri(context, Uri.fromFile(invalidSource)))
    }

    @Test
    fun summarizeGpxFile_computesDistanceAndPositiveAscentFromDisk() {
        val pts = listOf(
            TrackPoint(49.0000, 16.0000, altitudeMeters = 300.0, timestampMillis = 1_700_000_000_000L),
            TrackPoint(49.0090, 16.0000, altitudeMeters = 420.0, timestampMillis = 1_700_000_060_000L),
            TrackPoint(49.0180, 16.0000, altitudeMeters = 380.0, timestampMillis = 1_700_000_120_000L)
        )
        val saved = GpxExporter.saveTrackToGpx(context, pts, prefix = "sum")!!
        val summary = GpxExporter.summarizeGpxFile(saved)
        assertEquals(3, summary.pointCount)
        assertEquals(2.0, summary.distanceKm, 0.05)
        assertEquals("Only positive elevation gains should count toward ascent", 120, summary.ascentMeters)
    }

    @Test
    fun syncExternalGpxDirectories_autoImportsExternalTracksAndHonorsDeletedTombstone() {
        val extTracksDir = context.getExternalFilesDir("tracks")!!
        extTracksDir.mkdirs()
        val externalGpx = File(extTracksDir, "beskydy_ext.gpx").apply {
            writeText("""<gpx version="1.1"><trk><trkseg><trkpt lat="49.5460" lon="18.4470"><ele>1323</ele></trkpt></trkseg></trk></gpx>""")
        }
        try {
            val firstList = GpxExporter.listSavedGpxFiles(context)
            val synced = firstList.firstOrNull { it.name == "beskydy_ext.gpx" }
            assertNotNull("External GPX in external files/tracks must auto-import", synced)

            // Deleting must record tombstone so subsequent listSavedGpxFiles does NOT re-import it
            assertTrue(GpxExporter.deleteGpxFile(synced!!))
            val secondList = GpxExporter.listSavedGpxFiles(context)
            assertFalse("Deleted external track must not be re-imported", secondList.any { it.name == "beskydy_ext.gpx" })
        } finally {
            externalGpx.delete()
        }
    }

    @Test
    fun exportAndShareHelpers_handleMissingFilesAndSystemShareSheet() {
        val missing = File(context.filesDir, "tracks/does_not_exist.gpx")
        assertFalse(GpxExporter.exportGpxBackup(context, missing))
        assertFalse(GpxExporter.hasShareTarget(context, missing))
        assertFalse(GpxExporter.launchSystemShareIfAvailable(context, missing))
        assertFalse(GpxExporter.shareAndExportGpxFile(context, missing))

        val saved = GpxExporter.saveTrackToGpx(
            context,
            listOf(TrackPoint(49.12, 16.33, 310.0, 1_700_000_000_000L)),
            prefix = "share"
        )!!
        // Without a registered external ACTION_SEND receiver, hasShareTarget & launch return false safely
        assertFalse(GpxExporter.hasShareTarget(context, saved))
        assertFalse(GpxExporter.launchSystemShareIfAvailable(context, saved))
        // Empty track save returns null safely
        assertNull(GpxExporter.saveTrackToGpx(context, emptyList()))
    }
}

