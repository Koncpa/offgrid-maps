package cz.offgrid.maps.data.tracking

import cz.offgrid.maps.domain.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Centrální správce stavu nahrávání trasy, barometrického převýšení a GPX navigace.
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

    // Načtená GPX trasa pro navigaci po mapě
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
     * Aktualizuje nadmořskou výšku (z barometru nebo GPS fallbacku) a s hysterezí
     * započítává nastoupané výškové metry během aktivního záznamu.
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

        // Hysterezní práh: 3 m pro přesný barometr, 7 m pro GPS fallback (odfiltruje šum)
        val threshold = if (isBarometric) 3.0 else 7.0
        val diff = altitudeMeters - ref
        if (diff >= threshold) {
            _totalAscentMeters.value += diff
            referenceAltitudeForAscent = altitudeMeters
        } else if (diff <= -threshold) {
            // Při klesání posuneme referenční hladinu dolů, abychom měřili další výstup
            referenceAltitudeForAscent = altitudeMeters
        }
    }

    /**
     * Nastaví nebo vyčistí naplánovanou GPX trasu pro navigaci na mapě.
     */
    fun setPlannedRoute(name: String?, points: List<TrackPoint>) {
        _plannedRouteName.value = name
        _plannedRoutePoints.value = points
        _isOffRoute.value = false
    }

    /**
     * Přidá nový bod trasy, přičte vzdálenost a zkontroluje odchylku od naplánované GPX trasy.
     * Chrání přesnou polohu před skoky na BTS/Wi-Fi, ale v lese a hlubokých údolích plynule
     * přijímá reálné satelitní body (do 22 m), aby při aktivním treku nezamrzala šipka ani tlačítko GPS.
     */
    fun addPoint(point: TrackPoint) {
        val incomingAcc = point.accuracyMeters
        val existingLoc = _currentLocation.value

        if (existingLoc != null) {
            val existingAcc = existingLoc.accuracyMeters
            val ageDeltaMs = point.timestampMillis - existingLoc.timestampMillis

            // 1. Krátkodobá ochrana (do 8 s) proti okamžitému přepsání čerstvého <=10m fixu horším odhadem (>10m)
            if (incomingAcc > 10.0f && existingAcc <= 10.0f && ageDeltaMs in 0..8_000L) {
                return
            }

            // 2. Dlouhodobá ochrana (do 3 min) proti hrubým nesatelitním skokům z BTS vysílačů (> 25 m)
            if (incomingAcc > 25.0f && existingAcc <= 22.0f && ageDeltaMs in 0..180_000L) {
                return
            }
        }

        val enrichedPoint = if (_currentAltitudeMeters.value != null) {
            point.copy(altitudeMeters = _currentAltitudeMeters.value)
        } else {
            point
        }

        // Šipku aktuální polohy na mapě aktualizujeme pro každý validní satelitní fix (včetně lesa do 25 m),
        // a to i během aktivního nahrávání treku, aby tlačítko GPS reagovalo okamžitě.
        if (incomingAcc <= 25.0f || _currentLocation.value == null) {
            _currentLocation.value = enrichedPoint
        }

        // Kontrola odchylky od naplánované GPX trasy (> 60 metrů od nejbližšího úseku trasy = mimo trasu)
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

        // Do ukládaného GPX záznamu trasy pouštíme satelitní body do 22 m (pokryje i hustý les a skalní údolí)
        if (incomingAcc > 22.0f) {
            return
        }

        val previous = _trackPoints.value.lastOrNull()
        if (previous != null) {
            val delta = calculateDistanceMeters(
                previous.latitude, previous.longitude,
                enrichedPoint.latitude, enrichedPoint.longitude
            )
            // Odfiltrujeme GPS šum na místě (< 1.5 metru)
            if (delta < 1.5) return
            _totalDistanceMeters.value += delta
        }

        _trackPoints.value = _trackPoints.value + enrichedPoint
    }

    /**
     * Vyčistí záznam pro novou trasu.
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
     * Spočítá kolmou vzdálenost bodu [lat], [lon] od lomené čáry [route] (Point-to-Segment Cross-Track Distance).
     * Zabraňuje falešným poplachům "Mimo trasu" na dlouhých rovných lesních úsecích mezi dvěma body GPX.
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
     * Vzdálenost bodu P(lat, lon) od úsečky AB v lokální rovinné projekci (v metrech).
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
        // Projekce počátku P(0,0) na úsečku AB: t = -(A · (B - A)) / |B - A|^2
        val t = (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return hypot(projX, projY)
    }

    /**
     * Haversinův vzorec pro výpočet vzdálenosti na zemském elipsoidu (v metrech).
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
}
