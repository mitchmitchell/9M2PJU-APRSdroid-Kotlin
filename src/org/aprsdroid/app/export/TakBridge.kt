package org.aprsdroid.app.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A chat message carried in a CoT event (TAK's "GeoChat"). */
data class CotChat(
    val senderUid: String,
    val senderCallsign: String,
    /** UID of the person it is for, or the chat room's name for a group message. */
    val recipientUid: String,
    val chatroom: String,
    val text: String,
)

/** The parts of a received CoT event that the bridge acts on. */
data class CotEvent(
    val uid: String,
    val type: String,
    val how: String,
    val lat: Double?,
    val lon: Double?,
    val callsign: String?,
    /** True for a TAK client announcing itself (it carries a `takv` element). */
    val isTakClient: Boolean,
    val chat: CotChat?,
    val remarks: String?,
)

/**
 * Reads CoT XML. Deliberately small and tolerant: it pulls out the handful of
 * attributes the bridge needs and ignores everything else, so unknown detail
 * elements from plugins never break it. Pure Kotlin for unit tests.
 */
object CotParser {
    private val EVENT = Regex("<event\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val REMARKS = Regex("<remarks\\b([^>]*?)(?:/>|>(.*?)</remarks>)", setOf(RegexOption.DOT_MATCHES_ALL))

    private fun tag(xml: String, name: String): String? =
        Regex("<$name\\b([^>]*)>").find(xml)?.groupValues?.get(1)

    private fun attr(attrs: String?, name: String): String? {
        if (attrs == null) return null
        val m = Regex("(?:^|\\s)$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").find(attrs) ?: return null
        return unescape(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }

    @JvmStatic
    fun unescape(s: String): String =
        if (!s.contains('&')) s
        else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "").replace("&amp;", "&")

    /** @return the event, or null if [xml] is not a CoT event with a uid and type */
    @JvmStatic
    fun parse(xml: String): CotEvent? {
        val ev = EVENT.find(xml)?.groupValues?.get(1) ?: return null
        val uid = attr(ev, "uid") ?: return null
        val type = attr(ev, "type") ?: return null
        val point = tag(xml, "point")
        val contact = tag(xml, "contact")
        val rm = REMARKS.find(xml)
        val remarks = rm?.groupValues?.get(2)?.let { unescape(it).trim() }?.takeIf { it.isNotEmpty() }

        var chat: CotChat? = null
        val chatTag = tag(xml, "__chat")
        if (chatTag != null) {
            val grp = tag(xml, "chatgrp")
            val sender = attr(grp, "uid0") ?: attr(tag(xml, "link"), "uid")
            val recipient = attr(grp, "uid1") ?: attr(chatTag, "id")
            if (sender != null && recipient != null && remarks != null) {
                chat = CotChat(
                    senderUid = sender,
                    senderCallsign = attr(chatTag, "senderCallsign") ?: sender,
                    recipientUid = recipient,
                    chatroom = attr(chatTag, "chatroom") ?: recipient,
                    text = remarks,
                )
            }
        }
        return CotEvent(
            uid = uid,
            type = type,
            how = attr(ev, "how").orEmpty(),
            lat = attr(point, "lat")?.toDoubleOrNull(),
            lon = attr(point, "lon")?.toDoubleOrNull(),
            callsign = attr(contact, "callsign"),
            isTakClient = tag(xml, "takv") != null,
            chat = chat,
            remarks = remarks,
        )
    }
}

/**
 * Cuts a TAK stream (or a datagram) into whole `<event>...</event>` documents.
 * A stream delivers events back to back with no separator, split anywhere.
 */
class CotStreamSplitter(private val maxBuffer: Int = 256 * 1024) {
    private val buf = StringBuilder()

    fun feed(chunk: String): List<String> {
        buf.append(chunk)
        val out = ArrayList<String>()
        while (true) {
            val start = buf.indexOf("<event")
            if (start < 0) {
                // Keep a tail in case "<event" is split across chunks.
                if (buf.length > 16) buf.delete(0, buf.length - 16)
                break
            }
            val end = buf.indexOf("</event>", start)
            if (end < 0) {
                if (start > 0) buf.delete(0, start)
                // A single event this large is not one we want; drop it rather than grow forever.
                if (buf.length > maxBuffer) buf.setLength(0)
                break
            }
            out.add(buf.substring(start, end + 8))
            buf.delete(0, end + 8)
        }
        return out
    }
}

/** Builds TAK chat ("GeoChat") events, so an APRS message shows up in TAK's chat. */
object CotChatEncoder {
    /** TAK's name for the room everyone is in; used when we do not yet know who the TAK user is. */
    const val ALL_CHAT = "All Chat Rooms"

    private fun time(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ms))

    /**
     * @param toUid the TAK user's UID for a direct message, or [ALL_CHAT]
     * @param toName that user's callsign, or [ALL_CHAT]
     * @param messageId unique per message, so TAK does not show a repeat twice
     */
    @JvmStatic
    fun encode(
        fromUid: String,
        fromCallsign: String,
        toUid: String,
        toName: String,
        text: String,
        messageId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val e = CotEncoder::escape
        val now = time(nowMillis)
        val stale = time(nowMillis + 24 * 3600 * 1000L)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<event version=\"2.0\" uid=\"${e("GeoChat.$fromUid.$toUid.$messageId")}\" type=\"b-t-f\" how=\"h-g-i-g-o\"" +
            " time=\"$now\" start=\"$now\" stale=\"$stale\">" +
            "<point lat=\"0\" lon=\"0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
            "<detail>" +
            "<__chat parent=\"RootContactGroup\" groupOwner=\"false\" messageId=\"${e(messageId)}\"" +
            " chatroom=\"${e(toName)}\" id=\"${e(toUid)}\" senderCallsign=\"${e(fromCallsign)}\">" +
            "<chatgrp uid0=\"${e(fromUid)}\" uid1=\"${e(toUid)}\" id=\"${e(toUid)}\"/></__chat>" +
            "<link uid=\"${e(fromUid)}\" type=\"a-f-G-U-C\" relation=\"p-p\"/>" +
            // Tells a TAK Server who to deliver a direct message to.
            (if (toUid == ALL_CHAT) "" else "<marti><dest callsign=\"${e(toName)}\"/></marti>") +
            "<remarks source=\"BAO.F.APRS.${e(fromUid)}\" to=\"${e(toUid)}\" time=\"$now\">${e(text)}</remarks>" +
            "</detail></event>"
    }
}

/** The decisions the bridge makes, kept pure so they can be tested without a network. */
object TakBridgeRules {
    /** The longest text one APRS message carries. */
    const val APRS_MAX = 67

    /** The APRS callsign a TAK chat is addressed to, or null if it is not for an APRS contact. */
    @JvmStatic
    fun aprsCall(recipientUid: String, uidPrefix: String): String? {
        if (uidPrefix.isEmpty() || !recipientUid.startsWith(uidPrefix)) return null
        val call = recipientUid.substring(uidPrefix.length).trim().uppercase()
        return call.takeIf { it.isNotEmpty() && it.length <= 9 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
    }

    /**
     * Cut a chat text into APRS-sized messages, on word boundaries where it can.
     * Past [maxParts] the rest is dropped and the last part ends with "...".
     */
    @JvmStatic
    fun split(text: String, max: Int = APRS_MAX, maxParts: Int = 4): List<String> {
        // APRS message text may not contain these; newlines become spaces.
        var rest = text.replace(Regex("[\\r\\n]+"), " ").replace(Regex("[|~{]"), "").trim()
        val parts = ArrayList<String>()
        while (rest.isNotEmpty() && parts.size < maxParts) {
            if (rest.length <= max) { parts.add(rest); rest = ""; break }
            var cut = rest.lastIndexOf(' ', max)
            if (cut < max / 2) cut = max
            parts.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty() && parts.isNotEmpty()) {
            val last = parts.last()
            parts[parts.size - 1] = last.take(max - 3).trimEnd() + "..."
        }
        return parts
    }

    /** An APRS object name (at most nine printable characters) for a TAK marker, or null if none can be made. */
    @JvmStatic
    fun objectName(callsign: String?): String? {
        val clean = callsign.orEmpty().filter { it in ' '..'~' && it !in "|~*_" }.trim()
            .replace(Regex("\\s+"), " ")
        return clean.take(9).trim().takeIf { it.length >= 3 }
    }

    /** Is this event a marker a person placed (not a TAK user's own position, not chat, not one of ours)? */
    @JvmStatic
    fun isPlacedMarker(ev: CotEvent, uidPrefix: String): Boolean =
        ev.chat == null && !ev.isTakClient && !ev.uid.startsWith(uidPrefix) &&
            ev.lat != null && ev.lon != null &&
            (ev.type.startsWith("a-") || ev.type.startsWith("b-m-p")) && ev.how.startsWith("h-")
}
