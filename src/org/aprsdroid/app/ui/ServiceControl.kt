package org.aprsdroid.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.aprsdroid.app.AprsBackend
import org.aprsdroid.app.AprsService
import org.aprsdroid.app.LocationSources
import org.aprsdroid.app.PrefsWrapper
import org.aprsdroid.app.UIHelper

/**
 * Starts and stops the APRS service on behalf of a screen, asking for the
 * permissions the chosen connection and location source need first.
 *
 * Create it as a field of the activity (it registers a permission
 * launcher, which must happen before the activity starts).
 */
class ServiceController(private val activity: ComponentActivity) {

    private val prefs by lazy { PrefsWrapper(activity) }
    private var pendingAction: String? = null

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val action = pendingAction
        pendingAction = null
        if (action != null && results.values.all { it }) launch(action)
    }

    /** Beacon continuously until stopped. */
    fun startTracking() = request(AprsService.SERVICE)

    /** Send one position now. If not tracking, the service stops again afterwards. */
    fun sendPosition() = request(AprsService.SERVICE_ONCE)

    fun stop() {
        try {
            prefs.setBoolean("service_running", false)
            activity.stopService(AprsService.intent(activity, AprsService.SERVICE))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop service", e)
            Toast.makeText(activity, "Could not stop tracking: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun request(action: String) {
        try {
            val perms = LinkedHashSet<String>()
            perms.addAll(AprsBackend.defaultBackendPermissions(prefs))
            perms.addAll(LocationSources.getPermissions(prefs))
            val needed = perms.filter {
                activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isEmpty()) {
                launch(action)
            } else {
                pendingAction = action
                permissionLauncher.launch(needed.toTypedArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            Toast.makeText(activity, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launch(action: String) {
        try {
            activity.startService(AprsService.intent(activity, action))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            Toast.makeText(activity, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "APRSdroid.SvcCtl"
    }
}

/**
 * Whether the APRS service is running, kept current from the service's
 * broadcasts and re-checked whenever the screen resumes.
 */
@Composable
fun rememberServiceRunning(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var running by remember { mutableStateOf(AprsService.running) }
    DisposableEffect(context, lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                running = AprsService.running
            }
        }
        val filter = IntentFilter().apply {
            addAction(AprsService.SERVICE_STARTED)
            addAction(AprsService.SERVICE_STOPPED)
            addAction(AprsService.UPDATE)
        }
        UIHelper.safeRegisterReceiver(context, receiver, filter)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) running = AprsService.running
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }
    return running
}
