package org.aprsdroid.app

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One past position of a station. */
data class TrackPoint(val ts: Long, val lat: Double, val lon: Double)

/** One past weather report of a station. */
data class WeatherSample(val ts: Long, val weather: AprsWeather)

/** A station's history: where it has been and, for weather stations, what it reported. */
data class StationHistory(
    val track: List<TrackPoint>,
    val weather: List<WeatherSample>,
    /** Where it came from, for the user: "this device" or the history server's host. */
    val source: String,
)

/** Figures for the Track tab. Pure so it can be unit-tested. */
data class TrackStats(
    val points: Int,
    val distanceM: Double,
    /** Fastest leg in metres per second, or null with fewer than two usable points. */
    val topSpeedMps: Double?,
    val firstTs: Long?,
    val lastTs: Long?,
) {
    companion object {
        private const val EARTH_M = 6_371_000.0

        fun distanceM(a: TrackPoint, b: TrackPoint): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
            return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        /** @param track oldest first */
        fun of(track: List<TrackPoint>): TrackStats {
            var dist = 0.0
            var top: Double? = null
            for (i in 1 until track.size) {
                val d = distanceM(track[i - 1], track[i])
                dist += d
                val dt = (track[i].ts - track[i - 1].ts) / 1000.0
                // Legs shorter than 10 s give wild speeds from GPS jitter and duplicate packets.
                if (dt >= 10 && d > 0) {
                    val v = d / dt
                    if (v < 350 && (top == null || v > top)) top = v // 350 m/s: faster is a bad fix
                }
            }
            return TrackStats(track.size, dist, top, track.firstOrNull()?.ts, track.lastOrNull()?.ts)
        }
    }
}

/**
 * Reading history from a history server the user runs themselves.
 *
 * The app only knows what it has heard while running. A history server stays
 * connected to APRS-IS and stores packets, so it can answer "where has this
 * station been this week". The API is one GET; see doc/HISTORY-SERVER.md.
 */
object HistoryServer {

    /** The request URL for a station's history. */
    @JvmStatic
    fun url(base: String, call: String, hours: Int): String =
        base.trim().trimEnd('/') + "/v1/stations/" +
            java.net.URLEncoder.encode(call.trim().uppercase(), "UTF-8") + "/history?hours=" + hours

    /**
     * Parse a response. Unknown fields are ignored and missing arrays are
     * empty, so the server can grow without breaking the app.
     *
     * @throws org.json.JSONException if the body is not the expected JSON
     */
    @JvmStatic
    fun parse(json: String, source: String): StationHistory {
        val root = JSONObject(json)
        val track = ArrayList<TrackPoint>()
        root.optJSONArray("positions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.has("lat") || !o.has("lon") || !o.has("ts")) continue
                track.add(TrackPoint(o.getLong("ts") * 1000, o.getDouble("lat"), o.getDouble("lon")))
            }
        }
        val weather = ArrayList<WeatherSample>()
        root.optJSONArray("weather")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.has("ts")) continue
                // Either the raw APRS weather string, or already-decoded fields in APRS units.
                val w = o.optString("raw").takeIf { it.isNotEmpty() }?.let { AprsWeather.parse(it) }
                    ?: AprsWeather(
                        windDirDeg = o.intOrNull("wind_dir"),
                        windMph = o.intOrNull("wind_mph"),
                        gustMph = o.intOrNull("gust_mph"),
                        tempF = o.intOrNull("temp_f"),
                        rainHour = o.intOrNull("rain_1h"),
                        rain24h = o.intOrNull("rain_24h"),
                        rainToday = o.intOrNull("rain_today"),
                        humidityPct = o.intOrNull("humidity"),
                        pressureTenthsHpa = o.intOrNull("pressure_tenths_hpa"),
                        luminosity = o.intOrNull("luminosity"),
                    ).takeIf { it != AprsWeather() }
                if (w != null) weather.add(WeatherSample(o.getLong("ts") * 1000, w))
            }
        }
        return StationHistory(track.sortedBy { it.ts }, weather.sortedBy { it.ts }, source)
    }

    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
}
