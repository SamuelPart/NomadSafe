package com.nomadsafe.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Paleta de marca ───────────────────────────────────────────────────────────
// Los valores espejan res/values/colors.xml, que cubre la ventana previa a
// Compose (splash, barras del sistema). Si se cambia la marca hay que tocar los
// dos lados: un desajuste se ve como un flash de color al arrancar la app.

private val NsBlue = Color(0xFF0F6FFF)
private val NsBlueDark = Color(0xFF0A4FBE)
private val NsTeal = Color(0xFF00D3A7)
private val NsDanger = Color(0xFFFF3B30)
private val NsWarning = Color(0xFFFFB020)
private val NsSafe = Color(0xFF34C759)

private val NsBackgroundLight = Color(0xFFF5F7FB)
private val NsSurfaceLight = Color(0xFFFFFFFF)
private val NsOnBackgroundLight = Color(0xFF0A0E1A)

private val NsBackgroundDark = Color(0xFF0A0E1A)
private val NsSurfaceDark = Color(0xFF121829)
private val NsOnBackgroundDark = Color(0xFFE8ECF5)

/**
 * Colores semánticos propios de NomadSafe, que Material3 no modela.
 *
 * Material3 tiene `error`, pero una app de seguridad necesita distinguir TRES
 * estados que no son error: zona tranquila, precaución y peligro. Usar `error`
 * para los tres haría que "hay un robo cerca" y "tu ruta es segura" se vean igual.
 */
@Immutable
data class RiskColors(
    val low: Color,
    val moderate: Color,
    val high: Color,
    val critical: Color,
    val escortActive: Color,
    val sosActive: Color,
)

private val LightRiskColors = RiskColors(
    low = NsSafe,
    moderate = NsWarning,
    high = Color(0xFFFF6B35),
    critical = NsDanger,
    escortActive = NsBlue,
    sosActive = NsDanger,
)

private val DarkRiskColors = RiskColors(
    low = NsSafe,
    moderate = NsWarning,
    high = Color(0xFFFF8555),
    critical = NsDanger,
    escortActive = Color(0xFF4D94FF),
    sosActive = Color(0xFFFF5A4F),
)

private val LocalRiskColors = staticCompositionLocalOf { LightRiskColors }

/** Acceso a los colores de riesgo desde cualquier Composable. */
object NomadSafeTheme {
    val risk: RiskColors
        @Composable
        get() = LocalRiskColors.current
}

private val LightColors = lightColorScheme(
    primary = NsBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = NsBlueDark,
    secondary = NsTeal,
    onSecondary = Color(0xFF00382C),
    background = NsBackgroundLight,
    onBackground = NsOnBackgroundLight,
    surface = NsSurfaceLight,
    onSurface = NsOnBackgroundLight,
    surfaceVariant = Color(0xFFE4E9F2),
    onSurfaceVariant = Color(0xFF444B5C),
    error = NsDanger,
    onError = Color.White,
    outline = Color(0xFFB9C1D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4D94FF),
    onPrimary = Color(0xFF001A45),
    primaryContainer = NsBlueDark,
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = NsTeal,
    onSecondary = Color(0xFF001F19),
    background = NsBackgroundDark,
    onBackground = NsOnBackgroundDark,
    surface = NsSurfaceDark,
    onSurface = NsOnBackgroundDark,
    surfaceVariant = Color(0xFF1C2436),
    onSurfaceVariant = Color(0xFFB4BCCB),
    error = Color(0xFFFF6B60),
    onError = Color(0xFF3D0000),
    outline = Color(0xFF3A4356),
)

/**
 * Tema de NomadSafe.
 *
 * [darkTheme] sigue la preferencia del sistema por defecto, pero el Modo Escolta
 * fuerza el tema oscuro: de noche, una pantalla blanca encendida delata la
 * posición de quien camina y lo deslumbra, reduciendo su visión periférica. Es un
 * detalle de seguridad real, no estético.
 */
@Composable
fun NomadSafeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val riskColors = if (darkTheme) DarkRiskColors else LightRiskColors

    CompositionLocalProvider(LocalRiskColors provides riskColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = NomadSafeTypography,
            content = content,
        )
    }
}
