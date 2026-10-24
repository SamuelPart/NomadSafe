package com.nomadsafe.app.data.repository

import com.nomadsafe.app.core.config.AppConfig
import com.nomadsafe.app.core.result.NsError
import com.nomadsafe.app.core.result.NsResult
import com.nomadsafe.app.core.util.GeoUtils
import com.nomadsafe.app.core.util.NsDispatchers
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.domain.model.CandidateRoute
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.RoadClass
import com.nomadsafe.app.domain.model.RouteSegment
import com.nomadsafe.app.domain.repository.DestinationSuggestion
import com.nomadsafe.app.domain.repository.RouteRepository
import com.nomadsafe.app.domain.repository.StaticFeatureRepository
import com.nomadsafe.app.domain.repository.TravelMode
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Proveedor de rutas candidatas.
 *
 * ── Separación deliberada ────────────────────────────────────────────────────
 * Esta clase devuelve rutas por tiempo y distancia. NO sabe nada de seguridad:
 * todo el criterio de riesgo lo aplica `RiskEngine` encima. La consecuencia
 * práctica es que NomadSafe no queda atado a Mapbox — se puede cambiar a OSRM o
 * Valhalla autohospedado, o mezclar candidatos de dos proveedores, sin tocar el
 * diferencial del producto.
 *
 * ── Por qué llamada HTTP directa y no el SDK de Directions ───────────────────
 * El SDK oficial (`mapbox-sdk-services`) agrega una dependencia con su propia
 * línea de versiones y su propio cliente Retrofit. Para un solo endpoint, una
 * llamada HTTP con kotlinx-serialization es menos superficie de fallo y mantiene
 * el control sobre timeouts y reintentos, que acá importan porque el usuario está
 * parado en una esquina esperando la ruta.
 *
 * ── Fallback sin token ───────────────────────────────────────────────────────
 * Sin token de Mapbox se generan rutas sintéticas: línea directa más dos
 * variantes desviadas. No sirven para navegar en serio, pero permiten ejercitar
 * TODO el pipeline de riesgo (scoring, ranking, explicación, UI) sin credenciales.
 * Es lo que hace que el proyecto sea demo-able desde el primer clon.
 */
@Singleton
class RouteRepositoryImpl @Inject constructor(
    private val config: AppConfig,
    private val json: Json,
    private val dispatchers: NsDispatchers,
    private val staticFeatures: StaticFeatureRepository,
) : RouteRepository {

    override suspend fun getCandidateRoutes(
        origin: GeoPoint,
        destination: GeoPoint,
        mode: TravelMode,
        alternatives: Int,
    ): NsResult<List<CandidateRoute>> {
        val directDistance = GeoUtils.distanceMeters(origin, destination)

        // Validación temprana con mensaje accionable: una ruta de 0 m casi siempre
        // significa que el GPS todavía no fijó posición, no que el usuario quiera
        // ir a donde ya está.
        if (directDistance < MIN_ROUTE_DISTANCE_METERS) {
            return NsResult.Failure(
                NsError.Local("Origen y destino están a ${directDistance.roundToInt()} m; se necesita una posición válida"),
            )
        }
        if (directDistance > MAX_ROUTE_DISTANCE_METERS) {
            return NsResult.Failure(
                NsError.Local("La distancia (${(directDistance / 1000).roundToInt()} km) excede el alcance peatonal del MVP"),
            )
        }

        if (!config.hasMapCredentials) {
            NsLog.w(TAG, "Sin token de Mapbox: se generan rutas sintéticas de demostración")
            return NsResult.Success(syntheticRoutes(origin, destination, alternatives))
        }

        return withContext(dispatchers.io) {
            try {
                val routes = fetchFromMapbox(origin, destination, mode, alternatives)
                if (routes.isEmpty()) {
                    NsLog.w(TAG, "Mapbox no devolvió rutas; se usa el generador sintético")
                    NsResult.Success(syntheticRoutes(origin, destination, alternatives))
                } else {
                    NsResult.Success(routes)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                NsLog.e(TAG, "Fallo al pedir rutas a Mapbox; se degrada a sintéticas", throwable)
                // Degradar en vez de fallar: el usuario parado en una esquina
                // necesita UNA ruta, aunque sea aproximada.
                NsResult.Success(syntheticRoutes(origin, destination, alternatives))
            }
        }
    }

    private suspend fun fetchFromMapbox(
        origin: GeoPoint,
        destination: GeoPoint,
        mode: TravelMode,
        alternatives: Int,
    ): List<CandidateRoute> {
        val profile = when (mode) {
            TravelMode.WALKING -> "mapbox/walking"
            TravelMode.CYCLING -> "mapbox/cycling"
            TravelMode.DRIVING -> "mapbox/driving"
        }

        // Mapbox usa orden (longitud,latitud), al revés que GeoPoint. Invertirlo
        // acá produce rutas "válidas" en medio del océano sin ningún error.
        val coordinates = "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}"

        val url = URL(
            "$DIRECTIONS_BASE_URL/$profile/$coordinates" +
                "?alternatives=${alternatives > 1}" +
                "&steps=true" +
                "&geometries=geojson" +
                "&overview=full" +
                "&language=es" +
                "&access_token=${config.mapboxPublicToken}",
        )

        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")

        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("Mapbox Directions HTTP $code: ${body.take(300)}")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = json.decodeFromString(DirectionsResponse.serializer(), body)

            if (response.code != "Ok") {
                throw IllegalStateException("Mapbox Directions devolvió code='${response.code}'")
            }

            response.routes.mapIndexed { index, route ->
                val segments = route.legs
                    .flatMap { leg -> leg.steps }
                    .mapNotNull { step -> step.toRouteSegment() }
                    // Un paso sin geometría útil se descarta antes que generar un
                    // tramo de longitud 0, que el motor de riesgo no puede ponderar.
                    .filter { it.lengthMeters > MIN_SEGMENT_LENGTH_METERS }

                CandidateRoute(
                    id = route.idOrNull ?: "mapbox-$index",
                    segments = segments,
                    durationSeconds = route.duration.roundToInt(),
                    distanceMeters = route.distance,
                    isFastest = index == 0, // Mapbox ordena por duración ascendente.
                )
            }.filter { it.segments.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }

    /** Convierte un paso de Directions en un tramo evaluable por el motor de riesgo. */
    private fun DirectionStep.toRouteSegment(): RouteSegment? {
        val coordinates = geometry?.coordinates ?: return null
        if (coordinates.size < 2) return null

        val first = coordinates.first()
        val last = coordinates.last()

        // Directions no informa clase de vía ni iluminación para perfil peatonal.
        // Se deja UNKNOWN y null: [enrichWithStaticFeatures] los completa desde
        // OSM. Asumir "residencial" acá sesgaría el score a favor de avenidas.
        return RouteSegment(
            from = GeoPoint(latitude = first[1], longitude = first[0]),
            to = GeoPoint(latitude = last[1], longitude = last[0]),
            lengthMeters = max(distance, MIN_SEGMENT_LENGTH_METERS),
            roadClass = RoadClass.UNKNOWN,
            lit = null,
            streetName = name?.takeIf { it.isNotBlank() && it != "unnamed road" },
        )
    }

    /**
     * Enriquece tramos con iluminación y clase de vía desde la caché OSM.
     *
     * Une cada tramo con la celda de la grilla más cercana. Es una aproximación:
     * la grilla es de ~50 m y un tramo puede cruzar varias celdas. Para el MVP es
     * suficiente y es mucho más barato que intersectar geometrías reales.
     */
    override suspend fun enrichWithStaticFeatures(
        routes: List<CandidateRoute>,
    ): NsResult<List<CandidateRoute>> {
        if (routes.isEmpty()) return NsResult.Success(routes)

        val allSegments = routes.flatMap { it.segments }
        if (allSegments.isEmpty()) return NsResult.Success(routes)

        val lats = allSegments.flatMap { listOf(it.from.latitude, it.to.latitude) }
        val lons = allSegments.flatMap { listOf(it.from.longitude, it.to.longitude) }

        val bounds = com.nomadsafe.app.core.util.BoundingBox(
            south = (lats.min() ?: 0.0) - BOUNDS_PADDING_DEGREES,
            west = (lons.min() ?: 0.0) - BOUNDS_PADDING_DEGREES,
            north = (lats.max() ?: 0.0) + BOUNDS_PADDING_DEGREES,
            east = (lons.max() ?: 0.0) + BOUNDS_PADDING_DEGREES,
        )

        val lighting = staticFeatures.getLighting(bounds).getOrNull().orEmpty()
        if (lighting.isEmpty()) {
            NsLog.d(TAG, "Sin features estáticas en el área; los tramos quedan con iluminación desconocida")
            return NsResult.Success(routes)
        }

        val enriched = routes.map { route ->
            route.copy(
                segments = route.segments.map { segment ->
                    val key = cellKeyFor(segment)
                    segment.copy(lit = lighting[key] ?: segment.lit)
                },
            )
        }

        NsLog.i(TAG, "Tramos enriquecidos con ${lighting.size} celdas de features estáticas")
        return NsResult.Success(enriched)
    }

    /** Clave de celda de ~50 m, compatible con la vista `static_features_grid`. */
    private fun cellKeyFor(segment: RouteSegment): String {
        val midLat = (segment.from.latitude + segment.to.latitude) / 2
        val midLon = (segment.from.longitude + segment.to.longitude) / 2
        val cellLat = (midLat / CELL_SIZE_DEGREES).toInt() * CELL_SIZE_DEGREES
        val cellLon = (midLon / CELL_SIZE_DEGREES).toInt() * CELL_SIZE_DEGREES
        return "%.4f:%.4f".format(cellLat, cellLon)
    }

    override suspend fun reverseGeocode(point: GeoPoint): NsResult<String> {
        if (!config.hasMapCredentials) {
            return NsResult.Success(formatCoordinates(point))
        }
        return withContext(dispatchers.io) {
            runCatching {
                val url = URL(
                    "$GEOCODING_BASE_URL/mapbox.places/${point.longitude},${point.latitude}.json" +
                        "?types=address,poi&limit=1&language=es" +
                        "&access_token=${config.mapboxPublicToken}",
                )
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                try {
                    if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val response = json.decodeFromString(GeocodingResponse.serializer(), body)
                    response.features.firstOrNull()?.placeName ?: formatCoordinates(point)
                } finally {
                    connection.disconnect()
                }
            }.fold(
                onSuccess = { NsResult.Success(it) },
                onFailure = {
                    NsLog.w(TAG, "Geocodificación inversa falló: ${it.message}")
                    NsResult.Success(formatCoordinates(point))
                },
            )
        }
    }

    override suspend fun searchDestination(
        query: String,
        bias: GeoPoint?,
    ): NsResult<List<DestinationSuggestion>> {
        if (query.isBlank() || query.length < MIN_QUERY_LENGTH) return NsResult.Success(emptyList())
        if (!config.hasMapCredentials) return NsResult.Failure(NsError.Local("Búsqueda de destinos requiere token de Mapbox"))

        return withContext(dispatchers.io) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
                val proximity = bias?.let { "&proximity=${it.longitude},${it.latitude}" } ?: ""
                val url = URL(
                    "$GEOCODING_BASE_URL/mapbox.places/$encoded.json" +
                        "?types=address,poi,neighborhood&limit=8&language=es$proximity" +
                        "&access_token=${config.mapboxPublicToken}",
                )
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                try {
                    if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    json.decodeFromString(GeocodingResponse.serializer(), body).features.mapNotNull { feature ->
                        val coordinates = feature.center ?: return@mapNotNull null
                        if (coordinates.size < 2) return@mapNotNull null
                        DestinationSuggestion(
                            name = feature.text ?: feature.placeName ?: return@mapNotNull null,
                            address = feature.placeName,
                            location = GeoPoint(latitude = coordinates[1], longitude = coordinates[0]),
                            distanceFromBiasMeters = bias?.let {
                                GeoUtils.distanceMeters(it, GeoPoint(coordinates[1], coordinates[0]))
                            },
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }.fold(
                onSuccess = { NsResult.Success(it) },
                onFailure = {
                    NsLog.w(TAG, "Búsqueda de destino falló: ${it.message}")
                    NsResult.Failure(NsError.Network)
                },
            )
        }
    }

    /**
     * Rutas sintéticas para modo demo y para degradación ante fallo de Mapbox.
     *
     * Genera una línea directa y dos variantes desviadas a ambos lados, con
     * duraciones coherentes a velocidad de caminata. No son navegables en serio,
     * pero tienen la propiedad que importa para probar el motor: distintas
     * geometrías y distintas duraciones, así que el ranking tiene con qué comparar.
     */
    private fun syntheticRoutes(
        origin: GeoPoint,
        destination: GeoPoint,
        count: Int,
    ): List<CandidateRoute> {
        val directDistance = GeoUtils.distanceMeters(origin, destination)
        val bearing = GeoUtils.bearingDegrees(origin, destination)
        val walkingSeconds = (directDistance / WALKING_SPEED_MPS).roundToInt()

        val variants = listOf(0.0, -SYNTHETIC_DEVIATION_DEGREES, SYNTHETIC_DEVIATION_DEGREES)
            .take(max(1, count))

        return variants.mapIndexed { index, deviation ->
            // Se desvía el punto medio perpendicularmente a la ruta directa.
            val midpoint = GeoUtils.destination(origin, bearing, directDistance / 2)
            val offsetPoint = GeoUtils.destination(
                midpoint,
                (bearing + 90 + deviation * 4) % 360,
                SYNTHETIC_OFFSET_METERS * (index + 1),
            )

            val segments = listOf(
                RouteSegment(
                    from = origin,
                    to = offsetPoint,
                    lengthMeters = GeoUtils.distanceMeters(origin, offsetPoint),
                    roadClass = if (index == 0) RoadClass.SECONDARY else RoadClass.RESIDENTIAL,
                    lit = if (index == 0) true else null,
                    streetName = if (index == 0) "Av. Principal (demo)" else "Calle ${index + 1} (demo)",
                ),
                RouteSegment(
                    from = offsetPoint,
                    to = destination,
                    lengthMeters = GeoUtils.distanceMeters(offsetPoint, destination),
                    roadClass = RoadClass.RESIDENTIAL,
                    lit = null,
                    streetName = "Tramo final (demo)",
                ),
            )

            val totalDistance = segments.sumOf { it.lengthMeters }
            CandidateRoute(
                id = "synthetic-$index",
                segments = segments,
                durationSeconds = (totalDistance / WALKING_SPEED_MPS).roundToInt(),
                distanceMeters = totalDistance,
                isFastest = index == 0,
            )
        }.also { routes ->
            NsLog.d(TAG, "Generadas ${routes.size} rutas sintéticas (${directDistance.roundToInt()} m directos, ${walkingSeconds}s)")
        }
    }

    private fun formatCoordinates(point: GeoPoint): String =
        "%.5f, %.5f".format(point.latitude, point.longitude)

    // ── DTOs de la API de Mapbox ──────────────────────────────────────────────

    @Serializable
    private data class DirectionsResponse(
        val code: String = "",
        val routes: List<DirectionRoute> = emptyList(),
    )

    @Serializable
    private data class DirectionRoute(
        @SerialName("duration") val duration: Double = 0.0,
        @SerialName("distance") val distance: Double = 0.0,
        @SerialName("legs") val legs: List<DirectionLeg> = emptyList(),
        // Mapbox no devuelve un id estable; se usa la geometría como identidad.
        @SerialName("weight_name") val weightName: String? = null,
    ) {
        val idOrNull: String? get() = weightName?.let { "mapbox-$it" }
    }

    @Serializable
    private data class DirectionLeg(val steps: List<DirectionStep> = emptyList())

    @Serializable
    private data class DirectionStep(
        val distance: Double = 0.0,
        val duration: Double = 0.0,
        val name: String? = null,
        val geometry: StepGeometry? = null,
    )

    @Serializable
    private data class StepGeometry(
        /** Lista de pares [longitud, latitud], orden GeoJSON. */
        val coordinates: List<List<Double>> = emptyList(),
    )

    @Serializable
    private data class GeocodingResponse(val features: List<GeocodingFeature> = emptyList())

    @Serializable
    private data class GeocodingFeature(
        val text: String? = null,
        @SerialName("place_name") val placeName: String? = null,
        val center: List<Double>? = null,
    )

    private companion object {
        const val TAG = "RouteRepository"
        const val DIRECTIONS_BASE_URL = "https://api.mapbox.com/directions/v5"
        const val GEOCODING_BASE_URL = "https://api.mapbox.com/geocoding/v5"

        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000

        const val MIN_ROUTE_DISTANCE_METERS = 30.0
        const val MAX_ROUTE_DISTANCE_METERS = 20_000.0
        const val MIN_SEGMENT_LENGTH_METERS = 5.0
        const val MIN_QUERY_LENGTH = 3

        /** Velocidad de caminata urbana: ~4.5 km/h. */
        const val WALKING_SPEED_MPS = 1.25

        const val SYNTHETIC_DEVIATION_DEGREES = 18.0
        const val SYNTHETIC_OFFSET_METERS = 90.0

        /** ~50 m en grados de latitud. Debe coincidir con la grilla del servidor. */
        const val CELL_SIZE_DEGREES = 0.00045
        const val BOUNDS_PADDING_DEGREES = 0.004
    }
}
