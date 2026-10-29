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
        // Staroměstské náměstí (50.0875, 14.4214) -> Karlův most (50.0865, 14.4114)
        // Vzdušná vzdálenost je cca 719 metrů
        val distance = TrackingManager.calculateDistanceMeters(
            50.0875, 14.4214,
            50.0865, 14.4114
        )
        assertEquals(719.0, distance, 15.0) // Odchylka do 15 metrů
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
}
