package com.nomadsafe.app.feature.escort

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.domain.model.SosEvent
import com.nomadsafe.app.domain.model.SosStatus
import com.nomadsafe.app.domain.model.TrustContact
import com.nomadsafe.app.domain.repository.EscortRepository
import com.nomadsafe.app.domain.repository.TrustContactRepository
import com.nomadsafe.app.feature.growth.SosMessageBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordina la respuesta a un SOS.
 *
 * ── El principio de diseño ───────────────────────────────────────────────────
 * **Ningún canal es único punto de fallo.** Un SOS se difunde por cuatro vías
 * independientes y la falla de cualquiera no impide las otras tres:
 *
 *  1. **Backend (Supabase).** Escribe el evento en Postgres y dispara la Edge
 *     Function que notifica a los contactos por push/SMS. Es la vía que funciona
 *     aunque el teléfono del usuario quede inutilizado.
 *  2. **Intents directos a contactos.** Abre WhatsApp/SMS con el mensaje y la
 *     ubicación ya cargados. Funciona SIN backend y sin cuenta, que es lo que
 *     permite que la feature sea útil en modo demo o con la red caída.
 *  3. **Notificación local de alta prioridad.** Refuerzo visible en el dispositivo.
 *  4. **Outbox local.** Si todo lo anterior falla por red, el evento queda encolado
 *     en Room y se reintenta. Un SOS nunca se pierde en silencio.
 *
 * ── Por qué los intents no se lanzan solos ───────────────────────────────────
 * Desde Android 10 no se pueden arrancar Activities desde segundo plano. Un
 * servicio de escolta que intente abrir WhatsApp con la pantalla bloqueada recibe
 * `ActivityNotFoundException` o directamente nada. Por eso se construyen y se
 * exponen como [pendingContactIntents], que la notificación muestra como acciones
 * y que la Activity sí puede lanzar cuando el usuario la abre. El canal que sí
 * funciona desde segundo plano es la Edge Function vía backend.
 */
@Singleton
class SosCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val escortRepository: EscortRepository,
    private val trustContactRepository: TrustContactRepository,
    private val notificationFactory: EscortNotificationFactory,
    private val messageBuilder: SosMessageBuilder,
) {

    /**
     * Difunde el SOS por todos los canales disponibles.
     *
     * Devuelve el evento tal como quedó persistido (con los ids de los contactos
     * notificados) para que el servicio lo refleje en su estado.
     */
    suspend fun dispatch(event: SosEvent): SosEvent? {
        NsLog.e(TAG, "Difundiendo SOS ${event.id} (disparador=${event.trigger.wireName})")

        val contacts = runCatching { trustContactRepository.observeContacts().first() }
            .getOrDefault(emptyList())

        if (contacts.isEmpty()) {
            // Caso crítico y frecuente en un usuario nuevo: activó el Modo Escolta
            // sin configurar contactos. Se registra igual y se le dice en la UI,
            // pero la alerta no llega a nadie. Es el principal motivo para empujar
            // la carga de contactos durante el onboarding.
            NsLog.w(TAG, "SOS sin contactos de confianza configurados")
        }

        // Canal 1: backend. Es el único que funciona con el teléfono bloqueado.
        val persisted = when (val result = escortRepository.raiseSos(event.copy(notifiedContactIds = contacts.map { it.id }))) {
            is com.nomadsafe.app.core.result.NsResult.Success -> {
                NsLog.i(TAG, "SOS registrado en el backend: ${result.data}")
                event.copy(notifiedContactIds = contacts.map { it.id })
            }
            is com.nomadsafe.app.core.result.NsResult.Failure -> {
                // El repositorio ya lo encoló en el outbox; acá sólo se registra.
                NsLog.e(TAG, "SOS no pudo llegar al backend; quedó encolado (${result.error})")
                event
            }
        }

        // Canal 3: notificación local de alta prioridad.
        notificationFactory.postSosNotification(contacts.size)

        // Canal 2: intents listos para que la Activity los lance al abrirse.
        buildContactIntents(event, contacts)

        return persisted
    }

    /**
     * Construye los intents de contacto para un SOS.
     *
     * Se devuelven y se guardan en [lastBuiltIntents] en vez de lanzarse, por la
     * restricción de Activities desde segundo plano (ver KDoc de la clase).
     */
    private fun buildContactIntents(event: SosEvent, contacts: List<TrustContact>): List<Intent> {
        val intents = contacts.mapNotNull { contact ->
            val message = messageBuilder.buildSosMessage(event, contact)
            val liveMapUrl = messageBuilder.buildLiveTrackingUrl(event)

            runCatching {
                Intent(Intent.ACTION_SENDTO).apply {
                    // sms: con cuerpo precargado. Es el canal de mayor cobertura:
                    // funciona sin datos móviles y en cualquier teléfono, incluso
                    // sin WhatsApp instalado, que es el caso de muchos contactos
                    // de mayor edad.
                    data = Uri.parse("smsto:${contact.phoneE164}")
                    putExtra("sms_body", "$message\n\nSeguimiento en vivo: $liveMapUrl")
                }
            }.getOrNull()
        }

        lastBuiltIntents = intents
        NsLog.i(TAG, "${intents.size} intent(s) de contacto preparados")
        return intents
    }

    /** Intents pendientes, para que la UI los lance cuando el usuario la abre. */
    var lastBuiltIntents: List<Intent> = emptyList()
        private set

    /**
     * Abre WhatsApp hacia [contact] con el mensaje de SOS precargado.
     *
     * Debe llamarse desde una Activity, nunca desde el servicio: Android 10+
     * bloquea el arranque de Activities en segundo plano.
     *
     * Usa `https://wa.me/<numero>?text=...` y no el esquema privado
     * `whatsapp://send`, porque ese esquema cambió entre versiones de WhatsApp y
     * `wa.me` es estable y además degrada a web si la app no está instalada.
     */
    fun openWhatsApp(event: SosEvent, contact: TrustContact): Boolean {
        val message = messageBuilder.buildSosMessage(event, contact)
        val liveUrl = messageBuilder.buildLiveTrackingUrl(event)
        val url = "https://wa.me/${contact.normalizedPhone}?text=" +
            Uri.encode("$message\n\nSeguimiento en vivo: $liveUrl")

        return launchIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // Fallback: si WhatsApp no está instalado (o es la variante Business,
            // que tiene otro package), se abre el navegador con el mismo wa.me.
            fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** Abre la app de SMS hacia el primer contacto, con el cuerpo precargado. */
    fun openFirstSmsIntent(): Boolean {
        val intent = lastBuiltIntents.firstOrNull()?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return launchIntent(intent, fallback = null)
    }

    private fun launchIntent(intent: Intent, fallback: Intent?): Boolean {
        val launched = runCatching {
            context.startActivity(intent)
            true
        }.onFailure { NsLog.w(TAG, "Intent principal no disponible: ${it.message}") }.getOrDefault(false)

        if (launched) return true
        if (fallback == null) return false

        return runCatching {
            context.startActivity(fallback)
            true
        }.onFailure { NsLog.e(TAG, "Tampoco se pudo abrir el fallback", it) }.getOrDefault(false)
    }

    suspend fun resolveAsFalseAlarm(sosId: String) {
        escortRepository.updateSosStatus(sosId, SosStatus.FALSE_ALARM)
        lastBuiltIntents = emptyList()
        NsLog.i(TAG, "SOS $sosId marcado como falsa alarma")
    }

    suspend fun resolve(sosId: String) {
        escortRepository.updateSosStatus(sosId, SosStatus.RESOLVED)
        lastBuiltIntents = emptyList()
    }

    private companion object {
        const val TAG = "SosCoordinator"
        const val WHATSAPP_PACKAGE = "com.whatsapp"
    }
}
