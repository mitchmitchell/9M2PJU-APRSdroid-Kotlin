package org.aprsdroid.app.export

import net.ab0oo.aprs.parser.ObjectPacket
import net.ab0oo.aprs.parser.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TakInboundTest {
    private val report = StationReport(call = "W5ABC-9", origin = null, symbol = "/>", lat = 32.0, lon = -96.0, ts = 0L)
    private fun event(uid: String) = "<event version=\"2.0\" uid=\"$uid\" type=\"a-u-G\" how=\"h-e\"><point lat=\"1\" lon=\"2\"/><detail/></event>"

    @Test
    fun listenerTakesEventsOverTcpAndUdpOnLoopback() {
        val got = LinkedBlockingQueue<String>()
        val inbound = TakInbound(0) { got.add(it) }
        inbound.start()
        try {
            val port = inbound.boundPort
            Socket("127.0.0.1", port).use {
                // Two events back to back, as ATAK streams them, with a non-ASCII character.
                it.getOutputStream().write((event("tcp-1") + event("tcp-é")).toByteArray(Charsets.UTF_8))
            }
            assertEquals("tcp-1", CotParser.parse(got.poll(5, TimeUnit.SECONDS)!!)!!.uid)
            assertEquals("tcp-é", CotParser.parse(got.poll(5, TimeUnit.SECONDS)!!)!!.uid)
            DatagramSocket().use {
                val b = event("udp-1").toByteArray()
                it.send(DatagramPacket(b, b.size, InetAddress.getByName("127.0.0.1"), port))
            }
            assertEquals("udp-1", CotParser.parse(got.poll(5, TimeUnit.SECONDS)!!)!!.uid)
        } finally {
            inbound.stop()
        }
    }

    @Test
    fun stationsCarryAChatEndpointButObjectsDoNot() {
        val cfg = CotConfig(endpoint = "127.0.0.1:4349:tcp")
        assertTrue(CotEncoder.encode(report, cfg).contains("endpoint=\"127.0.0.1:4349:tcp\""))
        assertFalse(CotEncoder.encode(report.copy(isObject = true), cfg).contains("endpoint="))
        assertFalse(CotEncoder.encode(report, CotConfig()).contains("endpoint="))
    }

    @Test
    fun serverStreamHandsBackWhatTheServerSends() {
        val got = LinkedBlockingQueue<String>()
        ServerSocket(0).use { server ->
            val sink = CotStreamSink(
                "tcp:test", CotStreamSink.tcp("127.0.0.1", server.localPort), { CotConfig() },
                endpoint = "*:-1:stcp", onEvent = { got.add(it) },
            )
            try {
                sink.publish(report)
                server.accept().use { peer ->
                    val buf = ByteArray(2048)
                    val n = peer.getInputStream().read(buf)
                    assertTrue(String(buf, 0, n).contains("endpoint=\"*:-1:stcp\""))
                    peer.getOutputStream().write(event("from-server").toByteArray())
                    peer.getOutputStream().flush()
                    assertEquals("from-server", CotParser.parse(got.poll(5, TimeUnit.SECONDS)!!)!!.uid)
                }
            } finally {
                sink.close()
            }
        }
    }

    @Test
    fun markerBecomesAValidAprsObject() {
        val text = ObjectPacket("Net Contr".padEnd(9), true, Position(33.05, -97.01, 0, '/', '/'), "146.52 simplex").toString()
        assertTrue(text, text.startsWith(";Net Contr*"))
        assertTrue(text, text.endsWith("146.52 simplex"))
        assertTrue(text, text.contains("3303.00N/09700.60W/"))
    }
}
