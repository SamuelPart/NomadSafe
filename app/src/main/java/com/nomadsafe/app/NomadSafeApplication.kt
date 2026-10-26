package com.nomadsafe.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.maplibre.android.MapLibre
import com.nomadsafe.app.core.config.AppConfig
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.data.sync.OutboxSyncManager
import com.nomadsafe.app.data.sync.SyncWorker
import com.nomadsafe.app.feature.escort.EscortNotificationFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Punto de entrada de la aplicación.
 *
 * Implementa [Configuration.Provider] para que WorkManager use el
 * [HiltWorkerFactory]. Sin esto, los Workers no pueden recibir dependencias
 * inyectadas y la sincronización del outbox tendría que construir sus propias
 * dependencias a mano.
 */
@HiltAndroidApp
class NomadSafeApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var appConfig: AppConfig
    @Inject lateinit var notificationFactory: EscortNotificationFactory
    @Inject lateinit var outboxSyncManager: OutboxSyncManager

    @Inject
    @com.nomadsafe.app.core.util.ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            // En debug los logs de WorkManager son útiles; en release ensucian.
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN,
            )
            .build()

    override fun onCreate() {
        super.onCreate()

        configureLogging()
        configureMapLibre()

        // Los canales de notificación deben existir antes de que cualquier servicio
        // intente publicar. Crearlos acá es idempotente y barato.
        notificationFactory.ensureChannels()

        scheduleOutboxSync()
        drainOutboxOnStart()
    }

    /**
     * Inicializa MapLibre antes de que exista ningún MapView.
     *
     * `MapLibre.getInstance(context)` es obligatorio: sin él, el primer MapView
     * lanza una IllegalStateException críptica. No hay token que configurar — esa
     * es justamente la diferencia con el SDK propietario que reemplazamos: el
     * render es open source y los tiles se piden a OpenStreetMap sin credenciales.
     */
    private fun configureMapLibre() {
        runCatching { MapLibre.getInstance(this) }
            .onSuccess { NsLog.i(TAG, "MapLibre inicializado (sin credenciales requeridas)") }
            .onFailure { NsLog.e(TAG, "No se pudo inicializar MapLibre", it) }
    }

    /**
     * Sincronización periódica del outbox.
     *
     * KEEP: si ya existe, no se reemplaza. Con UPDATE se reiniciaría el período en
     * cada arranque de la app y, en un usuario que abre la app seguido, el trabajo
     * nunca llegaría a ejecutarse.
     *
     * 15 minutos es el mínimo que permite WorkManager para trabajo periódico.
     */
    private fun scheduleOutboxSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_PERIOD_MINUTES, TimeUnit.MINUTES)
            .setInitialDelay(SYNC_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onFailure { NsLog.e(TAG, "No se pudo programar la sincronización periódica", it) }
    }

    /**
     * Drenaje inmediato al arrancar.
     *
     * Complementa al periódico: un reporte o SOS encolado anoche debe salir en
     * cuanto el usuario abre la app, no hasta 15 minutos después.
     */
    private fun drainOutboxOnStart() {
        applicationScope.launch {
            runCatching { outboxSyncManager.drain() }
                .onFailure { NsLog.w(TAG, "Drenaje inicial del outbox falló: ${it.message}") }
        }
    }

    private fun configureLogging() {
        // En release se silencian los logs verbose. No es sólo higiene: los logs de
        // esta app pueden contener coordenadas, y un logcat con ubicaciones es un
        // incidente de privacidad si el dispositivo se comparte o se revende.
        NsLog.verboseEnabled = BuildConfig.DEBUG

        NsLog.i(
            TAG,
            "NomadSafe ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · " +
                "piloto=${appConfig.pilotCity} · demo=${appConfig.isDemoMode}",
        )
    }

    private companion object {
        const val TAG = "NomadSafeApp"
        const val SYNC_PERIOD_MINUTES = 15L
        const val SYNC_INITIAL_DELAY_SECONDS = 10L
    }
}
