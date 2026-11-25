package cz.offgrid.maps.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class PoiTileManagerTest {

    @Test
    fun ensurePackageIndexed_partitionsPoisAndPreservesUtf8AndBraces() {
        val pkgDir = Files.createTempDirectory("poi_tile_test").toFile()
        try {
            val nearPoi = """{"type":"Feature","properties":{"cat":"water","label":"Doležalova \"studánka\" {Ötscher}"},"geometry":{"type":"Point","coordinates":[13.79200,47.87200]}}"""
            val farPoi = """{"type":"Feature","properties":{"cat":"shelter","label":"Distant Triglav Hut"},"geometry":{"type":"Point","coordinates":[13.83600,46.37900]}}"""
            File(pkgDir, "pois.features").writeText("$nearPoi,$farPoi", StandardCharsets.UTF_8)

            PoiTileManager.ensurePackageIndexed(pkgDir)

            val geoJson = PoiTileManager.loadViewportPoisGeoJson(
                packageDirs = listOf(pkgDir),
                lat = 47.87,
                lon = 13.79
            )!!
            assertTrue("Expected nearby UTF-8 POI to remain", geoJson.contains("Doležalova \\\"studánka\\\" {Ötscher}"))
            assertFalse("Distant Triglav POI (~165 km south) must be excluded", geoJson.contains("Distant Triglav Hut"))
        } finally {
            pkgDir.deleteRecursively()
        }
    }

    @Test
    fun indexFeatureCollectionStream_deduplicatesAndSupportsCancellation() {
        val tempRoot = Files.createTempDirectory("poi_fc_test").toFile()
        try {
            val sharedFeature = """{"type":"Feature","properties":{"cat":"shelter","label":"Traunstein Haus"},"geometry":{"type":"Point","coordinates":[13.83500,47.87100]}}"""
            val fc = """{"type":"FeatureCollection","features":[$sharedFeature]}"""

            val pkgA = File(tempRoot, "pkg_a").apply { mkdirs() }
            val pkgB = File(tempRoot, "pkg_b").apply { mkdirs() }
            val tilesA = File(pkgA, "poi_tiles")
            val tilesB = File(pkgB, "poi_tiles")

            PoiTileManager.indexPoiStreamToTiles(
                ByteArrayInputStream(fc.toByteArray(StandardCharsets.UTF_8)),
                tilesA,
                isFeatureCollection = true
            )
            File(tilesA, ".indexed_v1").writeText("ok")
            PoiTileManager.indexPoiStreamToTiles(
                ByteArrayInputStream(fc.toByteArray(StandardCharsets.UTF_8)),
                tilesB,
                isFeatureCollection = true
            )
            File(tilesB, ".indexed_v1").writeText("ok")

            val combined = PoiTileManager.loadViewportPoisGeoJson(
                packageDirs = listOf(pkgA, pkgB),
                lat = 47.87,
                lon = 13.83
            )!!
            val parsed = OfflineRegionHelper.parseOutdoorPois(combined)
            assertEquals("Identical boundary POI across overlapping regions must be deduplicated", 1, parsed.size)
            assertEquals("Traunstein Haus", parsed.first().label)

            val cancelled = PoiTileManager.loadViewportPoisGeoJson(
                packageDirs = listOf(pkgA, pkgB),
                lat = 47.87,
                lon = 13.83,
                shouldCancel = { true }
            )
            assertNull("Superseded viewport POI request must abort early", cancelled)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
