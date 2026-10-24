package com.nomadsafe.app.feature.map

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.remember
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
import com.mapbox.geojson.FeatureCollection
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.applyDefaultParams
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.gestures.removeOnMapClickListener
import com.nomadsafe.app.R
import com.nomadsafe.app.core.config.AppConfig
import com.nomadsafe.app.core.designsystem.NomadSafeTheme
import com.nomadsafe.app.core.designsystem.RiskColors
import com.nomadsafe.app.core.util.NsLog
import com.nomadsafe.app.domain.model.GeoPoint
import com.nomadsafe.app.domain.model.Incident
import com.nomadsafe.app.domain.model.RiskLevel

/**
 * Pantalla principal del mapa.
 *
 * ── Por qué AndroidView y no una extensión Compose de Mapbox ──────────────────
 * Mapbox publica una extensión Compose experimental, pero su API cambió varias
 * veces y su disponibilidad por versión no es estable. Envolver [MapView] en
 * [AndroidView] con ciclo de vida manejado explícitamente funciona en todas las
 * versiones 11.x del SDK y deja el control del MapView en nuestras manos, que es
 * necesario para la escolta (actualizar la traza cada pocos segundos sin
 * recomponer el mapa entero).
 *
 * ── El bug de ciclo de vida que esto evita ────────────────────────────────────
 * Un MapView dentro de un AndroidView sin `onCreate`/`onStart`/`onStop`/`onDestroy`
 * filtra el contexto GL y el servicio de localización interno. La app se ve bien
 * en la primera apertura y degrada tras navegar varias veces. El
 * [DisposableEffect] más el [LifecycleEventObserver] cubren exactamente eso.
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

    // El MapView debe crearse una sola vez y sobrevivir a las recomposiciones.
    // `remember` sin claves: recrearlo en cada cambio de estado resetearía la
    // cámara y el zoom del usuario, que es el bug más notorio en mapas Compose.
    val mapView = remember {
        MapView(context, MapInitOptions(context).applyDefaultParams()).apply {
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

    // ── Carga del estilo y de las capas ───────────────────────────────────────
    LaunchedEffect(Unit) {
        mapView.mapboxMap.loadStyle(Style.MAPBOX_STREETS) { style ->
            configureInitialCamera(mapView, state.viewportCenter)
            installLayers(style)
        }
    }

    // ── Actualización de datos de las fuentes ─────────────────────────────────
    // Se hace fuera de `update` del AndroidView: el bloque update se ejecuta en
    // cada recomposición, y republicar GeoJSON ahí es caro. LaunchedEffect con las
    // listas como clave sólo lo hace cuando los datos cambian de verdad.
    LaunchedEffect(state.incidents) {
        mapView.mapboxMap.getStyle()?.let { style ->
            MapboxLayers.updateSource(
                style,
                MapboxLayers.SOURCE_INCIDENTS,
                MapboxLayers.incidentsToFeatureCollection(state.incidents),
            )
        }
    }

    LaunchedEffect(state.hotspots) {
        mapView.mapboxMap.getStyle()?.let { style ->
            MapboxLayers.updateSource(
                style,
                MapboxLayers.SOURCE_HOTSPOTS,
                MapboxLayers.hotspotsToFeatureCollection(state.hotspots),
            )
        }
    }

    // ── Visibilidad de capas ──────────────────────────────────────────────────
    LaunchedEffect(state.enabledLayers) {
        mapView.mapboxMap.getStyle()?.let { style ->
            MapboxLayers.setLayerVisibility(style, MapboxLayers.LAYER_HEATMAP, MapLayer.HEATMAP in state.enabledLayers)
            MapboxLayers.setLayerVisibility(style, MapboxLayers.LAYER_INCIDENTS, MapLayer.INCIDENTS in state.enabledLayers)
            MapboxLayers.setLayerVisibility(style, MapboxLayers.LAYER_HOTSPOTS, MapLayer.HEATMAP in state.enabledLayers)
        }
    }

    // ── Deep link: enfocar un incidente concreto ──────────────────────────────
    LaunchedEffect(focusIncidentId) {
        focusIncidentId?.let { viewModel.focusIncident(it) }
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

        // ── Selector de capas y ventana temporal ──────────────────────────────
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

private fun configureInitialCamera(mapView: MapView, center: GeoPoint) {
    mapView.mapboxMap.setCamera(
        CameraOptions.Builder()
            .center(com.mapbox.geojson.Point.fromLngLat(center.longitude, center.latitude))
            .zoom(15.0)
            .build(),
    )
}

/**
 * Instala fuentes y capas en el orden de apilado correcto.
 *
 * El orden importa y es fácil de romper: si el heatmap se agrega al final, tapa los
 * puntos de incidente y el usuario pierde la capacidad de ver reportes individuales.
 * Se recorre [MapboxLayers.LAYER_STACK_ORDER] para que el orden esté declarado en un
 * solo lugar.
 */
private fun installLayers(style: Style) {
    listOf(
        MapboxLayers.SOURCE_INCIDENTS,
        MapboxLayers.SOURCE_HOTSPOTS,
        MapboxLayers.SOURCE_ROUTE,
        MapboxLayers.SOURCE_TRAIL,
    ).forEach { sourceId ->
        if (!style.styleSourceExists(sourceId)) {
            style.addSource(
                com.mapbox.maps.extension.style.sources.GeoJsonSource.Builder(sourceId)
                    .data(FeatureCollection.fromFeatures(emptyList()))
                    .build(),
            )
        }
    }

    val layersInOrder = listOf(
        MapboxLayers.LAYER_HEATMAP to MapboxLayers.buildHeatmapLayer(),
        MapboxLayers.LAYER_HOTSPOTS to MapboxLayers.buildHotspotsLayer(),
        MapboxLayers.LAYER_ROUTE_CASING to MapboxLayers.buildRouteCasingLayer(),
        MapboxLayers.LAYER_ROUTE to MapboxLayers.buildRouteLayer(),
        MapboxLayers.LAYER_TRAIL to MapboxLayers.buildTrailLayer(),
        MapboxLayers.LAYER_INCIDENTS to MapboxLayers.buildIncidentsLayer(),
    )

    layersInOrder.forEach { (_, layer) -> MapboxLayers.ensureLayer(style, layer) }
    NsLog.i("MapScreen", "Estilo cargado con ${layersInOrder.size} capas")
}

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
