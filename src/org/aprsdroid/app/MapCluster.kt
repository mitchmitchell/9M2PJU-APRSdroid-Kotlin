package org.aprsdroid.app

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** Which stations the map shows. */
enum class StationFilter { ALL, MOBILE, FIXED }

/** Station counts for the filter chips. */
data class StationCounts(val all: Int, val mobile: Int, val fixed: Int)

/** Renderer-neutral filtering, shared by every map screen. */
object MapFilters {

    // Primary-table symbols that are vehicles, vessels, aircraft or people on the move.
    private const val MOBILE_PRIMARY = "!'<=>()*COPRSUXY[^abefgjksuv"
    // Alternate-table symbols of the same kind (overlay character ignored).
    private const val MOBILE_ALTERNATE = ">^Ssukv"

    /** A station counts as mobile if it reports a speed or uses a vehicle-type symbol. */
    @JvmStatic
    fun isMobile(symbol: String, speed: Int?): Boolean {
        if (speed != null && speed > 0) return true
        if (symbol.length < 2) return false
        return if (symbol[0] == '/') symbol[1] in MOBILE_PRIMARY else symbol[1] in MOBILE_ALTERNATE
    }

    @JvmStatic
    fun counts(stations: List<MapStation>): StationCounts {
        val mobile = stations.count { isMobile(it.symbol, it.speed) }
        return StationCounts(stations.size, mobile, stations.size - mobile)
    }

    /** Apply [filter]; stations named in [keep] (yourself, the followed station) always stay. */
    @JvmStatic
    fun apply(stations: List<MapStation>, filter: StationFilter, keep: Set<String>): List<MapStation> =
        when (filter) {
            StationFilter.ALL -> stations
            StationFilter.MOBILE -> stations.filter { it.call in keep || isMobile(it.symbol, it.speed) }
            StationFilter.FIXED -> stations.filter { it.call in keep || !isMobile(it.symbol, it.speed) }
        }
}

/**
 * Groups stations that would overlap at the current zoom into clusters.
 *
 * Stations are binned on a grid fixed to the map (Web Mercator pixels), not to
 * the screen, so clusters stay put while the map is dragged. A cell holding
 * one station yields that station at its true position; a cell holding more
 * yields one cluster at the cell centre, so cluster circles never overlap.
 */
object MapCluster {

    private const val TILE = 256.0

    /** One thing to draw: a single station ([items] has one entry) or a cluster. */
    class Group<T>(val lat: Double, val lon: Double, val items: List<T>) {
        val isCluster: Boolean get() = items.size > 1
    }

    @JvmStatic
    fun worldX(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * TILE * 2.0.pow(zoom)

    @JvmStatic
    fun worldY(lat: Double, zoom: Double): Double {
        val s = sin(lat.coerceIn(-85.0511, 85.0511) * PI / 180.0)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * TILE * 2.0.pow(zoom)
    }

    @JvmStatic
    fun lonOf(worldX: Double, zoom: Double): Double = worldX / (TILE * 2.0.pow(zoom)) * 360.0 - 180.0

    @JvmStatic
    fun latOf(worldY: Double, zoom: Double): Double {
        val n = PI - 2.0 * PI * worldY / (TILE * 2.0.pow(zoom))
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }

    /**
     * @param cell grid cell size in map pixels at [zoom] (a 256-pixel-tile world)
     * @param pinned stations that are never absorbed into a cluster
     * @param minClusterSize smallest number of stations in a cell that forms a cluster
     */
    @JvmStatic
    fun <T> cluster(
        items: List<T>,
        zoom: Double,
        cell: Double,
        lat: (T) -> Double,
        lon: (T) -> Double,
        pinned: (T) -> Boolean = { false },
        minClusterSize: Int = 2,
    ): List<Group<T>> {
        val out = ArrayList<Group<T>>()
        val cells = LinkedHashMap<Long, ArrayList<T>>()
        for (item in items) {
            if (pinned(item)) {
                out.add(Group(lat(item), lon(item), listOf(item)))
                continue
            }
            val cx = floor(worldX(lon(item), zoom) / cell).toLong()
            val cy = floor(worldY(lat(item), zoom) / cell).toLong()
            cells.getOrPut(cx shl 32 xor (cy and 0xffffffffL)) { ArrayList() }.add(item)
        }
        for ((key, members) in cells) {
            if (members.size < minClusterSize) {
                for (m in members) out.add(Group(lat(m), lon(m), listOf(m)))
            } else {
                val cx = key shr 32
                val cy = (key and 0xffffffffL).toInt().toLong()
                out.add(Group(latOf((cy + 0.5) * cell, zoom), lonOf((cx + 0.5) * cell, zoom), members))
            }
        }
        return out
    }
}
