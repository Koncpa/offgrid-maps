package cz.offgrid.maps.data.tracking

import cz.offgrid.maps.domain.model.TrackPoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TrackingManagerTest {

    @Before
    fun setup() {
        TrackingManager.clearTrack()
        TrackingManager.setTrackingActive(false)
    }

    @Test
    fun calculateDistanceMeters_samePoint_returnsZero() {
        val distance = TrackingManager.calculateDistanceMeters(
            50.0755, 14.4378,
            50.0755, 14.4378
        )
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun calculateDistanceMeters_knownLocations_calculatesAccurately() {
        // Old Town Square (50.0875, 14.4214) -> Charles Bridge (50.0865, 14.4114)
        // Straight-line distance is ~719 meters
        val distance = TrackingManager.calculateDistanceMeters(
            50.0875, 14.4214,
            50.0865, 14.4114
        )
        assertEquals(719.0, distance, 15.0)
    }

    @Test
    fun addPoint_whenTrackingActive_accumulatesDistanceAndStoresPoints() {
        TrackingManager.setTrackingActive(true)

        val point1 = TrackPoint(50.0875, 14.4214)
        val point2 = TrackPoint(50.0865, 14.4114)

        TrackingManager.addPoint(point1)
        assertEquals(1, TrackingManager.trackPoints.value.size)
        assertEquals(0.0, TrackingManager.totalDistanceMeters.value, 0.001)

        TrackingManager.addPoint(point2)
        assertEquals(2, TrackingManager.trackPoints.value.size)
        assertTrue(TrackingManager.totalDistanceMeters.value > 700.0)
    }

    @Test
    fun addPoint_whenTrackingInactive_updatesLocationOnly() {
        TrackingManager.setTrackingActive(false)

        val point = TrackPoint(50.0875, 14.4214)
        TrackingManager.addPoint(point)

        assertEquals(0, TrackingManager.trackPoints.value.size)
        assertEquals(0.0, TrackingManager.totalDistanceMeters.value, 0.001)
        assertEquals(point, TrackingManager.currentLocation.value)
    }

    @Test
    fun clearTrack_resetsPointsAndDistance() {
        TrackingManager.setTrackingActive(true)
        TrackingManager.addPoint(TrackPoint(50.0, 14.0))
        TrackingManager.addPoint(TrackPoint(50.1, 14.1))

        assertTrue(TrackingManager.trackPoints.value.isNotEmpty())
        assertTrue(TrackingManager.totalDistanceMeters.value > 0.0)

        TrackingManager.clearTrack()
        assertEquals(0, TrackingManager.trackPoints.value.size)
        assertEquals(0.0, TrackingManager.totalDistanceMeters.value, 0.0)
    }

    @Test
    fun updateAltitude_accumulatesAscentWithHysteresis() {
        TrackingManager.setTrackingActive(true)
        TrackingManager.updateAltitude(250.0, isBarometric = true)
        // Sensor noise of 1.5 m below the 3 m threshold is ignored
        TrackingManager.updateAltitude(251.5, isBarometric = true)
        assertEquals(0.0, TrackingManager.totalAscentMeters.value, 0.01)

        // Climb of 6 m above reference 250 m is accumulated
        TrackingManager.updateAltitude(256.0, isBarometric = true)
        assertEquals(6.0, TrackingManager.totalAscentMeters.value, 0.01)
    }

    @Test
    fun setTrackingActiveFalse_clearsLiveTrackPointsAndSessionCounters() {
        TrackingManager.setTrackingActive(true)
        TrackingManager.addPoint(TrackPoint(49.1246, 16.3353, accuracyMeters = 4.0f))
        TrackingManager.addPoint(TrackPoint(49.1268, 16.3321, accuracyMeters = 4.0f))
        TrackingManager.updateAltitude(250.0, isBarometric = true)
        TrackingManager.updateAltitude(258.0, isBarometric = true)

        assertTrue(TrackingManager.trackPoints.value.size >= 2)
        assertTrue(TrackingManager.totalDistanceMeters.value > 0.0)
        assertEquals(8.0, TrackingManager.totalAscentMeters.value, 0.01)

        TrackingManager.setTrackingActive(false)

        assertFalse(TrackingManager.isTracking.value)
        assertTrue(TrackingManager.trackPoints.value.isEmpty())
        assertEquals(0.0, TrackingManager.totalDistanceMeters.value, 0.001)
        assertEquals(0.0, TrackingManager.totalAscentMeters.value, 0.001)
    }

    @Test
    fun applyAltitudeCalibration_doesNotInflateAscentAndPreservesPartialClimb() {
        TrackingManager.setTrackingActive(true)
        // Baseline 300 m, walk +2 m (below 3 m hysteresis threshold)
        TrackingManager.applyAltitudeCalibration(300.0)
        TrackingManager.updateAltitude(302.0, isBarometric = true)
        assertEquals(0.0, TrackingManager.totalAscentMeters.value, 0.01)

        // Sudden +45 m GPS calibration shift must NOT count as climbed meters
        TrackingManager.applyAltitudeCalibration(altitudeMeters = 347.0, shiftMeters = 45.0)
        assertEquals(347.0, TrackingManager.currentAltitudeMeters.value!!, 0.01)
        assertEquals(0.0, TrackingManager.totalAscentMeters.value, 0.01)

        // Walking 1.5 m further reaches total +3.5 m above shifted baseline (345 m -> 348.5 m)
        TrackingManager.updateAltitude(348.5, isBarometric = true)
        assertEquals(3.5, TrackingManager.totalAscentMeters.value, 0.01)
    }

    @Test
    fun addPoint_withPlannedRoute_detectsOffRouteOver60Meters() {
        val route = listOf(
            TrackPoint(49.1246, 16.3353),
            TrackPoint(49.1268, 16.3321)
        )
        TrackingManager.setPlannedRoute("Oslavany loop", route)

        // Point directly on the route
        TrackingManager.addPoint(TrackPoint(49.1246, 16.3353))
        assertFalse(TrackingManager.isOffRoute.value)

        // Point ~300 m away from the route
        TrackingManager.addPoint(TrackPoint(49.1200, 16.3400))
        assertTrue(TrackingManager.isOffRoute.value)
    }

    @Test
    fun addPoint_ignoresInaccurateFixOver10mWhenStrictSatelliteFixExists() {
        val now = 1_700_000_000_000L
        // Accurate satellite fix (4.5 m <= 10 m)
        val haldaGpsPoint = TrackPoint(
            latitude = 49.1565,
            longitude = 16.3510,
            timestampMillis = now,
            accuracyMeters = 4.5f
        )
        TrackingManager.addPoint(haldaGpsPoint)
        assertEquals(49.1565, TrackingManager.currentLocation.value!!.latitude, 0.0001)

        // 5 seconds later, a less accurate estimate arrives (12.5 m > 10 m from a shielded pocket)
        val imprecisePocketOrNetworkPoint = TrackPoint(
            latitude = 49.1607,
            longitude = 16.3444,
            timestampMillis = now + 5_000L,
            accuracyMeters = 12.5f
        )
        TrackingManager.addPoint(imprecisePocketOrNetworkPoint)

        // Short-term guard keeps the accurate satellite fix
        assertEquals(49.1565, TrackingManager.currentLocation.value!!.latitude, 0.0001)
        assertEquals(16.3510, TrackingManager.currentLocation.value!!.longitude, 0.0001)
    }

    @Test
    fun parseGpxContent_supportsMapyCzRteptAndMultilineTrkpt() {
        val mapyCzGpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Mapy.cz">
              <rte>
                <rtept lon="16.3510" lat="49.1565">
                  <ele>348.5</ele>
                </rtept>
                <rtept lat="49.1580" lon="16.3490"/>
              </rte>
            </gpx>
        """.trimIndent()

        val points = GpxExporter.parseGpxContent(mapyCzGpx)
        assertEquals(2, points.size)
        assertEquals(49.1565, points[0].latitude, 0.0001)
        assertEquals(16.3510, points[0].longitude, 0.0001)
        assertEquals(348.5, points[0].altitudeMeters!!, 0.1)
        assertEquals(49.1580, points[1].latitude, 0.0001)
    }

    @Test
    fun addPoint_midpointOnLongStraightSegment_usesCrossTrackDistanceAndStaysOnRoute() {
        // Two GPX route vertices ~445 meters apart north-to-south (49.1200 -> 49.1240).
        // Point-to-vertex distance at the midpoint (49.1220) would be ~222 m and falsely trigger "Off route (>60 m)".
        // With perpendicular Point-to-Segment (Cross-Track) projection, deviation is 0 m.
        val route = listOf(
            TrackPoint(49.1200, 16.3350),
            TrackPoint(49.1240, 16.3350)
        )
        TrackingManager.setPlannedRoute("Straight forest segment", route)

        val midpoint = TrackPoint(49.1220, 16.3350, accuracyMeters = 4.0f)
        val crossTrackDist = TrackingManager.calculateMinDistanceToRouteMeters(
            midpoint.latitude,
            midpoint.longitude,
            route
        )
        assertEquals(0.0, crossTrackDist, 0.5)

        TrackingManager.addPoint(midpoint)
        assertFalse(TrackingManager.isOffRoute.value)
    }

    @Test
    fun setPlannedRoute_cancelAndReload_clearsOffRouteAndRestoresNavigation() {
        val route = listOf(
            TrackPoint(49.1246, 16.3353),
            TrackPoint(49.1268, 16.3321)
        )
        TrackingManager.setPlannedRoute("Udoli_Oslava", route)
        assertEquals("Udoli_Oslava", TrackingManager.plannedRouteName.value)
        assertEquals(2, TrackingManager.plannedRoutePoints.value.size)

        // Step off-route (> 60 m)
        TrackingManager.addPoint(TrackPoint(49.1350, 16.3500, accuracyMeters = 4.0f))
        assertTrue(TrackingManager.isOffRoute.value)

        // Clear active route -> resets planned route and off-route alert
        TrackingManager.setPlannedRoute(null, emptyList())
        assertNull(TrackingManager.plannedRouteName.value)
        assertTrue(TrackingManager.plannedRoutePoints.value.isEmpty())
        assertFalse(TrackingManager.isOffRoute.value)

        // Reload the same route -> immediately restores all points
        TrackingManager.setPlannedRoute("Udoli_Oslava", route.toList())
        assertEquals("Udoli_Oslava", TrackingManager.plannedRouteName.value)
        assertEquals(2, TrackingManager.plannedRoutePoints.value.size)
    }

    @Test
    fun deleteGpxFile_deletesFileAndRecordsInDeletedRegistry() {
        val tempDir = kotlin.io.path.createTempDirectory("gpx_test").toFile()
        try {
            val gpxFile = java.io.File(tempDir, "Test_Route.gpx")
            gpxFile.writeText("<gpx></gpx>")
            assertTrue(gpxFile.exists())

            val deleted = GpxExporter.deleteGpxFile(gpxFile)
            assertTrue(deleted)
            assertFalse(gpxFile.exists())

            // Verify that the file name is recorded in .deleted_tracks so external folder sync never restores it
            val registry = java.io.File(tempDir, ".deleted_tracks")
            assertTrue(registry.exists())
            assertTrue(registry.readLines().contains("Test_Route.gpx"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun addPoint_duringActiveTrekInForest_updatesLocationAndTrackSmoothly() {
        val now = 1_700_000_000_000L
        TrackingManager.setTrackingActive(true)

        // 1. Open clearing (accuracy 5.0 m)
        val clearingPoint = TrackPoint(
            latitude = 49.1246,
            longitude = 16.3353,
            timestampMillis = now,
            accuracyMeters = 5.0f
        )
        TrackingManager.addPoint(clearingPoint)
        assertEquals(1, TrackingManager.trackPoints.value.size)

        // 2. 12 seconds later, enter dense forest canyon (satellite accuracy 14.5 m)
        val forestPoint = TrackPoint(
            latitude = 49.1252,
            longitude = 16.3345,
            timestampMillis = now + 12_000L,
            accuracyMeters = 14.5f
        )
        TrackingManager.addPoint(forestPoint)

        // Live map position and recorded GPX track advance smoothly (no 3-minute freeze)
        assertEquals(49.1252, TrackingManager.currentLocation.value!!.latitude, 0.0001)
        assertEquals(2, TrackingManager.trackPoints.value.size)

        // 3. Coarse cell-tower estimate (accuracy 85 m) is still rejected
        val btsJump = TrackPoint(
            latitude = 49.1500,
            longitude = 16.3600,
            timestampMillis = now + 20_000L,
            accuracyMeters = 85.0f
        )
        TrackingManager.addPoint(btsJump)
        assertEquals(49.1252, TrackingManager.currentLocation.value!!.latitude, 0.0001)
        assertEquals(2, TrackingManager.trackPoints.value.size)
    }

    @Test
    fun wgs84ToMslAltitude_subtractsCzechGeoidUndulationAccurately() {
        // Zbýšov / Oslavany (49.160659, 16.344159):
        // Android GPS returns WGS84 ellipsoidal height = 412.2 m.
        // True MSL terrain elevation is ~368 m (EGM2008 geoid undulation is +44.56 m).
        val mslZbysov = BarometricAltimeterManager.wgs84ToMslAltitude(
            wgs84AltitudeMeters = 412.2,
            latitude = 49.160659,
            longitude = 16.344159
        )
        assertEquals(367.64, mslZbysov, 0.5)

        // Prague (50.08, 14.43): EGM2008 geoid undulation is ~46.5 m
        val geoidPrague = BarometricAltimeterManager.estimateEgm2008GeoidUndulationMeters(50.08, 14.43)
        assertEquals(46.5, geoidPrague, 0.4)
    }
}
