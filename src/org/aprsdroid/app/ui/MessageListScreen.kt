package org.aprsdroid.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.aprsdroid.app.AprsService
import org.aprsdroid.app.PrefsWrapper
import org.aprsdroid.app.R
import org.aprsdroid.app.data.AprsDatabase
import org.aprsdroid.app.data.MessageEntity

/**
 * Compose screen for a single message conversation (message thread
 * with one callsign). Includes message input field, send button,
 * context menu (copy, abort, resend), and start-tracking dialog.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessageListScreen(
    viewModel: MessageListViewModel,
    myCall: String,
    onBack: () -> Unit,
    onStartService: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var contextMenuFor by remember { mutableStateOf<MessageEntity?>(null) }
    var showStartTracking by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        viewModel.targetCall,
                        style = org.aprsdroid.app.ui.theme.CallsignStyle.copy(fontSize = 20.sp),
                    )
                },
                actions = {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(context, org.aprsdroid.app.StationActivity::class.java)
                                .putExtra("call", viewModel.targetCall),
                        )
                    }) { Text("Station") }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            MessageInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        sendMessage(context, viewModel.targetCall, inputText)
                        inputText = ""
                        if (!AprsService.running) {
                            showStartTracking = true
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "No messages with ${viewModel.targetCall}",
                    body = "Write one below. It is sent over APRS and retried until the other station confirms it.",
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages) { message ->
                    MessageBubble(
                        message = message,
                        myCall = myCall,
                        targetCall = viewModel.targetCall,
                        numOfRetries = viewModel.numOfRetries,
                        onLongClick = { contextMenuFor = message },
                    )
                }
            }
        }
    }

    // Context menu for messages
    contextMenuFor?.let { msg ->
        MessageContextMenu(
            message = msg,
            onDismiss = { contextMenuFor = null },
            onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("APRS message", msg.text ?: ""))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            },
            onAbort = {
                abortMessage(context, msg)
            },
            onResend = {
                resendMessage(context, msg)
            },
        )
    }

    // Start tracking dialog
    if (showStartTracking) {
        AlertDialog(
            onDismissRequest = { showStartTracking = false },
            title = { Text("Tracking is not started") },
            text = {
Text("Your message is saved, but the app is not connected. Start tracking to send it?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showStartTracking = false
                    onStartService()
                }) { Text("Start tracking") }
            },
            dismissButton = {
                TextButton(onClick = { showStartTracking = false }) {
                    Text("Not now")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    myCall: String,
    targetCall: String,
    numOfRetries: Int,
    onLongClick: () -> Unit,
) {
    val colors = org.aprsdroid.app.ui.theme.AprsTheme.colors
    val isOutgoing = message.type != MessageEntity.TYPE_INCOMING
    val failed = message.type == MessageEntity.TYPE_OUT_REJECTED ||
        message.type == MessageEntity.TYPE_OUT_ABORTED
    val bubbleColor = when {
        !isOutgoing -> MaterialTheme.colorScheme.surfaceContainerHigh
        failed -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val textColor = when {
        !isOutgoing -> MaterialTheme.colorScheme.onSurface
        failed -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    // What happened to it, in words. Colour only reinforces.
    val status = when (message.type) {
        MessageEntity.TYPE_OUT_NEW ->
            if (message.retrycnt <= 1) "Sending" else "Sending, try ${message.retrycnt} of $numOfRetries"
        MessageEntity.TYPE_OUT_ACKED -> "Delivered"
        MessageEntity.TYPE_OUT_REJECTED -> "Rejected by ${targetCall}"
        MessageEntity.TYPE_OUT_ABORTED -> "Not delivered"
        else -> ""
    }
    val statusColor = when (message.type) {
        MessageEntity.TYPE_OUT_ACKED -> colors.rx
        MessageEntity.TYPE_OUT_REJECTED, MessageEntity.TYPE_OUT_ABORTED -> colors.error
        else -> colors.muted
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
    ) {
        val shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isOutgoing) 16.dp else 4.dp,
            bottomEnd = if (isOutgoing) 4.dp else 16.dp,
        )
        Surface(
            color = bubbleColor,
            shape = shape,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(shape)
                .combinedClickable(onLongClick = onLongClick) {},
        ) {
            Text(
                text = message.text ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            if (status.isNotEmpty()) {
                Text(
                    text = "$status. ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                )
            }
            Text(
                text = StationFormat.age(message.ts),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
    }
}

/** APRS messages carry at most 67 characters. */
private const val MAX_MESSAGE_LENGTH = 67

@Composable
private fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { onTextChange(it.take(MAX_MESSAGE_LENGTH)) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                supportingText = {
                    val left = MAX_MESSAGE_LENGTH - text.length
                    if (left <= 20) Text("$left characters left")
                },
                shape = RoundedCornerShape(14.dp),
                maxLines = 3,
            )
            Spacer(modifier = Modifier.width(8.dp))
            androidx.compose.material3.FilledIconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun MessageContextMenu(
    message: MessageEntity,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onAbort: () -> Unit,
    onResend: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false; onDismiss() },
    ) {
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = { expanded = false; onDismiss(); onCopy() },
        )
        if (message.type == MessageEntity.TYPE_OUT_NEW) {
            DropdownMenuItem(
                text = { Text("Stop sending") },
                onClick = { expanded = false; onDismiss(); onAbort() },
            )
        }
        if (message.type != MessageEntity.TYPE_INCOMING) {
            DropdownMenuItem(
                text = { Text("Send again") },
                onClick = { expanded = false; onDismiss(); onResend() },
            )
        }
    }
}

// --- Message operations ---

private fun sendMessage(context: Context, targetCall: String, text: String) {
    val scope = kotlinx.coroutines.MainScope()
    scope.launch(Dispatchers.IO) {
        try {
            val db = AprsDatabase.get(context)
            val msgId = db.messageDao().createMsgId(targetCall)
            db.messageDao().insert(MessageEntity(
                ts = System.currentTimeMillis(),
                retrycnt = 0,
                call = targetCall,
                msgid = msgId.toString(),
                type = MessageEntity.TYPE_OUT_NEW,
                text = text,
            ))
            // Notify service to transmit
            val intent = Intent(AprsService.MESSAGETX)
                .putExtra(AprsService.SOURCE, PrefsWrapper(context).getCallSsid())
                .putExtra(AprsService.DEST, targetCall)
                .putExtra(AprsService.BODY, text)
            context.sendBroadcast(intent)
            // Notify UI
            context.sendBroadcast(Intent(AprsService.MESSAGE).setPackage("org.aprsdroid.app"))
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to send: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun abortMessage(context: Context, message: MessageEntity) {
    val scope = kotlinx.coroutines.MainScope()
    scope.launch(Dispatchers.IO) {
        try {
            val db = AprsDatabase.get(context)
            db.messageDao().updateMessageType(message.id, MessageEntity.TYPE_OUT_ABORTED)
            context.sendBroadcast(Intent(AprsService.MESSAGE).setPackage("org.aprsdroid.app"))
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun resendMessage(context: Context, message: MessageEntity) {
    val scope = kotlinx.coroutines.MainScope()
    scope.launch(Dispatchers.IO) {
        try {
            val db = AprsDatabase.get(context)
            db.messageDao().updateMessageRetry(message.id, 0, System.currentTimeMillis())
            db.messageDao().updateMessageType(message.id, MessageEntity.TYPE_OUT_NEW)
            // Notify service to retry
            context.sendBroadcast(Intent(AprsService.MESSAGETX).setPackage("org.aprsdroid.app"))
            context.sendBroadcast(Intent(AprsService.MESSAGE).setPackage("org.aprsdroid.app"))
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
