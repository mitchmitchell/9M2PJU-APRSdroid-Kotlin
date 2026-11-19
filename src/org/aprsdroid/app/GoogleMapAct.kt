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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
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
                if (trackedCall.isNotEmpty()) {
                    org.aprsdroid.app.ui.TrackingChip(
                        call = trackedCall,
                        onStop = { targetcall = ""; reload() },
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    )
                }
                org.aprsdroid.app.ui.MapControlStack(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    MapTypeMenu(currentTag = currentModeTag) { mode ->
                        if (!MapModes.switchTo(this@GoogleMapAct, prefs, mode, targetcall)) {
                            currentModeTag = mode.tag
                            (mode as? GoogleMapMode)?.let { map?.mapType = it.mapType }
                        }
                    }
                    IconButton(onClick = {
                        showObjects = prefs.toggleBoolean("show_objects", true)
                        reload()
                    }) {
                        Icon(
                            if (showObjects) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (showObjects) "Hide objects" else "Show objects",
                        )
                    }
                    IconButton(onClick = { centerOnMyLocation() }) {
                        Icon(Icons.Filled.MyLocation, contentDescription = "Go to my location")
                    }
                    AprsOverflowMenu(
                        showAgeFilter = true,
                        onPreferences = { AprsNavigation.openPreferences(this@GoogleMapAct) },
                        onAgeChanged = { reload() },
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }

    private fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        (MapModes.defaultMapMode(this, prefs) as? GoogleMapMode)?.let { googleMap.mapType = it.mapType }
        googleMap.uiSettings.isCompassEnabled = true
        googleMap.uiSettings.isZoomControlsEnabled = true
        val (pos, zoom) = MapData.loadCamera(this, prefs)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.lat, pos.lon), zoom))
        labelsVisible = googleMap.cameraPosition.zoom > CALLSIGN_ZOOM
        googleMap.setOnInfoWindowClickListener { m -> (m.tag as? String)?.let { openDetails(it) } }
        googleMap.setOnCameraIdleListener {
            val visible = googleMap.cameraPosition.zoom > CALLSIGN_ZOOM
            if (visible != labelsVisible) {
                labelsVisible = visible
                for (mi in markers.values) mi.label.isVisible = visible
            }
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

    private fun symbolIcon(symbol: String): BitmapDescriptor =
        icons.getOrPut(symbol) { BitmapDescriptorFactory.fromBitmap(AprsSymbols.bitmap(this, symbol, drawSize)) }

    /** Callsign label in the same style as the OSM map (black text, light green halo). */
    private fun labelBitmap(text: String): Bitmap {
        val fontSize = drawSize * 7f / 8f
        val textPaint = Paint().apply {
            color = 0xff000000.toInt()
            textSize = fontSize
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val strokePaint = Paint(textPaint).apply {
            color = 0xffc8ffc8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = drawSize / 6f
        }
        val pad = drawSize / 6
        val w = textPaint.measureText(text).toInt() + 2 * pad
        val h = (fontSize * 1.3f).toInt() + 2 * pad
        val b = Bitmap.createBitmap(w.coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val y = pad - textPaint.fontMetrics.ascent
        c.drawText(text, pad.toFloat(), y, strokePaint)
        c.drawText(text, pad.toFloat(), y, textPaint)
        return b
    }

    private fun onStationUpdate(stations: List<MapStation>) {
        isLoading = false
        val gm = map ?: return
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
                        .anchor(0.5f, -0.4f)
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
        followTarget()
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
