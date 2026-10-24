package com.nomadsafe.app.core.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/**
 * Destinos de navegación.
 *
 * Rutas como string y no como objetos serializables: con 8 destinos, el
 * type-safety extra de Navigation 3 no compensa la complejidad, y los strings
 * permiten construir deep links desde [com.nomadsafe.app.MainActivity] con el
 * mismo vocabulario.
 */
object NavDestination {
    const val AUTH = "auth"
    const val ONBOARDING = "onboarding"
    const val MAP = "map"
    const val REPORT = "report"
    const val ROUTE = "route"
    const val ESCORT = "escort"
    const val CONTACTS = "contacts"
    const val PROFILE = "profile"
    const val LIVE_TRACKING = "live_tracking"
}

/** Destinos de la barra inferior. El mapa es el principal. */
val TOP_LEVEL_DESTINATIONS = listOf(
    NavDestination.MAP,
    NavDestination.ROUTE,
    NavDestination.CONTACTS,
    NavDestination.PROFILE,
)

/**
 * Grafo de navegación de NomadSafe.
 *
 * ── Parámetros opcionales con valor por defecto ──────────────────────────────
 * `map` acepta `incidentId` y `onboarding` acepta `referral` como opcionales. Es
 * lo que permite que un deep link aterrice en la pantalla correcta con contexto
 * precargado sin necesitar rutas separadas para cada combinación.
 *
 * ── Estado del scaffold ──────────────────────────────────────────────────────
 * Las pantallas de cada destino están implementadas para MAP, ESCORT y REPORT,
 * que son las tres que sostienen el diferencial del producto. Las restantes
 * enrutan a un placeholder declarado explícitamente: un `TODO` silencioso en una
 * ruta de navegación produce una pantalla en blanco que parece un crash, mientras
 * que un placeholder nombrado dice de inmediato qué falta.
 */
@Composable
fun NomadSafeNavHost(
    startDestination: String? = null,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = startDestination ?: NavDestination.MAP,
    ) {
        mapDestinations(navController)
    }
}

private fun NavGraphBuilder.mapDestinations(navController: NavHostController) {
    composable(
        route = "${NavDestination.MAP}?incidentId={incidentId}",
        arguments = listOf(
            navArgument("incidentId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        com.nomadsafe.app.feature.map.MapScreen(
            focusIncidentId = entry.arguments?.getString("incidentId"),
            onReportClick = { navController.navigate(NavDestination.REPORT) },
            onEscortClick = { navController.navigate(NavDestination.ESCORT) },
            onRouteClick = { navController.navigate(NavDestination.ROUTE) },
        )
    }

    composable(NavDestination.REPORT) {
        com.nomadsafe.app.feature.report.ReportScreen(
            onDismiss = { navController.popBackStack() },
            onShare = { navController.navigate(NavDestination.MAP) },
        )
    }

    composable(NavDestination.ESCORT) {
        com.nomadsafe.app.feature.escort.EscortScreen(
            onBack = { navController.popBackStack() },
            onConfigureContacts = { navController.navigate(NavDestination.CONTACTS) },
        )
    }

    composable(NavDestination.ROUTE) {
        com.nomadsafe.app.feature.route.SafeRouteScreen(
            onStartEscort = { navController.navigate(NavDestination.ESCORT) },
        )
    }

    // ── Destinos con placeholder explícito ────────────────────────────────────
    composable(
        route = "${NavDestination.ONBOARDING}?referral={referral}",
        arguments = listOf(
            navArgument("referral") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        PlaceholderScreen(
            title = "Onboarding",
            detail = "Código de referido recibido: ${entry.arguments?.getString("referral") ?: "ninguno"}",
        )
    }

    composable(NavDestination.AUTH) { PlaceholderScreen("Autenticación", "OTP telefónico vía Supabase Auth") }
    composable(NavDestination.CONTACTS) { PlaceholderScreen("Contactos de confianza", "Alta e invitación por WhatsApp") }
    composable(NavDestination.PROFILE) { PlaceholderScreen("Perfil", "Ajustes, privacidad y borrado de cuenta") }

    composable(
        route = "${NavDestination.LIVE_TRACKING}?sosId={sosId}",
        arguments = listOf(
            navArgument("sosId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        PlaceholderScreen(
            title = "Seguimiento en vivo",
            detail = "SOS: ${entry.arguments?.getString("sosId") ?: "ninguno"}",
        )
    }
}

/**
 * Pantalla de marcador de posición.
 *
 * Dice explícitamente qué falta en vez de dejar el destino en blanco: una ruta de
 * navegación que lleva a una pantalla vacía se confunde con un crash, y en una
 * demo o en una revisión de código eso cuesta tiempo de diagnóstico.
 */
@Composable
private fun PlaceholderScreen(title: String, detail: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Pantalla pendiente de implementación",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
