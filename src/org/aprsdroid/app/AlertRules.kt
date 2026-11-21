package org.aprsdroid.app

import org.json.JSONObject

/** What the user asked to be told about. */
data class AlertConfig(
    /** Callsigns to watch; see [AlertRules.matches]. */
    val watch: List<String> = emptyList(),
    /** Tell me when a watched station is heard after being quiet. */
    val heard: Boolean = true,
    /** A watched station counts as quiet after this long. */
    val quietMs: Long = 30 * 60_000L,
    /** Tell me when a watched station starts moving. */
    val moved: Boolean = true,
    /** How far a station must go from where it last sat to count as moving. */
    val movedMetres: Double = 300.0,
    /** Tell me when a station comes inside this distance of the centre; 0 turns it off. */
    val areaMetres: Double = 0.0,
    /** Area alerts for watched stations only, or for any station on the move. */
    val areaWatchedOnly: Boolean = true,
)

data class StationAlert(val kind: Kind, val call: String, val distanceMetres: Double? = null) {
    enum class Kind { HEARD, MOVING, ENTERED_AREA }
}

/**
 * Decides which station reports deserve an alert. Holds what it has seen
 * this session; pure Kotlin so the rules can be tested without a phone.
 */
class AlertRules {
    private class Seen(var ts: Long, var anchorLat: Double, var anchorLon: Double, var inside: Boolean, var lastMoveAlert: Long)
    private val seen = HashMap<String, Seen>()

    companion object {
        /** A moving station is announced at most this often. */
        const val MOVE_REPEAT_MS = 15 * 60_000L

        /** Split "W5ABC, n5xyz-9 KD5*" into patterns. */
        @JvmStatic
        fun parseWatch(text: String): List<String> =
            text.split(',', ' ', ';', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

        /**
         * "W5ABC" matches that callsign with any SSID; "W5ABC-9" only that
         * one; a trailing * matches anything that starts so ("KD5*").
         */
        @JvmStatic
        fun matches(pattern: String, call: String): Boolean {
            val c = call.uppercase()
            return when {
                pattern.endsWith("*") -> c.startsWith(pattern.dropLast(1))
                pattern.contains('-') -> c == pattern
                else -> c == pattern || c.substringBefore('-') == pattern
            }
        }

        @JvmStatic
        fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
            return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
    }

    /**
     * @param centreLat centre of the area (normally where you are), or null if unknown
     * @param speedKnots the station's reported speed, if any
     */
    fun onPosition(
        cfg: AlertConfig,
        call: String,
        lat: Double,
        lon: Double,
        ts: Long,
        centreLat: Double?,
        centreLon: Double?,
        speedKnots: Int? = null,
    ): List<StationAlert> {
        val out = ArrayList<StationAlert>(1)
        val watched = cfg.watch.any { matches(it, call) }
        val prev = seen[call]
        val dist = if (centreLat != null && centreLon != null) metres(lat, lon, centreLat, centreLon) else null
        val insideNow = dist != null && cfg.areaMetres > 0 && dist <= cfg.areaMetres

        if (watched && cfg.heard && (prev == null || ts - prev.ts >= cfg.quietMs)) {
            out.add(StationAlert(StationAlert.Kind.HEARD, call, dist))
        }
        var movingNow = false
        if (prev != null) {
            val gone = metres(lat, lon, prev.anchorLat, prev.anchorLon)
            if (gone >= cfg.movedMetres) {
                movingNow = true
                if (watched && cfg.moved && ts - prev.lastMoveAlert >= MOVE_REPEAT_MS) {
                    out.add(StationAlert(StationAlert.Kind.MOVING, call, dist))
                    prev.lastMoveAlert = ts
                }
                prev.anchorLat = lat
                prev.anchorLon = lon
            }
        }
        // Entering: inside now, and known to be outside before (or new, for a watched station).
        if (insideNow) {
            val eligible = watched || (!cfg.areaWatchedOnly && (movingNow || (speedKnots ?: 0) >= 3))
            val wasInside = prev?.inside ?: false
            if (eligible && !wasInside && (prev != null || watched)) {
                out.add(StationAlert(StationAlert.Kind.ENTERED_AREA, call, dist))
            }
        }
        if (prev == null) {
            seen[call] = Seen(ts, lat, lon, insideNow, Long.MIN_VALUE / 2)
        } else {
            prev.ts = ts
            // Leave the area properly (20% beyond the edge) before it can be entered again.
            prev.inside = when {
                insideNow -> true
                dist != null && cfg.areaMetres > 0 && dist > cfg.areaMetres * 1.2 -> false
                else -> prev.inside
            }
        }
        // One notification per report is plenty: the most telling one wins.
        return out.sortedByDescending { it.kind.ordinal }.take(1)
    }
}

/** A weather alert from the US National Weather Service. */
data class NwsAlert(
    val id: String,
    val event: String,
    val severity: String,
    val headline: String,
    val area: String,
    val instruction: String,
)

object NwsAlerts {
    /** Severities in rising order, as the NWS names them. */
    private val ORDER = listOf("unknown", "minor", "moderate", "severe", "extreme")

    @JvmStatic
    fun url(lat: Double, lon: Double): String =
        "https://api.weather.gov/alerts/active?point=%.4f,%.4f".format(java.util.Locale.US, lat, lon)

    @JvmStatic
    fun rank(severity: String): Int = ORDER.indexOf(severity.lowercase()).coerceAtLeast(0)

    /** Alerts in the reply at or above [minSeverity], worst first. Bad input gives an empty list. */
    @JvmStatic
    fun parse(json: String, minSeverity: String = "moderate"): List<NwsAlert> = try {
        val features = JSONObject(json).optJSONArray("features")
        val out = ArrayList<NwsAlert>()
        if (features != null) {
            for (i in 0 until features.length()) {
                val p = features.getJSONObject(i).optJSONObject("properties") ?: continue
                val id = p.optString("id")
                val event = p.optString("event")
                if (id.isEmpty() || event.isEmpty()) continue
                if (p.optString("status", "Actual") != "Actual") continue // skip tests and exercises
                val sev = p.optString("severity", "Unknown")
                if (rank(sev) < rank(minSeverity)) continue
                out.add(
                    NwsAlert(
                        id = id, event = event, severity = sev,
                        headline = if (p.isNull("headline")) event else p.optString("headline", event),
                        area = if (p.isNull("areaDesc")) "" else p.optString("areaDesc"),
                        instruction = if (p.isNull("instruction")) "" else p.optString("instruction"),
                    ),
                )
            }
        }
        out.sortedByDescending { rank(it.severity) }
    } catch (_: Exception) {
        emptyList()
    }
}
