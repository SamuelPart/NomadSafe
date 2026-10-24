package com.nomadsafe.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Esquema local de NomadSafe.
 *
 * ── Por qué la app es local-first ────────────────────────────────────────────
 * No es una optimización, es un requisito del dominio. Los tres momentos en que
 * esta app se usa de verdad — una calle con señal mala, un sótano, un asalto en
 * curso — son exactamente los momentos en que la red falla. Una app de seguridad
 * que necesita conectividad para reportar una emergencia no funciona cuando se la
 * necesita.
 *
 * Consecuencias en el diseño:
 *  - Todo lo que el usuario crea (reportes, contactos, sesiones, SOS) se escribe
 *    PRIMERO en Room y luego se sincroniza.
 *  - Las lecturas sirven la caché de inmediato y se refrescan después.
 *  - Lo que no pudo enviarse vive en [OutboxEntity] hasta que haya red.
 *
 * ── Por qué coordenadas como Double y no como tipo espacial ──────────────────
 * Room no tiene tipos geográficos. Se guardan lat/lon como REAL y el filtrado
 * espacial se hace con un bounding box (`BETWEEN`), que SQLite resuelve bien con
 * un índice compuesto. La geometría pesada (kNN, agregación por celdas) vive en
 * PostGIS del lado del servidor, que es donde corresponde.
 */
@Database(
    entities = [
        IncidentEntity::class,
        TrustContactEntity::class,
        EscortSessionEntity::class,
        SosEventEntity::class,
        OutboxEntity::class,
        StaticFeatureEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(InstantConverter::class)
abstract class NomadSafeDatabase : RoomDatabase() {

    abstract fun incidentDao(): IncidentDao
    abstract fun trustContactDao(): TrustContactDao
    abstract fun escortDao(): EscortDao
    abstract fun outboxDao(): OutboxDao
    abstract fun staticFeatureDao(): StaticFeatureDao

    companion object {
        const val NAME = "nomadsafe.db"
    }
}

/**
 * Conversor de [kotlinx.datetime.Instant] a epoch millis.
 *
 * Se guarda epoch UTC y no un string ISO porque ocupa 8 bytes en vez de ~30 y
 * porque comparar rangos de tiempo (`WHERE occurred_at > ?`) es una comparación
 * de enteros en vez de un orden lexicográfico de strings.
 */
class InstantConverter {
    @TypeConverter
    fun fromInstant(value: kotlinx.datetime.Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun toInstant(value: Long?): kotlinx.datetime.Instant? =
        value?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Entidades
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "incidents", indices = [androidx.room.Index("occurred_at"), androidx.room.Index("latitude"), androidx.room.Index("longitude")])
data class IncidentEntity(
    @PrimaryKey val id: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "occurred_at") val occurredAt: kotlinx.datetime.Instant,
    @ColumnInfo(name = "reported_at") val reportedAt: kotlinx.datetime.Instant,
    val timing: String,
    val status: String,
    val description: String?,
    @ColumnInfo(name = "reporter_id") val reporterId: String?,
    @ColumnInfo(name = "is_anonymous") val isAnonymous: Boolean,
    @ColumnInfo(name = "confirmation_count") val confirmationCount: Int,
    @ColumnInfo(name = "dispute_count") val disputeCount: Int,
    @ColumnInfo(name = "accuracy_meters") val accuracyMeters: Float?,
    val neighborhood: String?,
    /** True mientras sólo exista localmente y no tenga confirmación del servidor. */
    @ColumnInfo(name = "is_pending_sync", defaultValue = "false") val isPendingSync: Boolean = false,
    /** True si lo creó este dispositivo. Permite mostrar "tu reporte" en el mapa. */
    @ColumnInfo(name = "is_mine", defaultValue = "false") val isMine: Boolean = false,
)

@Entity(tableName = "trust_contacts")
data class TrustContactEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "phone_e164") val phoneE164: String,
    val relationship: String?,
    @ColumnInfo(name = "is_nomadsafe_user") val isNomadSafeUser: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: kotlinx.datetime.Instant,
    val priority: Int,
    @ColumnInfo(name = "is_pending_sync", defaultValue = "false") val isPendingSync: Boolean = false,
)

@Entity(tableName = "escort_sessions")
data class EscortSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "started_at") val startedAt: kotlinx.datetime.Instant,
    @ColumnInfo(name = "destination_lat") val destinationLat: Double?,
    @ColumnInfo(name = "destination_lon") val destinationLon: Double?,
    @ColumnInfo(name = "destination_label") val destinationLabel: String?,
    @ColumnInfo(name = "estimated_arrival") val estimatedArrival: kotlinx.datetime.Instant?,
    val status: String,
    @ColumnInfo(name = "ended_at") val endedAt: kotlinx.datetime.Instant?,
    @ColumnInfo(name = "voice_detection_enabled") val voiceDetectionEnabled: Boolean,
    @ColumnInfo(name = "last_known_lat") val lastKnownLat: Double?,
    @ColumnInfo(name = "last_known_lon") val lastKnownLon: Double?,
    @ColumnInfo(name = "last_check_in_at") val lastCheckInAt: kotlinx.datetime.Instant?,
)

@Entity(tableName = "sos_events")
data class SosEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "escort_session_id") val escortSessionId: String?,
    @ColumnInfo(name = "user_id") val userId: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "accuracy_meters") val accuracyMeters: Float?,
    @ColumnInfo(name = "triggered_at") val triggeredAt: kotlinx.datetime.Instant,
    val trigger: String,
    @ColumnInfo(name = "recognized_phrase") val recognizedPhrase: String?,
    @ColumnInfo(name = "battery_level") val batteryLevel: Int?,
    @ColumnInfo(name = "is_moving") val isMoving: Boolean,
    @ColumnInfo(name = "speed_mps") val speedMetersPerSecond: Float?,
    @ColumnInfo(name = "notified_contact_ids") val notifiedContactIds: String,
    val status: String,
    @ColumnInfo(name = "is_pending_sync", defaultValue = "false") val isPendingSync: Boolean = false,
)

/**
 * Cola de operaciones que no se pudieron enviar.
 *
 * ── Por qué un outbox y no reintentos en memoria ─────────────────────────────
 * Un SOS o un reporte generado sin señal debe sobrevivir a que el proceso muera.
 * Si el reintento viviera en una coroutine, se perdería al matar la app — y ése
 * es justamente el escenario (teléfono sin señal, usuario cierra la app, se
 * recupera cobertura horas después).
 *
 * [payload] es el JSON exacto que se va a mandar a PostgREST. Se guarda ya
 * serializado para que el worker de sincronización no necesite conocer los DTOs
 * ni reconstruir objetos de dominio que podrían haber cambiado de forma entre
 * versiones de la app.
 */
@Entity(tableName = "outbox", indices = [androidx.room.Index("created_at")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Tabla de Postgres destino, p. ej. "incidents". */
    val table: String,
    /** Operación HTTP: INSERT, PATCH, DELETE. */
    val operation: OutboxOperation,
    val payload: String,
    /** Filtro para PATCH/DELETE, p. ej. "id=eq.abc". Null para INSERT. */
    val filter: String?,
    @ColumnInfo(name = "created_at") val createdAt: kotlinx.datetime.Instant,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    /**
     * Prioridad alta = SOS. Se procesa primero y con menos backoff.
     *
     * Un SOS encolado detrás de 40 reportes de la comunidad tardaría minutos en
     * salir, y esos minutos son exactamente los que importan.
     */
    @ColumnInfo(name = "is_critical", defaultValue = "false") val isCritical: Boolean = false,
)

enum class OutboxOperation { INSERT, PATCH, DELETE }

/**
 * Features estáticas de OpenStreetMap (iluminación, tipo de vía).
 *
 * Se cachean localmente porque cambian muy poco: una calle no se ilumina de un
 * día para el otro. Descargarlas en cada apertura del mapa sería lento y caro.
 */
@Entity(tableName = "static_features", indices = [androidx.room.Index("cell_key")])
data class StaticFeatureEntity(
    /** Clave de celda de la grilla, p. ej. "-8.1120:-79.0290". */
    @PrimaryKey @ColumnInfo(name = "cell_key") val cellKey: String,
    @ColumnInfo(name = "center_lat") val centerLat: Double,
    @ColumnInfo(name = "center_lon") val centerLon: Double,
    val lit: Boolean?,
    @ColumnInfo(name = "road_class") val roadClass: String,
    @ColumnInfo(name = "street_name") val streetName: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: kotlinx.datetime.Instant,
)

// ─────────────────────────────────────────────────────────────────────────────
// DAOs
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface IncidentDao {

    /**
     * Incidentes dentro de un bounding box y desde una fecha.
     *
     * El filtro espacial se hace con `BETWEEN` sobre lat/lon indexados, que es la
     * forma barata de acotar en SQLite. La geometría fina (distancia radial
     * exacta) la aplica el motor de riesgo en memoria sobre el conjunto ya
     * acotado, que es donde corresponde: PostGIS en el servidor para agregar,
     * bounding box en el cliente para acotar.
     */
    @Query(
        """
        SELECT * FROM incidents
        WHERE latitude BETWEEN :south AND :north
          AND longitude BETWEEN :west AND :east
          AND occurred_at > :sinceEpochMillis
          AND status != 'removed'
        ORDER BY occurred_at DESC
        LIMIT :limit
        """,
    )
    fun observeInBounds(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        sinceEpochMillis: Long,
        limit: Int = 1_000,
    ): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents WHERE id = :id")
    suspend fun findById(id: String): IncidentEntity?

    @Upsert
    suspend fun upsertAll(incidents: List<IncidentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(incident: IncidentEntity)

    @Query("UPDATE incidents SET confirmation_count = confirmation_count + 1 WHERE id = :id")
    suspend fun incrementConfirmation(id: String)

    @Query("UPDATE incidents SET dispute_count = dispute_count + 1 WHERE id = :id")
    suspend fun incrementDispute(id: String)

    @Query("UPDATE incidents SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** Marca un incidente local como sincronizado, reemplazando el id provisional. */
    @Query("UPDATE incidents SET is_pending_sync = 0, id = :serverId WHERE id = :localId")
    suspend fun markSynced(localId: String, serverId: String)

    @Query("SELECT COUNT(*) FROM incidents WHERE is_pending_sync = 1")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM incidents WHERE is_pending_sync = 1")
    suspend fun pendingCount(): Int

    /**
     * Purga incidentes viejos que ya no se muestran en el mapa.
     *
     * Sin esto la tabla crece indefinidamente y cada consulta de viewport se hace
     * más lenta. Se conserva sólo lo que el motor de riesgo puede llegar a usar.
     */
    @Query("DELETE FROM incidents WHERE occurred_at < :olderThanEpochMillis AND is_pending_sync = 0")
    suspend fun purgeOlderThan(olderThanEpochMillis: Long): Int
}

@Dao
interface TrustContactDao {
    @Query("SELECT * FROM trust_contacts ORDER BY priority ASC, created_at ASC")
    fun observeAll(): Flow<List<TrustContactEntity>>

    @Query("SELECT * FROM trust_contacts ORDER BY priority ASC")
    suspend fun getAll(): List<TrustContactEntity>

    @Query("SELECT COUNT(*) FROM trust_contacts")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(contact: TrustContactEntity)

    @Query("DELETE FROM trust_contacts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE trust_contacts SET is_nomadsafe_user = 1 WHERE id = :id")
    suspend fun markAsNomadSafeUser(id: String)
}

@Dao
interface EscortDao {
    @Query("SELECT * FROM escort_sessions WHERE status IN ('active','escalated','sos') ORDER BY started_at DESC LIMIT 1")
    fun observeActive(): Flow<EscortSessionEntity?>

    @Query("SELECT * FROM escort_sessions WHERE status IN ('active','escalated','sos') ORDER BY started_at DESC LIMIT 1")
    suspend fun findActive(): EscortSessionEntity?

    @Upsert
    suspend fun upsert(session: EscortSessionEntity)

    @Query("UPDATE escort_sessions SET status = :status, ended_at = :endedAt WHERE id = :id")
    suspend fun endSession(id: String, status: String, endedAt: kotlinx.datetime.Instant?)

    @Query(
        """
        UPDATE escort_sessions
        SET last_known_lat = :lat, last_known_lon = :lon, last_check_in_at = COALESCE(last_check_in_at, :checkIn)
        WHERE id = :id
        """,
    )
    suspend fun updateLocation(id: String, lat: Double, lon: Double, checkIn: kotlinx.datetime.Instant?)

    @Query("SELECT * FROM sos_events ORDER BY triggered_at DESC LIMIT :limit")
    fun observeSosHistory(limit: Int): Flow<List<SosEventEntity>>

    @Upsert
    suspend fun upsertSos(event: SosEventEntity)

    @Query("UPDATE sos_events SET status = :status WHERE id = :id")
    suspend fun updateSosStatus(id: String, status: String)
}

@Dao
interface OutboxDao {

    /**
     * Operaciones pendientes, críticos primero y luego por antigüedad.
     *
     * El orden importa: sin `is_critical DESC`, un SOS encolado detrás de decenas
     * de reportes comunitarios esperaría su turno, y en una emergencia eso es
     * inaceptable.
     */
    @Query("SELECT * FROM outbox WHERE is_critical DESC, created_at ASC LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun enqueue(entry: OutboxEntity): Long

    @Delete
    suspend fun remove(entry: OutboxEntity)

    @Query("UPDATE outbox SET attempt_count = attempt_count + 1, last_error = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String?)

    /**
     * Descarta operaciones demasiado viejas o demasiado reintentadas.
     *
     * Límite deliberado: un reporte de hace tres días ya no es útil para el mapa
     * (el riesgo decae) y seguir reintentándolo gasta batería y datos. Los
     * críticos (SOS) NO se purgan por edad: se conservan hasta que salgan.
     */
    @Query(
        """
        DELETE FROM outbox
        WHERE is_critical = 0
          AND (attempt_count >= :maxAttempts OR created_at < :olderThanEpochMillis)
        """,
    )
    suspend fun purgeStale(maxAttempts: Int, olderThanEpochMillis: Long): Int
}

@Dao
interface StaticFeatureDao {
    @Query(
        """
        SELECT * FROM static_features
        WHERE center_lat BETWEEN :south AND :north
          AND center_lon BETWEEN :west AND :east
        """
    )
    suspend fun inBounds(south: Double, west: Double, north: Double, east: Double): List<StaticFeatureEntity>

    @Upsert
    suspend fun upsertAll(features: List<StaticFeatureEntity>)

    @Query("SELECT MAX(fetched_at) FROM static_features")
    suspend fun lastUpdatedAt(): kotlinx.datetime.Instant?

    @Query("DELETE FROM static_features WHERE fetched_at < :olderThan")
    suspend fun purgeOlderThan(olderThan: kotlinx.datetime.Instant): Int
}
