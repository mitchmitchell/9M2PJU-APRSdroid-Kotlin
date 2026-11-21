package org.aprsdroid.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakBridgeTest {

    // A direct GeoChat as ATAK sends it.
    private val chatXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<event version="2.0" uid="GeoChat.ANDROID-abc123.APRS.W5ABC-9.d1e2" type="b-t-f" time="2026-11-21T04:00:00.000Z" start="2026-11-21T04:00:00.000Z" stale="2026-11-22T04:00:00.000Z" how="h-g-i-g-o">
<point lat="33.01" lon="-97.05" hae="180.0" ce="9.9" le="9999999.0"/>
<detail><__chat parent="RootContactGroup" groupOwner="false" messageId="d1e2" chatroom="W5ABC-9 (APRS)" id="APRS.W5ABC-9" senderCallsign="AE5HO/TAK-TABLET">
<chatgrp uid0="ANDROID-abc123" uid1="APRS.W5ABC-9" id="APRS.W5ABC-9"/></__chat>
<link uid="ANDROID-abc123" type="a-f-G-U-C" relation="p-p"/>
<remarks source="BAO.F.ATAK.ANDROID-abc123" to="APRS.W5ABC-9" time="2026-11-21T04:00:00.000Z">On my way &amp; bringing coffee</remarks>
<__serverdestination destinations="127.0.0.1:4242:tcp:ANDROID-abc123"/></detail></event>"""

    @Test
    fun readsADirectChatFromTak() {
        val ev = CotParser.parse(chatXml)!!
        assertEquals("b-t-f", ev.type)
        val chat = ev.chat!!
        assertEquals("ANDROID-abc123", chat.senderUid)
        assertEquals("AE5HO/TAK-TABLET", chat.senderCallsign)
        assertEquals("APRS.W5ABC-9", chat.recipientUid)
        assertEquals("On my way & bringing coffee", chat.text)
        assertEquals("W5ABC-9", TakBridgeRules.aprsCall(chat.recipientUid, "APRS."))
    }

    @Test
    fun onlyChatsToAprsContactsGoOnTheAir() {
        assertNull(TakBridgeRules.aprsCall("All Chat Rooms", "APRS."))
        assertNull(TakBridgeRules.aprsCall("ANDROID-def456", "APRS."))
        assertNull(TakBridgeRules.aprsCall("APRS.", "APRS."))
        assertNull(TakBridgeRules.aprsCall("APRS.NOT A CALL!", "APRS."))
        assertEquals("N5XYZ", TakBridgeRules.aprsCall("APRS.n5xyz", "APRS."))
    }

    @Test
    fun readsATakUsersOwnPositionAndAPlacedMarker() {
        val self = CotParser.parse(
            """<event version="2.0" uid="ANDROID-abc123" type="a-f-G-U-C" how="h-e" time="t" start="t" stale="t">
               <point lat="33.0112" lon="-97.0527" hae="165" ce="5" le="9999999"/>
               <detail><takv os="34" version="5.4" device="SAMSUNG" platform="ATAK-CIV"/>
               <contact endpoint="*:-1:stcp" callsign="AE5HO/TAK-TABLET"/></detail></event>""",
        )!!
        assertTrue(self.isTakClient)
        assertEquals("AE5HO/TAK-TABLET", self.callsign)
        assertFalse(TakBridgeRules.isPlacedMarker(self, "APRS."))

        val marker = CotParser.parse(
            """<event version="2.0" uid="6f1c-22aa" type="a-u-G" how="h-g-i-g-o" time="t" start="t" stale="t">
               <point lat="33.05" lon="-97.01" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
               <detail><contact callsign="Net Control"/><remarks>146.52 simplex</remarks></detail></event>""",
        )!!
        assertTrue(TakBridgeRules.isPlacedMarker(marker, "APRS."))
        assertEquals(33.05, marker.lat!!, 1e-9)
        assertEquals("146.52 simplex", marker.remarks)
        assertEquals("Net Contr", TakBridgeRules.objectName(marker.callsign))

        // Our own exported stations must never come back as objects.
        val ours = marker.copy(uid = "APRS.W5ABC-9")
        assertFalse(TakBridgeRules.isPlacedMarker(ours, "APRS."))
        assertNull(TakBridgeRules.objectName("a"))
        assertNull(CotParser.parse("<notcot/>"))
    }

    @Test
    fun aprsMessageBecomesAChatTakCanRead() {
        val xml = CotChatEncoder.encode(
            fromUid = "APRS.W5ABC-9", fromCallsign = "W5ABC-9 (APRS)",
            toUid = "ANDROID-abc123", toName = "AE5HO/TAK-TABLET",
            text = "QSL <on> \"146.52\" & standing by", messageId = "42", nowMillis = 1_795_000_000_000L,
        )
        val ev = CotParser.parse(xml)!!
        assertEquals("b-t-f", ev.type)
        assertEquals("GeoChat.APRS.W5ABC-9.ANDROID-abc123.42", ev.uid)
        val chat = ev.chat!!
        assertEquals("APRS.W5ABC-9", chat.senderUid)
        assertEquals("W5ABC-9 (APRS)", chat.senderCallsign)
        assertEquals("ANDROID-abc123", chat.recipientUid)
        assertEquals("AE5HO/TAK-TABLET", chat.chatroom)
        assertEquals("QSL <on> \"146.52\" & standing by", chat.text)
    }

    @Test
    fun longChatsAreCutIntoAprsSizedMessages() {
        assertEquals(listOf("short"), TakBridgeRules.split("short"))
        val long = "The net will move to the alternate repeater at 1900 local because of interference on the primary, please pass the word"
        val parts = TakBridgeRules.split(long)
        assertTrue(parts.size in 2..4)
        assertTrue(parts.all { it.length <= 67 })
        assertEquals(long, parts.joinToString(" "))
        // Very long text is capped and marked as cut.
        val capped = TakBridgeRules.split("word ".repeat(200))
        assertEquals(4, capped.size)
        assertTrue(capped.last().endsWith("..."))
        assertTrue(capped.all { it.length <= 67 })
        assertEquals(listOf("line one line two"), TakBridgeRules.split("line one\nline two"))
        assertTrue(TakBridgeRules.split("   ").isEmpty())
    }

    @Test
    fun streamIsCutIntoWholeEvents() {
        val a = "<event uid=\"1\" type=\"a-f\"><detail/></event>"
        val b = "<?xml version=\"1.0\"?><event uid=\"2\" type=\"a-f\"><detail/></event>"
        val s = CotStreamSplitter()
        val stream = a + b + a
        val got = ArrayList<String>()
        // Feed it in awkward pieces, including a split inside "<event".
        for (chunk in stream.chunked(7)) got += s.feed(chunk)
        assertEquals(3, got.size)
        assertEquals(a, got[0])
        assertNotNull(CotParser.parse(got[1]))
        assertEquals("2", CotParser.parse(got[1])!!.uid)
    }
}
