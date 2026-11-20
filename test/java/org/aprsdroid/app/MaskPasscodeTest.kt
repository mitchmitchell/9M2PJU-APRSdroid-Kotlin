package org.aprsdroid.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskPasscodeTest {
    @Test
    fun masksPasscodeInLoginLine() {
        assertEquals(
            "user N0CALL-10 pass ***** vers APRSdroid v2.1.0 filter m/50  r/33.0/-97.0/50",
            AprsPacket.maskPasscode("user N0CALL-10 pass 12345 vers APRSdroid v2.1.0 filter m/50  r/33.0/-97.0/50"),
        )
        assertEquals("user N0CALL pass *****", AprsPacket.maskPasscode("user N0CALL pass -1"))
    }

    @Test
    fun leavesOtherTextAlone() {
        val pkt = "N0CALL>APDR21,WIDE1-1:=3301.98N/09704.22W\$ please pass 12345 on"
        assertEquals(pkt, AprsPacket.maskPasscode(pkt))
    }
}
