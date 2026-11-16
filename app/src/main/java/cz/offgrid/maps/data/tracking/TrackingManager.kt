package cz.offgrid.maps.data.tracking

import cz.offgrid.maps.domain.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Central state manager for track recording, barometric elevation gain, and GPX route navigation.
 */
object TrackingManager {

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _recordingStartMillis = MutableStateFlow(0L)
    val recordingStartMillis: StateFlow<Long> = _recordingStartMillis.asStateFlow()

    private val _trackPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val trackPoints: StateFlow<List<TrackPoint>> = _trackPoints.asStateFlow()

    private val _totalDistanceMeters = MutableStateFlow(0.0)
    val totalDistanceMeters: StateFlow<Double> = _totalDistanceMeters.asStateFlow()

    private val _currentAltitudeMeters = MutableStateFlow<Double?>(null)
    val currentAltitudeMeters: StateFlow<Double?> = _currentAltitudeMeters.asStateFlow()

    private val _totalAscentMeters = MutableStateFlow(0.0)
    val totalAscentMeters: StateFlow<Double> = _totalAscentMeters.asStateFlow()

    private var referenceAltitudeForAscent: Double? = null

    private val _currentLocation = MutableStateFlow<TrackPoint?>(null)
    val currentLocation: StateFlow<TrackPoint?> = _currentLocation.asStateFlow()

    private val _lastSavedSummary = MutableStateFlow<String?>(null)
    val lastSavedSummary: StateFlow<String?> = _lastSavedSummary.asStateFlow()

    // Active planned GPX route for on-map navigation
    private val _plannedRoutePoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val plannedRoutePoints: StateFlow<List<TrackPoint>> = _plannedRoutePoints.asStateFlow()

    private val _plannedRouteName = MutableStateFlow<String?>(null)
    val plannedRouteName: StateFlow<String?> = _plannedRouteName.asStateFlow()

    private val _isOffRoute = MutableStateFlow(false)
    val isOffRoute: StateFlow<Boolean> = _isOffRoute.asStateFlow()

    fun setTrackingActive(active: Boolean) {
        if (active && !_isTracking.value) {
            _trackPoints.value = emptyList()
            _totalDistanceMeters.value = 0.0
            _totalAscentMeters.value = 0.0
            referenceAltitudeForAscent = _currentAltitudeMeters.value
            _recordingStartMillis.value = System.currentTimeMillis()
            _lastSavedSummary.value = null
            _isTracking.value = true

            _currentLocation.value?.let { startPt ->
                val alt = _currentAltitudeMeters.value ?: startPt.altitudeMeters
                _trackPoints.value = listOf(
                    startPt.copy(
                        altitudeMeters = alt,
                        timestampMillis = System.currentTimeMillis()
                    )
                )
            }
        } else if (!active) {
            _isTracking.value = false
        }
    }

    fun setSavedSummary(summary: String?) {
        _lastSavedSummary.value = summary
    }

    /**
     * Updates current elevation (from barometer or GPS fallback) and accumulates
     * elevation gain with hysteresis during active recording.
     */
    fun updateAltitude(altitudeMeters: Double, isBarometric: Boolean) {
        _currentAltitudeMeters.value = altitudeMeters

        if (!_isTracking.value) {
            referenceAltitudeForAscent = altitudeMeters
            return
        }

        val ref = referenceAltitudeForAscent
        if (ref == null) {
            referenceAltitudeForAscent = altitudeMeters
            return
        }

        // Hysteresis threshold: 3 m for accurate barometer, 7 m for GPS fallback (filters sensor noise)
        val threshold = if (isBarometric) 3.0 else 7.0
        val diff = altitudeMeters - ref
        if (diff >= threshold) {
            _totalAscentMeters.value += diff
            referenceAltitudeForAscent = altitudeMeters
        } else if (diff <= -threshold) {
            // Shift reference altitude down on descent to measure subsequent climbs accurately
            referenceAltitudeForAscent = altitudeMeters
        }
    }

    /**
     * Sets or clears the active planned GPX route for map navigation.
     */
    fun setPlannedRoute(name: String?, points: List<TrackPoint>) {
        _plannedRouteName.value = name
        _plannedRoutePoints.value = points
        _isOffRoute.value = false
    }

    /**
     * Adds a new location fix, accumulates distance, and checks off-route deviation.
     * Protects accurate fixes from coarse cell-tower/Wi-Fi jumps while smoothly accepting
     * real satellite fixes under forest canopy (up to 22 m) so active treks never freeze.
     */
    fun addPoint(point: TrackPoint) {
        val incomingAcc = point.accuracyMeters
        val existingLoc = _currentLocation.value

        if (existingLoc != null) {
            val existingAcc = existingLoc.accuracyMeters
            val ageDeltaMs = point.timestampMillis - existingLoc.timestampMillis

            // 1. Short-term guard (8 s) against overwriting a fresh <=10m fix with a less accurate estimate (>10m)
            if (incomingAcc > 10.0f && existingAcc <= 10.0f && ageDeltaMs in 0..8_000L) {
                return
            }

            // 2. Long-term guard (3 min) against coarse non-satellite cell-tower jumps (> 25 m)
            if (incomingAcc > 25.0f && existingAcc <= 22.0f && ageDeltaMs in 0..180_000L) {
                return
            }
        }

        val enrichedPoint = if (_currentAltitudeMeters.value != null) {
            point.copy(altitudeMeters = _currentAltitudeMeters.value)
        } else {
            point
        }

        // Update live map cursor for every valid satellite fix (including forest canopy up to 25 m)
        if (incomingAcc <= 25.0f || _currentLocation.value == null) {
            _currentLocation.value = enrichedPoint
        }

        // Check deviation from the planned GPX route (> 60 meters from nearest route segment = off-route)
        val route = _plannedRoutePoints.value
        if (route.isNotEmpty() && incomingAcc <= 25.0f) {
            val minDistanceToRoute = calculateMinDistanceToRouteMeters(
                enrichedPoint.latitude,
                enrichedPoint.longitude,
                route
            )
            _isOffRoute.value = minDistanceToRoute > 60.0
        } else if (route.isEmpty()) {
            _isOffRoute.value = false
        }

        if (!_isTracking.value) return

        // Accept satellite fixes up to 22 m into the recorded GPX track (covers dense forest and deep valleys)
        if (incomingAcc > 22.0f) {
            return
        }

        val previous = _trackPoints.value.lastOrNull()
        if (previous != null) {
            val delta = calculateDistanceMeters(
                previous.latitude, previous.longitude,
                enrichedPoint.latitude, enrichedPoint.longitude
            )
            // Filter stationary GPS jitter (< 1.5 meters)
            if (delta < 1.5) return
            _totalDistanceMeters.value += delta
        }

        _trackPoints.value = _trackPoints.value + enrichedPoint
    }

    /**
     * Resets all tracking state for a new hike.
     */
    fun clearTrack() {
        _isTracking.value = false
        _trackPoints.value = emptyList()
        _totalDistanceMeters.value = 0.0
        _totalAscentMeters.value = 0.0
        _currentAltitudeMeters.value = null
        referenceAltitudeForAscent = null
        _recordingStartMillis.value = 0L
        _currentLocation.value = null
        _lastSavedSummary.value = null
        _plannedRouteName.value = null
        _plannedRoutePoints.value = emptyList()
        _isOffRoute.value = false
    }

    /**
     * Computes the perpendicular distance from point [lat], [lon] to polyline [route] (Point-to-Segment Cross-Track Distance).
     * Prevents false "Off route" alerts on long straight forest segments between two GPX vertices.
     */
    fun calculateMinDistanceToRouteMeters(
        lat: Double,
        lon: Double,
        route: List<TrackPoint>
    ): Double {
        if (route.isEmpty()) return Double.MAX_VALUE
        if (route.size == 1) {
            return calculateDistanceMeters(lat, lon, route[0].latitude, route[0].longitude)
        }
        var minDist = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            val d = calculateDistanceToSegmentMeters(lat, lon, a.latitude, a.longitude, b.latitude, b.longitude)
            if (d < minDist) minDist = d
        }
        return minDist
    }

    /**
     * Distance from point P(lat, lon) to line segment AB in local planar projection (in meters).
     */
    fun calculateDistanceToSegmentMeters(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Double {
        val cosLat = cos(Math.toRadians(pLat))
        val metersPerDegLat = 111132.0
        val metersPerDegLon = 111319.5 * cosLat

        val ax = (aLon - pLon) * metersPerDegLon
        val ay = (aLat - pLat) * metersPerDegLat
        val bx = (bLon - pLon) * metersPerDegLon
        val by = (bLat - pLat) * metersPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6) {
            return hypot(ax, ay)
        }
        // Projection of origin P(0,0) onto segment AB: t = -(A · (B - A)) / |B - A|^2
        val t = (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return hypot(projX, projY)
    }

    /**
     * Haversine formula for great-circle distance on the Earth's ellipsoid (in meters).
     */
    fun calculateDistanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadiusMeters = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }

    /**
     * Computes forward azimuth (0..360°) from (lat1, lon1) to (lat2, lon2).
     */
    fun calculateBearingDegrees(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val theta = Math.toDegrees(atan2(y, x))
        return ((theta + 360.0) % 360.0).toFloat()
    }

    /**
     * Computes remaining distance (km) and remaining positive ascent (m) along the active planned GPX route
     * from the vertex closest to [currentLat], [currentLon] to the finish.
     */
    fun calculateRemainingRouteStats(currentLat: Double, currentLon: Double): Pair<Double, Int>? {
        val route = _plannedRoutePoints.value
        if (route.size < 2) return null

        var closestIdx = 0
        var minDist = Double.MAX_VALUE
        for (i in route.indices) {
            val d = calculateDistanceMeters(currentLat, currentLon, route[i].latitude, route[i].longitude)
            if (d < minDist) {
                minDist = d
                closestIdx = i
            }
        }

        var remMeters = 0.0
        var remAscent = 0.0
        for (i in closestIdx until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            remMeters += calculateDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val altA = a.altitudeMeters
            val altB = b.altitudeMeters
            if (altA != null && altB != null && altB > altA) {
                remAscent += (altB - altA)
            }
        }
        return (remMeters / 1000.0) to remAscent.roundToInt()
    }
}
