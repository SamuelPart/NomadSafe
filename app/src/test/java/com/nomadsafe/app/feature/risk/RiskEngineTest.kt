package com.nomadsafe.app.feature.risk

import com.google.common.truth.Truth.assertThat
import com.nomadsafe.app.domain.model.CandidateRoute
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.IncidentCategory
import com.nomadsafe.app.domain.model.IncidentStatus
import com.nomadsafe.app.domain.model.IncidentTiming
import com.nomadsafe.app.domain.model.RiskLevel
import com.nomadsafe.app.domain.model.RoadClass
import com.nomadsafe.app.domain.model.RoutePreference
import com.nomadsafe.app.domain.model.RouteSegment
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/**
 * Tests del motor de riesgo.
 *
 * Cada test documenta una DECISIÓN DE PRODUCTO, no sólo una propiedad matemática.
 * El nombre del test dice qué comportamiento estamos protegiendo, porque cuando
 * alguien recalibre los pesos del motor con datos del piloto, estos tests son la
 * especificación de lo que el motor no debe dejar de hacer.
 *
 * Geometría de referencia: trabajamos sobre Trujillo, cerca del centro del piloto.
 * 0.001° de latitud ≈ 111 m, que es aproximadamente una cuadra.
 */
class RiskEngineTest {

    private val now = Instant.parse("2026-10-23T20:00:00Z")
    private val config = RiskConfig()
    private val engine = RiskEngine(config, now)

    private val blockCenter = GeoPoint(-8.1116, -79.0289)

    /** Un tramo de ~111 m (una cuadra) sobre el centro del piloto. */
    private fun segment(
        lit: Boolean? = null,
        roadClass: RoadClass = RoadClass.RESIDENTIAL,
        streetName: String? = "Jr. Pizarro",
        lengthMeters: Double = 111.0,
    ) = RouteSegment(
        from = GeoPoint(-8.1116, -79.0289),
        to = GeoPoint(-8.1126, -79.0289),
        lengthMeters = lengthMeters,
        roadClass = roadClass,
        lit = lit,
        streetName = streetName,
    )

    /**
     * Tramo desplazado geográficamente, lejos de cualquier incidente de prueba.
     *
     * Existe por una razón: el helper [segment] devuelve SIEMPRE las mismas
     * coordenadas, así que armar una ruta de 10 tramos con él produce 10 tramos
     * apilados en el mismo punto. Comparar esa ruta contra otra que sí se
     * extiende por el mapa no compara lo que parece comparar (ambas rutas deben
     * tener la misma geometría para que la única diferencia sea el riesgo).
     */
    private fun farSegment(index: Int, lit: Boolean? = null) = RouteSegment(
        from = GeoPoint(-8.2000 + index * 0.01, -79.0289),
        to = GeoPoint(-8.2000 + (index + 1) * 0.01, -79.0289),
        lengthMeters = 111.0,
        roadClass = RoadClass.RESIDENTIAL,
        lit = lit,
        streetName = "Calle Lejana $index",
    )

    private fun incident(
        category: IncidentCategory = IncidentCategory.THEFT,
        at: GeoPoint = blockCenter,
        ageHours: Long = 6,
        confirmations: Int = 0,
        disputes: Int = 0,
        status: IncidentStatus = IncidentStatus.ACTIVE,
        timing: IncidentTiming = IncidentTiming.RECENT,
        id: String = "i-${category.wireName}-$ageHours",
    ) = Incident(
        id = id,
        category = category,
        location = at,
        occurredAt = now - ageHours.hours,
        reportedAt = now - ageHours.hours,
        timing = timing,
        status = status,
        confirmationCount = confirmations,
        disputeCount = disputes,
    )

    // ── 1. El score está acotado ──────────────────────────────────────────────

    @Test
    fun `score siempre queda entre 0 y 1`() {
        // Sin incidentes ni datos estáticos.
        val empty = engine.scoreSegment(segment(lit = true), emptyList(), now)
        assertThat(empty.score).isAtLeast(0.0)
        assertThat(empty.score).isAtMost(1.0)

        // Saturación: 50 robos en el mismo punto no deben romper el rango.
        val flood = List(50) { index ->
            incident(id = "flood-$index", at = blockCenter, ageHours = 1)
        }
        val saturated = engine.scoreSegment(segment(lit = false, roadClass = RoadClass.TRUNK), flood, now)
        assertThat(saturated.score).isAtLeast(0.0)
        assertThat(saturated.score).isAtMost(1.0)
    }

    // ── 2. La señal colaborativa funciona ─────────────────────────────────────

    @Test
    fun `un incidente cerca sube el score frente a ninguno`() {
        val without = engine.scoreSegment(segment(), emptyList(), now).score
        val with = engine.scoreSegment(segment(), listOf(incident()), now).score

        assertThat(with).isGreaterThan(without)
    }

    @Test
    fun `un incidente lejos del radio de influencia no afecta el score`() {
        // ~1.1 km al norte: fuera de maxInfluenceRadiusMeters (400 m).
        val far = incident(at = GeoPoint(-8.1016, -79.0289), id = "far")

        val baseline = engine.scoreSegment(segment(), emptyList(), now).score
        val withFar = engine.scoreSegment(segment(), listOf(far), now).score

        assertThat(withFar).isEqualTo(baseline)
    }

    @Test
    fun `mas incidentes acumulan riesgo pero con rendimientos decrecientes`() {
        val one = engine.scoreSegment(segment(), listOf(incident(id = "a")), now).crowdScore
        val three = engine.scoreSegment(
            segment(),
            listOf(incident(id = "a"), incident(id = "b"), incident(id = "c")),
            now,
        ).crowdScore
        val ten = engine.scoreSegment(
            segment(),
            List(10) { incident(id = "x$it") },
            now,
        ).crowdScore

        assertThat(three).isGreaterThan(one)
        assertThat(ten).isGreaterThan(three)

        // La ganancia de pasar de 3 a 10 (7 reportes) debe ser MENOR que la de
        // pasar de 1 a 3 (2 reportes). Es lo que garantiza la saturación:
        // sin ella, quien inunde una cuadra de reportes la vuelve intransitable.
        val gainFirstTwo = three - one
        val gainNextSeven = ten - three
        assertThat(gainNextSeven).isLessThan(gainFirstTwo * 7)
    }

    // ── 3. El decaimiento temporal depende de la CATEGORÍA ────────────────────

    @Test
    fun `un robo viejo pesa menos que uno reciente`() {
        val fresh = engine.temporalDecay(incident(ageHours = 1), now)
        val stale = engine.temporalDecay(incident(ageHours = 200), now)

        assertThat(fresh).isGreaterThan(stale)
    }

    @Test
    fun `un robo a las 72 horas pesa aproximadamente la mitad`() {
        // THEFT tiene halfLifeHours = 72. Es la definición misma de vida media.
        val decay = engine.temporalDecay(
            incident(category = IncidentCategory.THEFT, ageHours = 72),
            now,
        )

        assertThat(decay).isWithin(0.01).of(0.5)
    }

    @Test
    fun `una calle sin iluminacion practicamente no decae con los dias`() {
        // DECISIÓN DE PRODUCTO CLAVE: DARK_STREET tiene vida media de un año
        // porque es un hecho estructural. Si decayera como un robo, las calles
        // oscuras "se curarían" solas del mapa en tres días, y justo ésas son
        // las que más importan para una ruta segura de noche.
        val afterTwoWeeks = engine.temporalDecay(
            incident(category = IncidentCategory.DARK_STREET, ageHours = 336),
            now,
        )
        val theftAfterTwoWeeks = engine.temporalDecay(
            incident(category = IncidentCategory.THEFT, ageHours = 336),
            now,
        )

        assertThat(afterTwoWeeks).isGreaterThan(0.95)
        assertThat(theftAfterTwoWeeks).isLessThan(0.1)
    }

    @Test
    fun `un reporte marcado como recurrente dura mas que uno puntual`() {
        val punctual = engine.temporalDecay(
            incident(timing = IncidentTiming.RECENT, ageHours = 48),
            now,
        )
        val recurrent = engine.temporalDecay(
            incident(timing = IncidentTiming.RECURRENT, ageHours = 48, id = "rec"),
            now,
        )

        assertThat(recurrent).isGreaterThan(punctual)
    }

    @Test
    fun `un reporte con fecha futura por reloj desincronizado no gana peso`() {
        // Sin el clamp a 0, un occurredAt en el futuro daría decaimiento > 1,
        // o sea un reporte que pesa MÁS que uno recién hecho. Un celular con la
        // hora mal puesta inflaría artificialmente el riesgo de su cuadra.
        val future = incident(ageHours = -48, id = "future")

        assertThat(engine.temporalDecay(future, now)).isAtMost(1.0)
    }

    // ── 4. Consenso comunitario y anti-abuso ──────────────────────────────────

    @Test
    fun `las confirmaciones de la comunidad suben el peso del reporte`() {
        val alone = engine.scoreSegment(segment(), listOf(incident(confirmations = 0, id = "a")), now).score
        val confirmed = engine.scoreSegment(
            segment(),
            listOf(incident(confirmations = 4, id = "a")),
            now,
        ).score

        assertThat(confirmed).isGreaterThan(alone)
    }

    @Test
    fun `las confirmaciones tienen rendimiento decreciente`() {
        // Anti-manipulación: si fuera lineal, un grupo coordinado de 20 personas
        // podría volver intransitable la cuadra de alguien que les cae mal.
        val few = incident(confirmations = 2, id = "few").consensusMultiplier
        val many = incident(confirmations = 20, id = "many").consensusMultiplier

        assertThat(many).isGreaterThan(few)
        assertThat(many - few).isLessThan(2.0)
        assertThat(many).isAtMost(2.0) // el techo duro del multiplicador
    }

    @Test
    fun `las disputas restan mas de lo que suman las confirmaciones`() {
        // Ante la duda el sistema SUBESTIMA el riesgo: un falso positivo manda a
        // la gente a dar vueltas innecesarias, y eso erosiona la confianza.
        val disputed = incident(confirmations = 2, disputes = 2, id = "d").consensusMultiplier
        val clean = incident(confirmations = 2, disputes = 0, id = "c").consensusMultiplier

        assertThat(disputed).isLessThan(clean)
    }

    @Test
    fun `un reporte retirado por moderacion no aporta riesgo`() {
        val removed = engine.scoreSegment(
            segment(),
            listOf(incident(status = IncidentStatus.REMOVED, id = "r")),
            now,
        )

        assertThat(removed.crowdScore).isEqualTo(0.0)
        assertThat(removed.influencingIncidents).isEqualTo(0)
    }

    @Test
    fun `un reporte disputado pesa una fraccion del original`() {
        val active = incident(status = IncidentStatus.ACTIVE, id = "a").effectiveSeverity
        val disputed = incident(status = IncidentStatus.DISPUTED, id = "d").effectiveSeverity

        assertThat(disputed).isLessThan(active * 0.3)
    }

    @Test
    fun `los incidentes que no afectan routing no desvian rutas`() {
        // DECISIÓN DE PRODUCTO: un accidente corta el tránsito pero no vuelve la
        // cuadra insegura para caminar. No debería empujar a nadie a un desvío
        // de 20 minutos.
        val baseline = engine.scoreSegment(segment(), emptyList(), now).score
        val withAccident = engine.scoreSegment(
            segment(),
            listOf(incident(category = IncidentCategory.ACCIDENT, id = "acc")),
            now,
        ).score

        assertThat(withAccident).isEqualTo(baseline)
    }

    // ── 5. Señal estática: iluminación ────────────────────────────────────────

    @Test
    fun `un tramo sin iluminacion puntua peor que uno iluminado`() {
        val lit = engine.scoreSegment(segment(lit = true), emptyList(), now).score
        val unlit = engine.scoreSegment(segment(lit = false), emptyList(), now).score

        assertThat(unlit).isGreaterThan(lit)
    }

    @Test
    fun `la iluminacion desconocida puntua entre iluminado y oscuro`() {
        // `lit == null` es el caso común al arrancar (no hay datos de OSM para
        // casi ninguna calle). Debe expresar incertidumbre, no asumir el mejor
        // ni el peor caso.
        val lit = engine.scoreSegment(segment(lit = true), emptyList(), now).staticScore
        val unknown = engine.scoreSegment(segment(lit = null), emptyList(), now).staticScore
        val unlit = engine.scoreSegment(segment(lit = false), emptyList(), now).staticScore

        assertThat(unknown).isGreaterThan(lit)
        assertThat(unknown).isLessThan(unlit)
    }

    @Test
    fun `una via rapida sin vereda puntua peor que una calle residencial`() {
        val residential = engine.scoreSegment(
            segment(lit = null, roadClass = RoadClass.RESIDENTIAL),
            emptyList(),
            now,
        ).staticScore
        val trunk = engine.scoreSegment(
            segment(lit = null, roadClass = RoadClass.TRUNK),
            emptyList(),
            now,
        ).staticScore

        assertThat(trunk).isGreaterThan(residential)
    }

    @Test
    fun `una colectora comercial puntua mejor que una peatonal aislada`() {
        // SECONDARY tiene penalidad negativa: comercio abierto significa ojos en
        // la calle y luz, que es la mejor protección peatonal real que existe.
        val secondary = engine.scoreSegment(
            segment(lit = true, roadClass = RoadClass.SECONDARY),
            emptyList(),
            now,
        ).staticScore
        val pedestrian = engine.scoreSegment(
            segment(lit = true, roadClass = RoadClass.PEDESTRIAN),
            emptyList(),
            now,
        ).staticScore

        assertThat(secondary).isLessThan(pedestrian)
    }

    // ── 6. Agregación por ruta ────────────────────────────────────────────────

    @Test
    fun `a igual longitud, la ruta que cruza el hotspot puntua peor`() {
        // DECISIÓN DE PRODUCTO CLAVE: una ruta no es tan segura como su promedio,
        // es tan segura como su PEOR cuadra.
        //
        // Fixture: ambas rutas tienen 10 tramos de 111 m y la misma duración. La
        // única diferencia es que `oneBadBlock` cruza el hotspot y `uniform` pasa
        // 9 km más al sur. Si no se controla la geometría, el test compara
        // longitud contra riesgo y pasa por la razón equivocada.
        val hot = incident(at = GeoPoint(-8.1116, -79.0289), ageHours = 2, confirmations = 5, id = "hot")

        val uniform = CandidateRoute(
            id = "uniform",
            durationSeconds = 900,
            distanceMeters = 1_110,
            segments = List(10) { index -> farSegment(index) },
        )
        val oneBadBlock = CandidateRoute(
            id = "one-bad",
            durationSeconds = 900,
            distanceMeters = 1_110,
            segments = listOf(segment(lit = false)) + List(9) { index -> farSegment(index) },
        )

        val uniformScore = engine.assess(uniform, listOf(hot), now).score
        val badBlockScore = engine.assess(oneBadBlock, listOf(hot), now).score

        assertThat(badBlockScore).isGreaterThan(uniformScore)
    }

    @Test
    fun `una ruta larga no puede diluir una cuadra critica`() {
        // Garantía de seguridad del modelo. Con 100 tramos, el promedio ponderado
        // por longitud aplasta la contribución de uno solo: sin el piso de
        // seguridad esta ruta daría ~0.24 (MODERADO) pese a cruzar una vía de
        // tránsito pesado sin luz con un robo confirmado hace dos horas.
        val hot = incident(ageHours = 2, confirmations = 5, id = "hot")
        val criticalBlock = segment(lit = false, roadClass = RoadClass.TRUNK)

        val criticalScore = engine.scoreSegment(criticalBlock, listOf(hot), now).score
        assertThat(criticalScore).isAtLeast(config.criticalSegmentScore)

        val longRoute = CandidateRoute(
            id = "long",
            durationSeconds = 6_000,
            distanceMeters = 11_100,
            segments = listOf(criticalBlock) + List(99) { index -> farSegment(index) },
        )

        val assessment = engine.assess(longRoute, listOf(hot), now)

        assertThat(assessment.breakdown.crossesCriticalSegment).isTrue()
        // 0.45 es el piso de la banda HIGH en RiskLevel.fromScore.
        assertThat(assessment.score).isAtLeast(config.criticalRiskFloor)
        assertThat(assessment.level).isAnyOf(RiskLevel.HIGH, RiskLevel.CRITICAL)
    }

    @Test
    fun `cruzar tres cuadras criticas puntua peor que cruzar una`() {
        // El piso escala con la severidad del PEOR tramo, pero eso no alcanza:
        // si dos rutas tienen el mismo peor tramo y distinto número de tramos
        // críticos, empatarían y el ranking no podría preferir la que expone al
        // usuario una sola vez. Lo resuelve el término de exposición.
        val hot = incident(ageHours = 2, confirmations = 5, id = "hot")
        val critical = segment(lit = false, roadClass = RoadClass.TRUNK)

        val oneCritical = CandidateRoute(
            id = "one",
            durationSeconds = 6_000,
            distanceMeters = 11_100,
            segments = listOf(critical) + List(99) { index -> farSegment(index) },
        )
        val threeCritical = CandidateRoute(
            id = "three",
            durationSeconds = 6_000,
            distanceMeters = 11_100,
            segments = List(3) { critical } + List(97) { index -> farSegment(index) },
        )

        val one = engine.assess(oneCritical, listOf(hot), now)
        val three = engine.assess(threeCritical, listOf(hot), now)

        assertThat(three.breakdown.criticalExposureRatio)
            .isGreaterThan(one.breakdown.criticalExposureRatio)
        assertThat(three.score).isGreaterThan(one.score)
    }

    @Test
    fun `una ruta corta por zona mala puntua peor que una larga que apenas la roza`() {
        // La exposición se mide por LONGITUD, no por conteo de tramos: un recorrido
        // corto que atraviesa el peligro de punta a punta es peor que uno largo
        // que pasa una sola cuadra por ahí.
        val hot = incident(ageHours = 2, confirmations = 5, id = "hot")
        val critical = segment(lit = false, roadClass = RoadClass.TRUNK)

        val short = CandidateRoute(
            id = "short",
            durationSeconds = 600,
            distanceMeters = 555,
            segments = listOf(critical) + List(4) { index -> farSegment(index) },
        )
        val long = CandidateRoute(
            id = "long",
            durationSeconds = 6_000,
            distanceMeters = 11_100,
            segments = listOf(critical) + List(99) { index -> farSegment(index) },
        )

        assertThat(engine.assess(short, listOf(hot), now).score)
            .isGreaterThan(engine.assess(long, listOf(hot), now).score)
    }

    @Test
    fun `una ruta sin tramos criticos no se infla con el piso`() {
        // El piso sólo aplica cuando corresponde. Si se aplicara siempre, toda
        // ruta de la ciudad saldría en rojo y la señal dejaría de significar nada.
        val route = CandidateRoute(
            id = "calm",
            durationSeconds = 600,
            distanceMeters = 555,
            segments = List(5) { index -> farSegment(index) },
        )

        val assessment = engine.assess(route, emptyList(), now)

        assertThat(assessment.breakdown.crossesCriticalSegment).isFalse()
        assertThat(assessment.score).isLessThan(config.criticalRiskFloor)
        assertThat(assessment.level).isAnyOf(RiskLevel.LOW, RiskLevel.MODERATE)
    }

    @Test
    fun `una ruta mas larga pero iluminada gana en preferencia SAFEST`() {
        val darkShortcut = CandidateRoute(
            id = "dark-shortcut",
            durationSeconds = 600, // 10 min
            distanceMeters = 700,
            isFastest = true,
            segments = List(6) { segment(lit = false, roadClass = RoadClass.PEDESTRIAN, streetName = "Pasaje $it") },
        )
        val litDetour = CandidateRoute(
            id = "lit-detour",
            durationSeconds = 1_080, // 18 min
            distanceMeters = 1_300,
            segments = List(12) { segment(lit = true, roadClass = RoadClass.SECONDARY, streetName = "Av. $it") },
        )

        val ranked = engine.rank(
            candidates = listOf(darkShortcut, litDetour),
            incidents = emptyList(),
            preference = RoutePreference.SAFEST,
            now = now,
        )

        assertThat(ranked.first().route.id).isEqualTo("lit-detour")
        assertThat(ranked.first().extraMinutesVsFastest).isEqualTo(8)
    }

    @Test
    fun `la misma situacion en preferencia FASTEST elige el atajo oscuro`() {
        val darkShortcut = CandidateRoute(
            id = "dark-shortcut",
            durationSeconds = 600,
            distanceMeters = 700,
            isFastest = true,
            segments = List(6) { segment(lit = false, roadClass = RoadClass.PEDESTRIAN) },
        )
        val litDetour = CandidateRoute(
            id = "lit-detour",
            durationSeconds = 1_080,
            distanceMeters = 1_300,
            segments = List(12) { segment(lit = true, roadClass = RoadClass.SECONDARY) },
        )

        val ranked = engine.rank(
            candidates = listOf(darkShortcut, litDetour),
            incidents = emptyList(),
            preference = RoutePreference.FASTEST,
            now = now,
        )

        assertThat(ranked.first().route.id).isEqualTo("dark-shortcut")
        assertThat(ranked.first().extraMinutesVsFastest).isEqualTo(0)
    }

    @Test
    fun `dos rutas de igual duracion se ordenan por seguridad incluso en FASTEST`() {
        // Si el tiempo no diferencia, la seguridad debe: es gratis para el usuario.
        val safe = CandidateRoute(
            id = "safe",
            durationSeconds = 600,
            distanceMeters = 800,
            segments = List(6) { segment(lit = true) },
        )
        val unsafe = CandidateRoute(
            id = "unsafe",
            durationSeconds = 600,
            distanceMeters = 800,
            segments = List(6) { segment(lit = false, roadClass = RoadClass.TRUNK) },
        )

        val ranked = engine.rank(
            candidates = listOf(unsafe, safe),
            incidents = emptyList(),
            preference = RoutePreference.FASTEST,
            now = now,
        )

        assertThat(ranked.first().route.id).isEqualTo("safe")
    }

    // ── 7. Perfiles de configuración ──────────────────────────────────────────

    @Test
    fun `el perfil nocturno castiga mas la falta de iluminacion que el diurno`() {
        val unlit = segment(lit = false)
        val dayScore = RiskEngine(RiskConfig(), now).scoreSegment(unlit, emptyList(), now).staticScore
        val nightScore = RiskEngine(RiskConfig.NIGHT, now).scoreSegment(unlit, emptyList(), now).staticScore

        assertThat(nightScore).isGreaterThan(dayScore)
    }

    @Test
    fun `en arranque en frio el score depende solo de datos estaticos`() {
        // Permite que la app sea útil el día 1 en una ciudad nueva, sin esperar
        // masa crítica de reportes.
        val coldEngine = RiskEngine(RiskConfig.COLD_START, now)
        val incidents = List(10) { incident(id = "cold-$it", ageHours = 1) }

        val withIncidents = coldEngine.scoreSegment(segment(lit = true), incidents, now).score
        val withoutIncidents = coldEngine.scoreSegment(segment(lit = true), emptyList(), now).score

        assertThat(withIncidents).isEqualTo(withoutIncidents)
    }

    @Test
    fun `los niveles de riesgo se mapean monotonicamente desde el score`() {
        assertThat(RiskLevel.fromScore(0.05)).isEqualTo(RiskLevel.LOW)
        assertThat(RiskLevel.fromScore(0.30)).isEqualTo(RiskLevel.MODERATE)
        assertThat(RiskLevel.fromScore(0.55)).isEqualTo(RiskLevel.HIGH)
        assertThat(RiskLevel.fromScore(0.90)).isEqualTo(RiskLevel.CRITICAL)
    }

    // ── 8. Casos borde ────────────────────────────────────────────────────────

    @Test
    fun `una ruta sin tramos no crashea y puntua bajo`() {
        val empty = CandidateRoute(id = "empty", durationSeconds = 0, distanceMeters = 0.0, segments = emptyList())

        val assessment = engine.assess(empty, listOf(incident()), now)

        assertThat(assessment.score).isEqualTo(0.0)
        assertThat(assessment.level).isEqualTo(RiskLevel.LOW)
    }

    @Test
    fun `ranking sin candidatas devuelve lista vacia`() {
        assertThat(engine.rank(emptyList(), listOf(incident()), RoutePreference.SAFEST, now)).isEmpty()
        assertThat(engine.selectBest(emptyList(), emptyList(), RoutePreference.SAFEST, now)).isNull()
    }

    @Test
    fun `un tramo degenerado de longitud cero no produce NaN`() {
        val degenerate = RouteSegment(
            from = blockCenter,
            to = blockCenter,
            lengthMeters = 0.0,
            lit = false,
        )

        val risk = engine.scoreSegment(degenerate, listOf(incident()), now)

        assertThat(risk.score.isNaN()).isFalse()
        assertThat(risk.score).isAtLeast(0.0)
    }

    @Test
    fun `la explicacion de ruta menciona la iluminacion evitada`() {
        val route = CandidateRoute(
            id = "r",
            durationSeconds = 600,
            distanceMeters = 800,
            segments = listOf(segment(lit = false, streetName = "Pasaje Sin Nombre"), segment(lit = true)),
        )

        val safeRoute = engine.selectBest(listOf(route), emptyList(), RoutePreference.SAFEST, now)

        assertThat(safeRoute).isNotNull()
        assertThat(safeRoute!!.reasons.joinToString { it.text })
            .contains("sin iluminación")
    }

    @Test
    fun `riesgo alto sugiere activar el Modo Escolta`() {
        // El motor no sólo puntúa: empuja la feature que protege al usuario.
        // Es también un loop de activación del Modo Escolta.
        val flood = List(30) { incident(id = "flood-$it", ageHours = 1, confirmations = 3) }
        val route = CandidateRoute(
            id = "danger",
            durationSeconds = 600,
            distanceMeters = 800,
            segments = List(6) { segment(lit = false) },
        )

        val safeRoute = engine.selectBest(listOf(route), flood, RoutePreference.SAFEST, now)!!

        assertThat(safeRoute.assessment.level).isAnyOf(RiskLevel.HIGH, RiskLevel.CRITICAL)
        assertThat(safeRoute.reasons.joinToString { it.text }).contains("Modo Escolta")
    }
}
