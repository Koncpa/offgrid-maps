package cz.offgrid.maps.data.map

import java.io.File

/**
 * Generátor MapLibre GL Style JSON přizpůsobený pro AMOLED displeje hodinek a outdoor použití.
 *
 * Principy stylu:
 * 1. Background: #000000 (OLED subpixely vypnuté, 0 mW spotřeba).
 * 2. Voda a řeky: Tmavě modrá (#0D223D / #1C447A).
 * 3. Lesy / příroda: Velmi tmavě zelená (#07170D) pro minimální spotřebu, ale jasné odlišení lesa.
 * 4. Budovy a ulice: Kontrastní jemné linie a obrysy budov pro orientaci v obcích.
 * 5. Lesní cesty a pěšiny: Přerušované čáry (#8A949E).
 * 6. Turistické značení (KČT): Kompletní offline síť 14 591 úseků KČT tras Jihomoravského kraje
 *    přímo z vestavěného souboru asset://jmk_kct_trails.geojson (červená, modrá, zelená, žlutá).
 * 7. Popisky (Města, obce, vrcholy s nadmořskou výškou): Jasný text s černým lemem (halo).
 */
object StyleBuilder {

    /**
     * Vytvoří kompletní MapLibre GL styl JSON.
     */
    fun createAmoledOutdoorStyle(mbtilesFile: File?): String {
        val sourceDefinition = if (mbtilesFile != null && mbtilesFile.exists()) {
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
                "tiles": ["https://tiles.openfreemap.org/planet/20261025_080012_pt/{z}/{x}/{y}.pbf"],
                "minzoom": 0,
                "maxzoom": 14
            }
            """.trimIndent()
        }

        return """
        {
          "version": 8,
          "name": "OffGrid AMOLED Outdoor",
          "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
          "sources": {
            $sourceDefinition,
            "kct_trails_source": {
              "type": "geojson",
              "data": "asset://jmk_kct_trails.geojson"
            }
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
              "id": "landcover_wood",
              "type": "fill",
              "source": "offline_source",
              "source-layer": "landcover",
              "filter": ["==", ["get", "class"], "wood"],
              "paint": {
                "fill-color": "#07170D",
                "fill-opacity": 0.85
              }
            },
            {
              "id": "landuse_park",
              "type": "fill",
              "source": "offline_source",
              "source-layer": "park",
              "paint": {
                "fill-color": "#06140B",
                "fill-opacity": 0.6
              }
            },
            {
              "id": "water",
              "type": "fill",
              "source": "offline_source",
              "source-layer": "water",
              "paint": {
                "fill-color": "#0D2440"
              }
            },
            {
              "id": "waterway",
              "type": "line",
              "source": "offline_source",
              "source-layer": "waterway",
              "paint": {
                "line-color": "#1D4A82",
                "line-width": 1.6
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
              "id": "building",
              "type": "fill",
              "source": "offline_source",
              "source-layer": "building",
              "minzoom": 13,
              "paint": {
                "fill-color": "#121212",
                "fill-outline-color": "#262626"
              }
            },
            {
              "id": "roads_paths",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["in", "class", "path", "track", "footway", "bridleway"],
              "layout": {
                "line-cap": "round",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#8A949E",
                "line-width": 1.5,
                "line-dasharray": [2, 1.5]
              }
            },
            {
              "id": "roads_minor",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["in", "class", "minor", "service", "street"],
              "layout": {
                "line-cap": "round",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#454A52",
                "line-width": 2.0
              }
            },
            {
              "id": "roads_major",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["in", "class", "primary", "secondary", "tertiary", "trunk", "motorway"],
              "layout": {
                "line-cap": "round",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#788290",
                "line-width": 2.6
              }
            },
            {
              "id": "railway",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "filter": ["==", "class", "rail"],
              "paint": {
                "line-color": "#555555",
                "line-width": 1.5,
                "line-dasharray": [3, 3]
              }
            },
            {
              "id": "trail_kct_yellow",
              "type": "line",
              "source": "kct_trails_source",
              "filter": ["==", ["get", "colour"], "yellow"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#FFCC00",
                "line-width": ["interpolate", ["linear"], ["zoom"], 9, 1.8, 13, 3.2, 16, 4.5]
              }
            },
            {
              "id": "trail_kct_green",
              "type": "line",
              "source": "kct_trails_source",
              "filter": ["==", ["get", "colour"], "green"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#00D166",
                "line-width": ["interpolate", ["linear"], ["zoom"], 9, 1.8, 13, 3.2, 16, 4.5]
              }
            },
            {
              "id": "trail_kct_blue",
              "type": "line",
              "source": "kct_trails_source",
              "filter": ["==", ["get", "colour"], "blue"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#3388FF",
                "line-width": ["interpolate", ["linear"], ["zoom"], 9, 1.8, 13, 3.2, 16, 4.5]
              }
            },
            {
              "id": "trail_kct_red",
              "type": "line",
              "source": "kct_trails_source",
              "filter": ["==", ["get", "colour"], "red"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#FF3333",
                "line-width": ["interpolate", ["linear"], ["zoom"], 9, 2.0, 13, 3.5, 16, 5.0]
              }
            },
            {
              "id": "mountain_peaks",
              "type": "symbol",
              "source": "offline_source",
              "source-layer": "mountain_peak",
              "minzoom": 10,
              "layout": {
                "text-field": ["case",
                  ["has", "ele"],
                  ["concat", "▲ ", ["coalesce", ["get", "name:cs"], ["get", "name"]], "\n", ["to-string", ["get", "ele"]], " m"],
                  ["concat", "▲ ", ["coalesce", ["get", "name:cs"], ["get", "name"]]]
                ],
                "text-font": ["Noto Sans Regular"],
                "text-size": 11,
                "text-anchor": "center"
              },
              "paint": {
                "text-color": "#FF9E0B",
                "text-halo-color": "#000000",
                "text-halo-width": 1.8
              }
            },
            {
              "id": "place_labels",
              "type": "symbol",
              "source": "offline_source",
              "source-layer": "place",
              "layout": {
                "text-field": ["coalesce", ["get", "name:cs"], ["get", "name"]],
                "text-font": ["Noto Sans Regular"],
                "text-size": ["interpolate", ["linear"], ["zoom"], 8, 10, 14, 13],
                "text-anchor": "center"
              },
              "paint": {
                "text-color": "#E5E7EB",
                "text-halo-color": "#000000",
                "text-halo-width": 2.0
              }
            },
            {
              "id": "road_labels",
              "type": "symbol",
              "source": "offline_source",
              "source-layer": "transportation_name",
              "minzoom": 13,
              "layout": {
                "symbol-placement": "line",
                "text-field": ["coalesce", ["get", "name:cs"], ["get", "name"]],
                "text-font": ["Noto Sans Regular"],
                "text-size": 10
              },
              "paint": {
                "text-color": "#9CA3AF",
                "text-halo-color": "#000000",
                "text-halo-width": 1.5
              }
            }
          ]
        }
        """.trimIndent()
    }
}
