package com.nomadsafe.app.core.config

import com.nomadsafe.app.BuildConfig
import com.nomadsafe.app.domain.model.GeoPoint

/**
 * Configuración de la aplicación, resuelta en tiempo de compilación desde
 * `local.properties` → `BuildConfig`.
 *
 * Se modela como clase inyectable (y no como acceso directo a `BuildConfig`)
 * por dos razones concretas:
 *
 *  1. Testeabilidad: los tests pueden construir un [AppConfig] con valores
 *     falsos sin tocar BuildConfig, que es estático y no se puede mockear.
 *  2. Un único lugar donde decidir "¿hay credenciales reales o corro en modo
 *     demo?". Esa decisión la toman [hasBackendCredentials] y
 *     [hasMapCredentials], y de ella dependen los bindings de Hilt.
 *
 * Sobre secretos: la anon key de Supabase y el public token de Mapbox son
 * credenciales PÚBLICAS por diseño de ambos proveedores. Van embebidas en el
 * cliente y la protección real es otra: RLS en Postgres y restricción por
 * paquete+firma en Mapbox. Ver docs/SECURITY_PRIVACY.md.
 */
data class AppConfig(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val mapboxPublicToken: String,
    val pilotCity: String,
    val pilotCenter: GeoPoint,
    val isFcmEnabled: Boolean,
    val bundleVoskModel: Boolean,
) {
    /**
     * Sin credenciales de Supabase la app no puede hablar con el backend.
     * [com.nomadsafe.app.di.DataModule] usa esto para inyectar repositorios
     * locales en vez de remotos, de modo que el proyecto corre desde el primer
     * clon sin configurar nada.
     */
    val hasBackendCredentials: Boolean
        get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    /** Sin token de Mapbox el mapa no dibuja tiles; se muestra el aviso correspondiente. */
    val hasMapCredentials: Boolean
        get() = mapboxPublicToken.isNotBlank()

    /**
     * La app corre "en vacío" cuando no hay backend. Se expone para mostrar un
     * banner honesto al usuario en vez de que vea spinners eternos.
     */
    val isDemoMode: Boolean
        get() = !hasBackendCredentials

    companion object {
        /** Lee BuildConfig una sola vez. Se invoca desde el módulo de Hilt. */
        fun fromBuildConfig(): AppConfig = AppConfig(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
            mapboxPublicToken = BuildConfig.MAPBOX_PUBLIC_TOKEN,
            pilotCity = BuildConfig.PILOT_CITY,
            pilotCenter = GeoPoint(
                latitude = BuildConfig.PILOT_LAT,
                longitude = BuildConfig.PILOT_LON,
            ),
            isFcmEnabled = BuildConfig.FCM_ENABLED,
            bundleVoskModel = BuildConfig.BUNDLE_VOSK_MODEL,
        )
    }
}

/**
 * Ciudad del piloto cuando no hay configuración.
 *
 * Se usa en modo demo para que el perfil local y el mapa tengan un centro
 * coherente en vez de (0,0), que cae en el Golfo de Guinea y hace que el mapa
 * aparezca vacío sin ninguna pista de por qué.
 */
const val DEMO_PILOT_CITY = "Trujillo"
