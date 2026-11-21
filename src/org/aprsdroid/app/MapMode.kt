package org.aprsdroid.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.GoogleMap

/**
 * Map renderers ("map types") and dispatch to the activity that shows them.
 *
 * - OpenStreetMap and the vector maps render in [MapLibreAct].
 * - The Google Map Tiles types (and the coordinate chooser) still render in
 *   the Mapsforge-based [MapAct].
 * - The Google Maps SDK types render in [GoogleMapAct]. They need a
 *   build-time key (`mapsApiKey=` in local.properties) and Google Play
 *   services on the device.
 *
 * Types that lack what they need report `isAvailable() == false` and are
 * greyed out in the picker.
 */
open class MapMode(
    val tag: String,
    val titleRes: Int,
    val viewClass: Class<*>,
) {
    open fun isAvailable(ctx: Context): Boolean = true

    /** Short reason shown in the picker when [isAvailable] is false. */
    open fun unavailableReason(ctx: Context): String? = null
}

/** OpenStreetMap drawn by the old Mapsforge screen, kept as a fallback while the new one settles in. */
class MapsforgeOnlineMode(tag: String, titleRes: Int) : MapMode(tag, titleRes, MapAct::class.java)

/** OpenStreetMap and the vector maps, drawn by MapLibre in [MapLibreAct]. */
class MapLibreMode(val style: MapLibreStyle, titleRes: Int) : MapMode(style.tag, titleRes, MapLibreAct::class.java)

class GoogleTilesMode(
    tag: String,
    titleRes: Int,
    val tileType: GoogleTiles.Type,
) : MapMode(tag, titleRes, MapAct::class.java) {
    override fun isAvailable(ctx: Context): Boolean = GoogleTiles.hasKey(ctx)
    override fun unavailableReason(ctx: Context): String =
        ctx.getString(R.string.map_need_tiles_key)
}

class GoogleMapMode(
    tag: String,
    titleRes: Int,
    val mapType: Int,
    /** Muted colours and no points of interest, so station symbols stand out. */
    val quiet: Boolean = false,
) : MapMode(tag, titleRes, GoogleMapAct::class.java) {

    override fun isAvailable(ctx: Context): Boolean =
        BuildConfig.MAPS_SDK_KEY_SET && playServicesOk(ctx)

    override fun unavailableReason(ctx: Context): String =
        if (!BuildConfig.MAPS_SDK_KEY_SET) ctx.getString(R.string.map_need_sdk_key)
        else ctx.getString(R.string.map_need_play_services)

    private fun playServicesOk(ctx: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    } catch (_: Throwable) {
        false
    }
}

object MapModes {

    private val allMapModes = mutableListOf<MapMode>()

    @JvmStatic
    val all: List<MapMode>
        get() = allMapModes

    @JvmStatic
    fun initialize(ctx: Context) {
        if (allMapModes.isNotEmpty()) return
        allMapModes += MapLibreMode(MapLibreStyle.OSM, R.string.map_osm)
        allMapModes += MapLibreMode(MapLibreStyle.STREETS, R.string.map_ml_streets)
        allMapModes += MapLibreMode(MapLibreStyle.QUIET, R.string.map_ml_quiet)
        allMapModes += GoogleMapMode("google", R.string.map_google, GoogleMap.MAP_TYPE_NORMAL)
        allMapModes += GoogleMapMode("google_quiet", R.string.map_google_quiet, GoogleMap.MAP_TYPE_NORMAL, quiet = true)
        allMapModes += GoogleMapMode("satellite", R.string.map_satellite, GoogleMap.MAP_TYPE_HYBRID)
        allMapModes += GoogleMapMode("terrain", R.string.map_terrain, GoogleMap.MAP_TYPE_TERRAIN)
        allMapModes += GoogleTilesMode(GoogleTiles.Type.ROADMAP.tag, R.string.map_gtiles_roadmap, GoogleTiles.Type.ROADMAP)
        allMapModes += GoogleTilesMode(GoogleTiles.Type.QUIET.tag, R.string.map_gtiles_quiet, GoogleTiles.Type.QUIET)
        allMapModes += GoogleTilesMode(GoogleTiles.Type.SATELLITE.tag, R.string.map_gtiles_satellite, GoogleTiles.Type.SATELLITE)
        allMapModes += GoogleTilesMode(GoogleTiles.Type.HYBRID.tag, R.string.map_gtiles_hybrid, GoogleTiles.Type.HYBRID)
        allMapModes += GoogleTilesMode(GoogleTiles.Type.TERRAIN.tag, R.string.map_gtiles_terrain, GoogleTiles.Type.TERRAIN)
        allMapModes += MapsforgeOnlineMode("osm_classic", R.string.map_osm_classic)
    }

    @JvmStatic
    fun reloadOfflineMaps(ctx: Context) {
        // No-op — offline map discovery is handled by the tilepath preference.
    }

    @JvmStatic
    fun defaultMapMode(ctx: Context, prefs: PrefsWrapper): MapMode {
        initialize(ctx)
        val tag = prefs.getString("mapmode", "osm")
        var default: MapMode? = null
        for (mode in allMapModes) {
            val avail = mode.isAvailable(ctx)
            if (default == null && avail) default = mode
            if (mode.tag == tag && avail) return mode
        }
        Log.d("MapModes", "mode $tag unavailable, using ${default?.tag}")
        return default ?: allMapModes.first()
    }

    private fun mapIntent(ctx: Context, mm: MapMode, targetcall: String?): Intent {
        val intent = Intent(ctx, mm.viewClass)
        if (!targetcall.isNullOrEmpty()) {
            intent.data = Uri.parse(targetcall)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return intent
    }

    @JvmStatic
    fun startMap(ctx: Context, prefs: PrefsWrapper, targetcall: String?) {
        val mm = defaultMapMode(ctx, prefs)
        ctx.startActivity(mapIntent(ctx, mm, targetcall))
        if (ctx is Activity) {
            @Suppress("DEPRECATION")
            ctx.overridePendingTransition(0, 0)
        }
    }

    /**
     * Make [mode] the default. If it is rendered by a different activity
     * than [current], open that activity (keeping the tracked call) and
     * finish [current]; returns true in that case. Returns false when the
     * caller should just re-apply the mode itself.
     */
    @JvmStatic
    fun switchTo(current: Activity, prefs: PrefsWrapper, mode: MapMode, targetcall: String?): Boolean {
        setDefault(prefs, mode.tag)
        if (mode.viewClass == current.javaClass) return false
        val intent = mapIntent(current, mode, targetcall)
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT.inv()
        current.startActivity(intent)
        current.finish()
        @Suppress("DEPRECATION")
        current.overridePendingTransition(0, 0)
        return true
    }

    @JvmStatic
    fun setDefault(prefs: PrefsWrapper, tag: String) {
        prefs.set("mapmode", tag)
    }
}

/** Map styling shared by the Google Maps SDK map and Google map tiles. */
object MapStyles {
    /**
     * A quiet base map: nearly grey, with points of interest, transit and road
     * shields hidden, so the APRS symbols are the only colour on the map.
     */
    const val QUIET_JSON = """[
  {"stylers":[{"saturation":-85},{"lightness":8}]},
  {"featureType":"poi","stylers":[{"visibility":"off"}]},
  {"featureType":"transit","stylers":[{"visibility":"off"}]},
  {"featureType":"road","elementType":"labels.icon","stylers":[{"visibility":"off"}]}
]"""
}
