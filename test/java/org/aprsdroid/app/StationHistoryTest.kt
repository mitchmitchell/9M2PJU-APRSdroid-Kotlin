package org.aprsdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationHistoryTest {

    @Test
    fun weatherIsFoundInsideARawPacket() {
        val w = AprsWeather.fromPacket(
            "AE5HO>APRS,TCPIP*,qAC,AMBCWOP-2:@202245z3300.67N/09703.16W_114/001g002t071r000p000P000h68b10169L009AmbientCWOP.com",
        )
        assertNotNull(w)
        assertEquals(71, w!!.tempF)
        assertEquals(114, w.windDirDeg)
        assertEquals(68, w.humidityPct)
        assertEquals(10169, w.pressureTenthsHpa)
        assertNull(AprsWeather.fromPacket("KF5JUQ-9>APRS,WIDE1-1,WIDE2-1,qAR,W5DCR-3:=3246.90N/09646.67Wk"))
        assertNull(AprsWeather.fromPacket("N0CALL>APRS:>status text with_under scores"))
    }

    @Test
    fun pathIsExplainedInWords() {
        val rf = AprsPath.parse("KF5JUQ-9>APRS,W5DCR-3*,WIDE1*,WIDE2-1,qAR,KE5WTH:=3246.90N/09646.67Wk")!!
        assertEquals(listOf("W5DCR-3"), rf.digipeaters)
        assertEquals("KE5WTH", rf.igate)
        assertFalse(rf.internetOnly)
        assertEquals("Relayed over radio by W5DCR-3, then passed to the internet by KE5WTH.", rf.describe())

        val direct = AprsPath.parse("KF5JUQ-9>APRS,WIDE1-1,WIDE2-1,qAR,W5DCR-3:=3246.90N/09646.67Wk")!!
        assertTrue(direct.digipeaters.isEmpty())
        assertEquals("Heard directly over radio, then passed to the internet by W5DCR-3.", direct.describe())

        val net = AprsPath.parse("AE5HO>APRS,TCPIP*,qAC,AMBCWOP-2:@202245z3300.67N/09703.16W_")!!
        assertTrue(net.internetOnly)
        assertEquals("Sent over the internet, through AMBCWOP-2.", net.describe())

        val local = AprsPath.parse("N5ABC-9>APDR16,N5XYZ-1*,WA5LNL-3*:=3300.00N/09700.00W>")!!
        assertEquals("Relayed over radio by N5XYZ-1 and WA5LNL-3.", local.describe())
        assertNull(AprsPath.parse("not a packet"))
    }

    @Test
    fun trackStatsIgnoreJitterAndBadFixes() {
        val t0 = 1_800_000_000_000L
        val track = listOf(
            TrackPoint(t0, 33.0000, -97.0000),
            TrackPoint(t0 + 60_000, 33.0090, -97.0000),   // ~1 km north in 60 s: ~16.7 m/s
            TrackPoint(t0 + 61_000, 33.0100, -97.0000),   // 1 s later: too short to rate
            TrackPoint(t0 + 120_000, 40.0000, -97.0000),  // 780 km in a minute: bad fix
        )
        val s = TrackStats.of(track)
        assertEquals(4, s.points)
        assertEquals(16.7, s.topSpeedMps!!, 0.3)
        assertTrue(s.distanceM > 700_000)
        assertEquals(TrackStats(0, 0.0, null, null, null), TrackStats.of(emptyList()))
    }

    @Test
    fun historyServerReplyIsParsedLeniently() {
        assertEquals(
            "https://aprs.example.net/v1/stations/AE5HO-9/history?hours=168",
            HistoryServer.url(" https://aprs.example.net/ ", "ae5ho-9", 168),
        )
        val h = HistoryServer.parse(
            """{"call":"AE5HO","future_field":1,
               "positions":[{"ts":1800000060,"lat":33.01,"lon":-97.05},{"ts":1800000000,"lat":33.0,"lon":-97.0},{"bad":true}],
               "weather":[{"ts":1800000000,"raw":"114/001g002t071h68b10169"},
                          {"ts":1800000300,"temp_f":70,"humidity":69,"pressure_tenths_hpa":10170},
                          {"ts":1800000600}]}""",
            "aprs.example.net",
        )
        assertEquals(2, h.track.size)
        assertEquals(1_800_000_000_000L, h.track[0].ts) // sorted oldest first
        assertEquals(2, h.weather.size)
        assertEquals(71, h.weather[0].weather.tempF)
        assertEquals(70, h.weather[1].weather.tempF)
        assertEquals("aprs.example.net", h.source)
        assertTrue(HistoryServer.parse("{}", "x").track.isEmpty())
    }

    @Test
    fun qrzBaseCallDropsTheSsid() {
        assertEquals("AE5HO", QrzLookup.baseCall("ae5ho-10"))
        assertEquals("W5ABC", QrzLookup.baseCall(" W5ABC "))
    }

    @Test
    fun qrzRepliesAreParsed() {
        val ok = QrzLookup.parse(
            """<?xml version="1.0" ?>
            <QRZDatabase version="1.34" xmlns="http://xmldata.qrz.com">
              <Callsign>
                <call>AA7BQ</call><fname>FRED L</fname><name>LLOYD</name>
                <addr2>SCOTTSDALE</addr2><state>AZ</state><country>United States</country>
                <grid>DM43an</grid><class>E</class>
              </Callsign>
              <Session><Key>2331uf894c4bd29f3923f3bacf02c532d7bd9</Key><Count>123</Count></Session>
            </QRZDatabase>""",
        )
        assertEquals("2331uf894c4bd29f3923f3bacf02c532d7bd9", ok.sessionKey)
        assertEquals(QrzRecord("AA7BQ", "Fred L Lloyd", "SCOTTSDALE, AZ, United States", "DM43an", "E"), ok.record)
        assertNull(ok.error)

        val bad = QrzLookup.parse(
            """<?xml version="1.0" ?><QRZDatabase version="1.34">
               <Session><Error>Username/password incorrect</Error></Session></QRZDatabase>""",
        )
        assertNull(bad.sessionKey)
        assertNull(bad.record)
        assertEquals("Username/password incorrect", bad.error)

        val notFound = QrzLookup.parse(
            """<?xml version="1.0" ?><QRZDatabase version="1.34">
               <Session><Error>Not found: XX1XX</Error><Key>abc</Key></Session></QRZDatabase>""",
        )
        assertEquals("abc", notFound.sessionKey)
        assertNull(notFound.record)
    }
}
