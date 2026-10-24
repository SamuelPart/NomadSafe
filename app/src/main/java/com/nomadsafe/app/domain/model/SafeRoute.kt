package com.nomadsafe.app.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Clasificación funcional de la vía. Afecta el score estático de riesgo. */
enum class RoadClass(val wireName: String, val penalty: Double) {
    /** Peatonal o pasaje: poco tránsito, poca "vigilancia natural" de ojos en la calle. */
    PEDESTRIAN("pedestrian", 0.25),

    /** Calle residencial. El mejor caso: tránsito lento y vecinos cerca. */
    RESIDENTIAL("residential", 0.0),

    /** Colectora con comercio: iluminación y gente hasta tarde. Suele ser la mejor opción. */
    SECONDARY("secondary", -0.10),

    /** Avenida principal: iluminada y con tránsito, pero cruces largos y peligrosos. */
    PRIMARY("primary", 0.05),

    /** Vía rápida sin vereda útil. Para un peatón es directamente intransitable. */
    TRUNK("trunk", 0.60),

    /** Clasificación desconocida: se penaliza levemente por incertidumbre. */
    UNKNOWN("unknown", 0.15),
    ;

    companion object {
        fun fromWireName(value: String?): RoadClass =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Tramo elemental sobre el que se evalúa riesgo. */
@Serializable
data class RouteSegment(
    val from: GeoPoint,
    val to: GeoPoint,
    val lengthMeters: Double,
    val roadClass: RoadClass = RoadClass.UNKNOWN,
    /**
     * Estado de iluminación del tramo.
     *
     * Es triestado y no booleano porque la honestidad importa acá:
     * `null` significa "no sabemos", que es distinto de "sabemos que está
     * oscuro". El motor aplica una penalidad intermedia a lo desconocido, en
     * vez de asumirlo iluminado (peligroso) o asumirlo oscuro (paranoico y
     * volvería inútiles todas las rutas).
     */
    val lit: Boolean? = null,
    /** Nombre de la calle, para explicar la ruta en lenguaje humano. */
    val streetName: String? = null,
)

/** Cómo prioriza el usuario seguridad contra tiempo. */
enum class RoutePreference {
    /** Minimiza riesgo; acepta bastante más tiempo. */
    SAFEST,

    /** Punto medio. Es el default: la mayoría de la gente no quiere 25 min extra. */
    BALANCED,

    /** Minimiza tiempo; el riesgo sólo se muestra, no decide. */
    FASTEST,
}

/** Nivel de riesgo legible para la UI. Los umbrales viven en RiskConfig. */
enum class RiskLevel {
    LOW, MODERATE, HIGH, CRITICAL;

    companion object {
        fun fromScore(score: Double): RiskLevel = when {
            score < 0.20 -> LOW
            score < 0.45 -> MODERATE
            score < 0.70 -> HIGH
            else -> CRITICAL
        }
    }
}

/**
 * Ruta candidata devuelta por el proveedor de routing, antes de puntuar.
 *
 * El proveedor (Mapbox Directions, OSRM o el stub local) sólo sabe de tiempos
 * y distancias. Todo lo de seguridad lo agrega [com.nomadsafe.app.feature.risk.RiskEngine]
 * encima, que es justamente el punto: no dependemos de que un proveedor externo
 * entienda de seguridad ciudadana en Trujillo.
 */
@Serializable
data class CandidateRoute(
    val id: String,
    val segments: List<RouteSegment>,
    val durationSeconds: Int,
    val distanceMeters: Double,
    val isFastest: Boolean = false,
)

/** Desglose de por qué una ruta puntúa como puntúa. Se muestra al usuario. */
@Serializable
data class RiskBreakdown(
    val crowdScore: Double,
    val staticScore: Double,
    val worstSegmentScore: Double,
    val combinedScore: Double,
    val unlitSegmentCount: Int,
    val incidentsInfluencing: Int,
    val worstStreetName: String?,
    /**
     * True si la ruta cruza al menos un tramo crítico.
     *
     * Se expone aparte del score porque dispara comportamiento propio en la UI:
     * el aviso de "activá el Modo Escolta" y el color rojo del resumen de ruta.
     * También es la señal que permite auditar el piso de seguridad del motor.
     */
    val crossesCriticalSegment: Boolean = false,
    /**
     * Fracción de la longitud de la ruta que transcurre en tramos críticos (0..1).
     *
     * Se expone para la UI ("cruzás 3 cuadras críticas") y porque es el término
     * que permite al motor distinguir dos rutas cuyo peor tramo es igual de malo
     * pero que exponen al usuario una cantidad de veces distinta.
     */
    val criticalExposureRatio: Double = 0.0,
)

/** Evaluación de riesgo de una ruta candidata. */
@Serializable
data class RiskAssessment(
    /** Score normalizado 0..1. */
    val score: Double,
    val level: RiskLevel,
    val breakdown: RiskBreakdown,
)

/** Motivo legible para explicar la elección de ruta. */
@Serializable
data class RouteReason(val text: String, val iconHint: String)

/** Resultado final: la ruta recomendada y su justificación. */
@Serializable
data class SafeRoute(
    val route: CandidateRoute,
    val assessment: RiskAssessment,
    /**
     * Minutos extra frente a la ruta más rápida.
     *
     * Se calcula y se muestra SIEMPRE, incluso cuando es 0. Ocultar el costo en
     * tiempo de una decisión de seguridad es la forma más rápida de que el
     * usuario deje de confiar en la recomendación.
     */
    val extraMinutesVsFastest: Int,
    val reasons: List<RouteReason> = emptyList(),
    val evaluatedAt: Instant,
)
