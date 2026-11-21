package org.aprsdroid.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** An area of the map kept on the phone. */
data class SavedArea(val id: Long, val name: String)

/** Save the area on screen for use with no connection, and manage the saved ones. */
@Composable
fun OfflineMapsDialog(
    areas: List<SavedArea>,
    /** 0..1 while an area is being saved, else null. */
    progress: Float?,
    message: String,
    onSave: () -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline maps") },
        text = {
            Column {
                Text(
                    "Save the area on screen, from this zoom inwards, so the map works with no connection.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSave, enabled = progress == null, modifier = Modifier.fillMaxWidth()) {
                    Text("Save this area")
                }
                if (progress != null) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                if (message.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (areas.isEmpty()) "No saved areas yet" else "Saved areas",
                    style = MaterialTheme.typography.titleSmall,
                )
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    for (a in areas) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { onDelete(a.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${a.name}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
