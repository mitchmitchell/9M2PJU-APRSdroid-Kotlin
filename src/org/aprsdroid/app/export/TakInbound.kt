package org.aprsdroid.app.export

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Listens on this phone for CoT that ATAK sends to an APRS contact (a chat
 * typed in ATAK) or that the user points at it (ATAK → Network → Manage
 * Outputs, for markers).
 *
 * Bound to the loopback address only: nothing else on the network can reach
 * it, so only apps on this phone can make the radio transmit. Listens on
 * both TCP and UDP because ATAK uses TCP for chat and either for outputs.
 */
class TakInbound(
    private val port: Int,
    private val onEvent: (String) -> Unit,
) {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var tcp: ServerSocket? = null
    private val clients = Executors.newCachedThreadPool { r -> Thread(r, "TakInbound-client").apply { isDaemon = true } }

    /** The port actually bound (differs from the requested one only when that was 0, in tests). */
    val boundPort: Int get() = tcp?.localPort ?: port

    /** @throws java.io.IOException if the port is taken */
    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(loopback, port))
        tcp = server
        val dgram = try {
            DatagramSocket(InetSocketAddress(loopback, server.localPort))
        } catch (e: Exception) {
            server.close(); tcp = null
            throw e
        }
        udp = dgram
        thread("TakInbound-udp") {
            val buf = ByteArray(65535)
            val splitter = CotStreamSplitter()
            while (!dgram.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                dgram.receive(p)
                for (ev in splitter.feed(String(p.data, 0, p.length, Charsets.UTF_8))) deliver(ev)
            }
        }
        thread("TakInbound-tcp") {
            while (!server.isClosed) {
                val s = server.accept()
                clients.execute { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.use {
                it.soTimeout = 30_000
                val input = it.getInputStream()
                val buf = ByteArray(4096)
                val splitter = CotStreamSplitter()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (ev in splitter.feed(String(buf, 0, n, Charsets.ISO_8859_1))) {
                        deliver(String(ev.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun deliver(xml: String) {
        try { onEvent(xml) } catch (_: Exception) {}
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread({ try { body() } catch (_: Exception) {} }, name).apply { isDaemon = true; start() }
    }

    fun stop() {
        try { udp?.close() } catch (_: Exception) {}
        try { tcp?.close() } catch (_: Exception) {}
        udp = null
        tcp = null
        clients.shutdownNow()
    }
}
