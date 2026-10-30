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

    @Test
    fun updateAltitude_accumulatesAscentWithHysteresis() {
        TrackingManager.setTrackingActive(true)
        TrackingManager.updateAltitude(250.0, isBarometric = true)
        // Šum o 1.5 m pod prahem 3 m se nezapočítá
        TrackingManager.updateAltitude(251.5, isBarometric = true)
        assertEquals(0.0, TrackingManager.totalAscentMeters.value, 0.01)

        // Výstup o 6 m nad referenčních 250 m se započítá
        TrackingManager.updateAltitude(256.0, isBarometric = true)
        assertEquals(6.0, TrackingManager.totalAscentMeters.value, 0.01)
    }

    @Test
    fun addPoint_withPlannedRoute_detectsOffRouteOver60Meters() {
        val route = listOf(
            TrackPoint(49.1246, 16.3353),
            TrackPoint(49.1268, 16.3321)
        )
        TrackingManager.setPlannedRoute("Oslavany okruh", route)

        // Bod přímo na trase
        TrackingManager.addPoint(TrackPoint(49.1246, 16.3353))
        assertFalse(TrackingManager.isOffRoute.value)

        // Bod vzdálený cca 300 m od trasy
        TrackingManager.addPoint(TrackPoint(49.1200, 16.3400))
        assertTrue(TrackingManager.isOffRoute.value)
    }

    @Test
    fun addPoint_ignoresCoarseNetworkFixWhenAccurateSatelliteFixExists() {
        val now = 1_700_000_000_000L
        // Přesný satelitní fix na Zbýšovské haldě (přesnost 4.5 m)
        val haldaGpsPoint = TrackPoint(
            latitude = 49.1565,
            longitude = 16.3510,
            timestampMillis = now,
            accuracyMeters = 4.5f
        )
        TrackingManager.addPoint(haldaGpsPoint)
        assertEquals(49.1565, TrackingManager.currentLocation.value!!.latitude, 0.0001)

        // O 5 sekund později přijde hrubý síťový odhad z Wi-Fi/BTS v Sičce (nepřesnost 42 m)
        val coarseSickaNetworkPoint = TrackPoint(
            latitude = 49.1607,
            longitude = 16.3444,
            timestampMillis = now + 5_000L,
            accuracyMeters = 42.0f
        )
        TrackingManager.addPoint(coarseSickaNetworkPoint)

        // Šipka musí zůstat na Zbýšovské haldě, nikoliv přeskočit do Sičky
        assertEquals(49.1565, TrackingManager.currentLocation.value!!.latitude, 0.0001)
        assertEquals(16.3510, TrackingManager.currentLocation.value!!.longitude, 0.0001)
    }
}
