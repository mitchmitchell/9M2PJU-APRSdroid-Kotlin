package org.aprsdroid.app

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional online sources the user sets up themselves in Settings:
 * a history server (see doc/HISTORY-SERVER.md) and a QRZ.com account.
 *
 * Addresses, tokens and passwords are kept with the other secrets, in
 * preferences that are excluded from backups, and are never logged.
 * All calls here block: use a background thread.
 */
object OnlineLookups {
    private const val TAG = "OnlineLookups"
    const val KEY_HISTORY_URL = "history.url"
    const val KEY_HISTORY_TOKEN = "history.token"
    const val KEY_QRZ_USER = "qrz.user"
    const val KEY_QRZ_PASSWORD = "qrz.password"
    private const val KEY_QRZ_SESSION = "qrz.session"

    private fun secrets(ctx: Context) = GoogleTiles.secrets(ctx)

    fun get(ctx: Context, key: String): String = secrets(ctx).getString(key, "").orEmpty()

    fun set(ctx: Context, key: String, value: String) {
        val ed = secrets(ctx).edit().putString(key, value.trim())
        // A changed QRZ login invalidates the stored session.
        if (key == KEY_QRZ_USER || key == KEY_QRZ_PASSWORD) ed.remove(KEY_QRZ_SESSION)
        ed.apply()
        if (key == KEY_QRZ_USER || key == KEY_QRZ_PASSWORD) qrzCache.clear()
    }

    fun hasHistoryServer(ctx: Context): Boolean = get(ctx, KEY_HISTORY_URL).isNotEmpty()
    fun hasQrz(ctx: Context): Boolean = get(ctx, KEY_QRZ_USER).isNotEmpty() && get(ctx, KEY_QRZ_PASSWORD).isNotEmpty()

    private fun userAgent(): String =
        "APRSDroid-TAK/${BuildConfig.VERSION_NAME.removePrefix("v")} (+https://github.com/mitchmitchell/APRSDroid-TAK)"

    private fun http(url: String, token: String? = null, post: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", userAgent())
            conn.setRequestProperty("Accept", "application/json, application/xml")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (post != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(post.toByteArray()) }
            }
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---- history server ----

    /**
     * A station's history from the user's history server.
     * @throws Exception with a short reason if the server is unreachable or its reply is not understood
     */
    fun fetchHistory(ctx: Context, call: String, hours: Int): StationHistory {
        val base = get(ctx, KEY_HISTORY_URL)
        val url = HistoryServer.url(base, call, hours)
        val host = try { URL(url).host } catch (_: Exception) { "history server" }
        return HistoryServer.parse(http(url, token = get(ctx, KEY_HISTORY_TOKEN)), host)
    }

    // ---- QRZ.com ----

    private val qrzCache = java.util.concurrent.ConcurrentHashMap<String, QrzReply>()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun qrzLogin(ctx: Context): QrzReply {
        // Credentials go in the POST body over HTTPS, never in the URL.
        val body = "username=${enc(get(ctx, KEY_QRZ_USER))}&password=${enc(get(ctx, KEY_QRZ_PASSWORD))}" +
            "&agent=${enc("APRSDroid-TAK/" + BuildConfig.VERSION_NAME.removePrefix("v"))}"
        val reply = QrzLookup.parse(http(QrzLookup.BASE_URL, post = body))
        secrets(ctx).edit().putString(KEY_QRZ_SESSION, reply.sessionKey.orEmpty()).apply()
        return reply
    }

    /**
     * Look a callsign up on QRZ.com with the user's own account. Results are
     * cached for the life of the app, so each callsign costs one request.
     * @throws Exception if QRZ cannot be reached
     */
    fun qrz(ctx: Context, call: String): QrzReply {
        val base = QrzLookup.baseCall(call)
        qrzCache[base]?.let { return it }
        var session = secrets(ctx).getString(KEY_QRZ_SESSION, "").orEmpty()
        if (session.isEmpty()) {
            val login = qrzLogin(ctx)
            session = login.sessionKey ?: return login
        }
        var reply = QrzLookup.parse(http(QrzLookup.BASE_URL, post = "s=${enc(session)}&callsign=${enc(base)}"))
        if (reply.sessionKey == null) {
            // Session expired: log in once more and retry.
            val login = qrzLogin(ctx)
            session = login.sessionKey ?: return login
            reply = QrzLookup.parse(http(QrzLookup.BASE_URL, post = "s=${enc(session)}&callsign=${enc(base)}"))
        }
        if (reply.record != null || reply.error?.startsWith("Not found", true) == true) qrzCache[base] = reply
        Log.d(TAG, "QRZ lookup $base: " + if (reply.record != null) "found" else (reply.error ?: "no record"))
        return reply
    }

    /** Check the QRZ login. Returns null if it works, otherwise the reason. */
    fun testQrz(ctx: Context): String? = try {
        val r = qrzLogin(ctx)
        if (r.sessionKey != null) null else (r.error ?: "QRZ did not accept the login")
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
