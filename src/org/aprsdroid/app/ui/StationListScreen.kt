package org.aprsdroid.app.ui

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.aprsdroid.app.AprsSymbols
import org.aprsdroid.app.MapData
import org.aprsdroid.app.NavTarget
import org.aprsdroid.app.PrefsWrapper
import org.aprsdroid.app.StationActivity
import org.aprsdroid.app.data.StationEntity
import org.aprsdroid.app.ui.theme.AprsTheme
import org.aprsdroid.app.ui.theme.CallsignStyle

/** One row of the station list, with everything computed for display. */
private data class StationRow(
    val station: StationEntity,
    val isOwn: Boolean,
    /** Metres from us, or null if our position is unknown. */
    val distanceM: Float?,
    val bearingDeg: Float?,
)

/**
 * The station list ("Stations" tab): who is around, nearest first.
 *
 * Your own station is pinned to the top. Tap a station for its details,
 * long-press for the action menu (message, track on map, ...).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationListScreen(
    viewModel: StationListViewModel,
    onNavigate: (NavTarget) -> Unit = {},
    onPreferences: () -> Unit = {},
    service: ServiceController? = null,
) {
    val stations by viewModel.stations.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var contextMenuCall by remember { mutableStateOf<String?>(null) }
    var ageTick by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val myCall = remember { PrefsWrapper(context).getCallSsid() }

    // Our position: our own latest beacon if we have one, else the phone's last fix.
    val fallbackPos = remember { MapData.lastKnownLocation(context) }
    val rows = remember(stations, searchQuery, ageTick) {
        val own = stations.firstOrNull { it.call.equals(myCall, ignoreCase = true) }
        val myLat = own?.let { it.lat / 1e6 } ?: fallbackPos?.lat
        val myLon = own?.let { it.lon / 1e6 } ?: fallbackPos?.lon
        val showAfter = System.currentTimeMillis() - PrefsWrapper(context).getShowAge()
        stations.asSequence()
            .filter { it.ts > showAfter || it.call.equals(myCall, ignoreCase = true) }
            .filter { searchQuery.isBlank() || it.call.contains(searchQuery, ignoreCase = true) }
            .map { sta ->
                val res = FloatArray(2)
                val known = myLat != null && myLon != null
                if (known) Location.distanceBetween(myLat!!, myLon!!, sta.lat / 1e6, sta.lon / 1e6, res)
                StationRow(
                    station = sta,
                    isOwn = sta.call.equals(myCall, ignoreCase = true),
                    distanceM = if (known) res[0] else null,
                    bearingDeg = if (known) res[1] else null,
                )
            }
            .sortedWith(
                compareByDescending<StationRow> { it.isOwn }
                    .thenBy { it.distanceM ?: Float.MAX_VALUE }
                    .thenByDescending { it.station.ts },
            )
            .toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it.uppercase() },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            placeholder = { Text("Callsign") },
                            singleLine = true,
                            textStyle = CallsignStyle,
                        )
                    } else {
                        Column {
                            Text("Stations")
                            Text(
                                text = when {
                                    stations.isEmpty() -> "None heard yet"
                                    else -> "${rows.count { !it.isOwn }} heard nearby"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) searchQuery = ""
                    }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "Close search" else "Search callsigns",
                        )
                    }
                    AprsOverflowMenu(
                        showAgeFilter = true,
                        onPreferences = onPreferences,
                        onAgeChanged = { ageTick++ },
                    )
                },
            )
        },
        bottomBar = {
            AprsBottomBar(
                current = NavTarget.HUB,
                onNavigate = onNavigate,
                onPreferences = onPreferences,
                service = service,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                stations.isEmpty() -> EmptyState(
                    title = "No stations heard yet",
                    body = "Start tracking, and stations will appear here as their packets arrive.",
                )
                rows.isEmpty() -> EmptyState(
                    title = if (searchQuery.isBlank()) "No recent stations" else "No callsign contains \"$searchQuery\"",
                    body = if (searchQuery.isBlank()) "Everything heard is older than the \"Show last\" filter in the menu."
                    else "Check the spelling, or close search to see everyone.",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.station.call }) { row ->
                        StationItem(
                            row = row,
                            metric = viewModel.isMetric,
                            onClick = {
                                context.startActivity(
                                    Intent(context, StationActivity::class.java)
                                        .setData(Uri.parse(row.station.call)),
                                )
                            },
                            onLongClick = { contextMenuCall = row.station.call },
                        )
                        HorizontalDivider(color = AprsTheme.colors.line.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }

    contextMenuCall?.let { call ->
        CallsignContextMenu(call = call, expanded = true, onDismiss = { contextMenuCall = null })
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** An APRS symbol from the sprite sheet (with its overlay character, if any). */
@Composable
fun SymbolIcon(symbol: String?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val sym = AprsSymbols.validate(symbol)
    val bitmap = remember(sym, px) { AprsSymbols.bitmap(context, sym, px).asImageBitmap() }
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationItem(
    row: StationRow,
    metric: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val sta = row.station
    val colors = AprsTheme.colors
    val context = LocalContext.current
    // Stations fade as they go quiet: full strength when fresh, dimmer after half an hour.
    val ageMin = (System.currentTimeMillis() - sta.ts) / 60000f
    val freshness = if (row.isOwn) 1f else (1f - (ageMin / 30f).coerceIn(0f, 1f) * 0.45f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (row.isOwn) colors.ownRow else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Own station: a green edge, like a marker on a chart.
        Box(
            Modifier.width(4.dp).fillMaxHeight()
                .background(if (row.isOwn) colors.tx else androidx.compose.ui.graphics.Color.Transparent),
        )
        Row(
            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 14.dp, top = 9.dp, bottom = 9.dp).alpha(freshness),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymbolIcon(sta.symbol, 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = sta.call,
                    style = CallsignStyle,
                    color = if (row.isOwn) colors.tx else colors.callsign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The readable part of the comment only: course, altitude, antenna
                // and telemetry codes are data, shown elsewhere.
                val detail = remember(sta.comment, metric) {
                    // Weather stations: what they report, not the coded string.
                    org.aprsdroid.app.AprsWeather.parse(sta.comment, sta.course, sta.speed)
                        ?.let { org.aprsdroid.app.WeatherFormat.summary(it, metric) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: org.aprsdroid.app.AprsComment.clean(sta.comment)
                }
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = when {
                        row.isOwn -> "You"
                        row.distanceM != null && row.bearingDeg != null ->
                            StationFormat.distance(row.distanceM, row.bearingDeg, metric)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = StationFormat.age(sta.ts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
