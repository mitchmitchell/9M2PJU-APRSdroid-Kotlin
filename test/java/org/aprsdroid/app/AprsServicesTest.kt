package org.aprsdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AprsServicesTest {

    private fun action(call: String, label: String) =
        AprsServices.forCallsign(call)!!.actions.first { it.label == label }

    @Test
    fun commandsAreWrittenInEachServicesSyntax() {
        assertEquals(
            "SMS sam@example.com Running late, home by 7",
            action("WLNK-1", "Send a short email").build(mapOf("to" to " sam@example.com ", "msg" to "Running late, home by 7")),
        )
        assertEquals("L", action("wlnk-1", "Check my mail").build(emptyMap()))
        assertEquals("R2", action("WLNK-1", "Read a message").build(mapOf("n" to "2")))
        assertEquals(
            "CQ SKYWARN Hail at Flower Mound",
            action("ANSRVR", "Message a group").build(mapOf("group" to "skywarn", "msg" to "Hail  at Flower Mound")),
        )
        assertEquals("J ARES", action("ANSRVR", "Join a group").build(mapOf("group" to "ares")))
        assertEquals("W1AW", action("WHOIS", "Look up a callsign").build(mapOf("call" to "w1aw")))
        assertEquals(
            "@9725550100 No cell coverage",
            action("SMS", "Send a text").build(mapOf("number" to "(972) 555-0100", "msg" to "No cell coverage")),
        )
        assertEquals("today", action("WXBOT", "Forecast for today").build(emptyMap()))
    }

    @Test
    fun formsSayWhatIsMissingOrTooLong() {
        val email = action("WLNK-1", "Send a short email")
        assertEquals("Fill in to.", email.problem(mapOf("msg" to "hi")))
        assertNull(email.problem(mapOf("to" to "a@b.co", "msg" to "hi")))
        val long = email.problem(mapOf("to" to "someone@example.com", "msg" to "x".repeat(60)))
        assertNotNull(long)
        assertTrue(long!!.startsWith("Too long by "))
        // a phone number of only punctuation is empty once cleaned
        assertEquals("Fill in phone number.", action("SMS", "Send a text").problem(mapOf("number" to "()-", "msg" to "hi")))
    }

    @Test
    fun everyFieldlessCommandFitsAnAprsMessage() {
        for (s in AprsServices.ALL) {
            assertTrue(s.callsign, s.callsign.length <= 9 && s.actions.isNotEmpty())
            for (a in s.actions.filter { it.fields.isEmpty() }) {
                val text = a.build(emptyMap())
                assertTrue("${s.callsign} ${a.label}", text.isNotEmpty() && text.length <= AprsServices.MAX_LENGTH)
            }
        }
        assertNull(AprsServices.forCallsign("N0CALL"))
    }

    @Test
    fun helpShowsEachCommandWithPlaceholders() {
        assertEquals("@<phone number> <message>", action("SMS", "Send a text").example())
        assertEquals("SMS <to> <message>", action("WLNK-1", "Send a short email").example())
        assertEquals("CQ <group> <message>", action("ANSRVR", "Message a group").example())
        assertEquals("L", action("WLNK-1", "Check my mail").example())
    }
}
