package com.nomadsafe.app.domain.repository

import com.nomadsafe.app.core.result.NsResult
import com.nomadsafe.app.core.util.BoundingBox
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.IncidentCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Datos necesarios para crear un reporte.
 *
 * [occurredAt] va separado de [timing] porque la UI captura "está pasando ahora /
 * pasó hace poco / sucede seguido" y de ahí se deriva un timestamp concreto.
 */
data class NewIncident(
    val category: IncidentCategory,
    val location: GeoPoint,
    val accuracyMeters: Float?,
    val occurredAt: Instant,
    val description: String?,
    val isAnonymous: Boolean,
    val neighborhood: String?,
)

/**
 * Repositorio de incidentes.
 *
 * Contrato importante: las lecturas son [Flow] y SIEMPRE sirven primero lo que
 * hay en caché local. En una app de seguridad el usuario abre el mapa en un
 * sótano, en un micro o en una zona con señal mala; ver un mapa vacío mientras
 * carga la red no es aceptable. La estrategia local-first se implementa en
 * `IncidentRepositoryImpl`, no acá, para que la interfaz no la filtre.
 */
interface IncidentRepository {

    /**
     * Incidentes dentro de [bounds] que deben pintarse en el mapa.
     *
     * Emite de inmediato con la caché local y luego con datos frescos.
     * [since] acota la antigüedad: no tiene sentido pintar un robo de hace ocho
     * meses como si fuera riesgo actual, aunque sí sirve para estadísticas.
     */
    fun observeIncidents(bounds: BoundingBox, since: Instant): Flow<List<Incident>>

    /** Búsqueda puntual (cache-first + red). */
    suspend fun getIncidents(bounds: BoundingBox, since: Instant): NsResult<List<Incident>>

    suspend fun getIncident(id: String): NsResult<Incident>

    /**
     * Crea un reporte.
     *
     * Debe funcionar SIN red: persiste localmente y lo encola en el outbox para
     * sincronizar después. Devuelve el id local provisional, que luego se
     * reconcilia con el id de Postgres.
     */
    suspend fun reportIncident(incident: NewIncident): NsResult<String>

    /**
     * Confirmación comunitaria ("esto es cierto, a mí también me pasó").
     *
     * Es el mecanismo que alimenta [Incident.consensusMultiplier] y, a la vez,
     * el loop de retención: el usuario ve que su aporte cambia el mapa.
     */
    suspend fun confirmIncident(incidentId: String): NsResult<Unit>

    /** Marca un reporte como falso o impreciso. Contrapeso necesario de [confirmIncident]. */
    suspend fun disputeIncident(incidentId: String): NsResult<Unit>

    /**
     * Estado de la sincronización pendiente, para el banner "sin conexión".
     * Emite 0 cuando el outbox está vacío.
     */
    fun observePendingSyncCount(): Flow<Int>
}

/**
 * Features estáticas derivadas de OpenStreetMap: iluminación y tipo de vía.
 *
 * Es la señal que hace que la app sea útil el día 1 en una ciudad nueva, antes
 * de que exista un solo reporte. Se descarga por ciudad y se cachea en Room
 * porque cambia muy poco (una calle no se ilumina de un día para el otro).
 */
interface StaticFeatureRepository {

    /** Devuelve iluminación conocida por tramo, o null donde no hay dato. */
    suspend fun getLighting(bounds: BoundingBox): NsResult<Map<String, Boolean>>

    /** Refresca desde el servidor. Se dispara al cambiar de ciudad, no en cada uso. */
    suspend fun refresh(bounds: BoundingBox): NsResult<Int>

    /** Cuándo fue la última descarga, para decidir si hace falta refrescar. */
    suspend fun lastUpdatedAt(): Instant?
}
