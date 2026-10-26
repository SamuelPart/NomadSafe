package com.nomadsafe.app.feature.map

import com.google.gson.JsonObject
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.IncidentCategory
import com.nomadsafe.app.domain.model.RoadClass
import com.nomadsafe.app.domain.model.RouteSegment
import com.nomadsafe.app.feature.risk.Hotspot
import com.nomadsafe.app.feature.risk.HotspotSeverity
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Construcción de capas de MapLibre para NomadSafe.
 *
 * ── Por qué MapLibre y no Mapbox ──────────────────────────────────────────────
 * MapLibre GL Native es el fork open source (licencia BSD) del render de Mapbox.
 * La diferencia operativa que importa acá: **no requiere cuenta ni token de ningún
 * tipo**. El SDK vive en Maven Central y los tiles se piden directo a
 * OpenStreetMap. Con el SDK propietario, en cambio, la resolución de dependencias
 * exigía un "secret downloads token" contra un Maven privado, que era un punto
 * único de fallo para cualquier clone fresco del repo y una dependencia de un
 * servicio con planes pagos.
 *
 * El costo aceptado: MapLibre no trae los estilos vectoriales curados de Mapbox,
 * así que el mapa base es raster OSM. Para el MVP eso es correcto — los datos que
 * diferencian al producto (heatmap, hotspots, rutas) son capas nuestras encima —
 * y si más adelante se quiere un base map vectorial, se apunta a un estilo
 * libre (p. ej. OpenFreeMap) cambiando UNA línea en [baseRasterSource].
 *
 * ── Todo el código específico del SDK vive acá ────────────────────────────────
 * Igual que con Supabase: confinar la dependencia de un proveedor en un archivo
 * es gestión de riesgo. Si una API de MapLibre cambia, se ajusta acá y en ningún
 * otro lugar.
 */
object MapLayers {

    const val SOURCE_BASE = "nomadsafe-base-osm"
    const val SOURCE_INCIDENTS = "nomadsafe-incidents"
    const val SOURCE_HOTSPOTS = "nomadsafe-hotspots"
    const val SOURCE_ROUTE = "nomadsafe-route"
    const val SOURCE_TRAIL = "nomadsafe-trail"

    const val LAYER_BASE = "layer-base-osm"
    const val LAYER_HEATMAP = "layer-heatmap"
    const val LAYER_HOTSPOTS = "layer-hotspots"
    const val LAYER_INCIDENTS = "layer-incidents"
    const val LAYER_ROUTE = "layer-route"
    const val LAYER_ROUTE_CASING = "layer-route-casing"
    const val LAYER_TRAIL = "layer-trail"

    /**
     * Tiles raster de OpenStreetMap.
     *
     * Se usa raster y no vectorial a propósito: un base map vectorial exigiría
     * elegir un proveedor de tiles con su propio esquema de nombres de capas, y
     * acoplaría nuestro código a ese esquema. Con raster, el fondo es opaco a
     * nuestros cambios y las capas analíticas (que son el producto) viven encima
     * como GeoJSON propio.
     *
     * La política de uso de tile.openstreetmap.org pide un User-Agent identificable
     * y volumen moderado; para un piloto de una ciudad está dentro de lo aceptado.
     * Si el volumen creciera, el camino es un tile server propio, no un proveedor
     * pago.
     */
    fun baseRasterSource(): RasterSource = RasterSource(
        SOURCE_BASE,
        TileSet("2.1.0", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
        TILE_SIZE,
    )

    fun baseRasterLayer(): RasterLayer = RasterLayer(LAYER_BASE, SOURCE_BASE)

    // ── Paleta de riesgo, coherente con core/designsystem/NomadSafeTheme.kt ────
    private val RGB_LOW = intArrayOf(52, 199, 89)
    private val RGB_MODERATE = intArrayOf(255, 176, 32)
    private val RGB_HIGH = intArrayOf(255, 107, 53)
    private val RGB_CRITICAL = intArrayOf(255, 59, 48)
    private val RGB_ROUTE = intArrayOf(15, 111, 255)
    private val RGB_ESCORT = intArrayOf(0, 211, 167)

    private const val PROPERTY_SEVERITY = "severity"
    private const val PROPERTY_WEIGHT = "weight"
    private const val PROPERTY_CATEGORY = "category"
    private const val PROPERTY_STATUS = "status"
    private const val PROPERTY_INCIDENT_ID = "incident_id"

    private const val TILE_SIZE = 256

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
     * El radio va en PÍXELES y no en metros: en metros los círculos se solapan
     * tanto al alejar el zoom que todo el mapa queda rojo y se pierde la capacidad
     * de distinguir zonas.
     */
    fun buildHeatmapLayer(): HeatmapLayer =
        HeatmapLayer(LAYER_HEATMAP, SOURCE_INCIDENTS).withProperties(
            PropertyFactory.heatmapWeight(Expression.get(PROPERTY_WEIGHT)),
            PropertyFactory.heatmapIntensity(
                // Se atenúa al alejar el zoom: a nivel ciudad el detalle por cuadra
                // no tiene sentido y saturaría la pantalla.
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(10, 0.5),
                    Expression.stop(14, 1.0),
                    Expression.stop(18, 1.4),
                ),
            ),
            PropertyFactory.heatmapRadius(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(10, 8),
                    Expression.stop(14, 28),
                    Expression.stop(18, 46),
                ),
            ),
            PropertyFactory.heatmapColor(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.heatmapDensity(),
                    // Transparente en densidad baja: sin esto el heatmap pinta toda
                    // la ciudad de un color tenue y el mapa base deja de leerse.
                    Expression.stop(0.0, Expression.rgba(0, 0, 0, 0)),
                    Expression.stop(0.2, color(RGB_LOW)),
                    Expression.stop(0.45, color(RGB_MODERATE)),
                    Expression.stop(0.7, color(RGB_HIGH)),
                    Expression.stop(1.0, color(RGB_CRITICAL)),
                ),
            ),
            PropertyFactory.heatmapOpacity(
                // El heatmap se desvanece al hacer zoom máximo, donde los puntos
                // individuales ya son más informativos que la mancha.
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(15, 0.75),
                    Expression.stop(18, 0.25),
                ),
            ),
        )

    // ── Puntos de incidente ───────────────────────────────────────────────────

    /**
     * Círculos individuales por incidente.
     *
     * El radio escala con la severidad y la opacidad baja con el estado de
     * moderación: un reporte disputado se ve tenue, no con el mismo peso visual
     * que uno confirmado. Es la representación visual del mecanismo anti-abuso —
     * si un reporte falso se viera idéntico a uno verificado, disputarlo no
     * tendría ningún efecto perceptible y la comunidad dejaría de hacerlo.
     */
    fun buildIncidentsLayer(): CircleLayer =
        CircleLayer(LAYER_INCIDENTS, SOURCE_INCIDENTS).withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.get(PROPERTY_SEVERITY),
                    Expression.stop(0.0, 4.0),
                    Expression.stop(0.5, 7.0),
                    Expression.stop(1.0, 11.0),
                ),
            ),
            PropertyFactory.circleColor(categoryColorExpression()),
            PropertyFactory.circleOpacity(statusOpacityExpression()),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleStrokeColor(Expression.rgba(255, 255, 255, 255)),
            PropertyFactory.circleStrokeOpacity(0.9f),
        )

    private fun categoryColorExpression(): Expression = Expression.match(
        Expression.get(PROPERTY_CATEGORY),
        color(RGB_LOW),
        Expression.stop(IncidentCategory.ASSAULT.wireName, color(RGB_CRITICAL)),
        Expression.stop(IncidentCategory.THEFT.wireName, color(RGB_HIGH)),
        Expression.stop(IncidentCategory.HARASSMENT.wireName, color(RGB_HIGH)),
        Expression.stop(IncidentCategory.DARK_STREET.wireName, color(RGB_MODERATE)),
        Expression.stop(IncidentCategory.SUSPICIOUS.wireName, color(RGB_MODERATE)),
    )

    private fun statusOpacityExpression(): Expression = Expression.match(
        Expression.get(PROPERTY_STATUS),
        Expression.literal(0.85),
        Expression.stop("confirmed", Expression.literal(1.0)),
        Expression.stop("active", Expression.literal(0.85)),
        Expression.stop("under_review", Expression.literal(0.45)),
        Expression.stop("disputed", Expression.literal(0.25)),
    )

    // ── Hotspots ──────────────────────────────────────────────────────────────

    /**
     * Círculos de zona caliente, con radio proporcional a la extensión real del grupo.
     *
     * Se dibujan DEBAJO de los puntos individuales: el hotspot da el contexto de
     * zona y los puntos el detalle. Al revés, los puntos quedarían tapados.
     */
    fun buildHotspotsLayer(): CircleLayer =
        CircleLayer(LAYER_HOTSPOTS, SOURCE_HOTSPOTS).withProperties(
            // El radio viene en la feature, en metros.
            PropertyFactory.circleRadius(Expression.toNumber(Expression.get(PROPERTY_WEIGHT))),
            PropertyFactory.circleColor(severityFillExpression()),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleStrokeWidth(2.0f),
            PropertyFactory.circleStrokeColor(severityFillExpression()),
            PropertyFactory.circleStrokeOpacity(0.55f),
        )

    private fun severityFillExpression(): Expression = Expression.match(
        Expression.get(PROPERTY_SEVERITY),
        color(RGB_MODERATE),
        Expression.stop(HotspotSeverity.AVOID.name, color(RGB_CRITICAL)),
        Expression.stop(HotspotSeverity.CAUTION.name, color(RGB_HIGH)),
    )

    // ── Ruta segura ───────────────────────────────────────────────────────────

    /**
     * Línea de la ruta recomendada, con casing blanco debajo.
     *
     * El casing no es decorativo: sobre un mapa con manchas de heatmap rojas y
     * naranjas, una línea azul de 5 px se pierde. El borde blanco la separa del
     * fondo y la hace legible en cualquier zona de riesgo, que es precisamente
     * donde más se necesita verla.
     */
    fun buildRouteCasingLayer(): LineLayer =
        LineLayer(LAYER_ROUTE_CASING, SOURCE_ROUTE).withProperties(
            PropertyFactory.lineWidth(9.0f),
            PropertyFactory.lineColor(Expression.rgba(255, 255, 255, 255)),
            PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )

    fun buildRouteLayer(): LineLayer =
        LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
            PropertyFactory.lineWidth(5.0f),
            PropertyFactory.lineColor(
                Expression.match(
                    Expression.get(PROPERTY_SEVERITY),
                    color(RGB_ROUTE),
                    Expression.stop("low", color(RGB_LOW)),
                    Expression.stop("moderate", color(RGB_MODERATE)),
                    Expression.stop("high", color(RGB_HIGH)),
                    Expression.stop("critical", color(RGB_CRITICAL)),
                ),
            ),
            PropertyFactory.lineOpacity(0.95f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )

    /** Traza recorrida durante el Modo Escolta, en verde para distinguirla de la ruta. */
    fun buildTrailLayer(): LineLayer =
        LineLayer(LAYER_TRAIL, SOURCE_TRAIL).withProperties(
            PropertyFactory.lineWidth(4.0f),
            PropertyFactory.lineColor(color(RGB_ESCORT)),
            PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        )

    // ── Construcción de FeatureCollections ────────────────────────────────────

    /**
     * Incidentes → FeatureCollection.
     *
     * Las propiedades que se adjuntan son exactamente las que consumen las
     * expresiones de las capas de arriba, más [PROPERTY_INCIDENT_ID] que consume
     * el click-to-select de la pantalla. Agregar una propiedad acá sin que nadie
     * la lea sólo infla el payload GeoJSON que cruza al renderer nativo.
     */
    fun incidentsToFeatureCollection(incidents: List<Incident>): FeatureCollection {
        val features = incidents.mapNotNull { incident ->
            val point = Point.fromLngLat(incident.location.longitude, incident.location.latitude)

            Feature.fromGeometry(
                point,
                JsonObject().apply {
                    addProperty(PROPERTY_CATEGORY, incident.category.wireName)
                    addProperty(PROPERTY_STATUS, incident.status.wireName)
                    addProperty(PROPERTY_SEVERITY, incident.effectiveSeverity)
                    addProperty(PROPERTY_WEIGHT, incident.effectiveSeverity)
                    addProperty(PROPERTY_INCIDENT_ID, incident.id)
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
                JsonObject().apply {
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
     * que la ruta "explique" visualmente por dónde es peligrosa. Una línea de
     * color uniforme ocultaría justamente la información que el motor calculó.
     */
    fun routeToFeatureCollection(
        segments: List<RouteSegment>,
        segmentScores: List<Double>,
    ): FeatureCollection {
        val features = segments.mapIndexed { index, segment ->
            val score = segmentScores.getOrNull(index) ?: 0.0
            Feature.fromGeometry(
                LineString.fromLngLats(
                    listOf(
                        Point.fromLngLat(segment.from.longitude, segment.from.latitude),
                        Point.fromLngLat(segment.to.longitude, segment.to.latitude),
                    ),
                ),
                JsonObject().apply {
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

    fun trailToFeatureCollection(points: List<GeoPoint>): FeatureCollection {
        if (points.size < 2) return FeatureCollection.fromFeatures(emptyList())

        val lineString = LineString.fromLngLats(
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

    private fun color(rgb: IntArray): Expression = Expression.rgb(rgb[0], rgb[1], rgb[2])

    /**
     * Actualiza los datos de una fuente GeoJSON existente.
     *
     * Devuelve false si la fuente todavía no existe (el estilo aún no cargó), para
     * que el llamador pueda reintentar en el siguiente emit en vez de crashear.
     */
    fun updateSource(
        style: org.maplibre.android.maps.Style,
        sourceId: String,
        data: FeatureCollection,
    ): Boolean {
        val source = style.getSourceAs<GeoJsonSource>(sourceId) ?: return false
        return runCatching { source.setFeatureCollection(data) }
            .onFailure { NsLog.w(TAG, "No se pudo actualizar la fuente $sourceId: ${it.message}") }
            .isSuccess
    }

    /** Agrega una fuente GeoJSON vacía si no existe todavía. */
    fun ensureGeoJsonSource(style: org.maplibre.android.maps.Style, sourceId: String) {
        if (style.getSource(sourceId) != null) return
        style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(emptyList())))
    }

    /** Agrega una capa si no existe, en la posición indicada (o al final). */
    fun ensureLayer(
        style: org.maplibre.android.maps.Style,
        layer: org.maplibre.android.style.layers.Layer,
        belowLayerId: String? = null,
    ) {
        if (style.getLayer(layer.id) != null) return
        runCatching {
            if (belowLayerId != null && style.getLayer(belowLayerId) != null) {
                style.addLayerBelow(layer, belowLayerId)
            } else {
                style.addLayer(layer)
            }
        }.onFailure { NsLog.e(TAG, "No se pudo agregar la capa ${layer.id}", it) }
    }

    fun setLayerVisibility(style: org.maplibre.android.maps.Style, layerId: String, visible: Boolean) {
        val layer = style.getLayer(layerId) ?: return
        runCatching {
            layer.setProperties(
                PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE),
            )
        }
    }

    /**
     * Orden correcto de apilado de las capas.
     *
     * De abajo hacia arriba: base raster, heatmap (contexto difuso), hotspots
     * (zonas), casing de ruta, ruta, traza de escolta, puntos de incidente
     * (detalle). Invertir este orden tapa la información más precisa con la más
     * difusa.
     */
    val LAYER_STACK_ORDER = listOf(
        LAYER_BASE,
        LAYER_HEATMAP,
        LAYER_HOTSPOTS,
        LAYER_ROUTE_CASING,
        LAYER_ROUTE,
        LAYER_TRAIL,
        LAYER_INCIDENTS,
    )

    private const val TAG = "MapLayers"
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
