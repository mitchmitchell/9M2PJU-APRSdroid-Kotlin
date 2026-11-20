package org.aprsdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings screen shows a default connection type without storing it.
 * The service must fall back to the same one, or it connects with a
 * different link than the one displayed.
 */
class AprsBackendDefaultsTest {

    @Test
    fun tncProtocolsDefaultToBluetoothLikeTheSettingsScreen() {
        for (proto in listOf("kiss", "tnc2", "kenwood")) {
            val linkKey = AprsBackend.defaultProtoInfo(proto).link
            assertEquals("link", linkKey)
            assertEquals("bluetooth", AprsBackend.linkDefault(linkKey))
        }
    }

    @Test
    fun aprsIsDefaultsToTcpAndAfskToVox() {
        assertEquals("tcp", AprsBackend.linkDefault(AprsBackend.defaultProtoInfo("aprsis").link))
        assertEquals("vox", AprsBackend.linkDefault(AprsBackend.defaultProtoInfo("afsk").link))
    }

    @Test
    fun everyDefaultNamesARealBackend() {
        for (key in listOf("aprsis", "afsk", "link")) {
            assertTrue(key, AprsBackend.backendCollection.containsKey(AprsBackend.linkDefault(key)))
        }
    }
}
