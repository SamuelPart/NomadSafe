package com.nomadsafe.app.core.result

import com.nomadsafe.app.core.util.NsLog

/**
 * Error de dominio, independiente de la librería que lo produjo.
 *
 * Por qué no propagar excepciones de Ktor/Supabase hasta la UI: si el ViewModel
 * recibiera `io.ktor.client.network.UnresolvedAddressException`, la capa de
 * presentación quedaría acoplada al cliente HTTP. Cambiar de backend (o agregar
 * un modo offline) obligaría a tocar pantallas. Con [NsError], la UI sólo sabe
 * "se cayó la red" y muestra el string localizado que corresponde.
 */
sealed class NsError(open val cause: Throwable? = null) {

    /** Sin conectividad. Es el caso más común y el único con reintento automático. */
    data object Network : NsError()

    /** Sesión vencida o inexistente: hay que volver a autenticar. */
    data object Unauthorized : NsError()

    /** El usuario no puede hacer esto (RLS de Postgres lo rechazó). */
    data object PermissionDenied : NsError()

    data object NotFound : NsError()

    /** Validación rechazada por el servidor. [field] permite marcar el input. */
    data class Validation(val field: String?, val reason: String?) : NsError()

    /**
     * Límite de tasa excedido. Relevante acá: un cliente que reporte en loop
     * (por un bug de retry) debe retroceder, no insistir.
     */
    data class RateLimited(val retryAfterMillis: Long) : NsError()

    data class Server(val status: Int) : NsError()

    /**
     * Falla local: GPS apagado, permiso no concedido, base corrupta.
     * Distinto de [Network] porque el reintento no sirve; hay que guiar al usuario.
     */
    data class Local(val reason: String) : NsError()

    data class Unknown(override val cause: Throwable?) : NsError(cause)
}

/** Resultado de una operación de dominio. */
sealed interface NsResult<out T> {
    data class Success<T>(val data: T) : NsResult<T>
    data class Failure(val error: NsError) : NsResult<Nothing>
}

/** True si es [NsResult.Success]. Útil en `if` sin smart-cast. */
val <T> NsResult<T>.isSuccess: Boolean get() = this is NsResult.Success

/** El valor o null. */
fun <T> NsResult<T>.getOrNull(): T? = (this as? NsResult.Success)?.data

/** El valor o [fallback]. */
fun <T> NsResult<T>.getOrElse(fallback: T): T = getOrNull() ?: fallback

/** Transforma el valor si es éxito; propaga el error sin tocarlo. */
inline fun <T, R> NsResult<T>.map(transform: (T) -> R): NsResult<R> = when (this) {
    is NsResult.Success -> NsResult.Success(transform(data))
    is NsResult.Failure -> this
}

/** Encadena operaciones que pueden fallar. */
inline fun <T, R> NsResult<T>.flatMap(transform: (T) -> NsResult<R>): NsResult<R> = when (this) {
    is NsResult.Success -> transform(data)
    is NsResult.Failure -> this
}

/**
 * Ejecuta [block] traduciendo excepciones a [NsError].
 *
 * Es el único lugar del proyecto donde se hace `catch` genérico. Centralizarlo
 * garantiza que el mapeo excepción → error de dominio sea consistente: si cada
 * repositorio lo hiciera a su manera, un `SocketTimeoutException` sería "red" en
 * uno y "desconocido" en otro, y la UI mostraría mensajes distintos para el
 * mismo problema real.
 */
inline fun <T> nsRunCatching(
    tag: String = "NsResult",
    block: () -> T,
): NsResult<T> = try {
    NsResult.Success(block())
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    // SIEMPRE relanzar: tragarse una CancellationException rompe el cancelado
    // estructurado de coroutines y hace que un ViewModel destruido siga vivo.
    throw cancellation
} catch (throwable: Throwable) {
    NsLog.w(tag, "Operación falló: ${throwable::class.simpleName}", throwable)
    NsResult.Failure(mapThrowable(throwable))
}

/**
 * Mapeo de excepción → error de dominio.
 *
 * Se hace por NOMBRE DE CLASE en vez de por tipo importado a propósito: así
 * `core.result` no depende de Ktor ni de Supabase, y el mapeo sigue funcionando
 * aunque cambie la versión del cliente. Es un trade-off deliberado (pierde type
 * safety) a cambio de mantener esta capa sin dependencias de infraestructura.
 */
fun mapThrowable(throwable: Throwable): NsError {
    val className = throwable::class.java.name

    // Red: sin conexión, DNS, timeout.
    val networkExceptions = setOf(
        "java.net.UnknownHostException",
        "java.net.SocketTimeoutException",
        "java.net.ConnectException",
        "java.io.IOException",
        "io.ktor.client.network.sockets.ConnectTimeoutException",
        "io.ktor.client.network.sockets.SocketTimeoutException",
        "io.ktor.client.plugins.HttpRequestTimeoutException",
    )
    if (className in networkExceptions) return NsError.Network

    // Auth.
    if (className.contains("Unauthorized") || className.contains("InvalidCredentials")) {
        return NsError.Unauthorized
    }
    if (className.contains("Forbidden")) return NsError.PermissionDenied
    if (className.contains("NotFound") || className.contains("EmptyResult")) return NsError.NotFound
    if (className.contains("RateLimit") || className.contains("TooManyRequests")) {
        return NsError.RateLimited(retryAfterMillis = 60_000)
    }
    if (className.contains("RestException")) {
        // PostgREST devuelve el detalle de la violación RLS en el mensaje.
        val message = throwable.message.orEmpty()
        return when {
            "row-level security" in message || "42501" in message -> NsError.PermissionDenied
            "duplicate key" in message || "23505" in message ->
                NsError.Validation(field = null, reason = "already_exists")
            "check constraint" in message || "23514" in message ->
                NsError.Validation(field = null, reason = message)
            else -> NsError.Server(status = 400)
        }
    }

    // Causa raíz: si viene envuelta, se intenta clasificar la causa.
    throwable.cause?.let { cause ->
        if (cause !== throwable) {
            val mapped = mapThrowable(cause)
            if (mapped !is NsError.Unknown) return mapped
        }
    }

    return NsError.Unknown(throwable)
}
