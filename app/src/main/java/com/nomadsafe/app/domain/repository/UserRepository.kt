package com.nomadsafe.app.domain.repository

import com.nomadsafe.app.core.result.NsResult
import com.nomadsafe.app.domain.model.EscortSession
import com.nomadsafe.app.domain.model.EscortStatus
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.SosEvent
import com.nomadsafe.app.domain.model.SosStatus
import com.nomadsafe.app.domain.model.TrustContact
import com.nomadsafe.app.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/** Sesión de autenticación observada. */
interface UserRepository {

    /** Perfil del usuario autenticado, o null si no hay sesión. */
    fun observeCurrentUser(): Flow<UserProfile?>

    suspend fun getCurrentUser(): UserProfile?

    /**
     * Arranca el login por OTP telefónico.
     *
     * Se usa teléfono y no email a propósito: en el mercado objetivo casi nadie
     * recuerda su contraseña de email en una situación de estrés, pero todos
     * tienen el celular en la mano. Además el teléfono es el identificador que
     * ya necesita el sistema para los contactos de confianza.
     */
    suspend fun sendOtp(phoneE164: String): NsResult<Unit>

    suspend fun verifyOtp(phoneE164: String, code: String): NsResult<UserProfile>

    suspend fun signOut(): NsResult<Unit>

    suspend fun updateProfile(displayName: String?, city: String?): NsResult<UserProfile>

    /** Borrado completo exigido por la Ley de Protección de Datos peruana (29733). */
    suspend fun deleteAccount(): NsResult<Unit>

    /** Registra el token de push para recibir alertas con el proceso en frío. */
    suspend fun registerPushToken(token: String): NsResult<Unit>
}

/** Contactos de confianza: quienes reciben la ubicación en una emergencia. */
interface TrustContactRepository {

    fun observeContacts(): Flow<List<TrustContact>>

    suspend fun addContact(name: String, phoneE164: String, relationship: String?): NsResult<TrustContact>

    suspend fun removeContact(id: String): NsResult<Unit>

    /**
     * Marca el contacto como usuario de NomadSafe.
     *
     * Cuando el invitado se registra con ese número, el backend lo vincula y ese
     * contacto pasa a poder seguir la escolta EN VIVO en su propio mapa, en vez
     * de recibir sólo un link estático. Es el incentivo que cierra el loop viral.
     */
    suspend fun markAsNomadSafeUser(id: String): NsResult<Unit>

    suspend fun hasAnyContact(): Boolean
}

/** Sesiones de Modo Escolta y eventos SOS. */
interface EscortRepository {

    /**
     * Sesión activa, si existe.
     *
     * Se observa (no se consulta una sola vez) porque el servicio en primer plano
     * puede ser matado por el sistema y la UI necesita reaccionar al reanudar.
     */
    fun observeActiveSession(): Flow<EscortSession?>

    suspend fun startSession(destination: GeoPoint?, destinationLabel: String?, estimatedArrival: Instant?): NsResult<EscortSession>

    suspend fun updateLocation(sessionId: String, location: GeoPoint, accuracyMeters: Float?, isMoving: Boolean): NsResult<Unit>

    suspend fun recordCheckIn(sessionId: String): NsResult<Unit>

    suspend fun updateStatus(sessionId: String, status: EscortStatus): NsResult<Unit>

    suspend fun endSession(sessionId: String, status: EscortStatus): NsResult<Unit>

    /** Publica el evento SOS. Debe intentar red y, si falla, encolar en el outbox. */
    suspend fun raiseSos(event: SosEvent): NsResult<String>

    suspend fun updateSosStatus(sosId: String, status: SosStatus): NsResult<Unit>

    /** Historial del usuario, para la pantalla de actividad. */
    fun observeSosHistory(limit: Int = 20): Flow<List<SosEvent>>
}
