package org.aprsdroid.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aprsdroid.app.AprsPacket
import org.aprsdroid.app.NavTarget
import org.aprsdroid.app.data.PostEntity
import org.aprsdroid.app.ui.theme.AprsColors
import org.aprsdroid.app.ui.theme.AprsTheme
import org.aprsdroid.app.ui.theme.PacketStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The log: every packet sent and heard, plus service messages and errors,
 * newest first. Long-press a packet for the sender's action menu; tap a
 * long entry (such as a crash report) to expand it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostListScreen(
    viewModel: PostListViewModel,
    onNavigate: (NavTarget) -> Unit,
    onPreferences: () -> Unit,
    service: ServiceController? = null,
) {
    val posts by viewModel.posts.collectAsState()
    val linkError = rememberLinkStatus()
    val running = rememberServiceRunning()
    var contextMenuCall by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log") },
                actions = { AprsOverflowMenu(onPreferences = onPreferences) },
            )
        },
        bottomBar = {
            AprsBottomBar(
                current = NavTarget.LOG,
                onNavigate = onNavigate,
                onPreferences = onPreferences,
                service = service,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LinkStatusBar(linkError)
            if (posts.isEmpty()) {
                EmptyState(
                    title = "Nothing logged yet",
                    body = if (running) "Listening. Packets appear here as they are sent and heard."
                    else "Start tracking to send your position and hear other stations.",
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(posts, key = { it.id }) { post ->
                        PostItem(
                            post = post,
                            onLongClick = { extractCall(post.message)?.let { contextMenuCall = it } },
                        )
                        HorizontalDivider(color = AprsTheme.colors.line.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }

    contextMenuCall?.let { call ->
        CallsignContextMenu(call = call, expanded = true, onDismiss = { contextMenuCall = null })
    }
}

private fun typeColor(type: Int, c: AprsColors): Color = when (type) {
    PostEntity.TYPE_POST, PostEntity.TYPE_TX -> c.tx
    PostEntity.TYPE_INFO -> c.info
    PostEntity.TYPE_ERROR -> c.error
    PostEntity.TYPE_INCMG -> c.rx
    PostEntity.TYPE_DIGI -> c.digi
    PostEntity.TYPE_IG -> c.igate
    else -> c.muted
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
private const val COLLAPSED_LINES = 5

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PostItem(
    post: PostEntity,
    onLongClick: () -> Unit,
) {
    val colors = AprsTheme.colors
    val color = typeColor(post.type, colors)
    val isPacket = post.type != PostEntity.TYPE_INFO && post.type != PostEntity.TYPE_ERROR
    // Entries logged by older builds may still contain the passcode.
    val message = remember(post.message) { AprsPacket.maskPasscode(post.message ?: "").trim() }
    var expanded by remember(post.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick) { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                text = post.status?.takeIf { it.isNotBlank() } ?: if (post.type == PostEntity.TYPE_INFO) "Info" else "",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = remember(post.ts) { TIME_FORMAT.format(Date(post.ts)) },
                style = PacketStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (message.isNotEmpty()) {
            Text(
                text = message,
                style = if (isPacket || post.type == PostEntity.TYPE_ERROR) PacketStyle
                else MaterialTheme.typography.bodyMedium,
                color = if (post.type == PostEntity.TYPE_ERROR) color else MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

/**
 * Extract a callsign from an APRS packet message.
 * Format: "CALL>path:data" or "CALL-SSID>path:data"
 */
private fun extractCall(message: String?): String? {
    if (message.isNullOrEmpty()) return null
    val gt = message.indexOf('>')
    if (gt <= 0) return null
    return message.substring(0, gt)
}
