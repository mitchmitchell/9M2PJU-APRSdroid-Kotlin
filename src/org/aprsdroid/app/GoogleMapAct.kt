package org.aprsdroid.app

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import org.aprsdroid.app.ui.MapButton
import org.aprsdroid.app.ui.MapStationLayer
import org.aprsdroid.app.ui.AprsBottomBar
import org.aprsdroid.app.ui.AprsOverflowMenu
import org.aprsdroid.app.ui.MapTypeMenu
import org.aprsdroid.app.ui.theme.AprsTheme

/**
 * Google Maps SDK map — Kotlin/Compose port of the Scala `GoogleMapAct`.
 *
 * Only reachable when the app was built with a `mapsApiKey` and Play
 * services are installed (see [GoogleMapMode]). Stations come from the
 * same [MapData] loader as the OSM map, including movement traces.
 */
class GoogleMapAct : ComponentActivity() {

    private val tag = "APRSdroid.GMap"
    private val prefs by lazy { PrefsWrapper(this) }

    private lateinit var mapView: MapView
    private var map: GoogleMap? = null

    private var trackedCall by mutableStateOf("")
    private var selectedCall by mutableStateOf("")
    private var clusterCalls by mutableStateOf(emptyList<String>())
    private var stationFilter by mutableStateOf(StationFilter.ALL)
    private var stationCounts by mutableStateOf(StationCounts(0, 0, 0))
    /** Stations after the filter, as last loaded; re-clustered whenever the zoom changes. */
    private var shownStations: List<MapStation> = emptyList()
    private val clusterMarkers = ArrayList<Marker>()
    private var clusteredAtZoom = Float.NaN

    /** Tag on a cluster marker: what it stands for. */
    private class ClusterTag(val calls: List<String>, val lat: Double, val lon: Double)
    private var dataVersion by mutableStateOf(0)
    private var targetcall: String
        get() = trackedCall
        set(v) { trackedCall = v }
    private var showObjects by mutableStateOf(true)
    private var isLoading by mutableStateOf(true)
    private var currentModeTag by mutableStateOf("google")
    private var firstLoad = true
    private var labelsVisible = true

    private val drawSize by lazy { (resources.displayMetrics.density * 24).toInt() }
    private val icons = HashMap<String, BitmapDescriptor>()

    private class MarkerInfo(val icon: Marker, val label: Marker, var trace: Polyline?)
    private val markers = HashMap<String, MarkerInfo>()

    private val locReceiver by lazy {
        LocationReceiver2<List<MapStation>>(
            { MapData.loadStations(this, prefs, targetcall, showObjects) },
            { stations -> onStationUpdate(stations) },
            { },
        )
    }

    companion object {
        private const val CALLSIGN_ZOOM = 8f
        /** Cluster grid cell, in dp (Google's world is 256 dp per tile). */
        private const val CLUSTER_CELL_DP = 64f
        /** Height of the search bar, chips and status row over the map. */
        private const val TOP_CHROME_DP = 172f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UIHelper.applySystemBarInsets(this)
        targetcall = intent.dataString ?: ""
        showObjects = prefs.getShowObjects()
        currentModeTag = MapModes.defaultMapMode(this, prefs).tag

        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { googleMap -> onMapReady(googleMap) }

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
                            if (!MapModes.switchTo(this@GoogleMapAct, prefs, mode, targetcall)) {
                                currentModeTag = mode.tag
                                (mode as? GoogleMapMode)?.let { applyMode(it) }
                            }
                        }
                    }
                    MapButton {
                        AprsOverflowMenu(
                            showAgeFilter = true,
                            onPreferences = { AprsNavigation.openPreferences(this@GoogleMapAct) },
                            onAgeChanged = { reload() },
                        )
                    }
                }
                androidx.activity.compose.BackHandler(enabled = selectedCall.isNotEmpty() || clusterCalls.isNotEmpty()) {
                    clusterCalls = emptyList()
                    selectedCall = ""
                }
                MapStationLayer(
                    selectedCall = selectedCall,
                    clusterCalls = clusterCalls,
                    followedCall = trackedCall,
                    refreshKey = dataVersion,
                    filter = stationFilter,
                    counts = stationCounts,
                    onFilter = { stationFilter = it; reload() },
                    showObjects = showObjects,
                    onToggleObjects = {
                        showObjects = prefs.toggleBoolean("show_objects", true)
                        reload()
                    },
                    onFound = { sta ->
                        clusterCalls = emptyList()
                        selectedCall = sta.call
                        map?.let { gm ->
                            val zoom = maxOf(gm.cameraPosition.zoom, 13f)
                            gm.animateCamera(
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
            }
        }
    }

    private fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        (MapModes.defaultMapMode(this, prefs) as? GoogleMapMode)?.let { applyMode(it) }
        googleMap.uiSettings.isCompassEnabled = true
        googleMap.uiSettings.isZoomControlsEnabled = false // our own round buttons instead
        googleMap.uiSettings.isMapToolbarEnabled = false
        // Keep the compass and the centre of the map clear of the search bar, chips and status.
        googleMap.setPadding(0, (TOP_CHROME_DP * resources.displayMetrics.density).toInt(), 0, 0)
        val (pos, zoom) = MapData.loadCamera(this, prefs)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.lat, pos.lon), zoom))
        labelsVisible = googleMap.cameraPosition.zoom > CALLSIGN_ZOOM
        // Tapping a station opens the slide-up panel; the map stays in view.
        // Tapping a cluster lists its stations or zooms in on it.
        googleMap.setOnMarkerClickListener { m ->
            when (val tag = m.tag) {
                is ClusterTag -> openCluster(tag)
                is String -> { clusterCalls = emptyList(); selectedCall = tag }
            }
            true
        }
        googleMap.setOnCameraIdleListener {
            val zoom = googleMap.cameraPosition.zoom
            val visible = zoom > CALLSIGN_ZOOM
            if (visible != labelsVisible) {
                labelsVisible = visible
                for (mi in markers.values) mi.label.isVisible = visible
            }
            // The cluster grid is fixed to the map, so only a zoom change regroups stations.
            if (clusteredAtZoom.isNaN() || Math.abs(zoom - clusteredAtZoom) > 0.05f) render()
        }
        startLoading()
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

    private fun applyMode(mode: GoogleMapMode) {
        val gm = map ?: return
        gm.mapType = mode.mapType
        gm.setMapStyle(if (mode.quiet) MapStyleOptions(MapStyles.QUIET_JSON) else null)
    }

    private fun openCluster(tag: ClusterTag) {
        val gm = map ?: return
        val zoom = gm.cameraPosition.zoom
        if (tag.calls.size <= 6 || zoom >= 17f) {
            selectedCall = ""
            clusterCalls = tag.calls
        } else {
            gm.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(tag.lat, tag.lon), zoom + 2f))
        }
    }

    /** White disc with a navy ring and the station count, like the OSM map's clusters. */
    private fun clusterIcon(count: Int): BitmapDescriptor = icons.getOrPut("cluster:$count") {
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
        BitmapDescriptorFactory.fromBitmap(b)
    }

    private fun symbolIcon(symbol: String): BitmapDescriptor =
        icons.getOrPut(symbol) { BitmapDescriptorFactory.fromBitmap(AprsSymbols.bitmap(this, symbol, drawSize)) }

    /** Callsign label in the same style as the OSM map: navy text on a light pill. */
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
        val textH = fm.descent - fm.ascent
        val w = (textPaint.measureText(text) + 2 * padX).toInt().coerceAtLeast(1)
        val h = (textH + 2 * padY).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRoundRect(
            android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f,
            Paint().apply { color = 0xe6ffffff.toInt(); isAntiAlias = true },
        )
        c.drawText(text, padX, padY - fm.ascent, textPaint)
        return b
    }

    /** New data from the loader: remember it, then draw. */
    private fun onStationUpdate(stations: List<MapStation>) {
        dataVersion++
        isLoading = false
        stationCounts = MapFilters.counts(stations)
        shownStations = MapFilters.apply(stations, stationFilter, setOf(prefs.getCallSsid(), targetcall))
        render()
        followTarget()
    }

    /**
     * Draw [shownStations] for the current zoom: stations that would overlap
     * become cluster markers, the rest keep their own symbol, label and trace.
     */
    private fun render() {
        val gm = map ?: return
        val zoom = gm.cameraPosition.zoom
        clusteredAtZoom = zoom
        val mine = prefs.getCallSsid()
        val groups = MapCluster.cluster(
            shownStations, zoom.toDouble(), CLUSTER_CELL_DP.toDouble(),
            { it.pos.lat }, { it.pos.lon },
            { it.call == targetcall || it.call == mine },
        )

        for (m in clusterMarkers) m.remove()
        clusterMarkers.clear()
        val stations = ArrayList<MapStation>()
        for (g in groups) {
            if (!g.isCluster) {
                stations.add(g.items[0])
                continue
            }
            val marker = gm.addMarker(
                MarkerOptions()
                    .position(LatLng(g.lat, g.lon))
                    .anchor(0.5f, 0.5f)
                    .zIndex(1f)
                    .icon(clusterIcon(g.items.size)),
            ) ?: continue
            marker.tag = ClusterTag(g.items.map { it.call }, g.lat, g.lon)
            clusterMarkers.add(marker)
        }

        val seen = HashSet<String>()
        for (sta in stations) {
            seen.add(sta.call)
            val pos = LatLng(sta.pos.lat, sta.pos.lon)
            val mi = markers[sta.call]
            if (mi != null) {
                mi.icon.position = pos
                mi.icon.setIcon(symbolIcon(sta.symbol))
                mi.icon.snippet = sta.comment
                mi.label.position = pos
                updateTrace(gm, mi, sta)
            } else {
                val icon = gm.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .anchor(0.5f, 0.5f)
                        .icon(symbolIcon(sta.symbol))
                        .title(sta.callQrg())
                        .snippet(sta.comment),
                ) ?: continue
                val label = gm.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .anchor(0.5f, -0.9f)
                        .icon(BitmapDescriptorFactory.fromBitmap(labelBitmap(sta.call)))
                        .visible(labelsVisible),
                ) ?: continue
                icon.tag = sta.call
                label.tag = sta.call
                val info = MarkerInfo(icon, label, null)
                updateTrace(gm, info, sta)
                markers[sta.call] = info
            }
        }
        // Drop stations that aged out or were filtered away.
        val gone = markers.keys.filter { it !in seen }
        for (call in gone) {
            markers.remove(call)?.let { it.icon.remove(); it.label.remove(); it.trace?.remove() }
        }
    }

    private fun updateTrace(gm: GoogleMap, mi: MarkerInfo, sta: MapStation) {
        if (sta.trace.size < 2) {
            mi.trace?.remove()
            mi.trace = null
            return
        }
        val pts = sta.trace.map { LatLng(it.lat, it.lon) }
        val t = mi.trace
        if (t != null) {
            t.points = pts
        } else {
            mi.trace = gm.addPolyline(
                PolylineOptions()
                    .addAll(pts)
                    .color(0x806464ff.toInt())
                    .width(drawSize / 6f * 1.5f)
                    .geodesic(false),
            )
        }
    }

    private fun followTarget() {
        if (targetcall.isEmpty()) return
        val mi = markers[targetcall] ?: return
        val gm = map ?: return
        if (firstLoad) {
            gm.animateCamera(CameraUpdateFactory.newLatLngZoom(mi.icon.position, 14f))
            mi.icon.showInfoWindow()
            firstLoad = false
        } else {
            gm.animateCamera(CameraUpdateFactory.newLatLng(mi.icon.position))
        }
    }

    private fun centerOnMyLocation() {
        val loc = MapData.lastKnownLocation(this)
        if (loc == null) {
            Toast.makeText(this, R.string.map_no_location, Toast.LENGTH_SHORT).show()
            return
        }
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.lat, loc.lon), 14f))
    }

    private fun openDetails(call: String) {
        startActivity(Intent(this, StationActivity::class.java).setData(Uri.parse(call)))
    }

    // ---- MapView lifecycle forwarding ----

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        map?.cameraPosition?.let {
            MapData.saveCamera(prefs, it.target.latitude.toFloat(), it.target.longitude.toFloat(), it.zoom)
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
        Log.d(tag, "destroyed")
        super.onDestroy()
    }
}
