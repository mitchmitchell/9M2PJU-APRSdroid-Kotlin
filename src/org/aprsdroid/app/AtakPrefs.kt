package org.aprsdroid.app

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import org.aprsdroid.app.export.StationExporter
import org.aprsdroid.app.export.TakTls
import org.aprsdroid.app.ui.PrefItem
import org.aprsdroid.app.ui.PreferenceScreen
import org.aprsdroid.app.ui.theme.AprsTheme
import java.text.DateFormat

/**
 * Settings → ATAK / TAK export.
 *
 * Changes apply immediately while the APRS service is running.
 */
class AtakPrefs : ComponentActivity() {

    private var refreshTick by mutableStateOf(0)

    private val p12Picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askPassword(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UIHelper.applySystemBarInsets(this)
        setContent {
            AprsTheme {
                PreferenceScreen(
                    title = getString(R.string.p__atak),
                    onBack = { finish() },
                    items = run { refreshTick; items() },
                )
            }
        }
    }

    private fun items(): List<PrefItem> {
        val items = mutableListOf<PrefItem>()
        items.add(PrefItem.Switch(
            key = "atak.enabled",
            title = getString(R.string.p_atak_enabled),
            summary = getString(R.string.p_atak_enabled_summary),
            default = false,
        ))

        items.add(PrefItem.Category(getString(R.string.p_atak_local)))
        items.add(PrefItem.Switch(
            key = "atak.udp.enabled",
            title = getString(R.string.p_atak_udp),
            summary = getString(R.string.p_atak_udp_summary),
            default = true,
            dependency = "atak.enabled",
        ))
        items.add(PrefItem.EditText(
            key = "atak.udp.host",
            title = getString(R.string.p_atak_host),
            summary = getString(R.string.p_atak_udp_host_summary),
            default = "127.0.0.1",
            dependency = "atak.udp.enabled",
        ))
        items.add(PrefItem.EditText(
            key = "atak.udp.port",
            title = getString(R.string.p_atak_port),
            default = "4242",
            keyboardType = KeyboardType.Number,
            isNumeric = true,
            dependency = "atak.udp.enabled",
        ))

        items.add(PrefItem.Category(getString(R.string.p_atak_server)))
        items.add(PrefItem.Switch(
            key = "atak.tak.enabled",
            title = getString(R.string.p_atak_tak),
            summary = getString(R.string.p_atak_tak_summary),
            default = false,
            dependency = "atak.enabled",
        ))
        items.add(PrefItem.EditText(
            key = "atak.tak.host",
            title = getString(R.string.p_atak_host),
            summary = getString(R.string.p_atak_tak_host_summary),
            dependency = "atak.tak.enabled",
        ))
        items.add(PrefItem.EditText(
            key = "atak.tak.port",
            title = getString(R.string.p_atak_port),
            default = "8089",
            keyboardType = KeyboardType.Number,
            isNumeric = true,
            dependency = "atak.tak.enabled",
        ))
        items.add(PrefItem.Switch(
            key = "atak.tak.tls",
            title = getString(R.string.p_atak_tls),
            summaryOn = getString(R.string.p_atak_tls_on),
            summaryOff = getString(R.string.p_atak_tls_off),
            default = true,
            dependency = "atak.tak.enabled",
        ))
        items.add(PrefItem.Switch(
            key = "atak.tak.verify_host",
            title = getString(R.string.p_atak_verify_host),
            summaryOn = getString(R.string.p_atak_verify_host_on),
            summaryOff = getString(R.string.p_atak_verify_host_off),
            default = true,
            dependency = "atak.tak.tls",
        ))
        val cert = TakTls.loadInfo(StationExporter.clientP12(this))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_atak_cert),
            summary = if (cert == null) getString(R.string.p_atak_cert_none)
                else getString(
                    R.string.p_atak_cert_info, cert.subject, cert.caCount,
                    DateFormat.getDateInstance().format(cert.notAfter),
                ),
            onClick = { p12Picker.launch(arrayOf("application/x-pkcs12", "*/*")) },
        ))
        if (cert != null) {
            items.add(PrefItem.Clickable(
                title = getString(R.string.p_atak_cert_remove),
                onClick = {
                    StationExporter.clientP12(this).delete()
                    nudgeExporter()
                    refreshTick++
                },
            ))
        }

        items.add(PrefItem.Category(getString(R.string.p_atak_twoway)))
        items.add(PrefItem.Switch(
            key = "atak.chat",
            title = getString(R.string.p_atak_chat),
            summary = getString(R.string.p_atak_chat_summary),
            default = true,
            dependency = "atak.enabled",
        ))
        items.add(PrefItem.Switch(
            key = "atak.objects_from_tak",
            title = getString(R.string.p_atak_markers),
            summary = getString(R.string.p_atak_markers_summary),
            default = false,
            dependency = "atak.enabled",
        ))
        items.add(PrefItem.EditText(
            key = "atak.listen_port",
            title = getString(R.string.p_atak_listen_port),
            summary = getString(R.string.p_atak_listen_port_summary),
            default = "4349",
            keyboardType = KeyboardType.Number,
            isNumeric = true,
            dependency = "atak.enabled",
        ))

        items.add(PrefItem.Category(getString(R.string.p_atak_what)))
        items.add(PrefItem.Switch(
            key = "atak.include_own",
            title = getString(R.string.p_atak_own),
            summary = getString(R.string.p_atak_own_summary),
            default = false,
        ))
        items.add(PrefItem.Switch(
            key = "atak.include_objects",
            title = getString(R.string.p_atak_objects),
            default = true,
        ))
        items.add(PrefItem.Switch(
            key = "atak.include_internet",
            title = getString(R.string.p_atak_internet),
            summary = getString(R.string.p_atak_internet_summary),
            default = true,
        ))

        items.add(PrefItem.Category(getString(R.string.p_atak_cot)))
        items.add(PrefItem.EditText(
            key = "atak.stale_min",
            title = getString(R.string.p_atak_stale),
            summary = getString(R.string.p_atak_stale_summary),
            default = "60",
            keyboardType = KeyboardType.Number,
            isNumeric = true,
        ))
        items.add(PrefItem.EditText(
            key = "atak.uid_prefix",
            title = getString(R.string.p_atak_uid_prefix),
            summary = getString(R.string.p_atak_uid_prefix_summary),
            default = "APRS.",
        ))
        items.add(PrefItem.EditText(
            key = "atak.name_suffix",
            title = getString(R.string.p_atak_name_suffix),
            default = " (APRS)",
        ))
        items.add(PrefItem.EditText(
            key = "atak.cot_type",
            title = getString(R.string.p_atak_cot_type),
            summary = getString(R.string.p_atak_cot_type_summary),
            default = "a-f-G-U-C",
        ))

        items.add(PrefItem.Category(getString(R.string.p_atak_icons_cat)))
        items.add(PrefItem.Switch(
            key = "atak.icons",
            title = getString(R.string.p_atak_icons),
            summary = getString(R.string.p_atak_icons_summary),
            default = true,
        ))
        items.add(PrefItem.Clickable(
            title = getString(R.string.p_atak_iconset_export),
            summary = getString(R.string.p_atak_iconset_export_summary),
            onClick = { exportIconset() },
        ))
        items.add(PrefItem.EditText(
            key = "atak.iconset_uid",
            title = getString(R.string.p_atak_iconset_uid),
            summary = getString(R.string.p_atak_iconset_uid_summary),
            default = org.aprsdroid.app.export.AprsIconset.DEFAULT_UID,
            dependency = "atak.icons",
        ))
        return items
    }

    /** Build the APRS iconset data package from the app's own symbol sheet and hand it to ATAK. */
    private fun exportIconset() {
        Thread {
            try {
                val uid = PrefsWrapper(this).getString("atak.iconset_uid", org.aprsdroid.app.export.AprsIconset.DEFAULT_UID)
                    .trim().ifEmpty { org.aprsdroid.app.export.AprsIconset.DEFAULT_UID }
                val file = java.io.File(UIHelper.getExportDirectory(this), "APRS-Icons.zip")
                val sheet = AprsSymbols.sheet(this)
                val cell = sheet.width / 16
                file.outputStream().use { out ->
                    org.aprsdroid.app.export.AprsIconset.writeZip(out, uid) { table, index ->
                        val r = AprsSymbols.rect(index, table, cell)
                        val bmp = android.graphics.Bitmap.createBitmap(sheet, r.left, r.top, cell, cell)
                        val bytes = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
                        bytes.toByteArray()
                    }
                }
                runOnUiThread { UIHelper.shareFile(this, file, file.name, "application/zip") }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.p_atak_iconset_export_fail, e.message ?: e.toString()), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun askPassword(uri: android.net.Uri) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val frame = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.p_atak_cert_password)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pw = input.text.toString().toCharArray()
                try {
                    val info = contentResolver.openInputStream(uri)!!.use {
                        TakTls.importClientP12(it, pw, StationExporter.clientP12(this))
                    }
                    Toast.makeText(this, getString(R.string.p_atak_cert_ok, info.subject), Toast.LENGTH_LONG).show()
                    nudgeExporter()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.p_atak_cert_fail, e.message ?: e.toString()), Toast.LENGTH_LONG).show()
                } finally {
                    pw.fill(' ')
                }
                refreshTick++
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The certificate is a file, not a preference: touch a pref so a running exporter reconnects. */
    private fun nudgeExporter() {
        val prefs = PrefsWrapper(this)
        prefs.prefs.edit().putLong("atak.cert_changed", System.currentTimeMillis()).apply()
    }
}
