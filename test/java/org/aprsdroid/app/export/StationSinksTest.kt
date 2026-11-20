package org.aprsdroid.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

class StationSinksTest {

    private val report = StationReport(call = "AE5HO-9", origin = null, symbol = "/>", lat = 32.0, lon = -96.0, ts = 0L)
    private val cfg = { CotConfig() }

    @Test(timeout = 10_000)
    fun udpSinkSendsOneEventPerDatagram() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { rx ->
            val sink = CotUdpSink("127.0.0.1", rx.localPort, cfg)
            sink.publish(report)
            sink.publish(report.copy(call = "N0CALL"))
            val buf = ByteArray(8192)
            val p1 = DatagramPacket(buf.copyOf(), 8192).also { rx.receive(it) }
            val p2 = DatagramPacket(buf.copyOf(), 8192).also { rx.receive(it) }
            assertTrue(String(p1.data, 0, p1.length).contains("uid=\"APRS.AE5HO-9\""))
            assertTrue(String(p2.data, 0, p2.length).contains("uid=\"APRS.N0CALL\""))
            sink.close()
        }
    }

    @Test(timeout = 15_000)
    fun streamSinkWritesAndReconnectsAfterServerDrop() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            var now = 0L
            val sink = CotStreamSink("tcp", CotStreamSink.tcp("127.0.0.1", server.localPort), cfg, clock = { now })

            // First connection: receive one event, then the server hangs up.
            sink.publish(report)
            server.accept().use { c ->
                val got = readUntil(c.getInputStream(), "</event>")
                assertTrue(got.contains("uid=\"APRS.AE5HO-9\""))
            }
            // The reader thread notices the drop; the next publish reconnects.
            var delivered = false
            val second = Thread {
                server.accept().use { c ->
                    delivered = readUntil(c.getInputStream(), "</event>").contains("APRS.N0CALL")
                }
            }.apply { start() }
            val deadline = System.currentTimeMillis() + 8_000
            while (second.isAlive && System.currentTimeMillis() < deadline) {
                try {
                    sink.publish(report.copy(call = "N0CALL"))
                } catch (_: IOException) {
                    now += 120_000 // skip past the reconnect backoff
                }
                second.join(200)
            }
            assertTrue("second connection should receive the event", delivered)
            sink.close()
        }
    }

    @Test(timeout = 15_000)
    fun tlsWithClientCertAndTrustFromP12Chain() {
        val tmp = File.createTempFile("tak", ".p12").apply { deleteOnExit() }
        // Import the client .p12 the way the settings screen does (re-stored under the internal password).
        javaClass.getResourceAsStream("/tak/client.p12")!!.use {
            val info = TakTls.importClientP12(it, "testpass".toCharArray(), tmp)
            assertTrue(info.subject.contains("APRSdroid TEST client"))
            assertEquals(1, info.caCount)
        }
        val clientCtx = TakTls.sslContext(tmp)

        val server = tlsServer(requireClientAuth = true)
        val received = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        Thread {
            try {
                server.accept().use { c ->
                    received.write(readUntil(c.getInputStream(), "</event>").toByteArray())
                }
            } catch (_: IOException) {
            } finally {
                done.countDown()
            }
        }.start()

        val sink = CotStreamSink("tls", TakTls.connector("localhost", server.localPort, clientCtx, verifyHostname = true), cfg)
        sink.publish(report)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(received.toString().contains("uid=\"APRS.AE5HO-9\""))
        sink.close()
        server.close()
    }

    @Test(timeout = 15_000)
    fun tlsRejectsHostnameMismatch() {
        val tmp = File.createTempFile("tak", ".p12").apply { deleteOnExit() }
        javaClass.getResourceAsStream("/tak/client.p12")!!.use {
            TakTls.importClientP12(it, "testpass".toCharArray(), tmp)
        }
        val server = tlsServer(requireClientAuth = false)
        Thread { try { server.accept().use { c -> c.getInputStream().read() } } catch (_: IOException) {} }.start()
        // Certificate is for "localhost"; connecting by IP must fail the host name check.
        val connect = TakTls.connector("127.0.0.1", server.localPort, TakTls.sslContext(tmp), verifyHostname = true)
        try {
            connect().close()
            fail("expected host name mismatch")
        } catch (_: javax.net.ssl.SSLException) {
        }
        server.close()
    }

    @Test(timeout = 15_000)
    fun tlsRejectsServerFromUnknownCa() {
        // No client .p12 -> system trust store -> our test CA is not trusted.
        val server = tlsServer(requireClientAuth = false)
        Thread { try { server.accept().use { c -> c.getInputStream().read() } } catch (_: IOException) {} }.start()
        val connect = TakTls.connector("localhost", server.localPort, TakTls.sslContext(null), verifyHostname = true)
        try {
            connect().close()
            fail("expected untrusted server certificate")
        } catch (_: javax.net.ssl.SSLException) {
        }
        server.close()
    }

    private fun tlsServer(requireClientAuth: Boolean): SSLServerSocket {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/tak/server.p12")!!.use { ks.load(it, "testpass".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, "testpass".toCharArray())
        }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", ks.getCertificateChain("server").last())
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
        val s = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        s.needClientAuth = requireClientAuth
        return s
    }

    private fun readUntil(input: java.io.InputStream, end: String): String {
        val sb = StringBuilder()
        val buf = ByteArray(1024)
        while (!sb.contains(end)) {
            val n = input.read(buf)
            if (n < 0) break
            sb.append(String(buf, 0, n))
        }
        return sb.toString()
    }
}
