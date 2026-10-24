package com.nomadsafe.app.feature.growth

import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.SosEvent
import com.nomadsafe.app.domain.model.SosTrigger
import com.nomadsafe.app.domain.model.TrustContact
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Construcción de todo el contenido compartible de NomadSafe.
 *
 * Kotlin puro, sin Context: testeable en JVM y reutilizable desde la Edge
 * Function de backend si algún día los mensajes se envían por SMS server-side.
 *
 * ── Los tres loops de crecimiento que esto habilita ──────────────────────────
 * **1. Invitación (adquisición).** El usuario invita a sus contactos de
 * confianza por WhatsApp. No es un "compartí la app" genérico: el contacto de
 * confianza es alguien a quien el usuario YA decidió confiarle su ubicación en
 * una emergencia. La invitación viene con un vínculo emocional preexistente, que
 * es lo que la hace convertir.
 *
 * **2. Alerta comunitaria (activación + retención).** Compartir un incidente
 * concreto de tu barrio. Quien lo recibe ve valor inmediato ("mi zona está
 * peligrosa") antes de instalar nada, y el link lo lleva al mapa.
 *
 * **3. Seguimiento en vivo (retención del contacto).** Cuando un contacto tiene
 * la app instalada, puede seguir la escolta en vivo en su propio mapa. Esto
 * convierte al invitado en usuario activo: el loop se cierra y se autoalimenta,
 * que es la diferencia entre un share button y un verdadero loop viral.
 */
@Singleton
class GrowthContentBuilder @Inject constructor(
    private val strings: ShareStrings,
) {

    // ── Loop 1: invitación ────────────────────────────────────────────────────

    /**
     * URL de invitación con el código de referido.
     *
     * Es un link https y no sólo un deep link `nomadsafe://` porque:
     *  - WhatsApp no hace clickable un esquema propio de forma fiable.
     *  - Quien no tiene la app ve una página web (que además convierte), en vez
     *    de un error de "no se puede abrir este enlace".
     *  - El `assetlinks.json` del dominio habilita la verificación de App Links,
     *    así quien ya la tiene entra directo sin pasar por el navegador.
     */
    fun inviteUrl(referralCode: String): String =
        strings.inviteUrlTemplate.format(referralCode.encodeForUrl())

    fun inviteMessage(referralCode: String): String =
        strings.inviteMessage.format(inviteUrl(referralCode))

    // ── Loop 2: alerta comunitaria ────────────────────────────────────────────

    /**
     * Texto para compartir un incidente concreto.
     *
     * [humanAge] convierte el timestamp en "hace 20 minutos" en vez de una fecha
     * ISO. Es un detalle que importa: la antigüedad relativa es lo que le dice al
     * receptor si esto está pasando ahora o es historia, y una fecha absoluta lo
     * obliga a hacer la cuenta.
     */
    fun alertShareMessage(incident: Incident, categoryLabel: String, humanAge: String): String =
        strings.shareAlertMessage.format(
            incident.neighborhood ?: formatCoordinates(incident.location.latitude, incident.location.longitude),
            categoryLabel,
            humanAge,
            incidentUrl(incident.id),
        )

    fun incidentUrl(incidentId: String): String =
        strings.incidentUrlTemplate.format(incidentId.encodeForUrl())

    // ── Loop 3: seguimiento en vivo ───────────────────────────────────────────

    /**
     * URL de seguimiento en vivo de un SOS.
     *
     * Se firma con un token de acceso de un solo uso en el backend (ver
     * `supabase/functions/send-sos-alert`). Acá se construye la URL canónica; el
     * token lo añade la Edge Function cuando notifica, para que no quede un link
     * permanente y público a la ubicación de una persona en emergencia.
     */
    fun liveTrackingUrl(event: SosEvent): String =
        strings.liveTrackingUrlTemplate.format(event.id.encodeForUrl())

    fun genericShareMessage(referralCode: String): String =
        strings.genericShare.format(inviteUrl(referralCode))

    fun referralRewardMessage(days: Int): String = strings.referralReward.format(days)
}

/**
 * Construye los mensajes de emergencia dirigidos a un contacto.
 *
 * Separado de [GrowthContentBuilder] porque el tono y los requisitos son opuestos:
 * crecimiento busca ser atractivo, emergencia busca ser comprensible en tres
 * segundos por alguien que está asustado.
 */
@Singleton
class SosMessageBuilder @Inject constructor(
    private val strings: ShareStrings,
) {

    /**
     * Mensaje de SOS para [contact].
     *
     * Estructura deliberada, en este orden:
     *  1. Qué pasa (una línea, sin ambigüedad).
     *  2. Dónde (zona legible + coordenadas + precisión).
     *  3. Cuándo.
     *  4. Qué hacer (link de seguimiento en vivo).
     *
     * Se incluyen coordenadas crudas ADEMÁS del nombre de la zona porque quien
     * responde puede no conocer el barrio, y puede pegarle las coordenadas a la
     * policía o a Google Maps. El nombre de la zona, en cambio, es lo que un
     * humano entiende de un vistazo.
     */
    fun buildSosMessage(event: SosEvent, contact: TrustContact): String {
        val header = when (event.trigger) {
            // Se menciona la frase reconocida: le da al contacto el contexto de que
            // la persona pudo pedir ayuda verbalmente, que cambia la urgencia.
            SosTrigger.PANIC_WORD -> {
                val phrase = event.recognizedPhrase
                if (phrase.isNullOrBlank()) {
                    strings.sosPanicWordMessage.format("(voz detectada)")
                } else {
                    strings.sosPanicWordMessage.format("\"$phrase\"")
                }
            }
            // El check-in sin responder es el caso más ambiguo y hay que decirlo
            // explícitamente: el contacto debe saber que quizá la persona está
            // bien y simplemente no tocó el botón.
            SosTrigger.CHECKIN_MISSED -> strings.sosCheckInMessage
            SosTrigger.ROUTE_DEVIATION -> strings.sosCheckInMessage
            else -> strings.sosMessage.format(
                contact.name,
                formatCoordinates(event.location.latitude, event.location.longitude),
                formatClockTime(event.triggeredAt),
                formatAccuracy(event.accuracyMeters),
            )
        }

        return header
    }

    /**
     * Mensaje de falsa alarma, para desactivar la preocupación ya generada.
     *
     * Es tan importante como el de SOS. Si el contacto recibe la alerta y nunca
     * la cancelación, la próxima vez que suene el teléfono va a dudar — y ese
     * escepticismo es lo que mata un sistema de emergencia social.
     */
    fun buildFalseAlarmMessage(contact: TrustContact): String =
        strings.falseAlarmMessage.format(contact.name)

    fun buildLiveTrackingUrl(event: SosEvent): String =
        strings.liveTrackingUrlTemplate.format(event.id.encodeForUrl())

    private fun formatAccuracy(accuracyMeters: Float?): String = when {
        accuracyMeters == null -> "precisión desconocida"
        accuracyMeters <= 15f -> "±${accuracyMeters.roundToInt()} m (exacta)"
        accuracyMeters <= 50f -> "±${accuracyMeters.roundToInt()} m"
        // Con precisión mala hay que decirlo: mandar a alguien a buscar a un
        // radio de 300 m creyendo que es exacto pierde tiempo crítico.
        else -> "±${accuracyMeters.roundToInt()} m (aproximada, verificar)"
    }

    private fun formatClockTime(instant: kotlinx.datetime.Instant): String = instant
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        .let { "%02d:%02d".format(it.hour, it.minute) }
}

// ── Helpers compartidos ───────────────────────────────────────────────────────

/**
 * Coordena legible con 5 decimales.
 *
 * 5 decimales ≈ 1.1 m de precisión, que es más que suficiente y mantiene el
 * string corto para un SMS. Con 8 decimales el mensaje se ve como ruido técnico
 * y ocupa caracteres que en SMS se pagan.
 */
internal fun formatCoordinates(latitude: Double, longitude: Double): String =
    "%.5f, %.5f".format(latitude, longitude)

/**
 * Codificación mínima para segmentos de URL.
 *
 * Los códigos de referido son alfanuméricos por construcción, así que esto es
 * defensivo: si algún día se permite un id con caracteres raros, no se rompe el
 * link que ya circula por WhatsApp.
 */
internal fun String.encodeForUrl(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
        .replace("+", "%20")

/**
 * Genera un código de referido corto y legible.
 *
 * 6 caracteres de un alfabeto SIN los ambiguos (0/O, 1/I/l): el código se dicta
 * por teléfono y se escribe a mano en puntos de activación. Un 0 que parece O
 * cuesta una instalación perdida.
 *
 * 30^6 ≈ 729 millones de combinaciones: suficiente para el mercado objetivo y
 * corto para compartir.
 */
fun generateReferralCode(random: kotlin.random.Random = kotlin.random.Random.Default): String =
    (1..REFERRAL_CODE_LENGTH)
        .map { REFERRAL_ALPHABET[random.nextInt(REFERRAL_ALPHABET.size)] }
        .joinToString("")

private const val REFERRAL_CODE_LENGTH = 6
private const val REFERRAL_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
