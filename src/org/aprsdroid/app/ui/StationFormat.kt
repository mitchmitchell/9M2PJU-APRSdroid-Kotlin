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
}
