package cz.offgrid.maps.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfflineRegionHelperTest {

    @Test
    fun parseCatalogJson_readsContinentReleaseTagAndAssetName() {
        val sampleJson = """
            {
              "version": 5,
              "countries": [
                {
                  "countryCode": "JP",
                  "countryName": "Japan – Regions",
                  "continent": "Asia",
                  "regions": [
                    {
                      "id": "jp_hokkaido",
                      "title": "Japan – Hokkaido",
                      "subtitle": "Japanese Alps & regional trails",
                      "centerLat": 43.4,
                      "centerLon": 142.8,
                      "latSpanDeg": 4.2,
                      "lonSpanDeg": 6.4,
                      "packagePath": "maps/jp/jp_hokkaido",
                      "releaseTag": "world-as",
                      "assetName": "jp_hokkaido.zip"
                    }
                  ]
                },
                {
                  "countryCode": "BR",
                  "countryName": "Brazil – Regions",
                  "regions": [
                    {
                      "id": "br_sul",
                      "title": "Brazil – Sul",
                      "centerLat": -27.5,
                      "centerLon": -51.0
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val regions = OfflineRegionHelper.parseCatalogJson(sampleJson)
        assertNotNull(regions)
        assertEquals(2, regions!!.size)

        val jp = regions[0]
        assertEquals("Asia", jp.continent)
        assertEquals("world-as", jp.releaseTag)
        assertEquals("jp_hokkaido.zip", jp.assetName)
        assertEquals("maps/jp/jp_hokkaido", jp.packagePath)

        val br = regions[1]
        assertEquals("South America", br.continent)
        assertNull(br.releaseTag)
        assertNull(br.assetName)
    }

    @Test
    fun extractPackageZip_extractsTrailsPoisMetaAndStreamsContours() {
        val tempDir = Files.createTempDirectory("offgrid_zip_test").toFile()
        try {
            val zipBytes = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos).use { zos ->
                    fun addEntry(name: String, raw: ByteArray) {
                        zos.putNextEntry(ZipEntry(name))
                        zos.write(raw)
                        zos.closeEntry()
                    }
                    fun gzipBytes(text: String): ByteArray = ByteArrayOutputStream().use { out ->
                        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
                        out.toByteArray()
                    }
                    addEntry("region_meta.json", """{"id":"jp_hokkaido"}""".toByteArray())
                    addEntry(
                        "kct_trails.geojson.gz",
                        gzipBytes("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"colour":"red"}}]}""")
                    )
                    addEntry(
                        "outdoor_pois.geojson.gz",
                        gzipBytes("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"cat":"water","label":"Spring"}}]}""")
                    )
                    addEntry("contours_z12.tiles.gz", "dummy-contour-bytes".toByteArray())
                }
                baos.toByteArray()
            }

            var capturedContourSize = 0
            val result = OfflineRegionHelper.extractPackageZip(
                ByteArrayInputStream(zipBytes),
                tempDir
            ) { contourStream ->
                capturedContourSize = contourStream.readBytes().size
                true
            }

            assertTrue(result.trailsOk)
            assertTrue(result.poisOk)
            assertTrue(result.contoursOk)
            assertEquals("dummy-contour-bytes".length, capturedContourSize)
            assertEquals("""{"id":"jp_hokkaido"}""", File(tempDir, "region_meta.json").readText())
            assertTrue(File(tempDir, "trails.features").readText().contains("red"))
            assertTrue(File(tempDir, "pois.features").readText().contains("Spring"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun computeAdaptiveZoomRange_scalesZoomDownForCountrySizedBounds() {
        assertEquals(8.0 to 14.0, OfflineRegionHelper.computeAdaptiveZoomRange(0.4, 0.6))
        assertEquals(8.0 to 13.0, OfflineRegionHelper.computeAdaptiveZoomRange(1.5, 1.8))
        assertEquals(7.0 to 12.0, OfflineRegionHelper.computeAdaptiveZoomRange(3.2, 4.0))
        assertEquals(6.0 to 11.0, OfflineRegionHelper.computeAdaptiveZoomRange(8.0, 12.0))
    }

    @Test
    fun defaultCatalogAsset_parsesAllContinentsAndReleaseTags() {
        val assetFile = File("src/main/assets/default_catalog.json").let {
            if (it.exists()) it else File("app/src/main/assets/default_catalog.json")
        }
        assertTrue("default_catalog.json should exist", assetFile.exists())
        val parsed = OfflineRegionHelper.parseCatalogJson(assetFile.readText())
        assertNotNull(parsed)
        assertTrue("Expected > 400 global regions, got ${parsed!!.size}", parsed.size > 400)
        val continents = parsed.map { it.continent }.toSet()
        assertEquals(
            setOf("Europe", "North America", "South America", "Asia", "Africa", "Australia & Oceania"),
            continents
        )
        assertTrue(parsed.all { !it.releaseTag.isNullOrBlank() && !it.assetName.isNullOrBlank() })
    }

    @Test
    fun resolveCdnZipUrl_prefersCatalogReleaseBaseUrlOverDefault() {
        val sampleJson = """
            {
              "version": 5,
              "releaseBaseUrl": "https://maps.appmanufacture.dev/",
              "countries": [
                {
                  "countryCode": "CZ",
                  "countryName": "Czechia",
                  "regions": [
                    {
                      "id": "jihomoravsky_kraj",
                      "title": "South Moravia",
                      "centerLat": 49.08,
                      "centerLon": 16.58,
                      "releaseTag": "world-eu",
                      "assetName": "jihomoravsky_kraj.zip"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val region = OfflineRegionHelper.parseCatalogJson(sampleJson)!!.single()
        assertEquals(
            "https://maps.appmanufacture.dev/releases/world-eu/jihomoravsky_kraj.zip",
            OfflineRegionHelper.resolveCdnZipUrl(region, "https://pub-33e873553ed746db815f152b72306952.r2.dev")
        )

        val fallbackRegion = region.copy(releaseBaseUrl = null)
        assertEquals(
            "https://pub-33e873553ed746db815f152b72306952.r2.dev/releases/world-eu/jihomoravsky_kraj.zip",
            OfflineRegionHelper.resolveCdnZipUrl(fallbackRegion, "https://pub-33e873553ed746db815f152b72306952.r2.dev/")
        )
    }

    @Test
    fun enqueueUniquePreset_deduplicatesActiveAndQueuedRegions() {
        val queue = mutableListOf<OfflinePresetRegion>()
        val r1 = OfflinePresetRegion("at_wien", "Vienna", "Austria", 48.2, 16.3)
        val r2 = OfflinePresetRegion("at_niederoesterreich", "Lower Austria", "Austria", 48.1, 15.6)
        val r3 = OfflinePresetRegion("at_steiermark", "Styria", "Austria", 47.1, 15.4)

        assertTrue(!OfflineRegionHelper.enqueueUniquePreset(queue, "at_wien", r1))
        assertTrue(OfflineRegionHelper.enqueueUniquePreset(queue, "at_wien", r2))
        assertTrue(!OfflineRegionHelper.enqueueUniquePreset(queue, "at_wien", r2))
        assertTrue(OfflineRegionHelper.enqueueUniquePreset(queue, "at_wien", r3))
        assertEquals(listOf("at_niederoesterreich", "at_steiermark"), queue.map { it.id })
    }

    @Test
    fun contourTileManager_importTilePackageToDir_parsesValidLinesAndSkipsMalformed() {
        val tempDir = Files.createTempDirectory("offgrid_contours_test").toFile()
        try {
            val payload = listOf(
                "12/2236/1393\t{\"type\":\"Feature\",\"properties\":{\"ele\":450}}",
                "bad_line_without_tab",
                "12/99\tinvalid_tile_key",
                "12/2237/1393\t{\"type\":\"Feature\",\"properties\":{\"ele\":500}}"
            ).joinToString("\n")
            val gzBytes = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { it.write(payload.toByteArray()) }
                baos.toByteArray()
            }

            val count = ContourTileManager.importTilePackageToDir(tempDir, ByteArrayInputStream(gzBytes))
            assertEquals(2, count)
            assertEquals(
                """{"type":"Feature","properties":{"ele":450}}""",
                File(tempDir, "tile_12_2236_1393.features").readText()
            )
            assertEquals(
                """{"type":"Feature","properties":{"ele":500}}""",
                File(tempDir, "tile_12_2237_1393.features").readText()
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun streamZipFromUrlWithImporter_downloadsValidZipAndReturnsNullOn404() {
        val tempDir = Files.createTempDirectory("offgrid_http_zip_test").toFile()
        val server = java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val zipBytes = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos).use { zos ->
                    fun gzipStr(s: String): ByteArray = ByteArrayOutputStream().use { out ->
                        GZIPOutputStream(out).use { it.write(s.toByteArray()) }
                        out.toByteArray()
                    }
                    zos.putNextEntry(ZipEntry("region_meta.json"))
                    zos.write("""{"id":"test_reg"}""".toByteArray())
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("kct_trails.geojson.gz"))
                    zos.write(gzipStr("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"colour":"blue"}}]}"""))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("contours_z12.tiles.gz"))
                    zos.write("contour-data".toByteArray())
                    zos.closeEntry()
                }
                baos.toByteArray()
            }
            val serverThread = Thread {
                while (!server.isClosed) {
                    val sock = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    sock.use { client ->
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(client.getInputStream()))
                        val requestLine = reader.readLine() ?: ""
                        while (true) {
                            val hdr = reader.readLine()
                            if (hdr.isNullOrEmpty()) break
                        }
                        val out = client.getOutputStream()
                        if (requestLine.contains("test_reg.zip")) {
                            out.write(
                                "HTTP/1.1 200 OK\r\nContent-Length: ${zipBytes.size}\r\nConnection: close\r\n\r\n".toByteArray()
                            )
                            out.write(zipBytes)
                        } else {
                            out.write(
                                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                            )
                        }
                        out.flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val port = server.localPort

            val missingRes = OfflineRegionHelper.streamZipFromUrlWithImporter(
                tempDir,
                "http://127.0.0.1:$port/releases/world-eu/missing.zip"
            ) { true }
            assertNull(missingRes)

            val okRes = OfflineRegionHelper.streamZipFromUrlWithImporter(
                tempDir,
                "http://127.0.0.1:$port/releases/world-eu/test_reg.zip"
            ) { it.readBytes().isNotEmpty() }
            assertNotNull(okRes)
            assertTrue(okRes!!.trailsOk)
            assertTrue(okRes.contoursOk)
            assertTrue(File(tempDir, "trails.features").readText().contains("blue"))
            server.close()
            serverThread.join(1000)
        } finally {
            if (!server.isClosed) server.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun streamFeatureArrayFromGzip_handlesLargeUtf8MultiChunkPayload() {
        val tempDir = Files.createTempDirectory("offgrid_stream_test").toFile()
        try {
            val featureItem = """{"type":"Feature","properties":{"colour":"red","note":"Sněžka – Ötztal"},"geometry":{"type":"LineString","coordinates":[[15.739,50.736],[15.740,50.737]]}}"""
            val largeJson = buildString {
                append("""{"type":"FeatureCollection","features":[  """)
                repeat(450) { idx ->
                    if (idx > 0) append(',')
                    append(featureItem)
                }
                append("""  ]}  """)
            }
            val gzBytes = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { it.write(largeJson.toByteArray(Charsets.UTF_8)) }
                baos.toByteArray()
            }

            val outFile = File(tempDir, "trails.features")
            val ok = OfflineRegionHelper.streamFeatureArrayFromGzip(ByteArrayInputStream(gzBytes), outFile)
            assertTrue(ok)
            val extracted = outFile.readText(Charsets.UTF_8)
            assertTrue(extracted.startsWith("""{"type":"Feature""""))
            assertTrue(extracted.endsWith("]]}}"))
            assertTrue(extracted.contains("Sněžka – Ötztal"))

            val parsed = org.json.JSONObject("""{"type":"FeatureCollection","features":[$extracted]}""")
            assertEquals(450, parsed.getJSONArray("features").length())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun writeCombinedFeatureCollectionFile_mergesNonEmptyFragmentsIntoValidJson() {
        val tempDir = Files.createTempDirectory("offgrid_merge_test").toFile()
        try {
            val f1 = File(tempDir, "r1.features").apply {
                writeText("""{"type":"Feature","properties":{"colour":"red"},"geometry":null}""")
            }
            val empty = File(tempDir, "empty.features").apply { writeText("") }
            val f2 = File(tempDir, "r2.features").apply {
                writeText("""{"type":"Feature","properties":{"colour":"blue"},"geometry":null}""")
            }
            val outFile = File(tempDir, "active_trails.geojson")

            OfflineRegionHelper.writeCombinedFeatureCollectionFile(listOf(f1, empty, f2), outFile)
            val json = org.json.JSONObject(outFile.readText(Charsets.UTF_8))
            val arr = json.getJSONArray("features")
            assertEquals(2, arr.length())
            assertEquals("red", arr.getJSONObject(0).getJSONObject("properties").getString("colour"))
            assertEquals("blue", arr.getJSONObject(1).getJSONObject("properties").getString("colour"))

            val emptyOut = File(tempDir, "empty_trails.geojson")
            OfflineRegionHelper.writeCombinedFeatureCollectionFile(emptyList(), emptyOut)
            val emptyJson = org.json.JSONObject(emptyOut.readText(Charsets.UTF_8))
            assertEquals(0, emptyJson.getJSONArray("features").length())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
