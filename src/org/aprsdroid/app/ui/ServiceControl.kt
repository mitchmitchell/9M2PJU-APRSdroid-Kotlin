package org.aprsdroid.app.ui

import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.aprsdroid.app.AprsBackend
import org.aprsdroid.app.AprsService
import org.aprsdroid.app.LocationSources
import org.aprsdroid.app.PrefsWrapper

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

/** Whether the APRS service is running; updates the moment the service starts or stops. */
@Composable
fun rememberServiceRunning(): Boolean {
    val running by AprsService.runningState.collectAsState()
    return running
}
