package org.aprsdroid.app

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * What QRZ.com knows about a callsign. Which fields come back depends on the
 * user's QRZ account: without an XML data subscription QRZ returns only a few.
 */
data class QrzRecord(
    val call: String,
    val name: String?,
    /** City, state/province and country, as far as QRZ gave them. */
    val place: String?,
    val grid: String?,
    val licenseClass: String?,
)

/** One reply from QRZ's XML service. */
data class QrzReply(
    /** Session key to reuse for the next query; null means "log in again". */
    val sessionKey: String?,
    val record: QrzRecord?,
    /** QRZ's own error text ("Not found: XX1XX", "Username/password incorrect"), if any. */
    val error: String?,
    /** A notice that is not an error ("A subscription is required to access the complete record"). */
    val message: String?,
)

/** Parsing for QRZ's XML service (https://xmldata.qrz.com/xml/current/). Network calls live in the UI layer. */
object QrzLookup {
    const val BASE_URL = "https://xmldata.qrz.com/xml/current/"

    @JvmStatic
    fun parse(xml: String, parser: XmlPullParser = XmlPullParserFactory.newInstance().newPullParser()): QrzReply {
        parser.setInput(StringReader(xml))
        val session = HashMap<String, String>()
        val callsign = HashMap<String, String>()
        var section: String? = null
        var tag: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "Session", "Callsign" -> section = parser.name
                        else -> tag = parser.name
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = tag
                    val text = parser.text?.trim().orEmpty()
                    if (t != null && text.isNotEmpty()) {
                        when (section) {
                            "Session" -> session[t] = text
                            "Callsign" -> callsign[t] = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == section) section = null
                    tag = null
                }
            }
            event = parser.next()
        }
        val record = callsign["call"]?.let { call ->
            val name = listOfNotNull(callsign["fname"], callsign["name"]).joinToString(" ").ifBlank { null }
            val place = listOfNotNull(callsign["addr2"], callsign["state"], callsign["country"] ?: callsign["land"])
                .joinToString(", ").ifBlank { null }
            QrzRecord(call.uppercase(), name?.let { titleCase(it) }, place, callsign["grid"], callsign["class"])
        }
        return QrzReply(session["Key"], record, session["Error"], session["Message"])
    }

    /** QRZ stores many names in capitals ("FRED L LLOYD"). */
    private fun titleCase(s: String): String =
        if (s != s.uppercase()) s
        else s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** QRZ has one record per licensee: look up the callsign without its SSID. */
    @JvmStatic
    fun baseCall(call: String): String = call.trim().uppercase().substringBefore('-').substringBefore(' ')
}
