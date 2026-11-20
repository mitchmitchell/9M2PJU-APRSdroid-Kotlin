package org.aprsdroid.app

/**
 * Turns a raw APRS comment into something a person wants to read.
 *
 * APRS packs data into the comment field: course/speed, altitude, antenna
 * (PHG) data, extra position precision (DAO), telemetry and Mic-E radio
 * type codes. The list and detail screens show that data in their own
 * fields, so the comment text should not repeat it as raw codes.
 * Pure Kotlin so it can be unit-tested.
 */
object AprsComment {

    data class Parsed(
        /** The human-written part of the comment, or "" if there was none. */
        val text: String,
        /** Altitude in metres from `/A=nnnnnn` (feet), if present. */
        val altitudeM: Double?,
    )

    private val COURSE_SPEED = Regex("^(?:\\d{3}|\\.{3}| {3})/(?:\\d{3}|\\.{3}| {3})(?:/\\d{3}/\\d{3})?")
    private val ALTITUDE = Regex("/A=(-\\d{5}|\\d{6})")
    private val ANTENNA = Regex("^(?:PHG\\d{4}[0-9A-Z]?|RNG\\d{4}|DFS\\d{4})/?")
    private val DAO = Regex("![A-Za-z][\\x20-\\x7e]{2}!")
    private val TELEMETRY = Regex("\\|[\\x21-\\x7b]{4,14}\\|")
    /** Mic-E device type codes that radios append to the comment. */
    private val MICE_SUFFIXES = listOf("_%", "_#", "_\"", "_$", "_)", "_(", "_0", "_1", "_3", "|3", "|4", "^v", "*v")
    private val SPACES = Regex("\\s+")

    @JvmStatic
    fun parse(raw: String?): Parsed {
        if (raw.isNullOrBlank()) return Parsed("", null)
        var s: String = raw
        val alt = ALTITUDE.find(s)?.let { it.groupValues[1].toInt() * 0.3048 }
        s = ALTITUDE.replace(s, " ")
        s = COURSE_SPEED.replaceFirst(s, "")
        s = ANTENNA.replaceFirst(s.trimStart(), "")
        s = TELEMETRY.replace(s, " ")
        s = DAO.replace(s, " ")
        s = s.trim()
        for (suffix in MICE_SUFFIXES) {
            if (s.endsWith(suffix)) {
                s = s.dropLast(suffix.length)
                break
            }
        }
        return Parsed(SPACES.replace(s, " ").trim(), alt)
    }

    /** Just the readable text. */
    @JvmStatic
    fun clean(raw: String?): String = parse(raw).text
}
