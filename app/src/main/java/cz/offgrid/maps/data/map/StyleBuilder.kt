package cz.offgrid.maps.data.map

import java.io.File

/**
 * Generátor MapLibre GL Style JSON přizpůsobený pro AMOLED displeje hodinek a outdoor použití.
 *
 * Principy stylu:
 * 1. Background: #000000 (OLED subpixely vypnuté, 0 mW spotřeba).
 * 2. Voda a jezera: Tmavě modrá / indigová (#0A192F).
 * 3. Lesy / příroda: Tmavě šedá až černá (#0A0F0D) pro zachování úsporného profilu.
 * 4. Pěší cesty a stezky: Vysoce kontrastní světlé linie (#D1D5DB).
 * 5. Turistické značení (KČT): Zvýrazněné pásové barvy (červená, modrá, zelená, žlutá).
 * 6. Vrstevnice: Decentní šedé linie pro čitelnost terénu bez vizuálního smogu.
 */
object StyleBuilder {

    /**
     * Vytvoří MapLibre GL styl JSON pro daný lokální MBTiles soubor.
     */
    fun createAmoledOutdoorStyle(mbtilesFile: File?): String {
        val sourceConfig = if (mbtilesFile != null && mbtilesFile.exists()) {
            """
            "offline_source": {
                "type": "vector",
                "tiles": ["mbtiles://${mbtilesFile.absolutePath}/{z}/{x}/{y}"],
                "minzoom": 0,
                "maxzoom": 14
            }
            """.trimIndent()
        } else {
            """
            "offline_source": {
                "type": "vector",
                "tiles": []
            }
            """.trimIndent()
        }

        return """
        {
          "version": 8,
          "name": "OffGrid AMOLED Outdoor",
          "sources": {
            $sourceConfig
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": {
                "background-color": "#000000"
              }
            },
            {
              "id": "water",
              "type": "fill",
              "source": "offline_source",
              "source-layer": "water",
              "paint": {
                "fill-color": "#0F1E36"
              }
            },
            {
              "id": "waterway",
              "type": "line",
              "source": "offline_source",
              "source-layer": "waterway",
              "paint": {
                "line-color": "#1E3A5F",
                "line-width": 1.5
              }
            },
            {
              "id": "contour",
              "type": "line",
              "source": "offline_source",
              "source-layer": "contour",
              "paint": {
                "line-color": "#262626",
                "line-width": 0.8
              }
            },
            {
              "id": "roads_minor",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["in", "class", "path", "track", "footway"],
              "paint": {
                "line-color": "#808080",
                "line-width": 1.5,
                "line-dasharray": [2, 1]
              }
            },
            {
              "id": "roads_major",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["in", "class", "primary", "secondary", "tertiary"],
              "paint": {
                "line-color": "#A0A0A0",
                "line-width": 2.2
              }
            },
            {
              "id": "trail_kct_red",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["==", "colour", "red"],
              "paint": {
                "line-color": "#FF3333",
                "line-width": 3.0
              }
            },
            {
              "id": "trail_kct_blue",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["==", "colour", "blue"],
              "paint": {
                "line-color": "#3388FF",
                "line-width": 3.0
              }
            },
            {
              "id": "trail_kct_green",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["==", "colour", "green"],
              "paint": {
                "line-color": "#00D166",
                "line-width": 3.0
              }
            },
            {
              "id": "trail_kct_yellow",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["==", "colour", "yellow"],
              "paint": {
                "line-color": "#FFCC00",
                "line-width": 3.0
              }
            }
          ]
        }
        """.trimIndent()
    }
}
