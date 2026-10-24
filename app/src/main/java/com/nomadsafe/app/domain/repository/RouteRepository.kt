package com.nomadsafe.app.domain.repository

import com.nomadsafe.app.core.result.NsResult
import com.nomadsafe.app.domain.model.CandidateRoute
import com.nomadsafe.app.domain.model.GeoPoint

/** Modo de desplazamiento. El MVP es peatonal, que es donde la seguridad importa más. */
enum class TravelMode(val wireName: String) {
    WALKING("walking"),
    CYCLING("cycling"),
    DRIVING("driving"),
}

/**
 * Proveedor de rutas CANDIDATAS.
 *
 * Separación deliberada y central en la arquitectura:
 *
 * Este repositorio devuelve rutas por tiempo y distancia — lo que sabe hacer
 * cualquier engine de routing. NO sabe nada de seguridad. Todo el criterio de
 * riesgo vive en `RiskEngine`, que es código propio.
 *
 * La consecuencia práctica es que NomadSafe no está atado a Mapbox: se puede
 * cambiar a OSRM o Valhalla autohospedado (o agregar un segundo proveedor y
 * mezclar candidatos) sin tocar una sola línea del motor de riesgo. El
 * diferencial del producto queda en casa, no en el proveedor.
 */
interface RouteRepository {

    /**
     * Rutas alternativas entre [origin] y [destination].
     *
     * Debe devolver VARIAS opciones, no una. Si devuelve sólo la más rápida, el
     * motor de riesgo no tiene con qué comparar y la "ruta segura" sería
     * exactamente la misma que da Google Maps, que es lo que no queremos.
     *
     * [alternatives] es la cantidad deseada; el proveedor puede devolver menos.
     */
    suspend fun getCandidateRoutes(
        origin: GeoPoint,
        destination: GeoPoint,
        mode: TravelMode = TravelMode.WALKING,
        alternatives: Int = 3,
    ): NsResult<List<CandidateRoute>>

    /**
     * Enriquece los tramos con features estáticas de OSM (iluminación, tipo de vía).
     *
     * Va separado de [getCandidateRoutes] porque son dos fuentes de datos
     * distintas con latencias distintas: el routing viene del proveedor y las
     * features de nuestra propia tabla PostGIS, cacheada en el dispositivo.
     */
    suspend fun enrichWithStaticFeatures(routes: List<CandidateRoute>): NsResult<List<CandidateRoute>>

    /** Geocodificación inversa para mostrar "Jr. Pizarro 450" en vez de coordenadas. */
    suspend fun reverseGeocode(point: GeoPoint): NsResult<String>

    /** Búsqueda de destino por texto, con sesgo geográfico hacia [bias]. */
    suspend fun searchDestination(query: String, bias: GeoPoint?): NsResult<List<DestinationSuggestion>>
}

/** Sugerencia de destino del buscador. */
data class DestinationSuggestion(
    val name: String,
    val address: String?,
    val location: GeoPoint,
    val distanceFromBiasMeters: Double?,
)
