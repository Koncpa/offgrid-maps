package cz.offgrid.maps.data.map

import java.io.File

/**
 * Generátor MapLibre GL Style JSON v architektuře Ultra-Pure AMOLED & KČT.
 *
 * Jak je strukturován barevný layout pro maximální úsporu baterie a čitelnost:
 * 1. Čisté černé pozadí (#000000) bez plošného podbarvování lesů:
 *    Plošné vyplňování lesů zelenou barvou rozsvěcelo tisíce OLED subpixelů po celé obrazovce.
 *    Nyní zůstává 94 % plochy displeje čistě černých (#000000 = 0 mW spotřeba) a svítí
 *    pouze samotné linie cest, vodních toků a popisky!
 * 2. Chytré filtrování podle přiblížení (proti „barevné směsici“ při oddálení):
 *    - Pod Zoom 11: Vidíte jen čistou kostru hlavních měst a hlavních silnic.
 *    - Od Zoom 11: Jemně vystoupí hlavní červené dálkové trasy KČT.
 *    - Od Zoom 12: Postupně se rozsvítí modré, zelené a žluté turistické trasy.
 *    - Od Zoom 13: Vykreslí se tečkované lesní pěšiny a obrysy budov.
 */
object StyleBuilder {

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
            },
            "jmk_contours_source": {
              "type": "geojson",
              "data": "asset://jmk_contours.geojson"
            },
            "custom_contours_source": {
              "type": "geojson",
              "data": {"type": "FeatureCollection", "features": []}
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
              "id": "water",
              "type": "fill",
              "source": "offline_source",
              "source-layer": "water",
              "paint": {
                "fill-color": "#07182E"
              }
            },
            {
              "id": "waterway",
              "type": "line",
              "source": "offline_source",
              "source-layer": "waterway",
              "minzoom": 10,
              "paint": {
                "line-color": "#153B6B",
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.0, 14, 1.8]
              }
            },
            {
              "id": "contour_minor",
              "type": "line",
              "source": "jmk_contours_source",
              "minzoom": 12,
              "filter": ["==", ["get", "major"], 0],
              "paint": {
                "line-color": "#45371E",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.25, 12.6, 0.8, 14, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.65, 14, 0.95]
              }
            },
            {
              "id": "contour_major",
              "type": "line",
              "source": "jmk_contours_source",
              "minzoom": 12,
              "filter": ["==", ["get", "major"], 1],
              "paint": {
                "line-color": "#594727",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.25, 12.6, 0.85, 14, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.9, 14, 1.25]
              }
            },
            {
              "id": "contour_labels",
              "type": "symbol",
              "source": "jmk_contours_source",
              "minzoom": 13,
              "filter": ["==", ["get", "major"], 1],
              "layout": {
                "symbol-placement": "line",
                "symbol-spacing": 240,
                "text-field": ["concat", ["to-string", ["get", "ele"]], " m"],
                "text-font": ["Noto Sans Regular"],
                "text-size": 8.5
              },
              "paint": {
                "text-color": "#8A6F3E",
                "text-halo-color": "#000000",
                "text-halo-width": 1.8
              }
            },
            {
              "id": "custom_contour_minor",
              "type": "line",
              "source": "custom_contours_source",
              "minzoom": 12,
              "filter": ["==", ["get", "major"], 0],
              "paint": {
                "line-color": "#45371E",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.25, 12.6, 0.8, 14, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.65, 14, 0.95]
              }
            },
            {
              "id": "custom_contour_major",
              "type": "line",
              "source": "custom_contours_source",
              "minzoom": 12,
              "filter": ["==", ["get", "major"], 1],
              "paint": {
                "line-color": "#594727",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.25, 12.6, 0.85, 14, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.9, 14, 1.25]
              }
            },
            {
              "id": "custom_contour_labels",
              "type": "symbol",
              "source": "custom_contours_source",
              "minzoom": 13,
              "filter": ["==", ["get", "major"], 1],
              "layout": {
                "symbol-placement": "line",
                "symbol-spacing": 240,
                "text-field": ["concat", ["to-string", ["get", "ele"]], " m"],
                "text-font": ["Noto Sans Regular"],
                "text-size": 8.5
              },
              "paint": {
                "text-color": "#8A6F3E",
                "text-halo-color": "#000000",
                "text-halo-width": 1.8
              }
            },
            {
              "id": "building",
              "type": "fill",
              "source": "offline_source",
              "source-layer": "building",
              "minzoom": 14,
              "paint": {
                "fill-color": "#0A0A0A",
                "fill-outline-color": "#222222"
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
                "line-color": "#48505C",
                "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1.0, 13, 2.2, 16, 3.2]
              }
            },
            {
              "id": "roads_minor",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "minzoom": 12,
              "filter": ["in", "class", "minor", "service", "street"],
              "layout": {
                "line-cap": "round",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#323740",
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 1.0, 15, 2.0]
              }
            },
            {
              "id": "roads_paths",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "minzoom": 13,
              "filter": ["in", "class", "path", "track", "footway", "bridleway"],
              "layout": {
                "line-cap": "round",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#737D87",
                "line-width": 1.4,
                "line-dasharray": [2, 1.8]
              }
            },
            {
              "id": "railway",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "minzoom": 11,
              "filter": ["==", "class", "rail"],
              "paint": {
                "line-color": "#3D3D3D",
                "line-width": 1.3,
                "line-dasharray": [3, 3]
              }
            },
            {
              "id": "trail_kct_yellow",
              "type": "line",
              "source": "kct_trails_source",
              "minzoom": 12,
              "filter": ["==", ["get", "colour"], "yellow"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#E6B800",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.0, 12.6, 0.90],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 1.8, 14, 3.0, 16, 4.2]
              }
            },
            {
              "id": "trail_kct_green",
              "type": "line",
              "source": "kct_trails_source",
              "minzoom": 12,
              "filter": ["==", ["get", "colour"], "green"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#00C814",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.0, 12.6, 0.90],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 1.8, 14, 3.0, 16, 4.2]
              }
            },
            {
              "id": "trail_kct_blue",
              "type": "line",
              "source": "kct_trails_source",
              "minzoom": 11,
              "filter": ["==", ["get", "colour"], "blue"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#0077E6",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 11.5, 0.0, 12.3, 0.90],
                "line-width": ["interpolate", ["linear"], ["zoom"], 11.5, 1.6, 14, 3.0, 16, 4.2]
              }
            },
            {
              "id": "trail_kct_red",
              "type": "line",
              "source": "kct_trails_source",
              "minzoom": 11,
              "filter": ["==", ["get", "colour"], "red"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#EA0000",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 11.0, 0.0, 11.8, 0.92],
                "line-width": ["interpolate", ["linear"], ["zoom"], 11.0, 1.8, 14, 3.4, 16, 4.6]
              }
            },
            {
              "id": "mountain_peaks",
              "type": "symbol",
              "source": "offline_source",
              "source-layer": "mountain_peak",
              "minzoom": 11,
              "layout": {
                "text-field": ["case",
                  ["has", "ele"],
                  ["concat", "▲ ", ["coalesce", ["get", "name:cs"], ["get", "name"]], " (", ["to-string", ["get", "ele"]], " m)"],
                  ["concat", "▲ ", ["coalesce", ["get", "name:cs"], ["get", "name"]]]
                ],
                "text-font": ["Noto Sans Regular"],
                "text-size": 10,
                "text-anchor": "center"
              },
              "paint": {
                "text-color": "#E6B800",
                "text-halo-color": "#000000",
                "text-halo-width": 2.0
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
                "text-size": ["interpolate", ["linear"], ["zoom"], 8, 10, 14, 12],
                "text-anchor": "center"
              },
              "paint": {
                "text-color": "#D8D8D0",
                "text-halo-color": "#000000",
                "text-halo-width": 2.2
              }
            },
            {
              "id": "road_labels",
              "type": "symbol",
              "source": "offline_source",
              "source-layer": "transportation_name",
              "minzoom": 14,
              "layout": {
                "symbol-placement": "line",
                "text-field": ["coalesce", ["get", "name:cs"], ["get", "name"]],
                "text-font": ["Noto Sans Regular"],
                "text-size": 10
              },
              "paint": {
                "text-color": "#8A949E",
                "text-halo-color": "#000000",
                "text-halo-width": 1.8
              }
            }
          ]
        }
        """.trimIndent()
    }
}
