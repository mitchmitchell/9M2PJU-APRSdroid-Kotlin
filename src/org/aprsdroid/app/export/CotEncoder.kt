package org.aprsdroid.app.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One APRS station/object position, renderer- and transport-neutral.
 * Pure Kotlin (no Android types) so the encoder and sinks are unit-testable.
 */
data class StationReport(
    /** Station call-SSID, or object/item name. */
    val call: String,
    /** Originating station for objects/items, else null. */
    val origin: String?,
    /** Two-character APRS symbol (table + code). */
    val symbol: String,
    val lat: Double,
    val lon: Double,
    /** Altitude in metres, if the packet carried one. */
    val altM: Double? = null,
    val courseDeg: Int? = null,
    val speedMps: Double? = null,
    /** Position error radius in metres (from APRS position ambiguity). */
    val ceM: Double? = null,
    val comment: String? = null,
    val qrg: String? = null,
    /** Packet time, epoch milliseconds. */
    val ts: Long,
    val isObject: Boolean = false,
    /** Our own beacon (or an object we originated). */
    val isOwn: Boolean = false,
    /** Heard via the internet (APRS-IS backend or third-party packet) rather than RF. */
    val viaInternet: Boolean = false,
) {
    companion object {
        /** APRS altitude: six characters, digits or a minus sign plus five digits. */
        private val ALT_RE = Regex("/A=(-\\d{5}|\\d{6})")

        /** Parse the APRS `/A=nnnnnn` (feet) comment extension into metres. */
        @JvmStatic
        fun altitudeFromComment(comment: String?): Double? {
            val m = ALT_RE.find(comment ?: return null) ?: return null
            return m.groupValues[1].toInt() * 0.3048
        }
    }
}

/**
 * CoT output settings. Defaults match the aprscot gateway on the TAK
 * Server (UID `APRS.<call>`, contact `<call> (APRS)`, one uniform type
 * `a-f-G-U-C`, one hour stale, icon from the "APRS" iconset) so a station
 * heard by both is one marker with one icon, in the one "APRS" overlay.
 *
 * The APRS symbol is carried only by the `usericon` path, never by the
 * CoT type: varying the type just yields generic markers in ATAK.
 */
data class CotConfig(
    val uidPrefix: String = "APRS.",
    val nameSuffix: String = " (APRS)",
    val cotType: String = DEFAULT_COT_TYPE,
    val how: String = "m-g",
    val staleSeconds: Long = 3600,
    /** Maps an APRS symbol to an ATAK `usericon` iconsetpath; null sends no icon. */
    val iconPath: ((String) -> String?)? = { AprsIconset.iconPath(it) },
    /**
     * Where TAK should send chat for this contact ("host:port:tcp", or
     * "*:-1:stcp" through a TAK Server). Null: the contact cannot be messaged.
     */
    val endpoint: String? = null,
) {
    companion object {
        const val DEFAULT_COT_TYPE = "a-f-G-U-C"
    }
}

/** Builds Cursor-on-Target XML events from [StationReport]s. */
object CotEncoder {

    private const val UNKNOWN = "9999999.0"

    private fun timeFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** CoT UID for a report: prefix + call/object name, spaces removed. */
    @JvmStatic
    fun uid(r: StationReport, cfg: CotConfig): String =
        cfg.uidPrefix + r.call.trim().replace(" ", "")

    @JvmStatic
    fun encode(r: StationReport, cfg: CotConfig, nowMillis: Long = System.currentTimeMillis()): String {
        val tf = timeFormat()
        val now = tf.format(Date(nowMillis))
        val stale = tf.format(Date(nowMillis + cfg.staleSeconds * 1000L))
        val sb = StringBuilder(512)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<event version=\"2.0\"")
            .attr("uid", uid(r, cfg))
            .attr("type", cfg.cotType)
            .attr("how", cfg.how)
            .attr("time", now)
            .attr("start", now)
            .attr("stale", stale)
            .append('>')
        sb.append("<point")
            .attr("lat", fmt(r.lat))
            .attr("lon", fmt(r.lon))
            .attr("hae", r.altM?.let { fmt(it) } ?: UNKNOWN)
            .attr("ce", r.ceM?.let { fmt(it) } ?: UNKNOWN)
            .attr("le", UNKNOWN)
            .append("/>")
        sb.append("<detail>")
        sb.append("<contact").attr("callsign", r.call.trim() + cfg.nameSuffix)
        // Objects cannot answer a message, so only stations get an endpoint.
        if (cfg.endpoint != null && !r.isObject) sb.attr("endpoint", cfg.endpoint)
        sb.append("/>")
        if (r.courseDeg != null || r.speedMps != null) {
            sb.append("<track")
                .attr("course", r.courseDeg?.let { fmt(it.toDouble()) } ?: UNKNOWN)
                .attr("speed", r.speedMps?.let { fmt(it) } ?: UNKNOWN)
                .append("/>")
        }
        cfg.iconPath?.invoke(r.symbol)?.let { path ->
            sb.append("<usericon").attr("iconsetpath", path).append("/>")
        }
        val remarks = listOfNotNull(r.qrg?.takeIf { it.isNotBlank() }, r.comment?.takeIf { it.isNotBlank() })
            .joinToString(" ")
        if (remarks.isNotEmpty()) {
            sb.append("<remarks>").append(escape(remarks)).append("</remarks>")
        }
        // APRS-specific details for ATAK plugins / later iconset mapping.
        // ATAK keeps unknown detail elements and otherwise ignores them.
        sb.append("<__aprs")
            .attr("symbol", r.symbol)
            .attr("source", if (r.viaInternet) "aprs-is" else "rf")
        r.origin?.let { sb.attr("origin", it) }
        sb.append("/>")
        sb.append("</detail></event>")
        return sb.toString()
    }

    private fun fmt(d: Double): String = String.format(Locale.US, "%.6f", d).trimEnd('0').trimEnd('.')
        .let { if (it.isEmpty() || it == "-") "0" else it }

    private fun StringBuilder.attr(name: String, value: String): StringBuilder =
        append(' ').append(name).append("=\"").append(escape(value)).append('"')

    @JvmStatic
    fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> if (c >= ' ' || c == '\t') out.append(c) // drop other control chars (invalid in XML)
            }
        }
        return out.toString()
    }
}
