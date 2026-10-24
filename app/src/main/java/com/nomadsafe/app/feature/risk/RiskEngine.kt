package com.nomadsafe.app.feature.risk

import com.nomadsafe.app.core.util.GeoUtils
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.IncidentCategory
import com.nomadsafe.app.domain.model.RiskLevel
import com.nomadsafe.app.domain.model.RoutePreference
import com.nomadsafe.app.domain.model.RouteReason
import com.nomadsafe.app.domain.model.RouteSegment
import com.nomadsafe.app.domain.model.SafeRoute
import com.nomadsafe.app.domain.model.CandidateRoute
import com.nomadsafe.app.domain.model.RiskAssessment
import com.nomadsafe.app.domain.model.RiskBreakdown
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Riesgo de un tramo individual, ya descompuesto en sus dos señales. */
data class SegmentRisk(
    val segment: RouteSegment,
    val crowdScore: Double,
    val staticScore: Double,
    val score: Double,
    val influencingIncidents: Int,
)

/**
 * Motor de riesgo de NomadSafe.
 *
 * ── Qué problema resuelve ────────────────────────────────────────────────────
 * Google Maps y WSL responden "¿cómo llego más rápido?". NomadSafe responde
 * "¿cómo llego más seguro?". Son funciones de costo distintas sobre el mismo
 * grafo, y ése es exactamente el diferencial del producto: no competimos por
 * routing, competimos por criterio de routing.
 *
 * ── El modelo, en tres pasos ─────────────────────────────────────────────────
 * **1. Señal dinámica (crowd).** Cada incidente irradia riesgo a su alrededor.
 * La contribución de un incidente `i` a un punto `p` es:
 *
 * ```
 * contrib(i, p) = severidad_efectiva(i) · K_espacial(d(p,i)) · K_temporal(edad(i))
 * ```
 *
 *   - `severidad_efectiva` = categoría × estado de moderación × consenso comunitario.
 *   - `K_espacial(d) = exp(-d² / 2σ²)` truncado a `maxInfluenceRadius`. Gaussiano
 *     porque el riesgo decae suave y continuamente; un kernel de corte duro
 *     produciría fronteras invisibles donde cruzar la calle cambia la puntuación.
 *   - `K_temporal(edad) = 0.5^(edad / vidaMedia)`. La vida media depende de la
 *     CATEGORÍA, que es la clave del modelo: un robo decae en días, una calle sin
 *     iluminación prácticamente no decae (ver [IncidentCategory]).
 *
 * La suma se satura con `1 - exp(-Σ / k)` para llevarla a [0,1).
 *
 * **2. Señal estática.** Iluminación y tipo de vía, desde OpenStreetMap. Esta
 * señal es la que hace que la app sea útil el día 1, antes de tener reportes.
 *
 * **3. Agregación.** El score del tramo mezcla ambas señales. El score de la
 * ruta combina el promedio ponderado por longitud con el peor tramo, porque una
 * ruta es tan segura como su peor cuadra.
 *
 * ── Por qué es Kotlin puro ───────────────────────────────────────────────────
 * Ninguna dependencia de Android. Todo el algoritmo corre en tests de JVM en
 * milisegundos, lo que importa porque esto se va a recalibrar muchas veces con
 * datos del piloto y cada cambio necesita una red de tests que lo respalde.
 *
 * ── Complejidad ──────────────────────────────────────────────────────────────
 * O(T · I) con T tramos e I incidentes. En la práctica I se pre-filtra por
 * bounding box en PostGIS (`ST_DWithin`), así que I es el número de incidentes
 * del viewport, no de la ciudad. Para una ruta de 200 tramos y 300 incidentes
 * cercanos son 60.000 evaluaciones: ~2 ms en un gama media.
 */
@Singleton
class RiskEngine @Inject constructor(
    private val config: RiskConfig,
    private val timeProvider: TimeProvider,
) {
    /** Constructor secundario para tests que quieren fijar la hora sin inyectar. */
    constructor(config: RiskConfig, now: Instant) : this(
        config = config,
        timeProvider = object : TimeProvider {
            override fun now(): Instant = now
        },
    )

    // ── Señal temporal ────────────────────────────────────────────────────────

    /**
     * Decaimiento exponencial en base 1/2.
     *
     * Se expresa como `2^(-edad/vidaMedia)` en vez de `e^(-λt)` porque el
     * parámetro es legible para producto: "a las 72 horas, un robo pesa la
     * mitad" se discute en una reunión; "λ = 0.00962" no.
     */
    internal fun temporalDecay(incident: Incident, now: Instant): Double {
        val halfLife = incident.effectiveHalfLifeHours
        if (halfLife <= 0.0) return 0.0

        // Edad en horas, con protección contra relojes desincronizados: un
        // reporte con occurredAt en el futuro (celular con hora mal puesta)
        // daría decaimiento negativo, o sea PESO MAYOR QUE 1. Se clampa a 0.
        val ageHours = max(0.0, (now - incident.occurredAt).inWholeMilliseconds / 3_600_000.0)

        return exp(-LN_2 * ageHours / halfLife)
    }

    // ── Señal espacial ────────────────────────────────────────────────────────

    /**
     * Kernel gaussiano truncado desde un tramo hacia un incidente.
     *
     * Usa distancia punto-a-segmento y no punto-a-punto: una avenida de 600 m
     * puede tener un robo en una punta y el usuario caminando por la otra. Con
     * el punto medio del tramo se sobrestimaría la distancia y se subestimaría
     * el riesgo real del tramo completo.
     */
    internal fun spatialKernel(segment: RouteSegment, incidentLocation: GeoPoint): Double {
        val distance = GeoUtils.distanceToSegmentMeters(incidentLocation, segment.from, segment.to)
        if (distance > config.maxInfluenceRadiusMeters) return 0.0

        val sigma = config.spatialSigmaMeters
        return exp(-(distance * distance) / (2.0 * sigma * sigma))
    }

    /**
     * Score colaborativo acumulado del tramo, ya saturado a [0,1).
     * Devuelve también cuántos incidentes influyeron, para la explicación.
     */
    internal fun crowdScore(segment: RouteSegment, incidents: List<Incident>, now: Instant): Pair<Double, Int> {
        if (incidents.isEmpty()) return 0.0 to 0

        var accumulated = 0.0
        var influencing = 0

        for (incident in incidents) {
            if (!incident.isRoutingRelevant) continue

            val spatial = spatialKernel(segment, incident.location)
            if (spatial <= SPATIAL_EPSILON) continue

            val temporal = temporalDecay(incident, now)
            val contribution = incident.effectiveSeverity * spatial * temporal
            if (contribution > 0.0) {
                accumulated += contribution
                influencing++
            }
        }

        // Saturación suave: mapea [0,∞) → [0,1). Ver RiskConfig.crowdSaturation.
        val saturated = 1.0 - exp(-accumulated / config.crowdSaturation)
        return saturated to influencing
    }

    // ── Señal estática ────────────────────────────────────────────────────────

    /**
     * Score estructural del tramo: iluminación + tipo de vía.
     *
     * Es lo único que la app sabe el día 1 en una ciudad nueva, antes de que
     * exista un solo reporte. Sin esta señal, el producto no arranca.
     */
    internal fun staticScore(segment: RouteSegment): Double {
        val lightingPenalty = when (segment.lit) {
            true -> config.litBonus
            false -> config.unlitPenalty
            null -> config.unknownLightingPenalty
        }
        return (segment.roadClass.penalty + lightingPenalty).coerceIn(0.0, 1.0)
    }

    // ── Tramo ─────────────────────────────────────────────────────────────────

    fun scoreSegment(segment: RouteSegment, incidents: List<Incident>, now: Instant = timeProvider.now()): SegmentRisk {
        val (crowd, influencing) = crowdScore(segment, incidents, now)
        val static = staticScore(segment)
        val combined = (config.crowdWeight * crowd + config.staticWeight * static).coerceIn(0.0, 1.0)

        return SegmentRisk(
            segment = segment,
            crowdScore = crowd,
            staticScore = static,
            score = combined,
            influencingIncidents = influencing,
        )
    }

    // ── Ruta ──────────────────────────────────────────────────────────────────

    /**
     * Evalúa una ruta candidata completa.
     *
     * La agregación combina promedio ponderado por longitud con el peor tramo:
     * ```
     * score = (1 - w) · Σ(scoreᵢ · lenᵢ)/Σlenᵢ + w · máx(scoreᵢ)
     * ```
     */
    fun assess(route: CandidateRoute, incidents: List<Incident>, now: Instant = timeProvider.now()): RiskAssessment {
        val segments = route.segments
        if (segments.isEmpty()) {
            NsLog.w(TAG, "Ruta ${route.id} sin tramos; se puntúa como riesgo 0")
            return RiskAssessment(
                score = 0.0,
                level = RiskLevel.LOW,
                breakdown = RiskBreakdown(0.0, 0.0, 0.0, 0.0, 0, 0, null),
            )
        }

        val risks = segments.map { scoreSegment(it, incidents, now) }

        val totalLength = risks.sumOf { max(1.0, it.segment.lengthMeters) }
        val weightedMean = risks.sumOf { it.score * max(1.0, it.segment.lengthMeters) } / totalLength
        val worst = risks.maxByOrNull { it.score } ?: risks.first()

        val blended = ((1.0 - config.worstSegmentWeight) * weightedMean +
            config.worstSegmentWeight * worst.score).coerceIn(0.0, 1.0)

        /*
         * Piso de seguridad escalado.
         *
         * El promedio ponderado por longitud diluye los tramos individuales: en
         * una ruta de 100 tramos, uno crítico y 99 seguros producen 0.26 (riesgo
         * MODERADO). Eso es inaceptable: la ruta cruza una cuadra peligrosa y la
         * UI la mostraría en amarillo.
         *
         * El piso no es plano sino creciente en la severidad del peor tramo, para
         * que dos rutas que cruzan peligro distinto no empaten y el ranking pueda
         * desempatarlas. Ver RiskConfig.criticalFloorSlope.
         */
        val crossesCritical = worst.score >= config.criticalSegmentScore

        /*
         * Fracción de la longitud total que transcurre en tramos críticos.
         * Por longitud y no por conteo: 3 pasajes de 20 m no exponen lo mismo
         * que 3 avenidas de 400 m.
         */
        val criticalLength = risks
            .filter { it.score >= config.criticalSegmentScore }
            .sumOf { max(1.0, it.segment.lengthMeters) }
        val exposureRatio = if (totalLength <= 0.0) 0.0 else (criticalLength / totalLength).coerceIn(0.0, 1.0)

        val criticalFloor = config.criticalRiskFloor +
            (worst.score - config.criticalSegmentScore) * config.criticalFloorSlope +
            exposureRatio * config.criticalExposureWeight

        val combined = if (crossesCritical) {
            max(blended, min(criticalFloor, 1.0))
        } else {
            blended
        }.coerceIn(0.0, 1.0)

        val meanCrowd = risks.sumOf { it.crowdScore * max(1.0, it.segment.lengthMeters) } / totalLength
        val meanStatic = risks.sumOf { it.staticScore * max(1.0, it.segment.lengthMeters) } / totalLength

        return RiskAssessment(
            score = combined,
            level = RiskLevel.fromScore(combined),
            breakdown = RiskBreakdown(
                crowdScore = meanCrowd,
                staticScore = meanStatic,
                worstSegmentScore = worst.score,
                combinedScore = combined,
                unlitSegmentCount = segments.count { it.lit == false },
                // Incidentes distintos que tocaron algún tramo (sin doble conteo).
                incidentsInfluencing = countDistinctInfluencingIncidents(segments, incidents, now),
                worstStreetName = worst.segment.streetName,
                crossesCriticalSegment = crossesCritical,
                criticalExposureRatio = exposureRatio,
            ),
        )
    }

    private fun countDistinctInfluencingIncidents(
        segments: List<RouteSegment>,
        incidents: List<Incident>,
        now: Instant,
    ): Int {
        if (incidents.isEmpty()) return 0

        // Un incidente influye si aporta algo a AL MENOS un tramo. Se recorre
        // incidente-por-incidente (no tramo-por-tramo) para no contar dos veces
        // el mismo robo que afecta a tres cuadras consecutivas.
        return incidents.count { incident ->
            incident.isRoutingRelevant && segments.any { segment ->
                spatialKernel(segment, incident.location) > SPATIAL_EPSILON &&
                    temporalDecay(incident, now) > TEMPORAL_EPSILON
            }
        }
    }

    // ── Ranking ───────────────────────────────────────────────────────────────

    /**
     * Puntúa y ordena las rutas candidatas según la preferencia del usuario.
     *
     * Devuelve la lista ordenada de mejor a peor costo, con la justificación
     * legible de cada una. Nunca devuelve una lista vacía si recibió candidatas.
     */
    fun rank(
        candidates: List<CandidateRoute>,
        incidents: List<Incident>,
        preference: RoutePreference,
        now: Instant = timeProvider.now(),
    ): List<SafeRoute> {
        if (candidates.isEmpty()) return emptyList()

        val fastestSeconds = candidates.minOf { it.durationSeconds }
        val assessed = candidates.map { route ->
            val assessment = assess(route, incidents, now)
            Triple(route, assessment, costOf(route, assessment, fastestSeconds, preference))
        }

        return assessed
            .sortedBy { it.third }
            .map { (route, assessment, _) ->
                SafeRoute(
                    route = route,
                    assessment = assessment,
                    extraMinutesVsFastest = ((route.durationSeconds - fastestSeconds) / 60.0)
                        .let { kotlin.math.round(it).toInt() },
                    reasons = buildReasons(route, assessment, incidents, now),
                    evaluatedAt = now,
                )
            }
    }

    /** La mejor ruta para la preferencia dada, o null si no hay candidatas. */
    fun selectBest(
        candidates: List<CandidateRoute>,
        incidents: List<Incident>,
        preference: RoutePreference,
        now: Instant = timeProvider.now(),
    ): SafeRoute? = rank(candidates, incidents, preference, now).firstOrNull()

    /**
     * Función de costo que ordena las rutas. Menor = mejor.
     *
     * El tiempo se normaliza contra la ruta más rápida (`extra / fastest`) en
     * vez de usar minutos absolutos, para que la penalidad sea relativa: 5
     * minutos extra sobre un viaje de 10 es mucho; sobre uno de 90, poco.
     */
    private fun costOf(
        route: CandidateRoute,
        assessment: RiskAssessment,
        fastestSeconds: Int,
        preference: RoutePreference,
    ): Double {
        val risk = assessment.score
        val normalizedTime = if (fastestSeconds <= 0) {
            0.0
        } else {
            (route.durationSeconds - fastestSeconds).toDouble() / fastestSeconds
        }

        return when (preference) {
            // SAFEST: el riesgo manda; el tiempo sólo rompe empates.
            RoutePreference.SAFEST -> risk + config.safestTimePenalty * normalizedTime

            RoutePreference.BALANCED -> config.balancedRiskWeight * risk +
                (1.0 - config.balancedRiskWeight) * normalizedTime

            // FASTEST: el tiempo manda, pero el riesgo no se ignora del todo.
            // Si dos rutas tardan lo mismo, se prefiere la más segura: es gratis.
            RoutePreference.FASTEST -> config.fastestRiskWeight * risk + normalizedTime
        }
    }

    // ── Explicabilidad ────────────────────────────────────────────────────────

    /**
     * Razones legibles de por qué se recomendó esta ruta.
     *
     * No es cosmético. Una recomendación de seguridad que no se puede explicar
     * no se sostiene: si la app manda a alguien a dar una vuelta de 15 minutos
     * sin decir por qué, la próxima vez ignora la sugerencia. La explicabilidad
     * es lo que construye la confianza en el criterio del motor.
     */
    private fun buildReasons(
        route: CandidateRoute,
        assessment: RiskAssessment,
        incidents: List<Incident>,
        now: Instant,
    ): List<RouteReason> {
        val reasons = mutableListOf<RouteReason>()
        val breakdown = assessment.breakdown

        if (breakdown.unlitSegmentCount > 0) {
            reasons += RouteReason(
                text = "Atraviesa ${breakdown.unlitSegmentCount} tramo(s) sin iluminación reportada",
                iconHint = "lightbulb_off",
            )
        } else if (route.segments.any { it.lit == true }) {
            reasons += RouteReason(
                text = "Prioriza calles con iluminación confirmada",
                iconHint = "lightbulb",
            )
        }

        if (breakdown.incidentsInfluencing > 0) {
            reasons += RouteReason(
                text = "Esquiva ${breakdown.incidentsInfluencing} zona(s) con reportes recientes",
                iconHint = "warning_off",
            )
        }

        // Qué se evitó: la categoría dominante en el radio de la ruta. Sirve
        // para que el usuario entienda contra qué se lo está protegiendo.
        dominantAvoidedCategory(route, incidents, now)?.let { category ->
            reasons += RouteReason(
                text = "Menor exposición a ${category.displayName()}",
                iconHint = "shield",
            )
        }

        breakdown.worstStreetName?.let { street ->
            reasons += RouteReason(
                text = "Tramo más delicado: $street",
                iconHint = "info",
            )
        }

        if (assessment.level == RiskLevel.HIGH || assessment.level == RiskLevel.CRITICAL) {
            reasons += RouteReason(
                text = "Riesgo alto incluso en la mejor opción: activá el Modo Escolta",
                iconHint = "escort",
            )
        }

        return reasons
    }

    private fun dominantAvoidedCategory(
        route: CandidateRoute,
        incidents: List<Incident>,
        now: Instant,
    ): IncidentCategory? {
        val nearby = incidents
            .filter { it.isRoutingRelevant }
            .filter { incident ->
                route.segments.any { segment ->
                    spatialKernel(segment, incident.location) > SPATIAL_EPSILON &&
                        temporalDecay(incident, now) > TEMPORAL_EPSILON
                }
            }
        if (nearby.isEmpty()) return null

        return nearby
            .groupingBy { it.category }
            .eachCount()
            .maxByOrNull { (_, count) -> count }
            ?.key
    }

    private companion object {
        const val TAG = "RiskEngine"
        const val LN_2 = 0.6931471805599453

        /** Umbrales de corte para no procesar contribuciones despreciables. */
        const val SPATIAL_EPSILON = 1e-4
        const val TEMPORAL_EPSILON = 1e-4
    }
}

/** Nombre legible en español para las explicaciones de ruta. */
internal fun IncidentCategory.displayName(): String = when (this) {
    IncidentCategory.ASSAULT -> "agresiones"
    IncidentCategory.THEFT -> "robos"
    IncidentCategory.HARASSMENT -> "acoso"
    IncidentCategory.SUSPICIOUS -> "situaciones sospechosas"
    IncidentCategory.DARK_STREET -> "calles sin iluminación"
    IncidentCategory.ROAD_BLOCKED -> "vías bloqueadas"
    IncidentCategory.ACCIDENT -> "accidentes"
    IncidentCategory.OTHER -> "otros incidentes"
}

/** Utilidad para clampar scores dentro de [0,1] sin repetir `coerceIn`. */
internal fun clampScore(value: Double): Double = min(1.0, max(0.0, value))
