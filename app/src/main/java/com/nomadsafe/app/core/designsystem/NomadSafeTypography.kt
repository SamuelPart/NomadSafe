package com.nomadsafe.app.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tipografía.
 *
 * Se usa la familia del sistema a propósito: añadir una fuente propia suma
 * ~200 KB al APK y, más importante, una fuente con buena legibilidad latina no
 * garantiza cobertura de los glifos que usan los nombres de calles peruanos.
 *
 * Los tamaños están pensados para lectura en exteriores con luz directa, que es
 * el contexto real de uso: alguien mirando el teléfono parado en una esquina.
 * Eso significa contrastes y tamaños un poco más grandes de lo que se usaría en
 * una app de consumo típica.
 */
val NomadSafeTypography = Typography(
    // Números grandes del panel de escolta: distancia, ETA, tiempo restante.
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),

    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),

    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),

    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),

    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        // 17sp y no 16sp: legibilidad en exteriores.
        fontSize = 17.sp,
        lineHeight = 25.sp,
    ),

    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),

    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),

    // Estilo del botón SOS: grande, en mayúsculas, sin tracking excesivo para que
    // se lea de un vistazo en situación de estrés.
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)
