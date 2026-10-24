package com.nomadsafe.app.feature.risk

/**
 * Parámetros del modelo de riesgo.
 *
 * Todo lo que hace el [RiskEngine] se controla desde acá. Es una clase de datos
 * y no constantes dispersas por tres motivos prácticos:
 *
 *  1. **Se puede A/B testear.** Cambiar la ponderación de rutas es una decisión
 *     de producto, no de código. Con esto se puede servir una config distinta
 *     por ciudad sin recompilar.
 *  2. **Se puede calibrar contra datos reales.** Cuando el piloto de Trujillo
 *     acumule reportes y feedback ("esta ruta me pareció peor"), se ajustan los
 *     pesos midiendo, no opinando.
 *  3. **Se puede testear.** Los tests construyen configs extremas para verificar
 *     el comportamiento del motor sin depender de los valores de producción.
 *
 * Los valores por defecto son un punto de partida razonable, no una verdad
 * revelada. Están pensados para peatones en ciudad latinoamericana de densidad
 * media. Ver docs/RISK_ENGINE.md para la derivación de cada uno.
 */
data class RiskConfig(

    // ── Núcleo espacial: cómo se propaga un reporte por el mapa ──────────────
    /**
     * Desvío estándar del kernel gaussiano, en metros.
     *
     * 90 m ≈ una cuadra de Trujillo. Significa que un robo afecta sobre todo a
     * su propia cuadra y a la mitad de las vecinas. Un valor muy chico hace el
     * mapa inútil (sólo la cuadra exacta del hecho es riesgosa); uno muy grande
     * vuelve insegura media ciudad por un solo reporte.
     */
    val spatialSigmaMeters: Double = 90.0,

    /**
     * Radio de corte. Más allá, la contribución de un incidente es 0.
     *
     * Existe por rendimiento, no sólo por modelado: sin corte, cada tramo de
     * ruta evalúa todos los incidentes de la ciudad. Con 400 m el cálculo es
     * O(tramos × incidentes cercanos) y se puede pre-filtrar con PostGIS.
     */
    val maxInfluenceRadiusMeters: Double = 400.0,

    // ── Saturación ───────────────────────────────────────────────────────────
    /**
     * Constante de saturación del score colaborativo.
     *
     * El acumulado de incidentes es una suma no acotada: 20 reportes en una
     * esquina darían un valor enorme que rompería cualquier comparación. Se
     * comprime con `1 - e^(-crowd / k)`, que mapea [0,∞) → [0,1).
     *
     * k = 2.5 implica: 1 reporte grave y cercano ≈ 0.33, 3 ≈ 0.70, 10 ≈ 0.98.
     * O sea, el mapa distingue "un hecho" de "varios", pero no explota.
     */
    val crowdSaturation: Double = 2.5,

    // ── Mezcla de señales ────────────────────────────────────────────────────
    /** Peso de la señal dinámica (reportes de la comunidad). */
    val crowdWeight: Double = 0.70,

    /**
     * Peso de la señal estática (iluminación + tipo de vía, desde OSM).
     *
     * crowdWeight + staticWeight debe sumar 1.0 para que el score final quede
     * naturalmente en [0,1] sin renormalizar.
     */
    val staticWeight: Double = 0.30,

    // ── Señal estática: iluminación ──────────────────────────────────────────
    /** Penalidad por tramo con iluminación ausente confirmada. */
    val unlitPenalty: Double = 0.85,

    /**
     * Penalidad por iluminación DESCONOCIDA.
     *
     * Distinto de `unlitPenalty` a propósito. Al arrancar el producto no hay
     * datos de iluminación de casi ninguna calle, así que `lit == null` es el
     * caso común. Si se penalizara como "oscuro", todas las rutas puntuarían
     * igual de mal y la recomendación no discriminaría nada. Si no se penalizara
     * en absoluto, el sistema asumiría que todo está iluminado, que es la
     * suposición peligrosa. Un valor intermedio expresa la incertidumbre.
     */
    val unknownLightingPenalty: Double = 0.35,

    /** Bonus (negativo = resta riesgo) por tramo con iluminación confirmada. */
    val litBonus: Double = -0.15,

    // ── Agregación por ruta ──────────────────────────────────────────────────
    /**
     * Peso del PEOR tramo en el score de la ruta.
     *
     * Una ruta no es tan segura como su promedio: es tan segura como su peor
     * cuadra. Promediar sin más permitiría que una avenida larga y tranquila
     * esconda un cruce peligroso. Este término fuerza a que el tramo más
     * riesgoso se sienta en el total.
     *
     * OJO: este peso solo NO alcanza. En una ruta larga el promedio ponderado
     * por longitud diluye cualquier tramo individual — con 100 tramos, uno
     * crítico (score 1.0) y 99 seguros dan 0.75·0.01 + 0.25·1.0 = 0.26, o sea
     * riesgo MODERADO. Para eso existe [criticalRiskFloor].
     */
    val worstSegmentWeight: Double = 0.25,

    /**
     * Umbral a partir del cual un tramo individual se considera crítico.
     *
     * **Calibrado contra el modelo real, no contra un número redondo.** El techo
     * del score de tramo es 1.0 (crowd y static saturan cada uno a 1.0), y los
     * valores alcanzables en la práctica son:
     *
     *   - tramo iluminado en calle residencial, sin reportes .......... 0.00
     *   - iluminación desconocida (el caso común al arrancar) ......... 0.11
     *   - un robo reciente sin confirmar en una cuadra normal ......... 0.24
     *   - robo de hace 2 h confirmado por 5 vecinos + calle sin luz en
     *     una vía de tránsito pesado .................................. 0.61
     *
     * Un umbral de 0.75 exigiría ~5 incidentes graves confirmados en la MISMA
     * cuadra: inalcanzable en la práctica, y el piso de seguridad quedaría como
     * código muerto. 0.60 captura el caso de arriba, que es exactamente el tipo
     * de cuadra que uno quiere ver marcada en rojo.
     */
    val criticalSegmentScore: Double = 0.60,

    /**
     * Piso del score de ruta cuando se cruza al menos un tramo crítico.
     *
     * **Garantía de seguridad del modelo.** Una ruta que pasa por una cuadra
     * crítica nunca puede reportarse como tranquila, por más larga y segura que
     * sea el resto. Sin este piso, el promedio por longitud diluye el peligro:
     * cuanto más larga la ruta, más fácil esconder un punto negro, y justamente
     * los recorridos largos son los que exponen más tiempo al usuario.
     *
     * 0.50 cae dentro de la banda HIGH de [com.nomadsafe.app.domain.model.RiskLevel]
     * (que arranca en 0.45), así que la UI siempre mostrará la ruta en rojo y
     * el motor siempre sugerirá activar el Modo Escolta.
     */
    val criticalRiskFloor: Double = 0.50,

    /**
     * Pendiente con la que el piso CRECE según la severidad del peor tramo:
     * ```
     * piso(w) = criticalRiskFloor + (w - criticalSegmentScore) · criticalFloorSlope
     * ```
     *
     * Por qué no un piso plano: con `máx(blended, 0.50)` a secas, TODAS las rutas
     * que cruzan algo crítico colapsan al mismo 0.50 y el ranking pierde el
     * desempate — daría igual cruzar una cuadra complicada que cinco. Con la
     * pendiente, el piso es monótono en la severidad del peor tramo (0.50 en el
     * umbral, 0.90 con un tramo en 1.0) y la discriminación se conserva.
     */
    val criticalFloorSlope: Double = 1.0,

    /**
     * Peso de la EXPOSICIÓN total dentro del piso de seguridad.
     *
     * [criticalFloorSlope] resuelve "qué tan grave es el peor tramo", pero deja
     * un agujero: dos rutas con el MISMO peor tramo y distinto NÚMERO de tramos
     * críticos empatan. Una ruta que cruza 3 cuadras peligrosas expone al usuario
     * tres veces; una que cruza 1, una vez. Empatadas, el ranking no puede
     * preferir la segunda, que es estrictamente mejor.
     *
     * Se usa la fracción de LONGITUD en tramos críticos (no el conteo), para que
     * no dé igual cruzar 3 pasajes de 20 m que 3 avenidas de 400 m.
     *
     * Con 0.25: 1 tramo crítico de 100 → +0.003; 3 de 100 → +0.008; 1 de 5
     * tramos (ruta corta que atraviesa el peligro) → +0.05. La discriminación
     * aparece justo donde importa: recorridos cortos por zona mala.
     */
    val criticalExposureWeight: Double = 0.25,

    // ── Preferencia del usuario: seguridad contra tiempo ─────────────────────
    /** En SAFEST, cuánto cuesta cada unidad de tiempo extra normalizado. */
    val safestTimePenalty: Double = 0.10,

    /** En BALANCED, peso del riesgo (el resto es tiempo). */
    val balancedRiskWeight: Double = 0.65,

    /** En FASTEST, cuánto riesgo se tolera por ahorrar tiempo. */
    val fastestRiskWeight: Double = 0.15,

    // ── Clustering de zonas calientes ────────────────────────────────────────
    /** Radio de agrupamiento para detectar hotspots en el mapa. */
    val hotspotRadiusMeters: Double = 150.0,

    /** Mínimo de incidentes para que un grupo se considere hotspot. */
    val hotspotMinIncidents: Int = 3,
) {
    init {
        require(crowdWeight + staticWeight == 1.0) {
            "crowdWeight + staticWeight debe sumar 1.0 (suman ${crowdWeight + staticWeight})"
        }
        require(spatialSigmaMeters > 0) { "spatialSigmaMeters debe ser positivo" }
        require(crowdSaturation > 0) { "crowdSaturation debe ser positivo" }
        require(worstSegmentWeight in 0.0..1.0) { "worstSegmentWeight debe estar en [0,1]" }
        require(criticalSegmentScore in 0.0..1.0) { "criticalSegmentScore debe estar en [0,1]" }
        require(criticalRiskFloor in 0.0..criticalSegmentScore) {
            "criticalRiskFloor ($criticalRiskFloor) no puede superar a criticalSegmentScore " +
                "($criticalSegmentScore) o el piso inflaría rutas que no cruzan nada crítico"
        }
        require(criticalFloorSlope >= 0.0) { "criticalFloorSlope debe ser >= 0 (piso monótono creciente)" }
        require(criticalExposureWeight >= 0.0) { "criticalExposureWeight debe ser >= 0" }
        require(maxInfluenceRadiusMeters >= spatialSigmaMeters) {
            "maxInfluenceRadiusMeters debe ser >= spatialSigmaMeters o el kernel se trunca antes de decaer"
        }
    }

    companion object {
        /**
         * Perfil conservador: ante la duda, asume más peligro.
         *
         * Para modo nocturno o para usuarias que priorizan seguridad sobre
         * tiempo de forma explícita. Sube el peso de la iluminación, que es la
         * señal que más correlaciona con percepción de inseguridad nocturna.
         */
        val NIGHT = RiskConfig(
            crowdWeight = 0.55,
            staticWeight = 0.45,
            unlitPenalty = 0.95,
            unknownLightingPenalty = 0.55,
            litBonus = -0.25,
            crowdSaturation = 2.0,
        )

        /**
         * Perfil de arranque en frío, para ciudades sin reportes todavía.
         *
         * Con `crowdWeight = 0` el score depende sólo de datos estáticos de OSM.
         * Es lo que permite que la app sea útil el día 1 en una ciudad nueva,
         * en vez de mostrar un mapa vacío hasta que llegue masa crítica.
         */
        val COLD_START = RiskConfig(crowdWeight = 0.0, staticWeight = 1.0)
    }
}
