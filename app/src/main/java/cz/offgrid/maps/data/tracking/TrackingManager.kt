package cz.offgrid.maps.data.tracking

import cz.offgrid.maps.domain.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Centrální správce stavu nahrávání trasy (Reaktivní StateFlow).
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

    private val _currentLocation = MutableStateFlow<TrackPoint?>(null)
    val currentLocation: StateFlow<TrackPoint?> = _currentLocation.asStateFlow()

    private val _lastSavedSummary = MutableStateFlow<String?>(null)
    val lastSavedSummary: StateFlow<String?> = _lastSavedSummary.asStateFlow()

    fun setTrackingActive(active: Boolean) {
        if (active && !_isTracking.value) {
            // Zahajujeme novou trasu
            _trackPoints.value = emptyList()
            _totalDistanceMeters.value = 0.0
            _recordingStartMillis.value = System.currentTimeMillis()
            _lastSavedSummary.value = null
            _isTracking.value = true

            // Pokud už máme aktuální GPS polohu, hned ji vložíme jako 1. bod trasy
            _currentLocation.value?.let { startPt ->
                _trackPoints.value = listOf(startPt.copy(timestampMillis = System.currentTimeMillis()))
            }
        } else if (!active) {
            _isTracking.value = false
        }
    }

    fun setSavedSummary(summary: String?) {
        _lastSavedSummary.value = summary
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
            // Odfiltrujeme GPS šum na místě (< 1.5 metru), ale zaznamenáme reálnou chůzi
            if (delta < 1.5) return
            _totalDistanceMeters.value += delta
        }

        _trackPoints.value = _trackPoints.value + point
    }

    /**
     * Vyčistí záznam pro novou trasu.
     */
    fun clearTrack() {
        _isTracking.value = false
        _trackPoints.value = emptyList()
        _totalDistanceMeters.value = 0.0
        _recordingStartMillis.value = 0L
        _currentLocation.value = null
        _lastSavedSummary.value = null
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
