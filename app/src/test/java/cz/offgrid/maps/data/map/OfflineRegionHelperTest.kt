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
}
