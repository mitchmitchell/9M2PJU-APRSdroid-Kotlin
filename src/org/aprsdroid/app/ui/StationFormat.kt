package org.aprsdroid.app.ui

import android.text.format.DateUtils

/** Display formatting shared by the station list, station details and the map panel. */
internal object StationFormat {

    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compass(bearingDeg: Float): String = COMPASS[((bearingDeg.toInt() + 22 + 720) % 360) / 45]

    fun distance(metres: Float, bearingDeg: Float, metric: Boolean): String {
        val d = if (metric) metres / 1000.0 else metres / 1609.344
        val unit = if (metric) "km" else "mi"
        val num = if (d < 100) "%.1f".format(d) else "%.0f".format(d)
        return "$num $unit ${compass(bearingDeg)}"
    }

    /** APRS speeds are in knots. */
    fun speed(knots: Int, metric: Boolean): String =
        if (metric) "%.0f km/h".format(knots * 1.852) else "%.0f mph".format(knots * 1.15078)

    fun altitude(metres: Int, metric: Boolean): String =
        if (metric) "$metres m" else "%.0f ft".format(metres / 0.3048)

    fun age(ts: Long): String = DateUtils.getRelativeTimeSpanString(
        ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

    fun position(latE6: Int, lonE6: Int): String {
        val lat = latE6 / 1e6
        val lon = lonE6 / 1e6
        return "%.4f° %s, %.4f° %s".format(
            Math.abs(lat), if (lat >= 0) "N" else "S", Math.abs(lon), if (lon >= 0) "E" else "W",
        )
    }

    /** A length along a track, from metres. */
    fun trackDistance(metres: Double, metric: Boolean): String =
        if (metric) "%.1f km".format(metres / 1000.0) else "%.1f mi".format(metres / 1609.344)

    /** A speed from metres per second. */
    fun speedMps(mps: Double, metric: Boolean): String =
        if (metric) "%.0f km/h".format(mps * 3.6) else "%.0f mph".format(mps * 2.236936)

    /** "3 h 20 min", "45 min", "2 d 4 h". */
    fun duration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 60 -> "$min min"
            min < 48 * 60 -> "${min / 60} h ${min % 60} min"
            else -> "${min / 1440} d ${(min % 1440) / 60} h"
        }
    }

    /** What kind of station this is, from its symbol, for a badge. */
    fun kind(symbol: String?, isObject: Boolean): String {
        if (symbol == null || symbol.length < 2) return "Station"
        val primary = symbol[0] == '/'
        return when {
            symbol[1] == '_' -> "Weather station"
            symbol[1] == '#' -> "Digipeater"
            symbol[1] == '&' -> "Gateway"
            primary && symbol[1] == 'r' -> "Repeater"
            primary && symbol[1] == '-' -> "Home station"
            primary && symbol[1] == 'y' -> "Home station"
            primary && symbol[1] in "[b" -> "On foot or bike"
            primary && symbol[1] in "'^Xg" -> "Aircraft"
            primary && symbol[1] in "sY" -> "Boat"
            primary && symbol[1] == 'O' -> "Balloon"
            org.aprsdroid.app.MapFilters.isMobile(symbol, null) -> "Vehicle"
            isObject -> "Object"
            else -> "Station"
        }
    }
}
