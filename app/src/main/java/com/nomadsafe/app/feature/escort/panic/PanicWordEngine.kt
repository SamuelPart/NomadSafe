package com.nomadsafe.app.feature.escort.panic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Estado del motor de voz, para reflejarlo en la notificación persistente. */
enum class PanicEngineState {
    /** No inicializado (servicio detenido o usuario desactivó la voz). */
    IDLE,

    /** Descargando el modelo acústico (~40 MB la primera vez). */
    DOWNLOADING_MODEL,

    /** Cargando el modelo en memoria y preparando el reconocedor. */
    LOADING,

    /** Escuchando activamente. */
    LISTENING,

    /**
     * Falló la inicialización. El Modo Escolta SIGUE ACTIVO: la ubicación, el
     * check-in y el botón SOS manual continúan funcionando. Perder la detección
     * de voz no debe perder la escolta.
     */
    UNAVAILABLE,
}

/** Progreso de descarga del modelo, 0..100. */
data class ModelDownloadProgress(val percent: Int, val bytesDownloaded: Long, val totalBytes: Long)

/**
 * Motor de detección de palabras de pánico.
 *
 * ── Por qué una interfaz y no Vosk directamente ──────────────────────────────
 * Tres implementaciones conviven en el proyecto y las tres son necesarias:
 *
 *  - `VoskPanicWordEngine`: la real, en producción.
 *  - `FakePanicWordEngine`: para tests y para el modo demo. Permite ejercitar
 *    TODO el protocolo SOS (notificación, alerta a contactos, evento en el
 *    backend) sin gritarle al teléfono. Sin esto, el flujo más crítico de la
 *    app no se puede probar.
 *  - `UnavailablePanicWordEngine`: cuando el dispositivo no tiene micrófono o
 *    el usuario negó el permiso. Hace explícito que la escolta sigue andando.
 *
 * También deja la puerta abierta a cambiar de motor (Picovoice Porcupine tiene
 * wake-words de consumo mucho menor) sin tocar el servicio ni el protocolo SOS.
 *
 * ── Contrato de privacidad ───────────────────────────────────────────────────
 * Ninguna implementación puede grabar, persistir ni transmitir audio. El único
 * dato que sale del motor es texto reconocido y un nivel de energía. Esto no es
 * una convención de estilo: es la promesa que se le hace al usuario en pantalla
 * y en la política de privacidad, y el diseño la hace estructuralmente cierta.
 */
interface PanicWordEngine {

    /** Estado observable, para la UI y la notificación persistente. */
    val state: StateFlow<PanicEngineState>

    /** Progreso de descarga del modelo. Emite sólo mientras está descargando. */
    val downloadProgress: Flow<ModelDownloadProgress>

    /**
     * Detecciones positivas.
     *
     * Es un [Flow] y no un callback porque varios consumidores pueden
     * necesitarlo a la vez: el protocolo SOS, la UI y el log de auditoría.
     */
    val detections: Flow<PanicMatch>

    /**
     * Nivel de energía del audio entrante, 0f..1f.
     *
     * No es decorativo. Le muestra al usuario que el micrófono está vivo, que es
     * la única forma de confiar en una feature invisible. Sin esto, "la app me
     * escucha" es una afirmación que el usuario no puede verificar.
     */
    val audioLevel: Flow<Float>

    /**
     * Prepara el motor: asegura que el modelo esté descargado y carga el
     * reconocedor con la gramática restringida.
     *
     * Es idempotente y suspende hasta quedar listo o fallar. Debe llamarse ANTES
     * de [start]; llamar [start] sin preparar devuelve [PanicEngineState.UNAVAILABLE].
     */
    suspend fun prepare()

    /** Empieza a escuchar. Requiere el permiso RECORD_AUDIO ya concedido. */
    fun start()

    /** Deja de escuchar y libera el reconocedor. No borra el modelo descargado. */
    fun stop()

    /** Libera recursos nativos. Después de esto el motor no se puede reusar. */
    fun release()
}

/**
 * Implementación para cuando la detección de voz no está disponible.
 *
 * Existe para que el resto del sistema no tenga que lidiar con nulls: la escolta
 * se comporta igual, sólo que sin el disparador por voz. Un `PanicWordEngine?`
 * null obligaría a cada consumidor a preguntar y a duplicar la lógica de
 * degradación elegante en tres lugares distintos.
 */
class UnavailablePanicWordEngine(
    val unavailableReason: String,
) : PanicWordEngine {

    override val state: StateFlow<PanicEngineState> =
        MutableStateFlow(PanicEngineState.UNAVAILABLE).asStateFlow()

    override val downloadProgress: Flow<ModelDownloadProgress> = emptyFlow()
    override val detections: Flow<PanicMatch> = emptyFlow()
    override val audioLevel: Flow<Float> = emptyFlow()

    override suspend fun prepare() = Unit
    override fun start() = Unit
    override fun stop() = Unit
    override fun release() = Unit
}
