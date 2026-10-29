package cz.offgrid.maps.data.tracking

import cz.offgrid.maps.domain.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Centrální správce stavu nahrávání trasy (Reaktivní StateFlow).
 *
 * Architektura:
 * - Umožňuje sdílet aktuální data o trase mezi běžící Background službou a Compose UI.
 * - Provádí výpočty vzdálenosti pomocí Haversinova vzorce (čistá matematika, nezávislá na Android frameworku pro snadné testování).
 */
object TrackingManager {

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _trackPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val trackPoints: StateFlow<List<TrackPoint>> = _trackPoints.asStateFlow()

    private val _totalDistanceMeters = MutableStateFlow(0.0)
    val totalDistanceMeters: StateFlow<Double> = _totalDistanceMeters.asStateFlow()

    private val _currentLocation = MutableStateFlow<TrackPoint?>(null)
    val currentLocation: StateFlow<TrackPoint?> = _currentLocation.asStateFlow()

    fun setTrackingActive(active: Boolean) {
        _isTracking.value = active
    }

    /**
     * Přidá nový bod trasy a přičte vzdálenost od předchozího bodu.
     */
    fun addPoint(point: TrackPoint) {
        _currentLocation.value = point

        if (!_isTracking.value) return

        val previous = _trackPoints.value.lastOrNull()
        if (previous != null) {
            val delta = calculateDistanceMeters(
                previous.latitude, previous.longitude,
                point.latitude, point.longitude
            )
            _totalDistanceMeters.value += delta
        }

        _trackPoints.value = _trackPoints.value + point
    }

    /**
     * Vyčistí záznam pro novou trasu.
     */
    fun clearTrack() {
        _trackPoints.value = emptyList()
        _totalDistanceMeters.value = 0.0
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
