package org.aprsdroid.app

/** The base maps the MapLibre screen can show. */
enum class MapLibreStyle(
    val tag: String,
    /** A style URL, or null when [json] is used. */
    val url: String?,
    /** Areas of this map may be saved for offline use. */
    val canSave: Boolean,
) {
    /** The classic OpenStreetMap picture tiles. Their usage policy forbids saving areas in bulk. */
    OSM("osm", null, false),

    /** Vector street map from OpenFreeMap: sharp at any zoom, rotates, can be saved. */
    STREETS("ml_streets", "https://tiles.openfreemap.org/styles/liberty", true),

    /** Pale vector map with little detail, so station symbols stand out. */
    QUIET("ml_quiet", "https://tiles.openfreemap.org/styles/positron", true);

    val json: String?
        get() = if (this == OSM) OSM_JSON else null

    companion object {
        const val OSM_JSON = """{"version":8,"sources":{"osm":{"type":"raster",
"tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,
"attribution":"© OpenStreetMap contributors"}},
"layers":[{"id":"background","type":"background","paint":{"background-color":"#e8eaed"}},
{"id":"osm","type":"raster","source":"osm"}]}"""

        fun byTag(tag: String): MapLibreStyle = values().firstOrNull { it.tag == tag } ?: OSM
    }
}

/** Sizing rules for saving an area, kept pure for tests. */
object OfflineArea {
    /** Vector tiles stop at zoom 14; the map draws closer zooms from those. */
    const val MAX_ZOOM = 14.0
    /** The most tiles one saved area may need. */
    const val TILE_LIMIT = 6000L

    private fun tileX(lon: Double, z: Int): Long =
        Math.floor((lon + 180.0) / 360.0 * (1L shl z)).toLong().coerceIn(0, (1L shl z) - 1)

    private fun tileY(lat: Double, z: Int): Long {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1L shl z)
        return Math.floor(y).toLong().coerceIn(0, (1L shl z) - 1)
    }

    /** How many tiles cover the box at every zoom from [minZoom] to [maxZoom]. */
    @JvmStatic
    fun tileCount(south: Double, west: Double, north: Double, east: Double, minZoom: Int, maxZoom: Int): Long {
        var n = 0L
        for (z in minZoom..maxZoom) {
            val w = tileX(east, z) - tileX(west, z) + 1
            val h = tileY(south, z) - tileY(north, z) + 1
            n += maxOf(w, 0) * maxOf(h, 0)
        }
        return n
    }

    /** The zoom to start saving from: the one on screen, rounded down, at most [MAX_ZOOM]. */
    @JvmStatic
    fun minZoom(currentZoom: Double): Int = Math.floor(currentZoom).toInt().coerceIn(0, MAX_ZOOM.toInt())
}
