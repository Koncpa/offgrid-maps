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

            // Ověříme přítomnost vrstev pro české KČT značení
            assertTrue(styleJson.contains("trail_kct_red"))
            assertTrue(styleJson.contains("trail_kct_blue"))
            assertTrue(styleJson.contains("trail_kct_green"))
            assertTrue(styleJson.contains("trail_kct_yellow"))
        } finally {
            tempFile.delete()
        }
    }
}
