package org.aprsdroid.app.export

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket

/**
 * A destination for APRS station reports (ATAK on this phone, a TAK
 * Server, an ATAK plugin, ...). Pure Kotlin/Java networking so sinks can
 * be unit-tested on the JVM.
 *
 * [publish] is called from one coroutine per sink, so a slow or dead
 * destination never blocks the APRS service or the other sinks.
 */
interface StationSink {
    /** Short name for logs. */
    val name: String

    /** Send one report. May block; may throw — the exporter logs and carries on. */
    fun publish(report: StationReport)

    /** Release sockets/threads. Safe to call more than once. */
    fun close()
}

/**
 * Sends CoT XML as UDP datagrams — ATAK's network input on the same phone
 * (127.0.0.1:4242 by default), a unicast host, or the TAK "Mesh SA"
 * multicast group (239.2.3.1:6969). Multicast on Android also needs a
 * WifiManager.MulticastLock, which the exporter holds.
 */
class CotUdpSink(
    private val host: String,
    private val port: Int,
    private val config: () -> CotConfig,
) : StationSink {

    override val name = "udp:$host:$port"

    private var socket: DatagramSocket? = null
    private var target: InetAddress? = null

    private fun open(): Pair<DatagramSocket, InetAddress> {
        val addr = target ?: InetAddress.getByName(host).also { target = it }
        val s = socket ?: (
            if (addr.isMulticastAddress) MulticastSocket().apply { timeToLive = 4 } else DatagramSocket()
            ).also { socket = it }
        return Pair(s, addr)
    }

    override fun publish(report: StationReport) {
        val bytes = CotEncoder.encode(report, config()).toByteArray(Charsets.UTF_8)
        val (s, addr) = open()
        s.send(DatagramPacket(bytes, bytes.size, addr, port))
    }

    override fun close() {
        socket?.close()
        socket = null
    }
}

/**
 * Streams CoT XML over a TCP or TLS connection — a TAK Server's streaming
 * input (TLS 8089 normally, plain TCP 8087 if enabled), e.g. zodiac over
 * Tailscale. Reconnects on the next report after a failure, with backoff.
 * Anything the server sends back (pings, other users' CoT) is read and
 * discarded so the connection never stalls on a full receive buffer.
 *
 * @param connect opens a connected socket (plain or TLS); see TakConnect.
 */
class CotStreamSink(
    override val name: String,
    private val connect: () -> Socket,
    private val config: () -> CotConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) : StationSink {

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var reader: Thread? = null
    private var nextAttempt = 0L
    private var backoffMs = 2_000L

    @Synchronized
    private fun ensureConnected(): OutputStream {
        out?.let { return it }
        val now = clock()
        if (now < nextAttempt) throw IOException("$name: waiting ${nextAttempt - now} ms before reconnecting")
        try {
            val s = connect()
            socket = s
            val o = s.getOutputStream()
            out = o
            startReader(s)
            backoffMs = 2_000L
            return o
        } catch (e: IOException) {
            nextAttempt = now + backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            throw e
        }
    }

    private fun startReader(s: Socket) {
        val input: InputStream = s.getInputStream()
        reader = Thread({
            val buf = ByteArray(4096)
            try {
                while (input.read(buf) >= 0) { /* discard */ }
            } catch (_: IOException) {
            }
            // Server closed or reset: forget this socket (but not a newer one).
            synchronized(this) { if (socket === s) dropConnection() }
        }, "CotStreamSink-reader").apply { isDaemon = true; start() }
    }

    private fun dropConnection() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        out = null
    }

    override fun publish(report: StationReport) {
        val bytes = CotEncoder.encode(report, config()).toByteArray(Charsets.UTF_8)
        val o = ensureConnected()
        try {
            o.write(bytes)
            o.flush()
        } catch (e: IOException) {
            synchronized(this) { dropConnection() }
            throw e
        }
    }

    @Synchronized
    override fun close() {
        dropConnection()
        reader = null
    }

    companion object {
        /** Plain TCP connect with timeouts. */
        @JvmStatic
        fun tcp(host: String, port: Int, timeoutMs: Int = 10_000): () -> Socket = {
            Socket().apply {
                connect(InetSocketAddress(host, port), timeoutMs)
                tcpNoDelay = true
            }
        }
    }
}
