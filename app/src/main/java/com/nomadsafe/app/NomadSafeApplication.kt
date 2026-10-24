package com.nomadsafe.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mapbox.maps.MapboxOptions
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
        configureMapbox()

        // Los canales de notificación deben existir antes de que cualquier servicio
        // intente publicar. Crearlos acá es idempotente y barato.
        notificationFactory.ensureChannels()

        scheduleOutboxSync()
        drainOutboxOnStart()
    }

    /**
     * Configura el token de Mapbox por código.
     *
     * Se hace acá y no con el recurso `R.string.mapbox_access_token` por dos
     * razones: el token viene de `local.properties` (no debe estar versionado) y
     * `resValues` está desactivado globalmente en gradle.properties.
     *
     * Sin token, el SDK lanza al primer `MapView`. Por eso se valida y se registra
     * un aviso claro en vez de dejar que falle más adelante con un error críptico.
     */
    private fun configureMapbox() {
        if (!appConfig.hasMapCredentials) {
            NsLog.w(
                TAG,
                "MAPBOX_PUBLIC_TOKEN no configurado: el mapa no dibujará tiles. " +
                    "Agregalo en local.properties (ver docs/SETUP.md).",
            )
            return
        }
        runCatching { MapboxOptions.accessToken = appConfig.mapboxPublicToken }
            .onSuccess { NsLog.i(TAG, "Token de Mapbox configurado") }
            .onFailure { NsLog.e(TAG, "No se pudo configurar Mapbox", it) }
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
