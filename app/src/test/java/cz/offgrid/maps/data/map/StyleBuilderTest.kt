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
        // Vytvoříme dočasný mock soubor
        val tempFile = File.createTempFile("test_map", ".mbtiles")
        try {
            val styleJson = StyleBuilder.createAmoledOutdoorStyle(tempFile)

            // Ověříme, že styl odkazuje na lokální MBTiles soubor
            assertTrue(styleJson.contains("mbtiles://${tempFile.absolutePath}"))

            // Ověříme přítomnost vrstev pro české KČT značení a vrstevnice (10 m a 50 m)
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
        assertEquals("Jihomoravský kraj", OfflineRegionHelper.resolveCzechRegionTitle(49.1246, 16.3353))
        assertEquals("Praha a okolí", OfflineRegionHelper.resolveCzechRegionTitle(50.0755, 14.4378))
        assertEquals("Krkonoše", OfflineRegionHelper.resolveCzechRegionTitle(50.7360, 15.7397))
        assertEquals("Šumava", OfflineRegionHelper.resolveCzechRegionTitle(49.0200, 13.5500))
        assertEquals("Beskydy", OfflineRegionHelper.resolveCzechRegionTitle(49.5460, 18.4470))
    }

    @Test
    fun contourTileManager_webMercatorTileCoordinates_matchBundledJmkGrid() {
        // Oslavany / Údolí Oslavy (49.1246, 16.3353) při zoomu 12 leží v dlaždici x=2233, y=1404
        val tx = ContourTileManager.lonToTileX(16.3353, 12)
        val ty = ContourTileManager.latToTileY(49.1246, 12)
        assertEquals(2233, tx)
        assertEquals(1404, ty)
        assertTrue(ContourTileManager.BUNDLED_JMK_TILES.contains("${tx}_${ty}"))
    }

    @Test
    fun presetRegions_containsMajorCzechMountainRangesWithValidCoordinates() {
        val presets = OfflineRegionHelper.presetRegions
        assertTrue(presets.size >= 12)
        val titles = presets.map { it.title }.toSet()
        assertTrue(titles.contains("Krkonoše"))
        assertTrue(titles.contains("Šumava"))
        assertTrue(titles.contains("Beskydy"))
        assertTrue(titles.contains("Jeseníky"))
        presets.forEach { preset ->
            assertTrue(preset.centerLat in 48.5..51.1)
            assertTrue(preset.centerLon in 12.0..18.9)
            assertTrue(preset.subtitle.isNotBlank())
        }
    }
}
