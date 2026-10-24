package com.nomadsafe.app.domain.repository

import com.nomadsafe.app.core.result.NsResult
import com.nomadsafe.app.domain.model.EscortSession
import com.nomadsafe.app.domain.model.EscortStatus
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.SosEvent
import com.nomadsafe.app.domain.model.SosStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Repositorio del Modo Escolta.
 *
 * ── El contrato que importa: qué se encola y qué no ───────────────────────────
 * Esta interfaz mezcla a propósito dos políticas de escritura distintas, y la
 * diferencia no es un detalle de implementación:
 *
 * · **SOS, sesión y contactos** → se encolan en el outbox como críticos. Una
 *   alerta de emergencia tiene que llegar aunque el teléfono esté sin cobertura en
 *   el momento de dispararla, y se reintenta hasta que salga.
 * · **Posiciones en vivo** → NO se encolan. Se intentan y si fallan se descartan.
 *
 * La razón de la asimetría: una posición de hace diez minutos no le sirve a nadie.
 * El contacto que está mirando el mapa de seguimiento necesita saber dónde está la
 * persona AHORA; recibir de golpe las 700 posiciones de una caminata de una hora
 * cuando se recupera la red no reconstruye nada útil y sólo satura el canal. Un
 * incidente reportado hace diez minutos, en cambio, conserva todo su valor.
 *
 * Un repositorio que unificara ambas políticas "por simplicidad" estaría
 * eligiendo mal en uno de los dos casos, y el caso que elige mal es el de la
 * emergencia.
 */
interface EscortRepository {

    /**
     * Sesión activa, si hay alguna.
     *
     * Es un [Flow] y no una consulta puntual porque el servicio en primer plano y
     * la pantalla de escolta necesitan reaccionar a cambios de estado que pueden
     * originarse en el otro. Con una lectura única, la UI se quedaría mostrando
     * "escolta inactiva" mientras el servicio ya está corriendo.
     */
    fun observeActiveSession(): Flow<EscortSession?>

    /** SOS abierto sin resolver, si hay alguno. */
    fun observeActiveSos(): Flow<SosEvent?>

    /** Historial de SOS, del más reciente al más antiguo. */
    fun observeSosHistory(limit: Int): Flow<List<SosEvent>>

    /**
     * Inicia una sesión de escolta.
     *
     * El destino es opcional a propósito: la escolta también se usa para
     * "acompañame mientras vuelvo a casa" sin un destino formal, y exigir el
     * destino obligaría al usuario a inventar uno en un momento de apuro.
     */
    suspend fun startSession(
        destination: GeoPoint?,
        destinationLabel: String?,
        estimatedArrival: Instant?,
    ): NsResult<EscortSession>

    /**
     * Registra una posición de la traza.
     *
     * `isMoving` lo decide el llamador y no el repositorio: el servicio ya calcula
     * si el usuario se mueve comparando velocidad contra el umbral de caminata, y
     * recalcularlo acá requeriría acceder al historial de posiciones, que el
     * repositorio no tiene.
     */
    suspend fun updateLocation(
        sessionId: String,
        location: GeoPoint,
        accuracyMeters: Float?,
        isMoving: Boolean,
    ): NsResult<Unit>

    /**
     * Marca que el usuario respondió el check-in.
     *
     * NO toca la última posición conocida. Ese fue un bug real en una versión
     * previa de la implementación, que reutilizaba `updateLocation` con
     * coordenadas (0, 0) para estampar el timestamp: borraba la posición del
     * usuario y dejaba el punto de seguimiento en el Golfo de Guinea. El contacto
     * que mira el mapa durante una emergencia veía a la persona desaparecer del
     * continente.
     */
    suspend fun recordCheckIn(sessionId: String): NsResult<Unit>

    suspend fun updateStatus(sessionId: String, status: EscortStatus): NsResult<Unit>

    suspend fun endSession(sessionId: String, status: EscortStatus): NsResult<Unit>

    /**
     * Dispara un SOS.
     *
     * Devuelve el id del evento persistido, que es el mismo que generó el cliente:
     * se necesita para armar el enlace de seguimiento en vivo que va en el SMS a
     * los contactos.
     */
    suspend fun raiseSos(event: SosEvent): NsResult<String>

    suspend fun updateSosStatus(sosId: String, status: SosStatus): NsResult<Unit>

    /**
     * Cierra un SOS como falsa alarma o resuelto, con nota opcional.
     *
     * La nota importa para el patrón de abuso: si alguien dispara falsas alarmas
     * repetidas, el historial de notas es lo que permite distinguir un error de un
     * uso indebido. Sin ella, todos los cierres se ven iguales.
     */
    suspend fun resolveSos(sosId: String, note: String?): NsResult<Unit>
}
