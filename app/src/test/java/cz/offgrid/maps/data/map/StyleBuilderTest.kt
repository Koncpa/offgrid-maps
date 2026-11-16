package cz.offgrid.maps.data.map

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StyleBuilderTest {

    @Test
    fun createAmoledOutdoorStyle_withoutFile_containsAmoledBackground() {
        val styleJson = StyleBuilder.createAmoledOutdoorStyle(null)

        assertTrue(styleJson.contains("\"background-color\": \"#000000\""))
        assertTrue(styleJson.contains("\"version\": 8"))
        assertTrue(styleJson.contains("OffGrid AMOLED Outdoor"))
    }

    @Test
    fun createAmoledOutdoorStyle_withMbtilesFile_containsMbtilesSourceAndKctLayers() {
        // Create a temporary mock MBTiles file
        val tempFile = File.createTempFile("test_map", ".mbtiles")
        try {
            val styleJson = StyleBuilder.createAmoledOutdoorStyle(tempFile)

            // Verify that the style references the local MBTiles file
            assertTrue(styleJson.contains("mbtiles://${tempFile.absolutePath}"))

            // Verify presence of marked hiking trail layers (KČT) and contour layers (10 m and 50 m)
            assertTrue(styleJson.contains("trail_kct_red"))
            assertTrue(styleJson.contains("trail_kct_blue"))
            assertTrue(styleJson.contains("trail_kct_green"))
            assertTrue(styleJson.contains("trail_kct_yellow"))
            assertTrue(styleJson.contains("contour_minor"))
            assertTrue(styleJson.contains("contour_major"))
            assertTrue(styleJson.contains("contour_labels"))
            assertTrue(styleJson.contains("roads_tracks"))
            assertTrue(styleJson.contains("roads_paths"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun resolveCzechRegionTitle_identifiesCzechRegionsAndMountainsCorrectly() {
        assertEquals("South Moravia", OfflineRegionHelper.resolveCzechRegionTitle(49.1246, 16.3353))
        assertEquals("Prague & Surroundings", OfflineRegionHelper.resolveCzechRegionTitle(50.0755, 14.4378))
        assertEquals("Krkonoše", OfflineRegionHelper.resolveCzechRegionTitle(50.7360, 15.7397))
        assertEquals("Šumava", OfflineRegionHelper.resolveCzechRegionTitle(49.0200, 13.5500))
        assertEquals("Beskydy", OfflineRegionHelper.resolveCzechRegionTitle(49.5460, 18.4470))
    }

    @Test
    fun contourTileManager_webMercatorTileCoordinates_matchBundledJmkGrid() {
        // Oslavany / Oslava Valley (49.1246, 16.3353) at zoom 12 lies in tile x=2233, y=1404
        val tx = ContourTileManager.lonToTileX(16.3353, 12)
        val ty = ContourTileManager.latToTileY(49.1246, 12)
        assertEquals(2233, tx)
        assertEquals(1404, ty)
        assertTrue(ContourTileManager.BUNDLED_JMK_TILES.contains("${tx}_${ty}"))
    }

    @Test
    fun presetRegions_containsCzechAdministrativeRegionsLikeMapyCom() {
        val presets = OfflineRegionHelper.presetRegions
        assertEquals(13, presets.size)
        val titles = presets.map { it.title }.toSet()
        assertTrue(titles.contains("Vysočina Region"))
        assertTrue(titles.contains("South Bohemia"))
        assertTrue(titles.contains("Moravian-Silesian"))
        assertTrue(titles.contains("Hradec Králové Region"))
        presets.forEach { preset ->
            assertTrue(preset.centerLat in 48.5..51.1)
            assertTrue(preset.centerLon in 12.0..18.9)
            assertTrue(preset.subtitle.isNotBlank())
        }
    }

    @Test
    fun createAmoledOutdoorStyle_containsCustomTrailOtherLayerAndPurpleColor() {
        val styleJson = StyleBuilder.createAmoledOutdoorStyle(null)
        assertTrue(styleJson.contains("custom_trail_other"))
        assertTrue(styleJson.contains("custom_kct_trails_source"))
        assertTrue(styleJson.contains("#B266FF"))
    }

    @Test
    fun extractFeatureArray_returnsInnerFeaturesWithoutFullJsonParse() {
        val sample = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"colour":"red"}}]}"""
        val extracted = OfflineRegionHelper.extractFeatureArray(sample)
        assertEquals("""{"type":"Feature","properties":{"colour":"red"}}""", extracted)

        val empty = """{"type":"FeatureCollection","features":[]}"""
        assertEquals("", OfflineRegionHelper.extractFeatureArray(empty))
    }

    @Test
    fun resolveContinent_groupsEuropeNorthAmericaAndOceaniaCleanly() {
        assertEquals("Europe", OfflineRegionHelper.resolveContinent("CZ"))
        assertEquals("Europe", OfflineRegionHelper.resolveContinent("CH"))
        assertEquals("Europe", OfflineRegionHelper.resolveContinent("NO"))
        assertEquals("North America", OfflineRegionHelper.resolveContinent("US"))
        assertEquals("North America", OfflineRegionHelper.resolveContinent("CA"))
        assertEquals("Australia & NZ", OfflineRegionHelper.resolveContinent("AU"))
        assertEquals("Australia & NZ", OfflineRegionHelper.resolveContinent("NZ"))
    }

    @Test
    fun createAmoledOutdoorStyle_containsOutdoorPoiFerrataAndAerialwayLayers() {
        val styleJson = StyleBuilder.createAmoledOutdoorStyle(null)
        assertTrue(styleJson.contains("custom_outdoor_pois_source"))
        assertTrue(styleJson.contains("outdoor_poi_dots"))
        assertTrue(styleJson.contains("outdoor_poi_labels"))
        assertTrue(styleJson.contains("custom_trail_ferrata"))
        assertTrue(styleJson.contains("custom_aerialway"))
    }

    @Test
    fun parseOutdoorPois_parsesWaterShelterAndLandmarkFeaturesCorrectly() {
        val sample = """{"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"cat":"water","label":"Studánka U Buku"},"geometry":{"type":"Point","coordinates":[16.6845,49.3112]}},
            {"type":"Feature","properties":{"cat":"shelter","label":"Útulna Oslava"},"geometry":{"type":"Point","coordinates":[16.2155,49.1482]}}
        ]}"""
        val pois = OfflineRegionHelper.parseOutdoorPois(sample)
        assertEquals(2, pois.size)
        assertEquals("water", pois[0].category)
        assertEquals("Studánka U Buku", pois[0].label)
        assertEquals(49.3112, pois[0].lat, 0.0001)
        assertEquals(16.6845, pois[0].lon, 0.0001)
    }
}
