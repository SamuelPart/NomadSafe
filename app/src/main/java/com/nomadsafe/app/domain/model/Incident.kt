package com.nomadsafe.app.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Categoría de incidente reportado.
 *
 * Los dos parámetros que lleva cada constante no son decorativos: alimentan
 * directamente al [com.nomadsafe.app.feature.risk.RiskEngine].
 *
 * [baseSeverity] es cuánto pesa el hecho en el score de riesgo (0..1).
 *
 * [halfLifeHours] es cada cuántas horas el reporte pierde la mitad de su peso.
 * Este es el parámetro más importante del modelo y el más contraintuitivo:
 *
 *   - Un robo es un evento agudo. Si pasó hace 4 días, la calle ya no es más
 *     peligrosa hoy que antes del robo. Vida media corta (72 h).
 *   - Una calle sin iluminación es ESTRUCTURAL: no se arregla sola con el paso
 *     de las horas. Vida media de un año, o sea prácticamente no decae.
 *
 * Tratar ambas categorías con el mismo decaimiento sería el error de modelado
 * más grave del producto: haría que las calles oscuras "se curaran" solas en
 * el mapa a los tres días, justo las que más importan para una ruta segura.
 *
 * [affectsRouting] separa lo que debe desviar una ruta de lo que sólo se
 * muestra en el mapa. Un accidente corta el tránsito pero no vuelve la zona
 * insegura para caminar; no debería empujar a nadie a un desvío de 20 minutos.
 */
enum class IncidentCategory(
    val wireName: String,
    val baseSeverity: Double,
    val halfLifeHours: Double,
    val affectsRouting: Boolean,
) {
    /** Agresión física. El hecho más grave y el de memoria más larga en el barrio. */
    ASSAULT("assault", baseSeverity = 1.00, halfLifeHours = 96.0, affectsRouting = true),

    /** Robo o intento de robo. La categoría dominante en volumen. */
    THEFT("theft", baseSeverity = 0.92, halfLifeHours = 72.0, affectsRouting = true),

    /** Acoso. Alta gravedad y denuncia baja: cada reporte vale mucho. */
    HARASSMENT("harassment", baseSeverity = 0.80, halfLifeHours = 168.0, affectsRouting = true),

    /** Situación sospechosa. Señal débil y subjetiva: pesa poco y decae rápido. */
    SUSPICIOUS("suspicious", baseSeverity = 0.40, halfLifeHours = 36.0, affectsRouting = true),

    /** Calle sin iluminación. Estructural: casi no decae (ver KDoc de la clase). */
    DARK_STREET("dark_street", baseSeverity = 0.38, halfLifeHours = 8_760.0, affectsRouting = true),

    /** Vía bloqueada. Afecta el paso, no la seguridad: vida media muy corta. */
    ROAD_BLOCKED("road_blocked", baseSeverity = 0.45, halfLifeHours = 8.0, affectsRouting = false),

    /** Accidente. Informativo; no debería desviar rutas peatonales. */
    ACCIDENT("accident", baseSeverity = 0.50, halfLifeHours = 24.0, affectsRouting = false),

    OTHER("other", baseSeverity = 0.30, halfLifeHours = 24.0, affectsRouting = true),
    ;

    companion object {
        /**
         * Mapeo tolerante desde el nombre que viene de Postgres.
         * Devuelve [OTHER] en vez de lanzar: un valor desconocido en la base
         * (porque alguien agregó una categoría en el servidor) no debe crashear
         * la app de todos los usuarios que todavía no actualizaron.
         */
        fun fromWireName(value: String?): IncidentCategory =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/**
 * Cuándo ocurrió el hecho, desde la perspectiva de quien reporta.
 *
 * No es lo mismo "está pasando ahora" que "acá roban seguido": el primero
 * describe un punto en el tiempo, el segundo describe un patrón. El patrón
 * decae mucho más lento porque afirma algo persistente sobre el lugar.
 */
enum class IncidentTiming(val wireName: String, val decayMultiplier: Double) {
    HAPPENING_NOW("now", 1.0),
    RECENT("recent", 1.0),

    /** "Sucede seguido acá" → el reporte afirma recurrencia, así que dura más. */
    RECURRENT("recurrent", 3.0),
    ;

    companion object {
        fun fromWireName(value: String?): IncidentTiming =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: RECENT
    }
}

/**
 * Ciclo de vida de moderación.
 *
 * [DISPUTED] existe porque el vector de abuso obvio de una app de mapas
 * colaborativos de seguridad es reportar la casa de alguien que te cae mal.
 * Sin un estado que reduzca el peso de un reporte disputado, el mapa se
 * envenena con dos o tres usuarios malintencionados.
 */
enum class IncidentStatus(val wireName: String, val weightMultiplier: Double) {
    ACTIVE("active", 1.0),

    /** Confirmado por otros usuarios: señal fuerte. */
    CONFIRMED("confirmed", 1.25),

    /** En revisión por moderación o por denuncias de la comunidad. */
    UNDER_REVIEW("under_review", 0.5),

    /** Otros usuarios lo marcaron como falso o impreciso. */
    DISPUTED("disputed", 0.15),

    /** Retirado por moderación. Se conserva por auditoría, no pesa nada. */
    REMOVED("removed", 0.0),
    ;

    companion object {
        fun fromWireName(value: String?): IncidentStatus =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: ACTIVE
    }
}

/**
 * Un incidente de seguridad reportado por un ciudadano.
 *
 * [confirmationCount] y [disputeCount] son la base del mecanismo de consenso:
 * en vez de confiar en un reporte aislado, el sistema pesa lo que la comunidad
 * dice de él. Eso cumple dos funciones a la vez: defiende el mapa contra
 * reportes falsos y le da al usuario un motivo para volver (ver lo que aportó).
 */
@Serializable
data class Incident(
    val id: String,
    val category: IncidentCategory,
    val location: GeoPoint,
    val occurredAt: Instant,
    val reportedAt: Instant,
    val timing: IncidentTiming = IncidentTiming.RECENT,
    val status: IncidentStatus = IncidentStatus.ACTIVE,
    val description: String? = null,
    val reporterId: String? = null,
    val isAnonymous: Boolean = true,
    val confirmationCount: Int = 0,
    val disputeCount: Int = 0,
    /** Radio de precisión del GPS al momento del reporte, en metros. */
    val accuracyMeters: Float? = null,
    /** Nombre legible del barrio, para mostrar en la alerta compartida. */
    val neighborhood: String? = null,
) {
    /**
     * Multiplicador de confianza por consenso comunitario.
     *
     * Logarítmico a propósito: la primera confirmación vale mucho (pasa de "un
     * vecino lo dice" a "dos vecinos lo dicen"), la vigésima no puede seguir
     * escalando o un grupo coordinado dominaría el mapa de una cuadra.
     *
     * Las disputas restan de forma más agresiva que lo que suman las
     * confirmaciones: ante la duda, el sistema debe subestimar el riesgo,
     * porque un falso positivo acá manda a la gente a dar vueltas innecesarias.
     */
    val consensusMultiplier: Double
        get() {
            val confirmations = confirmationCount.coerceAtLeast(0)
            val disputes = disputeCount.coerceAtLeast(0)
            val boost = if (confirmations == 0) 0.0 else kotlin.math.ln(1.0 + confirmations) * 0.35
            val penalty = disputes * 0.20
            return (1.0 + boost - penalty).coerceIn(0.1, 2.0)
        }

    /** Vida media efectiva en horas, ajustada por el tipo de reporte. */
    val effectiveHalfLifeHours: Double
        get() = category.halfLifeHours * timing.decayMultiplier

    /** Peso final del incidente, combinando categoría, estado y consenso. */
    val effectiveSeverity: Double
        get() = (category.baseSeverity * status.weightMultiplier * consensusMultiplier)
            .coerceIn(0.0, 1.5)

    val isRoutingRelevant: Boolean get() = category.affectsRouting && status != IncidentStatus.REMOVED
}
