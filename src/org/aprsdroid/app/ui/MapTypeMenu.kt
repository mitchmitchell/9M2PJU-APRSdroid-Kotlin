package org.aprsdroid.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.aprsdroid.app.MapMode
import org.aprsdroid.app.MapModes
import org.aprsdroid.app.R

/**
 * Top-bar button that lists every map type (OSM, Google tiles, Google
 * Maps SDK). Unavailable types are shown disabled with the reason.
 */
@Composable
fun MapTypeMenu(
    currentTag: String,
    onSelect: (MapMode) -> Unit,
) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.Layers, contentDescription = ctx.getString(R.string.map_type))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        MapModes.initialize(ctx)
        for (mode in MapModes.all) {
            val available = mode.isAvailable(ctx)
            DropdownMenuItem(
                enabled = available,
                leadingIcon = {
                    RadioButton(selected = mode.tag == currentTag, onClick = null, enabled = available)
                },
                text = {
                    Column {
                        Text(ctx.getString(mode.titleRes))
                        if (!available) {
                            mode.unavailableReason(ctx)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(mode)
                },
            )
        }
    }
}
