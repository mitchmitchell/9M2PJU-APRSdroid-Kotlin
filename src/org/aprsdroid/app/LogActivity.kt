package org.aprsdroid.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import org.aprsdroid.app.ui.PostListScreen
import org.aprsdroid.app.ui.PostListViewModel
import org.aprsdroid.app.ui.theme.AprsTheme

/**
 * Kotlin/Compose implementation of `LogActivity`.
 *
 * Shows the packet log / activity feed. Uses [PostListViewModel]
 * backed by Room to reactively display packets as they arrive.
 * Includes Start/Stop service buttons and bottom navigation.
 */
class LogActivity : ComponentActivity() {

    private val viewModel: PostListViewModel by viewModels()
    private val prefs by lazy { PrefsWrapper(this) }
    private val service = org.aprsdroid.app.ui.ServiceController(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UIHelper.applySystemBarInsets(this)
        setContent {
            AprsTheme {
                PostListScreen(
                    viewModel = viewModel,
                    onNavigate = { target -> AprsNavigation.navigateTo(this, target, prefs) },
                    onPreferences = { AprsNavigation.openPreferences(this) },
                    service = service,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.prefs.edit().putString("activity", "log").apply()
    }
}

enum class NavTarget { HUB, LOG, MAP, MESSAGES }
