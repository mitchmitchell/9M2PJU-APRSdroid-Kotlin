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
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val tag = "APRSdroid.Export"
    private val prefs = PrefsWrapper(ctx)
    private var scope: CoroutineScope? = null
    private val queues = ArrayList<Channel<StationReport>>()
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

    private fun teardown() {
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

        if (prefs.getBoolean("atak.udp.enabled", true)) {
            val host = prefs.getString("atak.udp.host", "127.0.0.1").trim().ifEmpty { "127.0.0.1" }
            val port = prefs.getStringInt("atak.udp.port", 4242)
            sinks += CotUdpSink(host, port, cfg)
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
                    sinks += CotStreamSink("tls:$host:$port", TakTls.connector(host, port, ssl, verify), cfg)
                } catch (e: Exception) {
                    log(PostEntity.TYPE_ERROR, "TAK server TLS setup failed: ${e.message}")
                }
            } else {
                sinks += CotStreamSink("tcp:$host:$port", CotStreamSink.tcp(host, port), cfg)
            }
        }
        if (sinks.isEmpty()) return

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        for (sink in sinks) {
            val q = Channel<StationReport>(256, BufferOverflow.DROP_OLDEST)
            queues += q
            sc.launch { runSink(sink, q) }
        }
        log(PostEntity.TYPE_INFO, "ATAK export: " + sinks.joinToString(", ") { it.name })
    }

    private suspend fun runSink(sink: StationSink, q: Channel<StationReport>) {
        var failing = false
        for (r in q) {
            try {
                sink.publish(r)
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
