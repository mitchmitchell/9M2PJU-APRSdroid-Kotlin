package org.aprsdroid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Station and weather alerts. Owned by AprsService, so alerts run while
 * tracking is on. Settings live under the `alerts.` preference keys and are
 * picked up when they change.
 */
class AlertEngine(private val ctx: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val tag = "APRSdroid.Alerts"
    private val prefs = PrefsWrapper(ctx)
    private val rules = AlertRules()
    @Volatile private var enabled = false
    @Volatile private var cfg = AlertConfig()
    @Volatile private var fixedCentre: Pair<Double, Double>? = null
    private var scope: CoroutineScope? = null
    private var weatherJob: Job? = null
    private val shownWeather = HashSet<String>()

    companion object {
        const val CHANNEL = "alerts"
        const val PREFIX = "alerts."
        private const val WEATHER_EVERY_MS = 10 * 60_000L
        private const val NOTIFY_BASE = 500_000

        fun setupChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Station and weather alerts", NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }

        /** "33.01, -97.05" as a position, or null if it is not one. */
        @JvmStatic
        fun parseCentre(text: String): Pair<Double, Double>? {
            val p = text.split(',', ' ').filter { it.isNotBlank() }
            if (p.size != 2) return null
            val lat = p[0].toDoubleOrNull() ?: return null
            val lon = p[1].toDoubleOrNull() ?: return null
            return if (lat in -90.0..90.0 && lon in -180.0..180.0) Pair(lat, lon) else null
        }
    }

    fun start() {
        setupChannel(ctx)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs.prefs.registerOnSharedPreferenceChangeListener(this)
        reload()
    }

    fun stop() {
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
        scope?.cancel()
        scope = null
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == null || key.startsWith(PREFIX)) reload()
    }

    private fun reload() {
        enabled = prefs.getBoolean("alerts.enabled", false)
        val metric = prefs.isMetric()
        val radius = prefs.getString("alerts.area_radius", "0").replace(',', '.').toDoubleOrNull() ?: 0.0
        cfg = AlertConfig(
            watch = AlertRules.parseWatch(prefs.getString("alerts.watch", "")),
            heard = prefs.getBoolean("alerts.heard", true),
            moved = prefs.getBoolean("alerts.moved", true),
            areaMetres = radius.coerceAtLeast(0.0) * (if (metric) 1000.0 else 1609.344),
            areaWatchedOnly = !prefs.getBoolean("alerts.area_anyone", false),
        )
        fixedCentre = parseCentre(prefs.getString("alerts.area_centre", ""))
        weatherJob?.cancel()
        weatherJob = null
        if (enabled && prefs.getBoolean("alerts.weather", false)) {
            weatherJob = scope?.launch {
                while (isActive) {
                    checkWeather()
                    delay(WEATHER_EVERY_MS)
                }
            }
        }
    }

    private fun centre(): Pair<Double, Double>? =
        fixedCentre ?: MapData.lastKnownLocation(ctx)?.let { Pair(it.lat, it.lon) }

    /** Called for every station position the service stores. */
    fun onPosition(call: String, lat: Double, lon: Double, ts: Long, speedKnots: Int?, isOwn: Boolean) {
        if (!enabled || isOwn) return
        val c = centre()
        val alerts = synchronized(rules) {
            rules.onPosition(cfg, call, lat, lon, ts, c?.first, c?.second, speedKnots)
        }
        for (a in alerts) notifyStation(a)
    }

    private fun distanceText(m: Double?): String {
        if (m == null) return ""
        val metric = prefs.isMetric()
        val d = if (metric) m / 1000.0 else m / 1609.344
        return (if (d < 100) "%.1f".format(d) else "%.0f".format(d)) + (if (metric) " km" else " mi") + " away"
    }

    private fun notifyStation(a: StationAlert) {
        val (title, text) = when (a.kind) {
            StationAlert.Kind.HEARD -> Pair("${a.call} is on the air", distanceText(a.distanceMetres))
            StationAlert.Kind.MOVING -> Pair("${a.call} is on the move", distanceText(a.distanceMetres))
            StationAlert.Kind.ENTERED_AREA -> Pair("${a.call} is nearby", distanceText(a.distanceMetres))
        }
        val open = Intent(ctx, StationActivity::class.java)
            .setData(Uri.parse(a.call))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        post(NOTIFY_BASE + (a.call.hashCode() and 0xffff), title, text, null, open)
    }

    private fun checkWeather() {
        val c = centre() ?: return
        try {
            val conn = URL(NwsAlerts.url(c.first, c.second)).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            // The weather service asks every client to say who it is.
            conn.setRequestProperty(
                "User-Agent",
                "APRSDroid-TAK/${BuildConfig.VERSION_NAME.removePrefix("v")} (https://github.com/mitchmitchell/APRSDroid-TAK)",
            )
            conn.setRequestProperty("Accept", "application/geo+json")
            val body = try {
                if (conn.responseCode != 200) return
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val min = prefs.getString("alerts.weather_min", "moderate")
            for (w in NwsAlerts.parse(body, min)) {
                if (!shownWeather.add(w.id)) continue
                val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://forecast.weather.gov/MapClick.php?lat=${c.first}&lon=${c.second}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val detail = listOf(w.headline, w.area, w.instruction).filter { it.isNotBlank() }.joinToString("\n\n")
                post(NOTIFY_BASE + 0x10000 + (w.id.hashCode() and 0xffff), w.event, w.headline, detail, open)
            }
        } catch (e: Exception) {
            Log.d(tag, "weather check failed: $e")
        }
    }

    private fun post(id: Int, title: String, text: String, bigText: String?, open: Intent) {
        try {
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }
            b.setSmallIcon(R.drawable.icon)
                .setContentTitle(title)
                .setContentText(text)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            if (bigText != null) b.style = Notification.BigTextStyle().bigText(bigText)
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
        } catch (e: Exception) {
            Log.w(tag, "could not post alert: $e")
        }
    }
}
