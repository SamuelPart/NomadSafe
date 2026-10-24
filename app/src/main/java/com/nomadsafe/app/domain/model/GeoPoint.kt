package com.nomadsafe.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Coordenada WGS84.
 *
 * Invariante validada en el constructor: una latitud fuera de rango no es un
 * dato "raro", es un bug de parsing que corrompería el mapa de calor de toda
 * la ciudad. Es mejor fallar acá, cerca del origen, que descubrir un heatmap
 * vacío tres capas más arriba.
 */
@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitud fuera de rango: $latitude" }
        require(longitude in -180.0..180.0) { "Longitud fuera de rango: $longitude" }
    }

    /** (0,0) es un valor que el GPS devuelve al inicializar; nunca es una ubicación real. */
    val isPlausible: Boolean
        get() = !(latitude == 0.0 && longitude == 0.0)

    override fun toString(): String = "GeoPoint($latitude, $longitude)"

    companion object {
        /** Centro del piloto (Trujillo, Perú). Se sobreescribe vía BuildConfig. */
        val PILOT_FALLBACK = GeoPoint(latitude = -8.1116, longitude = -79.0289)
    }
}
