package com.nomadsafe.app.feature.escort

import com.nomadsafe.app.core.location.DeviceLocation
import com.nomadsafe.app.domain.model.EscortSession
import com.nomadsafe.app.domain.model.SosEvent
import com.nomadsafe.app.feature.escort.panic.PanicEngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Estado observable del Modo Escolta, para la UI y la notificación persistente. */
data class EscortUiState(
    val isActive: Boolean = false,
    val session: EscortSession? = null,
    val currentLocation: DeviceLocation? = null,
    val panicEngineState: PanicEngineState = PanicEngineState.IDLE,
    val audioLevel: Float = 0f,
    /** Tramo recorrido, en orden, para dibujar la traza sobre el mapa. */
    val trail: List<DeviceLocation> = emptyList(),
    val distanceWalkedMeters: Double = 0.0,
    val lastPhraseDetected: String? = null,
    val activeSos: SosEvent? = null,
    /** Aviso no bloqueante: batería baja, GPS apagado, ahorro de energía del OEM. */
    val warnings: Set<EscortWarning> = emptySet(),
)

/**
 * Avisos degradados del Modo Escolta.
 *
 * Se modelan como conjunto y no como un único mensaje porque pueden ocurrir
 * varios a la vez (batería baja Y GPS apagado), y porque ninguno debe detener la
 * escolta: son degradaciones, no fallos. La diferencia importa en el diseño —
 * si se trataran como errores, el usuario cancelaría la escolta por un aviso de
 * batería, quedándose sin protección.
 */
enum class EscortWarning {
    /** GPS del dispositivo apagado: sólo hay ubicación por red, mucho menos precisa. */
    LOCATION_DISABLED,

    /**
     * Permiso de micrófono revocado: la detección por palabra de pánico no
     * funciona, pero el resto de la escolta sí.
     */
    MICROPHONE_UNAVAILABLE,

    /** Batería por debajo del umbral: se pasa al perfil de ubicación de bajo consumo. */
    LOW_BATTERY,

    /**
     * El fabricante limita los servicios en segundo plano.
     *
     * Muy relevante en el mercado objetivo: Xiaomi, Huawei, Oppo y Vivo matan
     * foreground services agresivamente aunque Android no lo permita. Sin avisar,
     * la escolta se detiene en silencio y el usuario cree que está protegido.
     */
    OEM_BATTERY_OPTIMIZATION,

    /** Sin conexión: los eventos se están encolando localmente, no se están enviando. */
    OFFLINE,

    /** El modelo de Vosk todavía se está descargando. */
    VOICE_MODEL_PENDING,
}

/**
 * Fuente única de verdad del estado del Modo Escolta.
 *
 * ── Por qué existe esta clase ────────────────────────────────────────────────
 * El servicio en primer plano y la UI viven en componentes con ciclos de vida
 * independientes: la Activity se destruye al rotar o al ir a segundo plano, pero
 * el servicio sigue corriendo. Si el estado viviera en el servicio, la UI tendría
 * que bindearse a él, y si viviera en el ViewModel, se perdería al rotar.
 *
 * Un `@Singleton` con StateFlow resuelve las dos cosas: el servicio escribe, la UI
 * observa, y el estado sobrevive a recreaciones de Activity. Es el patrón
 * estándar para servicios Android con UI reactiva y evita el clásico bug de
 * "la pantalla muestra escolta activa pero el servicio ya murió".
 *
 * ── Invariante que protege ───────────────────────────────────────────────────
 * [isActive] es la única fuente de verdad sobre si hay una escolta en curso.
 * Todas las mutaciones pasan por los métodos de acá, que la mantienen consistente
 * con [session]. Nadie asigna el StateFlow desde afuera.
 */
@Singleton
class EscortStateHolder @Inject constructor() {

    private val _state = MutableStateFlow(EscortUiState())
    val state: StateFlow<EscortUiState> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value.isActive

    fun onSessionStarted(session: EscortSession) {
        _state.value = _state.value.copy(
            isActive = true,
            session = session,
            trail = emptyList(),
            distanceWalkedMeters = 0.0,
            activeSos = null,
        )
    }

    fun onSessionEnded() {
        // Se conserva `lastPhraseDetected` para la pantalla de resumen post-escolta.
        _state.value = _state.value.copy(
            isActive = false,
            session = null,
            trail = emptyList(),
            activeSos = null,
            warnings = emptySet(),
            panicEngineState = PanicEngineState.IDLE,
        )
    }

    /**
     * Registra una nueva posición y extiende la traza.
     *
     * La traza se recorta a [MAX_TRAIL_POINTS] porque una escolta de una hora a
     * 5 s por punto son 720 posiciones; dibujarlas todas en el mapa cada frame
     * degradaría la interfaz justo cuando el usuario está caminando y mirando la
     * pantalla. Se conserva el total recorrido aparte para el resumen.
     */
    fun onLocation(location: DeviceLocation) {
        val previous = _state.value.currentLocation
        val deltaMeters = if (previous == null) {
            0.0
        } else {
            com.nomadsafe.app.core.util.GeoUtils.distanceMeters(previous.point, location.point)
        }

        _state.value = _state.value.let { current ->
            val trail = (current.trail + location)
            val trimmed = if (trail.size > MAX_TRAIL_POINTS) trail.takeLast(MAX_TRAIL_POINTS) else trail
            current.copy(
                currentLocation = location,
                trail = trimmed,
                distanceWalkedMeters = current.distanceWalkedMeters + deltaMeters,
            )
        }
    }

    fun onPanicEngineStateChanged(state: PanicEngineState) {
        _state.value = _state.value.copy(panicEngineState = state)
    }

    fun onAudioLevel(level: Float) {
        _state.value = _state.value.copy(audioLevel = level)
    }

    fun onPhraseDetected(phrase: String) {
        _state.value = _state.value.copy(lastPhraseDetected = phrase)
    }

    fun onSosRaised(event: SosEvent) {
        _state.value = _state.value.copy(activeSos = event)
    }

    fun onSosResolved() {
        _state.value = _state.value.copy(activeSos = null)
    }

    fun addWarning(warning: EscortWarning) {
        _state.value = _state.value.copy(warnings = _state.value.warnings + warning)
    }

    fun removeWarning(warning: EscortWarning) {
        _state.value = _state.value.copy(warnings = _state.value.warnings - warning)
    }

    fun replaceWarnings(warnings: Set<EscortWarning>) {
        if (_state.value.warnings != warnings) {
            _state.value = _state.value.copy(warnings = warnings)
        }
    }

    private companion object {
        const val MAX_TRAIL_POINTS = 240 // ~20 min a 5 s por punto
    }
}
