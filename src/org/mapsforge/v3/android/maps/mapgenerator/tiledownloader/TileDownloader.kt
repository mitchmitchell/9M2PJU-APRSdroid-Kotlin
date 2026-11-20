package org.mapsforge.v3.android.maps.mapgenerator.tiledownloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.mapsforge.v3.android.maps.mapgenerator.MapGenerator
import org.mapsforge.v3.android.maps.mapgenerator.MapGeneratorJob
import org.mapsforge.v3.core.GeoPoint
import org.mapsforge.v3.core.Tile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Replacement for the class of the same name in the vendored Mapsforge 0.3
 * jar (removed from the jar; see doc/MAPS-AND-ATAK.md). Same public shape,
 * so the jar's own subclasses and `MapView` keep working, with the faults of
 * the original fixed:
 *
 *  - The original set no timeouts. One stalled tile request blocked the map
 *    worker thread forever, and because `MapView.destroy()` joins that thread
 *    on the main thread, leaving the map froze the app ("isn't responding").
 *    Requests now time out, and [abort] cuts the request in flight.
 *  - A response that was not a 256x256 image (an error page, a "blocked"
 *    tile of another size) threw on the worker thread and crashed the app.
 *  - Failures were logged where nobody saw them. The reason for the latest
 *    failure is now reported through [onError].
 */
abstract class TileDownloader protected constructor() : MapGenerator {

    private val pixels = IntArray(TILE_SIZE * TILE_SIZE)

    /** Sent as the User-Agent header; tile servers require one that names the app. */
    open var userAgent: String? = null

    @Volatile
    private var active: HttpURLConnection? = null

    abstract fun getHostName(): String
    abstract fun getProtocol(): String
    abstract fun getPort(): Int
    abstract fun getTilePath(tile: Tile): String

    final override fun cleanup() {}

    /** Cut the request in flight, if any, so the worker thread is free to stop. Safe from any thread. */
    fun abort() {
        try {
            active?.disconnect()
        } catch (_: Exception) {
        }
    }

    final override fun executeJob(mapGeneratorJob: MapGeneratorJob, bitmap: Bitmap): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val protocol = getProtocol()
            var port = getPort()
            if (port == -1) port = if (protocol == "https") 443 else 80
            val url = URL(protocol, getHostName(), port, getTilePath(mapGeneratorJob.tile))
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            active = conn

            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                report("HTTP $status from ${url.host}")
                return false
            }
            val decoded = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            if (decoded == null) {
                report("${url.host} did not return an image")
                return false
            }
            try {
                if (decoded.width != TILE_SIZE || decoded.height != TILE_SIZE) {
                    report("${url.host} returned a ${decoded.width}x${decoded.height} tile")
                    return false
                }
                decoded.getPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
            } finally {
                decoded.recycle()
            }
            bitmap.setPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
            return true
        } catch (e: Exception) {
            // Includes timeouts, no network, and the disconnect from abort().
            report(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            return false
        } finally {
            active = null
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun report(reason: String) {
        Log.w(TAG, "tile failed: $reason")
        try {
            onError?.invoke(reason)
        } catch (_: Exception) {
        }
    }

    final override fun getStartPoint(): GeoPoint = START_POINT

    final override fun getStartZoomLevel(): Byte = START_ZOOM_LEVEL

    final override fun requiresInternetConnection(): Boolean = true

    companion object {
        private const val TAG = "TileDownloader"
        private const val TILE_SIZE = 256
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 8000
        private val START_POINT = GeoPoint(51.33, 10.45)

        private const val START_ZOOM_LEVEL: Byte = 5

        /** Called on the map worker thread with the reason a tile could not be loaded. */
        @JvmStatic
        @Volatile
        var onError: ((String) -> Unit)? = null
    }
}
