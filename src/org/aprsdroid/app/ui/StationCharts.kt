package org.aprsdroid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.aprsdroid.app.TrackPoint
import kotlin.math.cos

/**
 * One reading over time: name on the left, a small trend line, the latest
 * value on the right. [points] are (time, value), oldest first.
 */
@Composable
fun TrendRow(label: String, latest: String, range: String, color: Color, points: List<Pair<Long, Float>>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label now $latest, $range" },
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(104.dp))
        Canvas(Modifier.weight(1f).height(52.dp)) {
            if (points.size < 2) return@Canvas
            val t0 = points.first().first
            val span = (points.last().first - t0).coerceAtLeast(1L).toFloat()
            var lo = points.minOf { it.second }
            var hi = points.maxOf { it.second }
            if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f } // a flat line sits mid-height
            val pad = 4.dp.toPx()
            fun x(t: Long) = (t - t0) / span * size.width
            fun y(v: Float) = pad + (1f - (v - lo) / (hi - lo)) * (size.height - 2 * pad)
            val line = Path()
            points.forEachIndexed { i, (t, v) -> if (i == 0) line.moveTo(x(t), y(v)) else line.lineTo(x(t), y(v)) }
            val fill = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, 3.5.dp.toPx(), Offset(x(points.last().first), y(points.last().second)))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            latest,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(96.dp),
        )
    }
}

/**
 * The station's trail drawn to scale, north up, without a base map: a quick
 * read of where it went. Oldest point hollow, newest filled.
 */
@Composable
fun TrackPlot(track: List<TrackPoint>, lineColor: Color, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        if (track.isEmpty()) return@Canvas
        val latMin = track.minOf { it.lat }
        val latMax = track.maxOf { it.lat }
        val lonMin = track.minOf { it.lon }
        val lonMax = track.maxOf { it.lon }
        // Degrees of longitude shrink with latitude: keep the shape true.
        val kx = cos(Math.toRadians((latMin + latMax) / 2)).coerceAtLeast(0.01)
        val w = ((lonMax - lonMin) * kx).coerceAtLeast(1e-5)
        val h = (latMax - latMin).coerceAtLeast(1e-5)
        val pad = 20.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h).toFloat()
        val ox = (size.width - (w * scale).toFloat()) / 2
        val oy = (size.height - (h * scale).toFloat()) / 2
        fun pt(p: TrackPoint) = Offset(
            ox + ((p.lon - lonMin) * kx * scale).toFloat(),
            oy + ((latMax - p.lat) * scale).toFloat(),
        )
        // faint cross-hairs through the middle, for a sense of the plot area
        drawLine(grid, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1f)
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)

        val path = Path()
        track.forEachIndexed { i, p -> val o = pt(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, lineColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lineColor, 5.dp.toPx(), pt(track.first()), style = Stroke(2.dp.toPx()))
        drawCircle(lineColor, 6.dp.toPx(), pt(track.last()))
    }
}
