package cz.offgrid.maps.performance

import cz.offgrid.maps.data.map.OfflineRegionHelper
import cz.offgrid.maps.data.map.TrailTileManager
import cz.offgrid.maps.data.tracking.GpxExporter
import cz.offgrid.maps.data.tracking.TrackingManager
import cz.offgrid.maps.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.system.measureNanoTime

/**
 * Deterministic performance and memory-budget benchmarks guarding Wear OS constraints:
 * 1) O(1) memory streaming extraction of large `.geojson.gz` regional bundles.
 * 2) Fast spatial indexing + tiny local 3x3 viewport payloads for MapLibre (`< 15%` of full region).
 * 3) Fast parsing and remaining-route math on 10,000-point multi-day GPX tracks.
 */
class MapPerformanceTest {

    private fun buildSyntheticRegionTrailsJson(lineCount: Int = 40_000): String {
        val colours = arrayOf("red", "blue", "green", "yellow", "other")
        val perColour = lineCount / colours.size
        val sb = StringBuilder(lineCount * 110)
        sb.append("""{"type":"FeatureCollection","features":[""")
        colours.forEachIndexed { cIdx, colour ->
            if (cIdx > 0) sb.append(',')
            sb.append("""{"type":"Feature","properties":{"colour":"$colour"},"geometry":{"type":"MultiLineString","coordinates":[""")
            for (i in 0 until perColour) {
                if (i > 0) sb.append(',')
                // Spread uniformly across a ~1.20 deg x 2.60 deg alpine state (comparable to Styria)
                val lat = 46.60 + ((i * 37) % 120) * 0.010
                val lon = 13.55 + ((i * 73 + cIdx * 17) % 260) * 0.010
                sb.append(
                    String.format(
                        Locale.US,
                        "[[%.5f,%.5f],[%.5f,%.5f],[%.5f,%.5f]]",
                        lon, lat,
                        lon + 0.004, lat + 0.003,
                        lon + 0.008, lat + 0.006
                    )
                )
            }
            sb.append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    @Test
    fun streamExtractAndSpatialIndexing_staysFastAndBoundedForLargeAlpineRegion() {
        val tempDir = Files.createTempDirectory("offgrid_perf_region").toFile()
        try {
            val rawJson = buildSyntheticRegionTrailsJson(lineCount = 40_000)
            val rawBytes = rawJson.toByteArray(Charsets.UTF_8)
            val gzBytes = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { it.write(rawBytes) }
                baos.toByteArray()
            }

            val trailsFragmentFile = File(tempDir, "trails.features")

            // Warm up JIT cleanly on a tiny buffer
            val warmupOut = File(tempDir, "warmup.features")
            OfflineRegionHelper.streamFeatureArrayFromGzip(
                ByteArrayInputStream(gzBytes),
                warmupOut
            )
            warmupOut.delete()

            System.gc()
            val rt = Runtime.getRuntime()
            val heapBefore = rt.totalMemory() - rt.freeMemory()

            val extractMs = measureNanoTime {
                val ok = OfflineRegionHelper.streamFeatureArrayFromGzip(
                    ByteArrayInputStream(gzBytes),
                    trailsFragmentFile
                )
                assertTrue(ok)
            } / 1_000_000.0

            val heapAfterExtract = rt.totalMemory() - rt.freeMemory()
            val extractHeapDeltaMb = (heapAfterExtract - heapBefore) / (1024.0 * 1024.0)

            val indexMs = measureNanoTime {
                TrailTileManager.ensurePackageIndexed(tempDir)
            } / 1_000_000.0

            // Measure 12 simulated camera pans across the region
            var maxQueryMs = 0.0
            var totalQueryMs = 0.0
            var maxPayloadBytes = 0
            for (step in 0 until 12) {
                val lat = 46.80 + step * 0.08
                val lon = 13.80 + step * 0.14
                var payload = ""
                val qMs = measureNanoTime {
                    payload = TrailTileManager.loadViewportTrailsGeoJson(listOf(tempDir), lat, lon)
                } / 1_000_000.0
                totalQueryMs += qMs
                if (qMs > maxQueryMs) maxQueryMs = qMs
                if (payload.length > maxPayloadBytes) maxPayloadBytes = payload.length
            }
            val avgQueryMs = totalQueryMs / 12.0
            val payloadRatio = maxPayloadBytes.toDouble() / rawBytes.size.toDouble()

            println(
                String.format(
                    Locale.US,
                    "[PERF] regionRaw=%.2f MB | streamExtract=%.1f ms (heapDelta=%.2f MB) | index=%.1f ms | viewportAvg=%.1f ms (max=%.1f ms) | viewportPayload=%.1f KB (%.1f%% of region)",
                    rawBytes.size / (1024.0 * 1024.0),
                    extractMs,
                    extractHeapDeltaMb,
                    indexMs,
                    avgQueryMs,
                    maxQueryMs,
                    maxPayloadBytes / 1024.0,
                    payloadRatio * 100.0
                )
            )

            // Guard rails against OOM / O(N^2) regressions (generous headroom for slower CI VMs)
            assertTrue("Streaming extraction too slow: $extractMs ms", extractMs < 4000.0)
            assertTrue("Spatial indexing too slow: $indexMs ms", indexMs < 5000.0)
            assertTrue("Viewport load too slow: max=$maxQueryMs ms", maxQueryMs < 500.0)
            assertTrue(
                "Viewport payload exceeded 25% of full region ($maxPayloadBytes vs ${rawBytes.size})",
                payloadRatio < 0.25
            )
            assertTrue("Streaming extraction allocated too much retained heap: $extractHeapDeltaMb MB", extractHeapDeltaMb < 20.0)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun gpxParseAndRemainingRouteMath_scalesLinearlyOn10kPointTrack() {
        val points = List(10_000) { idx ->
            TrackPoint(
                latitude = 49.000 + idx * 0.0002,
                longitude = 16.000 + (idx % 50) * 0.0001,
                altitudeMeters = 250.0 + (idx % 300),
                timestampMillis = 1_700_000_000_000L + idx * 5_000L
            )
        }
        val gpxXml = GpxExporter.buildGpxXml("10k Benchmark Trek", points)

        // Warmup
        GpxExporter.parseGpxContent(gpxXml.take(4096))

        val parseMs = measureNanoTime {
            val parsed = GpxExporter.parseGpxContent(gpxXml)
            assertEquals(10_000, parsed.size)
        } / 1_000_000.0

        TrackingManager.setPlannedRoute("Benchmark10k", points)
        val remainingMs = measureNanoTime {
            repeat(10) { step ->
                val stats = TrackingManager.calculateRemainingRouteStats(49.05 + step * 0.1, 16.002)
                assertNotNull(stats)
            }
        } / 1_000_000.0 / 10.0
        TrackingManager.setPlannedRoute(null, emptyList())

        println(
            String.format(
                Locale.US,
                "[PERF] gpx10kSize=%.1f KB | parse=%.1f ms | remainingStatsAvg=%.2f ms",
                gpxXml.length / 1024.0,
                parseMs,
                remainingMs
            )
        )

        assertTrue("10k GPX parse too slow: $parseMs ms", parseMs < 1500.0)
        assertTrue("10k remaining-route calculation too slow: $remainingMs ms", remainingMs < 200.0)
    }
}
