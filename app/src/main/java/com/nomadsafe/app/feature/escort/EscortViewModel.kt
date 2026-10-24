package com.nomadsafe.app.feature.escort

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nomadsafe.app.core.config.AppConfig
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.domain.model.EscortSession
import com.nomadsafe.app.domain.model.EscortStatus
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.SosEvent
import com.nomadsafe.app.domain.model.TrustContact
import com.nomadsafe.app.domain.repository.EscortRepository
import com.nomadsafe.app.domain.repository.TrustContactRepository
import com.nomadsafe.app.feature.growth.SosMessageBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class EscortScreenUiState(
    val activeSession: EscortSession? = null,
    val activeSos: SosEvent? = null,
    val contactCount: Int = 0,
    val contactsConfigured: Boolean = false,
    val sosHistory: List<SosEvent> = emptyList(),
    val canStartEscort: Boolean = false,
    val isSubmitting: Boolean = false,
    val checkInDeadlineEpochMillis: Long? = null,
    val timeProviderNowEpochMillis: Long = 0L,
    val warning: String? = null,
    val firstContactOrNull: TrustContact? = null,
) {
    val isActive: Boolean get() = activeSession?.status == EscortStatus.ACTIVE ||
        activeSession?.status == EscortStatus.ESCALATED

    val hasSos: Boolean get() = activeSos != null && !activeSos.isResolved

    /**
     * Bloqueo de arranque por falta de contactos.
     *
     * No es una validación de formulario: arrancar una escolta sin contactos es
     * arrancar algo que no puede cumplir su promesa. Si el usuario entra en peligro
     * y no hay a quién avisar, la app le dio una falsa sensación de seguridad, que
     * es peor que no haberla tenido. Por eso el estado lo expone como un bloqueo
     * explícito y la pantalla lo explica, no como un botón deshabilitado sin motivo.
     */
    val blockedReason: BlockedReason?
        get() = when {
            !contactsConfigured -> BlockedReason.NO_CONTACTS
            isSubmitting -> BlockedReason.SUBMITTING
            else -> null
        }
}

enum class BlockedReason { NO_CONTACTS, SUBMITTING }

/**
 * ViewModel del Modo Escolta.
 *
 * ── División de responsabilidad con EscortStateHolder ─────────────────────────
 * Este ViewModel NO ejecuta la escolta: quien lo hace es
 * [EscortForegroundService], que sobrevive a que la pantalla se cierre o la app
 * pase a segundo plano. El ViewModel sólo proyecta el estado compartido en
 * [EscortStateHolder] hacia la UI y despacha intenciones al servicio.
 *
 * Si el ViewModel intentara gestionar la escolta directamente, matarla el sistema
 * por presión de memoria cancelaría el seguimiento sin que la notificación ni los
 * contactos de confianza se enteraran. El servicio en primer plano existe
 * precisamente para eso.
 *
 * ── Check-in: el contador vive en la UI ───────────────────────────────────────
 * El deadline del check-in lo publica el servicio, pero la cuenta regresiva la
 * avanza la UI con un ticker local. El servicio no puede permitirse despertar cada
 * segundo para actualizar un número en pantalla, y la UI sí puede hacerlo sin costo
 * de batería porque ya está visible.
 */
@HiltViewModel
class EscortViewModel @Inject constructor(
    private val escortRepository: EscortRepository,
    private val contactRepository: TrustContactRepository,
    private val escortStateHolder: EscortStateHolder,
    private val appConfig: AppConfig,
    private val timeProvider: TimeProvider,
    private val sosMessages: SosMessageBuilder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EscortScreenUiState(timeProviderNowEpochMillis = timeProvider.nowMillis()),
    )
    val uiState: StateFlow<EscortScreenUiState> = _uiState.asStateFlow()

    init {
        observeRepository()
        observeServiceState()
        startClockTicker()
    }

    /** Sesión activa, SOS activo e historial, todos desde el repositorio local. */
    private fun observeRepository() {
        viewModelScope.launch {
            combine(
                escortRepository.observeActiveSession(),
                escortRepository.observeActiveSos(),
                escortRepository.observeSosHistory(limit = HISTORY_LIMIT),
                contactRepository.observeCount(),
            ) { session, sos, history, contactCount ->
                EscortScreenUiState(
                    activeSession = session,
                    activeSos = sos,
                    sosHistory = history,
                    contactCount = contactCount,
                    contactsConfigured = contactCount > 0,
                    canStartEscort = contactCount > 0,
                    timeProviderNowEpochMillis = timeProvider.nowMillis(),
                )
            }.collect { snapshot ->
                // Se preserva el estado efímero de UI (isSubmitting, warning) que
                // no viene del repositorio.
                _uiState.value = snapshot.copy(
                    isSubmitting = _uiState.value.isSubmitting,
                    warning = _uiState.value.warning,
                    checkInDeadlineEpochMillis = _uiState.value.checkInDeadlineEpochMillis,
                )
            }
        }
    }

    /** Proyección del estado del servicio: avisos, batería baja, desvíos. */
    private fun observeServiceState() {
        viewModelScope.launch {
            escortStateHolder.state.collect { serviceState ->
                _uiState.value = _uiState.value.copy(
                    warning = serviceState.warnings.firstOrNull()?.describe(),
                    // El deadline del check-in lo deriva la UI del último check-in
                    // de la sesión, no lo publica el servicio: el servicio no puede
                    // permitirse despertar cada segundo para republicar un número,
                    // y la UI visible sí puede contarlo sin costo de batería.
                    checkInDeadlineEpochMillis = deriveCheckInDeadline(serviceState.session),
                )
            }
        }
    }

    /** Ticker de 1 s sólo mientras la UI está viva, para la cuenta regresiva. */
    private fun startClockTicker() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(CLOCK_TICK)
                _uiState.value = _uiState.value.copy(
                    timeProviderNowEpochMillis = timeProvider.nowMillis(),
                )
            }
        }
    }

    /**
     * Arranca una sesión de escolta.
     *
     * Se arranca el SERVICIO, no una coroutine del ViewModel. La coroutine moriría
     * con la pantalla y el usuario perdería el seguimiento sin notificación alguna,
     * que es el fallo más grave posible en esta función.
     */
    fun startEscort(destination: GeoPoint?, destinationName: String?, voiceDetectionEnabled: Boolean) {
        if (_uiState.value.blockedReason == BlockedReason.NO_CONTACTS) {
            _uiState.value = _uiState.value.copy(
                warning = "Configurá al menos un contacto de confianza antes de arrancar la escolta.",
            )
            return
        }

        _uiState.value = _uiState.value.copy(isSubmitting = true)

        viewModelScope.launch {
            EscortForegroundService.start(
                context = appContext(),
                destination = destination,
                destinationName = destinationName,
                voiceDetectionEnabled = voiceDetectionEnabled,
            )
            _uiState.value = _uiState.value.copy(isSubmitting = false)
            NsLog.i(TAG, "Escolta solicitada al servicio (destino=$destinationName)")
        }
    }

    /**
     * Finaliza la escolta por decisión del usuario.
     *
     * Usa [EscortStatus.COMPLETED] y no CANCELLED: cancelar implicaría que la
     * escolta no cumplió su ciclo, y el historial debe distinguir "terminé de
     * caminar" de "la corté a la mitad", que es señal relevante si después hay que
     * auditar por qué un recorrido quedó sin cierre.
     */
    fun stopEscort() {
        viewModelScope.launch {
            val sessionId = _uiState.value.activeSession?.id ?: return@launch
            EscortForegroundService.sendAction(appContext(), EscortForegroundService.ACTION_STOP)
            escortRepository.endSession(sessionId, EscortStatus.COMPLETED)
        }
    }

    fun confirmArrival() {
        EscortForegroundService.sendAction(appContext(), EscortForegroundService.ACTION_ARRIVED)
    }

    /** SOS manual. Va por el servicio para que se ejecute aunque la app muera. */
    fun triggerSos() {
        EscortForegroundService.sendAction(appContext(), EscortForegroundService.ACTION_SOS)
    }

    fun confirmCheckIn() {
        EscortForegroundService.sendAction(appContext(), EscortForegroundService.ACTION_CHECKIN_OK)
    }

    fun resolveFalseAlarm(note: String?) {
        EscortForegroundService.sendAction(appContext(), EscortForegroundService.ACTION_FALSE_ALARM)
        viewModelScope.launch {
            _uiState.value.activeSos?.let { sos ->
                escortRepository.resolveSos(sos.id, note ?: "Falsa alarma marcada por el usuario")
            }
        }
    }

    /**
     * Arma el texto de WhatsApp para compartir el seguimiento en vivo.
     *
     * Devuelve null si no hay SOS activo: el llamador no tiene que distinguir ese
     * caso por su cuenta.
     */
    fun buildLiveTrackingShareText(): String? {
        val sos = _uiState.value.activeSos ?: return null
        val session = _uiState.value.activeSession ?: return null
        return sosMessages.buildSosMessage(sos, contact = firstContact())
    }

    /**
     * Deadline del próximo check-in.
     *
     * Se deriva sumando el intervalo del servicio al último check-in registrado.
     * Devuelve null si no hay sesión activa, que es lo que la UI usa para no
     * mostrar la tarjeta de cuenta regresiva.
     */
    private fun deriveCheckInDeadline(session: EscortSession?): Long? {
        if (session == null || session.status != EscortStatus.ACTIVE) return null
        val anchor = session.lastCheckInAt ?: session.startedAt
        return anchor.toEpochMilliseconds() + CHECKIN_INTERVAL_MILLIS
    }

    private fun firstContact(): TrustContact = _uiState.value.firstContactOrNull
        ?: TrustContact(
            id = "local",
            name = "Contacto de confianza",
            phoneE164 = "+51000000000",
            relationship = null,
            createdAt = timeProvider.now(),
        )

    private fun appContext() = appConfig.applicationContext

    /**
     * Traduce un aviso degradado a texto.
     *
     * [EscortWarning] es un enum y no una sealed class con datos: el porcentaje de
     * batería y los metros de desvío viven en el servicio, no en el aviso. Pasarlos
     * acá obligaría a un tipo distinto por aviso y a serializar datos efímeros a
     * través del StateFlow global, sólo para adornar una frase.
     */
    private fun EscortWarning.describe(): String = when (this) {
        EscortWarning.LOCATION_DISABLED ->
            "GPS apagado: la posición será menos precisa."
        EscortWarning.MICROPHONE_UNAVAILABLE ->
            "Sin permiso de micrófono: la detección por voz está desactivada."
        EscortWarning.LOW_BATTERY ->
            "Batería baja: la escolta pasó a modo de bajo consumo."
        EscortWarning.OEM_BATTERY_OPTIMIZATION ->
            "Tu teléfono puede cortar la escolta en segundo plano. Desactivá la optimización de batería para NomadSafe."
        EscortWarning.OFFLINE ->
            "Sin conexión: el seguimiento se guarda localmente y se sincroniza al recuperar señal."
        EscortWarning.VOICE_MODEL_PENDING ->
            "El modelo de voz todavía se está descargando; la detección por palabra de pánico arranca cuando termine."
    }

    private companion object {
        const val TAG = "EscortViewModel"
        const val HISTORY_LIMIT = 20

        /** Debe coincidir con CHECKIN_INTERVAL de EscortForegroundService. */
        const val CHECKIN_INTERVAL_MILLIS = 8L * 60 * 1000

        /**
         * Ticker de 1 s.
         *
         * Corre sólo mientras el ViewModel está vivo, es decir mientras la pantalla
         * de escolta está abierta. El servicio en primer plano no necesita este
         * ticker: sus intervalos reales (persistencia cada 10 s, check-in cada
         * 8 min) son órdenes de magnitud mayores y despertar cada segundo desde un
         * servicio gasta batería sin beneficio.
         */
        val CLOCK_TICK: Duration = 1.seconds
    }
}
