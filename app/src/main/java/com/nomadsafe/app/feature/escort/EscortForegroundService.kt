package com.nomadsafe.app.feature.escort

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nomadsafe.app.core.location.DeviceLocation
import com.nomadsafe.app.core.location.LocationProfile
import com.nomadsafe.app.core.location.LocationSource
import com.nomadsafe.app.core.util.GeoUtils
import com.nomadsafe.app.core.util.NsDispatchers
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.domain.model.EscortSession
import com.nomadsafe.app.domain.model.EscortStatus
import com.nomadsafe.app.domain.model.SosEvent
import com.nomadsafe.app.domain.model.SosTrigger
import com.nomadsafe.app.domain.repository.EscortRepository
import com.nomadsafe.app.feature.escort.panic.PanicEngineState
import com.nomadsafe.app.feature.escort.panic.PanicWordEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Servicio en primer plano del Modo Escolta.
 *
 * ── Qué garantiza ────────────────────────────────────────────────────────────
 * Que la protección siga activa con la pantalla bloqueada, con la app en segundo
 * plano y después de deslizarla fuera de Recientes (`stopWithTask="false"` en el
 * manifest). Es la diferencia entre una app que el usuario tiene que acordarse de
 * abrir y una que lo protege aunque no la esté mirando.
 *
 * ── Los cuatro fallos de Android que esta clase evita explícitamente ──────────
 * **1. `SecurityException` en `startForeground` (Android 14+).** Desde API 34 hay
 * que pasar el tipo de servicio y que coincida con el `foregroundServiceType` del
 * manifest, y el permiso runtime correspondiente debe estar YA concedido. Por eso
 * [EscortViewModel] pide RECORD_AUDIO y ubicación ANTES de arrancar el servicio,
 * y [EscortNotificationFactory.foregroundServiceType] sólo incluye el tipo de
 * micrófono si la voz está habilitada.
 *
 * **2. Micrófono silenciado con pantalla bloqueada.** Desde Android 9 el micrófono
 * se corta para apps en background. La única forma legítima de seguir capturando
 * es un foreground service con tipo `microphone` declarado. Sin eso, la detección
 * de palabras de pánico funciona en el emulador y falla en un teléfono real con la
 * pantalla apagada — el caso de uso central de la feature.
 *
 * **3. Muerte silenciosa por el OEM.** Xiaomi/Huawei/Oppo/Vivo matan servicios en
 * segundo plano pese a las garantías de Android. Se detecta el modo de ahorro
 * agresivo y se avisa con [EscortWarning.OEM_BATTERY_OPTIMIZATION] en vez de
 * fingir que la escolta está activa.
 *
 * **4. Fuga de batería.** Sin [WakeLock] persistente a propósito: un foreground
 * service con tipo `location|microphone` ya mantiene la CPU disponible para esas
 * dos tareas, y un wakelock de una hora drenaría la batería y es objetable ante
 * las políticas de Play. Se usa en cambio un perfil de ubicación adaptativo que
 * baja la frecuencia al caer la batería.
 */
@AndroidEntryPoint
class EscortForegroundService : Service() {

    @Inject lateinit var locationSource: LocationSource
    @Inject lateinit var panicEngine: PanicWordEngine
    @Inject lateinit var escortRepository: EscortRepository
    @Inject lateinit var sosCoordinator: SosCoordinator
    @Inject lateinit var notificationFactory: EscortNotificationFactory
    @Inject lateinit var stateHolder: EscortStateHolder
    @Inject lateinit var dispatchers: NsDispatchers
    @Inject lateinit var timeProvider: TimeProvider

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    private var locationJob: Job? = null
    private var panicJob: Job? = null
    private var watchdogJob: Job? = null
    private var currentSession: EscortSession? = null
    private var isForegroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationFactory.ensureChannels()
        NsLog.i(TAG, "Servicio de escolta creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        NsLog.i(TAG, "onStartCommand: $action")

        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop(EscortStatus.CANCELLED)
            ACTION_ARRIVED -> handleStop(EscortStatus.COMPLETED)
            ACTION_SOS -> scope.launch { raiseSos(SosTrigger.MANUAL, recognizedPhrase = null) }
            ACTION_CHECKIN_OK -> scope.launch { confirmCheckIn() }
            ACTION_FALSE_ALARM -> scope.launch { resolveSosAsFalseAlarm() }
            else -> NsLog.w(TAG, "Acción desconocida: $action")
        }

        /*
         * START_STICKY: si el sistema mata el servicio por memoria, lo recrea.
         *
         * Es la opción correcta acá y no en cualquier servicio: una escolta que se
         * pierde en silencio es peor que una que nunca existió, porque el usuario
         * cree estar protegido. Al recrearse, `intent` llega null y se intenta
         * reanudar la sesión persistida (ver [handleStart]).
         */
        return START_STICKY
    }

    private fun handleStart(intent: Intent?) {
        val voiceEnabled = intent?.getBooleanExtra(EXTRA_VOICE_ENABLED, true) ?: true
        val destinationLabel = intent?.getStringExtra(EXTRA_DESTINATION_LABEL)
        val destinationLat = intent?.getDoubleExtra(EXTRA_DESTINATION_LAT, Double.NaN) ?: Double.NaN
        val destinationLon = intent?.getDoubleExtra(EXTRA_DESTINATION_LON, Double.NaN) ?: Double.NaN

        val destination = if (!destinationLat.isNaN() && !destinationLon.isNaN()) {
            com.nomadsafe.app.domain.model.GeoPoint(destinationLat, destinationLon)
        } else {
            null
        }

        /*
         * PROMOVER A PRIMER PLANO ANTES QUE NADA.
         *
         * Hay que llamar startForeground dentro de los ~5 s de onStartCommand o el
         * sistema lanza ANR y mata el servicio. Por eso se usa la sesión en memoria
         * o una provisional, y la real se actualiza apenas exista.
         */
        promoteToForeground(voiceEnabled)

        scope.launch {
            // Reanudar la sesión persistida si existe (caso START_STICKY tras muerte).
            val existing = stateHolder.state.value.session ?: loadPersistedSession()

            val session = existing ?: escortRepository.startSession(
                destination = destination,
                destinationLabel = destinationLabel,
                estimatedArrival = null,
            ).let { result ->
                when (result) {
                    is com.nomadsafe.app.core.result.NsResult.Success -> result.data
                    is com.nomadsafe.app.core.result.NsResult.Failure -> {
                        // Sin backend igual se escolta: se arma una sesión local.
                        // Perder la protección porque falló la red sería inaceptable.
                        NsLog.e(TAG, "No se pudo crear la sesión remota; se escolta en modo local")
                        stateHolder.addWarning(EscortWarning.OFFLINE)
                        localSession(destination, destinationLabel)
                    }
                }
            }

            currentSession = session
            stateHolder.onSessionStarted(session)
            refreshNotification()

            startLocationTracking(session)
            if (voiceEnabled) startPanicDetection(session)
            startWatchdog(session)
        }
    }

    private suspend fun loadPersistedSession(): EscortSession? =
        escortRepository.observeActiveSession().first()

    private fun localSession(destination: com.nomadsafe.app.domain.model.GeoPoint?, label: String?) =
        EscortSession(
            id = "local-${UUID.randomUUID()}",
            userId = "local",
            startedAt = timeProvider.now(),
            destination = destination,
            destinationLabel = label,
            status = EscortStatus.ACTIVE,
        )

    /**
     * Promueve el servicio a primer plano con el tipo correcto según la versión.
     *
     * Se envuelve en try/catch porque en Android 14 una discrepancia entre permisos
     * y tipo lanza `SecurityException`. Si eso pasa, la escolta NO puede seguir en
     * silencio: se registra, se avisa al usuario y se detiene el servicio, que es
     * preferible a un servicio zombie que finge proteger.
     */
    private fun promoteToForeground(voiceEnabled: Boolean) {
        if (isForegroundStarted) return

        val notification = notificationFactory.buildEscortNotification(
            session = currentSession,
            panicState = if (voiceEnabled) PanicEngineState.LOADING else PanicEngineState.IDLE,
        )

        runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationChannels.ESCORT_NOTIFICATION_ID,
                notification,
                notificationFactory.foregroundServiceType(voiceEnabled),
            )
            isForegroundStarted = true
            NsLog.i(TAG, "Servicio promovido a primer plano (voz=$voiceEnabled, SDK=${Build.VERSION.SDK_INT})")
        }.onFailure { error ->
            NsLog.e(TAG, "startForeground falló; la escolta no puede continuar", error)
            stateHolder.addWarning(EscortWarning.MICROPHONE_UNAVAILABLE)
            stopSelf()
        }
    }

    private fun refreshNotification() {
        if (!isForegroundStarted) return
        val notification = notificationFactory.buildEscortNotification(
            session = stateHolder.state.value.session,
            panicState = stateHolder.state.value.panicEngineState,
        )
        ServiceCompat.startForeground(
            this,
            NotificationChannels.ESCORT_NOTIFICATION_ID,
            notification,
            notificationFactory.foregroundServiceType(
                voiceEnabled = stateHolder.state.value.panicEngineState == PanicEngineState.LISTENING,
            ),
        )
    }

    private fun startLocationTracking(session: EscortSession) {
        locationJob?.cancel()
        locationJob = scope.launch {
            // Perfil adaptativo: se arranca en normal y se degrada con batería baja.
            var profile = LocationProfile.ESCORT_TRACKING

            locationSource.observeLocations(profile)
                .distinctUntilChanged { old, new -> old.capturedAtEpochMillis == new.capturedAtEpochMillis }
                .collect { location ->
                    stateHolder.onLocation(location)
                    evaluateWarnings(location)

                    // Cambio de perfil por batería. Se relanza la suscripción con el
                    // perfil nuevo: es la única forma de cambiar el intervalo en vuelo.
                    val desired = if (batteryLevel() <= LOW_BATTERY_THRESHOLD) {
                        LocationProfile.ESCORT_LOW_POWER
                    } else {
                        LocationProfile.ESCORT_TRACKING
                    }
                    if (desired != profile) {
                        profile = desired
                        NsLog.i(TAG, "Perfil de ubicación → $profile (batería ${batteryLevel()}%)")
                        locationJob?.cancel()
                        startLocationTracking(session)
                        return@collect
                    }

                    persistLocation(session, location)
                    detectDeviation(session, location)
                }
        }
    }

    /**
     * Persiste la posición en el backend.
     *
     * Con throttle implícito: el repositorio ignora escrituras más frecuentes que
     * [PERSIST_INTERVAL]. Subir cada fix a 5 s durante una hora son 720 escrituras
     * en Postgres por usuario, que no escala ni aporta: para seguir una escolta en
     * vivo basta una posición cada pocos segundos.
     */
    private var lastPersistedAt: Instant? = null

    private fun persistLocation(session: EscortSession, location: DeviceLocation) {
        val now = timeProvider.now()
        val last = lastPersistedAt
        if (last != null && (now - last) < PERSIST_INTERVAL) return
        lastPersistedAt = now

        scope.launch {
            escortRepository.updateLocation(
                sessionId = session.id,
                location = location.point,
                accuracyMeters = location.accuracyMeters,
                isMoving = location.isMoving,
            )
        }
    }

    private fun startPanicDetection(session: EscortSession) {
        panicJob?.cancel()
        panicJob = scope.launch {
            // El estado del motor se refleja en la notificación persistente, para
            // que el usuario vea que la escucha está realmente activa.
            launch {
                panicEngine.state.collect { state ->
                    stateHolder.onPanicEngineStateChanged(state)
                    refreshNotification()
                    when (state) {
                        PanicEngineState.UNAVAILABLE -> addWarning(EscortWarning.MICROPHONE_UNAVAILABLE)
                        PanicEngineState.DOWNLOADING_MODEL -> addWarning(EscortWarning.VOICE_MODEL_PENDING)
                        PanicEngineState.LISTENING -> removeWarning(EscortWarning.MICROPHONE_UNAVAILABLE)
                            .also { removeWarning(EscortWarning.VOICE_MODEL_PENDING) }
                        else -> Unit
                    }
                }
            }

            launch { panicEngine.audioLevel.collect { stateHolder.onAudioLevel(it) } }

            /*
             * El disparador principal.
             *
             * Ante una detección se levanta el SOS SIN pedir confirmación. Es una
             * decisión de producto deliberada: si la persona tuvo que gritar
             * "auxilio", interrumpirla con un diálogo de "¿estás seguro?" es peor
             * que un falso positivo. El costo del falso positivo se paga del otro
             * lado, con la acción "Estoy bien" de un toque en la notificación.
             */
            launch {
                panicEngine.detections.collect { match ->
                    NsLog.w(TAG, "Detección de pánico: \"${match.phrase.raw}\" (SOS=${match.isSos})")
                    stateHolder.onPhraseDetected(match.phrase.raw)

                    if (match.isSos) {
                        raiseSos(SosTrigger.PANIC_WORD, match.phrase.raw)
                    } else {
                        // Severidad ALERT: primero se pide confirmación al usuario.
                        notificationFactory.postCheckInNotification()
                        scheduleCheckInEscalation(session)
                    }
                }
            }

            panicEngine.prepare()
            panicEngine.start()
        }
    }

    /**
     * Perro guardián: check-in periódico y monitoreo de salud de la escolta.
     *
     * Es la red de seguridad para el caso que ningún otro mecanismo cubre: la
     * persona incapacitada que no puede tocar el botón ni gritar. Si no responde
     * el check-in dentro de la ventana, se escala automáticamente.
     */
    private fun startWatchdog(session: EscortSession) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(CHECKIN_INTERVAL)

                if (!stateHolder.isActive) continue

                NsLog.i(TAG, "Check-in programado")
                notificationFactory.postCheckInNotification()

                // Ventana de respuesta. Si no hay confirmación, se escala.
                val responded = waitForCheckInResponse(CHECKIN_RESPONSE_WINDOW)
                if (!responded) {
                    NsLog.w(TAG, "Check-in sin responder; se escala a SOS preventivo")
                    raiseSos(SosTrigger.CHECKIN_MISSED, recognizedPhrase = null)
                }
            }
        }
    }

    private var checkInConfirmedAt: Instant? = null

    private suspend fun waitForCheckInResponse(window: kotlin.time.Duration): Boolean {
        val requestedAt = timeProvider.now()
        val deadline = window.inWholeMilliseconds

        var elapsed = 0L
        while (elapsed < deadline) {
            val confirmed = checkInConfirmedAt
            if (confirmed != null && confirmed >= requestedAt) return true
            delay(POLL_INTERVAL)
            elapsed += POLL_INTERVAL.inWholeMilliseconds
        }
        return false
    }

    private suspend fun scheduleCheckInEscalation(session: EscortSession) {
        val responded = waitForCheckInResponse(CHECKIN_RESPONSE_WINDOW)
        if (!responded) raiseSos(SosTrigger.CHECKIN_MISSED, recognizedPhrase = null)
    }

    private suspend fun confirmCheckIn() {
        checkInConfirmedAt = timeProvider.now()
        notificationFactory.cancelCheckInNotification()
        currentSession?.let { escortRepository.recordCheckIn(it.id) }
        stateHolder.onSessionStarted(
            (currentSession ?: return).copy(lastCheckInAt = checkInConfirmedAt),
        )
        NsLog.i(TAG, "Check-in confirmado por el usuario")
    }

    /**
     * Detecta desvío de la ruta planificada.
     *
     * Sólo aplica si la sesión tiene destino y el usuario va caminando. En
     * vehículo el GPS salta y los desvíos son normales; disparar ahí generaría
     * tantas falsas alarmas que el usuario desactivaría la feature.
     */
    private fun detectDeviation(session: EscortSession, location: DeviceLocation) {
        val destination = session.destination ?: return
        if (location.isMoving && (location.speedKmh ?: 0.0) > WALKING_MAX_KMH) return

        val distanceToDestination = GeoUtils.distanceMeters(location.point, destination)
        if (distanceToDestination <= DEVIATION_THRESHOLD_METERS) return

        val last = lastDeviationAlertAt
        val now = timeProvider.now()
        // Anti-spam: como mucho un aviso por ventana.
        if (last != null && (now - last) < DEVIATION_COOLDOWN) return
        lastDeviationAlertAt = now

        NsLog.i(TAG, "Desvío detectado: $distanceToDestination m del destino")
        notificationFactory.postCheckInNotification()
        scope.launch { scheduleCheckInEscalation(session) }
    }

    private var lastDeviationAlertAt: Instant? = null

    private fun evaluateWarnings(location: DeviceLocation) {
        val warnings = mutableSetOf<EscortWarning>()

        if (!locationSource.isLocationEnabled()) warnings += EscortWarning.LOCATION_DISABLED
        if (batteryLevel() <= LOW_BATTERY_THRESHOLD) warnings += EscortWarning.LOW_BATTERY
        if (isAggressiveBatteryOptimization()) warnings += EscortWarning.OEM_BATTERY_OPTIMIZATION
        if (stateHolder.state.value.panicEngineState == PanicEngineState.UNAVAILABLE) {
            warnings += EscortWarning.MICROPHONE_UNAVAILABLE
        }

        // Se preservan los avisos que pone el resto del sistema (p. ej. OFFLINE).
        val preserved = stateHolder.state.value.warnings.filter {
            it == EscortWarning.OFFLINE || it == EscortWarning.VOICE_MODEL_PENDING
        }
        stateHolder.replaceWarnings(warnings + preserved)
    }

    private fun addWarning(warning: EscortWarning) = stateHolder.addWarning(warning)
    private fun removeWarning(warning: EscortWarning) = stateHolder.removeWarning(warning)

    /**
     * Detecta el ahorro de batería agresivo del fabricante.
     *
     * `isIgnoringBatteryOptimizations` cubre el mecanismo estándar de Android, que
     * es el que la mayoría de los usuarios puede desactivar desde Ajustes. Los OEM
     * chinos añaden capas propias que no se pueden consultar por API, así que este
     * check es necesario pero no suficiente; por eso además se muestra un aviso
     * específico en la UI con instrucciones por fabricante.
     */
    private fun isAggressiveBatteryOptimization(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return !powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun batteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 100
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * Levanta un SOS.
     *
     * Idempotente dentro de una ventana corta: un grito produce varias detecciones
     * seguidas y un usuario en pánico puede apretar el botón varias veces. Sin
     * deduplicación se enviarían cinco alertas a los contactos, que es justo lo
     * que hace que dejen de tomarlas en serio.
     */
    private suspend fun raiseSos(trigger: SosTrigger, recognizedPhrase: String?) {
        val existing = stateHolder.state.value.activeSos
        if (existing != null) {
            val elapsed = timeProvider.now() - existing.triggeredAt
            if (elapsed < SOS_DEDUPLICATION_WINDOW) {
                NsLog.i(TAG, "SOS duplicado ignorado (${trigger.wireName})")
                return
            }
        }

        val location = stateHolder.state.value.currentLocation
        val event = SosEvent(
            id = "sos-${UUID.randomUUID()}",
            escortSessionId = currentSession?.id,
            userId = currentSession?.userId ?: "local",
            location = location?.point ?: lastKnownPoint(),
            accuracyMeters = location?.accuracyMeters,
            triggeredAt = timeProvider.now(),
            trigger = trigger,
            recognizedPhrase = recognizedPhrase,
            batteryLevel = batteryLevel(),
            isMoving = location?.isMoving ?: false,
            speedMetersPerSecond = location?.speedMetersPerSecond,
        )

        NsLog.e(TAG, "SOS DISPARADO por ${trigger.wireName}")
        val result = sosCoordinator.dispatch(event)
        result?.let { stateHolder.onSosRaised(it) }

        currentSession?.let { session ->
            escortRepository.updateStatus(session.id, EscortStatus.SOS)
        }
        refreshNotification()
    }

    private fun lastKnownPoint(): com.nomadsafe.app.domain.model.GeoPoint {
        // Se fuerza una lectura del sistema: en un SOS, una posición aproximada de
        // hace un minuto vale infinitamente más que ninguna posición. runBlocking
        // está justificado acá y sólo acá: onStartCommand no es suspendida y un SOS
        // sin coordenadas es un SOS que no sirve para nada.
        return kotlinx.coroutines.runBlocking {
            locationSource.getLastKnown()?.point
                ?: stateHolder.state.value.trail.lastOrNull()?.point
                ?: com.nomadsafe.app.domain.model.GeoPoint.PILOT_FALLBACK
        }
    }

    private suspend fun resolveSosAsFalseAlarm() {
        val sos = stateHolder.state.value.activeSos ?: return
        sosCoordinator.resolveAsFalseAlarm(sos.id)
        stateHolder.onSosResolved()
        notificationFactory.cancelSosNotification()
        currentSession?.let { escortRepository.updateStatus(it.id, EscortStatus.ACTIVE) }
        refreshNotification()
        NsLog.i(TAG, "SOS marcado como falsa alarma por el usuario")
    }

    private fun handleStop(finalStatus: EscortStatus) {
        NsLog.i(TAG, "Finalizando escolta con estado $finalStatus")

        val session = currentSession
        scope.launch {
            if (session != null) {
                escortRepository.endSession(session.id, finalStatus)
            }
            cleanup()
            stateHolder.onSessionEnded()
            notificationFactory.cancelCheckInNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        locationJob?.cancel()
        locationJob = null
        panicJob?.cancel()
        panicJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        // Orden importante: stop() antes de release(). release() libera el modelo
        // nativo, y si el SpeechService sigue corriendo accede a memoria liberada
        // → crash nativo (SIGSEGV) que ni siquiera aparece en el log de Java.
        runCatching { panicEngine.stop() }
        runCatching { panicEngine.release() }

        currentSession = null
        lastPersistedAt = null
        lastDeviationAlertAt = null
        checkInConfirmedAt = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        /*
         * NO se detiene la escolta al deslizar la app fuera de Recientes.
         *
         * Es deliberado y es el motivo del `stopWithTask="false"` del manifest.
         * Un usuario que cierra la app por costumbre no debe cancelar por accidente
         * su protección: es el peor fallo posible de este producto, porque además
         * es silencioso.
         */
        NsLog.i(TAG, "onTaskRemoved: la escolta continúa activa")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        NsLog.i(TAG, "Servicio de escolta destruido")
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EscortService"

        const val ACTION_START = "com.nomadsafe.app.escort.START"
        const val ACTION_STOP = "com.nomadsafe.app.escort.STOP"
        const val ACTION_ARRIVED = "com.nomadsafe.app.escort.ARRIVED"
        const val ACTION_SOS = "com.nomadsafe.app.escort.SOS"
        const val ACTION_CHECKIN_OK = "com.nomadsafe.app.escort.CHECKIN_OK"
        const val ACTION_FALSE_ALARM = "com.nomadsafe.app.escort.FALSE_ALARM"

        const val EXTRA_VOICE_ENABLED = "voice_enabled"
        const val EXTRA_DESTINATION_LABEL = "destination_label"
        const val EXTRA_DESTINATION_LAT = "destination_lat"
        const val EXTRA_DESTINATION_LON = "destination_lon"

        /** Cada cuánto se persiste una posición en el backend. */
        private val PERSIST_INTERVAL = 10.seconds

        /** Intervalo entre check-in automáticos. */
        private val CHECKIN_INTERVAL = 8.minutes

        /** Ventana para responder el check-in antes de escalar a SOS. */
        private val CHECKIN_RESPONSE_WINDOW = 90.seconds

        private val POLL_INTERVAL = 2.seconds

        /** Ventana de deduplicación de SOS. */
        private val SOS_DEDUPLICATION_WINDOW = 45.seconds

        private const val LOW_BATTERY_THRESHOLD = 15
        private const val DEVIATION_THRESHOLD_METERS = 250.0
        private val DEVIATION_COOLDOWN = 3.minutes
        private const val WALKING_MAX_KMH = 12.0

        /** Arranca el servicio. Se llama desde el ViewModel, nunca desde la UI directa. */
        fun start(
            context: Context,
            voiceEnabled: Boolean,
            destinationLabel: String? = null,
            destinationLat: Double? = null,
            destinationLon: Double? = null,
        ) {
            val intent = Intent(context, EscortForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VOICE_ENABLED, voiceEnabled)
                destinationLabel?.let { putExtra(EXTRA_DESTINATION_LABEL, it) }
                destinationLat?.let { putExtra(EXTRA_DESTINATION_LAT, it) }
                destinationLon?.let { putExtra(EXTRA_DESTINATION_LON, it) }
            }
            // startForegroundService es obligatorio desde Android 8 para servicios
            // que van a primer plano; con startService el sistema lo mata a los 5 s.
            ContextCompat.startForegroundService(context, intent)
        }

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, EscortForegroundService::class.java).apply { this.action = action }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) = sendAction(context, ACTION_STOP)
        fun arrived(context: Context) = sendAction(context, ACTION_ARRIVED)
        fun triggerSos(context: Context) = sendAction(context, ACTION_SOS)
    }
}
