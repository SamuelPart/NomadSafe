package com.nomadsafe.app.feature.escort.panic

import com.nomadsafe.app.core.util.NsDispatchers
import com.nomadsafe.app.core.util.NsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File

/**
 * Detección de palabras de pánico con Vosk, 100% offline.
 *
 * ── La decisión técnica más importante de esta clase ─────────────────────────
 * **Reconocimiento con gramática restringida**, no dictado libre.
 *
 * ```kotlin
 * Recognizer(model, SAMPLE_RATE, phrases.toGrammar())
 * ```
 *
 * Al pasarle al reconocedor el vocabulario cerrado `["auxilio","socorro","ayuda",...,"[unk]"]`,
 * Kaldi deja de intentar transcribir español general y sólo decide entre esas
 * palabras y "[unk]" (desconocido). El efecto es enorme:
 *
 *   - La precisión sobre las palabras objetivo sube mucho, porque el espacio de
 *     búsqueda se reduce de ~20.000 palabras a ~15.
 *   - El consumo de CPU baja, que en un foreground service permanente es batería
 *     del usuario.
 *   - Permite usar el modelo PEQUEÑO (~40 MB) en vez del grande (~1.4 GB), que
 *     es lo que hace viable empaquetar o descargar esto en un gama media.
 *
 * El token `[unk]` es OBLIGATORIO. Sin él, el reconocedor fuerza TODO el audio a
 * encajar en el vocabulario y el ruido de la calle se transcribe como "auxilio".
 * Es la causa nº1 de falsos positivos en keyword spotting con gramática cerrada.
 *
 * ── Por qué aun así hace falta el matcher difuso ─────────────────────────────
 * La gramática restringida mejora mucho las cosas pero no las resuelve: el modelo
 * pequeño igual confunde fonemas ("ausilio", "aucilio"). Por eso la salida pasa
 * por [PanicPhraseMatcher], que tolera distancia de edición 1–2. Sin esa capa, la
 * feature fallaría en el momento exacto en que debe funcionar, y en silencio.
 *
 * ── Privacidad ───────────────────────────────────────────────────────────────
 * El audio nunca se persiste ni se transmite. Vosk corre en el proceso, sobre el
 * buffer del `SpeechService`, y lo único que se emite hacia afuera es texto
 * reconocido y un nivel de energía. Ver docs/SECURITY_PRIVACY.md.
 */
class VoskPanicWordEngine(
    private val modelDirectory: File,
    private val phrases: List<PanicPhrase>,
    private val dispatchers: NsDispatchers,
    private val modelDownloader: VoskModelDownloader,
    private val sampleRate: Float = DEFAULT_SAMPLE_RATE,
) : PanicWordEngine {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _state = MutableStateFlow(PanicEngineState.IDLE)
    override val state: StateFlow<PanicEngineState> = _state.asStateFlow()

    private val _downloadProgress = MutableSharedFlow<ModelDownloadProgress>(replay = 1)
    override val downloadProgress: Flow<ModelDownloadProgress> = _downloadProgress.asSharedFlow()

    private val _detections = MutableSharedFlow<PanicMatch>(extraBufferCapacity = 8)
    override val detections: Flow<PanicMatch> = _detections.asSharedFlow()

    private val _audioLevel = MutableSharedFlow<Float>(extraBufferCapacity = 4)
    override val audioLevel: Flow<Float> = _audioLevel.asSharedFlow()

    private val matcher = PanicPhraseMatcher(phrases)
    private val grammar: Array<String> = phrases.toGrammar()

    // Recursos nativos. NO son thread-safe: Vosk exige que el Recognizer se use
    // desde un solo hilo, por eso todo el acceso está serializado con [mutex].
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var prepareJob: Job? = null
    private val mutex = Mutex()

    override suspend fun prepare() {
        // Idempotente: si ya está escuchando o listo, no se rehace nada. Sin esto,
        // cada cambio de configuración del servicio re-descargaría el modelo.
        if (_state.value == PanicEngineState.LISTENING) return

        prepareJob?.let { existing ->
            if (existing.isActive) {
                existing.join()
                return
            }
        }

        prepareJob = scope.launch {
            mutex.withLock {
                runCatching {
                    _state.value = PanicEngineState.DOWNLOADING_MODEL

                    // 1. Asegurar el modelo en disco (descarga o assets empaquetados).
                    modelDownloader.progress.collect { _downloadProgress.emit(it) }
                    val ready = modelDownloader.ensureModel()
                    if (!ready) {
                        NsLog.e(TAG, "No se pudo obtener el modelo de Vosk")
                        _state.value = PanicEngineState.UNAVAILABLE
                        return@runCatching
                    }

                    _state.value = PanicEngineState.LOADING

                    // 2. Cargar el modelo acústico en memoria nativa.
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    val loaded = Model(ready.absolutePath)
                    model = loaded

                    // 3. Crear el reconocedor con GRAMÁTICA RESTRINGIDA.
                    recognizer = Recognizer(loaded, sampleRate, grammar)

                    NsLog.i(TAG, "Motor Vosk listo (vocabulario de ${grammar.size - 1} términos)")
                }.onFailure { error ->
                    NsLog.e(TAG, "Fallo al preparar Vosk; la escolta sigue sin detección de voz", error)
                    _state.value = PanicEngineState.UNAVAILABLE
                    releaseNativeResourcesLocked()
                }
            }
        }
        prepareJob?.join()
    }

    override fun start() {
        scope.launch {
            mutex.withLock {
                val activeRecognizer = recognizer
                if (activeRecognizer == null) {
                    NsLog.w(TAG, "start() sin prepare() exitoso; se ignora")
                    _state.value = PanicEngineState.UNAVAILABLE
                    return@withLock
                }
                if (speechService != null) return@withLock

                runCatching {
                    // SpeechService abre el AudioRecord y bombea PCM al reconocedor.
                    // `timeout` en ms: 0 = sin timeout, escucha indefinida, que es lo
                    // que necesita una escolta que puede durar una hora.
                    val service = SpeechService(activeRecognizer, sampleRate)
                    service.startListening(listener)
                    speechService = service
                    _state.value = PanicEngineState.LISTENING
                    NsLog.i(TAG, "Escucha iniciada")
                }.onFailure { error ->
                    // El caso típico acá es SecurityException porque RECORD_AUDIO se
                    // revocó en Ajustes mientras la escolta estaba activa.
                    NsLog.e(TAG, "No se pudo iniciar la escucha", error)
                    _state.value = PanicEngineState.UNAVAILABLE
                }
            }
        }
    }

    override fun stop() {
        scope.launch {
            mutex.withLock {
                speechService?.runCatching {
                    stop()
                    shutdown()
                }
                speechService = null
                recognizer?.runCatching { close() }
                recognizer = null
                if (_state.value == PanicEngineState.LISTENING) {
                    _state.value = PanicEngineState.IDLE
                }
                NsLog.i(TAG, "Escucha detenida")
            }
        }
    }

    override fun release() {
        prepareJob?.cancel()
        scope.launch {
            mutex.withLock { releaseNativeResourcesLocked() }
            _state.value = PanicEngineState.IDLE
        }.invokeOnCompletion { scope.cancel() }
    }

    /** Debe llamarse con [mutex] tomado. Libera recursos nativos en orden correcto. */
    private fun releaseNativeResourcesLocked() {
        speechService?.runCatching {
            stop()
            shutdown()
        }
        speechService = null
        recognizer?.runCatching { close() }
        recognizer = null
        model?.runCatching { close() }
        model = null
    }

    /**
     * Callbacks de Vosk.
     *
     * Los tres métodos llegan en el hilo de audio, NO en el main. Por eso todo lo
     * que se emite va por [MutableSharedFlow] y el trabajo pesado se lanza a
     * [scope]: hacer I/O o tocar la UI desde acá bloquearía el pipeline de audio
     * y produciría pérdidas de frames, o sea palabras de pánico no detectadas.
     */
    private val listener = object : RecognitionListener {

        /** Resultado parcial: texto provisional mientras la persona habla. */
        override fun onPartialResult(hypothesis: String?) {
            // Los parciales se ignoran a propósito. Disparar sobre texto parcial
            // produce falsos positivos: a mitad de una frase normal pueden
            // aparecer sílabas que se parecen a "ayuda".
        }

        /** Resultado final de un utterance: acá sí se evalúa. */
        override fun onResult(hypothesis: String?) {
            handleHypothesis(hypothesis, confidences = null)
        }

        /** Resultado final al cerrar el reconocedor. */
        override fun onFinalResult(hypothesis: String?) {
            handleHypothesis(hypothesis, confidences = null)
        }

        override fun onError(exception: Exception?) {
            NsLog.e(TAG, "Error del reconocedor", exception)
            _state.value = PanicEngineState.UNAVAILABLE
        }

        override fun onTimeout() {
            NsLog.w(TAG, "Timeout del reconocedor; se reinicia la escucha")
            scope.launch {
                stop()
                prepare()
                start()
            }
        }
    }

    /**
     * Evalúa una hipótesis contra el matcher y emite la detección.
     *
     * Se extrae la confianza por palabra del JSON cuando viene, porque el matcher
     * la usa como guarda contra falsos positivos en palabras cortas y ambiguas
     * ("ayuda" aparece en conversación normal).
     */
    private fun handleHypothesis(hypothesis: String?, confidences: List<Float>?) {
        if (hypothesis.isNullOrBlank()) return

        val parsed = runCatching { parseVoskResult(hypothesis) }.getOrNull()
        val text = parsed?.text ?: return
        if (text.isBlank()) return

        val wordConfidences = confidences ?: parsed?.wordConfidences

        val match = matcher.find(text, wordConfidences) ?: run {
            NsLog.d(TAG, "Audio reconocido sin coincidencia: \"$text\"")
            return
        }

        NsLog.w(TAG, "PALABRA DE PÁNICO detectada: \"${match.phrase.raw}\" (distancia=${match.totalEditDistance})")

        scope.launch {
            _detections.emit(match)
            // Nivel de energía alto tras una detección: refuerzo visual inmediato
            // en la UI de que algo se escuchó.
            _audioLevel.emit(1.0f)
        }
    }

    /** Salida de Vosk en `onResult`, con confianza por palabra cuando está. */
    @Serializable
    private data class VoskResult(
        @SerialName("text") val text: String = "",
        @SerialName("result") val words: List<VoskWord> = emptyList(),
    )

    @Serializable
    private data class VoskWord(
        @SerialName("word") val word: String = "",
        @SerialName("conf") val confidence: Float = 0f,
    )

    private data class ParsedHypothesis(val text: String, val wordConfidences: List<Float>?)

    private fun parseVoskResult(json: String): ParsedHypothesis {
        val result = jsonSerializer.decodeFromString<VoskResult>(json)
        val confidences = result.words
            .map { it.confidence }
            .takeIf { it.isNotEmpty() }
        return ParsedHypothesis(result.text, confidences)
    }

    companion object {
        private const val TAG = "VoskPanicEngine"

        /**
         * 16 kHz es la tasa nativa de los modelos Vosk.
         *
         * No subir a 44.1 kHz "por calidad": el modelo fue entrenado a 16 kHz y
         * resamplear hacia arriba no agrega información, sólo triplica el costo
         * de CPU en un servicio que corre durante toda la caminata del usuario.
         */
        const val DEFAULT_SAMPLE_RATE = 16_000.0f

        private val jsonSerializer = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}

/**
 * Descarga/verificación del modelo acústico.
 *
 * Interfaz propia porque hay dos estrategias legítimas y se elige en el build:
 *  - empaquetado en `assets/` (`nomadsafe.bundleVoskModel=true`): APK ~40 MB más
 *    grande pero funciona sin descargar nada, que importa en zonas con datos
 *    móviles limitados o en el primer arranque en la calle.
 *  - descarga al primer uso (default): APK chico, pero la primera escolta necesita
 *    conectividad. Se debe comunicar claramente en la UI antes de que el usuario
 *    dependa de la feature.
 */
interface VoskModelDownloader {
    val progress: Flow<ModelDownloadProgress>

    /** Devuelve el directorio del modelo listo para usar, o null si falló. */
    suspend fun ensureModel(): File?
}

/**
 * Descarga el modelo español pequeño desde alphacephei.com y lo desempaqueta.
 *
 * Idempotente y tolerante a fallos: si la descarga se corta, el archivo parcial
 * se descarta y la próxima vez se reintenta desde cero. Dejar un zip a medio
 * descargar y tratar de descomprimirlo produciría un modelo corrupto que carga
 * pero no reconoce nada — el peor modo de fallo posible, porque parece funcionar.
 */
class RemoteVoskModelDownloader(
    private val targetDirectory: File,
    private val modelUrl: String,
    private val expectedDirectoryName: String,
    private val dispatchers: NsDispatchers,
) : VoskModelDownloader {

    private val _progress = MutableSharedFlow<ModelDownloadProgress>(replay = 1)
    override val progress: Flow<ModelDownloadProgress> = _progress.asSharedFlow()

    override suspend fun ensureModel(): File? {
        val ready = File(targetDirectory, expectedDirectoryName)
        val marker = File(ready, READY_MARKER)

        // Ya descargado y verificado: el marcador sólo se escribe al completar
        // correctamente, así que su presencia es garantía de integridad.
        if (ready.isDirectory && marker.exists()) {
            NsLog.i(TAG, "Modelo ya disponible en ${ready.absolutePath}")
            return ready
        }

        // Descarga anterior incompleta: se limpia todo y se arranca de cero.
        if (ready.exists()) {
            NsLog.w(TAG, "Modelo incompleto detectado; se limpia y se re-descarga")
            ready.deleteRecursively()
        }

        targetDirectory.mkdirs()
        val zipFile = File(targetDirectory, "$expectedDirectoryName.zip")

        return runCatching {
            downloadFile(modelUrl, zipFile)
            unzip(zipFile, targetDirectory)
            zipFile.delete()

            if (!ready.isDirectory) {
                NsLog.e(TAG, "El zip no contenía el directorio esperado: $expectedDirectoryName")
                return@runCatching null
            }

            marker.writeText("ok")
            NsLog.i(TAG, "Modelo instalado en ${ready.absolutePath}")
            ready
        }.getOrElse { error ->
            NsLog.e(TAG, "Fallo al descargar el modelo de Vosk", error)
            zipFile.delete()
            ready.deleteRecursively()
            null
        }
    }

    private suspend fun downloadFile(url: String, destination: File) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true

        try {
            val responseCode = connection.responseCode
            check(responseCode in 200..299) { "HTTP $responseCode al descargar el modelo" }

            val total = connection.contentLengthLong.coerceAtLeast(0L)
            var downloaded = 0L
            var lastEmittedPercent = -1

            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                        // Se emite sólo cuando cambia el porcentaje entero: emitir por
                        // cada buffer de 8 KB saturaría el flow y la UI.
                        if (percent != lastEmittedPercent) {
                            lastEmittedPercent = percent
                            _progress.emit(
                                ModelDownloadProgress(
                                    percent = percent.coerceIn(0, 100),
                                    bytesDownloaded = downloaded,
                                    totalBytes = total,
                                ),
                            )
                        }
                    }
                }
            }

            check(total <= 0 || downloaded >= total * 0.98) {
                "Descarga incompleta: $downloaded de $total bytes"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(zipFile: File, destination: File) {
        java.util.zip.ZipFile(zipFile).use { zip ->
            val canonicalDestination = destination.canonicalPath

            zip.entries().asSequence().forEach { entry ->
                val outputFile = File(destination, entry.name)

                /*
                 * Defensa contra Zip Slip.
                 *
                 * Sin esta validación, una entrada llamada "../../data/data/x"
                 * escribiría fuera del directorio destino. El modelo se descarga
                 * por HTTP de un tercero: aunque hoy la fuente es confiable,
                 * descomprimir sin validar rutas es un error clásico y barato de
                 * prevenir.
                 */
                if (!outputFile.canonicalPath.startsWith(canonicalDestination)) {
                    throw SecurityException("Entrada del zip fuera del destino: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outputFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoskModelDownloader"
        private const val BUFFER_SIZE = 8 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000

        /** Marcador de integridad: sólo existe si la instalación se completó. */
        private const val READY_MARKER = ".nomadsafe_ready"

        /**
         * Modelo español pequeño de Vosk (~40 MB).
         *
         * "Small" y no el grande (~1.4 GB): con gramática restringida el modelo
         * pequeño rinde bien para detectar ~15 palabras, y 1.4 GB en el
         * almacenamiento de un gama medio es directamente inviable.
         */
        const val DEFAULT_SPANISH_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        const val DEFAULT_SPANISH_MODEL_DIR = "vosk-model-small-es-0.42"
    }
}
