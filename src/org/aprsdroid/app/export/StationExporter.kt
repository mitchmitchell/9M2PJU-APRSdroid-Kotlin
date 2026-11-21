package org.aprsdroid.app.export

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.aprsdroid.app.PrefsWrapper
import org.aprsdroid.app.data.PostEntity
import java.io.File
import java.net.InetAddress

/**
 * Sends APRS station positions to ATAK / TAK as Cursor-on-Target.
 *
 * Owned by AprsService: [start] when the service starts, [offer] for every
 * position written to the database, [stop] on destroy. Settings live under
 * the `atak.` preference keys (Settings → ATAK / TAK export) and are
 * re-applied live when they change.
 *
 * Each enabled sink gets its own coroutine and bounded queue (oldest
 * dropped when full), so a dead TAK server never delays the radio side.
 */
class StationExporter(
    private val ctx: Context,
    private val log: (type: Int, msg: String) -> Unit,
    /** A chat typed in TAK for an APRS station: send [parts] to [call] as APRS messages, in order. */
    private val onChatToAprs: (call: String, parts: List<String>) -> Unit = { _, _ -> },
    /** A marker placed in TAK that should go on the air as an APRS object. */
    private val onMarker: (name: String, lat: Double, lon: Double, comment: String) -> Unit = { _, _, _, _ -> },
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val tag = "APRSdroid.Export"
    private val prefs = PrefsWrapper(ctx)
    private var scope: CoroutineScope? = null
    /** Each item is a [StationReport] or a ready-made CoT event (String). */
    private val queues = ArrayList<Channel<Any>>()
    private var inbound: TakInbound? = null
    @Volatile private var chatEnabled = false
    @Volatile private var markersEnabled = false

    /** A TAK user who has written to an APRS station; replies go straight back to them. */
    private data class Peer(val uid: String, val name: String)
    private val peers = HashMap<String, Peer>()
    @Volatile private var lastPeer: Peer? = null
    private val seenEvents = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 200
    }
    private val markerSent = HashMap<String, Triple<Double, Double, Long>>()
    private val sinks = ArrayList<StationSink>()
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var cotConfig = CotConfig()
    @Volatile private var filters = Filters()
    private val lastSent = HashMap<String, StationReport>()

    private data class Filters(
        val includeOwn: Boolean = false,
        val includeObjects: Boolean = true,
        val includeInternet: Boolean = true,
    )

    companion object {
        const val PREFIX = "atak."
        const val CLIENT_P12 = "tak/client.p12"

        /** Same report (call + position) within this window is not re-sent. */
        private const val DEDUPE_MS = 5_000L

        /** An unmoved TAK marker is put on the air again no sooner than this. */
        private const val MARKER_REPEAT_MS = 10 * 60_000L
        const val DEFAULT_LISTEN_PORT = 4349
        private const val PEER_UID = "takpeer.uid"
        private const val PEER_NAME = "takpeer.name"

        @JvmStatic
        fun clientP12(ctx: Context): File = File(ctx.filesDir, CLIENT_P12)

        @JvmStatic
        fun isEnabled(prefs: PrefsWrapper): Boolean = prefs.getBoolean("atak.enabled", false)
    }

    @Synchronized
    fun start() {
        prefs.prefs.registerOnSharedPreferenceChangeListener(this)
        restart()
    }

    @Synchronized
    fun stop() {
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
        teardown()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == null || key.startsWith(PREFIX)) {
            synchronized(this) { restart() }
        }
    }

    /** Queue a report for every enabled sink (non-blocking). */
    fun offer(r: StationReport) {
        val f = filters
        if (r.isOwn && !f.includeOwn) return
        if (r.isObject && !f.includeObjects) return
        if (r.viaInternet && !f.includeInternet) return
        synchronized(lastSent) {
            val prev = lastSent[r.call]
            if (prev != null && prev.lat == r.lat && prev.lon == r.lon && r.ts - prev.ts < DEDUPE_MS) return
            lastSent[r.call] = r
        }
        synchronized(this) {
            for (q in queues) q.trySend(r)
        }
    }

    /**
     * Show an APRS message received for this station in TAK's chat, as coming
     * from that station's contact. Goes straight to the TAK user who last
     * wrote to that station (or last wrote at all); to "All Chat Rooms" until
     * someone has.
     */
    fun offerMessage(fromCall: String, text: String, messageId: String?) {
        if (!chatEnabled || text.isBlank()) return
        val cfg = cotConfig
        val call = fromCall.trim().uppercase()
        val peer = synchronized(peers) { peers[call] } ?: lastPeer
        val xml = CotChatEncoder.encode(
            fromUid = cfg.uidPrefix + call,
            fromCallsign = call + cfg.nameSuffix,
            toUid = peer?.uid ?: CotChatEncoder.ALL_CHAT,
            toName = peer?.name ?: CotChatEncoder.ALL_CHAT,
            text = text,
            messageId = (messageId?.takeIf { it.isNotBlank() } ?: "x") + "-" + System.currentTimeMillis().toString(36),
        )
        synchronized(this) {
            for (q in queues) q.trySend(xml)
        }
    }

    /** One CoT event from TAK (from the local listener or the server stream). */
    private fun handleInbound(xml: String, local: Boolean) {
        val ev = CotParser.parse(xml) ?: return
        val prefix = cotConfig.uidPrefix
        val chat = ev.chat
        if (chat != null) {
            if (!chatEnabled) return
            // Never relay our own APRS-to-TAK chats back out.
            if (chat.senderUid.startsWith(prefix)) return
            val call = TakBridgeRules.aprsCall(chat.recipientUid, prefix) ?: return
            synchronized(seenEvents) { if (seenEvents.put(ev.uid, true) != null) return }
            val peer = Peer(chat.senderUid, chat.senderCallsign)
            synchronized(peers) { peers[call] = peer }
            rememberPeer(peer)
            val parts = TakBridgeRules.split(chat.text)
            if (parts.isEmpty()) return
            log(PostEntity.TYPE_INFO, "TAK chat from ${chat.senderCallsign} to $call (${parts.size} message${if (parts.size == 1) "" else "s"})")
            onChatToAprs(call, parts)
            return
        }
        // ATAK on this phone announcing itself: now we know who to address.
        if (local && ev.isTakClient && lastPeer == null && ev.callsign != null) {
            rememberPeer(Peer(ev.uid, ev.callsign))
            return
        }
        if (markersEnabled && TakBridgeRules.isPlacedMarker(ev, prefix)) {
            val name = TakBridgeRules.objectName(ev.callsign) ?: return
            val lat = ev.lat ?: return
            val lon = ev.lon ?: return
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return
            val now = System.currentTimeMillis()
            synchronized(markerSent) {
                val prev = markerSent[ev.uid]
                if (prev != null && prev.first == lat && prev.second == lon && now - prev.third < MARKER_REPEAT_MS) return
                markerSent[ev.uid] = Triple(lat, lon, now)
            }
            log(PostEntity.TYPE_INFO, "TAK marker \"$name\" sent as an APRS object")
            onMarker(name, lat, lon, ev.remarks.orEmpty().take(40))
        }
    }

    private fun rememberPeer(p: Peer) {
        if (lastPeer == p) return
        lastPeer = p
        // Not under the "atak." prefix, so saving it does not restart the export.
        prefs.prefs.edit().putString(PEER_UID, p.uid).putString(PEER_NAME, p.name).apply()
    }

    private fun teardown() {
        inbound?.stop()
        inbound = null
        scope?.cancel()
        scope = null
        for (q in queues) q.close()
        queues.clear()
        for (s in sinks) {
            try { s.close() } catch (_: Exception) {}
        }
        sinks.clear()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun restart() {
        teardown()
        if (!isEnabled(prefs)) return

        cotConfig = CotConfig(
            uidPrefix = prefs.getString("atak.uid_prefix", "APRS."),
            nameSuffix = prefs.getString("atak.name_suffix", " (APRS)"),
            cotType = prefs.getString("atak.cot_type", CotConfig.DEFAULT_COT_TYPE)
                .ifBlank { CotConfig.DEFAULT_COT_TYPE },
            staleSeconds = prefs.getStringInt("atak.stale_min", 60).coerceAtLeast(1) * 60L,
            iconPath = if (prefs.getBoolean("atak.icons", true)) {
                val uid = prefs.getString("atak.iconset_uid", AprsIconset.DEFAULT_UID)
                    .trim().ifEmpty { AprsIconset.DEFAULT_UID };
                { symbol -> AprsIconset.iconPath(symbol, uid) }
            } else null,
        )
        filters = Filters(
            includeOwn = prefs.getBoolean("atak.include_own", false),
            includeObjects = prefs.getBoolean("atak.include_objects", true),
            includeInternet = prefs.getBoolean("atak.include_internet", true),
        )
        val cfg = { cotConfig }
        chatEnabled = prefs.getBoolean("atak.chat", true)
        markersEnabled = prefs.getBoolean("atak.objects_from_tak", false)
        if (lastPeer == null) {
            val uid = prefs.getString(PEER_UID, "")
            if (uid.isNotEmpty()) lastPeer = Peer(uid, prefs.getString(PEER_NAME, uid))
        }
        val twoWay = chatEnabled || markersEnabled

        if (prefs.getBoolean("atak.udp.enabled", true)) {
            val host = prefs.getString("atak.udp.host", "127.0.0.1").trim().ifEmpty { "127.0.0.1" }
            val port = prefs.getStringInt("atak.udp.port", 4242)
            // ATAK on this phone reaches us on a loopback port of our own.
            var endpoint: String? = null
            if (twoWay && (host == "127.0.0.1" || host == "localhost")) {
                val listenPort = prefs.getStringInt("atak.listen_port", DEFAULT_LISTEN_PORT)
                try {
                    inbound = TakInbound(listenPort) { xml -> handleInbound(xml, local = true) }.also { it.start() }
                    if (chatEnabled) endpoint = "127.0.0.1:$listenPort:tcp"
                } catch (e: Exception) {
                    inbound = null
                    log(PostEntity.TYPE_ERROR, "Cannot listen for ATAK on port $listenPort: ${e.message}")
                }
            }
            sinks += CotUdpSink(host, port, cfg, endpoint)
            if (isMulticast(host)) acquireMulticastLock()
        }
        if (prefs.getBoolean("atak.tak.enabled", false)) {
            val host = prefs.getString("atak.tak.host", "").trim()
            val port = prefs.getStringInt("atak.tak.port", 8089)
            if (host.isEmpty()) {
                log(PostEntity.TYPE_ERROR, "TAK server export: no host set")
            } else if (prefs.getBoolean("atak.tak.tls", true)) {
                try {
                    val ssl = TakTls.sslContext(clientP12(ctx))
                    val verify = prefs.getBoolean("atak.tak.verify_host", true)
                    sinks += CotStreamSink(
                        "tls:$host:$port", TakTls.connector(host, port, ssl, verify), cfg,
                        endpoint = if (chatEnabled) "*:-1:stcp" else null,
                        onEvent = if (twoWay) { xml -> handleInbound(xml, local = false) } else null,
                    )
                } catch (e: Exception) {
                    log(PostEntity.TYPE_ERROR, "TAK server TLS setup failed: ${e.message}")
                }
            } else {
                sinks += CotStreamSink(
                    "tcp:$host:$port", CotStreamSink.tcp(host, port), cfg,
                    endpoint = if (chatEnabled) "*:-1:stcp" else null,
                    onEvent = if (twoWay) { xml -> handleInbound(xml, local = false) } else null,
                )
            }
        }
        if (sinks.isEmpty()) return

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        for (sink in sinks) {
            val q = Channel<Any>(256, BufferOverflow.DROP_OLDEST)
            queues += q
            sc.launch { runSink(sink, q) }
        }
        log(
            PostEntity.TYPE_INFO,
            "ATAK export: " + sinks.joinToString(", ") { it.name } +
                (inbound?.let { ", listening on ${it.boundPort}" } ?: ""),
        )
    }

    private suspend fun runSink(sink: StationSink, q: Channel<Any>) {
        var failing = false
        for (r in q) {
            try {
                if (r is StationReport) sink.publish(r) else sink.publishRaw(r as String)
                if (failing) {
                    failing = false
                    log(PostEntity.TYPE_INFO, "ATAK export ${sink.name}: connected")
                }
            } catch (e: Exception) {
                // Log the first failure of a streak only, to keep the log readable.
                if (!failing) {
                    failing = true
                    log(PostEntity.TYPE_ERROR, "ATAK export ${sink.name}: ${e.message ?: e}")
                }
                Log.d(tag, "${sink.name} failed: $e")
            }
        }
    }

    private fun isMulticast(host: String): Boolean = try {
        // Only literal addresses — never block on DNS here.
        host.firstOrNull()?.isDigit() == true && InetAddress.getByName(host).isMulticastAddress
    } catch (_: Exception) {
        false
    }

    private fun acquireMulticastLock() {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("aprsdroid-cot").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(tag, "no multicast lock: $e")
        }
    }
}
