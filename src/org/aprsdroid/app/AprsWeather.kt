package org.aprsdroid.app

import kotlin.math.roundToInt

/**
 * A decoded APRS weather report. APRS sends weather as fixed-width codes
 * packed into the comment, in US units:
 *
 *     161/005g012t074r000p000P000h61b10173L311
 *
 * wind from 161 degrees at 5 mph, gusts 12 mph, 74 F, no rain, 61 % humidity,
 * 1017.3 hPa, 311 W/m2 of sunlight. Every field is optional, and a station
 * sends dots or spaces for a sensor it does not have.
 * Values are kept in the units sent; [WeatherFormat] converts for display.
 */
data class AprsWeather(
    val windDirDeg: Int? = null,
    val windMph: Int? = null,
    val gustMph: Int? = null,
    val tempF: Int? = null,
    /** Rain in hundredths of an inch. */
    val rainHour: Int? = null,
    val rain24h: Int? = null,
    val rainToday: Int? = null,
    val humidityPct: Int? = null,
    /** Barometric pressure in tenths of a hectopascal (millibar). */
    val pressureTenthsHpa: Int? = null,
    /** Solar radiation in watts per square metre. */
    val luminosity: Int? = null,
    /** Snowfall in the last 24 hours, in tenths of an inch. */
    val snowTenthsIn: Int? = null,
) {
    companion object {
        private val WIND_PREFIX = Regex("^(\\d{3}|\\.{3}| {3})/(\\d{3}|\\.{3}| {3})")
        private val WIND_POSITIONLESS = Regex("c(\\d{3}|\\.{3})s(\\d{3}|\\.{3})")
        // The weather block: a run of letter+digits fields, starting with gust or temperature.
        private val BLOCK = Regex("(?:[gtrpPhbLls#](?:-\\d{1,4}|\\d{2,5}|\\.{2,5}| {2,5}))+")

        private fun field(block: String, letter: Char, width: Int): Int? {
            val m = Regex("$letter(-\\d{${width - 1}}|\\d{$width})").find(block) ?: return null
            return m.groupValues[1].toIntOrNull()
        }

        /**
         * @param comment the packet comment, with or without the leading wind "ddd/sss"
         * @param course wind direction already split off by the packet parser, if any
         * @param speed wind speed already split off by the packet parser, if any
         * @return the report, or null if the comment holds no weather data
         */
        @JvmStatic
        fun parse(comment: String?, course: Int? = null, speed: Int? = null): AprsWeather? {
            if (comment.isNullOrBlank()) return null
            var windDir = course
            var wind = speed
            var rest: String = comment
            WIND_PREFIX.find(rest)?.let {
                windDir = it.groupValues[1].toIntOrNull()
                wind = it.groupValues[2].toIntOrNull()
                rest = rest.substring(it.range.last + 1)
            }
            WIND_POSITIONLESS.find(rest)?.let {
                windDir = it.groupValues[1].toIntOrNull()
                wind = it.groupValues[2].toIntOrNull()
            }
            // The block must start at the front of what is left and carry a temperature
            // or gust, otherwise ordinary text like "p100 radio" would be read as rain.
            val block = BLOCK.find(rest)?.takeIf { it.range.first <= 8 }?.value ?: return null
            val temp = field(block, 't', 3)
            val gust = field(block, 'g', 3)
            if (temp == null && gust == null && !block.contains('t') && !block.contains('g')) return null

            val humidity = field(block, 'h', 2)?.let { if (it == 0) 100 else it }
            val lum = field(block, 'L', 3) ?: field(block, 'l', 3)?.plus(1000)
            val w = AprsWeather(
                // Direction 000 with no wind means "no reading", not north.
                windDirDeg = windDir?.takeIf { it in 1..360 || (it == 0 && (wind ?: 0) > 0) },
                windMph = wind,
                gustMph = gust,
                tempF = temp,
                rainHour = field(block, 'r', 3),
                rain24h = field(block, 'p', 3),
                rainToday = field(block, 'P', 3),
                humidityPct = humidity,
                pressureTenthsHpa = field(block, 'b', 5)?.takeIf { it in 8000..11000 },
                luminosity = lum,
                snowTenthsIn = field(block, 's', 3),
            )
            return w.takeIf { it != AprsWeather() }
        }

        private val IN_PACKET = Regex("_(?=(?:\\d{3}|\\.{3})/(?:\\d{3}|\\.{3})[gt]|\\d{8}c)")

        /**
         * Decode the weather report in a raw packet line
         * (`CALL>DEST,PATH:@...N/...W_114/001g002t071...`), or null if it has none.
         */
        @JvmStatic
        fun fromPacket(raw: String?): AprsWeather? {
            if (raw == null) return null
            val info = raw.substringAfter(':', "")
            val m = IN_PACKET.find(info) ?: return null
            var body = info.substring(m.range.last + 1)
            // Positionless reports carry an 8-digit timestamp before the fields.
            if (body.length > 8 && body.take(8).all { it.isDigit() } && body[8] == 'c') body = body.substring(8)
            return parse(body)
        }

        /** The comment with the weather block removed: usually the station software's name. */
        @JvmStatic
        fun textAfter(comment: String?): String {
            if (comment.isNullOrBlank()) return ""
            var rest = WIND_PREFIX.replaceFirst(comment, "")
            rest = WIND_POSITIONLESS.replace(rest, "")
            val m = BLOCK.find(rest)?.takeIf { it.range.first <= 8 } ?: return rest.trim()
            return rest.substring(m.range.last + 1).trim()
        }
    }
}

/** Weather values as text, in metric or US units. Pure so it can be unit-tested. */
object WeatherFormat {
    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compass(deg: Int): String = COMPASS[((deg + 22) % 360) / 45]

    fun temperature(f: Int, metric: Boolean): String =
        if (metric) "${((f - 32) / 1.8).roundToInt()} °C" else "$f °F"

    fun speed(mph: Int, metric: Boolean): String =
        if (metric) "${(mph * 1.609344).roundToInt()} km/h" else "$mph mph"

    /** "S 5 mph", "Calm", or "5 mph" when the direction is unknown. */
    fun wind(w: AprsWeather, metric: Boolean): String? {
        val s = w.windMph ?: return null
        if (s == 0) return "Calm"
        val dir = w.windDirDeg?.let { compass(it) + " " } ?: ""
        return dir + speed(s, metric)
    }

    /** Dew point in Fahrenheit (Magnus formula), or null without temperature and humidity. */
    fun dewPointF(w: AprsWeather): Int? {
        val t = w.tempF ?: return null
        val h = w.humidityPct?.takeIf { it in 1..100 } ?: return null
        val c = (t - 32) / 1.8
        val g = Math.log(h / 100.0) + 17.62 * c / (243.12 + c)
        return (243.12 * g / (17.62 - g) * 1.8 + 32).roundToInt()
    }

    /** Rain from hundredths of an inch. */
    fun rain(hundredths: Int, metric: Boolean): String =
        if (metric) "%.1f mm".format(hundredths * 0.254) else "%.2f in".format(hundredths / 100.0)

    fun pressure(tenthsHpa: Int, metric: Boolean): String =
        if (metric) "%.1f hPa".format(tenthsHpa / 10.0) else "%.2f inHg".format(tenthsHpa / 10.0 * 0.0295299875)

    /** One line for lists: "74 °F  •  S 5 mph  •  61 %". */
    fun summary(w: AprsWeather, metric: Boolean): String = listOfNotNull(
        w.tempF?.let { temperature(it, metric) },
        wind(w, metric),
        w.humidityPct?.let { "$it %" },
        w.rainHour?.takeIf { it > 0 }?.let { "rain " + rain(it, metric) + "/h" },
    ).joinToString("  •  ")
}
