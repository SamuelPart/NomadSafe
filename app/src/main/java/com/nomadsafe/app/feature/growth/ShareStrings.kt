package com.nomadsafe.app.feature.growth

/**
 * Plantillas de texto de los loops de crecimiento y de emergencia.
 *
 * ── Por qué es una clase de datos y no `R.string` directo ────────────────────
 * El texto de una invitación es la variable de crecimiento más importante del
 * producto: es literalmente lo que convence a la siguiente persona de instalar la
 * app. Se va a A/B testear y se va a reescribir muchas veces.
 *
 * Si los constructores de mensajes leyeran `R.string` directamente, cada prueba
 * de redacción obligaría a tocar recursos de Android y no se podría testear en
 * JVM. Separando las plantillas de la lógica:
 *  - La construcción de mensajes es Kotlin puro y testeable.
 *  - Hilt arma [ShareStrings] desde los recursos, así las traducciones siguen
 *    funcionando (values-es / values-en).
 *  - Se puede servir un conjunto de plantillas distinto por cohorte sin recompilar.
 *
 * Los placeholders son posicionales y se documentan en cada campo, porque un
 * `%1$s` corrido produce un mensaje roto que se comparte con cientos de personas.
 */
data class ShareStrings(
    /**
     * Invitación a instalar. Placeholders: %1$s = URL con código de referido.
     *
     * Redactado en primera persona y desde el valor recibido, no desde la app.
     * "Si algún día estoy en peligro, me ayuda" convierte el mensaje en una
     * petición personal, que es lo que hace que alguien lo abra. Un texto tipo
     * "Descargá NomadSafe, la app de seguridad" se ignora.
     */
    val inviteMessage: String,

    /** Alerta comunitaria. %1$s = zona, %2$s = categoría, %3$s = antigüedad, %4$s = URL. */
    val shareAlertMessage: String,

    /**
     * Mensaje de SOS a un contacto. %1$s = nombre del contacto, %2$s = zona,
     * %3$s = hora, %4$s = precisión en metros.
     *
     * Va al grano y pone la acción primero. Quien lo recibe está asustado y tiene
     * segundos: cualquier preámbulo ("Hola, espero que estés bien…") es daño.
     */
    val sosMessage: String,

    /** SOS disparado por palabra de pánico. %1$s = frase reconocida. */
    val sosPanicWordMessage: String,

    /** SOS por check-in sin responder. */
    val sosCheckInMessage: String,

    /** Aviso de falsa alarma. %1$s = nombre del contacto. */
    val falseAlarmMessage: String,

    /** Texto del share sheet genérico de la app. %1$s = URL. */
    val genericShare: String,

    /** Base del link de seguimiento en vivo. %1$s = id del evento SOS. */
    val liveTrackingUrlTemplate: String,

    /** Base del link de invitación. %1$s = código de referido. */
    val inviteUrlTemplate: String,

    /** Base del deep link a un incidente en el mapa. %1$s = id del incidente. */
    val incidentUrlTemplate: String,

    /** Recompensa por referido. %1$s = días de escolta premium. */
    val referralReward: String,
)
