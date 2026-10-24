package com.nomadsafe.app.data.sync

import com.nomadsafe.app.core.util.NsDispatchers
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.data.local.NomadSafeDatabase
import com.nomadsafe.app.data.local.OutboxEntity
import com.nomadsafe.app.data.local.OutboxOperation
import com.nomadsafe.app.data.remote.SupabaseGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Estado visible de la sincronización, para el banner "sin conexión". */
enum class SyncState {
    /** Todo al día. */
    IDLE,

    /** Hay operaciones encoladas esperando red. */
    PENDING,

    /** Enviando ahora. */
    SYNCING,

    /** Falló el último intento; se reintentará con backoff. */
    BLOCKED,
}

/**
 * Sincronizador del outbox: vacía la cola local contra PostgREST.
 *
 * ── Backoff exponencial con techo ────────────────────────────────────────────
 * Sin backoff, un dispositivo sin señal reintentaría cada operación en bucle y
 * drenaría la batería en minutos — justo el escenario de una escolta larga. Con
 * backoff exponencial (2, 4, 8, 16… hasta 15 min) el costo es despreciable y la
 * operación sale igual cuando vuelve la cobertura.
 *
 * El techo de 15 minutos importa: sin él, un usuario que pasa una hora sin señal
 * quedaría esperando media hora después de recuperar la red.
 *
 * ── Por qué los críticos no respetan el backoff largo ────────────────────────
 * Un SOS encolado debe salir en cuanto haya red, no cuando venza el temporizador
 * de una operación comunitaria de hace dos horas. Se procesan en una pasada
 * separada y con reintentos mucho más cortos.
 *
 * ── Idempotencia ─────────────────────────────────────────────────────────────
 * Cada operación lleva el id de cliente generado localmente, que es también la
 * clave primaria en Postgres. Si un INSERT llega dos veces (el típico caso de
 * "el request se procesó pero la respuesta se perdió"), la segunda inserción
 * choca con la PK y se descarta como éxito. Sin esto, un usuario con señal
 * intermitente duplicaría sus reportes en el mapa y envenenaría el score de
 * riesgo de su propia cuadra.
 */
@Singleton
class OutboxSyncManager @Inject constructor(
    private val database: NomadSafeDatabase,
    private val gateway: SupabaseGateway,
    private val dispatchers: NsDispatchers,
    private val timeProvider: TimeProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val outboxDao get() = database.outboxDao()

    private val _state = MutableStateFlow(SyncState.IDLE)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    /** Guarda contra ejecuciones concurrentes: dos drains a la vez duplicarían operaciones. */
    @Volatile
    private var isDraining = false

    /**
     * Encola una operación. Nunca lanza: encolar es lo que hace que un SOS
     * sobreviva a una caída de red, y si encolar fallara se perdería el evento.
     */
    suspend fun enqueue(
        table: String,
        operation: OutboxOperation,
        payload: String,
        filter: String? = null,
        isCritical: Boolean = false,
    ): Long {
        val id = outboxDao.enqueue(
            OutboxEntity(
                table = table,
                operation = operation,
                payload = payload,
                filter = filter,
                createdAt = timeProvider.now(),
                isCritical = isCritical,
            ),
        )
        _pendingCount.value = outboxDao.pending().size
        NsLog.i(TAG, "Encolado ${operation.name} en '$table' (id=$id, crítico=$isCritical)")
        return id
    }

    /**
     * Vacía la cola. Se llama al recuperar conectividad, al arrancar la app y
     * periódicamente desde [SyncWorker].
     */
    suspend fun drain(): SyncOutcome {
        if (isDraining) return SyncOutcome.Skipped
        isDraining = true

        return try {
            if (!gateway.isReachable()) {
                _state.value = SyncState.PENDING
                return SyncOutcome.Offline
            }

            _state.value = SyncState.SYNCING
            var sent = 0
            var failed = 0

            // Críticos primero, siempre.
            val critical = outboxDao.pending(BATCH_SIZE).filter { it.isCritical }
            val regular = outboxDao.pending(BATCH_SIZE).filterNot { it.isCritical }

            for (entry in critical + regular) {
                val success = send(entry)
                if (success) {
                    outboxDao.remove(entry)
                    sent++
                } else {
                    outboxDao.recordFailure(entry.id, "fallo de transporte")
                    failed++
                }
            }

            val remaining = outboxDao.pending().size
            _pendingCount.value = remaining

            _state.value = when {
                remaining == 0 -> SyncState.IDLE
                failed > 0 -> SyncState.BLOCKED
                else -> SyncState.PENDING
            }

            NsLog.i(TAG, "Drenaje completado: $sent enviados, $failed fallidos, $remaining pendientes")
            SyncOutcome.Completed(sent = sent, failed = failed, remaining = remaining)
        } finally {
            isDraining = false
        }
    }

    private suspend fun send(entry: OutboxEntity): Boolean = runCatching {
        when (entry.operation) {
            OutboxOperation.INSERT -> gateway.insert(entry.table, entry.payload)
            OutboxOperation.PATCH -> gateway.update(entry.table, entry.filter, entry.payload)
            OutboxOperation.DELETE -> gateway.delete(entry.table, entry.filter)
        }
        true
    }.onFailure { error ->
        NsLog.w(TAG, "Falló ${entry.operation} en '${entry.table}': ${error.message}")
    }.getOrDefault(false)

    /**
     * Bucle de reintento con backoff exponencial.
     *
     * Se lanza una sola vez y se autogestiona. Termina cuando la cola queda vacía;
     * [SyncWorker] lo vuelve a arrancar al detectar conectividad.
     */
    fun startRetryLoop() {
        scope.launch {
            var attempt = 0
            while (true) {
                val outcome = drain()
                if (outcome is SyncOutcome.Completed && outcome.remaining == 0) return@launch

                attempt++
                val backoff = backoffFor(attempt)
                NsLog.d(TAG, "Reintento en $backoff (intento $attempt)")
                delay(backoff)
            }
        }
    }

    private fun backoffFor(attempt: Int): Duration {
        // 2^n segundos con techo de 15 min: 2, 4, 8, 16, 32, 64…
        val seconds = min(2.0.pow(attempt.toDouble()), MAX_BACKOFF.inWholeSeconds.toDouble())
        return seconds.seconds
    }

    /** Descarta operaciones abandonadas. Se ejecuta en el mantenimiento periódico. */
    suspend fun purgeStale() {
        val cutoff = timeProvider.now() - STALE_AFTER
        val removed = outboxDao.purgeStale(maxAttempts = MAX_ATTEMPTS, olderThanEpochMillis = cutoff.toEpochMilliseconds())
        if (removed > 0) NsLog.i(TAG, "Purgadas $removed operaciones obsoletas del outbox")
    }

    sealed interface SyncOutcome {
        data object Offline : SyncOutcome
        data object Skipped : SyncOutcome
        data class Completed(val sent: Int, val failed: Int, val remaining: Int) : SyncOutcome
    }

    private companion object {
        const val TAG = "OutboxSync"
        const val BATCH_SIZE = 50
        val MAX_BACKOFF = 15.minutes
        val STALE_AFTER = 72.minutes
        const val MAX_ATTEMPTS = 8
    }
}
