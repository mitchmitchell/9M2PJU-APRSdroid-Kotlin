package org.aprsdroid.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.aprsdroid.app.ui.PrefItem
import org.aprsdroid.app.ui.PreferenceScreen
import org.aprsdroid.app.ui.stringArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Kotlin/Compose port of the Scala `PrefsAct`.
 *
 * Main preferences screen built with Jetpack Compose. Handles
 * profile import/export, offline map file picker, all-files-access
 * settings, and restart dialog when offline mapping is toggled.
 */
class PrefsAct : ComponentActivity() {

    private val prefs by lazy { PrefsWrapper(this) }

    /** Bumped to rebuild the item list after changes made outside a PrefItem (e.g. the tiles key dialog). */
    private var refreshTick by mutableStateOf(0)

    private val tilepathPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: SecurityException) {
            }
            val resolvedPath = when (uri.scheme) {
                "file" -> uri.path
                "content" -> resolveContentUri(uri)
                else -> null
            }
            if (resolvedPath != null) {
                PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .putString("tilepath", resolvedPath)
                    .commit()
                Toast.makeText(
                    this,
                    getString(R.string.selected_file, File(resolvedPath).name),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(this, R.string.mapfile_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val profileImportPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val intent = Intent(this, ProfileImportActivity::class.java)
            intent.data = uri
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UIHelper.applySystemBarInsets(this)
        setContent {
            var showMenu by remember { mutableStateOf(false) }
            PreferenceScreen(
                title = getString(R.string.app_prefs),
                onBack = { finish() },
                items = run { refreshTick; prefsItems() },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(getString(R.string.profile_load)) },
                            onClick = {
                                showMenu = false
                                profileImportPicker.launch(arrayOf("*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(getString(R.string.profile_export)) },
                            onClick = {
                                showMenu = false
                                exportPrefs()
                            },
                        )
                    }
                },
            )
        }
    }

    private fun prefsItems(): List<PrefItem> {
        val items = mutableListOf<PrefItem>()

        // ---- APRS category ----
        items.add(PrefItem.Category(title = getString(R.string.p__aprs)))
        items.add(PrefItem.EditText(
            key = "callsign",
            title = getString(R.string.p_callsign_nossid),
            summary = getString(R.string.p_callsign_summary),
            dialogTitle = getString(R.string.p_callsign_entry),
            hint = getString(R.string.p_callsign_nossid),
            digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            maxLength = 7,
        ))
        items.add(PrefItem.List(
            key = "ssid",
            title = getString(R.string.p_ssid),
            // No fixed summary: the row shows the SSID in use.
            entries = stringArray(R.array.p_ssid_e),
            entryValues = stringArray(R.array.p_ssid_ev),
            // Must match PrefsWrapper.getSsid(), or the list marks one SSID while another is sent.
            default = PrefsWrapper.DEFAULT_SSID,
            dialogTitle = getString(R.string.p_ssid_entry),
        ))
        items.add(PrefItem.EditText(
            key = "digi_path",
            title = getString(R.string.p_aprs_path),
            summary = getString(R.string.p_aprs_path_summary),
            dialogTitle = getString(R.string.p_aprs_path_entry),
            default = "WIDE1-1",
            hint = "hop1,hop2,..",
            digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ,-",
        ))

        // ---- Connection category ----
        items.add(PrefItem.Category(title = getString(R.string.p__connection)))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_connsetup),
            summary = prefs.getBackendName(),
            onClick = {
                startActivity(Intent(this, BackendPrefs::class.java))
            },
        ))

        // ---- Appearance ----
        items.add(PrefItem.Category(title = getString(R.string.p__appearance)))
        items.add(PrefItem.List(
            key = "theme",
            title = getString(R.string.p_theme),
            summary = getString(R.string.p_theme_summary),
            entries = arrayOf(
                getString(R.string.p_theme_system),
                getString(R.string.p_theme_dark),
                getString(R.string.p_theme_light),
            ),
            entryValues = arrayOf("system", "dark", "light"),
            default = "system",
            onChanged = { recreate() },
        ))

        // ---- ATAK / TAK export ----
        items.add(PrefItem.Clickable(
            title = getString(R.string.p__atak),
            summary = getString(R.string.p_atak_link_summary),
            onClick = { startActivity(Intent(this, AtakPrefs::class.java)) },
        ))

        // ---- Map category ----
        // ---- optional online sources the user sets up themselves ----
        items.add(PrefItem.Category(title = "Online lookups"))
        val historyUrl = OnlineLookups.get(this, OnlineLookups.KEY_HISTORY_URL)
        items.add(PrefItem.Clickable(
            title = "History server",
            summary = if (historyUrl.isEmpty()) "Not set. Tracks and weather trends use what this device has heard."
                else historyUrl,
            onClick = {
                secretDialog(
                    "History server", "Address of a history server you run, for example https://aprs.example.net. " +
                        "Leave empty to use only what this device has heard.",
                    OnlineLookups.KEY_HISTORY_URL, "https://", password = false,
                )
            },
        ))
        items.add(PrefItem.Clickable(
            title = "History server token",
            summary = if (OnlineLookups.get(this, OnlineLookups.KEY_HISTORY_TOKEN).isEmpty()) "None"
                else "Set",
            enabled = historyUrl.isNotEmpty(),
            onClick = {
                secretDialog(
                    "History server token", "Sent as a bearer token, if your server asks for one.",
                    OnlineLookups.KEY_HISTORY_TOKEN, "Token", password = true,
                )
            },
        ))
        val qrzUser = OnlineLookups.get(this, OnlineLookups.KEY_QRZ_USER)
        items.add(PrefItem.Clickable(
            title = "QRZ.com username",
            summary = if (qrzUser.isEmpty()) "Not set. Station pages link to QRZ.com instead of showing the operator."
                else qrzUser,
            onClick = {
                secretDialog(
                    "QRZ.com username", "Your QRZ.com callsign login. Full records need QRZ's XML data subscription; " +
                        "without it QRZ returns only a few fields.",
                    OnlineLookups.KEY_QRZ_USER, "Callsign", password = false,
                )
            },
        ))
        items.add(PrefItem.Clickable(
            title = "QRZ.com password",
            summary = if (OnlineLookups.get(this, OnlineLookups.KEY_QRZ_PASSWORD).isEmpty()) "Not set" else "Set",
            enabled = qrzUser.isNotEmpty(),
            onClick = {
                secretDialog(
                    "QRZ.com password", "Kept on this device only, and left out of backups.",
                    OnlineLookups.KEY_QRZ_PASSWORD, "Password", password = true,
                )
            },
        ))
        items.add(PrefItem.Clickable(
            title = "Test QRZ.com login",
            enabled = OnlineLookups.hasQrz(this),
            onClick = {
                Thread {
                    val err = OnlineLookups.testQrz(this)
                    runOnUiThread {
                        Toast.makeText(
                            this, if (err == null) "QRZ.com login works." else "QRZ.com login failed: $err",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }.start()
            },
        ))

        items.add(PrefItem.Category(title = getString(R.string.p__map)))
        MapModes.initialize(this)
        val availableModes = MapModes.all.filter { it.isAvailable(this) }
        items.add(PrefItem.List(
            key = "mapmode",
            title = getString(R.string.map_type),
            summary = getString(
                R.string.p_map_type_summary,
                getString(MapModes.defaultMapMode(this, prefs).titleRes),
            ),
            entries = availableModes.map { getString(it.titleRes) }.toTypedArray(),
            entryValues = availableModes.map { it.tag }.toTypedArray(),
            default = "osm",
            onChanged = { refreshTick++ },
        ))
        val tilesKey = GoogleTiles.apiKey(this)
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_gtiles_key),
            summary = if (tilesKey.isEmpty()) getString(R.string.p_gtiles_key_summary_unset)
                else getString(R.string.p_gtiles_key_summary_set, tilesKey.takeLast(4)),
            onClick = { showTilesKeyDialog() },
        ))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_gtiles_test),
            enabled = tilesKey.isNotEmpty(),
            onClick = { testTilesKey() },
        ))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_gmaps_sdk_status),
            summary = getString(if (BuildConfig.MAPS_SDK_KEY_SET) R.string.p_gmaps_sdk_on else R.string.p_gmaps_sdk_off),
            enabled = false,
            onClick = { },
        ))

        // ---- Offline map category ----
        items.add(PrefItem.Category(title = getString(R.string.p_offlinemap)))
        val tilepath = prefs.prefs.getString("tilepath", null)
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_mbtiles_file_picker_title),
            summary = tilepath ?: getString(R.string.p_mbtiles_file_picker_summary),
            onClick = { tilepathPicker.launch(arrayOf("*/*")) },
        ))
        items.add(PrefItem.Switch(
            key = "p.offlinemap",
            title = getString(R.string.p_offlinemap_entry),
            summary = getString(R.string.p_offlinemap_summary),
            default = false,
            onChanged = { checked -> if (checked) showRestartDialog() },
        ))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_all_files_access_title),
            summary = getString(R.string.p_all_files_access_summary),
            onClick = { openAllFilesAccessSettings() },
        ))
        items.add(PrefItem.Switch(
            key = "hardware_acceleration",
            title = getString(R.string.p_hwacceleration_title),
            summary = getString(R.string.p_hwacceleration_summary),
            default = true,
        ))

        // ---- Units category ----
        items.add(PrefItem.Category(title = getString(R.string.p_units_title)))
        items.add(PrefItem.List(
            key = "p.units",
            title = getString(R.string.p_units),
            entries = stringArray(R.array.p_units_e),
            entryValues = stringArray(R.array.p_units_ev),
            default = "1",
            dialogTitle = getString(R.string.p_units_entry),
        ))

        // ---- Digipeating category ----
        items.add(PrefItem.Category(title = getString(R.string.p__digipeating)))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_digipeating),
            summary = getString(R.string.p_digipeating_preferences),
            onClick = { startActivity(Intent(this, DigiPrefs::class.java)) },
        ))

        // ---- Igate category ----
        items.add(PrefItem.Category(title = getString(R.string.p__igating)))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_igating),
            summary = getString(R.string.p_igating_preferences),
            onClick = { startActivity(Intent(this, IgatePrefs::class.java)) },
        ))

        // ---- Messaging category ----
        items.add(PrefItem.Category(title = getString(R.string.p__messaging)))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_messaging),
            summary = getString(R.string.p_messaging_preferences),
            onClick = { startActivity(Intent(this, MessagingPrefs::class.java)) },
        ))

        // ---- Position category ----
        items.add(PrefItem.Category(title = getString(R.string.p__position)))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p__location_compressed_settings),
            summary = getString(R.string.p__location_compressed_summary),
            onClick = { startActivity(Intent(this, CompressedPrefs::class.java)) },
        ))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_symbol),
            summary = getString(R.string.p_symbol_summary) + ": " +
                prefs.getString("symbol", "/$"),
            onClick = { startActivity(Intent(this, PrefSymbolAct::class.java)) },
        ))
        items.add(PrefItem.EditText(
            key = "frequency",
            title = getString(R.string.p_frequency),
            summary = getString(R.string.p_frequency_summary),
            dialogTitle = getString(R.string.p_frequency_summary),
            isNumeric = true,
        ))
        items.add(PrefItem.EditText(
            key = "status",
            title = getString(R.string.p_status),
            summary = getString(R.string.p_status_summary),
            dialogTitle = getString(R.string.p_status_entry),
            default = getString(R.string.default_status),
            hint = getString(R.string.default_status),
            maxLength = 50,
        ))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p__location),
            summary = prefs.getLocationSourceName(),
            onClick = { startActivity(Intent(this, LocationPrefs::class.java)) },
        ))

        // ---- Privacy sub-section ----
        items.add(PrefItem.Category(title = getString(R.string.p_privacy)))
        items.add(PrefItem.List(
            key = "priv_ambiguity",
            title = getString(R.string.p_priv_ambiguity),
            summary = getString(R.string.p_priv_ambiguity_summary),
            entries = stringArray(R.array.p_ambiguity_e),
            entryValues = stringArray(R.array.p_ambiguity_ev),
            default = "0",
            dialogTitle = getString(R.string.p_priv_ambiguity),
        ))
        items.add(PrefItem.Switch(
            key = "priv_spdbear",
            title = getString(R.string.p_priv_spdbear),
            summary = getString(R.string.p_priv_spdbear_summary),
            default = true,
        ))
        items.add(PrefItem.Switch(
            key = "priv_altitude",
            title = getString(R.string.p_priv_altitude),
            summary = getString(R.string.p_priv_altitude_summary),
            default = true,
        ))

        // ---- Display category ----
        items.add(PrefItem.Category(title = getString(R.string.p__display)))
        items.add(PrefItem.Switch(
            key = "keepscreen",
            title = getString(R.string.p_keepscreen),
            summary = getString(R.string.p_keepscreen_summary),
        ))

        // ---- Notification: messages ----
        items.add(PrefItem.Category(title = getString(R.string.p_msg)))
        items.add(PrefItem.Switch(
            key = "notify_led",
            title = getString(R.string.p_msg_led),
            summaryOn = getString(R.string.p_msg_led_on),
            summaryOff = getString(R.string.p_msg_led_off),
            default = true,
        ))
        items.add(PrefItem.Switch(
            key = "notify_vibr",
            title = getString(R.string.p_msg_vibr),
            summaryOn = getString(R.string.p_msg_vibr_on),
            summaryOff = getString(R.string.p_msg_vibr_off),
            default = true,
        ))
        items.add(PrefItem.Ringtone(
            key = "notify_ringtone",
            title = getString(R.string.p_msg_ring),
            summary = getString(R.string.p_msg_ring_summary),
        ))

        // ---- Notification: position reports ----
        items.add(PrefItem.Category(title = getString(R.string.p_pos)))
        items.add(PrefItem.Switch(
            key = "pos_notify_led",
            title = getString(R.string.p_msg_led),
            summaryOn = getString(R.string.p_msg_led_on),
            summaryOff = getString(R.string.p_msg_led_off),
            default = false,
        ))
        items.add(PrefItem.Switch(
            key = "pos_notify_vibr",
            title = getString(R.string.p_msg_vibr),
            summaryOn = getString(R.string.p_msg_vibr_on),
            summaryOff = getString(R.string.p_msg_vibr_off),
            default = false,
        ))
        items.add(PrefItem.Ringtone(
            key = "pos_notify_ringtone",
            title = getString(R.string.p_pos_ring),
            summary = getString(R.string.p_pos_ring_summary),
        ))

        // ---- Notification: digipeated position reports ----
        items.add(PrefItem.Category(title = getString(R.string.p_dgp)))
        items.add(PrefItem.Switch(
            key = "dgp_notify_led",
            title = getString(R.string.p_msg_led),
            summaryOn = getString(R.string.p_msg_led_on),
            summaryOff = getString(R.string.p_msg_led_off),
            default = false,
        ))
        items.add(PrefItem.Switch(
            key = "dgp_notify_vibr",
            title = getString(R.string.p_msg_vibr),
            summaryOn = getString(R.string.p_msg_vibr_on),
            summaryOff = getString(R.string.p_msg_vibr_off),
            default = false,
        ))
        items.add(PrefItem.Ringtone(
            key = "dgp_notify_ringtone",
            title = getString(R.string.p_dgp_ring),
            summary = getString(R.string.p_dgp_ring_summary),
        ))

        // ---- Notification: tracking start/stop ----
        items.add(PrefItem.Category(title = getString(R.string.p_tracking)))
        items.add(PrefItem.Ringtone(
            key = "start_notify_ringtone",
            title = getString(R.string.p_start_ring),
            summary = getString(R.string.p_start_ring_summary),
        ))
        items.add(PrefItem.Ringtone(
            key = "stop_notify_ringtone",
            title = getString(R.string.p_stop_ring),
            summary = getString(R.string.p_stop_ring_summary),
        ))

        // ---- Frequency control category ----
        items.add(PrefItem.Category(title = getString(R.string.freq_control)))
        items.add(PrefItem.Switch(
            key = "freq_control",
            title = getString(R.string.freq_control_options),
            summary = getString(R.string.freq_control_options_summary),
            default = false,
        ))
        items.add(PrefItem.EditText(
            key = "frequency_control_value",
            title = getString(R.string.freq_control_title),
            summary = getString(R.string.freq_control_summary),
            dialogTitle = getString(R.string.freq_control_title),
            default = "144.390",
            hint = getString(R.string.freq_control_hint),
            isNumeric = true,
        ))

        return items
    }

    private fun exportPrefs() {
        val filename = "profile-${SimpleDateFormat("yyyyMMdd-HHmm").format(Date())}.aprs"
        val directory = UIHelper.getExportDirectory(this)
        val file = File(directory, filename)
        try {
            directory.mkdirs()
            val allPrefs = PreferenceManager.getDefaultSharedPreferences(this).all
            allPrefs.remove("map_zoom")
            val json = JSONObject(allPrefs)
            PrintWriter(file).use { fo ->
                fo.println(json.toString(2))
            }
            UIHelper.shareFile(this, file, filename)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "", Toast.LENGTH_LONG).show()
        }
    }

    private fun showTilesKeyDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.p_gtiles_key_hint)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(GoogleTiles.secrets(this@PrefsAct).getString(GoogleTiles.KEY_PREF, ""))
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.p_gtiles_key)
            .setMessage(R.string.p_gtiles_key_help)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                GoogleTiles.setApiKey(this, input.text.toString())
                refreshTick++
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Edit one value kept with the secrets (out of backups). */
    private fun secretDialog(title: String, help: String, key: String, hint: String, password: Boolean) {
        val input = android.widget.EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            inputType = if (password) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
            // Never show a stored password back; an empty box keeps nothing by mistake only if saved.
            if (!password) setText(OnlineLookups.get(this@PrefsAct, key))
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(help)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                OnlineLookups.set(this, key, input.text.toString())
                refreshTick++
            }
            .setNeutralButton("Clear") { _, _ ->
                OnlineLookups.set(this, key, "")
                refreshTick++
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun testTilesKey() {
        val key = GoogleTiles.apiKey(this)
        Thread {
            val err = GoogleTiles.testKey(key)
            runOnUiThread {
                val msg = if (err == null) getString(R.string.p_gtiles_test_ok)
                    else getString(R.string.p_gtiles_test_fail, err)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showRestartDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restart_required_title)
            .setMessage(R.string.restart_required_body)
            .setPositiveButton(R.string.restart_now) { _, _ ->
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                finishAffinity()
                if (intent != null) startActivity(intent)
                Process.killProcess(Process.myPid())
            }
            .setNegativeButton(R.string.restart_later, null)
            .show()
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun resolveContentUri(uri: Uri): String? {
        val parts = uri.path?.replace("/document/", "")?.split(":", limit = 2) ?: return null
        val storage = parts[0]
        val path = parts.getOrNull(1) ?: return null
        Log.d("PrefsAct", "resolveContentUri s=$storage p=$path")
        return if (storage == "primary") {
            "${Environment.getExternalStorageDirectory()}/$path"
        } else {
            "/storage/$storage/$path"
        }
    }
}
