package com.nomadsafe.app.feature.map

import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.dsl.generated.expressions
import com.mapbox.maps.extension.style.layers.CircleLayer
import com.mapbox.maps.extension.style.layers.HeatmapLayer
import com.mapbox.maps.extension.style.layers.properties.generated.CirclePitchScale
import com.mapbox.maps.extension.style.layers.properties.generated.HeatmapUnit
import com.mapbox.maps.extension.style.sources.GeoJsonSource
import com.mapbox.maps.extension.style.sources.addSource
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.IncidentCategory
import com.nomadsafe.app.domain.model.RoadClass
import com.nomadsafe.app.domain.model.RouteSegment
import com.nomadsafe.app.feature.risk.Hotspot
import com.nomadsafe.app.feature.risk.HotspotSeverity

/**
 * Construcción de capas de Mapbox para NomadSafe.
 *
 * ── Todo el código específico de Mapbox vive acá ─────────────────────────────
 * Igual que con Supabase: confinar la dependencia de un proveedor en un archivo
 * es gestión de riesgo. El SDK de Mapbox v11 cambió varias veces de API entre
 * 11.0 y 11.33 (los builders de expresiones, sobre todo). Si algo no compila en la
 * versión que resuelva Gradle, se ajusta acá y en ningún otro lugar.
 *
 * ── GeoJSON en vez de tiles propios ──────────────────────────────────────────
 * Se publican FeatureCollections al estilo en runtime y no un tile server propio.
 * Para el volumen del piloto (cientos de incidentes en el viewport) es más simple
 * y suficientemente rápido. El límite práctico está alrededor de 5.000 features
 * por capa; más allá conviene servir tiles vectoriales desde PostGIS
 * (`ST_AsMVT`), que es el camino documentado en docs/ARCHITECTURE.md.
 */
object MapboxLayers {

    const val SOURCE_INCIDENTS = "nomadsafe-incidents"
    const val SOURCE_HOTSPOTS = "nomadsafe-hotspots"
    const val SOURCE_ROUTE = "nomadsafe-route"
    const val SOURCE_TRAIL = "nomadsafe-trail"

    const val LAYER_HEATMAP = "layer-heatmap"
    const val LAYER_HOTSPOTS = "layer-hotspots"
    const val LAYER_INCIDENTS = "layer-incidents"
    const val LAYER_ROUTE = "layer-route"
    const val LAYER_ROUTE_CASING = "layer-route-casing"
    const val LAYER_TRAIL = "layer-trail"

    /** Paleta de riesgo, coherente con core/designsystem/NomadSafeTheme.kt. */
    private const val COLOR_LOW = "#34C759"
    private const val COLOR_MODERATE = "#FFB020"
    private const val COLOR_HIGH = "#FF6B35"
    private const val COLOR_CRITICAL = "#FF3B30"
    private const val COLOR_ROUTE = "#0F6FFF"
    private const val COLOR_ESCORT = "#00D3A7"

    private const val PROPERTY_SEVERITY = "severity"
    private const val PROPERTY_WEIGHT = "weight"
    private const val PROPERTY_CATEGORY = "category"
    private const val PROPERTY_STATUS = "status"

    // ── Capa de calor ─────────────────────────────────────────────────────────

    /**
     * Heatmap de incidentes ponderado por severidad.
     *
     * ── El detalle que importa ────────────────────────────────────────────────
     * El `heatmap-weight` NO es constante: usa la severidad de la categoría y el
     * multiplicador de consenso calculado en el dominio.
     *
     * Un heatmap con peso uniforme es engañoso de una forma específica y grave:
     * muestra 20 reportes de "persona sospechosa" como una mancha roja enorme y un
     * solo asalto confirmado como un punto débil. Eso invierte exactamente la
     * señal que el usuario necesita. Ponderar por severidad hace que el mapa
     * muestre lo mismo que el motor de rutas considera peligroso.
     *
     * `heatmap-radius` en PÍXELES y no en metros: en metros los círculos se
     * solapan tanto al alejar el zoom que todo el mapa queda rojo y se pierde la
     * capacidad de distinguir zonas.
     */
    fun buildHeatmapLayer(sourceId: String = SOURCE_INCIDENTS): HeatmapLayer =
        HeatmapLayer(LAYER_HEATMAP, sourceId).apply {
            heatmapWeight(
                expressions {
                    // severity viene precalculada en la feature (0..1.5).
                    get(PROPERTY_WEIGHT)
                },
            )
            heatmapIntensity(
                expressions {
                    // Se atenúa al alejar el zoom: a nivel ciudad el detalle por
                    // cuadra no tiene sentido y saturaría la pantalla.
                    interpolate {
                        zoom()
                        stop { literal(10); literal(0.5) }
                        stop { literal(14); literal(1.0) }
                        stop { literal(18); literal(1.4) }
                    }
                },
            )
            heatmapRadius(
                expressions {
                    interpolate {
                        zoom()
                        stop { literal(10); literal(8) }
                        stop { literal(14); literal(28) }
                        stop { literal(18); literal(46) }
                    }
                },
            )
            heatmapColor(
                expressions {
                    interpolate {
                        linear()
                        heatmapDensity()
                        // Transparente en el centro de baja densidad: sin esto el
                        // heatmap pinta toda la ciudad de un color tenue y el mapa
                        // base deja de leerse.
                        stop { literal(0.0); rgba(0, 0, 0, 0) }
                        stop { literal(0.2); color(COLOR_LOW) }
                        stop { literal(0.45); color(COLOR_MODERATE) }
                        stop { literal(0.7); color(COLOR_HIGH) }
                        stop { literal(1.0); color(COLOR_CRITICAL) }
                    }
                },
            )
            heatmapOpacity(
                expressions {
                    // El heatmap se desvanece al hacer zoom máximo, donde los puntos
                    // individuales ya son más informativos que la mancha.
                    interpolate {
                        zoom()
                        stop { literal(15); literal(0.75) }
                        stop { literal(18); literal(0.25) }
                    }
                },
            )
            heatmapUnit(HeatmapUnit.PIXELS)
        }

    // ── Puntos de incidente ───────────────────────────────────────────────────

    /**
     * Círculos individuales por incidente.
     *
     * El radio escala con la severidad y la opacidad baja con el estado de
     * moderación: un reporte disputado se ve tenue, no con el mismo peso visual que
     * uno confirmado. Es la representación visual del mecanismo anti-abuso — si un
     * reporte falso se viera idéntico a uno verificado, disputarlo no tendría
     * ningún efecto perceptible y la comunidad dejaría de hacerlo.
     */
    fun buildIncidentsLayer(sourceId: String = SOURCE_INCIDENTS): CircleLayer =
        CircleLayer(LAYER_INCIDENTS, sourceId).apply {
            circleRadius(
                expressions {
                    interpolate {
                        linear()
                        get(PROPERTY_SEVERITY)
                        stop { literal(0.0); literal(4.0) }
                        stop { literal(0.5); literal(7.0) }
                        stop { literal(1.0); literal(11.0) }
                    }
                },
            )
            circleColor(
                expressions {
                    match {
                        get(PROPERTY_CATEGORY)
                        stop { literal(IncidentCategory.ASSAULT.wireName); color(COLOR_CRITICAL) }
                        stop { literal(IncidentCategory.THEFT.wireName); color(COLOR_HIGH) }
                        stop { literal(IncidentCategory.HARASSMENT.wireName); color(COLOR_HIGH) }
                        stop { literal(IncidentCategory.DARK_STREET.wireName); color(COLOR_MODERATE) }
                        stop { literal(IncidentCategory.SUSPICIOUS.wireName); color(COLOR_MODERATE) }
                        fallback { color(COLOR_LOW) }
                    }
                },
            )
            circleOpacity(
                expressions {
                    match {
                        get(PROPERTY_STATUS)
                        stop { literal("confirmed"); literal(1.0) }
                        stop { literal("active"); literal(0.85) }
                        stop { literal("under_review"); literal(0.45) }
                        stop { literal("disputed"); literal(0.25) }
                        fallback { literal(0.85) }
                    }
                },
            )
            circleStrokeWidth(1.5)
            circleStrokeColor(rgba(255, 255, 255, 255))
            circleStrokeOpacity(0.9)
        }

    // ── Hotspots ──────────────────────────────────────────────────────────────

    /**
     * Círculos de zona caliente, con radio proporcional a la extensión real del grupo.
     *
     * Se dibujan DEBAJO de los puntos individuales: el hotspot da el contexto de
     * zona y los puntos el detalle. Al revés, los puntos quedarían tapados.
     */
    fun buildHotspotsLayer(sourceId: String = SOURCE_HOTSPOTS): CircleLayer =
        CircleLayer(LAYER_HOTSPOTS, sourceId).apply {
            // El radio viene en la feature, en metros, y se usa con escala por pitch
            // para que respete la perspectiva del mapa.
            circleRadius(
                expressions {
                    toNumber(get(PROPERTY_WEIGHT))
                },
            )
            circleColor(
                expressions {
                    match {
                        get(PROPERTY_SEVERITY)
                        stop { literal(HotspotSeverity.AVOID.name); color(COLOR_CRITICAL) }
                        stop { literal(HotspotSeverity.CAUTION.name); color(COLOR_HIGH) }
                        fallback { color(COLOR_MODERATE) }
                    }
                },
            )
            circleOpacity(0.18)
            circleStrokeWidth(2.0)
            circleStrokeColor(
                expressions {
                    match {
                        get(PROPERTY_SEVERITY)
                        stop { literal(HotspotSeverity.AVOID.name); color(COLOR_CRITICAL) }
                        stop { literal(HotspotSeverity.CAUTION.name); color(COLOR_HIGH) }
                        fallback { color(COLOR_MODERATE) }
                    }
                },
            )
            circleStrokeOpacity(0.55)
            circlePitchScale(CirclePitchScale.MAP)
        }

    // ── Ruta segura ───────────────────────────────────────────────────────────

    /**
     * Línea de la ruta recomendada, con casing blanco debajo.
     *
     * El casing no es decorativo: sobre un mapa con manchas de heatmap rojas y
     * naranjas, una línea azul de 5 px se pierde. El borde blanco la separa del
     * fondo y la hace legible en cualquier zona de riesgo, que es precisamente
     * donde más se necesita verla.
     */
    fun buildRouteCasingLayer(sourceId: String = SOURCE_ROUTE): com.mapbox.maps.extension.style.layers.LineLayer =
        com.mapbox.maps.extension.style.layers.LineLayer(LAYER_ROUTE_CASING, sourceId).apply {
            lineWidth(9.0)
            lineColor(rgba(255, 255, 255, 255))
            lineOpacity(0.9)
            lineCap(com.mapbox.maps.extension.style.layers.properties.generated.LineCap.ROUND)
            lineJoin(com.mapbox.maps.extension.style.layers.properties.generated.LineJoin.ROUND)
        }

    fun buildRouteLayer(sourceId: String = SOURCE_ROUTE): com.mapbox.maps.extension.style.layers.LineLayer =
        com.mapbox.maps.extension.style.layers.LineLayer(LAYER_ROUTE, sourceId).apply {
            lineWidth(5.0)
            lineColor(
                expressions {
                    match {
                        get(PROPERTY_SEVERITY)
                        stop { literal("low"); color(COLOR_LOW) }
                        stop { literal("moderate"); color(COLOR_MODERATE) }
                        stop { literal("high"); color(COLOR_HIGH) }
                        fallback { color(COLOR_ROUTE) }
                    }
                },
            )
            lineOpacity(0.95)
            lineCap(com.mapbox.maps.extension.style.layers.properties.generated.LineCap.ROUND)
            lineJoin(com.mapbox.maps.extension.style.layers.properties.generated.LineJoin.ROUND)
        }

    /** Traza recorrida durante el Modo Escolta, en verde para distinguirla de la ruta. */
    fun buildTrailLayer(sourceId: String = SOURCE_TRAIL): com.mapbox.maps.extension.style.layers.LineLayer =
        com.mapbox.maps.extension.style.layers.LineLayer(LAYER_TRAIL, sourceId).apply {
            lineWidth(4.0)
            lineColor(
                expressions {
                    color(COLOR_ESCORT)
                },
            )
            lineOpacity(0.9)
            lineCap(com.mapbox.maps.extension.style.layers.properties.generated.LineCap.ROUND)
        }

    // ── Construcción de FeatureCollections ────────────────────────────────────

    /**
     * Incidentes → FeatureCollection.
     *
     * Las propiedades que se adjuntan son exactamente las que consumen las
     * expresiones de las capas de arriba. Agregar una propiedad acá sin usarla en
     * ninguna expresión sólo infla el payload GeoJSON que cruza el puente JNI hacia
     * el renderer nativo, que es caro.
     */
    fun incidentsToFeatureCollection(incidents: List<Incident>): FeatureCollection {
        val features = incidents.mapNotNull { incident ->
            val point = Point.fromLngLat(incident.location.longitude, incident.location.latitude)

            Feature.fromGeometry(
                point,
                com.google.gson.JsonObject().apply {
                    addProperty(PROPERTY_CATEGORY, incident.category.wireName)
                    addProperty(PROPERTY_STATUS, incident.status.wireName)
                    addProperty(PROPERTY_SEVERITY, incident.effectiveSeverity)
                    addProperty(PROPERTY_WEIGHT, incident.effectiveSeverity)
                    addProperty("incident_id", incident.id)
                    addProperty("occurred_at", incident.occurredAt.toString())
                    incident.neighborhood?.let { addProperty("neighborhood", it) }
                    incident.description?.take(MAX_DESCRIPTION_LENGTH)?.let { addProperty("description", it) }
                },
            )
        }
        return FeatureCollection.fromFeatures(features)
    }

    fun hotspotsToFeatureCollection(hotspots: List<Hotspot>): FeatureCollection {
        val features = hotspots.map { hotspot ->
            Feature.fromGeometry(
                Point.fromLngLat(hotspot.center.longitude, hotspot.center.latitude),
                com.google.gson.JsonObject().apply {
                    addProperty(PROPERTY_SEVERITY, hotspot.severity.name)
                    addProperty(PROPERTY_WEIGHT, hotspot.radiusMeters)
                    addProperty("risk_score", hotspot.riskScore)
                    addProperty("count", hotspot.incidentCount)
                    addProperty("dominant_category", hotspot.dominantCategory.wireName)
                },
            )
        }
        return FeatureCollection.fromFeatures(features)
    }

    /**
     * Tramos de ruta → LineString con severidad por tramo.
     *
     * Se emite un LineString por tramo y no uno solo para toda la ruta: así cada
     * tramo puede pintarse de su propio color según su riesgo, que es lo que hace
     * que la ruta "explique" visualmente por dónde es peligrosa. Una línea de color
     * uniforme ocultaría justamente la información que el motor calculó.
     */
    fun routeToFeatureCollection(
        segments: List<RouteSegment>,
        segmentScores: List<Double>,
    ): FeatureCollection {
        val features = segments.mapIndexed { index, segment ->
            val score = segmentScores.getOrNull(index) ?: 0.0
            Feature.fromGeometry(
                com.mapbox.geojson.LineString.fromLngLats(
                    listOf(
                        Point.fromLngLat(segment.from.longitude, segment.from.latitude),
                        Point.fromLngLat(segment.to.longitude, segment.to.latitude),
                    ),
                ),
                com.google.gson.JsonObject().apply {
                    addProperty(PROPERTY_SEVERITY, severityLabel(score))
                    addProperty("score", score)
                    addProperty("lit", segment.lit)
                    addProperty("road_class", segment.roadClass.wireName)
                    segment.streetName?.let { addProperty("street", it) }
                },
            )
        }
        return FeatureCollection.fromFeatures(features)
    }

    fun trailToFeatureCollection(points: List<com.nomadsafe.app.domain.model.GeoPoint>): FeatureCollection {
        if (points.size < 2) return FeatureCollection.fromFeatures(emptyList())

        val lineString = com.mapbox.geojson.LineString.fromLngLats(
            points.map { Point.fromLngLat(it.longitude, it.latitude) },
        )
        return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(lineString)))
    }

    private fun severityLabel(score: Double): String = when {
        score < 0.20 -> "low"
        score < 0.45 -> "moderate"
        score < 0.70 -> "high"
        else -> "critical"
    }

    /** Agrega una fuente GeoJson al estilo, o la reemplaza si ya existe. */
    fun ensureSource(style: Style, sourceId: String, initial: FeatureCollection = FeatureCollection.fromFeatures(emptyList())) {
        if (style.styleSourceExists(sourceId)) {
            updateSource(style, sourceId, initial)
            return
        }
        style.addSource(GeoJsonSource.Builder(sourceId).data(initial).build())
    }

    /** Actualiza los datos de una fuente existente. */
    fun updateSource(style: Style, sourceId: String, data: FeatureCollection) {
        runCatching {
            style.getSourceAs<GeoJsonSource>(sourceId)?.data(data)
        }.onFailure { NsLog.w(TAG, "No se pudo actualizar la fuente $sourceId: ${it.message}") }
    }

    /** Agrega una capa si no existe, en la posición indicada (o al final). */
    fun ensureLayer(style: Style, layer: com.mapbox.maps.extension.style.layers.GeneratedLayer, belowLayerId: String? = null) {
        if (style.styleLayerExists(layer.layerId)) return
        runCatching {
            if (belowLayerId != null && style.styleLayerExists(belowLayerId)) {
                style.addLayerBelow(layer, belowLayerId)
            } else {
                style.addLayer(layer)
            }
        }.onFailure { NsLog.e(TAG, "No se pudo agregar la capa ${layer.layerId}", it) }
    }

    fun setLayerVisibility(style: Style, layerId: String, visible: Boolean) {
        runCatching {
            style.getStyleLayer(layerId)?.setProperties(
                expressions {
                    visibility(if (visible) "visible" else "none")
                },
            )
        }
    }

    /**
     * Orden correcto de apilado de las capas.
     *
     * De abajo hacia arriba: heatmap (contexto difuso), hotspots (zonas),
     * casing de ruta, ruta, traza de escolta, puntos de incidente (detalle).
     * Invertir este orden tapa la información más precisa con la más difusa.
     */
    val LAYER_STACK_ORDER = listOf(
        LAYER_HEATMAP,
        LAYER_HOTSPOTS,
        LAYER_ROUTE_CASING,
        LAYER_ROUTE,
        LAYER_TRAIL,
        LAYER_INCIDENTS,
    )

    private const val TAG = "MapboxLayers"
    private const val MAX_DESCRIPTION_LENGTH = 200
}

/** Clasificación de vía útil para leyendas del mapa. */
fun RoadClass.displayName(): String = when (this) {
    RoadClass.PEDESTRIAN -> "Peatonal"
    RoadClass.RESIDENTIAL -> "Calle residencial"
    RoadClass.SECONDARY -> "Colectora"
    RoadClass.PRIMARY -> "Avenida"
    RoadClass.TRUNK -> "Vía rápida"
    RoadClass.UNKNOWN -> "Clasificación desconocida"
}
