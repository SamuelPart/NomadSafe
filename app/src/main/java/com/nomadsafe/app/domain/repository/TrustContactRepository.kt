package com.nomadsafe.app.domain.repository

import com.nomadsafe.app.core.result.NsResult
import com.nomadsafe.app.domain.model.TrustContact
import kotlinx.coroutines.flow.Flow

/**
 * Repositorio de contactos de confianza.
 *
 * ── Por qué el límite se valida en dos lugares ────────────────────────────────
 * [addContact] rechaza más de `MAX_TRUST_CONTACTS` en el cliente, y la migración
 * de Supabase tiene un trigger que impone el mismo límite en el servidor. No es
 * redundancia por descuido.
 *
 * El lado del cliente existe para dar feedback inmediato: sin él, el usuario
 * escribe nombre y teléfono, toca guardar, espera una ida y vuelta de red y recién
 * ahí se entera de que no puede. El lado del servidor existe porque una
 * restricción sólo del cliente se salta con una petición directa a PostgREST, y en
 * esta app un contacto extra no autorizado es alguien que recibe la ubicación en
 * vivo del usuario durante una emergencia.
 *
 * La validación del cliente es experiencia; la del servidor es la garantía.
 */
interface TrustContactRepository {

    /** Lista completa, ordenada. Es un Flow porque la UI debe reflejar altas y bajas. */
    fun observeContacts(): Flow<List<TrustContact>>

    /**
     * Cantidad de contactos, como Flow.
     *
     * Se expone como Flow y no como un conteo puntual porque la pantalla de
     * escolta bloquea el arranque cuando vale cero. Con una lectura única, agregar
     * el primer contacto no desbloquearía el botón hasta recomponer por otro
     * motivo, y el usuario vería un botón deshabilitado sin razón aparente justo
     * después de haber hecho lo que se le pidió.
     */
    fun observeCount(): Flow<Int>

    /**
     * Alta de contacto.
     *
     * Normaliza el teléfono a E.164 internamente y falla con
     * `NsError.Validation(field = "phone", reason = "not_e164")` si no puede. No
     * acepta el número "tal como lo escribió el usuario": el enlace `wa.me/<n>`
     * sólo funciona con formato internacional sin símbolos, y un teléfono guardado
     * como `987 654 321` genera un enlace que WhatsApp rechaza en silencio. El
     * contacto nunca recibe la invitación y nadie se entera de que falló.
     */
    suspend fun addContact(
        name: String,
        phoneE164: String,
        relationship: String?,
    ): NsResult<TrustContact>

    suspend fun removeContact(id: String): NsResult<Unit>

    /**
     * Marca que el contacto ya usa NomadSafe.
     *
     * Cambia el canal de notificación durante un SOS: en vez de sólo SMS, recibe
     * una alerta in-app y puede seguir la escolta en el mapa dentro de la
     * aplicación. Es el paso que convierte un contacto invitado en un usuario.
     */
    suspend fun markAsNomadSafeUser(id: String): NsResult<Unit>

    /**
     * Verificación puntual, para el servicio en primer plano.
     *
     * El servicio necesita saber si hay contactos al arrancar una escolta y no
     * puede suscribirse a un Flow para eso: una suscripción que vive mientras vive
     * el servicio mantiene el repositorio y Room activos durante toda la caminata
     * para responder una pregunta de sí o no.
     */
    suspend fun hasAnyContact(): Boolean
}
