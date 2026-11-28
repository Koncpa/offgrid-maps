package cz.offgrid.maps.data.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
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
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineRegionContextTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("offgrid_map_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "maps").deleteRecursively()
        File(context.filesDir, "github_regions").deleteRecursively()
        File(context.filesDir, "contour_tiles").deleteRecursively()
        File(context.filesDir, "jmk_poi_tiles").deleteRecursively()
        OfflineRegionHelper.invalidateTrailPackages()
        OfflineRegionHelper.clearDownloadQueue()
    }

    @Test
    fun saveAndLoadLastKnownCenter_persistsThrottlesAndForcesFlush() {
        // Initial save must persist cleanly
        OfflineRegionHelper.saveLastKnownCenter(context, 47.8712, 13.8351, force = true)
        val (lat1, lon1) = OfflineRegionHelper.loadLastKnownCenter(context)
        assertEquals(47.8712, lat1, 0.001)
        assertEquals(13.8351, lon1, 0.001)

        // Tiny 20 m nudge without force must be throttled and NOT rewrite SharedPreferences
        OfflineRegionHelper.saveLastKnownCenter(context, 47.8714, 13.8353, force = false)
        val (lat2, lon2) = OfflineRegionHelper.loadLastKnownCenter(context)
        assertEquals(47.8712, lat2, 0.001)
        assertEquals(13.8351, lon2, 0.001)

        // Move > 155 m must persist the updated coordinate
        OfflineRegionHelper.saveLastKnownCenter(context, 47.8750, 13.8351, force = false)
        val (lat3, lon3) = OfflineRegionHelper.loadLastKnownCenter(context)
        assertEquals(47.8750, lat3, 0.001)
        assertEquals(13.8351, lon3, 0.001)
    }

    @Test
    fun mapFileManager_listsMbtilesCaseInsensitivelyAndComputesTotalSize() {
        val mgr = MapFileManager(context)
        assertNull(mgr.getDefaultMap())
        assertEquals(0L, mgr.getTotalMapsSizeMb())

        File(mgr.mapsDirectory, "alps.mbtiles").writeBytes(ByteArray(1024 * 1024))
        File(mgr.mapsDirectory, "TATRAS.MBTILES").writeBytes(ByteArray(2 * 1024 * 1024))
        File(mgr.mapsDirectory, "notes.txt").writeText("ignored")

        val maps = mgr.getAvailableMaps()
        assertEquals(2, maps.size)
        assertNotNull(mgr.getDefaultMap())
        assertEquals(3L, mgr.getTotalMapsSizeMb())
    }

    @Test
    fun ensureLocalStyleServerUrl_servesValidStyleJsonOverLoopback() {
        val urlStr = OfflineRegionHelper.ensureLocalStyleServerUrl()
        assertTrue("Local server must bind strictly to 127.0.0.1", urlStr.startsWith("http://127.0.0.1:"))

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        try {
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            assertEquals(8, json.getInt("version"))
            assertTrue(json.getJSONObject("sources").has("offline_source"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun refreshActiveViewportOnExecutor_loadsVisibleRegionsTrailsAndPoisAndExcludesDistantRegions() {
        val regionsRoot = File(context.filesDir, "github_regions")
        val alpsDir = File(regionsRoot, "at_salzburg").apply { mkdirs() }
        File(alpsDir, "region_meta.json").writeText(
            """{"title":"Salzburg","bounds":[48.0,14.2,47.0,12.5]}"""
        )
        File(alpsDir, "trails.features").writeText(
            """{"type":"Feature","properties":{"colour":"red"},"geometry":{"type":"MultiLineString","coordinates":[[[13.83,47.87],[13.84,47.88]]]}}"""
        )
        File(alpsDir, "pois.features").writeText(
            """{"type":"Feature","properties":{"cat":"water","label":"Traunstein Spring"},"geometry":{"type":"Point","coordinates":[13.835,47.871]}}"""
        )

        val distantDir = File(regionsRoot, "ro_romania").apply { mkdirs() }
        File(distantDir, "region_meta.json").writeText(
            """{"title":"Romania","bounds":[48.0,29.0,44.0,21.0]}"""
        )
        File(distantDir, "pois.features").writeText(
            """{"type":"Feature","properties":{"cat":"shelter","label":"Carpathian Hut"},"geometry":{"type":"Point","coordinates":[25.0,45.5]}}"""
        )

        OfflineRegionHelper.invalidateTrailPackages()
        OfflineRegionHelper.refreshActiveViewportOnExecutor(context, 47.87, 13.835)

        val trailsJson = OfflineRegionHelper.customTrailsGeoJson.value.orEmpty()
        assertTrue("Visible Salzburg trail must load", trailsJson.contains("13.83"))
        assertFalse("Raw trails.features must be cleaned post-index", File(alpsDir, "trails.features").exists())
        assertFalse("Raw pois.features must be cleaned post-index", File(alpsDir, "pois.features").exists())

        val pois = OfflineRegionHelper.activePois.value
        assertEquals(1, pois.size)
        assertEquals("Traunstein Spring", pois[0].label)
        assertFalse(OfflineRegionHelper.customPoisGeoJson.value.orEmpty().contains("Carpathian Hut"))
    }

    @Test
    fun contourTileManager_importTilePackageAndUpdateViewport_readsCacheAndSkipsBundledJmkTiles() {
        val sampleLines = buildString {
            // Non-JMK tile (Austria) -> must be loaded
            append("12/2204/1428\t")
            append("""{"type":"Feature","properties":{"ele":1000,"major":1},"geometry":{"type":"MultiLineString","coordinates":[[[13.83,47.87],[13.84,47.87]]]}}""")
            append('\n')
            // Bundled JMK tile (2233_1404) -> must be skipped to avoid duplicating assets/jmk_contours.geojson
            append("12/2233/1404\t")
            append("""{"type":"Feature","properties":{"ele":350,"major":1},"geometry":{"type":"MultiLineString","coordinates":[[[16.33,49.12],[16.34,49.12]]]}}""")
            append('\n')
        }
        val gz = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(sampleLines.toByteArray(Charsets.UTF_8)) }
            baos.toByteArray()
        }

        val count = ContourTileManager.importTilePackage(context, ByteArrayInputStream(gz))
        assertEquals(2, count)

        ContourTileManager.updateActiveViewportTiles(
            context = context,
            minTx = 2204,
            maxTx = 2204,
            minTy = 1428,
            maxTy = 1428,
            allowNetworkFetch = false
        )
        val austriaContours = ContourTileManager.customContoursGeoJson.value.orEmpty()
        assertTrue("Imported Austrian contour tile must be emitted", austriaContours.contains("\"ele\":1000"))

        ContourTileManager.updateActiveViewportTiles(
            context = context,
            minTx = 2233,
            maxTx = 2233,
            minTy = 1404,
            maxTy = 1404,
            allowNetworkFetch = false
        )
        val jmkContours = ContourTileManager.customContoursGeoJson.value.orEmpty()
        assertFalse("Bundled JMK tile must be skipped in custom contours", jmkContours.contains("\"ele\":350"))
    }

    @Test
    fun streamZipFromUrlWithImporter_downloadsValidZipOverLoopbackAndReturnsNullOn404() {
        fun gzipBytes(text: String): ByteArray = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            baos.toByteArray()
        }

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("region_meta.json"))
                zos.write("""{"title":"Tirol","bounds":[47.6,12.5,46.8,10.2]}""".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("kct_trails.geojson.gz"))
                zos.write(
                    gzipBytes(
                        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"colour":"red"},"geometry":{"type":"MultiLineString","coordinates":[[[11.4,47.2],[11.41,47.21]]]}}]}"""
                    )
                )
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("outdoor_pois.geojson.gz"))
                zos.write(
                    gzipBytes(
                        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"cat":"peak","label":"Patscherkofel"},"geometry":{"type":"Point","coordinates":[11.46,47.208]}}]}"""
                    )
                )
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("contours_z12.tiles.gz"))
                zos.write(gzipBytes("12/2178/1438\t{\"type\":\"Feature\",\"properties\":{\"ele\":2200,\"major\":1}}\n"))
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val serverThread = thread(isDaemon = true) {
            repeat(2) {
                try {
                    val client = server.accept()
                    client.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val line = reader.readLine()
                            if (line == null || line.isEmpty()) break
                        }
                        val out = sock.getOutputStream()
                        if (requestLine.contains("/tirol.zip")) {
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${zipBytes.size}\r\nConnection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.UTF_8))
                            out.write(zipBytes)
                        } else {
                            val header = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.UTF_8))
                        }
                        out.flush()
                    }
                } catch (_: Exception) {
                }
            }
        }

        try {
            val pkgDir = File(context.filesDir, "github_regions/at_tirol").apply { mkdirs() }
            val result = OfflineRegionHelper.streamZipFromUrlWithImporter(
                pkgDir = pkgDir,
                downloadUrl = "http://127.0.0.1:$port/tirol.zip"
            ) { contourStream ->
                ContourTileManager.importTilePackage(context, contourStream) > 0
            }

            assertNotNull("200 OK ZIP stream must extract cleanly", result)
            assertTrue(result!!.trailsOk)
            assertTrue(result.poisOk)
            assertTrue(result.contoursOk)
            assertTrue(File(pkgDir, "region_meta.json").exists())
            assertTrue(File(pkgDir, "trails.features").readText().contains("11.4"))
            assertTrue(File(pkgDir, "pois.features").readText().contains("Patscherkofel"))

            // 404 response must return null safely
            val missingResult = OfflineRegionHelper.streamZipFromUrlWithImporter(
                pkgDir = pkgDir,
                downloadUrl = "http://127.0.0.1:$port/missing.zip"
            ) { true }
            assertNull("404 HTTP response must return null", missingResult)
        } finally {
            server.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun deleteTrailPackage_removesDirectoryAndClearsActiveTrails() {
        val pkgDir = File(context.filesDir, "github_regions/cz_palava").apply { mkdirs() }
        File(pkgDir, "region_meta.json").writeText(
            """{"title":"Palava","bounds":[48.95,16.80,48.75,16.50]}"""
        )
        File(pkgDir, "trails.features").writeText(
            """{"type":"Feature","properties":{"colour":"red"},"geometry":{"type":"MultiLineString","coordinates":[[[16.64,48.87],[16.65,48.88]]]}}"""
        )
        OfflineRegionHelper.invalidateTrailPackages()
        OfflineRegionHelper.refreshActiveViewportOnExecutor(context, 48.87, 16.64)
        assertTrue(OfflineRegionHelper.customTrailsGeoJson.value.orEmpty().contains("16.64"))

        // Delete package asynchronously on trailExecutor and wait for directory removal
        OfflineRegionHelper.deleteTrailPackage(context, "Palava")
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline && pkgDir.exists()) {
            Thread.sleep(25L)
        }
        assertFalse("Deleted region package directory must be removed from disk", pkgDir.exists())
    }

    @Test
    fun poiTileManager_handlesEmptyPackageAndBundledJmkAssetFallback() {
        val emptyPkg = File(context.filesDir, "github_regions/empty_pkg").apply { mkdirs() }
        PoiTileManager.ensurePackageIndexed(emptyPkg)
        assertEquals("empty", File(emptyPkg, "poi_tiles/.indexed_v1").readText())

        // Missing asset fallback must create empty marker without crashing
        val jmkTilesDir = PoiTileManager.ensureJmkAssetIndexed(context, "nonexistent_pois.geojson")
        assertTrue(File(jmkTilesDir, ".indexed_v1").exists())
        assertEquals("empty", File(jmkTilesDir, ".indexed_v1").readText())

        // Real bundled JMK asset (after removing marker) indexes POIs cleanly onto disk
        File(jmkTilesDir, ".indexed_v1").delete()
        val indexedDir = PoiTileManager.ensureJmkAssetIndexed(context, "jmk_pois.geojson")
        assertTrue(File(indexedDir, ".indexed_v1").exists())
    }

    @Test
    fun contourTileManager_zoomGateAndLoadCachedCustomContours() {
        // Compute tile coordinates for Traunstein (47.87, 13.83) at z=12 and write a cached tile
        val w = ContourTileManager.computeContourTileWindow(47.87, 13.83, 13.5)
        val tilesDir = File(context.filesDir, "contour_tiles").apply { mkdirs() }
        File(tilesDir, "tile_12_${w.minTx}_${w.minTy}.features").writeText(
            """{"type":"Feature","properties":{"ele":1650,"major":1},"geometry":{"type":"MultiLineString","coordinates":[[[13.83,47.87],[13.84,47.87]]]}}"""
        )

        // Zoom below 11.8 must short-circuit without loading anything
        ContourTileManager.updateActiveViewportTiles(context, 0, 0, 0, 0, allowNetworkFetch = false)
        val before = ContourTileManager.customContoursGeoJson.value
        ContourTileManager.ensureViewportContours(context, 47.87, 13.83, zoom = 10.5, allowNetworkFetch = false)
        assertEquals("Zoom < 11.8 must not trigger contour load", before, ContourTileManager.customContoursGeoJson.value)

        // loadCachedCustomContours runs at zoom 13.0 on Dispatchers.IO and populates customContoursGeoJson
        ContourTileManager.loadCachedCustomContours(context, 47.87, 13.83)
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            if (ContourTileManager.customContoursGeoJson.value.orEmpty().contains("\"ele\":1650")) break
            Thread.sleep(25L)
        }
        assertTrue(
            "loadCachedCustomContours must asynchronously load cached contour tile",
            ContourTileManager.customContoursGeoJson.value.orEmpty().contains("\"ele\":1650")
        )
    }
}

