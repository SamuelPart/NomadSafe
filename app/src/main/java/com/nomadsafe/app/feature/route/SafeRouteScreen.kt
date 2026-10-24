package com.nomadsafe.app.feature.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomadsafe.app.core.designsystem.NomadSafeTheme
import com.nomadsafe.app.domain.model.RiskLevel
import com.nomadsafe.app.domain.model.RoutePreference
import com.nomadsafe.app.domain.model.SafeRoute
import kotlin.time.Duration

/**
 * Pantalla de Rutas Seguras.
 *
 * ── Comparación explícita entre rutas ─────────────────────────────────────────
 * Se muestran las tres rutas calculadas con su riesgo, su duración y su
 * iluminación, y se marca cuál recomienda la preferencia elegida. Ocultar las
 * alternativas dejaría al usuario sin contexto para decidir: ver que la ruta más
 * segura cuesta 4 minutos más que la más rápida es exactamente la información que
 * permite tomar la decisión consciente. Una recomendación sin comparación es una
 * orden, no una ayuda.
 *
 * ── El desglose por tramo ─────────────────────────────────────────────────────
 * Cada ruta expande sus tramos críticos. Decir "riesgo alto" sin decir DÓNDE obliga
 * al usuario a adivinar; decirle "la cuadra de Av. España entre X e Y está sin
 * iluminación" es accionable.
 */
@Composable
fun SafeRouteScreen(
    onStartEscort: () -> Unit = {},
    viewModel: RouteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var destinationQuery by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ruta segura", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ── Destino ───────────────────────────────────────────────────────────
        OutlinedTextField(
            value = destinationQuery,
            onValueChange = { destinationQuery = it },
            label = { Text("¿A dónde vas?") },
            supportingText = { Text("Se buscan calles y referencias del piloto") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { viewModel.searchDestination(destinationQuery) },
            enabled = destinationQuery.length >= SEARCH_MIN_LENGTH,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Calcular rutas")
        }

        // ── Preferencia ───────────────────────────────────────────────────────
        PreferenceSelector(
            selected = state.preference,
            onSelect = { viewModel.setPreference(it) },
        )

        // ── Cargando ──────────────────────────────────────────────────────────
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        state.error?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = NomadSafeTheme.risk.high.copy(alpha = 0.12f))) {
                Text(error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── Resultados ────────────────────────────────────────────────────────
        if (state.routes.isNotEmpty()) {
            Text(
                text = "${state.routes.size} rutas · ${state.preference.displayName()}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            state.routes.forEach { route ->
                RouteCard(
                    route = route,
                    isRecommended = route.id == state.recommendedRouteId,
                    onSelect = { viewModel.selectRoute(route.id) },
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onStartEscort,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NomadSafeTheme.risk.escortActive),
            ) {
                Text("Iniciar escolta en esta ruta")
            }
        }
    }
}

@Composable
private fun PreferenceSelector(selected: RoutePreference, onSelect: (RoutePreference) -> Unit) {
    Column {
        Text("Preferencia", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutePreference.entries.forEach { preference ->
                androidx.compose.material3.AssistChip(
                    onClick = { onSelect(preference) },
                    label = { Text(preference.displayName()) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                )
            }
        }
        Text(
            text = selected.explanation(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RouteCard(route: SafeRoute, isRecommended: Boolean, onSelect: () -> Unit) {
    val riskColors = NomadSafeTheme.risk
    val riskColor = when (route.riskLevel) {
        RiskLevel.LOW -> riskColors.low
        RiskLevel.MODERATE -> riskColors.moderate
        RiskLevel.HIGH -> riskColors.high
        RiskLevel.CRITICAL -> riskColors.critical
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isRecommended) {
            CardDefaults.cardColors(containerColor = riskColor.copy(alpha = 0.10f))
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(riskColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = route.riskLevel.displayName().uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = riskColor,
                    modifier = Modifier.weight(1f),
                )
                if (isRecommended) {
                    Text(
                        text = "Recomendada",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = riskColor,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric(label = "Distancia", value = route.distanceMeters.formatDistance())
                Metric(label = "Tiempo", value = route.duration.formatDuration())
                Metric(label = "Iluminación", value = "${(route.litRatio * 100).toInt()}%")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = route.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Tramos críticos ───────────────────────────────────────────────
            val critical = route.criticalSegments()
            if (critical.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Tramos de atención (${critical.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = riskColors.high,
                )
                critical.take(MAX_CRITICAL_SHOWN).forEach { segment ->
                    Text(
                        text = "• ${segment.streetName ?: "Tramo sin nombre"} — " +
                            segment.reason(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                Text("Ver en el mapa")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val SEARCH_MIN_LENGTH = 3
private const val MAX_CRITICAL_SHOWN = 4

private fun RoutePreference.displayName(): String = when (this) {
    RoutePreference.SAFEST -> "Más segura"
    RoutePreference.BALANCED -> "Equilibrada"
    RoutePreference.FASTEST -> "Más rápida"
}

private fun RoutePreference.explanation(): String = when (this) {
    RoutePreference.SAFEST -> "Minimiza el riesgo aunque sume tiempo. Recomendada de noche."
    RoutePreference.BALANCED -> "Balancea riesgo y tiempo. La opción por defecto."
    RoutePreference.FASTEST -> "Minimiza el tiempo; el riesgo sólo desempata entre rutas iguales."
}

private fun RiskLevel.displayName(): String = when (this) {
    RiskLevel.LOW -> "Zona tranquila"
    RiskLevel.MODERATE -> "Precaución"
    RiskLevel.HIGH -> "Riesgo alto"
    RiskLevel.CRITICAL -> "Evitar"
}

private fun Double.formatDistance(): String = when {
    this < 1000 -> "${this.toInt()} m"
    else -> "%.1f km".format(this / 1000)
}

private fun Duration.formatDuration(): String = when {
    this.inWholeMinutes < 1 -> "menos de 1 min"
    this.inWholeHours < 1 -> "${this.inWholeMinutes} min"
    else -> "${this.inWholeHours} h ${this.inWholeMinutes % 60} min"
}

/**
 * Motivo legible por el que un tramo se marca como crítico.
 *
 * Se deriva del desglose del motor y no de un umbral arbitrario acá: si la UI
 * inventara su propio criterio, podría decir "sin iluminación" sobre un tramo que
 * el motor penalizó por otra cosa, y el usuario aprendería a desconfiar de la
 * explicación.
 */
private fun com.nomadsafe.app.domain.model.RouteSegment.reason(): String = when {
    lit == false -> "${lengthMeters.toInt()} m sin iluminación"
    lit == null -> "${lengthMeters.toInt()} m con iluminación desconocida"
    roadClass == com.nomadsafe.app.domain.model.RoadClass.TRUNK -> "${lengthMeters.toInt()} m en vía rápida"
    else -> "${lengthMeters.toInt()} m de exposición"
}
