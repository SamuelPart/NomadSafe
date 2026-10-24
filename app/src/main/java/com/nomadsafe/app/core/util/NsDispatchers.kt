package com.nomadsafe.app.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Inyección de dispatchers y de reloj.
 *
 * Ambas cosas parecen burocracia pero son lo que hace testeable el proyecto:
 *  - Los dispatchers, para que un test unitario use
 *    [kotlinx.coroutines.test.StandardTestDispatcher] en vez de `Dispatchers.IO`
 *    real, que haría el test no determinista.
 *  - El reloj, porque el motor de riesgo decae los incidentes por antigüedad.
 *    Sin un reloj inyectable no se puede probar "un reporte de hace 3 días pesa
 *    menos que uno de hace 3 horas" sin dormir el hilo del test.
 */

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/** Qualifier del [kotlinx.coroutines.CoroutineScope] de vida de aplicación. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Reloj inyectable. En producción [SystemTimeProvider]; en tests se fija la hora. */
interface TimeProvider {
    fun now(): Instant

    /** Atajo para código que sólo necesita epoch millis (Room, logs). */
    fun nowMillis(): Long = now().toEpochMilliseconds()
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Instant = Clock.System.now()
}

/**
 * Dispatchers de la aplicación agrupados en una interfaz, en vez de inyectar
 * tres qualifiers sueltos en cada ViewModel: menos ruido en los constructores
 * y un único punto donde sustituirlos por TestDispatcher en tests.
 */
interface NsDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

@Singleton
class DefaultNsDispatchers @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : NsDispatchers {
    override val io: CoroutineDispatcher get() = ioDispatcher
    override val default: CoroutineDispatcher get() = defaultDispatcher
    override val main: CoroutineDispatcher get() = mainDispatcher
}

/**
 * Log centralizado.
 *
 * No llamamos `android.util.Log` directamente en el resto del código para poder
 * silenciarlo todo en release desde un solo lugar: los logs de una app de
 * seguridad pueden filtrar ubicaciones, y eso en producción es un incidente de
 * privacidad, no un detalle de estilo.
 */
object NsLog {

    /** En release se apaga todo salvo errores. */
    var verboseEnabled: Boolean = com.nomadsafe.app.BuildConfig.DEBUG

    fun d(tag: String, message: String) {
        if (verboseEnabled) android.util.Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (verboseEnabled) android.util.Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (verboseEnabled) {
            if (error != null) android.util.Log.w(tag, message, error) else android.util.Log.w(tag, message)
        }
    }

    /** Los errores se registran siempre: son la única señal de fallos en campo. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) android.util.Log.e(tag, message, error) else android.util.Log.e(tag, message)
    }
}
