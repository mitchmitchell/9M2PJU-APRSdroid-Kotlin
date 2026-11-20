package org.aprsdroid.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.aprsdroid.app.NavTarget
import org.aprsdroid.app.data.MessageEntity

/**
 * Compose screen for the conversations list.
 *
 * Shows the most recent message per callsign. Tapping a conversation
 * opens the message thread. A FAB allows starting a new conversation.
 * Long-press shows a context menu (clear messages, details, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(
    viewModel: ConversationsViewModel,
    onConversationClick: (String) -> Unit,
    onNavigate: (NavTarget) -> Unit = {},
    onPreferences: () -> Unit = {},
) {
    val conversations by viewModel.conversations.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var contextMenuCall by remember { mutableStateOf<String?>(null) }
    var openService by remember { mutableStateOf<org.aprsdroid.app.AprsServices.Service?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Messages") })
        },
        bottomBar = {
            AprsBottomBar(
                current = NavTarget.MESSAGES,
                onNavigate = onNavigate,
                onPreferences = onPreferences,
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { showNewDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New message", fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // Room for the "New message" button, so it never covers the last row.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
        ) {
            item { ServiceCards(onOpen = { openService = it }) }
            item {
                Text(
                    "Conversations",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            if (conversations.isEmpty()) {
                item {
                    Text(
                        "No messages yet. Messages sent to your callsign appear here. " +
                            "Choose New message to write to another station, or try a service above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(conversations) { message ->
                ConversationItem(
                    message = message,
                    onClick = { onConversationClick(message.call) },
                    onLongClick = { contextMenuCall = message.call },
                )
                HorizontalDivider(color = org.aprsdroid.app.ui.theme.AprsTheme.colors.line.copy(alpha = 0.45f))
            }
        }
    }

    // New conversation dialog
    openService?.let { service ->
        ServiceDialog(
            service = service,
            onDismiss = { openService = null },
            onSend = { callsign, text ->
                openService = null
                sendMessage(context, callsign, text)
                // Open the conversation so the reply is seen when it arrives.
                onConversationClick(callsign)
            },
        )
    }

    if (showNewDialog) {
        NewConversationDialog(
            onDismiss = { showNewDialog = false },
            onConfirm = { call ->
                showNewDialog = false
                onConversationClick(call.uppercase().trim())
            },
        )
    }

    // Context menu
    contextMenuCall?.let { call ->
        CallsignContextMenu(
            call = call,
            expanded = true,
            onDismiss = { contextMenuCall = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    message: MessageEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = org.aprsdroid.app.ui.theme.AprsTheme.colors
    val incoming = message.type == MessageEntity.TYPE_INCOMING
    // Say plainly who spoke last and, for our own messages, what became of it.
    val prefix = when (message.type) {
        MessageEntity.TYPE_INCOMING -> ""
        MessageEntity.TYPE_OUT_NEW -> "You (sending): "
        MessageEntity.TYPE_OUT_REJECTED -> "You (rejected): "
        MessageEntity.TYPE_OUT_ABORTED -> "You (cancelled): "
        else -> "You: "
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A lamp marks threads where the other station spoke last.
        Box(
            Modifier
                .padding(end = 12.dp)
                .size(10.dp)
                .then(
                    if (incoming) Modifier.background(colors.rx, CircleShape) else Modifier,
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = message.call,
                style = org.aprsdroid.app.ui.theme.CallsignStyle,
                color = colors.callsign,
            )
            Text(
                text = prefix + (message.text ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (incoming) FontWeight.SemiBold else FontWeight.Normal,
                color = if (incoming) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Text(
            text = StationFormat.age(message.ts),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun NewConversationDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var callText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message") },
        text = {
            OutlinedTextField(
                value = callText,
                onValueChange = { callText = it.uppercase().trim() },
                label = { Text("To callsign") },
                placeholder = { Text("W1ABC-9") },
                singleLine = true,
                textStyle = org.aprsdroid.app.ui.theme.CallsignStyle,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(callText) },
                enabled = callText.isNotBlank(),
            ) { Text("Write message") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
