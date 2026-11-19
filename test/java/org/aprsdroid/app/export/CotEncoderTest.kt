package org.aprsdroid.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class CotEncoderTest {

    private val t0 = 1_763_582_400_000L // 2025-11-19T20:00:00Z

    private fun parse(xml: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream()).documentElement

    private fun child(e: Element, name: String): Element? =
        e.getElementsByTagName(name).let { if (it.length == 0) null else it.item(0) as Element }

    private val base = StationReport(
        call = "AE5HO-9", origin = null, symbol = "/>",
        lat = 32.7767, lon = -96.797, ts = t0,
    )

    @Test
    fun defaultsMatchStockAprscot() {
        val ev = parse(CotEncoder.encode(base, CotConfig(), t0))
        assertEquals("event", ev.tagName)
        assertEquals("APRS.AE5HO-9", ev.getAttribute("uid"))
        assertEquals("a-f-G-I-U-T-r", ev.getAttribute("type"))
        assertEquals("m-g", ev.getAttribute("how"))
        assertEquals("2025-11-19T20:00:00.000Z", ev.getAttribute("time"))
        assertEquals("2025-11-19T21:00:00.000Z", ev.getAttribute("stale"))
        assertEquals("AE5HO-9 (APRS)", child(ev, "contact")!!.getAttribute("callsign"))
        val pt = child(ev, "point")!!
        assertEquals("32.7767", pt.getAttribute("lat"))
        assertEquals("-96.797", pt.getAttribute("lon"))
        assertEquals("9999999.0", pt.getAttribute("hae"))
        assertEquals("9999999.0", pt.getAttribute("ce"))
        // No course/speed known -> no track element; no comment -> no remarks.
        assertNull(child(ev, "track"))
        assertNull(child(ev, "remarks"))
        assertNull(child(ev, "usericon"))
        val aprs = child(ev, "__aprs")!!
        assertEquals("/>", aprs.getAttribute("symbol"))
        assertEquals("rf", aprs.getAttribute("source"))
    }

    @Test
    fun trackAltitudeAndRemarks() {
        val r = base.copy(
            altM = 152.4, courseDeg = 270, speedMps = 12.5, ceM = 6.0,
            comment = "Mobile <TH-D75> & \"friends\" /A=000500", qrg = "146.520MHz",
            viaInternet = true,
        )
        val ev = parse(CotEncoder.encode(r, CotConfig(staleSeconds = 600), t0))
        assertEquals("2025-11-19T20:10:00.000Z", ev.getAttribute("stale"))
        val pt = child(ev, "point")!!
        assertEquals("152.4", pt.getAttribute("hae"))
        assertEquals("6", pt.getAttribute("ce"))
        val track = child(ev, "track")!!
        assertEquals("270", track.getAttribute("course"))
        assertEquals("12.5", track.getAttribute("speed"))
        // Escaped in XML, intact after parsing.
        assertEquals("146.520MHz Mobile <TH-D75> & \"friends\" /A=000500", child(ev, "remarks")!!.textContent)
        assertEquals("aprs-is", child(ev, "__aprs")!!.getAttribute("source"))
    }

    @Test
    fun objectsUseObjectNameAndCarryOrigin() {
        val r = base.copy(call = "FIELD DAY", origin = "AE5HO", isObject = true, symbol = "\\a")
        val ev = parse(CotEncoder.encode(r, CotConfig(), t0))
        assertEquals("APRS.FIELDDAY", ev.getAttribute("uid"))
        assertEquals("FIELD DAY (APRS)", child(ev, "contact")!!.getAttribute("callsign"))
        assertEquals("AE5HO", child(ev, "__aprs")!!.getAttribute("origin"))
    }

    @Test
    fun customConfigAndIconPath() {
        val cfg = CotConfig(
            uidPrefix = "APRSDROID-", nameSuffix = "", cotType = "a-n-G",
            iconPath = { sym -> "f7f71666-8b28-4b57-9fbb-e38e61d33b79/APRS/${sym[0].code}-${sym[1].code}.png" },
        )
        val ev = parse(CotEncoder.encode(base, cfg, t0))
        assertEquals("APRSDROID-AE5HO-9", ev.getAttribute("uid"))
        assertEquals("a-n-G", ev.getAttribute("type"))
        assertEquals("AE5HO-9", child(ev, "contact")!!.getAttribute("callsign"))
        assertEquals(
            "f7f71666-8b28-4b57-9fbb-e38e61d33b79/APRS/47-62.png",
            child(ev, "usericon")!!.getAttribute("iconsetpath"),
        )
    }

    @Test
    fun controlCharactersAreDropped() {
        val xml = CotEncoder.encode(base.copy(comment = "a\u0001b\u0007c"), CotConfig(), t0)
        assertFalse(xml.contains('\u0001'))
        assertEquals("abc", child(parse(xml), "remarks")!!.textContent)
    }

    @Test
    fun altitudeFromComment() {
        assertEquals(304.8, StationReport.altitudeFromComment("hi /A=001000 there")!!, 1e-9)
        assertEquals(-30.48, StationReport.altitudeFromComment("/A=-00100")!!, 1e-9)
        assertNull(StationReport.altitudeFromComment("no altitude"))
        assertNull(StationReport.altitudeFromComment(null))
        assertTrue(StationReport.altitudeFromComment("/A=000000") == 0.0)
    }
}
