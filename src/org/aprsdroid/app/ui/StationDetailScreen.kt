package org.aprsdroid.app.ui

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.aprsdroid.app.AprsComment
import org.aprsdroid.app.MapModes
import org.aprsdroid.app.MessageActivity
import org.aprsdroid.app.PrefsWrapper
import org.aprsdroid.app.data.StationEntity
import org.aprsdroid.app.ui.theme.AprsTheme
import org.aprsdroid.app.ui.theme.CallsignStyle

/**
 * Station details: who it is, where it is relative to you, what it last
 * said, and what you can do about it (follow it on the map, message it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationDetailScreen(
    viewModel: StationDetailViewModel,
    onSsidClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val station by viewModel.station.collectAsState()
    val ssids by viewModel.ssids.collectAsState()
    val posts by viewModel.posts.collectAsState()
    val myPosition by viewModel.myPosition.collectAsState()
    val context = LocalContext.current
    val call = viewModel.targetCall
    val isOwn = call.equals(viewModel.myCall, ignoreCase = true)

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Station") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Header(call = call, station = station, isOwn = isOwn)
                station?.let { sta ->
                    Facts(sta, myPosition, isOwn, viewModel.isMetric)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { MapModes.startMap(context, PrefsWrapper(context), call) },
                        enabled = station != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Follow on map", fontWeight = FontWeight.Bold, maxLines = 1) }
                    if (!isOwn) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(context, MessageActivity::class.java).putExtra("call", call),
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Message", maxLines = 1) }
                    }
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = {
                        openUrl("https://aprs.fi/info/a/$call?utm_source=aprsdroid&utm_medium=inapp&utm_campaign=aprsfi")
                    }) { Text("Look up on aprs.fi") }
                    TextButton(onClick = {
                        openUrl("https://qrz.com/db/${call.split("-", " ")[0]}")
                    }) { Text("Look up on QRZ") }
                }
            }

            val others = ssids.filter { !it.call.equals(call, ignoreCase = true) }
            if (others.isNotEmpty()) {
                item {
                    SectionTitle("Same operator")
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (other in others) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.clickable { onSsidClick(other.call) },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    SymbolIcon(other.symbol, 22.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        other.call,
                                        style = CallsignStyle.copy(fontSize = 15.sp),
                                        color = AprsTheme.colors.callsign,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Recent packets") }
            if (posts.isEmpty()) {
                item {
                    Text(
                        "No packets from this station are in the log.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(posts, key = { it.id }) { post ->
                    PostItem(post = post, onLongClick = {})
                    HorizontalDivider(color = AprsTheme.colors.line.copy(alpha = 0.45f))
                }
            }
        }
    }
}

@Composable
private fun Header(call: String, station: StationEntity?, isOwn: Boolean) {
    val colors = AprsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolIcon(station?.symbol, 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = call,
                style = CallsignStyle.copy(fontSize = 28.sp, lineHeight = 34.sp),
                color = if (isOwn) colors.tx else colors.callsign,
            )
            when {
                station == null -> Text(
                    "Not heard yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val text = remember(station.comment) { AprsComment.clean(station.comment) }
                    if (text.isNotEmpty()) {
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                    val sub = listOfNotNull(
                        if (isOwn) "This is you" else null,
                        station.origin?.let { "Object placed by $it" },
                    ).joinToString(". ")
                    if (sub.isNotEmpty()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Label/value pairs, two to a row. Only facts we actually have are shown. */
@Composable
private fun Facts(sta: StationEntity, myPosition: Pair<Int, Int>?, isOwn: Boolean, metric: Boolean) {
    val facts = ArrayList<Pair<String, String>>()
    if (!isOwn && myPosition != null) {
        val res = FloatArray(2)
        Location.distanceBetween(
            myPosition.first / 1e6, myPosition.second / 1e6, sta.lat / 1e6, sta.lon / 1e6, res,
        )
        facts += "Distance" to StationFormat.distance(res[0], res[1], metric)
    }
    facts += "Last heard" to StationFormat.age(sta.ts)
    val alt = sta.alt ?: AprsComment.parse(sta.comment).altitudeM?.let { Math.round(it).toInt() }
    if (alt != null) facts += "Altitude" to StationFormat.altitude(alt, metric)
    val speed = sta.speed
    if (speed != null && speed > 0) {
        facts += "Speed" to StationFormat.speed(speed, metric)
        sta.course?.let { facts += "Heading" to "$it° ${StationFormat.compass(it.toFloat())}" }
    }
    sta.qrg?.takeIf { it.isNotBlank() }?.let { facts += "Frequency" to it }
    facts += "Position" to StationFormat.position(sta.lat, sta.lon)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            for (pair in facts.chunked(2)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    for ((label, value) in pair) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(value, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Column {
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
