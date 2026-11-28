package cz.offgrid.maps.data.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrailTileManagerTest {

    @Test
    fun ensurePackageIndexed_splitsLargeRegionIntoCellsAndLoadsLocalViewportOnly() {
        val pkgDir = Files.createTempDirectory("offgrid_trail_tiles").toFile()
        try {
            // Two trails in Schladming (47.39, 13.68), one trail crossing boundary nearby (47.46, 13.85),
            // and one distant trail in Graz (47.07, 15.44) > 1.5 degrees away.
            val trailsFragment = """
                {"type":"Feature","properties":{"colour":"red"},"geometry":{"type":"MultiLineString","coordinates":[[[13.68,47.39],[13.69,47.40]],[[15.44,47.07],[15.45,47.08]]]}},
                {"type":"Feature","properties":{"colour":"blue"},"geometry":{"type":"MultiLineString","coordinates":[[[13.67,47.38],[13.85,47.46]]]}}
            """.trimIndent()
            File(pkgDir, "trails.features").writeText(trailsFragment)

            TrailTileManager.ensurePackageIndexed(pkgDir)

            // Query Schladming viewport: must return red Schladming + blue boundary-crossing line, NOT Graz
            val schladmingJson = TrailTileManager.loadViewportTrailsGeoJson(listOf(pkgDir), 47.39, 13.68)
            val parsedSchladming = JSONObject(schladmingJson)
            val features = parsedSchladming.getJSONArray("features")
            assertEquals(2, features.length())

            val byColour = (0 until features.length()).associate { idx ->
                val obj = features.getJSONObject(idx)
                obj.getJSONObject("properties").getString("colour") to
                    obj.getJSONObject("geometry").getJSONArray("coordinates")
            }
            assertEquals(1, byColour.getValue("red").length())
            assertEquals(1, byColour.getValue("blue").length())
            assertFalse(schladmingJson.contains("15.44"))

            // Query Graz viewport: must return Graz red line, NOT Schladming
            val grazJson = TrailTileManager.loadViewportTrailsGeoJson(listOf(pkgDir), 47.07, 15.44)
            assertTrue(grazJson.contains("15.44"))
            assertFalse(grazJson.contains("13.68"))
        } finally {
            pkgDir.deleteRecursively()
        }
    }

    @Test
    fun loadViewportTrailsGeoJson_handlesEmptyPackagesAndDeduplicatesCrossingLines() {
        val pkgDir = Files.createTempDirectory("offgrid_trail_empty").toFile()
        try {
            File(pkgDir, "trails.features").writeText("")
            val emptyResult = TrailTileManager.loadViewportTrailsGeoJson(listOf(pkgDir), 47.39, 13.68)
            assertEquals(0, JSONObject(emptyResult).getJSONArray("features").length())

            // Re-test with a long line spanning multiple adjacent 0.15x0.20 cells inside the 3x3 window
            File(pkgDir, "trail_tiles").deleteRecursively()
            File(pkgDir, "trails.features").writeText(
                """{"type":"Feature","properties":{"colour":"ferrata"},"geometry":{"type":"MultiLineString","coordinates":[[[13.61,47.32],[13.78,47.42]]]}}"""
            )
            val json = TrailTileManager.loadViewportTrailsGeoJson(listOf(pkgDir), 47.39, 13.68)
            val features = JSONObject(json).getJSONArray("features")
            assertEquals(1, features.length())
            assertEquals(1, features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").length())
        } finally {
            pkgDir.deleteRecursively()
        }
    }

    @Test
    fun ensurePackageIndexed_deletesRawFileAfterMarkerAndSupportsReindex() {
        val pkgDir = Files.createTempDirectory("offgrid_trail_cleanup").toFile()
        try {
            val rawFile = File(pkgDir, "trails.features")
            rawFile.writeText(
                """{"type":"Feature","properties":{"colour":"red"},"geometry":{"type":"MultiLineString","coordinates":[[[13.68,47.39],[13.69,47.40]]]}}"""
            )
            assertTrue(rawFile.exists())

            TrailTileManager.ensurePackageIndexed(pkgDir)

            assertTrue("Index marker must exist", File(pkgDir, "trail_tiles/.indexed_v1").exists())
            assertFalse("Raw trails.features should be deleted after indexing", rawFile.exists())
            assertTrue(
                "Viewport trails must still load from spatial tiles",
                TrailTileManager.loadViewportTrailsGeoJson(listOf(pkgDir), 47.39, 13.68).contains("13.68")
            )

            // Simulate region re-download with updated trail coordinates
            rawFile.writeText(
                """{"type":"Feature","properties":{"colour":"green"},"geometry":{"type":"MultiLineString","coordinates":[[[13.70,47.41],[13.71,47.42]]]}}"""
            )
            TrailTileManager.invalidateIndex(pkgDir)
            TrailTileManager.ensurePackageIndexed(pkgDir)

            assertFalse("Re-downloaded raw trails.features should also be cleaned", rawFile.exists())
            val reloaded = TrailTileManager.loadViewportTrailsGeoJson(listOf(pkgDir), 47.41, 13.70)
            assertTrue(reloaded.contains("\"green\""))
            assertTrue(reloaded.contains("13.70"))
        } finally {
            pkgDir.deleteRecursively()
        }
    }

    @Test
    fun loadViewportTrailsGeoJsonOrNull_cancelsEarlyAndDeduplicatesAcrossOverlappingPackages() {
        val tempRoot = Files.createTempDirectory("offgrid_trail_multi").toFile()
        try {
            val sharedTrail = """{"type":"Feature","properties":{"colour":"red"},"geometry":{"type":"MultiLineString","coordinates":[[[13.68,47.39],[13.69,47.40]]]}}"""
            val pkgA = File(tempRoot, "at_salzburg").apply { mkdirs() }
            val pkgB = File(tempRoot, "at_steiermark").apply { mkdirs() }
            File(pkgA, "trails.features").writeText(sharedTrail)
            File(pkgB, "trails.features").writeText(sharedTrail)

            val combined = TrailTileManager.loadViewportTrailsGeoJson(listOf(pkgA, pkgB), 47.39, 13.68)
            val features = JSONObject(combined).getJSONArray("features")
            assertEquals(1, features.length())
            assertEquals(
                "Duplicate trail segment across overlapping border regions must be deduplicated",
                1,
                features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").length()
            )

            val cancelled = TrailTileManager.loadViewportTrailsGeoJsonOrNull(listOf(pkgA, pkgB), 47.39, 13.68) { true }
            org.junit.Assert.assertNull("Superseded viewport trail load must abort early", cancelled)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun indexTrailsFragmentToTiles_capsDegenerateDatelineWrapSpanWithoutCreatingHundredsOfCells() {
        val pkgDir = Files.createTempDirectory("offgrid_trail_dateline").toFile()
        try {
            // Degenerate line spanning from -179.9 to +179.9 (> 16 cells) must fall back to midpoint cell cleanly
            File(pkgDir, "trails.features").writeText(
                """{"type":"Feature","properties":{"colour":"other"},"geometry":{"type":"MultiLineString","coordinates":[[[-179.9,10.0],[179.9,10.0]]]}}"""
            )
            TrailTileManager.ensurePackageIndexed(pkgDir)
            val tileFiles = File(pkgDir, "trail_tiles").listFiles { f -> f.name.endsWith(".tsv") } ?: emptyArray()
            assertEquals("Degenerate wide span must emit into a single fallback tile file", 1, tileFiles.size)
        } finally {
            pkgDir.deleteRecursively()
        }
    }
}
