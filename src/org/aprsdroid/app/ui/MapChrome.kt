package org.aprsdroid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.aprsdroid.app.ui.theme.AprsTheme
import org.aprsdroid.app.ui.theme.CallsignStyle

// The map screens are map-first: the map fills the screen and the few
// controls float on top of it, so nothing but the tab bar takes map space.

/**
 * The map's buttons: separate round buttons down the right edge, each a
 * comfortable thumb target. Put [MapButton]s (or menus wrapped in one) inside.
 */
@Composable
fun MapButtonColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** One round floating map button. [content] is usually an IconButton or a menu that brings its own. */
@Composable
fun MapButton(content: @Composable () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp),
        ) { content() }
    }
}

/** Floating label for the station being followed, with a button to stop following. */
@Composable
fun TrackingChip(call: String, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
            Column {
                Text(
                    "Following",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(call, style = CallsignStyle, color = AprsTheme.colors.callsign)
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = "Stop following $call")
            }
        }
    }
}

/** Crosshair for picking a position: dark lines with a light halo, readable on any map. */
@Composable
fun MapCrosshair(modifier: Modifier = Modifier) {
    Canvas(modifier.size(56.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val gap = size.width * 0.12f
        fun arms(color: Color, w: Float) {
            drawLine(color, Offset(0f, c.y), Offset(c.x - gap, c.y), w)
            drawLine(color, Offset(c.x + gap, c.y), Offset(size.width, c.y), w)
            drawLine(color, Offset(c.x, 0f), Offset(c.x, c.y - gap), w)
            drawLine(color, Offset(c.x, c.y + gap), Offset(c.x, size.height), w)
        }
        arms(Color.White, 7f)
        arms(Color(0xFF0D182D), 3f)
        drawCircle(Color.White, 5f, c)
        drawCircle(Color(0xFFD32F2F), 3f, c)
    }
}
