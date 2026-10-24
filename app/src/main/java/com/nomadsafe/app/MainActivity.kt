package com.nomadsafe.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomadsafe.app.core.designsystem.NomadSafeTheme
import com.nomadsafe.app.core.navigation.NavDestination
import com.nomadsafe.app.core.navigation.NomadSafeNavHost
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.feature.escort.EscortForegroundService
import com.nomadsafe.app.feature.escort.EscortStateHolder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Única Activity de la app (single-Activity + Compose Navigation).
 *
 * `launchMode="singleTask"` en el manifest es lo que hace que una alerta push o un
 * deep link de invitación traigan al frente la instancia existente en vez de crear
 * una segunda Activity con su propio estado del mapa. Sin eso, tocar una
 * notificación de SOS abriría una pantalla de mapa nueva y vacía.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var escortStateHolder: EscortStateHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen debe ir ANTES de super.onCreate: es la única forma de
        // que el splash se mantenga mientras se restaura el estado.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // Deep link de invitación (https://nomadsafe.app/i/CODIGO) o de alerta.
        val initialDestination = resolveDeepLink(intent)

        setContent {
            // La escolta activa fuerza tema oscuro: de noche una pantalla blanca
            // delata la posición de quien camina y lo deslumbra. Es seguridad, no
            // estética. Ver NomadSafeTheme.
            val escortActive by escortStateHolder.state.collectAsStateWithLifecycle()

            NomadSafeTheme(darkTheme = escortActive.isActive || androidx.compose.foundation.isSystemInDarkTheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NomadSafeNavHost(startDestination = initialDestination)
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Con singleTask, los deep links posteriores al primer arranque llegan acá.
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            // Las acciones del servicio llegan como intent de Activity cuando el
            // usuario toca una acción de la notificación persistente.
            EscortForegroundService.ACTION_CHECKIN_OK -> {
                NsLog.i(TAG, "Check-in confirmado desde la notificación")
                EscortForegroundService.sendAction(this, EscortForegroundService.ACTION_CHECKIN_OK)
            }
            EscortForegroundService.ACTION_FALSE_ALARM -> {
                NsLog.i(TAG, "Falsa alarma marcada desde la notificación")
                EscortForegroundService.sendAction(this, EscortForegroundService.ACTION_FALSE_ALARM)
            }
        }

        resolveDeepLink(intent)?.let { destination ->
            NsLog.i(TAG, "Deep link resuelto → $destination")
        }
    }

    /**
     * Convierte un intent entrante en un destino de navegación.
     *
     * Devuelve null si no hay deep link, y el llamador usa el destino por defecto.
     * Los tres casos cubiertos son los loops de crecimiento y de emergencia:
     * invitación, alerta compartida y seguimiento en vivo de un SOS.
     */
    private fun resolveDeepLink(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        val path = uri.pathSegments

        return when {
            // nomadsafe://invite/CODIGO  o  https://nomadsafe.app/i/CODIGO
            uri.host == "invite" && path.isNotEmpty() ->
                "${NavDestination.ONBOARDING}?referral=${path.first()}"
            uri.host == "nomadsafe.app" && path.firstOrNull() == "i" && path.size > 1 ->
                "${NavDestination.ONBOARDING}?referral=${path[1]}"

            // https://nomadsafe.app/alert/ID — alerta compartida en redes.
            uri.host == "nomadsafe.app" && path.firstOrNull() == "alert" && path.size > 1 ->
                "${NavDestination.MAP}?incidentId=${path[1]}"
            uri.host == "incident" && path.isNotEmpty() ->
                "${NavDestination.MAP}?incidentId=${path.first()}"

            // https://nomadsafe.app/track/SOS_ID — contacto siguiendo la escolta en vivo.
            uri.host == "nomadsafe.app" && path.firstOrNull() == "track" && path.size > 1 ->
                "${NavDestination.LIVE_TRACKING}?sosId=${path[1]}"
            uri.host == "track" && path.isNotEmpty() ->
                "${NavDestination.LIVE_TRACKING}?sosId=${path.first()}"

            else -> {
                NsLog.w(TAG, "Deep link no reconocido: $uri")
                null
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
