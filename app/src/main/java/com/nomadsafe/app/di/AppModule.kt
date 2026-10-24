package com.nomadsafe.app.di

import android.content.Context
import androidx.room.Room
import com.nomadsafe.app.core.config.AppConfig
import com.nomadsafe.app.core.util.ApplicationScope
import com.nomadsafe.app.core.util.DefaultDispatcher
import com.nomadsafe.app.core.util.DefaultNsDispatchers
import com.nomadsafe.app.core.util.IoDispatcher
import com.nomadsafe.app.core.util.MainDispatcher
import com.nomadsafe.app.core.util.NsDispatchers
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.SystemTimeProvider
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.data.local.NomadSafeDatabase
import com.nomadsafe.app.data.remote.SupabaseGateway
import com.nomadsafe.app.data.remote.SupabaseGatewayImpl
import com.nomadsafe.app.data.remote.UnconfiguredSupabaseGateway
import com.nomadsafe.app.feature.risk.RiskConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Dependencias de núcleo: configuración, dispatchers, reloj, JSON y base local.
 *
 * Todo lo que vive acá es agnóstico del dominio y no cambia entre features.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppConfig(): AppConfig = AppConfig.fromBuildConfig()

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @Singleton
    fun provideDispatchers(
        @IoDispatcher io: CoroutineDispatcher,
        @DefaultDispatcher default: CoroutineDispatcher,
        @MainDispatcher main: CoroutineDispatcher,
    ): NsDispatchers = DefaultNsDispatchers(io, default, main)

    /**
     * Alcance de vida de aplicación con [SupervisorJob].
     *
     * SupervisorJob es esencial acá: los repositorios lanzan refrescos en segundo
     * plano desde este scope. Con un Job normal, un fallo en el refresco de
     * incidentes cancelaría TODOS los hermanos, incluyendo la suscripción de
     * Realtime. Con supervisor, cada hijo falla aislado.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(dispatchers: NsDispatchers): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.io)

    /**
     * JSON global.
     *
     * `ignoreUnknownKeys` no es opcional: cuando el backend agregue una columna
     * nueva, las apps ya instaladas con una versión vieja del DTO deben seguir
     * funcionando. Sin este flag, agregar una columna al servidor rompe todas las
     * apps publicadas hasta que los usuarios actualicen.
     *
     * `explicitNulls = false` omite los nulls al serializar, para que PostgREST
     * aplique los DEFAULT de la columna en vez de escribir NULL explícito.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Base de datos Room.
     *
     * `fallbackToDestructiveMigration` está deliberadamente AUSENTE. En una app de
     * seguridad, destruir la base local significa borrar contactos de confianza y
     * SOS encolados en el outbox: datos que el usuario no puede volver a crear.
     * Toda migración debe ser explícita y probada. Si falta una migración, es
     * mejor un crash visible en desarrollo que una pérdida silenciosa en producción.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NomadSafeDatabase =
        Room.databaseBuilder(context, NomadSafeDatabase::class.java, NomadSafeDatabase.NAME)
            .build()

    /**
     * Configuración del motor de riesgo.
     *
     * Se provee acá (y no como valor por defecto en el constructor del motor) para
     * que sea el punto único donde cambiar de perfil: diurno, nocturno o de
     * arranque en frío. Más adelante esto se vuelve dinámico según la hora del día
     * y la densidad de reportes de la ciudad.
     */
    @Provides
    @Singleton
    fun provideRiskConfig(): RiskConfig = RiskConfig()
}

/**
 * Cableado del backend.
 *
 * Acá ocurre la decisión que permite que el proyecto compile y corra sin ninguna
 * credencial: si no hay URL/key de Supabase, se inyecta
 * [UnconfiguredSupabaseGateway] y la app funciona en modo local con Room.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSupabaseGateway(config: AppConfig, json: Json): SupabaseGateway {
        if (!config.hasBackendCredentials) {
            NsLog.w(
                "DataModule",
                "Sin credenciales de Supabase: modo local activo. " +
                    "Configurá SUPABASE_URL y SUPABASE_ANON_KEY en local.properties para conectar el backend.",
            )
            return UnconfiguredSupabaseGateway
        }
        NsLog.i("DataModule", "Supabase configurado: ${config.supabaseUrl}")
        return SupabaseGatewayImpl(config, json)
    }

    @Provides
    @Singleton
    fun provideIncidentRepository(
        impl: com.nomadsafe.app.data.repository.IncidentRepositoryImpl,
    ): com.nomadsafe.app.domain.repository.IncidentRepository = impl

    @Provides
    @Singleton
    fun provideStaticFeatureRepository(
        impl: com.nomadsafe.app.data.repository.StaticFeatureRepositoryImpl,
    ): com.nomadsafe.app.domain.repository.StaticFeatureRepository = impl

    @Provides
    @Singleton
    fun provideUserRepository(
        impl: com.nomadsafe.app.data.repository.UserRepositoryImpl,
    ): com.nomadsafe.app.domain.repository.UserRepository = impl

    @Provides
    @Singleton
    fun provideTrustContactRepository(
        impl: com.nomadsafe.app.data.repository.TrustContactRepositoryImpl,
    ): com.nomadsafe.app.domain.repository.TrustContactRepository = impl

    @Provides
    @Singleton
    fun provideEscortRepository(
        impl: com.nomadsafe.app.data.repository.EscortRepositoryImpl,
    ): com.nomadsafe.app.domain.repository.EscortRepository = impl

    @Provides
    @Singleton
    fun provideRouteRepository(
        impl: com.nomadsafe.app.data.repository.RouteRepositoryImpl,
    ): com.nomadsafe.app.domain.repository.RouteRepository = impl
}
