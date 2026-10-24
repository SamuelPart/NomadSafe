package com.nomadsafe.app.feature.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nomadsafe.app.domain.model.IncidentCategory
import com.nomadsafe.app.domain.model.IncidentTiming

/** Estado local del formulario de reporte. */
private data class ReportFormState(
    val category: IncidentCategory = IncidentCategory.THEFT,
    val description: String = "",
    val timing: IncidentTiming = IncidentTiming.RECENT,
    /** Desplazamiento del pin respecto de la posición del usuario, en metros. */
    val locationOffsetMeters: Double = 0.0,
    val shareAfterSubmit: Boolean = true,
) {
    val canSubmit: Boolean get() = description.length >= MIN_DESCRIPTION_LENGTH
}

private const val MIN_DESCRIPTION_LENGTH = 10
private const val MAX_DESCRIPTION_LENGTH = 500

/**
 * Formulario de reporte de incidente.
 *
 * ── Fricción deliberada, no olvido ────────────────────────────────────────────
 * El formulario exige una descripción mínima de 10 caracteres y pide elegir cuándo
 * ocurrió. No es burocracia: cada campo extra es una barrera contra el reporte
 * falso o vacío, que es lo que destruye la utilidad de un mapa colaborativo. Un
 * mapa lleno de puntos sin contexto deja de informar y la gente deja de usarlo.
 *
 * ── El desplazamiento del pin ─────────────────────────────────────────────────
 * Permite mover la ubicación exacta del reporte unos metros. Parece un detalle
 * menor y no lo es: reportar en la puerta de tu casa cuando el incidente fue en la
 * esquina revela dónde vivís a todo el que vea el mapa. Dar control explícito sobre
 * la precisión es privacidad por diseño, no un ajuste opcional.
 *
 * ── Compartir al publicar ─────────────────────────────────────────────────────
 * Activado por defecto y con el texto ya redactado. Es el loop de crecimiento
 * principal: cada reporte puede convertirse en una alerta compartida que trae
 * usuarios nuevos al mapa.
 */
@Composable
fun ReportScreen(
    onDismiss: () -> Unit = {},
    onShare: () -> Unit = {},
) {
    var form by rememberSaveable(stateSaver = ReportFormStateSaver) { mutableStateOf(ReportFormState()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Reportar incidente", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = "Tu reporte se publica en el mapa comunitario. Los datos exactos de tu ubicación no se comparten.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Categoría ─────────────────────────────────────────────────────────
        CategorySelector(
            selected = form.category,
            onSelect = { category -> form = form.copy(category = category) },
        )

        // ── Cuándo ocurrió ────────────────────────────────────────────────────
        TimingSelector(
            selected = form.timing,
            onSelect = { timing -> form = form.copy(timing = timing) },
        )

        // ── Descripción ───────────────────────────────────────────────────────
        Column {
            OutlinedTextField(
                value = form.description,
                onValueChange = { text ->
                    form = form.copy(description = text.take(MAX_DESCRIPTION_LENGTH))
                },
                label = { Text("Qué pasó") },
                supportingText = {
                    Text("${form.description.length}/$MAX_DESCRIPTION_LENGTH — mínimo $MIN_DESCRIPTION_LENGTH caracteres")
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
            )
        }

        // ── Precisión de la ubicación ─────────────────────────────────────────
        LocationPrecisionCard(
            offsetMeters = form.locationOffsetMeters,
            onOffsetChange = { offset -> form = form.copy(locationOffsetMeters = offset) },
        )

        // ── Compartir ─────────────────────────────────────────────────────────
        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Compartir alerta al publicar", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Genera un mensaje listo para WhatsApp o redes con el mapa del incidente.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Checkbox(
                        checked = form.shareAfterSubmit,
                        onCheckedChange = { checked -> form = form.copy(shareAfterSubmit = checked) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onShare,
            enabled = form.canSubmit,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Publicar reporte")
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}

@Composable
private fun CategorySelector(selected: IncidentCategory, onSelect: (IncidentCategory) -> Unit) {
    Column {
        Text("Categoría", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        // Flow de chips. Se usa un arreglo simple en filas de a 3 en vez de
        // FlowRow para no depender del API experimental de foundation-layout.
        val categories = IncidentCategory.entries.chunked(3)
        categories.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { category ->
                    AssistChip(
                        onClick = { onSelect(category) },
                        label = { Text(category.displayName(), style = MaterialTheme.typography.labelSmall) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimingSelector(selected: IncidentTiming, onSelect: (IncidentTiming) -> Unit) {
    Column {
        Text("Cuándo ocurrió", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IncidentTiming.entries.forEach { timing ->
                AssistChip(
                    onClick = { onSelect(timing) },
                    label = { Text(timing.displayName()) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
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
 * Control de precisión de la ubicación.
 *
 * Se presenta como "qué tan exacto querés que sea el punto" y no como "agregá
 * ruido a tu ubicación": el segundo framing suena a defecto técnico, el primero a
 * control de privacidad, que es lo que realmente es.
 */
@Composable
private fun LocationPrecisionCard(offsetMeters: Double, onOffsetChange: (Double) -> Unit) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Precisión de la ubicación", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    offsetMeters < 10.0 -> "Ubicación exacta. Recomendado sólo si el incidente fue lejos de tu casa."
                    offsetMeters < 60.0 -> "Precisión de cuadra. Buena opción por defecto."
                    else -> "Precisión de barrio. Oculta tu ubicación aproximada."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = offsetMeters.toFloat(),
                onValueChange = { value -> onOffsetChange(value.toDouble()) },
                valueRange = 0f..120f,
                steps = 11,
            )
            Text("${offsetMeters.toInt()} m de desplazamiento", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun IncidentCategory.displayName(): String = when (this) {
    IncidentCategory.ASSAULT -> "Agresión"
    IncidentCategory.THEFT -> "Robo"
    IncidentCategory.HARASSMENT -> "Acoso"
    IncidentCategory.SUSPICIOUS -> "Sospechoso"
    IncidentCategory.DARK_STREET -> "Calle oscura"
    IncidentCategory.ROAD_BLOCKED -> "Calle cortada"
    IncidentCategory.ACCIDENT -> "Accidente"
    IncidentCategory.OTHER -> "Otro"
}

private fun IncidentTiming.displayName(): String = when (this) {
    IncidentTiming.NOW -> "Ahora"
    IncidentTiming.RECENT -> "Reciente"
    IncidentTiming.RECURRENT -> "Pasa seguido"
}

/**
 * Explicación del impacto del timing.
 *
 * Decirle al usuario qué hace cada opción es lo que convierte el campo en
 * educación sobre el modelo de riesgo: "pasa seguido" pesa 3× en el score, y
 * saberlo hace que la gente reporte patrones y no sólo eventos aislados, que es
 * exactamente la señal más valiosa para el mapa.
 */
private fun IncidentTiming.explanation(): String = when (this) {
    IncidentTiming.NOW -> "Situación en curso. Máxima prioridad en el mapa."
    IncidentTiming.RECENT -> "Ocurrió hace poco. Pesa normal en el cálculo de riesgo."
    IncidentTiming.RECURRENT -> "Zona con problemas repetidos. Pesa 3× en el riesgo: un patrón importa más que un evento aislado."
}

/**
 * Saver para [ReportFormState].
 *
 * Sin esto, rotar la pantalla o un cambio de configuración del sistema descarta el
 * texto que el usuario estaba escribiendo. Perder un reporte de un incidente de
 * seguridad a mitad de escritura es perderlo por completo: la probabilidad de que
 * vuelva a escribirlo es baja.
 */
private val ReportFormStateSaver = androidx.compose.runtime.saveable.Saver<ReportFormState, List<Any>>(
    save = { state ->
        listOf(
            state.category.name,
            state.description,
            state.timing.name,
            state.locationOffsetMeters,
            state.shareAfterSubmit,
        )
    },
    restore = { saved ->
        ReportFormState(
            category = runCatching { IncidentCategory.valueOf(saved[0] as String) }.getOrDefault(IncidentCategory.OTHER),
            description = saved[1] as String,
            timing = runCatching { IncidentTiming.valueOf(saved[2] as String) }.getOrDefault(IncidentTiming.RECENT),
            locationOffsetMeters = saved[3] as Double,
            shareAfterSubmit = saved[4] as Boolean,
        )
    },
)
