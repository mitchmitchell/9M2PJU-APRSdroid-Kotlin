package org.aprsdroid.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aprsdroid.app.AprsService
import org.aprsdroid.app.PrefsWrapper
import org.aprsdroid.app.UIHelper
import org.aprsdroid.app.UpdateChecker
import org.aprsdroid.app.data.AprsDatabase
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Shared overflow menu for all main screens.
 *
 * Provides: Preferences, Export Log, Clear Log, Clear Messages,
 * Age Filter, Check Updates, About, Report Bug.
 */
@Composable
fun AprsOverflowMenu(
    showAgeFilter: Boolean = false,
    onPreferences: () -> Unit,
    onAgeChanged: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showAgeDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showMenu = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
    }

    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false },
    ) {
        DropdownMenuItem(
            text = { Text("Preferences") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = {
                showMenu = false
                onPreferences()
            },
        )
        if (showAgeFilter && onAgeChanged != null) {
            DropdownMenuItem(
                text = { Text("Show last...") },
                leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                onClick = {
                    showMenu = false
                    showAgeDialog = true
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Export Log") },
            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
            onClick = {
                showMenu = false
                exportLog(context as Activity)
            },
        )
        DropdownMenuItem(
            text = { Text("Clear Log") },
            leadingIcon = { Icon(Icons.Filled.Clear, contentDescription = null) },
            onClick = {
                showMenu = false
                clearLog(context)
            },
        )
        DropdownMenuItem(
            text = { Text("Clear Messages") },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = {
                showMenu = false
                clearAllMessages(context)
            },
        )
        DropdownMenuItem(
            text = { Text("Check for Updates") },
            leadingIcon = { Icon(Icons.Filled.Update, contentDescription = null) },
            onClick = {
                showMenu = false
                UpdateChecker.forceCheckForUpdates(context as Activity)
            },
        )
        DropdownMenuItem(
            text = { Text("About") },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = {
                showMenu = false
                showAbout = true
            },
        )
        DropdownMenuItem(
            text = { Text("Report Bug") },
            leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
            onClick = {
                showMenu = false
                reportBug(context as Activity)
            },
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    if (showAgeDialog && onAgeChanged != null) {
        AgeDialog(
            onSelected = { minutes ->
                showAgeDialog = false
                PrefsWrapper(context).prefs.edit()
                    .putLong("show_age", minutes * 60 * 1000L)
                    .commit()
                onAgeChanged()
            },
            onDismiss = { showAgeDialog = false },
        )
    }
}

/** Where About and Report a bug point. One place to change when the project gets a home page. */
private const val PROJECT_URL = "https://github.com/mitchmitchell/APRSDroid-TAK"

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    } catch (_: Exception) { "unknown" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("APRSDroid-TAK") },
        text = {
            Text(
                "Version $version\n\n" +
                "APRSdroid with a TAK link: an Android app for the Automatic Packet Reporting System (APRS). " +
                "Free software under the GNU GPL v2.\n\n" +
                "Built on the work of:\n" +
                "• Bob Bruninga, WB4APR: creator of APRS\n" +
                "• Georg Lukas, DO1GL: original APRSdroid\n" +
                "• Mike, NA7Q: BLE TNC, digipeater, IGate, offline maps\n" +
                "• 9M2PJU: Kotlin and Compose rewrite\n\n" +
                "This build adds Google Maps, sending stations to ATAK and TAK Server, " +
                "and a redesigned interface.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse(PROJECT_URL))
                context.startActivity(intent)
            }) { Text("Project page") }
        },
    )
}

@Composable
private fun AgeDialog(
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ages = listOf(
        5 to "5 minutes", 10 to "10 minutes", 15 to "15 minutes",
        30 to "30 minutes", 60 to "1 hour", 120 to "2 hours",
        240 to "4 hours", 720 to "12 hours", 1440 to "1 day", 2880 to "2 days",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show stations from last...") },
        text = {
            Text("Select the time range for displaying stations:")
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
    // Simple selection via a second dropdown would be complex;
    // use a simple list dialog approach
}

private fun exportLog(activity: Activity) {
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val db = AprsDatabase.get(activity)
            // Full log: packets plus info, error and crash entries.
            val posts = db.postDao().getFullLogList()
            if (posts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Nothing to export", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val dir = UIHelper.getExportDirectory(activity)
            dir.mkdirs()
            val sdf = SimpleDateFormat("yyyyMMdd-HHmm")
            val file = File(dir, "aprsdroid-${sdf.format(Date())}.log")
            PrintWriter(file).use { writer ->
                writer.println("# APRSdroid log export")
                writer.println("# ${activity.getString(org.aprsdroid.app.R.string.build_version)}" +
                    " / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
                    " / ${Build.MANUFACTURER} ${Build.MODEL}")
                for (post in posts) {
                    val typeStr = when (post.type) {
                        0 -> "TX"
                        1 -> "INFO"
                        2 -> "ERR"
                        3 -> "RX"
                        4 -> "TX"
                        5 -> "DP"
                        6 -> "IG"
                        else -> "??"
                    }
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(post.ts))
                    val status = if (post.type == 1 || post.type == 2) "${post.status ?: ""}\t" else ""
                    val msg = org.aprsdroid.app.AprsPacket.maskPasscode(post.message ?: "")
                    writer.println("$ts\t$typeStr\t$status$msg")
                }
            }
            withContext(Dispatchers.Main) {
                UIHelper.shareFile(activity, file, file.name)
                Toast.makeText(activity, "Exported ${posts.size} log entries", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun clearLog(context: android.content.Context) {
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val db = AprsDatabase.get(context)
            // Trim older than 2 days
            val cutoff = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
            db.postDao().trimOlderThan(cutoff)
            db.positionDao().trimOlderThan(cutoff)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Log cleared (kept last 2 days)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Clear failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun clearAllMessages(context: android.content.Context) {
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val db = AprsDatabase.get(context)
            db.messageDao().deleteAll()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "All messages cleared", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Clear failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun reportBug(activity: Activity) {
    val version = try {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        pi.versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    val body = "Please describe the bug:\n\n\n" +
        "App version: $version\n" +
        "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
        "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n"

    // Bugs go to the project's issue tracker, with the device details filled in.
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("$PROJECT_URL/issues/new").buildUpon()
            .appendQueryParameter("title", "Bug: ")
            .appendQueryParameter("body", body)
            .build(),
    )
    try {
        activity.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(activity, "No app found to open the issue tracker", Toast.LENGTH_SHORT).show()
    }
}
