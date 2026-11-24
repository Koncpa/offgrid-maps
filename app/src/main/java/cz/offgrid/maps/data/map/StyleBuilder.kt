package cz.offgrid.maps.data.map

import java.io.File

/**
 * MapLibre GL Style JSON generator using the Ultra-Pure AMOLED & KČT cartographic architecture.
 *
 * How the color layout is structured for maximum battery life and outdoor legibility:
 * 1. Pure black background (#000000) without solid forest polygon fills:
 *    Filling forest polygons with solid green would illuminate thousands of OLED subpixels across the screen.
 *    Instead, 94% of the display remains pure black (#000000 = 0 mW power draw), illuminating only
 *    road lines, waterways, contour lines, and labels.
 * 2. Topographic zoom-level hierarchy:
 *    - Zoom 9+: Marked hiking trails (Red, Blue, Green, Yellow) and primary roads.
 *    - Zoom 12+: Unpaved forest tracks (`class = track`, long dashes) + 10 m and 50 m contour lines.
 *    - Zoom 13+: Narrow footpaths (`class = path/footway`, short dashes) and contour elevation labels.
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
            "custom_kct_trails_source": {
              "type": "geojson",
              "data": {"type": "FeatureCollection", "features": []}
            },
            "jmk_contours_source": {
              "type": "geojson",
              "data": "asset://jmk_contours.geojson"
            },
            "custom_contours_source": {
              "type": "geojson",
              "data": {"type": "FeatureCollection", "features": []}
            },
            "custom_outdoor_pois_source": {
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
                "line-color": "#3D301A",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.65, 13, 0.85, 15, 0.92],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.6, 13, 0.75, 15, 1.0]
              }
            },
            {
              "id": "contour_major",
              "type": "line",
              "source": "jmk_contours_source",
              "minzoom": 12,
              "filter": ["==", ["get", "major"], 1],
              "paint": {
                "line-color": "#4C3C21",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.72, 13, 0.88, 15, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.85, 13, 1.1, 15, 1.45]
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
                "line-color": "#3D301A",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.65, 13, 0.85, 15, 0.92],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.6, 13, 0.75, 15, 1.0]
              }
            },
            {
              "id": "custom_contour_major",
              "type": "line",
              "source": "custom_contours_source",
              "minzoom": 12,
              "filter": ["==", ["get", "major"], 1],
              "paint": {
                "line-color": "#4C3C21",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.72, 13, 0.88, 15, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.85, 13, 1.1, 15, 1.45]
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
                "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1.0, 12, 1.8, 14, 2.5, 16, 3.4]
              }
            },
            {
              "id": "roads_minor",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "minzoom": 11,
              "filter": ["in", "class", "minor", "service", "street"],
              "layout": {
                "line-cap": "round",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#363C46",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 11, 0.5, 12, 0.9, 14, 1.0],
                "line-width": ["interpolate", ["linear"], ["zoom"], 11, 0.8, 13, 1.4, 15, 2.2]
              }
            },
            {
              "id": "roads_tracks",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "minzoom": 12,
              "filter": ["==", "class", "track"],
              "layout": {
                "line-cap": "butt",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#6E7680",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0.75, 13, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 1.1, 14, 1.6, 16, 2.2],
                "line-dasharray": [4.0, 2.0]
              }
            },
            {
              "id": "roads_paths",
              "type": "line",
              "source": "offline_source",
              "source-layer": "transportation",
              "minzoom": 13,
              "filter": ["in", "class", "path", "footway", "bridleway", "steps"],
              "layout": {
                "line-cap": "round",
                "line-join": "round"
              },
              "paint": {
                "line-color": "#828C96",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0.8, 14, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 13, 1.1, 15, 1.5, 16, 1.9],
                "line-dasharray": [1.5, 1.8]
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
              "minzoom": 11,
              "filter": ["==", ["get", "colour"], "yellow"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#E6B800",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 11, 0.75, 12, 0.92],
                "line-width": ["interpolate", ["linear"], ["zoom"], 11, 1.4, 12, 2.0, 14, 3.0, 16, 4.2]
              }
            },
            {
              "id": "trail_kct_green",
              "type": "line",
              "source": "kct_trails_source",
              "minzoom": 11,
              "filter": ["==", ["get", "colour"], "green"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#00C814",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 11, 0.75, 12, 0.92],
                "line-width": ["interpolate", ["linear"], ["zoom"], 11, 1.4, 12, 2.0, 14, 3.0, 16, 4.2]
              }
            },
            {
              "id": "trail_kct_blue",
              "type": "line",
              "source": "kct_trails_source",
              "minzoom": 10,
              "filter": ["==", ["get", "colour"], "blue"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#0077E6",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0.7, 11, 0.88, 12, 0.94],
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.3, 12, 2.1, 14, 3.1, 16, 4.3]
              }
            },
            {
              "id": "trail_kct_red",
              "type": "line",
              "source": "kct_trails_source",
              "minzoom": 10,
              "filter": ["==", ["get", "colour"], "red"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#EA0000",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0.75, 11, 0.90, 12, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.5, 12, 2.3, 14, 3.4, 16, 4.6]
              }
            },
            {
              "id": "custom_trail_other",
              "type": "line",
              "source": "custom_kct_trails_source",
              "minzoom": 10,
              "filter": ["==", ["get", "colour"], "other"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#B266FF",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0.7, 12, 0.9],
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.2, 12, 1.9, 14, 2.8, 16, 3.9]
              }
            },
            {
              "id": "custom_trail_kct_yellow",
              "type": "line",
              "source": "custom_kct_trails_source",
              "minzoom": 11,
              "filter": ["==", ["get", "colour"], "yellow"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#E6B800",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 11, 0.75, 12, 0.92],
                "line-width": ["interpolate", ["linear"], ["zoom"], 11, 1.4, 12, 2.0, 14, 3.0, 16, 4.2]
              }
            },
            {
              "id": "custom_trail_kct_green",
              "type": "line",
              "source": "custom_kct_trails_source",
              "minzoom": 11,
              "filter": ["==", ["get", "colour"], "green"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#00C814",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 11, 0.75, 12, 0.92],
                "line-width": ["interpolate", ["linear"], ["zoom"], 11, 1.4, 12, 2.0, 14, 3.0, 16, 4.2]
              }
            },
            {
              "id": "custom_trail_kct_blue",
              "type": "line",
              "source": "custom_kct_trails_source",
              "minzoom": 10,
              "filter": ["==", ["get", "colour"], "blue"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#0077E6",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0.7, 11, 0.88, 12, 0.94],
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.3, 12, 2.1, 14, 3.1, 16, 4.3]
              }
            },
            {
              "id": "custom_trail_kct_red",
              "type": "line",
              "source": "custom_kct_trails_source",
              "minzoom": 10,
              "filter": ["==", ["get", "colour"], "red"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "#EA0000",
                "line-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0.75, 11, 0.90, 12, 0.95],
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.5, 12, 2.3, 14, 3.4, 16, 4.6]
              }
            },
            {
              "id": "custom_aerialway",
              "type": "line",
              "source": "custom_kct_trails_source",
              "minzoom": 11,
              "filter": ["==", ["get", "colour"], "aerialway"],
              "layout": { "line-cap": "butt", "line-join": "round" },
              "paint": {
                "line-color": "#4DA6FF",
                "line-dasharray": [3.0, 2.0],
                "line-width": ["interpolate", ["linear"], ["zoom"], 11, 1.3, 14, 2.2]
              }
            },
            {
              "id": "custom_trail_ferrata",
              "type": "line",
              "source": "custom_kct_trails_source",
              "minzoom": 12,
              "filter": ["==", ["get", "colour"], "ferrata"],
              "layout": { "line-cap": "butt", "line-join": "round" },
              "paint": {
                "line-color": "#FF5500",
                "line-dasharray": [1.2, 1.2],
                "line-width": ["interpolate", ["linear"], ["zoom"], 12, 2.2, 14, 3.4, 16, 4.4]
              }
            },
            {
              "id": "vector_outdoor_pois",
              "type": "symbol",
              "source": "offline_source",
              "source-layer": "poi",
              "minzoom": 13,
              "filter": ["in", ["get", "subclass"], ["literal", [
                "drinking_water", "shelter", "alpine_hut", "wilderness_hut", "camp_site",
                "viewpoint", "castle", "ruins", "cave_entrance", "picnic_site"
              ]]],
              "layout": {
                "text-field": ["coalesce", ["get", "name:en"], ["get", "name"], ["get", "subclass"]],
                "text-font": ["Noto Sans Regular"],
                "text-size": 9,
                "text-anchor": "center",
                "text-optional": true
              },
              "paint": {
                "text-color": ["match", ["get", "subclass"],
                  "drinking_water", "#00C8FF",
                  ["shelter", "alpine_hut", "wilderness_hut", "camp_site"], "#E6B800",
                  "#D8D8D0"
                ],
                "text-halo-color": "#000000",
                "text-halo-width": 1.8
              }
            },
            {
              "id": "outdoor_poi_dots",
              "type": "circle",
              "source": "custom_outdoor_pois_source",
              "minzoom": 13,
              "filter": ["==", ["get", "cat"], "guidepost"],
              "paint": {
                "circle-radius": ["interpolate", ["linear"], ["zoom"], 13, 1.8, 15, 2.8],
                "circle-color": "#7E8892",
                "circle-stroke-color": "#000000",
                "circle-stroke-width": 1.0
              }
            },
            {
              "id": "outdoor_poi_icons",
              "type": "symbol",
              "source": "custom_outdoor_pois_source",
              "minzoom": 12,
              "filter": ["!=", ["get", "cat"], "guidepost"],
              "layout": {
                "icon-image": ["match", ["get", "cat"],
                  "water", "poi_water",
                  "shelter", "poi_shelter",
                  "viewpoint", "poi_viewpoint",
                  "picnic", "poi_picnic",
                  "saddle", "poi_saddle",
                  "rescue", "poi_rescue",
                  "poi_landmark"
                ],
                "icon-size": ["interpolate", ["linear"], ["zoom"], 12, 0.70, 14, 0.90, 16, 1.08],
                "icon-allow-overlap": true
              }
            },
            {
              "id": "outdoor_poi_labels",
              "type": "symbol",
              "source": "custom_outdoor_pois_source",
              "minzoom": 13,
              "filter": ["match", ["get", "label"],
                ["Viewpoint", "Picnic", "Shelter", "Spring", "Drinking Water", "Trailhead", "Bivouac / Hut", "Alpine Hut", "Mountain Rescue"],
                false,
                true
              ],
              "layout": {
                "text-field": ["get", "label"],
                "text-font": ["Noto Sans Regular"],
                "text-size": 9.5,
                "text-offset": [0, 1.15],
                "text-anchor": "top",
                "text-optional": true
              },
              "paint": {
                "text-color": ["match", ["get", "cat"],
                  "water", "#00C8FF",
                  "shelter", "#E6B800",
                  "viewpoint", "#FFD54F",
                  "picnic", "#4CD964",
                  "saddle", "#C8B88A",
                  "rescue", "#FF4444",
                  "landmark", "#E0E0D8",
                  "#9AA4AE"
                ],
                "text-halo-color": "#000000",
                "text-halo-width": 1.8
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
                  ["concat", "▲ ", ["coalesce", ["get", "name:en"], ["get", "name"]], " (", ["to-string", ["get", "ele"]], " m)"],
                  ["concat", "▲ ", ["coalesce", ["get", "name:en"], ["get", "name"]]]
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
                "text-field": ["coalesce", ["get", "name:en"], ["get", "name"]],
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
                "text-field": ["coalesce", ["get", "name:en"], ["get", "name"]],
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
