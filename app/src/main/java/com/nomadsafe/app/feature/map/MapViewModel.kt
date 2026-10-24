package com.nomadsafe.app.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nomadsafe.app.core.config.AppConfig
import com.nomadsafe.app.core.location.DeviceLocation
import com.nomadsafe.app.core.location.LocationProfile
import com.nomadsafe.app.core.location.LocationSource
import com.nomadsafe.app.core.util.BoundingBox
import com.nomadsafe.app.core.util.GeoUtils
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.IncidentCategory
import com.nomadsafe.app.domain.model.RiskLevel
import com.nomadsafe.app.domain.repository.IncidentRepository
import com.nomadsafe.app.feature.risk.Hotspot
import com.nomadsafe.app.feature.risk.HotspotDetector
import com.nomadsafe.app.feature.risk.RiskConfig
import com.nomadsafe.app.feature.risk.RiskEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Capas conmutables del mapa. */
enum class MapLayer(val isEnabledByDefault: Boolean) {
    /** Zonas de riesgo agregadas (heatmap). */
    HEATMAP(true),

    /** Puntos individuales de incidentes. */
    INCIDENTS(true),

    /** Iluminación reportada o derivada de OSM. */
    LIGHTING(false),
}

data class MapUiState(
    val viewportCenter: GeoPoint,
    val zoom: Double = DEFAULT_ZOOM,
    val incidents: List<Incident> = emptyList(),
    val hotspots: List<Hotspot> = emptyList(),
    val userLocation: DeviceLocation? = null,
    val enabledLayers: Set<MapLayer> = MapLayer.entries.filter { it.isEnabledByDefault }.toSet(),
    val riskLevelAtCenter: RiskLevel = RiskLevel.LOW,
    val riskScoreAtCenter: Double = 0.0,
    val isLoading: Boolean = true,
    val isDemoMode: Boolean = false,
    val pendingSyncCount: Int = 0,
    val selectedIncident: Incident? = null,
    /** Antigüedad máxima de los incidentes mostrados. */
    val lookbackDays: Int = DEFAULT_LOOKBACK_DAYS,
) {
    val visibleIncidentCount: Int get() = incidents.size

    /** Conteo por categoría, para el desglose del panel inferior. */
    val countByCategory: Map<IncidentCategory, Int>
        get() = incidents.groupingBy { it.category }.eachCount()
}

/**
 * ViewModel del mapa.
 *
 * ── La decisión de diseño importante ─────────────────────────────────────────
 * El viewport se modela como [StateFlow] y la suscripción a incidentes se deriva
 * de él con [flatMapLatest]. Esto significa que al mover el mapa se CANCELA la
 * consulta anterior y se lanza la nueva, en vez de acumular suscripciones.
 *
 * Sin `flatMapLatest`, deslizar el mapa produciría decenas de consultas
 * concurrentes a Postgres y a Room, cada una emitiendo listas parciales que la UI
 * vería parpadear. Es el bug de rendimiento más común en mapas reactivos.
 *
 * ── Debounce del viewport ────────────────────────────────────────────────────
 * [distinctUntilChanged] más la cuantización del centro a ~50 m evitan relanzar
 * la consulta por cada píxel de desplazamiento. Sin eso, un gesto de arrastre
 * genera cientos de consultas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MapViewModel @Inject constructor(
    private val incidentRepository: IncidentRepository,
    private val locationSource: LocationSource,
    private val riskEngine: RiskEngine,
    private val hotspotDetector: HotspotDetector,
    private val riskConfig: RiskConfig,
    private val appConfig: AppConfig,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MapUiState(
            viewportCenter = appConfig.pilotCenter,
            isDemoMode = appConfig.isDemoMode,
        ),
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** Centro del viewport cuantizado, para no relanzar consultas por cada píxel. */
    private val quantizedCenter = MutableStateFlow(appConfig.pilotCenter)

    init {
        observeUserLocation()
        observeIncidents()
        observePendingSync()

        if (appConfig.isDemoMode) {
            NsLog.w(TAG, "Mapa en modo demo: sin backend, sólo datos locales")
        }
    }

    private fun observeUserLocation() {
        viewModelScope.launch {
            // SINGLE_FIX y no seguimiento continuo: el mapa necesita saber dónde
            // está el usuario, no seguirlo cada 5 s (eso es del Modo Escolta).
            locationSource.observeLocations(LocationProfile.SINGLE_FIX)
                .distinctUntilChanged { old, new -> old.capturedAtEpochMillis == new.capturedAtEpochMillis }
                .collect { location ->
                    _uiState.value = _uiState.value.copy(
                        userLocation = location,
                        isLoading = false,
                    )
                    // El primer fix centra el mapa en el usuario, no en el piloto.
                    if (_uiState.value.viewportCenter == appConfig.pilotCenter) {
                        onViewportChanged(location.point, _uiState.value.zoom)
                    }
                }
        }
    }

    /**
     * Suscripción a incidentes derivada del viewport.
     *
     * `flatMapLatest` cancela la consulta anterior al mover el mapa. Se combina con
     * los hotspots calculados y con el score de riesgo en el centro, que es lo que
     * alimenta la etiqueta "zona tranquila / precaución / riesgo".
     */
    private fun observeIncidents() {
        viewModelScope.launch {
            quantizedCenter
                .flatMapLatest { center ->
                    val bounds = GeoUtils.boundingBox(center, VIEWPORT_RADIUS_METERS)
                    val since = timeProvider.now() - _uiState.value.lookbackDays.days

                    incidentRepository.observeIncidents(bounds, since)
                }
                .map { incidents ->
                    val center = _uiState.value.viewportCenter
                    val since = timeProvider.now() - _uiState.value.lookbackDays.days

                    // Hotspots y riesgo local se recalculan sobre el conjunto ya
                    // acotado por el viewport, que es barato.
                    val hotspots = hotspotDetector.detect(incidents)
                    val centerRisk = computeRiskAt(center, incidents)

                    Triple(incidents, hotspots, centerRisk)
                }
                .collect { (incidents, hotspots, centerRisk) ->
                    _uiState.value = _uiState.value.copy(
                        incidents = incidents,
                        hotspots = hotspots,
                        riskScoreAtCenter = centerRisk,
                        riskLevelAtCenter = RiskLevel.fromScore(centerRisk),
                        isLoading = false,
                    )
                }
        }
    }

    private fun observePendingSync() {
        viewModelScope.launch {
            incidentRepository.observePendingSyncCount().collect { count ->
                _uiState.value = _uiState.value.copy(pendingSyncCount = count)
            }
        }
    }

    /**
     * Riesgo en un punto concreto.
     *
     * Se evalúa un tramo corto virtual centrado en el punto, reutilizando el mismo
     * kernel espacial y temporal del motor de rutas. Reusar el motor en vez de
     * calcular otro score aparte garantiza que lo que el mapa muestra como
     * "peligroso" coincida con lo que el motor evita al trazar una ruta. Dos
     * fórmulas distintas darían dos verdades contradictorias en la misma pantalla.
     */
    private fun computeRiskAt(point: GeoPoint, incidents: List<Incident>): Double {
        val probeSegment = com.nomadsafe.app.domain.model.RouteSegment(
            from = GeoUtils.destination(point, 0.0, PROBE_HALF_LENGTH_METERS),
            to = GeoUtils.destination(point, 180.0, PROBE_HALF_LENGTH_METERS),
            lengthMeters = PROBE_HALF_LENGTH_METERS * 2,
            roadClass = com.nomadsafe.app.domain.model.RoadClass.UNKNOWN,
            lit = null,
        )

        val risk = riskEngine.scoreSegment(probeSegment, incidents, timeProvider.now())

        // Se descuenta la componente estática, que en la sonda es siempre la de
        // "iluminación desconocida": si no, todo el mapa saldría teñido del mismo
        // valor base y no se verían las diferencias reales entre barrios.
        val staticContribution = riskConfig.staticWeight * riskConfig.unknownLightingPenalty
        return ((risk.score - staticContribution) / riskConfig.crowdWeight).coerceIn(0.0, 1.0)
    }

    /** Llamado por la UI cuando el usuario mueve el mapa. */
    fun onViewportChanged(center: GeoPoint, zoom: Double) {
        val current = quantizedCenter.value

        // Cuantización a ~50 m: un gesto de arrastre no debe lanzar una consulta
        // por cada píxel. Sin esto, un swipe genera cientos de consultas.
        val movedMeters = GeoUtils.distanceMeters(current, center)
        val zoomChanged = kotlin.math.abs(zoom - _uiState.value.zoom) > ZOOM_EPSILON

        if (movedMeters < VIEWPORT_QUANTIZATION_METERS && !zoomChanged) return

        quantizedCenter.value = center
        _uiState.value = _uiState.value.copy(viewportCenter = center, zoom = zoom)
        NsLog.d(TAG, "Viewport → $center (zoom $zoom, desplazamiento ${movedMeters.toInt()} m)")
    }

    fun toggleLayer(layer: MapLayer) {
        val current = _uiState.value.enabledLayers
        _uiState.value = _uiState.value.copy(
            enabledLayers = if (layer in current) current - layer else current + layer,
        )
    }

    fun selectIncident(incident: Incident?) {
        _uiState.value = _uiState.value.copy(selectedIncident = incident)
    }

    fun setLookbackDays(days: Int) {
        _uiState.value = _uiState.value.copy(lookbackDays = days.coerceIn(MIN_LOOKBACK_DAYS, MAX_LOOKBACK_DAYS))
        // Se fuerza el re-disparo de la suscripción.
        quantizedCenter.value = quantizedCenter.value
        observeIncidents()
    }

    /** Centra el mapa en un incidente, p. ej. al llegar desde un link compartido. */
    fun focusIncident(incidentId: String) {
        val incident = _uiState.value.incidents.firstOrNull { it.id == incidentId } ?: return
        onViewportChanged(incident.location, FOCUS_ZOOM)
        selectIncident(incident)
    }

    fun recenterOnUser() {
        val location = _uiState.value.userLocation ?: return
        onViewportChanged(location.point, DEFAULT_ZOOM)
    }

    private companion object {
        const val TAG = "MapViewModel"
        const val DEFAULT_ZOOM = 15.0
        const val FOCUS_ZOOM = 17.0
        const val ZOOM_EPSILON = 0.25

        /** Radio del viewport consultado. ~8 cuadras a la redonda. */
        const val VIEWPORT_RADIUS_METERS = 900.0

        const val VIEWPORT_QUANTIZATION_METERS = 50.0
        const val PROBE_HALF_LENGTH_METERS = 25.0

        const val DEFAULT_LOOKBACK_DAYS = 14
        const val MIN_LOOKBACK_DAYS = 1
        const val MAX_LOOKBACK_DAYS = 90
    }
}
