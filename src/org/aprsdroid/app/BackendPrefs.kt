package org.aprsdroid.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.aprsdroid.app.ui.PrefItem
import org.aprsdroid.app.ui.PreferenceScreen
import org.aprsdroid.app.ui.stringArray

/**
 * Kotlin/Compose port of `BackendPrefs`.
 *
 * Backend connection preferences. Dynamically builds the preference
 * list based on the selected protocol (APRS-IS, AFSK, KISS, TNC2,
 * Kenwood) and link type. Handles passcode dialog and GPS permission.
 */
class BackendPrefs : ComponentActivity() {

    private val prefs by lazy { PrefsWrapper(this) }

    private var showBluetoothPicker by mutableStateOf(false)
    private var settingsVersion by mutableStateOf(0)

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showBluetoothPicker = true
        } else {
            android.widget.Toast.makeText(
                this, "Allow Nearby devices so the app can list your paired TNCs.", android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val gpsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            // Grant the kenwood.gps preference
            val sp = getSharedPreferences("org.aprsdroid.app_preferences", MODE_PRIVATE)
            sp.edit().putBoolean("kenwood.gps", true).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UIHelper.applySystemBarInsets(this)
        setContent {
            var showPasscode by remember { mutableStateOf(false) }
            // Re-read the summaries (e.g. the chosen TNC) whenever something changes.
            @Suppress("UNUSED_VARIABLE")
            val refresh = settingsVersion
            PreferenceScreen(
                title = getString(R.string.p_connsetup),
                onBack = { finish() },
                items = backendPrefItems(showPasscode = { showPasscode = true }),
            )
            if (showBluetoothPicker) {
                org.aprsdroid.app.ui.theme.AprsTheme {
                    BluetoothTncPicker(
                        onPicked = { address ->
                            prefs.set("bluetooth.target", address)
                            showBluetoothPicker = false
                            settingsVersion++
                        },
                        onDismiss = { showBluetoothPicker = false },
                    )
                }
            }
            if (showPasscode) {
                PasscodeDialogCompose(
                    activity = this,
                    firstrun = false,
                    onDismiss = { showPasscode = false },
                )
            }
        }
    }

    private fun backendPrefItems(showPasscode: () -> Unit = {}): List<PrefItem> {
        val items = mutableListOf<PrefItem>()

        // Common backend preferences
        items.add(PrefItem.Switch(
            key = "conn_log",
            title = getString(R.string.p_connlog),
            summary = getString(R.string.p_connlog_summary),
        ))

        // Protocol selection
        val connTypeEntries = stringArray(R.array.p_conntype_e)
        val connTypeValues = stringArray(R.array.p_conntype_ev)
        items.add(PrefItem.List(
            key = "proto",
            title = getString(R.string.p_conntype),
            entries = connTypeEntries,
            entryValues = connTypeValues,
            default = "aprsis",
            dialogTitle = getString(R.string.p_conntype_entry),
            onChanged = { recreate() },
        ))

        // Protocol-specific preferences
        val proto = prefs.getString("proto", "aprsis")
        items.addAll(protoItems(proto, showPasscode))

        return items
    }

    private fun protoItems(proto: String, showPasscode: () -> Unit): List<PrefItem> {
        return when (proto) {
            "aprsis" -> aprsisItems(showPasscode)
            "afsk" -> afskItems()
            "kenwood" -> kenwoodItems()
            "kiss" -> kissItems()
            "tnc2" -> tnc2Items()
            else -> aprsisItems(showPasscode)
        }
    }

    private fun aprsisItems(showPasscode: () -> Unit): List<PrefItem> = listOf(
        PrefItem.Category(title = getString(R.string.p_conn_aprsis)),
        PrefItem.Clickable(
            title = getString(R.string.p_passcode),
            summary = getString(R.string.p_passcode_summary),
            onClick = showPasscode,
        ),
        PrefItem.Clickable(
            title = getString(R.string.p_passreq),
            summary = getString(R.string.p_passreq_summary),
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(getString(R.string.passcode_url))))
            },
        ),
        PrefItem.List(
            key = "aprsis",
            title = getString(R.string.p_link),
            entries = stringArray(R.array.p_aprsis_e),
            entryValues = stringArray(R.array.p_aprsis_ev),
            default = AprsBackend.linkDefault("aprsis"),
            dialogTitle = getString(R.string.p_link_entry),
            onChanged = { recreate() },
        ),
    ) + aprsisLinkItems()

    private fun afskItems(): List<PrefItem> = listOf(
        PrefItem.Category(title = getString(R.string.p_conn_afsk)),
        PrefItem.Switch(
            key = "afsk.hqdemod",
            title = getString(R.string.p_afsk_hqdemod),
            summary = getString(R.string.p_afsk_hqdemod_summary),
            default = true,
        ),
        PrefItem.Switch(
            key = "afsk.btsco",
            title = getString(R.string.p_afsk_btsco),
            summary = getString(R.string.p_afsk_btsco_summary),
            default = false,
            dependency = "afsk.hqdemod",
        ),
        PrefItem.List(
            key = "afsk.output",
            title = getString(R.string.p_afsk_output),
            entries = stringArray(R.array.p_afsk_out_e),
            entryValues = stringArray(R.array.p_afsk_out_ev),
            default = "0",
            dialogTitle = getString(R.string.p_afsk_output),
        ),
        PrefItem.EditText(
            key = "afsk.prefix",
            title = getString(R.string.p_afsk_prefix),
            summary = getString(R.string.p_afsk_prefix_summary),
            dialogTitle = getString(R.string.p_afsk_prefix_entry),
            default = "200",
            isNumeric = true,
        ),
        PrefItem.List(
            key = "afsk",
            title = getString(R.string.p_link),
            entries = stringArray(R.array.p_afsk_e),
            entryValues = stringArray(R.array.p_afsk_ev),
            default = "vox",
            dialogTitle = getString(R.string.p_link_entry),
        ),
    )

    private fun kenwoodItems(): List<PrefItem> = listOf(
        PrefItem.Category(title = getString(R.string.p_conn_kwd)),
        PrefItem.Clickable(
            title = getString(R.string.p_conn_kwd_info),
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(getString(R.string.kwd_help_url))))
            },
        ),
        PrefItem.Switch(
            key = "kenwood.gps",
            title = getString(R.string.p_conn_kwd_gps),
            summary = getString(R.string.p_conn_kwd_gps_summary),
            default = false,
            onChanged = { checked ->
                if (checked) {
                    gpsPermissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                }
            },
        ),
        PrefItem.Switch(
            key = "kenwood.gps_debug",
            title = getString(R.string.p_conn_kwd_gps_debug),
            summary = getString(R.string.p_conn_kwd_gps_debug_summary),
            default = false,
            dependency = "kenwood.gps",
        ),
        PrefItem.List(
            key = "link",
            title = getString(R.string.p_link),
            entries = stringArray(R.array.p_link_e),
            entryValues = stringArray(R.array.p_link_ev),
            default = AprsBackend.linkDefault("link"),
            dialogTitle = getString(R.string.p_link_entry),
            onChanged = { recreate() },
        ),
    ) + linkItems()

    private fun kissItems(): List<PrefItem> = listOf(
        PrefItem.Category(title = getString(R.string.p_conn_kiss)),
        PrefItem.EditText(
            key = "kiss.init",
            title = getString(R.string.p_tnc_init),
            summary = getString(R.string.p_tnc_init_summary),
            dialogTitle = getString(R.string.p_tnc_init),
            default = "",
        ),
        PrefItem.EditText(
            key = "kiss.delay",
            title = getString(R.string.p_tnc_delay),
            summary = getString(R.string.p_tnc_delay_summary),
            dialogTitle = getString(R.string.p_tnc_delay_entry),
            default = "300",
            isNumeric = true,
        ),
        PrefItem.List(
            key = "link",
            title = getString(R.string.p_link),
            entries = stringArray(R.array.p_link_e),
            entryValues = stringArray(R.array.p_link_ev),
            default = AprsBackend.linkDefault("link"),
            dialogTitle = getString(R.string.p_link_entry),
            onChanged = { recreate() },
        ),
    ) + linkItems()

    private fun tnc2Items(): List<PrefItem> = listOf(
        PrefItem.Category(title = getString(R.string.p_conn_tnc2)),
        PrefItem.List(
            key = "link",
            title = getString(R.string.p_link),
            entries = stringArray(R.array.p_link_e),
            entryValues = stringArray(R.array.p_link_ev),
            default = AprsBackend.linkDefault("link"),
            dialogTitle = getString(R.string.p_link_entry),
            onChanged = { recreate() },
        ),
    ) + linkItems()

    // ---- settings that depend on the chosen link ----

    private fun hasBluetoothPermission(): Boolean =
        checkSelfPermission(AprsBackend.BLUETOOTH_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Name of the chosen Bluetooth TNC for the settings row, or a prompt to choose one. */
    private fun bluetoothTncSummary(): String {
        val address = prefs.getString("bluetooth.target", "")
        if (address.isEmpty()) return "None chosen. Tap to pick one of your paired devices."
        val name = try {
            if (hasBluetoothPermission()) {
                getSystemService(android.bluetooth.BluetoothManager::class.java)
                    ?.adapter?.getRemoteDevice(address)?.name
            } else null
        } catch (_: Exception) {
            null
        }
        return if (name.isNullOrEmpty()) address else "$name ($address)"
    }

    /** Settings for the link chosen under a TNC protocol (KISS, TNC2, Kenwood). */
    private fun linkItems(): List<PrefItem> =
        when (prefs.getString("link", AprsBackend.linkDefault("link"))) {
            "bluetooth" -> listOf(
                PrefItem.Clickable(
                    title = getString(R.string.p_bt_tnc_device),
                    summary = bluetoothTncSummary(),
                    onClick = {
                        if (hasBluetoothPermission()) showBluetoothPicker = true
                        else bluetoothPermissionLauncher.launch(AprsBackend.BLUETOOTH_PERMISSION)
                    },
                ),
                PrefItem.Clickable(
                    title = getString(R.string.p_bt_prefs),
                    summary = getString(R.string.p_bt_prefs_summary),
                    onClick = { openBluetoothSettings() },
                ),
            )
            "tcpip" -> listOf(
                PrefItem.EditText(
                    key = "tcp.server",
                    title = getString(R.string.p_host),
                    summary = getString(R.string.p_tcptnc_server_summary),
                    dialogTitle = getString(R.string.p_tcptnc_entry),
                    hint = getString(R.string.p_host_hint),
                    default = "",
                ),
                PrefItem.EditText(
                    key = "tcp.sotimeout",
                    title = getString(R.string.p_sotimeout),
                    summary = getString(R.string.p_sotimeout_summary),
                    dialogTitle = getString(R.string.p_sotimeout_entry),
                    default = "120",
                    isNumeric = true,
                ),
            )
            "ble" -> listOf(notAvailable("Bluetooth LE TNCs"))
            "usb" -> listOf(notAvailable("USB serial TNCs"))
            else -> emptyList()
        }

    /** Server settings for the APRS-IS connection type in use. */
    private fun aprsisLinkItems(): List<PrefItem> =
        when (prefs.getString("aprsis", AprsBackend.linkDefault("aprsis"))) {
            "udp" -> listOf(serverItem("udp.server"))
            "http" -> listOf(serverItem("http.server"))
            else -> listOf(
                PrefItem.EditText(
                    key = "tcp.server",
                    title = getString(R.string.p_host),
                    summary = getString(R.string.p_tcp_server_summary),
                    dialogTitle = getString(R.string.p_host_entry),
                    hint = getString(R.string.p_host_hint),
                    default = "rotate.aprs2.net",
                ),
                PrefItem.EditText(
                    key = "tcp.filterdist",
                    title = getString(R.string.p_filterdist),
                    summary = getString(R.string.p_filterdist_summary),
                    dialogTitle = getString(R.string.p_filterdist_entry),
                    default = "50",
                    isNumeric = true,
                ),
                PrefItem.EditText(
                    key = "tcp.filter",
                    title = getString(R.string.p_filter),
                    summary = getString(R.string.p_filter_summary),
                    dialogTitle = getString(R.string.p_filter_entry),
                    hint = getString(R.string.p_filter_hint),
                    default = "",
                ),
                PrefItem.Clickable(
                    title = getString(R.string.p_filterhelp),
                    summary = getString(R.string.p_filterhelp_summary),
                    onClick = {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.aprs-is.net/javAPRSFilter.aspx")))
                    },
                ),
                PrefItem.EditText(
                    key = "tcp.sotimeout",
                    title = getString(R.string.p_sotimeout),
                    summary = getString(R.string.p_sotimeout_summary),
                    dialogTitle = getString(R.string.p_sotimeout_entry),
                    default = "120",
                    isNumeric = true,
                ),
            )
        }

    private fun serverItem(key: String) = PrefItem.EditText(
        key = key,
        title = getString(R.string.p_host),
        summary = getString(R.string.p_host_summary),
        dialogTitle = getString(R.string.p_host_entry),
        hint = getString(R.string.p_host_hint),
        default = getString(R.string.aprs_server),
    )

    private fun notAvailable(what: String) = PrefItem.Clickable(
        title = "Not available yet",
        summary = "$what are not supported in this version. Use Bluetooth SPP or TCP/IP.",
        enabled = false,
        onClick = {},
    )

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {
        }
    }

    /** Lists the phone's paired Bluetooth devices so the user can pick their TNC. */
    @androidx.compose.runtime.Composable
    private fun BluetoothTncPicker(onPicked: (String) -> Unit, onDismiss: () -> Unit) {
        val devices = remember {
            try {
                getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices
                    ?.map { (it.name ?: it.address) to it.address }
                    ?.sortedBy { it.first.lowercase() }
                    .orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        }
        val current = prefs.getString("bluetooth.target", "")
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { androidx.compose.material3.Text(getString(R.string.p_bt_tnc_device_entry)) },
            text = {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    if (devices.isEmpty()) {
                        androidx.compose.material3.Text(
                            "No paired devices. Pair your TNC in Android's Bluetooth settings first, " +
                                "and make sure Bluetooth is on.",
                        )
                    }
                    for ((name, address) in devices) {
                        androidx.compose.material3.ListItem(
                            headlineContent = { androidx.compose.material3.Text(name) },
                            supportingContent = { androidx.compose.material3.Text(address) },
                            leadingContent = {
                                androidx.compose.material3.RadioButton(
                                    selected = address == current, onClick = { onPicked(address) },
                                )
                            },
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = androidx.compose.ui.Modifier.clickable { onPicked(address) },
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { openBluetoothSettings() }) {
                    androidx.compose.material3.Text(getString(R.string.p_bt_prefs))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text(getString(android.R.string.cancel))
                }
            },
        )
    }
}
