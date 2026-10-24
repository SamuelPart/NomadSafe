package com.nomadsafe.app.feature.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nomadsafe.app.core.config.AppConfig
import com.nomadsafe.app.core.location.LocationSource
import com.nomadsafe.app.core.result.NsResult
import com.nomadsafe.app.core.util.GeoUtils
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.domain.model.CandidateRoute
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.RoutePreference
import com.nomadsafe.app.domain.model.SafeRoute
import com.nomadsafe.app.domain.repository.DestinationSuggestion
import com.nomadsafe.app.domain.repository.IncidentRepository
import com.nomadsafe.app.domain.repository.RouteRepository
import com.nomadsafe.app.domain.repository.TravelMode
import com.nomadsafe.app.feature.risk.RiskEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.days

data class RouteUiState(
    val origin: GeoPoint? = null,
    val originLabel: String? = null,
    val destination: DestinationSuggestion? = null,
    val suggestions: List<DestinationSuggestion> = emptyList(),
    val routes: List<SafeRoute> = emptyList(),
    val recommendedRouteId: String? = null,
    val selectedRouteId: String? = null,
    val preference: RoutePreference = RoutePreference.BALANCED,
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val error: String? = null,
) {
    /** True cuando ya hay rutas y sólo falta re-ordenarlas por preferencia. */
    val canRerankLocally: Boolean get() = candidates.isNotEmpty()

    /**
     * Candidatas crudas, conservadas para re-rankear sin red.
     *
     * No se exponen a la UI: son el insumo interno que permite cambiar de
     * preferencia sin volver a llamar al proveedor de routing.
     */
    val candidates: List<CandidateRoute> = emptyList()
}

/**
 * ViewModel de Rutas Seguras.
 *
 * ── La separación que define esta pantalla ────────────────────────────────────
 * [RouteRepository] devuelve candidatas por tiempo y distancia; NO sabe nada de
 * seguridad. [RiskEngine] puntúa el riesgo; no sabe nada de routing. Este
 * ViewModel es el único lugar donde se juntan, y su trabajo es precisamente ese
 * ensamblaje: pedir candidatas, enriquecerlas con iluminación/tipo de vía, traer
 * los incidentes del corredor y dejar que el motor las ordene.
 *
 * Mantener la unión acá y no dentro del repositorio es lo que permite cambiar de
 * proveedor de routing (Mapbox → OSRM propio) sin tocar una sola línea del cálculo
 * de riesgo.
 *
 * ── Cambiar de preferencia no usa la red ──────────────────────────────────────
 * Las candidatas se conservan en el estado. Al cambiar de SAFEST a FASTEST se
 * re-ordena localmente con [RiskEngine.rank] en vez de volver a pedir rutas.
 *
 * No es sólo una optimización de cuota: el usuario compara preferencias tocando
 * chips, y si cada toque hiciera una petición HTTP de 1-3 s la comparación se
 * sentiría rota. Re-ordenar en memoria es instantáneo, que es lo que hace que la
 * gente explore qué sacrifica cada opción.
 */
@HiltViewModel
class RouteViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
    private val incidentRepository: IncidentRepository,
    private val riskEngine: RiskEngine,
    private val locationSource: LocationSource,
    private val appConfig: AppConfig,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteUiState())
    val uiState: StateFlow<RouteUiState> = _uiState.asStateFlow()

    init {
        resolveOrigin()
    }

    /**
     * Origen de la ruta.
     *
     * Se usa la última posición conocida y no un fix nuevo: el usuario quiere la
     * ruta YA, y esperar un fix GPS puede tardar decenas de segundos en interior.
     * Si no hay posición (permiso denegado, primer arranque) se cae al centro del
     * piloto, que mantiene la pantalla utilizable en vez de bloquearla.
     */
    private fun resolveOrigin() {
        viewModelScope.launch {
            val lastKnown = runCatching { locationSource.getLastKnown() }.getOrNull()
            val origin = lastKnown?.point ?: appConfig.pilotCenter

            if (lastKnown == null) {
                NsLog.w(TAG, "Sin posición conocida; se usa el centro del piloto como origen")
            }

            _uiState.value = _uiState.value.copy(origin = origin)

            // Etiqueta legible del origen, en segundo plano: si falla, la ruta se
            // calcula igual. Bloquear el cálculo por no poder resolver "Jr. Pizarro
            // 450" sería priorizar cosmética sobre función.
            launch {
                when (val result = routeRepository.reverseGeocode(origin)) {
                    is NsResult.Success -> _uiState.value = _uiState.value.copy(originLabel = result.data)
                    is NsResult.Failure -> NsLog.d(TAG, "reverseGeocode del origen falló; se omite la etiqueta")
                }
            }
        }
    }

    /** Busca el destino y, si hay un único resultado claro, calcula las rutas. */
    fun searchDestination(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < SEARCH_MIN_LENGTH) {
            _uiState.value = _uiState.value.copy(error = null)
            return
        }

        _uiState.value = _uiState.value.copy(isSearching = true, error = null)

        viewModelScope.launch {
            val origin = _uiState.value.origin ?: appConfig.pilotCenter

            when (val result = routeRepository.searchDestination(trimmed, bias = origin)) {
                is NsResult.Success -> {
                    val suggestions = result.data
                    _uiState.value = _uiState.value.copy(
                        suggestions = suggestions,
                        isSearching = false,
                        // Con un único resultado no hay nada que elegir: se usa
                        // directo. Obligar a confirmar un resultado único es fricción
                        // sin beneficio.
                        destination = suggestions.singleOrNull(),
                        error = if (suggestions.isEmpty()) "No se encontró \"$trimmed\" en la zona del piloto." else null,
                    )

                    if (suggestions.size == 1) calculateRoutes()
                }

                is NsResult.Failure -> _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = describe(result.error),
                )
            }
        }
    }

    fun selectSuggestion(suggestion: DestinationSuggestion) {
        _uiState.value = _uiState.value.copy(destination = suggestion, suggestions = emptyList())
        calculateRoutes()
    }

    /**
     * Cambia la preferencia y re-ordena las candidatas YA descargadas.
     *
     * Sin red: ver la nota de la clase.
     */
    fun setPreference(preference: RoutePreference) {
        val current = _uiState.value
        if (current.preference == preference) return

        _uiState.value = current.copy(preference = preference)

        if (current.candidates.isNotEmpty()) {
            rerank(current.candidates, preference)
        } else {
            calculateRoutes()
        }
    }

    fun selectRoute(routeId: String) {
        _uiState.value = _uiState.value.copy(selectedRouteId = routeId)
    }

    /**
     * Calcula las rutas seguras de origen a destino.
     *
     * Secuencia y por qué cada paso está donde está:
     * 1. Candidatas del proveedor de routing.
     * 2. Enriquecido con iluminación/tipo de vía — va DESPUÉS porque sólo tiene
     *    sentido pedir features de OSM para los tramos que realmente existen.
     *    Hacerlo antes obligaría a consultar la grilla de toda la ciudad.
     * 3. Incidentes del corredor, acotados al bounding box de las candidatas.
     * 4. Ranking por el motor de riesgo.
     */
    fun calculateRoutes() {
        val state = _uiState.value
        val origin = state.origin ?: appConfig.pilotCenter
        val destination = state.destination

        if (destination == null) {
            _uiState.value = state.copy(error = "Elegí un destino primero.")
            return
        }

        _uiState.value = state.copy(isLoading = true, error = null)

        viewModelScope.launch {
            // 1. Candidatas.
            val candidates = when (val result = routeRepository.getCandidateRoutes(
                origin = origin,
                destination = destination.location,
                mode = TravelMode.WALKING,
                alternatives = ALTERNATIVE_COUNT,
            )) {
                is NsResult.Success -> result.data
                is NsResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = describe(result.error))
                    return@launch
                }
            }

            if (candidates.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "No se encontró ninguna ruta caminable hasta ahí.",
                )
                return@launch
            }

            // 2. Enriquecido con features estáticas.
            // Si falla se continúa con lo que hay: saber que una calle está sin
            // luz es mejor que no tener ruta. El repositorio ya degrada a rutas
            // sintéticas cuando no hay token de Mapbox, así que este fallo es poco
            // frecuente y nunca debe bloquear al usuario que está parado en una
            // esquina necesitando saber por dónde ir.
            val enriched = when (val result = routeRepository.enrichWithStaticFeatures(candidates)) {
                is NsResult.Success -> result.data
                is NsResult.Failure -> {
                    NsLog.w(TAG, "enrichWithStaticFeatures falló; se puntúa sin datos de iluminación")
                    candidates
                }
            }

            // 3. Incidentes del corredor.
            val incidents = fetchCorridorIncidents(origin, destination.location, enriched)

            // 4. Ranking.
            _uiState.value = _uiState.value.copy(candidates = enriched)
            rerank(enriched, _uiState.value.preference, incidents)
            _uiState.value = _uiState.value.copy(isLoading = false)

            NsLog.i(TAG, "${enriched.size} rutas calculadas hacia ${destination.name}")
        }
    }

    /**
     * Re-ordena candidatas ya obtenidas.
     *
     * Separa el ranking de la obtención para que cambiar de preferencia no repita
     * los pasos 1-3 (que son los que usan red).
     */
    private fun rerank(
        candidates: List<CandidateRoute>,
        preference: RoutePreference,
        incidents: List<com.nomadsafe.app.domain.model.Incident>? = null,
    ) {
        viewModelScope.launch {
            val resolved = incidents ?: fetchCorridorIncidents(
                _uiState.value.origin ?: appConfig.pilotCenter,
                _uiState.value.destination?.location ?: appConfig.pilotCenter,
                candidates,
            )

            val ranked = riskEngine.rank(candidates, resolved, preference, timeProvider.now())

            _uiState.value = _uiState.value.copy(
                routes = ranked,
                recommendedRouteId = ranked.firstOrNull()?.route?.id,
                // Se conserva la selección del usuario si la ruta sigue existiendo.
                // Resetearla al re-ordenar haría que cambiar de preferencia
                // deselecte lo que estaba mirando, sin motivo.
                selectedRouteId = _uiState.value.selectedRouteId
                    ?.takeIf { id -> ranked.any { it.route.id == id } },
            )
        }
    }

    /**
     * Incidentes que influyen en el corredor.
     *
     * Se acota al bounding box que cubre origen y destino con margen, en vez de
     * traer los incidentes de toda la ciudad. El motor de riesgo ya descarta por
     * distancia lo que está lejos de cada tramo, pero traer miles de incidentes
     * para descartarlos en memoria es pagar dos veces: en la consulta y en el
     * filtro.
     */
    private suspend fun fetchCorridorIncidents(
        origin: GeoPoint,
        destination: GeoPoint,
        candidates: List<CandidateRoute>,
    ): List<com.nomadsafe.app.domain.model.Incident> {
        // Centro del corredor y radio que cubre ambos extremos más margen, para que
        // las rutas alternativas (que se desvían del camino directo) quengan dentro.
        val center = GeoPoint(
            latitude = (origin.latitude + destination.latitude) / 2.0,
            longitude = (origin.longitude + destination.longitude) / 2.0,
        )
        val halfSpan = GeoUtils.distanceMeters(origin, destination) / 2.0
        val radius = halfSpan + CORRIDOR_MARGIN_METERS

        val bounds = GeoUtils.boundingBox(center, radius)
        val since = timeProvider.now() - INCIDENT_LOOKBACK

        return when (val result = incidentRepository.getIncidents(bounds, since)) {
            is NsResult.Success -> result.data
            // Sin incidentes el motor igual puntúa la componente estructural
            // (iluminación y tipo de vía), que es exactamente el comportamiento
            // de arranque en frío. Fallar acá dejaría al usuario sin ruta por un
            // problema de datos que no le impide caminar.
            is NsResult.Failure -> {
                NsLog.w(TAG, "No se pudieron traer incidentes del corredor; se puntúa sólo con datos estáticos")
                emptyList()
            }
        }
    }

    private fun describe(error: com.nomadsafe.app.core.result.NsError): String = when (error) {
        is com.nomadsafe.app.core.result.NsError.Network ->
            "Sin conexión. Revisá tu red e intentá de nuevo."
        is com.nomadsafe.app.core.result.NsError.NotFound ->
            "No encontramos ese destino en la zona del piloto."
        is com.nomadsafe.app.core.result.NsError.RateLimited ->
            "Demasiadas consultas seguidas. Esperá unos segundos."
        is com.nomadsafe.app.core.result.NsError.Server ->
            "El servicio de rutas falló (${error.status}). Intentá de nuevo."
        is com.nomadsafe.app.core.result.NsError.Validation ->
            "Datos inválidos: ${error.reason ?: error.field ?: "revisá el destino"}"
        is com.nomadsafe.app.core.result.NsError.Unauthorized,
        is com.nomadsafe.app.core.result.NsError.PermissionDenied ->
            "Falta permisos de ubicación para calcular la ruta desde donde estás."
        is com.nomadsafe.app.core.result.NsError.Local ->
            "Error local: ${error.reason}"
        is com.nomadsafe.app.core.result.NsError.Unknown ->
            "No pudimos calcular la ruta. Intentá de nuevo."
    }

    private companion object {
        const val TAG = "RouteViewModel"
        const val SEARCH_MIN_LENGTH = 3
        const val ALTERNATIVE_COUNT = 3

        /**
         * Margen sobre el corredor para incluir rutas alternativas.
         *
         * Las alternativas se desvían del camino directo; sin margen quedarían
         * fuera del bounding box y se puntuarían como si no tuvieran incidentes
         * cerca, favoreciéndolas injustamente frente a la ruta directa.
         */
        const val CORRIDOR_MARGIN_METERS = 400.0

        val INCIDENT_LOOKBACK = 30.days
    }
}
