package com.nomadsafe.app.feature.escort.panic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor de voz simulado.
 *
 * No es un stub de conveniencia: es la única manera de probar el flujo más
 * crítico de toda la app. El protocolo SOS completo (notificación de alta
 * prioridad, alerta a contactos de confianza, evento en el backend, cambio de
 * estado de la sesión) no se puede ejercitar si el único disparador es gritarle
 * a un teléfono.
 *
 * Se usa en tres contextos:
 *  1. Tests instrumentados y unitarios del protocolo SOS.
 *  2. Modo demo (sin credenciales de Supabase ni modelo de Vosk descargado).
 *  3. QA manual: `simulate("auxilio")` desde adb o desde un control oculto de
 *     debug permite verificar la cadena entera en segundos.
 */
@Singleton
class FakePanicWordEngine @Inject constructor() : PanicWordEngine {

    private val _state = MutableStateFlow(PanicEngineState.IDLE)
    override val state: StateFlow<PanicEngineState> = _state.asStateFlow()

    private val _downloadProgress = MutableSharedFlow<ModelDownloadProgress>(replay = 0)
    override val downloadProgress: Flow<ModelDownloadProgress> = _downloadProgress.asSharedFlow()

    private val _detections = MutableSharedFlow<PanicMatch>(extraBufferCapacity = 8)
    override val detections: Flow<PanicMatch> = _detections.asSharedFlow()

    private val _audioLevel = MutableSharedFlow<Float>(extraBufferCapacity = 4)
    override val audioLevel: Flow<Float> = _audioLevel.asSharedFlow()

    private val matcher = PanicPhraseMatcher(PanicPhraseMatcher.defaultSpanishPhrases())

    override suspend fun prepare() {
        _state.value = PanicEngineState.LISTENING
    }

    override fun start() {
        _state.value = PanicEngineState.LISTENING
    }

    override fun stop() {
        _state.value = PanicEngineState.IDLE
    }

    override fun release() {
        _state.value = PanicEngineState.IDLE
    }

    /**
     * Inyecta una detección pasando por el matcher real.
     *
     * Se rutea por el matcher a propósito y no se emite un [PanicMatch] a mano:
     * así el test también cubre que la frase simulada es una que el sistema
     * reconocería de verdad. Simular "asdfgh" y que dispare un SOS sería un test
     * que pasa pero no verifica nada.
     */
    suspend fun simulate(utterance: String): PanicMatch? {
        val match = matcher.find(utterance) ?: return null
        _detections.emit(match)
        return match
    }

    /** Emite un nivel de audio falso para probar la animación de la UI. */
    suspend fun simulateAudioLevel(level: Float) {
        _audioLevel.emit(level.coerceIn(0f, 1f))
    }
}
