package org.aprsdroid.app

import org.aprsdroid.app.ui.MapStationLayer
import org.aprsdroid.app.ui.MapButton
import org.aprsdroid.app.ui.AprsBottomBar
import org.aprsdroid.app.ui.AprsOverflowMenu
import org.aprsdroid.app.ui.MapTypeMenu

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.LocationManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import org.mapsforge.v3.android.maps.MapActivity
import org.mapsforge.v3.android.maps.MapView
import org.mapsforge.v3.android.maps.Projection
import org.mapsforge.v3.android.maps.overlay.ItemizedOverlay
import org.mapsforge.v3.android.maps.overlay.OverlayItem
import org.mapsforge.v3.core.GeoPoint
import java.io.File
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.aprsdroid.app.data.AprsDatabase

/**
 * Kotlin/Compose port of the Scala `MapAct` + `MapMenuHelper` + `StationOverlay`.
 *
 * OSM map view using MapsForge v3, wrapped in a Jetpack Compose
 * `AndroidView`. Shows APRS stations as symbols from the allicons
 * sprite sheet, with callsign labels and movement traces. Supports
 * offline map files, online OSM tiles, coordinate chooser mode, and
 * tap-to-open station details.
 */
class MapAct : MapActivity(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val tag = "APRSdroid.Map"

    private val prefs by lazy { PrefsWrapper(this) }
    private val db by lazy { AprsDatabase.get(this) }

    private var trackedCall by mutableStateOf("")
    /** Station shown in the slide-up panel ("" = none). */
    private var selectedCall by mutableStateOf("")
    /** Stations of a tapped cluster, offered as a list in the panel. */
    private var clusterCalls by mutableStateOf(emptyList<String>())
    private var stationFilter by mutableStateOf(StationFilter.ALL)
    private var stationCounts by mutableStateOf(StationCounts(0, 0, 0))
    /** Bumped on every station reload so the panel refreshes. */
    private var dataVersion by mutableStateOf(0)
    private var targetcall: String
        get() = trackedCall
        set(v) { trackedCall = v }
    private var showObjects = false
    private val isCoordinateChooser: Boolean by lazy { callingActivity != null }

    internal var mapview: MapView? = null
    private val allicons by lazy { ContextCompat.getDrawable(this, R.drawable.allicons) as BitmapDrawable }
    private val staoverlay by lazy { StationOverlay(allicons, this) }

    // --- Compose lifecycle support ---
    // MapActivity (from MapsForge) is a plain Activity, not ComponentActivity,
    // so we need to provide Lifecycle/ViewModelStore/SavedStateRegistry for ComposeView.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val _viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val locReceiver by lazy {
        LocationReceiver2(
            { loadStations() },
            { stations -> staoverlay.replaceStations(stations) },
            { /* cancel: nop */ },
        )
    }

    private val resultIntent = Intent()

    // Compose state for UI elements
    private var isLoading by mutableStateOf(true)
    private var coordInfo by mutableStateOf("")
    private var acceptEnabled by mutableStateOf(false)
    private var showObjectsChecked by mutableStateOf(false)
    private var currentModeTag by mutableStateOf("osm")
    private var attribution by mutableStateOf<String?>(null)

    private val uiScope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val attributionRunnable = Runnable { refreshAttribution() }
    private var lastAttributionKey = ""
    /** Mapsforge's own persistence setting, restored when leaving Google tiles. */
    private var defaultCachePersistent: Boolean? = null

    /** The map type this activity is showing (OSM or a Google tiles type). */
    private fun currentMode(): MapMode {
        MapModes.initialize(this)
        return MapModes.all.firstOrNull { it.tag == currentModeTag && it.viewClass == MapAct::class.java }
            ?: MapModes.all.first { it.tag == "osm" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(savedInstanceState)
        UIHelper.applySystemBarInsets(this)

        targetcall = intent.dataString ?: ""
        showObjects = prefs.getShowObjects()
        val mode = MapModes.defaultMapMode(this, prefs)
        // The coordinate chooser always opens here, even when the default
        // map type is a Google Maps SDK type — fall back to OSM then.
        currentModeTag = if (mode.viewClass == MapAct::class.java) mode.tag else "osm"
        showObjectsChecked = showObjects

        setResult(RESULT_CANCELED)

        // MapActivity is a plain Activity, so nothing registers the lifecycle
        // owners on the view tree for us. Compose looks them up there
        // (ViewTreeLifecycleOwner etc.) and crashes if they are missing. Set
        // them on the decor view so popups and dialogs find them too.
        window.decorView.let { root ->
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeViewModelStoreOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)
        }
        val composeView = ComposeView(this)
        composeView.setContent {
            org.aprsdroid.app.ui.theme.AprsTheme { MapScreen() }
        }
        setContentView(composeView)

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        org.mapsforge.v3.android.maps.mapgenerator.tiledownloader.TileDownloader.onError = { onTileError(it) }
        mapview?.requestFocus()
    }

    override fun onPause() {
        org.mapsforge.v3.android.maps.mapgenerator.tiledownloader.TileDownloader.onError = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        val mv = mapview
        if (mv != null) {
            val pos = mv.mapPosition.mapPosition
            if (pos?.geoPoint != null) {
                MapData.saveCamera(
                    prefs,
                    pos.geoPoint.latitudeE6 / 1000000.0f,
                    pos.geoPoint.longitudeE6 / 1000000.0f,
                    pos.zoomLevel.toFloat(),
                )
            }
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        savedStateRegistryController.performSave(outState)
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    /** The online tile source in use, kept so a request in flight can be cut when the map closes. */
    private var tileSource: org.mapsforge.v3.android.maps.mapgenerator.tiledownloader.TileDownloader? = null
    private var lastTileErrorShown = 0L

    /**
     * Tile servers (OpenStreetMap's in particular) require a User-Agent that
     * identifies the app and gives a contact, and block generic ones.
     */
    private fun tileUserAgent(): String =
        "APRSDroid-TAK/${BuildConfig.VERSION_NAME} (+https://github.com/mitchmitchell/APRSDroid-TAK)"

    /** Tell the user why the map is blank, at most once a minute. Called on the map worker thread. */
    private fun onTileError(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastTileErrorShown < 60_000) return
        lastTileErrorShown = now
        runOnUiThread {
            if (!isFinishing) {
                Toast.makeText(this, "Map tiles could not be loaded: $reason", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        // MapView.destroy() waits for the tile worker thread on the main thread.
        // Cut any request it is stuck in first, so closing the map cannot freeze the app.
        tileSource?.abort()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        _viewModelStore.clear()
        handler.removeCallbacks(attributionRunnable)
        uiScope.cancel()
        locReceiver.cancel()
        try {
            unregisterReceiver(locReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MapScreen() {
        Scaffold(
            topBar = {
                // Only the position picker has a title bar; the normal map is
                // map-first and its controls float on the map.
                if (isCoordinateChooser) {
                    TopAppBar(title = { Text(getString(R.string.p_source_from_map)) })
                }
            },
            bottomBar = {
                if (!isCoordinateChooser) {
                    AprsBottomBar(
                        current = NavTarget.MAP,
                        onNavigate = { target -> AprsNavigation.navigateTo(this, target, prefs) },
                        onPreferences = { AprsNavigation.openPreferences(this) },
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // MapsForge MapView wrapped in AndroidView
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).also { mv ->
                            // Mapsforge only handles touch gestures (drag, pinch
                            // zoom, double-tap) on a clickable view. The old XML
                            // layout set these; a view created in code must too.
                            mv.isClickable = true
                            mv.isFocusable = true
                            mv.isFocusableInTouchMode = true
                            // The normal map has its own round zoom buttons; the position picker has no chrome.
                            mv.setBuiltInZoomControls(isCoordinateChooser)
                            mv.overlays.add(staoverlay)
                            mv.setTextScale(resources.displayMetrics.density)
                            mapview = mv
                            applyHardwareAcceleration(
                                prefs.getBoolean("hardware_acceleration", true),
                            )
                            reloadMapAndTheme()
                            startLoading()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (!isCoordinateChooser) {
                    org.aprsdroid.app.ui.MapButtonColumn(
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    ) {
                        MapButton {
                            IconButton(onClick = { changeZoom(1) }) {
                                Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                            }
                        }
                        MapButton {
                            IconButton(onClick = { changeZoom(-1) }) {
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
                                if (!MapModes.switchTo(this@MapAct, prefs, mode, targetcall)) {
                                    currentModeTag = mode.tag
                                    reloadMapAndTheme()
                                }
                            }
                        }
                        MapButton {
                            AprsOverflowMenu(
                                showAgeFilter = true,
                                onPreferences = { AprsNavigation.openPreferences(this@MapAct) },
                                onAgeChanged = { reloadMap() },
                            )
                        }
                    }
                    MapStationLayer(
                        selectedCall = selectedCall,
                        clusterCalls = clusterCalls,
                        followedCall = trackedCall,
                        refreshKey = dataVersion,
                        filter = stationFilter,
                        counts = stationCounts,
                        onFilter = { stationFilter = it; reloadMap() },
                        showObjects = showObjectsChecked,
                        onToggleObjects = {
                            val newState = prefs.toggleBoolean("show_objects", true)
                            showObjectsChecked = newState
                            showObjects = newState
                            reloadMap()
                        },
                        onFound = { sta ->
                            clusterCalls = emptyList()
                            selectedCall = sta.call
                            mapview?.let { mv ->
                                mv.controller.setCenter(GeoPoint(sta.lat, sta.lon))
                                if (mv.mapPosition.zoomLevel < 13) mv.controller.setZoom(13)
                            }
                        },
                        onPick = { call -> clusterCalls = emptyList(); selectedCall = call },
                        onDismiss = { selectedCall = ""; clusterCalls = emptyList() },
                        onFollow = { call -> targetcall = call; reloadMap() },
                        onStopFollowing = { targetcall = ""; reloadMap() },
                    )
                }

                // Loading indicator
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                // Google Map Tiles attribution (required by Google's terms)
                attribution?.let { text ->
                    Text(
                        text = "Google  $text",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(Color(0xB3FFFFFF))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }

                // Coordinate chooser overlay
                if (isCoordinateChooser) {
                    // Crosshair
                    org.aprsdroid.app.ui.MapCrosshair(Modifier.align(Alignment.Center))
                    // Info text
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .align(Alignment.TopCenter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (coordInfo.isNotEmpty()) {
                            androidx.compose.material3.Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shadowElevation = 6.dp,
                            ) {
                                Text(
                                    text = coordInfo,
                                    style = org.aprsdroid.app.ui.theme.PacketStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    // Accept button
                    AcceptButton(
                        enabled = acceptEnabled,
                        onClick = {
                            updateResultIntent()
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun AcceptButton(
        enabled: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val ctx = LocalContext.current
        val label = try {
            ctx.getString(intent.getIntExtra("info", 0))
        } catch (_: Exception) {
            getString(R.string.p_source_from_map_save)
        }
        androidx.compose.material3.Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            Text(label)
        }
    }

    private fun applyHardwareAcceleration(useHwAccel: Boolean) {
        val mv = mapview ?: return
        if (useHwAccel) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            )
            Log.d(tag, "Hardware acceleration enabled.")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            mv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            Log.d(tag, "Hardware acceleration disabled for MapView.")
        }
    }

    private fun centerOnMyLocation() {
        try {
            val locMan = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = PeriodicGPS.bestProvider(locMan)
            if (provider != null) {
                val loc = locMan.getLastKnownLocation(provider)
                if (loc != null) {
                    mapview?.controller?.setCenter(GeoPoint(loc.latitude, loc.longitude))
                    mapview?.controller?.setZoom(14)
                    return
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
        Toast.makeText(this, R.string.map_no_location, Toast.LENGTH_SHORT).show()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
    }

    private fun loadMapViewPosition() {
        val mv = mapview ?: return
        val (pos, zoom) = MapData.loadCamera(this, prefs)
        mv.controller.setCenter(GeoPoint(pos.lat, pos.lon))
        mv.controller.setZoom(zoom.toInt())
    }

    private fun reloadMapAndTheme() {
        val mv = mapview ?: return
        val tilepath = prefs.getString("tilepath", "")
        val mapfilePath = if (tilepath.isNotEmpty()) tilepath
            else prefs.getString("mapfile", "${Environment.getExternalStorageDirectory()}/aprsdroid.map")
        val mapfile = File(mapfilePath)
        val isMapFileValid = mapfilePath.endsWith(".map") && mapfile.exists() && mapfile.canRead()

        if (prefs.isOfflineMap()) {
            try {
                if (isMapFileValid) {
                    val result = mv.setMapFile(mapfile)
                    if (!result.isSuccess) {
                        Toast.makeText(this, result.errorMessage ?: "", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    loadOnlineMap()
                }
            } catch (e: Exception) {
                Log.e(tag, "Unexpected error during map reload", e)
                loadOnlineMap()
            }
        } else {
            loadOnlineMap()
        }

        val themefile = File(prefs.getString("themefile", "${Environment.getExternalStorageDirectory()}/aprsdroid.xml"))
        if (themefile.exists()) {
            try {
                mv.setRenderTheme(themefile)
            } catch (e: Exception) {
                // FileNotFoundException, or UnsupportedOperationException when an
                // online tile source is active (render themes only apply to .map files).
                Log.d(tag, "render theme not applied: $e")
            }
        }
        loadMapViewPosition()
    }

    private fun loadOnlineMap() {
        val mv = mapview ?: return
        if (mv.mapFile != null) return
        val mode = currentMode()
        if (mode is GoogleTilesMode) {
            loadGoogleTiles(mode)
            return
        }
        attribution = null
        try {
            val mapGen = OsmTileDownloader.create(this)
            mapGen.userAgent = tileUserAgent()
            tileSource = mapGen
            mv.setMapGenerator(mapGen)
        } catch (e: Exception) {
            Log.e(tag, "could not set the OSM tile source", e)
            return
        }
        applyCacheSettings(mv, mode, persistent = null)
    }

    /** Create/refresh the Google session off the main thread, then switch the tile source. */
    private fun loadGoogleTiles(mode: GoogleTilesMode) {
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(GoogleTiles.session(this@MapAct, mode.tileType))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val mv = mapview ?: return@launch
            result.onSuccess { session ->
                val gen = GoogleTileDownloader(session.token, GoogleTiles.apiKey(this@MapAct))
                gen.userAgent = tileUserAgent()
                tileSource = gen
                try {
                    mv.setMapGenerator(gen)
                } catch (e: Exception) {
                    Log.e(tag, "could not set the Google tile source", e)
                    return@onSuccess
                }
                // Google's terms limit storing tiles: keep them out of the persistent cache.
                applyCacheSettings(mv, mode, persistent = false)
                lastAttributionKey = ""
                scheduleAttribution()
            }.onFailure { e ->
                Log.w(tag, "Google tiles unavailable", e)
                Toast.makeText(
                    this@MapAct,
                    getString(R.string.map_gtiles_error, e.message ?: e.toString()),
                    Toast.LENGTH_LONG,
                ).show()
                currentModeTag = "osm"
                loadOnlineMap()
            }
        }
    }

    /**
     * Keep each tile source's cached tiles apart.
     *
     * Mapsforge 0.3 keys cached tiles by host name plus the "job
     * parameters" (render theme + text scale), every Google map type shares
     * one host, and its caches cannot be flushed (setCapacity throws
     * UnsupportedOperationException on the in-memory cache). Downloaded
     * tiles ignore the text scale, so give each source its own slightly
     * different value: that changes the cache key without changing how
     * anything is drawn. OSM keeps the plain density so its existing cache
     * stays valid.
     */
    private fun separateTileCache(mv: MapView, mode: MapMode) {
        val slot = if (mode is GoogleTilesMode) mode.tileType.ordinal + 1 else 0
        mv.setTextScale(resources.displayMetrics.density + slot * 0.001f)
    }

    /**
     * Cache housekeeping after the tile source is set. Best effort: a
     * failure here must never stop the map from showing.
     * @param persistent null restores Mapsforge's own setting.
     */
    private fun applyCacheSettings(mv: MapView, mode: MapMode, persistent: Boolean?) {
        try {
            separateTileCache(mv, mode)
        } catch (e: Exception) {
            Log.w(tag, "could not separate tile cache", e)
        }
        try {
            val cache = mv.fileSystemTileCache
            if (defaultCachePersistent == null) defaultCachePersistent = cache.isPersistent
            val want = persistent ?: defaultCachePersistent ?: return
            if (cache.isPersistent != want) cache.setPersistent(want)
        } catch (e: Exception) {
            Log.w(tag, "could not change tile cache persistence", e)
        }
    }

    /** Our own callsign, for the overlay (never clustered). */
    fun myCallForOverlay(): String = prefs.getCallSsid()

    /** The followed callsign, for the overlay (drawn first and highlighted). */
    fun trackedCallForOverlay(): String = trackedCall

    /** Called from the overlay on every redraw; debounces attribution lookups. */
    fun onMapRedrawn() {
        if (currentMode() !is GoogleTilesMode) return
        scheduleAttribution()
    }

    private fun scheduleAttribution() {
        handler.removeCallbacks(attributionRunnable)
        handler.postDelayed(attributionRunnable, 1500)
    }

    private fun refreshAttribution() {
        val mode = currentMode() as? GoogleTilesMode ?: run { attribution = null; return }
        val mv = mapview ?: return
        val proj = mv.projection ?: return
        if (mv.width == 0 || mv.height == 0) return
        val nw = proj.fromPixels(0, 0) ?: return
        val se = proj.fromPixels(mv.width, mv.height) ?: return
        val zoom = mv.mapPosition.zoomLevel.toInt()
        val key = "${mode.tag}/$zoom/${nw.latitudeE6 / 10000}/${nw.longitudeE6 / 10000}"
        if (key == lastAttributionKey) return
        lastAttributionKey = key
        uiScope.launch {
            val text = withContext(Dispatchers.IO) {
                GoogleTiles.attribution(
                    this@MapAct, mode.tileType, zoom,
                    nw.latitude, se.latitude, se.longitude, nw.longitude,
                )
            }
            if (currentMode() == mode) attribution = text ?: attribution
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            // Back closes search or the station panel before leaving the map.
            KeyEvent.KEYCODE_BACK -> if (selectedCall.isNotEmpty() || clusterCalls.isNotEmpty()) {
                clusterCalls = emptyList()
                selectedCall = ""
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                changeZoom(+1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                changeZoom(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (isCoordinateChooser) {
                    updateResultIntent()
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun updateResultIntent() {
        val mv = mapview ?: return
        val pos = mv.mapPosition.mapPosition ?: return
        pos.geoPoint ?: return
        val lat = pos.geoPoint.latitudeE6 / 1000000.0f
        val lon = pos.geoPoint.longitudeE6 / 1000000.0f
        resultIntent.putExtra("lat", lat)
        resultIntent.putExtra("lon", lon)
        updateCoordinateInfo(lat, lon)
    }

    private fun updateCoordinateInfo(lat: Float, lon: Float) {
        if (!isCoordinateChooser) return
        val (latS, lonS) = AprsPacket.formatCoordinates(lat, lon)
        coordInfo = "$latS\n$lonS"
        acceptEnabled = true
    }

    fun updateCoordinateInfoFromMap() {
        if (!isCoordinateChooser) return
        val mv = mapview ?: return
        val pos = mv.mapPosition.mapPosition ?: return
        pos.geoPoint ?: return
        updateCoordinateInfo(
            pos.geoPoint.latitudeE6 / 1000000.0f,
            pos.geoPoint.longitudeE6 / 1000000.0f,
        )
    }

    fun changeZoom(delta: Int) {
        val mv = mapview ?: return
        mv.controller.setZoom(mv.mapPosition.zoomLevel + delta)
    }

    private fun animateToCall() {
        if (targetcall.isNotEmpty()) {
            Thread {
                val sta = runBlocking { db.stationDao().getStaPosition(targetcall) }
                if (sta != null) {
                    runOnUiThread {
                        // lat/lon are stored as microdegrees: use the (Int, Int) E6 constructor.
                        mapview?.controller?.setCenter(GeoPoint(sta.lat, sta.lon))
                    }
                }
            }.start()
        }
    }

    internal fun onPostLoad() {
        dataVersion++
        mapview?.invalidate()
        onStopLoading()
        animateToCall()
    }

    private fun startLoading() {
        val filter = IntentFilter(AprsService.UPDATE)
        // POSITION is sent after the station is written to the database, so a
        // reload triggered by it always sees the newest position.
        filter.addAction(AprsService.POSITION)
        UIHelper.safeRegisterReceiver(this, locReceiver, filter)
        locReceiver.startTask(null)
    }

    fun reloadMap() {
        onStartLoading()
        locReceiver.startTask(null)
    }

    fun onStartLoading() {
        isLoading = true
    }

    fun onStopLoading() {
        isLoading = false
    }

    /** A station was tapped: show it in the slide-up panel, keeping the map in view. */
    fun openDetails(call: String) {
        runOnUiThread {
            clusterCalls = emptyList()
            selectedCall = call
        }
    }

    /**
     * A cluster was tapped. A handful of stations (or a pile that will not
     * separate however far you zoom) is listed in the panel; a bigger cluster
     * zooms in on itself.
     */
    fun openCluster(calls: List<String>, lat: Double, lon: Double) {
        runOnUiThread {
            val mv = mapview ?: return@runOnUiThread
            val zoom = mv.mapPosition.zoomLevel.toInt()
            if (calls.size <= 6 || zoom >= 16) {
                selectedCall = ""
                clusterCalls = calls
            } else {
                mv.controller.setCenter(GeoPoint(lat, lon))
                mv.controller.setZoom(minOf(zoom + 2, 18))
            }
        }
    }

    /**
     * Load stations for the map overlay via the shared [MapData] loader.
     */
    private fun loadStations(): ArrayList<OSMStation> {
        val stations = ArrayList<OSMStation>()
        val all = MapData.loadStations(this, prefs, targetcall, showObjects)
        val counts = MapFilters.counts(all)
        runOnUiThread { stationCounts = counts }
        val keep = setOf(prefs.getCallSsid(), targetcall)
        for (sta in MapFilters.apply(all, stationFilter, keep)) {
            val movelog = ArrayList<GeoPoint>(sta.trace.size)
            for (p in sta.trace) movelog.add(GeoPoint(p.lat, p.lon))
            stations.add(OSMStation(movelog, GeoPoint(sta.pos.lat, sta.pos.lon), sta.call, sta.origin ?: "", sta.symbol))
        }
        Log.d(tag, "total ${stations.size} items")
        return stations
    }
}

/**
 * A station on the map overlay.
 */
class OSMStation(
    val movelog: ArrayList<GeoPoint>,
    pt: GeoPoint,
    val call: String,
    val origin: String,
    val symbol: String,
) : OverlayItem(pt, call, origin) {

    fun inArea(bl: GeoPoint, tr: GeoPoint): Boolean {
        val pt = point
        val latOk = bl.latitudeE6 <= pt.latitudeE6 && pt.latitudeE6 <= tr.latitudeE6
        val lonOk = if (bl.longitudeE6 <= tr.longitudeE6) {
            bl.longitudeE6 <= pt.longitudeE6 && pt.longitudeE6 <= tr.longitudeE6
        } else {
            bl.longitudeE6 <= pt.longitudeE6 || pt.longitudeE6 <= tr.longitudeE6
        }
        return latOk && lonOk
    }
}

/**
 * Custom ItemizedOverlay that renders APRS stations as symbols from
 * the allicons sprite sheet, with callsign labels and movement traces.
 */
/** Cluster grid cell, in dp. Circles are at most 0.46 of this in radius, so they never touch. */
private const val CLUSTER_CELL_DP = 64f

class StationOverlay(
    icons: Drawable,
    private val context: MapAct,
) : ItemizedOverlay<OSMStation>(icons) {

    private val tag = "APRSdroid.StaOverlay"

    var stations = ArrayList<OSMStation>()

    init {
        // prevent android bug #11666
        populate()
    }

    private val iconbitmap = (icons as BitmapDrawable).bitmap
    private val symbolSize = iconbitmap.width / 16
    private val drawSize = (context.resources.displayMetrics.density * 24).toInt()

    init {
        icons.setBounds(0, 0, symbolSize, symbolSize)
    }

    /** A cluster as last drawn, in screen pixels, for hit-testing taps. */
    private class ClusterHit(val x: Int, val y: Int, val r: Int, val lat: Double, val lon: Double, val calls: List<String>)

    @Volatile
    private var lastClusters: List<ClusterHit> = emptyList()

    override fun size(): Int = stations.size
    override fun createItem(idx: Int): OSMStation = stations[idx]

    private fun symbol2rect(index: Int, page: Int): Rect {
        if (index < 0 || index >= 6 * 16) return Rect(0, 0, symbolSize, symbolSize)
        val altOffset = page * symbolSize * 6
        val y = (index / 16) * symbolSize + altOffset
        val x = (index % 16) * symbolSize
        return Rect(x, y, x + symbolSize, y + symbolSize)
    }

    private fun symbol2rect(symbol: String): Rect {
        return symbol2rect(symbol[1].code - 33, if (symbol[0] == '/') 0 else 1)
    }

    private fun symbolIsOverlayed(symbol: String): Boolean {
        return symbol[0] != '/' && symbol[0] != '\\'
    }

    private fun drawTrace(c: Canvas, proj: Projection, s: OSMStation) {
        val tracePaint = Paint().apply {
            setARGB(128, 100, 100, 255)
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = drawSize / 6f
            isAntiAlias = true
        }
        val dotPaint = Paint().apply {
            setARGB(128, 255, 0, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        if (s.movelog.size < 2) return

        val path = Path()
        val point = Point()
        var first = true
        for (p in s.movelog) {
            proj.toPixels(p, point)
            if (first) {
                path.moveTo(point.x.toFloat(), point.y.toFloat())
                first = false
            } else {
                path.lineTo(point.x.toFloat(), point.y.toFloat())
            }
            c.drawCircle(point.x.toFloat(), point.y.toFloat(), drawSize / 12f, dotPaint)
        }
        c.drawPath(path, tracePaint)
    }

    override fun drawOverlayBitmap(c: Canvas, dp: Point, proj: Projection, zoom: Byte) {
        val mv = context.mapview ?: return
        if (!mv.mapPosition.isValid) return
        Log.d(tag, "draw: symbolSize=$symbolSize drawSize=$drawSize")
        // Labels: navy text on a light pill, readable on any map. Smaller than
        // the icon so they do not dominate, and dropped where they would cover
        // another label or icon, so a busy area stays legible. Zoom in to see more.
        val fontSize = drawSize * 0.58f
        val textPaint = Paint().apply {
            color = 0xff0d182d.toInt()
            textAlign = Paint.Align.CENTER
            textSize = fontSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }
        val pillPaint = Paint().apply {
            color = 0xe6ffffff.toInt()
            isAntiAlias = true
        }
        val trackedPillPaint = Paint(pillPaint).apply { color = 0xffebcf3a.toInt() }

        val p = Point()
        val width = c.width
        val height = c.height
        val ss = drawSize / 2
        val showDetail = zoom.toInt() >= 10
        val tracked = context.trackedCallForOverlay()

        // Group stations that would overlap at this zoom into clusters. The
        // followed station and our own are never absorbed.
        val mine = context.myCallForOverlay()
        val cell = (CLUSTER_CELL_DP * context.resources.displayMetrics.density).toDouble()
        val groups = MapCluster.cluster(
            stations, zoom.toDouble(), cell,
            { it.point.latitude }, { it.point.longitude },
            { it.call == tracked || it.call == mine },
        )

        // Visible single stations, the followed one first so it always gets its label.
        class Visible(val s: OSMStation, val x: Int, val y: Int)
        class VisibleCluster(val g: MapCluster.Group<OSMStation>, val x: Int, val y: Int, val r: Int)
        val visible = ArrayList<Visible>()
        val clusters = ArrayList<VisibleCluster>()
        val margin = (cell / 2).toInt()
        for (g in groups) {
            proj.toPixels(GeoPoint(g.lat, g.lon), p)
            if (p.x < -margin || p.y < -margin || p.x >= width + margin || p.y >= height + margin) continue
            if (g.isCluster) {
                // Bigger clusters get a slightly bigger circle, but never bigger than the cell.
                val r = (cell * (0.30 + 0.04 * minOf(g.items.size.toString().length, 4))).toInt()
                clusters.add(VisibleCluster(g, p.x, p.y, r))
            } else {
                visible.add(Visible(g.items[0], p.x, p.y))
            }
        }
        visible.sortByDescending { it.s.call == tracked }
        lastClusters = clusters.map { ClusterHit(it.x, it.y, it.r, it.g.lat, it.g.lon, it.g.items.map { s -> s.call }) }

        // 1. traces underneath everything
        if (showDetail) for (v in visible) drawTrace(c, proj, v.s)

        // 2. icons
        val taken = ArrayList<Rect>(visible.size * 2)
        for (v in visible) {
            val destRect = Rect(v.x - ss, v.y - ss, v.x + ss, v.y + ss)
            c.drawBitmap(iconbitmap, symbol2rect(v.s.symbol), destRect, null)
            if (symbolIsOverlayed(v.s.symbol)) {
                c.drawBitmap(iconbitmap, symbol2rect(v.s.symbol[0].code - 33, 2), destRect, null)
            }
            // Icons block labels with a slightly smaller box, so neighbours can still be named.
            taken.add(Rect(v.x - ss * 3 / 4, v.y - ss * 3 / 4, v.x + ss * 3 / 4, v.y + ss * 3 / 4))
        }

        // 2b. cluster circles: white disc, navy ring and count
        if (clusters.isNotEmpty()) {
            val disc = Paint().apply { color = 0xf7ffffff.toInt(); isAntiAlias = true }
            val ring = Paint().apply {
                color = 0xff1c2f51.toInt(); isAntiAlias = true
                style = Paint.Style.STROKE; strokeWidth = drawSize / 16f
            }
            val countPaint = Paint().apply {
                color = 0xff0d182d.toInt(); isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            for (cl in clusters) {
                c.drawCircle(cl.x.toFloat(), cl.y.toFloat(), cl.r.toFloat(), disc)
                c.drawCircle(cl.x.toFloat(), cl.y.toFloat(), cl.r.toFloat(), ring)
                countPaint.textSize = cl.r * 0.85f
                val fmc = countPaint.fontMetrics
                c.drawText(cl.g.items.size.toString(), cl.x.toFloat(), cl.y - (fmc.ascent + fmc.descent) / 2, countPaint)
                taken.add(Rect(cl.x - cl.r, cl.y - cl.r, cl.x + cl.r, cl.y + cl.r))
            }
        }

        // 3. labels, skipping any that would collide
        if (showDetail) {
            val padX = fontSize * 0.35f
            val padY = fontSize * 0.18f
            val fm = textPaint.fontMetrics
            val textH = fm.descent - fm.ascent
            for ((i, v) in visible.withIndex()) {
                val w = textPaint.measureText(v.s.call)
                val left = v.x - w / 2 - padX
                val top = v.y + ss + 2f
                val box = android.graphics.RectF(left, top, left + w + 2 * padX, top + textH + 2 * padY)
                val boxI = Rect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
                val isTracked = v.s.call == tracked
                var free = true
                if (!isTracked) {
                    for ((j, r) in taken.withIndex()) {
                        if (j == i) continue // own icon
                        if (Rect.intersects(r, boxI)) { free = false; break }
                    }
                }
                if (!free) continue
                taken.add(boxI)
                c.drawRoundRect(box, textH / 2, textH / 2, if (isTracked) trackedPillPaint else pillPaint)
                c.drawText(v.s.call, v.x.toFloat(), top + padY - fm.ascent, textPaint)
            }
        }
        context.runOnUiThread {
            context.updateCoordinateInfoFromMap()
            context.onMapRedrawn()
        }
    }

    fun replaceStations(s: ArrayList<OSMStation>) {
        stations = s
        Benchmark("populate") { populate() }
        context.onPostLoad()
    }

    override fun onTap(gp: GeoPoint, mv: MapView): Boolean {
        val proj = mv.projection
        val p = proj.toPixels(gp, null)
        // A cluster circle takes the tap before any single station near it.
        for (cl in lastClusters) {
            val dx = p.x - cl.x
            val dy = p.y - cl.y
            val reach = cl.r + 12
            if (dx * dx + dy * dy <= reach * reach) {
                context.openCluster(cl.calls, cl.lat, cl.lon)
                return true
            }
        }
        val clustered = HashSet<String>()
        for (cl in lastClusters) clustered.addAll(cl.calls)
        val botleft = proj.fromPixels(p.x - 50, p.y + 50)
        val topright = proj.fromPixels(p.x + 50, p.y - 50)
        Log.d(tag, "from $botleft to $topright")
        val list = stations.filter { it.call !in clustered && it.inArea(botleft, topright) }.map { it.call }
        Log.d(tag, "found ${list.size} stations")
        return when {
            list.isEmpty() -> false
            list.size == 1 -> {
                context.openDetails(list[0])
                true
            }
            else -> {
                context.openCluster(list, gp.latitude, gp.longitude)
                true
            }
        }
    }

    override fun onTap(index: Int): Boolean {
        val s = stations[index]
        Log.d(tag, "user clicked on ${s.call}")
        context.openDetails(s.call)
        return true
    }
}
