package com.nomadsafe.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.nomadsafe.app.core.util.GeoUtils
import com.nomadsafe.app.core.util.NsDispatchers
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.domain.model.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Umbral de velocidad para distinguir caminata de vehículo, en km/h.
 *
 * A nivel de archivo (y no dentro de una clase) porque lo usan tanto
 * [DeviceLocation.isMoving] como el detector de desvío del Modo Escolta, y tener
 * dos umbrales duplicados en dos clases es la receta para que diverjan.
 */
const val WALKING_THRESHOLD_KMH = 7.0

/** Lectura de ubicación con la metadata que el motor de riesgo y el SOS necesitan. */
data class DeviceLocation(
    val point: GeoPoint,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val altitudeMeters: Double?,
    val capturedAtEpochMillis: Long,
    val provider: String?,
) {
    /** Velocidad en km/h, o null si el sensor no la reporta. */
    val speedKmh: Double? get() = speedMetersPerSecond?.let { it.toDouble() * 3.6 }

    /**
     * Heurística de movimiento.
     *
     * Importa para el SOS: a 60 km/h una desviación de ruta es normal (el
     * colectivo tomó otra calle), pero a 4 km/h puede significar que están
     * trasladando a la persona a la fuerza. Sin este dato, el detector de desvío
     * dispara falsas alarmas en cada viaje en vehículo.
     *
     * Si el sensor no reporta velocidad se asume "en movimiento" sólo si hay un
     * bearing válido, que es la señal secundaria de que el GPS ve desplazamiento.
     */
    val isMoving: Boolean
        get() = when {
            speedKmh != null -> speedKmh!! > WALKING_THRESHOLD_KMH
            bearingDegrees != null -> true
            else -> false
        }
}

/**
 * Perfil de precisión de ubicación.
 *
 * Configurable porque el mapa y el Modo Escolta tienen necesidades opuestas:
 * el mapa quiere una posición exacta una sola vez; la escolta quiere
 * actualizaciones continuas pero BARATAS, porque puede durar una hora y el GPS a
 * alta precisión drena la batería en minutos. Batería agotada = escolta muerta =
 * la feature falla exactamente cuando se la necesita.
 */
enum class LocationProfile(
    val intervalSeconds: Long,
    val minUpdateDistanceMeters: Float,
    val priority: Int,
) {
    /** Una posición lo más precisa posible. */
    SINGLE_FIX(0, 0f, Priority.PRIORITY_HIGH_ACCURACY),

    /** Seguimiento de escolta: equilibrio entre precisión y batería. */
    ESCORT_TRACKING(5, 10f, Priority.PRIORITY_BALANCED_POWER_ACCURACY),

    /**
     * Bajo consumo, para escoltas largas o batería crítica.
     *
     * 15 s y 25 m alcanzan para saber que la persona sigue avanzando y para el
     * check-in. Se activa automáticamente al caer el nivel de batería.
     */
    ESCORT_LOW_POWER(15, 25f, Priority.PRIORITY_BALANCED_POWER_ACCURACY),
    ;

    fun toLocationRequest(): LocationRequest =
        LocationRequest.Builder(intervalSeconds.seconds.inWholeMilliseconds)
            .setPriority(priority)
            .setMinUpdateDistanceMeters(minUpdateDistanceMeters)
            // false: entregar el primer fix disponible aunque sea aproximado, en
            // vez de esperar uno preciso. Para una escolta, saber "está por acá"
            // de inmediato vale más que saber la cuadra exacta 30 segundos tarde.
            .setWaitForAccurateLocation(false)
            .build()
}

/**
 * Abstracción sobre el proveedor de ubicación.
 *
 * Convierte el API de callbacks de Play Services en [Flow], que es lo que consume
 * el resto del sistema. El `awaitClose` es esencial: sin él la callback seguiría
 * registrada después de que el consumidor cancele, y en un servicio de escolta
 * eso es una fuga de batería que el usuario no puede diagnosticar.
 */
interface LocationSource {

    /**
     * Ubicaciones continuas mientras haya suscriptor.
     *
     * Requiere ACCESS_FINE_LOCATION o ACCESS_COARSE_LOCATION ya concedido. Si no
     * está concedido, el flow se cierra sin emitir: nunca debe lanzar
     * SecurityException desde acá, porque quien llama es un foreground service que
     * no tiene forma de mostrar un diálogo de permisos.
     */
    fun observeLocations(profile: LocationProfile): Flow<DeviceLocation>

    /** Última posición conocida, sin esperar un fix nuevo. Rápido y barato. */
    suspend fun getLastKnown(): DeviceLocation?

    /** GPS encendido. Distinto del permiso: puede estar concedido y el GPS apagado. */
    fun isLocationEnabled(): Boolean

    fun hasFineLocationPermission(): Boolean

    fun hasCoarseLocationPermission(): Boolean
}

@Singleton
class FusedLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedClient: FusedLocationProviderClient,
    private val dispatchers: NsDispatchers,
) : LocationSource {

    @SuppressLint("MissingPermission") // Se verifica el permiso antes de cada llamada.
    override fun observeLocations(profile: LocationProfile): Flow<DeviceLocation> = callbackFlow {
        if (!hasAnyLocationPermission()) {
            NsLog.w(TAG, "observeLocations sin permiso de ubicación; se cierra el flow")
            close()
            return@callbackFlow
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: result.locations.lastOrNull() ?: return
                val deviceLocation = location.toDeviceLocation()

                // Filtro anti-ruido: Play Services suele entregar un fix de baja
                // precisión al arrancar. Propagarlo dibujaría al usuario a tres
                // cuadras de donde está.
                if (deviceLocation.accuracyMeters > MAX_ACCEPTABLE_ACCURACY_METERS) {
                    NsLog.d(TAG, "Fix descartado por precisión baja: ${deviceLocation.accuracyMeters} m")
                    return
                }

                trySend(deviceLocation)
            }
        }

        // Looper.getMainLooper() es obligatorio: con null la callback se entrega en
        // un hilo arbitrario y trySend puede competir con el cierre del flow.
        fusedClient.requestLocationUpdates(profile.toLocationRequest(), callback, Looper.getMainLooper())

        // Pedir también la última posición conocida de inmediato: el primer fix en
        // frío tarda 10-30 s y la UI no debe quedar en blanco mientras tanto.
        runCatching { fusedClient.lastLocation.await() }
            .getOrNull()
            ?.toDeviceLocation()
            ?.takeIf { it.accuracyMeters <= MAX_ACCEPTABLE_ACCURACY_METERS }
            ?.let { trySend(it) }

        awaitClose {
            runCatching { fusedClient.removeLocationUpdates(callback) }
            NsLog.d(TAG, "Actualizaciones de ubicación liberadas")
        }
    }.flowOn(dispatchers.io)

    @SuppressLint("MissingPermission")
    override suspend fun getLastKnown(): DeviceLocation? {
        if (!hasAnyLocationPermission()) return null

        // Primero Play Services, que fusiona GPS+red+WiFi y da el mejor fix.
        runCatching { fusedClient.lastLocation.await() }
            .getOrNull()
            ?.toDeviceLocation()
            ?.let { return it }

        // Fallback al LocationManager del sistema.
        return systemLastKnownLocation()
    }

    /**
     * Fallback al LocationManager del sistema.
     *
     * En dispositivos sin Google Play Services — cada vez más comunes en LATAM por
     * los Huawei sin GMS — `fusedClient.lastLocation` devuelve null para siempre.
     * Sin este fallback la app simplemente no funciona en esos equipos, que son
     * justamente los de usuarios que no pueden pagar un gama alta y que más
     * necesitan seguridad ciudadana.
     */
    @SuppressLint("MissingPermission")
    private fun systemLastKnownLocation(): DeviceLocation? {
        if (!hasAnyLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toDeviceLocation()
    }

    override fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    override fun hasFineLocationPermission(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun hasCoarseLocationPermission(): Boolean = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasAnyLocationPermission(): Boolean =
        hasFineLocationPermission() || hasCoarseLocationPermission()

    private fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toDeviceLocation() = DeviceLocation(
        point = GeoPoint(latitude = latitude, longitude = longitude),
        accuracyMeters = accuracy,
        speedMetersPerSecond = if (hasSpeed()) speed else null,
        bearingDegrees = if (hasBearing()) bearing else null,
        altitudeMeters = if (hasAltitude()) altitude else null,
        capturedAtEpochMillis = time,
        provider = provider,
    )

    private companion object {
        const val TAG = "FusedLocationSource"

        /**
         * Precisión máxima aceptable, en metros.
         *
         * 100 m es deliberadamente permisivo: en ciudad densa el GPS rebota entre
         * edificios y ver fixes de 40-60 m es normal. Filtrar más agresivamente
         * dejaría al usuario sin posición durante minutos, que es peor que una
         * posición aproximada. La precisión se guarda junto con la coordenada en
         * el evento SOS, así quien responde sabe cuán confiable es.
         */
        const val MAX_ACCEPTABLE_ACCURACY_METERS = 100f
    }
}

/**
 * Fuente de ubicación simulada, para modo demo y para tests.
 *
 * Recorre un camino lineal desde el centro del piloto a velocidad de caminata.
 * Permite desarrollar y demostrar el Modo Escolta completo — traza, desvío de
 * ruta, check-in, SOS — sin salir a caminar y sin depender del GPS del emulador,
 * que es notoriamente errático.
 */
class MockLocationSource @Inject constructor(
    private val dispatchers: NsDispatchers,
    private val timeProvider: TimeProvider,
) : LocationSource {

    override fun observeLocations(profile: LocationProfile): Flow<DeviceLocation> = flow {
        var step = 0
        while (true) {
            val point = GeoUtils.destination(
                origin = START,
                bearingDegrees = BEARING_DEGREES,
                distanceMeters = step * STEP_METERS,
            )
            emit(
                DeviceLocation(
                    point = point,
                    accuracyMeters = 8f,
                    speedMetersPerSecond = 1.3f,
                    bearingDegrees = BEARING_DEGREES.toFloat(),
                    altitudeMeters = 34.0,
                    capturedAtEpochMillis = timeProvider.nowMillis(),
                    provider = PROVIDER_MOCK,
                ),
            )
            step++
            delay(profile.intervalSeconds.seconds)
        }
    }.flowOn(dispatchers.io)

    override suspend fun getLastKnown(): DeviceLocation = DeviceLocation(
        point = START,
        accuracyMeters = 8f,
        speedMetersPerSecond = 1.3f,
        bearingDegrees = BEARING_DEGREES.toFloat(),
        altitudeMeters = 34.0,
        capturedAtEpochMillis = timeProvider.nowMillis(),
        provider = PROVIDER_MOCK,
    )

    override fun isLocationEnabled(): Boolean = true
    override fun hasFineLocationPermission(): Boolean = true
    override fun hasCoarseLocationPermission(): Boolean = true

    private companion object {
        val START = GeoPoint(-8.1116, -79.0289)
        const val BEARING_DEGREES = 45.0
        const val STEP_METERS = 12.0
        const val PROVIDER_MOCK = "mock"
    }
}
