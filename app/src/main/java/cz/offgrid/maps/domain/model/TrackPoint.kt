package cz.offgrid.maps.domain.model

/**
 * Immutable data model representing a recorded or planned GPS track point.
 *
 * Memory-efficient: holds only the essential telemetry required for navigation and GPX export.
 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val accuracyMeters: Float = 0f,
    val speedMps: Float? = null
)
