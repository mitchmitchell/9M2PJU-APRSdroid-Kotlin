package org.aprsdroid.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aprsdroid.app.AprsServices
import org.aprsdroid.app.ui.theme.AprsTheme
import org.aprsdroid.app.ui.theme.CallsignStyle
import org.aprsdroid.app.ui.theme.PacketStyle

// APRS services made usable: a row of cards on the Messages screen, a sheet
// listing what each service can do, and a form for commands that need
// details. The app writes the command; the user never has to know the syntax.

/** The "Things you can do with messages" row at the top of the Messages screen. */
@Composable
fun ServiceCards(onOpen: (AprsServices.Service) -> Unit) {
    Column {
        Text(
            "Services",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Text(
            "Automated stations that answer APRS messages. Pick one and the app writes the command for you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            for (service in AprsServices.ALL) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .width(190.dp)
                        .height(112.dp)
                        .clickable(onClickLabel = "Open ${service.title}") { onOpen(service) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(service.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            service.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(top = 2.dp),
                        )
                        Text(
                            service.callsign,
                            style = CallsignStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                            color = AprsTheme.colors.callsign,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a service can do. Commands with nothing to fill in are sent at once;
 * the others open a form. [onSent] is called with the service callsign after
 * a message has been queued, so the host can open that conversation.
 */
@Composable
fun ServiceDialog(
    service: AprsServices.Service,
    onDismiss: () -> Unit,
    onSend: (callsign: String, text: String) -> Unit,
) {
    var action by remember(service) { mutableStateOf<AprsServices.Action?>(null) }
    val current = action
    if (current != null) {
        ActionForm(service, current, onBack = { action = null }, onSend = { onSend(service.callsign, it) })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(service.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(service.summary, style = MaterialTheme.typography.bodyMedium)
                service.note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                for (a in service.actions) {
                    HorizontalDivider(color = AprsTheme.colors.line.copy(alpha = 0.45f))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (a.fields.isEmpty()) onSend(service.callsign, a.build(emptyMap())) else action = a
                            }
                            .heightIn(min = 56.dp)
                            .padding(vertical = 8.dp),
                    ) {
                        Text(a.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            a.explain,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ActionForm(
    service: AprsServices.Service,
    action: AprsServices.Action,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    val values = remember(action) { mutableStateMapOf<String, String>() }
    val problem = action.problem(values)
    val preview = action.build(values)
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(action.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(action.explain, style = MaterialTheme.typography.bodyMedium)
                for (f in action.fields) {
                    OutlinedTextField(
                        value = values[f.key].orEmpty(),
                        onValueChange = { values[f.key] = if (f.upper) it.uppercase() else it },
                        label = { Text(f.label) },
                        placeholder = { if (f.hint.isNotEmpty()) Text(f.hint) },
                        singleLine = f.key != "msg",
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when {
                                f.digitsOnly -> KeyboardType.Phone
                                f.key == "to" -> KeyboardType.Email
                                else -> KeyboardType.Text
                            },
                            capitalization = if (f.upper) KeyboardCapitalization.Characters
                            else if (f.key == "msg") KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
                // Show exactly what goes on the air, so nothing is hidden and the syntax can be learned.
                Text(
                    "Sends to ${service.callsign}:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(preview.ifEmpty { " " }, style = PacketStyle)
                val left = AprsServices.MAX_LENGTH - preview.length
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (left >= 0) "$left characters left" else problem.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (left >= 0) MaterialTheme.colorScheme.onSurfaceVariant else AprsTheme.colors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(preview) }, enabled = problem == null) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("Back") } },
    )
}
