package com.nomadsafe.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nomadsafe.app.core.util.NsLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Worker que vacía el outbox cuando hay conectividad.
 *
 * ── Por qué un Worker con restricción de red ─────────────────────────────────
 * [Constraints] con [NetworkType.CONNECTED] hace que WorkManager NO ejecute el
 * trabajo sin red y lo corra apenas vuelva la conectividad. Eso es exactamente la
 * semántica que necesita un outbox, y reimplementarla con un listener de
 * conectividad propio sería más código, menos fiable y gastaría batería.
 *
 * WorkManager además persiste el trabajo: sobrevive a reinicios del dispositivo y
 * a que la app sea matada, que es justo el escenario de "reporté sin señal en el
 * sótano, salí a la calle y el teléfono se reinició".
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxSyncManager: OutboxSyncManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        NsLog.i(TAG, "SyncWorker iniciado (intento ${runAttemptCount + 1})")

        // Mantenimiento: descartar operaciones abandonadas antes de intentar enviar.
        // Sin esto la cola crece con reportes de hace días que ya no le sirven a nadie.
        runCatching { outboxSyncManager.purgeStale() }

        return when (val outcome = outboxSyncManager.drain()) {
            // Sin red: se reintenta más tarde con backoff de WorkManager.
            OutboxSyncManager.SyncOutcome.Offline -> Result.retry()

            // Otro drenaje ya estaba en curso; no hay nada que hacer.
            OutboxSyncManager.SyncOutcome.Skipped -> Result.success()

            is OutboxSyncManager.SyncOutcome.Completed -> {
                if (outcome.remaining > 0 && outcome.sent > 0) {
                    // Progreso parcial: se reintenta para terminar la cola.
                    Result.retry()
                } else {
                    Result.success()
                }
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "nomadsafe-outbox-sync"

        /** Programación periódica de respaldo. */
        const val PERIODIC_WORK_NAME = "nomadsafe-outbox-sync-periodic"

        /**
         * Encola un drenaje inmediato, requerido por conectividad.
         *
         * REPLACE a propósito: si ya hay uno pendiente, no tiene sentido acumular
         * varios idénticos. El outbox es idempotente, pero N ejecuciones
         * concurrentes serían trabajo y batería desperdiciados.
         */
        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }.onFailure { NsLog.e(TAG, "No se pudo encolar la sincronización inmediata", it) }
        }
    }
}
