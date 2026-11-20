package org.aprsdroid.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.v3.android.maps.mapgenerator.tiledownloader.TileDownloader
import org.mapsforge.v3.core.Tile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Google Map Tiles API (2D tiles) support for the Mapsforge map view.
 *
 * Unlike the Maps SDK, the Map Tiles API takes its key on each request,
 * so the key can be entered by the user at runtime (Settings → Map) instead
 * of being compiled into the app. A build-time default can be supplied via
 * `mapsTilesApiKey=` in local.properties.
 *
 * Note: Mapsforge's TileDownloader can only set the User-Agent header, so
 * the X-Android-Package / X-Android-Cert headers that Google needs for an
 * "Android apps" key restriction cannot be sent. Restrict the tiles key to
 * the Map Tiles API (API restriction) and set a quota instead.
 */
object GoogleTiles {

    private const val TAG = "APRSdroid.GTiles"
    private const val HOST = "tile.googleapis.com"

    /** Separate prefs file: excluded from profile export and from Android backup. */
    const val SECRETS_PREFS = "aprsdroid_secrets"
    const val KEY_PREF = "gmaps.tiles_key"

    enum class Type(val tag: String) {
        ROADMAP("gtiles_roadmap"),
        QUIET("gtiles_quiet"),
        SATELLITE("gtiles_satellite"),
        HYBRID("gtiles_hybrid"),
        TERRAIN("gtiles_terrain"),
    }

    data class Session(val token: String, val expiryMillis: Long)

    fun secrets(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)

    /** The user's key, falling back to the build-time default. Empty if none. */
    @JvmStatic
    fun apiKey(ctx: Context): String {
        val user = secrets(ctx).getString(KEY_PREF, "")?.trim().orEmpty()
        return user.ifEmpty { BuildConfig.MAPS_TILES_API_KEY }
    }

    @JvmStatic
    fun hasKey(ctx: Context): Boolean = apiKey(ctx).isNotEmpty()

    @JvmStatic
    fun setApiKey(ctx: Context, key: String) {
        secrets(ctx).edit()
            .putString(KEY_PREF, key.trim())
            .apply()
        clearSessions(ctx)
    }

    private fun clearSessions(ctx: Context) {
        val ed = secrets(ctx).edit()
        for (t in Type.values()) ed.remove("session.${t.tag}").remove("expiry.${t.tag}").remove("keyhash.${t.tag}")
        ed.apply()
    }

    private fun sessionRequest(type: Type): JSONObject {
        val locale = Locale.getDefault()
        val o = JSONObject()
        o.put("language", locale.toLanguageTag())
        if (locale.country.isNotEmpty()) o.put("region", locale.country)
        when (type) {
            Type.ROADMAP -> o.put("mapType", "roadmap")
            Type.QUIET -> {
                o.put("mapType", "roadmap")
                o.put("styles", JSONArray(MapStyles.QUIET_JSON))
            }
            Type.SATELLITE -> o.put("mapType", "satellite")
            Type.HYBRID -> {
                o.put("mapType", "satellite")
                o.put("layerTypes", JSONArray().put("layerRoadmap"))
                o.put("overlay", false)
            }
            Type.TERRAIN -> {
                o.put("mapType", "terrain")
                o.put("layerTypes", JSONArray().put("layerRoadmap"))
            }
        }
        return o
    }

    /**
     * Return a valid session token for [type], creating one if needed.
     * Blocking network call — run off the main thread.
     * @throws IOException with Google's error message on failure.
     */
    @JvmStatic
    fun session(ctx: Context, type: Type): Session {
        val key = apiKey(ctx)
        if (key.isEmpty()) throw IOException("No Google Map Tiles API key configured")
        val sp = secrets(ctx)
        val token = sp.getString("session.${type.tag}", null)
        val expiry = sp.getLong("expiry.${type.tag}", 0L)
        val keyHash = sp.getInt("keyhash.${type.tag}", 0)
        // Renew a day early so a long-running map never hits an expired token.
        if (token != null && keyHash == key.hashCode() &&
            expiry - 24 * 3600 * 1000L > System.currentTimeMillis()
        ) {
            return Session(token, expiry)
        }
        val resp = http(
            "https://$HOST/v1/createSession?key=${enc(key)}",
            sessionRequest(type).toString(),
        )
        val json = JSONObject(resp)
        val newToken = json.getString("session")
        // "expiry" is seconds since the epoch, as a string.
        val newExpiry = json.optString("expiry", "0").toLongOrNull()?.times(1000L)
            ?: (System.currentTimeMillis() + 7 * 24 * 3600 * 1000L)
        sp.edit()
            .putString("session.${type.tag}", newToken)
            .putLong("expiry.${type.tag}", newExpiry)
            .putInt("keyhash.${type.tag}", key.hashCode())
            .apply()
        Log.d(TAG, "new ${type.tag} session, expires $newExpiry")
        return Session(newToken, newExpiry)
    }

    /**
     * Google's required attribution text for the given viewport.
     * Blocking network call — run off the main thread. Returns null on error.
     */
    @JvmStatic
    fun attribution(ctx: Context, type: Type, zoom: Int, north: Double, south: Double, east: Double, west: Double): String? {
        return try {
            val s = session(ctx, type)
            val url = "https://$HOST/tile/v1/viewport?session=${enc(s.token)}&key=${enc(apiKey(ctx))}" +
                "&zoom=$zoom&north=$north&south=$south&east=$east&west=$west"
            JSONObject(http(url, null)).optString("copyright").ifEmpty { null }
        } catch (e: Exception) {
            Log.d(TAG, "attribution failed: $e")
            null
        }
    }

    /** Check that [key] works by creating a roadmap session. Returns null on success, else the error. */
    @JvmStatic
    fun testKey(key: String): String? {
        return try {
            JSONObject(
                http("https://$HOST/v1/createSession?key=${enc(key.trim())}", sessionRequest(Type.ROADMAP).toString()),
            ).getString("session")
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(url: String, postJson: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (postJson != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(postJson.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(body).getJSONObject("error").getString("message")
                } catch (_: Exception) {
                    body.take(200)
                }
                throw IOException("HTTP $code: $msg")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Mapsforge tile source for the Google Map Tiles API.
 *
 * Mapsforge keys its tile caches by host name only, and every Google map
 * type shares one host — `MapAct` flushes the caches when switching between
 * Google types so stale tiles from another type are not shown.
 */
class GoogleTileDownloader(
    private val session: String,
    private val key: String,
) : TileDownloader() {

    private val sb = StringBuilder()

    override fun getHostName(): String = "tile.googleapis.com"
    override fun getProtocol(): String = "https"
    override fun getPort(): Int = 443

    override fun getTilePath(tile: Tile): String {
        sb.setLength(0)
        sb.append("/v1/2dtiles/").append(tile.zoomLevel.toInt())
            .append('/').append(tile.tileX)
            .append('/').append(tile.tileY)
            .append("?session=").append(session)
            .append("&key=").append(key)
        return sb.toString()
    }

    override fun getZoomLevelMax(): Byte = 20
}
