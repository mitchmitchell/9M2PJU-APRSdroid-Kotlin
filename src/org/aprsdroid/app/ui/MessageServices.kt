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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import org.aprsdroid.app.PrefsWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
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
 * Compose a message to a service by filling in a form. The service's
 * callsign and its command words are filled in by the app; the user only
 * supplies their own details (an address, a phone number, the text).
 *
 * The first thing a service does is selected to begin with, so the form is
 * ready to type into; the other things it can do are one tap away. Details
 * such as a phone number are remembered for next time.
 *
 * @param onSend called with the service callsign and the finished message text
 */
@Composable
fun ServiceDialog(
    service: AprsServices.Service,
    onDismiss: () -> Unit,
    onSend: (callsign: String, text: String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { PrefsWrapper(context).prefs }
    var index by remember(service) { mutableIntStateOf(0) }
    val action = service.actions[index]
    val values = remember(service) { mutableStateMapOf<String, String>() }
    var helpOpen by remember { mutableStateOf(false) }

    fun memoryKey(f: AprsServices.Field) = "service.${service.callsign}.${f.key}"
    // Fill in what was used last time, for fields the user has not touched yet.
    for (f in action.fields) {
        if (f.remembered && f.key !in values) {
            prefs.getString(memoryKey(f), "")?.takeIf { it.isNotEmpty() }?.let { values[f.key] = it }
        }
    }

    val problem = action.problem(values)
    val preview = action.build(values)
    val left = AprsServices.MAX_LENGTH - preview.length

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(service.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // What do you want to do?
                if (service.actions.size > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                    ) {
                        service.actions.forEachIndexed { i, a ->
                            FilterChip(
                                selected = i == index,
                                onClick = { index = i },
                                label = { Text(a.label) },
                            )
                        }
                    }
                }
                Text(action.explain, style = MaterialTheme.typography.bodyMedium)

                // The address is always filled in.
                OutlinedTextField(
                    value = service.callsign,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Send to") },
                    textStyle = CallsignStyle,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                for (f in action.fields) {
                    val isMessage = f.key == "msg"
                    OutlinedTextField(
                        value = values[f.key].orEmpty(),
                        onValueChange = { values[f.key] = if (f.upper) it.uppercase() else it },
                        label = { Text(f.label) },
                        placeholder = { if (f.hint.isNotEmpty()) Text(f.hint) },
                        singleLine = !isMessage,
                        minLines = if (isMessage) 2 else 1,
                        supportingText = if (isMessage) {
                            {
                                Text(
                                    if (left >= 0) "$left characters left" else "Too long by ${-left} characters",
                                    color = if (left >= 0) MaterialTheme.colorScheme.onSurfaceVariant
                                    else AprsTheme.colors.error,
                                )
                            }
                        } else null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when {
                                f.digitsOnly -> KeyboardType.Phone
                                f.key == "to" -> KeyboardType.Email
                                else -> KeyboardType.Text
                            },
                            capitalization = if (f.upper) KeyboardCapitalization.Characters
                            else if (isMessage) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
                // Exactly what goes on the air: nothing hidden, and the syntax can be picked up along the way.
                if (preview.isNotEmpty()) {
                    Text(
                        "The app will send:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(preview, style = PacketStyle)
                }
                if (problem != null && action.fields.none { it.key == "msg" } && left < 0) {
                    Text(problem, style = MaterialTheme.typography.labelMedium, color = AprsTheme.colors.error)
                }

                // Help drawer: what the service is, what to know first, and everything it can do.
                HorizontalDivider(
                    color = AprsTheme.colors.line.copy(alpha = 0.45f),
                    modifier = Modifier.padding(top = 14.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = helpOpen, role = Role.Button, onValueChange = { helpOpen = it })
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        "How ${service.title} works",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (helpOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (helpOpen) "Hide help" else "Show help",
                    )
                }
                AnimatedVisibility(visible = helpOpen) {
                    Column {
                        Text(
                            service.summary + " It is an automated station: you message " +
                                "${service.callsign} and it messages you back.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        service.note?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        }
                        Text(
                            "What you can ask it",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        for (a in service.actions) {
                            Row(Modifier.padding(top = 6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        a.explain,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                // The command as typed by hand, with field names where your details go.
                                Text(
                                    a.example(),
                                    style = PacketStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val ed = prefs.edit()
                    for (f in action.fields) {
                        if (f.remembered) ed.putString(memoryKey(f), values[f.key].orEmpty().trim())
                    }
                    ed.apply()
                    onSend(service.callsign, preview)
                },
                enabled = problem == null,
            ) { Text("Send", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
