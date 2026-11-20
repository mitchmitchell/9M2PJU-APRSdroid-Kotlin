package org.aprsdroid.app.ui

import android.content.Intent
import android.location.Location
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.aprsdroid.app.AprsComment
import org.aprsdroid.app.MapData
import org.aprsdroid.app.MessageActivity
import org.aprsdroid.app.PrefsWrapper
import org.aprsdroid.app.StationActivity
import org.aprsdroid.app.data.AprsDatabase
import org.aprsdroid.app.data.StationEntity
import org.aprsdroid.app.ui.theme.AprsTheme
import org.aprsdroid.app.ui.theme.CallsignStyle

// Shared by both map screens (Mapsforge and Google Maps SDK): callsign search
// at the top of the map, and a panel that slides up from the bottom for the
// station you tapped or found. The map stays visible behind both.

private class PanelData(val station: StationEntity, val distance: String?)

/**
 * Callsign search and the selected-station panel, drawn over the map.
 *
 * @param refreshKey changes whenever new station data may have arrived, so the panel stays current.
 * @param onFound the user picked a search result; the host moves the map there.
 */
@Composable
fun BoxScope.MapStationLayer(
    selectedCall: String,
    followedCall: String,
    searchOpen: Boolean,
    refreshKey: Any?,
    onSearchClose: () -> Unit,
    onFound: (StationEntity) -> Unit,
    onDismiss: () -> Unit,
    onFollow: (String) -> Unit,
    onStopFollowing: () -> Unit,
) {
    AnimatedVisibility(
        visible = searchOpen,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        // Leave room for the control stack on the right.
        modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 76.dp),
    ) {
        MapSearch(onClose = onSearchClose, onFound = onFound)
    }

    AnimatedVisibility(
        visible = selectedCall.isNotEmpty(),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
    ) {
        // Keep showing the last station while the panel slides away.
        var shownCall by remember { mutableStateOf(selectedCall) }
        if (selectedCall.isNotEmpty()) shownCall = selectedCall
        StationPanel(
            call = shownCall,
            followed = shownCall.isNotEmpty() && shownCall == followedCall,
            refreshKey = refreshKey,
            onDismiss = onDismiss,
            onFollow = { onFollow(shownCall) },
            onStopFollowing = onStopFollowing,
        )
    }
}

@Composable
private fun MapSearch(onClose: () -> Unit, onFound: (StationEntity) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val results by produceState(emptyList<StationEntity>(), query) {
        val q = query.filter { it.isLetterOrDigit() || it == '-' }
        if (q.length < 2) {
            value = emptyList()
        } else {
            delay(150) // let typing settle
            value = withContext(Dispatchers.IO) {
                AprsDatabase.get(context).stationDao().searchByCall("%$q%", 6)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        Column {
            TextField(
                value = query,
                onValueChange = { query = it.uppercase().take(12) },
                placeholder = { Text("Find a callsign") },
                singleLine = true,
                textStyle = CallsignStyle,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close search")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let(onFound) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (query.length >= 2 && results.isEmpty()) {
                HorizontalDivider(color = AprsTheme.colors.line.copy(alpha = 0.45f))
                Text(
                    "No station heard with \"$query\" in its callsign.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (sta in results) {
                HorizontalDivider(color = AprsTheme.colors.line.copy(alpha = 0.45f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFound(sta) }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SymbolIcon(sta.symbol, 28.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        sta.call,
                        style = CallsignStyle,
                        color = AprsTheme.colors.callsign,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        StationFormat.age(sta.ts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StationPanel(
    call: String,
    followed: Boolean,
    refreshKey: Any?,
    onDismiss: () -> Unit,
    onFollow: () -> Unit,
    onStopFollowing: () -> Unit,
) {
    val context = LocalContext.current
    val data by produceState<PanelData?>(null, call, refreshKey) {
        value = withContext(Dispatchers.IO) {
            val prefs = PrefsWrapper(context)
            val dao = AprsDatabase.get(context).stationDao()
            val sta = dao.getStaPosition(call) ?: return@withContext null
            val myCall = prefs.getCallSsid()
            val me = if (call.equals(myCall, ignoreCase = true)) null
            else dao.getStaPosition(myCall)?.let { it.lat / 1e6 to it.lon / 1e6 }
                ?: MapData.lastKnownLocation(context)?.let { it.lat.toDouble() to it.lon.toDouble() }
            val distance = me?.let {
                val res = FloatArray(2)
                Location.distanceBetween(it.first, it.second, sta.lat / 1e6, sta.lon / 1e6, res)
                StationFormat.distance(res[0], res[1], prefs.isMetric())
            }
            PanelData(sta, distance)
        }
    }
    val metric = remember { PrefsWrapper(context).isMetric() }
    val sta = data?.station

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SymbolIcon(sta?.symbol, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        call,
                        style = CallsignStyle.copy(fontSize = 22.sp, lineHeight = 28.sp),
                        color = AprsTheme.colors.callsign,
                    )
                    if (sta != null) {
                        val speed = sta.speed
                        val facts = listOfNotNull(
                            data?.distance,
                            StationFormat.age(sta.ts),
                            if (speed != null && speed > 0) StationFormat.speed(speed, metric) else null,
                        ).joinToString("  •  ")
                        Text(
                            facts,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            val comment = remember(sta?.comment) { AprsComment.clean(sta?.comment) }
            if (comment.isNotEmpty()) {
                Text(
                    comment,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, end = 12.dp),
            ) {
                val shape = RoundedCornerShape(10.dp)
                Button(
                    onClick = if (followed) onStopFollowing else onFollow,
                    shape = shape,
                    modifier = Modifier.weight(1.2f).heightIn(min = 48.dp),
                ) {
                    Text(if (followed) "Stop following" else "Follow", fontWeight = FontWeight.Bold, maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(context, MessageActivity::class.java).putExtra("call", call))
                    },
                    shape = shape,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Message", maxLines = 1) }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(context, StationActivity::class.java).putExtra("call", call))
                    },
                    shape = shape,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Details", maxLines = 1) }
            }
        }
    }
}
