package org.aprsdroid.app.ui

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.aprsdroid.app.AprsComment
import org.aprsdroid.app.AprsPath
import org.aprsdroid.app.AprsService
import org.aprsdroid.app.AprsWeather
import org.aprsdroid.app.MapModes
import org.aprsdroid.app.PrefsWrapper
import org.aprsdroid.app.StationHistory
import org.aprsdroid.app.TrackStats
import org.aprsdroid.app.WeatherFormat
import org.aprsdroid.app.data.PostEntity
import org.aprsdroid.app.data.StationEntity
import org.aprsdroid.app.ui.theme.AprsTheme
import org.aprsdroid.app.ui.theme.CallsignStyle

private const val LIVE_WINDOW_MS = 30 * 60 * 1000L
private val CONTENT_MAX = 920.dp

/**
 * Everything about one station, in tabs: an overview, where it has been,
 * its weather over time, your conversation with it, and its raw packets.
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
    val history by viewModel.history.collectAsState()
    val historyError by viewModel.historyError.collectAsState()
    val context = LocalContext.current
    val call = viewModel.targetCall
    val isOwn = call.equals(viewModel.myCall, ignoreCase = true)
    val metric = viewModel.isMetric

    // Current weather: the stored comment first, else the newest report in the log.
    val weather = remember(station, history) {
        station?.let { AprsWeather.parse(it.comment, it.course, it.speed) }
            ?.takeIf { station?.symbol?.getOrNull(1) == '_' || it.tempF != null }
            ?: history.weather.lastOrNull()?.weather
    }

    val tabs = remember(isOwn) {
        listOfNotNull("Overview", "Track", "Weather", if (isOwn) null else "Messages", "Raw")
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }

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
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = CONTENT_MAX)) {
                Header(call, station, isOwn, weather != null && station?.symbol?.getOrNull(1) == '_')
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val shape = RoundedCornerShape(10.dp)
                    Button(
                        onClick = { MapModes.startMap(context, PrefsWrapper(context), call) },
                        enabled = station != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = shape,
                    ) { Text("Follow on map", fontWeight = FontWeight.Bold, maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            station?.let { s ->
                                val lat = s.lat / 1e6
                                val lon = s.lon / 1e6
                                openUrl("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(call)})")
                            }
                        },
                        enabled = station != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = shape,
                    ) { Text("Directions", maxLines = 1) }
                }
                ScrollableTabRow(
                    selectedTabIndex = tab.coerceAtMost(tabs.lastIndex),
                    edgePadding = 16.dp,
                    containerColor = Color.Transparent,
                    divider = { HorizontalDivider(color = AprsTheme.colors.line.copy(alpha = 0.6f)) },
                ) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
            }
            Box(Modifier.widthIn(max = CONTENT_MAX).fillMaxSize()) {
                when (tabs.getOrElse(tab) { "Overview" }) {
                    "Overview" -> OverviewTab(
                        viewModel, station, weather, posts, myPosition, ssids.filter { !it.call.equals(call, true) },
                        isOwn, metric, onSsidClick, ::openUrl,
                    )
                    "Track" -> TrackTab(call, history, historyError, metric, ::openUrl)
                    "Weather" -> WeatherTab(call, history, historyError, metric, ::openUrl)
                    "Messages" -> {
                        val app = context.applicationContext as android.app.Application
                        val messages: MessageListViewModel =
                            viewModel(key = "messages-$call", factory = MessageListViewModel.Factory(app, call))
                        MessageListScreen(
                            viewModel = messages,
                            myCall = viewModel.myCall,
                            onBack = {},
                            onStartService = {
                                context.startService(AprsService.intent(context, AprsService.SERVICE))
                            },
                            embedded = true,
                        )
                    }
                    else -> RawTab(posts)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(call: String, station: StationEntity?, isOwn: Boolean, isWeather: Boolean) {
    val colors = AprsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
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
            if (station == null) {
                Text(
                    "Not heard yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val live = System.currentTimeMillis() - station.ts < LIVE_WINDOW_MS
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            if (isOwn) "You" else if (isWeather) "Weather station"
                            else StationFormat.kind(station.symbol, station.origin != null),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(9.dp).background(if (live) colors.rx else colors.muted, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        (if (live) "Live, " else "Last heard ") + StationFormat.age(station.ts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                station.origin?.let {
                    Text(
                        "Object placed by $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) { Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() } }
}

/** A label over a value. Tiles flow into as many columns as fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tiles(tiles: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((label, value) in tiles) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.widthIn(min = 150.dp).weight(1f),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Says where the history came from, and why the history server was not used if it failed. */
@Composable
private fun HistorySource(history: StationHistory, error: String?) {
    val text = when {
        error != null -> "From what this device has heard. The history server could not be used: $error"
        history.source == "this device" -> "From what this device has heard."
        else -> "From your history server, ${history.source}."
    }
    Note(text)
}

// ---------------------------------------------------------------- overview

@Composable
private fun OverviewTab(
    viewModel: StationDetailViewModel,
    station: StationEntity?,
    weather: AprsWeather?,
    posts: List<PostEntity>,
    myPosition: Pair<Int, Int>?,
    others: List<StationEntity>,
    isOwn: Boolean,
    metric: Boolean,
    onSsidClick: (String) -> Unit,
    openUrl: (String) -> Unit,
) {
    val call = viewModel.targetCall
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
        if (station == null) {
            Note("Nothing has been heard from $call yet. Its details appear here once a packet arrives.")
        } else {
            if (weather != null) WeatherCard(weather, station.ts, metric)

            // facts
            val facts = ArrayList<Pair<String, String>>()
            if (!isOwn && myPosition != null) {
                val res = FloatArray(2)
                Location.distanceBetween(
                    myPosition.first / 1e6, myPosition.second / 1e6, station.lat / 1e6, station.lon / 1e6, res,
                )
                facts += "Distance from you" to StationFormat.distance(res[0], res[1], metric)
            }
            val alt = station.alt ?: AprsComment.parse(station.comment).altitudeM?.let { Math.round(it).toInt() }
            if (alt != null) facts += "Altitude" to StationFormat.altitude(alt, metric)
            val speed = station.speed
            if (weather == null && speed != null && speed > 0) {
                facts += "Speed" to StationFormat.speed(speed, metric)
                station.course?.let { facts += "Heading" to "$it° ${StationFormat.compass(it.toFloat())}" }
            }
            station.qrg?.takeIf { it.isNotBlank() }?.let { facts += "Frequency" to it }
            facts += "Position" to StationFormat.position(station.lat, station.lon)
            Tiles(facts, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

            // what it says about itself
            val text = remember(station.comment, weather) {
                if (weather != null) AprsWeather.textAfter(station.comment) else AprsComment.clean(station.comment)
            }
            if (text.isNotEmpty()) {
                Card { Text(text, style = MaterialTheme.typography.bodyLarge) }
            }

            // how the last packet got here
            val route = remember(posts) {
                posts.firstOrNull { it.message?.startsWith("$call>") == true }?.let { AprsPath.parse(it.message) }
            }
            if (route != null) {
                SectionTitle("How it reached you")
                Card { Text(route.describe(), style = MaterialTheme.typography.bodyLarge) }
            }
        }

        QrzSection(viewModel, openUrl)

        if (others.isNotEmpty()) {
            SectionTitle("Same operator")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
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
                            Column {
                                Text(
                                    other.call,
                                    style = CallsignStyle.copy(fontSize = 15.sp),
                                    color = AprsTheme.colors.callsign,
                                )
                                Text(
                                    StationFormat.age(other.ts),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = {
                openUrl("https://aprs.fi/info/a/$call?utm_source=aprsdroid&utm_medium=inapp&utm_campaign=aprsfi")
            }) { Text("Open on aprs.fi") }
        }
    }
}

/** The current weather report, biggest number first. */
@Composable
private fun WeatherCard(w: AprsWeather, reportTs: Long, metric: Boolean) {
    SectionTitle("Weather", "reported " + StationFormat.age(reportTs))
    Card {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                w.tempF?.let {
                    Text(
                        WeatherFormat.temperature(it, metric),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(16.dp))
                }
                WeatherFormat.wind(w, metric)?.let {
                    Text(
                        "Wind $it",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            val rows = ArrayList<Pair<String, String>>()
            w.gustMph?.takeIf { it > 0 }?.let { rows += "Gusts" to WeatherFormat.speed(it, metric) }
            w.humidityPct?.let { rows += "Humidity" to "$it %" }
            WeatherFormat.dewPointF(w)?.let { rows += "Dew point" to WeatherFormat.temperature(it, metric) }
            w.pressureTenthsHpa?.let { rows += "Pressure" to WeatherFormat.pressure(it, metric) }
            w.rainHour?.let { rows += "Rain, last hour" to WeatherFormat.rain(it, metric) }
            w.rain24h?.let { rows += "Rain, 24 hours" to WeatherFormat.rain(it, metric) }
            w.rainToday?.let { rows += "Rain today" to WeatherFormat.rain(it, metric) }
            w.luminosity?.let { rows += "Sunlight" to "$it W/m²" }
            if (rows.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                for (pair in rows.chunked(2)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
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
}

/** Who the licensee is, from the user's own QRZ.com account, or a plain link when that is not set up. */
@Composable
private fun QrzSection(viewModel: StationDetailViewModel, openUrl: (String) -> Unit) {
    val base = org.aprsdroid.app.QrzLookup.baseCall(viewModel.targetCall)
    val page = "https://www.qrz.com/db/$base"
    if (!viewModel.qrzConfigured) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { openUrl(page) }) { Text("Look up on QRZ.com") }
        }
        return
    }
    val reply by viewModel.qrz.collectAsState()
    val error by viewModel.qrzError.collectAsState()
    val record = reply?.record
    if (record == null && error == null && reply == null) return // objects, or still loading
    SectionTitle("Operator", "from QRZ.com")
    Card(Modifier.clickable { openUrl(page) }) {
        Column {
            when {
                record != null -> {
                    Text(record.name ?: record.call, style = MaterialTheme.typography.titleMedium)
                    val line = listOfNotNull(
                        record.place,
                        record.grid?.let { "grid $it" },
                        record.licenseClass?.let { "class $it" },
                    ).joinToString("  •  ")
                    if (line.isNotEmpty()) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    reply?.message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                error != null -> Text(
                    "QRZ.com could not be reached: $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    reply?.error ?: "QRZ.com has no record for $base.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- track

@Composable
private fun TrackTab(
    call: String,
    history: StationHistory,
    error: String?,
    metric: Boolean,
    openUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val track = history.track
    val stats = remember(track) { TrackStats.of(track) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
        if (track.size < 2 || stats.distanceM < 25) {
            Note(
                if (track.isEmpty()) "No positions from $call are stored yet."
                else "$call has stayed in one place: ${track.size} position reports, all from the same spot.",
            )
        } else {
            Card {
                TrackPlot(track, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(280.dp))
            }
            val tiles = ArrayList<Pair<String, String>>()
            tiles += "Position reports" to stats.points.toString()
            tiles += "Distance" to StationFormat.trackDistance(stats.distanceM, metric)
            stats.topSpeedMps?.let { tiles += "Top speed" to StationFormat.speedMps(it, metric) }
            if (stats.firstTs != null && stats.lastTs != null) {
                tiles += "Over" to StationFormat.duration(stats.lastTs - stats.firstTs)
            }
            Tiles(tiles, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Note("North is up. The ring marks the oldest position, the dot the newest.")
        }
        HistorySource(history, error)
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { MapModes.startMap(context, PrefsWrapper(context), call) }) { Text("Show on the map") }
            TextButton(onClick = {
                openUrl("https://aprs.fi/#!call=a%2F${Uri.encode(call)}&timerange=604800&tail=604800")
            }) { Text("Longer track on aprs.fi") }
        }
    }
}

// ---------------------------------------------------------------- weather

@Composable
private fun WeatherTab(
    call: String,
    history: StationHistory,
    error: String?,
    metric: Boolean,
    openUrl: (String) -> Unit,
) {
    val samples = history.weather
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
        if (samples.size < 2) {
            Note(
                if (samples.isEmpty()) "No weather reports from $call are stored. Trends appear once a few have arrived."
                else "Only one weather report from $call is stored so far. Trends appear once a few have arrived.",
            )
        } else {
            val span = StationFormat.duration(samples.last().ts - samples.first().ts)
            SectionTitle("Trends", "last $span, ${samples.size} reports")
            Card {
                Column {
                    fun series(pick: (AprsWeather) -> Float?): List<Pair<Long, Float>> =
                        samples.mapNotNull { s -> pick(s.weather)?.let { s.ts to it } }
                    fun range(pts: List<Pair<Long, Float>>, fmt: (Float) -> String): String =
                        "low ${fmt(pts.minOf { it.second })}, high ${fmt(pts.maxOf { it.second })}"

                    val last = samples.last().weather
                    val temp = series { it.tempF?.toFloat() }
                    if (temp.size > 1) TrendRow(
                        "Temperature", last.tempF?.let { WeatherFormat.temperature(it, metric) } ?: "",
                        range(temp) { WeatherFormat.temperature(it.toInt(), metric) }, Color(0xFFE0A030), temp,
                    )
                    val hum = series { it.humidityPct?.toFloat() }
                    if (hum.size > 1) TrendRow(
                        "Humidity", last.humidityPct?.let { "$it %" } ?: "",
                        range(hum) { "${it.toInt()} %" }, Color(0xFF4A9FE0), hum,
                    )
                    val pres = series { it.pressureTenthsHpa?.toFloat() }
                    if (pres.size > 1) TrendRow(
                        "Pressure", last.pressureTenthsHpa?.let { WeatherFormat.pressure(it, metric) } ?: "",
                        range(pres) { WeatherFormat.pressure(it.toInt(), metric) }, Color(0xFF3F9E6E), pres,
                    )
                    val wind = series { it.windMph?.toFloat() }
                    if (wind.size > 1) TrendRow(
                        "Wind", last.windMph?.let { WeatherFormat.speed(it, metric) } ?: "",
                        range(wind) { WeatherFormat.speed(it.toInt(), metric) }, Color(0xFF9A6BE0), wind,
                    )
                    val rain = series { it.rainHour?.toFloat() }
                    if (rain.size > 1) TrendRow(
                        "Rain, 1 h", last.rainHour?.let { WeatherFormat.rain(it, metric) } ?: "",
                        range(rain) { WeatherFormat.rain(it.toInt(), metric) }, Color(0xFF3FB6C8), rain,
                    )
                }
            }
        }
        HistorySource(history, error)
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { openUrl("https://aprs.fi/weather/a/${Uri.encode(call)}") }) {
                Text("Weather history graphs on aprs.fi")
            }
        }
    }
}

// ---------------------------------------------------------------- raw

@Composable
private fun RawTab(posts: List<PostEntity>) {
    if (posts.isEmpty()) {
        Note("No packets from this station are in the log.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(posts, key = { it.id }) { post ->
            PostItem(post = post, onLongClick = {})
            HorizontalDivider(color = AprsTheme.colors.line.copy(alpha = 0.45f))
        }
    }
}
