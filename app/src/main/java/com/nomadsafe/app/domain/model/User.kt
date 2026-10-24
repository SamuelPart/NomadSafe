package com.nomadsafe.app.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Contacto de confianza: quien recibe la ubicación del usuario en una emergencia.
 *
 * [phoneE164] es obligatorio en formato E.164 (+51987654321) porque de ahí sale
 * el link `wa.me/<numero>` y una llamada de emergencia. Un número sin código de
 * país funciona en la agenda del teléfono pero genera un link de WhatsApp roto,
 * y ese bug sólo aparece en el peor momento posible.
 */
@Serializable
data class TrustContact(
    val id: String,
    val name: String,
    val phoneE164: String,
    val relationship: String? = null,
    /** Si tiene la app instalada puede seguir la escolta en vivo, no sólo recibir un SMS. */
    val isNomadSafeUser: Boolean = false,
    val createdAt: Instant,
    val priority: Int = 0,
) {
    /** Sólo dígitos, para armar URLs wa.me y tel:. */
    val normalizedPhone: String get() = phoneE164.filter { it.isDigit() }

    val whatsappLink: String get() = "https://wa.me/$normalizedPhone"
    val callUri: String get() = "tel:$phoneE164"
}

/** Límite de contactos por usuario. Más de 5 diluye la responsabilidad de responder. */
const val MAX_TRUST_CONTACTS = 5

/**
 * Sesión de Modo Escolta.
 *
 * Se modela como entidad con estado explícito porque el servicio en primer plano
 * puede ser matado por el sistema en cualquier momento (batería, memoria, OEM
 * agresivo como Xiaomi/Huawei). Al reiniciar, la app necesita saber si había una
 * escolta en curso para retomarla o cerrarla, no adivinarlo.
 */
@Serializable
data class EscortSession(
    val id: String,
    val userId: String,
    val startedAt: Instant,
    val destination: GeoPoint? = null,
    val destinationLabel: String? = null,
    val estimatedArrival: Instant? = null,
    val status: EscortStatus = EscortStatus.ACTIVE,
    val endedAt: Instant? = null,
    val voiceDetectionEnabled: Boolean = true,
    /** Último punto conocido, para reanudar la traza después de un reinicio. */
    val lastKnownLocation: GeoPoint? = null,
    /** Último check-in confirmado por el usuario. */
    val lastCheckInAt: Instant? = null,
)

enum class EscortStatus(val wireName: String) {
    ACTIVE("active"),

    /** El usuario no respondió el check-in; se elevó una alerta preventiva. */
    ESCALATED("escalated"),

    /** SOS disparado, manual o por palabra de pánico. */
    SOS("sos"),

    COMPLETED("completed"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWireName(value: String?): EscortStatus =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: ACTIVE
    }
}

/** Origen del disparo de emergencia. Importa para auditoría y para afinar umbrales. */
enum class SosTrigger(val wireName: String) {
    /** Botón SOS mantenido por el usuario. */
    MANUAL("manual"),

    /** Palabra de pánico detectada por Vosk en el dispositivo. */
    PANIC_WORD("panic_word"),

    /** Check-in de escolta sin responder dentro de la ventana. */
    CHECKIN_MISSED("checkin_missed"),

    /** Desvío fuerte de la ruta planificada. */
    ROUTE_DEVIATION("route_deviation"),

    /** Disparado por un contacto de confianza que ve la escolta en vivo. */
    CONTACT_RAISED("contact_raised"),
    ;

    companion object {
        fun fromWireName(value: String?): SosTrigger =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: MANUAL
    }
}

/**
 * Evento SOS: la alerta de emergencia en sí.
 *
 * Incluye [batteryLevel] y [isMoving] porque son lo primero que pregunta quien
 * recibe la alerta y lo primero que necesita la policía: si la persona va
 * caminando o en un vehículo cambia completamente cómo se responde.
 */
@Serializable
data class SosEvent(
    val id: String,
    val escortSessionId: String?,
    val userId: String,
    val location: GeoPoint,
    val accuracyMeters: Float?,
    val triggeredAt: Instant,
    val trigger: SosTrigger,
    /** Texto reconocido por Vosk, si el disparo fue por voz. Sólo el fragmento, no audio. */
    val recognizedPhrase: String? = null,
    val batteryLevel: Int? = null,
    val isMoving: Boolean = false,
    val speedMetersPerSecond: Float? = null,
    val notifiedContactIds: List<String> = emptyList(),
    val status: SosStatus = SosStatus.OPEN,
)

enum class SosStatus(val wireName: String) {
    OPEN("open"),
    ACKNOWLEDGED("acknowledged"),
    RESOLVED("resolved"),
    FALSE_ALARM("false_alarm"),
    ;

    companion object {
        fun fromWireName(value: String?): SosStatus =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: OPEN
    }
}

/** Perfil del usuario autenticado. */
@Serializable
data class UserProfile(
    val id: String,
    val phoneE164: String,
    val displayName: String? = null,
    val city: String? = null,
    /** Código de referido propio: es el que se comparte en los loops de crecimiento. */
    val referralCode: String,
    /** De quién vino este usuario. Se usa para atribuir la invitación. */
    val referredBy: String? = null,
    val createdAt: Instant,
    /** Puntos de crecimiento canjeados/pendientes (días de escolta premium). */
    val referralCredits: Int = 0,
)
