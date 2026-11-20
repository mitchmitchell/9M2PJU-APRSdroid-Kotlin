package org.aprsdroid.app.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class AprsIconsetTest {

    private val uid = AprsIconset.DEFAULT_UID

    @Test
    fun pathsMatchTheGatewayPatch() {
        assertEquals("$uid/APRS/table0_symbol_29.png", AprsIconset.iconPath("/>"))
        assertEquals("$uid/APRS/table0_symbol_00.png", AprsIconset.iconPath("/!"))
        assertEquals("$uid/APRS/table0_symbol_93.png", AprsIconset.iconPath("/~"))
        // Alternate table and overlay characters both use table1.
        assertEquals("$uid/APRS/table1_symbol_29.png", AprsIconset.iconPath("\\>"))
        assertEquals("$uid/APRS/table1_symbol_02.png", AprsIconset.iconPath("L#"))
        // Out-of-range code -> 0; missing symbol -> car in the primary table.
        assertEquals("$uid/APRS/table0_symbol_00.png", AprsIconset.iconPath("/é"))
        assertEquals("$uid/APRS/table0_symbol_29.png", AprsIconset.iconPath(null))
        assertEquals("other/APRS/table0_symbol_29.png", AprsIconset.iconPath("/>", "other"))
    }

    @Test
    fun zipHasTheGatewayLayout() {
        val out = ByteArrayOutputStream()
        AprsIconset.writeZip(out) { table, index -> byteArrayOf(table.toByte(), index.toByte()) }
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
            }
        }
        assertEquals(2 + 192, entries.size)
        assertEquals(AprsIconset.iconsetXml(), String(entries["iconset.xml"]!!))
        assertTrue(String(entries["iconset.xml"]!!).contains("uid=\"$uid\""))
        assertTrue(String(entries["iconset.xml"]!!).contains("selectedGroup=\"APRS\""))
        val manifest = String(entries["MANIFEST/manifest.xml"]!!)
        assertTrue(manifest.contains("<Parameter name=\"onReceiveImport\" value=\"true\"/>"))
        assertTrue(manifest.contains("zipEntry=\"APRS/table1_symbol_95.png\""))
        assertArrayEquals(byteArrayOf(0, 29), entries["APRS/table0_symbol_29.png"])
        assertArrayEquals(byteArrayOf(1, 95), entries["APRS/table1_symbol_95.png"])
    }
}
