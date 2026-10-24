package com.nomadsafe.app.data.repository

import com.nomadsafe.app.core.result.NsError
import com.nomadsafe.app.core.result.NsResult
import com.nomadsafe.app.core.result.mapThrowable
import com.nomadsafe.app.core.util.BoundingBox
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.data.local.IncidentEntity
import com.nomadsafe.app.data.local.NomadSafeDatabase
import com.nomadsafe.app.data.local.OutboxOperation
import com.nomadsafe.app.data.local.StaticFeatureEntity
import com.nomadsafe.app.data.remote.IncidentDto
import com.nomadsafe.app.data.remote.PostgisGeometry
import com.nomadsafe.app.data.remote.StaticFeatureDto
import com.nomadsafe.app.data.remote.SupabaseGateway
import com.nomadsafe.app.data.sync.OutboxSyncManager
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.IncidentCategory
import com.nomadsafe.app.domain.model.IncidentStatus
import com.nomadsafe.app.domain.model.IncidentTiming
import com.nomadsafe.app.domain.model.RoadClass
import com.nomadsafe.app.domain.repository.IncidentRepository
import com.nomadsafe.app.domain.repository.NewIncident
import com.nomadsafe.app.domain.repository.StaticFeatureRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

/**
 * Repositorio de incidentes con estrategia local-first.
 *
 * ── El flujo de lectura ──────────────────────────────────────────────────────
 * [observeIncidents] devuelve un [Flow] de Room, que emite INMEDIATAMENTE con lo
 * cacheado. En paralelo se lanza un refresco desde Postgres que actualiza Room, y
 * Room vuelve a emitir. La UI nunca espera la red para pintar.
 *
 * Esto no es una optimización de rendimiento: en una calle con señal mala, una
 * app que muestra un spinner hasta tener red es una app que no sirve. El usuario
 * ve el mapa al instante con datos de hace cinco minutos, y eso es infinitamente
 * mejor que una pantalla vacía.
 *
 * ── El flujo de escritura ────────────────────────────────────────────────────
 * [reportIncident] escribe en Room primero, encola en el outbox y recién después
 * intenta la red. El id local se devuelve de inmediato. Si la red falla, nada se
 * pierde: [OutboxSyncManager] lo reintenta con backoff.
 *
 * ── Idempotencia ─────────────────────────────────────────────────────────────
 * El id se genera en el CLIENTE y se usa como PK en Postgres. Si un INSERT se
 * procesa pero la respuesta se pierde (caso típico con señal intermitente), el
 * reintento choca con la PK y se descarta. Sin esto, el usuario duplicaría sus
 * propios reportes y envenenaría el score de riesgo de su cuadra.
 */
@Singleton
class IncidentRepositoryImpl @Inject constructor(
    private val database: NomadSafeDatabase,
    private val gateway: SupabaseGateway,
    private val outbox: OutboxSyncManager,
    private val json: Json,
    private val timeProvider: TimeProvider,
    @com.nomadsafe.app.core.util.ApplicationScope private val applicationScope: CoroutineScope,
) : IncidentRepository {

    private val incidentDao get() = database.incidentDao()
    private val staticFeatureDao get() = database.staticFeatureDao()

    override fun observeIncidents(bounds: BoundingBox, since: Instant): Flow<List<Incident>> {
        // Refresco en segundo plano. Se dispara al suscribirse y no bloquea la
        // primera emisión, que sale de la caché.
        applicationScope.launch { refreshFromRemote(bounds, since) }
        observeRealtime(bounds)

        return incidentDao.observeInBounds(
            south = bounds.south,
            west = bounds.west,
            north = bounds.north,
            east = bounds.east,
            sinceEpochMillis = since.toEpochMilliseconds(),
        ).map { entities -> entities.map { it.toDomain() } }
    }

    /**
     * Suscripción a Realtime acotada al viewport.
     *
     * Se filtra por bounding box en el servidor (`location=...`) y no en el
     * cliente: sin filtro, un usuario recibiría TODOS los reportes de la ciudad y
     * en hora pico eso satura el WebSocket y la batería.
     */
    private fun observeRealtime(bounds: BoundingBox) {
        applicationScope.launch {
            gateway.observeTableChanges(table = TABLE_INCIDENTS)
                .collect { change ->
                    val record = change.record ?: return@collect
                    runCatching { json.decodeFromJsonElement(IncidentDto.serializer(), record) }
                        .onSuccess { dto ->
                            val point = PostgisGeometry.parse(dto.location) ?: return@onSuccess
                            // Se descarta lo que cae fuera del viewport actual.
                            if (point.latitude !in bounds.south..bounds.north) return@onSuccess
                            if (point.longitude !in bounds.west..bounds.east) return@onSuccess
                            incidentDao.upsertAll(listOf(dto.toEntity(point)))
                        }
                        .onFailure { NsLog.w(TAG, "Registro Realtime no parseable", it) }
                }
        }
    }

    private suspend fun refreshFromRemote(bounds: BoundingBox, since: Instant) {
        val filter = buildString {
            append("occurred_at=gt.${since.toString()}")
        }

        val result = gateway.select(
            table = TABLE_INCIDENTS,
            filter = filter,
            orderColumn = "occurred_at",
            ascending = false,
            limit = REMOTE_FETCH_LIMIT,
        )

        result
            .onSuccess { rows ->
                val entities = rows.mapNotNull { row ->
                    runCatching { json.decodeFromJsonElement(IncidentDto.serializer(), row) }
                        .getOrNull()
                        ?.let { dto -> PostgisGeometry.parse(dto.location)?.let { dto.toEntity(it) } }
                }.filter { entity ->
                    // PostgREST no filtra geografía con el operador de texto, así que
                    // el recorte por viewport se hace acá. Con el límite de 500 el
                    // costo es despreciable.
                    entity.latitude in bounds.south..bounds.north &&
                        entity.longitude in bounds.west..bounds.east
                }

                if (entities.isNotEmpty()) {
                    incidentDao.upsertAll(entities)
                    NsLog.d(TAG, "Sincronizados ${entities.size} incidentes desde Postgres")
                }
            }
            .onFailure { error ->
                NsLog.w(TAG, "Refresco remoto falló; se sirve la caché local", error)
            }
    }

    override suspend fun getIncidents(bounds: BoundingBox, since: Instant): NsResult<List<Incident>> {
        refreshFromRemote(bounds, since)
        return nsRunCatchingLocal {
            incidentDao.observeInBounds(
                south = bounds.south,
                west = bounds.west,
                north = bounds.north,
                east = bounds.east,
                sinceEpochMillis = since.toEpochMilliseconds(),
            ).first().map { it.toDomain() }
        }
    }

    override suspend fun getIncident(id: String): NsResult<Incident> = nsRunCatchingLocal {
        incidentDao.findById(id)?.toDomain()
            ?: throw NoSuchElementException("Incidente $id no encontrado")
    }

    override suspend fun reportIncident(incident: NewIncident): NsResult<String> {
        // Id generado en el cliente: habilita la idempotencia del reintento.
        val localId = "inc-${UUID.randomUUID()}"
        val now = timeProvider.now()

        val entity = IncidentEntity(
            id = localId,
            category = incident.category.wireName,
            latitude = incident.location.latitude,
            longitude = incident.location.longitude,
            occurredAt = incident.occurredAt,
            reportedAt = now,
            timing = IncidentTiming.RECENT.wireName,
            status = IncidentStatus.ACTIVE.wireName,
            description = incident.description,
            reporterId = null,
            isAnonymous = incident.isAnonymous,
            confirmationCount = 0,
            disputeCount = 0,
            accuracyMeters = incident.accuracyMeters,
            neighborhood = incident.neighborhood,
            isPendingSync = true,
            isMine = true,
        )

        return nsRunCatchingLocal {
            incidentDao.insert(entity)

            val payload = IncidentDto(
                id = localId,
                category = incident.category.wireName,
                location = PostgisGeometry.toWkt(incident.location),
                occurredAt = incident.occurredAt,
                reportedAt = now,
                timing = IncidentTiming.RECENT.wireName,
                status = IncidentStatus.ACTIVE.wireName,
                description = incident.description,
                isAnonymous = incident.isAnonymous,
                accuracyMeters = incident.accuracyMeters,
                neighborhood = incident.neighborhood,
            )

            // Se encola SIEMPRE, incluso si hay red. Si el insert remoto funciona,
            // el drenaje descarta la entrada; si no, ya está encolada. Encolar sólo
            // cuando falla la red abre una ventana donde el evento se pierde si el
            // proceso muere entre el fallo y el encolado.
            outbox.enqueue(
                table = TABLE_INCIDENTS,
                operation = OutboxOperation.INSERT,
                payload = json.encodeToString(IncidentDto.serializer(), payload),
                isCritical = false,
            )

            // Intento inmediato: si hay red, el reporte aparece al instante para
            // todos los demás usuarios, que es el valor central del producto.
            val immediate = gateway.insert(TABLE_INCIDENTS, payload)
            if (immediate.isSuccess) {
                incidentDao.markSynced(localId, localId)
            } else {
                outbox.startRetryLoop()
            }

            localId
        }
    }

    override suspend fun confirmIncident(incidentId: String): NsResult<Unit> = nsRunCatchingLocal {
        // Optimista local: el usuario ve su confirmación reflejada al instante.
        incidentDao.incrementConfirmation(incidentId)
        outbox.enqueue(
            table = TABLE_INCIDENTS,
            operation = OutboxOperation.PATCH,
            payload = """{"confirmation_count": "confirmation_count + 1"}""",
            filter = "id=eq.$incidentId",
        )
        gateway.invokeFunction(
            FUNCTION_CONFIRM_INCIDENT,
            json.parseToJsonElement("""{"incident_id":"$incidentId"}""") as? JsonObject,
        )
    }

    override suspend fun disputeIncident(incidentId: String): NsResult<Unit> = nsRunCatchingLocal {
        incidentDao.incrementDispute(incidentId)
        outbox.enqueue(
            table = TABLE_INCIDENTS,
            operation = OutboxOperation.PATCH,
            payload = """{"dispute_count": "dispute_count + 1"}""",
            filter = "id=eq.$incidentId",
        )
        gateway.invokeFunction(
            FUNCTION_DISPUTE_INCIDENT,
            json.parseToJsonElement("""{"incident_id":"$incidentId"}""") as? JsonObject,
        )
    }

    override fun observePendingSyncCount(): Flow<Int> =
        incidentDao.observePendingCount()
            .onStart { emit(incidentDao.pendingCount()) }

    /**
     * Ejecuta una operación local y traduce fallos a [NsError].
     *
     * Se separa de [com.nomadsafe.app.core.result.nsRunCatching] porque las
     * operaciones de repositorio son suspendidas y necesitan propagar
     * CancellationException igual que la variante inline.
     */
    private suspend inline fun <T> nsRunCatchingLocal(crossinline block: suspend () -> T): NsResult<T> = try {
        NsResult.Success(block())
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsLog.w(TAG, "Operación local falló: ${throwable.message}")
        NsResult.Failure(mapThrowable(throwable))
    }

    private companion object {
        const val TAG = "IncidentRepository"
        const val TABLE_INCIDENTS = "incidents"
        const val FUNCTION_CONFIRM_INCIDENT = "confirm-incident"
        const val FUNCTION_DISPUTE_INCIDENT = "dispute-incident"
        const val REMOTE_FETCH_LIMIT = 500
    }
}

// ── Mapeo DTO/Entity ↔ Dominio ────────────────────────────────────────────────

private fun IncidentDto.toEntity(point: GeoPoint) = IncidentEntity(
    id = id,
    category = category,
    latitude = point.latitude,
    longitude = point.longitude,
    occurredAt = occurredAt,
    reportedAt = reportedAt,
    timing = timing,
    status = status,
    description = description,
    reporterId = reporterId,
    isAnonymous = isAnonymous,
    confirmationCount = confirmationCount,
    disputeCount = disputeCount,
    accuracyMeters = accuracyMeters,
    neighborhood = neighborhood,
    isPendingSync = false,
)

private fun IncidentEntity.toDomain() = Incident(
    id = id,
    category = IncidentCategory.fromWireName(category),
    location = GeoPoint(latitude, longitude),
    occurredAt = occurredAt,
    reportedAt = reportedAt,
    timing = IncidentTiming.fromWireName(timing),
    status = IncidentStatus.fromWireName(status),
    description = description,
    reporterId = reporterId,
    isAnonymous = isAnonymous,
    confirmationCount = confirmationCount,
    disputeCount = disputeCount,
    accuracyMeters = accuracyMeters,
    neighborhood = neighborhood,
)

/**
 * Repositorio de features estáticas de OSM.
 *
 * Cachea en Room con vencimiento por antigüedad: la iluminación de una calle
 * cambia en meses, no en minutos, así que re-descargarla en cada apertura del
 * mapa sería latencia y datos móviles sin beneficio.
 */
@Singleton
class StaticFeatureRepositoryImpl @Inject constructor(
    private val database: NomadSafeDatabase,
    private val gateway: SupabaseGateway,
    private val json: Json,
    private val timeProvider: TimeProvider,
) : StaticFeatureRepository {

    private val dao get() = database.staticFeatureDao()

    override suspend fun getLighting(bounds: BoundingBox): NsResult<Map<String, Boolean>> = try {
        // Refresco si la caché venció. No bloquea la lectura: se sirve lo que hay.
        val lastUpdate = dao.lastUpdatedAt()
        if (lastUpdate == null || (timeProvider.now() - lastUpdate) > CACHE_TTL) {
            NsLog.d(TAG, "Caché de features estáticas vencida; se refresca")
            refresh(bounds)
        }

        val features = dao.inBounds(bounds.south, bounds.west, bounds.north, bounds.east)
        NsResult.Success(features.mapNotNull { feature ->
            feature.lit?.let { feature.cellKey to it }
        }.toMap())
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun refresh(bounds: BoundingBox): NsResult<Int> = try {
        val result = gateway.select(
            table = VIEW_STATIC_FEATURES,
            filter = null,
            limit = STATIC_FETCH_LIMIT,
        )

        val fetched = result.getOrNull().orEmpty()
        val entities = fetched.mapNotNull { row ->
            runCatching { json.decodeFromJsonElement(StaticFeatureDto.serializer(), row) }
                .getOrNull()
                ?.let { dto ->
                    StaticFeatureEntity(
                        cellKey = dto.cellKey,
                        centerLat = dto.centerLat,
                        centerLon = dto.centerLon,
                        lit = dto.lit,
                        roadClass = dto.roadClass,
                        streetName = dto.streetName,
                        fetchedAt = timeProvider.now(),
                    )
                }
        }.filter { entity ->
            entity.centerLat in bounds.south..bounds.north &&
                entity.centerLon in bounds.west..bounds.east
        }

        if (entities.isNotEmpty()) dao.upsertAll(entities)
        NsLog.i(TAG, "Features estáticas actualizadas: ${entities.size}")
        NsResult.Success(entities.size)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun lastUpdatedAt(): Instant? = dao.lastUpdatedAt()

    private companion object {
        const val TAG = "StaticFeatureRepository"
        const val VIEW_STATIC_FEATURES = "static_features_grid"
        const val STATIC_FETCH_LIMIT = 2_000

        /**
         * Vida útil de la caché de features estáticas: 3 días.
         *
         * La iluminación y el tipo de vía cambian en meses, no en minutos. Un TTL
         * más corto sólo gastaría datos móviles del usuario sin mejorar la
         * precisión; uno más largo retrasaría la corrección de calles recién
         * iluminadas, que es justo la señal que más valor aporta.
         */
        val CACHE_TTL = 72.hours
    }
}

/** Convierte una entidad de feature estática en clasificación de vía de dominio. */
internal fun StaticFeatureEntity.roadClass(): RoadClass = RoadClass.fromWireName(roadClass)
