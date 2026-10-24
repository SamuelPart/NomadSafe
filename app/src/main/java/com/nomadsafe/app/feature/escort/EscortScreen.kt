package com.nomadsafe.app.feature.escort

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.nomadsafe.app.domain.model.EscortStatus

/**
 * Pantalla del Modo Escolta.
 *
 * ── La jerarquía visual refleja la jerarquía de riesgo ────────────────────────
 * Si hay un SOS activo, esa tarjeta domina la pantalla y todo lo demás pasa a
 * segundo plano. Si no, el control principal es el botón de arranque. No se
 * muestran ambos al mismo nivel: en una situación de estrés el usuario no puede
 * permitirse escanear la pantalla buscando qué botón importa.
 *
 * ── El botón de SOS es grande, rojo y siempre accesible ───────────────────────
 * Ocupa el ancho completo y no se deshabilita nunca, ni siquiera cuando no hay
 * sesión activa. Un botón de SOS que se deshabilita "por coherencia de estado" es
 * un botón que falla en el único momento en que importa. Si no hay sesión, el
 * disparo crea una igualmente.
 */
@Composable
fun EscortScreen(
    onBack: () -> Unit = {},
    onConfigureContacts: () -> Unit = {},
    viewModel: EscortViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var voiceDetectionEnabled by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Escolta virtual",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        // ── Bloqueo por falta de contactos ────────────────────────────────────
        if (state.blockedReason == BlockedReason.NO_CONTACTS) {
            NoContactsCard(onConfigureContacts = onConfigureContacts)
        }

        // ── SOS activo: máxima prioridad visual ───────────────────────────────
        if (state.hasSos) {
            SosActiveCard(
                onResolveFalseAlarm = { viewModel.resolveFalseAlarm(null) },
                onShareTracking = { viewModel.buildLiveTrackingShareText() },
            )
        }

        // ── Aviso del servicio (batería, desvío, GPS) ─────────────────────────
        state.warning?.let { warning ->
            WarningBanner(text = warning)
        }

        // ── Check-in pendiente con cuenta regresiva ───────────────────────────
        val deadline = state.checkInDeadlineEpochMillis
        if (deadline != null && state.isActive) {
            val remainingSeconds = ((deadline - state.timeProviderNowEpochMillis) / 1000L).coerceAtLeast(0)
            CheckInCard(
                remainingSeconds = remainingSeconds,
                onConfirm = { viewModel.confirmCheckIn() },
            )
        }

        // ── Estado de la sesión ───────────────────────────────────────────────
        if (state.isActive) {
            ActiveSessionCard(
                status = state.activeSession?.status ?: EscortStatus.ACTIVE,
                onStop = { viewModel.stopEscort() },
                onArrived = { viewModel.confirmArrival() },
            )
        } else if (!state.hasSos) {
            StartEscortCard(
                voiceDetectionEnabled = voiceDetectionEnabled,
                onVoiceToggle = { voiceDetectionEnabled = it },
                canStart = state.canStartEscort,
                onStart = { viewModel.startEscort(null, null, voiceDetectionEnabled) },
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Botón de SOS: siempre presente, nunca deshabilitado ───────────────
        SosButton(onClick = { viewModel.triggerSos() })

        // ── Historial ─────────────────────────────────────────────────────────
        if (state.sosHistory.isNotEmpty()) {
            Text(
                text = "Historial de alertas",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            state.sosHistory.forEach { event ->
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = event.trigger.displayName(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (event.isResolved) "Resuelto" else "Activo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoContactsCard(onConfigureContacts: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = NomadSafeTheme.risk.moderate.copy(alpha = 0.15f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Necesitás contactos de confianza", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Sin contactos no hay a quién avisar si entrás en peligro. La escolta seguiría registrando tu posición, pero no podría alertar a nadie.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onConfigureContacts) { Text("Configurar contactos") }
        }
    }
}

@Composable
private fun SosActiveCard(onResolveFalseAlarm: () -> Unit, onShareTracking: () -> String?) {
    Card(colors = CardDefaults.cardColors(containerColor = NomadSafeTheme.risk.sosActive.copy(alpha = 0.18f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("ALERTA ENVIADA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NomadSafeTheme.risk.sosActive)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tus contactos de confianza recibieron tu ubicación y el enlace de seguimiento.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onResolveFalseAlarm) { Text("Fue una falsa alarma") }
            }
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = NomadSafeTheme.risk.moderate.copy(alpha = 0.12f))) {
        Text(text = text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CheckInCard(remainingSeconds: Long, onConfirm: () -> Unit) {
    val totalSeconds = 90L
    val progress = (remainingSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)

    Card(colors = CardDefaults.cardColors(containerColor = NomadSafeTheme.risk.high.copy(alpha = 0.12f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("¿Estás bien?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Si no confirmás en ${remainingSeconds}s se enviará una alerta a tus contactos.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Estoy bien") }
        }
    }
}

@Composable
private fun ActiveSessionCard(status: EscortStatus, onStop: () -> Unit, onArrived: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = NomadSafeTheme.risk.escortActive.copy(alpha = 0.15f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(NomadSafeTheme.risk.escortActive),
                )
                Spacer(Modifier.width(8.dp))
                Text(status.displayName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "La escolta sigue activa con la pantalla bloqueada. Vas a recibir avisos si algo anda mal.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onArrived) { Text("Llegué") }
                OutlinedButton(onClick = onStop) { Text("Finalizar") }
            }
        }
    }
}

@Composable
private fun StartEscortCard(
    voiceDetectionEnabled: Boolean,
    onVoiceToggle: (Boolean) -> Unit,
    canStart: Boolean,
    onStart: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Iniciar escolta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Registra tu recorrido, pide confirmaciones periódicas y activa la detección de palabras de pánico por micrófono, todo sin conexión a internet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Detección por voz", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Procesa el audio localmente con Vosk. Nada sale del teléfono.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = voiceDetectionEnabled, onCheckedChange = onVoiceToggle)
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NomadSafeTheme.risk.escortActive),
            ) {
                Text("Comenzar")
            }
        }
    }
}

/**
 * Botón de SOS.
 *
 * Ocupa el ancho completo y usa un tamaño de toque generoso (56 dp). En una
 * situación real de peligro el usuario puede estar moviéndose, con las manos
 * ocupadas o con la pantalla mojada. Un botón pequeño es un botón que falla.
 */
@Composable
private fun SosButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NomadSafeTheme.risk.sosActive),
    ) {
        Text("SOS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
    }
}

private fun EscortStatus.displayName(): String = when (this) {
    EscortStatus.ACTIVE -> "Escolta activa"
    EscortStatus.ESCALATED -> "Precaución: check-in pendiente"
    EscortStatus.SOS -> "Alerta enviada"
    EscortStatus.COMPLETED -> "Finalizada"
    EscortStatus.CANCELLED -> "Cancelada"
}

private fun com.nomadsafe.app.domain.model.SosTrigger.displayName(): String = when (this) {
    com.nomadsafe.app.domain.model.SosTrigger.MANUAL -> "Botón de SOS"
    com.nomadsafe.app.domain.model.SosTrigger.PANIC_WORD -> "Palabra de pánico detectada"
    com.nomadsafe.app.domain.model.SosTrigger.CHECKIN_MISSED -> "Check-in sin responder"
    com.nomadsafe.app.domain.model.SosTrigger.ROUTE_DEVIATION -> "Desvío de ruta"
    com.nomadsafe.app.domain.model.SosTrigger.CONTACT_RAISED -> "Alerta de un contacto"
}
