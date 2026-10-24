package com.nomadsafe.app.feature.escort

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nomadsafe.app.MainActivity
import com.nomadsafe.app.R
import com.nomadsafe.app.domain.model.EscortSession
import com.nomadsafe.app.feature.escort.panic.PanicEngineState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canales de notificación de NomadSafe.
 *
 * Tres canales con importancias deliberadamente distintas. No es un detalle
 * cosmético: en Android el usuario puede silenciar un canal entero desde la
 * notificación, así que agrupar mal tiene consecuencias reales.
 *
 *  - [SOS] en MAX y con sonido/vibración propios. Debe atravesar No Molestar
 *    (que el usuario configure como prioridad) y despertar la pantalla.
 *  - [ESCORT] en LOW y silenciosa. Va a estar visible durante toda una caminata;
 *    si suena o vibra, el usuario desactiva la escolta, que es exactamente lo
 *    que no puede pasar.
 *  - [ALERTS] en DEFAULT. Alertas comunitarias: relevantes pero no urgentes.
 */
object NotificationChannels {
    const val ESCORT = "nomadsafe_escort"
    const val ALERTS = "nomadsafe_alerts"
    const val SOS = "nomadsafe_sos"

    /** Id de la notificación persistente del servicio en primer plano. */
    const val ESCORT_NOTIFICATION_ID = 1001
    const val SOS_NOTIFICATION_ID = 1002
    const val CHECKIN_NOTIFICATION_ID = 1003
}

@Singleton
class EscortNotificationFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Crea los canales. Idempotente: se llama en [com.nomadsafe.app.NomadSafeApplication]
     * y también al arrancar el servicio, por si el usuario borró los canales o
     * actualizó desde una versión que no los tenía.
     */
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val escortChannel = NotificationChannel(
            NotificationChannels.ESCORT,
            context.getString(R.string.notification_channel_escort),
            // LOW y no MIN: MIN puede ocultar la notificación en algunos launchers
            // y el usuario perdería la evidencia visual de que la escolta sigue activa.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_escort_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val alertsChannel = NotificationChannel(
            NotificationChannels.ALERTS,
            context.getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_alerts_desc)
        }

        val sosChannel = NotificationChannel(
            NotificationChannels.SOS,
            context.getString(R.string.notification_channel_sos),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_sos_desc)
            enableVibration(true)
            enableLights(true)
            // Patrón propio y reconocible: corto-largo-corto. Que el usuario pueda
            // identificar una emergencia NomadSafe sin mirar el teléfono.
            vibrationPattern = longArrayOf(0, 300, 200, 800, 200, 300)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true) // Requiere que el usuario conceda acceso a No Molestar.
        }

        manager.createNotificationChannels(listOf(escortChannel, alertsChannel, sosChannel))
    }

    /**
     * Notificación persistente del Modo Escolta.
     *
     * Es obligatoria mientras el servicio esté en primer plano y cumple una
     * función de producto además de la técnica: es la prueba visible y constante
     * de que la app está acompañando. Se actualiza en cada cambio de estado para
     * que refleje si la detección de voz está activa.
     */
    fun buildEscortNotification(
        session: EscortSession?,
        panicState: PanicEngineState,
    ): Notification {
        val destinationLabel = session?.destinationLabel
        val contentText = if (destinationLabel.isNullOrBlank()) {
            context.getString(R.string.notification_escort_text_no_destination)
        } else {
            context.getString(R.string.notification_escort_text, destinationLabel)
        }

        // Línea de estado del motor de voz: el usuario necesita saber si la
        // detección por palabra de pánico está realmente escuchando.
        val voiceStatus = when (panicState) {
            PanicEngineState.LISTENING -> context.getString(R.string.escort_listening)
            PanicEngineState.DOWNLOADING_MODEL,
            PanicEngineState.LOADING,
            -> context.getString(R.string.state_loading)
            PanicEngineState.UNAVAILABLE -> context.getString(R.string.escort_not_listening)
            PanicEngineState.IDLE -> context.getString(R.string.escort_not_listening)
        }

        return NotificationCompat.Builder(context, NotificationChannels.ESCORT)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(context.getString(R.string.notification_escort_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\n$voiceStatus"))
            .setOngoing(true)
            // No ocultable por swipe: deslizarla no debe dar la ilusión de que la
            // escolta terminó. Sólo se va cuando el servicio se detiene.
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(context, R.color.ns_primary))
            .setContentIntent(openAppIntent())
            .addAction(
                0,
                context.getString(R.string.escort_arrived),
                serviceIntent(EscortForegroundService.ACTION_ARRIVED),
            )
            .addAction(
                0,
                context.getString(R.string.escort_stop),
                serviceIntent(EscortForegroundService.ACTION_STOP),
            )
            .build()
    }

    /**
     * Notificación de SOS disparado.
     *
     * `CATEGORY_CALL` + prioridad MAX: en muchos OEM eso la hace aparecer como
     * heads-up a pantalla completa, que es lo que se necesita cuando la persona
     * no puede mirar el teléfono.
     */
    fun buildSosNotification(contactCount: Int): Notification =
        NotificationCompat.Builder(context, NotificationChannels.SOS)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(context.getString(R.string.notification_sos_title))
            .setContentText(context.getString(R.string.notification_sos_text))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.notification_sos_text) +
                        if (contactCount > 0) " ($contactCount)" else "",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setColor(ContextCompat.getColor(context, R.color.ns_danger))
            .setContentIntent(openAppIntent())
            .addAction(
                0,
                context.getString(R.string.escort_im_ok),
                serviceIntent(EscortForegroundService.ACTION_FALSE_ALARM),
            )
            .build()

    /** Notificación de check-in: "¿sigues bien?". Con acción de un toque. */
    fun buildCheckInNotification(): Notification =
        NotificationCompat.Builder(context, NotificationChannels.ESCORT)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(context.getString(R.string.escort_checkin))
            // IMPORTANCE_LOW del canal no la hace heads-up, así que se refuerza
            // con prioridad alta a nivel de notificación.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.ns_warning))
            .setContentIntent(serviceIntent(EscortForegroundService.ACTION_CHECKIN_OK))
            .addAction(
                0,
                context.getString(R.string.escort_im_ok),
                serviceIntent(EscortForegroundService.ACTION_CHECKIN_OK),
            )
            .build()

    /** Alerta comunitaria: incidente reportado cerca del usuario. */
    fun buildCommunityAlertNotification(
        categoryLabel: String,
        neighborhood: String?,
        incidentId: String,
    ): Notification {
        val title = if (neighborhood.isNullOrBlank()) categoryLabel else "$categoryLabel · $neighborhood"
        return NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.app_tagline))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.ns_primary))
            .setContentIntent(openAppIntent(deepLink = "nomadsafe://incident/$incidentId"))
            .build()
    }

    /**
     * Tipo de foreground service a declarar en `startForeground()`.
     *
     * Android 14 (API 34) exige pasar el tipo explícito y que coincida con el
     * `foregroundServiceType` del manifest. Si no coincide, o si el permiso
     * runtime correspondiente no está concedido, `startForeground` lanza
     * `SecurityException` y el servicio muere — en el peor momento posible.
     *
     * El tipo de micrófono sólo se incluye si la detección de voz está activa,
     * porque declararlo sin tener RECORD_AUDIO concedido es justo lo que provoca
     * esa excepción.
     */
    fun foregroundServiceType(voiceEnabled: Boolean): Int = when {
        Build.VERSION.SDK_INT >= 34 -> {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (voiceEnabled) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            type
        }
        // FOREGROUND_SERVICE_TYPE_LOCATION existe desde API 29.
        Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        else -> 0
    }

    fun postSosNotification(contactCount: Int) {
        // POST_NOTIFICATIONS es runtime desde API 33. Sin el permiso, notify()
        // no lanza: simplemente no muestra nada. Por eso el SOS además escribe en
        // el backend y dispara los intents a contactos — la notificación local es
        // refuerzo, no el mecanismo principal.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        NotificationManagerCompat.from(context)
            .notify(NotificationChannels.SOS_NOTIFICATION_ID, buildSosNotification(contactCount))
    }

    fun cancelSosNotification() {
        NotificationManagerCompat.from(context).cancel(NotificationChannels.SOS_NOTIFICATION_ID)
    }

    fun postCheckInNotification() {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        NotificationManagerCompat.from(context)
            .notify(NotificationChannels.CHECKIN_NOTIFICATION_ID, buildCheckInNotification())
    }

    fun cancelCheckInNotification() {
        NotificationManagerCompat.from(context).cancel(NotificationChannels.CHECKIN_NOTIFICATION_ID)
    }

    private fun openAppIntent(deepLink: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (deepLink != null) {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(deepLink)
            }
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceIntent(action: String): PendingIntent {
        val intent = Intent(context, EscortForegroundService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val REQUEST_OPEN_APP = 9001
    }
}
