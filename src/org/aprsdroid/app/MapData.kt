package org.aprsdroid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.location.LocationManager
import kotlinx.coroutines.runBlocking
import org.aprsdroid.app.data.AprsDatabase

/** A latitude/longitude pair in degrees. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * A station as shown on any map renderer (Mapsforge/OSM, Google tiles,
 * Google Maps SDK). Renderer-neutral so every map shares one loader.
 */
data class MapStation(
    val call: String,
    val origin: String?,
    val symbol: String,
    val pos: LatLon,
    val qrg: String?,
    val comment: String?,
    val speed: Int?,
    val course: Int?,
    /** Recent positions, oldest first; empty or single-point means "no trace". */
    val trace: List<LatLon>,
) {
    fun callQrg(): String = if (!qrg.isNullOrEmpty()) "$call $qrg" else call
}

/**
 * Shared map logic, extracted from `MapAct` so the OSM and Google map
 * screens load and draw stations the same way.
 */
object MapData {

    private const val MAX_TRACE_POINTS = 100

    /**
     * Load stations for a map. Blocking — call from a background thread.
     *
     * Mirrors the Scala `MapLoaderBase.load_stations` filter: stations heard
     * within the age filter (objects only when [showObjects]), plus our own
     * station and the tracked [targetcall] regardless of age.
     */
    @JvmStatic
    fun loadStations(ctx: Context, prefs: PrefsWrapper, targetcall: String, showObjects: Boolean): List<MapStation> {
        val db = AprsDatabase.get(ctx)
        val ageTs = System.currentTimeMillis() - prefs.getShowAge()
        val (rows, positions) = runBlocking {
            Pair(
                db.stationDao().mapStations(ageTs, prefs.getCallSsid(), targetcall, showObjects),
                db.positionDao().positionsSince(ageTs),
            )
        }
        val traces = HashMap<String, ArrayList<LatLon>>()
        for (p in positions) {
            val list = traces.getOrPut(p.call) { ArrayList() }
            val pt = LatLon(p.lat / 1_000_000.0, p.lon / 1_000_000.0)
            if (list.isEmpty() || list.last() != pt) list.add(pt)
        }
        return rows.filter { it.call.isNotEmpty() }.map { sta ->
            val trace = traces[sta.call].orEmpty()
            MapStation(
                call = sta.call,
                origin = sta.origin,
                symbol = AprsSymbols.validate(sta.symbol),
                pos = LatLon(sta.lat / 1_000_000.0, sta.lon / 1_000_000.0),
                qrg = sta.qrg,
                comment = sta.comment,
                speed = sta.speed,
                course = sta.course,
                trace = if (trace.size > MAX_TRACE_POINTS) trace.takeLast(MAX_TRACE_POINTS) else trace,
            )
        }
    }

    /** Last stored map centre/zoom, or the device's last fix, or Berlin. */
    @JvmStatic
    fun loadCamera(ctx: Context, prefs: PrefsWrapper): Pair<LatLon, Float> {
        val savedLat = prefs.prefs.getFloat("map_lat", Float.NaN)
        val savedLon = prefs.prefs.getFloat("map_lon", Float.NaN)
        val zoom = prefs.prefs.getFloat("map_zoom", 14.0f)
        if (!savedLat.isNaN() && !savedLon.isNaN()) {
            return Pair(LatLon(savedLat.toDouble(), savedLon.toDouble()), zoom)
        }
        return Pair(lastKnownLocation(ctx) ?: LatLon(52.5075, 13.39027), zoom)
    }

    @JvmStatic
    fun saveCamera(prefs: PrefsWrapper, lat: Float, lon: Float, zoom: Float) {
        prefs.prefs.edit()
            .putFloat("map_lat", lat)
            .putFloat("map_lon", lon)
            .putFloat("map_zoom", zoom)
            .apply()
    }

    @JvmStatic
    fun lastKnownLocation(ctx: Context): LatLon? {
        return try {
            val locMan = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = PeriodicGPS.bestProvider(locMan) ?: return null
            val loc = locMan.getLastKnownLocation(provider) ?: return null
            LatLon(loc.latitude, loc.longitude)
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * APRS symbol sprite helpers for the `allicons` sheet (16 columns;
 * 6 rows primary table, 6 rows alternate table, 6 rows overlay characters).
 */
object AprsSymbols {

    private var sheet: Bitmap? = null
    private val cache = HashMap<String, Bitmap>()

    @JvmStatic
    fun sheet(ctx: Context): Bitmap {
        return sheet ?: synchronized(this) {
            sheet ?: BitmapFactory.decodeResource(
                ctx.resources, R.drawable.allicons,
                BitmapFactory.Options().apply { inScaled = false },
            ).also { sheet = it }
        }
    }

    /** Return [symbol] if it is a valid table+code pair, else the red-X symbol. */
    @JvmStatic
    fun validate(symbol: String?): String {
        if (symbol == null || symbol.length != 2) return "/$"
        val table = symbol[0]
        val code = symbol[1]
        return if (code in '!'..'~' && "\\/0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".contains(table)) symbol else "/."
    }

    @JvmStatic
    fun isOverlayed(symbol: String): Boolean = symbol[0] != '/' && symbol[0] != '\\'

    @JvmStatic
    fun rect(index: Int, page: Int, symbolSize: Int): Rect {
        if (index < 0 || index >= 6 * 16) return Rect(0, 0, symbolSize, symbolSize)
        val y = (index / 16 + 6 * page) * symbolSize
        val x = (index % 16) * symbolSize
        return Rect(x, y, x + symbolSize, y + symbolSize)
    }

    @JvmStatic
    fun rect(symbol: String, symbolSize: Int): Rect =
        rect(symbol[1].code - 33, if (symbol[0] == '/') 0 else 1, symbolSize)

    /** Rect of the overlay character for an overlayed symbol. */
    @JvmStatic
    fun overlayRect(symbol: String, symbolSize: Int): Rect =
        rect(symbol[0].code - 33, 2, symbolSize)

    /** Render [symbol] (with overlay character, if any) to a [size]×[size] bitmap. Cached. */
    @JvmStatic
    fun bitmap(ctx: Context, symbol: String, size: Int): Bitmap {
        val key = "$symbol@$size"
        synchronized(cache) { cache[key]?.let { return it } }
        val src = sheet(ctx)
        val symbolSize = src.width / 16
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.TRANSPARENT)
        val c = Canvas(b)
        val dst = Rect(0, 0, size, size)
        c.drawBitmap(src, rect(symbol, symbolSize), dst, null)
        if (isOverlayed(symbol)) c.drawBitmap(src, overlayRect(symbol, symbolSize), dst, null)
        synchronized(cache) { cache[key] = b }
        return b
    }
}
