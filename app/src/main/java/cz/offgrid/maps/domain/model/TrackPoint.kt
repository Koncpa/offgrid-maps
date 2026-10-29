package cz.offgrid.maps.domain.model

/**
 * Neměnný datový model pro zaznamenaný bod trasy.
 *
 * Šetrný k paměti: obsahuje pouze nezbytná telemetrická data pro navigaci a GPX export.
 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val accuracyMeters: Float = 0f,
    val speedMps: Float? = null
)
