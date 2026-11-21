package org.aprsdroid.app

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The kinds of station the map can filter by. Every station is exactly one
 * kind, decided from its symbol (and speed), so the counts add up to the total.
 */
enum class StationKind(val label: String) {
    MOBILE("Mobile"),
    FIXED("Fixed"),
    WEATHER("Weather"),
    DIGIPEATER("Digipeaters"),
    IGATE("IGates"),
    BALLOON("Balloons"),
    BOAT("Boats"),
    AIR("Aircraft"),
}

/** Station counts for the filter chips. */
data class StationCounts(
    val all: Int,
    val byKind: Map<StationKind, Int>,
    /** Objects known, whether or not they are being shown. */
    val objects: Int = 0,
) {
    operator fun get(kind: StationKind): Int = byKind[kind] ?: 0

    companion object {
        val NONE = StationCounts(0, emptyMap())
    }
}

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

    /** What kind of station this is. Infrastructure and weather win over "fixed". */
    @JvmStatic
    fun kindOf(symbol: String, speed: Int?): StationKind {
        if (symbol.length >= 2) {
            val primary = symbol[0] == '/'
            val c = symbol[1]
            when {
                c == '_' || (!primary && c == 'W') -> return StationKind.WEATHER
                c == '#' -> return StationKind.DIGIPEATER
                c == '&' -> return StationKind.IGATE
                primary && c == 'O' -> return StationKind.BALLOON
                (primary && c in "sYC") || (!primary && c == 's') -> return StationKind.BOAT
                (primary && c in "'^Xg") || (!primary && c == '^') -> return StationKind.AIR
            }
        }
        return if (isMobile(symbol, speed)) StationKind.MOBILE else StationKind.FIXED
    }

    @JvmStatic
    fun counts(stations: List<MapStation>): StationCounts =
        StationCounts(stations.size, stations.groupingBy { kindOf(it.symbol, it.speed) }.eachCount())

    /**
     * Split a full load (objects included) into what the map should show and
     * the counts for the filter controls. The object count is always the
     * number known, so the Objects switch can say how many it hides.
     */
    @JvmStatic
    fun withObjects(everything: List<MapStation>, showObjects: Boolean): Pair<List<MapStation>, StationCounts> {
        val objects = everything.count { it.origin != null }
        val shown = if (showObjects) everything else everything.filter { it.origin == null }
        return shown to counts(shown).copy(objects = objects)
    }

    /**
     * Keep the stations whose kind is in [shown]; an empty set shows everything.
     * Stations named in [keep] (yourself, the followed station) always stay.
     */
    @JvmStatic
    fun apply(stations: List<MapStation>, shown: Set<StationKind>, keep: Set<String>): List<MapStation> =
        if (shown.isEmpty()) stations
        else stations.filter { it.call in keep || kindOf(it.symbol, it.speed) in shown }

    /**
     * What tapping a kind's chip does to the selection.
     *
     * With everything shown, tapping a kind shows only that kind (a filter).
     * After that each chip is an on/off switch: tap another kind to add it,
     * tap a selected one to remove it. Switching the last one off, or every
     * one on, is the same as "All".
     */
    @JvmStatic
    fun toggle(shown: Set<StationKind>, kind: StationKind): Set<StationKind> {
        val next = if (kind in shown) shown - kind else shown + kind
        return if (next.size == StationKind.values().size) emptySet() else next
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
