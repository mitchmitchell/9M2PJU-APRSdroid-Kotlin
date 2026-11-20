package org.aprsdroid.app.ui

import android.content.Intent
import android.location.Location
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import org.aprsdroid.app.StationCounts
import org.aprsdroid.app.StationFilter
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Everything drawn over the map except the buttons: the search bar, filter
 * chips and connection status at the top, and at the bottom the panel for the
 * station (or small cluster) the user tapped.
 *
 * @param clusterCalls stations of a tapped cluster to choose from; empty when none.
 * @param refreshKey changes whenever new station data may have arrived, so the panel stays current.
 * @param onFound the user picked a search result; the host moves the map there.
 */
@Composable
fun BoxScope.MapStationLayer(
    selectedCall: String,
    clusterCalls: List<String>,
    followedCall: String,
    refreshKey: Any?,
    filter: StationFilter,
    counts: StationCounts,
    onFilter: (StationFilter) -> Unit,
    showObjects: Boolean,
    onToggleObjects: () -> Unit,
    onFound: (StationEntity) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onFollow: (String) -> Unit,
    onStopFollowing: () -> Unit,
) {
    Column(
        // Leave room for the button column on the right.
        modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 72.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapSearch(onFound = onFound)
        FilterChips(filter, counts, onFilter, showObjects, onToggleObjects)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill()
            if (followedCall.isNotEmpty()) TrackingChip(call = followedCall, onStop = onStopFollowing)
        }
    }

    val panelOpen = selectedCall.isNotEmpty() || clusterCalls.isNotEmpty()
    AnimatedVisibility(
        visible = panelOpen,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
    ) {
        // Keep showing the last content while the panel slides away.
        var shownCall by remember { mutableStateOf(selectedCall) }
        var shownCluster by remember { mutableStateOf(clusterCalls) }
        if (panelOpen) {
            shownCall = selectedCall
            shownCluster = clusterCalls
        }
        if (shownCall.isEmpty() && shownCluster.isNotEmpty()) {
            ClusterPanel(calls = shownCluster, onPick = onPick, onDismiss = onDismiss)
        } else {
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
}

/** "All 212 / Mobile 14 / Fixed 198": thin a crowded map with one tap. */
@Composable
private fun FilterChips(
    filter: StationFilter,
    counts: StationCounts,
    onFilter: (StationFilter) -> Unit,
    showObjects: Boolean,
    onToggleObjects: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        FilterChip("All", counts.all, filter == StationFilter.ALL) { onFilter(StationFilter.ALL) }
        FilterChip("Mobile", counts.mobile, filter == StationFilter.MOBILE) { onFilter(StationFilter.MOBILE) }
        FilterChip("Fixed", counts.fixed, filter == StationFilter.FIXED) { onFilter(StationFilter.FIXED) }
        ObjectsChip(showObjects, onToggleObjects)
    }
}

@Composable
private fun FilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                count.toString(),
                style = CallsignStyle.copy(fontSize = 13.sp),
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * On/off switch for APRS objects: things other stations place on the map
 * (repeaters, nets, events, hazards) as opposed to stations reporting themselves.
 */
@Composable
private fun ObjectsChip(shown: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (shown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 3.dp,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .toggleable(value = shown, role = Role.Switch, onValueChange = { onToggle() }),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                if (shown) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (shown) "Objects shown" else "Objects hidden",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Whether data is flowing, and over what: "APRS-IS, live" or "Not connected". */
@Composable
private fun StatusPill() {
    val context = LocalContext.current
    val running = rememberServiceRunning()
    val link = remember(running) {
        when (PrefsWrapper(context).getProto()) {
            "afsk" -> "Audio modem"
            "kiss", "tnc2" -> "TNC"
            "kenwood" -> "Kenwood"
            else -> "APRS-IS"
        }
    }
    val colors = AprsTheme.colors
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp),
        ) {
            Box(Modifier.size(10.dp).background(if (running) colors.rx else colors.muted, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) "$link, live" else "Not connected",
                style = MaterialTheme.typography.labelLarge,
                color = if (running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MapSearch(onFound: (StationEntity) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }

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
    fun pick(sta: StationEntity) {
        query = ""
        focusManager.clearFocus()
        onFound(sta)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 440.dp),
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
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; focusManager.clearFocus() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { pick(it) } }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
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
                StationRow(sta.call, sta.symbol, StationFormat.age(sta.ts)) { pick(sta) }
            }
        }
    }
}

@Composable
private fun StationRow(call: String, symbol: String?, trailing: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SymbolIcon(symbol, 28.dp)
        Spacer(Modifier.width(12.dp))
        Text(call, style = CallsignStyle, color = AprsTheme.colors.callsign, modifier = Modifier.weight(1f))
        Text(
            trailing,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The stations of a small cluster (or several at one spot), to pick one. */
@Composable
private fun ClusterPanel(calls: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val stations by produceState(emptyList<StationEntity>(), calls) {
        value = withContext(Dispatchers.IO) {
            val dao = AprsDatabase.get(context).stationDao()
            calls.mapNotNull { dao.getStaPosition(it) }.sortedByDescending { it.ts }
        }
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp),
            ) {
                Text(
                    "${calls.size} stations here",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                for (sta in stations) {
                    HorizontalDivider(color = AprsTheme.colors.line.copy(alpha = 0.45f))
                    StationRow(sta.call, sta.symbol, StationFormat.age(sta.ts)) { onPick(sta.call) }
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
