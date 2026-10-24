package com.nomadsafe.app.data.repository

import com.nomadsafe.app.core.result.NsError
import com.nomadsafe.app.core.result.NsResult
import com.nomadsafe.app.core.result.mapThrowable
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.data.local.EscortSessionEntity
import com.nomadsafe.app.data.local.NomadSafeDatabase
import com.nomadsafe.app.data.local.OutboxOperation
import com.nomadsafe.app.data.local.SosEventEntity
import com.nomadsafe.app.data.local.TrustContactEntity
import com.nomadsafe.app.data.remote.PostgisGeometry
import com.nomadsafe.app.data.remote.SupabaseGateway
import com.nomadsafe.app.data.sync.OutboxSyncManager
import com.nomadsafe.app.domain.model.EscortSession
import com.nomadsafe.app.domain.model.EscortStatus
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.MAX_TRUST_CONTACTS
import com.nomadsafe.app.domain.model.SosEvent
import com.nomadsafe.app.domain.model.SosStatus
import com.nomadsafe.app.domain.model.SosTrigger
import com.nomadsafe.app.domain.model.TrustContact
import com.nomadsafe.app.domain.model.UserProfile
import com.nomadsafe.app.domain.repository.EscortRepository
import com.nomadsafe.app.domain.repository.TrustContactRepository
import com.nomadsafe.app.domain.repository.UserRepository
import com.nomadsafe.app.feature.growth.generateReferralCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Regex E.164: + seguido de 8 a 15 dígitos, sin espacios ni guiones. */
private val E164_REGEX = Regex("""^\+[1-9]\d{7,14}$""")

/**
 * Normaliza un teléfono a E.164.
 *
 * Es el validador más importante de todo el flujo de contactos: de este string
 * sale el link `wa.me/<numero>` y una llamada de emergencia. Un número con
 * espacios o sin código de país funciona en la agenda del teléfono pero genera un
 * link de WhatsApp roto, y ese bug sólo aparece en el peor momento posible.
 *
 * Devuelve null si no se puede normalizar, para que la UI lo diga ANTES de
 * guardar, no cuando el usuario intenta usar el contacto en una emergencia.
 */
fun normalizePhoneE164(raw: String, defaultCountryCode: String = "+51"): String? {
    val digitsOnly = raw.filter { it.isDigit() || it == '+' }

    val candidate = when {
        digitsOnly.startsWith("+") -> digitsOnly
        // "00" es el prefijo de salida internacional en Perú y buena parte de LATAM.
        digitsOnly.startsWith("00") -> "+${digitsOnly.removePrefix("00")}"
        // Número local peruano de 9 dígitos sin código: se antepone +51.
        digitsOnly.length == 9 && defaultCountryCode == "+51" -> "$defaultCountryCode$digitsOnly"
        else -> "$defaultCountryCode$digitsOnly"
    }

    return candidate.takeIf { E164_REGEX.matches(it) }
}

@Singleton
class TrustContactRepositoryImpl @Inject constructor(
    private val database: NomadSafeDatabase,
    private val gateway: SupabaseGateway,
    private val outbox: OutboxSyncManager,
    private val json: Json,
    private val timeProvider: TimeProvider,
) : TrustContactRepository {

    private val dao get() = database.trustContactDao()

    override fun observeContacts(): Flow<List<TrustContact>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun addContact(
        name: String,
        phoneE164: String,
        relationship: String?,
    ): NsResult<TrustContact> = try {
        if (name.isBlank()) {
            NsResult.Failure(NsError.Validation(field = "name", reason = "blank"))
        } else {
            val normalized = normalizePhoneE164(phoneE164)
            if (normalized == null) {
                NsResult.Failure(NsError.Validation(field = "phone", reason = "not_e164"))
            } else if (dao.count() >= MAX_TRUST_CONTACTS) {
                NsResult.Failure(NsError.Validation(field = null, reason = "contact_limit"))
            } else {
                val contact = TrustContact(
                    id = "tc-${UUID.randomUUID()}",
                    name = name.trim(),
                    phoneE164 = normalized,
                    relationship = relationship?.trim()?.takeIf { it.isNotEmpty() },
                    createdAt = timeProvider.now(),
                    priority = dao.count(),
                )
                val entity = contact.toEntity()

                dao.upsert(entity)
                outbox.enqueue(
                    table = TABLE,
                    operation = OutboxOperation.INSERT,
                    payload = json.encodeToString(
                        com.nomadsafe.app.data.remote.TrustContactDto.serializer(),
                        com.nomadsafe.app.data.remote.TrustContactDto(
                            id = contact.id,
                            ownerId = gateway.currentSession()?.userId ?: "local",
                            name = contact.name,
                            phoneE164 = contact.phoneE164,
                            relationship = contact.relationship,
                            createdAt = contact.createdAt,
                            priority = contact.priority,
                        ),
                    ),
                    // Crítico: un contacto de confianza que no llega al backend es un
                    // SOS que no le llega a nadie.
                    isCritical = true,
                )
                NsLog.i(TAG, "Contacto de confianza agregado: ${contact.name}")
                NsResult.Success(contact)
            }
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun removeContact(id: String): NsResult<Unit> = try {
        dao.deleteById(id)
        outbox.enqueue(TABLE, OutboxOperation.DELETE, payload = "{}", filter = "id=eq.$id")
        NsResult.Success(Unit)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun markAsNomadSafeUser(id: String): NsResult<Unit> = try {
        dao.markAsNomadSafeUser(id)
        outbox.enqueue(
            TABLE,
            OutboxOperation.PATCH,
            payload = """{"is_nomadsafe_user": true}""",
            filter = "id=eq.$id",
        )
        NsResult.Success(Unit)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    /**
     * Conteo como Flow.
     *
     * La pantalla de escolta bloquea el arranque cuando vale cero. Con una lectura
     * puntual, agregar el primer contacto no desbloquearía el botón hasta
     * recomponer por otro motivo: el usuario haría exactamente lo que se le pidió y
     * vería el botón seguir deshabilitado.
     */
    override fun observeCount(): Flow<Int> = dao.observeCount()

    override suspend fun hasAnyContact(): Boolean = dao.count() > 0

    private companion object {
        const val TAG = "TrustContactRepository"
        const val TABLE = "trust_contacts"
    }
}

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val gateway: SupabaseGateway,
    private val timeProvider: TimeProvider,
) : UserRepository {

    /**
     * Perfil observado.
     *
     * En modo demo (sin Supabase) se emite un perfil local estable, para que toda
     * la app — incluido el código de referido y los loops de invitación — funcione
     * sin credenciales. Sin esto, el flujo de crecimiento no se podría probar.
     */
    override fun observeCurrentUser(): Flow<UserProfile?> =
        gateway.observeSessionStatus().map { session ->
            session?.let {
                UserProfile(
                    id = it.userId,
                    phoneE164 = it.phone.orEmpty(),
                    displayName = null,
                    city = null,
                    referralCode = referralCodeFor(it.userId),
                    createdAt = timeProvider.now(),
                )
            } ?: demoProfile()
        }

    override suspend fun getCurrentUser(): UserProfile? =
        gateway.currentSession()?.let {
            UserProfile(
                id = it.userId,
                phoneE164 = it.phone.orEmpty(),
                referralCode = referralCodeFor(it.userId),
                createdAt = timeProvider.now(),
            )
        } ?: demoProfile()

    override suspend fun sendOtp(phoneE164: String): NsResult<Unit> {
        val normalized = normalizePhoneE164(phoneE164)
            ?: return NsResult.Failure(NsError.Validation(field = "phone", reason = "not_e164"))

        return gateway.sendPhoneOtp(normalized)
            .fold(
                onSuccess = { NsResult.Success(Unit) },
                onFailure = { NsResult.Failure(mapThrowable(it)) },
            )
    }

    override suspend fun verifyOtp(phoneE164: String, code: String): NsResult<UserProfile> {
        val normalized = normalizePhoneE164(phoneE164)
            ?: return NsResult.Failure(NsError.Validation(field = "phone", reason = "not_e164"))

        if (code.length != OTP_LENGTH) {
            return NsResult.Failure(NsError.Validation(field = "code", reason = "wrong_length"))
        }

        return gateway.verifyPhoneOtp(normalized, code).fold(
            onSuccess = { session ->
                NsResult.Success(
                    UserProfile(
                        id = session.userId,
                        phoneE164 = normalized,
                        referralCode = referralCodeFor(session.userId),
                        createdAt = timeProvider.now(),
                    ),
                )
            },
            onFailure = { NsResult.Failure(mapThrowable(it)) },
        )
    }

    override suspend fun signOut(): NsResult<Unit> =
        gateway.signOut().fold(
            onSuccess = { NsResult.Success(Unit) },
            onFailure = { NsResult.Failure(mapThrowable(it)) },
        )

    override suspend fun updateProfile(displayName: String?, city: String?): NsResult<UserProfile> =
        NsResult.Failure(NsError.Local("updateProfile requiere la tabla profiles sincronizada (Fase 2)"))

    override suspend fun deleteAccount(): NsResult<Unit> =
        gateway.invokeFunction(FUNCTION_DELETE_ACCOUNT, null).fold(
            onSuccess = { NsResult.Success(Unit) },
            onFailure = { NsResult.Failure(mapThrowable(it)) },
        )

    override suspend fun registerPushToken(token: String): NsResult<Unit> =
        gateway.invokeFunction(
            FUNCTION_REGISTER_PUSH,
            Json.parseToJsonElement("""{"token":"$token"}""") as? JsonObject,
        ).fold(
            onSuccess = { NsResult.Success(Unit) },
            onFailure = { NsResult.Failure(mapThrowable(it)) },
        )

    /**
     * Código de referido derivado del user id.
     *
     * Determinista a propósito: si se generara aleatorio y se guardara, un
     * reinstall sin backend perdería el código y rompería la atribución de
     * invitaciones ya circulando por WhatsApp. Derivarlo del id hace que el mismo
     * usuario tenga siempre el mismo código.
     */
    private fun referralCodeFor(userId: String): String {
        val hash = userId.hashCode().toLong() and 0xFFFFFFFFL
        val random = kotlin.random.Random(hash)
        return generateReferralCode(random)
    }

    private fun demoProfile() = UserProfile(
        id = DEMO_USER_ID,
        phoneE164 = "+51900000000",
        displayName = "Usuario local",
        city = com.nomadsafe.app.core.config.DEMO_PILOT_CITY,
        referralCode = referralCodeFor(DEMO_USER_ID),
        createdAt = timeProvider.now(),
    )

    private companion object {
        const val OTP_LENGTH = 6
        const val DEMO_USER_ID = "local-demo-user"
        const val FUNCTION_DELETE_ACCOUNT = "delete-account"
        const val FUNCTION_REGISTER_PUSH = "register-push-token"
    }
}

/**
 * Repositorio de sesiones de escolta y eventos SOS.
 *
 * Los SOS se encolan como CRÍTICOS en el outbox y con reintento corto. Un SOS que
 * no llega al backend es el fallo más grave posible del producto, así que se
 * prioriza sobre cualquier otra operación pendiente.
 */
@Singleton
class EscortRepositoryImpl @Inject constructor(
    private val database: NomadSafeDatabase,
    private val gateway: SupabaseGateway,
    private val outbox: OutboxSyncManager,
    private val json: Json,
    private val timeProvider: TimeProvider,
) : EscortRepository {

    private val dao get() = database.escortDao()

    override fun observeActiveSession(): Flow<EscortSession?> =
        dao.observeActive().map { it?.toDomain() }

    override suspend fun startSession(
        destination: GeoPoint?,
        destinationLabel: String?,
        estimatedArrival: Instant?,
    ): NsResult<EscortSession> = try {
        val session = EscortSession(
            id = "esc-${UUID.randomUUID()}",
            userId = gateway.currentSession()?.userId ?: "local",
            startedAt = timeProvider.now(),
            destination = destination,
            destinationLabel = destinationLabel,
            estimatedArrival = estimatedArrival,
            status = EscortStatus.ACTIVE,
            voiceDetectionEnabled = true,
        )

        dao.upsert(session.toEntity())
        outbox.enqueue(
            table = TABLE_SESSIONS,
            operation = OutboxOperation.INSERT,
            payload = json.encodeToString(
                com.nomadsafe.app.data.remote.EscortSessionDto.serializer(),
                com.nomadsafe.app.data.remote.EscortSessionDto(
                    id = session.id,
                    userId = session.userId,
                    startedAt = session.startedAt,
                    destination = destination?.let { PostgisGeometry.toWkt(it) },
                    destinationLabel = destinationLabel,
                    estimatedArrival = estimatedArrival,
                    status = EscortStatus.ACTIVE.wireName,
                ),
            ),
            isCritical = true,
        )

        NsLog.i(TAG, "Sesión de escolta iniciada: ${session.id}")
        NsResult.Success(session)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun updateLocation(
        sessionId: String,
        location: GeoPoint,
        accuracyMeters: Float?,
        isMoving: Boolean,
    ): NsResult<Unit> = try {
        // Se escribe local siempre: la traza debe sobrevivir a una caída de red.
        dao.updateLocation(sessionId, location.latitude, location.longitude, null)

        // La posición en vivo va por Edge Function y NO por el outbox: una
        // posición vieja no sirve para nada, así que si falla se descarta en vez
        // de encolar. Encolar 700 posiciones de una caminata de una hora para
        // mandarlas todas juntas al recuperar red sería inútil y caro.
        gateway.invokeFunction(
            FUNCTION_LIVE_POSITION,
            json.parseToJsonElement(
                """{"session_id":"$sessionId","location":"${PostgisGeometry.toWkt(location)}","is_moving":$isMoving}""",
            ) as? JsonObject,
        )
        NsResult.Success(Unit)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun recordCheckIn(sessionId: String): NsResult<Unit> = try {
        dao.recordCheckIn(sessionId, timeProvider.now())
        NsResult.Success(Unit)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun updateStatus(sessionId: String, status: EscortStatus): NsResult<Unit> = try {
        outbox.enqueue(
            TABLE_SESSIONS,
            OutboxOperation.PATCH,
            payload = """{"status":"${status.wireName}"}""",
            filter = "id=eq.$sessionId",
            isCritical = status == EscortStatus.SOS,
        )
        NsResult.Success(Unit)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun endSession(sessionId: String, status: EscortStatus): NsResult<Unit> = try {
        dao.endSession(sessionId, status.wireName, timeProvider.now())
        outbox.enqueue(
            TABLE_SESSIONS,
            OutboxOperation.PATCH,
            payload = """{"status":"${status.wireName}","ended_at":"${timeProvider.now()}"}""",
            filter = "id=eq.$sessionId",
        )
        NsResult.Success(Unit)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun raiseSos(event: SosEvent): NsResult<String> = try {
        // 1. Local primero: el SOS queda registrado pase lo que pase con la red.
        dao.upsertSos(event.toEntity())

        // 2. Outbox crítico: garantiza el reintento si todo lo demás falla.
        outbox.enqueue(
            table = TABLE_SOS,
            operation = OutboxOperation.INSERT,
            payload = json.encodeToString(
                com.nomadsafe.app.data.remote.SosEventDto.serializer(),
                com.nomadsafe.app.data.remote.SosEventDto(
                    id = event.id,
                    escortSessionId = event.escortSessionId,
                    userId = event.userId,
                    location = PostgisGeometry.toWkt(event.location),
                    accuracyMeters = event.accuracyMeters,
                    triggeredAt = event.triggeredAt,
                    trigger = event.trigger.wireName,
                    recognizedPhrase = event.recognizedPhrase,
                    batteryLevel = event.batteryLevel,
                    isMoving = event.isMoving,
                    speedMetersPerSecond = event.speedMetersPerSecond,
                    notifiedContactIds = event.notifiedContactIds,
                    status = event.status.wireName,
                ),
            ),
            isCritical = true,
        )

        // 3. Intento inmediato y directo de notificar. Es la vía que activa la
        //    Edge Function que avisa a los contactos por push/SMS.
        val notified = gateway.invokeFunction(
            FUNCTION_RAISE_SOS,
            json.parseToJsonElement(
                """
                {"sos_id":"${event.id}","escort_session_id":${event.escortSessionId?.let { "\"$it\"" } ?: "null"},
                 "location":"${PostgisGeometry.toWkt(event.location)}",
                 "accuracy_meters":${event.accuracyMeters ?: "null"},
                 "trigger":"${event.trigger.wireName}","is_moving":${event.isMoving}}
                """.trimIndent(),
            ) as? JsonObject,
        ).isSuccess

        if (!notified) {
            NsLog.e(TAG, "El SOS ${event.id} no pudo notificarse; queda en el outbox crítico")
            outbox.startRetryLoop()
        } else {
            NsLog.i(TAG, "SOS ${event.id} notificado correctamente")
        }

        NsResult.Success(event.id)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsLog.e(TAG, "Fallo grave al levantar SOS", throwable)
        NsResult.Failure(mapThrowable(throwable))
    }

    override suspend fun updateSosStatus(sosId: String, status: SosStatus): NsResult<Unit> = try {
        dao.updateSosStatus(sosId, status.wireName)
        outbox.enqueue(
            TABLE_SOS,
            OutboxOperation.PATCH,
            payload = """{"status":"${status.wireName}"}""",
            filter = "id=eq.$sosId",
            isCritical = true,
        )
        NsResult.Success(Unit)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    override fun observeSosHistory(limit: Int): Flow<List<SosEvent>> =
        dao.observeSosHistory(limit).map { entities -> entities.map { it.toDomain() } }

    override fun observeActiveSos(): Flow<SosEvent?> =
        dao.observeActiveSos().map { it?.toDomain() }

    /**
     * Cierra un SOS como falsa alarma o resuelto.
     *
     * Se encola como crítico igual que el disparo: si el cierre no llega al
     * servidor, los contactos siguen viendo una emergencia activa y pueden estar
     * yendo hacia una ubicación donde ya no hay nadie. Un cierre perdido es tan
     * dañino como un disparo perdido, sólo que en dirección opuesta.
     */
    override suspend fun resolveSos(sosId: String, note: String?): NsResult<Unit> = try {
        dao.updateSosStatus(sosId, SosStatus.RESOLVED.wireName)
        outbox.enqueue(
            table = TABLE_SOS,
            operation = OutboxOperation.PATCH,
            payload = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("status", SosStatus.RESOLVED.wireName)
                    put("resolved_at", timeProvider.now().toString())
                    note?.let { put("resolution_note", it) }
                },
            ),
            filter = "id=eq.$sosId",
            isCritical = true,
        )
        NsLog.i(TAG, "SOS $sosId resuelto")
        NsResult.Success(Unit)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        NsResult.Failure(mapThrowable(throwable))
    }

    private companion object {
        const val TAG = "EscortRepository"
        const val TABLE_SESSIONS = "escort_sessions"
        const val TABLE_SOS = "sos_events"
        const val FUNCTION_RAISE_SOS = "raise-sos"
        const val FUNCTION_LIVE_POSITION = "live-position"
    }
}

// ── Mapeos ────────────────────────────────────────────────────────────────────

private fun TrustContact.toEntity() = TrustContactEntity(
    id = id,
    name = name,
    phoneE164 = phoneE164,
    relationship = relationship,
    isNomadSafeUser = isNomadSafeUser,
    createdAt = createdAt,
    priority = priority,
)

private fun TrustContactEntity.toDomain() = TrustContact(
    id = id,
    name = name,
    phoneE164 = phoneE164,
    relationship = relationship,
    isNomadSafeUser = isNomadSafeUser,
    createdAt = createdAt,
    priority = priority,
)

private fun EscortSession.toEntity() = EscortSessionEntity(
    id = id,
    userId = userId,
    startedAt = startedAt,
    destinationLat = destination?.latitude,
    destinationLon = destination?.longitude,
    destinationLabel = destinationLabel,
    estimatedArrival = estimatedArrival,
    status = status.wireName,
    endedAt = endedAt,
    voiceDetectionEnabled = voiceDetectionEnabled,
    lastKnownLat = lastKnownLocation?.latitude,
    lastKnownLon = lastKnownLocation?.longitude,
    lastCheckInAt = lastCheckInAt,
)

private fun EscortSessionEntity.toDomain() = EscortSession(
    id = id,
    userId = userId,
    startedAt = startedAt,
    destination = if (destinationLat != null && destinationLon != null) {
        GeoPoint(destinationLat, destinationLon)
    } else {
        null
    },
    destinationLabel = destinationLabel,
    estimatedArrival = estimatedArrival,
    status = EscortStatus.fromWireName(status),
    endedAt = endedAt,
    voiceDetectionEnabled = voiceDetectionEnabled,
    lastKnownLocation = if (lastKnownLat != null && lastKnownLon != null) {
        GeoPoint(lastKnownLat, lastKnownLon)
    } else {
        null
    },
    lastCheckInAt = lastCheckInAt,
)

private fun SosEvent.toEntity() = SosEventEntity(
    id = id,
    escortSessionId = escortSessionId,
    userId = userId,
    latitude = location.latitude,
    longitude = location.longitude,
    accuracyMeters = accuracyMeters,
    triggeredAt = triggeredAt,
    trigger = trigger.wireName,
    recognizedPhrase = recognizedPhrase,
    batteryLevel = batteryLevel,
    isMoving = isMoving,
    speedMetersPerSecond = speedMetersPerSecond,
    // Se serializa como CSV: Room no tiene conversor de listas y una tabla extra
    // para 5 ids por evento es complejidad sin beneficio.
    notifiedContactIds = notifiedContactIds.joinToString(","),
    status = status.wireName,
)

private fun SosEventEntity.toDomain() = SosEvent(
    id = id,
    escortSessionId = escortSessionId,
    userId = userId,
    location = GeoPoint(latitude, longitude),
    accuracyMeters = accuracyMeters,
    triggeredAt = triggeredAt,
    trigger = SosTrigger.fromWireName(trigger),
    recognizedPhrase = recognizedPhrase,
    batteryLevel = batteryLevel,
    isMoving = isMoving,
    speedMetersPerSecond = speedMetersPerSecond,
    notifiedContactIds = notifiedContactIds.split(",").filter { it.isNotBlank() },
    status = SosStatus.fromWireName(status),
)
