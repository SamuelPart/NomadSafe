package com.nomadsafe.app.feature.map

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomadsafe.app.R
import com.nomadsafe.app.core.designsystem.NomadSafeTheme
import com.nomadsafe.app.core.designsystem.RiskColors
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.IncidentCategory
import com.nomadsafe.app.domain.model.RiskLevel
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Pantalla principal del mapa.
 *
 * ── Por qué AndroidView y no una extensión Compose ────────────────────────────
 * Envolver [MapView] en [AndroidView] con ciclo de vida manejado explícitamente
 * funciona en todas las versiones del SDK y deja el control del MapView en
 * nuestras manos, que es necesario para la escolta (actualizar la traza cada
 * pocos segundos sin recomponer el mapa entero).
 *
 * ── El bug de ciclo de vida que esto evita ────────────────────────────────────
 * Un MapView dentro de un AndroidView sin `onCreate`/`onStart`/`onStop`/
 * `onDestroy` filtra el contexto GL y el cliente de ubicación interno. La app se
 * ve bien en la primera apertura y degrada tras navegar varias veces. El
 * [DisposableEffect] más el [LifecycleEventObserver] cubren exactamente eso.
 *
 * ── getMapAsync y no acceso directo ───────────────────────────────────────────
 * El [MapLibreMap] sólo existe después de que el MapView termina su
 * inicialización asíncrona. Guardarlo en un [mutableStateOf] y gatillar los
 * efectos a partir de ahí evita el clásico NPE de tocar el mapa antes de tiempo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    focusIncidentId: String? = null,
    onReportClick: () -> Unit = {},
    onEscortClick: () -> Unit = {},
    onRouteClick: () -> Unit = {},
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }

    // El MapView debe crearse una sola vez y sobrevivir a las recomposiciones.
    // `remember` sin claves: recrearlo en cada cambio de estado resetearía la
    // cámara y el zoom del usuario, que es el bug más notorio en mapas Compose.
    val mapView = remember {
        MapView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    // ── Ciclo de vida del MapView ─────────────────────────────────────────────
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // ── Carga del estilo: base raster OSM + capas analíticas ──────────────────
    LaunchedEffect(Unit) {
        mapView.getMapAsync { map ->
            mapLibreMap = map

            map.setStyle(
                Style.Builder()
                    .withSource(MapLayers.baseRasterSource())
                    .withLayer(MapLayers.baseRasterLayer()),
            ) { style ->
                loadedStyle = style
                installLayers(style)
                configureInitialCamera(map, state.viewportCenter)
                installClickHandler(map) { id -> viewModel.selectIncidentById(id) }
                installCameraListener(map) { center, zoom -> viewModel.onViewportChanged(center, zoom) }
            }
        }
    }

    // ── Actualización de datos de las fuentes ─────────────────────────────────
    // Se hace fuera de `update` del AndroidView: el bloque update se ejecuta en
    // cada recomposición, y republicar GeoJSON ahí es caro. LaunchedEffect con las
    // listas como clave sólo lo hace cuando los datos cambian de verdad.
    LaunchedEffect(state.incidents, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        MapLayers.updateSource(
            style,
            MapLayers.SOURCE_INCIDENTS,
            MapLayers.incidentsToFeatureCollection(state.incidents),
        )
    }

    LaunchedEffect(state.hotspots, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        MapLayers.updateSource(
            style,
            MapLayers.SOURCE_HOTSPOTS,
            MapLayers.hotspotsToFeatureCollection(state.hotspots),
        )
    }

    // ── Visibilidad de capas ──────────────────────────────────────────────────
    LaunchedEffect(state.enabledLayers, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        MapLayers.setLayerVisibility(style, MapLayers.LAYER_HEATMAP, MapLayer.HEATMAP in state.enabledLayers)
        MapLayers.setLayerVisibility(style, MapLayers.LAYER_HOTSPOTS, MapLayer.HEATMAP in state.enabledLayers)
        MapLayers.setLayerVisibility(style, MapLayers.LAYER_INCIDENTS, MapLayer.INCIDENTS in state.enabledLayers)
    }

    // ── Deep link: enfocar un incidente concreto ──────────────────────────────
    LaunchedEffect(focusIncidentId) {
        focusIncidentId?.let { viewModel.focusIncident(it) }
    }

    // ── Mover la cámara cuando el ViewModel lo pide (recentrar / enfocar) ─────
    LaunchedEffect(state.viewportCenter, state.zoom, loadedStyle) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(state.viewportCenter.latitude, state.viewportCenter.longitude))
                    .zoom(state.zoom)
                    .build(),
            ),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // ── Encabezado: nivel de riesgo de la zona ────────────────────────────
        RiskHeader(
            riskLevel = state.riskLevelAtCenter,
            riskScore = state.riskScoreAtCenter,
            incidentCount = state.visibleIncidentCount,
            isLoading = state.isLoading,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
        )

        // ── Selector de capas ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 128.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapLayer.entries.forEach { layer ->
                AssistChip(
                    onClick = { viewModel.toggleLayer(layer) },
                    label = { Text(layer.label(), style = MaterialTheme.typography.labelMedium) },
                    shape = CircleShape,
                )
            }
        }

        // ── Botón de recentrar ────────────────────────────────────────────────
        FloatingActionButton(
            onClick = { viewModel.recenterOnUser() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 160.dp, end = 16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.action_recenter))
        }

        // ── Acciones principales ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FloatingActionButton(onClick = onReportClick, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Shield, contentDescription = stringResource(R.string.action_report))
            }
            FloatingActionButton(onClick = onRouteClick, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text(stringResource(R.string.tab_routes).take(1))
            }
            FloatingActionButton(onClick = onEscortClick, containerColor = NomadSafeTheme.risk.escortActive) {
                Text(stringResource(R.string.tab_escort).take(1))
            }
        }

        // ── Aviso de sincronización pendiente ─────────────────────────────────
        if (state.pendingSyncCount > 0) {
            SyncPendingBadge(
                count = state.pendingSyncCount,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 16.dp),
            )
        }

        // ── Ficha del incidente seleccionado ──────────────────────────────────
        state.selectedIncident?.let { incident ->
            IncidentDetailSheet(
                incident = incident,
                onDismiss = { viewModel.selectIncident(null) },
                onShare = { /* el builder de contenido de crecimiento arma el texto */ },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private fun configureInitialCamera(map: MapLibreMap, center: GeoPoint) {
    map.cameraPosition = CameraPosition.Builder()
        .target(LatLng(center.latitude, center.longitude))
        .zoom(DEFAULT_ZOOM)
        .build()
}

/**
 * Cada movimiento de cámara informado al ViewModel.
 *
 * Se usa el listener de "cámara quieta" y no el de movimiento continuo: informar
 * el viewport en cada frame de un gesto lanzaría una consulta por frame. El
 * ViewModel además cuantiza a ~50 m, pero no conviene ni generar el evento.
 */
private fun installCameraListener(
    map: MapLibreMap,
    onViewport: (GeoPoint, Double) -> Unit,
) {
    map.addOnCameraIdleListener {
        val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
        // onViewportChanged ignora deltas chicos y cuantiza a ~50 m, así que
        // llamarlo en cada "cámara quieta" es barato y no relanza consultas.
        onViewport(
            GeoPoint(latitude = target.latitude, longitude = target.longitude),
            map.cameraPosition.zoom,
        )
    }
}

/**
 * Click sobre un punto de incidente → ficha de detalle.
 *
 * Se consulta qué feature quedó debajo del toque en la capa de incidentes, en vez
 * de hacer la cuenta geométrica a mano (distancia píxel→metros por zoom). El
 * renderer ya sabe qué dibujó dónde; preguntarle es exacto y gratis.
 */
private fun installClickHandler(
    map: MapLibreMap,
    onIncidentClicked: (String) -> Unit,
) {
    map.addOnMapClickListener { latLng ->
        val screen = map.projection.toScreenLocation(latLng)
        val features = map.queryRenderedFeatures(screen, MapLayers.LAYER_INCIDENTS)
        val incidentId = features.firstOrNull()?.getStringProperty(PROPERTY_INCIDENT_ID_KEY)

        if (incidentId != null) onIncidentClicked(incidentId)

        // Devolver true consume el toque (evita que el gesto también mueva la
        // cámara); false deja que otros gestos sigan.
        incidentId != null
    }
}

/**
 * Instala fuentes y capas en el orden de apilado correcto.
 *
 * El orden importa y es fácil de romper: si el heatmap se agrega al final, tapa los
 * puntos de incidente y el usuario pierde la capacidad de ver reportes individuales.
 * Se recorre [MapLayers.LAYER_STACK_ORDER] para que el orden esté declarado en un
 * solo lugar.
 */
private fun installLayers(style: Style) {
    listOf(
        MapLayers.SOURCE_INCIDENTS,
        MapLayers.SOURCE_HOTSPOTS,
        MapLayers.SOURCE_ROUTE,
        MapLayers.SOURCE_TRAIL,
    ).forEach { sourceId -> MapLayers.ensureGeoJsonSource(style, sourceId) }

    MapLayers.ensureLayer(style, MapLayers.buildHeatmapLayer())
    MapLayers.ensureLayer(style, MapLayers.buildHotspotsLayer())
    MapLayers.ensureLayer(style, MapLayers.buildRouteCasingLayer())
    MapLayers.ensureLayer(style, MapLayers.buildRouteLayer())
    MapLayers.ensureLayer(style, MapLayers.buildTrailLayer())
    MapLayers.ensureLayer(style, MapLayers.buildIncidentsLayer())

    NsLog.i("MapScreen", "Estilo cargado: base raster OSM + ${MapLayers.LAYER_STACK_ORDER.size - 1} capas analíticas")
}

private const val DEFAULT_ZOOM = 15.0
private const val PROPERTY_INCIDENT_ID_KEY = "incident_id"

private fun MapLayer.label(): String = when (this) {
    MapLayer.HEATMAP -> "Riesgo"
    MapLayer.INCIDENTS -> "Reportes"
    MapLayer.LIGHTING -> "Iluminación"
}

@Composable
private fun RiskHeader(
    riskLevel: RiskLevel,
    riskScore: Double,
    incidentCount: Int,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val riskColors: RiskColors = NomadSafeTheme.risk

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(riskColors.forLevel(riskLevel), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = riskLevel.displayName().uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = riskColors.forLevel(riskLevel),
                )
                Text(
                    text = stringResource(R.string.map_risk_summary, incidentCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = "%.2f".format(riskScore),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SyncPendingBadge(count: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = NomadSafeTheme.risk.moderate),
    ) {
        Text(
            text = "$count",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun IncidentDetailSheet(
    incident: Incident,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = incident.category.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = incident.status.displayName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = incident.neighborhood ?: stringResource(R.string.map_no_neighborhood),
                style = MaterialTheme.typography.bodyMedium,
            )
            incident.description?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onShare, label = { Text(stringResource(R.string.action_share)) })
                AssistChip(onClick = onDismiss, label = { Text(stringResource(R.string.action_close)) })
            }
        }
    }
}

private fun RiskColors.forLevel(level: RiskLevel) = when (level) {
    RiskLevel.LOW -> low
    RiskLevel.MODERATE -> moderate
    RiskLevel.HIGH -> high
    RiskLevel.CRITICAL -> critical
}

private fun IncidentCategory.displayName(): String = when (this) {
    IncidentCategory.ASSAULT -> "Agresión"
    IncidentCategory.THEFT -> "Robo"
    IncidentCategory.HARASSMENT -> "Acoso"
    IncidentCategory.SUSPICIOUS -> "Sospechoso"
    IncidentCategory.DARK_STREET -> "Calle oscura"
    IncidentCategory.ROAD_BLOCKED -> "Calle cortada"
    IncidentCategory.ACCIDENT -> "Accidente"
    IncidentCategory.OTHER -> "Otro"
}

private fun com.nomadsafe.app.domain.model.IncidentStatus.displayName(): String = when (this) {
    com.nomadsafe.app.domain.model.IncidentStatus.ACTIVE -> "Activo"
    com.nomadsafe.app.domain.model.IncidentStatus.CONFIRMED -> "Confirmado"
    com.nomadsafe.app.domain.model.IncidentStatus.UNDER_REVIEW -> "En revisión"
    com.nomadsafe.app.domain.model.IncidentStatus.DISPUTED -> "Disputado"
    com.nomadsafe.app.domain.model.IncidentStatus.REMOVED -> "Retirado"
}
