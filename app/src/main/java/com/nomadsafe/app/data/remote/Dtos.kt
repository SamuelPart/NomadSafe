package com.nomadsafe.app.data.remote

import com.nomadsafe.app.domain.model.GeoPoint
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs de Postgres.
 *
 * Separados de las entidades de dominio a propósito, aunque se parezcan mucho.
 * El esquema de la base va a evolucionar (columnas nuevas, renombrados) y el
 * dominio no debería cambiar cada vez. Tener los dos modelos permite migrar la
 * base sin romper la UI, y viceversa.
 *
 * ── Por qué `location` es un String y no un tipo espacial ────────────────────
 * En Postgres la columna es `geography(Point,4326)`. PostgREST la serializa como
 * texto en formato WKT/EWKT (`0101000020E6100000...` en hex, o `POINT(-79.03 -8.11)`).
 * El formato exacto depende de la versión de PostGIS y de la configuración del
 * proyecto, así que se recibe como String y se parsea tolerando ambas formas.
 *
 * Asumir un solo formato acá es la fuente de bugs más probable de toda la
 * integración con el backend: el resto de la app funciona y el mapa aparece vacío.
 */
@Serializable
data class IncidentDto(
    val id: String,
    val category: String,
    val location: String,
    @SerialName("occurred_at") val occurredAt: Instant,
    @SerialName("reported_at") val reportedAt: Instant,
    val timing: String = "recent",
    val status: String = "active",
    val description: String? = null,
    @SerialName("reporter_id") val reporterId: String? = null,
    @SerialName("is_anonymous") val isAnonymous: Boolean = true,
    @SerialName("confirmation_count") val confirmationCount: Int = 0,
    @SerialName("dispute_count") val disputeCount: Int = 0,
    @SerialName("accuracy_meters") val accuracyMeters: Float? = null,
    val neighborhood: String? = null,
)

@Serializable
data class TrustContactDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    @SerialName("phone_e164") val phoneE164: String,
    val relationship: String? = null,
    @SerialName("is_nomadsafe_user") val isNomadSafeUser: Boolean = false,
    @SerialName("created_at") val createdAt: Instant,
    val priority: Int = 0,
)

@Serializable
data class EscortSessionDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("started_at") val startedAt: Instant,
    @SerialName("destination") val destination: String? = null,
    @SerialName("destination_label") val destinationLabel: String? = null,
    @SerialName("estimated_arrival") val estimatedArrival: Instant? = null,
    val status: String = "active",
    @SerialName("ended_at") val endedAt: Instant? = null,
    @SerialName("voice_detection_enabled") val voiceDetectionEnabled: Boolean = true,
    @SerialName("last_known_location") val lastKnownLocation: String? = null,
    @SerialName("last_check_in_at") val lastCheckInAt: Instant? = null,
)

@Serializable
data class SosEventDto(
    val id: String,
    @SerialName("escort_session_id") val escortSessionId: String? = null,
    @SerialName("user_id") val userId: String,
    val location: String,
    @SerialName("accuracy_meters") val accuracyMeters: Float? = null,
    @SerialName("triggered_at") val triggeredAt: Instant,
    val trigger: String,
    @SerialName("recognized_phrase") val recognizedPhrase: String? = null,
    @SerialName("battery_level") val batteryLevel: Int? = null,
    @SerialName("is_moving") val isMoving: Boolean = false,
    @SerialName("speed_mps") val speedMetersPerSecond: Float? = null,
    @SerialName("notified_contact_ids") val notifiedContactIds: List<String> = emptyList(),
    val status: String = "open",
)

@Serializable
data class ProfileDto(
    val id: String,
    @SerialName("phone_e164") val phoneE164: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val city: String? = null,
    @SerialName("referral_code") val referralCode: String? = null,
    @SerialName("referred_by") val referredBy: String? = null,
    @SerialName("created_at") val createdAt: Instant? = null,
    @SerialName("referral_credits") val referralCredits: Int = 0,
)

/**
 * Fila de la vista de features estáticas derivadas de OSM.
 *
 * Se sirve desde una vista materializada (`static_features_grid`) y no desde la
 * tabla cruda de ways de OSM: agregar iluminación y tipo de vía por celda en cada
 * petición sería caro, y los datos cambian muy poco.
 */
@Serializable
data class StaticFeatureDto(
    @SerialName("cell_key") val cellKey: String,
    @SerialName("center_lat") val centerLat: Double,
    @SerialName("center_lon") val centerLon: Double,
    val lit: Boolean? = null,
    @SerialName("road_class") val roadClass: String = "unknown",
    @SerialName("street_name") val streetName: String? = null,
)

/** Cuerpo de la Edge Function `raise-sos`. */
@Serializable
data class RaiseSosRequest(
    @SerialName("sos_id") val sosId: String,
    @SerialName("escort_session_id") val escortSessionId: String? = null,
    val location: String,
    @SerialName("accuracy_meters") val accuracyMeters: Float? = null,
    val trigger: String,
    @SerialName("recognized_phrase") val recognizedPhrase: String? = null,
    @SerialName("battery_level") val batteryLevel: Int? = null,
    @SerialName("is_moving") val isMoving: Boolean = false,
)

/**
 * Parseo de geometría PostGIS a [GeoPoint].
 *
 * Tolera los tres formatos que PostgREST puede devolver según versión y
 * configuración: WKT texto, EWKT con SRID, y WKB hex. Devuelve null si no
 * reconoce ninguno, en vez de lanzar: un registro con geometría corrupta no debe
 * tumbar la carga del mapa entero.
 */
object PostgisGeometry {

    private val wktPoint = Regex(
        """POINT\s*\(\s*(-?\d+\.?\d*(?:[eE][-+]?\d+)?)\s+(-?\d+\.?\d*(?:[eE][-+]?\d+)?)\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(value: String?): GeoPoint? {
        if (value.isNullOrBlank()) return null

        // 1. WKT / EWKT: "POINT(-79.0289 -8.1116)" o "SRID=4326;POINT(...)"
        wktPoint.find(value)?.let { match ->
            val first = match.groupValues[1].toDoubleOrNull() ?: return@let
            val second = match.groupValues[2].toDoubleOrNull() ?: return@let

            /*
             * WKT es (longitud latitud), al revés que casi todas las APIs de mapas.
             *
             * Interpretarlo al revés no falla de forma evidente: produce coordenadas
             * que a veces siguen siendo "válidas" y caen en el océano o en otro
             * continente, y el mapa aparece vacío sin ningún error en el log.
             *
             * Se asume el orden correcto de WKT. Sólo si eso da una latitud fuera de
             * rango se prueba el orden invertido, que es lo que pasaría si algún día
             * un proveedor escribe "POINT(lat lon)". No se hace al revés: adivinar
             * por defecto convertiría un dato correcto en incorrecto.
             */
            if (second in -90.0..90.0 && first in -180.0..180.0) {
                return GeoPoint(latitude = second, longitude = first)
            }
            if (first in -90.0..90.0 && second in -180.0..180.0) {
                return GeoPoint(latitude = first, longitude = second)
            }
        }

        // 2. WKB hex: "0101000020E6100000...." little-endian POINT.
        if (value.length >= 42 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return parseWkbHex(value)
        }

        return null
    }

    /** Serializa a WKT, que es lo que acepta el insert de PostgREST/PostGIS. */
    fun toWkt(point: GeoPoint): String = "POINT(${point.longitude} ${point.latitude})"

    private fun parseWkbHex(hex: String): GeoPoint? = runCatching {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (bytes.size < 21) return@runCatching null

        val littleEndian = bytes[0].toInt() == 1

        // Offset 5: X (double), offset 13: Y (double). Se salta byte order (1) +
        // tipo (4), que pueden venir precedidos por el SRID en EWKB.
        val dataStart = if (bytes.size >= 25 && hasSridFlag(bytes, littleEndian)) 9 else 5
        val x = bytes.toDouble(dataStart, littleEndian)
        val y = bytes.toDouble(dataStart + 8, littleEndian)

        val longitude = x
        val latitude = y
        if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            GeoPoint(latitude, longitude)
        } else {
            null
        }
    }.getOrNull()

    private fun hasSridFlag(bytes: ByteArray, littleEndian: Boolean): Boolean {
        if (bytes.size < 9) return false
        val type = bytes.toInt32(1, littleEndian)
        // Bit 0x20000000 del tipo WKB indica presencia de SRID (EWKB de PostGIS).
        return (type and 0x20000000) != 0
    }

    private fun ByteArray.toDouble(offset: Int, littleEndian: Boolean): Double =
        java.lang.Double.longBitsToDouble(toInt64(offset, littleEndian))

    private fun ByteArray.toInt64(offset: Int, littleEndian: Boolean): Long {
        var result = 0L
        for (i in 0 until 8) {
            val byte = this[offset + i].toLong() and 0xFF
            result = if (littleEndian) result or (byte shl (8 * i)) else (result shl 8) or byte
        }
        return result
    }

    private fun ByteArray.toInt32(offset: Int, littleEndian: Boolean): Int {
        var result = 0
        for (i in 0 until 4) {
            val byte = this[offset + i].toInt() and 0xFF
            result = if (littleEndian) result or (byte shl (8 * i)) else (result shl 8) or byte
        }
        return result
    }
}
