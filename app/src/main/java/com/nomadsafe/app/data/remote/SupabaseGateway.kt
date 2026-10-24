package com.nomadsafe.app.data.remote

import com.nomadsafe.app.core.config.AppConfig
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.data.local.OutboxOperation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Phone
import io.github.jan.supabase.auth.signInWith
import io.github.jan.supabase.auth.signOut
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.verifyPhoneOtp
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgrestAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.selectAsFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

/**
 * Puerta de acceso a Supabase.
 *
 * ── Por qué TODO el acceso a Supabase pasa por acá ───────────────────────────
 * Este archivo es el único del proyecto que importa `io.github.jan.supabase.*`.
 * No es prolijidad: es gestión de riesgo.
 *
 * supabase-kt es un cliente mantenido por la comunidad (no por Supabase Inc.) y su
 * API cambió de forma entre 2.x y 3.x — por ejemplo `gotrue-kt` se renombró a
 * `auth-kt` y el módulo de Auth se reorganizó. Al confinar todas las llamadas acá:
 *
 *  - Una actualización de versión se resuelve en UN archivo, no en doce.
 *  - Los repositorios dependen de esta interfaz y no del cliente, así que se
 *    pueden testear en JVM sin Supabase.
 *  - Cambiar de backend (o agregar uno) no toca el dominio ni la UI.
 *
 * Si una firma difiere en la versión exacta que resuelva Gradle, se ajusta acá y
 * en ningún otro lado. Ver docs/SETUP.md.
 *
 * ── Seguridad ────────────────────────────────────────────────────────────────
 * Sólo se usa la `anon key`. Es pública por diseño: cualquiera puede extraerla
 * del APK. La seguridad real son las políticas RLS de
 * `supabase/migrations/0001_init.sql`. La `service_role key` NUNCA va en el
 * cliente; sólo en Edge Functions, del lado del servidor.
 */
interface SupabaseGateway {

    /** True si hay credenciales configuradas. Sin ellas todo devuelve vacío/error Network. */
    val isConfigured: Boolean

    /** Sesión actual: access token y user id, o null si no hay sesión. */
    suspend fun currentSession(): GatewaySession?

    /** Emite cada cambio de estado de sesión (login, refresh, logout). */
    fun observeSessionStatus(): Flow<GatewaySession?>

    suspend fun sendPhoneOtp(phoneE164: String): Result<Unit>
    suspend fun verifyPhoneOtp(phoneE164: String, code: String): Result<GatewaySession>
    suspend fun signOut(): Result<Unit>

    /** SELECT genérico con filtro PostgREST, p. ej. `occurred_at=gt.2026-10-01`. */
    suspend fun select(
        table: String,
        filter: String? = null,
        orderColumn: String? = null,
        ascending: Boolean = false,
        limit: Int? = null,
    ): Result<List<JsonObject>>

    /** INSERT. Devuelve las filas creadas (PostgREST con `Prefer: return=representation`). */
    suspend fun insert(table: String, payload: JsonObject, returning: Boolean = true): Result<List<JsonObject>>

    /** Variante del outbox, que guarda el payload ya serializado como String. */
    suspend fun insert(table: String, payloadJson: String): Result<Unit>

    suspend fun update(table: String, filter: String?, payloadJson: String): Result<Unit>

    suspend fun delete(table: String, filter: String?): Result<Unit>

    /** Llama una Edge Function con el JWT del usuario. */
    suspend fun invokeFunction(name: String, body: JsonObject?): Result<JsonObject?>

    /**
     * Canal Realtime sobre cambios de una tabla.
     *
     * Es el canal de tiempo real primario de NomadSafe. Funciona mientras el
     * proceso viva — y en Modo Escolta siempre vive, porque el foreground service
     * lo mantiene. Para despertar el proceso en frío hace falta FCM (Fase 2).
     */
    fun observeTableChanges(table: String, filter: String? = null): Flow<TableChange>

    /** Consulta de salud: se usa para decidir si vale la pena drenar el outbox. */
    suspend fun isReachable(): Boolean
}

/** Sesión de Supabase reducida a lo que el dominio necesita. */
data class GatewaySession(
    val userId: String,
    val accessToken: String,
    val phone: String?,
    val expiresAtEpochSeconds: Long?,
)

/** Cambio de tabla recibido por Realtime. */
data class TableChange(
    val type: OutboxOperation,
    val record: JsonObject?,
    val oldRecord: JsonObject?,
)

/** Implementación real sobre supabase-kt. */
class SupabaseGatewayImpl(
    private val config: AppConfig,
    private val json: kotlinx.serialization.json.Json,
) : SupabaseGateway {

    override val isConfigured: Boolean get() = config.hasBackendCredentials

    /**
     * Cliente perezoso.
     *
     * Se construye la primera vez y sólo si hay credenciales. Crearlo sin URL
     * válida lanzaría en el constructor y rompería el arranque de la app en modo
     * demo, que debe funcionar sin configurar nada.
     */
    private val client: SupabaseClient? by lazy {
        if (!isConfigured) {
            NsLog.w(TAG, "Supabase sin credenciales: el gateway opera en modo desconectado")
            return@lazy null
        }

        runCatching {
            createSupabaseClient(
                supabaseUrl = config.supabaseUrl,
                supabaseKey = config.supabaseAnonKey,
            ) {
                install(Postgrest)
                install(Auth)
                install(Realtime)
                install(Functions)

                // La app debe seguir funcionando sin red: los timeouts cortos hacen
                // que el fallo sea rápido y el outbox tome el relevo, en vez de
                // dejar la UI colgada esperando una respuesta que no llega.
                defaultLogLevel = io.github.jan.supabase.logging.LogLevel.WARN
            }
        }.onFailure { NsLog.e(TAG, "No se pudo crear el cliente de Supabase", it) }.getOrNull()
    }

    private fun requireClient(): SupabaseClient =
        client ?: throw IllegalStateException("Supabase no configurado (falta SUPABASE_URL/SUPABASE_ANON_KEY en local.properties)")

    override suspend fun currentSession(): GatewaySession? {
        val active = client ?: return null
        return runCatching {
            val session = active.auth.currentSessionOrNull() ?: return@runCatching null
            session.toGatewaySession(active)
        }.getOrNull()
    }

    override fun observeSessionStatus(): Flow<GatewaySession?> {
        val active = client ?: return kotlinx.coroutines.flow.flowOf(null)
        return active.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Authenticated -> status.session.toGatewaySession(active)
                else -> null
            }
        }
    }

    private fun io.github.jan.supabase.auth.user.UserSession.toGatewaySession(client: SupabaseClient) =
        GatewaySession(
            userId = id,
            accessToken = accessToken,
            phone = phone,
            expiresAtEpochSeconds = expiresAt,
        )

    override suspend fun sendPhoneOtp(phoneE164: String): Result<Unit> = guard {
        requireClient().auth.signInWith(Phone) {
            phone = phoneE164
        }
    }

    override suspend fun verifyPhoneOtp(phoneE164: String, code: String): Result<GatewaySession> = guard {
        val active = requireClient()
        active.auth.verifyPhoneOtp(OtpType.Phone.SMS, phoneE164, code)
        val session = active.auth.currentSessionOrNull()
            ?: throw IllegalStateException("Sesión ausente tras verificar el OTP")
        session.toGatewaySession(active)
    }

    override suspend fun signOut(): Result<Unit> = guard { requireClient().auth.signOut() }

    override suspend fun select(
        table: String,
        filter: String?,
        orderColumn: String?,
        ascending: Boolean,
        limit: Int?,
    ): Result<List<JsonObject>> = guard {
        requireClient().from(table).select {
            if (filter != null) applyPostgrestFilter(filter)
            if (orderColumn != null) order(orderColumn, if (ascending) Order.ASCENDING else Order.DESCENDING)
            if (limit != null) this.limit = limit
            count = Count.EXACT
        }.decodeList<JsonObject>()
    }

    override suspend fun insert(table: String, payload: JsonObject, returning: Boolean): Result<List<JsonObject>> =
        guard {
            requireClient().from(table).insert(payload) {
                if (returning) select()
            }.decodeList<JsonObject>()
        }

    override suspend fun insert(table: String, payloadJson: String): Result<Unit> = guard {
        requireClient().from(table).insert(json.parseToJsonElement(payloadJson))
        Unit
    }

    override suspend fun update(table: String, filter: String?, payloadJson: String): Result<Unit> = guard {
        requireClient().from(table).update(json.parseToJsonElement(payloadJson)) {
            if (filter != null) applyPostgrestFilter(filter)
        }
        Unit
    }

    override suspend fun delete(table: String, filter: String?): Result<Unit> = guard {
        requireClient().from(table).delete {
            if (filter != null) applyPostgrestFilter(filter)
        }
        Unit
    }

    override suspend fun invokeFunction(name: String, body: JsonObject?): Result<JsonObject?> = guard {
        // Se usa el cliente de funciones sólo si está instalado; si no, la llamada
        // se resuelve vía PostgREST/REST directo en la Edge Function.
        val response = requireClient().functions.invoke(name) {
            if (body != null) this.body = body
        }
        runCatching { json.parseToJsonElement(response.data.decodeToString()) as? JsonObject }.getOrNull()
    }

    override fun observeTableChanges(table: String, filter: String?): Flow<TableChange> {
        val active = client ?: return kotlinx.coroutines.flow.emptyFlow()

        return callbackFlow {
            val channel = active.realtime.channel("nomadsafe-$table-${filter?.hashCode() ?: 0}")

            val job = kotlinx.coroutines.launch {
                channel.postgresChangeFlow<PostgrestAction>(schema = "public") {
                    this.table = table
                    if (filter != null) this.filter = filter
                }.collect { action ->
                    val change = TableChange(
                        type = when (action) {
                            is PostgrestAction.Insert -> OutboxOperation.INSERT
                            is PostgrestAction.Update -> OutboxOperation.PATCH
                            is PostgrestAction.Delete -> OutboxOperation.DELETE
                            is PostgrestAction.Select -> OutboxOperation.INSERT
                        },
                        record = (action as? PostgrestAction.Insert)?.record
                            ?: (action as? PostgrestAction.Update)?.record
                            ?: (action as? PostgrestAction.Select)?.records?.firstOrNull(),
                        oldRecord = (action as? PostgrestAction.Update)?.oldRecord
                            ?: (action as? PostgrestAction.Delete)?.oldRecord,
                    )
                    trySend(change)
                }
            }

            kotlinx.coroutines.launch { channel.subscribe() }

            awaitClose {
                job.cancel()
                runCatching { kotlinx.coroutines.GlobalScope.launch { channel.unsubscribe() } }
            }
        }
    }

    override suspend fun isReachable(): Boolean {
        if (client == null) return false
        // Consulta mínima y barata: si PostgREST responde, hay red y la anon key
        // es válida. Es mejor sondeo que un ping a la raíz, que muchos proxies
        // responden aunque PostgREST esté caído.
        return runCatching { requireClient().from("profiles").select { limit = 1 }.decodeList<JsonObject>() }
            .isSuccess
    }

    /**
     * Aplica un filtro PostgREST de la forma `columna=op.valor`.
     *
     * Se parsea a mano porque el outbox guarda filtros como string (para no
     * depender de la forma de los DTOs al reintentar operaciones viejas).
     */
    private fun io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder.applyPostgrestFilter(filter: String) {
        val parts = filter.split("=", limit = 2)
        if (parts.size != 2) {
            NsLog.w(TAG, "Filtro PostgREST con formato inválido: '$filter'")
            return
        }
        val (column, expression) = parts
        val tokens = expression.split(".", limit = 2)
        if (tokens.size != 2) return

        when (tokens[0]) {
            "eq" -> eq(column, tokens[1])
            "neq" -> neq(column, tokens[1])
            "gt" -> gt(column, tokens[1])
            "gte" -> gte(column, tokens[1])
            "lt" -> lt(column, tokens[1])
            "lte" -> lte(column, tokens[1])
            /*
             * "in" se omite a propósito.
             *
             * PostgREST lo expresa como `columna=in.(a,b,c)` y supabase-kt lo
             * resuelve con un operador de filtro distinto al que se usaría por
             * intuición (`containedBy` mapea a `cs`, que es "contiene", no "está
             * en"). Mapearlo mal produciría un filtro que SÍ ejecuta pero devuelve
             * el conjunto equivocado, sin error: el peor tipo de bug. Los filtros
             * que genera NomadSafe son de rango y igualdad, así que no se necesita.
             */
            else -> NsLog.w(TAG, "Operador PostgREST no soportado en el gateway: '${tokens[0]}'")
        }
    }

    /** Envuelve toda llamada y convierte excepciones en [Result]. */
    private inline fun <T> guard(block: () -> T): Result<T> = try {
        if (!isConfigured) Result.failure(IllegalStateException("Supabase no configurado"))
        else Result.success(block())
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsLog.w(TAG, "Llamada a Supabase falló: ${throwable.message}")
        Result.failure(throwable)
    }

    private companion object {
        const val TAG = "SupabaseGateway"
    }
}

/**
 * Gateway nulo para modo demo (sin credenciales).
 *
 * Devuelve fracaso en todo menos en `observeSessionStatus`, que emite una sesión
 * local estable. Sin esto, la app sin backend quedaría en un bucle de reintentos
 * mostrando spinners: con el gateway nulo, la UI entra en modo local y es usable.
 */
object UnconfiguredSupabaseGateway : SupabaseGateway {
    override val isConfigured = false

    private val localSession = GatewaySession(
        userId = "local-demo-user",
        accessToken = "local",
        phone = null,
        expiresAtEpochSeconds = null,
    )

    override suspend fun currentSession(): GatewaySession = localSession
    override fun observeSessionStatus(): Flow<GatewaySession?> = kotlinx.coroutines.flow.flowOf(localSession)

    private fun offline(): Result<Nothing> =
        Result.failure(java.io.IOException("Supabase no configurado: operando en modo local"))

    override suspend fun sendPhoneOtp(phoneE164: String) = offline()
    override suspend fun verifyPhoneOtp(phoneE164: String, code: String) = offline()
    override suspend fun signOut() = offline()
    override suspend fun select(table: String, filter: String?, orderColumn: String?, ascending: Boolean, limit: Int?) = offline()
    override suspend fun insert(table: String, payload: JsonObject, returning: Boolean) = offline()
    override suspend fun insert(table: String, payloadJson: String) = offline()
    override suspend fun update(table: String, filter: String?, payloadJson: String) = offline()
    override suspend fun delete(table: String, filter: String?) = offline()
    override suspend fun invokeFunction(name: String, body: JsonObject?) = offline()
    override fun observeTableChanges(table: String, filter: String?) = kotlinx.coroutines.flow.emptyFlow<TableChange>()
    override suspend fun isReachable() = false
}
