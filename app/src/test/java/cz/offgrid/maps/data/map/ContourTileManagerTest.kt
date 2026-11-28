package cz.offgrid.maps.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.sinh

class ContourTileManagerTest {

    private fun tileCenterLatLon(tx: Double, ty: Double, z: Int): Pair<Double, Double> {
        val n = (1 shl z).toDouble()
        val lon = tx / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * ty / n))))
        return lat to lon
    }

    @Test
    fun computeContourTileWindow_loadsSingleTileInCenterAndExpandsOnlyNearEdges() {
        val baseTx = 2233
        val baseTy = 1404

        // Exact tile center -> must load 1x1 (single tile) at both z=12.0 and z=14.0
        val (centerLat, centerLon) = tileCenterLatLon(baseTx + 0.50, baseTy + 0.50, 12)
        val centerWin = ContourTileManager.computeContourTileWindow(centerLat, centerLon, zoom = 14.0)
        assertEquals(1, centerWin.tileCount)
        assertEquals(baseTx, centerWin.minTx)
        assertEquals(baseTx, centerWin.maxTx)
        assertEquals(baseTy, centerWin.minTy)
        assertEquals(baseTy, centerWin.maxTy)

        // Near eastern edge, vertical center -> must expand to 2x1 (2 tiles)
        val (eastLat, eastLon) = tileCenterLatLon(baseTx + 0.90, baseTy + 0.50, 12)
        val eastWin = ContourTileManager.computeContourTileWindow(eastLat, eastLon, zoom = 14.0)
        assertEquals(2, eastWin.tileCount)
        assertEquals(baseTx, eastWin.minTx)
        assertEquals(baseTx + 1, eastWin.maxTx)
        assertEquals(baseTy, eastWin.minTy)
        assertEquals(baseTy, eastWin.maxTy)

        // Near northwest corner -> must expand to 2x2 (4 tiles max, never 9)
        val (cornerLat, cornerLon) = tileCenterLatLon(baseTx + 0.05, baseTy + 0.05, 12)
        val cornerWin = ContourTileManager.computeContourTileWindow(cornerLat, cornerLon, zoom = 13.0)
        assertEquals(4, cornerWin.tileCount)
        assertEquals(baseTx - 1, cornerWin.minTx)
        assertEquals(baseTx, cornerWin.maxTx)
        assertEquals(baseTy - 1, cornerWin.minTy)
        assertEquals(baseTy, cornerWin.maxTy)
    }

    @Test
    fun computeContourTileWindow_clampsAtWorldEdgesSafely() {
        val nw = ContourTileManager.computeContourTileWindow(85.0511, -179.99, zoom = 12.0)
        assertEquals(0, nw.minTx)
        assertEquals(0, nw.minTy)
        assertTrue(nw.tileCount in 1..4)

        val maxTile = (1 shl 12) - 1
        val se = ContourTileManager.computeContourTileWindow(-85.0511, 179.99, zoom = 12.0)
        assertEquals(maxTile, se.maxTx)
        assertEquals(maxTile, se.maxTy)
        assertTrue(se.tileCount in 1..4)
    }

    @Test
    fun webMercatorTileCoordinates_workAcrossWesternAndSouthernHemispheres() {
        // US Rockies (Denver: 39.74, -104.99) -> western hemisphere tx < 2048, northern ty < 2048
        val usTx = ContourTileManager.lonToTileX(-104.99, 12)
        val usTy = ContourTileManager.latToTileY(39.74, 12)
        assertTrue("Western hemisphere tx should be in 0..2047, was $usTx", usTx in 0..2047)
        assertTrue("Northern hemisphere ty should be in 0..2047, was $usTy", usTy in 0..2047)

        // New Zealand (Queenstown: -45.03, 168.66) -> eastern tx > 2048, southern ty > 2048
        val nzTx = ContourTileManager.lonToTileX(168.66, 12)
        val nzTy = ContourTileManager.latToTileY(-45.03, 12)
        assertTrue("Eastern hemisphere tx should be in 2048..4095, was $nzTx", nzTx in 2048..4095)
        assertTrue("Southern hemisphere ty should be in 2048..4095, was $nzTy", nzTy in 2048..4095)

        val nzWin = ContourTileManager.computeContourTileWindow(-45.03, 168.66, zoom = 13.5)
        assertTrue(nzWin.tileCount in 1..4)
    }

    @Test
    fun generateContoursFromElevationGrid_emitsValidMinorAndMajorMultiLineStrings() {
        // 32x32 sloped elevation grid rising from 400 m (west/north) to 620 m (east/south)
        val w = 32
        val h = 32
        val elev = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                elev[y * w + x] = 400f + (x + y) * 3.5f
            }
        }

        val featuresJson = ContourTileManager.generateContoursFromElevationGrid(12, 2233, 1404, w, h, elev)
        assertTrue("Expected contour features on sloped mountain grid", featuresJson.isNotEmpty())

        val fc = org.json.JSONObject("""{"type":"FeatureCollection","features":[$featuresJson]}""")
        val features = fc.getJSONArray("features")
        assertTrue("Expected multiple 10 m contour levels, got ${features.length()}", features.length() >= 10)

        var majorCount = 0
        var minorCount = 0
        for (i in 0 until features.length()) {
            val feat = features.getJSONObject(i)
            val props = feat.getJSONObject("properties")
            val ele = props.getInt("ele")
            val major = props.getInt("major")
            assertEquals(if (ele % 50 == 0) 1 else 0, major)
            if (major == 1) majorCount++ else minorCount++
            assertEquals("MultiLineString", feat.getJSONObject("geometry").getString("type"))
            assertTrue(feat.getJSONObject("geometry").getJSONArray("coordinates").length() > 0)
        }
        assertTrue("Expected >= 3 major (50 m) contour levels", majorCount >= 3)
        assertTrue("Expected >= 7 minor (10 m) contour levels", minorCount >= 7)

        // Completely flat grid -> zero crossings -> empty output
        val flat = FloatArray(w * h) { 355f }
        assertEquals("", ContourTileManager.generateContoursFromElevationGrid(12, 2233, 1404, w, h, flat))
    }
}
