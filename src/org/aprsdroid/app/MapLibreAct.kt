package org.aprsdroid.app

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.OkHttpClient
import org.aprsdroid.app.ui.AprsBottomBar
import org.aprsdroid.app.ui.AprsOverflowMenu
import org.aprsdroid.app.ui.MapButton
import org.aprsdroid.app.ui.MapStationLayer
import org.aprsdroid.app.ui.MapTypeMenu
import org.aprsdroid.app.ui.OfflineMapsDialog
import org.aprsdroid.app.ui.SavedArea
import org.aprsdroid.app.ui.theme.AprsTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The OpenStreetMap and vector map types, drawn by MapLibre: smooth zoom,
 * rotation and tilt, and (for the vector maps) areas saved for offline use.
 *
 * Stations come from the same [MapData] loader and the same clustering as the
 * other map screens. They are drawn as one GeoJSON source: an icon layer
 * (station symbols and cluster discs), a label layer whose callsign pills
 * hide themselves where they would overlap, and a line layer for traces.
 * Symbols and pills are bitmaps made on demand, so nothing depends on fonts
 * from the network and stations still draw with no connection.
 */
class MapLibreAct : ComponentActivity() {

    private val tag = "APRSdroid.MLMap"
    private val prefs by lazy { PrefsWrapper(this) }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var stationSource: GeoJsonSource? = null
    private var traceSource: GeoJsonSource? = null

    private var trackedCall by mutableStateOf("")
    private var selectedCall by mutableStateOf("")
    private var clusterCalls by mutableStateOf(emptyList<String>())
    /** Kinds of station shown on the map; empty means all. */
    private var stationFilter by mutableStateOf(emptySet<StationKind>())
    private var stationCounts by mutableStateOf(StationCounts.NONE)
    private var filterPanelOpen by mutableStateOf(false)
    private var shownStations: List<MapStation> = emptyList()
    private var clusteredAtZoom = Double.NaN
    private var dataVersion by mutableStateOf(0)
    private var targetcall: String
        get() = trackedCall
        set(v) { trackedCall = v }
    private var showObjects by mutableStateOf(true)
    private var isLoading by mutableStateOf(true)
    private var currentModeTag by mutableStateOf("osm")
    private var currentStyle by mutableStateOf(MapLibreStyle.OSM)
    private var firstLoad = true

    private var offlineOpen by mutableStateOf(false)
    private var offlineAreas by mutableStateOf(emptyList<SavedArea>())
    private var offlineProgress by mutableStateOf<Float?>(null)
    private var offlineMessage by mutableStateOf("")
    private val regions = HashMap<Long, OfflineRegion>()

    private val drawSize by lazy { (resources.displayMetrics.density * 24).toInt() }

    private val locReceiver by lazy {
        LocationReceiver2<List<MapStation>>(
            { MapData.loadStations(this, prefs, targetcall, true) },
            { stations -> onStationUpdate(stations) },
            { },
        )
    }

    companion object {
        /** MapLibre counts zoom for 512-pixel tiles: one less than the zoom the rest of the app uses. */
        private const val ZOOM_OFFSET = 1.0
        private const val LABEL_MIN_ZOOM = 7f
        private const val CLUSTER_CELL_DP = 64f
        private const val TOP_CHROME_DP = 172f
        private const val SRC_STATIONS = "aprs-stations"
        private const val SRC_TRACES = "aprs-traces"
        private const val LAYER_ICONS = "aprs-icons"
        private const val LAYER_LABELS = "aprs-labels"
        private const val LAYER_TRACES = "aprs-traces"
        private var httpReady = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UIHelper.applySystemBarInsets(this)
        targetcall = intent.dataString ?: ""
        showObjects = prefs.getShowObjects()
        val mode = MapModes.defaultMapMode(this, prefs)
        currentModeTag = mode.tag
        currentStyle = (mode as? MapLibreMode)?.style ?: MapLibreStyle.OSM

        MapLibre.getInstance(this)
        if (!httpReady) {
            httpReady = true
            // Tile servers ask apps to say who they are.
            val agent = "APRSDroid-TAK/${BuildConfig.VERSION_NAME.removePrefix("v")} (+https://github.com/mitchmitchell/APRSDroid-TAK)"
            HttpRequestUtil.setOkHttpClient(
                OkHttpClient.Builder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build())
                }.build(),
            )
        }
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        // Symbols, callsign pills and cluster discs are drawn the first time the map asks for them.
        mapView.addOnStyleImageMissingListener { id -> imageFor(id)?.let { style?.addImage(id, it) } }
        mapView.getMapAsync { m -> onMapReady(m) }

        setContent { AprsTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val call = intent.dataString ?: ""
        if (call.isNotEmpty() && call != targetcall) {
            targetcall = call
            firstLoad = true
            reload()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun Screen() {
        Scaffold(
            bottomBar = {
                AprsBottomBar(
                    current = NavTarget.MAP,
                    onNavigate = { target -> AprsNavigation.navigateTo(this, target, prefs) },
                    onPreferences = { AprsNavigation.openPreferences(this) },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                org.aprsdroid.app.ui.MapButtonColumn(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    MapButton {
                        IconButton(onClick = { filterPanelOpen = !filterPanelOpen }) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = "Filter by type",
                                // Tinted while a filter is in force, so a thinned map is never a mystery.
                                tint = if (stationFilter.isEmpty()) LocalContentColor.current else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    MapButton {
                        IconButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) }) {
                            Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                        }
                    }
                    MapButton {
                        IconButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) }) {
                            Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
                        }
                    }
                    MapButton {
                        IconButton(onClick = { centerOnMyLocation() }) {
                            Icon(Icons.Filled.MyLocation, contentDescription = "Go to my location")
                        }
                    }
                    MapButton {
                        MapTypeMenu(currentTag = currentModeTag) { mode ->
                            if (!MapModes.switchTo(this@MapLibreAct, prefs, mode, targetcall)) {
                                currentModeTag = mode.tag
                                (mode as? MapLibreMode)?.let { applyStyle(it.style) }
                            }
                        }
                    }
                    if (currentStyle.canSave) {
                        MapButton {
                            IconButton(onClick = { offlineOpen = true }) {
                                Icon(Icons.Filled.DownloadForOffline, contentDescription = "Offline maps")
                            }
                        }
                    }
                    MapButton {
                        AprsOverflowMenu(
                            showAgeFilter = true,
                            onPreferences = { AprsNavigation.openPreferences(this@MapLibreAct) },
                            onAgeChanged = { reload() },
                        )
                    }
                }
                androidx.activity.compose.BackHandler(
                    enabled = filterPanelOpen || selectedCall.isNotEmpty() || clusterCalls.isNotEmpty(),
                ) {
                    if (filterPanelOpen) {
                        filterPanelOpen = false
                    } else {
                        clusterCalls = emptyList()
                        selectedCall = ""
                    }
                }
                MapStationLayer(
                    selectedCall = selectedCall,
                    clusterCalls = clusterCalls,
                    followedCall = trackedCall,
                    refreshKey = dataVersion,
                    filter = stationFilter,
                    counts = stationCounts,
                    onFilter = { stationFilter = it; reload() },
                    filterPanelOpen = filterPanelOpen,
                    onCloseFilterPanel = { filterPanelOpen = false },
                    showObjects = showObjects,
                    onToggleObjects = {
                        showObjects = prefs.toggleBoolean("show_objects", true)
                        reload()
                    },
                    onFound = { sta ->
                        clusterCalls = emptyList()
                        selectedCall = sta.call
                        map?.let { m ->
                            val zoom = maxOf(m.cameraPosition.zoom, 12.0)
                            m.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(sta.lat / 1e6, sta.lon / 1e6), zoom),
                            )
                        }
                    },
                    onPick = { call -> clusterCalls = emptyList(); selectedCall = call },
                    onDismiss = { selectedCall = ""; clusterCalls = emptyList() },
                    onFollow = { call -> targetcall = call; reload() },
                    onStopFollowing = { targetcall = ""; reload() },
                )
                if (isLoading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                if (offlineOpen) {
                    OfflineMapsDialog(
                        areas = offlineAreas,
                        progress = offlineProgress,
                        message = offlineMessage,
                        onSave = { saveVisibleArea() },
                        onDelete = { deleteArea(it) },
                        onDismiss = { offlineOpen = false },
                    )
                }
            }
        }
    }

    private fun onMapReady(m: MapLibreMap) {
        map = m
        val density = resources.displayMetrics.density
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.isCompassEnabled = true
        // Keep the compass clear of the search bar, chips and status row.
        m.uiSettings.setCompassMargins(0, ((TOP_CHROME_DP + 8) * density).toInt(), (72 * density).toInt(), 0)
        m.setMaxZoomPreference(19.0)
        val (pos, zoom) = MapData.loadCamera(this, prefs)
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.lat, pos.lon), zoom - ZOOM_OFFSET))
        m.addOnMapClickListener { point -> onTap(m, point) }
        m.addOnCameraIdleListener {
            // The cluster grid is fixed to the map, so only a zoom change regroups stations.
            val z = m.cameraPosition.zoom
            if (clusteredAtZoom.isNaN() || Math.abs(z - clusteredAtZoom) > 0.05) render()
        }
        applyStyle(currentStyle)
        startLoading()
    }

    /** Load a base map, then put the station layers back on top of it. */
    private fun applyStyle(s: MapLibreStyle) {
        val m = map ?: return
        currentStyle = s
        style = null
        stationSource = null
        traceSource = null
        val builder = Style.Builder().let { b -> s.url?.let { b.fromUri(it) } ?: b.fromJson(s.json!!) }
        m.setStyle(builder) { loaded ->
            style = loaded
            val traces = GeoJsonSource(SRC_TRACES)
            val stations = GeoJsonSource(SRC_STATIONS)
            loaded.addSource(traces)
            loaded.addSource(stations)
            loaded.addLayer(
                LineLayer(LAYER_TRACES, SRC_TRACES).withProperties(
                    PropertyFactory.lineColor("rgba(100,100,255,0.55)"),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            loaded.addLayer(
                SymbolLayer(LAYER_LABELS, SRC_STATIONS).withProperties(
                    PropertyFactory.iconImage(Expression.get("label")),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_TOP),
                    PropertyFactory.iconOffset(arrayOf(0f, 14f)),
                    // Pills that would overlap give way, instead of piling up.
                    PropertyFactory.iconAllowOverlap(false),
                    PropertyFactory.symbolSortKey(Expression.get("rank")),
                ).withFilter(Expression.has("label")).apply { minZoom = LABEL_MIN_ZOOM },
            )
            loaded.addLayer(
                SymbolLayer(LAYER_ICONS, SRC_STATIONS).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
            traceSource = traces
            stationSource = stations
            render()
        }
    }

    private fun onTap(m: MapLibreMap, point: LatLng): Boolean {
        val p = m.projection.toScreenLocation(point)
        val r = drawSize * 0.9f
        val hits = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), LAYER_ICONS)
        val f = hits.firstOrNull() ?: return false
        val calls = f.getStringProperty("calls")
        if (calls != null) {
            val list = calls.split('\n')
            val at = (f.geometry() as? Point)?.let { LatLng(it.latitude(), it.longitude()) } ?: point
            if (list.size <= 6 || m.cameraPosition.zoom >= 16.0) {
                selectedCall = ""
                clusterCalls = list
            } else {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(at, m.cameraPosition.zoom + 2.0))
            }
        } else {
            clusterCalls = emptyList()
            selectedCall = f.getStringProperty("call") ?: return false
        }
        return true
    }

    private fun startLoading() {
        val filter = IntentFilter(AprsService.UPDATE)
        filter.addAction(AprsService.POSITION)
        UIHelper.safeRegisterReceiver(this, locReceiver, filter)
        reload()
    }

    private fun reload() {
        isLoading = true
        locReceiver.startTask(null)
    }

    // ---- images, made on demand by name ----

    private fun imageFor(id: String): Bitmap? = try {
        when {
            id.startsWith("sym:") -> AprsSymbols.bitmap(this, id.substring(4), drawSize)
            id.startsWith("lbl:") -> labelBitmap(id.substring(4))
            id.startsWith("cl:") -> clusterBitmap(id.substring(3).toInt())
            else -> null
        }
    } catch (e: Exception) {
        Log.w(tag, "no image for $id: $e")
        null
    }

    /** White disc with a navy ring and the station count, as on the other map screens. */
    private fun clusterBitmap(count: Int): Bitmap {
        val density = resources.displayMetrics.density
        val r = CLUSTER_CELL_DP * density * (0.30f + 0.04f * minOf(count.toString().length, 4))
        val stroke = drawSize / 16f
        val size = (2 * (r + stroke)).toInt() + 2
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val cx = size / 2f
        c.drawCircle(cx, cx, r, Paint().apply { color = 0xf7ffffff.toInt(); isAntiAlias = true })
        c.drawCircle(cx, cx, r, Paint().apply {
            color = 0xff1c2f51.toInt(); isAntiAlias = true
            style = Paint.Style.STROKE; strokeWidth = stroke
        })
        val text = Paint().apply {
            color = 0xff0d182d.toInt(); isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = r * 0.85f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val fm = text.fontMetrics
        c.drawText(count.toString(), cx, cx - (fm.ascent + fm.descent) / 2, text)
        return b
    }

    /** Callsign label: navy text on a light pill. */
    private fun labelBitmap(text: String): Bitmap {
        val fontSize = drawSize * 0.58f
        val textPaint = Paint().apply {
            color = 0xff0d182d.toInt()
            textSize = fontSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }
        val padX = fontSize * 0.35f
        val padY = fontSize * 0.18f
        val fm = textPaint.fontMetrics
        val w = (textPaint.measureText(text) + 2 * padX).toInt().coerceAtLeast(1)
        val h = (fm.descent - fm.ascent + 2 * padY).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f,
            Paint().apply { color = 0xe6ffffff.toInt(); isAntiAlias = true },
        )
        c.drawText(text, padX, padY - fm.ascent, textPaint)
        return b
    }

    // ---- stations ----

    private fun onStationUpdate(stations: List<MapStation>) {
        dataVersion++
        isLoading = false
        val (visible, counts) = MapFilters.withObjects(stations, showObjects)
        stationCounts = counts
        shownStations = MapFilters.apply(visible, stationFilter, setOf(prefs.getCallSsid(), targetcall))
        render()
        followTarget()
    }

    /** Group [shownStations] for the current zoom and hand the result to the map. */
    private fun render() {
        val m = map ?: return
        val stations = stationSource ?: return
        val zoom = m.cameraPosition.zoom
        clusteredAtZoom = zoom
        val mine = prefs.getCallSsid()
        val groups = MapCluster.cluster(
            shownStations, zoom + ZOOM_OFFSET, CLUSTER_CELL_DP.toDouble(),
            { it.pos.lat }, { it.pos.lon },
            { it.call == targetcall || it.call == mine },
        )
        val points = ArrayList<Feature>(groups.size)
        val lines = ArrayList<Feature>()
        for (g in groups) {
            val f = Feature.fromGeometry(Point.fromLngLat(g.lon, g.lat))
            if (g.isCluster) {
                f.addStringProperty("icon", "cl:${g.items.size}")
                f.addStringProperty("calls", g.items.joinToString("\n") { it.call })
            } else {
                val sta = g.items[0]
                f.addStringProperty("icon", "sym:${sta.symbol}")
                f.addStringProperty("label", "lbl:${sta.call}")
                f.addStringProperty("call", sta.call)
                // Your own station and the one you follow keep their label when space is short.
                f.addNumberProperty("rank", if (sta.call == targetcall || sta.call == mine) 0 else 1)
                if (sta.trace.size >= 2) {
                    lines.add(Feature.fromGeometry(LineString.fromLngLats(sta.trace.map { Point.fromLngLat(it.lon, it.lat) })))
                }
            }
            points.add(f)
        }
        stations.setGeoJson(FeatureCollection.fromFeatures(points))
        traceSource?.setGeoJson(FeatureCollection.fromFeatures(lines))
    }

    private fun followTarget() {
        if (targetcall.isEmpty()) return
        val sta = shownStations.firstOrNull { it.call == targetcall } ?: return
        val m = map ?: return
        val at = LatLng(sta.pos.lat, sta.pos.lon)
        if (firstLoad) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(at, 13.0))
            firstLoad = false
        } else {
            m.animateCamera(CameraUpdateFactory.newLatLng(at))
        }
    }

    private fun centerOnMyLocation() {
        val loc = MapData.lastKnownLocation(this)
        if (loc == null) {
            Toast.makeText(this, R.string.map_no_location, Toast.LENGTH_SHORT).show()
            return
        }
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.lat, loc.lon), 13.0))
    }

    // ---- saved areas ----

    private fun areaName(r: OfflineRegion): String = try { String(r.metadata, Charsets.UTF_8) } catch (_: Exception) { "Saved area" }

    private fun refreshAreas() {
        OfflineManager.getInstance(this).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                regions.clear()
                val list = ArrayList<SavedArea>()
                for (r in offlineRegions.orEmpty()) {
                    regions[r.id] = r
                    list.add(SavedArea(r.id, areaName(r)))
                }
                offlineAreas = list
            }

            override fun onError(error: String) {
                offlineMessage = "Could not read saved areas: $error"
            }
        })
    }

    private fun saveVisibleArea() {
        val m = map ?: return
        val bounds = m.projection.visibleRegion.latLngBounds
        val minZoom = OfflineArea.minZoom(m.cameraPosition.zoom)
        val tiles = OfflineArea.tileCount(
            bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast,
            minZoom, OfflineArea.MAX_ZOOM.toInt(),
        )
        if (tiles > OfflineArea.TILE_LIMIT) {
            offlineMessage = "This area is too large to save. Zoom in and try again."
            return
        }
        val url = currentStyle.url ?: return
        val name = "%s  %.2f, %.2f".format(
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date()),
            bounds.center.latitude, bounds.center.longitude,
        )
        val def = OfflineTilePyramidRegionDefinition(
            url, bounds, minZoom.toDouble(), OfflineArea.MAX_ZOOM, resources.displayMetrics.density,
        )
        offlineProgress = 0f
        offlineMessage = "Saving…"
        OfflineManager.getInstance(this).createOfflineRegion(
            def, name.toByteArray(Charsets.UTF_8),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val need = status.requiredResourceCount
                            if (need > 0) offlineProgress = status.completedResourceCount.toFloat() / need
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                offlineProgress = null
                                offlineMessage = "Saved. This area now works with no connection."
                                refreshAreas()
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            offlineMessage = "Saving stopped: ${error.message}"
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            offlineProgress = null
                            offlineMessage = "This area is too large to save. Zoom in and try again."
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    refreshAreas()
                }

                override fun onError(error: String) {
                    offlineProgress = null
                    offlineMessage = "Could not save this area: $error"
                }
            },
        )
    }

    private fun deleteArea(id: Long) {
        val r = regions[id] ?: return
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                offlineMessage = ""
                refreshAreas()
            }

            override fun onError(error: String) {
                offlineMessage = "Could not delete: $error"
            }
        })
    }

    // ---- MapView lifecycle forwarding ----

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        refreshAreas()
    }

    override fun onPause() {
        map?.cameraPosition?.let { cp ->
            cp.target?.let {
                MapData.saveCamera(prefs, it.latitude.toFloat(), it.longitude.toFloat(), (cp.zoom + ZOOM_OFFSET).toFloat())
            }
        }
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        locReceiver.cancel()
        try {
            unregisterReceiver(locReceiver)
        } catch (_: Exception) {
        }
        mapView.onDestroy()
        super.onDestroy()
    }
}
