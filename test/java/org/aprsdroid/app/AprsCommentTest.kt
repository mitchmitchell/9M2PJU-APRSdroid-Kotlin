package org.aprsdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AprsCommentTest {

    @Test
    fun stripsCourseSpeedAndAltitude() {
        val p = AprsComment.parse("114/000/A=000487 Mitch mobile https://example.org")
        assertEquals("Mitch mobile https://example.org", p.text)
        assertEquals(487 * 0.3048, p.altitudeM!!, 1e-6)
    }

    @Test
    fun stripsAntennaData() {
        assertEquals("AllStar Node", AprsComment.clean("PHG1230/A=000590AllStar Node"))
        assertEquals("W3,TXn-N", AprsComment.clean("PHG5630/W3,TXn-N"))
    }

    @Test
    fun keepsFrequenciesAndText() {
        assertEquals(
            "440.8000 70cm MMDVM Voice (C4FM) 440.80000MHz +0.0000MHz, APRS for YSFGateway",
            AprsComment.clean("440.8000  /A=00000070cm MMDVM Voice (C4FM) 440.80000MHz +0.0000MHz, APRS for YSFGateway"),
        )
        assertEquals("iGate Operator KE7A Southlake TX", AprsComment.clean("iGate Operator KE7A Southlake TX"))
    }

    @Test
    fun stripsTelemetryDaoAndRadioCodes() {
        assertEquals("MT-RTG", AprsComment.clean("MT-RTG|)f&)'{|!w6C!|3"))
        assertEquals("", AprsComment.clean("|!L&\"p|!wC9!|3"))
        assertEquals("", AprsComment.clean("_%"))
        assertEquals("!DCARES!WINLINK", AprsComment.clean("090/050/A=000000!DCARES!WINLINK!w6s!"))
    }

    @Test
    fun altitudeOnly() {
        val p = AprsComment.parse("/A=000545")
        assertEquals("", p.text)
        assertEquals(545 * 0.3048, p.altitudeM!!, 1e-6)
        assertNull(AprsComment.parse("hello").altitudeM)
        assertNull(AprsComment.parse(null).altitudeM)
    }
}
