package com.nomadsafe.app.feature.route

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.nomadsafe.app.domain.model.CandidateRoute
import com.nomadsafe.app.domain.model.RiskAssessment
import com.nomadsafe.app.domain.model.RiskLevel
import com.nomadsafe.app.domain.model.RoutePreference
import com.nomadsafe.app.domain.model.RouteSegment
import com.nomadsafe.app.domain.model.SafeRoute
import com.nomadsafe.app.domain.repository.DestinationSuggestion

/**
 * Pantalla de Rutas Seguras.
 *
 * ── Comparación explícita entre rutas ─────────────────────────────────────────
 * Se muestran todas las candidatas con su riesgo, duración, distancia e
 * iluminación, marcando cuál recomienda la preferencia elegida.
 *
 * Ocultar las alternativas dejaría al usuario sin contexto para decidir. Ver que
 * la ruta más segura cuesta 4 minutos más que la rápida es exactamente la
 * información que permite tomar la decisión consciente; una recomendación sin
 * comparación es una orden, no una ayuda.
 *
 * ── El costo en tiempo se muestra SIEMPRE, incluso cuando es cero ─────────────
 * `extraMinutesVsFastest` se pinta en cada tarjeta. Ocultar el costo temporal de
 * una decisión de seguridad es la forma más rápida de que el usuario deje de
 * confiar en la recomendación: si un día descubre por su cuenta que la ruta
 * "segura" tardaba el doble y la app no se lo dijo, deja de usarla.
 *
 * ── Justificación legible ─────────────────────────────────────────────────────
 * Cada ruta muestra los `reasons` que produjo el motor. Decir "riesgo alto" sin
 * decir por qué obliga a adivinar; decir "cruzás 3 cuadras sin iluminación en
 * Av. España" es accionable y, además, enseña qué mira la app.
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

        // ── Origen ────────────────────────────────────────────────────────────
        Text(
            text = state.originLabel ?: "Desde tu ubicación actual",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Destino ───────────────────────────────────────────────────────────
        OutlinedTextField(
            value = destinationQuery,
            onValueChange = { destinationQuery = it },
            label = { Text("¿A dónde vas?") },
            supportingText = { Text("Calles y referencias dentro de la zona del piloto") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = { viewModel.searchDestination(destinationQuery) },
            enabled = destinationQuery.trim().length >= SEARCH_MIN_LENGTH && !state.isSearching,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isSearching) "Buscando…" else "Buscar destino")
        }

        // ── Sugerencias de destino ────────────────────────────────────────────
        // Se muestran sólo cuando hay más de una: con un resultado único el
        // ViewModel ya lo eligió y calcular las rutas directamente.
        if (state.suggestions.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Elegí cuál buscabas",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                state.suggestions.forEach { suggestion ->
                    SuggestionRow(
                        suggestion = suggestion,
                        onClick = {
                            viewModel.selectSuggestion(suggestion)
                            destinationQuery = suggestion.name
                        },
                    )
                }
            }
        }

        state.destination?.let { destination ->
            Text(
                text = "Destino: ${destination.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ── Preferencia ───────────────────────────────────────────────────────
        PreferenceSelector(
            selected = state.preference,
            onSelect = { viewModel.setPreference(it) },
        )

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
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

            state.routes.forEach { safeRoute ->
                RouteCard(
                    safeRoute = safeRoute,
                    isRecommended = safeRoute.route.id == state.recommendedRouteId,
                    isSelected = safeRoute.route.id == state.selectedRouteId,
                    onSelect = { viewModel.selectRoute(safeRoute.route.id) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Aviso cuando la recomendada cruza un tramo crítico ────────────
            // Es el gancho entre esta pantalla y el Modo Escolta: si la mejor ruta
            // disponible igual pasa por una zona peligrosa, ofrecer la escolta es
            // lo único que queda por hacer, y decirlo acá es el momento en que el
            // usuario está decidiendo, no después de que ya salió.
            val recommended = state.routes.firstOrNull { it.route.id == state.recommendedRouteId }
            if (recommended?.assessment?.breakdown?.crossesCriticalSegment == true) {
                Card(colors = CardDefaults.cardColors(containerColor = NomadSafeTheme.risk.critical.copy(alpha = 0.12f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Incluso la ruta más segura cruza una zona crítica",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = NomadSafeTheme.risk.critical,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onStartEscort,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NomadSafeTheme.risk.escortActive),
                        ) {
                            Text("Activar escolta para este recorrido")
                        }
                    }
                }
            } else {
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
}

@Composable
private fun SuggestionRow(suggestion: DestinationSuggestion, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(suggestion.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            suggestion.address?.let { address ->
                Text(
                    text = address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            suggestion.distanceFromBiasMeters?.let { meters ->
                Text(
                    text = "a ${meters.formatDistance()} de tu posición",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
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
                AssistChip(
                    onClick = { onSelect(preference) },
                    label = { Text(preference.displayName()) },
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = selected.explanation(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tarjeta de una ruta evaluada.
 *
 * Recibe el [SafeRoute] completo — candidata más evaluación — y no un modelo
 * aplanado inventado en la UI: los campos que se muestran (`assessment.level`,
 * `route.durationSeconds`, `reasons`) vienen directamente del motor, así que lo
 * que se ve es exactamente lo que se calculó.
 */
@Composable
private fun RouteCard(
    safeRoute: SafeRoute,
    isRecommended: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val candidate: CandidateRoute = safeRoute.route
    val assessment: RiskAssessment = safeRoute.assessment
    val riskColors = NomadSafeTheme.risk
    val riskColor = riskColors.forLevel(assessment.level)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isRecommended) {
            CardDefaults.cardColors(containerColor = riskColor.copy(alpha = 0.10f))
        } else {
            CardDefaults.cardColors()
        },
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Encabezado: nivel de riesgo ───────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(riskColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = assessment.level.displayName().uppercase(),
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

            Spacer(Modifier.height(10.dp))

            // ── Métricas ──────────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric("Distancia", candidate.distanceMeters.formatDistance())
                Metric("Tiempo", candidate.durationSeconds.formatSeconds())
                Metric("Iluminada", candidate.segments.litRatio().formatPercent())
                Metric("Riesgo", "%.2f".format(assessment.score))
            }

            // ── Costo en tiempo frente a la más rápida ────────────────────────
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    candidate.isFastest -> "Es la ruta más rápida"
                    safeRoute.extraMinutesVsFastest <= 0 -> "Mismo tiempo que la más rápida"
                    else -> "+${safeRoute.extraMinutesVsFastest} min que la más rápida"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (safeRoute.extraMinutesVsFastest > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    riskColors.low
                },
            )

            // ── Justificación del motor ───────────────────────────────────────
            if (safeRoute.reasons.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                safeRoute.reasons.take(MAX_REASONS_SHOWN).forEach { reason ->
                    Text(
                        text = "· ${reason.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Tramos sin iluminación ────────────────────────────────────────
            val unlit = candidate.segments.filter { it.lit == false }
            if (unlit.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Tramos sin iluminación (${unlit.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = riskColors.high,
                )
                unlit.take(MAX_CRITICAL_SHOWN).forEach { segment ->
                    Text(
                        text = "• ${segment.streetName ?: "Tramo sin nombre"} — ${segment.lengthMeters.toInt()} m",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                Text(if (isSelected) "Seleccionada" else "Ver en el mapa")
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
private const val MAX_REASONS_SHOWN = 3

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

private fun com.nomadsafe.app.core.designsystem.RiskColors.forLevel(level: RiskLevel) = when (level) {
    RiskLevel.LOW -> low
    RiskLevel.MODERATE -> moderate
    RiskLevel.HIGH -> high
    RiskLevel.CRITICAL -> critical
}

/**
 * Proporción de tramos con iluminación CONFIRMADA.
 *
 * `lit == null` (desconocida) no cuenta como iluminada ni como oscura: contarla
 * como iluminada inflaría el porcentaje y haría ver segura una ruta de la que
 * simplemente no tenemos datos, que es el sesgo más peligroso posible en una app
 * de prevención. El denominador son todos los tramos, así que la iluminación
 * desconocida se refleja como ausencia de confirmación.
 */
private fun List<RouteSegment>.litRatio(): Double {
    if (isEmpty()) return 0.0
    val totalLength = sumOf { it.lengthMeters }
    if (totalLength <= 0.0) return 0.0
    // Ponderado por longitud y no por cantidad de tramos: una avenida larga sin luz
    // importa más que tres callejones cortos iluminados.
    val litLength = filter { it.lit == true }.sumOf { it.lengthMeters }
    return litLength / totalLength
}

private fun Double.formatDistance(): String = when {
    this < 1000 -> "${toInt()} m"
    else -> "%.1f km".format(this / 1000)
}

private fun Double.formatPercent(): String = "${(this * 100).toInt()}%"

/**
 * Duración desde segundos.
 *
 * `CandidateRoute.durationSeconds` es un Int de segundos, no un `Duration`: viene
 * así del DTO de routing (esquema OSRM v5) y convertirlo a Duration sólo para formatearlo
 * agregaría un tipo intermedio sin beneficio.
 */
private fun Int.formatSeconds(): String = when {
    this < 60 -> "menos de 1 min"
    this < 3600 -> "${this / 60} min"
    else -> "${this / 3600} h ${(this % 3600) / 60} min"
}
