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
}
