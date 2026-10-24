package com.nomadsafe.app.feature.risk

import com.nomadsafe.app.core.util.GeoUtils
import com.nomadsafe.app.core.util.TimeProvider
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.IncidentCategory
import com.nomadsafe.app.domain.model.RiskLevel
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/** Severidad de un hotspot, para decidir tamaño y color del círculo en el mapa. */
enum class HotspotSeverity { WATCH, CAUTION, AVOID }

/**
 * Zona caliente: un grupo de incidentes cercanos que conviene mostrar como una
 * sola entidad en el mapa y en las alertas.
 *
 * [riskScore] reutiliza la misma fórmula de saturación del [RiskEngine] para que
 * el score de un hotspot y el score de una ruta sean comparables entre sí.
 */
data class Hotspot(
    val center: GeoPoint,
    val incidentIds: List<String>,
    val dominantCategory: IncidentCategory,
    val riskScore: Double,
    val riskLevel: RiskLevel,
    val severity: HotspotSeverity,
    /** Radio en metros del grupo, para dibujar el círculo. */
    val radiusMeters: Double,
    /** Antigüedad del incidente más reciente del grupo, en horas. */
    val freshestAgeHours: Double,
) {
    val incidentCount: Int get() = incidentIds.size
}

/**
 * Agrupamiento de incidentes en zonas calientes.
 *
 * ── Por qué clustering propio y no DBSCAN de una librería ────────────────────
 * DBSCAN estándar necesita un índice espacial y parámetros (eps, minPts) que no
 * se adaptan bien a datos de seguridad: acá los puntos tienen peso (un acoso no
 * equivale a una "situación sospechosa") y antigüedad (un robo de hoy no
 * equivale a uno de hace un mes). Un DBSCAN plano trataría ambos casos iguales.
 *
 * Este algoritmo es greedy ponderado:
 *   1. Ordenar por peso efectivo (severidad × estado × consenso × decaimiento temporal).
 *   2. Tomar el de mayor peso como semilla de un nuevo grupo.
 *   3. Absorber todos los incidentes dentro de `hotspotRadiusMeters` de la semilla.
 *   4. Recalcular el centroide del grupo y repetir con los restantes.
 *
 * Empieza por el más pesado a propósito: garantiza que un hecho grave defina el
 * centro de su zona, en vez de quedar diluido en el centroide de un grupo de
 * reportes menores alrededor.
 *
 * Complejidad O(n²) en el peor caso, que es aceptable porque `n` es el número de
 * incidentes del viewport (decenas a cientos), no de la ciudad. Si creciera, el
 * paso correcto es un grid index, no cambiar el algoritmo.
 *
 * Es determinista: con la misma entrada produce siempre la misma salida, porque
 * los empates de peso se rompen por `id`. Eso importa para poder testearlo.
 */
@Singleton
class HotspotDetector @Inject constructor(
    private val config: RiskConfig,
    private val timeProvider: TimeProvider,
) {

    fun detect(incidents: List<Incident>, now: Instant = timeProvider.now()): List<Hotspot> {
        val relevant = incidents.filter { it.status.weightMultiplier > 0.0 }
        if (relevant.size < config.hotspotMinIncidents) return emptyList()

        // Peso de cada incidente en este instante. Se calcula una sola vez.
        val weighted = relevant
            .map { it to weightOf(it, now) }
            .filter { (_, weight) -> weight > 0.0 }
            // Empates rotos por id: sin esto el orden depende del orden de entrada
            // y dos dispositivos con los mismos datos mostrarían hotspots distintos.
            .sortedWith(compareByDescending<Pair<Incident, Double>> { it.second }.thenBy { it.first.id })

        val assigned = mutableSetOf<String>()
        val hotspots = mutableListOf<Hotspot>()

        for ((seed, _) in weighted) {
            if (seed.id in assigned) continue

            val members = mutableListOf<Incident>()
            for ((candidate, _) in weighted) {
                if (candidate.id in assigned) continue
                if (GeoUtils.distanceMeters(seed.location, candidate.location) <= config.hotspotRadiusMeters) {
                    members += candidate
                }
            }

            if (members.size < config.hotspotMinIncidents) {
                // No forma un grupo; se descarta pero NO se marca como asignado,
                // por si un incidente posterior lo absorbe desde otro centro.
                continue
            }

            members.forEach { assigned += it.id }
            hotspots += buildHotspot(members, now)
        }

        return hotspots.sortedByDescending { it.riskScore }
    }

    /**
     * Peso instantáneo de un incidente.
     *
     * Usa el mismo decaimiento temporal que el [RiskEngine] para que ambos
     * componentes coincidan: sería inconsistente que el mapa mostrara una zona
     * como caliente mientras el motor de rutas la considera irrelevante.
     */
    private fun weightOf(incident: Incident, now: Instant): Double {
        val halfLife = incident.effectiveHalfLifeHours
        if (halfLife <= 0.0) return 0.0

        val ageHours = maxOf(0.0, (now - incident.occurredAt).inWholeMilliseconds / 3_600_000.0)
        val decay = exp(-LN_2 * ageHours / halfLife)

        return incident.effectiveSeverity * decay * incident.status.weightMultiplier
    }

    private fun buildHotspot(members: List<Incident>, now: Instant): Hotspot {
        // Centroide ponderado por peso, no promedio simple: un hecho grave
        // arrastra el centro hacia sí, que es lo que uno espera ver en el mapa.
        val weights = members.map { maxOf(weightOf(it, now), 1e-6) }
        val totalWeight = weights.sum()

        val centerLat = members.zip(weights).sumOf { (i, w) -> i.location.latitude * w } / totalWeight
        val centerLon = members.zip(weights).sumOf { (i, w) -> i.location.longitude * w } / totalWeight
        val center = GeoPoint(
            latitude = centerLat.coerceIn(-90.0, 90.0),
            longitude = centerLon.coerceIn(-180.0, 180.0),
        )

        // Radio real del grupo (distancia máxima desde el centroide), con un
        // piso para que los hotspots de 3 incidentes muy juntos no sean un punto.
        val maxDistance = members.maxOf { GeoUtils.distanceMeters(center, it.location) }
        val radius = maxOf(maxDistance, MIN_DISPLAY_RADIUS_METERS)

        // Misma saturación que el motor de riesgo → scores comparables.
        val accumulated = members.sumOf { weightOf(it, now) }
        val riskScore = (1.0 - exp(-accumulated / config.crowdSaturation)).coerceIn(0.0, 1.0)

        val dominant = members
            .groupingBy { it.category }
            .eachCount()
            .maxByOrNull { (_, count) -> count }
            ?.key ?: IncidentCategory.OTHER

        val freshestAge = members
            .minOf { maxOf(0.0, (now - it.occurredAt).inWholeMilliseconds / 3_600_000.0) }

        return Hotspot(
            center = center,
            incidentIds = members.map { it.id },
            dominantCategory = dominant,
            riskScore = riskScore,
            riskLevel = RiskLevel.fromScore(riskScore),
            severity = severityOf(riskScore, freshestAge),
            radiusMeters = radius,
            freshestAgeHours = freshestAge,
        )
    }

    /**
     * Severidad para el mapa. Combina score con frescura: un hotspot de score
     * alto pero con reportes de hace tres semanas no debería decir "evitar",
     * porque la situación probablemente ya cambió.
     */
    private fun severityOf(riskScore: Double, freshestAgeHours: Double): HotspotSeverity {
        val stale = freshestAgeHours > STALE_AFTER_HOURS
        return when {
            riskScore >= 0.70 && !stale -> HotspotSeverity.AVOID
            riskScore >= 0.40 && !stale -> HotspotSeverity.CAUTION
            else -> HotspotSeverity.WATCH
        }
    }

    private companion object {
        const val LN_2 = 0.6931471805599453
        const val MIN_DISPLAY_RADIUS_METERS = 60.0
        const val STALE_AFTER_HOURS = 336.0 // 2 semanas
    }
}
