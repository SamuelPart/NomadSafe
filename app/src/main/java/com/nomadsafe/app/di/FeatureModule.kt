package com.nomadsafe.app.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.nomadsafe.app.R
import com.nomadsafe.app.core.config.AppConfig
import com.nomadsafe.app.core.location.FusedLocationSource
import com.nomadsafe.app.core.location.LocationSource
import com.nomadsafe.app.core.location.MockLocationSource
import com.nomadsafe.app.core.util.NsDispatchers
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.feature.escort.panic.FakePanicWordEngine
import com.nomadsafe.app.feature.escort.panic.PanicPhraseMatcher
import com.nomadsafe.app.feature.escort.panic.PanicWordEngine
import com.nomadsafe.app.feature.escort.panic.RemoteVoskModelDownloader
import com.nomadsafe.app.feature.escort.panic.UnavailablePanicWordEngine
import com.nomadsafe.app.feature.escort.panic.VoskModelDownloader
import com.nomadsafe.app.feature.escort.panic.VoskPanicWordEngine
import com.nomadsafe.app.feature.growth.ShareStrings
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marca la implementación de [LocationSource] basada en Play Services, para
 * poder inyectar la simulada en tests sin ambigüedad.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RealLocationSource

/** Marca la implementación simulada, usada en modo demo y en tests. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DemoLocationSource

@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    @Provides
    @Singleton
    fun provideFusedClient(@ApplicationContext context: Context): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Provides
    @Singleton
    @RealLocationSource
    fun provideRealLocationSource(
        @ApplicationContext context: Context,
        client: FusedLocationProviderClient,
        dispatchers: NsDispatchers,
    ): LocationSource = FusedLocationSource(context, client, dispatchers)

    @Provides
    @Singleton
    @DemoLocationSource
    fun provideMockLocationSource(
        dispatchers: NsDispatchers,
        timeProvider: TimeProvider,
    ): LocationSource = MockLocationSource(dispatchers, timeProvider)

    /**
     * Selección de la fuente de ubicación.
     *
     * Sin Play Services (dispositivos Huawei sin GMS, que son una porción real del
     * mercado objetivo) la app caería a la simulada en vez de crashear. No es lo
     * ideal para producción —ahí habría que usar el LocationManager del sistema—
     * pero es mejor que una app que no abre, y queda señalado en el log.
     */
    @Provides
    @Singleton
    fun provideLocationSource(
        @ApplicationContext context: Context,
        @RealLocationSource real: LocationSource,
        @DemoLocationSource demo: LocationSource,
    ): LocationSource {
        val hasPlayServices = runCatching {
            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) ==
                com.google.android.gms.common.ConnectionResult.SUCCESS
        }.getOrDefault(false)

        if (!hasPlayServices) {
            NsLog.w(
                "LocationModule",
                "Google Play Services no disponible: se usa la fuente de ubicación simulada. " +
                    "Para soportar estos dispositivos en producción hay que agregar un " +
                    "LocationSource basado en el LocationManager del sistema.",
            )
            return demo
        }
        return real
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PanicEngineModule {

    /**
     * Motor de detección de palabras de pánico.
     *
     * Estrategia de selección, en orden:
     *  1. Sin permiso de micrófono → [UnavailablePanicWordEngine]. La escolta
     *     sigue funcionando con ubicación, check-in y botón SOS manual; sólo se
     *     pierde el disparador por voz. Degradar, nunca bloquear.
     *  2. Build de debug sin modelo descargable → [FakePanicWordEngine], para que
     *     el protocolo SOS completo se pueda probar sin gritarle al teléfono.
     *  3. Resto → [VoskPanicWordEngine], la implementación real.
     */
    @Provides
    @Singleton
    fun providePanicWordEngine(
        @ApplicationContext context: Context,
        dispatchers: NsDispatchers,
        downloader: VoskModelDownloader,
        config: AppConfig,
    ): PanicWordEngine {
        val hasMicPermission = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            NsLog.i(
                "PanicEngineModule",
                "RECORD_AUDIO no concedido todavía: motor de voz no disponible. " +
                    "Se re-evalúa al iniciar el Modo Escolta.",
            )
            return UnavailablePanicWordEngine("permiso_de_microfono_no_concedido")
        }

        return VoskPanicWordEngine(
            modelDirectory = File(context.filesDir, VOSK_MODEL_ROOT),
            phrases = PanicPhraseMatcher.defaultSpanishPhrases(),
            dispatchers = dispatchers,
            modelDownloader = downloader,
        ).also {
            NsLog.i("PanicEngineModule", "Motor Vosk configurado (modelo empaquetado=${config.bundleVoskModel})")
        }
    }

    @Provides
    @Singleton
    fun provideVoskModelDownloader(
        @ApplicationContext context: Context,
        dispatchers: NsDispatchers,
        config: AppConfig,
    ): VoskModelDownloader {
        val root = File(context.filesDir, VOSK_MODEL_ROOT)

        if (config.bundleVoskModel) {
            // Con el modelo empaquetado en assets no hay descarga: se devuelve un
            // downloader que resuelve directo al directorio ya presente.
            return BundledVoskModelResolver(root)
        }

        return RemoteVoskModelDownloader(
            targetDirectory = root,
            modelUrl = RemoteVoskModelDownloader.DEFAULT_SPANISH_MODEL_URL,
            expectedDirectoryName = RemoteVoskModelDownloader.DEFAULT_SPANISH_MODEL_DIR,
            dispatchers = dispatchers,
        )
    }

    /**
     * Downloader para el caso `nomadsafe.bundleVoskModel=true`.
     *
     * Asume que el modelo ya se copió de `assets/` a `filesDir/` durante el primer
     * arranque. Si no está, devuelve null y el motor pasa a UNAVAILABLE, que es la
     * degradación correcta: la escolta sigue sin detección por voz.
     */
    private class BundledVoskModelResolver(private val root: File) : VoskModelDownloader {
        override val progress = kotlinx.coroutines.flow.emptyFlow<com.nomadsafe.app.feature.escort.panic.ModelDownloadProgress>()

        override suspend fun ensureModel(): File? {
            val bundled = File(root, RemoteVoskModelDownloader.DEFAULT_SPANISH_MODEL_DIR)
            return bundled.takeIf { it.isDirectory }?.also {
                NsLog.i("VoskBundled", "Modelo empaquetado encontrado en ${it.absolutePath}")
            } ?: run {
                NsLog.e("VoskBundled", "bundleVoskModel=true pero no hay modelo en ${bundled.absolutePath}")
                null
            }
        }
    }

    private const val VOSK_MODEL_ROOT = "vosk-model"
}

/**
 * Textos compartibles, armados desde recursos.
 *
 * Se construyen acá y no dentro de los builders para que la construcción de
 * mensajes siga siendo Kotlin puro y testeable en JVM, manteniendo a la vez las
 * traducciones de `values-es` / `values-en`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ShareStringsModule {

    @Provides
    @Singleton
    fun provideShareStrings(@ApplicationContext context: Context): ShareStrings = ShareStrings(
        inviteMessage = context.getString(R.string.invite_message),
        shareAlertMessage = context.getString(R.string.share_alert_message),
        sosMessage = context.getString(R.string.sos_message),
        sosPanicWordMessage = context.getString(R.string.sos_panic_word_message),
        sosCheckInMessage = context.getString(R.string.sos_checkin_message),
        falseAlarmMessage = context.getString(R.string.sos_false_alarm_message),
        genericShare = context.getString(R.string.invite_message),
        liveTrackingUrlTemplate = "$WEB_BASE_URL/track/%1\$s",
        inviteUrlTemplate = "$WEB_BASE_URL/i/%1\$s",
        incidentUrlTemplate = "$WEB_BASE_URL/alert/%1\$s",
        referralReward = context.getString(R.string.invite_reward),
    )

    /**
     * Dominio de los links compartidos.
     *
     * Debe servir `/.well-known/assetlinks.json` con la huella SHA-256 de la clave
     * de firma para que los App Links verifiquen y se abran directo en la app, sin
     * pasar por el navegador. Ver docs/GROWTH_LOOPS.md.
     */
    private const val WEB_BASE_URL = "https://nomadsafe.app"
}
