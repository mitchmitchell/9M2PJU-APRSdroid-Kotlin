package org.aprsdroid.app.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aprsdroid.app.NavTarget
import org.aprsdroid.app.ui.theme.AprsTheme

/**
 * Bottom chrome shared by the main screens: the transmit strip (when the
 * screen can control the service) and five labelled tabs.
 */
@Composable
fun AprsBottomBar(
    current: NavTarget,
    onNavigate: (NavTarget) -> Unit,
    onPreferences: () -> Unit,
    service: ServiceController? = null,
) {
    Column {
        if (service != null) TransmitStrip(service)
        HorizontalDivider(color = AprsTheme.colors.line)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
        ) {
            Tab("Stations", Icons.Filled.Sensors, current == NavTarget.HUB) { onNavigate(NavTarget.HUB) }
            Tab("Map", Icons.Filled.Map, current == NavTarget.MAP) { onNavigate(NavTarget.MAP) }
            Tab("Messages", Icons.AutoMirrored.Filled.Chat, current == NavTarget.MESSAGES) { onNavigate(NavTarget.MESSAGES) }
            Tab("Log", Icons.AutoMirrored.Filled.ListAlt, current == NavTarget.LOG) { onNavigate(NavTarget.LOG) }
            Tab("Settings", Icons.Filled.Settings, false, onPreferences)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        // Tapping the tab you are on does nothing (no reload, no flicker).
        onClick = { if (!selected) onClick() },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, maxLines = 1) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * The transmit strip: an on-air lamp with the current state, and the two
 * things you do most in the field — send one position, or start/stop
 * tracking — as large buttons that are always in the same place.
 */
@Composable
fun TransmitStrip(service: ServiceController) {
    val running = rememberServiceRunning()
    val linkError = rememberLinkStatus()
    val colors = AprsTheme.colors
    val lampColor = when {
        !running -> colors.muted
        linkError != 0 -> colors.error
        else -> colors.tx
    }
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        HorizontalDivider(color = colors.line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnAirLamp(color = lampColor, pulsing = running && linkError == 0)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        !running -> "Not tracking"
                        linkError != 0 -> "Tracking, link down"
                        else -> "Tracking"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (running) lampColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { service.sendPosition() },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Send position", maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (running) service.stop() else service.startTracking() },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = if (running) ButtonDefaults.buttonColors(
                    containerColor = colors.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) else ButtonDefaults.buttonColors(
                    containerColor = colors.tx,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                Text(if (running) "Stop tracking" else "Start tracking", maxLines = 1, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** A small lamp that breathes slowly while on air. Static if the user has animations turned off. */
@Composable
private fun OnAirLamp(color: androidx.compose.ui.graphics.Color, pulsing: Boolean) {
    val context = LocalContext.current
    val animationsOn = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val alpha = if (pulsing && animationsOn) {
        val t = rememberInfiniteTransition(label = "lamp")
        val a by t.animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "lampAlpha",
        )
        a
    } else 1f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(18.dp)) {
        Box(Modifier.size(18.dp).alpha(alpha * 0.3f).clip(CircleShape).background(color))
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}
